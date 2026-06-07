// sthal/src/qnn_runtime.cpp
//
// Two implementations of IQnnRuntime:
//  - QnnHtpRuntime: dlopen's libQnnHtp.so + libQnnSystem.so, dlsym's
//    QnnInterface_getProviders / QnnSystemInterface_getProviders, and then
//    drives the three-stage wake-word pipeline through QnnGraph (see
//    qnn_graph.{h,cpp}). We deliberately include QNN SDK headers here (this
//    is a .cpp, not a public header) and build against QNN_SDK_ROOT/include.
//  - NullQnnRuntime: no-op.
//
// Pipeline (per real-time 80 ms PCM chunk @ 16 kHz = 1280 samples):
//    chunk -> melspectrogram   (stage 1) -> append frames to mel ring buffer
//          -> embedding_model  (stage 2) on latest MEL_WINDOW=76 frames
//          -> sireneviy        (stage 3) on trailing N_FRAMES=16 embeddings
//          -> sigmoid score; if > 0.5, fire detection callback.
//
// Constants mirror phone OpenWakeWordEngine.kt (see
//   AI/clients/phone/app/src/main/java/com/repository/listener/audio/OpenWakeWordEngine.kt
// lines 18..23 + runClassifier / runEmbedding / runMelspectrogram) so that
// what the phone classifier learned in ARM-ONNX transfers verbatim to the
// on-HTP glasses model.
//
// Silero VAD: intentionally skipped on the HAL path. The model's LSTM does
// not compile on HTP 'prepare'; we run mel -> embedding -> classifier
// unconditionally, which matches test/train distribution (the ONNX fallback
// still gates via VAD). False-positive risk on silence is bounded by the
// classifier threshold.
//
// Policy: any failure (dlopen, dlsym, context load, binary parse) logs and
// leaves the object in a state where feedPcm() is safe but a no-op. The
// caller then either accepts the null fallback (dev) or refuses the model
// (production). onHexagon() reports actual post-init mode.

#include "qnn_runtime.h"
#include "qnn_graph.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <dirent.h>
#include <dlfcn.h>
#include <memory>
#include <mutex>
#include <string>
#include <sys/stat.h>
#include <vector>

#include <android/log.h>

#include <QnnBackend.h>
#include <QnnCommon.h>
#include <QnnDevice.h>
#include <QnnInterface.h>
#include <QnnTypes.h>
#include <System/QnnSystemInterface.h>

#define LOG_TAG "sthal.qnn"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace sthal {

