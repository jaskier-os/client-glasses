# Rokid YodaOS Service Binding -- Navigation HUD

## Overview

The Rokid glasses OS exposes several bindable Android services via standard Binder/AIDL IPC.
Any app running with user permissions can bind to exported services and call their methods programmatically.

Source: decompiled apps at `Recon/rokid-docs/yodaos/DECOMPILED-APPS/product/app/`

---

## Main Bindable Service: MasterAssistService

**App:** `RokidSpriteAssistServer`
**Package:** `com.rokid.os.sprite.assist`
**Action:** `com.rokid.os.sprite.assist.MasterAssistService`
**Exported:** Yes

### AIDL Interface: IAssistServer

**Descriptor:** `com.rokid.os.sprite.assist.server.IAssistServer`

**Source file:** `RokidSpriteAssistServer/jadx/sources/com/rokid/os/sprite/assist/server/IAssistServer.java`

| Method | Transaction ID | Purpose |
|---|---|---|
| `registerClient(String packageName, IAssistClient client)` | 1 | Register your app as a client with a callback |
| `unRegisterClient(String packageName)` | 2 | Unregister |
| `controlMsgJson(String packageName, String jsonData)` | 3 | Send commands as JSON |
| `scanQrCodeBitmap(Bitmap bitmap)` | 4 | QR code scanning |
| `scanQrCodeBmList(List<Bitmap> list)` | 5 | Batch QR scanning |

### Binding Example (Kotlin)

```kotlin
val intent = Intent("com.rokid.os.sprite.assist.MasterAssistService")
intent.setPackage("com.rokid.os.sprite.assist")
bindService(intent, object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        val server = IAssistServer.Stub.asInterface(service)
        server.registerClient("your.package.name", clientCallback)
        server.controlMsgJson("your.package.name", jsonCommand)
    }
    override fun onServiceDisconnected(name: ComponentName) {}
}, Context.BIND_AUTO_CREATE)
```

---

## Navigation Command Protocol

Commands are sent via `controlMsgJson()` and routed internally through `GattNavigationManager.parseNavigationData()`.

### Bluetooth Message Types (BtMsg.java)

Defined in `RokidSpriteLauncher/jadx/sources/com/rokid/os/sprite/launcher/page/navigation/BtMsg.java`:

| Message Type | Purpose |
|---|---|
| `Nav_Start` | Start navigation |
| `Nav_Stop` | Stop navigation |
| `Nav_Data` | Binary map/turn data |
| `Nav_Map_Data` | Map tile data |
| `Nav_UpdateInfo` | Update turn-by-turn info (next road, distance) |
| `Nav_SetShowMode` | Switch overview / turn-by-turn mode |
| `Nav_UpdateLocPermissionTip` | Location permission notifications |
| `Nav_NetProxyRequest` | HTTP proxy request for map tiles |
| `Nav_NetProxyResponse` | HTTP proxy response for map tiles |

### Internal Event Dispatch

Navigation commands are dispatched via EventBus to `RokidSpriteLauncher`:
- `NaviStart`, `NaviStop`, `NaviUpdateInfo`, `NaviMapData`, `NaviShowMode`

### Map Rendering Pipeline

```
controlMsgJson(json)
  -> GattNavigationManager.parseNavigationData()
    -> EventBus dispatch
      -> NavigationPageActivity / NavigationOverseaPageActivity
        -> Navigation.java (JNI bridge)
          -> libnavigation.so (C++ map engine)
            -> MapSurfaceView (HUD rendering on SurfaceView)
```

### Native Methods (Navigation.java)

`RokidSpriteLauncher/jadx/sources/com/rokid/os/sprite/launcher/page/navigation/Navigation.java`

```java
private native void nativeInit(String rootDir, AssetManager assetManager);
private native void nativeCreateView(int naviType, int width, int height);
private native void nativeDestroyView();
private native void nativeSetShowMode(boolean isOverView);
private native void nativeSetViewport(int width, int height);
private native void nativeNaviData(byte[] data);
private native void nativeResume();
private native void nativePause();
private native void nativeRender();
private native void nativeDeInit();
```

