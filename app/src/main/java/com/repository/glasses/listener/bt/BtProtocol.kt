package com.repository.glasses.listener.bt

object BtProtocol {
    const val CH_STATUS = "listener_status"

    // Glasses -> phone authoritative state snapshot, sent on every (re)connect.
    // Args: [state: String, requestId: String]. Phone reconciles its own
    // glassesAudioState to match. Per-transition CH_STATUS messages remain a
    // low-latency optimization; this is the source of truth across reconnects.
    const val CH_STATE_SNAPSHOT = "listener_state_snapshot"
    const val CH_RESPONSE = "listener_response"
    const val CH_COMMAND = "listener_command"
    const val CH_COMMAND_RESPONSE = "listener_command_response"
    const val CH_TTS_AUDIO = "listener_tts_audio"
    const val CH_TTS_INTERRUPT = "listener_tts_interrupt"
    const val CH_SETTINGS = "listener_settings"
    const val CH_TOOL_STATUS = "listener_tool_status"
    const val CH_STREAMING_TEXT = "listener_streaming_text"

    // Phone -> glasses
    const val CH_DISMISS_SESSION = "listener_dismiss_session"
    const val CH_ROKID_COMMAND = "listener_rokid_command"
    const val CH_ACTIVATE = "listener_activate"

    // Phone -> glasses device tool commands (dedicated channel to avoid CXR-S subscription bugs)
    const val CH_DEVICE_COMMAND = "listener_device_command"

    // Phone -> glasses map base frame (navigation minimap). Raw WEBP bytes delivered
    // as a single binary arg over the dedicated map RFCOMM socket (MAP_UUID). NOT base64,
    // NOT UTF-8 decoded. See MessageRelay binaryChannels + onBinaryMessage.
    const val CH_MAP_BITMAP_BIN = "listener_map_bitmap_bin"

    // Phone -> glasses arrow position+heading samples for the minimap.
    // Args: [normX: String(Float), normY: String(Float), headingDeg: String(Float)]
    // Sent at ~5 Hz; the glasses interpolate between samples for smooth motion.
    const val CH_MAP_ARROW = "listener_map_arrow"

    // Health check (phone -> glasses -> phone)
    const val CH_HEALTH_PING = "listener_health_ping"
    const val CH_HEALTH_PONG = "listener_health_pong"

    // Chat list (glasses -> phone -> glasses)
    const val CH_CHAT_LIST_REQUEST = "listener_chat_list_req"
    const val CH_CHAT_LIST_RESPONSE = "listener_chat_list_resp"
    const val CH_SWITCH_CHAT = "listener_switch_chat"
    const val CH_NEW_CHAT = "listener_new_chat"
    const val CH_CHAT_HISTORY = "listener_chat_history"

    // Glasses -> phone heading (IMU rotation vector)
    const val CH_GLASSES_HEADING = "listener_glasses_heading"

    // Glasses -> phone mouse HID report (6-byte base64-encoded).
    // Sent at ~30 Hz while head tracking is active. Phone forwards to BT Classic HID.
    const val CH_MOUSE_REPORT = "listener_mouse_report"

    // Phone -> glasses translation (custom UI, bypasses Rokid OS)
    const val CH_TRANSLATION_RESULT = "listener_trans_result"
    const val CH_TRANSLATION_CONFIG = "listener_trans_config"

    // Phone -> glasses desired translation on/off state (authoritative reconciliation).
    // The phone owns translationMode; the glasses reconcile their translationFrontMicRecorder
    // to match. Heals any lost edge-triggered start_translation/stop_translation command
    // (fire-and-forget RFCOMM can drop, esp. during the reconnect burst). Sent on glasses
    // connect, whenever translationMode changes, and periodically while translation is active.
    // Single JSON arg: {active, from, to, fromNllb, toNllb, fontSize, twoWay}.
    const val CH_TRANSLATION_STATE = "listener_trans_state"