namespace {

// ---- Pipeline constants (mirror of OpenWakeWordEngine.kt :18..23) -----------
// Chunk fed to the mel stage at once: 80 ms at 16 kHz.
constexpr size_t kChunkSize    = 1280;
// Mel bins per frame. The mel graph emits N x MEL_BINS floats per chunk.
constexpr size_t kMelBins      = 32;
// Mel window required by the embedding stage: 76 frames of MEL_BINS.
constexpr size_t kMelWindow    = 76;
// Embedding length produced by the embedding stage, one per mel-window inference.
constexpr size_t kEmbeddingDim = 96;
// Classifier-input window length in embeddings.
constexpr size_t kNumEmbFrames = 16;
// Keep at most this many mel frames around. (> 2 x MEL_WINDOW safe.)
constexpr size_t kMaxMelBuffer = 200;
// Detection threshold; mirrors phone AppConfig.OWW_THRESHOLD / WakeWordDetector
// SCORE_ACCUMULATE_THRESHOLD is 0.1 but a full 'hit' fires at 0.5.
constexpr float  kDetectionThreshold = 0.5f;
// Per-HAL cooldown between consecutive fires. Matches phone
// WakeWordDetector.WAKE_COOLDOWN_MS=1500 so back-to-back chunk scores above
// threshold still produce only one detection event.
static constexpr int64_t kCooldownNs = 1'500'000'000LL;

// Monotonic nanosecond timestamp (steady_clock). Used by the HAL-side
// cooldown guard in processChunk(). Never wraps within a device uptime.
static inline int64_t monotonic_ns() {
    return static_cast<int64_t>(
        std::chrono::steady_clock::now().time_since_epoch().count());
}

// Sigmoid fallback. Applied only if the classifier tensor value is outside
// [0,1], i.e. the upstream ONNX-to-QNN conversion folded away the final
// sigmoid and we're reading raw logits. Normal path is a no-op.
static inline float sig(float x) {
    return 1.0f / (1.0f + std::exp(-x));
}

// Wake-word pipeline stage names, in execution order. Matches the graph_names
// produced by sthal/tools/compile_onnx_to_qnn.sh and the file stems pushed to
// /data/local/diy-overlay/system/etc/sthal/models/<stage>.bin.
//
// silero_vad: would live between PCM and mel, but HTP cannot compile its LSTM
// stack. Intentionally absent from the executable pipeline here.
constexpr const char* kStageMel = "melspectrogram";
constexpr const char* kStageEmb = "embedding_model";
constexpr const char* kStageCls = "sireneviy";

// QNN 'getProviders' entry points (dlsym'd from the backend/system libs).
using FnCoreGetProviders =
    Qnn_ErrorHandle_t (*)(const QnnInterface_t***, uint32_t*);
using FnSystemGetProviders =
    Qnn_ErrorHandle_t (*)(const QnnSystemInterface_t***, uint32_t*);

// -----------------------------------------------------------------------------
// Null impl
// -----------------------------------------------------------------------------
class NullQnnRuntimeImpl : public IQnnRuntime {
public:
    bool init(const std::string&) override {
        ALOGI("NullQnnRuntime::init (no QNN libs; dev fallback)");
        return true;
    }
    void feedPcm(const int16_t*, size_t) override {}
    void setCallback(QnnDetectionCallback cb) override { cb_ = std::move(cb); }
    void reset() override {}
    void shutdown() override {}
    bool onHexagon() const override { return false; }
private:
    QnnDetectionCallback cb_;
};

// -----------------------------------------------------------------------------
// HTP impl
// -----------------------------------------------------------------------------
class QnnHtpRuntimeImpl : public IQnnRuntime {
public:
    ~QnnHtpRuntimeImpl() override { shutdown(); }

