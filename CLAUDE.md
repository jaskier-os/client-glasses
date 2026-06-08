# CLAUDE.md -- client-glasses

Guidance for Claude Code working in this repo. This is the **glasses half** of a
glasses + phone AR assistant. Listener app for Rokid AR glasses (YodaOS /
Android 12, arm64): it captures mic audio, runs on-device wake-word + VAD, takes
photos, draws an on-glasses overlay, does on-device face detection (ReID), and
relays messages/files to the companion phone over Bluetooth RFCOMM and Wi-Fi
Direct. It does NOT talk to the backend directly -- the phone app relays to the
orchestrator. The phone app is in the `client-phone` repo.

For the whole-system map (orchestrator, agents, port allocation, communicator),
see the `jaskier-os/orchestrator` repo's CLAUDE.md / docs rather than duplicating
it here.

**Listener package:** `com.repository.glasses.listener`

IMPORTANT: NEVER USE EMOJIS ANYWHERE IN LOGGING, CODE, OR OTHER TEXT.

IMPORTANT: Treat this codebase as work in progress. Never do
backwards-compatibility or legacy support unless explicitly asked to. Remove code
that becomes redundant. After any replacement, remove every orphan (field,
comment, import) that references the old system.

## Modules

Gradle multi-module project (`settings.gradle.kts`): `:app`, `:bt-manager`,
`:capture`, `:filesync`, `:glasses-tracing`. The `sthal/`, `*-daemon/`,
`wake-word-training/`, `*-test*/`, `KOTI_AEC/`, `WebRTC_AECM/`, `external/`,
`nightvision-asset/`, `test/`, `docs/` trees are research/native/test tooling,
not part of the APK.

| Module | Type | Role |
|---|---|---|
| `app/` | listener APK (`com.repository.glasses.listener`) | Main glasses app: UI (`MainActivity`, default process) + `:backend` service (`ListenerService`). Bundles wake-word/VAD models + native C++ via CMake/NDK. |
| `bt-manager/` | priv-app APK (`com.repository.glasses.btmanager`) | System-app Bluetooth broker. Wraps hidden `BluetoothA2dpSink` / `BluetoothHeadsetClient` reflection, RFCOMM sockets, BLE advertising/wake, plus `ProfileAutoConnector` (HFP+A2DP after pairing). Listener binds via `IBtManager.aidl`. Needs `BLUETOOTH_PRIVILEGED` (`bt-manager/priv-permissions.xml`). |
| `capture/` | priv-app APK (`com.repository.glasses.capture`) | Out-of-process camera/HUD capture service (`ICapture.aidl`). Isolates Camera2 + MediaCodec so a capture crash can't take the listener down. No privapp-permissions XML -- plain `adb install` is correct for capture (it is a normal user app). |
| `filesync/` | priv-app APK (`com.repository.glasses.filesync`) | WiFi P2P file/log sync server. Hosts the persistent log at `/sdcard/Download/glasses-client.log` over HTTP on port 8848. Needs WiFi privileged perms (`filesync/priv-permissions.xml`). |
| `glasses-tracing/` | shared Android lib (Kotlin) | `GT.section` / `GT.counter` Perfetto helpers. Consumed by `app`, `bt-manager`, `capture`, `filesync`. See "Perfetto Tracing". |
| `glasses-power-daemon/` | native arm64 C binary (`/system/bin/glasses-power-daemon`) | Root daemon: screen timeout, fold/take-off suspend, time sync, `enforce_psensor`/`enforce_hall` unlatch on boot, LED battery indicator, tombstone collection. Init `class core`, auto-respawn. Has its own CLAUDE.md. |
| `touchpad-daemon/` | native arm64 C binary (`/system/bin/rokid-touchpad-daemon`) + patched `psoc_ts_drv_right.ko` | Grabs `/dev/input/event1` from the PSoC touchpad, emits filtered/scaled `KEY_KP*` events on a uinput device, defers `KEY_PROG1`. Also contains the PSoC firmware-patch pipeline. Has its own CLAUDE.md. |
| `sthal/` | sound trigger HAL (`sound_trigger.primary.neo.so`) + Sirenev wakeword models | Replaces Rokid's stock sound-trigger HAL with a SoC-DSP-offloaded one. Baked into super_4 via `root-firmware.sh`. Own privapp-permissions XML (`sthal/priv-permissions.xml`). |
| `wake-word-training/` | Python | Wake-word model training/augmentation (`augment_samples.py`, `generate_clips.py`, `stress_test.py`, etc.). Models land in `app/`. |
| `touchpad-test-app/`, `led-cam-test/` | standalone APKs | Diagnostic harnesses. Not deployed in production. |
| `KOTI_AEC/`, `WebRTC_AECM/` | C/C++ source + JNI | AEC variants; `WebRTC_AECM/` is the live AECM path (`audio/WebRtcAecm.kt`). `KOTI_AEC/` is reference. |
| `external/` | vendored deps | Currently `speexdsp`. |
| `nightvision-asset/` | binary asset + deploy script | `nightvision_unet.onnx` for the Night Vision tab + pusher. |
| `test/` | Python pytest harness | E2E tests, `adb/` helpers. |
| `docs/` | Markdown | Hardware reference (touchpad, mic audio, Rokid service binding). |

