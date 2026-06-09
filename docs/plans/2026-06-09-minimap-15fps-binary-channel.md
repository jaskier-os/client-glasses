# Sustained 10-15 FPS Glasses Minimap: Binary Map Frames + Dedicated RFCOMM Channel

Date: 2026-06-09
Status: PLAN (no code written yet)

## Problem

The glasses minimap base frame updates at ~0.5-3 FPS today because it is movement-gated
(`MIN_RECAPTURE_METERS=8.0`) and each frame is a ~52 KB WEBP that is then **base64-inflated to
~70-109 KB** and pushed over the **single shared RFCOMM relay** that also carries audio, TTS,
arrow samples, and all control traffic. The user wants a **sustained 10-15 FPS** map.

Two explicit directives:
1. A **separate dedicated RFCOMM channel/socket** for map frames is allowed/encouraged, so heavy
   map traffic cannot overflow or head-of-line-block the shared audio/control relay.
2. **Stop base64-encoding the map frame.** Transmit **raw binary** bytes and/or compress harder.

## Verified code map (symbols confirmed by reading the files)

Phone (Kotlin):
- `AI/clients/phone/navigation/src/main/java/com/repository/navigation/MapBitmapStreamer.kt`
  - Constants L50-L80: `CROP_WIDTH=600`, `CROP_HEIGHT=300`, `OUTPUT_WIDTH=1200`, `OUTPUT_HEIGHT=600`,
    `WEBP_QUALITY=90`, `CAPTURE_INTERVAL_MS=100`, `ARROW_INTERVAL_MS=100`, `MIN_RECAPTURE_METERS=8.0`.
  - `captureRunnable` L180-186 polls every 100 ms; `captureAndSend()` L269-299 applies the movement
    gate (L283-286 `moved < MIN_RECAPTURE_METERS return`).
  - `processAndSend()` L301-335: scale to 1200x600, `scaled.compress(WEBP, 90, stream)` L317,
    `Base64.encodeToString(bytes, NO_WRAP)` L321, `sendBitmap(base64)` L322. Log line L324-328
    `processAndSend: ... webp(q$WEBP_QUALITY)=${bytes.size}B base64=${base64.length}B`.
  - `sendBitmap: (String) -> Unit` ctor arg L39; `refreshNow()` L124-128 clears anchor.
- `AI/clients/phone/navigation/.../NavigationManager.kt`
  - `var sendMapBitmap: ((String) -> Unit)?` L120, `var sendMapArrow` L123, streamer constructed
    L453, `streamer.sendArrow` L454, `streamer.transmitEnabled = mapTransmitEnabled` L455.
- `AI/clients/phone/app/.../service/ListenerService.kt`
  - L3214 `navManager.sendMapBitmap = { b64 -> phoneBtHost.sendMapBitmap(b64) }`, L3215 arrow.
- `AI/clients/phone/app/.../bt/PhoneBtHost.kt`
  - `rfcommClient = GlassesRfcommClient(context)` L55. `sendMapBitmap(bitmapBase64: String)` L1441-1448
    -> `rfcommClient.send(BtProtocol.CH_MAP_BITMAP, bitmapBase64)` L1443. `sendMapArrow` L1450-1462.
- `AI/clients/phone/app/.../bt/GlassesRfcommClient.kt` (THE relay socket, phone side)
  - `MESSAGE_UUID = "b2c3d4e5-f6a7-8901-bcde-f12345678901"` L32, `READ_BUF_SIZE=4096` L33,
    `MAX_FRAME_BYTES=8MB` L34, `OUTBOUND_QUEUE_MAX=100` L35.
  - `send(channel, vararg args: String)` L134-154 (queues when disconnected, drop-oldest).
  - `sendNow()` L156-167 -> `buildFrame` + `synchronized(writeLock){ out.write(frame) }`.
  - `buildFrame(channel, args: Array<out String>)` L336-356: each arg `arg.toByteArray(UTF_8)` L346.
  - Outbound connect: `device.createRfcommSocketToServiceRecord(UUID(MESSAGE_UUID))` + `s.connect()`
    in `connectLoop()` L221-235. `parseFrame` L308-332 decodes args as `String(..., UTF_8)` L322.
- `AI/clients/phone/app/.../bt/BtProtocol.kt`: `CH_MAP_BITMAP="listener_map_bitmap"` L33,
  `CH_MAP_ARROW="listener_map_arrow"` L38.

