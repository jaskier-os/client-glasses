# Glasses wake-word architecture (current state, useNativeAcd=false)

## Overview

The Rokid AR Lite listener app runs an always-on wake-word detector entirely on the AP (no DSP/co-processor offload) when `useNativeAcd=false`. The pipeline subscribes to a shared mic bus, runs a Silero VAD gate followed by an openWakeWord ONNX chain (mel -> embedding -> sireneviy classifier), and on a hit fires an in-process broadcast (`ACTION_WAKE_WORD_HIT`) that the listener service consumes to start the BT live-utterance flow. ONNX execution prefers the QNN HTP execution provider with a CPU fallback.

Process layout (from `AndroidManifest.xml`):

- `:backend` process: `ListenerService` (declared `android:process=":backend"` at `<repo-root>/app/src/main/AndroidManifest.xml:125-128`). All wake-word work runs here -- `WakeWordPipeline` is constructed by `ListenerService.onCreate` so it lives in `:backend`.
- Default process: `MainActivity` -- the UI. It does NOT register a receiver for `ACTION_WAKE_WORD_HIT`; the broadcast is consumed inside the listener service via a `RECEIVER_NOT_EXPORTED` registration.

## Data flow when listening for wake word (useNativeAcd=false path)

1. `AudioRecorder` (in `capture/`) opens the mic at 16 kHz mono PCM_S16LE and pushes frames into `MicBus` (`<repo-root>/app/src/main/java/com/repository/glasses/listener/capture/MicBus.kt`). MicBus is a fan-out pub/sub: each `onPcmFrame(pcmMono16k, offset, length, epochNanos)` is delivered synchronously to every `MicSubscriber`.
2. Subscribers under demand: `WakeWordPipeline`, `LocalOpusWriter` (audio archive), `PrebufferingAudioSubscriber` (4 s rolling pre-buffer for replay), and the BT live-stream Opus encoder when the live gate is open.
3. `WakeWordPipeline.onPcmFrame` (`wakeword/WakeWordPipeline.kt:444-494`) copies the slice (MicBus reuses the array next frame) and dispatches the chunk onto a single-thread `WakeWord-Infer` executor.
4. RMS gate at 0.002 (`WakeWordPipeline.kt:120` + check at `:616`) discards near-silence.
5. Silero VAD: 16 kHz, frame size `VAD_INPUT_DIM=576` samples, LSTM hidden/cell state of 128 each (`:127, :161-167`), threshold `VAD_WAKE_THRESHOLD=0.40` (`:110`), hangover `VAD_HANGOVER_MS=2000` (`:123`). The OWW chain only runs while VAD is live or within hangover (`:622-639`).
6. openWakeWord chain (mirrors phone): chunk size 1280 samples = 80 ms (`:130`), mel produces 32-bin frames with `/10 + 2` bias, sliding mel window of 76 frames (`:131-132, :819`), embedding model emits 96-dim vectors (`:133`), classifier consumes 16 stacked embeddings (`:134, :860-882`).
7. Score logic (`processScore`, `:653-687`): require score >= `OWW_THRESHOLD=0.5` in 1-of-3 sliding frames (`:114-117`), enforce `WAKE_COOLDOWN_MS=1500` between fires (`:112, :666`).
8. Broadcast on hit: `Intent(ACTION_WAKE_WORD_HIT)` with `EXTRA_CONFIDENCE` (Float) and `EXTRA_EPOCH_NANOS` (Long), `setPackage(context.packageName)`, `context.sendBroadcast(intent)` (`:677-686`). Action constant `com.repository.glasses.listener.ACTION_WAKE_WORD_HIT` (`:95`).
9. Power profile: this is an irreducible CPU floor. CLAUDE.md ("Idle Power Floor") notes ~36 s of CPU per 15 min of idle wall-time across `AGMIPC@1.0-service`, `audio.service_64`, and `audioserver` due to Qualcomm AGM keeping the audio graph clock domain hot whenever AudioRecord is alive. Disabling wake word means the listener can also drop the mic stream (it is the only consumer once both phone/pc audio links are down -- see `reconcileMicStream` below), which is the actionable lever for power reduction.

## Classes involved

