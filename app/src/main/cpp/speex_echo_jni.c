#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <speex/speex_echo.h>
#include <speex/speex_preprocess.h>

#define TAG "SpeexAEC"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef struct {
    SpeexEchoState *echo_state;
    SpeexPreprocessState *preprocess;
    int frame_size;
    int sample_rate;
} AecContext;

JNIEXPORT jlong JNICALL
Java_com_repository_glasses_listener_audio_SpeexEchoCanceller_nativeInit(
    JNIEnv *env, jobject thiz,
    jint sample_rate, jint frame_size, jint filter_length)
{
    AecContext *ctx = (AecContext *)calloc(1, sizeof(AecContext));
    if (!ctx) {
        LOGE("Failed to allocate AecContext");
        return 0;
    }

    ctx->frame_size = frame_size;
    ctx->sample_rate = sample_rate;

    ctx->echo_state = speex_echo_state_init(frame_size, filter_length);
    if (!ctx->echo_state) {
        LOGE("speex_echo_state_init failed");
        free(ctx);
        return 0;
    }
    speex_echo_ctl(ctx->echo_state, SPEEX_ECHO_SET_SAMPLING_RATE, &sample_rate);

    /* Preprocessor with echo suppression only (no noise gate/AGC to avoid pumping) */
    ctx->preprocess = speex_preprocess_state_init(frame_size, sample_rate);
    if (ctx->preprocess) {
        speex_preprocess_ctl(ctx->preprocess, SPEEX_PREPROCESS_SET_ECHO_STATE, ctx->echo_state);
        /* Disable noise gate and AGC (these cause pumping artifacts) */
        int denoise = 0;
        speex_preprocess_ctl(ctx->preprocess, SPEEX_PREPROCESS_SET_DENOISE, &denoise);
        int agc = 0;
        speex_preprocess_ctl(ctx->preprocess, SPEEX_PREPROCESS_SET_AGC, &agc);
        /* Aggressive echo suppression */
        int echo_suppress = -60;
        speex_preprocess_ctl(ctx->preprocess, SPEEX_PREPROCESS_SET_ECHO_SUPPRESS, &echo_suppress);
        int echo_suppress_active = -30;
        speex_preprocess_ctl(ctx->preprocess, SPEEX_PREPROCESS_SET_ECHO_SUPPRESS_ACTIVE, &echo_suppress_active);
    }

    LOGI("AEC init: rate=%d frame=%d filter=%d", sample_rate, frame_size, filter_length);
    return (jlong)(intptr_t)ctx;
}

JNIEXPORT void JNICALL
Java_com_repository_glasses_listener_audio_SpeexEchoCanceller_nativeProcess(
    JNIEnv *env, jobject thiz,
    jlong handle, jshortArray mic_arr, jshortArray echo_arr, jshortArray out_arr)
{
    AecContext *ctx = (AecContext *)(intptr_t)handle;
    if (!ctx || !ctx->echo_state) return;

    jshort *mic = (*env)->GetShortArrayElements(env, mic_arr, NULL);
    jshort *echo = (*env)->GetShortArrayElements(env, echo_arr, NULL);
    jshort *out = (*env)->GetShortArrayElements(env, out_arr, NULL);

    if (mic && echo && out) {
        speex_echo_cancellation(ctx->echo_state, mic, echo, out);
        if (ctx->preprocess) {
            speex_preprocess_run(ctx->preprocess, out);
        }
        /* Verify AEC is computing: log if output differs from input */
        static int call_count = 0;
        call_count++;
        if (call_count <= 10 || call_count % 500 == 0) {
            int differs = 0;
            for (int i = 0; i < ctx->frame_size && i < 20; i++) {
                if (out[i] != mic[i]) differs++;
            }
            LOGI("AEC frame #%d: differs=%d/%d mic[0]=%d echo[0]=%d out[0]=%d",
                 call_count, differs, ctx->frame_size < 20 ? ctx->frame_size : 20,
                 mic[0], echo[0], out[0]);
        }
    }

    if (mic) (*env)->ReleaseShortArrayElements(env, mic_arr, mic, JNI_ABORT);
    if (echo) (*env)->ReleaseShortArrayElements(env, echo_arr, echo, JNI_ABORT);
    if (out) (*env)->ReleaseShortArrayElements(env, out_arr, out, 0);
}

JNIEXPORT void JNICALL
Java_com_repository_glasses_listener_audio_SpeexEchoCanceller_nativeDestroy(
    JNIEnv *env, jobject thiz, jlong handle)
{
    AecContext *ctx = (AecContext *)(intptr_t)handle;
    if (!ctx) return;

    if (ctx->preprocess) {
        speex_preprocess_state_destroy(ctx->preprocess);
    }
    if (ctx->echo_state) {
        speex_echo_state_destroy(ctx->echo_state);
    }
    free(ctx);
    LOGI("AEC destroyed");
}