    // Glasses -> phone mic audio (raw PCM16LE, Base64 encoded)
    const val CH_AUDIO_DATA = "listener_audio_data"

    // Glasses -> phone inward mic audio (VOICE_COMMUNICATION source, wearer's voice)
    const val CH_AUDIO_DATA_INWARD = "listener_audio_data_inward"

    // Glasses -> phone HFP call downlink audio (far party). Opus-compressed 16kHz mono:
    // concatenated 2-byte-LE-length Opus frames, Base64 (NO_WRAP) encoded. Tapped from
    // the 8ch mic array's hardware-echo channel (the SCO downlink rendered to the glasses
    // speaker) inside TranslationFrontMicRecorder while a call is active, then Opus-encoded
    // to keep RFCOMM from saturating. Fed into the phone's translation session as the
    // "system audio" sub-source during calls. Single arg: [b64Opus].
    const val CH_AUDIO_DATA_CALL = "listener_audio_data_call"

    // Glasses -> phone HFP SCO call-audio state. The glasses are the HFP hands-free
    // endpoint, so their SCO state is the authoritative signal for "far-party call audio
    // is arriving". The phone flips its translation system sub-source (playback<->call)
    // on this. Args: ["1" sco active, "0" sco idle].
    const val CH_CALL_STATE = "listener_call_state"

    // Phone -> glasses live partial transcription (Vosk partials while speaking)
    const val CH_GLASSES_PARTIAL_TEXT = "listener_partial_text"

    // Phone -> glasses user's final transcribed text
    const val CH_GLASSES_USER_TEXT = "listener_user_text"

    // ReID face detection (glasses -> phone -> API -> phone -> glasses)
    const val CH_REID_FACE = "listener_reid_face"
    const val CH_REID_RESULT = "listener_reid_result"
    const val CH_REID_MERGE = "listener_reid_merge"

    // ReID best thumbnail (phone -> glasses, sent after successful match)
    const val CH_REID_BEST_THUMB = "listener_reid_best_thumb"

    // ReID person intel (glasses -> phone -> glasses)
    const val CH_REID_PERSON_REQ = "listener_reid_person_req"
    const val CH_REID_PERSON_RESP = "listener_reid_person_resp"

    // Phone -> glasses system status (orchestrator connection)
    const val CH_SYSTEM_STATUS = "listener_system_status"

    // Todo list (glasses -> phone -> glasses)
    const val CH_TODO_LIST_REQ = "listener_todo_list_req"
    const val CH_TODO_LIST_RESP = "listener_todo_list_resp"
    const val CH_TODO_TOGGLE = "listener_todo_toggle"
    const val CH_TODO_ADD = "listener_todo_add"
    const val CH_TODO_REMOVE = "listener_todo_remove"

    // Alarm list (glasses <-> phone, plus proactive push)
    const val CH_ALARM_LIST_REQ = "listener_alarm_list_req"
    const val CH_ALARM_LIST_RESP = "listener_alarm_list_resp"

    // Job list (glasses <-> phone, plus proactive push)
    const val CH_JOB_LIST_REQ = "listener_job_list_req"
    const val CH_JOB_LIST_RESP = "listener_job_list_resp"

    // Telegram chat (glasses <-> phone)
    const val CH_TG_CHAT_LIST_REQ = "listener_tg_chat_list_req"
    const val CH_TG_CHAT_LIST_RESP = "listener_tg_chat_list_resp"
    const val CH_TG_MESSAGES_REQ = "listener_tg_msgs_req"
    const val CH_TG_MESSAGES_RESP = "listener_tg_msgs_resp"
    const val CH_TG_SEND_REQ = "listener_tg_send_req"
    const val CH_TG_SEND_RESP = "listener_tg_send_resp"
    const val CH_TG_SUBSCRIBE = "listener_tg_subscribe"
    const val CH_TG_UNSUBSCRIBE = "listener_tg_unsubscribe"
    const val CH_TG_NEW_MESSAGE = "listener_tg_new_msg"
    const val CH_TG_OPEN_CHAT = "listener_tg_open_chat"
    const val CH_TG_CLOSE_CHAT = "listener_tg_close_chat"
    const val CH_TG_TOPICS_REQ = "listener_tg_topics_req"
    const val CH_TG_TOPICS_RESP = "listener_tg_topics_resp"
    const val CH_TG_VOICE_START = "listener_tg_voice_start"
    const val CH_TG_VOICE_STOP = "listener_tg_voice_stop"

