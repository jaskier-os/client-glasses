# A2DP-Only Ducking Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Duck ONLY the incoming A2DP music on the glasses during TTS playback, leaving the TTS voice at full volume and preserving normal user volume control.

**Architecture:** The A2DP-sink PCM is rendered in the `com.android.bluetooth` process via `AAudioStream_write` (libaaudio.so). We interpose that symbol from an LD_PRELOADed hook library (same proven mechanism as the existing `libsinkconn_hook.so`) and multiply the PCM buffer by a gain factor when a duck flag is set. The flag is a system property the hook polls. TTS moves to STREAM_ASSISTANT (an independent volume group), so it is unaffected. STREAM_MUSIC volume is never changed, so AVRCP absolute-volume sync is never triggered and user volume keys keep working.

**Tech Stack:** C (NDK arm64 API 32, LD_PRELOAD interposer), Kotlin (Android listener app), Android system properties, ADB instrumentation.

---

## Background (verified root cause)

- A2DP music + TTS both currently render on glasses **STREAM_MUSIC**.
- STREAM_MUSIC is **AVRCP absolute-volume synced** phone<->glasses (glasses are master). Any local STREAM_MUSIC duck is reverted by the native sync loop; phone-side duck drops the whole synced volume (music AND TTS).
- The native A2DP-sink gain hook `BtifAvrcpSetAudioTrackGain()` in bluedroid is an **empty stub** -- AOSP's built-in `a2dp_sink_duck_percent` path computes a ratio and discards it. That's why AUDIOFOCUS_..._CAN_DUCK produced no audible duck.
- PCM render symbol confirmed mapped in BT process: `AAudioStream_write` (libaaudio.so).
- `libsinkconn_hook.so` is already LD_PRELOADed into the BT process via an `app_process64` wrapper + `setenv LD_PRELOAD` in init.zygote rc. Gate prop: `persist.sys.sinkconn_hook.enabled`.
- **STREAM_ASSISTANT is a fully independent volume group** (Max 15), NOT abs-vol synced -- confirmed by changing phone media volume and seeing glasses STREAM_ASSISTANT unchanged.

Device serials: glasses `<GLASSES_SERIAL>`, phone `<PHONE_SERIAL>`.

WiFi ADB helper (USB drops when testing): `bash <workspace>/AI/scripts/enable-glasses-wifi-adb.sh '5G-Vaccination-SlimyBirb' 'SlimyBirb'`.

---

## Task 1: Create the A2DP duck hook library

Mirror the existing sinkconn-hook structure. New hook interposes `AAudioStream_write`.

**Files:**
- Create dir: `Recon/rokid-docs/yodaos-root-full/a2dpduck-hook/`
- Create: `Recon/rokid-docs/yodaos-root-full/a2dpduck-hook/src/hook.c`
- Create: `Recon/rokid-docs/yodaos-root-full/a2dpduck-hook/build.sh`
- Reference (read, do not modify): `Recon/rokid-docs/yodaos-root-full/sinkconn-hook/src/hook.c`, `sinkconn-hook/build.sh`

**Step 1: Write hook.c**

Interpose `AAudioStream_write(AAudioStream*, const void* buffer, int32_t numFrames, int64_t timeoutNanoseconds)`. The buffer is interleaved PCM. The A2DP sink uses 16-bit PCM (confirmed: `AUDIO_FORMAT_PCM_16_BIT`, stereo, 44.1/48k). When the duck prop is set, scale each int16 sample by the gain.

Key design decisions:
- **Gain source:** poll system property `persist.glasses.a2dp_duck` (string float, default "1.0"). Cache it, refresh at most every 100ms (avoid `__system_property_get` on every audio callback). A value like "0.25" ducks to 25%.
- **Buffer is const:** copy to a thread-local scratch buffer, scale, pass the scratch copy to real `AAudioStream_write`. Resize scratch as needed.
- **Format guard:** we only know it's PCM16. Add a cheap sanity gate: only scale when gain != 1.0; otherwise pass through untouched (zero overhead in the common case).
- **Real symbol:** `dlsym(RTLD_NEXT, "AAudioStream_write")`.
- **Only act in BT process:** like sinkconn, gate on process name prefix `droid.bluetoo` (set a global `g_is_bt` in an interposed `prctl(PR_SET_NAME)`; if not BT, pass through). This keeps the lib safe to load into every zygote child.

