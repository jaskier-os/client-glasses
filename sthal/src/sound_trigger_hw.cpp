// sthal/src/sound_trigger_hw.cpp
//
// Custom sound_trigger.primary.neo.so — Rokid Neo glasses, API 32.
// Replaces /vendor/lib64/hw/sound_trigger.primary.neo.so (via DIY overlay).
//
// Implements the AOSP legacy SoundTrigger HAL interface (v1.2) using QNN on
// Hexagon HTP for wake-word inference and a HAL-owned LAB (look-ahead buffer)
// so the app gets the utterance that triggered detection.
//
// Build: NDK arm64-v8a (see CMakeLists.txt). No QNN headers required —
// QnnRuntime dlopen's QNN at start_recognition time.

#include "../include/sound_trigger_hw.h"
#include "qnn_runtime.h"
#include "mic_reader.h"

#include <atomic>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <sys/stat.h>
#include <vector>

#include <android/log.h>

#include "st_trace.h"

#define LOG_TAG "sthal"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// -----------------------------------------------------------------------------
// Implementation identity — appears in `dumpsys media.sound_trigger_hw`
// -----------------------------------------------------------------------------
static constexpr const char* kImplementor   = "Repository";
static constexpr const char* kDescription   = "Repository custom wake-word HAL (QNN/HTP)";
static constexpr unsigned int kImplVersion  = 1;

// -----------------------------------------------------------------------------
// Identity UUIDs
//
// kHalUuid identifies this HAL implementation (returned by get_properties).
// kKeyphraseUuid identifies the single sound model this HAL recognises; the
// Kotlin side loads a sound_trigger_generic_sound_model whose `uuid` field
// matches kKeyphraseUuid so the framework routes the load/start to us.
//
// IMPORTANT: kKeyphraseUuid MUST stay in sync with
// AI/clients/glasses/app/src/main/java/com/repository/glasses/listener/
//     wakeword/WakeWordPipeline.kt  ->  KEYPHRASE_UUID.
// If either value changes without the other, the HAL probe in start() will
// reject the load and the app will silently fall back to the ARM-ONNX path.
// Layout: {timeLow, timeMid, timeHiAndVersion, clockSeq, node[6]}
//   a1234567-b89c-def0-1234-56789abcdef0
// -----------------------------------------------------------------------------
static constexpr sound_trigger_uuid_t kHalUuid = {
    0xa1234567u, 0xb89cu, 0xdef0u, 0x1234u,
    {0x56u, 0x78u, 0x9au, 0xbcu, 0xdeu, 0xf0u},
};
static constexpr sound_trigger_uuid_t kKeyphraseUuid = {
    0xa1234567u, 0xb89cu, 0xdef0u, 0x1234u,
    {0x56u, 0x78u, 0x9au, 0xbcu, 0xdeu, 0xf0u},
};

// Default search paths for the QNN wake-word context binaries. Each path is
// probed in order; the first hit wins. A directory path is preferred: the
// QnnRuntime walks it for per-stage .bin files (silero_vad.bin,
// melspectrogram.bin, embedding_model.bin, sireneviy.bin) and loads each
// stage into its own QNN context. A file path is accepted as a fall-back for
// the legacy single-graph wakeword.bin layout.
static const char* const kModelSearch[] = {
    "/data/local/diy-overlay/system/etc/sthal/models",           // multi-graph dir (preferred)
    "/vendor/etc/sthal/models",                                  // multi-graph dir (vendor-side)
    "/data/local/diy-overlay/system/etc/sthal/models/wakeword.bin", // legacy single-file
    "/vendor/etc/sthal/models/wakeword.bin",                        // legacy single-file
    nullptr,
};

// Round LAB capacity up to 2 s @ 16 kHz = 32000 samples = 64 KB.
static constexpr size_t kLabSamples = 32000;

namespace {

// -----------------------------------------------------------------------------
// Per-model state
// -----------------------------------------------------------------------------
struct ModelSlot {
    sound_model_handle_t handle = 0;
    sound_trigger_sound_model_type_t type = SOUND_MODEL_TYPE_UNKNOWN;
    std::string                         opaqueToken; // from model->data payload
    sound_model_callback_t              modelCb = nullptr;
    void*                               modelCookie = nullptr;

