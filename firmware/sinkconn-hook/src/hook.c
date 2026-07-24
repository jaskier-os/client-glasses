/*
 * libsinkconn_hook.so -- runtime A2DP sink_conn uncap for Rokid Glasses.
 *
 * Loaded via `setenv LD_PRELOAD /system/lib64/libsinkconn_hook.so` on the
 * zygote service. Inherits into every zygote-forked app process.
 *
 * Trigger point: we INTERPOSE prctl(). Every Zygote-specialized app calls
 * prctl(PR_SET_NAME, "<procname>") shortly after fork, AFTER UID drop /
 * SELinux transition / capability drop are complete. Calling pthread_create
 * from inside our prctl wrapper is safe (regular call on the main thread,
 * no race with specialize bookkeeping). pthread_atfork child handlers, by
 * contrast, race the specialize path and brick zygote.
 *
 * On receiving PR_SET_NAME == "com.android.bluetooth" (possibly truncated
 * by prctl to 15 chars), we spawn a detached worker thread that:
 *   1. AttachCurrentThread to the VM.
 *   2. Poll ActivityThread.currentActivityThread().getApplication() until
 *      the app classloader exists.
 *   3. Class.forName("com.android.bluetooth.a2dpsink.A2dpSinkService", cl).
 *   4. SetStaticIntField(mMaxA2dpSinkConnections, 64).
 *   5. Re-arm every 5s for 5min in case A2dpSinkService.start() re-runs and
 *      resets the field via Math.min(prop, 2).
 */

#define _GNU_SOURCE
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#define TAG "sinkconn_hook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* Android passes PR_SET_NAME using the LAST 15 chars of the package name.
 * For "com.android.bluetooth" that yields "droid.bluetooth" (or the pre-
 * truncation "droid.bluetoot" seen in one earlier PR_SET_NAME call). We
 * match by common prefix "droid.bluetoo" (13 chars). */
#define TARGET_PREFIX         "droid.bluetoo"
#define TARGET_PREFIX_LEN     13
#define TARGET_CLASS          "com.android.bluetooth.a2dpsink.A2dpSinkService"
#define TARGET_FIELD          "mMaxA2dpSinkConnections"
#define TARGET_MAX            64

#define VM_WAIT_MS            60000
#define VM_POLL_MS            200
#define APP_WAIT_MS           60000
#define APP_POLL_MS           100
#define CLASS_WAIT_MS         120000
#define CLASS_POLL_MS         200
#define TOTAL_REARM_MS        300000
#define REARM_INTERVAL_MS     5000

typedef jint (*jni_get_created_vms_t)(JavaVM **vms, jsize bufLen, jsize *nVMs);
static jni_get_created_vms_t p_JNI_GetCreatedJavaVMs = NULL;

static pthread_once_t g_spawn_once = PTHREAD_ONCE_INIT;

static void sleep_ms(int ms) {
    struct timespec ts = { ms / 1000, (ms % 1000) * 1000000L };
    nanosleep(&ts, NULL);
}

static JavaVM *wait_for_vm(void) {
    if (!p_JNI_GetCreatedJavaVMs) {
        p_JNI_GetCreatedJavaVMs =
            (jni_get_created_vms_t)dlsym(RTLD_DEFAULT, "JNI_GetCreatedJavaVMs");
        if (!p_JNI_GetCreatedJavaVMs) {
            const char *libs[] = { "libnativehelper.so", "libart.so", NULL };
            for (int i = 0; libs[i]; i++) {
                void *h = dlopen(libs[i], RTLD_NOW | RTLD_GLOBAL);
                if (!h) continue;
                p_JNI_GetCreatedJavaVMs =
                    (jni_get_created_vms_t)dlsym(h, "JNI_GetCreatedJavaVMs");
                if (p_JNI_GetCreatedJavaVMs) break;
            }
        }
        if (!p_JNI_GetCreatedJavaVMs) {
            LOGE("cannot resolve JNI_GetCreatedJavaVMs");
            return NULL;
        }
    }
    int waited = 0;
    while (waited < VM_WAIT_MS) {
        JavaVM *vms[1] = { NULL };
        jsize n = 0;
        if (p_JNI_GetCreatedJavaVMs(vms, 1, &n) == JNI_OK && n > 0 && vms[0])
            return vms[0];
        sleep_ms(VM_POLL_MS);
        waited += VM_POLL_MS;
    }
    return NULL;
}