```c
/*
 * liba2dpduck_hook.so -- duck ONLY the A2DP-sink PCM on Rokid AR glasses.
 *
 * Loaded via LD_PRELOAD into every zygote child (same as libsinkconn_hook).
 * Acts only inside com.android.bluetooth. Interposes AAudioStream_write and,
 * when persist.glasses.a2dp_duck < 1.0, scales the int16 PCM buffer by that
 * gain before forwarding to the real AAudioStream_write. This attenuates the
 * locally rendered A2DP music WITHOUT touching STREAM_MUSIC, so AVRCP
 * absolute-volume sync is never triggered and user volume control is intact.
 */
#define _GNU_SOURCE
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/system_properties.h>
#include <time.h>
#include <unistd.h>

#define TAG "a2dpduck_hook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

#define TARGET_PREFIX     "droid.bluetoo"
#define TARGET_PREFIX_LEN 13
#define DUCK_PROP         "persist.glasses.a2dp_duck"
#define GAIN_REFRESH_MS   100

static int   g_is_bt = 0;
static float g_gain  = 1.0f;
static long long g_gain_ts_ms = 0;

static __thread int16_t *g_scratch = NULL;
static __thread size_t   g_scratch_cap = 0;  /* in int16 samples */

typedef int32_t (*aaudio_write_t)(void *stream, const void *buf,
                                  int32_t numFrames, int64_t timeoutNs);
static aaudio_write_t real_write = NULL;

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
    if (n > 0) { g = strtof(buf, NULL); if (g < 0.0f) g = 0.0f; if (g > 1.0f) g = 1.0f; }
    g_gain = g;
    return g;
}

__attribute__((visibility("default")))
int32_t AAudioStream_write(void *stream, const void *buffer,
                           int32_t numFrames, int64_t timeoutNanoseconds) {
    if (!real_write) {
        real_write = (aaudio_write_t)dlsym(RTLD_NEXT, "AAudioStream_write");
        if (!real_write) return -1;
    }
    if (!g_is_bt || numFrames <= 0 || !buffer)
        return real_write(stream, buffer, numFrames, timeoutNanoseconds);

    float gain = read_gain();
    if (gain >= 0.999f)
        return real_write(stream, buffer, numFrames, timeoutNanoseconds);

    /* Scale int16 PCM. We don't know channel count here; scaling per-sample
     * is channel-agnostic. Assume stereo int16 (A2DP sink): total int16
     * samples = numFrames * 2. To be safe against mono, derive from a stored
     * channel count if available; default 2. */
    size_t samples = (size_t)numFrames * 2;  /* stereo assumption */
    if (g_scratch_cap < samples) {
        int16_t *p = (int16_t *)realloc(g_scratch, samples * sizeof(int16_t));
        if (!p) return real_write(stream, buffer, numFrames, timeoutNanoseconds);
        g_scratch = p; g_scratch_cap = samples;
    }
    const int16_t *src = (const int16_t *)buffer;
    int32_t q = (int32_t)(gain * 4096.0f);  /* Q12 fixed point */
    for (size_t i = 0; i < samples; i++) {
        int32_t v = ((int32_t)src[i] * q) >> 12;
        if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
        g_scratch[i] = (int16_t)v;
    }
    return real_write(stream, g_scratch, numFrames, timeoutNanoseconds);
}

__attribute__((visibility("default")))
int prctl(int option, ...) {
    va_list ap; va_start(ap, option);
    unsigned long a2 = va_arg(ap, unsigned long);
    unsigned long a3 = va_arg(ap, unsigned long);
    unsigned long a4 = va_arg(ap, unsigned long);
    unsigned long a5 = va_arg(ap, unsigned long);
    va_end(ap);
    static int (*real_prctl)(int, unsigned long, unsigned long,
                             unsigned long, unsigned long) = NULL;
    if (!real_prctl)
        real_prctl = (void *)dlsym(RTLD_NEXT, "prctl");
    int ret = real_prctl(option, a2, a3, a4, a5);
    if (option == PR_SET_NAME && a2) {
        const char *name = (const char *)a2;
        if (strncmp(name, TARGET_PREFIX, TARGET_PREFIX_LEN) == 0) {
            g_is_bt = 1;
            LOGI("A2DP duck hook armed in BT process (pid=%d)", getpid());
        }
    }
    return ret;
}
```