    // Recognition
    std::atomic<bool>            active{false};
    recognition_callback_t       recoCb = nullptr;
    void*                        recoCookie = nullptr;
    bool                         captureRequested = false;
    int                          captureSession = 0;

    // Per-recognition mic source. Opened in startRecognition(), closed in
    // stopRecognitionLocked(). Not a Sthal-singleton member so that each
    // recognition session owns its capture lifecycle end-to-end.
    sthal::MicReader             mic;
};

// -----------------------------------------------------------------------------
// HAL state singleton
// -----------------------------------------------------------------------------
class Sthal {
public:
    Sthal() : qnn_(sthal::makeQnnRuntime()) {}

    int getProperties(struct sound_trigger_properties* p) {
        ST_TRACE("st.get_properties");
        if (!p) return -EINVAL;
        std::memset(p, 0, sizeof(*p));
        std::strncpy(p->implementor,
                     kImplementor, SOUND_TRIGGER_MAX_STRING_LEN - 1);
        std::strncpy(p->description,
                     kDescription, SOUND_TRIGGER_MAX_STRING_LEN - 1);
        p->version              = kImplVersion;
        p->uuid                 = kHalUuid;
        p->max_sound_models     = 1;
        p->max_key_phrases      = 0;  // we only do SOUND_MODEL_TYPE_GENERIC
        p->max_users            = 0;
        p->recognition_modes    = RECOGNITION_MODE_GENERIC_TRIGGER;
        p->capture_transition   = true;
        p->max_buffer_ms        = 2000; // size of LAB
        p->concurrent_capture   = true; // mic can be read concurrently by the app
        p->trigger_in_event     = false;
        p->power_consumption_mw = 10;   // aDSP estimate
        return 0;
    }

    // v1.3 extended properties. The Qualcomm HIDL @2.3 shim calls this and
    // expects a POINTER to a persistent (HAL-owned) struct back -- NOT a
    // write-through return into a caller-provided header. The shim reads
    //   returned_ptr->version   (must be >= 0x0103)
    //   returned_ptr->size      (ignored? but fill it defensively)
    //   followed by the full properties_extended_1_3 body.
    // See sthal_module_open: this struct is initialised once, then its
    // address is handed back on every call.
    const struct sound_trigger_properties_extended_1_3* getPropertiesExtended() {
        ST_TRACE("st.get_properties_extended");
        // Lazy-init under the lock so the first call populates, subsequent
        // calls just return the cached pointer. The struct outlives this HAL
        // instance (kExtProps is file-scope below).
        std::lock_guard<std::mutex> lk(mu_);
        if (!extPropsInit_) {
            kExtProps.header.version = SOUND_TRIGGER_DEVICE_API_VERSION_1_3;
            kExtProps.header.size    = sizeof(kExtProps);
            int rc = getProperties(&kExtProps.base);
            if (rc != 0) {
                ALOGE("getPropertiesExtended: legacy getProperties rc=%d", rc);
                return nullptr;
            }
            std::strncpy(kExtProps.supported_model_arch,
                         "Repository-QNN-HTP",
                         SOUND_TRIGGER_PROPERTIES_EXTENDED_MAX_ARCH_LEN - 1);
            kExtProps.supported_model_arch[
                SOUND_TRIGGER_PROPERTIES_EXTENDED_MAX_ARCH_LEN - 1] = '\0';
            // audio_capabilities bitmask. 0 == no HW-level EC/NS; we feed
            // raw PCM from tinyalsa straight into QNN.
            kExtProps.audio_capabilities = 0;
            extPropsInit_ = true;
            ALOGI("getPropertiesExtended: initialised (version=0x%04x size=%u)",
                  kExtProps.header.version, kExtProps.header.size);
        }
        return &kExtProps;
    }