static jobject get_app_classloader(JNIEnv *env) {
    jclass atCls = (*env)->FindClass(env, "android/app/ActivityThread");
    if (!atCls) { (*env)->ExceptionClear(env); return NULL; }
    jmethodID cur = (*env)->GetStaticMethodID(
        env, atCls, "currentActivityThread", "()Landroid/app/ActivityThread;");
    if (!cur) { (*env)->ExceptionClear(env); (*env)->DeleteLocalRef(env, atCls); return NULL; }
    jobject at = (*env)->CallStaticObjectMethod(env, atCls, cur);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (!at) { (*env)->DeleteLocalRef(env, atCls); return NULL; }
    jmethodID getApp = (*env)->GetMethodID(
        env, atCls, "getApplication", "()Landroid/app/Application;");
    jobject app = getApp ? (*env)->CallObjectMethod(env, at, getApp) : NULL;
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, at);
    (*env)->DeleteLocalRef(env, atCls);
    if (!app) return NULL;
    jclass appCls = (*env)->GetObjectClass(env, app);
    jmethodID getCl = (*env)->GetMethodID(
        env, appCls, "getClassLoader", "()Ljava/lang/ClassLoader;");
    jobject cl = getCl ? (*env)->CallObjectMethod(env, app, getCl) : NULL;
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, app);
    (*env)->DeleteLocalRef(env, appCls);
    return cl;
}

static jclass load_class(JNIEnv *env, const char *name, jobject classLoader) {
    jclass classCls = (*env)->FindClass(env, "java/lang/Class");
    if (!classCls) { (*env)->ExceptionClear(env); return NULL; }
    jmethodID forName = (*env)->GetStaticMethodID(
        env, classCls, "forName",
        "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");
    jstring jname = (*env)->NewStringUTF(env, name);
    jclass target = (jclass)(*env)->CallStaticObjectMethod(
        env, classCls, forName, jname, JNI_TRUE, classLoader);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); target = NULL; }
    (*env)->DeleteLocalRef(env, jname);
    (*env)->DeleteLocalRef(env, classCls);
    return target;
}

static int write_field(JNIEnv *env, jclass cls, int value) {
    jfieldID fid = (*env)->GetStaticFieldID(env, cls, TARGET_FIELD, "I");
    if (!fid) { (*env)->ExceptionClear(env); return 0; }
    (*env)->SetStaticIntField(env, cls, fid, (jint)value);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return 0; }
    return 1;
}

static int read_field(JNIEnv *env, jclass cls, int *out) {
    jfieldID fid = (*env)->GetStaticFieldID(env, cls, TARGET_FIELD, "I");
    if (!fid) { (*env)->ExceptionClear(env); return 0; }
    *out = (int)(*env)->GetStaticIntField(env, cls, fid);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return 0; }
    return 1;
}