    bool init(const std::string& ctxBinPath) override {
        std::lock_guard<std::mutex> lk(mu_);
        if (initialized_) return true;

        if (!resolveProviders()) {
            shutdown_unlocked();
            return false;
        }

        // Create logger (optional) + backend + device. We skip the logger -- QNN
        // is happy with nullptr and we already have Android log for errors.
        if (api_.core->backendCreate(/*log=*/nullptr, /*cfg=*/nullptr, &backend_)
                != QNN_SUCCESS || !backend_) {
            ALOGE("QnnBackend_create failed");
            shutdown_unlocked();
            return false;
        }
        if (api_.core->deviceCreate(/*log=*/nullptr, /*cfg=*/nullptr, &device_)
                != QNN_SUCCESS || !device_) {
            ALOGE("QnnDevice_create failed");
            shutdown_unlocked();
            return false;
        }

        // Resolve ctxBinPath to a directory with per-stage .bin files. We do
        // not support the single-binary legacy path here any more -- the
        // compile toolchain always produces per-stage artifacts now.
        struct stat st{};
        std::string stageDir;
        if (::stat(ctxBinPath.c_str(), &st) == 0 && S_ISDIR(st.st_mode)) {
            stageDir = ctxBinPath;
            if (!stageDir.empty() && stageDir.back() == '/') stageDir.pop_back();
        } else {
            ALOGE("init: '%s' is not a directory of stage binaries",
                  ctxBinPath.c_str());
            shutdown_unlocked();
            return false;
        }

        auto loadStage = [&](const char* stage, std::unique_ptr<QnnGraph>& dest) -> bool {
            std::string p = stageDir + "/" + stage + ".bin";
            if (::stat(p.c_str(), &st) != 0) {
                ALOGW("stage %s absent at %s", stage, p.c_str());
                return false;
            }
            dest.reset(new QnnGraph());
            if (!dest->load(api_, backend_, device_, p, stage)) {
                ALOGE("stage %s load failed (%s)", stage, p.c_str());
                dest.reset();
                return false;
            }
            return true;
        };

        bool okMel = loadStage(kStageMel, mel_);
        bool okEmb = loadStage(kStageEmb, emb_);
        bool okCls = loadStage(kStageCls, cls_);
        if (!(okMel && okEmb && okCls)) {
            ALOGE("init: required pipeline stages missing (mel=%d emb=%d cls=%d)",
                  (int)okMel, (int)okEmb, (int)okCls);
            shutdown_unlocked();
            return false;
        }

        // Validate tensor shapes match what we'll feed. If these asserts fire,
        // the model was compiled with a layout we don't know how to drive.
        if (mel_->numInputs() < 1 || mel_->inputElements(0) != kChunkSize) {
            ALOGE("mel: input[0] elemCount=%zu != kChunkSize=%zu",
                  mel_->inputElements(0), kChunkSize);
            shutdown_unlocked();
            return false;
        }
        // mel output is either [1,1,N,32] or [1,N,32,1]; we only need total.
        if (mel_->numOutputs() < 1 || mel_->outputElements(0) % kMelBins != 0) {
            ALOGE("mel: output[0] elemCount=%zu not multiple of %zu",
                  mel_->outputElements(0), kMelBins);
            shutdown_unlocked();
            return false;
        }
        melFramesPerChunk_ = mel_->outputElements(0) / kMelBins;
        if (emb_->numInputs() < 1 || emb_->inputElements(0) != kMelWindow * kMelBins) {
            ALOGE("emb: input[0] elemCount=%zu != kMelWindow*kMelBins=%zu",
                  emb_->inputElements(0), kMelWindow * kMelBins);
            shutdown_unlocked();
            return false;
        }
        if (emb_->numOutputs() < 1 || emb_->outputElements(0) != kEmbeddingDim) {
            ALOGE("emb: output[0] elemCount=%zu != kEmbeddingDim=%zu",
                  emb_->outputElements(0), kEmbeddingDim);
            shutdown_unlocked();
            return false;
        }
        if (cls_->numInputs() < 1 ||
            cls_->inputElements(0) != kNumEmbFrames * kEmbeddingDim) {
            ALOGE("cls: input[0] elemCount=%zu != N_FRAMES*EMB_DIM=%zu",
                  cls_->inputElements(0), kNumEmbFrames * kEmbeddingDim);
            shutdown_unlocked();
            return false;
        }
        if (cls_->numOutputs() < 1 || cls_->outputElements(0) == 0) {
            ALOGE("cls: output[0] empty");
            shutdown_unlocked();
            return false;
        }

        ALOGI("QnnHtpRuntime init OK (mel %zu->%zux%zu, emb %zux%zu->%zu, cls %zux%zu->%zu)",
              kChunkSize, melFramesPerChunk_, kMelBins,
              kMelWindow, kMelBins, kEmbeddingDim,
              kNumEmbFrames, kEmbeddingDim, cls_->outputElements(0));

        onHtp_       = true;
        initialized_ = true;
        return true;
    }

    void feedPcm(const int16_t* pcm, size_t frames) override {
        std::lock_guard<std::mutex> lk(mu_);
        if (!initialized_ || !mel_ || !emb_ || !cls_) return;
        if (execErrors_ >= kMaxExecErrors) return; // stop after too many failures

        // Latch into accumulator and process one 80 ms chunk at a time.
        size_t srcPos = 0;
        while (srcPos < frames) {
            size_t room = kChunkSize - accumPos_;
            size_t toCopy = std::min(frames - srcPos, room);
            for (size_t i = 0; i < toCopy; ++i) {
                accum_[accumPos_ + i] = pcm[srcPos + i];
            }
            accumPos_ += toCopy;
            srcPos    += toCopy;
            if (accumPos_ < kChunkSize) return;
            accumPos_ = 0;
            processChunk();
        }
    }

