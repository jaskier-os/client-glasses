// sthal/src/mic_reader.cpp
//
// ============================================================================
// STATUS: DISABLED AT THE CALLER (Phase 3 ship point). See mic_reader.h for
// the full explanation. Short version: sound_trigger_hw.cpp no longer calls
// open()/start() because tinyalsa direct-PCM access conflicts with the
// vendor PAL that owns the Rokid Neo DSP audio path. This file stays intact
// as future work; the replacement will keep the same public MicReader
// interface but back it with PalStream via libpal_client.so.
// ============================================================================
//
// Real (non-stub) implementation of MicReader using vendored tinyalsa v2.0.0
// (see sthal/third_party/tinyalsa). Opens a capture PCM with the caller's
// (card, device, rate, channels), configures one period of ~20 ms and 4
// periods of driver-side buffering, then spins a dedicated thread that
// pcm_readi()s one period at a time and hands it to the supplied
// MicFrameCallback.
//
// Underrun recovery: pcm_readi() returns a negative error on xrun; we call
// pcm_prepare() and retry. After kMaxReadFailuresInARow consecutive errors
// we log and stop the thread -- caller may reopen.
//
// Channel handling: capture devices on the LPASS codec are frequently
// multi-channel by default. If `channels` > 1 we pull a full multi-channel
// frame but deliver only the first channel to the callback (via a staging
// buffer). Enough for wake-word models that expect mono S16_LE.

#include "mic_reader.h"

#include <cerrno>
#include <cstring>
#include <vector>

#include <android/log.h>

#include <tinyalsa/pcm.h>

#include "st_trace.h"

#define LOG_TAG "sthal-mic"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace sthal {

MicReader::MicReader() = default;

MicReader::~MicReader() {
    stop();
    close();
}

bool MicReader::open(unsigned int card,
                     unsigned int device,
                     unsigned int sampleRateHz,
                     unsigned int channels) {
    ST_TRACE("st.mic.open");
    if (pcm_) {
        ALOGW("open(card=%u dev=%u) ignored: already open", card, device);
        return true;
    }
    if (!sampleRateHz || !channels) {
        ALOGE("open: invalid rate=%u ch=%u", sampleRateHz, channels);
        return false;
    }

    // One period = kPeriodMs ms at the requested rate. 20 ms @ 16 kHz = 320.
    const unsigned int periodFrames =
        (sampleRateHz * kPeriodMs + 999u) / 1000u;

    struct pcm_config cfg;
    std::memset(&cfg, 0, sizeof(cfg));
    cfg.channels          = channels;
    cfg.rate              = sampleRateHz;
    cfg.period_size       = periodFrames;
    cfg.period_count      = kPeriodCount;
    cfg.format            = PCM_FORMAT_S16_LE;
    cfg.start_threshold   = 0; // start on first read
    cfg.stop_threshold    = 0; // driver default
    cfg.silence_threshold = 0;

    const unsigned int flags = PCM_IN | PCM_MONOTONIC;
    struct pcm* p = pcm_open(card, device, flags, &cfg);
    if (!p) {
        ALOGE("pcm_open(card=%u dev=%u) returned nullptr", card, device);
        return false;
    }
    if (!pcm_is_ready(p)) {
        const char* err = pcm_get_error(p);
        ALOGE("pcm_is_ready(card=%u dev=%u) == false: %s",
              card, device, err ? err : "unknown");
        pcm_close(p);
        return false;
    }

    pcm_           = p;
    sampleRateHz_  = sampleRateHz;
    channels_      = channels;
    periodFrames_  = periodFrames;

    ALOGI("opened card=%u dev=%u rate=%u ch=%u period=%zu frames (%u ms) x%u",
          card, device, sampleRateHz, channels, periodFrames_, kPeriodMs,
          kPeriodCount);
    return true;
}

void MicReader::close() {
    ST_TRACE("st.mic.close");
    if (!pcm_) return;
    if (running_.load()) {
        ALOGW("close() called while running; forcing stop()");
        stop();
    }
    pcm_close(pcm_);
    pcm_           = nullptr;
    sampleRateHz_  = 0;
    channels_      = 0;
    periodFrames_  = 0;
    ALOGI("closed");
}

bool MicReader::start(MicFrameCallback cb) {
    ST_TRACE("st.mic.start");
    if (!pcm_) {
        ALOGE("start(): not open");
        return false;
    }
    if (running_.exchange(true)) {
        ALOGW("start(): already running");
        return true;
    }
    cb_ = std::move(cb);
    thread_ = std::make_unique<std::thread>(&MicReader::readLoop, this);
    ALOGI("start(): capture thread launched");
    return true;
}

void MicReader::stop() {
    ST_TRACE("st.mic.stop");
    if (!running_.exchange(false)) return;
    if (thread_ && thread_->joinable()) {
        thread_->join();
    }
    thread_.reset();
    cb_ = nullptr;
    errCb_ = nullptr;
    ALOGI("stop(): capture thread joined");
}

void MicReader::readLoop() {
    ST_TRACE("st.mic.read_loop");
    // Full (possibly multi-channel) interleaved frame buffer, sized to one
    // period.
    const size_t interleavedSamples = periodFrames_ * channels_;
    std::vector<int16_t> frame(interleavedSamples);

    // Mono-downmix staging buffer (picks channel 0). Only used if channels>1.
    std::vector<int16_t> mono;
    if (channels_ > 1) mono.resize(periodFrames_);

    unsigned int failCount = 0;

    while (running_.load()) {
        int rc;
        {
            ST_TRACE("st.mic.pcm_readi");
            rc = pcm_readi(pcm_, frame.data(), periodFrames_);
        }
        if (rc < 0 || static_cast<unsigned int>(rc) != periodFrames_) {
            ++failCount;
            // Capture errno alongside pcm_get_error() so triage can tell
            // ENODEV / EBUSY / EBADFD apart without a second logcat pass.
            const int savedErrno = errno;
            const char* err = pcm_get_error(pcm_);
            ALOGW("pcm_readi rc=%d (want %zu frames): %s errno=%d (%s) (fail %u/%u)",
                  rc, periodFrames_, err ? err : "?",
                  savedErrno, std::strerror(savedErrno),
                  failCount, kMaxReadFailuresInARow);
            // pcm_prepare() re-arms after xrun; cheap to call regardless.
            pcm_prepare(pcm_);
            if (failCount >= kMaxReadFailuresInARow) {
                ALOGE("giving up after %u consecutive read failures (errno=%d %s)",
                      failCount, savedErrno, std::strerror(savedErrno));
                running_.store(false);
                // Terminal failure -- notify the owner (HAL) so it can surface
                // a RECOGNITION_STATUS_FAILURE event to the framework. Invoke
                // BEFORE break so the callback runs from the same dying
                // thread while state is still coherent. Exceptions are
                // disabled for the HAL (-fno-exceptions) so the callback must
                // be exception-free; callers wiring this are expected to
                // honour that.
                if (errCb_) errCb_();
                break;
            }
            continue;
        }
        failCount = 0;

        const int16_t* deliverPtr;
        const size_t   deliverFrames = periodFrames_;
        if (channels_ > 1) {
            for (size_t i = 0; i < periodFrames_; ++i) {
                mono[i] = frame[i * channels_];
            }
            deliverPtr = mono.data();
        } else {
            deliverPtr = frame.data();
        }

        if (cb_) {
            ST_TRACE("st.mic.frame_cb");
            cb_(deliverPtr, deliverFrames);
        }
    }
}

} // namespace sthal
