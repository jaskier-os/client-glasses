# Option 3 — DSP-side VAD via SoundTrigger HAL (PAL backend)

End-state goal: while glasses are worn but no active session, the application
processor enters `aosd`/`cxsd`/`ddr` deep sleep. The audio DSP (aDSP) listens
continuously on its own clock domain at <1 mA. When voice activity is detected
the DSP fires a SoundTrigger recognition callback, which wakes the AP; our
listener processes the look-ahead-buffer (LAB) PCM through the OWW chain to
confirm the wake word; if confirmed it enters the existing
`StreamMode.LIVE_UTTERANCE` path, otherwise it goes back to sleep.

This unblocks the work originally scoped as Track A and described in
`sthal/DESIGN.md` / `sthal/README.md`. **The ATRACE conversion (commit
`1a2b9fb8d`) and listener-side wakelock fixes (commit `55aa8b682`) are already
in place** — once the HAL actually captures audio, the AP can finally suspend
between voice events.

## Why we need this beyond what we already have

After commit `55aa8b682` the kernel enters AOSD when **off-head**:
- `aosd` count went from 0 → 30,560 in 20 min unplugged off-head test.
- Off-head idle drain: ~32 mA → ~5 mA (6× improvement).

But **when worn, AudioRecord stays open** for wake-word capture. AudioRecord
opens an Android-side input stream → audioserver holds an `AudioIn`
PARTIAL_WAKE_LOCK → kernel can't suspend. So worn-idle floor still sits around
48 mA.

To suspend while worn, the listening must be done by the DSP, not by the AP.

## Repo state to build on

### sthal (existing scaffolding)

`<repo-root>/sthal/`

- `src/sound_trigger_hw.cpp` — Implements the AOSP `sound_trigger_hw_device`
  ops. Functions wired today: `getProperties`, `getPropertiesExtended`,
  `loadSoundModel`, `unloadSoundModel`, `startRecognition`, `stopRecognition`,
  `stopAllRecognitions`, `getModelState`, `recognition_callback` (UP into the
  framework). All ATRACE-instrumented (`st.*` slices).
- `src/sound_trigger_hw.cpp:301-320` — `startRecognition()` does **not** call
  `MicReader::open()/start()` — explicitly disabled with a comment because the
  tinyalsa backend collides with the vendor PAL stream. **THIS is the line that
  needs to flip on once PAL backend lands.**
- `src/mic_reader.h` / `mic_reader.cpp` — Currently a tinyalsa-based stub. The
  `open()`/`start()`/`stop()`/`close()`/`readLoop()` lifecycle is implemented;
  the swap target is "use PAL `pal_stream_open` + `pal_stream_read` instead of
  `pcm_open` + `pcm_readi`" (per `mic_reader.h` design comment).
- `src/qnn_runtime.cpp:24-25` and `:111-116` — Notes that silero VAD does not
  compile on HTP because of unsupported LSTM ops. **Implication: silero is not
  the right model for the DSP path.** A different model is required.
- `src/st_trace.h` — trace_marker helpers for ATRACE on this Android 12 build
  (added commit `1a2b9fb8d`). Reuse for any new code.
- `CMakeLists.txt` — Standalone NDK build; produces
  `sound_trigger.primary.neo.so`. Currently links `tinyalsa_static`, `log`,
  `dl`, `android` (for trace_marker via `<android/trace.h>` — NOT used since
  the trace_marker switch; can be removed if unused).
- `DESIGN.md`, `README.md` — Existing design docs. Read them.

### Listener app (already prepared)

`<repo-root>/app/src/main/java/com/repository/glasses/listener/wakeword/`

- `WakeWordPipeline.kt:74` — Constructor takes `useNativeHal: Boolean = true`.
- `WakeWordPipeline.kt:197-216` — `start()` documents the "armed-passive"
  co-run pattern: `tryStartNativeHal()` is called first, but the ARM-ONNX path
  also runs because the HAL has no audio. Once the HAL fires real detections
  this co-run becomes a probation-window fallback.
- `WakeWordPipeline.kt:238-260` — `tryStartNativeHal()`: probes via reflection
  on `android.media.soundtrigger.SoundTriggerManager`, calls
  `NativeHalDetector.start(callback)`. Returns true if the HAL accepts the
  model and starts recognition.
- `WakeWordPipeline.kt:335-375` — `onHalDetection()` callback already wired:
  fires `ACTION_WAKE_WORD_HIT` broadcast on HAL detection (currently uses
  confidence=1.0 since the HAL is the decision authority).
