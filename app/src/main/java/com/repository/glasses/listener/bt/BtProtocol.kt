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

    // Glasses -> phone mic audio (raw PCM16LE, Base64 encoded)
    const val CH_AUDIO_DATA = "listener_audio_data"

    // Glasses -> phone inward mic audio (VOICE_COMMUNICATION source, wearer's voice)
    const val CH_AUDIO_DATA_INWARD = "listener_audio_data_inward"

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
}
