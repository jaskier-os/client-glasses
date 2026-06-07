#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

/* Provide speex_alloc/speex_free that kiss_fft needs */
void *speex_alloc(int size) { return calloc(1, size); }
void speex_free(void *ptr) { free(ptr); }

#include "kiss_fft.h"
#include "kiss_fftr.h"

#define TAG "CoherenceAEC"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

typedef struct {
    int frame_size;
    int freq_bins;       /* frame_size/2 + 1 */
    kiss_fftr_cfg fft_forward;
    kiss_fftr_cfg fft_inverse;
    float *window;       /* Hann window */
    float *mic_windowed;
    float *echo_windowed;
    float *output;
    kiss_fft_cpx *mic_fft;
    kiss_fft_cpx *echo_fft;
    kiss_fft_cpx *out_fft;
    /* Running spectral estimates for coherence (exponential smoothing) */
    float *Smm;   /* mic auto-power */
    float *See;   /* echo auto-power */
    float *Sme_r; /* smoothed |cross-power|^2 (delay-invariant) */
    float alpha;  /* smoothing factor (0=no smoothing, 1=infinite memory) */
    float floor;  /* minimum gain to avoid musical noise */
} CoherenceCtx;

JNIEXPORT jlong JNICALL
Java_com_repository_glasses_listener_audio_SpeexEchoCanceller_nativeInit(
    JNIEnv *env, jobject thiz,
    jint sample_rate, jint frame_size, jint filter_length)
{
    CoherenceCtx *ctx = (CoherenceCtx *)calloc(1, sizeof(CoherenceCtx));
    if (!ctx) return 0;

    ctx->frame_size = frame_size;
    ctx->freq_bins = frame_size / 2 + 1;
    ctx->alpha = 0.85f;  /* smoothing: higher = smoother but slower adaptation */
    ctx->floor = 0.02f;  /* -34dB suppression floor */

    ctx->fft_forward = kiss_fftr_alloc(frame_size, 0, NULL, NULL);
    ctx->fft_inverse = kiss_fftr_alloc(frame_size, 1, NULL, NULL);
    if (!ctx->fft_forward || !ctx->fft_inverse) {
        LOGI("FFT alloc failed for frame_size=%d", frame_size);
        free(ctx);
        return 0;
    }

    ctx->window = (float *)calloc(frame_size, sizeof(float));
    ctx->mic_windowed = (float *)calloc(frame_size, sizeof(float));
    ctx->echo_windowed = (float *)calloc(frame_size, sizeof(float));
    ctx->output = (float *)calloc(frame_size, sizeof(float));
    ctx->mic_fft = (kiss_fft_cpx *)calloc(ctx->freq_bins, sizeof(kiss_fft_cpx));
    ctx->echo_fft = (kiss_fft_cpx *)calloc(ctx->freq_bins, sizeof(kiss_fft_cpx));
    ctx->out_fft = (kiss_fft_cpx *)calloc(ctx->freq_bins, sizeof(kiss_fft_cpx));
    ctx->Smm = (float *)calloc(ctx->freq_bins, sizeof(float));
    ctx->See = (float *)calloc(ctx->freq_bins, sizeof(float));
    ctx->Sme_r = (float *)calloc(ctx->freq_bins, sizeof(float));

    /* Hann window */
    for (int i = 0; i < frame_size; i++) {
        ctx->window[i] = 0.5f * (1.0f - cosf(2.0f * 3.14159265f * i / (frame_size - 1)));
    }

    LOGI("Coherence AEC init: frame=%d bins=%d alpha=%.2f floor=%.3f",
         frame_size, ctx->freq_bins, ctx->alpha, ctx->floor);
    return (jlong)(intptr_t)ctx;
}

