# Beamforming API Research: agt_voice_control

## 1. Initialization Sequence

The full sequence in Rokid's code is:

```
RtInstructSdk constructor:
  1. initSdk(jsonConfig)    -- native, calls agt_init in libaudigent.so
  2. startSdk()             -- native, calls agt_start in libaudigent.so

Then for scene changes:
  3. sendVoiceControl(callId, jsonPayload)  -- native, calls agt_voice_control
```

**File:** `RtInstructSdk.java` (line 36-44)
- Constructor takes: `String str` (JSON config), `SpeechSceneCallback callback`
- Calls `initSdk(str)` immediately
- Then `startSdk()` is called by OffLineManager after construction (line 114 of OffLineManager.java)

**OffLineManager.java** (line 80-114) shows the init config:
```java
// Init JSON = Gson serialization of a list containing:
//   VoiceControlItem.ofFirmwareControl(0, languageId)  -- scene 0 (idle), language "en" or "zh"
//   VoiceControlItem.ofPowerControl(offLineIgnore)      -- power control
new RtInstructSdk(gson.toJson(CollectionsKt.arrayListOf(
    companion.ofFirmwareControl(0, languageId),
    VoiceControlItem.INSTANCE.ofPowerControl(this.offLineIgnore)
)), callback);
rtInstructSdk.startSdk();
```

## 2. JSON Format for sendVoiceControl

### VoiceControlItem structure (serialized by Gson):
```json
{
  "cmd": "change_firmware",
  "params": {
    "scene": <int>,      // 0=idle, 1=cardioid, 2=omni, 3=conference
    "lang": "<string>",  // "en" or "zh" (or "" when not changing language)
    "enable": false       // always false for firmware control
  }
}
```

**Source files:**
- `VoiceControlItem.java` (line 103-106): `ofFirmwareControl(sceneId, language)` creates `new VoiceControlItem("change_firmware", new ControlParam(sceneId, language, false, 4, null))`
- `ControlParam.java`: data class with fields `scene` (int), `lang` (String), `enable` (boolean)

### Power control variant:
```json
{
  "cmd": "lowpower_mode",
  "params": {
    "scene": 0,
    "lang": "",
    "enable": true/false
  }
}
```

### How changeSpeechControl sends it (OffLineManager.java line 140-148):
```java
int newCallId = INSTANCE.getNewCallId();  // monotonically incrementing int
callMap.put(newCallId, new RequestCallBack(sceneId, callback));
offLineSdk.sendVoiceControl(newCallId, gson.toJson(VoiceControlItem.ofFirmwareControl(sceneId, language)));
```

So `sendVoiceControl(int callId, String jsonPayload)` takes:
- `callId`: an incrementing integer (starts at 0, incremented each call)
- `jsonPayload`: the Gson-serialized VoiceControlItem shown above

## 3. Scene IDs (confirmed from RtInstructSdk.java lines 11-14)

```java
AGT_SPEECH_SCENE_INTERACTION = 0   // idle/default
AGT_SPEECH_SCENE_TRANSLATION = 1   // cardioid beamforming (directional)
AGT_SPEECH_SCENE_CALL = 2          // omni-directional
AGT_SPEECH_SCENE_CONFERENCE = 3    // conference mode
```

## 4. How TranslateScene triggers it

`TranslateScene.java` method `onSpeechSceneChange(boolean open)`:
- If open && audio mode == "orientation": `changeSpeechScene(1)` (cardioid)
- If open && audio mode == "omni": `changeSpeechScene(2)` (omni)
- If !open: `changeSpeechScene(0)` (idle)

The setting key is `settings_translate_audio_mode` with values `"orientation"` or `"omni"`.

## 5. Can We Load the .so Files From Our App?

### Critical blocker: `/dev/rt600_spidev`

`libaudigent.so` communicates with the RT600 DSP chip via `/dev/rt600_spidev` (SPI device).
This is a hardware device node that requires:

1. **SELinux access** - the `audigent_service` class inside libaudigent talks to hardware directly
2. **Device file permissions** - `/dev/rt600_spidev` is likely restricted to `system` uid
3. **AssistServer runs as `android.uid.system`** (sharedUserId in manifest)