**OPEN RISK to verify in Task 2:** stereo assumption + that the A2DP sink path actually goes through the public `AAudioStream_write` symbol (vs an internal `AAudioStream_write` C++ method or a different writer). If the symbol isn't called, the duck is silent and we must find the real writer (candidates: `AAudioStream::write`, internal `processData`, or libaudioclient `AudioTrack::write`). Task 2 instruments this BEFORE relying on it.

**Step 2: Write build.sh** -- copy `sinkconn-hook/build.sh`, change only the hook output name to `liba2dpduck_hook.so` and source to this `hook.c`. Link `-llog`. Drop the wrapper build (we reuse the existing app_process64 wrapper, see Task 3).

**Step 3: Build**

Run: `bash Recon/rokid-docs/yodaos-root-full/a2dpduck-hook/build.sh`
Expected: `liba2dpduck_hook.so` ELF arm64 produced in `build/`.

**Step 4: Commit**

```bash
cd Recon && git add rokid-docs/yodaos-root-full/a2dpduck-hook && \
git commit -m "feat(glasses): add A2DP-only PCM duck hook library"
```

---

## Task 2: Verify the interposer actually intercepts A2DP PCM (instrumented)

Before wiring anything, prove the hook ducks audibly. Deploy the lib via Tier-2 overlay and add it to the BT process's LD_PRELOAD temporarily.

**Step 1: Push lib to overlay slot**

```bash
G=<GLASSES_SERIAL>
adb -s $G push Recon/rokid-docs/yodaos-root-full/a2dpduck-hook/build/liba2dpduck_hook.so \
  /data/local/diy-overlay/system/lib64/liba2dpduck_hook.so
adb -s $G shell "su 0 cp /data/local/diy-overlay/system/lib64/liba2dpduck_hook.so /system/lib64/ 2>/dev/null; \
  ls -la /system/lib64/liba2dpduck_hook.so"
```

**Step 2: Chain it into the existing preload**

The app_process64 wrapper dlopen's `/system/lib64/libsinkconn_hook.so`. Simplest non-invasive test: temporarily set the colon-separated LD_PRELOAD in the zygote rc to include both. For the INSTRUMENTED test only, restart the BT process with both libs preloaded via a one-shot:

```bash
# Verify symbol is exported by our lib
adb -s $G shell "su 0 sh -c 'grep -a AAudioStream_write /system/lib64/liba2dpduck_hook.so >/dev/null && echo SYMBOL_OK'"
```

(Full boot-time injection is Task 3. For this test we rely on the wrapper change in Task 3 OR a manual `setprop wrap.com.android.bluetooth` if supported. If neither is convenient, do Task 3 injection first, then return here.)

**Step 3: Start A2DP music from phone, set duck prop, listen**

```bash
G=<GLASSES_SERIAL>
adb -s $G shell "setprop persist.glasses.a2dp_duck 0.25"
# >>> LISTEN: music should drop to ~25% <<<
adb -s $G shell "setprop persist.glasses.a2dp_duck 1.0"
# >>> LISTEN: music should return to full <<<
```

**Step 4: Confirm via logcat the hook armed**

```bash
adb -s $G shell "logcat -d -s a2dpduck_hook | tail -5"
```
Expected: "A2DP duck hook armed in BT process".

**DECISION GATE:**
- Audible duck works -> proceed to Task 3.
- No duck but hook armed -> the public `AAudioStream_write` isn't the render path. STOP, instrument which writer the sink uses (add an `AudioTrack::write` / `AudioStreamOut` interposer probe), update Task 1, repeat. Do NOT proceed.

---

## Task 3: Bake the hook into boot injection

Add `liba2dpduck_hook.so` to the same LD_PRELOAD chain as sinkconn, via the DIY overlay + zygote rc, so it survives reboot. LD_PRELOAD supports colon-separated libs.

**Files:**
- Modify: `Recon/rokid-docs/yodaos-root-full/root-firmware.sh` (the `setenv LD_PRELOAD` patch + overlay copy/bind sections)
- Reference: lines ~123, 147-148, 367-370, 506-523, 651-665, 758 (sinkconn handling -- mirror each for a2dpduck)