    void setCallback(QnnDetectionCallback cb) override {
        std::lock_guard<std::mutex> lk(mu_);
        cb_ = std::move(cb);
    }

    void reset() override {
        std::lock_guard<std::mutex> lk(mu_);
        accumPos_ = 0;
        melBuffer_.clear();
        embBuffer_.clear();
        lastScore_ = 0.0f;
        execErrors_ = 0;
        lastFireNs_.store(0, std::memory_order_relaxed);
        sigLogged_.store(false, std::memory_order_relaxed);
    }

    void shutdown() override {
        std::lock_guard<std::mutex> lk(mu_);
        shutdown_unlocked();
    }

    bool onHexagon() const override { return onHtp_.load(); }

private:
    // Max per-frame execution errors to tolerate before giving up for the
    // remainder of this session. Avoids log-spam + runaway CPU if the
    // backend is in a bad state.
    static constexpr size_t kMaxExecErrors = 8;

    // Resolve libQnnSystem.so + libQnnHtp.so and their function tables.
    // QNN_INTERFACE_VER_NAME resolves (per SDK 2.34) to v2_34 (core) /
    // v1_7 (system). We pick the first provider in each list whose api
    // version is >= 2.x (core) / 1.x (system).
    bool resolveProviders() {
        hSystem_ = dlopen("libQnnSystem.so", RTLD_NOW | RTLD_LOCAL);
        if (!hSystem_) { ALOGW("dlopen libQnnSystem.so failed: %s", dlerror()); return false; }
        hHtp_    = dlopen("libQnnHtp.so",    RTLD_NOW | RTLD_LOCAL);
        if (!hHtp_)    { ALOGW("dlopen libQnnHtp.so failed: %s", dlerror());    return false; }

        auto sysFn  = reinterpret_cast<FnSystemGetProviders>(
            dlsym(hSystem_, "QnnSystemInterface_getProviders"));
        auto coreFn = reinterpret_cast<FnCoreGetProviders>(
            dlsym(hHtp_, "QnnInterface_getProviders"));
        if (!sysFn || !coreFn) {
            ALOGE("dlsym getProviders failed (sys=%p core=%p)", (void*)sysFn, (void*)coreFn);
            return false;
        }

        const QnnSystemInterface_t** sysList = nullptr;
        uint32_t nSys = 0;
        if (sysFn(&sysList, &nSys) != QNN_SUCCESS || nSys == 0 || !sysList) {
            ALOGE("QnnSystemInterface_getProviders returned no providers");
            return false;
        }
        const QnnInterface_t** coreList = nullptr;
        uint32_t nCore = 0;
        if (coreFn(&coreList, &nCore) != QNN_SUCCESS || nCore == 0 || !coreList) {
            ALOGE("QnnInterface_getProviders returned no providers");
            return false;
        }

        // Use provider[0] -- conforms to SDK convention for single-backend libs.
        api_.system = &sysList[0]->QNN_SYSTEM_INTERFACE_VER_NAME;
        api_.core   = &coreList[0]->QNN_INTERFACE_VER_NAME;
        ALOGI("QNN providers: sys=%u core=%u (sys name='%s' core name='%s')",
              nSys, nCore,
              sysList[0]->providerName ? sysList[0]->providerName : "",
              coreList[0]->providerName ? coreList[0]->providerName : "");
        return true;
    }

