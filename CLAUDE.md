# CLAUDE.md

Companion client for Rokid AR glasses. Kotlin, native Android (no React Native). Communicates with the phone app over BT via CXR-S SDK. Phone handles wake word detection, VAD, recording, transcription, AI orchestration, and relays results back. Glasses handle audio streaming, TTS playback, camera capture, on-device face detection (ReID), screen/HUD recording, navigation, translation display, teleprompter, notifications, and a multi-tab UI.

**Package:** `com.repository.glasses.listener`

## ReID OSINT feature flags

The russia-specific ReID OSINT / intel feature is gated by a build flag that
defaults OFF. Set it in `local.properties` (or as an env var of the same name --
env wins); it is declared in `app/build.gradle.kts` and read as `BuildConfig.*`.
Documented in `local.properties.example`.

This repo's flag:

- **`ENABLE_REID_OSINT`** (default `false`) -- enables the person "intel" (OSINT)
  request + intel modal (`requestPersonIntel` in `MainActivity.kt`, gated on
  `BuildConfig.ENABLE_REID_OSINT`). The core ReID tab and on-device face
  recognition are NOT gated and work regardless.

### Related switches in other repos

The feature spans the whole stack; flipping it on end-to-end means setting flags
in three repos:

- **client-phone** (`local.properties` / env, `BuildConfig.*`):
  - `ENABLE_REID_RU_TABS` -- shows the ReID "Phone Numbers" sub-tab + person
    "Intel" tab in the phone UI (`ReidSubTabAdapter.kt`, `ReidFragment.kt`,
    `PersonDetailTabAdapter.kt`, `PersonDetailActivity.kt`).
  - `ENABLE_REID_OSINT` -- enables the assistant-driven OSINT lookup tool
    (`lookup_person_info` -> `searchPersonInfo`) in `ListenerService.kt`. Core
    face re-id (`identify_person`) is NOT gated.
- **reid/reid-analytics backend** (`backend/.env`): `ENABLE_OSINT` -- mounts the
  OSINT/sherlock API routes (`osint-photos`/`osint-reports`, `search-phone`,
  `persons/batch-phone-lookup`, `persons/:id/search-info`) in
  `backend/routes/reidRoutes.js`. When off, those routes are not mounted and
  return 404.
- **reid/reid-analytics frontend** (`frontend/.env`): `VITE_ENABLE_OSINT` -- shows
  the person "OSINT" tab + sections in `PersonDetail.jsx`.

The glasses (and phone) OSINT lookups call the reid-analytics backend as their
data source, so flipping only the client flag without the backend's `ENABLE_OSINT`
yields 404s.

**To enable the whole feature:** phone `local.properties`
`ENABLE_REID_RU_TABS=true` + `ENABLE_REID_OSINT=true`; glasses `local.properties`
`ENABLE_REID_OSINT=true`; reid-analytics backend `.env` `ENABLE_OSINT=true`;
reid-analytics frontend `.env` `VITE_ENABLE_OSINT=true`.

## Architecture: Separate Processes

The app runs as TWO separate Android processes:
- **ListenerService** (`:backend` process) -- background service handling BT communication, audio streaming, camera capture, ReID, screen recording, TTS playback, Rokid OS integration, notification overlay, and all device commands. This is the backend.
- **MainActivity** (default process) -- UI only. Multi-tab interface with chat, chat list, ReID, todo, night vision, translation, map, and teleprompter views. Communicates with ListenerService via local broadcasts (`sendBroadcast`/`BroadcastReceiver`).

They do NOT share memory. Data flows from ListenerService to MainActivity via Intent extras (strings, not object references). A broadcast sent from ListenerService only reaches MainActivity if the receiver is registered for that action.

