# `setVoiceCtrl` Investigation (READ-ONLY)

Date: 2026-06-08. Question: what does `cxr.setVoiceCtrl(value)` actually toggle on the
Rokid AR glasses, and is forcing it OFF safe? Goal: decide whether to hard-disable Rokid's
built-in voice control so only our own WakeWordPipeline/ACD runs.

## TL;DR

- `setVoiceCtrl` does NOT directly start/stop a service. It pushes a **Rokid OS persisted
  setting** `settings_voice_control` to the glasses via a CXR `Settings_Update` message.
- On the glasses, `RokidSpriteAssistServer` (Rokid's built-in assistant) listens to that
  setting and, when it changes, calls `voicePowerControl()` on its **offline voice/wakeword
  engine** (`RtInstructSdk` → `librt_instruct.so` + `libaudigent.so`, the `agt_*` engine).
  It sends a `lowpower_mode` firmware command to power the offline wakeword engine on/off.
- This is a **completely different engine from the crashing sound_trigger/ACD HAL**
  (`sound_trigger.primary.neo.so` / `PAL_STREAM_ACD`). Neither `librt_instruct.so` nor
  `libaudigent.so` reference sound_trigger/PAL/ACD. So Rokid voice control is NOT opening
  PAL ACD streams that would collide with ours.
- **VALUE SEMANTICS MISMATCH (important):** the glasses compare the setting's value against
  the literal string `"open"`. The phone app sends `"1"` (On) / `"0"` (Off). `"1" != "open"`,
  so from the glasses' point of view *any* value the phone currently sends is treated as
  "not open" → `mOffLineIgnore = true` → offline voice engine kept OFF. To deliberately
  ENABLE Rokid voice you would have to send the string `"open"`; to disable it, send anything
  else (e.g. `"close"`, `"0"`, or `"1"`).

---

## 1. Phone side — exact definition / semantics

`cxr` is `com.rokid.cxr.client.extend.CxrApi` (CXR-M "client-m" SDK).

Phone call sites:
- `AI/clients/phone/app/.../bt/PhoneBtHost.kt:2151` `val cxr = CxrApi.getInstance()`
- `PhoneBtHost.kt:2153-2156` reads `AppConfig.getGlassesVoiceControl(context)` then `cxr.setVoiceCtrl(voiceControl)`
- `AI/clients/phone/app/.../ui/GlassesSettingsActivity.kt:61` `val voiceControlOptions = listOf("On" to "1", "Off" to "0")`
- `GlassesSettingsActivity.kt:357` `cxr.setVoiceCtrl(new.voiceControl)`

So the value passed is the dropdown string `"1"` (On) or `"0"` (Off).

### SDK definition (decompiled from CXR-M `client-m-1.0.8` runtime jar)

JAR: `~/.gradle/caches/8.13/transforms/dc137431737018687a749730b08132eb/transformed/client-m-1.0.8-runtime.jar`
Class: `com/rokid/cxr/client/extend/CxrApi.class`

`javap -c -p` of `public ValueUtil$CxrStatus setVoiceCtrl(java.lang.String)`:

```
  ldc "settings_voice_control"   // key
  JSONObject.put("key",   "settings_voice_control")
  JSONObject.put("value", <arg>)             // the "1"/"0" string, verbatim
  // wrapped in a JSONArray -> paramsJson = [{"key":"settings_voice_control","value":"<v>"}]
  Caps.write("Settings_Update")
  Caps.write(paramsJson)
  CxrController.getInstance().request(C, "Settings", caps, null)
```

i.e. it constructs a CXR `Caps` = `["Settings_Update", "[{\"key\":\"settings_voice_control\",\"value\":\"<v>\"}]"]`
and sends it on the `"Settings"` channel. No javadoc in the jar; the parameter is an
opaque String forwarded as the setting value. Return is a `CxrStatus` (REQUEST_FAILED on
exception). There is also a sibling `setLongPressFun(String)` built the same way.

## 2. Glasses side — what receives it and what it toggles

Receiver app: `RokidSpriteAssistServer` (decompiled under
`Recon/rokid-docs/yodaos/DECOMPILED-APPS/product/app/RokidSpriteAssistServer/`).

`jadx/.../com/rokid/sprite/bluetooth/manager/CXRServiceManager.java`:
- `:95` `KEY_BLUETOOTH_SETTINGS_UPDATE = "Settings_Update"`
- `:1205 SettingsModeReceived(...)`: reads `caps.at(0)` (key) and `caps.at(1)` (jsonStr);
  if key == `Settings_Update` → `RKSettingsManager.getInstance().update(jsonStr)` and forwards
  to launcher via `ServerToLauncherUtils.sendAppSettings`.

`jadx/.../com/rokid/os/sprite/basic/settings/RKSettingsManager.java`:
- `:175 update(jsonStr)`: parses the JSONArray, builds `key->value` map, persists each entry
  to `SharedPreferences` (`settingsPrefs`, field `PREFS_NAME`), and `notifyListeners(key, old, new)`
  for changed keys. `specialData()` only special-cases store-demo-mode, NOT voice_control, so
  `settings_voice_control` passes straight through and is **persisted to SharedPreferences**.

`jadx/.../com/rokid/os/sprite/assist/instruct/InstructService.java`:
- `:45 SETTINGS_VOICE_CONTROL = "settings_voice_control"`
- `:47-48 VOICE_CONTROL_VALUE_CLOSE = "close"`, `VOICE_CONTROL_VALUE_OPEN = "open"`
- `:130` at startup: `mOffLineIgnore = !"open".equals(RKSettingsManager.get(SETTINGS_VOICE_CONTROL, "open"))`
  → **default value is `"open"`**, so default `mOffLineIgnore = false` (offline voice ENABLED by default).
- `:137 offLineManager = new OffLineManager(handler, mOffLineIgnore)`
- `:153-166` registers a listener on `settings_voice_control`; on change:
  `mOffLineIgnore = !"open".equals(newValue); voicePowerControl(mOffLineIgnore)`.
- `:307 voicePowerControl(boolean open)` → `offLineManager.voicePowerControl(open)`.

`jadx/.../com/rokid/os/sprite/assist/instruct/offline/OffLineManager.java`:
- `:30 offLineSdk : RtInstructSdk`; constructed `:80` with
  `ofFirmwareControl(0, lang)` + `ofPowerControl(offLineIgnore)`, then `startSdk()`.
- `:150-162 voicePowerControl(open)` → `offLineSdk.sendVoiceControl(callId, gson(ofPowerControl(open)))`.

`jadx/.../com/rokid/os/sprite/assist/instruct/offline/VoiceControlItem.java`:
- `:99-101 ofPowerControl(boolean open)` → `new VoiceControlItem("lowpower_mode", new ControlParam(0, null, open, ...))`
  i.e. it issues a **`lowpower_mode` firmware control** to the offline engine to gate it.

So `settings_voice_control` toggles **Rokid's offline (on-device) voice/wakeword engine**
inside `RokidSpriteAssistServer`, driven through `RtInstructSdk` →
`librt_instruct.so` + `libaudigent.so` (`agt_voice_control` / `sendVoiceControl`).
This is Rokid's built-in wakeword/assistant, exactly the thing we want to suppress.

## 3. Default state + persistence

- Default = **ON** (`get(SETTINGS_VOICE_CONTROL, "open")` → `"open"` → `mOffLineIgnore=false`,
  engine started enabled at `InstructService.onCreate`). So out of the box Rokid offline voice
  is RUNNING.
- **Persistent across reboot**: stored in `RKSettingsManager`'s SharedPreferences and reloaded
  on next `onCreate`. It does NOT need to be re-sent every session once written — but our phone
  app DOES re-sync it on connect (`PhoneBtHost.kt:2153`), which is harmless.
- No dedicated `getprop` was found tying to this; it's an app-level SharedPreferences setting,
  not an Android system property. (The `vendor.rkd.*` props are unrelated.)

## 4. Relationship to the ACD / sound_trigger HAL crash

- Our crash path is `PAL_STREAM_ACD` via `sound_trigger.primary.neo.so` (PAL/AGM HAL).
- Rokid's voice control path is `RtInstructSdk`/`libaudigent.so` (`agt_*`), an **offline DSP
  firmware engine** addressed with `change_firmware` / `lowpower_mode` scene commands.
- `strings` over `librt_instruct.so` and `libaudigent.so` returned **no** `sound_trigger`,
  `PAL_STREAM_ACD`, `st_session`, `sthal`, or `pal_` symbols. → Different engine, different HAL.
- Conclusion: leaving Rokid voice control ON is **not** what opens the PAL ACD stream that
  SIGABRTs ours. They are independent. (Caveat: both ultimately contend for the mic/DSP audio
  front-end, so there can still be mic-routing contention, but not a shared sound_trigger
  session.)

## 5. Is forcing OFF safe? Side effects?

- To genuinely DISABLE Rokid offline voice, the persisted value must be **anything other than
  the exact string `"open"`** (e.g. `"close"` or `"0"`). That sets `mOffLineIgnore=true` and
  sends `lowpower_mode(true)` to the offline engine → Rokid wakeword powered down.
- It only gates the offline voice/wakeword engine. It does NOT touch the microphone device,
  audio HAL, A2DP, BT, or our app's pipeline. So our own WakeWordPipeline/ACD and mic capture
  are unaffected.
- It does NOT disable the Rokid OS assistant UI suppression we may rely on elsewhere — this
  setting is purely the offline-instruct engine power gate.
- **Watch-out (the value bug):** the phone currently sends `"1"` for "On". Because the glasses
  only treat `"open"` as enabled, the phone "On" (`"1"`) actually leaves Rokid voice DISABLED,
  and "Off" (`"0"`) also disabled. The dropdown is effectively "off / off" today. If we want a
  guaranteed permanent hard-disable, sending `"0"` (or better, the explicit `"close"`) is safe
  and idempotent and will keep Rokid offline voice off. There is no value other than literal
  `"open"` that turns it on, so `"0"` can be sent unconditionally with no risk of accidentally
  enabling it.

### Recommendation
Hard-disable is safe. Always send `setVoiceCtrl("0")` (or `"close"`) on connect. It powers
down Rokid's offline wakeword (`lowpower_mode`), persists across reboot, is independent of the
crashing sound_trigger/ACD HAL, and does not disturb the mic, BT, or our own pipeline. If we
ever needed Rokid voice back, only the literal `"open"` re-enables it.

## Evidence file paths

- Phone: `AI/clients/phone/app/src/main/java/com/repository/listener/bt/PhoneBtHost.kt:2151-2157`
- Phone: `AI/clients/phone/app/src/main/java/com/repository/listener/ui/GlassesSettingsActivity.kt:61,357`
- Phone: `AI/clients/phone/app/src/main/java/com/repository/listener/config/AppConfig.kt:294`
- CXR SDK jar: `~/.gradle/caches/8.13/transforms/dc137431737018687a749730b08132eb/transformed/client-m-1.0.8-runtime.jar` → `com/rokid/cxr/client/extend/CxrApi.class` (`setVoiceCtrl`)
- Glasses recv: `Recon/rokid-docs/yodaos/DECOMPILED-APPS/product/app/RokidSpriteAssistServer/jadx/sources/com/rokid/sprite/bluetooth/manager/CXRServiceManager.java:95,1205`
- Settings store: `.../RKSettingsManager.java:175,214`
- Engine gate: `.../assist/instruct/InstructService.java:45-48,130,153-166,307`;
  `.../offline/OffLineManager.java:30,80,150-162`; `.../offline/VoiceControlItem.java:99-106`
- Native engine libs (no sound_trigger): `.../RokidSpriteAssistServer/apktool/lib/arm64-v8a/librt_instruct.so`, `.../libaudigent.so`
- Our glasses app (currently a TODO, not wired): `AI/clients/glasses/app/src/main/java/com/repository/glasses/listener/config/GlassesConfig.kt:44,85-89,127,145`