## Architecture: Separate Processes

The listener app runs as TWO separate Android processes:
- **ListenerService** (`:backend` process) -- backend: BT communication, audio
  streaming, camera capture, ReID, screen recording, TTS playback, Rokid OS
  integration, notification overlay, all device commands. ~3500 lines.
- **MainActivity** (default process) -- UI only. Multi-tab interface
  (chat, chat list, ReID, todo, night vision, translation, map, teleprompter).

They do NOT share memory. Data flows ListenerService -> MainActivity via local
broadcasts (`sendBroadcast` / `BroadcastReceiver`) carrying Intent extras
(strings, not object references). A broadcast only reaches MainActivity if its
receiver is registered for that action.

**Auto-start:** `BootReceiver` starts ListenerService and launches MainActivity
on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`.

## Source Structure (listener app)

```
app/src/main/java/com/repository/glasses/listener/
  MainActivity.kt              -- UI, tab navigation, focus state machine, key input
  GlassesListenerApp.kt        -- Application class
  service/ListenerService.kt   -- Backend process. BT, audio, camera, ReID, recording, TTS
  bt/        GlassesBtClient.kt, BtProtocol.kt, MessageRelay, BtManagerBridge (RFCOMM relay)
  capture/   AudioRecorder.kt, CameraCapturer.kt, ArCameraPreview.kt, HudRecorder.kt
  reid/      ReidController.kt (ML Kit face detection), ReidCameraCapturer.kt
  nightvision/ NightVisionPreview.kt
  rokid/     RokidServiceBridge.kt (AIDL to MasterAssistService), RokidNavigationController.kt, AssistantSuppressor.kt
  audio/     TtsPlayer.kt, OpusEncoder, SpeexEchoCanceller, WebRtcAecm.kt
  config/    GlassesConfig.kt (model, deviceId, notification duration, wearing sensitivity)
  boot/      BootReceiver.kt
  ui/        ChatAdapter, ChatListAdapter, TeleprompterController, TodoChecklistAdapter,
             NotificationOverlay (WindowManager overlay), ScrollDrainer, Lum.kt, etc.
  util/      LogCollector.kt, ScreenStateReceiver.kt