**Auto-start:** `BootReceiver` starts ListenerService and launches MainActivity on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`.

## Modules

Top-level Gradle subprojects + bundled native libraries living in this directory. Most are referenced elsewhere in this file; this section just gives a one-line tour so nothing stays unexplained.

| Module | Type | Role |
|---|---|---|
| `app/` | listener APK (`com.repository.glasses.listener`) | The main glasses app (UI + `:backend` service). Everything in "Source Structure" below lives here. |
| `bt-manager/` | priv-app APK (`com.repository.glasses.btmanager`) | System-app Bluetooth broker. Wraps hidden `BluetoothA2dpSink` / `BluetoothHeadsetClient` reflection, RFCOMM sockets, BLE advertising/wake, plus `ProfileAutoConnector` that brings HFP+A2DP up after pairing. Listener binds via `IBtManager.aidl`. Needs `BLUETOOTH_PRIVILEGED` (privapp-permissions XML in repo root). |
| `capture/` | priv-app APK (`com.repository.glasses.capture`) | Out-of-process camera/HUD capture service exposing `ICapture.aidl`. Isolates Camera2 + MediaCodec from the listener process so a capture crash can't take the listener with it. |
| `filesync/` | priv-app APK (`com.repository.glasses.filesync`) | WiFi P2P file/log sync server. Hosts the persistent log at `/sdcard/Download/glasses-client.log` over HTTP on port 8848 (used by `test/adb/pull_glasses_log.sh`). |
| `glasses-power-daemon/` | native arm64 C binary (`/system/bin/glasses-power-daemon`) | Root daemon for screen timeout, fold/take-off shutdown, time sync, and the `enforce_psensor`/`enforce_hall` unlatch on boot. Init service `class core`, auto-respawn. |
| `glasses-tracing/` | shared Android library (Kotlin) | The `GT.section` / `GT.counter` Perfetto helpers. Consumed by `app`, `bt-manager`, `capture`, `filesync` so all four APKs emit slices with the `gt.` prefix. See "Perfetto Tracing" below. |
| `sthal/` | sound trigger HAL (`sound_trigger.primary.neo.so`) + Sirenev wakeword models | Replaces Rokid's stock sound-trigger HAL with our SoC-DSP-offloaded one. Baked into super_4 via `root-firmware.sh`. Has its own privapp-permissions XML. |
| `touchpad-daemon/` | native arm64 C binary (`/system/bin/rokid-touchpad-daemon`) + patched `psoc_ts_drv_right.ko` | Grabs `/dev/input/event1` from the PSoC touchpad and emits filtered/scaled `KEY_KP*` events on a uinput device. Also defers `KEY_PROG1` to suppress accidental Rokid AI triggers. See "Touchpad Daemon + App-Side Scroll". |
| `touchpad-test-app/` | standalone APK | Diagnostic harness for the touchpad daemon -- visualizes incoming key events. Not deployed in production. |
| `led-cam-test/` | standalone APK | Diagnostic harness for the LED ring + camera HAL. Not deployed in production. |
| `external/` | vendored deps | Currently just `speexdsp`. |
| `nightvision-asset/` | binary asset + deploy script | `nightvision_unet.onnx` ONNX model for the Night Vision tab, plus `deploy-nightvision-asset.sh` to push it to the glasses. |
| `raw_data/` | captured PCM | Sample microphone recordings used by AEC bring-up tests. |
| `test/` | Python pytest harness | E2E tests, `adb/` helpers, `fn-button-daemon.sh`. |
| `docs/` | Markdown notes | Hardware reference docs (touchpad, mic audio, Rokid service binding). |

## Source Structure

```
app/src/main/java/com/repository/glasses/listener/
  MainActivity.kt              -- UI, tab navigation, focus state machine, key input
  GlassesListenerApp.kt        -- Application class
  service/
    ListenerService.kt         -- Backend process (~3500 lines). BT, audio, camera, ReID, recording, TTS
  bt/
    GlassesBtClient.kt         -- CXRServiceBridge wrapper for BT communication
    BtProtocol.kt              -- Channel names and message format constants
  capture/
    AudioRecorder.kt           -- Mic audio capture
    CameraCapturer.kt          -- Camera2 API photo/video capture
    ArCameraPreview.kt         -- AR camera preview surface
    HudRecorder.kt             -- HUD overlay recording
  reid/
    ReidController.kt          -- Face detection controller (ML Kit)
    ReidCameraCapturer.kt      -- Camera feed for ReID pipeline
  nightvision/
    NightVisionPreview.kt      -- Night vision camera mode
  rokid/
    RokidServiceBridge.kt      -- AIDL binding to MasterAssistService
    RokidNavigationController.kt -- Rokid OS navigation launch
    AssistantSuppressor.kt     -- Suppresses Rokid's built-in assistant
  audio/
    TtsPlayer.kt               -- TTS audio playback
  config/
    GlassesConfig.kt           -- Runtime settings (model, deviceId, notification duration, wearing sensitivity)
  boot/
    BootReceiver.kt            -- Auto-start on boot / package update
  ui/
    ChatAdapter.kt, ChatListAdapter.kt, ChatMessage.kt, ChatSummaryItem.kt
    TeleprompterController.kt  -- Teleprompter scroll/pause/speed control
    TodoChecklistAdapter.kt, TodoItem.kt
    TelegramSavedAdapter.kt, TelegramMessage.kt
    NotificationOverlay.kt     -- WindowManager overlay for phone notifications
    SpinnerView.kt, Lum.kt, Anim.kt, BitmapUtils.kt, MessageItemAnimator.kt
  util/
    LogCollector.kt            -- In-memory + external file log collector
    ScreenStateReceiver.kt     -- Screen on/off events
