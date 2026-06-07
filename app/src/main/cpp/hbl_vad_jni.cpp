// JNI bridge to Rokid's HblVad implementation in /system/lib64/librokid_agc.so.
//
// HblVad is a tiny (~419 KB) classical-DSP VAD shipped on the Rokid AR Lite as
// part of librokid_agc.so. It runs at fixed 16 kHz / 20 ms frames (mode=6) and
// returns a per-frame speech/silence decision with effectively zero CPU cost
// compared to silero_vad.onnx (~50 MB ONNX session, multi-ms inference per
// frame).
//
// We dlopen the vendor lib lazily on the first nativeCreate() so build hosts
// without the .so still link this JNI fine; the Kotlin wrapper falls back to
// silero when nativeCreate returns 0.
//
// ABI reverse-engineered via /tmp/vadprobe/probe_vad.c (see ABI_NOTES.md):
//   void* HblVadCreate(int mode);          // mode=6 -> 16 kHz / 20 ms
//   int   HblVadProcess(void* h, hbl_AudioFrame* af);  // 0=silence, >=1=speech
//   int   HblVadDelete(void* h);
//
// Frame layout (mock; only the four named fields are read):
//   off  0..23 : pad
//   off 24..27 : uint32 sample_size_bytes  (= 2 for int16)
//   off 28..31 : pad
//   off 32..39 : int16_t* data_ptr
//   off 40..43 : uint32 flag (= 0)
//   off 44..55 : pad
//   off 56..59 : uint32 mode (= 6, matches Create)
//   off 60..67 : pad (struct padded to 64 bytes)

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>

#define LOG_TAG "HblVadJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

using HblVadCreate_t  = void* (*)(int);
using HblVadProcess_t = int   (*)(void*, void*);
using HblVadDelete_t  = int   (*)(void*);

struct VadVtable {
    void*            handle = nullptr;
    HblVadCreate_t   create  = nullptr;
    HblVadProcess_t  process = nullptr;
    HblVadDelete_t   destroy = nullptr;
    bool             attempted = false;
    bool             ok = false;
};

VadVtable g_vt;
std::mutex g_vtMutex;
std::string g_appDataDir;