### Library dependency chain:
```
librt_instruct.so -> libaudigent.so -> /dev/rt600_spidev (SPI to RT600 DSP)
                  -> liblog.so, libm.so, libdl.so, libc.so (standard Android)
```

Both libs have minimal dependencies (only standard Android libs). No HIDL/AIDL binder dependencies.

### Loading from our app process:
- **Technically possible** to `System.load("/path/to/librt_instruct.so")` after extracting from the APK
- **Will likely fail** when libaudigent tries to open `/dev/rt600_spidev` due to:
  - SELinux denial (our app's SELinux context != system_app)
  - Unix permissions on the device node
  - Possible singleton conflict if AssistServer already has it open

### JNI registration:
- `librt_instruct.so` uses `JNI_OnLoad` for dynamic registration (not static naming)
- It registers native methods for `RtInstructSdk` class specifically
- The JNI methods call back into Java (`onInstructCall`, `onSceneCall`, `onErrorCall`) on the RtInstructSdk instance

## 6. Alternative Approaches (ranked by feasibility)

### Option A: Shell command via `adb shell` or `su` (EASIEST)
Since we have root, we could write a small native binary that calls `agt_init`, `agt_start`, `agt_voice_control` directly via libaudigent.so, running as root to bypass SELinux/permissions. Or use `LD_PRELOAD` tricks.

### Option B: Bind to InstructService via AIDL/broadcast
InstructService is a Service in AssistServer. However:
- It returns `null` from `onBind()` (line 65-68) -- NO bound service interface
- It registers itself via `BasicApplication.setInstructServer(this)` -- app-internal only
- No broadcast receiver for scene changes found

### Option C: Settings-based trigger
TranslateScene watches `settings_translate_audio_mode` via RKSettingsManager. We could:
1. Write to the settings database to trigger the translate scene
2. Then change the audio mode setting to "orientation" for cardioid

But this requires the translate scene to be running, and involves Rokid's whole scene management.

### Option D: Direct native caller binary (BEST FOR US)
Write a small C program or use our existing root access:
```c
// Link against libaudigent.so
void* ctx = NULL;
agt_init(init_json);  // same JSON as RtInstructSdk constructor
agt_start();
agt_voice_control(voice_control_json, ctx);  // JSON with scene=1
```

Run as root from our app via `su -c /data/local/tmp/beamform_ctl 1`.

### Option E: Replicate RtInstructSdk in our app
Copy the JNI approach: bundle librt_instruct.so + libaudigent.so in our APK, create a Java wrapper mimicking RtInstructSdk. Run our app as system uid (we already have priv-app capabilities). Risk: conflict with AssistServer holding the SPI device.

### Option F: Use the existing AssistServer
The AssistServer's `changeAudioSceneIdGlobal(int)` method on IInstructServer interface is accessible if we can get a reference. CXRServiceManager calls it via BT commands from the phone. We could send the same BT command format. The key is `KEY_BLUETOOTH_SYS_CHANGE_AUDIO_SCENE_ID` with `Trans_SceneId` param.

## 7. Key Files Reference

| File | Path |
|------|------|
| RtInstructSdk.java | `.../jadx/sources/com/rokid/os/sprite/assist/instruct/offline/RtInstructSdk.java` |
| OffLineManager.java | `.../jadx/sources/com/rokid/os/sprite/assist/instruct/offline/OffLineManager.java` |
| InstructService.java | `.../jadx/sources/com/rokid/os/sprite/assist/instruct/InstructService.java` |
| VoiceControlItem.java | `.../jadx/sources/com/rokid/os/sprite/assist/instruct/offline/VoiceControlItem.java` |
| ControlParam.java | `.../jadx/sources/com/rokid/os/sprite/assist/instruct/offline/ControlParam.java` |
| TranslateScene.java | `.../jadx/sources/com/rokid/os/sprite/assist/instruct/scene/scene/TranslateScene.java` |
| IInstructServer.java | `.../jadx/sources/com/rokid/os/sprite/basic/server/IInstructServer.java` |
| CXRServiceManager.java | `.../jadx/sources/com/rokid/sprite/bluetooth/manager/CXRServiceManager.java` |

All under: `/media/varingait/Lobotomite/Repository/Recon/rokid-docs/yodaos/DECOMPILED-APPS/product/app/RokidSpriteAssistServer/`