    int loadSoundModel(struct sound_trigger_sound_model* m,
                       sound_model_callback_t cb, void* cookie,
                       sound_model_handle_t* outHandle) {
        ST_TRACE("st.load_sound_model");
        if (!m || !outHandle) return -EINVAL;
        if (m->type != SOUND_MODEL_TYPE_GENERIC) {
            ALOGW("loadSoundModel: only GENERIC supported, got %d", m->type);
            return -ENOSYS;
        }

        // Validate data_offset / data_size before dereferencing the trailing
        // payload. data_offset must be at least past the struct header, and
        // data_size must stay below a sane cap -- the framework enforces the
        // upper bound elsewhere; this is a guard against programmer error or
        // malicious callers slipping past that.
        if (m->data_size > 0) {
            if (m->data_offset < sizeof(sound_trigger_sound_model)) {
                ALOGW("loadSoundModel: bogus data_offset=%u (< header size %zu)",
                      m->data_offset, sizeof(sound_trigger_sound_model));
                return -EINVAL;
            }
            if (m->data_size > (1u << 20)) {
                ALOGW("loadSoundModel: data_size=%u exceeds 1 MiB cap",
                      m->data_size);
                return -EINVAL;
            }
        }

        std::string token;
        if (m->data_size > 0) {
            const char* data = reinterpret_cast<const char*>(m) + m->data_offset;
            // Bound the token read by the advertised size AND by the first NUL
            // to avoid UB on malformed input.
            size_t maxLen = m->data_size;
            size_t len = strnlen(data, maxLen);
            token.assign(data, len);
        }
        ALOGI("loadSoundModel: token='%s' size=%u", token.c_str(), m->data_size);

        std::lock_guard<std::mutex> lk(mu_);
        sound_model_handle_t h = nextHandle_++;
        auto slot = std::make_unique<ModelSlot>();
        slot->handle      = h;
        slot->type        = m->type;
        slot->opaqueToken = std::move(token);
        slot->modelCb     = cb;
        slot->modelCookie = cookie;
        models_[h] = std::move(slot);
        *outHandle = h;
        return 0;
    }

    int unloadSoundModel(sound_model_handle_t h) {
        ST_TRACE("st.unload_sound_model");
        // Phase 1 (under lock): locate the slot, atomically disarm detection,
        // clear the QNN callback so any in-flight QNN worker firing races into
        // a dead callback rather than a live slot. Then drop the lock so the
        // join below cannot deadlock with a callback that is trying to take
        // mu_ via onDetection().
        ModelSlot* s = nullptr;
        bool wasActive = false;
        {
            std::lock_guard<std::mutex> lk(mu_);
            auto it = models_.find(h);
            if (it == models_.end()) return -EINVAL;
            s = it->second.get();
            if (s->active.exchange(false)) {
                wasActive = true;
                qnn_->setCallback(nullptr);
            }
        }

        // Phase 2 (unlocked): join the mic thread. At this point active=false
        // and the QNN callback is nulled, so any pending detection on the QNN
        // worker is a no-op. Any last feedPcm() call from the mic thread into
        // QnnRuntime still runs, which must be safe -- QnnRuntime::feedPcm
        // takes its own lock and checks the graph handle.
        if (wasActive) {
            s->mic.stop();
            s->mic.close();
        }

        // Phase 3 (under lock): finalise teardown and drop the slot. The
        // unique_ptr destructor is now safe because the mic thread has been
        // joined and the QNN callback is already unregistered.
        {
            std::lock_guard<std::mutex> lk(mu_);
            auto it = models_.find(h);
            if (it == models_.end()) return 0; // vanished under us; benign
            if (wasActive) {
                qnn_->reset();
                it->second->recoCb = nullptr;
                it->second->recoCookie = nullptr;
            }
            models_.erase(it);
        }
        return 0;
    }