**Step 1:** In the `setenv LD_PRELOAD` patch, change the value from
`/system/lib64/libsinkconn_hook.so` to
`/system/lib64/libsinkconn_hook.so:/system/lib64/liba2dpduck_hook.so`.

**Step 2:** Mirror every sinkconn overlay action for a2dpduck: copy into `$OVERLAY_DIR/lib64/`, bind-mount in the post-flash overlay, add to rawprogram `write`/`sif`, and `_verify_pair`.

**Step 3:** Because the app_process64 wrapper only dlopen's sinkconn explicitly, confirm whether the colon LD_PRELOAD alone loads both. If the wrapper's explicit dlopen is the only load path (and it clears LD_PRELOAD), add a second `dlopen("/system/lib64/liba2dpduck_hook.so", ...)` to `sinkconn-hook/src/wrapper.c` (non-fatal, gated by the same enabled prop). Verify by reading wrapper.c fully first.

**Step 4: Deploy via the documented path** (NOT plain adb install):

```bash
bash Recon/rokid-docs/yodaos-root-full/root-firmware.sh --post-flash
# then reboot, wait sys.boot_completed=1
```

**Step 5: Verify after reboot**

```bash
G=<GLASSES_SERIAL>
adb -s $G shell "cat /proc/$(adb -s $G shell pidof com.android.bluetooth)/maps | grep a2dpduck"
```
Expected: liba2dpduck_hook.so mapped in BT process.

**Step 6: Commit**

```bash
cd Recon && git add rokid-docs/yodaos-root-full && \
git commit -m "feat(glasses): inject A2DP duck hook at boot via overlay"
```

---

## Task 4: Move glasses TTS to STREAM_ASSISTANT

TTS must leave STREAM_MUSIC so it's unaffected by the (now unused) music volume and clearly separated. STREAM_ASSISTANT is independent.

**Files:**
- Modify: `AI/clients/glasses/app/src/main/java/com/repository/glasses/listener/audio/TtsPlayer.kt` (AudioAttributes builder, ~line 366-371)

**Step 1:** Change TTS AudioTrack AudioAttributes from `USAGE_MEDIA` to `USAGE_ASSISTANT`, keep `CONTENT_TYPE_SPEECH`.

**Step 2:** Remove the `duckCompensation` field and its AudioTrack.setVolume usage (no longer needed -- TTS plays at full STREAM_ASSISTANT volume; music is ducked at the PCM layer instead). Verify no other references via grep.

**Step 3: Build + deploy**

```bash
bash Recon/scripts/deploy-to-glasses.sh
```

**Step 4: Verify** TTS still audible after a test notification (see Task 6 trigger).

**Step 5: Commit** (AI repo)

```bash
cd AI && git add clients/glasses/app/src/main/java/com/repository/glasses/listener/audio/TtsPlayer.kt && \
git commit -m "feat(glasses): render TTS on STREAM_ASSISTANT (independent of A2DP music)"
```

---

## Task 5: Replace ducking trigger with the duck prop

Swap the old STREAM_MUSIC AudioDucker + cross-device CH_AUDIO_DUCK for setting `persist.glasses.a2dp_duck`.

**Files:**
- Modify: `AI/clients/glasses/app/src/main/java/com/repository/glasses/listener/service/ListenerService.kt` (updateDuckState ~1924-1940; audioDucker init ~2409; reset ~8162; CH_AUDIO_DUCK send)
- Delete: `AI/clients/glasses/app/src/main/java/com/repository/glasses/listener/audio/AudioDucker.kt`
- Modify (phone): `AI/clients/phone/app/src/main/java/com/repository/listener/service/ListenerService.kt` (onAudioDuck handler ~2312) and remove phone `AudioDucker` usage
- Reference: `bt/GlassesBtClient.kt:593` (sendAudioDuck), `bt/BtProtocol.kt:141` (CH_AUDIO_DUCK)

