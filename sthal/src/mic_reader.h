// sthal/src/mic_reader.h
//
// Low-power mic PCM source for the custom SoundTrigger HAL.
//
// ============================================================================
// STATUS: DISABLED AT THE CALLER (Phase 3 ship point).
//
// sound_trigger_hw.cpp::startRecognition no longer calls MicReader::open()
// or MicReader::start(). The tinyalsa direct-PCM path collides with the
// vendor PAL (proprietary Pulse Audio Library) which already owns the DSP
// capture graph on the Rokid Neo board, and the resulting contention
// segfaults the audio HIDL process.
//
// This file is NOT legacy -- it is future work. Do NOT delete. The PAL
// integration that will replace the open()/start() callers looks like:
//
//   1. dlopen /vendor/lib64/libpal_client.so (vendor-shipped, on device).
//   2. Include Qualcomm's proprietary PAL headers (not in this repo).
//   3. Replace the tinyalsa backend in this class with a PalStream handle
//      opened in PAL_STREAM_LOW_LATENCY mode with the SoundTrigger capture
//      profile; pal_stream_read() in readLoop() instead of pcm_readi().
//      Everything above the tinyalsa abstraction (callback shape, error
//      retry, periodFrames sizing) stays identical.
//   4. Re-enable the open()/start() calls in
//      sound_trigger_hw.cpp::startRecognition.
//
// Estimated effort: 1-2 days once the PAL header set is sourced.
// ============================================================================
//
// Target: 16 kHz mono S16_LE, 20 ms frames (320 samples / 640 bytes) read from
// the primary-mic capture PCM on the Rokid Neo audio card. A dedicated thread
// pulls one period at a time from tinyalsa and invokes the caller-supplied
// MicFrameCallback synchronously. The callback must not block for long -- it
// runs on the capture thread.
//
// open() configures the tinyalsa pcm. start() launches the capture thread.
// stop() requests shutdown and joins. close() tears the pcm down and is
// idempotent. The destructor calls stop()+close() so stack-scoped use is safe.
//
// Implementation links against a vendored copy of tinyalsa (see
// sthal/third_party/tinyalsa). No dlopen; the archive is statically linked
// into sound_trigger.primary.neo.so so we are independent of the vendor's
// /vendor/lib64/libtinyalsa.so ABI.

#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <thread>

struct pcm; // from tinyalsa/pcm.h -- forward-declared to keep this header light.

namespace sthal {

using MicFrameCallback = std::function<void(const int16_t* pcm, size_t frames)>;

// Invoked from the capture thread immediately before readLoop() returns after
// kMaxReadFailuresInARow consecutive tinyalsa errors. The callback runs on
// the capture thread -- don't block for long and don't call back into
// MicReader::stop() or ::close() directly, or you'll self-join.
using MicErrorCallback = std::function<void()>;

class MicReader {
public:
    MicReader();
    ~MicReader();

    MicReader(const MicReader&) = delete;
    MicReader& operator=(const MicReader&) = delete;

    // Open a tinyalsa PCM for capture with PCM_IN | PCM_MONOTONIC,
    // PCM_FORMAT_S16_LE, the supplied sample rate and channel count, a
    // period_size picked so one period is ~20 ms of audio, and period_count =
    // kPeriodCount. Returns true on success, false on failure (failure is
    // logged and the object is left closed for retry).
    bool open(unsigned int card,
              unsigned int device,
              unsigned int sampleRateHz,
              unsigned int channels);

    // Close the PCM. Idempotent. Must not be called while the read thread is
    // still running -- call stop() first.
    void close();

    bool isOpen() const { return pcm_ != nullptr; }

    // Launch the capture thread. Must be called after a successful open().
    // The callback receives one period worth of frames per invocation; the
    // pointer is valid only for the duration of the call (copy if retaining).
    // Returns false if not open or already running.
    bool start(MicFrameCallback cb);

    // Optional: set a terminal-failure callback. Fires exactly once per
    // capture session, from the capture thread, right before readLoop()
    // returns because kMaxReadFailuresInARow has been hit. Use this to
    // synthesise a framework RECOGNITION_STATUS_FAILURE event so the
    // SoundTrigger manager can decide whether to retry. No-op if null.
    void setErrorCallback(MicErrorCallback cb) { errCb_ = std::move(cb); }

    // Request the capture thread to stop and join. Idempotent; safe from any
    // thread. Does not close the PCM -- call close() separately (or let the
    // destructor do it).
    void stop();

    bool isRunning() const { return running_.load(); }

    // One period worth of frames (mono count, not samples * channels).
    // Populated by open(); 0 while closed.
    size_t periodFrames() const { return periodFrames_; }

private:
    void readLoop();

    // Opaque tinyalsa handle. nullptr means closed.
    struct pcm* pcm_ = nullptr;

    // Captured-at-open config used by the read loop.
    unsigned int sampleRateHz_ = 0;
    unsigned int channels_     = 0;
    size_t       periodFrames_ = 0;

    std::atomic<bool>            running_{false};
    std::unique_ptr<std::thread> thread_;
    MicFrameCallback             cb_;
    MicErrorCallback             errCb_;

    // Number of periods buffered in the ALSA driver. 4 * 20 ms = 80 ms of
    // headroom before underrun forces pcm_prepare().
    static constexpr unsigned int kPeriodCount = 4;

    // Period length in ms. 20 ms matches typical wake-word model framing.
    static constexpr unsigned int kPeriodMs    = 20;

    // Consecutive read failures tolerated before the loop gives up.
    static constexpr unsigned int kMaxReadFailuresInARow = 3;
};

} // namespace sthal
