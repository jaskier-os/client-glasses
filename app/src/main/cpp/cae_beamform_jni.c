/*
 * cae_beamform_jni.c -- JNI wrapper for iFlytek CAE beamformer (libcae.so)
 *
 * Loads /system/lib64/libcae.so via dlopen/dlsym and exposes init/process/destroy
 * to Kotlin. The CAE engine takes 6-channel interleaved audio (4 mic + 2 ref,
 * channels [2..7] from the 8-ch AudioRecord) and produces beamformed mono output.
 *
 * API surface (from RE of libcae.so + working test_cae.c):
 *   vtn_api_init(&handle, params_struct)  -- create Pipeline + CAE engine
 *   CAEStart(pipeline)                    -- start engine before processing
 *   CAEAppendAudioData(pipeline, float*, frames) -- feed 6ch float audio
 *   CAERunStep(pipeline, out_ptrs, out_counts, result) -- get beamformed output
 *   vtn_api_destroy(handle)               -- cleanup
 */

#include <jni.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define TAG "CaeBeamformJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* CAE function signatures */
typedef int (*vtn_api_init_fn)(void** handle, void* params);
typedef int (*vtn_api_destroy_fn)(void* handle);
typedef int (*CAEAppendAudioData_fn)(void* h, float* data, int frames);
typedef int (*CAERunStep_fn)(void* h, void* out1, void* out2, void* result);
typedef int (*CAEStart_fn)(void* h);

/* vtn_api_init params struct (48 bytes) */
struct init_params {
    char* json_config;   /* +0  */
    long  json_len;      /* +8  */
    long  zero1;         /* +16 */
    long  zero2;         /* +24 */
    void* callback_fn;   /* +32 */
    void* callback_ctx;  /* +40 */
};

/* Dummy callback required by vtn_api_init */
static int dummy_cb(long* d, void* c) {
    (void)d; (void)c;
    return 0;
}

/* Processing constants */
#define EXT_CH      6      /* channels extracted from 8ch: [2,3,4,5,6,7] */
#define FRAMES      256    /* frames per CAE input block */
#define BEAM_IDX    1      /* beam 0 = AEC, beam 1 = beamformed */

/* State */
static void* lib_handle = NULL;
static void* api_handle = NULL;
static void* cae_engine = NULL;

static vtn_api_init_fn fn_api_init = NULL;
static vtn_api_destroy_fn fn_api_destroy = NULL;
static CAEAppendAudioData_fn fn_cae_append = NULL;
static CAERunStep_fn fn_cae_run = NULL;
static CAEStart_fn fn_cae_start = NULL;

static int initialized = 0;

/* Pre-allocated buffers for processing */
static float* float_buf = NULL;      /* FRAMES * EXT_CH floats */
static int16_t* out_ptrs_buf = NULL; /* output pointer storage */
static int16_t* out_counts_buf = NULL;
static int16_t result_buf[64];

