// sthal/src/qnn_runtime.h
//
// Abstract QNN runtime interface + two concrete impls:
//   QnnHtpRuntime  — real impl, dlopen's libQnnHtp.so / libQnnSystem.so at runtime.
//   NullQnnRuntime — no-op, used when the QNN SDK libs aren't on the device.
//
// The HAL never direct-links QNN. On QnnHtpRuntime::init() failure it logs and
// returns false; the HAL then either falls back to NullQnnRuntime (dev-only) or
// refuses to load the model (production).

#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>

namespace sthal {

struct QnnDetection {
    float    confidence;   // 0..1 score from the wake-word graph
    uint32_t pcm_frames;   // # of 16 kHz mono frames backing this detection
};

using QnnDetectionCallback = std::function<void(const QnnDetection& d)>;

class IQnnRuntime {
public:
    virtual ~IQnnRuntime() = default;

    // Load a QNN context binary (.bin) and prepare graphs. Returns false on any
    // dlopen / QnnContext_createFromBinary failure. Safe to call repeatedly.
    //
    // If `contextBinaryPath` points at a directory, init() walks it for all
    // pipeline stages named in kPipelineGraphNames (melspectrogram.bin,
    // embedding_model.bin, sireneviy.bin, silero_vad.bin) and loads whichever
    // exist as separate contexts. Missing stages are tolerated (the HAL will
    // skip them at runtime).
    //
    // If it points at a regular file, loads it as a single context for
    // backwards compatibility with the pre-multi-graph wakeword.bin layout.
    virtual bool init(const std::string& contextBinaryPath) = 0;

    // Feed a frame of mic PCM (int16 mono 16 kHz, typ. 20 ms = 320 samples).
    // Implementation slides its internal window and, when a new detection fires,
    // invokes the callback set via setCallback().
    virtual void feedPcm(const int16_t* pcm, size_t frames) = 0;

    // Detection callback. Called on the QNN worker thread.
    virtual void setCallback(QnnDetectionCallback cb) = 0;

    // Reset internal state; keep the graph loaded.
    virtual void reset() = 0;

    // Release everything (context, device, backend, dlopen handles).
    virtual void shutdown() = 0;

    // Whether the graph is currently on HTP (true) or fell back to CPU / null (false).
    virtual bool onHexagon() const = 0;
};

// Real HTP-backed impl. Does not crash if QNN libs are missing — returns false
// from init(). Do not instantiate directly; use makeQnnRuntime().
class QnnHtpRuntime;

// Null impl for dev builds.
class NullQnnRuntime;

// Factory: picks HTP if libQnnHtp.so + libQnnSystem.so dlopen clean, else Null.
std::unique_ptr<IQnnRuntime> makeQnnRuntime();

} // namespace sthal