```

### UI Tabs

`TabId` enum: `CHAT`, `CHAT_LIST`, `REID`, `TODO`, `NIGHTVISION`, `TRANSLATE`,
`MAP`, `TELEPROMPTER`. Default active: `TODO, CHAT, CHAT_LIST, TRANSLATE,
TELEGRAM, REID`. `MAP`/`TELEPROMPTER`/`NIGHTVISION` are added/removed
dynamically. `FocusState` machine has a `*_FOCUSED` state per tab plus `TAB_NAV`
and `STOP_MODAL`.

**Adding a dynamic tab:** the selected tab is identified by `currentTabId: TabId`,
not a numeric index. The pill highlight sits at
`activeTabs.indexOf(currentTabId) * TAB_SLOT_DP`. To add/remove without breaking
pill placement:
1. Add the value to the `TabId` enum.
2. `show<X>Tab()`: build the icon View, `insertTabFrameAt(activeIdx, frame)`,
   then `activeTabs.add(activeIdx, TabId.<X>)`.
3. `hide<X>Tab()`: capture `idx = activeTabs.indexOf(TabId.<X>)`, remove the icon,
   then `activeTabs.remove(TabId.<X>)`.
4. **Always** finish with exactly one `afterTabsChanged(switchToAdded = TabId.<X>)`
   (add) or `afterTabsChanged(removedAt = idx)` (remove). That hook resizes the
   pill container, picks the surviving `TabId` to focus, and re-anchors
   `pillHighlight.translationX`. Mutating `activeTabs` without it desyncs the pill
   -- you'll see a `NAV: pill width mismatch` warning in `glasses-client.log`.

## Build

Build with **JDK 17**. JDK 21 breaks R8 on the minified debug variant. The debug
variant is the one we ship (minify + shrink enabled). Native code is `arm64-v8a`
only -- you need the Android SDK + NDK.

```bash
./gradlew :app:assembleDebug      # listener APK only
./gradlew assembleDebug           # all modules
./gradlew clean
```

The APK needs no server endpoint or secrets -- all transport is local and the
peer is negotiated at runtime. The only build requirement is the SDK path: copy
`local.properties.example` to `local.properties` and set `sdk.dir` (or
`ANDROID_HOME` / `ANDROID_SDK_ROOT`).

## Deploy (MANDATORY -- read before installing anything)

ALWAYS deploy via the in-repo script. Never run `gradlew` install tasks or plain
`adb install` by hand for the priv-app packages.

```bash
bash scripts/deploy-to-glasses.sh
```

It builds debug, then installs `app`, `bt-manager`, `capture`, `filesync` via
ADB (or via the phone relay if no direct ADB). It installs the priv-app modules
(`listener`, `bt-manager`, `filesync`) via the
`/data/local/diy-overlay/system/priv-app/<pkg>/<apk>` overlay slot using an
in-place `cat > target` rewrite (a direct `adb push` to the busy bind-mount
silently no-ops), removes any `/data/app` sideload shadows, reboots once, waits
for `sys.boot_completed=1`, verifies each priv-app resolved to
`/system/priv-app/`, then applies runtime grants (`MANAGE_EXTERNAL_STORAGE`,
`WRITE_SETTINGS`, `NETWORK_SETTINGS`, `OVERRIDE_WIFI_CONFIG`,
`WRITE_SECURE_SETTINGS`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`),
notification-listener allow, accessibility enable, and the HOME role. `capture`
is a normal user app and gets `adb install -r`.

IMPORTANT: NEVER bypass `scripts/deploy-to-glasses.sh`, and NEVER use plain
`adb install` / `adb install -r` / `pm install` for any package shipping a
privapp-permissions XML (`com.repository.glasses.listener`,
`com.repository.glasses.filesync`, `com.repository.glasses.btmanager`). Those
packages MUST be installed via the priv-app overlay slot followed by a reboot.
`adb install` puts the APK under `/data/app/`, which shadows the priv-app slot
and silently strips signature/privileged grants (`BLUETOOTH_PRIVILEGED`,
`NETWORK_SETTINGS`, `OVERRIDE_WIFI_CONFIG`, `CHANGE_WIFI_STATE`, etc.). Symptoms:
pairing / `setScanMode` SecurityException on listener, `setWifiEnabled` returns
false on filesync, audio sync stalls in IDLE, BT bridge ops fail. The other
correct path is `Recon/rokid-docs/yodaos-root-full/root-firmware.sh --post-flash`
after reflashing super_4. Both apply ALL required grants/appops/roles
automatically; never reapply them by hand and never skip the reboot.

IMPORTANT: When changing the AndroidManifest.xml of any priv-app deployed via the
overlay (`listener`, `filesync`, `btmanager`), BUMP `versionCode` (and ideally
`versionName`) in that module's `build.gradle.kts`. The priv-app slot's APK is
bind-mounted at the same path each boot; if `versionCode` doesn't change, PMS
reuses the cached parse from `/data/system/package_cache/` and ignores the new
manifest -- the classic symptom is a `foregroundServiceType` mismatch crash where
PMS reports the OLD type. Bumping `versionCode` forces re-parse. The cache can
also be cleared with `adb shell su 0 rm -rf /data/system/package_cache/*` +
reboot, but bumping the version is the durable fix and should be the default for
any manifest-affecting change.

