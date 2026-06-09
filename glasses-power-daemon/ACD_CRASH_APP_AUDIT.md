# ACD Crash — Listener App Audio Audit

Question: Does the glasses listener app (`com.repository.glasses.listener`) initiate
the PAL ACD stream whose `StreamACD::start -> ACDEngine::StartEngine` crashes the
vendor audio HAL (`sound_trigger.primary.neo.so`, SIGABRT ubsan mul-overflow), or is
it purely a victim of the HAL dying?

Investigated read-only on 2026-06-08. App source root:
`AI/clients/glasses/app/src/main/java/com/repository/glasses/listener`.

---

## 1. Does our app contain ANY ACD-specific code? YES.

The app contains a deliberate, first-class native ACD client:

- `wakeword/AcdNativeDetector.kt` — Kotlin wrapper over JNI bridge. Doc comment:
  *"Opens a `PAL_STREAM_ACD` against the SPEECH context (0x08001335) so the SoC DSP
  fires a callback when voice activity is detected."* Native worker thread does
  `pal_init / open / LOAD_SOUND_MODEL / RECOGNITION_CONFIG / pal_stream_start`.
  - Loads `System.loadLibrary("acd_native")` in its companion `init {}` (runs on
    first class-load).
  - `isAvailable()` = libacd_native.so loaded AND libpalclient.so dlopen'able AND
    `/vendor/etc/models/acd/speech.eai` readable.
  - `start(callback)` -> `nativeStart()` -> opens the PAL ACD stream.
- `app/src/main/cpp/acd_native.cpp` — the JNI/PAL native source. Built artifact
  present: `app/build/intermediates/.../arm64-v8a/libacd_native.so` and it is
  packaged in the APK (CMake target `acd_native` in `app/src/main/cpp/CMakeLists.txt`).
- `wakeword/WakeWordPipeline.kt` — orchestrator. `tryStartNativeAcd()` (line 307)
  probes `AcdNativeDetector.isAvailable()` and arms it. Comment explicitly references
  *"PAL_STREAM_ACD with QC_ACD vendor UUID + speech.eai ... Stock QTI HAL extracted
  from yodaos-stock-full super_6.img and /vendor/etc/resourcemanager_neo_idp.xml
  acd_platform_info wire this up. SPEECH context 0x08001335 fires when the LPAI
  Hexagon model detects ambient speech."*

This is exactly the path that reaches `StreamACD::start -> ACDEngine::StartEngine`
inside the custom neo sound_trigger HAL. The app is NOT merely a victim — it has
purpose-built code to open this stream.

Note: `useNativeAcd` default = **true** (WakeWordPipeline.kt:88). `tryStartNativeAcd()`
is the FIRST action of `WakeWordPipeline.start()` (line 242:
`val acdArmed = useNativeAcd && tryStartNativeAcd()`).

### SVA keyphrase path (separate, retired from live use)
`wakeword/SvaSoundTriggerDetector.kt` reflects `SoundTriggerManager.loadSoundModel /
startRecognition` against `/system/etc/ant_rokid_model.bin` (CAPTURE_AUDIO_HOTWORD).
This is the `project_glasses_sva_hal_blocker` binder-bypass code. It is **dead in the
live path**: the only references in WakeWordPipeline are `svaDetector?.stop()` and
`svaDetector = null` (lines 359/368). It is never constructed or `.start()`ed
anywhere. So SVA keyphrase is NOT a trigger source today.

---

## 2. Every code path that starts a SoundTrigger / ACD / AudioRecord capture

### A. PAL ACD stream (the crash path)
- `WakeWordPipeline.start()` -> `tryStartNativeAcd()` (WakeWordPipeline.kt:242, 307)
  -> `AcdNativeDetector.start()` -> `nativeStart()` -> PAL ACD open.
- **Trigger condition: `WakeWordPipeline.start()` being called.** See gating in §3.

### B. Always-on ARM-ONNX mic capture (fallback, victim-side)
- If ACD arms FALSE, `WakeWordPipeline.start()` does `MicBus.subscribe(this)`
  (WakeWordPipeline.kt ~line 250), consuming the shared `AudioRecord` owned by
  ListenerService. This is the silero-VAD + ONNX always-on chain. Does NOT open a
  PAL ACD stream.

### C. The single process AudioRecord (MicBus producer)
- `MicBus.kt`: *"exactly one AudioRecord in the process (owned by ListenerService)
  ... CHANNEL_IN_MONO + AudioSource.MIC"*. ListenerService opens it with
  **`AudioSource.MIC`** then forces MONO + sometimes `VOICE_RECOGNITION` to dodge an
  8-channel HAL profile (ListenerService.kt:4313-4368, 4523-4529). This is a normal
  record stream, NOT a SoundTrigger/HOTWORD stream, and is not the ACD path.