JNIEXPORT void JNICALL
Java_com_repository_glasses_listener_audio_SpeexEchoCanceller_nativeProcess(
    JNIEnv *env, jobject thiz,
    jlong handle, jshortArray mic_arr, jshortArray echo_arr, jshortArray out_arr)
{
    CoherenceCtx *ctx = (CoherenceCtx *)(intptr_t)handle;
    if (!ctx) return;

    jshort *mic = (*env)->GetShortArrayElements(env, mic_arr, NULL);
    jshort *echo = (*env)->GetShortArrayElements(env, echo_arr, NULL);
    jshort *out = (*env)->GetShortArrayElements(env, out_arr, NULL);
    if (!mic || !echo || !out) goto cleanup;

    int N = ctx->frame_size;
    int bins = ctx->freq_bins;
    float alpha = ctx->alpha;

    /* No windowing -- rectangular window to preserve signal integrity
       (overlap-add would be needed for Hann, too complex for now) */
    for (int i = 0; i < N; i++) {
        ctx->mic_windowed[i] = (float)mic[i];
        ctx->echo_windowed[i] = (float)echo[i];
    }

    /* Forward FFT */
    kiss_fftr(ctx->fft_forward, ctx->mic_windowed, ctx->mic_fft);
    kiss_fftr(ctx->fft_forward, ctx->echo_windowed, ctx->echo_fft);

    /* Spectral subtraction: suppress mic bins where echo ref has energy.
       G(k) = max(1 - beta * |Echo(k)| / |Mic(k)|, floor)
       Voice passes because echo ref is low at voice frequencies when only mic picks them up. */
    float beta = 1.5f;  /* oversubtract slightly to ensure echo removal */
    for (int k = 0; k < bins; k++) {
        float mr = ctx->mic_fft[k].r;
        float mi = ctx->mic_fft[k].i;
        float er = ctx->echo_fft[k].r;
        float ei = ctx->echo_fft[k].i;

        float mic_mag = sqrtf(mr * mr + mi * mi);
        float echo_mag = sqrtf(er * er + ei * ei);

        /* Smooth echo magnitude estimate to avoid musical noise */
        ctx->See[k] = alpha * ctx->See[k] + (1.0f - alpha) * echo_mag;

        float gain;
        if (mic_mag > 1e-6f && ctx->See[k] > 1e-3f) {
            gain = 1.0f - beta * ctx->See[k] / mic_mag;
            if (gain < ctx->floor) gain = ctx->floor;
        } else {
            gain = 1.0f;
        }

        ctx->out_fft[k].r = mr * gain;
        ctx->out_fft[k].i = mi * gain;
    }

    /* Inverse FFT */
    kiss_fftri(ctx->fft_inverse, ctx->out_fft, ctx->output);

    /* Normalize IFFT output (kiss_fft doesn't normalize) */
    float norm = 1.0f / N;
    for (int i = 0; i < N; i++) {
        float sample = ctx->output[i] * norm;
        if (sample > 32767.0f) sample = 32767.0f;
        if (sample < -32768.0f) sample = -32768.0f;
        out[i] = (short)sample;
    }

    /* Log periodically */
    static int frame_count = 0;
    frame_count++;
    if (frame_count <= 5 || frame_count % 50 == 0) {
        LOGI("frame=%d beta=%.1f floor=%.3f", frame_count, beta, ctx->floor);
    }

cleanup:
    if (mic) (*env)->ReleaseShortArrayElements(env, mic_arr, mic, JNI_ABORT);
    if (echo) (*env)->ReleaseShortArrayElements(env, echo_arr, echo, JNI_ABORT);
    if (out) (*env)->ReleaseShortArrayElements(env, out_arr, out, 0);
}

JNIEXPORT void JNICALL
Java_com_repository_glasses_listener_audio_SpeexEchoCanceller_nativeDestroy(
    JNIEnv *env, jobject thiz, jlong handle)
{
    CoherenceCtx *ctx = (CoherenceCtx *)(intptr_t)handle;
    if (!ctx) return;

    if (ctx->fft_forward) kiss_fftr_free(ctx->fft_forward);
    if (ctx->fft_inverse) kiss_fftr_free(ctx->fft_inverse);
    free(ctx->window);
    free(ctx->mic_windowed);
    free(ctx->echo_windowed);
    free(ctx->output);
    free(ctx->mic_fft);
    free(ctx->echo_fft);
    free(ctx->out_fft);
    free(ctx->Smm);
    free(ctx->See);
    free(ctx->Sme_r);
    free(ctx);
    LOGI("Coherence AEC destroyed");
}
