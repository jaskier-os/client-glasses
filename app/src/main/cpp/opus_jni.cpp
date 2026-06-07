// opus_jni.cpp -- native Opus encoder JNI wrapper for the glasses listener.
//
// Bridges libopus (vendored at cpp/opus/) into Kotlin's OpusEncoder class.
// Fixed configuration matching the existing wire format the phone already
// decodes frame-at-a-time:
//
//   16 kHz mono, 16 kbps CBR, 20 ms frames (320 samples / 640 PCM bytes in,
//   one Opus packet out). The 2-byte little-endian length prefix is prepended
//   by the Kotlin caller, NOT here -- this layer returns just the raw Opus
//   bytes for one frame.
//
// Thread safety: an encoder handle is single-threaded by construction. Callers
// must not share a handle across threads; the native side does NOT lock.
//
// Error model: all three entry points return negative / 0 on error and log a
// single line via __android_log_print under tag "opus_jni".

#include <jni.h>
#include <android/log.h>
#include <opus.h>
#include <cstring>
#include <new>

#define LOG_TAG "opus_jni"
#define OPUS_COMPLEXITY 1
#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, fmt, ##__VA_ARGS__)
#define LOGW(fmt, ...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, fmt, ##__VA_ARGS__)
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, fmt, ##__VA_ARGS__)

namespace {

// Opaque handle returned to the JVM as a jlong (pointer-sized integer).
// We own the OpusEncoder* and free it in nativeDestroy.
struct EncoderHandle {
    OpusEncoder* enc;
    int sample_rate;
    int channels;
};

inline jlong toHandle(EncoderHandle* h) {
    return reinterpret_cast<jlong>(h);
}

inline EncoderHandle* fromHandle(jlong h) {
    return reinterpret_cast<EncoderHandle*>(h);
}

} // namespace