IMPORTANT: NEVER use `pm clear`, `pm uninstall`, `adb shell wipe`, or any
destructive app-data command unless the user explicitly asks. These destroy user
settings, permissions, and cached state. Always use the overlay reinstall path
(it keeps data).

## ADB via USB Cable

When the glasses are on a USB cable, direct ADB access is available (`adb
devices` shows the glasses serial). This enables direct logcat, screencap, and
hardware control otherwise impossible over BT. Always pass `-s <serial>`. (Serials
have changed over time; read it from `adb devices` rather than hard-coding.)

### Device Info

- **Model:** RG-glasses (Rokid AR Lite, OEM RV101). **Android:** 12 (API 32).
- **SoC:** Qualcomm "neo", 4x Cortex-A55. **RAM:** 1.7 GB. **Storage:** 19 GB.
- **Display:** 480x640 @ 240dpi, JBD4020 Micro-LED waveguide (right eye), up to 144 Hz.
- **Camera:** 1x front, HAL v3.7, RAW + HDR.
- **Mics:** built-in 8-channel (16 kHz, PCM16) + back mic (mono/stereo, 8k-48k).
- **Sensors:** ICM-4x6xx IMU, proximity (UCS146E0, also wear detection), ambient
  light. **No magnetometer.**
- **BT:** A2DP Sink, HFP HF -- glasses are the audio receiver from the phone.
- **WiFi:** 802.11, WiFi Aware, WiFi Direct.

### Logcat / Screenshot

```bash
adb logcat -s "GlassesListener:*" --pid=$(adb shell pidof com.repository.glasses.listener)
adb shell screencap -p /sdcard/test.png && adb pull /sdcard/test.png /tmp/glasses_screenshot.png
```

## Logging -- External File + WiFi P2P Pull

On USB, direct ADB logcat works. Otherwise (BT-only) glasses logcat is invisible
from the PC and the persistent file is the only path. All logs go to
`LogCollector.writeExternal()` -> `/sdcard/Download/glasses-client.log`. Pull it
from the phone over WiFi P2P (the glasses HTTP server is on port 8848); the phone
repo's `test/adb/pull_glasses_log.sh` does this, BT-independent.

- **In ListenerService:** use `btLog(msg)` / `btErr(msg)` -- both write to the
  external file only (no BT push).
- **In other classes** (RokidServiceBridge, CameraCapturer, ReidController...):
  accept a `remoteLog: ((String) -> Unit)?` and call it for ALL logging; the
  service wires it as `{ btLog(it) }`.
- **In MainActivity:** `LogCollector.i()` for UI-only logs (stay on logcat).

There is no real-time stream. The BT log relay was removed for battery reasons:
it pinned the RFCOMM connection awake during idle (~1.6 lines/s), preventing
bt-manager's idle-teardown timer from releasing `hal_bluetooth_lock`.

## LED Control

RGB + White LEDs. Two access paths:

1. **Rokid `lights_ctrl` service** (`com.rokid.light.ILightsCtrl` AIDL). LED IDs
   (bitwise): RED=1, GREEN=2, BLUE=4, WHITE=8. Transaction codes: 1 setBrightness,
   2 setFlashing, 3 pulse, 4 turnOn, 5 turnOnAll, 6 turnOff, 7 turnOffAll, 8
   sendEvent(eventType,eventId), 9 cancelEvent. Each stock `sendEvent` lights
   exactly ONE channel, so it CANNOT show two colors at once; raw
   `turnOn`/`setBrightness`/`turnOnAll` are no-ops on this firmware. eventType =
   `floor(eventId/1000)`. Known-good: GREEN `sendEvent(1,1013)`, RED
   `sendEvent(3,3016)`, BLUE `sendEvent(4,4011)`, WHITE `sendEvent(2,2014)`.
   ```bash
   adb shell "service call lights_ctrl 8 i32 3 i32 3021"   # AI wake
   adb shell dumpsys lights_ctrl
   ```
2. **Direct sysfs (ROOT only)** `/sys/class/leds/{red,green,blue,white}/brightness`
   (0..255). The four are separate emitters: writing red=255 + green=255 shows
   both dots at once (NOT a blended amber). Direct sysfs is the ONLY way to show
   two colors simultaneously.

NEVER clear `dalvik-cache` on the glasses -- it crashes the OS immediately. Just
reboot; caches regenerate.