    // --- RC mirror (spec 2026-08-06, six channels; there is deliberately no CH_RC_LIST_REQ:
    // the state push is a full unsolicited snapshot, re-sent on every link-up) ---
    const val CH_RC_STATE_PUSH    = "listener_rc_state_push"   // phone -> glasses
    const val CH_RC_MESSAGES_REQ  = "listener_rc_msgs_req"     // glasses -> phone
    const val CH_RC_MESSAGES_RESP = "listener_rc_msgs_resp"    // phone -> glasses
    const val CH_RC_SEND_REQ      = "listener_rc_send_req"     // glasses -> phone
    const val CH_RC_SEND_RESP     = "listener_rc_send_resp"    // phone -> glasses (errors only)
    const val CH_RC_ANSWER_REQ    = "listener_rc_answer_req"   // glasses -> phone

    // Notifications (phone -> glasses)
    const val CH_NOTIFICATION = "listener_notification"
    const val CH_NOTIFICATION_TTS = "listener_notification_tts"

    // Phone -> glasses replace-current-notification-body. Sent when the phone merges
    // same-sender notifications: it keeps per-message segments, re-sorts them by
    // timestamp, and pushes the full recomputed body so the glasses REPLACE (not
    // append) the on-screen text and restart the dismiss timer. Args: [notifId, fullText].
    const val CH_NOTIFICATION_SETTEXT = "listener_notification_settext"

    // Notifications (glasses -> phone acknowledgement)
    const val CH_NOTIFICATION_DONE = "listener_notification_done"

    // Notification hold-to-reply (glasses -> phone). Native Telegram reply flow.
    // CH_NOTIFICATION payload now carries a 5th arg `repliable` in {"1","0"}:
    //   [notifId, sender, text, chat, repliable]
    const val CH_NOTIF_REPLY_START = "listener_notif_reply_start"   // args: [notifId]
    const val CH_NOTIF_REPLY_SEND = "listener_notif_reply_send"     // args: [notifId, text]
    const val CH_NOTIF_REPLY_CANCEL = "listener_notif_reply_cancel" // args: [notifId]

    // Phone -> glasses reply delivery result, sent after the phone actually fires
    // (or fails) the RemoteInput. Drives the overlay SENT / FAILED state instead of
    // the glasses optimistically claiming SENT. Args: [notifId, ok("1"/"0")].
    const val CH_NOTIF_REPLY_RESULT = "listener_notif_reply_result"

    // Glasses -> phone audio ducking (duck phone STREAM_MUSIC during glasses TTS)
    const val CH_AUDIO_DUCK = "listener_audio_duck"

    // Glasses -> phone wear state (proximity-driven on-head detection).
    // Args: ["1" worn, "0" off-head]. Emitted on every transition and once on (re)connect.
    const val CH_WEAR_STATE = "listener_wear_state"

    // Glasses -> phone screen on/off. Used (alongside wear state) to gate the
    // heavy map-bitmap stream so we don't burn BT bandwidth on a dark HUD.
    // Args: ["1" on, "0" off]. Emitted on every transition and once on (re)connect.
    const val CH_SCREEN_STATE = "listener_screen_state"

    // File sync (glasses <-> phone). See SyncChannelHandler / GlassesSyncClient.
    const val CH_SYNC = "listener_sync"