```

## UI Tabs

`TabId` enum: `CHAT`, `CHAT_LIST`, `REID`, `TODO`, `NIGHTVISION`, `TRANSLATE`, `MAP`, `TELEPROMPTER`

Default active tabs: `TODO, CHAT, CHAT_LIST, TRANSLATE, TELEGRAM, REID`. Others (`MAP`, `TELEPROMPTER`, `NIGHTVISION`) are added/removed dynamically when their features activate.

`FocusState` machine: `TAB_NAV`, `CHAT_FOCUSED`, `LIST_FOCUSED`, `MAP_FOCUSED`, `STOP_MODAL`, `TRANSLATE_FOCUSED`, `TELEPROMPTER_FOCUSED`, `REID_FOCUSED`, `TODO_FOCUSED`, `NIGHTVISION_FOCUSED`

### Adding a new dynamic tab

The selected tab is identified by `currentTabId: TabId`, not by a numeric index. The pill highlight (`pillHighlight`) sits at `activeTabs.indexOf(currentTabId) * TAB_SLOT_DP` and is rebuilt every time the tab list mutates. To add a new dynamically-toggled tab without breaking pill placement:

1. Add the value to the `TabId` enum in `MainActivity.kt`.
2. In `show<X>Tab()`: build the icon `View`, insert it into `tabIconsRow` via `insertTabFrameAt(activeIdx, frame)`, then `activeTabs.add(activeIdx, TabId.<X>)`.
3. In `hide<X>Tab()`: capture `val idx = activeTabs.indexOf(TabId.<X>)`, remove the icon from `tabIconsRow`, then `activeTabs.remove(TabId.<X>)`.
4. **Always** finish with exactly one call to `afterTabsChanged(switchToAdded = TabId.<X>)` (on add, when the new tab should auto-focus) or `afterTabsChanged(removedAt = idx)` (on remove). That single hook resizes the pill container, picks the right surviving `TabId` to focus, and re-anchors `pillHighlight.translationX` on its icon -- so the pill always ends up under the currently selected tab regardless of where the mutation happened.

Mutating `activeTabs` without calling `afterTabsChanged` desyncs the pill from the icon row. The pill's `applyPillAndTints` step logs a `NAV: pill width mismatch` warning to `glasses-client.log` when this happens, which is your signal to add the missing call.

## Deploy to Glasses

Builds all four modules (bt-manager + capture + filesync + app), installs the
priv-app APKs via the `/system/priv-app` overlay slot, reboots so PMS re-applies
the privapp-permissions, and applies runtime grants. Requires the glasses
connected over USB (the script auto-detects them by `ro.product.model`).

```bash
bash /media/user/Lobotomite/Repository/AI/clients/glasses/scripts/deploy-to-glasses.sh
```

## ADB via USB Cable

When glasses are connected via USB cable, direct ADB access is available (`adb devices` shows `<GLASSES_SERIAL>`). This enables direct debugging, app install, screencap, and hardware control that is otherwise impossible over BT.

### Device Info

- **Model:** RG-glasses (Rokid Glasses, OEM model RV101)
- **Android:** 12 (API 32)
- **SoC:** Qualcomm ("neo" board), 4x Cortex-A55
- **RAM:** 1.7 GB
- **Storage:** 19 GB internal
- **Display:** 480x640 @ 240dpi, JBD JBD4020 Micro-LED waveguide (right eye), 60 Hz
- **Camera:** 1x world-facing (back), HAL v3.7
- **Microphones:** Built-in DMIC array (up to 8 lines wired; default AudioRecord path captures mono 16 kHz PCM 16-bit) + back mic (mono/stereo, 8k-48k)
- **Sensors:** ICM-4x6xx IMU (accel + gyro), proximity sensor (Sensortek UCS146E0 -- aimed outward, NOT a wear sensor; actual wear detection is the PSoC capacitive channel, see Wear Detection). No magnetometer (Game Rotation Vector only).
- **BT:** A2DP Sink, HFP HF -- glasses act as audio receiver from phone
- **WiFi:** 802.11, WiFi Aware, WiFi Direct supported

### ADB Deploy (USB cable)

```bash
# Build + direct install (keeps app data)
/media/user/Lobotomite/Repository/AI/clients/glasses/gradlew -p /media/user/Lobotomite/Repository/AI/clients/glasses assembleDebug && adb install -r /media/user/Lobotomite/Repository/AI/clients/glasses/app/build/outputs/apk/debug/app-debug.apk
```

### ADB Logcat (USB cable)

```bash
# Direct logcat -- works only when connected via USB
adb logcat -s "GlassesListener:*" --pid=$(adb shell pidof com.repository.glasses.listener)
```

### Screenshot

```bash
adb shell screencap -p /sdcard/test.png && adb pull /sdcard/test.png /tmp/glasses_screenshot.png
```

### LED Control

RGB + White LEDs controlled via Rokid `lights_ctrl` service (`com.rokid.light.ILightsCtrl` AIDL).

**LED IDs** (bitwise flags): RED=1, GREEN=2, BLUE=4, WHITE=8, RGB_MASK=7

**Service call transaction codes:**

| Code | Method | Args |
|---|---|---|
| 1 | `setBrightness` | i32 lightId, f brightness (0.0-255.0) |
| 2 | `setFlashing` | i32 lightId, i32 onMS, i32 offMS |
| 3 | `pulse` | i32 lightId, i32 durationMS |
| 4 | `turnOn` | i32 lightId |
| 5 | `turnOnAll` | none |
| 6 | `turnOff` | i32 lightId |
| 7 | `turnOffAll` | none |
| 8 | `sendEvent` | i32 eventType, i32 eventId |
| 9 | `cancelEvent` | i32 eventType, i32 eventId |

**Event types:** NORMAL=1, WARN=2, SPECIAL=3, BLUETOOTH=4, OTHER=5

**Common events:** AI_WAKE=3021, AI_LISTEN=3012, BT_CONNECT=4011, CAMERA_OPEN=2014, PHONE_RING=3017

```bash
# Example: trigger AI wake LED pattern
adb shell "service call lights_ctrl 8 i32 3 i32 3021"

# Cancel it
adb shell "service call lights_ctrl 9 i32 3 i32 3021"

# Check current LED state
adb shell dumpsys lights_ctrl
```

**Programmatic access (Kotlin):** Use `LightsCtrl` static wrapper from `com.rokid.light.LightsCtrl` (framework class). Service obtained via `ServiceManager.getServiceOrThrow("lights_ctrl")`.

Decompiled source reference: `Recon/rokid-docs/yodaos/DECOMPILED-APPS/system/framework/jadx/framework/sources/com/rokid/light/`

#### Direct sysfs channels (RGBW)

The LED is physically four independent single-color emitters on the mp2724 charger PMIC, exposed as sysfs nodes (ROOT-only writable):

| Node | Range |
|---|---|
| `/sys/class/leds/red/brightness` | 0..255 |
| `/sys/class/leds/green/brightness` | 0..255 |
| `/sys/class/leds/blue/brightness` | 0..255 |
| `/sys/class/leds/white/brightness` | 0..255 |

Writing multiple channels lights them **simultaneously** -- e.g. `red=255` + `green=255` shows both a red dot and a green dot at once. They are separate emitters, NOT a blended amber. Direct sysfs writes are the **only** way to show two colors at once.

#### sendEvent vs sysfs

- **`lights_ctrl sendEvent(eventType, eventId)`** (txn 8; cancel via txn 9) drives the LED through firmware EventFactoryV4. Each stock event lights exactly **one** channel (its internal `mLightId` is a single-bit mask), so `sendEvent` CANNOT show two colors at once.
- The raw `turnOn` / `setBrightness` / `turnOnAll` transactions (4 / 1 / 5) are **no-ops** on this firmware -- they return success but do nothing.
- eventType convention: `floor(eventId / 1000)`. A mismatched type/id hits the firmware default branch and produces no light.

Known-working steady `sendEvent` colors:

| Color | Call(s) |
|---|---|
| GREEN | `sendEvent(1,1013)` or `sendEvent(3,3018)` |
| RED | `sendEvent(3,3016)` |
| BLUE | `sendEvent(4,4011)` |
| WHITE | `sendEvent(2,2014)` or `sendEvent(3,3021)` |
| BREATHING GREEN | `sendEvent(3,3017)` (PHONE_RING) |

Full event -> color -> animation table: `Recon/rokid-docs/LED-EVENT-TABLE.md`. The capture app's privacy light uses the WHITE channel via `lights_ctrl turnOn(id=8)`; the battery indicator deliberately avoids blue/white to not collide.

#### Battery-charge indicator

While charging and sitting still (off-head), the LED shows battery level by color, re-asserted every 5s via direct sysfs writes:

| Battery level | Color (channels) |
|---|---|
| >= 45% | GREEN |
| 15-45% | RED + GREEN (both dots) |
| < 15% | RED |

**Ownership split.** Only root can write `/sys/class/leds`, and only the app can read the IMU, so the work is split:

- The native root `glasses-power-daemon` owns the LED. It reads battery `capacity` and charger `online` from `/sys/class/power_supply/` and writes the red/green channels directly (`led_*` + `read_battery_pct` / `read_is_charging` + `led_tick` in `glasses-power-daemon/src/main.c`).
- The listener app owns the "should it be on" decision and signals via a flag file (`BatteryLedControl.kt`).

**Flag-file IPC.** `/data/local/diy-overlay/glasses-led-battery-arm`, one char: `1` = arm, `0` or absent = disarm. The daemon `inotify`-watches the directory and reacts immediately (instant off on movement).

**Arming rule.** The app arms only after charging AND the IMU reports STILL for >= 60s continuously (`BatteryLedArmer.kt` + `StillnessSensor.kt`). Any movement or unplug disarms instantly. Because worn glasses always micro-move, they never reach 60s-still, so the LED never shows while worn (privacy).

**Gotcha: `online`, not `status`.** Charging detection uses the charger `online` node (cable present), NOT `status`. The mp2724 `status` string flaps between "Charging" / "Not charging" every ~1s during trickle at high SoC, which would strobe the LED; `online` stays `1` the whole time the cable is in.

**Gotcha: wake-up IMU sensor.** The `StillnessSensor` uses the WAKE-UP sensor variant so motion detection survives screen-off Doze. (On USB the device never truly suspends, so only Doze applies.) The non-wakeup variant stops delivering during idle and would freeze the stillness verdict.

Design/plan docs: `clients/glasses/docs/plans/2026-06-06-battery-charging-led-design.md` and `...-led.md`.

### Wear Detection

There are **two** proximity sensors on this hardware, and only one of them reflects the wearer:

| Chip | Where | What we found |
|---|---|---|
| Sensortek UCS146E0 (IR) | Behind SLPI / `Sensor.TYPE_PROXIMITY`, I2C-2 @ 0x38 | **Physically blind to the wearer.** Emits the same `values[0]=5.0` constantly whether on-head or off. Aimed outward, not toward the face. Do not use. |
| PSoC 4000R (capacitive) | `/sys/devices/platform/soc/a90000.i2c/i2c-1/1-0008/` | Detects contact with the head via the temple-side psensor channel. This is the real wear sensor. |

#### Signal chain (canonical)

```
PSoC capacitive psensor
  -> kernel extcon uevent (DOCK=spread, JIG=wear) at
     /sys/devices/platform/soc/a90000.i2c/i2c-1/1-0008/extcon/extcon3/state
  -> RokidSysConfig.apk / PsensorObserver
  -> broadcast com.rokid.sprite.ACTION_TAKE_STATUS_CHANGED
       extra glasses_take_state = "1" | "0"
  -> property vendor.rkd.glasses.is_take_on