### Battery-charge indicator

While charging AND sitting still off-head, the LED shows battery level (GREEN
>=45%, RED+GREEN 15-45%, RED <15%), re-asserted every 5s via direct sysfs.
Ownership is split: only root can write `/sys/class/leds` and only the app can
read the IMU. The `glasses-power-daemon` owns the LED (reads `capacity` +
charger `online`, writes red/green). The listener owns the "should it be on"
decision (`BatteryLedControl.kt`) and signals via flag file
`/data/local/diy-overlay/glasses-led-battery-arm` (`1`=arm). The daemon
inotify-watches the dir. The app arms only after charging AND the IMU reports
STILL for >=60s continuous (`BatteryLedArmer.kt` + `StillnessSensor.kt`), so worn
glasses (always micro-moving) never reach 60s-still and the LED never shows while
worn (privacy). Charging detection uses charger `online` (cable present), NOT
`status` (which flaps every ~1s during trickle). `StillnessSensor` uses the
WAKE-UP sensor variant so motion detection survives Doze.

## Wear Detection

There are TWO proximity sensors; only one reflects the wearer:
- **Sensortek UCS146E0** (`Sensor.TYPE_PROXIMITY`, I2C-2 @ 0x38) is physically
  blind to the wearer (constant `values[0]=5.0`). Do not use.
- **PSoC 4000R capacitive** (`/sys/.../i2c-1/1-0008/`) is the real wear sensor.

Signal chain: PSoC -> kernel extcon uevent (DOCK=spread, JIG=wear) at
`/sys/.../1-0008/extcon/extcon3/state` -> RokidSysConfig.apk / PsensorObserver ->
broadcast `com.rokid.sprite.ACTION_TAKE_STATUS_CHANGED` (extra
`glasses_take_state` = "1"/"0") -> property `vendor.rkd.glasses.is_take_on`. The
listener's `WearSensor` subscribes to the broadcast and reads the property once
at start(). No polling, no sysfs reads.

**Fold authority is `vendor.rkd.glasses.is_spread`** (1=unfolded, 0=folded), NOT
the raw hall sysfs (its polarity is inverted -- hall=1 is unfolded-worn). Using
raw hall made the power daemon spuriously broadcast folded=true every ~3min ->
A2DP teardown + volume reset.

**The enforce-latch trap:** `.../1-0008/enforce_psensor` and `enforce_hall` pin
the extcon state high forever when =1 (PsensorObserver never fires). Stock
firmware ships them =1, which is why `is_take_on` appears stuck at 1 on fresh
boot. `glasses-power-daemon` writes 0 to both at startup. Also
`persist.rkd.enablePsensor` must not be explicitly `"false"`. The WearSensor path
ONLY works if `glasses-power-daemon` has run since boot.

```bash
adb shell cat /sys/.../i2c-1/1-0008/enforce_psensor   # want 0
adb shell cat /sys/.../i2c-1/1-0008/extcon/extcon3/state
adb shell getprop vendor.rkd.glasses.is_take_on
adb shell getprop vendor.rkd.glasses.is_spread
```

## Input Devices + Touchpad Daemon

- **ROKID,PSOC-TP-R** (`/dev/input/event1`) -- capacitive touch on right temple,
  Cypress PSoC 4000R MCU on I2C-1 @ 0x08. Emits **discrete keycodes only** (no
  raw coords): `KEY_UP/DOWN/LEFT/RIGHT/ENTER/BACK` (1-finger) and
  `KEY_F13/F14/PROG1/PROG2/PROG3/DASHBOARD` (2-finger).
- **qpnp_pon** (`/dev/input/event0`) -- power/functional button (`KEY_VOLUMEDOWN`,
  `KEY_MENU`). No volume buttons exist on these glasses.

`touchpad-daemon/` builds `rokid-touchpad-daemon` (`/system/bin/`), which:
1. `EVIOCGRAB`s `/dev/input/event1` so Android never sees raw PSoC keycodes.
2. Parses driver `pre_position`/`p_delta` from `/dev/kmsg` for finger position.
3. Scales scroll-step by sample time-gap (velocity proxy; `--step-slow 40
   --step-fast 7`).