Glasses (Kotlin):
- `AI/clients/glasses/app/.../bt/MessageRelay.kt` (receiving relay)
  - `MESSAGE_UUID` L37, `SERVICE_NAME="GlassesMessages"` L38, `MAX_FRAME_BYTES=8MB` L39.
  - Inbound server socket opened via `bridge.listenRfcommInbound(SERVICE_NAME, MESSAGE_UUID)` L249.
  - `publish(channel, vararg args)` L281-296 -> `buildFrame` L300-320 -> `bridge.writeRfcomm`.
  - `handleIncomingBytes` L322-353 + `parseFrame` L355-376 (args decoded `String(..., UTF_8)` L369).
  - `txBytesTotal`/`rxBytesTotal` counters L99-100 fed to `GT.counter("bt.tx_bytes"/"bt.rx_bytes")`.
- `AI/clients/glasses/app/.../bt/GlassesBtClient.kt`
  - `dispatch()` `CH_MAP_BITMAP` L170-173 -> `listener?.onMapBitmap(bitmapBase64)`.
    `CH_MAP_ARROW` L174-180. `chunkBuffers` L73 (only used for chat/contacts, NOT map).
- `AI/clients/glasses/app/.../service/ListenerService.kt`
  - `onMapBitmap(bitmapBase64)` L6834-: broadcasts `ACTION_MAP_BITMAP` with `EXTRA_MAP_BITMAP`
    (constants L107/L117), and also decodes for an optional overlay (`Base64.decode(...NO_WRAP)` L6843).
- `AI/clients/glasses/app/.../MainActivity.kt`
  - `pendingMapFrame = AtomicReference<String?>` L491. `mapBitmapReceiver` L1394-1402 (coalesce: keep
    newest). `processLatestMapFrame()` L1408-: `Base64.decode(base64, NO_WRAP)` L1411,
    `BitmapFactory.decodeByteArray` L1412, `BitmapUtils.toMonochromeGreen(bitmap)` L1424.
  - Display: `mapContentView` ImageView **330x330dp, centerCrop** (oversized rotation square),
    visible window `mapKeystone` **288x144dp** (`activity_main.xml` L866-867, L884-885).
    `minimapView` 120x120dp (L1600). At 240 dpi (1.667 px/dp): 330dp ~= **550x550 px**,
    288x144dp ~= 480x240 px.
- `AI/clients/glasses/app/.../ui/BitmapUtils.kt` `toMonochromeGreen` L12-: per-pixel luminance ->
  green channel only; output ARGB_8888. **The phone color content is thrown away here.**

bt-manager (priv-app broker, glasses side):
- `AI/clients/glasses/bt-manager/.../RfcommManager.kt`
  - `connectOutbound(addr, uuid)` L135-175 (client), `listenInbound(serviceName, uuid, idleTimeoutMs=
    DEFAULT_IDLE_TIMEOUT_MS=90s)` L177-301 (server, per-UUID, closes stale listener on same UUID
    L188-195), `write(socketId, data)` L350-381 (`synchronized(writeLock){ out.write; out.flush }`),
    `readLoop` 4096-byte buffer L303-348, idle watchdog L391-440 gated by `isSessionActive()`.
  - **Multiple distinct UUIDs are fully supported** -- sockets keyed by generated socketId, each UUID
    gets its own server thread + accept loop. A second UUID is a clean second socket.
- `IBtManager.aidl` L21-26: `connectRfcommOutbound`, `listenRfcommInbound`, `closeRfcommSocket`,
  `writeRfcommSocket(String socketId, in byte[] data)`, `isRfcommConnected`. **The AIDL is already
  byte[]-based** -- no AIDL change needed for binary.
- `BtManagerService.kt` L181-198 just forwards the binder calls to `rfcommManager`.

Key insight: the wire frame format is **already binary-safe** (4B length-prefixed args). The ONLY
things that corrupt raw bytes are: the phone `send(... vararg args: String)` API + `arg.toByteArray(
UTF_8)` in `buildFrame`, and the receive-side `String(buf, p, argLen, UTF_8)` in `parseFrame`. base64
exists solely to survive that String round-trip. Remove the String round-trip for the map channel and
base64 is unnecessary.

---

## 1. Bandwidth budget / FPS feasibility

### RFCOMM ceiling (assumption + how to confirm)

Assume a **practical sustained RFCOMM ceiling of ~250 kbit/s = ~31 KB/s** on this hardware while
A2DP audio is also streaming (BT Classic EDR theoretical 2-3 Mbit/s, but RFCOMM through this
bt-manager broker + AIDL byte[] copy + a co-existing A2DP sink leaves far less). Treat **31 KB/s** as
the planning ceiling and **confirm empirically** before/after with existing logs:

