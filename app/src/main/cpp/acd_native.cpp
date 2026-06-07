// JNI bridge to PAL ACD (DSP-side voice activity detection).
//
// Mirrors AI/clients/glasses/sthal/acd-probe/main.cpp but exposes start/stop
// to a Kotlin caller. libpalclient.so is dlopen'd at runtime so a missing
// /vendor/lib64/libpalclient.so does not abort the listener.
//
// Threading model:
//   - nativeStart() spawns a worker thread that does pal_init / pal_stream_open /
//     LOAD_SOUND_MODEL / RECOGNITION_CONFIG / pal_stream_start. The worker then
//     parks on a condition variable until nativeStop() flips the run flag.
//   - PAL invokes our C callback (stream_cb) on its OWN thread. We attach to
//     the JVM, invoke the Kotlin callback's onSpeech() method, then detach.
//   - nativeStop() signals the worker, which calls pal_stream_stop / close /
//     deinit and returns; the JVM thread joins it.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <mutex>
#include <pthread.h>
#include <string>
#include <sys/stat.h>
#include <thread>
#include <unistd.h>
#include <vector>
#include <dlfcn.h>

#include "pal/PalDefs.h"
#include "pal/PalApi.h"

#define LOG_TAG "AcdNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// -----------------------------------------------------------------------------
// libpalclient symbols
// -----------------------------------------------------------------------------
using fn_init_t      = int32_t (*)(void);
using fn_deinit_t    = void    (*)(void);
using fn_open_t      = int32_t (*)(struct pal_stream_attributes *, uint32_t,
                                   struct pal_device *, uint32_t,
                                   struct modifier_kv *, pal_stream_callback,
                                   uint64_t, pal_stream_handle_t **);
using fn_close_t     = int32_t (*)(pal_stream_handle_t *);
using fn_start_t     = int32_t (*)(pal_stream_handle_t *);
using fn_stop_t      = int32_t (*)(pal_stream_handle_t *);
using fn_set_param_t = int32_t (*)(pal_stream_handle_t *, uint32_t,
                                   pal_param_payload *);

struct PalSyms {
    void *handle = nullptr;
    fn_init_t      init      = nullptr;
    fn_deinit_t    deinit    = nullptr;
    fn_open_t      open      = nullptr;
    fn_close_t     close     = nullptr;
    fn_start_t     start     = nullptr;
    fn_stop_t      stop      = nullptr;
    fn_set_param_t set_param = nullptr;
};

bool resolvePal(PalSyms &s) {
    if (s.handle) return true;
    s.handle = dlopen("libpalclient.so", RTLD_NOW);
    if (!s.handle) {
        s.handle = dlopen("/vendor/lib64/libpalclient.so", RTLD_NOW);
    }
    if (!s.handle) {
        LOGW("dlopen libpalclient.so failed: %s", dlerror());
        return false;
    }
#define LOAD(field, sym) \
    s.field = reinterpret_cast<decltype(s.field)>(dlsym(s.handle, sym)); \
    if (!s.field) { LOGW("dlsym %s failed: %s", sym, dlerror()); return false; }
    LOAD(init,      "pal_init");
    LOAD(deinit,    "pal_deinit");
    LOAD(open,      "pal_stream_open");
    LOAD(close,     "pal_stream_close");
    LOAD(start,     "pal_stream_start");
    LOAD(stop,      "pal_stream_stop");
    LOAD(set_param, "pal_stream_set_param");
#undef LOAD
    return true;
}

// -----------------------------------------------------------------------------
// QC ACD path — proper Acoustic Context Detection via PAL_STREAM_ACD.
// vendor_uuid 4e93281b-296e-4d73-9833-2710c3c7c1db (QC_ACD) per
// /vendor/etc/resourcemanager_neo_idp.xml stream_config name="QC_ACD".
// speech.eai is the pre-shipped LPAI Hexagon model that detects ambient speech;
// context 0x08001335 (AMBIENCE_SPEECH) is the only context this model supports.
// -----------------------------------------------------------------------------
const struct st_uuid kAcdVendorUuid = {
    0x4e93281b, 0x296e, 0x4d73, 0x9833,
    { 0x27, 0x10, 0xc3, 0xc7, 0xc1, 0xdb }
};
constexpr uint32_t kAcdContextSpeech = 0x08001335; // AMBIENCE_SPEECH
constexpr const char *kSpeechEaiPath = "/vendor/etc/models/acd/speech.eai";