- `WakeWordPipeline` (entry, `:backend`) -- `<repo-root>/app/src/main/java/com/repository/glasses/listener/wakeword/WakeWordPipeline.kt:75-965`. Public API: `start()`, `stop()`, `isRunning()`, `injectPcmFile(path)` (debug only).
- `AcdNativeDetector` -- `<repo-root>/app/src/main/java/com/repository/glasses/listener/wakeword/AcdNativeDetector.kt`. Skipped entirely when `useNativeAcd=false`; the pipeline subscribes to MicBus directly.
- `MicBus` + `AudioRecorder` -- `capture/MicBus.kt`, `capture/AudioRecorder.kt`. Driven by `ListenerService.startMicStream` / `stopMicStream`.
- `ListenerService.wakeWordHitReceiver` -- `service/ListenerService.kt:1247-1290`. In-process receiver for `ACTION_WAKE_WORD_HIT`; opens the 30 s BT live-utterance gate and relays the wake event to the phone over RFCOMM.
- `ListenerService.reconcileWakeWord` -- `service/ListenerService.kt:3541` (and onward through ~end-of-method, ~25 lines).
- `ListenerService.reconcileMicStream` -- `service/ListenerService.kt` (function near `reconcileWakeWord`).
- `BootReceiver` -- `boot/BootReceiver.kt`. Starts `ListenerService` on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`.
- No `MainActivity` receiver for wake events. The wake hit is consumed in `:backend` only; UI updates flow from the listener service via other broadcast channels (chat list, etc.) after the orchestrator round-trip.

## How the pipeline gets started today

1. `BootReceiver.onReceive` -> `context.startForegroundService(Intent(this, ListenerService::class.java))` (`boot/BootReceiver.kt`).
2. `ListenerService.onCreate` constructs the pipeline at `service/ListenerService.kt:2844`:
   ```
   wakeWordPipeline = WakeWordPipeline(this)
   if (!initialWearKnownOff) {
       wakeWordPipeline.start()
   }
   ```
   The `initialWearKnownOff` gate (`:2845-2850`) skips boot-time start when the PSoC psensor reports off-head. The two-arg constructor uses Kotlin's default for `useNativeAcd` -- the source default in the file is `true`, but ARM-ONNX always-on is the working path on this build because PAL ACD never engages (gpio6 held by `lpi_tdm1_pinctrl`, see comment `WakeWordPipeline.kt:78-87`). `tryStartNativeAcd()` returns `false` when libpalclient or speech.eai is missing, so behaviour matches `useNativeAcd=false` even with the default.
3. Subsequent reconcile triggers (call sites of `reconcileWakeWord("<reason>")`):
   - `ListenerService.kt:1936` `phone-connect`
   - `ListenerService.kt:1963` `phone-disconnect`
   - `ListenerService.kt:1984` `phone-error`
   - `ListenerService.kt:2434, :2455` `wear` (on/off transitions from the PSoC psensor extcon broadcast)
4. Debug overrides:
   - `debug.glasses.ww.force_start=1` -- read fresh inside `reconcileWakeWord` (`ListenerService.kt:3543`-ish, see body); treats RFCOMM-disconnected as connected so the pipeline can run during ACD bring-up without a phone peer.
   - `debug.glasses.acd.never_stop=1` -- read fresh inside `WakeWordPipeline.stop()` (`WakeWordPipeline.kt:294-308`); makes `stop()` a no-op for live ACD bring-up. Test hook only -- production code paths must NOT depend on this.

`reconcileWakeWord` body (current logic):

```
val worn = lastWornState != false
val forced = SystemProperties.get("debug.glasses.ww.force_start","0") in {"1","true"}
val phone = phoneAudioConnected || forced
val needed = worn && phone
if (needed && !running) wakeWordPipeline.start()
else if (!needed && running) wakeWordPipeline.stop()
```

`reconcileMicStream` body (mic is needed when wake word is running OR a peer wants audio):

```
val ww = ::wakeWordPipeline.isInitialized && wakeWordPipeline.isRunning()
val needed = worn && (phoneAudioConnected || pcAudioConnected || ww)
```

## Phone -> glasses setting plumbing

- Persistence on glasses: `config/GlassesConfig.kt`. Settings live in SharedPreferences `glasses_config`. `applySettings(ctx, json)` parses an incoming JSON blob and persists via `save(ctx)`. After parsing it sends a local broadcast `ACTION_GLASSES_CONFIG_CHANGED` for any in-process consumers.
- BT channel: `BtProtocol.CH_SETTINGS = "listener_settings"` -- defined identically on both sides:
  - Phone: `clients/phone/app/src/main/java/com/repository/listener/bt/BtProtocol.kt:16`.
  - Glasses: `clients/glasses/app/src/main/java/com/repository/glasses/listener/bt/BtProtocol.kt`.
- Phone -> glasses send path:
  - `PhoneBtHost.sendSettings(json)` at `clients/phone/.../bt/PhoneBtHost.kt:969` -- `rfcommClient.send(BtProtocol.CH_SETTINGS, settingsJson)`.
  - Helper `PhoneBtHost.sendSettingsUpdate(vararg pairs)` at `:1535` for incremental key/value pushes (used by `GlassesSettingsActivity`).
  - The full snapshot pusher near `:1564-1608` builds a JSON of all known keys (`settings_msg_notification_display_duration`, `settings_msg_notification_sound_enabled`, `settings_screen_ui_bottom_margin`, `settings_chat_font_size`, `settings_brightness`, `settings_screen_timeout_s`, `settings_power_timeout_min`, etc.) and calls `sendSettings(...)`.
- Phone settings UI: `clients/phone/.../ui/GlassesSettingsActivity.kt`, `GlassesSettingsFragment.kt`, `GlassesSubTabAdapter.kt`. `GlassesSettingsActivity.sendSettingsUpdate(...)` at `:224` is the canonical pattern: build pairs, hand to `PhoneBtHost`.
- Glasses receive path:
  - Framing handled by `MessageRelay.handleIncomingBytes` / `parseFrame` at `bt/MessageRelay.kt:296-360` (length-prefixed channel + UTF-8 args).
  - Channel dispatch in `GlassesBtClient` at `bt/GlassesBtClient.kt:127-130`:
    ```
    BtProtocol.CH_SETTINGS -> {
        val settingsJson = args.getOrElse(0) { "{}" }
        listener?.onSettings(settingsJson)
    }
    ```
  - The listener implementation is `ListenerService.onSettings(settingsJson)` at `service/ListenerService.kt:5670-5688`, which calls `GlassesConfig.applySettings(this, settingsJson)`.
- BLE wake / wake-event channel (informational): `BtProtocol.CH_WAKE_EVENT = "listener_wake_event"` is the glasses -> phone direction (confidence + epochNanos). It is NOT involved in the new toggle.

## Implementation guide for adding "Disable wake word" toggle

### Phone side

1. Add a SharedPreference / DataStore key `glasses_wakeword_enabled` (default `true`) wherever the existing glasses settings live (look for the prefs writes in `GlassesSettingsActivity.kt` / `GlassesSettingsFragment.kt`).
2. Add a Settings UI row with a switch in `GlassesSettingsActivity` (or its fragment). Mirror the existing notification-sound row as a template; that one is a Boolean and rides the same pipeline.
3. On change, persist locally first, then push:
   ```
   phoneBtHost.sendSettingsUpdate("settings_wakeword_enabled" to value.toString())
   ```
   This piggybacks on the existing `CH_SETTINGS` JSON channel -- no new BT message type required. If a discrete typed message is preferred, add `const val CH_WAKEWORD_ENABLED = "listener_wakeword_enabled"` to `BtProtocol.kt` on both sides and a matching dispatch arm in `GlassesBtClient.handleMessage`.
4. Also include the same key in the full-snapshot push near `PhoneBtHost.kt:1594-1608` so a fresh-paired glasses unit picks up the user's preference on first connect.

### Glasses side (listener app)

1. Add to `config/GlassesConfig.kt`:
   - `var wakewordEnabled: Boolean = true`
   - Read in `load(ctx)`: `wakewordEnabled = sp.getBoolean("wakewordEnabled", wakewordEnabled)`
   - Persist in `save(ctx)`: `.putBoolean("wakewordEnabled", wakewordEnabled)`
   - Parse in `applySettings(ctx, json)`: `if (obj.has("settings_wakeword_enabled")) wakewordEnabled = obj.getString("settings_wakeword_enabled").toBoolean()`
2. After `GlassesConfig.applySettings` returns, `ListenerService.onSettings` (`ListenerService.kt:5670`) must call a new method `setWakeWordEnabled(GlassesConfig.wakewordEnabled)`. The cleanest hook is to add the call right after the existing `GlassesConfig.applySettings(this, settingsJson)` line at `ListenerService.kt:5674`.
3. Add `ListenerService.setWakeWordEnabled(enabled: Boolean)`:
   - Set a `@Volatile var wakeWordEnabled: Boolean = true` field on the service.
   - Call `reconcileWakeWord("setting-changed")`.
4. Extend `reconcileWakeWord`:
   ```
   val needed = worn && phone && wakeWordEnabled
   ```
   The existing `start/stop` branches then do the right thing: flipping `false` triggers `wakeWordPipeline.stop()`; flipping `true` re-evaluates and re-starts if `worn && phone`.
5. Extend `reconcileMicStream` only if you want the mic released when wake word is the sole consumer. The existing condition already handles it: `ww` becomes false after `reconcileWakeWord` stops the pipeline, and if no phone/pc audio path is up the mic is torn down. No code change required there.
6. Boot path in `ListenerService.onCreate`: load `GlassesConfig` (already happens in `GlassesListenerApp.onCreate`) BEFORE the `wakeWordPipeline.start()` line at `:2846`, then short-circuit:
   ```
   if (!initialWearKnownOff && GlassesConfig.wakewordEnabled) {
       wakeWordPipeline.start()
   }
   ```
7. `wakeWordPipeline.stop()` already tears down the ACD JNI native path defensively (see `WakeWordPipeline.kt:315-344`), so calling `stop()` is safe regardless of `useNativeAcd`. The `:362` `executor = null` line ensures any in-flight `onPcmFrame` exits early on the next handoff.
8. AudioRecord release: `MicBus` unsubscribes inside `WakeWordPipeline.stop()` at `:346`. If `reconcileMicStream` then sees no consumers, `stopMicStream` releases AudioRecord. Verify by tailing `[MicDemand]` log lines after toggling -- should print `needed=false` and the AudioRecord teardown trace.

### Edge cases to handle

- Setting flips while a wake hit is in flight: the cooldown (`WAKE_COOLDOWN_MS=1500`) and the in-process `wakeWordHitReceiver` registration are independent of `wakeWordEnabled`. After `stop()` no further hits can fire because MicBus is unsubscribed and the executor is shut down. An already-broadcast hit will still complete its 30 s BT gate -- this is acceptable; do NOT try to cancel it.
- Boot with `wakewordEnabled=false`: load config first, then short-circuit the `wakeWordPipeline.start()` call in `onCreate` (step 6 above). The pipeline is still constructed (so reconcile can later start it), it just never enters `running=true`.
- Privilege check on the BT message receiver: RFCOMM authentication and pairing are already enforced by `bt-manager`'s priv-app and the bonded-only RFCOMM socket in `BtManagerBridge`. No additional check is needed at the `CH_SETTINGS` dispatch site -- only the paired phone can deliver bytes on this channel.

## Files the implementer will touch

- `<workspace>/AI/clients/phone/app/src/main/java/com/repository/listener/ui/GlassesSettingsActivity.kt` (UI row + send)
- `<workspace>/AI/clients/phone/app/src/main/java/com/repository/listener/ui/GlassesSettingsFragment.kt` (UI row, if fragment-backed)
- `<workspace>/AI/clients/phone/app/src/main/java/com/repository/listener/bt/PhoneBtHost.kt:1594-1608` (include key in full snapshot)
- `<repo-root>/app/src/main/java/com/repository/glasses/listener/config/GlassesConfig.kt` (var + load/save/applySettings)
- `<repo-root>/app/src/main/java/com/repository/glasses/listener/service/ListenerService.kt:2844-2850` (boot gate)
- `<repo-root>/app/src/main/java/com/repository/glasses/listener/service/ListenerService.kt:3541` (`reconcileWakeWord` -- add `&& wakeWordEnabled` to `needed`)
- `<repo-root>/app/src/main/java/com/repository/glasses/listener/service/ListenerService.kt:5670-5688` (`onSettings` -- call `setWakeWordEnabled` after `applySettings`)
- New method `setWakeWordEnabled(enabled: Boolean)` in `ListenerService.kt` (location: near other reconcile helpers, ~`:3500`)
- Optional, only if a discrete BT message type is preferred over piggy-backing on `CH_SETTINGS` JSON: both `BtProtocol.kt` files (phone + glasses) plus a new dispatch arm in `GlassesBtClient.kt:127`.