**Step 1:** Add a helper in ListenerService to set the prop. Since the app likely lacks permission to write `persist.*` props directly, set it via a root shell the daemon/app already uses, OR use a non-persist prop the hook also reads. **Decision:** use `persist.glasses.a2dp_duck` written via `Runtime.getRuntime().exec(arrayOf("su","0","setprop","persist.glasses.a2dp_duck","0.25"))` IF su works from the app; otherwise fall back to a small ADB-less mechanism. VERIFY app can set the prop in a scratch test first:
```bash
adb -s <GLASSES_SERIAL> shell "su 0 setprop persist.glasses.a2dp_duck 0.3; getprop persist.glasses.a2dp_duck"
```
If the app's uid can't exec su, switch the hook to read a world-readable file (e.g. `/data/local/tmp/a2dp_duck`) instead of a prop, and have the app write that file (it already writes under /data/local/tmp for crash logs via the daemon). Pick the mechanism that the app uid can actually write.

**Step 2:** In `updateDuckState()`, replace `audioDucker.setDucked(true/false)` with setting the duck value (0.25 when shouldDuck, 1.0 otherwise). Keep the existing `shouldDuck` conditions (LISTENING, ttsIsPlaying, notifTtsPlaying, telegramVoiceActive, speakerVerifying).

**Step 3:** Remove `audioDucker` field/init/reset and the `ttsPlayer.duckCompensation` lines.

**Step 4:** Remove the CH_AUDIO_DUCK send (`btClient.sendAudioDuck`) -- the phone is no longer involved in ducking. On the phone side, remove the `onAudioDuck -> audioDucker.setDucked` wiring and the phone AudioDucker. Leave CH_AUDIO_DUCK constant for now (no-op) or remove fully if no other refs (grep first).

**Step 5: Build + deploy both** (phone first per deploy order):

```bash
bash Recon/scripts/deploy-to-phone.sh
bash Recon/scripts/deploy-to-glasses.sh
```

**Step 6: Commit** (separate commits per repo).

---

## Task 6: End-to-end verification

**Step 1:** Start A2DP music from the phone to the glasses (real playback).

**Step 2:** Trigger a test notification TTS:
```bash
adb -s <PHONE_SERIAL> shell "am broadcast -n com.repository.listener/.adb.AdbCommandReceiver \
  -a com.repository.listener.ADB_COMMAND --es type test_notif --es command_id ducktest \
  --es params '{\"sender\":\"DuckTest\",\"text\":\"Testing A2DP only ducking. Music should be quiet, this voice should be loud and clear.\",\"chat\":\"DuckTest\"}'"
```

**Step 3: LISTEN and confirm:**
- Music ducks to ~25% during TTS.
- TTS voice is at FULL volume (clearly louder than the ducked music).
- After TTS ends, music returns to full.

**Step 4: Confirm user volume control still works:** press glasses volume keys during music -- volume should change normally (proves AVRCP/STREAM_MUSIC untouched by our duck).

**Step 5: Confirm prop round-trips:**
```bash
adb -s <GLASSES_SERIAL> shell "getprop persist.glasses.a2dp_duck"
```
Expected: "1.0" at rest, "0.25" during TTS (catch it live or via logcat).

**Step 6:** Record a screen/audio capture if possible and bounce to Telegram per user CLAUDE.md.

---

## Rollback

- Disable hook instantly: `adb -s <GLASSES_SERIAL> shell "setprop persist.glasses.a2dp_duck 1.0"` (no duck, full passthrough).
- Disable hook load: `setprop persist.sys.sinkconn_hook.enabled 0` (shared gate) or remove the a2dpduck path from LD_PRELOAD and reboot.
- The hook is pure passthrough when gain==1.0, so a stuck prop never silences music.

## Risk Register

1. **Wrong write symbol** (Task 2 gate catches this). Fallback writers to probe: `AudioTrack::write` in libaudioclient.so, `AudioStreamOut` HAL write.
2. **Channel count != 2** -> over/under-reads scratch. Mitigation: if Task 2 shows mono or distortion, query channel count from the AAudioStream via `AAudioStream_getChannelCount(stream)` (resolve via dlsym) instead of assuming 2.
3. **Prop write permission** from app uid (Task 5 Step 1 decides prop vs file).
4. **Per-callback CPU cost** -- Q12 integer scale over ~960 samples is trivial; passthrough when gain==1.0 is branch-only.
5. **Platform signature** -- NOT touched. APK untouched; only a preloaded .so + native libbluetooth behavior, exactly like the existing sinkconn hook.