- `wakeword/NativeHalDetector.kt` — Reflection wrapper around
  `SoundTriggerManager` + `SoundTriggerDetector`. Loads a `GenericSoundModel`
  with our `kKeyphraseUuid` constant.

The hard part on the app side is **already done**. We just need the HAL to
fire detections.

### bt-manager + listener integration (no changes expected)

The G3 BLE wake channel + active-session ref-counting in bt-manager already
handle "phone signals us to wake". HAL detection is a parallel wake source —
fires `ACTION_WAKE_WORD_HIT` broadcast → existing receiver in `ListenerService`
calls `enterLiveUtteranceMode("wake-word", 30_000L)` → BLE notify to phone →
RFCOMM bring-up. No new wiring needed.

## What's blocking us today

### Blocker 1 — PAL headers

PAL is a per-process client library at `/vendor/lib64/libpalclient.so` with
all symbols exported (`pal_init`, `pal_stream_open`, `pal_stream_read`,
`pal_stream_close`, `pal_stream_get_param`, etc., all `T` in nm).

But: **no PAL headers exist on the device or in our repo.** Function signatures
are known by name only — the structs (`pal_stream_attributes`, `pal_device`,
`pal_stream_handle_t`, `pal_stream_type_t`, `pal_audio_fmt_t`, etc.) are
unknown. We can't allocate and pass these without their definitions.

Possible sources:
1. **Open-source AGM/PAL** on CodeLinaro / Qualcomm's GitHub mirrors. Search
   for `vendor/qcom/opensource/pal` or `agm-aosp`. The header layout has been
   open-sourced by Qualcomm in some BSP drops.
2. **Qualcomm BSP source drop** for the SoC variant on the Rokid AR Lite
   ("neo" board, per CLAUDE.md). Would require vendor agreement with
   Rokid/Qualcomm.
3. **Reverse-engineer** from the `libpalclient.so` binary plus the equivalent
   AOSP `audio_hw.c` source for similar Qualcomm devices (some struct layouts
   leak via debug strings + Ghidra analysis).

### Blocker 2 — VAD model compatible with Hexagon HTP

Per `qnn_runtime.cpp:24-25,111-116`, silero VAD's LSTM ops don't compile on
HTP. We need a different VAD/trigger model:

Options:
- **Qualcomm SVA (Smart Voice Activity)** — DSP firmware-native models.
  Format: `.uim` or proprietary `.bin`. Provided by Qualcomm; some BSPs ship
  a reference SVA model. May require a license / training pipeline access.
- **Custom-trained KWS for HTP** via `qnn-onnx-converter`. Compile a small
  CNN-based KWS model (e.g. 30k params) for HTP. Avoid LSTMs; use 1-D
  convolutions with windowing. Could be trained on a generic "any voice"
  dataset for VAD-style triggering.
- **Energy-only "voice-likely" trigger** — DSP runs a simple RMS + zero-
  crossing-rate threshold, fires on anything voice-like. False-positive heavy
  (any loud sound triggers AP wake), but trivial to implement on the DSP.

The cheapest path to prove the architecture works is option 3: a stub
"trigger on any non-silent audio" model. Once the wake → AP-confirm pipeline
is end-to-end, swap in a smarter model.

### Blocker 3 — SoundTrigger framework on this BSP

The standard Android `SoundTriggerManager` API expects `KeyphraseSoundModel`
or `GenericSoundModel` types. Our scaffolding uses `GenericSoundModel` with
the custom UUID `a1234567-b89c-def0-1234-56789abcdef0` (constant
`kKeyphraseUuid` in `sound_trigger_hw.cpp` and
`WakeWordPipeline.kt:55`). On Qualcomm BSPs the framework dispatches generic
models to the SoundTrigger HAL we're implementing.

Verify on this device:
- `dumpsys sound_trigger_hal` shows our HAL registered.
- `cmd soundtrigger list-models` lists active models.
- `getservice -- vendor.qti.hardware.soundtrigger@*` enumerates Qualcomm's
  HIDL implementation if any.

If Qualcomm ships their own `sound_trigger.primary.neo.so` in
`/vendor/lib64/hw/`, our DIY-overlay bind-mount needs to override it (per
CLAUDE.md the overlay path supports this). Verify via:

```bash
adb shell ls -la /vendor/lib64/hw/sound_trigger.*
adb shell md5sum /vendor/lib64/hw/sound_trigger.primary.neo.so
```