// -----------------------------------------------------------------------------
// Singleton state. Only one ACD session per process.
// -----------------------------------------------------------------------------
struct AcdState {
    std::mutex                       mu;
    std::atomic<bool>                running{false};
    std::thread                      worker;
    PalSyms                          pal;
    pal_stream_handle_t             *streamHandle = nullptr;

    // JVM callback. Global ref to a Kotlin object implementing
    // AcdNativeDetector.SpeechCallback.onSpeech(long epochNanos).
    JavaVM                          *jvm        = nullptr;
    jobject                          callbackGlobal = nullptr;
    jmethodID                        onSpeechMid   = nullptr;

    // Reentrancy counter for streamCb. nativeStop must wait for in-flight
    // callbacks to drain before deleting the JNI global ref, otherwise the
    // PAL callback thread can dereference a freed jobject.
    std::atomic<int>                 inCallback{0};
};

AcdState g_state;

bool readAll(const char *path, std::vector<uint8_t> &out) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) { LOGW("open(%s) failed: %s", path, strerror(errno)); return false; }
    struct stat st{};
    if (fstat(fd, &st) < 0) { close(fd); return false; }
    out.resize((size_t)st.st_size);
    ssize_t n = read(fd, out.data(), out.size());
    close(fd);
    return n == (ssize_t)out.size();
}

// RAII guard for AcdState::inCallback so nativeStop can spin-wait until any
// in-flight PAL callback has returned before tearing down the JNI globals.
struct CbGuard {
    CbGuard()  { g_state.inCallback.fetch_add(1, std::memory_order_acq_rel); }
    ~CbGuard() { g_state.inCallback.fetch_sub(1, std::memory_order_acq_rel); }
};

// PAL invokes this on its own thread.
int32_t streamCb(pal_stream_handle_t * /*handle*/, uint32_t event_id,
                 uint32_t *event_data, uint32_t event_data_size,
                 uint64_t /*cookie*/) {
    CbGuard guard;
    LOGI("acd cb event=0x%08x size=%u", event_id, event_data_size);
    if (event_data && event_data_size >= sizeof(struct pal_st_recognition_event)) {
        auto *ev = reinterpret_cast<struct pal_st_recognition_event *>(event_data);
        if (ev->status != 0) {
            LOGI("acd cb status=%d (non-success), ignoring", ev->status);
            return 0;
        }
    }

    JavaVM *jvm = g_state.jvm;
    jobject cb  = g_state.callbackGlobal;
    jmethodID mid = g_state.onSpeechMid;
    if (!jvm || !cb || !mid) return 0;

    JNIEnv *env = nullptr;
    bool attached = false;
    int getRc = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (getRc == JNI_EDETACHED) {
        if (jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            LOGW("AttachCurrentThread failed in stream cb");
            return 0;
        }
        attached = true;
    } else if (getRc != JNI_OK || env == nullptr) {
        LOGW("GetEnv failed rc=%d", getRc);
        return 0;
    }

    jlong epoch = (jlong)std::chrono::duration_cast<std::chrono::nanoseconds>(
                      std::chrono::steady_clock::now().time_since_epoch())
                      .count();
    env->CallVoidMethod(cb, mid, epoch);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }

    if (attached) jvm->DetachCurrentThread();
    return 0;
}

