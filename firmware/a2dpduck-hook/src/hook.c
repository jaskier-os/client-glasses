/*
 * liba2dpduck_hook.so -- duck ONLY the A2DP-sink PCM on Rokid AR glasses.
 *
 * Loaded via LD_PRELOAD into every zygote child (same mechanism as
 * libsinkconn_hook). Acts only inside com.android.bluetooth. Interposes
 * AAudioStream_write and, when persist.glasses.a2dp_duck < 1.0, scales the
 * int16 PCM buffer by that gain before forwarding to the real
 * AAudioStream_write. This attenuates the locally rendered A2DP music WITHOUT
 * touching STREAM_MUSIC, so AVRCP absolute-volume sync is never triggered and
 * user volume control stays intact.
 *
 * Passthrough (zero copy) when gain >= 0.999 or not in the BT process.
 */
#define _GNU_SOURCE
#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/system_properties.h>
#include <time.h>
#include <unistd.h>

#define TAG "a2dpduck_hook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

#define DUCK_PROP         "persist.glasses.a2dp_duck"
#define GAIN_REFRESH_MS   100

static int   g_is_bt = -1;   /* -1 = unknown, 0 = no, 1 = yes (BT process) */
static float g_gain  = 1.0f;
static long long g_gain_ts_ms = 0;
static int   g_channels = 0;   /* resolved lazily from the stream */

static __thread int16_t *g_scratch = NULL;
static __thread size_t   g_scratch_cap = 0;  /* in int16 samples */

typedef int32_t (*aaudio_write_t)(void *stream, const void *buf,
                                  int32_t numFrames, int64_t timeoutNs);
typedef int32_t (*aaudio_get_channels_t)(void *stream);

static aaudio_write_t        real_write = NULL;
static aaudio_get_channels_t p_get_channels = NULL;

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static float read_gain(void) {
    long long t = now_ms();
    if (t - g_gain_ts_ms < GAIN_REFRESH_MS) return g_gain;
    g_gain_ts_ms = t;
    char buf[PROP_VALUE_MAX];
    int n = __system_property_get(DUCK_PROP, buf);
    float g = 1.0f;
    if (n > 0) {
        g = strtof(buf, NULL);
        if (g < 0.0f) g = 0.0f;
        if (g > 1.0f) g = 1.0f;
    }
    g_gain = g;
    return g;
}

static int is_bt_process(void) {
    if (g_is_bt >= 0) return g_is_bt;
    g_is_bt = 0;
    int fd = open("/proc/self/cmdline", O_RDONLY);
    if (fd >= 0) {
        char buf[256];
        ssize_t n = read(fd, buf, sizeof(buf) - 1);
        close(fd);
        if (n > 0) {
            buf[n] = '\0';  /* argv[0] is NUL-terminated proc name */
            if (strcmp(buf, "com.android.bluetooth") == 0) {
                g_is_bt = 1;
                LOGI("A2DP duck hook armed in BT process (pid=%d)", getpid());
            }
        }
    }
    return g_is_bt;
}

static int channels_for(void *stream) {
    if (g_channels > 0) return g_channels;
    if (!p_get_channels)
        p_get_channels = (aaudio_get_channels_t)
            dlsym(RTLD_DEFAULT, "AAudioStream_getChannelCount");
    int ch = 2;
    if (p_get_channels) {
        int c = p_get_channels(stream);
        if (c == 1 || c == 2) ch = c;
    }
    g_channels = ch;
    LOGI("A2DP stream channels=%d", ch);
    return ch;
}

__attribute__((visibility("default")))
int32_t AAudioStream_write(void *stream, const void *buffer,
                           int32_t numFrames, int64_t timeoutNanoseconds) {
    if (!real_write) {
        real_write = (aaudio_write_t)dlsym(RTLD_NEXT, "AAudioStream_write");
        if (!real_write) return -1;
    }
    if (!is_bt_process() || numFrames <= 0 || !buffer)
        return real_write(stream, buffer, numFrames, timeoutNanoseconds);

    float gain = read_gain();
    if (gain >= 0.999f)
        return real_write(stream, buffer, numFrames, timeoutNanoseconds);

    int ch = channels_for(stream);
    size_t samples = (size_t)numFrames * (size_t)ch;
    if (g_scratch_cap < samples) {
        int16_t *p = (int16_t *)realloc(g_scratch, samples * sizeof(int16_t));
        if (!p) return real_write(stream, buffer, numFrames, timeoutNanoseconds);
        g_scratch = p;
        g_scratch_cap = samples;
    }
    const int16_t *src = (const int16_t *)buffer;
    int32_t q = (int32_t)(gain * 4096.0f);  /* Q12 fixed point */
    for (size_t i = 0; i < samples; i++) {
        int32_t v = ((int32_t)src[i] * q) >> 12;
        if (v > 32767) v = 32767;
        else if (v < -32768) v = -32768;
        g_scratch[i] = (int16_t)v;
    }
    return real_write(stream, g_scratch, numFrames, timeoutNanoseconds);
}

/* Diagnostic interpose: proves version-bound AAudio symbols now route through
 * our lib. If this fires but AAudioStream_write doesn't, the sink uses a
 * different stream object. */
typedef int32_t (*aaudio_open_t)(void *builder, void **stream);
__attribute__((visibility("default")))
int32_t AAudioStreamBuilder_openStream(void *builder, void **stream) {
    static aaudio_open_t real_open = NULL;
    if (!real_open)
        real_open = (aaudio_open_t)dlsym(RTLD_NEXT, "AAudioStreamBuilder_openStream");
    int32_t r = real_open ? real_open(builder, stream) : -1;
    if (is_bt_process()) LOGI("AAudioStreamBuilder_openStream intercepted (rc=%d)", r);
    return r;
}