```

`com.repository.glasses.listener`'s `WearSensor` subscribes to the broadcast and reads the property once at start() for the initial state. No polling, no sysfs reads, no sensor HAL.

#### The enforce-latch trap

The PSoC driver exposes two sysfs knobs that pin the extcon state high and break the whole chain:

| sysfs | Effect when = 1 |
|---|---|
| `.../1-0008/enforce_psensor` | Latches `JIG` / `psensor` bits high forever; PsensorObserver never fires |
| `.../1-0008/enforce_hall` | Latches `DOCK` / `hall` bits high forever |

On stock firmware these ship set to `1`, which is why `vendor.rkd.glasses.is_take_on` appears stuck at `1` on a fresh boot and why `is_take_on` never transitions even when the glasses are taken off and put back on. `glasses-power-daemon` writes `0` to both at startup so the extcon pipeline actually works.

Also relevant:
- `persist.rkd.enablePsensor` must not be explicitly `"false"`. The default is true; an empty value still loads PsensorObserver. When explicitly disabled, RokidSysConfig force-writes `is_take_on=1` and `is_spread=1`.
- Rokid plays a distinctive earcon on wear transitions (sound effect 18 on put-on, 56 on take-off) and calls `PowerManager.wakeUp("psensor")`. If you hear those chimes, PsensorObserver is alive.

#### Quick on-device checks

```bash
# Is the latch off (required)?
adb shell cat /sys/devices/platform/soc/a90000.i2c/i2c-1/1-0008/enforce_psensor   # want: 0
adb shell cat /sys/devices/platform/soc/a90000.i2c/i2c-1/1-0008/enforce_hall      # want: 0

# Live extcon state
adb shell cat /sys/devices/platform/soc/a90000.i2c/i2c-1/1-0008/extcon/extcon3/state
# DOCK=1 JIG=1 when worn + unfolded; JIG=0 when taken off