// Worker thread: open stream, load model, set rec config, start. Park until
// running flips false, then stop/close/deinit.
void runWorker() {
    pthread_setname_np(pthread_self(), "AcdWorker");

    PalSyms &P = g_state.pal;
    int32_t rc = P.init();
    LOGI("pal_init -> %d", rc);
    if (rc != 0) { g_state.running.store(false); return; }

    // PAL_STREAM_ACD = 23. ACD low_power handset capture profile is
    // SINGLE_MIC_16KHZ_16BIT_LPI (1ch, va-mic-mono-lpi).
    struct pal_stream_attributes attr{};
    attr.type      = PAL_STREAM_ACD;
    attr.direction = PAL_AUDIO_INPUT;
    attr.in_media_config.sample_rate     = 16000;
    attr.in_media_config.bit_width       = 16;
    attr.in_media_config.aud_fmt_id      = PAL_AUDIO_FMT_PCM_S16_LE;
    attr.in_media_config.ch_info.channels = 1;
    attr.in_media_config.ch_info.ch_map[0] = PAL_CHMAP_CHANNEL_FL;
    attr.out_media_config = attr.in_media_config;

    struct pal_device dev{};
    dev.id = PAL_DEVICE_IN_HANDSET_VA_MIC;
    dev.config.sample_rate = 16000;
    dev.config.bit_width   = 16;
    dev.config.aud_fmt_id  = PAL_AUDIO_FMT_PCM_S16_LE;
    dev.config.ch_info.channels = 1;
    dev.config.ch_info.ch_map[0] = PAL_CHMAP_CHANNEL_FL;

    pal_stream_handle_t *handle = nullptr;
    rc = P.open(&attr, 1, &dev, 0, nullptr, streamCb, 0, &handle);
    LOGI("pal_stream_open -> %d handle=%p", rc, handle);
    if (rc != 0 || !handle) {
        P.deinit();
        g_state.running.store(false);
        return;
    }
    g_state.streamHandle = handle;

    // Load sound model
    std::vector<uint8_t> eai;
    if (!readAll(kSpeechEaiPath, eai)) {
        LOGW("read %s failed", kSpeechEaiPath);
        P.close(handle);
        g_state.streamHandle = nullptr;
        P.deinit();
        g_state.running.store(false);
        return;
    }

    // ACD generic sound model: header = pal_st_sound_model with type=GENERIC,
    // vendor_uuid=ACD QC UUID, data_size=eai size, data_offset=sizeof(header).
    size_t smHdrSz = sizeof(struct pal_st_sound_model);
    size_t totalSz = smHdrSz + eai.size();
    std::vector<uint8_t> smBuf(totalSz, 0);
    auto *sm = reinterpret_cast<struct pal_st_sound_model *>(smBuf.data());
    sm->type        = PAL_SOUND_MODEL_TYPE_GENERIC;
    sm->vendor_uuid = kAcdVendorUuid;
    sm->data_size   = (uint32_t)eai.size();
    sm->data_offset = (uint32_t)smHdrSz;
    memcpy(smBuf.data() + smHdrSz, eai.data(), eai.size());

    std::vector<uint8_t> ppBuf(sizeof(pal_param_payload) + totalSz, 0);
    auto *pp = reinterpret_cast<pal_param_payload *>(ppBuf.data());
    pp->payload_size = (uint32_t)totalSz;
    memcpy(pp->payload, smBuf.data(), totalSz);

    rc = P.set_param(handle, PAL_PARAM_ID_LOAD_SOUND_MODEL, pp);
    LOGI("LOAD_SOUND_MODEL -> %d", rc);
    if (rc != 0) {
        P.close(handle); g_state.streamHandle = nullptr; P.deinit();
        g_state.running.store(false); return;
    }

    // PAL ACD setParameters case 1 (Frida-confirmed runtime behaviour on
    // this build of libpalclient.so):
    //
    //   uVar1       = *(uint32_t*)(payload + kSentinelOff);   // = 996
    //   acd_cfg_ptr = payload + uVar1 + 12;
    //   StreamACD::UpdateRecognitionConfig reads param_1 as
    //     { uint32_t reserved_0; uint32_t num_contexts;
    //       struct {id, threshold, step}[N]; }
    //
    // We size pal_st_recognition_config (cfgHdrSz = 1000) and append our
    // acd_recognition_cfg at offset cfgHdrSz of the buffer. Sentinel uVar1
    // is written at offset cfgHdrSz - 4 (= 996) and equals cfgHdrSz - 8
    // (= 992). PAL derefs payload + 992 + 12 = payload + cfgHdrSz + 4, which
    // lands 4 bytes into our acd_recognition_cfg -- past reserved_0 onto
    // num_contexts, so PAL's first read (param_1[0]) sees num_contexts.
    //
    // Buffer layout (rcBuf, total = cfgHdrSz + sizeof(acd_recognition_cfg)):
    //
    //   offset 0                              996       1000        1004
    //   |                                       |         |           |
    //   +-------- pal_st_recognition_config ----+---------+-----------+-----+
    //   | capture_handle / device / num_phrases | uVar1   | reserved_0      |
    //   | callback / cookie / data_offset / ... | =992    | (PAL skips this)|
    //   |                          (data_size:) | (over-  +-----------+-----+
    //   |                                       |  writes |   PAL ptr lands |
    //   |                                       | data_sz)|   here, reads:  |
    //   +---------------------------------------+---------+ num_contexts(1) |
    //                                                     | ctx0.{id,thr,st}|
    //                                                     +-----------------+
    //
    // Confirmed empirically: the listener log prints "Num Contexts = 1" and
    // pal_stream_start returns 0. data_size at cfgHdrSz-4 is overwritten by
    // the sentinel; the ACD dispatch path does not read it.
    struct acd_ctx { uint32_t id; uint32_t threshold; uint32_t step; };
    struct acd_recognition_cfg {
        uint32_t reserved_0;     // param_1[0] -- read by PAL, ignored downstream
        uint32_t num_contexts;   // param_1[1] -- "Num Contexts = %d"
        struct acd_ctx ctx0;     // param_1[2..] -- per-context tuples
    };

    // Fail fast if the PAL header layout shifts under us. The sentinel math
    // assumes cfgHdrSz == 1000 (PAL hardcodes the offset 1000 read).
    static_assert(sizeof(struct pal_st_recognition_config) == 1000,
                  "pal_st_recognition_config size changed -- PAL ACD sentinel "
                  "math (uVar1 at +996, struct at +1000) needs re-verification "
                  "against libpalclient.so on this device.");

    constexpr size_t cfgHdrSz   = sizeof(struct pal_st_recognition_config); // 1000
    constexpr size_t kSentinelOff   = cfgHdrSz - 4;             // 996: where PAL reads uVar1
    constexpr size_t kAcdCfgOff     = cfgHdrSz;                  // 1000: our acd_recognition_cfg
    constexpr uint32_t kSentinelVal = (uint32_t)(cfgHdrSz - 8); // 992: PAL derefs payload+992+12
    // PAL ptr = payload + kSentinelVal + 12 = payload + (cfgHdrSz + 4),
    // i.e. 4 bytes into our acd_recognition_cfg. Combined with the
    // reserved_0 leading field, param_1[0] sees num_contexts at runtime.
    // (Empirically verified by "Num Contexts = 1" in the listener log.)
    static_assert(kSentinelVal + 12 == kAcdCfgOff + 4,
                  "PAL ACD sentinel deref must land 4 bytes into acd_recognition_cfg");

    size_t acdSz     = sizeof(struct acd_recognition_cfg);
    size_t rcTotal   = cfgHdrSz + acdSz;
    std::vector<uint8_t> rcBuf(rcTotal, 0);
    auto *rcfg = reinterpret_cast<struct pal_st_recognition_config *>(rcBuf.data());
    rcfg->capture_handle    = -1;
    rcfg->capture_device    = PAL_DEVICE_IN_HANDSET_VA_MIC;
    rcfg->capture_requested = false;
    rcfg->num_phrases       = 0;
    rcfg->callback          = nullptr;
    rcfg->cookie            = nullptr;
    rcfg->data_size         = (uint32_t)acdSz;     // overwritten by sentinel below
    rcfg->data_offset       = (uint32_t)cfgHdrSz;
    auto *acdCfg = reinterpret_cast<struct acd_recognition_cfg*>(rcBuf.data() + kAcdCfgOff);
    acdCfg->reserved_0   = 0;
    acdCfg->num_contexts = 1;
    acdCfg->ctx0         = { kAcdContextSpeech, 0, 0 };

    // Plant the sentinel last so it overwrites whatever rcfg field shares
    // these 4 bytes (currently data_size).
    *reinterpret_cast<uint32_t*>(rcBuf.data() + kSentinelOff) = kSentinelVal;

    std::vector<uint8_t> pp2Buf(sizeof(pal_param_payload) + rcTotal, 0);
    auto *pp2 = reinterpret_cast<pal_param_payload *>(pp2Buf.data());
    pp2->payload_size = (uint32_t)rcTotal;
    memcpy(pp2->payload, rcBuf.data(), rcTotal);

    rc = P.set_param(handle, PAL_PARAM_ID_RECOGNITION_CONFIG, pp2);
    LOGI("RECOGNITION_CONFIG -> %d (cfgHdrSz=%zu acdSz=%zu uVar1@rcBuf[%zu]=%u num=1 ctx=0x%08x)",
         rc, cfgHdrSz, acdSz, kSentinelOff, kSentinelVal, kAcdContextSpeech);
    if (rc != 0) {
        P.close(handle); g_state.streamHandle = nullptr; P.deinit();
        g_state.running.store(false); return;
    }

    rc = P.start(handle);
    LOGI("pal_stream_start -> %d", rc);
    if (rc != 0) {
        P.close(handle); g_state.streamHandle = nullptr; P.deinit();
        g_state.running.store(false); return;
    }

    LOGI("ACD running");

    // Park until stop requested
    while (g_state.running.load()) {
        sleep(1);
    }

    LOGI("ACD worker shutting down");
    rc = P.stop(handle);
    LOGI("pal_stream_stop -> %d", rc);
    rc = P.close(handle);
    LOGI("pal_stream_close -> %d", rc);
    g_state.streamHandle = nullptr;
    P.deinit();
    LOGI("pal_deinit done");
}

} // namespace