Compare to our build artifact MD5 to confirm bind-mount activated post-reboot.

## Work breakdown for the next agent

### Phase 1 — Source PAL headers (1-2 days, blocking everything else)

Find or reverse-engineer the headers. Specific symbols needed (from
`libpalclient.so` symbol table):

```
pal_init(void)
pal_deinit(void)
pal_stream_open(struct pal_stream_attributes*, uint32_t no_of_devices,
                struct pal_device*, uint32_t no_of_modifiers,
                struct modifier_kv*, pal_stream_callback,
                uint64_t cookie, pal_stream_handle_t**)
pal_stream_close(pal_stream_handle_t*)
pal_stream_start(pal_stream_handle_t*)
pal_stream_stop(pal_stream_handle_t*)
pal_stream_read(pal_stream_handle_t*, struct pal_buffer*)
```

Output: `sthal/include/pal/Pal.h` (or whatever the canonical name is) with the
struct definitions, enum values for `pal_stream_type_t` (need
`PAL_STREAM_LOW_LATENCY`), `pal_audio_fmt_t` (need `PAL_AUDIO_FMT_PCM_S16_LE`),
`pal_device_id_t` (need `PAL_DEVICE_IN_HANDSET_MIC` or equivalent for the
glasses' built-in mic).

Validation: a 30-line standalone test program that opens a PAL stream, reads
1 second of PCM, prints the RMS, and closes. Build with NDK using the new
headers, run on glasses (root, push to `/data/local/tmp/`), confirm it doesn't
collide with Android's audio path.

### Phase 2 — PAL backend in MicReader (2-3 days)

Replace the tinyalsa backend in `sthal/src/mic_reader.cpp` with PAL:

```cpp
// mic_reader.cpp (new backend)
struct MicReader::Impl {
    pal_stream_handle_t* stream;
    std::thread reader_thread;
    std::atomic<bool> running;
    MicFrameCallback cb;
};

bool MicReader::open(uint32_t card, uint32_t device,
                     uint32_t sampleRateHz, uint32_t channels) {
    // (card/device args become PAL stream attributes)
    pal_stream_attributes attr = {
        .type = PAL_STREAM_LOW_LATENCY,
        .info.opt_stream_info.version = 1,
        .info.opt_stream_info.size = sizeof(pal_stream_attributes),
        .direction = PAL_AUDIO_INPUT,
        .in_media_config.sample_rate = sampleRateHz,
        .in_media_config.bit_width = 16,
        .in_media_config.aud_fmt_id = PAL_AUDIO_FMT_PCM_S16_LE,
        .in_media_config.ch_info.channels = channels,
        // ... fill in channel map, etc.
    };
    pal_device dev = {
        .id = PAL_DEVICE_IN_HANDSET_MIC, // confirm correct device id for glasses mic
        .config = { /* device config */ },
    };
    return pal_stream_open(&attr, 1, &dev, 0, nullptr,
                          nullptr, 0, &impl_->stream) == 0;
}
```

Update `CMakeLists.txt` to link `palclient` (which is the on-device library
name; verify with `ls /vendor/lib64/libpal*`).

### Phase 3 — Flip the startRecognition() switch (1 day)

`sthal/src/sound_trigger_hw.cpp:301-320` — re-enable the
`MicReader::open()/start()` calls in `startRecognition()`. Implement the
recognition logic:

1. Mic frames arrive at `MicReader::frame_callback`.
2. The frames are accumulated in a circular LAB buffer (look-ahead, ~2s).
3. A simple VAD (RMS threshold, or the chosen HTP-compatible model) runs on
   each frame.
4. When VAD fires, build a `sound_trigger_recognition_event` with the LAB
   PCM attached and call the framework's `recognition_callback`.

The framework receives the event → fires `SoundTriggerDetector.OnDetected()` →
our `NativeHalDetector` callback fires → `WakeWordPipeline.onHalDetection()`
→ `ACTION_WAKE_WORD_HIT` broadcast.

### Phase 4 — Tear down ARM-ONNX co-run when HAL is live (1 day)

`WakeWordPipeline.kt:197-216` — After PAL is solid, change `start()` to NOT
co-run the ARM-ONNX pipeline when `halActive == true`. Instead:

```kotlin
fun start() {
    if (useNativeHal && tryStartNativeHal()) {
        Log.i(TAG, "HAL active — ARM-ONNX dormant until detection")
        return  // do not subscribe MicBus, do not init ONNX sessions
    }
    // Fallback: ARM-ONNX path as today
    ...
}
```

When `onHalDetection()` fires, the listener's existing `wakeWordHitReceiver`
calls `enterLiveUtteranceMode(...)`. **At that point** we may want to spin up
the OWW chain briefly to confirm the HAL's voice-detection wasn't a false
positive (HAL VAD will have higher false-positive rate than our 4-stage
silero+OWW chain). Architecture:

- HAL fires VAD detection, hands AP the LAB PCM.
- AP runs `WakeWordPipeline.processOwwAudio(labPcm)` to score the wake word.
- If score > threshold → real wake, enter LIVE_UTTERANCE.
- If score < threshold → false positive, ignore and go back to sleep.

This keeps WW accuracy at silero+OWW levels while letting the AP suspend
99% of the time.

### Phase 5 — Verify AP suspend during worn idle (1 day, the prize)

After deploy:

1. Wear glasses. Phone connected. No audio session.
2. Capture qcom_sleep_stats baseline.
3. Wait 5 min worn-idle.
4. Pull qcom_sleep_stats again. Expect:
   - `aosd` count > 0 (currently always 0 when worn)
   - `cxsd`, `ddr` ticking up
   - `suspend_stats success > 0`
5. Pull BLE battery telemetry log. Expect mean current 5-15 mA worn-idle
   (down from 48 mA).
6. Manual test: speak the wake word. Should still fire (HAL → broadcast → OWW
   confirm → enterLiveUtteranceMode). Latency should be acceptable
   (HAL → AP wake + OWW confirm ~ 200-400 ms typical).

## Risks

- **PAL headers may be unavailable.** If neither open-source nor Qualcomm
  source nor reverse-engineering yields usable headers within ~3 days, the
  whole plan stalls. Mitigation: drop to Path 2 (hard duty-cycle on the AP
  side) as documented elsewhere.
- **HAL false-positive rate.** Even with the OWW confirm step, if the HAL
  fires too often (every loud sound) the AP wake-up cost cancels the suspend
  win. Mitigation: tune VAD threshold conservatively; profile false-positive
  rate per minute in typical environments.
- **Coexistence with audioserver.** Audioserver still expects to be able to
  open input streams (e.g. for HFP, recording apps, voice notes). The HAL's
  PAL stream and audioserver's PAL stream have to share the mic without
  collision. Need to verify whether `PAL_STREAM_LOW_LATENCY` allows
  concurrent capture or requires arbitration. If exclusive, the listener has
  to explicitly close the HAL when audioserver opens for another purpose, and
  reopen after. Mitigation: read PAL docs once headers are sourced;
  experiment empirically.
- **kKeyphraseUuid namespace collision.** The UUID
  `a1234567-b89c-def0-1234-56789abcdef0` is hardcoded in our HAL and listener.
  If Qualcomm or another vendor uses a clashing UUID, the framework dispatch
  may misroute. Mitigation: verify via `dumpsys sound_trigger_hal` after
  deploy; pick a fresh UUID if needed.

## Success criteria

- `qcom_sleep_stats/aosd Count > 0` while worn-idle for 5+ min.
- Mean current ≤ 20 mA while worn-idle (target 10 mA).
- Wake word latency from utterance to LIVE_UTTERANCE entry ≤ 500 ms
  (HAL VAD → AP wake → OWW confirm → enterLiveUtteranceMode).
- No regressions in: A2DP playback, HFP calls, file sync, photo capture.

## References

- `sthal/DESIGN.md`
- `sthal/README.md`
- `sthal/src/sound_trigger_hw.cpp:301-320` — the line to flip
- `sthal/src/mic_reader.h` — design comments for PAL backend swap
- `sthal/src/qnn_runtime.cpp:24-25,111-116` — silero/HTP incompatibility note
- `clients/glasses/app/src/main/java/com/repository/glasses/listener/wakeword/WakeWordPipeline.kt:74,197-216,238-260,335-375`
- `clients/glasses/app/src/main/java/com/repository/glasses/listener/wakeword/NativeHalDetector.kt`
- `clients/glasses/CLAUDE.md` "Idle Power Floor" section — pre-existing OEM-cooperation note (now superseded by this work, since wakelock fix proved AP CAN suspend if blockers are addressed app-side)
- Wakelock-fix commit `55aa8b682` — proved AP suspends fine when our wakelocks are released; this work extends that to the worn case
- ATRACE conversion commit `1a2b9fb8d` — sthal already emits `st.*` slices via trace_marker (verified pattern)