- Glasses `RfcommManager.readLoop` already logs `event=readLoop.tick ... window_bytes=N window_ms=M`
  (`TAG_READ="BtMgr:Read"`, L327-334). `window_bytes / (window_ms/1000)` is the achieved RX byte
  rate. Capture during a steady map stream and read the asymptote.
- Glasses `MessageRelay` feeds `GT.counter("bt.rx_bytes", rxBytesTotal)` (L153-154) -- slope of that
  counter in a Perfetto trace (`glasses-profiling/scripts/profile_once.sh`) is the same number,
  visualized.
- Phone `GlassesRfcommClient.sendNow` is the TX side; add a byte/timestamp log there (see step 6) to
  read the phone-side offered rate and compare with glasses RX to detect backlog.

### The math

Let P = per-frame payload bytes on the wire (post-compression, raw binary, plus ~10 B framing
overhead which is negligible). Sustained rate at F fps is `P * F` bytes/s and must be <= ceiling C,
with headroom for audio + arrow + control. Reserve ~40% headroom -> usable map budget
**B = 0.6 * C ~= 18-19 KB/s** at C=31 KB/s.

- Current frame: ~52 KB WEBP. At 15 fps that is **~780 KB/s** -- ~25x over the *raw* ceiling and
  ~40x over the headroom budget. **Infeasible**, exactly as suspected. Even base64 removed (52 KB
  raw) is ~780 KB/s; even at 3 fps it is ~156 KB/s, already 5x over.
- Target per-frame budget:
  - **15 fps**: P <= B/15 ~= **1.2 KB/frame**.
  - **10 fps**: P <= B/10 ~= **1.8 KB/frame**.

So the design target is **a map frame of ~1.0-1.8 KB**. That is a ~30-50x shrink from today. It is
achievable only by combining ALL of: drop base64 (-25%), shrink resolution (~5-7x fewer pixels),
single-channel/grayscale encode (~2-3x smaller WEBP), and lower WEBP quality (~1.5-2x). See step 4
for the per-lever quantification that lands inside this budget.

If empirical C is higher (say 400-500 kbit/s with audio idle), the budget relaxes proportionally,
but the levers below should be sized to the conservative 31 KB/s so the map degrades gracefully when
audio is active.

---

## 2. Drop base64 -> raw binary

**Recommendation: option (a)** -- add a binary-arg send/receive path that bypasses UTF-8 for the map
channel. The frame format does not change on the wire; only the in-process String coupling is
removed. This deletes ~33% bytes AND the base64 encode (phone) / decode (glasses x2: ListenerService
overlay + MainActivity) CPU.

Exactly which functions change:

### Phone `GlassesRfcommClient.kt`
1. Add a binary builder + sender alongside the String ones (do NOT touch the String path used by
   every other channel):
   ```kotlin
   // New: single raw-byte arg frame. Mirrors buildFrame() L336-356 byte-for-byte but
   // writes the payload bytes verbatim instead of arg.toByteArray(UTF_8).
   private fun buildBinaryFrame(channel: String, payload: ByteArray): ByteArray {
       val channelBytes = channel.toByteArray(Charsets.UTF_8)
       require(channelBytes.size <= 255)
       val body = ByteArrayOutputStream(channelBytes.size + 6 + payload.size)
       body.write(channelBytes.size and 0xFF)
       body.write(channelBytes)
       body.write(1)                                   // argCount = 1
       body.write(ByteBuffer.allocate(4).putInt(payload.size).array())
       body.write(payload)
       val bodyBytes = body.toByteArray()
       val frame = ByteArrayOutputStream(4 + bodyBytes.size)
       frame.write(ByteBuffer.allocate(4).putInt(bodyBytes.size).array())
       frame.write(bodyBytes)
       return frame.toByteArray()
   }

   fun sendBinary(channel: String, payload: ByteArray): Boolean {
       // Map frames are droppable: if the link is down, DROP (do not queue) so a
       // backlog can never build. Contrast with send() which queues for reconnect.
       if (!isConnected) return false
       val out = outputStream ?: return false
       return try { synchronized(writeLock) { out.write(buildBinaryFrame(channel, payload)) }; true }
       catch (e: Exception) { listener?.onLog("sendBinary($channel) failed: ${e.message}"); handleDisconnect(); false }
   }
   ```
   (When the dedicated socket of step 3 is added, `sendBinary` lives on that socket's client class
   instead; the body is identical.)
2. Receive side does NOT need to change on the phone for the map (map is phone->glasses only).