// -----------------------------------------------------------------------------
// JNI exports
// -----------------------------------------------------------------------------
extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_repository_glasses_listener_wakeword_AcdNativeDetector_nativeIsAvailable(
        JNIEnv * /*env*/, jclass /*cls*/) {
    PalSyms tmp{};
    bool ok = resolvePal(tmp);
    if (tmp.handle) {
        // Keep dlopen'd lib loaded -- cheaper to reuse on actual start.
        // Do NOT dlclose here.
    }
    // Also confirm speech.eai exists.
    if (ok) {
        struct stat st{};
        if (stat(kSpeechEaiPath, &st) != 0 || st.st_size <= 0) {
            LOGW("speech.eai missing at %s", kSpeechEaiPath);
            ok = false;
        }
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_repository_glasses_listener_wakeword_AcdNativeDetector_nativeStart(
        JNIEnv *env, jclass /*cls*/, jobject callback) {
    std::lock_guard<std::mutex> lk(g_state.mu);
    if (g_state.running.load()) {
        LOGW("nativeStart: already running");
        return -1;
    }
    if (!resolvePal(g_state.pal)) {
        return -2;
    }

    // Stash JVM + global ref to callback + method id
    if (env->GetJavaVM(&g_state.jvm) != JNI_OK) {
        LOGE("GetJavaVM failed");
        return -3;
    }
    if (g_state.callbackGlobal) {
        env->DeleteGlobalRef(g_state.callbackGlobal);
        g_state.callbackGlobal = nullptr;
    }
    g_state.callbackGlobal = env->NewGlobalRef(callback);
    if (!g_state.callbackGlobal) {
        LOGE("NewGlobalRef failed");
        return -4;
    }
    jclass cbCls = env->GetObjectClass(g_state.callbackGlobal);
    g_state.onSpeechMid = env->GetMethodID(cbCls, "onSpeech", "(J)V");
    env->DeleteLocalRef(cbCls);
    if (!g_state.onSpeechMid) {
        LOGE("GetMethodID(onSpeech) failed");
        env->DeleteGlobalRef(g_state.callbackGlobal);
        g_state.callbackGlobal = nullptr;
        return -5;
    }

    g_state.running.store(true);
    g_state.worker = std::thread(runWorker);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_repository_glasses_listener_wakeword_AcdNativeDetector_nativeStop(
        JNIEnv *env, jclass /*cls*/) {
    std::thread joiner;
    {
        std::lock_guard<std::mutex> lk(g_state.mu);
        if (!g_state.running.load() && !g_state.worker.joinable()) return;
        g_state.running.store(false);
        if (g_state.worker.joinable()) joiner = std::move(g_state.worker);
    }
    if (joiner.joinable()) joiner.join();

    // PAL may still be inside streamCb on its own thread even after
    // pal_stream_stop / close in the worker. Spin-wait (200 ms cap) until any
    // in-flight callback returns so DeleteGlobalRef below is safe.
    {
        using clk = std::chrono::steady_clock;
        auto deadline = clk::now() + std::chrono::milliseconds(200);
        while (g_state.inCallback.load(std::memory_order_acquire) > 0 &&
               clk::now() < deadline) {
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }
        int leaked = g_state.inCallback.load(std::memory_order_acquire);
        if (leaked > 0) {
            LOGW("nativeStop: %d streamCb still in flight after 200ms drain; "
                 "leaking callback global ref to avoid use-after-free", leaked);
            return;
        }
    }

    std::lock_guard<std::mutex> lk(g_state.mu);
    if (g_state.callbackGlobal) {
        env->DeleteGlobalRef(g_state.callbackGlobal);
        g_state.callbackGlobal = nullptr;
    }
    g_state.onSpeechMid = nullptr;
}

} // extern "C"