static void *hook_worker(void *arg) {
    (void)arg;
    JavaVM *vm = wait_for_vm();
    if (!vm) { LOGE("no JavaVM"); return NULL; }

    JNIEnv *env = NULL;
    JavaVMAttachArgs aa = { JNI_VERSION_1_6, "sinkconn-hook", NULL };
    if ((*vm)->AttachCurrentThread(vm, &env, &aa) != JNI_OK) {
        LOGE("AttachCurrentThread failed");
        return NULL;
    }

    jobject classLoader = NULL;
    int waited = 0;
    while (waited < APP_WAIT_MS) {
        classLoader = get_app_classloader(env);
        if (classLoader) break;
        sleep_ms(APP_POLL_MS);
        waited += APP_POLL_MS;
    }
    if (!classLoader) {
        LOGE("classloader not available after %dms", APP_WAIT_MS);
        (*vm)->DetachCurrentThread(vm);
        return NULL;
    }
    LOGI("classloader resolved after %dms", waited);
    jobject clGlobal = (*env)->NewGlobalRef(env, classLoader);
    (*env)->DeleteLocalRef(env, classLoader);

    jclass targetCls = NULL;
    int waitedCls = 0;
    while (waitedCls < CLASS_WAIT_MS) {
        targetCls = load_class(env, TARGET_CLASS, clGlobal);
        if (targetCls) break;
        sleep_ms(CLASS_POLL_MS);
        waitedCls += CLASS_POLL_MS;
    }
    if (!targetCls) {
        LOGE("%s not loadable after %dms", TARGET_CLASS, CLASS_WAIT_MS);
        (*env)->DeleteGlobalRef(env, clGlobal);
        (*vm)->DetachCurrentThread(vm);
        return NULL;
    }
    jclass targetGlobal = (jclass)(*env)->NewGlobalRef(env, targetCls);
    (*env)->DeleteLocalRef(env, targetCls);

    int cur = -1;
    (void)read_field(env, targetGlobal, &cur);
    LOGI("initial %s=%d writing %d", TARGET_FIELD, cur, TARGET_MAX);
    (void)write_field(env, targetGlobal, TARGET_MAX);

    int rearmed = 0;
    while (rearmed < TOTAL_REARM_MS) {
        sleep_ms(REARM_INTERVAL_MS);
        rearmed += REARM_INTERVAL_MS;
        cur = -1;
        if (!read_field(env, targetGlobal, &cur)) continue;
        if (cur != TARGET_MAX) {
            LOGW("%s drifted to %d; rewriting %d", TARGET_FIELD, cur, TARGET_MAX);
            (void)write_field(env, targetGlobal, TARGET_MAX);
        }
    }
    LOGI("re-arm window elapsed; exiting");

    (*env)->DeleteGlobalRef(env, targetGlobal);
    (*env)->DeleteGlobalRef(env, clGlobal);
    (*vm)->DetachCurrentThread(vm);
    return NULL;
}

static void spawn_worker_once(void) {
    LOGI("BT detected (pid=%d); spawning hook thread", getpid());
    pthread_t t;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    if (pthread_create(&t, &attr, hook_worker, NULL) != 0) {
        LOGE("pthread_create failed: %s", strerror(errno));
    }
    pthread_attr_destroy(&attr);
}

/* Interposed prctl: bionic declares `int prctl(int __op, ...)` as variadic,
 * so we must match. We always forward all five potential args to the real
 * syscall (unused slots are ignored by the kernel). Every zygote-forked
 * app calls PR_SET_NAME during specialize -- a safe point to spawn our
 * worker thread (no race with UID/SELinux/capability drops). */
__attribute__((visibility("default")))
int prctl(int option, ...) {
    va_list ap;
    va_start(ap, option);
    unsigned long a2 = va_arg(ap, unsigned long);
    unsigned long a3 = va_arg(ap, unsigned long);
    unsigned long a4 = va_arg(ap, unsigned long);
    unsigned long a5 = va_arg(ap, unsigned long);
    va_end(ap);

    static int (*real_prctl)(int, unsigned long, unsigned long,
                             unsigned long, unsigned long) = NULL;
    if (!real_prctl) {
        real_prctl = (int (*)(int, unsigned long, unsigned long,
                              unsigned long, unsigned long))
                     dlsym(RTLD_NEXT, "prctl");
        if (!real_prctl) {
            errno = ENOSYS;
            return -1;
        }
    }
    int ret = real_prctl(option, a2, a3, a4, a5);

    if (option == PR_SET_NAME && a2) {
        const char *name = (const char *)a2;
        if (name && strncmp(name, TARGET_PREFIX, TARGET_PREFIX_LEN) == 0) {
            /* Only fires once per process via pthread_once. Many threads
             * within the BT process also set names -- all of them match
             * the prefix, but only the first triggers spawn_worker_once. */
            pthread_once(&g_spawn_once, spawn_worker_once);
        }
    }
    return ret;
}