- `capture/TranslationFrontMicRecorder.kt:140` — `AudioRecord(AudioSource.MIC, ...)`,
  front-mic translation feature. Normal capture, no ACD.
- `RokidServiceBridge.kt` `startAudioRecord/stopAudioRecord("audio_no_ui")` — vendor
  Rokid record bridge, normal.
- The app **never** requests `AudioSource.HOTWORD`. `VOICE_RECOGNITION` appears only
  as an AR1 HAL-routing workaround on the ordinary record stream, plus a mic
  self-test (`testMicConfig(... VOICE_RECOGNITION)` at ListenerService.kt:4837).

No other path opens a SoundTrigger or ACD stream.

---

## 3. Can the ACD path fire while wakeword is DISABLED?

**Under normal operation: NO.** `WakeWordPipeline.start()` has exactly two callers,
BOTH gated on `GlassesConfig.wakewordEnabled`:

1. Service init (ListenerService.kt:3273-3279):
   ```
   wakeWordPipeline = WakeWordPipeline(this)
   val wwEnabled = GlassesConfig.wakewordEnabled
   if (!initialWearKnownOff && wwEnabled) { wakeWordPipeline.start() }
   else btLog("[WakeWord] pipeline held ... wakewordEnabled=$wwEnabled")
   ```
2. `reconcileWakeWord()` (ListenerService.kt:4186-4213):
   ```
   val wakewordEnabled = GlassesConfig.wakewordEnabled
   val needed = worn && phone && wakewordEnabled
   if (needed && !running) wakeWordPipeline.start()
   else if (!needed && running) wakeWordPipeline.stop()
   ```
   Called on phone-connect / phone-disconnect / phone-error / wear changes
   (lines 2164, 2224, 2261, 1290).

`GlassesConfig.wakewordEnabled` (GlassesConfig.kt:40, default true) is read from
JSON config (`wakeword_enabled`, line 76-77) and SharedPreferences (line 125). When
the user has disabled wakeword, `wwEnabled=false`, `needed=false`, so `start()` is
never called and the ACD stream is never opened on any event — phone connect, BT,
wear, or service start.

**The ONE bypass:** debug system property
`debug.glasses.ww.force_start 1` (ListenerService.kt:4188-4197). If set, `reconcileWakeWord`
forces start regardless of `wakewordEnabled`. Also `debug.glasses.acd.never_stop 1`
keeps ACD armed across `stop()`. These are bring-up-only props; if either is set on
the device, ACD will run even with wakeword "disabled". **Worth checking on-device:**
`getprop debug.glasses.ww.force_start` and `getprop debug.glasses.acd.never_stop`.

There is no AudioRecord-with-HOTWORD path and no SoundTrigger path that runs
independently of the wakeword gate.

---

## 4. Assessment: initiator or victim?

**If wakeword is genuinely disabled AND neither debug prop is set: the app is a
VICTIM, not the initiator** — it opens no ACD/SoundTrigger stream; its only audio use
is the ordinary `AudioSource.MIC` AudioRecord, which goes through the normal record
HAL graph, not ACD. Something else (stock firmware / vendor assistant) would be the
ACD initiator. Confidence ~75%.

**However** the app unambiguously HAS the capability and code to open the exact
crashing stream (`AcdNativeDetector` -> `PAL_STREAM_ACD` SPEECH 0x08001335), it is
armed by default (`useNativeAcd=true`), and the WakeWordPipeline doc even notes the
ACD path is "Default OFF on this build" only because *mic capture never engages*
(gpio6 held by lpi_tdm1_pinctrl) — i.e. it still calls `pal_stream_open`/set_param
when armed. So the decisive question is purely runtime state:

- Confirm on-device: is `wakeword_enabled` actually false in the live config/prefs
  that ListenerService reads? (`GlassesConfig` JSON + SharedPreferences.)
- Confirm `debug.glasses.ww.force_start` / `debug.glasses.acd.never_stop` are NOT set.
- Check logcat for `WakeWordPipeline` tag: `"start() complete (... ACD-gated)"` or
  `"Native ACD ... armed"` vs `"pipeline held (wakewordEnabled=false)"`. The
  `[WWGate]` btLog line prints `wakewordEnabled=` on every reconcile.

If those three confirm wakeword-off and no debug props, our app is exonerated as the
ACD initiator and the crash originates in stock firmware. If `force_start` is set or
the live config still has wakeword enabled, our `AcdNativeDetector` is very likely the
initiator of the crashing ACD stream (high confidence, since it is literally the only
PAL_STREAM_ACD opener in the process).

### Recommendation
Grep the running device's effective wakeword state and the two debug props before
blaming stock firmware. The crash cadence ("every few minutes") matches
`reconcileWakeWord` re-arming on wear/phone events if the gate is somehow passing.
