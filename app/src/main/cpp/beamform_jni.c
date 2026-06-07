#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>

#define TAG "BeamformJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef int (*agt_init_fn)(const char*);
typedef int (*agt_start_fn)(void);
typedef int (*agt_stop_fn)(void);
typedef int (*agt_voice_control_fn)(const char*, void*);

static void* lib_handle = NULL;
static agt_init_fn fn_init = NULL;
static agt_start_fn fn_start = NULL;
static agt_stop_fn fn_stop = NULL;
static agt_voice_control_fn fn_voice_control = NULL;
static int initialized = 0;

JNIEXPORT jboolean JNICALL
Java_com_repository_glasses_listener_capture_BeamformController_nativeInit(
    JNIEnv* env, jobject thiz, jstring libPath, jstring initConfig) {

    if (initialized) {
        LOGI("Already initialized, skipping");
        return JNI_TRUE;
    }

    const char* path = (*env)->GetStringUTFChars(env, libPath, NULL);
    LOGI("dlopen: %s", path);
    lib_handle = dlopen(path, RTLD_NOW);
    (*env)->ReleaseStringUTFChars(env, libPath, path);

    if (!lib_handle) {
        LOGE("dlopen failed: %s", dlerror());
        return JNI_FALSE;
    }

    fn_init = (agt_init_fn)dlsym(lib_handle, "agt_init");
    fn_start = (agt_start_fn)dlsym(lib_handle, "agt_start");
    fn_stop = (agt_stop_fn)dlsym(lib_handle, "agt_stop");
    fn_voice_control = (agt_voice_control_fn)dlsym(lib_handle, "agt_voice_control");

    if (!fn_init || !fn_start || !fn_stop || !fn_voice_control) {
        LOGE("dlsym failed: init=%p start=%p stop=%p vc=%p",
             fn_init, fn_start, fn_stop, fn_voice_control);
        dlclose(lib_handle);
        lib_handle = NULL;
        return JNI_FALSE;
    }

    const char* config = (*env)->GetStringUTFChars(env, initConfig, NULL);
    LOGI("agt_init config: %s", config);
    int ret = fn_init(config);
    (*env)->ReleaseStringUTFChars(env, initConfig, config);
    // Rokid's RtInstructSdk.initSdk is declared void -- it ignores the return value.
    // The DSP firmware loads even when ret != 0, so treat any return as success.
    LOGI("agt_init returned: %d (0x%x)", ret, ret);

    ret = fn_start();
    LOGI("agt_start returned: %d (0x%x)", ret, ret);

    initialized = 1;
    LOGI("Beamform initialized successfully");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_repository_glasses_listener_capture_BeamformController_nativeSetScene(
    JNIEnv* env, jobject thiz, jstring json) {

    if (!initialized || !fn_voice_control) {
        LOGE("nativeSetScene called but not initialized");
        return JNI_FALSE;
    }

    const char* cmd = (*env)->GetStringUTFChars(env, json, NULL);
    LOGI("agt_voice_control: %s", cmd);
    int ret = fn_voice_control(cmd, NULL);
    (*env)->ReleaseStringUTFChars(env, json, cmd);

    // Rokid's sendVoiceControl is declared void -- return value is not an error indicator.
    LOGI("agt_voice_control returned %d", ret);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_repository_glasses_listener_capture_BeamformController_nativeDestroy(
    JNIEnv* env, jobject thiz) {

    if (fn_stop && initialized) {
        LOGI("agt_stop");
        fn_stop();
    }
    if (lib_handle) {
        dlclose(lib_handle);
        lib_handle = NULL;
    }
    initialized = 0;
    fn_init = NULL;
    fn_start = NULL;
    fn_stop = NULL;
    fn_voice_control = NULL;
    LOGI("Beamform destroyed");
}