    // Sideload-through-phone session (phone <-> glasses). A minimal parallel handshake
    // to open/close the WiFi Direct link for a deploy session, independent of the photo
    // pull FSM (CH_SYNC). Reuses the SAME filesync WifiDirectHost via FileSyncBridge.
    // See SideloadChannelHandler. Wire (single JSON arg):
    //   phone -> glasses {"t":"OPEN_WIFI"} | {"t":"CLOSE_WIFI"}
    //   glasses -> phone {"t":"WIFI_READY","details":{ssid,passphrase,ip,port,deviceAddress}}
    //                    {"t":"WIFI_ERROR","reason":"..."} | {"t":"WIFI_CLOSED"}
    const val CH_SIDELOAD = "listener_sideload"

    // Glasses -> phone command (translation toggle, etc.)
    const val CH_GLASSES_COMMAND = "glasses_command"

    // Glasses -> phone wake event (on-glasses wake-word detector fire).
    // Phone-side handler: ListenerService.handleGlassesWakeEvent -- the authoritative
    // trigger for activating a glasses voice session. The phone does not run a
    // wake-word detector against the glasses audio stream.
    // Args: [confidence: String (Float.toString), epochNanos: String (Long.toString)]
    const val CH_WAKE_EVENT = "listener_wake_event"

    // Phone -> glasses wall-clock + timezone sync. Args: [epochMillis, tzId].
    const val CH_TIME_SYNC = "listener_time_sync"

    // Phone -> glasses weather widget.
    // Args: [iconTag: String, tempC: String, locationLabel: String]
    // iconTag: "clear"|"cloudy"|"rain"|"snow"|"thunder"|"fog"|"" (empty hides widget)
    const val CH_WEATHER = "listener_weather"

    // Contact list cache for HFP caller-ID. See phone-side BtProtocol for wire format.
    const val CH_CONTACTS = "listener_contacts"

    // Remote input events from a registered InputSource (Wear watch bezel/tap today, a future
    // BLE gadget later). Carried on the DEDICATED input RFCOMM socket (MessageRelay.INPUT_UUID),
    // never the shared message socket -- bulk frames there head-of-line-block input for seconds
    // (a 100 KB TTS blob for ~2.5 s, a sideload for minutes) and would evict other features'
    // frames from the shared bounded outbound queue.
    // Args: [v, src, sid, seq, type, steps, wms, tag]  -- all decimal ASCII except src, type, tag.
    //   v:     protocol version, always "1". Receivers drop anything else.
    //   src:   source id, matched against the registered InputSource ids. [a-z0-9_]{1,16}.
    //   sid:   session id minted by the source. UNSIGNED decimal 0..4294967295, no sign character,
    //          no leading zeros. A sender holding this in a signed 32-bit int MUST convert before
    //          rendering, or its digest will not match the receiver's.
    //   seq:   monotonic per (src, sid), incremented for EVERY event including OPEN/CLOSE/PING.
    //          Same unsigned decimal rendering as sid.
    //   type:  "SCROLL"|"SELECT"|"BACK"|"OPEN"|"CLOSE"|"PING". The NAME, not a numeric opcode --
    //          including inside the HMAC input.
    //   steps: coalesced detent count as SIGNED decimal ("3", "-3", "0"). Positive = forward/down,
    //          negative = back/up. No leading "+", no leading zeros. "0" for non-SCROLL types.
    //   wms:   source elapsedRealtime low 32 bits at detent time. Age is derived against the
    //          OPEN frame's baseline, so it is a single-clock delta with no cross-device skew.
    //          Same unsigned decimal rendering as sid -- this field crosses the sign bit routinely.
    //   tag:   16 lowercase hex chars, HMAC-SHA256 truncated to 8 bytes. The signed string is
    //          RemoteInputAuth.canonicalMessage() -- a domain-separated, length-prefixed encoding,
    //          NOT a bare "|"-join. Senders must port that exact function.
    // Receivers MUST check args.size >= 8, use toIntOrNull()/toLongOrNull(), and wrap the whole
    // parse in try/catch -- onMessage runs on a Binder thread and an uncaught throw kills the
    // service. Extra trailing args MUST be ignored (forward compatibility inside v1); fewer than
    // 8 MUST be rejected.
    const val CH_REMOTE_INPUT = "listener_remote_input"