// Copy /system/lib64/librokid_agc.so into the app's private data dir so we can
// dlopen it. The classloader-namespace forbids direct loads from /system/lib64
// for arbitrary vendor libs (no public.libraries entry), but the app's own
// data dir is on permitted_paths, so a local copy works around the namespace
// without requiring privapp / SELinux changes.
//
// Returns the local path on success, empty string on failure. Caches the
// result so subsequent calls are no-ops.
const char* stageLibLocal() {
    if (g_appDataDir.empty()) return nullptr;
    static std::string localPath;
    if (!localPath.empty()) return localPath.c_str();

    const char* kSrc = "/system/lib64/librokid_agc.so";
    std::string dst = g_appDataDir + "/librokid_agc.so";

    int sfd = open(kSrc, O_RDONLY | O_CLOEXEC);
    if (sfd < 0) {
        LOGW("open(%s) failed: %s", kSrc, strerror(errno));
        return nullptr;
    }
    int dfd = open(dst.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (dfd < 0) {
        LOGW("open(%s) for write failed: %s", dst.c_str(), strerror(errno));
        close(sfd);
        return nullptr;
    }
    char buf[8192];
    ssize_t n;
    bool ok = true;
    while ((n = read(sfd, buf, sizeof(buf))) > 0) {
        ssize_t off = 0;
        while (off < n) {
            ssize_t w = write(dfd, buf + off, n - off);
            if (w < 0) { ok = false; break; }
            off += w;
        }
        if (!ok) break;
    }
    if (n < 0) ok = false;
    close(sfd);
    close(dfd);
    if (!ok) {
        LOGW("copy %s -> %s failed", kSrc, dst.c_str());
        unlink(dst.c_str());
        return nullptr;
    }
    LOGI("staged librokid_agc.so -> %s", dst.c_str());
    localPath = dst;
    return localPath.c_str();
}

bool ensureLoaded() {
    std::lock_guard<std::mutex> lock(g_vtMutex);
    if (g_vt.attempted) return g_vt.ok;
    g_vt.attempted = true;

    // Try /system path first; on most builds this is blocked by the
    // classloader-namespace, but if a future Rokid OTA adds librokid_agc to
    // public.libraries.txt it will Just Work.
    const char* kLibPath = "/system/lib64/librokid_agc.so";
    g_vt.handle = dlopen(kLibPath, RTLD_NOW | RTLD_LOCAL);
    if (!g_vt.handle) {
        LOGW("dlopen(%s) failed: %s -- staging local copy", kLibPath, dlerror());
        const char* local = stageLibLocal();
        if (!local) return false;
        g_vt.handle = dlopen(local, RTLD_NOW | RTLD_LOCAL);
    }
    if (!g_vt.handle) {
        LOGW("dlopen staged copy failed: %s", dlerror());
        return false;
    }
    g_vt.create  = reinterpret_cast<HblVadCreate_t>(dlsym(g_vt.handle, "HblVadCreate"));
    g_vt.process = reinterpret_cast<HblVadProcess_t>(dlsym(g_vt.handle, "HblVadProcess"));
    g_vt.destroy = reinterpret_cast<HblVadDelete_t>(dlsym(g_vt.handle, "HblVadDelete"));
    if (!g_vt.create || !g_vt.process || !g_vt.destroy) {
        LOGW("dlsym missing: create=%p process=%p delete=%p",
             g_vt.create, g_vt.process, g_vt.destroy);
        dlclose(g_vt.handle);
        g_vt.handle = nullptr;
        g_vt.create = nullptr;
        g_vt.process = nullptr;
        g_vt.destroy = nullptr;
        return false;
    }
    g_vt.ok = true;
    LOGI("librokid_agc.so loaded; HblVad ABI resolved");
    return true;
}

// 64-byte struct populated per call. Stack-allocated by nativeProcess.
struct alignas(8) HblAudioFrame {
    uint8_t  pad0[24];
    uint32_t sample_size_bytes;  // off 24
    uint8_t  pad1[4];            // off 28
    int16_t* data_ptr;           // off 32
    uint32_t flag;               // off 40
    uint8_t  pad2[12];           // off 44
    uint32_t mode;               // off 56
    uint8_t  pad3[4];            // off 60..63
};
static_assert(sizeof(HblAudioFrame) == 64, "HblAudioFrame must be 64 bytes");
static_assert(offsetof(HblAudioFrame, sample_size_bytes) == 24, "sample_size_bytes offset");
static_assert(offsetof(HblAudioFrame, data_ptr) == 32, "data_ptr offset");
static_assert(offsetof(HblAudioFrame, flag) == 40, "flag offset");
static_assert(offsetof(HblAudioFrame, mode) == 56, "mode offset");

constexpr jint kFrameSamples = 320;  // 20 ms @ 16 kHz

// Per-handle wrapper. nativeProcess() increments in_use for the duration of
// the native call; nativeDelete() flips deleting and spin-waits (max 200 ms)
// for in_use==0 before calling HblVadDelete on the underlying handle. This
// mirrors the inCallback atomic-guard pattern used in acd_native.cpp and
// prevents a UAF where close() on one thread races a still-in-flight
// isSpeech() on another.
struct HblHandle {
    void*            h;
    std::atomic<int> in_use;
    std::atomic<int> deleting;
};

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_repository_glasses_listener_wakeword_HblVadDetector_nativeSetDataDir(
        JNIEnv* env, jclass /*clazz*/, jstring dir) {
    if (!dir) return;
    const char* c = env->GetStringUTFChars(dir, nullptr);
    if (c) {
        // Hold g_vtMutex while writing g_appDataDir so a racing first
        // nativeCreate() on another thread doesn't read a half-published
        // std::string inside stageLibLocal().
        {
            std::lock_guard<std::mutex> lock(g_vtMutex);
            g_appDataDir = c;
        }
        env->ReleaseStringUTFChars(dir, c);
    }
}

JNIEXPORT jlong JNICALL
Java_com_repository_glasses_listener_wakeword_HblVadDetector_nativeCreate(
        JNIEnv* /*env*/, jclass /*clazz*/, jint mode) {
    if (!ensureLoaded()) return 0;
    void* h = g_vt.create(mode);
    if (!h) {
        LOGW("HblVadCreate(mode=%d) returned null", mode);
        return 0;
    }
    auto* wrap = new (std::nothrow) HblHandle{h, {0}, {0}};
    if (!wrap) {
        g_vt.destroy(h);
        return 0;
    }
    LOGI("HblVadCreate(mode=%d) -> raw=%p wrap=%p", mode, h, wrap);
    return reinterpret_cast<jlong>(wrap);
}

JNIEXPORT jint JNICALL
Java_com_repository_glasses_listener_wakeword_HblVadDetector_nativeProcess(
        JNIEnv* env, jclass /*clazz*/, jlong handle, jshortArray pcm, jint n) {
    if (handle == 0 || !g_vt.ok) return -1;
    if (n != kFrameSamples) return -1;
    if (!pcm) return -1;

    auto* wrap = reinterpret_cast<HblHandle*>(handle);
    // Refuse if delete already started; otherwise pin in_use across the call.
    if (wrap->deleting.load(std::memory_order_acquire) != 0) return -1;
    wrap->in_use.fetch_add(1, std::memory_order_acq_rel);
    // Re-check after the increment in case delete won the race.
    if (wrap->deleting.load(std::memory_order_acquire) != 0) {
        wrap->in_use.fetch_sub(1, std::memory_order_acq_rel);
        return -1;
    }

    int16_t buf[kFrameSamples];
    env->GetShortArrayRegion(pcm, 0, kFrameSamples, reinterpret_cast<jshort*>(buf));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        wrap->in_use.fetch_sub(1, std::memory_order_acq_rel);
        return -1;
    }

    HblAudioFrame af;
    std::memset(&af, 0, sizeof(af));
    af.sample_size_bytes = 2;
    af.data_ptr          = buf;
    af.flag              = 0;
    af.mode              = 6;

    jint result = static_cast<jint>(g_vt.process(wrap->h, &af));
    wrap->in_use.fetch_sub(1, std::memory_order_acq_rel);
    return result;
}

JNIEXPORT void JNICALL
Java_com_repository_glasses_listener_wakeword_HblVadDetector_nativeDelete(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    if (handle == 0 || !g_vt.ok) return;
    auto* wrap = reinterpret_cast<HblHandle*>(handle);
    int expected = 0;
    if (!wrap->deleting.compare_exchange_strong(expected, 1,
            std::memory_order_acq_rel, std::memory_order_acquire)) {
        // Another thread is already deleting; bail.
        return;
    }
    // Spin-wait up to ~200 ms for any in-flight nativeProcess to finish.
    auto start = std::chrono::steady_clock::now();
    while (wrap->in_use.load(std::memory_order_acquire) != 0) {
        if (std::chrono::steady_clock::now() - start >
                std::chrono::milliseconds(200)) {
            LOGW("nativeDelete timed out waiting for in_use=0 (still %d); proceeding",
                 wrap->in_use.load(std::memory_order_acquire));
            break;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    g_vt.destroy(wrap->h);
    delete wrap;
}

}  // extern "C"
