# ACD Crash — HIDL Client Investigation

Date: 2026-06-08 (read-only investigation on glasses, adb <GLASSES_SERIAL>, Android 12)

## TL;DR

The crashing ACD (Acoustic Context Detection) stream is opened by **OUR app**:
`com.repository.glasses.listener:backend` (PID 9632, UID 10028), through its
native JNI tag **`AcdNative`** / **`AcdNativeDetector`**, which is part of the
app's **`WakeWordPipeline`**. It is NOT stock Rokid firmware. The app calls
`pal_stream_open(type=23)` -> `pal_stream_start` on the ACD stream every time it
"arms" the wakeword pipeline. `pal_stream_start` returns -131 ("pcm open not
ready") and, inside the vendor HAL, the failure path runs through
`SessionAlsaPcm::start` which hits `__ubsan_handle_mul_overflow` in the custom
`sound_trigger.primary.neo.so` and **aborts the entire audio HAL**. This is a
self-inflicted crash, not a case where our app is merely a victim of the cascade
— our app is the INITIATOR. The HAL death then cascades and restarts everyone.

## 1. Confirmed crash signature + recurrence

Abort message: `ubsan: mul-overflow`, SIGABRT in
`/vendor/bin/hw/android.hardware.audio.service_64` (uid 1041).

Backtrace (identical across every tombstone):
```
#00 abort
#01 sound_trigger.primary.neo.so  abort_with_message
#02 sound_trigger.primary.neo.so  __ubsan_handle_mul_overflow_minimal_abort
#03 libar-pal.so                  SessionAlsaPcm::start(Stream*)+10224
#04 libar-pal.so                  ACDEngine::ProcessStartEngine(Stream*)+252
#05 libar-pal.so                  ACDEngine::StartEngine(Stream*)+156
#06 libar-pal.so                  StreamACD::ACDLoaded::ProcessEvent(...)
```

Recent tombstones (all same signature, audio.service_64):
```
tombstone_49  2026-06-08 21:57
tombstone_48  2026-06-08 21:54
tombstone_47  2026-06-08 21:50
tombstone_46  2026-06-08 21:44
tombstone_45  2026-06-08 21:41
tombstone_44  2026-06-08 21:41
tombstone_43  2026-06-08 21:21
tombstone_42  2026-06-08 21:18
... earlier cluster 03:09-04:21 (tombstone_30..41)
```
Crash-buffer DEBUG headers confirm each is
`Cmdline: /vendor/bin/hw/android.hardware.audio.service_64`,
`Abort message: 'ubsan: mul-overflow'`, e.g. at 21:21:58, 21:41:35, 21:41:37,
21:44:27. Recurs every few minutes, in bursts that line up with phone
connect/disconnect churn.

## 2. IDENTIFIED client = our app (evidence)

PID/UID map:
```
 9625  1041 android.hardware.audio.service_64        <- the HAL that crashes
 9632 10028 com.repository.glasses.listener:backend  <- the CLIENT
```

The PAL client wrapper logs run under PID **9632**, and the app's own
`AcdNative` tag prints the open/start sequence under the same PID 9632, e.g.:
```
21:58:40.732  9632 10024 I GlassesListenerSvc: [WWGate] reason=phone-connect worn=true phone=true wakewordEnabled=true needed=true running=false action=start
21:58:40.740  9632 10024 I AcdNativeDetector: ACD native armed
21:58:40.741  9632 10031 I AcdNative: pal_init -> 0
21:58:40.742  9632 10024 I WakeWordPipeline: start() loading ONNX sessions (acdArmed=true, vad=silero)
21:58:40.743  9632 10031 I AcdNative: pal_stream_open -> 0 handle=0xb40000708c630480
21:58:40.748  9632 10031 I AcdNative: LOAD_SOUND_MODEL -> 0
21:58:40.756  9632 10031 I AcdNative: RECOGNITION_CONFIG -> 0 (... num=1 ctx=0x08001335)
21:58:40.788  9632 10031 I AcdNative: pal_stream_start -> -131   <-- this start crashes the HAL
```

Matching HAL-side PAL (PID 9625, fed by client 9632 via pal_client_wrapper):
```
21:58:40.741  9632 10031 D pal_client_wrapper: pal_stream_open:251 channels[in 16:out 16] ...
21:58:40.742  9625  9625 I PAL: API: pal_stream_open: 213: Enter, stream type:23
21:58:40.742  9625  9625 I PAL: StreamACD: StreamACD: 146: capture conc enable 1,voice conc enable 1,voip conc enable 1
21:58:40.748  9625  9625 I PAL: StreamACD: GetUUID: Input vendor uuid : 4e93281b-296e-4d73-9833-2710c3c7c1db
21:58:40.749  9625  9625 I PAL: StreamACD: UpdateRecognitionConfig: Num Contexts = 1
21:58:40.754  9625  9625 I PAL: ACDEngine: PopulateEventPayload: Registering event for context id 0x8001335
21:58:40.754  9625  9625 I PAL: ACDEngine: LoadSoundModel: Loading model 2
21:58:40.756  9625  9625 I PAL: API: pal_stream_start: 683: Enter ...
21:58:40.757  9625  9625 E PAL: ResourceManager: voteSleepMonitor: ioctl device is not open
21:58:40.787  9625  9625 E PAL: SessionAlsaPcm: start: 800: pcm open not ready
21:58:40.787  9625  9625 E PAL: ACDEngine: ProcessStartEngine: Error:-131 Failed to start session
```
Stream type 23 = PAL_STREAM_ACD. UUID 4e93281b-296e-... = QC ACD vendor uuid.
ACDModuleType = QC_ACD. Single context id 0x08001335.

So the app opens a Qualcomm PAL **ACD** stream (NOT the SVA keyphrase path) and
uses it as an `acdArmed`/`ACD-gated` trigger for its ONNX WakeWordPipeline. The
app's own log literally calls it "ACD native armed" and "ACD-gated".

## 3. Stock firmware vs our app

OUR APP. The only voice-related processes running are:
```
com.rokid.sysconfig          (system, idle in epoll)
com.repository.glasses.listener        (PID 2106, u0_a28)
com.repository.glasses.listener:backend(PID 9632, u0_a28/uid 10028) <- the ACD client
```
No turen / AssistServer / vui / com.rokid.voice process is the client. The
ACD open is driven entirely by our `:backend` process. `com.rokid.sysconfig`
is just sitting idle and is not opening ACD.

## 4. What triggers the ACD start

The ACD start is gated by our app's `WWGate`/`SvcWake` state machine and fires
on **phone (A2DP) connect/disconnect churn while worn**. Each cycle:
```
[WWGate] reason=phone-connect worn=true phone=true wakewordEnabled=true needed=true running=false action=start
  -> AcdNativeDetector: ACD native armed
  -> AcdNative: pal_stream_open/start  -> HAL abort
...later...
[WWGate] reason=phone-disconnect ... needed=false running=true action=stop
  -> WakeWordPipeline: stop()
```
The phone (BT name "iPhone 14 Pro", the POCO companion) is flapping
connect/disconnect (see A2dpSinkService / BtMgr autoconnect periodic logs), and
every "phone-connect" edge re-arms ACD, re-issuing the crashing
`pal_stream_start`. That is why it recurs every few minutes. Screen-on and
notif-tts events also bump `SvcWake` but the ACD start itself is tied to the
WWGate `action=start` on phone-connect.

IMPORTANT nuance re "wakeword disabled": the app logs
`wakewordEnabled=true` here. Either the on-device wakeword toggle the user
believes is OFF is not actually reflected in this `:backend` state, or the
"disabled" setting only suppresses the keyphrase match while the **ACD gate**
(a separate path) still runs and still opens the crashing stream. The ACD gate
is what opens stream type 23 — it is independent of the SVA keyphrase model.

## 5. Is our app initiator or victim?

INITIATOR. Our `:backend` (PID 9632) explicitly calls
`pal_stream_open(type=23)` + `pal_stream_start` on the ACD stream via its
`AcdNative` JNI. That start is the exact call that detonates the
`__ubsan_handle_mul_overflow` in `sound_trigger.primary.neo.so` and aborts the
audio HAL. The HAL death then cascades to AudioFlinger/AudioPolicy and restarts
processes (the user's perceived A2DP/HFP jump). So our app is the root cause of
the crash, and also a downstream victim of the restart it triggers.

## Recommended fix direction (not applied — read-only task)

Stop arming the native ACD gate. The `AcdNativeDetector` / `acdArmed` path opens
a QC PAL ACD stream (type 23) whose start path is broken in the baked-in custom
`sound_trigger.primary.neo.so` (ubsan mul-overflow on the
`SessionAlsaPcm::start` failure branch when "pcm open not ready"). Options:
- Disable the ACD-gating in `WakeWordPipeline` and run the ONNX wakeword
  ungated (it already loads ONNX/silero VAD independently — "ACD-gated; AudioIn
  idle").
- Or gate on something other than the QC ACD HAL stream.
- The underlying HAL bug (mul-overflow in neo.so) cannot be patched without the
  stock `sound_trigger.primary.neo.so` (see MEMORY: SVA HAL blocker), so the fix
  must be on our side: do not call pal_stream_start on the ACD stream.