    // Execute the three-stage pipeline on one 1280-sample PCM chunk sitting
    // in accum_. Mutates melBuffer_ / embBuffer_ / lastScore_ and fires cb_
    // on crossing kDetectionThreshold.
    //
    // Mirrors processChunk() in OpenWakeWordEngine.kt. Every mel chunk feeds
    // the embedding stage (mel-version gating in the phone is an ARM-side
    // cache; on HTP the execute cost is low enough we skip it).
    void processChunk() {
        // 1) mel: PCM -> float frames. Normalize to [-1,1] just like
        // OpenWakeWordEngine.runMelspectrogram :206.
        float* melIn = mel_->inputData(0);
        if (!melIn) { ++execErrors_; return; }
        for (size_t i = 0; i < kChunkSize; ++i) {
            melIn[i] = static_cast<float>(accum_[i]) / 32768.0f;
        }
        if (!mel_->execute()) {
            if (execErrors_ == 0) ALOGE("QnnGraph_execute(mel) failed");
            ++execErrors_;
            return;
        }

        // 2) append mel frames to the ring buffer. The phone post-processes
        // each mel value as `v/10 + 2` before feeding the embedding stage
        // (OpenWakeWordEngine.kt :215). Apply the same affine here.
        const float* melOut   = mel_->outputData(0);
        const size_t nFrames  = melFramesPerChunk_;
        for (size_t f = 0; f < nFrames; ++f) {
            std::array<float, kMelBins> frame{};
            for (size_t j = 0; j < kMelBins; ++j) {
                frame[j] = melOut[f * kMelBins + j] / 10.0f + 2.0f;
            }
            melBuffer_.push_back(frame);
        }
        while (melBuffer_.size() > kMaxMelBuffer) melBuffer_.pop_front();
        if (melBuffer_.size() < kMelWindow) return;

        // 3) embedding: last kMelWindow frames -> kEmbeddingDim floats.
        // Input layout expected by the compiled graph is the phone's
        // [1, MEL_WINDOW, MEL_BINS, 1] row-major (see
        // OpenWakeWordEngine.kt :228) which matches a plain flat copy.
        float* embIn = emb_->inputData(0);
        if (!embIn) { ++execErrors_; return; }
        const size_t start = melBuffer_.size() - kMelWindow;
        for (size_t i = 0; i < kMelWindow; ++i) {
            const auto& src = melBuffer_[start + i];
            std::memcpy(embIn + i * kMelBins, src.data(), kMelBins * sizeof(float));
        }
        if (!emb_->execute()) {
            if (execErrors_ == 0) ALOGE("QnnGraph_execute(emb) failed");
            ++execErrors_;
            return;
        }
        std::array<float, kEmbeddingDim> embOut{};
        std::memcpy(embOut.data(), emb_->outputData(0),
                    kEmbeddingDim * sizeof(float));
        embBuffer_.push_back(embOut);
        while (embBuffer_.size() > kNumEmbFrames) embBuffer_.pop_front();
        if (embBuffer_.size() < kNumEmbFrames) return;

        // 4) classifier: N_FRAMES x EMB_DIM -> 1 sigmoid.
        float* clsIn = cls_->inputData(0);
        if (!clsIn) { ++execErrors_; return; }
        for (size_t i = 0; i < kNumEmbFrames; ++i) {
            std::memcpy(clsIn + i * kEmbeddingDim, embBuffer_[i].data(),
                        kEmbeddingDim * sizeof(float));
        }
        if (!cls_->execute()) {
            if (execErrors_ == 0) ALOGE("QnnGraph_execute(cls) failed");
            ++execErrors_;
            return;
        }
        const float rawScore = cls_->outputData(0)[0];
        float score;
        if (rawScore < 0.0f || rawScore > 1.0f) {
            // Upstream sigmoid folded away; apply it locally. Log once so a
            // silent model-shape change is visible in logcat.
            score = sig(rawScore);
            bool expected = false;
            if (sigLogged_.compare_exchange_strong(
                    expected, true, std::memory_order_relaxed)) {
                ALOGI("cls: raw score %.4f outside [0,1]; applying local sigmoid -> %.4f",
                      rawScore, score);
            }
        } else {
            score = rawScore;
        }
        lastScore_ = score;
        if (score > kDetectionThreshold) {
            // HAL-side cooldown. Mirrors phone WAKE_COOLDOWN_MS=1500. Without
            // this, consecutive 80 ms chunks above threshold each fire a
            // detection event and the phone sees rapid-fire wake-word bursts.
            const int64_t now  = monotonic_ns();
            const int64_t prev = lastFireNs_.load(std::memory_order_relaxed);
            if (now - prev < kCooldownNs) return;
            lastFireNs_.store(now, std::memory_order_relaxed);
            dispatchDetection(score);
        }
    }