    // Glasses -> source status backchannel on the same dedicated input socket. Lets a source tell
    // its user why nothing is happening rather than showing a connected state while events are
    // silently dropped.
    // Args: [sessionOpen, sinkAttached, droppedTotal] -- decimal ASCII, "1"/"0" for the flags.
    //   sessionOpen:  the glasses hold an open session for this source.
    //   sinkAttached: a UI sink is attached, i.e. events will be acted on rather than dropped.
    //                 This is the glasses-side input to the relaying phone's `glassesSinkAttached`
    //                 status bit; sessionOpen=1 with sinkAttached=0 means the glasses screen is not
    //                 active and the source should say so instead of showing a ready state.
    //   droppedTotal: cumulative events dropped for this source since the router was created.
    //                 A rising value is the source's `lastSendDropped` signal.
    // Sent on session open/close, on sink attach/detach, and at most once per second otherwise.
    const val CH_REMOTE_INPUT_STATUS = "listener_remote_input_status"

    // Sink attach/detach, as a standalone signal. Args: ["1"] attached, ["0"] detached.
    //
    // Narrower than CH_REMOTE_INPUT_STATUS on purpose: this one bit answers "would an event sent
    // right now actually be acted on", which is the only thing the remote device needs in order to
    // avoid showing READY while every event is being dropped. It is genuinely dynamic, because the
    // sink lives in a different process from the Bluetooth transport -- the UI process can be
    // unstarted, unbound, or dead while the backend is perfectly healthy.
    //
    // Emitted from the SAME transitions that attach and detach the AIDL sink, so the two cannot
    // disagree. A sink state that lies is worse than none.
    const val CH_REMOTE_INPUT_SINK = "listener_remote_input_sink"

    // Glasses -> phone: a transcript produced by the ON-GLASSES recogniser.
    // Args: [sessionTag, status, text]
    //   sessionTag: "assistant" (AI hold / wake-word follow-on) or "tg_voice"
    //               (Telegram voice, notification reply and RC voice all share it;
    //               the glasses focusState disambiguates, exactly as today).
    //   status:     "ok"   -- local recognition produced a final, `text` is it.
    //               "fail" -- local STT could not do it (model absent, NPU busy,
    //                         Binder timeout, capture dead). The phone falls back
    //                         to batch-transcribing the PCM it buffered.
    //   text:       the final transcript. EMPTY IS MEANINGFUL: "" with status=ok
    //               is an explicit empty final, i.e. the wearer cancelled, and the
    //               phone must emit the empty user text and clear its pending
    //               notification reply. Encoders must keep "" in its argument
    //               slot; collapsing it to a missing arg hangs a notification
    //               reply in SENDING forever. See LocalTranscriptWire.
    // Finals only -- local mode emits no partials, so no ACTION_PARTIAL_TEXT.
    // Receivers MUST check args.size >= 3 and wrap the parse in try/catch:
    // onMessage runs on a Binder thread and an uncaught throw kills the service.
    const val CH_LOCAL_TRANSCRIPT = "listener_local_transcript"

    // Glasses -> phone, sent BEFORE the session opens: which recogniser will
    // handle it. Args: [mode, sessionTag], mode = "local" | "remote".
    // On "local" the phone opens no transcriber WebSocket, does not feed its VAD
    // and does not arm the no-speech watchdog -- but it KEEPS buffering the PCM,
    // because that buffer is what the "fail" fallback transcribes.
    // Anything not exactly "local" means remote: failing the other way would
    // leave nobody transcribing at all.
    const val CH_STT_MODE = "listener_stt_mode"

    // Glasses -> phone, on connect and on change: whether local recognition is
    // possible at all on this device. Args: [available ("1"|"0"), modelVersion].
    // Independent of any session, so the phone can surface the state rather than
    // inferring it from a mode announcement that may never come.
    const val CH_STT_CAPABILITY = "listener_stt_capability"
}