extern "C" {

/*
 * Class:     com_repository_glasses_listener_audio_OpusEncoder
 * Method:    nativeCreate
 * Signature: (III)J
 *
 * Returns an opaque handle (>0) on success, 0 on failure.
 */
JNIEXPORT jlong JNICALL
Java_com_repository_glasses_listener_audio_OpusEncoder_nativeCreate(
        JNIEnv* /*env*/,
        jclass  /*clazz*/,
        jint sampleRateHz,
        jint channels,
        jint bitrateBps) {

    if (sampleRateHz != 8000 && sampleRateHz != 12000 &&
        sampleRateHz != 16000 && sampleRateHz != 24000 &&
        sampleRateHz != 48000) {
        LOGE("nativeCreate: unsupported sampleRate=%d (libopus accepts 8/12/16/24/48 kHz)",
             sampleRateHz);
        return 0;
    }
    if (channels != 1 && channels != 2) {
        LOGE("nativeCreate: unsupported channels=%d (must be 1 or 2)", channels);
        return 0;
    }

    int err = OPUS_OK;
    OpusEncoder* enc = opus_encoder_create(sampleRateHz,
                                           channels,
                                           OPUS_APPLICATION_VOIP,
                                           &err);
    if (enc == nullptr || err != OPUS_OK) {
        LOGE("nativeCreate: opus_encoder_create failed: %s", opus_strerror(err));
        if (enc != nullptr) {
            opus_encoder_destroy(enc);
        }
        return 0;
    }

    // Tune for low-bitrate always-on voice capture.
    err = opus_encoder_ctl(enc, OPUS_SET_BITRATE(bitrateBps));
    if (err != OPUS_OK) {
        LOGW("nativeCreate: OPUS_SET_BITRATE(%d) failed: %s", bitrateBps, opus_strerror(err));
    }
    err = opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(OPUS_COMPLEXITY));
    if (err != OPUS_OK) {
        LOGW("nativeCreate: OPUS_SET_COMPLEXITY(%d) failed: %s", OPUS_COMPLEXITY, opus_strerror(err));
    }
    err = opus_encoder_ctl(enc, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    if (err != OPUS_OK) {
        LOGW("nativeCreate: OPUS_SET_SIGNAL(VOICE) failed: %s", opus_strerror(err));
    }
    err = opus_encoder_ctl(enc, OPUS_SET_INBAND_FEC(0));
    if (err != OPUS_OK) {
        LOGW("nativeCreate: OPUS_SET_INBAND_FEC(0) failed: %s", opus_strerror(err));
    }
    err = opus_encoder_ctl(enc, OPUS_SET_VBR(0)); // hard CBR at the chosen bitrate
    if (err != OPUS_OK) {
        LOGW("nativeCreate: OPUS_SET_VBR(0) failed: %s", opus_strerror(err));
    }
    err = opus_encoder_ctl(enc, OPUS_SET_DTX(1));
    if (err != OPUS_OK) {
        LOGW("nativeCreate: OPUS_SET_DTX(1) failed: %s", opus_strerror(err));
    }

    EncoderHandle* h = new (std::nothrow) EncoderHandle();
    if (h == nullptr) {
        LOGE("nativeCreate: out of memory allocating EncoderHandle");
        opus_encoder_destroy(enc);
        return 0;
    }
    h->enc = enc;
    h->sample_rate = sampleRateHz;
    h->channels = channels;

    LOGI("nativeCreate: ok (rate=%d ch=%d bitrate=%d CBR VOIP complexity=%d DTX=1)",
         sampleRateHz, channels, bitrateBps, OPUS_COMPLEXITY);
    return toHandle(h);
}

/*
 * Class:     com_repository_glasses_listener_audio_OpusEncoder
 * Method:    nativeEncodeFrame
 * Signature: (J[SII[BI)I
 *
 * Encodes exactly one frame of PCM (pcmFrames samples per channel).
 * Returns bytes written to `out` starting at `outOffset`, or a negative code
 * on error:
 *     -1 = bad handle
 *     -2 = JNI array access failed
 *     -3 = libopus returned negative (error logged)
 *     -4 = PCM range invalid
 *     -5 = output buffer too small
 */
JNIEXPORT jint JNICALL
Java_com_repository_glasses_listener_audio_OpusEncoder_nativeEncodeFrame(
        JNIEnv*   env,
        jclass    /*clazz*/,
        jlong     handle,
        jshortArray pcm,
        jint      pcmOffset,
        jint      pcmFrames,
        jbyteArray out,
        jint      outOffset) {

    EncoderHandle* h = fromHandle(handle);
    if (h == nullptr || h->enc == nullptr) {
        LOGE("nativeEncodeFrame: null handle");
        return -1;
    }

    if (pcm == nullptr || out == nullptr) {
        LOGE("nativeEncodeFrame: null array");
        return -2;
    }

    const jsize pcmLen = env->GetArrayLength(pcm);
    const jsize outLen = env->GetArrayLength(out);
    const jsize pcmSamplesNeeded = pcmFrames * h->channels;

    if (pcmOffset < 0 || pcmFrames <= 0 ||
        pcmOffset + pcmSamplesNeeded > pcmLen) {
        LOGE("nativeEncodeFrame: bad pcm range offset=%d frames=%d arrLen=%d ch=%d",
             (int)pcmOffset, (int)pcmFrames, (int)pcmLen, h->channels);
        return -4;
    }
    if (outOffset < 0 || outOffset >= outLen) {
        LOGE("nativeEncodeFrame: bad out range offset=%d arrLen=%d",
             (int)outOffset, (int)outLen);
        return -5;
    }

    const jsize outCapacity = outLen - outOffset;

    // Precheck output capacity up front, BEFORE pinning the PCM array or calling
    // opus_encode. Callers (Kotlin OpusEncoder) provide a 2048-byte scratch buffer,
    // so this branch should never trigger, but a caller-visible -5 is clearer than
    // catching the capacity violation after encoding.
    if (outCapacity < 1276) {
        LOGE("nativeEncodeFrame: output capacity %d < 1276 (RFC-6716 max)", (int)outCapacity);
        return -5;
    }

    // Pin the short array for the duration of opus_encode.
    jshort* pcmPtr = env->GetShortArrayElements(pcm, nullptr);
    if (pcmPtr == nullptr) {
        LOGE("nativeEncodeFrame: GetShortArrayElements failed");
        return -2;
    }

    // RFC 6716 hard max for an Opus packet is 1275 bytes (120 ms frame, 510 kbps).
    // We size the scratch to 1276 so SetByteArrayRegion only copies the real tail.
    // At our 20 ms / 16 kbps CBR config the actual payload is ~40 bytes.
    unsigned char tmp[1276];
    const opus_int32 maxBytes = (opus_int32)sizeof(tmp);

    opus_int32 written = opus_encode(h->enc,
                                     pcmPtr + pcmOffset,
                                     (int)pcmFrames,
                                     tmp,
                                     maxBytes);

    env->ReleaseShortArrayElements(pcm, pcmPtr, JNI_ABORT);

    if (written < 0) {
        LOGE("nativeEncodeFrame: opus_encode error %d: %s", (int)written, opus_strerror(written));
        return -3;
    }
    if (written == 0) {
        // DTX engaged: encoder chose not to transmit this frame (silence).
        // Return 0 so the Kotlin caller can skip emitting a record.
        return 0;
    }
    if ((jsize)written > outCapacity) {
        LOGE("nativeEncodeFrame: encoded %d bytes but output capacity only %d",
             (int)written, (int)outCapacity);
        return -5;
    }

    env->SetByteArrayRegion(out, outOffset, (jsize)written, reinterpret_cast<jbyte*>(tmp));
    return (jint)written;
}

/*
 * Class:     com_repository_glasses_listener_audio_OpusEncoder
 * Method:    nativeDestroy
 * Signature: (J)V
 */
JNIEXPORT void JNICALL
Java_com_repository_glasses_listener_audio_OpusEncoder_nativeDestroy(
        JNIEnv* /*env*/,
        jclass  /*clazz*/,
        jlong   handle) {
    EncoderHandle* h = fromHandle(handle);
    if (h == nullptr) {
        return;
    }
    if (h->enc != nullptr) {
        opus_encoder_destroy(h->enc);
        h->enc = nullptr;
    }
    delete h;
}

} // extern "C"