    void dispatchDetection(float score) {
        if (!cb_) return;
        QnnDetection d{};
        d.confidence = score;
        // Frames backing the final classifier inference window:
        //   kNumEmbFrames embeddings, each produced from 80 ms of new PCM
        //   (the embedding's 76-frame mel window slides in 80 ms steps).
        d.pcm_frames = static_cast<uint32_t>(kNumEmbFrames * kChunkSize);
        cb_(d);
    }

    void shutdown_unlocked() {
        mel_.reset();
        emb_.reset();
        cls_.reset();
        if (device_ && api_.core && api_.core->deviceFree) {
            api_.core->deviceFree(device_);
        }
        device_ = nullptr;
        if (backend_ && api_.core && api_.core->backendFree) {
            api_.core->backendFree(backend_);
        }
        backend_ = nullptr;
        api_.core   = nullptr;
        api_.system = nullptr;
        if (hHtp_)    { dlclose(hHtp_);    hHtp_    = nullptr; }
        if (hSystem_) { dlclose(hSystem_); hSystem_ = nullptr; }
        initialized_ = false;
        onHtp_       = false;
        accumPos_    = 0;
        melBuffer_.clear();
        embBuffer_.clear();
        execErrors_  = 0;
        lastFireNs_.store(0, std::memory_order_relaxed);
        sigLogged_.store(false, std::memory_order_relaxed);
    }

    std::mutex mu_;
    bool       initialized_ = false;
    std::atomic<bool> onHtp_{false};

    void* hSystem_ = nullptr;
    void* hHtp_    = nullptr;

    QnnApi               api_{};
    Qnn_BackendHandle_t  backend_ = nullptr;
    Qnn_DeviceHandle_t   device_  = nullptr;

    std::unique_ptr<QnnGraph> mel_;
    std::unique_ptr<QnnGraph> emb_;
    std::unique_ptr<QnnGraph> cls_;

    // Pipeline state
    size_t melFramesPerChunk_ = 0;           // derived from mel output shape

    std::array<int16_t, kChunkSize> accum_{};
    size_t accumPos_ = 0;

    std::deque<std::array<float, kMelBins>>      melBuffer_;
    std::deque<std::array<float, kEmbeddingDim>> embBuffer_;
    float  lastScore_  = 0.0f;
    size_t execErrors_ = 0;

    // HAL-side detection cooldown. monotonic_ns timestamp of the last fire;
    // 0 means "never fired". Mirrors phone WAKE_COOLDOWN_MS so we don't emit
    // bursts of consecutive detections from the 80 ms chunk cadence.
    std::atomic<int64_t> lastFireNs_{0};
    // One-shot flag: has the local sigmoid fallback been taken this session?
    // Logged at INFO via compare_exchange_strong on first flip so a silent
    // model-shape change is visible without per-chunk spam.
    std::atomic<bool>    sigLogged_{false};

    QnnDetectionCallback cb_;
};

} // namespace

std::unique_ptr<IQnnRuntime> makeQnnRuntime() {
    // Probe: can we dlopen both libs? If yes, go HTP; if no, Null.
    void* h1 = dlopen("libQnnSystem.so", RTLD_NOW | RTLD_LOCAL);
    void* h2 = dlopen("libQnnHtp.so",    RTLD_NOW | RTLD_LOCAL);
    if (h1 && h2) {
        dlclose(h1); dlclose(h2);
        ALOGI("makeQnnRuntime: using HTP backend");
        return std::unique_ptr<IQnnRuntime>(new QnnHtpRuntimeImpl());
    }
    if (h1) dlclose(h1);
    if (h2) dlclose(h2);
    ALOGW("makeQnnRuntime: QNN libs absent; using Null runtime");
    return std::unique_ptr<IQnnRuntime>(new NullQnnRuntimeImpl());
}

} // namespace sthal