JNIEXPORT jboolean JNICALL
Java_com_repository_glasses_listener_capture_TranslationFrontMicRecorder_nativeCaeInit(
    JNIEnv* env, jobject thiz, jstring workDir) {

    if (initialized) {
        LOGI("CAE already initialized");
        return JNI_TRUE;
    }

    /* dlopen libcae.so */
    lib_handle = dlopen("/system/lib64/libcae.so", RTLD_NOW);
    if (!lib_handle) {
        LOGE("dlopen /system/lib64/libcae.so failed: %s", dlerror());
        return JNI_FALSE;
    }

    /* Resolve symbols */
    fn_api_init = (vtn_api_init_fn)dlsym(lib_handle, "vtn_api_init");
    fn_api_destroy = (vtn_api_destroy_fn)dlsym(lib_handle, "vtn_api_destroy");
    fn_cae_append = (CAEAppendAudioData_fn)dlsym(lib_handle, "CAEAppendAudioData");
    fn_cae_run = (CAERunStep_fn)dlsym(lib_handle, "CAERunStep");
    fn_cae_start = (CAEStart_fn)dlsym(lib_handle, "CAEStart");

    if (!fn_api_init || !fn_cae_append || !fn_cae_run) {
        LOGE("dlsym failed: api_init=%p append=%p run=%p start=%p destroy=%p",
             fn_api_init, fn_cae_append, fn_cae_run, fn_cae_start, fn_api_destroy);
        dlclose(lib_handle);
        lib_handle = NULL;
        return JNI_FALSE;
    }

    /* Build JSON config */
    const char* dir = (*env)->GetStringUTFChars(env, workDir, NULL);
    char json[256];
    snprintf(json, sizeof(json),
             "{\"params\":{\"appid\":\"42029a9d\",\"sn\":\"glasses_cae\",\"work_dir\":\"%s\"}}", dir);
    (*env)->ReleaseStringUTFChars(env, workDir, dir);

    /* Init params */
    struct init_params ip;
    memset(&ip, 0, sizeof(ip));
    ip.json_config = json;
    ip.json_len = strlen(json);
    ip.callback_fn = (void*)dummy_cb;

    api_handle = NULL;
    int ret = fn_api_init(&api_handle, &ip);
    if (ret != 0 || !api_handle) {
        LOGE("vtn_api_init failed: ret=%d handle=%p", ret, api_handle);
        dlclose(lib_handle);
        lib_handle = NULL;
        return JNI_FALSE;
    }
    LOGI("vtn_api_init OK (ret=%d)", ret);

    /* Extract CAE engine handle from Pipeline internal state:
     * handle -> h[1] = Pipeline*, Pipeline+312 = CAE engine wrapper */
    long* h = (long*)api_handle;
    unsigned char* pipeline = (unsigned char*)(h[1]);
    cae_engine = *(void**)(pipeline + 312);
    LOGI("CAE engine handle: %p", cae_engine);

    /* Start engine */
    if (fn_cae_start) {
        ret = fn_cae_start(cae_engine);
        LOGI("CAEStart returned: %d", ret);
    }

    /* Allocate processing buffers */
    float_buf = (float*)malloc(FRAMES * EXT_CH * sizeof(float));
    out_ptrs_buf = (int16_t*)calloc(1024, sizeof(int16_t));
    out_counts_buf = (int16_t*)calloc(64, sizeof(int16_t));

    if (!float_buf || !out_ptrs_buf || !out_counts_buf) {
        LOGE("Buffer allocation failed");
        if (fn_api_destroy) fn_api_destroy(api_handle);
        dlclose(lib_handle);
        lib_handle = NULL;
        api_handle = NULL;
        cae_engine = NULL;
        free(float_buf); float_buf = NULL;
        free(out_ptrs_buf); out_ptrs_buf = NULL;
        free(out_counts_buf); out_counts_buf = NULL;
        return JNI_FALSE;
    }

    initialized = 1;
    LOGI("CAE beamformer initialized successfully");
    return JNI_TRUE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_repository_glasses_listener_capture_TranslationFrontMicRecorder_nativeCaeProcess(
    JNIEnv* env, jobject thiz, jbyteArray input6ch) {

    if (!initialized || !cae_engine) return NULL;

    jint input_len = (*env)->GetArrayLength(env, input6ch);
    int expected = FRAMES * EXT_CH * 2;  /* 256 * 6 * 2 = 3072 bytes */
    if (input_len != expected) {
        LOGE("Bad input size: %d (expected %d)", input_len, expected);
        return NULL;
    }

    /* Get input bytes */
    jbyte* in_bytes = (*env)->GetByteArrayElements(env, input6ch, NULL);
    int16_t* in_pcm = (int16_t*)in_bytes;

    /* Convert int16 interleaved 6ch to float */
    for (int f = 0; f < FRAMES; f++) {
        for (int c = 0; c < EXT_CH; c++) {
            float_buf[f * EXT_CH + c] = (float)in_pcm[f * EXT_CH + c];
        }
    }

    (*env)->ReleaseByteArrayElements(env, input6ch, in_bytes, JNI_ABORT);

    /* Feed to CAE */
    int ret = fn_cae_append(cae_engine, float_buf, FRAMES);
    if (ret != 0) return NULL;

    /* Run step */
    memset(out_ptrs_buf, 0, 1024 * 2);
    memset(out_counts_buf, 0, 64 * 2);
    memset(result_buf, 0, sizeof(result_buf));

    ret = fn_cae_run(cae_engine, out_ptrs_buf, out_counts_buf, result_buf);

    int nbeams = result_buf[0];
    if (nbeams <= 0 || BEAM_IDX >= nbeams) return NULL;

    long* ptrs = (long*)out_ptrs_buf;
    if (!ptrs[0]) return NULL;

    int16_t* audio = (int16_t*)ptrs[0];
    int nsamp = out_counts_buf[BEAM_IDX];

    /* Calculate offset: sum sample counts of all beams before BEAM_IDX */
    int offset = 0;
    for (int b = 0; b < BEAM_IDX; b++) offset += out_counts_buf[b];

    if (nsamp <= 0 || nsamp > 1024) return NULL;

    /* Return beam 1 samples as byte array (int16 PCM) */
    int out_bytes = nsamp * 2;
    jbyteArray result = (*env)->NewByteArray(env, out_bytes);
    if (!result) return NULL;

    (*env)->SetByteArrayRegion(env, result, 0, out_bytes, (jbyte*)(audio + offset));
    return result;
}

JNIEXPORT void JNICALL
Java_com_repository_glasses_listener_capture_TranslationFrontMicRecorder_nativeCaeDestroy(
    JNIEnv* env, jobject thiz) {

    if (fn_api_destroy && api_handle) {
        LOGI("vtn_api_destroy");
        fn_api_destroy(api_handle);
    }

    if (lib_handle) {
        dlclose(lib_handle);
    }

    free(float_buf);
    free(out_ptrs_buf);
    free(out_counts_buf);

    lib_handle = NULL;
    api_handle = NULL;
    cae_engine = NULL;
    fn_api_init = NULL;
    fn_api_destroy = NULL;
    fn_cae_append = NULL;
    fn_cae_run = NULL;
    fn_cae_start = NULL;
    float_buf = NULL;
    out_ptrs_buf = NULL;
    out_counts_buf = NULL;
    initialized = 0;

    LOGI("CAE beamformer destroyed");
}