### Glasses `MessageRelay.kt` -- receive path
`parseFrame` (L355-376) currently always does `String(buf, p, argLen, UTF_8)`. The map channel must
deliver the raw bytes WITHOUT decoding. Add a binary-channel fast path:
```kotlin
// Channels whose single arg is raw binary and must NOT be UTF-8 decoded.
// (only the map base frame today)
private val binaryChannels = setOf(BtProtocol.CH_MAP_BITMAP_BIN)

private fun parseFrame(buf: ByteArray, start: Int, length: Int) {
    var p = start; val end = start + length
    val chanLen = buf[p].toInt() and 0xFF; p++
    require(p + chanLen <= end)
    val channel = String(buf, p, chanLen, Charsets.UTF_8); p += chanLen
    if (channel in binaryChannels) {
        val argCount = buf[p].toInt() and 0xFF; p++          // expect 1
        require(argCount == 1)
        require(p + 4 <= end); val argLen = ByteBuffer.wrap(buf, p, 4).int; p += 4
        require(argLen >= 0 && p + argLen <= end)
        val payload = buf.copyOfRange(p, p + argLen)         // raw bytes, no UTF-8
        GT.section("bt.dispatch.$channel") { listener?.onBinaryMessage(channel, payload) }
        return
    }
    // ...unchanged String path for all other channels...
}
```
Add `fun onBinaryMessage(channel: String, payload: ByteArray) {}` (default no-op) to
`MessageRelay.Listener` (L42-46) and `BtManagerBridge.RfcommListener` is not involved (MessageRelay is
above it). Route it through `GlassesBtClient` to a new `listener?.onMapBitmapBytes(payload)`.

### Glasses `GlassesBtClient.kt` -- dispatch
Replace/augment the `CH_MAP_BITMAP` case (L170-173). Implement `onBinaryMessage` on the relay
listener (L404 region) and dispatch the map channel to a new callback
`listener?.onMapBitmapBytes(payload: ByteArray)` instead of `onMapBitmap(String)`. Keep the existing
String `onMapBitmap` only if any legacy path still needs it; per repo policy (no legacy), remove the
String map path once the binary path is in.

### Glasses `ListenerService.kt` + `MainActivity.kt`
- `ListenerService.onMapBitmapBytes(payload)`: broadcast `ACTION_MAP_BITMAP` with a **byte[] extra**
  `putExtra(EXTRA_MAP_BITMAP_BYTES, payload)` (new constant) instead of the base64 String. The
  optional overlay decode at L6843 becomes `BitmapFactory.decodeByteArray(payload, 0, payload.size)`
  -- no base64.
- `MainActivity`: `pendingMapFrame` becomes `AtomicReference<ByteArray?>`. `mapBitmapReceiver`
  (L1394) reads `intent.getByteArrayExtra(EXTRA_MAP_BITMAP_BYTES)`. `processLatestMapFrame()`
  (L1408) drops the `Base64.decode` (L1411) and feeds the bytes straight to
  `BitmapFactory.decodeByteArray(payload, 0, payload.size)` -> `toMonochromeGreen`. Net: removes one
  full base64 decode per frame on the A55 glasses, plus the ListenerService overlay decode.

Note: Android `Intent` byte[] extras travel through a Binder for `sendBroadcast`; at ~1-2 KB/frame
this is fine (well under the 1 MB Binder limit and cheap). The current base64 String path already
crosses the same Binder, larger.

---

## 3. Dedicated RFCOMM channel/socket for map frames

**Recommendation: implement the dedicated socket.** The shared relay multiplexes audio/TTS/arrow/
control; a single `out.write(frame)` of a multi-KB map frame under `writeLock` (phone `sendNow`
L160; glasses `RfcommManager.write` L366) head-of-line-blocks everything else on that socket for the
duration of the kernel write, and a transient slow link lets map frames pile up. A second socket
isolates map traffic completely: even if the map socket saturates, audio keeps flowing on the relay
socket. bt-manager already cleanly supports N sockets on N UUIDs (step-1 verification), so the cost
is moderate and the isolation is exactly what the user asked for.

### New UUID
Add a second SPP UUID distinct from `MESSAGE_UUID`, e.g.:
```
MAP_UUID = "c3d4e5f6-a7b8-9012-cdef-234567890abc"   // map frame channel only
```
Define it in BOTH `GlassesRfcommClient.kt` (phone) and a new glasses `MapRelay` (or reuse
`MessageRelay` parameterized by UUID/serviceName).