# Rokid-derived props
adb shell getprop vendor.rkd.glasses.is_take_on
adb shell getprop vendor.rkd.glasses.is_spread
```

#### Hardware dependency on the power daemon

The WearSensor broadcast path only works if `glasses-power-daemon` has run since boot -- it's the thing that unlatches the enforce flags. The daemon is registered as an init `service glasses-power-daemon` (class core) inside `diy-overlay.rc`, baked into super by `root-firmware.sh`, so init launches it automatically at boot and auto-respawns it if it dies. Binary location: `/system/bin/glasses-power-daemon`.

For development iteration without reflashing super, push the new build to `/data/local/diy-overlay/system/bin/glasses-power-daemon` and `adb reboot`; the `diy-overlay-walker` bind-mounts the tier-2 copy over the baked one at `post-fs-data`.

`deploy.sh run` inside `glasses-power-daemon/` is still useful for quick manual iterations without rebooting: it `pkill -9`'s the live instance and launches a fresh one from `/data/local/tmp/glasses-power-daemon`. The singleton flock on `/data/local/tmp/glasses-power-daemon.lock` prevents the runtime copy from racing with the init-managed one after reboot.

### Input Devices

- **ROKID,PSOC-TP-R** (`/dev/input/event1`) -- capacitive touch sensor (swipe/tap on right temple). Driven by a Cypress PSoC 4000R MCU on I2C-1 addr `0x08`. Emits **discrete keycodes only** (no raw coordinates): `KEY_UP/DOWN/LEFT/RIGHT/ENTER/BACK` for 1-finger gestures and `KEY_F13/F14/PROG1/PROG2/PROG3/DASHBOARD` for 2-finger gestures (`two_finger_click_en`/`two_finger_flick_en` already `1` in firmware). Raw-coordinate access, sysfs tuning knobs, and brick-risk list documented in `docs/rokid-touchpad.md`.

### Touchpad Daemon + App-Side Scroll

`touchpad-daemon/` builds a native arm64 C binary (`rokid-touchpad-daemon`) that sits in front of the PSoC chip. Installed to `/system/bin/rokid-touchpad-daemon` via the firmware builder (see below) and auto-started at post-fs-data.

What the daemon does:

1. `EVIOCGRAB`s `/dev/input/event1`, so Android never sees the raw PSoC keycodes.
2. Parses the driver's `pre_position` / `p_delta` messages from `/dev/kmsg` to get approximate finger position during motion.
3. Measures the time-gap between kmsg samples as a velocity proxy; scales the effective scroll-step accordingly (`--step-slow 40 --step-fast 7` by default).
4. Emits synthetic keycodes on a `uinput` virtual keyboard `rokid-touchpad-virt`:
   - `KEY_KP0` (NUMPAD_0) = scroll step forward
   - `KEY_KP1` (NUMPAD_1) = scroll step backward
   - `KEY_KP2` (NUMPAD_2) = touch released
   - Proxies `KEY_DASHBOARD`, `KEY_ENTER`, `KEY_BACK`, `KEY_F13/F14`, `KEY_PROG1-3` unchanged.
5. Defers `KEY_PROG1` (long-press -> Rokid AI assistant) by 400 ms. If any motion arrives in the grace window, the event is dropped (slow drags no longer trigger AI). Otherwise the key is proxied through as a legitimate long-press.

App side (`MainActivity.onKeyDown`) maps `NUMPAD_0 -> DPAD_RIGHT`, `NUMPAD_1 -> DPAD_LEFT`, `NUMPAD_2` -> silent consume. The glasses listener's existing `when()` blocks pair `DPAD_RIGHT|DOWN` and `DPAD_LEFT|UP` as aliases, so one horizontal remap covers tab switching, subtab nav, and content scrolling.

For smooth scrolling under rapid daemon-emitted key bursts, every `smoothScrollBy` call routes through `ui/ScrollDrainer.kt` -- it accumulates pixels into a per-view buffer and drains ~25% per animation frame via `scrollBy`. Calling `smoothScrollBy` directly cancels its prior animation on each call, which collapses 15 rapid keystrokes into ~2 visible items of scroll; the drainer guarantees every pixel renders.

`MainActivity.SCROLL_THROTTLE_MS` is pinned to `0`: the daemon already paces events based on finger velocity, so any app-side throttle just swallows legit events during fast swipes.

### DIY Firmware Overlay (persistent root / app substitution)

The rooted Rokid firmware + our DIY overlay mechanism live in `Recon/rokid-docs/yodaos-root-full/`. Reference: `OVERLAY-README.md` in that directory. TL;DR:

- `root-firmware.sh` -- builds a patched `super_4.img` + `rawprogram_dev.xml`. Binary-patches build.prop for root/SELinux-permissive, regenerates the AVB hashtree, and (via `debugfs`) injects into `/system/`:
  - `/system/bin/diy-overlay.sh` + `/system/etc/init/diy-overlay.rc` -- Magisk-style bind-mount overlay that walks `/data/local/diy-overlay/<abs-path>` at post-fs-data.
  - `/system/bin/enter-edl` -- Android-side helper that arms the Qualcomm download cookie and reboots to 9008 (no test-points needed).
  - `/system/lib/modules/psoc_ts_drv_right.ko` -- patched touchpad driver, bind-mounted over the stock `vendor_dlkm` one via an `on init` directive.
  - `/system/bin/rokid-touchpad-daemon` -- bundled so it auto-launches at boot.
- `flash.sh` -- `sudo qdl --storage emmc xbl_s_devprg_ns.melf rawprogram_dev.xml patch0.xml` to flash the output.

Once installed, `adb shell enter-edl` drops to EDL without hardware test-points. Never modify vbmeta / vbmeta_system -- they stay stock. The metadata partition is reflashed by `rawprogram_dev.xml` to clear any sticky `disable-verity` state.

#### Development workflow: overlay first, reflash last

**Default to the DIY overlay for iteration.** Reflashing is expensive (EDL + ~1 min qdl + boot + state loss risk) so do it only once per shipped config. For anything that changes between dev runs -- daemon binaries, model files, stub libraries, config text -- push into `/data/local/diy-overlay/<abs-path>` and `adb reboot`. The `.rc` file baked into super does a root-NS `mount none SRC DST bind` so the bind propagates to every process including init-launched services.

**Only reflash super_4.img when:**
1. Adding a new bind-mount rule to `diy-overlay.rc` (init parses `.rc` only at boot).
2. Adding a new stub target file that didn't previously exist in `/system/` (DIY overlay is file-over-file, can't create new inodes).
3. Adding a new `service <name> /system/bin/...` entry (init registers services only at boot).
4. Changing build.prop / SELinux / other items in the `root-firmware.sh` patch set.

**For a new user-space binary** the pattern is:
1. First time: bake a last-known-good copy at `/system/bin/<binary>` via `root-firmware.sh`, add `service` entry, add `mount none /data/local/diy-overlay/system/bin/<binary> /system/bin/<binary> bind` in `diy-overlay.rc` post-fs-data. Reflash once.
2. Every subsequent iteration: `adb push new-build /data/local/diy-overlay/system/bin/<binary> && adb reboot`. No reflash.

The `rokid-touchpad-daemon` setup is the canonical example of this hybrid pattern.

- **qpnp_pon** (`/dev/input/event0`) -- power/functional button. Emits `KEY_VOLUMEDOWN`, `KEY_MENU`.

### Rokid System Services

```bash
adb shell service list | grep rokid   # lights_ctrl (ILightsCtrl)
adb shell getprop | grep rkd          # Rokid-specific properties
```

### Hardware Docs Reference

Detailed hardware documentation: `Recon/rokid-docs/yodaos/docs/hardware/` (audio, display, sensors)

## Sideloading (deploy/control the glasses through the phone, no USB)

Lets a **desktop deploy to and control the glasses over LAN + WiFi-Direct via the phone**,
replacing the adb-USB cable. `scripts/deploy-to-glasses-via-phone.sh` is the over-the-air sibling
of `deploy-to-glasses.sh`: it builds the APKs and runs the whole priv-app overlay install + grants
+ reboot + verify with the glasses never on adb. The phone-side half (LAN server, the
"Enable sideloading" toggle, the BT/WiFi-Direct forwarder, the desktop HTTP API) is documented in
the **phone app CLAUDE.md ("Sideloading")**; this section covers the glasses-side internals.

### Glasses-side architecture

The **filesync APK** is the glasses end. When the listener app receives `enable_sideloading=true`
on the `CH_SETTINGS` BT channel it tells filesync (over the `IFileSync` AIDL
`setSideloadEnabled`), which makes `http/FileHttpServer.kt` accept the `POST /sideload/*` routes.
The same `WifiDirectHost` that serves photo/log pulls hosts the Group-Owner HTTP server on
**:8849**; the phone opens/closes that link over BT via the `listener_sideload` channel (handled in
the app by `sync/SideloadChannelHandler.kt`, which drives the shared filesync
`openWifiDirectForSync`/`closeWifiDirect` -- WiFi is toggled ONLY by `WifiDirectHost`, never
duplicated).

All privileged work (writes into /system, `pm install`, grants, reboot, arbitrary shell) runs as
**root via the `appsud` daemon** (see "Glasses App Root" below) -- the filesync app uid is not
itself root.

### Glasses HTTP routes (filesync FileHttpServer, port 8849)

| Route | Purpose |
|---|---|
| `POST /sideload/upload` (header `X-Upload-Name`, raw body) | stream to the app-private staging dir; returns `{ok,path,size,sha256}` |
| `POST /sideload/exec` (`{cmd}`) | **synchronous** root exec, capped at `SYNC_EXEC_CAP_MS`=120s; returns `{rc,stdout,stderr,truncated}` |
| `POST /sideload/exec/start` (`{cmd}`) -> `{job}` | **async** root exec, no time limit |
| `POST /sideload/exec/poll` (`{job,stdoutFrom,stderrFrom}`) | incremental `{running,rc,stdoutB64,stderrB64,stdoutTotal,stderrTotal,truncated,error}` |
| `POST /sideload/cleanup` | wipe staging |

All routes 403 unless sideloading is currently enabled. No auth beyond that flag (the device
single-user-trusts on-device callers by design).

### Sideloading exec internals (run ANY command, including long-running)

Root commands run through the `appsud` daemon's **streaming frame protocol** (see "Glasses App
Root"). filesync's `ExecJob` (inner class in `FileHttpServer.kt`) holds the appsud LocalSocket on a
background thread and consumes frames into rolling 16 MiB stdout/stderr buffers, so the HTTP layer
never blocks on the command. The desktop polls `/sideload/exec/poll` for incremental output +
final `rc`; output is returned **base64** so it is binary/UTF-8 clean across poll boundaries. There
is no exec time limit -- long commands stream until they exit. Jobs are killed on session stop and
retired (with a staging wipe) once the desktop has drained all output.

Two gotchas learned during bring-up (don't reintroduce):
- The desktop `gl_exec` must NOT parse `running` with jq's `.running // true`: jq's `//` treats
  boolean `false` as empty and returns the fallback, so a finished job reads as still-running and
  the poll loop never ends. Extract it explicitly.
- The WiFi-Direct GO group tears down after ~36s of NO traffic (firmware
  `hdd_psoc_idle_timeout` -> psoc shutdown, plus a "Wifi turning off from UI" path). Under
  continuous polling (gl_exec polls every 0.3s) the link stays up for the whole command; only
  idle GAPS trigger the teardown. Don't leave a sideload session idle -- close it or keep polling.

### Tmp hygiene (uploaded/derived files never persist)

Required guarantee: nothing sideload writes survives the session. Coverage:
- Uploads land in the filesync **app-private** dir (`<filesDir>/sideload/`, since
  `/data/local/tmp` is `shell:shell 0771` and a priv_app can't mkdir there; appsud-root can still
  read it). Wiped by `wipeStaging()` on session open, close, stop, `/sideload/cleanup`, sync-exec
  finally, and async-job finish.
- APK installs need a **world-readable** copy (PackageManager/system_server cannot read the
  app-private dir), placed in `/data/local/tmp/sideload-stage/` (`INSTALL_SCRATCH_DIR`). The
  desktop removes it inline after `pm install`; additionally `wipeStaging()` **force-wipes that
  dir via the appsud root daemon on every teardown** (the dir is root-owned, so the app uid can't
  delete it itself), so a job killed mid-install leaves nothing behind. Verified on-device: a
  root-owned leftover planted there is gone after `/sideload/close`.

## Logging -- External File + WiFi P2P Pull

When connected via USB cable, direct ADB logcat is available. Otherwise (BT-only connection), glasses logcat is invisible from the PC and the persistent file is the only path.

All logs go to `LogCollector.writeExternal()` -- a persistent file on glasses at `/sdcard/Download/glasses-client.log`. To read from PC, use the WiFi P2P pull script (`AI/clients/phone/test/adb/pull_glasses_log.sh`). The BT relay was removed for battery reasons (it pinned the RFCOMM connection awake during idle by emitting ~1.6 lines/s, preventing the bt-manager idle-teardown timer from ever expiring).

**In ListenerService:** Use `btLog(msg)` / `btErr(msg)`. Both write to `LogCollector.writeExternal()` only (no BT push).

**In all other classes (RokidServiceBridge, CameraCapturer, ReidController, etc.):** Accept a `remoteLog: ((String) -> Unit)?` property and call it for ALL logging. The service wires it as `{ btLog(it) }` after initialization, so all subsystem logs land in the same persistent file.

**In MainActivity:** Uses `LogCollector.i()` for UI-only logs (these stay on glasses logcat, invisible from PC). This is acceptable since UI logs are low-priority diagnostics.

## Perfetto Tracing (GT.* slices)

App-side slices that land in Perfetto traces alongside kernel sched/freq/idle events, so you can see which Kotlin code path caused which CPU spike. Uses `androidx.tracing:tracing-ktx:1.2.0` under the hood (thin wrapper over `android.os.Trace` with API-level safety). Zero cost when a trace is not being recorded.

### Helper

`glasses-tracing/src/main/java/com/repository/glasses/tracing/GT.kt` -- shared module consumed by the listener app and the bt-manager / capture / filesync sibling APKs (depend on it via `implementation(project(":glasses-tracing"))`). All app code should go through this object rather than calling `android.os.Trace` directly. Every slice name is automatically prefixed with `gt.` so everything filters cleanly.

```kotlin
import com.repository.glasses.tracing.GT

// Scoped -- preferred. inline fun, so plain `return value` inside works.
fun encode(pcm: ByteArray): ByteArray? = GT.section("audio.opus.encode") {
    // body; use return@section for early exits
}

// Explicit pair (when the lifetime doesn't fit one block).
GT.begin("svc.long_op")
try { ... } finally { GT.end() }

// Async section (work that spans threads or is not strictly nested).
val cookie = System.identityHashCode(req)
GT.beginAsync("cap.audio_rec", cookie)
// ... later, possibly on another thread:
GT.endAsync("cap.audio_rec", cookie)

// Counters (time-series, appear as counter tracks in Perfetto UI).
GT.counter("bt.tx_bytes", totalTxBytes)
GT.counter("tts.queue.depth", queue.size)
```

### Naming convention

`gt.<subsystem>.<operation>`. Keep the `gt.` prefix so callers can select all app slices with `WHERE s.name LIKE 'gt.%'` in trace_processor.

Subsystems currently in use:

| prefix | where |
|---|---|
| `gt.svc.*` | `service/ListenerService`, `GlassesListenerApp` lifecycle + capture start/stop |
| `gt.bt.*` | `bt/MessageRelay`, `bt/BtManagerBridge`, `bt/GlassesBtClient` -- send/recv per channel, bridge IPC, session async |
| `gt.audio.*` | `audio/OpusEncoder`, `audio/TtsPlayer`, `audio/SpeexEchoCanceller`, `audio/WebRtcAecm` -- encode, AEC, TTS playback |
| `gt.cap.*` | `capture/AudioRecorder`, `capture/CameraCapturer`, `capture/CaptureBridge` -- recording sessions, photo, bridge IPC + callbacks |
| `gt.ui.*` | `MainActivity` lifecycle + `onKeyDown` |
| `gt.input.*` | `input/FunctionButtonHandler`, `mouse/DpadInputHandler` -- key handlers |
| `gt.head.*` | `mouse/HeadTracker` -- 50 Hz gyro callback, start/stop |
| `gt.sync.*` | `sync/SyncChannelHandler` -- per-msgType receive |
| `gt.tts.*` | `audio/TtsPlayer` -- enqueue, decode_opus, stream_chunk, interrupt |

Counters emitted: `bt.tx_bytes`, `bt.rx_bytes`, `bt.chunk_bytes`, `tts.queue.depth`, `ui.keycode`.

### Capturing and viewing traces

The capture + analysis pipeline lives outside this repo at `/media/user/Lobotomite/Repository/glasses-profiling/`. One-shot capture + HTML report:

```bash
bash /media/user/Lobotomite/Repository/glasses-profiling/scripts/profile_once.sh iter-name 30
```

Outputs `reports/<ts>_iter-name.html`. The **App Traces** tab shows a Gantt timeline (rows per subsystem, segments per slice), a slice rate chart, and a filterable top-slices table. `grep -n 'GT\.' app/src/main/java/...` to find existing trace points when adding new ones.

Raw `.pftrace` files can also be opened at `https://ui.perfetto.dev` (drag + drop) for full Perfetto UI features.

### Config gotchas (do not "simplify" these)

The Perfetto config at `glasses-profiling/perfetto-configs/baseline.pbtxt` has two rules learned the hard way on this device:

1. **Both process names must be in `atrace_apps:`** -- this device treats `com.repository.glasses.listener` and `com.repository.glasses.listener:backend` as distinct atrace apps. If the `:backend` entry is removed, all ListenerService slices (the majority) silently vanish from traces. Any new `:process` suffix added to the manifest needs its own `atrace_apps:` line.

2. **Keep `atrace_categories:` small** -- listing ~18+ standard atrace categories silently disables userspace atrace entirely on this Android 12 build, leaving only kernel-side ftrace events. The working baseline uses 5: `am`, `view`, `gfx`, `input`, `binder_driver`. Add more only with verification via `adb shell getprop debug.atrace.tags.enableflags` during a live trace (should be non-zero).

### Adding instrumentation to other APKs

The bt-manager, capture, and filesync APKs are separate packages. To get GT-style slices from them, each needs its own `androidx.tracing:tracing-ktx` dependency + helper + `atrace_apps:` entry in the Perfetto config. Not wired up yet -- they only emit binder transactions today.

## Debugging Glasses Client

When not connected via USB cable, there is no direct ADB access. The only non-USB path is:

**Pull persistent log via WiFi P2P:**
```bash
bash AI/clients/phone/test/adb/pull_glasses_log.sh
```
Downloads `/sdcard/Download/glasses-client.log` from the glasses HTTP server (port 8848) via WiFi P2P. Works independently of BT. The glasses app writes all `btLog()`/`btErr()` lines to this file.

There is no real-time stream. The BT log relay was removed because it kept ~1.6 lines/s flowing during idle, which prevented bt-manager's idle-teardown timer from releasing `hal_bluetooth_lock`.

## Copilot overlay e2e

`CopilotCardOverlayInstrumentedTest` (`app/src/androidTest/.../ui/`) is the
glasses-side e2e for the Copilot (formerly "assistant") fact-check card overlay.
It mocks cards and PROGRAMMATICALLY asserts each one actually rendered on the
waveguide using **framebuffer pixel counting**, NOT UiAutomator accessibility:
`CopilotCardOverlay` draws into a `TYPE_APPLICATION_OVERLAY` window
(`FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`) that the a11y tree cannot see, so the
test captures the screen via `UiAutomation.takeScreenshot()` and counts
green-on-black pixels across show/stack/dismiss/show/hideAll steps (deltas vs an
evidence-based baseline captured before any card). ~2.5-3s holds between steps
let an external `screenrecord` capture each state.

Run safely (explicit user confirmation; install ONLY the `.test` APK; app must be
deployed via the priv-app overlay slot (`scripts/deploy-to-glasses.sh`), never
`adb install`) and the recording
flow (screenrecord, no SIGINT, tg-upload) are in `docs/copilot-card-overlay-e2e.md`.
The orchestrator live-AI WS drivers that feed this end-to-end are in
`AI/orchestrator/test/copilot-e2e/README.md`; the phone-side injection hook
(`copilot_inject`) and the Chat Logs e2e are in the phone CLAUDE.md.

## Common Commands

```bash
# Native build only
/media/user/Lobotomite/Repository/AI/clients/glasses/gradlew -p /media/user/Lobotomite/Repository/AI/clients/glasses assembleDebug

# Clean
/media/user/Lobotomite/Repository/AI/clients/glasses/gradlew -p /media/user/Lobotomite/Repository/AI/clients/glasses clean
```

## Dependencies

- `com.rokid.cxr:cxr-service-bridge` -- CXR-S SDK for glasses-side BT communication
- `com.google.mlkit:face-detection` -- On-device face detection for ReID
- AndroidX: core-ktx, appcompat, constraintlayout, lifecycle-service, recyclerview
- AIDL: `IAssistServer`, `IAssistClient` for Rokid MasterAssistService binding

## Rokid Waveguide Display Rules

The Rokid Glasses use a monochrome green micro-LED waveguide. Black (#000000) = pixels OFF = transparent/see-through. Any non-black pixel = green light emitted. Luminance hierarchy is defined in `Lum.kt` (GLOW > BRIGHT > MID > DIM > SOFT > GHOST > TRACE > black/off).

**Required attributes for any scrollable/focusable View (RecyclerView, ScrollView, etc.):**
```xml
android:focusable="false"
android:focusableInTouchMode="false"
android:defaultFocusHighlightEnabled="false"
android:overScrollMode="never"
android:scrollbars="none"
```

**Theme must force all accent/highlight colors to black:**
```xml
<item name="colorPrimary">@android:color/black</item>
<item name="colorPrimaryDark">@android:color/black</item>
<item name="colorAccent">@android:color/black</item>
<item name="android:colorEdgeEffect">@android:color/black</item>
```

**General rules:**
- Every View must have explicit `android:background="#000000"` or `setBackgroundColor(0xFF000000.toInt())`
- Never use transparency or alpha blending -- the hardware handles see-through naturally when pixels are black
- Key/scroll events are handled by `Activity.onKeyDown()`, so `focusable="false"` on RecyclerView does not break navigation

## Input Handling

### Hardware Inputs

Two physical inputs on the Rokid Glasses. There is no volume rocker, though the power/functional button does generate a `KEY_VOLUMEDOWN` keycode at the kernel level.

**Touch sensor** -- capacitive touchpad on right temple. Swipe gestures translate to DPAD keycodes:
- Swipe forward: `KEYCODE_DPAD_RIGHT` (22)
- Swipe back: `KEYCODE_DPAD_LEFT` (21)
- Tap: `KEYCODE_DPAD_CENTER` (23) / `KEYCODE_ENTER`

**Functional button** -- physical button on the glasses frame (`qpnp_pon`, `/dev/input/event0`). Kernel emits `KEY_MENU` + `KEY_VOLUMEDOWN`. Currently passed through to Rokid OS (not consumed by the app).

### Key Input Mapping

Override `Activity.onKeyDown()`. Return `true` to consume the event. The mapping is context-dependent based on `FocusState`:

- **TAB_NAV:** DPAD_RIGHT/LEFT = switch tabs, DPAD_CENTER = enter focused mode for current tab
- **CHAT_FOCUSED:** DPAD_RIGHT = scroll down, DPAD_LEFT = scroll up
- **TELEPROMPTER_FOCUSED:** DPAD_CENTER = toggle pause, DPAD_RIGHT = speed up, DPAD_LEFT = speed down
- **REID_FOCUSED / TODO_FOCUSED / NIGHTVISION_FOCUSED / TRANSLATE_FOCUSED / LIST_FOCUSED / MAP_FOCUSED:** Tab-specific navigation
- **BACK:** Cancel active session > unfocus > hide app (layered)
- **Double-tap DPAD_CENTER** during LISTENING/RESPONDING: cancel session

### Interrupting / Unregistering Inputs

1. **Key events** -- handled at Activity level, active only while Activity is in foreground. To conditionally ignore: use `focusState` guards. To stop consuming: return `false` or `super.onKeyDown()`.

2. **Rokid OS service binding** -- `RokidServiceBridge` binds to `MasterAssistService` via AIDL. To disconnect: call `unRegisterClient(packageName)` then `unbindService(connection)`. Auto-reconnects on disconnect (3s delay) unless explicitly unbound. Ref: `RokidServiceBridge.kt`, `docs/rokid-service-binding.md`

3. **CXR-S message subscriptions** -- `GlassesBtClient` wraps `CXRServiceBridge` and subscribes to BT channels (response, command, command_response, device_command, tts_audio). No explicit unsubscribe API in CXR-S SDK; subscriptions live for bridge instance lifetime. To stop processing: set a flag in the callback or destroy the bridge.

4. **Broadcast receivers** -- service-to-UI communication. Register in `onStart()`/`onResume()`, unregister in `onStop()`/`onPause()`. Always pair registration with unregistration to prevent leaks.

## Idle Power Floor

### Audio HAL idle floor (~36 s CPU per 15 min idle)

`AGMIPC@1.0-service`, `audio.service_64`, and `audioserver` keep ~36 s of CPU per 15 min of idle wall-time even after `ListenerService.stopMicStream()` calls `AudioRecord.stop()` + `release()` correctly. Cause: Qualcomm AGM (Audio Graph Manager) keeps the audio graph's clock domain powered for fast input-stream restart. There is no public Android API to force HAL power-collapse; the vendor PAL `Pal_StreamClose` is internal-only and not reachable from the app.

Implication: this is an irreducible idle-CPU floor on this device until Rokid / Qualcomm exposes a HAL-suspend hook. Don't add reflection workarounds -- they fail silently and create false hope. App-side work (wear-gating, demand-mic in `ListenerService.reconcileMicStream`) already minimizes when the mic is even started, which is the actionable lever.