### Map Rendering Callbacks (MapSurfaceView.java)

`RokidSpriteLauncher/jadx/sources/com/rokid/os/sprite/launcher/page/navigation/adapter/MapSurfaceView.java`

```java
onRenderBeginDrawing(int mapId, AwkRenderContext status)
onRenderCommitDrawing(int mapId)
onRenderPoint(int mapId, AwkPoint point, int pointSize, AwkPaintStyle style)
onRenderPolyline(int mapId, AwkPoint[] points, int pointSize, AwkPaintStyle style)
onRenderPolygon(int mapId, AwkPoint[] points, int pointSize, AwkPaintStyle style)
onRenderBitmap(int mapId, AwkRectArea area, AwkBitmap awkBitmap)
onRenderColor(int mapId, AwkRectArea area)
onRenderText(int mapId, AwkPoint center, String text)
```

---

## Other Exported Services (RokidSpriteAssistServer)

**Manifest:** `RokidSpriteAssistServer/apktool/AndroidManifest.xml`

| Service | Intent Action | Notes |
|---|---|---|
| `MasterAssistService` | `com.rokid.os.sprite.assist.MasterAssistService` | Main Binder interface |
| `SpriteWifiService` | `com.rokid.os.sprite.assist.wifi.SpriteWifiService` | WiFi management |
| `InstructService` | `com.rokid.os.sprite.assist.instruct.InstructService` | Voice/instruction handling |
| `PaymentService` | `com.rokid.os.sprite.assist.payment.PaymentService` | Payments |
| `SystemFuncService` | `com.rokid.os.sprite.assist.system.SystemFuncService` | Volume, brightness, battery, schedules |
| `SpriteMediaService` | `com.rokid.os.sprite.assist.media.SpriteMediaService` | Media playback |
| `TtsService` | `com.rokid.os.sprite.tts.TTS_SERVICE` | Text-to-speech |
| `WebServerService` | (web server) | Local web server |
| `RokidBluetoothService` | (no intent filter) | Bluetooth |
| `NsdService` | (no intent filter) | Network service discovery |

**Note:** `SystemFuncService` returns null from `onBind()` -- not actually bindable despite being exported.

### SystemFuncService Interface (ISystemFuncServer)

Though not bindable, documents the available system control methods:

```java
// Schedule management
syncSchedule(String scheduleJson)
addSchedule(String scheduleJson)
removeSchedule(String scheduleJson)
clearSchedule(boolean clearCmd)

// Journey/route management
syncJourney(String journeyJson)
addJourney(String journeyJson)
removeJourney(String journeyJson)
clearJourney(boolean clearCmd)
getJourney()

// Device control
getVolumeSpecified()
setVolumeSpecified(int value)
getBrightnessSpecified()
setBrightnessSpecified(int value)
getBatteryLevel()
glassTakeOnChange(boolean takeOn)
```

---

## Architecture Diagram

```
Your App (any app with user permissions)
  |
  | bindService(intent, connection, flags)
  |
  V
MasterAssistService (IAssistServer Binder)
  |
  +---> registerClient(pkg, callback)  -- register for callbacks
  |
  +---> controlMsgJson(pkg, json)      -- send commands
  |        |
  |        +---> GattNavigationManager.parseNavigationData()
  |                 |
  |                 +---> EventBus dispatch
  |
  +---> RokidSpriteLauncher receives events
           |
           +---> NavigationPageActivity / NavigationOverseaPageActivity
           |
           +---> Navigation.java (JNI -> libnavigation.so)
           |
           +---> MapSurfaceView (HUD rendering on Canvas/SurfaceView)

Mobile Phone (companion app)
  |
  | Bluetooth via CXR-M SDK
  |
  V
RokidBluetoothService -> GattNavigationManager -> same EventBus pipeline
```

---

## TODO

- [ ] Reverse-engineer exact JSON schema for `controlMsgJson()` navigation commands
- [ ] Analyze `GattNavigationManager.parseNavigationData()` for command format
- [ ] Check if `IAssistClient` callback interface provides navigation state updates
- [ ] Test binding from a custom app on the device