### Glasses server socket (inbound)
Create a second relay instance dedicated to map. Cleanest: **parameterize `MessageRelay`** by
`(uuid, serviceName)` instead of the hardcoded companion `MESSAGE_UUID`/`SERVICE_NAME` (L37-38), then
instantiate twice in `ListenerService`:
- existing: `MessageRelay(bridge, ctx, MESSAGE_UUID, "GlassesMessages")`
- new: `MessageRelay(bridge, ctx, MAP_UUID, "GlassesMap")`

The new instance's `start()`/`openServerSocket()` calls `bridge.listenRfcommInbound("GlassesMap",
MAP_UUID)` (RfcommManager L177 handles a second UUID independently). Set its
`idleTimeoutMs` via a new `listenRfcommInbound` overload OR rely on the existing 90 s default; the map
stream is active only during navigation, so when navigation stops the socket idles out and tears down
to release wakelocks -- desirable. Wire its `onBinaryMessage(CH_MAP_BITMAP_BIN)` to the same
`onMapBitmapBytes` path.

The map relay only needs the binary path; it does not carry the String channels. Keep its
`Listener` minimal (onConnected/onDisconnected/onBinaryMessage).

### Phone outbound connect
Add a second lightweight client mirroring `GlassesRfcommClient`'s connect/reconnect (`connectLoop`
L192-244) but connecting to `MAP_UUID`. Two options:
- (preferred) **Extract a small `RfcommLink` class** parameterized by UUID with the connect loop,
  `reconnectSignal`, `sendBinary`, and disconnect handling; instantiate it twice in `PhoneBtHost`
  (`rfcommClient` for control + a new `mapRfcommClient`). Avoids duplicating the reconnect machinery.
- (faster, more duplication) copy `GlassesRfcommClient` into `MapRfcommClient` with `MAP_UUID` and
  only the binary send.

Lifecycle (mirror existing relay): `PhoneBtHost.startBtHost` (L633) also starts `mapRfcommClient`;
its connect is triggered by the SAME signals already used (`requestImmediateReconnect` on BLE wake /
CXR-M connect, L417/L505/L729). On disconnect it reconnects on demand. Because map frames are
**droppable**, `mapRfcommClient` must NOT use the queue-on-disconnect behavior (`send()` L134-154) --
use `sendBinary` which drops when down (step 2). Both sockets connect to the same bonded device
(`findGlassesDevice` L246-262) -- reuse that finder.

`PhoneBtHost.sendMapBitmap` (L1441) switches to:
```kotlin
fun sendMapBitmapBytes(webp: ByteArray) {
    mapRfcommClient.sendBinary(BtProtocol.CH_MAP_BITMAP_BIN, webp)
    txByteCount.addAndGet(webp.size.toLong())
}
```
`MapBitmapStreamer.sendBitmap` ctor type changes from `(String) -> Unit` to `(ByteArray) -> Unit`;
`NavigationManager.sendMapBitmap` (L120) becomes `((ByteArray) -> Unit)?`; the wiring at phone
`ListenerService` L3214 becomes `{ bytes -> phoneBtHost.sendMapBitmapBytes(bytes) }`.

Arrow stays on the **control relay** (`rfcommClient`, `CH_MAP_ARROW`) -- it is tiny and must remain
in-order with control; do not move it to the map socket.

### Concurrency / pairing note
Two RFCOMM sockets to the same device share the BT Classic ACL link; they multiplex at L2CAP. This
is standard and supported. The map socket competes with A2DP + the control socket for airtime --
which is the whole point of sizing frames small (step 4) and pacing (step 5). No new pairing/bond is
needed (same device, new SDP service record only).

### Simpler alternative (single socket + strict drop + budget)
Keep one socket; give the map channel a strict phone-side **drop-if-in-flight** flag and a per-second
byte budget so it can never enqueue more than B bytes/s, yielding remaining airtime to audio. This
avoids the second socket but does NOT remove head-of-line blocking: a single in-progress multi-KB
`out.write` still stalls audio for that write. **Recommendation: do the dedicated socket** -- the
user explicitly green-lit it and it is the only option that truly prevents map traffic from stalling
audio. Keep the strict drop + budget too (step 5); they are complementary.

---

## 4. Shrink the frame to actually hit the FPS

The display is monochrome green, visible window 288x144dp (~480x240 px @ 240dpi), drawn from a
330x330dp (~550x550 px) centerCrop square (rotation oversize). Sending 1200x600 is ~3-6x the pixels
the waveguide can even show. Levers, each quantified against the ~1.2-1.8 KB/frame budget:

(a) **Drop OUTPUT resolution.** The smallest square that still fully covers the 288x144dp window under
any heading rotation is the window diagonal ~= sqrt(288^2+144^2) ~= 322dp ~= **~537 px @ 240dpi**.
The current ImageView is 330dp (~550px). So a source of **~360x360** (downscaled, centerCrop fills
537px window with mild upscale) to **at most 540x540** is the real ceiling; the current 1200x600 is
wildly oversized AND the wrong aspect (the square crop discards the 1200x600 sides anyway).
Recommend keeping the rendered/encoded frame **square ~360x360** (matches the centerCrop square,
covers the rotated window, ~9x fewer pixels than 1200x600). Pixel count 360^2=129.6k vs
1200x600=720k -> **~5.5x fewer pixels**, roughly proportional WEBP shrink. This requires decoupling
`OUTPUT_*` (shipped pixels) from `CROP_*` (mercator extent used for arrow normalization L357-360) --
`CROP_WIDTH/HEIGHT` MUST stay 600x300 (the comment at L45-49 and the arrow math depend on it). Only
the encode/scale target changes. Provider render: ask the source for a square (or render 600x300 then
center-crop+scale to 360x360 on the phone before encode; the arrow math is unaffected because it
normalizes against CROP_*, not the shipped pixels -- see L351-356).

(b) **Single-channel / grayscale encode.** The glasses immediately collapse to one channel via
`BitmapUtils.toMonochromeGreen` (luminance -> green), discarding all color. Encoding color WEBP on the
phone wastes ~2-3x bytes carrying chroma that is thrown away. Convert the rendered bitmap to
**luminance grayscale on the phone before compress** (R=G=B=lum). Grayscale content makes the WEBP
chroma planes flat/near-constant, so lossy WEBP shrinks ~1.5-2.5x for the same visual map. (WEBP has
no true 8-bit-gray mode via `Bitmap.compress`, but a gray-filled ARGB encodes far smaller because
chroma is constant.) Bonus: the glasses `toMonochromeGreen` luminance pass gets cheaper/identical
input. Quantify: expect ~1.7x reduction on top of (a).

(c) **Lower WEBP_QUALITY.** 90 is "visually lossless"; for a sparse green map on a low-res waveguide,
**quality ~55-65** is indistinguishable and ~1.5-2x smaller than q90. Make it a tunable constant.

### Combined estimate
Start ~52 KB @ 1200x600/color/q90. Apply: resolution 360x360 (~/5.5 -> ~9.5 KB) x grayscale (~/1.7 ->
~5.6 KB) x q60 (~/1.7 -> ~3.3 KB) x drop-base64 (already raw). ~3.3 KB is still above the 1.2-1.8 KB
15 fps budget, so push one more notch: **320x320 + q50** lands ~**1.8-2.2 KB**, hitting the **10 fps**
budget comfortably and **12-13 fps** within the 31 KB/s ceiling. To reach a hard 15 fps either accept
~300x300/q45 (~1.3 KB) OR rely on empirically-higher real ceiling. Recommended shipping config:

```
OUTPUT_SQUARE = 340            // new; replaces OUTPUT_WIDTH/HEIGHT for the encoded frame
WEBP_QUALITY  = 55             // down from 90
GRAYSCALE     = true           // new; convert before compress
```
Target steady ~1.6-2.0 KB/frame -> ~10-12 fps at C=31 KB/s with audio, more if audio idle. These three
constants are the FPS knobs; tune against measured `window_bytes` rate (step 6).

Arrow/heading stays on the existing 100 ms fast path (already smooth via `arrowSamples` interpolation
in `MainActivity.onArrowSample` L1470 + Choreographer `drawArrowFrame`). Do not touch it.

---

## 5. Pacing & backpressure

### Decouple heavy frame from the movement gate
The user wants smooth continuous updates, so remove the `MIN_RECAPTURE_METERS` gate from the *send*
cadence. In `MapBitmapStreamer`:
- Keep `captureRunnable` but change its period to a fixed frame timer:
  `CAPTURE_INTERVAL_MS = 80` (12.5 fps) or `66` (15 fps) -- this is now the **target frame period**,
  not a poll.
- In `captureAndSend()` (L269-299): **delete** the movement-gate early-return (L283-286). Always
  re-render at the current position each tick. (The render still skips if `capturing` L275 or no fix
  L276.) Keep `lastCapturedLat/Lng` updated for the arrow center (L330-331) -- the arrow normalization
  depends on it.
- Because the map provider render (`source.render` L291) may itself be slow/expensive, keep the
  `capturing` guard (L275/L292) so a slow render naturally drops ticks instead of queueing.

### Phone-side drop-if-still-sending
Add a single in-flight guard so a slow link degrades gracefully instead of building a backlog:
```kotlin
private val mapSendInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
// in processAndSend, after encode:
if (mapSendInFlight.compareAndSet(false, true)) {
    try { sendBitmap(payloadBytes) } finally { mapSendInFlight.set(false) }
} else {
    // previous frame still being written; drop this one (newest-wins handled by next tick)
}
```
Because `sendBinary` (step 2) writes synchronously under `writeLock` and DROPS when disconnected, the
in-flight window equals the kernel write time; if writes back up, frames are dropped at the source --
the link never sees a queue. This is the phone-side analog of the glasses `pendingMapFrame` coalescing
(`MainActivity` L491/L1400), which we KEEP (still newest-wins on receive).

### Ordering: map must not starve audio
The dedicated socket (step 3) already prevents map writes from blocking the audio/control relay
socket. Additionally, at the ACL level, keep the map frame small (step 4) so each L2CAP burst is
short and A2DP gets airtime between frames. Do NOT raise `RfcommManager` buffer or remove flush. If
desired, add a tiny inter-frame yield by keeping the frame timer (80 ms) rather than a tight loop, so
the radio interleaves A2DP between map bursts.

### Active-session label
While navigating, hold a map session label so the bt-manager idle watchdog (RfcommManager L391, gated
by `isSessionActive()` L405) does not tear the socket down mid-navigation. Reuse the existing
`setActiveSession`/`clearActiveSession` plumbing (`PhoneBtHost` L1384-1385 -> `GlassesRfcommClient`
L392; glasses side `RfcommManager.setActiveSession`). Add a label e.g. `"map_streaming"` set on
nav-start, cleared on nav-stop. Add it to `DEFAULT_SAFETY_TIMEOUT_MS` (RfcommManager L45-52) with a
generous timeout (e.g. 300_000L) so a missed clear can't pin forever.

---

## 6. Risks & verification

### Risks
- **RFCOMM saturation vs A2DP.** The map socket and A2DP share one ACL. If the map frame is too big or
  the fps too high, audio underruns (stutter). Mitigation: conservative frame size (step 4) + drop +
  the dedicated socket + measure. Treat 31 KB/s as the cap with audio active; back off fps if the
  glasses `readLoop.tick window_bytes` rate plateaus below `P*F` (means link is the bottleneck and
  frames are dropping).
- **bt-manager write limits.** `RfcommManager.write` (L350) is a synchronous `out.write+flush` under a
  per-socket `writeLock`; no internal queue. A slow write blocks only that socket's writer thread
  (the phone, remote). Fine -- our phone-side in-flight guard prevents pile-up.
- **8 MB MAX_FRAME_BYTES** (`MessageRelay` L39, `GlassesRfcommClient` L34): our frames are ~2 KB, far
  under. No change. The cap also protects against a corrupt length field.
- **Decode cost on the glasses (4x A55, 1.7 GB).** At 15 fps the glasses must `decodeByteArray` +
  `toMonochromeGreen` (a full getPixels/setPixels pass, `BitmapUtils` L12-) + crossfade
  (`setMapBaseBitmap` L1454 runs a 250 ms animator -- shorten it to ~60-100 ms or it will overlap at
  15 fps and thrash). Smaller frames (340x340 vs 1200x600 = ~10x fewer pixels) make both decode and
  the monochrome pass ~10x cheaper, which is what makes 15 fps decode feasible. The
  `pendingMapFrame` newest-wins coalescing (L1400) already guarantees the worker never falls behind
  by more than one frame. Watch `gt.ui.map.process` slice duration in Perfetto.
- **Crossfade at high fps.** The 250 ms `mapBaseFadeView` animation (L1461) is fine at 1-3 fps but
  must be cut to <= the frame period (e.g. 60 ms) or removed for the high-rate path, else animations
  queue. Recommend dropping the crossfade entirely on the fast path and just `setImageBitmap`.

### Verification recipe (measure achieved end-to-end FPS)
1. Build + deploy phone (`--prod`) then glasses (per repo deploy order: phone first). No `adb install`
   of the app APK.
2. Start a navigation session (mock route is fine). Confirm both sockets connect: glasses log
   `BtMgr:Rfcomm event=accept.ok ... uuid=<MAP_UUID>` and the control UUID, and phone
   `GlassesRfcomm Connected` for both links.
3. **Phone offered rate**: add a log in `sendBinary`/`processAndSend` printing `bytes=` + a frame
   counter + wall-ms; compute frames/s and bytes/s. Compare to the target (e.g. 12 fps, ~2 KB).
   (`MapBitmapStreamer.processAndSend` already logs frame size L324 -- extend it to log the raw
   `payloadBytes.size` and a monotonic fps from `debugTickCount`.)
4. **Link rate (glasses RX)**: pull glasses log (`AI/clients/phone/test/adb/pull_glasses_log.sh`) and
   read `BtMgr:Read event=readLoop.tick ... window_bytes / window_ms` on the map socketId; that is the
   delivered byte rate. Frames/s = window_bytes / P. If it plateaus below the offered rate, the link
   is saturated -> reduce fps or frame size.
5. **Decode/render rate (glasses)**: run
   `glasses-profiling/scripts/profile_once.sh minimap-15fps 30` and inspect `gt.ui.map.process`
   slice rate + duration and the `bt.rx_bytes` counter slope. The slice rate is the true rendered fps.
6. **A2DP coexistence**: play audio (TTS) during the map stream; confirm no audio stutter in the
   recording and that `bt.rx_bytes` slope is unchanged (map yields to audio). If audio stutters,
   lower `WEBP_QUALITY`/resolution until the byte rate drops under the measured ceiling with audio.
7. Record the glasses HUD (`adb shell screenrecord`, no SIGINT, 180 s cap) showing the smooth map,
   bounce to Telegram Saved Messages, quote the shortId.

Success criterion: glasses `gt.ui.map.process` slice rate sustains **>= 10 fps** (target 12-15) for
60 s of continuous movement with TTS audio playing and no audio dropouts, with map traffic on the
dedicated socket and zero base64 in the path.

---

## Change checklist (files)

Phone:
- `navigation/.../MapBitmapStreamer.kt`: ctor `sendBitmap: (ByteArray)->Unit`; new
  `OUTPUT_SQUARE`/`GRAYSCALE`, lower `WEBP_QUALITY`; `processAndSend` grayscale+scale-to-square,
  drop base64 (delete L321-322), emit raw bytes, in-flight drop guard; `captureAndSend` delete
  movement gate (L283-286); frame timer `CAPTURE_INTERVAL_MS=66..80`.
- `navigation/.../NavigationManager.kt`: `sendMapBitmap: ((ByteArray)->Unit)?` (L120).
- `app/.../service/ListenerService.kt`: L3214 wire to `sendMapBitmapBytes`; nav-start/stop
  set/clear `"map_streaming"` session label.
- `app/.../bt/PhoneBtHost.kt`: add `mapRfcommClient` (or `RfcommLink` x2); `sendMapBitmapBytes`;
  start/stop in `startBtHost` (L633); reconnect wiring reusing existing signals.
- `app/.../bt/GlassesRfcommClient.kt` (or new `RfcommLink`/`MapRfcommClient`): `MAP_UUID`,
  `buildBinaryFrame`, `sendBinary` (drop-when-down).
- `app/.../bt/BtProtocol.kt`: add `CH_MAP_BITMAP_BIN` (and `MAP_UUID` const if centralized).

Glasses:
- `app/.../bt/MessageRelay.kt`: parameterize by `(uuid, serviceName)`; `binaryChannels` set;
  binary `parseFrame` path; `onBinaryMessage` on `Listener`.
- `app/.../bt/GlassesBtClient.kt`: dispatch `CH_MAP_BITMAP_BIN` -> `onMapBitmapBytes(ByteArray)`.
- `app/.../service/ListenerService.kt`: instantiate second `MessageRelay` on `MAP_UUID`/`"GlassesMap"`;
  `onMapBitmapBytes` broadcasts `EXTRA_MAP_BITMAP_BYTES` (byte[]); overlay decode L6843 from bytes.
- `app/.../MainActivity.kt`: `pendingMapFrame: AtomicReference<ByteArray?>`; receiver reads byte[]
  extra; `processLatestMapFrame` drops base64 (L1411), decodes bytes; shorten/remove crossfade
  (`setMapBaseBitmap` L1461 250 ms -> ~60 ms / direct set) on the fast path.
- `app/.../bt/BtProtocol.kt` (glasses copy): add `CH_MAP_BITMAP_BIN`.

bt-manager: **no AIDL change** (`writeRfcommSocket` is already `byte[]`); supports the second UUID as
a second `listenRfcommInbound` call already.

Per repo policy (no legacy): once the binary + dedicated-socket path is in, remove the String
`CH_MAP_BITMAP` map path and base64 map code entirely.