4. Emits synthetic keycodes on uinput `rokid-touchpad-virt`: `KEY_KP0`=scroll
   forward, `KEY_KP1`=scroll backward, `KEY_KP2`=touch released; proxies
   `KEY_DASHBOARD/ENTER/BACK/F13/F14/PROG1-3` unchanged.
5. Defers `KEY_PROG1` (long-press -> Rokid AI) by 400 ms; drops it if motion
   arrives in the grace window (slow drags no longer trigger AI).

App side (`MainActivity.onKeyDown`) maps `NUMPAD_0 -> DPAD_RIGHT`, `NUMPAD_1 ->
DPAD_LEFT`, `NUMPAD_2` -> silent consume. The listener's `when()` blocks alias
`DPAD_RIGHT|DOWN` and `DPAD_LEFT|UP`, so one horizontal remap covers tab
switching, subtab nav, and scrolling. Smooth scroll routes through
`ui/ScrollDrainer.kt` (accumulate pixels, drain ~25%/frame) -- calling
`smoothScrollBy` directly cancels its prior animation and collapses bursts.
`MainActivity.SCROLL_THROTTLE_MS` is pinned to 0 (the daemon already paces
events). The PSoC firmware-patch pipeline + RE facts are in
`touchpad-daemon/CLAUDE.md`.

## DIY Firmware Overlay (persistent root / app substitution)

The rooted firmware + DIY overlay live in
`Recon/rokid-docs/yodaos-root-full/` (reference: `OVERLAY-README.md` there). TL;DR:

- `root-firmware.sh` builds a patched `super_4.img` + `rawprogram_dev.xml`. It
  injects `diy-overlay.sh` + `diy-overlay.rc` (Magisk-style bind-mount overlay
  walking `/data/local/diy-overlay/<abs-path>` at post-fs-data), `enter-edl`
  (arms the EDL cookie, no test-points), the patched `psoc_ts_drv_right.ko`, and
  `rokid-touchpad-daemon`.
- `flash.sh` -> `sudo qdl --storage emmc xbl_s_devprg_ns.melf rawprogram_dev.xml
  patch0.xml`. After flashing, `adb shell enter-edl` drops to EDL without
  test-points.

IMPORTANT (root/recovery guardrails):
- NEVER flash the active A/B boot slot -- always flash untested images to the
  inactive slot first.
- NEVER modify / flash `vbmeta` / `vbmeta_system` with modified contents (instant
  brick); they stay stock. The flow REQUIRES `abl_old.elf` (1.17 abl) flashed
  alongside the rooted super_4 -- the rooted super_4 bootloops on 1.18's strict
  abl. NEVER flash full stock 1.18 without re-running root-firmware's super4
  rawprogram afterwards.
- NEVER manipulate USB state programmatically (no `usbreset`, unbind/bind,
  `udevadm`). Report and ask the user to physically unplug/replug.
- After any reflash/reboot, poll `getprop sys.boot_completed` (with a fastboot
  check) before doing post-flash work; STOP and report if the device didn't boot.

**Overlay first, reflash last.** For anything that changes between dev runs
(daemon binaries, model files, config text), push into
`/data/local/diy-overlay/<abs-path>` and `adb reboot`. Only reflash super_4 when:
adding a new bind-mount rule to `diy-overlay.rc`, a new stub target file that
didn't exist in `/system/`, a new `service` entry, or changing build.prop /
SELinux / the `root-firmware.sh` patch set.

## Rokid Waveguide Display Rules