    int startRecognition(sound_model_handle_t h,
                         const struct sound_trigger_recognition_config* cfg,
                         recognition_callback_t cb, void* cookie) {
        ST_TRACE("st.start_recognition");
        std::lock_guard<std::mutex> lk(mu_);
        auto it = models_.find(h);
        if (it == models_.end()) return -EINVAL;
        ModelSlot* s = it->second.get();
        if (s->active.exchange(true)) return 0; // already running

        s->recoCb            = cb;
        s->recoCookie        = cookie;
        s->captureRequested  = cfg ? cfg->capture_requested : false;
        s->captureSession    = cfg ? cfg->capture_handle    : 0;

        if (!qnnInitialized_) {
            std::string path = pickModelPath();
            if (path.empty() || !qnn_->init(path)) {
                ALOGE("startRecognition: QNN init failed (path='%s')", path.c_str());
                s->active = false;
                return -ENODEV;
            }
            qnnInitialized_ = true;
            qnn_->setCallback([this, s](const sthal::QnnDetection& d) {
                onDetection(s, d);
            });
        }

        // ---------------------------------------------------------------------
        // Mic capture via tinyalsa is DISABLED at this ship point.
        //
        // Direct /dev/snd/pcmC0D6c access collides with the vendor PAL
        // (proprietary Pulse Audio Library) already holding the DSP audio
        // path on the Rokid Neo board. Doing this right requires linking
        // against /vendor/lib64/libpal_client.so and feeding PCM in via a
        // PalStream handle; that needs Qualcomm's proprietary PAL headers
        // which are not part of this repo. See MicReader header comment
        // for the full PAL-integration roadmap.
        //
        // With the mic reader disabled, the HAL stays "armed but passive":
        //   - The framework sees a healthy module, load, start sequence.
        //   - StatusListener.onServiceDied does NOT fire (no segfault).
        //   - No audio flows into the QNN graphs, so no on-HAL detections.
        //   - The app must drive wake-word detection from its ARM-ONNX
        //     fallback path until PAL integration lands.
        //
        // Keep the MicReader, QnnRuntime, and LAB ring intact -- they are
        // future work, not legacy. Once PalStream replaces MicReader here,
        // this comment and the WARN log go away.
        // ---------------------------------------------------------------------
        ALOGW("mic reader disabled pending pal_client integration; "
              "HAL is passive until app feeds audio");

        ALOGI("startRecognition h=%d capture=%d session=%d onHex=%d mic=disabled",
              h, (int)s->captureRequested, s->captureSession,
              (int)qnn_->onHexagon());
        return 0;
    }

    int stopRecognition(sound_model_handle_t h) {
        ST_TRACE("st.stop_recognition");
        // Phase 1 (under lock): disarm + clear QNN callback so the QNN worker
        // can't fire into a dying slot.
        ModelSlot* s;
        {
            std::lock_guard<std::mutex> lk(mu_);
            auto it = models_.find(h);
            if (it == models_.end()) return -EINVAL;
            s = it->second.get();
            if (!s->active.exchange(false)) return 0;
            qnn_->setCallback(nullptr);
        }
        // Phase 2 (unlocked): join the mic thread. No other code path touches
        // s->mic, so this is safe without the lock.
        s->mic.stop();
        s->mic.close();
        // Phase 3 (under lock): reset QNN state and drop the recognition
        // callback. The slot itself stays in models_ -- unload is a separate
        // op.
        {
            std::lock_guard<std::mutex> lk(mu_);
            qnn_->reset();
            s->recoCb = nullptr;
            s->recoCookie = nullptr;
        }
        return 0;
    }

    int stopAllRecognitions() {
        ST_TRACE("st.stop_all_recognitions");
        // Phase 1 (under lock): collect pointers to every active slot,
        // atomically disarm each, and clear the QNN callback once -- there
        // is a single QnnRuntime shared by all slots.
        std::vector<ModelSlot*> toJoin;
        {
            std::lock_guard<std::mutex> lk(mu_);
            toJoin.reserve(models_.size());
            for (auto& kv : models_) {
                ModelSlot* s = kv.second.get();
                if (s->active.exchange(false)) {
                    toJoin.push_back(s);
                }
            }
            if (!toJoin.empty()) qnn_->setCallback(nullptr);
        }
        // Phase 2 (unlocked): join every captured mic thread. Pointer
        // stability is guaranteed because unique_ptr in the map keeps the
        // slot alive -- we never erase from stopAllRecognitions.
        for (ModelSlot* s : toJoin) {
            s->mic.stop();
            s->mic.close();
        }
        // Phase 3 (under lock): reset QNN state once and clear per-slot
        // recognition callbacks.
        {
            std::lock_guard<std::mutex> lk(mu_);
            if (!toJoin.empty()) qnn_->reset();
            for (ModelSlot* s : toJoin) {
                s->recoCb = nullptr;
                s->recoCookie = nullptr;
            }
        }
        return 0;
    }