Monochrome green micro-LED waveguide. Black (#000000) = pixels OFF =
transparent/see-through; any non-black pixel = green light. Luminance hierarchy
in `Lum.kt` (GLOW > BRIGHT > MID > DIM > SOFT > GHOST > TRACE > off).

Required attributes for any scrollable/focusable View:
```xml
android:focusable="false"
android:focusableInTouchMode="false"
android:defaultFocusHighlightEnabled="false"
android:overScrollMode="never"
android:scrollbars="none"
```
Theme must force all accent/highlight colors to black (`colorPrimary`,
`colorPrimaryDark`, `colorAccent`, `android:colorEdgeEffect` ->
`@android:color/black`). Every View needs explicit `android:background="#000000"`.
Never use transparency/alpha -- the hardware handles see-through naturally when
pixels are black. Key/scroll events go through `Activity.onKeyDown()`, so
`focusable="false"` on RecyclerView does not break navigation.

Glasses JPEGs must be physically pixel-rotated 90 CCW before JPEG encode -- never
rely on EXIF orientation alone.

## Input Handling

Override `Activity.onKeyDown()`; return `true` to consume. Mapping is
context-dependent on `FocusState`: TAB_NAV (DPAD_RIGHT/LEFT switch tabs,
DPAD_CENTER enters focused mode); CHAT_FOCUSED (scroll); TELEPROMPTER_FOCUSED
(toggle pause / speed); per-tab focused states; BACK is layered (cancel session >
unfocus > hide app); double-tap DPAD_CENTER during LISTENING/RESPONDING cancels
the session. Always verify input mappings in the existing `onKeyDown` handler
before assuming a mapping.

Touchpad gesture -> keycode mapping (raw, before the daemon remaps): tap=NUMPAD_2,
hold(500ms)=NUMPAD_3 (then NUMPAD_2 on release), scroll=NUMPAD_0/1. No sustained
DPAD_CENTER. Hold(NUMPAD_3) opens AI chat; intercept it conditionally for new
hold gestures.

Unregistering inputs: Rokid OS binding -- `RokidServiceBridge` binds
`MasterAssistService` via AIDL, auto-reconnects on disconnect (3s) unless
explicitly unbound (`unRegisterClient` + `unbindService`). Broadcast receivers --
register in `onStart`/`onResume`, unregister in `onStop`/`onPause`.

## Perfetto Tracing (GT.* slices)

`glasses-tracing/.../GT.kt` is the shared helper consumed by app, bt-manager,
capture, filesync (`implementation(project(":glasses-tracing"))`). Use
`GT.section("subsystem.op") { ... }` (inline), `GT.begin/end`,
`GT.beginAsync/endAsync(name, cookie)`, `GT.counter(name, value)`. Every slice is
prefixed `gt.`; select all app slices with `WHERE s.name LIKE 'gt.%'`.
Subsystems: `gt.svc.*`, `gt.bt.*`, `gt.audio.*`, `gt.cap.*`, `gt.ui.*`,
`gt.input.*`, `gt.head.*`, `gt.sync.*`, `gt.tts.*`.

Config gotchas (do NOT "simplify"): both process names
(`com.repository.glasses.listener` AND `...listener:backend`) must be in the
Perfetto `atrace_apps:` list -- dropping `:backend` silently vanishes all
ListenerService slices. Keep `atrace_categories:` SMALL (working baseline: `am`,
`view`, `gfx`, `input`, `binder_driver`); ~18+ categories silently disable
userspace atrace entirely on this Android 12 build.

## Idle Power Floor

`AGMIPC@1.0-service`, `audio.service_64`, `audioserver` keep ~36 s CPU per 15 min
idle even after correct `AudioRecord.stop()`+`release()`. Cause: Qualcomm AGM
keeps the audio graph's clock domain powered for fast restart; no public API
forces HAL power-collapse and the vendor PAL `Pal_StreamClose` is internal-only.
This is an irreducible floor on this device. Do NOT add reflection workarounds
(they fail silently). App-side wear-gating / demand-mic
(`ListenerService.reconcileMicStream`) is the actionable lever.

## Audio / BT gotchas

- WiFi P2P requires Location Services. Android 10+ P2P fails with reason=0 when
  Location is off -- check first.
- The companion phone advertises over BT as **"iPhone 14 Pro"** (it is the POCO
  M7 Pro). It IS the companion phone -- never reject it.
- NEVER disable BT on the glasses (`svc bluetooth disable` / adapter cycling) --
  it kills pairing, connections, and audio profiles.
- AI responses are always English; only user prompts are Russian.
- Audio ducking: local STREAM_MUSIC duck (50%) + `AudioTrack.setVolume(2.0)`
  compensation; AVRCP syncs phone volume to glasses (cross-device ducking
  disabled). The BT APK is platform-signed and can't be patched.

## Dependencies

- `com.google.mlkit:face-detection` -- on-device ReID face detection.
- AndroidX: core-ktx, appcompat, constraintlayout, lifecycle-service, recyclerview.
- AIDL: `IBtManager`, `ICapture`, `IAssistServer`/`IAssistClient` (Rokid
  MasterAssistService).