    int getModelState(sound_model_handle_t /*h*/) {
        ST_TRACE("st.get_model_state");
        // Not supported — framework expects a state-response event; we decline.
        return -ENOSYS;
    }

private:
    std::mutex                                         mu_;
    std::map<sound_model_handle_t, std::unique_ptr<ModelSlot>> models_;
    sound_model_handle_t                               nextHandle_ = 1;

    std::unique_ptr<sthal::IQnnRuntime>                qnn_;
    bool                                               qnnInitialized_ = false;

    // Storage for v1.3 extended properties. The Qualcomm HIDL @2.3 shim
    // expects get_properties_extended() to return a pointer to a HAL-owned
    // buffer that lives beyond the call (the shim dereferences it directly
    // to read version/size and subsequent fields). kExtProps is lazy-populated
    // on the first call under mu_ and never mutated afterwards.
    struct sound_trigger_properties_extended_1_3      kExtProps{};
    bool                                               extPropsInit_ = false;

    // LAB ring. labHead_ is written by the mic thread (in pushLab) and read
    // by the QNN worker thread (in snapshotLab via onDetection). Sample-level
    // torn reads across the ring boundary are acceptable -- audio is a lossy
    // signal and a few bad samples at the seam won't break downstream
    // transcription. But we still want an atomic head index for publication
    // so the reader can't observe a partial pointer update on platforms that
    // do not guarantee word-sized atomic loads.
    std::vector<int16_t>                               lab_{std::vector<int16_t>(kLabSamples, 0)};
    std::atomic<size_t>                                labHead_{0};
    std::atomic<uint64_t>                              labTotal_{0};

    static std::string pickModelPath() {
        // Accept either a directory (multi-graph) or a file (legacy single
        // context binary). stat() on the candidate; the QnnRuntime decides
        // which flavour based on S_ISDIR.
        for (const char* const* p = kModelSearch; *p; ++p) {
            struct stat st{};
            if (::stat(*p, &st) == 0) {
                return *p;
            }
        }
        return {};
    }

    void pushLab(const int16_t* pcm, size_t n) {
        if (n == 0) return;
        size_t head = labHead_.load(std::memory_order_relaxed);
        for (size_t i = 0; i < n; ++i) {
            lab_[head] = pcm[i];
            head = (head + 1) % kLabSamples;
        }
        labHead_.store(head, std::memory_order_release);
        labTotal_.fetch_add(n, std::memory_order_relaxed);
    }

    // Snapshot last kLabSamples into a contiguous buffer (caller-freed).
    void snapshotLab(std::vector<int16_t>& out) {
        out.resize(kLabSamples);
        size_t head = labHead_.load(std::memory_order_acquire);
        // oldest sample is at head (ring-buffer invariant)
        std::memcpy(out.data(),              lab_.data() + head,
                    (kLabSamples - head) * sizeof(int16_t));
        std::memcpy(out.data() + (kLabSamples - head),
                    lab_.data(), head * sizeof(int16_t));
    }

    void onDetection(ModelSlot* s, const sthal::QnnDetection& d) {
        ST_TRACE("st.recognition_callback");
        // Called on QnnRuntime worker thread. We must not block; emit the
        // framework event and return.
        if (!s || !s->active.load() || !s->recoCb) return;

        // Alloc generic recognition event + LAB payload contiguous block.
        std::vector<int16_t> lab;
        if (s->captureRequested) snapshotLab(lab);

        const size_t payloadBytes = lab.size() * sizeof(int16_t);
        const size_t headerBytes  = sizeof(struct sound_trigger_generic_recognition_event);
        std::vector<uint8_t> blob(headerBytes + payloadBytes, 0);

        auto* ev = reinterpret_cast<struct sound_trigger_generic_recognition_event*>(blob.data());
        ev->common.status              = RECOGNITION_STATUS_SUCCESS;
        ev->common.type                = SOUND_MODEL_TYPE_GENERIC;
        ev->common.model               = s->handle;
        ev->common.capture_available   = s->captureRequested;
        ev->common.capture_session     = s->captureSession;
        ev->common.capture_delay_ms    = 0;
        ev->common.capture_preamble_ms = 0;
        ev->common.trigger_in_data     = false;
        ev->common.sample_rate         = 16000;
        ev->common.channel_mask        = 0x1; // AUDIO_CHANNEL_IN_MONO constant value
        ev->common.format              = 1;   // AUDIO_FORMAT_PCM_16_BIT constant
        ev->common.data_size           = static_cast<unsigned int>(payloadBytes);
        ev->common.data_offset         = static_cast<unsigned int>(headerBytes);
        if (payloadBytes) {
            std::memcpy(blob.data() + headerBytes, lab.data(), payloadBytes);
        }
        ALOGI("detection: conf=%.3f frames=%u lab=%zu bytes",
              d.confidence, d.pcm_frames, payloadBytes);

        {
            ST_TRACE("st.recognition_callback.upcall");
            s->recoCb(&ev->common, s->recoCookie);
        }
    }
};

Sthal& g() { static Sthal s; return s; }

// -----------------------------------------------------------------------------
// Function-pointer table wrappers (C linkage)
// -----------------------------------------------------------------------------
extern "C" {

static int stdev_close(hw_device_t* /*dev*/) {
    ST_TRACE("st.close");
    // We keep the g() singleton alive across framework-driven close so the
    // next open() is fast. BUT: any active recognition must be torn down
    // here, otherwise the mic thread keeps pulling audio with no callback
    // target, and on the framework's next load_sound_model we end up with
    // two mic threads racing for the same PCM device.
    g().stopAllRecognitions();
    return 0;
}
static int stdev_get_properties(const sound_trigger_hw_device* /*dev*/,
                                struct sound_trigger_properties* p) {
    return g().getProperties(p);
}
static const struct sound_trigger_properties_header*
stdev_get_properties_extended(const sound_trigger_hw_device* /*dev*/) {
    // Cast from extended struct ptr to header-common prefix -- both layouts
    // begin with `sound_trigger_properties_header header` so the ptr is
    // identical. The QTI shim reads header.version first, then casts the
    // same pointer up to extended_1_3 itself.
    const auto* ext = g().getPropertiesExtended();
    if (!ext) return nullptr;
    return &ext->header;
}
// v1.3 model-parameter entrypoints. We don't expose any tunable parameters,
// so every call returns -ENOSYS which the HIDL shim translates to
// ModelParameterRange::Invalid / SetParameter::NOT_SUPPORTED. This is the
// documented path for HALs that don't implement model params.
static int stdev_set_parameter(const sound_trigger_hw_device* /*dev*/,
                               sound_model_handle_t /*handle*/,
                               int /*param*/, int /*value*/) {
    return -ENOSYS;
}
static int stdev_get_parameter(const sound_trigger_hw_device* /*dev*/,
                               sound_model_handle_t /*handle*/,
                               int /*param*/, int* /*value*/) {
    return -ENOSYS;
}
static int stdev_query_parameter(const sound_trigger_hw_device* /*dev*/,
                                 sound_model_handle_t /*handle*/,
                                 int /*param*/, void* /*range*/) {
    return -ENOSYS;
}

// Forward declare -- stdev_start_recognition is defined below but referenced
// from stdev_start_recognition_extended above it.
static int stdev_start_recognition(const sound_trigger_hw_device*,
                                   sound_model_handle_t,
                                   const struct sound_trigger_recognition_config*,
                                   recognition_callback_t, void*);

// 1.3-extended start_recognition: same semantics as the legacy 1.0 entry for
// our HAL since we don't support model parameters. The QTI shim calls this
// at vtable offset 0xd0 when the client invokes ISoundTriggerHw v2.3
// startRecognition_2_3.
static int stdev_start_recognition_extended(const sound_trigger_hw_device* dev,
                                            sound_model_handle_t handle,
                                            const struct sound_trigger_recognition_config* cfg,
                                            recognition_callback_t cb,
                                            void* cookie) {
    return stdev_start_recognition(dev, handle, cfg, cb, cookie);
}
static int stdev_load_sound_model(const sound_trigger_hw_device* /*dev*/,
                                  struct sound_trigger_sound_model* m,
                                  sound_model_callback_t cb, void* cookie,
                                  sound_model_handle_t* h) {
    return g().loadSoundModel(m, cb, cookie, h);
}
static int stdev_unload_sound_model(const sound_trigger_hw_device* /*dev*/,
                                    sound_model_handle_t h) {
    return g().unloadSoundModel(h);
}
static int stdev_start_recognition(const sound_trigger_hw_device* /*dev*/,
                                   sound_model_handle_t h,
                                   const struct sound_trigger_recognition_config* cfg,
                                   recognition_callback_t cb, void* cookie) {
    return g().startRecognition(h, cfg, cb, cookie);
}
static int stdev_stop_recognition(const sound_trigger_hw_device* /*dev*/,
                                  sound_model_handle_t h) {
    return g().stopRecognition(h);
}
static int stdev_stop_all_recognitions(const sound_trigger_hw_device* /*dev*/) {
    return g().stopAllRecognitions();
}
static int stdev_get_model_state(const sound_trigger_hw_device* /*dev*/,
                                 sound_model_handle_t h) {
    return g().getModelState(h);
}

// -----------------------------------------------------------------------------
// Module open() — framework calls this via module->methods->open(...)
// -----------------------------------------------------------------------------
static sound_trigger_hw_device kDev = {};

// Compile-time verification that our struct matches the QTI @2.3 shim's
// expected memory layout. Any mismatch causes EINVAL or SIGSEGV in the
// shim. Offsets verified via llvm-objdump of
// /vendor/lib64/hw/android.hardware.soundtrigger@2.3-impl.so:
//   SoundTriggerHw::getProperties    -> ldr [mHwDevice, #0x78]
//   SoundTriggerHw::doLoadSoundModel -> ldr [mHwDevice, #0x80]
//   SoundTriggerHw::unloadSoundModel -> ldr [mHwDevice, #0x88]
//   SoundTriggerHw::startRec_2_3     -> ldr [mHwDevice, #0xd0]
//   SoundTriggerHw::stopRec          -> ldr [mHwDevice, #0x98]
//   SoundTriggerHw::setParameter     -> ldr [mHwDevice, #0xb0]
//   SoundTriggerHw::getParameter     -> ldr [mHwDevice, #0xb8]
//   SoundTriggerHw::queryParameter   -> ldr [mHwDevice, #0xc0]
//   SoundTriggerHw::getProperties_2_3-> ldr [mHwDevice, #0xc8]
static_assert(offsetof(sound_trigger_hw_device, get_properties)             == 0x78,
              "QTI @2.3 shim reads get_properties at dev+0x78");
static_assert(offsetof(sound_trigger_hw_device, load_sound_model)           == 0x80,
              "QTI @2.3 shim reads load_sound_model at dev+0x80");
static_assert(offsetof(sound_trigger_hw_device, unload_sound_model)         == 0x88,
              "QTI @2.3 shim reads unload_sound_model at dev+0x88");
static_assert(offsetof(sound_trigger_hw_device, start_recognition)          == 0x90,
              "QTI @2.3 shim reads start_recognition at dev+0x90");
static_assert(offsetof(sound_trigger_hw_device, stop_recognition)           == 0x98,
              "QTI @2.3 shim reads stop_recognition at dev+0x98");
static_assert(offsetof(sound_trigger_hw_device, stop_all_recognitions)      == 0xa0,
              "QTI @2.3 shim reads stop_all_recognitions at dev+0xa0");
static_assert(offsetof(sound_trigger_hw_device, get_model_state)            == 0xa8,
              "QTI @2.3 shim reads get_model_state at dev+0xa8");
static_assert(offsetof(sound_trigger_hw_device, set_parameter)              == 0xb0,
              "QTI @2.3 shim reads set_parameter at dev+0xb0");
static_assert(offsetof(sound_trigger_hw_device, get_parameter)              == 0xb8,
              "QTI @2.3 shim reads get_parameter at dev+0xb8");
static_assert(offsetof(sound_trigger_hw_device, query_parameter)            == 0xc0,
              "QTI @2.3 shim reads query_parameter at dev+0xc0");
static_assert(offsetof(sound_trigger_hw_device, get_properties_extended)    == 0xc8,
              "QTI @2.3 shim reads get_properties_extended at dev+0xc8");
static_assert(offsetof(sound_trigger_hw_device, start_recognition_extended) == 0xd0,
              "QTI @2.3 shim reads start_recognition_extended at dev+0xd0");
// Extended properties struct: QTI shim reads base at ext+0x10,
// supported_model_arch at ext+0xc4, audio_capabilities at ext+0x104.
static_assert(offsetof(sound_trigger_properties_extended_1_3, base) == 0x10,
              "QTI @2.3 shim reads base at ext+0x10");
static_assert(offsetof(sound_trigger_properties_extended_1_3, supported_model_arch) == 0xc4,
              "QTI @2.3 shim reads supported_model_arch at ext+0xc4");
static_assert(offsetof(sound_trigger_properties_extended_1_3, audio_capabilities) == 0x104,
              "QTI @2.3 shim reads audio_capabilities at ext+0x104");

static int sthal_module_open(const hw_module_t* module, const char* id,
                             hw_device_t** device) {
    ST_TRACE("st.module_open");
    if (!id || std::strcmp(id, SOUND_TRIGGER_HARDWARE_INTERFACE) != 0) {
        return -EINVAL;
    }
    kDev.common.tag     = HARDWARE_DEVICE_TAG;
    // v1.3 is required by the HIDL @2.3 legacy shim -- lower values get the
    // "wrong sound trigger hw device version 0102" error at service start
    // and zero modules surface to the middleware.
    kDev.common.version = SOUND_TRIGGER_DEVICE_API_VERSION_1_3;
    kDev.common.module  = const_cast<hw_module_t*>(module);
    kDev.common.close   = stdev_close;

    // Wire QTI-compatible vtable. The ORDER here does not matter; only the
    // memory layout dictated by the struct declaration matters. The struct
    // places pointers at the exact offsets QTI's @2.3 shim expects.
    kDev.get_properties              = stdev_get_properties;
    kDev.load_sound_model            = stdev_load_sound_model;
    kDev.unload_sound_model          = stdev_unload_sound_model;
    kDev.start_recognition           = stdev_start_recognition;
    kDev.stop_recognition            = stdev_stop_recognition;
    kDev.stop_all_recognitions       = stdev_stop_all_recognitions;
    // 1.3 (QTI offsets 0xb0..0xd0)
    kDev.set_parameter               = stdev_set_parameter;
    kDev.get_parameter               = stdev_get_parameter;
    kDev.query_parameter             = stdev_query_parameter;
    kDev.get_properties_extended     = stdev_get_properties_extended;
    kDev.start_recognition_extended  = stdev_start_recognition_extended;
    // 1.2 trailing entry (kept for legacy 2.x paths)
    kDev.get_model_state             = stdev_get_model_state;

    // Touch g() once so singleton is constructed before any framework call.
    (void)g();

    ALOGI("sthal: module opened (id=%s, version=%s, device_api=0x%04x)",
          id, kDescription, kDev.common.version);
    *device = &kDev.common;
    return 0;
}

static hw_module_methods_t sthal_module_methods = {
    .open = sthal_module_open,
};

// The symbol the AOSP loader looks up: HAL_MODULE_INFO_SYM = HMI.
__attribute__((visibility("default")))
hw_module_t HAL_MODULE_INFO_SYM = {
    .tag                = HARDWARE_MODULE_TAG,
    .module_api_version = SOUND_TRIGGER_MODULE_API_VERSION_1_0,
    .hal_api_version    = 0,
    .id                 = SOUND_TRIGGER_HARDWARE_MODULE_ID,
    .name               = "Repository custom SoundTrigger HAL",
    .author             = "Repository",
    .methods            = &sthal_module_methods,
    .dso                = nullptr,
    .reserved           = {0},
};

} // extern "C"

} // namespace
