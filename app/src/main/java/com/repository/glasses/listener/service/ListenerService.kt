package com.repository.glasses.listener.service

import android.app.ActivityManager
import com.repository.glasses.listener.ui.NotificationOverlay
import com.repository.glasses.listener.ui.CallOverlay
import com.repository.glasses.listener.bt.CallController
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.AssetFileDescriptor
import android.media.MediaPlayer
import com.repository.glasses.listener.media.MediaSessionMonitor
import com.repository.glasses.listener.audio.routing.AudioRoutingController
import com.repository.glasses.listener.audio.routing.WearState
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import java.io.IOException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import android.os.Binder
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.repository.glasses.listener.ui.BitmapUtils
import com.repository.glasses.listener.rokid.AssistantSuppressor
import com.repository.glasses.listener.audio.TtsPlayer
import com.repository.glasses.listener.bt.GlassesBtClient
import com.repository.glasses.listener.config.GlassesConfig
import com.repository.glasses.listener.capture.AudioRecorder
import com.repository.glasses.listener.capture.BeamformController
import com.repository.glasses.listener.capture.LocalOpusWriter
import com.repository.glasses.listener.capture.MicArrayTestRecorder
import com.repository.glasses.listener.capture.MicBus
import com.repository.glasses.listener.wakeword.WakeWordPipeline
import com.repository.glasses.listener.rokid.RokidServiceBridge
import com.repository.glasses.listener.rokid.RokidNavigationController
import com.repository.glasses.listener.util.LogCollector
import com.repository.glasses.listener.reid.ReidController
import com.repository.glasses.tracing.GT
import com.repository.glasses.listener.util.ScreenStateReceiver
import android.content.pm.ApplicationInfo
import org.json.JSONObject
import java.io.File

class ListenerService : LifecycleService(),
    GlassesBtClient.Listener,
    ScreenStateReceiver.ScreenStateListener {

    companion object {
        private const val TAG = "GlassesListenerSvc"
        /** A2DP music duck level as a fraction of current STREAM_MUSIC index. */
        private const val BTSINK_DUCK_FRACTION = 0.3f
        private const val TAG_WD = "App:Watchdog"
        private const val TAG_WAKE = "App:Wakelock"
        private const val TAG_FG = "App:Foreground"
        private const val TAG_DSR = "App:Dsr5"
        private const val TAG_RFCOMM = "App:Rfcomm"
        private const val TAG_LIFE = "App:SvcLife"
        /** Hardware echo reference channel index (speaker loopback). */
        /** Echo channel RMS threshold for speaker activity detection. */
        private const val ECHO_THRESHOLD = 0.001f
        private const val NOTIF_DEDUP_MS = 10_000L
        const val ACTION_STATE_UPDATE = "com.repository.glasses.listener.STATE_UPDATE"
        const val ACTION_CHAT_MESSAGE = "com.repository.glasses.listener.CHAT_MESSAGE"
        const val ACTION_TOOL_STATUS = "com.repository.glasses.listener.TOOL_STATUS"
        const val ACTION_STREAMING_TEXT = "com.repository.glasses.listener.STREAMING_TEXT"
        const val ACTION_BT_STATE = "com.repository.glasses.listener.BT_STATE"
        const val ACTION_SESSION_RESET = "com.repository.glasses.listener.SESSION_RESET"
        const val EXTRA_STATE = "state"
        const val EXTRA_CHAT_MESSAGE = "chat_message"
        const val EXTRA_TOOL_STATUS = "tool_status"
        const val EXTRA_STREAMING_TEXT = "streaming_text"
        const val EXTRA_BT_CONNECTED = "bt_connected"
        const val ACTION_ORCHESTRATOR_STATE = "com.repository.glasses.listener.ORCHESTRATOR_STATE"
        const val EXTRA_ORCHESTRATOR_CONNECTED = "orchestrator_connected"
        const val ACTION_CAMERA_PREVIEW = "com.repository.glasses.listener.CAMERA_PREVIEW"
        const val ACTION_CAMERA_PREVIEW_READY = "com.repository.glasses.listener.CAMERA_PREVIEW_READY"
        const val ACTION_TELEPROMPTER = "com.repository.glasses.listener.TELEPROMPTER"
        const val ACTION_TELEPROMPTER_STATE = "com.repository.glasses.listener.TELEPROMPTER_STATE"
        const val ACTION_MAP_BITMAP = "com.repository.glasses.listener.MAP_BITMAP"
        const val ACTION_MAP_ARROW = "com.repository.glasses.listener.MAP_ARROW"
        const val EXTRA_ARROW_X = "arrow_x"
        const val EXTRA_ARROW_Y = "arrow_y"
        const val EXTRA_ARROW_HEADING = "arrow_heading"
        const val ACTION_MAP_MINIMAP = "com.repository.glasses.listener.MAP_MINIMAP"
        const val ACTION_MAP_PIN = "com.repository.glasses.listener.MAP_PIN"
        const val ACTION_MAP_TAB_VISIBLE = "com.repository.glasses.listener.MAP_TAB_VISIBLE"
        const val ACTION_NAV_STEPS = "com.repository.glasses.listener.NAV_STEPS"
        const val ACTION_NAV_STEP_INDEX = "com.repository.glasses.listener.NAV_STEP_INDEX"
        const val EXTRA_MAP_BITMAP_BYTES = "map_bitmap_bytes"

        const val ACTION_CHAT_LIST = "com.repository.glasses.listener.CHAT_LIST"
        const val EXTRA_CHAT_LIST = "chat_list"
        const val ACTION_CHAT_HISTORY_LOADED = "com.repository.glasses.listener.CHAT_HISTORY_LOADED"
        const val EXTRA_CHAT_HISTORY = "chat_history"
        const val EXTRA_CONVERSATION_ID = "conversation_id"

        const val ACTION_REQUEST_NEW_CHAT = "com.repository.glasses.listener.REQUEST_NEW_CHAT"
        const val ACTION_TOGGLE_ASSISTANT = "com.repository.glasses.listener.TOGGLE_ASSISTANT"
        const val ACTION_REQUEST_CHAT_LIST = "com.repository.glasses.listener.REQUEST_CHAT_LIST"
        const val ACTION_SWITCH_CHAT = "com.repository.glasses.listener.SWITCH_CHAT"
        const val ACTION_DEBUG_STATUS = "com.repository.glasses.listener.DEBUG_STATUS"
        const val EXTRA_DEBUG_STATUS = "debug_status"
        const val ACTION_TRANSLATION_RESULT = "com.repository.glasses.listener.TRANSLATION_RESULT"
        const val ACTION_TRANSLATION_CONFIG = "com.repository.glasses.listener.TRANSLATION_CONFIG"
        const val ACTION_TRANSLATION_STATE = "com.repository.glasses.listener.TRANSLATION_STATE"
        const val ACTION_MOUSE_STATE = "com.repository.glasses.listener.MOUSE_STATE"
        const val EXTRA_MOUSE_ACTIVE = "mouse_active"
        const val EXTRA_MOUSE_SENSITIVITY_X = "sensitivity_x"
        const val EXTRA_MOUSE_SENSITIVITY_Y = "sensitivity_y"
        const val ACTION_RFCOMM_MOUSE_EVENT = "com.repository.glasses.listener.RFCOMM_MOUSE_EVENT"
        const val ACTION_TOOL_THUMBNAIL = "com.repository.glasses.listener.TOOL_THUMBNAIL"
        const val ACTION_BOTTOM_PADDING = "com.repository.glasses.listener.BOTTOM_PADDING"
        const val EXTRA_PADDING = "padding"
        const val ACTION_CHAT_FONT_SIZE = "com.repository.glasses.listener.CHAT_FONT_SIZE"
        const val EXTRA_CHAT_FONT_SIZE = "chat_font_size"
        const val ACTION_TAB_CHANGED = "com.repository.glasses.listener.TAB_CHANGED"
        const val EXTRA_TAB_ID = "tab_id"
        const val ACTION_CANCEL_SESSION = "com.repository.glasses.listener.CANCEL_SESSION"
        const val ACTION_SENSOR_LONG_PRESS = "com.repository.glasses.listener.SENSOR_LONG_PRESS"
        const val ACTION_STOP_JOURNEY = "com.repository.glasses.listener.STOP_JOURNEY"
        const val ACTION_NAV_ZOOM = "com.repository.glasses.listener.NAV_ZOOM"
        const val EXTRA_ZOOM_FRACTION = "zoom_fraction"
        const val ACTION_PARTIAL_TEXT = "com.repository.glasses.listener.PARTIAL_TEXT"
        const val ACTION_USER_TEXT = "com.repository.glasses.listener.USER_TEXT"
        const val EXTRA_PARTIAL_TEXT = "partial_text"
        const val EXTRA_USER_TEXT = "user_text"
        const val EXTRA_USER_TEXT_REQUEST_ID = "user_text_request_id"
        const val EXTRA_USER_PHOTO_THUMB = "photoThumbBase64"
        const val ACTION_PHOTO_PROGRESS = "com.repository.glasses.listener.PHOTO_PROGRESS"
        const val ACTION_RESPONSE_META = "com.repository.glasses.listener.RESPONSE_META"
        const val EXTRA_RESPONSE_META = "response_meta"
        const val ACTION_REID_FACES = "com.repository.glasses.listener.REID_FACES"
        const val ACTION_REID_STATS = "com.repository.glasses.listener.REID_STATS"
        const val ACTION_REID_STATUS = "com.repository.glasses.listener.REID_STATUS"
        const val ACTION_REID_START = "com.repository.glasses.listener.REID_START"
        const val ACTION_REID_STOP = "com.repository.glasses.listener.REID_STOP"
        const val ACTION_REID_PERSON_REQUEST = "com.repository.glasses.listener.REID_PERSON_REQUEST"
        const val ACTION_REID_PERSON_RESPONSE = "com.repository.glasses.listener.REID_PERSON_RESPONSE"
        const val ACTION_REID_BEST_THUMB = "com.repository.glasses.listener.REID_BEST_THUMB"
        const val EXTRA_REID_PERSON_UID = "reid_person_uid"
        const val EXTRA_REID_PERSON_JSON = "reid_person_json"
        const val EXTRA_REID_BEST_THUMB_DATA = "reid_best_thumb_data"
        const val ACTION_REQUEST_CAMERA_PERMISSION = "com.repository.glasses.listener.REQUEST_CAMERA_PERM"
        const val ACTION_CAMERA_PERMISSION_GRANTED = "com.repository.glasses.listener.CAMERA_PERM_GRANTED"
        const val ACTION_UI_RECORD = "com.repository.glasses.listener.UI_RECORD"
        const val ACTION_UI_RECORD_STARTED = "com.repository.glasses.listener.UI_RECORD_STARTED"
        const val ACTION_UI_RECORD_STOPPED = "com.repository.glasses.listener.UI_RECORD_STOPPED"
        const val EXTRA_REID_FACES = "reid_faces"
        const val EXTRA_REID_STATS = "reid_stats"
        const val EXTRA_REID_STATUS = "reid_status"
        const val EXTRA_TRANSLATION_RESULT = "translation_result"
        const val EXTRA_TRANSLATION_CONFIG = "translation_config"
        const val EXTRA_TRANSLATION_ACTIVE = "translation_active"
        // Glasses-initiated translation toggle (from MainActivity DPAD_CENTER on TRANSLATE tab)
        const val ACTION_REQUEST_TRANSLATION_TOGGLE = "com.repository.glasses.listener.REQUEST_TRANSLATION_TOGGLE"

        // Weather widget
        const val ACTION_WEATHER_UPDATE = "com.repository.glasses.listener.action.WEATHER_UPDATE"
        const val EXTRA_WEATHER_ICON = "icon"
        const val EXTRA_WEATHER_TEMP = "temp"
        const val EXTRA_WEATHER_LOCATION = "location"

        // Lone mode: backend -> UI process HUD indicator (foreign-device count, beside weather).
        const val ACTION_LONE_INDICATOR = "com.repository.glasses.listener.action.LONE_INDICATOR"
        const val EXTRA_LONE_ACTIVE = "lone_active"
        const val EXTRA_LONE_COUNT = "lone_count"

        // Video recording indicator (status bar glyph next to weather).
        // state: 0 = idle, 1 = recording, 2 = paused.
        const val ACTION_RECORDING_STATE = "com.repository.glasses.listener.action.RECORDING_STATE"
        const val EXTRA_RECORDING_STATE = "state"
        const val RECORDING_STATE_IDLE = 0
        const val RECORDING_STATE_ACTIVE = 1
        const val RECORDING_STATE_PAUSED = 2

        // Todo tab
        const val ACTION_REQUEST_TODO_LIST = "com.repository.glasses.listener.REQUEST_TODO_LIST"
        const val ACTION_TODO_TOGGLE = "com.repository.glasses.listener.TODO_TOGGLE"
        const val ACTION_TODO_ADD = "com.repository.glasses.listener.TODO_ADD"
        const val ACTION_TODO_REMOVE = "com.repository.glasses.listener.TODO_REMOVE"
        const val ACTION_TODO_LIST_LOADED = "com.repository.glasses.listener.TODO_LIST_LOADED"
        const val EXTRA_TODO_JSON = "todo_json"
        const val EXTRA_TODO_ID = "todo_id"
        const val EXTRA_TODO_TEXT = "todo_text"

        const val ACTION_ALARM_LIST_LOADED = "com.repository.glasses.listener.ALARM_LIST_LOADED"
        const val EXTRA_ALARM_JSON = "alarm_json"
        const val ACTION_JOB_LIST_LOADED = "com.repository.glasses.listener.JOB_LIST_LOADED"
        const val EXTRA_JOB_JSON = "job_json"
        const val ACTION_REQUEST_ALARM_LIST = "com.repository.glasses.listener.REQUEST_ALARM_LIST"
        const val ACTION_REQUEST_JOB_LIST = "com.repository.glasses.listener.REQUEST_JOB_LIST"

        // Telegram chat (service <-> UI)
        const val ACTION_TG_CHAT_LIST = "com.repository.glasses.listener.TG_CHAT_LIST"
        const val ACTION_TG_MESSAGES = "com.repository.glasses.listener.TG_MESSAGES"
        const val ACTION_TG_NEW_MESSAGE = "com.repository.glasses.listener.TG_NEW_MESSAGE"
        const val ACTION_TG_SEND_RESULT = "com.repository.glasses.listener.TG_SEND_RESULT"
        const val ACTION_REQUEST_TG_CHAT_LIST = "com.repository.glasses.listener.REQUEST_TG_CHAT_LIST"
        const val ACTION_REQUEST_TG_MESSAGES = "com.repository.glasses.listener.REQUEST_TG_MESSAGES"
        const val ACTION_TG_SEND_MSG = "com.repository.glasses.listener.TG_SEND_MSG"
        const val ACTION_TG_SUBSCRIBE = "com.repository.glasses.listener.TG_SUBSCRIBE"
        const val ACTION_TG_UNSUBSCRIBE = "com.repository.glasses.listener.TG_UNSUBSCRIBE"
        const val ACTION_TG_OPEN_CHAT = "com.repository.glasses.listener.TG_OPEN_CHAT"
        const val ACTION_TG_CLOSE_CHAT = "com.repository.glasses.listener.TG_CLOSE_CHAT"
        const val ACTION_REQUEST_TG_TOPICS = "com.repository.glasses.listener.REQUEST_TG_TOPICS"
        const val ACTION_TG_TOPICS = "com.repository.glasses.listener.TG_TOPICS"
        const val ACTION_TG_VOICE_START = "com.repository.glasses.listener.TG_VOICE_START"
        const val ACTION_TG_VOICE_STOP = "com.repository.glasses.listener.TG_VOICE_STOP"
        // Notification hold-to-reply.
        // service -> MainActivity
        const val ACTION_NOTIFICATION_SHOWN = "com.repository.glasses.listener.NOTIFICATION_SHOWN"
        const val ACTION_NOTIFICATION_HIDDEN = "com.repository.glasses.listener.NOTIFICATION_HIDDEN"
        // MainActivity -> service
        const val ACTION_NOTIF_HOLD_PROGRESS = "com.repository.glasses.listener.NOTIF_HOLD_PROGRESS"
        const val ACTION_NOTIF_HOLD_FREEZE = "com.repository.glasses.listener.NOTIF_HOLD_FREEZE"
        const val ACTION_NOTIF_REPLY_START = "com.repository.glasses.listener.NOTIF_REPLY_START"
        const val ACTION_NOTIF_REPLY_SEND = "com.repository.glasses.listener.NOTIF_REPLY_SEND"
        const val ACTION_NOTIF_REPLY_CANCEL = "com.repository.glasses.listener.NOTIF_REPLY_CANCEL"
        // Notification-solo backdrop (screen-was-off glanceable view).
        // service -> MainActivity: solo is armed (id extra). MainActivity arms its reveal trigger.
        const val ACTION_NOTIFICATION_SOLO_SHOW = "com.repository.glasses.listener.ACTION_NOTIFICATION_SOLO_SHOW"
        // MainActivity -> service: user interacted, MainActivity revealed its own content; ends the solo session.
        const val ACTION_NOTIFICATION_SOLO_REVEAL = "com.repository.glasses.listener.ACTION_NOTIFICATION_SOLO_REVEAL"
        // service -> MainActivity: solo session over, disarm.
        const val ACTION_NOTIFICATION_SOLO_END = "com.repository.glasses.listener.ACTION_NOTIFICATION_SOLO_END"
        // Debug-only: scripts the notification reply overlay through all phases
        // with sample data on a Handler timeline. No phone/transcriber involved.
        // NOT_EXPORTED. Used purely to record the look without real speech.
        const val ACTION_NOTIF_REPLY_DEMO = "com.repository.glasses.listener.NOTIF_REPLY_DEMO"
        // TEST-ONLY: inject a notification through the REAL onNotification() path so the
        // production screenWasOff/lastWornState solo decision runs (the reply DEMO above
        // hardcodes solo=false and cannot exercise the solo blackout). Optional extras:
        // "sender", "text", "chat" (string), "repliable", "force_worn" (bool).
        // Its receiver is registered NOT_EXPORTED -- this is a debugging aid, never a
        // shipping feature, so it is NOT reachable from an external `adb shell am broadcast`.
        // To trigger it for on-device testing, fire it IN-PROCESS, e.g. from an instrumented
        // androidTest in this UID:
        //   context.sendBroadcast(Intent(ACTION_NOTIFICATION_TEST)
        //       .setPackage("com.repository.glasses.listener")
        //       .putExtra("force_worn", true))
        const val ACTION_NOTIFICATION_TEST = "com.repository.glasses.listener.ACTION_NOTIFICATION_TEST"
        const val EXTRA_NOTIF_ID = "notif_id"
        const val EXTRA_NOTIF_REPLIABLE = "notif_repliable"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_HOLD_DURATION = "hold_duration"
        const val EXTRA_FREEZE = "freeze"
        const val EXTRA_REPLY_TEXT = "reply_text"
        // HFP call broadcasts.
        // MainActivity -> service: user touchpad actions.
        const val ACTION_CALL_ACCEPT = "com.repository.glasses.listener.CALL_ACCEPT"
        const val ACTION_CALL_DECLINE = "com.repository.glasses.listener.CALL_DECLINE"
        const val ACTION_CALL_TERMINATE = "com.repository.glasses.listener.CALL_TERMINATE"
        const val ACTION_HF_MIC_MUTE_TOGGLE = "com.repository.glasses.listener.HF_MIC_MUTE_TOGGLE"
        // CallController -> MainActivity: UI phase updates.
        const val ACTION_CALL_UI_STATE = "com.repository.glasses.listener.CALL_UI_STATE"
        // Debug-only triggers to preview the call UI without a real phone call.
        const val ACTION_DEBUG_CALL_SHOW_INCOMING = "com.repository.glasses.listener.DEBUG_CALL_SHOW_INCOMING"
        const val ACTION_DEBUG_CALL_SHOW_ACTIVE = "com.repository.glasses.listener.DEBUG_CALL_SHOW_ACTIVE"
        const val ACTION_DEBUG_CALL_HIDE = "com.repository.glasses.listener.DEBUG_CALL_HIDE"
        // Debug-only trigger to fire the Lone-mode alert SFX without a real foreign-device sighting.
        const val ACTION_DEBUG_LONE_ALERT = "com.repository.glasses.listener.DEBUG_LONE_ALERT"

        const val EXTRA_TG_CHAT_LIST_JSON = "tg_chat_list_json"
        const val EXTRA_TG_MESSAGES_JSON = "tg_messages_json"
        const val EXTRA_TG_NEW_MESSAGE_JSON = "tg_new_message_json"
        const val EXTRA_TG_SEND_RESULT_JSON = "tg_send_result_json"
        const val EXTRA_TG_CHAT_ID = "tg_chat_id"
        const val EXTRA_TG_CHAT_TITLE = "tg_chat_title"
        const val EXTRA_TG_TEXT = "tg_text"
        const val EXTRA_TG_LIMIT = "tg_limit"
        const val EXTRA_TG_TOPICS_JSON = "tg_topics_json"
        const val EXTRA_TG_TOPIC_ID = "tg_topic_id"
        const val EXTRA_TG_OFFSET_ID = "tg_offset_id"
        // Music media control
        const val ACTION_MEDIA_COMMAND = "com.repository.glasses.listener.MEDIA_COMMAND"
        const val ACTION_MEDIA_STATE = "com.repository.glasses.listener.MEDIA_STATE"
        const val EXTRA_MEDIA_COMMAND = "command"
        const val EXTRA_MEDIA_TRACK = "track"
        const val EXTRA_MEDIA_PLAYING = "playing"
        const val ACTION_MEDIA_PROGRESS = "com.repository.glasses.listener.MEDIA_PROGRESS"
        const val EXTRA_MEDIA_POSITION = "position"
        const val EXTRA_MEDIA_DURATION = "duration"
        /** SystemClock.elapsedRealtime() when [EXTRA_MEDIA_POSITION] was sampled.
         *  UI uses this to extrapolate live position while [EXTRA_MEDIA_PLAYING]. */
        const val EXTRA_MEDIA_POSITION_TS = "positionTs"

        const val ACTION_NOTIFICATION = "com.repository.glasses.listener.NOTIFICATION"
        const val ACTION_NOTIFICATION_DISMISSED = "com.repository.glasses.listener.NOTIFICATION_DISMISSED"
        const val ACTION_AUDIO_LEVELS = "com.repository.glasses.listener.AUDIO_LEVELS"
        const val EXTRA_AUDIO_LEVELS = "audio_levels"
        const val EXTRA_AUDIO_LEVELS_BANDS = "audio_levels_bands"
        // Each 1 s mic chunk is split into 20 sub-windows of ~50 ms so the visualizer
        // gets a 20 Hz envelope from a single broadcast, instead of one update per second.
        private const val LEVEL_SUBWINDOWS = 20

        // pendingAudioFrames dynamic cap. The buffer grows while the RFCOMM audio
        // socket is connecting so the full pre-connect window is retained and
        // flushed in order on connect (no first-word clipping). It is bounded by
        // total buffered audio DURATION to prevent unbounded growth / OOM during a
        // long disconnect window. 30 s is generous headroom over the realistic
        // worst-case cold connect; a 30 s utterance hitting the cap is already
        // pathological (Azure segments long before), so dropping the very oldest
        // FIFO frame at the cap is acceptable and bounded.
        private const val PENDING_AUDIO_MAX_MS = 30_000L
        // Per published frame = one mic chunk = chunkFrames(16000)/sampleRate(16000)
        // = 1000 ms of audio, encoded to a single Opus packet before queueing.
        // If the mic chunk cadence ever changes, update this to match so the
        // frame-count cap stays duration-accurate.
        private const val PENDING_AUDIO_FRAME_MS = 1000L
        private const val PENDING_AUDIO_MAX_FRAMES = (PENDING_AUDIO_MAX_MS / PENDING_AUDIO_FRAME_MS).toInt()

        private const val FG_CHANNEL_ID = "glasses_fg"
        private const val FG_NOTIF_ID = 1
        private const val LAUNCH_CHANNEL_ID = "glasses_launch"
        private const val LAUNCH_NOTIF_ID = 9999
        private const val WATCHDOG_INTERVAL_MS = 30_000L
        private const val WATCHDOG_RESPONDING_TIMEOUT_MS = 60_000L
        private const val WATCHDOG_TTS_IDLE_TIMEOUT_MS = 30_000L
        private const val WATCHDOG_LISTENING_TIMEOUT_MS = 180_000L // 3 min
    }

    enum class State { IDLE, LISTENING, RESPONDING }

    @Volatile
    private var state = State.IDLE
    private lateinit var btClient: GlassesBtClient
    /** Dedicated relay for the binary map base-frame stream (MAP_UUID). Null if init failed. */
    private var mapRelay: com.repository.glasses.listener.bt.MessageRelay? = null
    private lateinit var ttsPlayer: TtsPlayer
    private lateinit var notificationTtsPlayer: TtsPlayer
    private lateinit var notificationOverlay: NotificationOverlay
    private lateinit var callOverlay: CallOverlay
    private val assistantCardOverlay: com.repository.glasses.listener.ui.CopilotCardOverlay by lazy {
        com.repository.glasses.listener.ui.CopilotCardOverlay(this).also { it.remoteLog = { msg -> btLog(msg) } }
    }
    @Volatile private var assistantActive = false
    private val callController = CallController()
    private val contactsCache by lazy {
        com.repository.glasses.listener.bt.ContactsCache(applicationContext)
    }
    private var activatePlayer: MediaPlayer? = null

    // RFCOMM mouse -- runs HeadTracker in :backend process, sends reports to phone via RFCOMM
    private var rfcommMouseTracker: com.repository.glasses.listener.mouse.HeadTracker? = null
    private val rfcommMouseHandler = Handler(Looper.getMainLooper())
    private var rfcommMouseDx = 0f
    private var rfcommMouseDy = 0f
    private var rfcommMouseScroll = 0
    private var rfcommMouseButtons = 0
    private var rfcommMouseDirty = false
    private var rfcommMouseActive = false
    private val rfcommMouseFlushRunnable = Runnable { flushRfcommMouse() }

    // FIFO of one-shot callbacks waiting for the next photo to land via the
    // capture APK. Drained in order by captureFeedbackListener.onPhotoTaken.
    // onCaptureError drains all pending with null. The capture APK serializes
    // takePhoto() calls so order is preserved.
    private val pendingPhotoCallbacks = java.util.concurrent.ConcurrentLinkedQueue<(java.io.File?) -> Unit>()
    // Background executor for photo-callback work that arrives on the AIDL
    // binder thread (file.readBytes, bitmap decode, base64, BT send). Keeping
    // these off the binder thread avoids blocking the capture APK's binder
    // pool and ANR risk. Single-thread keeps callback ordering deterministic.
    private val photoCallbackExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "photo-callback").apply { priority = Thread.NORM_PRIORITY }
    }
    private lateinit var rawFrameCapturer: com.repository.glasses.listener.nightvision.RawFrameCapturer
    private lateinit var glassesAudioRecorder: AudioRecorder
    private lateinit var rokidBridge: RokidServiceBridge
    private lateinit var navigationCtrl: RokidNavigationController
    private lateinit var mediaSessionMonitor: MediaSessionMonitor
    private var btMediaSource: com.repository.glasses.listener.media.BtMediaSource? = null
    private var audioRouting: AudioRoutingController? = null
    private var batteryLedArmer: com.repository.glasses.listener.power.BatteryLedArmer? = null
    private val batteryLedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: android.content.Intent?) {
            batteryLedArmer?.setCharging(isCablePlugged(i))
        }
    }

    // "Charging" for the battery LED means a cable is PLUGGED, not the charge
    // STATUS. EXTRA_STATUS flaps CHARGING<->FULL<->NOT_CHARGING every ~1s at high
    // SoC (mp2724 trickle/maintenance), which would disarm the LED and restart
    // the 60s still-arm timer every second. EXTRA_PLUGGED stays non-zero for the
    // whole time the cable is in -- the app-side mirror of the daemon's reliance
    // on the charger `online` node over `status`.
    private fun isCablePlugged(i: android.content.Intent?): Boolean {
        val plugged = i?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        return plugged > 0
    }

    // On-glasses audio pipelines fed by MicBus (always-on mic pump).
    //   - localOpusWriter: writes rotated Opus archive under DCIM/Repository/audio-archive
    //   - wakeWordPipeline: silero VAD + sireneviy (QNN EP, CPU fallback); broadcasts
    //     ACTION_WAKE_WORD_HIT on fire, consumed by wakeWordHitReceiver below.
    //     Authoritative wake-word detector -- the phone no longer runs its own
    //     detector against the glasses audio stream.
    private lateinit var localOpusWriter: LocalOpusWriter
    private lateinit var wakeWordPipeline: WakeWordPipeline

    // Single-thread executor for off-main-thread reconcile of LocalOpusWriter.
    // localOpusWriter.stop() blocks up to 3s on awaitTermination, so we never
    // run it on the main thread (battery receiver, settings broadcast, etc.).
    private val reconcileExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "RecordingReconcile").apply { isDaemon = true }
    }

    // G4: 4-second mic PCM prebuffer. Captures every MicBus frame so that on
    // wake-word fire we can replay the ~0.5-1 s of audio that preceded the
    // wake event (lost otherwise to RFCOMM bring-up latency).
    private val prebuffer = com.repository.glasses.listener.capture.PrebufferingAudioSubscriber()
    private val pendingPrebufferSnapshot = java.util.concurrent.atomic.AtomicReference<com.repository.glasses.listener.capture.PrebufferingAudioSubscriber.Snapshot?>()
    private val prebufferFlushExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "PrebufferFlush").apply { isDaemon = true }
        }
    @Volatile private var prebufferFlushInProgress = false

    // Track B wear gating: cached most recent worn state from
    // AudioRoutingController.onWearChangedRaw. Volatile because the wear
    // broadcast fires on the main thread and we read it from the same thread
    // during boot, but treat it as a memory barrier in case future code reads
    // it from another thread. null means we haven't seen any wear event yet.
    @Volatile private var lastWornState: Boolean? = null
    @Volatile private var psensorEnforceLatched = false
    // Touched only on reconcileExecutor; tracks last on-demand bit seen by OpusGate
    // so we can detect on->off transitions and rotate the writer file.
    private var lastOnDemandSeen: Boolean = false
    private var translationFrontMicRecorder: com.repository.glasses.listener.capture.TranslationFrontMicRecorder? = null

    @Volatile
    private var currentRequestId: String? = null
    @Volatile
    private var cancelledRequestId: String? = null
    @Volatile
    private var audioSentTimestamp: Long = 0
    @Volatile
    private var streamingDelivered = false
    // Active recording state (both auto-stop and manual-stop)
    @Volatile private var activeRecordingRequestId: String? = null
    @Volatile private var activeRecordingCameraType: String? = null
    // "portrait" (rotate camera 270 to match Rokid waveguide) or "landscape" (native).
    @Volatile private var activeRecordingOrientation: String = "portrait"
    private var autoStopRunnable: Runnable? = null
    // Screen recording backup: MixRecordManager deletes the original after finishing,
    // so we copy it to our app's files dir when SCREENRECORD_STOP is received
    @Volatile private var lastScreenRecordBackupPath: String? = null
    // Screen recording via screenrecord shell process (hybrid approach)
    @Volatile private var activeScreenRecordProcess: Process? = null
    @Volatile private var activeScreenRecordFile: java.io.File? = null
    // Off-screen camera recorder (Camera2 -> MediaRecorder, no display)
    private var arVideoRecorder: com.repository.glasses.listener.capture.ArVideoRecorder? = null

    @Volatile private var activeArVideoFile: java.io.File? = null
    // Audio capture sink -- taps mic stream ch1 during video recording
    private var videoAudioFile: java.io.File? = null
    private var videoAudioStream: java.io.FileOutputStream? = null
    @Volatile private var videoAudioRecording = false
    private var videoAudioBytesWritten = 0

    /**
     * Scratch buffer for the un-amplified video-audio write. Mic pump's
     * monoBuffer carries the 24x-gained ch1 needed by MicBus / wake-word /
     * BT live stream; recordings deliberately bypass that gain so the saved
     * mp4 has a clean noise floor. Lazily resized to chunkFrames * 2 bytes.
     */
    private var videoRecScratch: ByteArray = ByteArray(0)
    // When true, the mic pump mixes the speaker echo-reference channels
    // (5,6,7 zero-indexed = mics 6-8, the taps that pick up what the device
    // is playing) into the WAV alongside the main beamformed mic. AR
    // recordings need this so playback contains system audio (TTS, BT music,
    // notification sounds). Plain record_video keeps mic-only.
    @Volatile private var videoAudioMixEcho = false
    // UI recording is done by MainActivity via ViewRecorder (draws view hierarchy to video)
    // Cache last photo thumbnail for auto-attach to USER messages (60s window)
    @Volatile private var lastPhotoThumbBase64: String? = null
    @Volatile private var lastPhotoThumbTimestamp: Long = 0

    // DCIM FileObserver for auto-detecting manual camera button photos
    private val dcimObservers = mutableListOf<android.os.FileObserver>()

    // State machine watchdog
    @Volatile private var stateEnteredTime = SystemClock.elapsedRealtime()
    @Volatile private var lastTtsReceivedTime = 0L
    private var lastActivityLaunchAttempt = 0L
    @Volatile private var activeTeleprompterRequestId: String? = null
    private lateinit var assistantSuppressor: AssistantSuppressor
    private lateinit var screenStateReceiver: ScreenStateReceiver
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    // NOTE: a periodic screen-state heartbeat used to live here (10s `sendStatus("heartbeat_screen_on/off")`).
    // Removed because it was the sole upstream emitter during idle, which kept bt-manager's RFCOMM
    // idle-teardown timer from ever expiring (commit e4b18d1f3). G2 then removed the wire-level
    // `__hb__` heartbeat entirely and replaced keep-alive with bt-manager active-session
    // ref-counting (RfcommManager.setActiveSession). Explicit screen_off / glasses_audio_open /
    // LISTENING / IDLE events are emitted on real transitions when the phone actually needs to
    // know. Keeping any periodic heartbeat would defeat the whole idle teardown.
    private var reidController: ReidController? = null

    // Map overlay (persistent minimap when pinned)
    private var mapOverlayView: ImageView? = null
    private val overlayHandler = Handler(Looper.getMainLooper())

    // Periodic recording_status resync: pushes localOpusWriter.isRunning() to
    // phone every 10s so a dropped event-driven push cannot leave the phone's
    // mirror state diverged from reality.
    private val recordingStatusResyncHandler = Handler(Looper.getMainLooper())
    private val RECORDING_STATUS_RESYNC_INTERVAL_MS = 10_000L
    private val recordingStatusResyncRunnable = object : Runnable {
        override fun run() {
            try {
                pushRecordingStatusToPhone()
            } catch (t: Throwable) {
                btErr("recordingStatusResync failed: ${t.message}")
            }
            recordingStatusResyncHandler.postDelayed(this, RECORDING_STATUS_RESYNC_INTERVAL_MS)
        }
    }

    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var watchdogTickCount = 0L
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            watchdogTickCount++
            val elapsed = SystemClock.elapsedRealtime() - stateEnteredTime
            if (watchdogTickCount % 12L == 0L) {
                Log.v(TAG_WD, "event=watchdog_tick n=$watchdogTickCount state=$state elapsed_ms=$elapsed")
            }
            when (state) {
                State.RESPONDING -> {
                    val ttsIdle = if (lastTtsReceivedTime > 0)
                        SystemClock.elapsedRealtime() - lastTtsReceivedTime
                    else
                        elapsed
                    if (elapsed > WATCHDOG_RESPONDING_TIMEOUT_MS && ttsIdle > WATCHDOG_TTS_IDLE_TIMEOUT_MS) {
                        btErr("WATCHDOG: stuck in RESPONDING for ${elapsed}ms, tts idle ${ttsIdle}ms -- forcing IDLE")
                        ttsPlayer.interrupt()
                        transitionToIdle()
                    }
                }
                State.LISTENING -> {
                    if (elapsed > WATCHDOG_LISTENING_TIMEOUT_MS) {
                        btErr("WATCHDOG: stuck in LISTENING for ${elapsed}ms -- forcing IDLE")
                        transitionToIdle()
                    }
                }
                State.IDLE -> { /* OK */ }
            }
            watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private fun btLog(msg: String) {
        // Mirror to logcat so debugging via `adb logcat` works even when the
        // persistent /sdcard/Download/glasses-client.log file isn't writable
        // (observed: WRITE_EXTERNAL_STORAGE / MANAGE_EXTERNAL_STORAGE not yet
        // granted, externalWriter == null, every btLog() became a no-op and
        // every handleSensorLongPress / activateListening / WW gate
        // transition was invisible).
        Log.i(TAG, msg)
        LogCollector.writeExternal("${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())} I/$TAG: $msg")
    }

    private fun btErr(msg: String) {
        Log.e(TAG, msg)
        LogCollector.writeExternal("${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())} E/$TAG: $msg")
    }


    private fun scheduleAutoStop(requestId: String, cameraType: String, duration: Int) {
        btLog("SCHED[1] creating auto-stop runnable: req=$requestId type=$cameraType delay=${duration}s")
        val runnable = Runnable {
            btLog("SCHED[2] auto-stop FIRED: req=$requestId type=$cameraType (after ${duration}s)")
            autoStopRunnable = null
            btLog("SCHED[3] cleared autoStopRunnable, calling stopRokidRecordingAndReport...")
            stopRokidRecordingAndReport(requestId, cameraType)
            btLog("SCHED[4] stopRokidRecordingAndReport returned")
        }
        autoStopRunnable = runnable
        watchdogHandler.postDelayed(runnable, duration * 1000L)
        btLog("SCHED[5] posted to handler, delay=${duration * 1000L}ms")
    }

    private fun startVideoAudioCapture(file: java.io.File) = GT.section("svc.startVideoAudioCapture") {
        try {
            file.parentFile?.mkdirs()
            val fos = java.io.FileOutputStream(file)
            // Write placeholder WAV header (44 bytes), will be fixed on stop
            val header = ByteArray(44)
            val sr = 16000
            val ch = 1
            val bps = 16
            val byteRate = sr * ch * bps / 8
            val blockAlign = ch * bps / 8
            header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
            header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
            // bytes 4-7: file size - 8 (placeholder)
            header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
            header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
            header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
            header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
            writeIntLE(header, 16, 16) // subchunk1 size
            writeShortLE(header, 20, 1) // PCM format
            writeShortLE(header, 22, ch)
            writeIntLE(header, 24, sr)
            writeIntLE(header, 28, byteRate)
            writeShortLE(header, 32, blockAlign)
            writeShortLE(header, 34, bps)
            header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
            header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
            // bytes 40-43: data size (placeholder)
            fos.write(header)
            videoAudioFile = file
            videoAudioStream = fos
            videoAudioBytesWritten = 0
            videoAudioRecording = true
            btLog("VIDEO_AUDIO: capture started -> ${file.name}")
        } catch (e: Exception) {
            btErr("VIDEO_AUDIO: failed to start capture: ${e.message}")
            videoAudioRecording = false
        }
    }

    private fun stopVideoAudioCapture(): java.io.File? = GT.section("svc.stopVideoAudioCapture") {
        videoAudioRecording = false
        videoAudioMixEcho = false
        val fos = videoAudioStream
        val file = videoAudioFile
        videoAudioStream = null
        videoAudioFile = null
        if (fos == null || file == null) return@section null
        try {
            fos.flush()
            fos.close()
        } catch (_: Exception) {}
        // Fix WAV header with actual sizes
        if (file.exists() && file.length() > 44) {
            try {
                val raf = java.io.RandomAccessFile(file, "rw")
                val fileSize = file.length()
                val dataSize = fileSize - 44
                raf.seek(4)
                writeIntLE(raf, (fileSize - 8).toInt())
                raf.seek(40)
                writeIntLE(raf, dataSize.toInt())
                raf.close()
                btLog("VIDEO_AUDIO: capture stopped, ${dataSize / 1024}KB audio written to ${file.name}")
            } catch (e: Exception) {
                btErr("VIDEO_AUDIO: failed to fix WAV header: ${e.message}")
            }
            return@section file
        }
        btLog("VIDEO_AUDIO: capture stopped but file empty/missing")
        null
    }

    private fun writeIntLE(arr: ByteArray, off: Int, v: Int) {
        arr[off] = (v and 0xFF).toByte()
        arr[off + 1] = (v shr 8 and 0xFF).toByte()
        arr[off + 2] = (v shr 16 and 0xFF).toByte()
        arr[off + 3] = (v shr 24 and 0xFF).toByte()
    }

    private fun writeShortLE(arr: ByteArray, off: Int, v: Int) {
        arr[off] = (v and 0xFF).toByte()
        arr[off + 1] = (v shr 8 and 0xFF).toByte()
    }

    private fun writeIntLE(raf: java.io.RandomAccessFile, v: Int) {
        raf.write(v and 0xFF)
        raf.write(v shr 8 and 0xFF)
        raf.write(v shr 16 and 0xFF)
        raf.write(v shr 24 and 0xFF)
    }

    private fun stopRokidRecordingAndReport(requestId: String, cameraType: String) {
        btLog("STOP[1] enter: type=$cameraType req=$requestId")
        activeRecordingRequestId = null
        activeRecordingCameraType = null

        try {
            // Step 1: Stop screenrecord shell process (SIGINT for clean mp4 finalization)
            val screenProc = activeScreenRecordProcess
            val screenFile = activeScreenRecordFile
            btLog("STOP[2] stopping screenrecord: process=${screenProc != null} file=${screenFile?.name}")
            if (screenProc != null) {
                try {
                    val pid = screenProc.toString().let {
                        val match = Regex("pid=(\\d+)").find(it)
                        match?.groupValues?.get(1)
                    }
                    if (pid != null) {
                        Runtime.getRuntime().exec(arrayOf("kill", "-2", pid))
                        btLog("STOP[2b] sent SIGINT to screenrecord pid=$pid")
                    } else {
                        screenProc.destroy()
                        btLog("STOP[2b] called destroy() on screenrecord process")
                    }
                } catch (e: Exception) {
                    btErr("STOP[2b-ERR] kill screenrecord: ${e.message}")
                    try { screenProc.destroy() } catch (_: Exception) {}
                }
                activeScreenRecordProcess = null
            }

            // Step 2: Stop UI recording via MainActivity ViewRecorder
            if (cameraType == "ar_offscreen") {
                btLog("STOP[3] telling Activity to stop UI recording...")
                sendBroadcast(Intent(ACTION_UI_RECORD).apply {
                    setPackage(packageName)
                    putExtra("action", "stop")
                })
            }

            // Step 3: Stop camera recording
            if (cameraType == "ar_offscreen") {
                btLog("STOP[4] stopping ArVideoRecorder...")
                val arAudioWav = stopVideoAudioCapture()
                arVideoRecorder?.stop { camPath ->
                    btLog("STOP[4b] ArVideoRecorder stopped: $camPath")
                    broadcastRecordingState(RECORDING_STATE_IDLE)
                    setCameraLedEnabled(true)
                    if (camPath != null && arAudioWav != null && arAudioWav.exists() && arAudioWav.length() > 44) {
                        // Mux audio into camera video before compositing
                        val muxedPath = camPath.replace(".mp4", "_av.mp4")
                        btLog("STOP[4c] muxing audio into AR camera video -> $muxedPath")
                        val muxer = com.repository.glasses.listener.capture.AudioVideoMuxer()
                        muxer.remoteLog = { btLog(it) }
                        muxer.mux(camPath, arAudioWav.absolutePath, muxedPath) { result ->
                            val finalCamPath = if (result != null) {
                                try { java.io.File(camPath).delete() } catch (_: Exception) {}
                                try { arAudioWav.delete() } catch (_: Exception) {}
                                val orig = java.io.File(camPath)
                                java.io.File(result).renameTo(orig)
                                orig.absolutePath
                            } else {
                                btLog("STOP[4d] mux failed, compositing without audio")
                                try { arAudioWav.delete() } catch (_: Exception) {}
                                camPath
                            }
                            reportArRecordingResult(requestId, finalCamPath, screenFile)
                        }
                    } else {
                        try { arAudioWav?.delete() } catch (_: Exception) {}
                        reportArRecordingResult(requestId, camPath, screenFile)
                    }
                }
            } else if (cameraType == "video_record") {
                btLog("STOP[3] stopping ArVideoRecorder for video_record...")
                val audioWav = stopVideoAudioCapture()
                val plainOrientation = activeRecordingOrientation
                arVideoRecorder?.stop { camPath ->
                    btLog("STOP[3b] ArVideoRecorder stopped: $camPath orientation=$plainOrientation")
                    broadcastRecordingState(RECORDING_STATE_IDLE)
                    setCameraLedEnabled(true)
                    if (camPath == null) {
                        try { audioWav?.delete() } catch (_: Exception) {}
                        btClient.sendCommandResult(requestId, JSONObject().apply {
                            put("success", false)
                            put("file_path", "")
                        }.toString())
                        return@stop
                    }

                    // Helper: mux audio into [videoForMux], patch tkhd to [tkhdRot],
                    // then atomically replace camPath with the muxed file. If no audio
                    // is available, just return videoForMux (already at camPath for
                    // portrait, or the transcoded file for landscape).
                    val muxInto: (String, Int) -> Unit = { videoForMux, tkhdRot ->
                        if (audioWav != null && audioWav.exists() && audioWav.length() > 44) {
                            val muxedPath = camPath.replace(".mp4", "_av.mp4")
                            btLog("STOP[3c] muxing audio -> ${java.io.File(muxedPath).name}")
                            val muxer = com.repository.glasses.listener.capture.AudioVideoMuxer()
                            muxer.remoteLog = { btLog(it) }
                            muxer.mux(videoForMux, audioWav.absolutePath, muxedPath, rotation = tkhdRot) { result ->
                                val finalPath = if (result != null) {
                                    try { java.io.File(videoForMux).delete() } catch (_: Exception) {}
                                    try { audioWav.delete() } catch (_: Exception) {}
                                    val orig = java.io.File(camPath)
                                    java.io.File(result).renameTo(orig)
                                    orig.absolutePath
                                } else {
                                    btLog("STOP[3d] mux failed, returning video without audio")
                                    try { audioWav.delete() } catch (_: Exception) {}
                                    videoForMux
                                }
                                notifyFileSync(finalPath, "video")
                                btClient.sendCommandResult(requestId, JSONObject().apply {
                                    put("success", true)
                                    put("file_path", finalPath)
                                }.toString())
                            }
                        } else {
                            try { audioWav?.delete() } catch (_: Exception) {}
                            // No audio: if videoForMux isn't already at camPath, move it.
                            if (videoForMux != camPath) {
                                try { java.io.File(camPath).delete() } catch (_: Exception) {}
                                java.io.File(videoForMux).renameTo(java.io.File(camPath))
                            }
                            notifyFileSync(camPath, "video")
                            btClient.sendCommandResult(requestId, JSONObject().apply {
                                put("success", true)
                                put("file_path", camPath)
                            }.toString())
                        }
                    }

                    // Both portrait and landscape use the fast AudioVideoMuxer
                    // tkhd patch (rot=270). The raw sensor capture is already in
                    // the correct orientation for each mode -- ArVideoRecorder
                    // picked a landscape-shaped sensor size for portrait output
                    // and a portrait-shaped sensor size for landscape output.
                    muxInto(camPath, 270)
                } ?: run {
                    stopVideoAudioCapture()?.delete()
                    btClient.sendCommandResult(requestId, JSONObject().apply {
                        put("success", false)
                        put("error", "No active video recorder")
                    }.toString())
                }
            } else {
                // Legacy: close Rokid recording scene
                btLog("STOP[3] closing $cameraType scene...")
                val closeIntent = Intent("com.rokid.os.master.assist.server.cmd").apply {
                    putExtra("cmd_type", "control_scene")
                    putExtra("scene", cameraType)
                    putExtra("open", "false")
                }
                sendBroadcast(closeIntent)
                btLog("STOP[3b] close $cameraType broadcast sent")
                // Legacy path: wait and scan for files
                Handler(Looper.getMainLooper()).postDelayed({
                    reportArRecordingResult(requestId, null, screenFile)
                }, 3000)
            }
        } catch (e: Exception) {
            btErr("STOP-ERR: ${e.javaClass.simpleName}: ${e.message}")
            btClient.sendCommandResult(requestId, JSONObject().apply {
                put("success", false)
                put("error", "Failed to stop recording: ${e.message}")
            }.toString())
        }
    }

    /**
     * FN long-press handler. Three cases:
     *
     *   1. A phone-driven recording is currently in progress (the listener
     *      owns the camera via ArVideoRecorder for record_video or
     *      record_ar_screen). The user wants to take over with the regular
     *      capture-process video, with LED. Stop the phone recording
     *      cleanly, restore the LED gate to default (so the capture HAL's
     *      auto-LED fires), then start the capture-process video once the
     *      camera is released.
     *
     *   2. The capture process is already recording (regular video started
     *      previously by FN long-press). Just stop it -- normal toggle.
     *
     *   3. Nothing recording. Start a regular capture-process video.
     */
    private fun handleFnLongPressVideo() {
        val arRec = arVideoRecorder
        if (arRec != null && arRec.isRecording) {
            btLog("FN long-press: phone recording active -> interrupting + switching to capture")
            // Best-effort: tell the phone its recording got cancelled so it
            // doesn't sit waiting for a result. The phone command will see
            // success=false with reason interrupted_by_user.
            val reqId = activeRecordingRequestId
            if (reqId != null) {
                try {
                    btClient.sendCommandResult(reqId, JSONObject().apply {
                        put("success", false)
                        put("error", "interrupted_by_user_long_press")
                    }.toString())
                } catch (_: Exception) {}
            }
            activeRecordingRequestId = null
            activeRecordingCameraType = null
            try { stopVideoAudioCapture()?.delete() } catch (_: Exception) {}
            // Restore LED gating to default BEFORE the camera handoff so
            // that capture's open path triggers the LED auto-fire (and
            // capture's LedController takes it from there).
            setCameraLedEnabled(true)
            arRec.stop { camPath ->
                broadcastRecordingState(RECORDING_STATE_IDLE)
                // MediaRecorder.stop() called only a few seconds into an
                // open occasionally leaves a 0-byte (or sub-100KB header-
                // only) stub. Delete it so the phone catalogue + filesync
                // don't sync junk. Anything bigger is a usable partial
                // and we keep it.
                if (camPath != null) {
                    try {
                        val f = java.io.File(camPath)
                        if (f.exists() && f.length() < 100_000L) {
                            f.delete()
                            btLog("FN long-press: pruned partial phone recording")
                        }
                    } catch (_: Exception) {}
                }
                // Camera close inside ArVideoRecorder.stop is async; the
                // session unwinds on the camera handler thread after this
                // callback returns. Give the HAL ~500ms to fully release
                // before capture tries to open it -- otherwise the capture
                // open races and gets CAMERA_DEVICE_PERSIST_FAILURE.
                Handler(Looper.getMainLooper()).postDelayed({
                    btLog("FN long-press: phone recorder stopped, starting capture video")
                    try { captureBridge.startVideo() } catch (e: Exception) {
                        btErr("FN long-press: captureBridge.startVideo failed: ${e.message}")
                    }
                }, 500L)
            }
            return
        }
        // Capture is the source of truth otherwise. Toggle as usual.
        try {
            val capRec = captureBridge.isRecording()
            btLog("FN long-press: phone idle, capture rec=$capRec -> ${if (capRec) "stopVideo" else "startVideo"}")
            if (capRec) captureBridge.stopVideo() else captureBridge.startVideo()
        } catch (e: Exception) {
            btErr("FN long-press: capture toggle failed: ${e.message}")
        }
    }

    // ---- Camera LED gate -----------------------------------------------------
    //
    // Rokid's libcameraservice reads `vendor.rkd.camera.led.enable` on every
    // camera open. When set to "0", cameraserver skips the auto-fire of the
    // CAMERA_OPEN lights_ctrl event entirely -- not a suppression, the event
    // is never queued. When unset or "1", default behavior (LED lights for
    // the duration of the camera open).
    //
    // We toggle the property to "0" around phone-driven recordings
    // (record_video, record_ar_screen) so the wearer sees no LED, then
    // restore "1" on stop. FN-button photo / video paths leave the property
    // at default; the capture APK also pulses the white LED explicitly via
    // LedController in CaptureService so the wearer always gets feedback.
    //
    // SELinux is permissive on this rooted build -- a plain setprop shell-out
    // works without privileged-property access. If we ever lock down SELinux
    // we'd need android.os.SystemProperties via reflection (privapp).
    private fun setCameraLedEnabled(enabled: Boolean) {
        val v = if (enabled) "1" else "0"
        // Try the privileged-app reflection path first (signature-protected
        // android.os.SystemProperties.set). Falls back to a setprop shell-out
        // if reflection is denied (the listener is a priv-app since the
        // overlay deploy, so this should normally succeed).
        var ok = false
        try {
            val cls = Class.forName("android.os.SystemProperties")
            val m = cls.getMethod("set", String::class.java, String::class.java)
            m.invoke(null, "vendor.rkd.camera.led.enable", v)
            ok = true
            android.util.Log.i("CamLed", "SystemProperties.set led.enable=$v")
        } catch (t: Throwable) {
            android.util.Log.w("CamLed", "SystemProperties.set threw: ${t.message}")
        }
        if (!ok) {
            try {
                val proc = Runtime.getRuntime().exec(
                    arrayOf("/system/bin/setprop", "vendor.rkd.camera.led.enable", v))
                proc.waitFor()
                android.util.Log.i("CamLed", "/system/bin/setprop led.enable=$v exit=${proc.exitValue()}")
            } catch (e: Exception) {
                android.util.Log.e("CamLed", "setprop shell fallback failed", e)
            }
        }
        // Verify the value actually landed.
        try {
            val r = Runtime.getRuntime().exec(arrayOf("/system/bin/getprop", "vendor.rkd.camera.led.enable"))
            val read = r.inputStream.bufferedReader().readText().trim()
            r.waitFor()
            android.util.Log.i("CamLed", "verify led.enable='$read' (wanted=$v)")
        } catch (_: Exception) {}
        btLog("LED gate: vendor.rkd.camera.led.enable=$v")
    }

    /**
     * Tell the filesync APK that a new file landed in /sdcard/DCIM/Repository/.
     * Without this nudge, FileSyncService only scans on its own onCreate, so
     * recordings made between filesync restarts stay invisible to the
     * phone-side catalogue. Bind/unbind happens off-main inside
     * SyncNotifier; we invoke from a worker thread because it blocks up to
     * 5s waiting for the AIDL bind.
     */
    private fun notifyFileSync(absPath: String, kind: String) {
        if (absPath.isBlank()) return
        val f = java.io.File(absPath)
        Thread({
            try {
                com.repository.glasses.listener.sync.SyncNotifier(applicationContext).notify(f, kind)
            } catch (e: Exception) {
                btErr("notifyFileSync threw: ${e.message}")
            }
        }, "FileSyncNotify-${f.name.take(20)}").start()
    }

    private fun reportArRecordingResult(requestId: String, cameraVideoPath: String?, screenFile: java.io.File?) {
        // Wait 2s for ViewRecorder to finalize the mp4 container.
        Handler(Looper.getMainLooper()).postDelayed({
            val screenRecordPath = if (screenFile != null && screenFile.exists() && screenFile.length() > 0)
                screenFile.absolutePath else null
            btLog("STOP[4] cam=$cameraVideoPath screen=$screenRecordPath")
            activeScreenRecordFile = null
            activeArVideoFile = null

            // Both raw files present -> composite, then send success only
            // after the composite is fully on disk. Sending success earlier
            // (as we used to) raced phone-side playback against the still-
            // running composite, producing "doesn't open / corrupted" mp4
            // opens. The composite is fast enough now (~3-4s) that holding
            // the result until it lands is fine for the phone's UX.
            if (cameraVideoPath != null && screenRecordPath != null) {
                val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                // Final composite goes into DCIM/Repository/ -- that's the
                // root the filesync APK indexes for WiFi-P2P sync to the
                // phone. Intermediate cam_/screen_ files stay in
                // /sdcard/ScreenRecorder/ since they're deleted on success
                // anyway. Without this, ar_*.mp4 lived in ScreenRecorder/
                // and the phone "Videos:" counter stayed at 1 (an old
                // stale 0-byte stub) forever -- the new files were never
                // visible to filesync.
                val compositeFile = java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DCIM),
                    "Repository/ar_${ts}.mp4"
                )
                compositeFile.parentFile?.mkdirs()
                val compositeOrientation = activeRecordingOrientation
                btLog("STOP[5] compositing -> ${compositeFile.absolutePath} orientation=$compositeOrientation (success deferred)")
                val compositor = com.repository.glasses.listener.capture.VideoCompositor()
                compositor.remoteLog = { btLog(it) }
                compositor.composite(cameraVideoPath, screenRecordPath, compositeFile.absolutePath, compositeOrientation) { resultPath ->
                    if (resultPath != null) {
                        try { java.io.File(cameraVideoPath).delete() } catch (_: Exception) {}
                        try { java.io.File(screenRecordPath).delete() } catch (_: Exception) {}
                        btLog("STOP[6] composite done: $resultPath (${java.io.File(resultPath).length() / 1024}KB)")
                        notifyFileSync(resultPath, "video")
                        btClient.sendCommandResult(requestId, JSONObject().apply {
                            put("success", true)
                            put("composite_path", resultPath)
                            put("format", "mp4")
                        }.toString())
                    } else {
                        // Composite failed; raw files are kept for recovery.
                        btLog("STOP[6] composite failed, returning raw files")
                        btClient.sendCommandResult(requestId, JSONObject().apply {
                            put("success", false)
                            put("error", "composite_failed")
                            put("camera_video_path", cameraVideoPath)
                            put("screen_record_path", screenRecordPath)
                            put("format", "mp4")
                        }.toString())
                    }
                    btLog("STOP[7] === AR RECORDING END ===")
                }
            } else {
                // Only one (or neither) raw file present -- nothing to composite.
                // Send what we have immediately.
                btClient.sendCommandResult(requestId, JSONObject().apply {
                    put("success", cameraVideoPath != null || screenRecordPath != null)
                    put("camera_video_path", cameraVideoPath ?: "")
                    put("screen_record_path", screenRecordPath ?: "")
                    put("format", "mp4")
                }.toString())
                btLog("STOP[5] === AR RECORDING END (no composite) ===")
            }
        }, 2000)
    }

    // Receives log lines relayed from the UI (main) process
    private val logRelayReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val line = intent?.getStringExtra(LogCollector.EXTRA_LOG_LINE) ?: return
            btLog(line)
        }
    }

    private val callControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_CALL_ACCEPT -> {
                    Log.i("App:Call", "event=action_accept_broadcast phase=${callController.phase} id=${callController.currentCallId}")
                    btLog("CallControl: accept requested")
                    val ok = callController.accept()
                    Log.i("App:Call", "event=action_accept_broadcast_result ok=$ok")
                }
                ACTION_CALL_DECLINE -> {
                    Log.i("App:Call", "event=action_decline_broadcast phase=${callController.phase} id=${callController.currentCallId}")
                    btLog("CallControl: decline requested")
                    val ok = callController.decline()
                    Log.i("App:Call", "event=action_decline_broadcast_result ok=$ok")
                }
                ACTION_CALL_TERMINATE -> {
                    Log.i("App:Call", "event=action_terminate_broadcast phase=${callController.phase} id=${callController.currentCallId}")
                    btLog("CallControl: terminate requested")
                    val ok = callController.terminateActive()
                    Log.i("App:Call", "event=action_terminate_broadcast_result ok=$ok")
                }
                ACTION_HF_MIC_MUTE_TOGGLE -> {
                    Log.i("App:Call", "event=action_mute_toggle_broadcast phase=${callController.phase} muted=${callController.micMuted}")
                    btLog("CallControl: mute toggle requested")
                    val ok = callController.toggleHfMicMute()
                    Log.i("App:Call", "event=action_mute_toggle_broadcast_result ok=$ok")
                }
            }
        }
    }

    // Debug-only receiver: lets us preview the incoming-call overlay and the
    // active-call status row without actually placing a call. Triggered by adb
    // broadcasts (see ACTION_DEBUG_CALL_* above). Does NOT drive CallController
    // state -- it bypasses the controller entirely and just renders the UI.
    private val debugCallReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_DEBUG_CALL_SHOW_INCOMING -> {
                    val name = intent.getStringExtra("name") ?: ""
                    val number = intent.getStringExtra("number") ?: ""
                    btLog("DebugCall: show incoming name='$name' number='$number'")
                    try { callOverlay.showIncoming(name, number) } catch (e: Exception) {
                        btErr("DebugCall showIncoming failed: ${e.message}")
                    }
                    sendBroadcast(Intent(ACTION_CALL_UI_STATE).apply {
                        setPackage(packageName)
                        putExtra("phase", "INCOMING")
                        putExtra("number", number)
                        putExtra("name", name)
                        putExtra("callId", "debug")
                        putExtra("startedElapsedRealtime", 0L)
                        putExtra("scoActive", false)
                    })
                }
                ACTION_DEBUG_CALL_SHOW_ACTIVE -> {
                    val elapsedSec = intent.getIntExtra("elapsedSec", 42)
                    val started = SystemClock.elapsedRealtime() - elapsedSec * 1000L
                    btLog("DebugCall: show active elapsedSec=$elapsedSec")
                    try { callOverlay.hide() } catch (_: Exception) {}
                    sendBroadcast(Intent(ACTION_CALL_UI_STATE).apply {
                        setPackage(packageName)
                        putExtra("phase", "ACTIVE")
                        putExtra("number", "+1 555 019 2217")
                        putExtra("name", "Jane Doe")
                        putExtra("callId", "debug")
                        putExtra("startedElapsedRealtime", started)
                        putExtra("scoActive", true)
                    })
                }
                ACTION_DEBUG_CALL_HIDE -> {
                    btLog("DebugCall: hide")
                    try { callOverlay.hide() } catch (_: Exception) {}
                    sendBroadcast(Intent(ACTION_CALL_UI_STATE).apply {
                        setPackage(packageName)
                        putExtra("phase", "IDLE")
                        putExtra("number", "")
                        putExtra("name", "")
                        putExtra("callId", "")
                        putExtra("startedElapsedRealtime", 0L)
                        putExtra("scoActive", false)
                    })
                }
                ACTION_DEBUG_LONE_ALERT -> {
                    btLog("DebugLoneAlert: firing lone alert SFX")
                    playLoneAlert()
                }
            }
        }
    }

    // Stage C: config plumbing. GlassesConfig.applySettings broadcasts
    // ACTION_GLASSES_CONFIG_CHANGED after parsing the phone's CH_SETTINGS JSON.
    // We react by: (1) applying brightness (Settings.System, su fallback),
    // (2) writing the power-daemon conf.
    private val configChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val brightness0to15 = intent.getIntExtra(GlassesConfig.EXTRA_BRIGHTNESS, GlassesConfig.brightness)
            val screenTimeoutS = intent.getIntExtra(GlassesConfig.EXTRA_SCREEN_TIMEOUT_S, GlassesConfig.screenTimeoutSec)
            val powerTimeoutMin = intent.getIntExtra(GlassesConfig.EXTRA_POWER_TIMEOUT_MIN, GlassesConfig.powerTimeoutMin)
            // Settings.System.SCREEN_BRIGHTNESS is the 0..255 scale; the
            // display HAL maps it to the panel's 0..max_brightness (511 on
            // this panel) for us. This matches what Rokid Sprite does.
            val brightness0to255 = (brightness0to15 / 15f * 255f).toInt().coerceIn(0, 255)
            btLog("ConfigChanged: brightness=$brightness0to15 (0..15) -> $brightness0to255 (0..255), screenTimeoutS=$screenTimeoutS, powerTimeoutMin=$powerTimeoutMin")

            // Ensure manual brightness mode so auto-brightness doesn't
            // immediately overwrite what we set.
            try {
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                )
            } catch (e: Exception) {
                btLog("ConfigChanged: SCREEN_BRIGHTNESS_MODE putInt failed: ${e.message}")
            }
            // Apply brightness. Requires WRITE_SETTINGS, granted by
            // deploy-to-glasses.sh via `appops set ... WRITE_SETTINGS allow`.
            val applied = try {
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    brightness0to255,
                )
            } catch (e: SecurityException) {
                btErr("ConfigChanged: WRITE_SETTINGS not granted (${e.message}); run deploy-to-glasses.sh to reinstall with the appops grant")
                false
            } catch (e: Exception) {
                btErr("ConfigChanged: Settings.System error: ${e.message}")
                false
            }
            btLog("ConfigChanged: Settings.System.SCREEN_BRIGHTNESS putInt($brightness0to255) -> $applied")

            // (2) Power-daemon conf.
            com.repository.glasses.listener.power.PowerDaemonControl.writeConfig(
                screenTimeoutSec = screenTimeoutS,
                powerTimeoutMin = powerTimeoutMin,
            )
        }
    }

    // Fold-state receiver: the native power daemon broadcasts
    // ACTION_FOLD_CHANGED (folded=true on fold, folded=false on unfold).
    // On fold: request screen lock + disable BT adapter (kills A2DP stream).
    // On unfold: re-enable BT adapter. Cancellation of the pending shutdown
    // timer lives entirely in the daemon.
    // Dedup: the daemon may send duplicate broadcasts (multiple instances, or
    // fold-debounce retries). Process only real transitions, and enforce a
    // 2-second rate limit so a rapid-fire fold/unfold cycle doesn't leave the
    // Bluetooth adapter in an indeterminate state mid-toggle.
    @Volatile private var lastFoldedState: Boolean? = null
    @Volatile private var lastFoldActionMs: Long = 0L

    private fun handleFoldChange(folded: Boolean, source: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        android.util.Log.i("FoldTrace", "handleFoldChange enter: source=$source folded=$folded lastState=$lastFoldedState lastActionMs=$lastFoldActionMs nowMs=$now")
        if (lastFoldedState == folded) {
            android.util.Log.i("FoldTrace", "  -> dedup: same as lastFoldedState, ignoring")
            btLog("FoldChanged($source): duplicate folded=$folded ignored")
            return
        }
        if (now - lastFoldActionMs < 2_000L) {
            android.util.Log.i("FoldTrace", "  -> rate-limited: ${now - lastFoldActionMs}ms < 2000ms, ignoring")
            btLog("FoldChanged($source): rate-limited folded=$folded (last ${now - lastFoldActionMs}ms ago)")
            return
        }
        lastFoldedState = folded
        lastFoldActionMs = now
        android.util.Log.i("FoldTrace", "  -> ACCEPTED: lastFoldedState updated to $folded, calling setFolded")
        btLog("FoldChanged($source): folded=$folded")
        // WEAR-DECOUPLED 2026-05-08: fold is now the canonical "worn" signal
        // for the capture stack. Mirror it into lastWornState so all worn-gated
        // call sites (signalAudioStart, reconcileMicStream, reconcileWakeWord,
        // reconcileLocalOpusWriter) follow fold instead of the noisy wear sensor.
        lastWornState = !folded
        val reason = if (folded) "folded" else "unfolded"
        btLog("FoldGate: $reason -- reconciling capture stack")
        if (folded && streamMode == StreamMode.LIVE_UTTERANCE) {
            exitLiveUtteranceMode(reason)
        }
        reconcileWakeWord(reason)
        postReconcileLocalOpusWriter(reason)
        reconcileMicStream(reason)
        // Fold is the sole off-head signal: folded -> A2DP off, unfolded -> on.
        audioRouting?.setFolded(folded)
        // Notify the fn-button handler so it can switch to folded-mode
        // semantics (suppress capture, enable triple-press -> pairing).
        try {
            val initialized = ::functionButtonHandler.isInitialized
            android.util.Log.i("FoldTrace", "  -> functionButtonHandler.isInitialized=$initialized")
            if (initialized) functionButtonHandler.setFolded(folded)
        } catch (t: Throwable) {
            android.util.Log.e("FoldTrace", "  -> setFolded threw", t)
            btErr("FoldChanged: setFolded threw: ${t.message}")
        }
        if (folded) {
            sendBroadcast(
                Intent(com.repository.glasses.listener.service.ScreenOffAccessibilityService.ACTION_LOCK_SCREEN)
                    .setPackage(packageName)
            )
            // BT stays on while folded (old disable-on-fold removed).
        } else {
            try {
                val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                if (adapter?.isEnabled == false) {
                    @Suppress("DEPRECATION", "MissingPermission")
                    adapter.enable()
                    btLog("FoldChanged: BT re-enabled")
                }
                // Unfold always exits pairing mode (and cancels its timeout).
                exitPairingMode("unfold")
            } catch (t: Throwable) {
                btErr("FoldChanged: BT enable failed: ${t.message}")
            }
        }
    }

    // Our daemon (glasses-power-daemon) broadcasts this. Not always running, so
    // we also listen for the native Rokid action below.
    private val foldChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            android.util.Log.i("FoldTrace", "foldChangedReceiver.onReceive intent=$intent")
            if (intent == null) return
            val folded = intent.getBooleanExtra("folded", false)
            handleFoldChange(folded, "daemon")
        }
    }

    // While in pairing mode, listen for BOND_STATE_CHANGED -> BOND_BONDED for
    // any device and end pairing mode. A pair completing is the natural exit.
    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            if (intent.action != android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val state = intent.getIntExtra(android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE, -1)
            val prev = intent.getIntExtra(android.bluetooth.BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
            val dev: android.bluetooth.BluetoothDevice? = intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
            val addr = try { dev?.address } catch (_: Throwable) { null } ?: "?"
            btLog("BondState: dev=$addr prev=$prev new=$state")
            if (state == android.bluetooth.BluetoothDevice.BOND_BONDED && pairingModeActive) {
                btLog("[Pairing] bond completed with $addr, exiting pairing mode")
                exitPairingMode("bonded:$addr")
            }
        }
    }

    // Native Rokid PsensorObserver broadcasts this on every leg fold/unfold:
    //   intent.action = "com.rokid.sprite.ACTION_LEG_STATUS_CHANGED"
    //   extra glasses_leg_state = "1" (unfolded/spread) | "0" (folded)
    // Decompiled source: DECOMPILED-APPS/system/priv-app/RokidSysConfig/.../PsensorObserver.java
    // This is the canonical source and always fires regardless of whether our
    // own daemon is alive.
    private val nativeLegReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            android.util.Log.i("FoldTrace", "nativeLegReceiver.onReceive intent=$intent")
            if (intent == null) return
            val raw = intent.getStringExtra("glasses_leg_state")
            val folded = when (raw) {
                "1" -> false   // spread = unfolded
                "0" -> true    // not spread = folded
                else -> {
                    android.util.Log.i("FoldTrace", "  -> malformed glasses_leg_state=$raw")
                    btLog("LegStatus: malformed glasses_leg_state=$raw")
                    return
                }
            }
            handleFoldChange(folded, "native")
        }
    }

    // WakeWordPipeline fires ACTION_WAKE_WORD_HIT on the local process via
    // Context.sendBroadcast (see WakeWordPipeline.fireHit). We react by:
    //   1. Opening the BT live-utterance gate for 30s so the BT live-stream path
    //      starts forwarding Opus frames to the phone (otherwise the default gate
    //      is LOCAL_ONLY and the radio stays cold).
    //   2. Relaying the wake event to the phone via CH_WAKE_EVENT so the phone-side
    //      state machine can activate the glasses session. The phone no longer
    //      runs a wake-word detector against the glasses audio stream -- detection
    //      is authoritative on the glasses side.
    @Volatile private var cachedWorn: Boolean = true
    @Volatile private var cachedWornAtMs: Long = 0L
    private val wearStateCacheMs: Long = 200L

    // Mirrors MainActivity.isGlassesWorn(): direct sysfs read of the PSoC
    // capacitive psensor with a 200 ms cache. Defaults to "worn" on read
    // failure so a missing sysfs node does not silently disable the wake word.
    private fun isGlassesWorn(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - cachedWornAtMs < wearStateCacheMs) return cachedWorn
        val raw = try {
            java.io.File("/sys/devices/platform/soc/a90000.i2c/i2c-1/1-0008/psensor")
                .readText().trim().toIntOrNull()
        } catch (_: Exception) { null }
        cachedWorn = raw == null || raw != 0
        cachedWornAtMs = now
        return cachedWorn
    }

    private val wakeWordHitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val confidence = intent.getFloatExtra(WakeWordPipeline.EXTRA_CONFIDENCE, 0f)
            val epochNanos = intent.getLongExtra(WakeWordPipeline.EXTRA_EPOCH_NANOS, 0L)
            if (!isGlassesWorn()) {
                btLog("[WakeWord] IGNORED conf=$confidence: glasses off-head")
                return
            }
            btLog("[WakeWord] HIT confidence=$confidence epochNanos=$epochNanos")
            // Atomic check-and-enter. If the gate was already open we skip the BT
            // side effect -- prevents double-fire across races between debug
            // injectPcmFile and a live hit, or two hits slipping through the
            // 1500 ms cooldown window concurrently.
            val transitioned = try {
                enterLiveUtteranceMode("wake-word", 30_000L)
            } catch (t: Throwable) {
                btErr("[WakeWord] enterLiveUtteranceMode threw: ${t.message}")
                return
            }
            if (!transitioned) {
                btLog("[WakeWord] already in LIVE_UTTERANCE, skipping BT send")
                return
            }
            // G3: poke phone over BLE wake channel so it brings up RFCOMM
            // immediately if it's torn down. This is the primary wake path now;
            // the existing CH_WAKE_EVENT below still flies on whichever socket
            // happens to be live.
            if (::btManagerBridge.isInitialized) {
                try {
                    val notified = btManagerBridge.notifyPhone(
                        com.repository.glasses.listener.bt.BleWakeEvent.WAKE_WORD,
                        System.nanoTime()
                    )
                    btLog("[WakeWord] BLE notify WAKE_WORD ok=$notified")
                } catch (t: Throwable) {
                    btErr("[WakeWord] BLE notifyPhone threw: ${t.message}")
                }
            }
            // G4: snapshot the prebuffer immediately so subsequent live frames
            // don't overwrite the wake-window audio. If RFCOMM is already up
            // we flush right now; otherwise defer to onConnected.
            try {
                val snap = prebuffer.snapshotForFlush()
                pendingPrebufferSnapshot.set(snap)
                btLog("[Prebuffer] snapshot=${snap.sampleCount} samples (${snap.sampleCount / 16} ms)") // 16 samples = 1 ms at 16 kHz mono
                if (phoneAudioConnected && phoneAudioSocketId != null) {
                    val s = pendingPrebufferSnapshot.getAndSet(null)
                    if (s != null) {
                        flushPrebufferAsync(s)
                    }
                }
            } catch (t: Throwable) {
                btErr("[Prebuffer] snapshot/flush schedule failed: ${t.message}")
            }
            try {
                val ok = btClient.sendWakeEvent(confidence, epochNanos)
                if (!ok) {
                    btLog("[WakeWord] CH_WAKE_EVENT send dropped (BT not connected)")
                }
            } catch (t: Throwable) {
                btErr("[WakeWord] sendWakeEvent threw: ${t.message}")
            }
        }
    }

    // FileSyncService fires ACTION_REQUEST_AUDIO_ARCHIVE_SYNC every 60 min
    // when the audio-archive dir has at least one completed rotated file. We nudge
    // the phone to start a pull over the existing CH_SYNC channel so GlassesSyncClient
    // on the phone can drive the WiFi-P2P handshake. Re-uses HELLO semantics: the
    // phone-side SyncChannelHandler treats any inbound HELLO as "begin sync now".
    private val audioArchiveSyncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            // Extras mirror FileSyncService constants (filesync module not a direct
            // dependency of :app, so we reference the action + extra keys as literals).
            val pending = intent.getIntExtra("pending_file_count", 0)
            val dir = intent.getStringExtra("archive_dir") ?: ""
            // Defense-in-depth: even though the action is gated by a signature-level
            // permission (so only same-signature apps can deliver it), validate that
            // the archive_dir extra points inside our own app's private data tree.
            // Nothing outside that tree should ever be forwarded to the phone.
            // canonicalPath resolves symlinks and normalizes ".." segments so a
            // crafted absolutePath like "/data/data/us/../other-app/x" cannot slip
            // through a prefix check.
            val sent = try { File(dir).canonicalPath } catch (e: IOException) {
                btErr("[AudioArchiveSync] reject: canonicalize failed: ${e.message}")
                return
            }
            val expectedRoot = try {
                File(
                    File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DCIM
                        ),
                        "Repository"
                    ),
                    "audio-archive"
                ).canonicalPath
            } catch (e: IOException) { null }
            if (expectedRoot == null || !sent.startsWith(expectedRoot)) {
                btErr("[AudioArchiveSync] reject: archive_dir='$dir' not under shared archive root ($expectedRoot)")
                return
            }
            btLog("[AudioArchiveSync] request received pending=$pending dir=$dir")
            // ui_round_trip session: this is a request-response style sync RPC.
            // sendSync is itself blocking on the response, so by the time it returns
            // the round-trip is already complete -- clear in finally so the session
            // is released on every exit path (success, failure, throw). Otherwise the
            // bt-manager safety timeout (30s) is the only thing that releases it,
            // pinning RFCOMM open for 30s per successful RPC and defeating deep-sleep.
            setBtSession("ui_round_trip")
            try {
                val ok = btClient.sendSync("REQUEST_SYNC_NOW", "0", pending.toString(), dir)
                if (!ok) {
                    btLog("[AudioArchiveSync] CH_SYNC send dropped (BT not connected)")
                }
            } catch (t: Throwable) {
                btErr("[AudioArchiveSync] sendSync threw: ${t.message}")
            } finally {
                clearBtSession("ui_round_trip")
            }
        }
    }

    // Debug-only: adb-driven PCM injection into the wake-word pipeline. Gated by the
    // application's FLAG_DEBUGGABLE so release builds never expose this surface.
    private val wakeWordTestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val path = intent.getStringExtra("pcm_file_path") ?: return
            btLog("[WakeWord] TEST inject pcm=$path")
            try {
                if (::wakeWordPipeline.isInitialized) {
                    wakeWordPipeline.injectPcmFile(path)
                }
            } catch (t: Throwable) {
                btErr("[WakeWord] injectPcmFile threw: ${t.message}")
            }
        }
    }

    // Broadcast receiver to monitor SCREENRECORD_START/STOP events from RokidScreenRecord app
    private val screenRecordEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            btLog("SCREEN_REC_EVENT: action=$action")
            intent.extras?.let { extras ->
                for (key in extras.keySet()) {
                    btLog("SCREEN_REC_EVENT extra: $key=${extras.get(key)}")
                }
            }
            // On SCREENRECORD_STOP: backup the screen recording file before MixRecordManager deletes it
            if (action == "com.rokid.yodaos.action.SCREENRECORD_STOP") {
                val filePath = intent.getStringExtra("ScreenRecordFilePath")
                btLog("SCREEN_REC_STOP: filePath=$filePath")
                if (!filePath.isNullOrEmpty()) {
                    try {
                        val srcFile = File(filePath)
                        if (srcFile.exists() && srcFile.length() > 0) {
                            val backupDir = File(filesDir, "screen_recordings")
                            backupDir.mkdirs()
                            val backupFile = File(backupDir, srcFile.name)
                            srcFile.copyTo(backupFile, overwrite = true)
                            lastScreenRecordBackupPath = backupFile.absolutePath
                            btLog("SCREEN_REC_STOP: backed up ${srcFile.length()}B to ${backupFile.absolutePath}")
                        } else {
                            btErr("SCREEN_REC_STOP: source file missing or empty: $filePath exists=${srcFile.exists()} size=${if (srcFile.exists()) srcFile.length() else -1}")
                        }
                    } catch (e: Exception) {
                        btErr("SCREEN_REC_STOP: backup failed: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }
        }
    }

    private fun logScreenRecordSystemState() {
        try {
            val inScreenRecord = Settings.Global.getInt(contentResolver, "rokid_os_in_screen_record", -1)
            btLog("SCREEN_REC_STATE: Settings.Global[rokid_os_in_screen_record]=$inScreenRecord")
        } catch (e: Exception) {
            btErr("SCREEN_REC_STATE: Failed to read Settings.Global: ${e.message}")
        }
        try {
            val ext = Environment.getExternalStorageDirectory()
            val stat = StatFs(ext.absolutePath)
            val freeBytes = stat.availableBytes
            val freeMb = freeBytes / 1024 / 1024
            btLog("SCREEN_REC_STATE: free space=${freeMb}MB (need >400MB)")
        } catch (e: Exception) {
            btErr("SCREEN_REC_STATE: Failed to check free space: ${e.message}")
        }
    }

    private fun registerScreenRecordReceiver() {
        val filter = IntentFilter().apply {
            addAction("com.rokid.yodaos.action.SCREENRECORD_START")
            addAction("com.rokid.yodaos.action.SCREENRECORD_STOP")
            addAction("com.rokid.yodaos.action.SCREENRECORD_ON")
            addAction("com.rokid.yodaos.action.SCREENRECORD_OFF")
        }
        registerReceiver(screenRecordEventReceiver, filter, Context.RECEIVER_EXPORTED)
        btLog("ScreenRecord event receiver registered (RECEIVER_EXPORTED)")
    }

    private fun listStorageDir(label: String, path: String): List<String> {
        val dir = File(path)
        val files = mutableListOf<String>()
        if (!dir.exists()) {
            btLog("STORAGE[$label]: dir does not exist: $path")
            return files
        }
        val entries = dir.listFiles()
        if (entries == null || entries.isEmpty()) {
            btLog("STORAGE[$label]: empty dir: $path")
            return files
        }
        btLog("STORAGE[$label]: ${entries.size} files in $path")
        // Sort by last modified desc, show newest first
        entries.sortByDescending { it.lastModified() }
        for (f in entries.take(20)) {
            val sizeMb = "%.2f".format(f.length() / 1024.0 / 1024.0)
            val mod = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(f.lastModified()))
            val entry = "  ${f.name} (${sizeMb}MB, $mod)"
            btLog("STORAGE[$label]: $entry")
            files.add(f.name)
        }
        if (entries.size > 20) btLog("STORAGE[$label]: ... and ${entries.size - 20} more")
        return files
    }

    private fun listAllMediaStorage(): JSONObject {
        val result = JSONObject()
        val ext = Environment.getExternalStorageDirectory().absolutePath

        val dirs = mapOf(
            "Movies/Camera" to "$ext/Movies/Camera",
            "DCIM/Camera" to "$ext/DCIM/Camera",
            "ScreenRecorder" to "$ext/ScreenRecorder",
            "Recordings" to "$ext/Recordings",
            "Movies" to "$ext/Movies"
        )

        for ((label, path) in dirs) {
            val dir = File(path)
            val count = dir.listFiles()?.size ?: 0
            result.put(label, count)
            listStorageDir(label, path)
        }
        return result
    }

    private fun broadcastToolThumbnail(requestId: String, thumbBase64: String, source: String) {
        btLog("broadcastToolThumbnail: requestId=$requestId source=$source thumb=${thumbBase64.length} chars")
        sendBroadcast(Intent(ACTION_TOOL_THUMBNAIL).apply {
            setPackage(packageName)
            putExtra("requestId", requestId)
            putExtra("thumbBase64", thumbBase64)
            putExtra("source", source)
        })
    }

    private fun generateThumbnailBase64(bitmap: Bitmap): String {
        val scale = 160f / bitmap.height
        val thumbW = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val thumb = Bitmap.createScaledBitmap(bitmap, thumbW, 160, true)
        val stream = ByteArrayOutputStream()
        thumb.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        thumb.recycle()
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun resizeImageForBt(bitmap: Bitmap, maxDim: Int, quality: Int): String {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDim) {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        }
        val scale = maxDim.toFloat() / longest
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        scaled.recycle()
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun extractFaceFromImage(
        imageBase64: String,
        callback: (faceCropBase64: String?, thumbBase64: String?) -> Unit
    ) {
        val bytes = Base64.decode(imageBase64, Base64.DEFAULT)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bitmap == null) {
            btLog("extractFace: failed to decode bitmap")
            callback(null, null)
            return
        }

        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setMinFaceSize(0.1f)
            .build()
        val detector = FaceDetection.getClient(options)
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isEmpty()) {
                    btLog("extractFace: no faces detected, sending resized full image")
                    val resized = resizeImageForBt(bitmap, maxDim = 640, quality = 80)
                    bitmap.recycle()
                    detector.close()
                    callback(resized, null)
                    return@addOnSuccessListener
                }
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!
                val box = face.boundingBox

                // Crop with 100% padding (matches ReidFrameConsumer.cropAndCompressJpeg)
                val padX = (box.width() * 1.0f).toInt()
                val padY = (box.height() * 1.0f).toInt()
                val cropRect = Rect(
                    (box.left - padX).coerceAtLeast(0),
                    (box.top - padY).coerceAtLeast(0),
                    (box.right + padX).coerceAtMost(bitmap.width),
                    (box.bottom + padY).coerceAtMost(bitmap.height)
                )
                if (cropRect.width() <= 0 || cropRect.height() <= 0) {
                    btLog("extractFace: invalid crop rect")
                    bitmap.recycle()
                    detector.close()
                    callback(null, null)
                    return@addOnSuccessListener
                }

                val crop = Bitmap.createBitmap(bitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
                val cropStream = ByteArrayOutputStream()
                crop.compress(Bitmap.CompressFormat.JPEG, 85, cropStream)
                crop.recycle()
                val faceCropB64 = Base64.encodeToString(cropStream.toByteArray(), Base64.NO_WRAP)

                // Thumbnail (15% padding, 100px height)
                val tPadX = (box.width() * 0.15f).toInt()
                val tPadY = (box.height() * 0.15f).toInt()
                val thumbRect = Rect(
                    (box.left - tPadX).coerceAtLeast(0),
                    (box.top - tPadY).coerceAtLeast(0),
                    (box.right + tPadX).coerceAtMost(bitmap.width),
                    (box.bottom + tPadY).coerceAtMost(bitmap.height)
                )
                val thumbCrop = Bitmap.createBitmap(bitmap, thumbRect.left, thumbRect.top, thumbRect.width(), thumbRect.height())
                val tScale = 100f / thumbCrop.height
                val thumbW = (thumbCrop.width * tScale).toInt().coerceAtLeast(1)
                val thumb = Bitmap.createScaledBitmap(thumbCrop, thumbW, 100, true)
                thumbCrop.recycle()
                val thumbStream = ByteArrayOutputStream()
                thumb.compress(Bitmap.CompressFormat.JPEG, 90, thumbStream)
                thumb.recycle()
                val thumbB64 = Base64.encodeToString(thumbStream.toByteArray(), Base64.NO_WRAP)

                btLog("extractFace: crop=${faceCropB64.length} chars, thumb=${thumbB64.length} chars")
                bitmap.recycle()
                detector.close()
                callback(faceCropB64, thumbB64)
            }
            .addOnFailureListener { e ->
                btLog("extractFace: ML Kit failed: ${e.message}, sending resized")
                val resized = resizeImageForBt(bitmap, maxDim = 640, quality = 80)
                bitmap.recycle()
                detector.close()
                callback(resized, null)
            }
    }

    private fun getRecentDcimPhoto(maxAgeMs: Long): String? {
        try {
            // Two photo sources on this device:
            //   - DCIM/Repository/  -- our own capture APK (FileNamer.kt writes
            //     IMG_yyyyMMdd_HHmmss.jpg here). This is the path the voice-photo
            //     auto-attach should prefer.
            //   - DCIM/Camera/      -- Rokid stock camera + manual button. Kept
            //     as a fallback so the existing fn-button workflow still works.
            val dirs = listOf(
                File(Environment.getExternalStorageDirectory(), "DCIM/Repository"),
                File(Environment.getExternalStorageDirectory(), "DCIM/Camera"),
            ).filter { it.exists() && it.isDirectory }
            if (dirs.isEmpty()) return null

            // Rokid saves as: img-20260319-232114-ee-P2-9.jpg (lowercase, dashes, random suffix)
            // Standard Android: IMG_20260319_143000.jpg or IMG_20260319_143000_1.jpg
            val rokidPattern = Regex("^img-(\\d{8})-(\\d{6})", RegexOption.IGNORE_CASE)
            val standardSdf = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US)
            val now = System.currentTimeMillis()

            val allFiles = dirs.flatMap { d ->
                d.listFiles()?.toList() ?: emptyList()
            }.filter { it.isFile && it.name.endsWith(".jpg", ignoreCase = true) }

            btLog("getRecentDcimPhoto: found ${allFiles.size} jpg files")

            val latest = allFiles
                .mapNotNull { file ->
                    val name = file.nameWithoutExtension
                    // Try Rokid format first: img-YYYYMMDD-HHMMSS-...
                    val rokidMatch = rokidPattern.find(name)
                    if (rokidMatch != null) {
                        val dateStr = rokidMatch.groupValues[1] + rokidMatch.groupValues[2]
                        try {
                            val ts = standardSdf.parse(dateStr)?.time ?: return@mapNotNull null
                            return@mapNotNull Pair(file, ts)
                        } catch (_: Exception) {}
                    }
                    // Try standard Android: IMG_YYYYMMDD_HHMMSS[_counter]
                    val stdMatch = Regex("^IMG_(\\d{8})_(\\d{6})", RegexOption.IGNORE_CASE).find(name)
                    if (stdMatch != null) {
                        val dateStr = stdMatch.groupValues[1] + stdMatch.groupValues[2]
                        try {
                            val ts = standardSdf.parse(dateStr)?.time ?: return@mapNotNull null
                            return@mapNotNull Pair(file, ts)
                        } catch (_: Exception) {}
                    }
                    // Fallback: use file lastModified
                    Pair(file, file.lastModified())
                }
                .maxByOrNull { it.second }

            if (latest == null) {
                btLog("getRecentDcimPhoto: no parseable files found")
                return null
            }
            val (file, ts) = latest
            if (now - ts > maxAgeMs) {
                btLog("getRecentDcimPhoto: latest is ${file.name} but too old (${(now - ts) / 1000}s > ${maxAgeMs / 1000}s)")
                return null
            }

            btLog("getRecentDcimPhoto: using ${file.name} (${(now - ts) / 1000}s ago)")
            val bytes = file.readBytes()
            return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            btLog("getRecentDcimPhoto error: ${e.message}")
            return null
        }
    }

    private fun startDcimObserver() {
        try {
            // Watch BOTH the capture-APK output (DCIM/Repository) and the
            // stock camera dir (DCIM/Camera) so any new photo from either
            // source pre-warms the cachedDcimPhotoBase64 / lastDcimPhotoName
            // cache used by voice-photo auto-attach. Without this, the
            // capture APK's photos -- which land in DCIM/Repository -- were
            // invisible to the listener and every voice request triggered
            // a fresh capture even when a fresh frame was already on disk.
            val candidates = listOf("DCIM/Repository", "DCIM/Camera")
                .map { File(Environment.getExternalStorageDirectory(), it) }
                .filter { it.exists() && it.isDirectory }
            if (candidates.isEmpty()) {
                btLog("No DCIM dirs found, observer not started")
                return
            }
            for (dcimDir in candidates) {
                val obs = object : android.os.FileObserver(dcimDir.absolutePath, CLOSE_WRITE) {
                    override fun onEvent(event: Int, path: String?) {
                        if (path == null || !path.endsWith(".jpg", ignoreCase = true)) return
                        val file = File(dcimDir, path)
                        Thread {
                            try {
                                // Brief delay for file to be fully written
                                Thread.sleep(500)
                                if (!file.exists()) return@Thread
                                handleNewDcimPhoto(file)
                            } catch (e: Exception) {
                                btLog("DCIM observer error: ${e.message}")
                            }
                        }.start()
                    }
                }
                obs.startWatching()
                dcimObservers.add(obs)
                btLog("DCIM observer started on ${dcimDir.absolutePath}")
            }
        } catch (e: Exception) {
            btLog("DCIM observer init failed: ${e.message}")
        }
    }

    @Volatile private var lastDcimPhotoName: String? = null
    @Volatile private var cachedDcimPhotoBase64: String? = null
    private val recentPhotoSendLock = java.util.concurrent.locks.ReentrantLock()

    private fun handleNewDcimPhoto(file: File) {
        try {
            // Deduplicate: FileObserver can fire multiple times for the same file
            if (file.name == lastDcimPhotoName) return
            lastDcimPhotoName = file.name

            val bytes = file.readBytes()
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap == null) {
                btLog("DCIM photo decode failed: ${file.name}")
                return
            }

            // Generate and cache thumbnail for UI
            val thumbB64 = generateThumbnailBase64(bitmap)
            lastPhotoThumbBase64 = thumbB64
            lastPhotoThumbTimestamp = SystemClock.elapsedRealtime()

            // Pre-cache resized base64 so fetch_dcim_photo can send instantly
            // 1280px q40 = ~60-80KB base64, transfers in ~4s over BT
            cachedDcimPhotoBase64 = resizeImageForBt(bitmap, maxDim = 1280, quality = 40)
            bitmap.recycle()

            btLog("DCIM photo cached: ${file.name} (${cachedDcimPhotoBase64?.length} chars ready)")

            // Proactively push to phone so it's already cached for voice auto-attach.
            // Lock prevents chunk interleaving with a concurrent fetch_dcim_photo.
            val photoToSend = cachedDcimPhotoBase64
            if (photoToSend != null && ::btClient.isInitialized) {
                recentPhotoSendLock.lock()
                try {
                    btClient.sendChunked("recent_photo", photoToSend)
                    btLog("Proactively pushed photo to phone (${photoToSend.length} chars)")
                } finally {
                    recentPhotoSendLock.unlock()
                }
            }
        } catch (e: Exception) {
            btLog("DCIM photo handling error: ${e.message}")
        }
    }

    private fun memLog(label: String) {
        val rt = Runtime.getRuntime()
        val javaUsed = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
        val javaMax = rt.maxMemory() / 1024 / 1024
        val nativeHeap = Debug.getNativeHeapAllocatedSize() / 1024 / 1024
        btLog("MEM[$label] java=${javaUsed}/${javaMax}MB native=${nativeHeap}MB")
    }

    private fun transitionState(newState: State, reason: String) {
        val old = state
        state = newState
        stateEnteredTime = SystemClock.elapsedRealtime()
        btLog("STATE: $old -> $newState ($reason)")
        updateDuckState()
    }

    @Volatile private var lastDuckSent = false
    /** Media playing state snapshot taken before SFX/TTS plays. */
    @Volatile private var mediaPlayingSnapshot = false

    @Volatile private var btsinkDucked = false

    /**
     * Re-evaluate ducking from current feature states. Ducks ONLY the incoming
     * A2DP music via the vendor HAL parameter "btsink_volume" (0-15), which is
     * independent of STREAM_MUSIC, STREAM_ASSISTANT (where TTS renders), and the
     * AVRCP absolute-volume sync. TTS therefore stays at full volume while music
     * drops. The A2DP sink renders through an offloaded DSP path, so app-layer
     * stream/track volume cannot touch it -- btsink_volume is the only knob that
     * reaches it. User volume control is unaffected (btsink_volume tracks the
     * STREAM_MUSIC index, which we read back as the restore baseline).
     */
    private fun updateDuckState() {
        val wearState = audioRouting?.state
        val wornOk = wearState != WearState.OFF_HEAD && wearState != WearState.TRANSITIONING_OFF
        val shouldDuck = wornOk && (
               state == State.LISTENING
            || ttsIsPlaying
            || notifTtsPlaying
            || telegramVoiceActive
            || loneAlertPlaying
        )
        if (shouldDuck == btsinkDucked) return
        btsinkDucked = shouldDuck
        try {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            if (shouldDuck) {
                val musicIdx = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                val ducked = (musicIdx * BTSINK_DUCK_FRACTION).toInt().coerceAtLeast(1)
                am.setParameters("btsink_volume=$ducked")
                btLog("A2DP duck ON: btsink_volume=$ducked (music idx=$musicIdx)")
            } else {
                val musicIdx = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                am.setParameters("btsink_volume=$musicIdx")
                btLog("A2DP duck OFF: btsink_volume=$musicIdx")
            }
        } catch (e: Exception) {
            btErr("btsink_volume duck failed: ${e.message}")
        }
    }

    private var launchChannelCreated = false

    private fun ensureLaunchChannel() {
        if (launchChannelCreated) return
        val channel = NotificationChannel(
            LAUNCH_CHANNEL_ID, "Activity Launch",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Brings activity to foreground"
            setSound(null, null)
            enableVibration(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        launchChannelCreated = true
    }

    private fun ensureActivityRunning(extras: android.os.Bundle? = null) {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastActivityLaunchAttempt
        if (elapsed < 2000) {
            btLog("ensureActivity: throttled (${elapsed}ms ago)")
            return
        }
        lastActivityLaunchAttempt = now

        // Acquire a temporary screen-on wake lock
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val screenLock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "GlassesListener::ScreenOn"
            )
            screenLock.acquire(3000)
            Log.d(TAG_WAKE, "event=wakelock_notify type=FULL_WAKE timeout_ms=3000")
            btLog("ensureActivity: screen wake acquired")
        } catch (e: Exception) {
            btLog("ensureActivity: screen wake failed: ${e.message}")
        }

        // Try moveTaskToFront if our task already exists (works for backgrounded activity)
        // For moveTaskToFront, activity is already running so broadcasts work -- send extras via startActivity with SINGLE_TOP
        var movedToFront = false
        try {
            @Suppress("DEPRECATION")
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val tasks = am.getRunningTasks(10)
            for (task in tasks) {
                @Suppress("DEPRECATION")
                if (task.baseActivity?.packageName == packageName) {
                    @Suppress("DEPRECATION")
                    am.moveTaskToFront(task.id, ActivityManager.MOVE_TASK_WITH_HOME)
                    @Suppress("DEPRECATION")
                    btLog("ensureActivity: moveTaskToFront taskId=${task.id}")
                    movedToFront = true
                    break
                }
            }
        } catch (e: Exception) {
            btLog("ensureActivity: moveTaskToFront failed: ${e.message}")
        }
        if (movedToFront) {
            // Activity already running; deliver extras via onNewIntent
            if (extras != null) {
                try {
                    val intent = Intent(this, Class.forName("com.repository.glasses.listener.MainActivity")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtras(extras)
                    }
                    startActivity(intent)
                } catch (_: Exception) {}
            }
            return
        }

        // Full-screen notification intent -- bypasses Android 10+ background activity restrictions
        try {
            ensureLaunchChannel()
            val intent = Intent(this, Class.forName("com.repository.glasses.listener.MainActivity")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (extras != null) putExtras(extras)
            }
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(this, LAUNCH_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Glasses")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .build()
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(LAUNCH_NOTIF_ID, notif)
            btLog("ensureActivity: full-screen notification posted")

            // Cancel notification after 1s -- just needed to trigger the activity launch
            Handler(Looper.getMainLooper()).postDelayed({
                nm.cancel(LAUNCH_NOTIF_ID)
            }, 1000)
        } catch (e: Exception) {
            btErr("ensureActivity: notification failed: ${e.message}")
            // Last resort fallback: plain startActivity
            try {
                val intent = Intent(this, Class.forName("com.repository.glasses.listener.MainActivity")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    if (extras != null) putExtras(extras)
                }
                startActivity(intent)
                btLog("ensureActivity: fallback startActivity called")
            } catch (e2: Exception) {
                btErr("ensureActivity: all methods failed: ${e2.message}")
            }
        }
    }

    private val binder = Binder()

    override fun onBind(intent: Intent): IBinder = GT.section("svc.onBind") {
        super.onBind(intent)
        binder
    }

    override fun onCreate() = GT.section("svc.onCreate") {
        Log.d(TAG_LIFE, "event=onCreate")
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val msg = "UNCAUGHT on ${thread.name}: ${throwable.message}\n${throwable.stackTraceToString()}"
            try {
                LogCollector.writeExternal("${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())} FATAL/$TAG: $msg")
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // Foreground service required -- Android kills background services without startForeground().
        // Use IMPORTANCE_MIN channel so no icon/popup appears on the waveguide display.
        promoteToForeground()
        // Service wakelock is now CONDITIONAL. Create the lock instance now but
        // don't acquire -- reconcileServiceWakeLock("boot") below decides.
        ensureServiceWakeLock()

        // Init BtManagerBridge FIRST (MessageRelay depends on it)
        try {
            btManagerBridge = com.repository.glasses.listener.bt.BtManagerBridge(this).apply {
                remoteLog = { btLog(it) }
            }
            // Register RFCOMM listener for audio sockets (message relay uses its own listener)
            btManagerBridge.addRfcommListener(object : com.repository.glasses.listener.bt.BtManagerBridge.RfcommListener {
                override fun onConnected(socketId: String, address: String, name: String) {
                    when (socketId) {
                        phoneAudioSocketId -> {
                            phoneAudioConnected = true
                            phoneAudioConnecting.set(false)
                            cancelAudioReconnect()
                            btLog("AudioSocket: connected to $name ($address)")
                            reconcileWakeWord("phone-connect")
                            reconcileMicStream("phone-audio-connect")
                            // MINOR 4 ordering: dispatch the prebuffer snapshot FIRST so
                            // it claims prebufferFlushInProgress (which holds back live
                            // frames) before anything else writes. The buffered-mic flush
                            // and live frames then land after it. Cross-executor exact
                            // interleave of buffered-mic vs prebuffer is not enforced (both
                            // still precede live, so utterance order holds); tightening it
                            // further would mean funneling buffered-mic frames through the
                            // prebuffer executor, which risks the happy path, so skipped.
                            //
                            // G4: drain any pending wake-word prebuffer queued while RFCOMM
                            // was coming up. For a live notif-reply the reply-start snapshot
                            // is one-shot: notifReplyPrebufferFlushed gates it so a mid-reply
                            // reconnect can't re-flush seconds-old stale audio into the
                            // middle of the utterance. The wake-word path (no active reply)
                            // keeps its original drain-on-every-connect semantics.
                            val replyLive = notifReplyId != null
                            if (replyLive && notifReplyPrebufferFlushed) {
                                // Already flushed once this reply -- drop the stale snapshot
                                // outright so flushPrebufferAsync can't re-stash it.
                                pendingPrebufferSnapshot.set(null)
                            } else {
                                val snap = pendingPrebufferSnapshot.getAndSet(null)
                                if (snap != null) {
                                    if (replyLive) notifReplyPrebufferFlushed = true
                                    flushPrebufferAsync(snap)
                                }
                            }
                            // Flush audio frames buffered while socket was connecting
                            // (current live audio, NOT stale -- flushes on every reconnect).
                            var flushed = 0
                            while (true) {
                                val frame = pendingAudioFrames.poll() ?: break
                                try {
                                    synchronized(phoneAudioWriteLock) {
                                        btManagerBridge.writeRfcomm(socketId, frame)
                                    }
                                    flushed++
                                }
                                catch (_: Exception) { break }
                            }
                            if (flushed > 0) btLog("AudioSocket: flushed $flushed buffered frames")
                        }
                        pcAudioSocketId -> {
                            pcAudioConnected = true
                            btLog("PcAudio: connected from $name ($address)")
                            // Track C: demand reconcile starts the mic if needed.
                            reconcileMicStream("pc-audio-connect")
                            enterLiveUtteranceMode("pc-demand")
                        }
                    }
                }
                override fun onDisconnected(socketId: String) {
                    when (socketId) {
                        phoneAudioSocketId -> {
                            btLog("AudioSocket: disconnected")
                            phoneAudioConnected = false
                            phoneAudioSocketId = null
                            phoneAudioConnecting.set(false)
                            reconcileWakeWord("phone-disconnect")
                            // A live notif voice-reply (or any active stream demand)
                            // still wants audio. Mirror the onError keepalive: arm an
                            // event-driven reconnect and keep the mic running (the
                            // reconcile below now keeps it alive via the wantedAudio
                            // term; frames buffer into pendingAudioFrames and flush on
                            // reconnect). Without this the mid-reply drop would stop
                            // the mic and the reply would hang forever.
                            if (notifReplyId != null || wantAudioStream) {
                                btLog("[NREPLY] phone-audio disconnected mid-reply, keeping mic + reconnecting")
                                scheduleAudioReconnect("disconnect")
                            } else if (micStreaming) {
                                scheduleAudioReconnect("disconnect")
                            }
                            reconcileMicStream("phone-audio-disconnect")
                            GlassesConfig.onDemandRecordingActive = false
                            postReconcileLocalOpusWriter("phone-disconnect")
                        }
                        pcAudioSocketId -> {
                            btLog("PcAudio: disconnected")
                            pcAudioConnected = false
                            // Mic pump stays on; if no phone audio consumer either,
                            // drop the BT live gate so nothing writes.
                            if (!wantAudioStream) {
                                exitLiveUtteranceMode("pc-disconnect")
                            }
                            reconcileMicStream("pc-audio-disconnect")
                        }
                    }
                }
                override fun onError(socketId: String, error: String) {
                    Log.w(TAG_RFCOMM, "event=rfcomm_error sid=$socketId err=$error")
                    btLog("RFCOMM error on $socketId: $error")
                    if (socketId == phoneAudioSocketId) {
                        phoneAudioConnected = false
                        phoneAudioSocketId = null
                        phoneAudioConnecting.set(false)
                        reconcileWakeWord("phone-error")
                        // A live notif voice-reply (or any active stream demand) still
                        // wants audio. Stopping the mic here would freeze the local
                        // spectrogram. Keep the mic running, drop the stale socket,
                        // and arm an event-driven reconnect (coalesced + settle-
                        // delayed) so the link silently recovers; the mic-pump
                        // buffers frames meanwhile (Layer A) and flushes on
                        // reconnect. When no reply/stream is wanted, fall through to
                        // the normal reconcile so idle teardown still stops the mic.
                        if (notifReplyId != null || wantAudioStream) {
                            btLog("[NREPLY] phone-audio dropped mid-reply, keeping mic + reconnecting")
                            scheduleAudioReconnect("error")
                        } else {
                            reconcileMicStream("phone-audio-error")
                        }
                    }
                }
            })
        } catch (e: Exception) {
            btErr("BtManagerBridge init failed: ${e.message}")
        }

        // Init BT client on top of MessageRelay (direct RFCOMM, no CXR-S)
        try {
            val messageRelay = com.repository.glasses.listener.bt.MessageRelay(btManagerBridge, applicationContext)
            btClient = GlassesBtClient(messageRelay)
            btClient.listener = this
            btClient.remoteLog = { btLog(it) }
        } catch (e: Exception) {
            broadcastDebugStatus("BT:init_exception:${e.message}")
        }

        // Dedicated map relay on its own RFCOMM socket (MAP_UUID). The map base frame
        // arrives as a single binary arg (raw WEBP) on CH_MAP_BITMAP_BIN; everything else
        // stays on the main messaging relay. Kept separate so heavy 10-15 FPS map traffic
        // cannot starve latency-sensitive messaging frames.
        try {
            val relay = com.repository.glasses.listener.bt.MessageRelay(
                btManagerBridge,
                applicationContext,
                com.repository.glasses.listener.bt.MessageRelay.MAP_UUID,
                com.repository.glasses.listener.bt.MessageRelay.MAP_SERVICE_NAME,
            )
            relay.remoteLog = { btLog(it) }
            relay.listener = object : com.repository.glasses.listener.bt.MessageRelay.Listener {
                override fun onConnected() { btLog("Map relay connected") }
                override fun onDisconnected() { btLog("Map relay disconnected") }
                override fun onMessage(channel: String, args: List<String>) {
                    btLog("Map relay unexpected string channel: $channel")
                }
                override fun onBinaryMessage(channel: String, payload: ByteArray) {
                    if (channel == com.repository.glasses.listener.bt.BtProtocol.CH_MAP_BITMAP_BIN) {
                        onMapBitmapBytes(payload)
                    }
                }
            }
            mapRelay = relay
        } catch (e: Exception) {
            btErr("Map relay init failed: ${e.message}")
        }

        // Init CaptureBridge + FunctionButtonHandler. ScreenOffAccessibilityService is the
        // source of KEYCODE_CAMERA events (system-wide, foreground-independent) and posts
        // them here via ACTION_FN_KEY broadcasts, which drive the long-press state machine
        // and ultimately CaptureBridge AIDL calls into the capture APK.
        try {
            photoPreviewOverlay = com.repository.glasses.listener.ui.PhotoPreviewOverlay(this).apply {
                remoteLog = { btLog(it) }
            }
            captureBridge = com.repository.glasses.listener.capture.CaptureBridge(this).apply {
                remoteLog = { btLog(it) }
                addListener(captureFeedbackListener)
                beforeVideoStart = { flushMemoryForCapture() }
                bind()
            }
            functionButtonHandler = com.repository.glasses.listener.input.FunctionButtonHandler(
                capture = captureBridge,
                log = { btLog(it) },
                onPairingRequested = { enterPairingMode() },
                // Bring up the shutter overlay's empty-frame placeholder
                // immediately on FN release. The bitmap arrives later via
                // captureFeedbackListener.onPhotoTaken and swaps in.
                onPhotoTriggered = { photoPreviewOverlay?.requestPlaceholder() },
                onLongPressVideo = { handleFnLongPressVideo() },
            )
            // EXPORTED so adb / native daemons / the accessibility service's sendBroadcast path
            // (different UID paths) can all trigger photo capture uniformly. No sensitive data
            // in the intent; the broadcast just toggles a state machine that either takes a
            // photo or toggles video -- same thing a hardware button press does.
            registerReceiver(
                fnKeyReceiver,
                IntentFilter(ScreenOffAccessibilityService.ACTION_FN_KEY),
                Context.RECEIVER_EXPORTED,
            )
            btLog("CaptureBridge + FunctionButtonHandler + ACTION_FN_KEY receiver ready")
        } catch (e: Exception) {
            btErr("CaptureBridge init failed: ${e.message}")
        }

        // Init FileSyncBridge + SyncChannelHandler. Binds to filesync APK via AIDL.
        try {
            fileSyncBridge = com.repository.glasses.listener.sync.FileSyncBridge(this).apply {
                remoteLog = { btLog(it) }
            }
            syncChannelHandler = com.repository.glasses.listener.sync.SyncChannelHandler(btClient, fileSyncBridge, btManagerBridge).apply {
                remoteLog = { btLog(it) }
            }
            fileSyncBridge.bind()
            btLog("FileSyncBridge bind() called")
        } catch (e: Exception) {
            btErr("FileSyncBridge init failed: ${e.message}")
        }

        // Init RokidBridge BEFORE btClient.initialize() so it's available when
        // onConnected() fires (which calls signalAudioStart -> rokidBridge.startAudioRecord).
        try {
            rokidBridge = RokidServiceBridge(this).apply {
                remoteLog = { btLog(it) }
            }
            rokidBridge.bind()
            navigationCtrl = RokidNavigationController(this, rokidBridge).apply {
                remoteLog = { btLog(it) }
            }
            btLog("Rokid service bridge initialized")
        } catch (e: Exception) {
            btErr("Rokid bridge failed: ${e.message}")
        }

        // Bind to BtManager AFTER all callbacks and btClient wired up.
        // Defer btClient.initialize() until onBound so MessageRelay.start() has a live bridge.
        btManagerBridge.onBound = {
            btLog("BtManagerBridge bound, initializing BT client + PC audio listener")
            try {
                btClient.initialize()
                btLog("BT initialized: ${btClient.initStatus}")
                broadcastDebugStatus("BT:${btClient.initStatus}")
                try {
                    mapRelay?.start()
                    btLog("Map relay started")
                } catch (e: Exception) {
                    btErr("Map relay start failed: ${e.message}")
                }
            } catch (e: Exception) {
                broadcastDebugStatus("BT:init_exception:${e.message}")
            }
            startPcAudioListener()
        }
        try {
            btManagerBridge.bind()
            btLog("BtManagerBridge bind() called")
        } catch (e: Exception) {
            btErr("BtManagerBridge bind failed: ${e.message}")
        }

        // Battery telemetry: every distinct % step pushes a BLE notify with
        // SoC packed into byte[1]. Sticky broadcast fires once at registration
        // with the current value, then on every kernel update -- zero polling.
        batteryReporter = com.repository.glasses.listener.battery.BatteryReporter(this) { pct, epoch ->
            try {
                if (::btManagerBridge.isInitialized) {
                    val ok = btManagerBridge.notifyPhoneWithData(
                        com.repository.glasses.listener.bt.BleWakeEvent.BATTERY_LEVEL,
                        pct.toByte(),
                        epoch,
                    )
                    btLog("BATTERY_LEVEL pct=$pct notified=$ok")
                }
            } catch (t: Throwable) {
                btLog("BATTERY_LEVEL notify failed: ${t.message}")
            }
        }.also { it.start() }

        // Recording-gate battery monitor: tracks GlassesConfig.batteryPct and
        // re-evaluates the LocalOpusWriter when it crosses the 5% floor.
        try {
            glassesBatteryMonitor = com.repository.glasses.listener.capture.GlassesBatteryMonitor { pct ->
                btLog("[OpusGate] battery cross pct=$pct")
                postReconcileLocalOpusWriter("battery_cross")
                pushRecordingStatusToPhone()
            }.also { it.start(this) }
        } catch (t: Throwable) {
            btErr("GlassesBatteryMonitor init failed: ${t.message}")
        }

        // Send any previous crash log via BT
        try {
            val crashFile = File(filesDir, "crash.log")
            if (crashFile.exists()) {
                val content = crashFile.readText().takeLast(500)
                btLog("PREVIOUS CRASH: $content")
                crashFile.delete()
            }
        } catch (e: Exception) {
            btErr("Failed to read crash log: ${e.message}")
        }

        try {
            rawFrameCapturer = com.repository.glasses.listener.nightvision.RawFrameCapturer(this).apply {
                remoteLog = { btLog(it) }
            }
            glassesAudioRecorder = AudioRecorder(this).apply {
                remoteLog = { btLog(it) }
            }
            btLog("Capture components created")
        } catch (e: Exception) {
            btErr("Capture init failed: ${e.message}")
        }

        startDcimObserver()

        reidController = ReidController().apply {
            remoteLog = { btLog(it) }
            captureBridge = if (this@ListenerService::captureBridge.isInitialized) this@ListenerService.captureBridge else null
            onActiveSessionEnter = { setBtSession("reid_streaming") }
            onActiveSessionExit = { clearBtSession("reid_streaming") }
            btSender = object : ReidController.BtSender {
                override fun sendFace(trackingId: Int, webpBase64: String) {
                    btClient.sendReidFace(trackingId, webpBase64)
                }
            }
            uiCallback = object : ReidController.UiCallback {
                override fun onFacesUpdated(verified: List<ReidController.VerifiedFace>, pendingCount: Int) {
                    val json = org.json.JSONArray()
                    for (face in verified) {
                        json.put(org.json.JSONObject().apply {
                            put("uid", face.personUid)
                            put("name", face.displayName)
                            put("data", face.thumbnailBase64)
                            put("width", face.thumbnailWidth)
                            put("score", face.score.toDouble())
                        })
                    }
                    sendBroadcast(Intent(ACTION_REID_FACES).apply {
                        putExtra(EXTRA_REID_FACES, json.toString())
                        setPackage(packageName)
                    })
                }
                override fun onStatsUpdated(frames: Int, faces: Int, fps: Double, pending: Int, verified: Int) {
                    val stats = "#$frames | $faces faces | ${verified}v/${pending}p | ${"%.1f".format(fps)} fps"
                    sendBroadcast(Intent(ACTION_REID_STATS).apply {
                        putExtra(EXTRA_REID_STATS, stats)
                        setPackage(packageName)
                    })
                }
                override fun onStatusChanged(status: String) {
                    sendBroadcast(Intent(ACTION_REID_STATUS).apply {
                        putExtra(EXTRA_REID_STATUS, status)
                        setPackage(packageName)
                    })
                }
            }
        }
        btLog("ReidController created")

        // Suppress Rokid built-in assistant
        try {
            assistantSuppressor = AssistantSuppressor(this) { btLog(it) }
            assistantSuppressor.onSensorLongPress = { handleSensorLongPress() }
            assistantSuppressor.suppress()
            registerReceiver(sensorLongPressReceiver, IntentFilter(ACTION_SENSOR_LONG_PRESS))
            btLog("AssistantSuppressor activated")
        } catch (e: Exception) {
            btErr("AssistantSuppressor failed: ${e.message}")
        }


        try {
            ttsPlayer = TtsPlayer()
            ttsPlayer.setCacheDir(cacheDir)
            ttsPlayer.setRemoteLog { btLog("TtsPlayer: $it") }
            ttsPlayer.setListener(object : TtsPlayer.TtsListener {
                override fun onPlaybackStarted() {
                    mediaPlayingSnapshot = mediaSessionMonitor.isPlaying
                    ttsIsPlaying = true
                    btLog("TTS: playback STARTED (ttsIsPlaying=true)")
                    updateDuckState()
                    reconcileServiceWakeLock("tts-start")
                }
                override fun onPlaybackFinished() {
                    ttsIsPlaying = false
                    btLog("TTS: playback FINISHED (ttsIsPlaying=false)")
                    updateDuckState()
                    if (state == State.RESPONDING) {
                        btLog("TTS: transitioning RESPONDING -> IDLE")
                        transitionToIdle()
                    }
                    reconcileServiceWakeLock("tts-finish")
                }
                override fun onInterrupted(requestId: String) {
                    ttsIsPlaying = false
                    btLog("TTS: playback INTERRUPTED ($requestId) (ttsIsPlaying=false)")
                    updateDuckState()
                    reconcileServiceWakeLock("tts-interrupt")
                }
                override fun onTtsAmplitude(level: Float) {
                    // Amplitude updates - no logging to avoid spam
                }
                override fun shouldApplyGain(): Boolean {
                    val active = lastEchoRms > ECHO_THRESHOLD
                    btLog("TTS gain decision: echoRms=${"%.5f".format(lastEchoRms)} threshold=$ECHO_THRESHOLD gain=$active")
                    return active
                }
                override fun onActiveSessionEnter() { setBtSession("tts_playback") }
                override fun onActiveSessionExit() { clearBtSession("tts_playback") }
            })
            btLog("TtsPlayer initialized")
        } catch (e: Exception) {
            btErr("TtsPlayer failed: ${e.message}")
        }

        try {
            notificationTtsPlayer = TtsPlayer()
            notificationTtsPlayer.setCacheDir(cacheDir)
            notificationTtsPlayer.setRemoteLog { btLog("NotifTTS: $it") }
            notificationTtsPlayer.setListener(object : TtsPlayer.TtsListener {
                override fun onPlaybackStarted() {
                    btLog("[Notif] TTS player started: $activeNotifId")
                    mediaPlayingSnapshot = mediaSessionMonitor.isPlaying
                    notifTtsPlaying = true
                    updateDuckState()
                    // Hold the bt-manager active session for the whole notification
                    // lifecycle (TTS + overlay). Cleared in onAllDismissed below.
                    setBtSession("notification_tts")
                    reconcileServiceWakeLock("notif-tts-start")
                }
                override fun onPlaybackFinished() {
                    val nid = notifTtsPlayingId
                    btLog("[Notif] TTS player finished: $nid")
                    notifTtsPlaying = false
                    updateDuckState()
                    if (nid != null) {
                        synchronized(notifLatchLock) { notifTtsDoneIds.add(nid) }
                        checkNotifComplete(nid)
                    }
                    reconcileServiceWakeLock("notif-tts-finish")
                }
                override fun onInterrupted(requestId: String) {
                    val nid = notifTtsPlayingId
                    btLog("[Notif] TTS player interrupted: $nid (req=$requestId)")
                    notifTtsPlaying = false
                    updateDuckState()
                    if (nid != null) {
                        synchronized(notifLatchLock) { notifTtsDoneIds.add(nid) }
                        checkNotifComplete(nid)
                    }
                    reconcileServiceWakeLock("notif-tts-interrupt")
                }
                override fun onTtsAmplitude(level: Float) {}
                // tts_playback label is also held for the playback-thread duration via
                // TtsPlayer's built-in onActiveSessionEnter/Exit hooks.
                override fun onActiveSessionEnter() { setBtSession("tts_playback") }
                override fun onActiveSessionExit() { clearBtSession("tts_playback") }
            })
            btLog("NotificationTtsPlayer initialized")

            notificationOverlay = NotificationOverlay(this)
            notificationOverlay.remoteLog = { btLog("NotifOverlay: $it") }
            notificationOverlay.onItemShown = { id, repliable ->
                btLog("[Notif] Overlay item shown: $id repliable=$repliable")
                if (repliable) {
                    sendBroadcast(Intent(ACTION_NOTIFICATION_SHOWN).apply {
                        setPackage(packageName)
                        putExtra(EXTRA_NOTIF_ID, id)
                        putExtra(EXTRA_NOTIF_REPLIABLE, true)
                    })
                }
            }
            notificationOverlay.onItemDismissed = { id ->
                btLog("[Notif] Overlay item dismissed: $id")
                synchronized(notifLatchLock) { notifOverlayDoneIds.add(id) }
                sendBroadcast(Intent(ACTION_NOTIFICATION_HIDDEN).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_NOTIF_ID, id)
                })
                checkNotifComplete(id)
            }
            notificationOverlay.onAllDismissed = {
                btLog("[Notif] Overlay queue empty")
                if (notifSoloSessionActive) {
                    notifSoloSessionActive = false
                    btLog("[NSOLO] solo session ended -- broadcasting SOLO_END")
                    sendBroadcast(Intent(ACTION_NOTIFICATION_SOLO_END).apply {
                        setPackage(packageName)
                    })
                }
                clearBtSession("notification_tts")
                if (notifWokeScreen) {
                    notifWokeScreen = false
                    btLog("Notification dismissed, releasing screen wake lock")
                    notifScreenLock?.let { if (it.isHeld) it.release() }
                    notifScreenLock = null
                    notifReplyHoldingScreen = false
                    // Turn screen back off since we woke it for this notification
                    notifHandler.removeCallbacks(notifLockScreenRunnable)
                    notifHandler.postDelayed(notifLockScreenRunnable, 500)
                }
            }
            btLog("NotificationOverlay initialized")
        } catch (e: Exception) {
            btErr("NotificationTtsPlayer/Overlay failed: ${e.message}")
        }

        try {
            callOverlay = CallOverlay(this)
            callOverlay.remoteLog = { btLog(it) }
            callController.start(btManagerBridge, callOverlay, ttsPlayer, this) { btLog(it) }
            btManagerBridge.addOnBoundListener {
                btManagerBridge.addCallListener(callController)
                btLog("CallController registered with BtManagerBridge")
                try {
                    callController.onBtManagerBound()
                } catch (e: Exception) {
                    btErr("CallController.onBtManagerBound failed: ${e.message}")
                }
            }
            val callFilter = IntentFilter().apply {
                addAction(ACTION_CALL_ACCEPT)
                addAction(ACTION_CALL_DECLINE)
                addAction(ACTION_CALL_TERMINATE)
                addAction(ACTION_HF_MIC_MUTE_TOGGLE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(callControlReceiver, callFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(callControlReceiver, callFilter)
            }
            val debugCallFilter = IntentFilter().apply {
                addAction(ACTION_DEBUG_CALL_SHOW_INCOMING)
                addAction(ACTION_DEBUG_CALL_SHOW_ACTIVE)
                addAction(ACTION_DEBUG_CALL_HIDE)
                addAction(ACTION_DEBUG_LONE_ALERT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(debugCallReceiver, debugCallFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(debugCallReceiver, debugCallFilter)
            }
            btLog("CallController + CallOverlay initialized (debug receiver attached)")
        } catch (e: Exception) {
            btErr("CallController/CallOverlay init failed: ${e.message}")
        }

        try {
            val afd: AssetFileDescriptor = assets.openFd("activate.mp3")
            activatePlayer = MediaPlayer().apply {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                prepare()
            }
            btLog("ActivatePlayer loaded")
        } catch (e: Exception) {
            btErr("ActivatePlayer failed: ${e.message}")
        }

        // Local MediaSessionMonitor: used only for AudioRoutingController.isPlaying
        // (wear-based A2DP pause/resume). UI broadcasts come from BtMediaSource below.
        mediaSessionMonitor = MediaSessionMonitor(this) { btLog(it) }
        mediaSessionMonitor.callback = object : MediaSessionMonitor.Callback {
            override fun onMediaStateChanged(track: String, playing: Boolean) {}
            override fun onProgressUpdate(positionMs: Long, durationMs: Long) {}
        }
        mediaSessionMonitor.start()
        btLog("MediaSessionMonitor started")

        // BtMediaSource: phone audio arrives via A2DP sink; AVRCP metadata is
        // bridged into a local MediaSession owned by BluetoothMediaBrowserService.
        // We subscribe to that session to drive the music tab UI AND send
        // transport commands back to the phone over standard AVRCP.
        btMediaSource = com.repository.glasses.listener.media.BtMediaSource(this) { btLog(it) }
            .also { src ->
                src.start(object : com.repository.glasses.listener.media.BtMediaSource.Listener {
                    override fun onState(s: com.repository.glasses.listener.media.BtMediaSource.State) {
                        // AVRCP returns the literal string "not provided" when
                        // the source has no metadata for a field; treat it as
                        // empty so the UI shows the empty-state hint.
                        fun clean(v: String): String {
                            val trimmed = v.trim()
                            return if (trimmed.equals("not provided", ignoreCase = true)) "" else trimmed
                        }
                        val t = clean(s.title)
                        val a = clean(s.artist)
                        val display = when {
                            t.isEmpty() && a.isEmpty() -> ""
                            a.isEmpty() -> t
                            t.isEmpty() -> a
                            else -> "$t - $a"
                        }
                        sendBroadcast(Intent(ACTION_MEDIA_STATE).apply {
                            setPackage(packageName)
                            putExtra(EXTRA_MEDIA_TRACK, display)
                            putExtra(EXTRA_MEDIA_PLAYING, s.playing)
                            putExtra(EXTRA_MEDIA_POSITION, s.positionMs)
                            putExtra(EXTRA_MEDIA_DURATION, s.durationMs)
                            putExtra(EXTRA_MEDIA_POSITION_TS, s.positionTs)
                        })
                        sendBroadcast(Intent(ACTION_MEDIA_PROGRESS).apply {
                            setPackage(packageName)
                            putExtra(EXTRA_MEDIA_POSITION, s.positionMs)
                            putExtra(EXTRA_MEDIA_DURATION, s.durationMs)
                            putExtra(EXTRA_MEDIA_POSITION_TS, s.positionTs)
                        })
                    }
                })
                btLog("BtMediaSource started")
            }

        // Stage E: A2DP auto-routing by wear state. Wire IR proximity sensor to
        // A2DP_SINK connect/disconnect so audio follows the glasses on/off head.
        try {
            audioRouting = AudioRoutingController(this, mediaSessionMonitor) { msg ->
                android.util.Log.i("AudioRoutingController", msg)
                btLog("[AudioRouting] $msg")
            }
            audioRouting?.onWearChangedRaw = { worn ->
                btLog("Wear change hook: worn=$worn -> relay to phone (capture stack now fold-driven)")
                // WEAR-DECOUPLED 2026-05-08: lastWornState is now sourced from
                // fold state in handleFoldChange. Wear sensor only relays to
                // phone for UI; it no longer gates capture/mic.
                // lastWornState = worn

                // Latch PSoC enforce_psensor on first on-head detection so the
                // chip keeps the touchpad alive after take-off. The I2C command
                // only sticks when the chip is physically worn (psensor active).
                if (worn && !psensorEnforceLatched) {
                    psensorEnforceLatched = true
                    try {
                        val cls = Class.forName("android.os.SystemProperties")
                        val m = cls.getMethod("set", String::class.java, String::class.java)
                        m.invoke(null, "rokid.debug.enforce_psensor", "1")
                        btLog("[Psensor] enforce_psensor latched on first wear")
                    } catch (t: Throwable) {
                        btErr("[Psensor] enforce_psensor latch failed: ${t.message}")
                    }
                }
                try { btClient.sendWearState(worn) } catch (t: Throwable) {
                    btErr("sendWearState failed: ${t.message}")
                }
                // G3: also nudge phone over BLE so it can refresh glasses-status
                // UI without waiting for an RFCOMM polling cycle.
                if (::btManagerBridge.isInitialized) {
                    try {
                        val ok = btManagerBridge.notifyPhone(
                            com.repository.glasses.listener.bt.BleWakeEvent.WEAR_CHANGED,
                            System.nanoTime()
                        )
                        btLog("[Wear] BLE notify WEAR_CHANGED ok=$ok worn=$worn")
                    } catch (t: Throwable) {
                        btErr("[Wear] BLE notifyPhone threw: ${t.message}")
                    }
                }
                updateDuckState()

                // WEAR-DECOUPLED 2026-05-08: capture stack reconcile moved to
                // handleFoldChange. Wear sensor no longer starts/stops mic /
                // wake-word / opus writer; fold does.
                // // Track B: gate the always-on capture stack on the wear sensor.
                // // worn=false (off-head / on desk) stops AudioRecord + WakeWordPipeline
                // // + LocalOpusWriter so the device doesn't burn CPU encoding silence.
                // // worn=true puts everything back. start()/stop() on each component is
                // // idempotent (see WakeWordPipeline.kt:196-235 / 273-325 and
                // // LocalOpusWriter.kt:81-151).
                // if (worn) {
                //     btLog("WearGate: glasses put on -- restarting capture stack")
                //     // Track C: start wake-word FIRST so it counts as a consumer
                //     // when reconcileMicStream evaluates demand. WW only starts if
                //     // an RFCOMM phone peer is also connected (reconcileWakeWord).
                //     reconcileWakeWord("wear")
                //     postReconcileLocalOpusWriter("wear")
                //     reconcileMicStream("wear-on")
                // } else {
                //     btLog("WearGate: glasses taken off -- stopping capture stack")
                //     // If a live BT utterance is in flight, close the gate cleanly so
                //     // the phone-side session terminates rather than seeing a dead
                //     // socket mid-stream. exitLiveUtteranceMode is the canonical cancel
                //     // hook (see signalAudioStart / stopGlassesAudioStream).
                //     if (streamMode == StreamMode.LIVE_UTTERANCE) {
                //         exitLiveUtteranceMode("wear-off")
                //     }
                //     postReconcileLocalOpusWriter("wear")
                //     reconcileWakeWord("wear")
                //     // Track C: reconcile after stopping wake-word so demand recomputes
                //     // to false and the mic pump stops cleanly. With worn=false the
                //     // demand condition is unconditionally false regardless of peers.
                //     reconcileMicStream("wear-off")
                    // WEAR-DECOUPLED 2026-05-08: wear sensor is too noisy to drive
                    // screen-off (false-zero blips would blank the display mid-use).
                    // Fold + idle paths in glasses-power-daemon still blank the screen.
                    // // Blank the display when the user takes the glasses off. ScreenOffAccessibilityService
                    // // performs GLOBAL_ACTION_LOCK_SCREEN -- this only locks/blanks the display, it
                    // // does NOT power the device off (the fold-timeout path in glasses-power-daemon
                    // // is the only thing that calls `svc power shutdown`). Without this, the display
                    // // stays on off-head, wasting battery and emitting light from the waveguide.
                    // sendBroadcast(Intent(ScreenOffAccessibilityService.ACTION_LOCK_SCREEN).apply {
                    //     setPackage(packageName)
                    // })
                // }
            }
            audioRouting?.start()
            btLog("AudioRoutingController started")
        } catch (e: Exception) {
            audioRouting = null
            btErr("AudioRoutingController init failed: ${e.message}")
        }

        // Battery LED arming: light the battery-color LED only when charging AND
        // physically still for >= 60s. Worn glasses micro-move, so the LED never
        // shows while worn. The native daemon owns the LED; this only sets the arm flag.
        try {
            batteryLedArmer = com.repository.glasses.listener.power.BatteryLedArmer(this) { btLog(it) }
            batteryLedArmer?.start()
            // Sticky ACTION_BATTERY_CHANGED: registerReceiver returns the current
            // battery Intent, so the initial charging state is seeded immediately.
            val initialBattery = registerReceiver(
                batteryLedReceiver,
                IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            )
            batteryLedArmer?.setCharging(isCablePlugged(initialBattery))
            btLog("BatteryLedArmer wired: battery receiver registered, charging seeded")
        } catch (e: Exception) {
            batteryLedArmer = null
            btErr("BatteryLedArmer init failed: ${e.message}")
        }

        // Register teleprompter state receiver (relays state from MainActivity to phone via BT)
        registerReceiver(
            teleprompterStateReceiver,
            IntentFilter(ACTION_TELEPROMPTER_STATE),
            Context.RECEIVER_NOT_EXPORTED
        )
        btLog("TeleprompterStateReceiver registered")

        registerReceiver(
            cancelSessionReceiver,
            IntentFilter(ACTION_CANCEL_SESSION),
            Context.RECEIVER_NOT_EXPORTED
        )

        registerReceiver(
            configChangedReceiver,
            IntentFilter(GlassesConfig.ACTION_GLASSES_CONFIG_CHANGED),
            Context.RECEIVER_NOT_EXPORTED
        )
        btLog("ConfigChangedReceiver registered")

        // Fold receiver: sender is the native power daemon via `am broadcast`
        // (shell UID), so must be EXPORTED to accept it.
        registerReceiver(
            foldChangedReceiver,
            IntentFilter("com.repository.glasses.listener.ACTION_FOLD_CHANGED"),
            Context.RECEIVER_EXPORTED
        )
        btLog("FoldChangedReceiver registered")

        // Native Rokid leg/fold event (com.rokid.sprite.ACTION_LEG_STATUS_CHANGED
        // from PsensorObserver in RokidSysConfig). More reliable than our own
        // daemon since it's always running inside the system BT stack. Listens
        // alongside the daemon broadcast; `handleFoldChange` dedups.
        registerReceiver(
            nativeLegReceiver,
            IntentFilter("com.rokid.sprite.ACTION_LEG_STATUS_CHANGED"),
            Context.RECEIVER_EXPORTED
        )
        btLog("NativeLegReceiver registered (com.rokid.sprite.ACTION_LEG_STATUS_CHANGED)")

        // Bond state: used to auto-exit pairing mode after successful pair.
        registerReceiver(
            bondStateReceiver,
            IntentFilter(android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            Context.RECEIVER_EXPORTED,
        )
        btLog("BondStateReceiver registered")

        // Allow an adb broadcast to enter pairing mode programmatically via our
        // tracked path (so it arms the 3-min timer and the bond-completion auto-exit).
        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    btLog("[Pairing] DEBUG_DISCOVERABLE broadcast -> enterPairingMode")
                    enterPairingMode()
                }
            },
            IntentFilter("com.repository.glasses.listener.ACTION_ENTER_PAIRING"),
            Context.RECEIVER_EXPORTED,
        )
        btLog("EnterPairingReceiver registered")

        // Debug entry: initiate pairing TO a MAC address (glasses act as
        // initiator). Useful when the remote adapter can't scan but will
        // accept incoming pair requests via a silent agent. Extras:
        //   --es mac AA:BB:CC:DD:EE:FF
        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val mac = intent?.getStringExtra("mac") ?: return
                    try {
                        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                        val dev = adapter?.getRemoteDevice(mac) ?: run {
                            btErr("[InitiatePair] no adapter / bad mac=$mac"); return
                        }
                        val prev = dev.bondState
                        btLog("[InitiatePair] dev=$mac prevBondState=$prev -> createBond()")
                        val ok = dev.createBond()
                        btLog("[InitiatePair] createBond() returned $ok")
                    } catch (t: Throwable) {
                        btErr("[InitiatePair] threw: ${t.message}")
                    }
                }
            },
            IntentFilter("com.repository.glasses.listener.ACTION_INITIATE_PAIR"),
            Context.RECEIVER_EXPORTED,
        )
        btLog("InitiatePairReceiver registered")

        // Auto-confirm incoming pair requests while we're in pairing mode.
        // Handles the "Confirm passkey" / "Consent" flavour so a headless
        // source (the desktop bluez Agent NoInputNoOutput) can complete pair
        // without a user tap on the glasses.
        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != android.bluetooth.BluetoothDevice.ACTION_PAIRING_REQUEST) return
                    val dev: android.bluetooth.BluetoothDevice? =
                        intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                    val variant = intent.getIntExtra(android.bluetooth.BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                    val passkey = intent.getIntExtra(android.bluetooth.BluetoothDevice.EXTRA_PAIRING_KEY, -1)
                    val addr = try { dev?.address } catch (_: Throwable) { null } ?: "?"
                    btLog("[PairingReq] dev=$addr variant=$variant passkey=$passkey active=$pairingModeActive")
                    if (!pairingModeActive || dev == null) return
                    try {
                        // Variants that accept a boolean confirmation via setPairingConfirmation.
                        when (variant) {
                            2 /* PAIRING_VARIANT_PASSKEY_CONFIRMATION */,
                            3 /* PAIRING_VARIANT_CONSENT */ -> {
                                val ok = dev.setPairingConfirmation(true)
                                btLog("[PairingReq] setPairingConfirmation(true) ok=$ok")
                                abortBroadcast()
                            }
                            0 /* PIN */ -> {
                                val ok = dev.setPin("0000".toByteArray())
                                btLog("[PairingReq] setPin(0000) ok=$ok")
                                abortBroadcast()
                            }
                            else -> btLog("[PairingReq] variant $variant not auto-confirmed")
                        }
                    } catch (t: Throwable) {
                        btErr("[PairingReq] auto-confirm threw: ${t.message}")
                    }
                }
            },
            IntentFilter(android.bluetooth.BluetoothDevice.ACTION_PAIRING_REQUEST).apply {
                priority = 1000
            },
            Context.RECEIVER_EXPORTED,
        )
        btLog("PairingRequestReceiver registered")

        // Seed initial fold state from vendor.rkd.glasses.is_spread ("1"=spread
        // = unfolded, "0"=folded) so the fn-button handler is correct from boot
        // even before the first transition broadcast arrives.
        try {
            val spread = try {
                val c = Class.forName("android.os.SystemProperties")
                val m = c.getMethod("get", String::class.java, String::class.java)
                (m.invoke(null, "vendor.rkd.glasses.is_spread", "") as? String) ?: ""
            } catch (_: Throwable) { "" }
            val initialFolded = when (spread) {
                "1" -> false
                "0" -> true
                else -> null
            }
            if (initialFolded != null) {
                btLog("Fold initial: is_spread=$spread -> folded=$initialFolded")
                handleFoldChange(initialFolded, "initial")
            }
        } catch (t: Throwable) {
            btErr("Fold initial read threw: ${t.message}")
        }

        // Poll vendor.rkd.glasses.is_spread every 1s. The two normal sources
        // (our glasses-power-daemon ACTION_FOLD_CHANGED and Rokid's
        // ACTION_LEG_STATUS_CHANGED) can both go silent in practice (daemon
        // stderr -> /dev/null hides crashes; native PsensorObserver depends on
        // RokidSysConfig being alive). The vendor prop is set by the kernel
        // PSoC driver and is always authoritative, so polling it guarantees
        // the fn-button triple-press pairing path is reachable regardless of
        // broadcast plumbing health. handleFoldChange dedups so re-running
        // with the same state is a no-op.
        foldPollRunnable = object : Runnable {
            override fun run() {
                try {
                    val s = try {
                        val c = Class.forName("android.os.SystemProperties")
                        val m = c.getMethod("get", String::class.java, String::class.java)
                        (m.invoke(null, "vendor.rkd.glasses.is_spread", "") as? String) ?: ""
                    } catch (t: Throwable) {
                        android.util.Log.e("FoldPoll", "SystemProperties.get threw", t)
                        ""
                    }
                    val f = when (s) {
                        "1" -> false
                        "0" -> true
                        else -> null
                    }
                    android.util.Log.i("FoldPoll", "tick: is_spread='$s' -> folded=$f")
                    if (f != null) handleFoldChange(f, "poll")
                } catch (t: Throwable) {
                    android.util.Log.e("FoldPoll", "tick threw", t)
                    btErr("Fold poll threw: ${t.message}")
                }
                heartbeatHandler.postDelayed(this, 5000L)
            }
        }
        android.util.Log.i("FoldPoll", "starting 5s poller")
        heartbeatHandler.postDelayed(foldPollRunnable!!, 5000L)

        // Register new chat / chat list / switch receivers (from MainActivity)
        registerReceiver(
            newChatRequestReceiver,
            IntentFilter(ACTION_REQUEST_NEW_CHAT),
            Context.RECEIVER_NOT_EXPORTED
        )
        registerReceiver(
            chatListRequestReceiver,
            IntentFilter(ACTION_REQUEST_CHAT_LIST),
            Context.RECEIVER_NOT_EXPORTED
        )
        registerReceiver(
            switchChatReceiver,
            IntentFilter(ACTION_SWITCH_CHAT),
            Context.RECEIVER_NOT_EXPORTED
        )
        registerReceiver(
            assistantToggleReceiver,
            IntentFilter(ACTION_TOGGLE_ASSISTANT),
            Context.RECEIVER_NOT_EXPORTED
        )
        btLog("Chat list receivers registered")

        // Register todo/telegram receivers (from MainActivity)
        registerReceiver(
            todoListRequestReceiver,
            IntentFilter(ACTION_REQUEST_TODO_LIST),
            Context.RECEIVER_NOT_EXPORTED
        )
        registerReceiver(
            todoToggleReceiver,
            IntentFilter(ACTION_TODO_TOGGLE),
            Context.RECEIVER_NOT_EXPORTED
        )
        registerReceiver(
            todoAddReceiver,
            IntentFilter(ACTION_TODO_ADD),
            Context.RECEIVER_NOT_EXPORTED
        )
        registerReceiver(
            todoRemoveReceiver,
            IntentFilter(ACTION_TODO_REMOVE),
            Context.RECEIVER_NOT_EXPORTED
        )
        registerReceiver(
            alarmListRequestReceiver,
            IntentFilter(ACTION_REQUEST_ALARM_LIST),
            Context.RECEIVER_NOT_EXPORTED
        )
        registerReceiver(
            jobListRequestReceiver,
            IntentFilter(ACTION_REQUEST_JOB_LIST),
            Context.RECEIVER_NOT_EXPORTED
        )
        btLog("Todo/Telegram receivers registered")

        // Register Telegram chat receivers (from MainActivity)
        registerReceiver(tgChatListRequestReceiver, IntentFilter(ACTION_REQUEST_TG_CHAT_LIST), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(tgMessagesRequestReceiver, IntentFilter(ACTION_REQUEST_TG_MESSAGES), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(tgTopicsRequestReceiver, IntentFilter(ACTION_REQUEST_TG_TOPICS), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(tgSendMsgReceiver, IntentFilter(ACTION_TG_SEND_MSG), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(tgSubscribeReceiver, IntentFilter(ACTION_TG_SUBSCRIBE), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(tgUnsubscribeReceiver, IntentFilter(ACTION_TG_UNSUBSCRIBE), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(tgOpenChatReceiver, IntentFilter(ACTION_TG_OPEN_CHAT), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(tgCloseChatReceiver, IntentFilter(ACTION_TG_CLOSE_CHAT), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(tgVoiceStartReceiver, IntentFilter(ACTION_TG_VOICE_START), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(tgVoiceStopReceiver, IntentFilter(ACTION_TG_VOICE_STOP), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(notifHoldProgressReceiver, IntentFilter(ACTION_NOTIF_HOLD_PROGRESS), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(notifHoldFreezeReceiver, IntentFilter(ACTION_NOTIF_HOLD_FREEZE), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(notifReplyStartReceiver, IntentFilter(ACTION_NOTIF_REPLY_START), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(notifReplySendReceiver, IntentFilter(ACTION_NOTIF_REPLY_SEND), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(notifReplyCancelReceiver, IntentFilter(ACTION_NOTIF_REPLY_CANCEL), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(notifSoloRevealReceiver, IntentFilter(ACTION_NOTIFICATION_SOLO_REVEAL), Context.RECEIVER_NOT_EXPORTED)
        // Exported so the scripted phase-walk demo can be triggered from adb for recording/QA.
        registerReceiver(notifReplyDemoReceiver, IntentFilter(ACTION_NOTIF_REPLY_DEMO), Context.RECEIVER_EXPORTED)
        // NOT_EXPORTED: this is a test hook, not a shipping feature. It can only be invoked
        // in-process (sendBroadcast with setPackage from within this app, or an instrumented
        // test running in the same UID) -- never from an external `adb shell am broadcast`.
        registerReceiver(notificationTestReceiver, IntentFilter(ACTION_NOTIFICATION_TEST), Context.RECEIVER_NOT_EXPORTED)
        btLog("Telegram chat receivers registered")

        // Register map pin receiver (from MainActivity)
        registerReceiver(
            mapPinReceiver,
            IntentFilter(ACTION_MAP_PIN),
            Context.RECEIVER_NOT_EXPORTED
        )

        registerReceiver(
            mapTabVisibleReceiver,
            IntentFilter(ACTION_MAP_TAB_VISIBLE),
            Context.RECEIVER_NOT_EXPORTED
        )

        registerReceiver(
            tabChangedReceiver,
            IntentFilter(ACTION_TAB_CHANGED),
            Context.RECEIVER_NOT_EXPORTED
        )

        registerReceiver(
            translationToggleReceiver,
            IntentFilter(ACTION_REQUEST_TRANSLATION_TOGGLE),
            Context.RECEIVER_NOT_EXPORTED
        )

        // Register stop journey receiver (from MainActivity)
        registerReceiver(
            stopJourneyReceiver,
            IntentFilter(ACTION_STOP_JOURNEY),
            Context.RECEIVER_NOT_EXPORTED
        )

        registerReceiver(
            navZoomReceiver,
            IntentFilter(ACTION_NAV_ZOOM),
            Context.RECEIVER_NOT_EXPORTED
        )

        // Register media command receiver (from MainActivity)
        registerReceiver(
            mediaCommandReceiver,
            IntentFilter(ACTION_MEDIA_COMMAND),
            Context.RECEIVER_NOT_EXPORTED
        )

        // Register screen record event receiver (monitor SCREENRECORD_START/STOP)
        registerScreenRecordReceiver()

        // Register screen state receiver
        screenStateReceiver = ScreenStateReceiver()
        screenStateReceiver.setListener(this)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenStateReceiver, filter)
        btLog("ScreenStateReceiver registered")

        // Receive log lines relayed from the UI process
        registerReceiver(
            logRelayReceiver,
            IntentFilter(LogCollector.ACTION_LOG_RELAY),
            Context.RECEIVER_NOT_EXPORTED
        )
        btLog("LogRelay receiver registered")

        registerReceiver(
            reidStartReceiver,
            IntentFilter(ACTION_REID_START),
            Context.RECEIVER_NOT_EXPORTED
        )
        registerReceiver(
            reidStopReceiver,
            IntentFilter(ACTION_REID_STOP),
            Context.RECEIVER_NOT_EXPORTED
        )
        registerReceiver(
            cameraPermGrantedReceiver,
            IntentFilter(ACTION_CAMERA_PERMISSION_GRANTED),
            Context.RECEIVER_NOT_EXPORTED
        )
        registerReceiver(
            reidPersonRequestReceiver,
            IntentFilter(ACTION_REID_PERSON_REQUEST),
            Context.RECEIVER_NOT_EXPORTED
        )
        btLog("Reid receivers registered")

        registerReceiver(
            testCommandReceiver,
            IntentFilter("com.repository.glasses.listener.TEST_COMMAND"),
            Context.RECEIVER_EXPORTED
        )
        registerReceiver(
            uiRecordStoppedReceiver,
            IntentFilter(ACTION_UI_RECORD_STOPPED),
            Context.RECEIVER_NOT_EXPORTED
        )

        watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
        btLog("State machine watchdog started")

        // Periodic recording_status resync: every 10s, push the current
        // recording state to the phone so a dropped status push (BT glitch,
        // frame loss) cannot leave the phone's mirror state diverged from
        // reality. Source-of-truth is localOpusWriter.isRunning() inside
        // pushRecordingStatusToPhone().
        recordingStatusResyncHandler.postDelayed(recordingStatusResyncRunnable, RECORDING_STATUS_RESYNC_INTERVAL_MS)
        btLog("Recording status resync started (10s interval)")

        btLog("LogCollector status: ${LogCollector.getWriterStatus()}")

        // On-glasses pipelines -- MUST be constructed BEFORE the mic pump so
        // LocalOpusWriter / WakeWordPipeline can subscribe to MicBus before the
        // first emit. Both classes subscribe inside their own start(); the mic
        // pump fan-out uses MicBus so ordering is the only thing that matters
        // here.
        //
        // Track B wear gating: if the wear sensor already reported off-head
        // before we got here (the AudioRoutingController.start() above runs
        // WearSensor.start() synchronously and fires our onWearChangedRaw
        // callback for the initial state), don't start the heavy pipelines on
        // boot -- the wear handler will start them when the user puts the
        // glasses on. Default to "running" when wear is unknown, since silent
        // on first put-on is worse than burning a few seconds of CPU.
        // audioRouting?.start() above invokes WearSensor.start() synchronously,
        // which fires our onWearChangedRaw callback once with the initial wear
        // state. By the time we reach this point, lastWornState reflects the
        // current value (or stays null if the property is empty / reflection
        // failed). Treat null as "unknown -> default to running" per Track B.
        val initialWearKnownOff: Boolean = (lastWornState == false)
        btLog("WearGate: initial wear=${lastWornState} -> ${if (initialWearKnownOff) "hold capture stack" else "start capture stack"}")
        try {
            localOpusWriter = LocalOpusWriter(this, remoteLog = { btLog(it) })
            postReconcileLocalOpusWriter("boot")
        } catch (e: Exception) {
            btErr("[LocalOpusWriter] init failed: ${e.message}")
        }
        try {
            wakeWordPipeline = WakeWordPipeline(this)
            val wwEnabled = GlassesConfig.wakewordEnabled
            if (!initialWearKnownOff && wwEnabled) {
                wakeWordPipeline.start()
                btLog("[WakeWord] pipeline started (wakewordEnabled=true)")
            } else {
                btLog("[WakeWord] pipeline held (wear=${lastWornState} wakewordEnabled=$wwEnabled)")
            }
        } catch (e: Exception) {
            btErr("[WakeWord] pipeline start failed: ${e.message}")
        }

        // G4: subscribe the prebuffer for the lifetime of the service. MicBus
        // is wear-gated upstream; when the mic is off the prebuffer naturally
        // idles (no onPcmFrame calls).
        try {
            MicBus.subscribe(prebuffer)
            btLog("[Prebuffer] subscribed to MicBus (capacity=${com.repository.glasses.listener.capture.PrebufferingAudioSubscriber.CAPACITY_SAMPLES} samples)")
        } catch (e: Exception) {
            btErr("[Prebuffer] subscribe failed: ${e.message}")
        }

        // WakeWordPipeline broadcasts ACTION_WAKE_WORD_HIT via context.sendBroadcast
        // (no LocalBroadcastManager in this codebase). RECEIVER_NOT_EXPORTED keeps
        // the action in-process on Android 13+ -- matches the ACTION_REID_* pattern.
        try {
            registerReceiver(
                wakeWordHitReceiver,
                IntentFilter(WakeWordPipeline.ACTION_WAKE_WORD_HIT),
                Context.RECEIVER_NOT_EXPORTED
            )
            btLog("[WakeWord] hit receiver registered")
        } catch (e: Exception) {
            btErr("[WakeWord] hit receiver register failed: ${e.message}")
        }

        // FileSyncService broadcasts ACTION_REQUEST_AUDIO_ARCHIVE_SYNC from its
        // process every 60 min when there are rotated .opus files to pull. Filesync
        // runs in a separate process, so the receiver must be exported to cross the
        // process boundary. The action is protected by a signature-level permission
        // declared in the app manifest, so only same-signature apps can deliver it;
        // third-party or adb-shell broadcasts are rejected by the OS at enforcement
        // time. The 5-arg registerReceiver overload passes the permission string.
        try {
            registerReceiver(
                audioArchiveSyncReceiver,
                IntentFilter("com.repository.glasses.listener.ACTION_REQUEST_AUDIO_ARCHIVE_SYNC"),
                "com.repository.glasses.listener.permission.AUDIO_ARCHIVE_SYNC",
                /* scheduler = */ null,
                Context.RECEIVER_EXPORTED
            )
            btLog("[AudioArchiveSync] receiver registered (signature-protected)")
        } catch (e: Exception) {
            btErr("[AudioArchiveSync] receiver register failed: ${e.message}")
        }

        // Debug-only: adb-driven wake-word PCM injection. Register only for
        // debuggable builds. Must be RECEIVER_EXPORTED because `adb shell am
        // broadcast` comes from the shell UID.
        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebuggable) {
            try {
                registerReceiver(
                    wakeWordTestReceiver,
                    IntentFilter(WakeWordPipeline.ACTION_WAKE_WORD_TEST),
                    Context.RECEIVER_EXPORTED
                )
                btLog("[WakeWord] TEST receiver registered (debuggable build)")
            } catch (e: Exception) {
                btErr("[WakeWord] TEST receiver register failed: ${e.message}")
            }
        }

        btLog("Service created - all init done")
        memLog("initComplete")
        broadcastState("IDLE")
        broadcastBtState(btClient.isConnected)

        // Start audio streaming if screen is already on (handles app restart while screen active)
        val pm = getSystemService(android.os.PowerManager::class.java)
        // Mic is always-on for the lifetime of the service. Start the pump on
        // service boot, regardless of screen/BT state. Under LOCAL_ONLY (the
        // default) the radio stays cold; under LIVE_UTTERANCE the BT sockets and
        // RFCOMM writes turn on.
        if (!initialWearKnownOff) {
            // Track C: demand-driven boot. wakeWordPipeline.start() ran above for
            // !initialWearKnownOff, so wake-word counts as a consumer and the
            // mic will start. If a future toggle disables wake-word at boot and
            // no peer has connected yet, the mic stays off until demand arrives.
            reconcileMicStream("boot")
            if (pm?.isInteractive == true) {
                signalAudioStart("init")
            } else {
                btLog("[StreamMode] service boot with screen off -- mic pump up, BT gate default ($streamMode)")
            }
        } else {
            btLog("[StreamMode] service boot with wear=off -- mic pump held until put-on")
        }
        // Final reconcile after all boot state is populated. Without this, the
        // service wakelock would stay released until the first state change --
        // but if we're already worn+connected at boot we want it acquired now.
        reconcileServiceWakeLock("boot")

        // Launch Activity immediately so chat UI is always visible
        ensureActivityRunning()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = GT.section("svc.onStartCommand") {
        super.onStartCommand(intent, flags, startId)
        START_STICKY
    }

    // ADB test hook: adb shell am broadcast -a com.repository.glasses.listener.TEST_COMMAND --es command record_ar_screen --es params '{"duration_seconds":10}'
    private val testCommandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val command = intent.getStringExtra("command") ?: return
            val paramsJson = intent.getStringExtra("params") ?: "{}"
            btLog("TEST_COMMAND: command=$command params=$paramsJson")
            if (command == "play_tone") {
                val durationSec = try {
                    org.json.JSONObject(paramsJson).optInt("duration_seconds", 5)
                } catch (_: Exception) { 5 }
                playTestTone(durationSec)
                return
            }
            if (command == "set_beamform") {
                val scene = try {
                    org.json.JSONObject(paramsJson).optInt("scene", 0)
                } catch (_: Exception) { 0 }
                Thread {
                    BeamformController.remoteLog = { btLog(it) }
                    BeamformController.init(this@ListenerService)
                    val ok = BeamformController.setScene(scene)
                    btLog("TEST_COMMAND: set_beamform scene=$scene result=$ok")
                }.start()
                return
            }
            if (command == "record_mic_array") {
                val params = try { org.json.JSONObject(paramsJson) } catch (_: Exception) { org.json.JSONObject() }
                val durationSec = params.optInt("duration_seconds", 10)
                val scene = params.optInt("scene", -1)  // -1 = don't change
                val audioSource = params.optInt("audio_source", 1)  // 1=MIC, 6=VOICE_RECOGNITION, 7=VOICE_COMMUNICATION
                if (scene >= 0) {
                    // Set beamform scene before recording
                    BeamformController.remoteLog = { btLog(it) }
                    BeamformController.init(this@ListenerService)
                    BeamformController.setScene(scene)
                    Thread.sleep(500)  // let DSP settle
                }
                recordMicArray(durationSec, audioSource)
                return
            }
            onCommand(command, "test_${System.currentTimeMillis()}", paramsJson)
        }
    }

    /**
     * Diagnostic helper: synthesize a 440 Hz sine on the speaker via AudioTrack,
     * with USAGE_MEDIA. Used to verify that AR audio captures device playback
     * through the rear-firing echo channels (mics 6-8). Plays asynchronously.
     */
    private fun playTestTone(durationSec: Int) {
        Thread {
            try {
                val sr = 44100
                val n = sr * durationSec
                val pcm = ShortArray(n)
                for (i in 0 until n) {
                    pcm[i] = (kotlin.math.sin(2 * Math.PI * 440 * i / sr) * 16000).toInt().toShort()
                }
                val track = android.media.AudioTrack.Builder()
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        android.media.AudioFormat.Builder()
                            .setSampleRate(sr)
                            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(n * 2)
                    .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                    .build()
                track.write(pcm, 0, n)
                track.play()
                btLog("playTestTone: started ${durationSec}s 440Hz")
                Thread.sleep(durationSec * 1000L + 500)
                track.stop()
                track.release()
                btLog("playTestTone: done")
            } catch (e: Exception) {
                btErr("playTestTone failed: ${e.message}")
            }
        }.start()
    }

    /**
     * Record all 8 channels of the mic array to /data/local/tmp/mic_8ch.pcm.
     * Runs on a background thread. The main mic stream must be stopped first
     * because AudioRecord is exclusive on this device.
     */
    private fun recordMicArray(durationSec: Int, audioSource: Int = 1) {
        if (translationFrontMicRecorder != null) {
            btErr("MicArrayTest: REFUSED -- translation front mic is active, would cause HAL contention")
            return
        }
        btLog("MicArrayTest: stopping main mic stream for exclusive access (audioSource=$audioSource)")
        stopMicStream("mic-array-test")
        Thread {
            try {
                val recorder = MicArrayTestRecorder().apply {
                    remoteLog = { btLog(it) }
                }
                val path = recorder.record(durationSec, audioSource)
                if (path != null) {
                    btLog("MicArrayTest: DONE -- pull with: adb pull $path")
                } else {
                    btErr("MicArrayTest: recording failed")
                }
            } catch (e: Exception) {
                btErr("MicArrayTest: exception: ${e.message}")
            } finally {
                btLog("MicArrayTest: restarting main mic stream")
                Handler(Looper.getMainLooper()).post { reconcileMicStream("mic-array-test-done") }
            }
        }.start()
    }

    // Receives UI recording status from MainActivity's ViewRecorder
    private val uiRecordStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val path = intent.getStringExtra("path") ?: ""
            btLog("UI_REC_STOPPED: path=$path")
        }
    }

    // --- Audio streaming ---
    // Glasses capture mic locally with AudioRecord (single instance, 1 s chunks) and
    // fan out to subscribers via MicBus. The mic runs always-on while the service is
    // alive -- it no longer stops on screen-off or BT disconnect. Those events only
    // flip the StreamMode gate that controls the BT live-stream branch (Opus encode
    // + RFCOMM write to phone/PC).
    @Volatile
    private var wantAudioStream = false
    @Volatile
    private var micRecord: android.media.AudioRecord? = null
    private var micEchoCanceler: android.media.audiofx.AcousticEchoCanceler? = null
    private var micNoiseSuppressor: android.media.audiofx.NoiseSuppressor? = null
    @Volatile private var micStreamThread: Thread? = null
    @Volatile private var micStreaming = false

    /**
     * Gates the BT live-stream branch inside the mic pump. Mic capture is independent
     * and always-on while the service is alive. Phase 2 default is LOCAL_ONLY: the
     * on-glasses WakeWordPipeline is live, and the BT live stream only opens on a
     * wake hit (or an explicit signalAudioStart("...") trigger from phone/PC).
     * Callers that used to force the mic + BT stream on together now only flip this
     * mode via [enterLiveUtteranceMode]; the mic itself is always running from
     * onCreate. The BT branch auto-reverts to LOCAL_ONLY after maxDurationMs.
     */
    enum class StreamMode { LOCAL_ONLY, LIVE_UTTERANCE }

    @Volatile private var streamMode: StreamMode = StreamMode.LOCAL_ONLY
    private val liveUtteranceHandler = Handler(Looper.getMainLooper())
    private val liveUtteranceRevertRunnable = Runnable {
        if (streamMode == StreamMode.LIVE_UTTERANCE) {
            btLog("[StreamMode] auto-revert -> LOCAL_ONLY (live window expired)")
            streamMode = StreamMode.LOCAL_ONLY
            clearBtSession("live_utterance")
            reconcileServiceWakeLock("live-revert")
        }
    }

    /**
     * Switch the BT live stream on for at most [maxDurationMs]. Existing triggers
     * (phone wake, PC demand, ...) all go through here; no direct mic start/stop.
     * Safe to call repeatedly; each call resets the auto-revert timer.
     *
     * Returns true if the mode actually transitioned from LOCAL_ONLY to
     * LIVE_UTTERANCE, false if the gate was already open. The caller can use the
     * return to gate one-shot side effects (e.g. sending a wake event to the
     * phone) so concurrent triggers don't double-fire. The timer is still reset
     * either way; holding the gate open longer is the intended behavior.
     */
    fun enterLiveUtteranceMode(reasonTag: String, maxDurationMs: Long = 30_000L): Boolean {
        val transitioned: Boolean
        synchronized(liveUtteranceHandler) {
            liveUtteranceHandler.removeCallbacks(liveUtteranceRevertRunnable)
            val prev = streamMode
            transitioned = prev != StreamMode.LIVE_UTTERANCE
            streamMode = StreamMode.LIVE_UTTERANCE
            btLog("[StreamMode] $prev -> LIVE_UTTERANCE ($reasonTag, timeout=${if (maxDurationMs > 0) "${maxDurationMs}ms" else "none"}, transitioned=$transitioned)")
            if (maxDurationMs > 0) {
                liveUtteranceHandler.postDelayed(liveUtteranceRevertRunnable, maxDurationMs)
            }
        }
        // Hold the bt-manager active session for the live-utterance window so the RFCOMM
        // idle watchdog can't tear down the connection mid-conversation. Ref-counted, so
        // overlapping triggers (wake + pc-demand) compose correctly. Always paired with
        // exitLiveUtteranceMode below (which is the only way the gate closes -- via the
        // revert runnable, explicit exit, or wear-off).
        if (transitioned) {
            setBtSession("live_utterance")
        }
        reconcileServiceWakeLock("live-enter:$reasonTag")
        return transitioned
    }

    fun exitLiveUtteranceMode(reasonTag: String) {
        liveUtteranceHandler.removeCallbacks(liveUtteranceRevertRunnable)
        val wasOpen = streamMode != StreamMode.LOCAL_ONLY
        if (wasOpen) {
            btLog("[StreamMode] $streamMode -> LOCAL_ONLY ($reasonTag)")
        }
        streamMode = StreamMode.LOCAL_ONLY
        if (wasOpen) {
            clearBtSession("live_utterance")
        }
        reconcileServiceWakeLock("live-exit:$reasonTag")
    }

    /**
     * Bt-manager active-session helpers. Each label held while a logical operation is
     * in flight prevents the RFCOMM idle watchdog from tearing down the connection.
     * Ref-counted in bt-manager so overlapping calls compose. Per-label safety timeouts
     * auto-clear stale labels on the bt-manager side.
     */
    private fun setBtSession(label: String) {
        if (!::btManagerBridge.isInitialized) return
        try { btManagerBridge.setActiveSession(label) } catch (t: Throwable) {
            btErr("setBtSession($label) failed: ${t.message}")
        }
    }
    private fun clearBtSession(label: String) {
        if (!::btManagerBridge.isInitialized) return
        try { btManagerBridge.clearActiveSession(label) } catch (t: Throwable) {
            btErr("clearBtSession($label) failed: ${t.message}")
        }
    }
    @Volatile private var micChunkCount = 0L
    @Volatile private var micDiagnosticDone = false
    @Volatile var ttsIsPlaying = false
    @Volatile private var notifTtsPlaying = false
    @Volatile private var lastEchoRms = 0f


    // RFCOMM audio via BtManagerBridge
    private val audioSocketUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
    // Settle delay before an event-driven audio-socket reconnect. The BT stack
    // emits NO "channel fully released" event, so we wait one short beat after a
    // teardown/failure callback before reconnecting to the same BD_ADDR -- this
    // lets the RFCOMM MCB release so the next connect isn't rejected with
    // "RFCOMM_CreateConnection already opened state:2". Triggered by a teardown
    // callback, NOT a blind poll.
    private val AUDIO_RECONNECT_SETTLE_MS = 400L
    // Dedicated main-looper handler for the coalesced audio-socket reconnect.
    private val audioReconnectHandler = Handler(Looper.getMainLooper())
    // The single pending reconnect runnable (coalesced -- only one in flight).
    private var audioReconnectRunnable: Runnable? = null
    private val pcAudioSocketUuid = "c3d4e5f6-a7b8-9012-cdef-345678901234"
    @Volatile private var phoneAudioSocketId: String? = null
    @Volatile private var phoneAudioConnected = false
    // Single-writer discipline for the phone audio RFCOMM socket. THREE threads
    // write length-prefixed Opus frames to phoneAudioSocketId concurrently: the
    // live mic publisher (MicStream thread), the prebuffer flush
    // (prebufferFlushExecutor), and the onConnected pending-frame drain (BT relay
    // thread). Without serialization, a multi-frame buffer from one writer can
    // interleave mid-frame with another, so the phone reads a bogus length prefix
    // from the middle of an opus payload and desyncs the decoder permanently.
    // Every write to phoneAudioSocketId MUST hold this lock so each frame buffer
    // lands contiguously on the wire. The lock is held only for the duration of a
    // single writeRfcomm call (short), so writers still interleave at frame
    // boundaries -- never mid-frame -- and the prebuffer's real-time Thread.sleep
    // pacing stays OUTSIDE the lock. This lock is specific to the audio socket;
    // the message (b2c3) and pc-mic (c3d4) sockets use different code paths and
    // are intentionally unaffected.
    private val phoneAudioWriteLock = Any()
    // Guards against two AudioSocketConnect threads spawning concurrently. The
    // write-failure path (disconnectPhoneAudio + connectPhoneAudio) and the
    // RFCOMM onError/onDisconnected callbacks can both call connectPhoneAudio in
    // the same window after phoneAudioSocketId is nulled, both passing the cheap
    // null fast-path and racing connectRfcommOutbound -> leaked socket. Only one
    // connect thread may be in flight at a time.
    private val phoneAudioConnecting = java.util.concurrent.atomic.AtomicBoolean(false)
    // One-time latch for the notif-reply prebuffer snapshot. Armed false at
    // reply-start; set true after the snapshot is drained once. Prevents a
    // mid-reply socket reconnect from re-flushing the seconds-old reply-start
    // snapshot into the middle of the live utterance (stale audio injection).
    @Volatile private var notifReplyPrebufferFlushed = false
    /** Opus-encoded frames buffered while LISTENING but socket not yet connected. */
    private val pendingAudioFrames = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    // Rate-limit latch for the "buffer at cap" log so it fires once per fill-up
    // episode, not per evicted frame. Reset to false whenever the buffer drains
    // back below the cap.
    @Volatile private var pendingAudioAtCapLogged = false
    @Volatile private var pcAudioSocketId: String? = null
    @Volatile private var pcAudioConnected = false
    private val pcExtractChannel = 1  // ch1 for PC
    private val pcMicGain = 48
    private lateinit var btManagerBridge: com.repository.glasses.listener.bt.BtManagerBridge
    private var batteryReporter: com.repository.glasses.listener.battery.BatteryReporter? = null
    private var glassesBatteryMonitor: com.repository.glasses.listener.capture.GlassesBatteryMonitor? = null
    private lateinit var fileSyncBridge: com.repository.glasses.listener.sync.FileSyncBridge
    private var syncChannelHandler: com.repository.glasses.listener.sync.SyncChannelHandler? = null
    private lateinit var captureBridge: com.repository.glasses.listener.capture.CaptureBridge
    private lateinit var functionButtonHandler: com.repository.glasses.listener.input.FunctionButtonHandler

    // BT pairing-mode (discoverable) state + auto-timeout.
    // - enterPairingMode(): flip scan mode to CONNECTABLE_DISCOVERABLE + arm 3min timeout.
    // - exitPairingMode(): flip back to CONNECTABLE + cancel timeout.
    // Triggered by: triple-press fn button while folded (enter); unfold or timeout (exit).
    private val pairingHandler = Handler(Looper.getMainLooper())
    private var foldPollRunnable: Runnable? = null
    @Volatile private var pairingModeActive: Boolean = false
    private val pairingTimeoutRunnable = Runnable {
        btLog("[Pairing] 3min timeout reached, exiting pairing mode")
        exitPairingMode("timeout")
    }
    private val pairingModeDurationMs: Long = 3 * 60 * 1000L
    private fun enterPairingMode() {
        android.util.Log.i("Pairing", "enterPairingMode() called")
        try {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: run {
                android.util.Log.e("Pairing", "no BluetoothAdapter")
                btErr("[Pairing] no BluetoothAdapter"); return
            }
            if (!adapter.isEnabled) {
                @Suppress("DEPRECATION", "MissingPermission")
                adapter.enable()
                btLog("[Pairing] adapter was OFF, enabling")
            }
            val ok = try {
                val m = adapter.javaClass.getMethod(
                    "setScanMode",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                android.util.Log.i("Pairing", "trying setScanMode(int,int) ...")
                val r = m.invoke(adapter, 23 /* CONNECTABLE_DISCOVERABLE */, 180 /* duration hint */) as? Boolean ?: true
                android.util.Log.i("Pairing", "setScanMode(int,int) returned $r")
                r
            } catch (t1: Throwable) {
                android.util.Log.w("Pairing", "setScanMode(int,int) threw, falling back", t1)
                try {
                    val m2 = adapter.javaClass.getMethod("setScanMode", Int::class.javaPrimitiveType)
                    val r2 = m2.invoke(adapter, 23) as? Boolean ?: true
                    android.util.Log.i("Pairing", "setScanMode(int) returned $r2")
                    r2
                } catch (t2: Throwable) {
                    android.util.Log.e("Pairing", "setScanMode(int) also threw", t2)
                    throw t2
                }
            }
            pairingModeActive = true
            pairingHandler.removeCallbacks(pairingTimeoutRunnable)
            pairingHandler.postDelayed(pairingTimeoutRunnable, pairingModeDurationMs)
            android.util.Log.i("Pairing", "setScanMode(DISCOVERABLE) ok=$ok; auto-exit in ${pairingModeDurationMs}ms")
            btLog("[Pairing] enter: setScanMode(DISCOVERABLE) ok=$ok; auto-exit in ${pairingModeDurationMs}ms")
        } catch (t: Throwable) {
            android.util.Log.e("Pairing", "enter threw", t)
            btErr("[Pairing] enter threw: ${t.message}")
        }
    }
    private fun exitPairingMode(reason: String) {
        try {
            pairingHandler.removeCallbacks(pairingTimeoutRunnable)
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                pairingModeActive = false
                btLog("[Pairing] exit($reason): no adapter, flag cleared")
                return
            }
            val sm = try { adapter.scanMode } catch (_: Throwable) { -1 }
            // Always drop to CONNECTABLE if we're currently DISCOVERABLE, no
            // matter who flipped it on (our enter path, a DEBUG broadcast, or
            // an earlier session that left state behind after a restart).
            if (sm == 23 /* CONNECTABLE_DISCOVERABLE */) {
                val ok = try {
                    val m = adapter.javaClass.getMethod("setScanMode", Int::class.javaPrimitiveType)
                    m.invoke(adapter, 21 /* CONNECTABLE */) as? Boolean ?: true
                } catch (t: Throwable) {
                    btErr("[Pairing] exit setScanMode(CONNECTABLE) threw: ${t.message}")
                    false
                }
                btLog("[Pairing] exit($reason): setScanMode(CONNECTABLE) ok=$ok")
            } else {
                btLog("[Pairing] exit($reason): not DISCOVERABLE (sm=$sm), no change")
            }
            pairingModeActive = false
        } catch (t: Throwable) {
            btErr("[Pairing] exit threw: ${t.message}")
        }
    }
    private var photoPreviewOverlay: com.repository.glasses.listener.ui.PhotoPreviewOverlay? = null

    private fun broadcastRecordingState(state: Int) {
        sendBroadcast(Intent(ACTION_RECORDING_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_RECORDING_STATE, state)
        })
    }

    /**
     * Aggressively free memory in the listener process right before capture starts a recording.
     * Goal: drop listener's RSS so capture isn't the highest-RSS user when lmkd activates --
     * lmkd targets by oom_score_adj * size, and a foreground priv-app at 100 MB beats a
     * recording priv-app at 200 MB. Requests GC only -- ReID stays running since it now shares
     * the capture-owned camera over AIDL.
     * Conservative: does NOT touch active TTS playback, BT, or chat history.
     */
    private fun flushMemoryForCapture() {
        val rt = Runtime.getRuntime()
        val before = (rt.totalMemory() - rt.freeMemory()) / 1024
        // ReID is no longer stopped here: video and ReID now share the single capture-owned
        // camera (frames arrive over AIDL), so starting a recording must not tear ReID down.
        // Coexistence is memory-safe: ReidFrameConsumer is strictly single-in-flight (the
        // processingFrame guard admits one frame at a time, recycled in its finally), so ReID's
        // live footprint is at most ONE decoded frame bitmap (~5MB at 1280x960 ARGB_8888, plus a
        // transient rotated copy) on top of the once-loaded ML Kit graph -- a <20MB delta that the
        // video encoder comfortably tolerates on this 1.7GB device. No frames are buffered.
        // Hint two passes: first runs finalizers, second collects them.
        try { System.gc(); System.runFinalization(); System.gc() } catch (_: Throwable) {}
        val after = (rt.totalMemory() - rt.freeMemory()) / 1024
        val msg = "flushMemoryForCapture: heap before=${before}kB after=${after}kB freed=${before - after}kB"
        android.util.Log.i("CaptureKillDetector", msg)
        btLog(msg)
    }

    /**
     * Print a /proc-derived snapshot for forensics after a capture-OOM kill. Logs self-process
     * RSS plus system MemAvailable / MemTotal so we can correlate the kill with overall pressure.
     */
    private fun logMemPressureSnapshot() {
        try {
            val pid = android.os.Process.myPid()
            val selfStatus = java.io.File("/proc/$pid/status").readText()
            val selfRss = Regex("VmRSS:\\s*(\\d+)").find(selfStatus)?.groupValues?.getOrNull(1)
            val meminfo = java.io.File("/proc/meminfo").readText()
            val memAvail = Regex("MemAvailable:\\s*(\\d+)").find(meminfo)?.groupValues?.getOrNull(1)
            val memTotal = Regex("MemTotal:\\s*(\\d+)").find(meminfo)?.groupValues?.getOrNull(1)
            val msg = "CAPTURE-OOM-SNAPSHOT: listener_rss_kb=$selfRss mem_available_kb=$memAvail mem_total_kb=$memTotal"
            android.util.Log.e("CaptureKillDetector", msg)
            btLog(msg)
        } catch (e: Throwable) {
            btErr("logMemPressureSnapshot threw: ${e.message}")
        }
    }

    private val captureFeedbackListener = object : com.repository.glasses.listener.capture.CaptureBridge.Listener {
        override fun onPhotoTaken(absPath: String, sizeBytes: Long) {
            photoPreviewOverlay?.show(absPath)
            // ONE-SHOT ReID on the photo frame, independent of whether ReID mode is on:
            // every func-button photo also drives a single identify attempt. The photo file
            // is stored upright (pixels rotated, EXIF NORMAL), so rotationDeg=0 -- the same
            // orientation the live ReID frames are delivered in. Runs off the binder thread
            // (decode + ML Kit) and does NOT start ReID mode, the periodic loop, or the LED.
            photoCallbackExecutor.execute {
                try {
                    val bytes = java.io.File(absPath).readBytes()
                    reidController?.detectPhotoOneShot(bytes, 0)
                } catch (t: Throwable) {
                    btErr("photo one-shot reid failed: ${t.message}")
                }
            }
            // Drain one waiter (FIFO) -- BT command flows that asked for
            // bytes get the file path here. FN button doesn't enqueue, so
            // the queue is empty for those and this is a no-op.
            // Dispatch off the binder thread: callbacks read MB-sized files,
            // decode bitmaps, base64-encode, and ship over BT.
            pendingPhotoCallbacks.poll()?.let { cb ->
                photoCallbackExecutor.execute {
                    try { cb(java.io.File(absPath)) }
                    catch (t: Throwable) { btErr("photo callback threw: ${t.message}") }
                }
            }
        }
        override fun onVideoStarted(absPath: String) {
            broadcastRecordingState(RECORDING_STATE_ACTIVE)
        }
        override fun onVideoPaused(absPath: String) {
            broadcastRecordingState(RECORDING_STATE_PAUSED)
        }
        override fun onVideoResumed(absPath: String) {
            broadcastRecordingState(RECORDING_STATE_ACTIVE)
        }
        override fun onVideoStopped(absPath: String, durationMs: Long, sizeBytes: Long) {
            broadcastRecordingState(RECORDING_STATE_IDLE)
        }
        override fun onCaptureKilledDuringRecording(activePath: String?) {
            broadcastRecordingState(RECORDING_STATE_IDLE)
            val sizeKb = try {
                if (activePath != null) java.io.File(activePath).length() / 1024 else -1L
            } catch (_: Throwable) { -1L }
            val msg = "CAPTURE-OOM-KILL: recording lost path=$activePath orphan_size_kb=$sizeKb -- system memory pressure killed capture priv-app mid-record"
            android.util.Log.e("CaptureKillDetector", msg)
            btErr(msg)
            logMemPressureSnapshot()
        }
        override fun onCaptureError(code: Int, msg: String) {
            // Dismiss any spinner that was shown eagerly on fn-button press --
            // otherwise a rejected capture (e.g. "raw still busy") leaves the
            // placeholder hanging until PLACEHOLDER_TIMEOUT_MS.
            photoPreviewOverlay?.clearPendingPlaceholder()
            // Capture errors aren't paired 1:1 with takePhoto requests
            // (could fire mid-video etc.), so drain everything pending
            // with null rather than risk a stuck queue.
            while (true) {
                val cb = pendingPhotoCallbacks.poll() ?: break
                photoCallbackExecutor.execute {
                    try { cb(null) } catch (t: Throwable) { btErr("photo error callback threw: ${t.message}") }
                }
            }
        }
    }

    /**
     * Take a photo via the capture APK and hand the resulting file (or null
     * on failure) to [cb]. Use for BT command flows that need bytes; the
     * FN-button shutter just calls captureBridge.takePhoto() directly since
     * captureFeedbackListener.onPhotoTaken handles the preview swap.
     */
    private fun requestPhotoFile(cb: (java.io.File?) -> Unit) {
        pendingPhotoCallbacks.add(cb)
        captureBridge.takePhoto()
    }
    private val fnKeyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.getStringExtra(ScreenOffAccessibilityService.EXTRA_EVENT_ACTION) ?: return
            val repeat = intent.getIntExtra(ScreenOffAccessibilityService.EXTRA_REPEAT, 0)
            android.util.Log.i("FnKeyReceiver", "ACTION_FN_KEY $action repeat=$repeat")
            if (!::functionButtonHandler.isInitialized) {
                android.util.Log.w("FnKeyReceiver", "FunctionButtonHandler not initialized yet -- dropping event")
                return
            }
            when (action) {
                "DOWN" -> functionButtonHandler.onKeyDown(repeat)
                "UP" -> functionButtonHandler.onKeyUp()
            }
        }
    }

    /**
     * Single-shot, companion-only connect of the dedicated RFCOMM AUDIO DATA
     * socket (mic PCM glasses->phone). NO loop, NO sleep-poll, NO bonded
     * fan-out: the companion phone is the ONLY RFCOMM-audio peer. The real
     * connect result arrives LATER as onConnected/onError(socketId) on the
     * RfcommListener at onCreate; those callbacks (not a blind timer) drive any
     * reconnect via scheduleAudioReconnect. This is a SEPARATE subsystem from
     * A2DP/HFP profile routing to other bonded devices -- that is untouched.
     */
    /**
     * Single shared "audio is wanted" predicate used by BOTH connectPhoneAudio
     * and scheduleAudioReconnect so the two can never disagree. Audio is wanted
     * if the mic is streaming OR a notif reply is live OR a stream demand is
     * open -- this matches the broader gate the onError callback uses, so a
     * mid-reply drop during a brief micStreaming=false window still reconnects.
     */
    private fun audioWanted(): Boolean = micStreaming || notifReplyId != null || wantAudioStream

    private fun connectPhoneAudio() {
        if (phoneAudioSocketId != null || phoneAudioConnected) return // already connecting/connected
        if (!audioWanted()) return                                    // nothing wants audio
        if (!phoneAudioConnecting.compareAndSet(false, true)) return  // single-flight
        val companion = btClient.companionAddress
        if (companion == null) {
            phoneAudioConnecting.set(false)
            // No companion yet: do NOT busy-retry. A later relay-connect / mic
            // demand calls connectPhoneAudio() again; arm ONE short re-check
            // (coalesced) so a transient cold link still recovers.
            scheduleAudioReconnect("no-companion-yet")
            return
        }
        try {
            val sid = btManagerBridge.connectRfcommOutbound(companion, audioSocketUuid)
            if (sid != null) {
                phoneAudioSocketId = sid
                btLog("[AudioSocket] connect requested -> $companion (socketId=$sid)")
                // Do NOT poll. onConnected/onError(sid) resolves it and clears
                // phoneAudioConnecting.
            } else {
                phoneAudioSocketId = null
                phoneAudioConnecting.set(false)
                scheduleAudioReconnect("connect-returned-null")
            }
        } catch (e: Exception) {
            phoneAudioSocketId = null
            phoneAudioConnecting.set(false)
            btErr("[AudioSocket] connect threw: ${e.message}")
            scheduleAudioReconnect("connect-threw")
        }
    }

    /**
     * Event-driven, coalesced reconnect of the audio data socket. Only ever
     * called from a teardown/failure callback (onDisconnected/onError) or a
     * cold-link path inside connectPhoneAudio -- never a free-running poll. The
     * settle delay lets the RFCOMM MCB release before the next connect to the
     * same BD_ADDR so the stack doesn't reject it as already-open.
     */
    private fun scheduleAudioReconnect(reason: String) {
        if (!audioWanted()) return
        audioReconnectRunnable?.let { audioReconnectHandler.removeCallbacks(it) } // coalesce
        val r = Runnable {
            audioReconnectRunnable = null
            connectPhoneAudio()
        }
        audioReconnectRunnable = r
        audioReconnectHandler.postDelayed(r, AUDIO_RECONNECT_SETTLE_MS)
        btLog("[AudioSocket] reconnect scheduled (reason=$reason) in ${AUDIO_RECONNECT_SETTLE_MS}ms")
    }

    /** Cancel any pending audio-socket reconnect (teardown / no-longer-wanted). */
    private fun cancelAudioReconnect() {
        audioReconnectRunnable?.let { audioReconnectHandler.removeCallbacks(it) }
        audioReconnectRunnable = null
    }

    private fun disconnectPhoneAudio() {
        cancelAudioReconnect()
        phoneAudioConnecting.set(false)
        val sid = phoneAudioSocketId ?: return
        phoneAudioSocketId = null
        phoneAudioConnected = false
        btManagerBridge.closeRfcommSocket(sid)
    }

    /**
     * G4: drain a wake-word prebuffer snapshot through the dedicated phone
     * audio RFCOMM socket. The frames are encoded with a SEPARATE OpusEncoder
     * instance (the live publisher's encoder is owned by the MicStream-Thread
     * and not safe to share). Pacing is real-time (1 s of audio per 1 s of
     * wall) so the phone STT jitter buffer doesn't overrun.
     *
     * While [prebufferFlushInProgress] is true the live publisher skips its
     * write, ensuring buffered frames land before any live frame.
     */
    private fun flushPrebufferAsync(snap: com.repository.glasses.listener.capture.PrebufferingAudioSubscriber.Snapshot) {
        if (snap.sampleCount == 0) {
            btLog("[Prebuffer] flush skipped: empty snapshot")
            return
        }
        prebufferFlushExecutor.execute {
            val sid = phoneAudioSocketId
            if (sid == null || !phoneAudioConnected) {
                btLog("[Prebuffer] flush deferred: phoneSid=$sid connected=$phoneAudioConnected; re-stash")
                // Re-stash for the next onConnected to retry, but only if there's no NEWER
                // snapshot already pending (putIfAbsent semantics via compareAndSet from null).
                pendingPrebufferSnapshot.compareAndSet(null, snap)
                return@execute
            }
            prebufferFlushInProgress = true
            GT.section("audio.prebuffer.flush") {
                val flushEncoder = com.repository.glasses.listener.audio.OpusEncoder(
                    sampleRate = 16000, bitrate = 16000, log = { btLog("PrebufFlushOpus: $it") }
                )
                try {
                    if (!flushEncoder.initialize() || !flushEncoder.isAvailable()) {
                        btErr("[Prebuffer] flush encoder init failed; sending raw PCM not supported on phone path, dropping")
                        return@section
                    }
                    val ms = snap.sampleCount / 16 // 16 samples = 1 ms at 16 kHz mono
                    btLog("[Prebuffer] flush begin samples=${snap.sampleCount} (~${ms} ms)")
                    val chunkSize = 16000  // 1 s @ 16 kHz
                    var idx = 0
                    var frameIndex = 0
                    val startMs = System.currentTimeMillis()
                    while (idx < snap.sampleCount) {
                        val take = minOf(chunkSize, snap.sampleCount - idx)
                        if (take < 40) {
                            // Smaller than the minimum opus frame (2.5 ms @ 16 kHz). Drop the
                            // tail rather than corrupt the encoder.
                            btLog("[Prebuffer] dropping ${take}-sample tail (< minimum opus frame)")
                            break
                        }
                        val opus = try {
                            flushEncoder.encodeShort(snap.samples, idx, take)
                        } catch (e: IllegalArgumentException) {
                            btErr("[Prebuffer] encode range error: ${e.message}")
                            null
                        }
                        if (opus != null && opus.isNotEmpty()) {
                            try {
                                synchronized(phoneAudioWriteLock) {
                                    btManagerBridge.writeRfcomm(sid, opus)
                                }
                                frameIndex++
                            } catch (e: Exception) {
                                btErr("[Prebuffer] writeRfcomm failed: ${e.message}; aborting flush")
                                return@section
                            }
                        }
                        idx += take
                        // Real-time pacing.
                        val targetWallMs = startMs + (idx / 16)
                        val deltaMs = targetWallMs - System.currentTimeMillis()
                        if (deltaMs > 0) {
                            try { Thread.sleep(deltaMs) } catch (_: InterruptedException) {}
                        }
                    }
                    btLog("[Prebuffer] flush done frames=$frameIndex samples=${snap.sampleCount}")
                } catch (t: Throwable) {
                    btErr("[Prebuffer] flush failed: ${t.message}")
                } finally {
                    try { flushEncoder.release() } catch (_: Throwable) {}
                    prebufferFlushInProgress = false
                }
            }
        }
    }

    private fun startPcAudioListener() {
        if (pcAudioSocketId != null) return
        val sid = btManagerBridge.listenRfcommInbound("GlassesPcMic", pcAudioSocketUuid)
        if (sid != null) {
            pcAudioSocketId = sid
            btLog("PcAudio: listening via BtManager (socketId=$sid)")
        } else {
            btLog("PcAudio: failed to start listener")
        }
    }

    private fun stopPcAudioListener() {
        val sid = pcAudioSocketId ?: return
        pcAudioSocketId = null
        pcAudioConnected = false
        btManagerBridge.closeRfcommSocket(sid)
    }

    private fun extractChannelTo(
        rawBuffer: ByteArray, outBuffer: ByteArray,
        channel: Int, gain: Int, frames: Int, bytesPerFrame: Int
    ) {
        val chByteOff = channel * 2
        for (i in 0 until frames) {
            val srcOff = i * bytesPerFrame + chByteOff
            val dstOff = i * 2
            val lo = rawBuffer[srcOff].toInt() and 0xFF
            val hi = rawBuffer[srcOff + 1].toInt()
            val sample = (hi shl 8) or lo
            val amplified = (sample * gain).coerceIn(-32768, 32767)
            outBuffer[dstOff] = (amplified and 0xFF).toByte()
            outBuffer[dstOff + 1] = (amplified shr 8).toByte()
        }
    }

    /**
     * Bring the audio path to a fully live state.
     *
     * @param reason free-form label for diagnostics.
     * @param force when true (hold-tap / explicit activation), bypass the wear
     *   gate and proactively cycle the phone audio socket. The wear sensor can
     *   lag behind reality (user holding the glasses with a hand, sensor warm-
     *   up, etc.), and the audio RFCOMM socket can be half-dead from the phone
     *   side without glasses noticing -- forced activation reconciles both.
     *   Non-forced callers (BT-connect, screen-on, post-idle) keep the wear
     *   gate so we don't pin AudioIn / AGM HAL awake when the user isn't there.
     *
     * Idempotent: every internal step (gate flip, mic pump start, socket
     * connect) is a no-op if already in the desired state, except the forced
     * socket cycle which intentionally drops+reconnects.
     */
    private fun signalAudioStart(reason: String, force: Boolean = false, durationMs: Long = 30_000L) {
        val worn = lastWornState != false
        if (!worn && !force) {
            btLog("Audio stream arm ($reason) -- off-head, refusing to open mic")
            return
        }
        if (!worn && force) {
            btLog("Audio stream arm ($reason) -- forced (off-head wear ignored)")
        }
        wantAudioStream = true
        // Forced path: cycle the audio socket so a half-dead peer doesn't keep
        // accepting writes that never arrive. connectPhoneAudio() early-returns
        // if a socket id exists, so without the explicit drop a stale socket
        // would persist forever.
        if (force && phoneAudioSocketId != null && !phoneAudioConnected) {
            btLog("Audio stream arm ($reason) -- forced cycle of stale phone audio socket")
            disconnectPhoneAudio()
        }
        // enterLiveUtteranceMode flips streamMode to LIVE_UTTERANCE first so that
        // the subsequent startMicStream sees the live gate and runs its
        // connectPhoneAudio() side effect. Order matters: gate first, mic after.
        val transitioned = enterLiveUtteranceMode(reason, durationMs)
        startMicStream(reason)
        // Reconcile the wakelock after both the gate and the pump have flipped.
        reconcileServiceWakeLock("signal:$reason")
        btLog("Audio stream arm ($reason) -- transitioned=$transitioned force=$force")
        btClient.sendStatus("glasses_audio_open")
    }

    private fun stopGlassesAudioStream(reason: String) {
        // Do NOT stop the mic. It stays on so MicBus subscribers (LocalOpusWriter
        // for the rotating audio archive, WakeWordPipeline for on-glasses wake
        // detection) keep seeing audio. We just close the BT live-stream gate
        // and any open audio socket.
        btLog("Audio stream stop ($reason)")
        wantAudioStream = false
        exitLiveUtteranceMode(reason)
        // Drop any pre-connect frames buffered for this stream so a now-larger
        // dynamic buffer can't linger and flush stale audio into a LATER session.
        pendingAudioFrames.clear()
        pendingAudioAtCapLogged = false
        if (!pcAudioConnected) {
            disconnectPhoneAudio()
        } else {
            btLog("Phone audio socket kept alive for PC consumer")
        }
        btClient.sendStatus("screen_off")
    }

    /**
     * Track C: recompute mic demand and start/stop the mic stream accordingly.
     * Called whenever a consumer-state changes: wear, phone audio, pc audio,
     * or wake-word arming.
     *
     * Mic is needed iff:
     *   worn AND (phoneAudioConnected || pcAudioConnected || wakeWordPipeline.isRunning())
     *
     * Today wake-word is always armed when worn (Track B), so the practical
     * effect matches Track B. The win is structural: future modes that disable
     * wake-word (e.g. "do not disturb") automatically save mic power when no
     * peer is connected.
     */
    /**
     * WW gating: stop wake-word when no phone connected. Wake-word is useless
     * without an RFCOMM peer to wake -- worn-but-disconnected = no-op state.
     * Idempotent; safe to call multiple times. Mirrors reconcileMicStream
     * structure but governs only WakeWordPipeline lifecycle.
     */
    private fun reconcileWakeWord(reason: String) {
        val worn = lastWornState != false  // null treated as worn (matches Track B default)
        // Debug override: setprop debug.glasses.ww.force_start 1 — for ACD bring-up
        // tests when no companion phone RFCOMM peer is available. Read fresh each call.
        val forced = try {
            val sp = Class.forName("android.os.SystemProperties")
            val get = sp.getMethod("get", String::class.java, String::class.java)
            (get.invoke(null, "debug.glasses.ww.force_start", "0") as String).let {
                it == "1" || it.equals("true", ignoreCase = true)
            }
        } catch (_: Throwable) { false }
        val phone = phoneAudioConnected || forced
        val wakewordEnabled = GlassesConfig.wakewordEnabled
        val needed = worn && phone && wakewordEnabled
        val running = ::wakeWordPipeline.isInitialized && wakeWordPipeline.isRunning()
        val action = when {
            needed && !running -> "start"
            !needed && running -> "stop"
            else -> "noop"
        }
        Log.i(TAG, "[WWGate] reason=$reason worn=$worn phone=$phone wakewordEnabled=$wakewordEnabled needed=$needed running=$running action=$action")
        btLog("[WWGate] reason=$reason worn=$worn phone=$phone wakewordEnabled=$wakewordEnabled needed=$needed running=$running")
        if (needed && !running) {
            if (::wakeWordPipeline.isInitialized) {
                try { wakeWordPipeline.start() } catch (t: Throwable) { btErr("[WWGate] start failed: ${t.message}") }
            }
        } else if (!needed && running) {
            try { wakeWordPipeline.stop() } catch (t: Throwable) { btErr("[WWGate] stop failed: ${t.message}") }
        }
        reconcileServiceWakeLock("ww:$reason")
    }

    private fun postReconcileLocalOpusWriter(reason: String) {
        try {
            reconcileExecutor.execute { reconcileLocalOpusWriter(reason) }
        } catch (t: Throwable) {
            btErr("[OpusGate] postReconcile reason=$reason failed: ${t.message}")
        }
    }

    private fun reconcileLocalOpusWriter(reason: String) {
        if (!::localOpusWriter.isInitialized) {
            btLog("[OpusGate] reason=$reason skipped: writer not yet initialized")
            return
        }
        val cfg = GlassesConfig
        val worn = lastWornState != false
        val shouldRun = cfg.batteryPct >= 5 && (
            cfg.onDemandRecordingActive ||                          // bypass wear
            (cfg.alwaysRecordEnabled && worn)                       // wear-gated
        )
        val running = localOpusWriter.isRunning()
        // Detect on-demand on->off transition so we can rotate the file (sync the
        // just-finished on-demand recording, then keep going under always-record).
        val prevOnDemand = lastOnDemandSeen
        val curOnDemand = cfg.onDemandRecordingActive
        lastOnDemandSeen = curOnDemand
        val onDemandStopped = prevOnDemand && !curOnDemand
        btLog("[OpusGate] reason=$reason worn=$worn battery=${cfg.batteryPct} alwaysOn=${cfg.alwaysRecordEnabled} onDemand=$curOnDemand prevOnDemand=$prevOnDemand onDemandStopped=$onDemandStopped -> shouldRun=$shouldRun running=$running")
        if (shouldRun && !running) {
            try { localOpusWriter.start() } catch (t: Throwable) { btErr("[OpusGate] start failed: ${t.message}") }
        } else if (!shouldRun && running) {
            try { localOpusWriter.stop() } catch (t: Throwable) { btErr("[OpusGate] stop failed: ${t.message}") }
            val f = localOpusWriter.lastClosedFileOrNull()
            if (f != null) {
                try { notifyFileSync(f.absolutePath, "audio-archive") } catch (t: Throwable) { btErr("[OpusGate] notifyFileSync failed: ${t.message}") }
            }
        } else if (shouldRun && running && onDemandStopped) {
            // Sync the on-demand file immediately, then transparently start a fresh
            // always-record segment without dropping mic frames.
            val closed = try { localOpusWriter.rotateNow("on-demand-stop") } catch (t: Throwable) {
                btErr("[OpusGate] rotateNow failed: ${t.message}"); null
            }
            if (closed != null) {
                try { notifyFileSync(closed.absolutePath, "audio-archive") } catch (t: Throwable) { btErr("[OpusGate] notifyFileSync(rotate) failed: ${t.message}") }
            }
        }
        pushRecordingStatusToPhone()
    }

    private fun pushRecordingStatusToPhone() {
        try {
            val cfg = GlassesConfig
            val running = if (::localOpusWriter.isInitialized) localOpusWriter.isRunning() else false
            val json = JSONObject().apply {
                put("glasses_battery_pct", cfg.batteryPct)
                put("always_record_enabled", cfg.alwaysRecordEnabled)
                put("on_demand_recording_active", cfg.onDemandRecordingActive)
                put("recording_active", running)
                put("worn", lastWornState != false)
            }
            if (::btClient.isInitialized) {
                btClient.sendStatus("recording_status:${json}")
            }
        } catch (t: Throwable) {
            btErr("pushRecordingStatusToPhone failed: ${t.message}")
        }
    }

    private fun reconcileMicStream(reason: String) {
        val worn = lastWornState != false  // null (unknown) treated as worn -- match Track B default
        val ww = ::wakeWordPipeline.isInitialized && wakeWordPipeline.isRunning()
        val cfg = GlassesConfig
        val onDemand = cfg.onDemandRecordingActive
        val sinkNeeded = worn && (phoneAudioConnected || pcAudioConnected || ww)
        val archiveNeeded = cfg.batteryPct >= 5 && worn && cfg.alwaysRecordEnabled
        // An active notif voice-reply (or any open stream demand) wants audio even
        // if the phone audio socket momentarily dropped during a reconnect. Without
        // this term, a mid-reply socket drop computes needed=false and stops the mic
        // pump -- freezing the local spectrogram AND starving Azure of PCM so the
        // reply hangs forever. Keep the mic alive while audio is wanted; frames
        // buffer into pendingAudioFrames and flush on reconnect.
        val wantedAudio = notifReplyId != null || wantAudioStream
        val needed = onDemand || sinkNeeded || archiveNeeded || wantedAudio
        if (wantedAudio && !sinkNeeded && !phoneAudioConnected) {
            btLog("[NREPLY] keeping mic alive (audio wanted, socket not connected): notifReply=${notifReplyId != null} wantStream=$wantAudioStream")
        }
        btLog("[MicDemand] reason=$reason worn=$worn phone=$phoneAudioConnected pc=$pcAudioConnected ww=$ww onDemand=$onDemand alwaysOn=${cfg.alwaysRecordEnabled} wantedAudio=$wantedAudio -> needed=$needed (running=$micStreaming)")
        if (needed && !micStreaming) {
            startMicStream("demand:$reason")
        } else if (!needed && micStreaming) {
            stopMicStream("demand:$reason")
        }
        reconcileServiceWakeLock("mic:$reason")
    }

    private fun useSingleMicMode(): Boolean {
        // AR1-specific: forced to true. AudioSource.MIC on this device triggers the
        // 8-channel mix_record HAL profile (~8x audioserver data rate); using
        // VOICE_RECOGNITION + CHANNEL_IN_MONO lands on the lower-channel HAL graph.
        return true
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startMicStream(reason: String) {
        if (micStreaming) {
            btLog("[MicBus] Mic pump already running, skipping start ($reason)")
            // Still kick off phone audio socket for BT live stream -- the pump is
            // idempotent, but the phone RFCOMM socket is gated by callers.
            if (streamMode == StreamMode.LIVE_UTTERANCE) {
                connectPhoneAudio()
            }
            return
        }
        micStreaming = true
        micChunkCount = 0L

        // useSingleMicMode() is forced true on AR1; multi-mic/beamformer path removed.
        btLog("[Mic] mode=MONO")

        val sampleRate = 16000
        // 1-second chunks: one ARM wake per second instead of eight. Keeps the
        // 8-channel 0x6000FC beamformer mask + ch1 extraction + 24x gain path.
        val chunkFrames = 16000
        // AR1-specific: force MONO + VOICE_RECOGNITION. AudioSource.MIC triggers the
        // 8-channel mix_record HAL profile (~8x audioserver data rate); VOICE_RECOGNITION
        // + CHANNEL_IN_MONO lands on the lower-channel HAL graph.
        val channelMask = android.media.AudioFormat.CHANNEL_IN_MONO
        val numChannels = 1
        val bytesPerFrame = numChannels * 2

        // AudioRecord.getMinBufferSize() returns bytes for MONO; for 8 channels we
        // want at least 1 s of 8-channel PCM16, but never below the system minimum.
        val desiredBufBytes = chunkFrames * bytesPerFrame
        val sysMinBuf = try {
            android.media.AudioRecord.getMinBufferSize(
                sampleRate,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            )
        } catch (_: Exception) { -1 }
        val audioRecordBufBytes = maxOf(desiredBufBytes, sysMinBuf)
        btLog("[MicBus] AudioRecord init: MIC ${numChannels}ch rate=$sampleRate mask=0x${channelMask.toString(16)} chunkFrames=$chunkFrames bufBytes=$audioRecordBufBytes sysMin=$sysMinBuf")

        // AudioSource note: VOICE_RECOGNITION + MONO routes through PAL_DEVICE_IN_SPEAKER_MIC,
        // which on this Rokid build has NO ACDB calibration graph -- PAL fails the start with
        // "Graph Alias chunk data does not exist" and AudioRecord then silently delivers all-zero
        // PCM (the user-visible "silent recordings" symptom). AudioSource.MIC routes through a
        // different PAL device that does have calibration. Trade-off: MIC may trigger the higher-
        // channel HAL profile, but actual audio capture trumps the data-rate optimization.
        try {
            micRecord = android.media.AudioRecord.Builder()
                .setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    android.media.AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(audioRecordBufBytes)
                .build()
        } catch (e: Exception) {
            btErr("[MicBus] AudioRecord.Builder failed: ${e.message}")
            micStreaming = false
            return
        }

        if (micRecord?.state != android.media.AudioRecord.STATE_INITIALIZED) {
            btErr("[MicBus] MicRecord failed to initialize")
            micRecord?.release()
            micRecord = null
            micStreaming = false
            return
        }

        val actualChannelCount = micRecord?.channelCount ?: -1
        val actualFormat = micRecord?.audioFormat ?: -1
        val actualRate = micRecord?.sampleRate ?: -1
        btLog("[MicBus] AudioRecord actual: channels=$actualChannelCount format=$actualFormat rate=$actualRate")

        attachAudioEffects(micRecord!!)

        micRecord?.startRecording()
        MicBus.notifyStart()

        // Connect phone RFCOMM socket only when the BT live-stream gate is open.
        // Under LOCAL_ONLY we keep the radio cold.
        if (streamMode == StreamMode.LIVE_UTTERANCE) {
            connectPhoneAudio()
        }

        // Opus encoder for phone BT bandwidth reduction
        val opusEncoder = com.repository.glasses.listener.audio.OpusEncoder(
            sampleRate = sampleRate, bitrate = 16000, log = { btLog(it) }
        )
        opusEncoder.initialize()

        // Separate Opus encoder for PC (ch1, independent from phone ch2)
        val pcOpusEncoder = com.repository.glasses.listener.audio.OpusEncoder(
            sampleRate = sampleRate, bitrate = 16000, log = { btLog("PcOpus: $it") }
        )
        pcOpusEncoder.initialize()

        // Echo cancellation (disabled -- infrastructure kept ready for retry)
        val aecEnabled = false
        val aecm = if (aecEnabled) {
            com.repository.glasses.listener.audio.WebRtcAecm(sampleRate = sampleRate, log = { btLog(it) }).also { it.initialize() }
        } else null

        micStreamThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val rawBuffer = ByteArray(chunkFrames * bytesPerFrame)
            val monoBuffer = ByteArray(chunkFrames * 2)
            val pcMonoBuffer = ByteArray(chunkFrames * 2)
            // ShortArray view of the mono channel for MicBus subscribers.
            val monoShort = ShortArray(chunkFrames)
            var consecutiveZeroChunks = 0
            // ~2 s of silence -> restart AudioRecord. With 1 s chunks this is 2 chunks.
            val zeroThreshold = 2

            // Per-chunk log aggregation. The first 10 chunks log in full for bring-up
            // diagnostics; after that we emit one aggregated summary per minute
            // (avg maxAmp, p99 writeMs, subscriber count) to avoid ~86k lines/day.
            var aggCount = 0
            var aggMaxAmpSum = 0L
            var aggWriteMsSum = 0L
            val aggWriteMsSamples = ArrayList<Long>(128)
            var aggStartMs = SystemClock.elapsedRealtime()
            val aggWindowMs = 60_000L

            while (micStreaming) {
                val bytesRead = micRecord?.read(rawBuffer, 0, rawBuffer.size) ?: -1
                if (bytesRead > 0) {
                    val epochNanos = System.nanoTime()
                    micChunkCount++

                    val micGain = 24
                    val frames = bytesRead / bytesPerFrame
                    var chunkMaxAmp = 0
                    var monoZeroCount = 0
                    // 1-channel mono: rawBuffer is already PCM16 mono. Apply gain
                    // and copy through. No channel extraction.
                    for (i in 0 until frames) {
                        val srcOff = i * 2
                        val lo = rawBuffer[srcOff].toInt() and 0xFF
                        val hi = rawBuffer[srcOff + 1].toInt()
                        val sample = (hi shl 8) or lo
                        val amplified = (sample * micGain).coerceIn(-32768, 32767)
                        monoBuffer[srcOff] = (amplified and 0xFF).toByte()
                        monoBuffer[srcOff + 1] = (amplified shr 8).toByte()
                        monoShort[i] = amplified.toShort()
                        val abs = kotlin.math.abs(amplified)
                        if (abs > chunkMaxAmp) chunkMaxAmp = abs
                        if (amplified == 0) monoZeroCount++
                    }

                    // Fan out the mono 16 kHz PCM to all MicBus subscribers (local
                    // archive writer + on-glasses wake detector once wired). Always
                    // happens -- independent of BT live-stream mode.
                    MicBus.emit(monoShort, 0, frames, epochNanos)

                    // Write ch1 PCM to video audio file if recording. The
                    // 24x software gain we apply for MicBus / wake-word
                    // streams does NOT belong on recordings -- it amplifies
                    // background noise just as much as voice and is the
                    // root cause of the noisy floor on saved videos. The
                    // recording path now reads ch1 directly from rawBuffer
                    // *unamplified* into a fresh recBuffer (player gain
                    // makes up the loudness on the consumer side). For AR
                    // (videoAudioMixEcho=true) the rear-firing echo channels
                    // 5/6/7 are averaged and summed into the unamplified
                    // mic sample so the WAV captures both voice and device
                    // playback.
                    if (videoAudioRecording) {
                        try {
                            // Reuse a per-thread reusable scratch buffer.
                            // Allocate on first use (frames is bounded by
                            // chunkFrames=16000 = 32 KB).
                            if (videoRecScratch.size < frames * 2) {
                                videoRecScratch = ByteArray(frames * 2)
                            }
                            val rec = videoRecScratch
                            // Mono path: rawBuffer IS the mic channel. Echo mix
                            // unavailable (no ch5/6/7) -- write unamplified mic.
                            // TODO: confirm dead under singleMic-always: videoAudioMixEcho field + setters at ~4472/4559 are unreachable now.
                            System.arraycopy(rawBuffer, 0, rec, 0, frames * 2)
                            videoAudioStream?.write(rec, 0, frames * 2)
                            videoAudioBytesWritten += frames * 2
                        } catch (_: Exception) {}
                    }

                    // Echo reference RMS unavailable in single-mic mode (no ch6).
                    lastEchoRms = 0f

                    // Detect dead mic: if all-zero for ~2s, recreate AudioRecord
                    // Skip when gate is active (intentional attenuation)
                    if (chunkMaxAmp == 0) {
                        consecutiveZeroChunks++
                        if (consecutiveZeroChunks == zeroThreshold) {
                            btLog("Mic dead (${consecutiveZeroChunks} zero chunks), restarting AudioRecord")
                            releaseAudioEffects()
                            try {
                                micRecord?.stop()
                                micRecord?.release()
                            } catch (_: Exception) {}
                            micRecord = try {
                                // AR1-specific: AudioSource.MIC + MONO. VOICE_RECOGNITION routes
                                // through PAL_DEVICE_IN_SPEAKER_MIC which has no ACDB Graph Alias
                                // chunk on this Rokid build -- audio comes back as silent PCM.
                                // MIC routes through a calibrated device; the channel-count cost is
                                // accepted in exchange for actual audio.
                                android.media.AudioRecord.Builder()
                                    .setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                                    .setAudioFormat(
                                        android.media.AudioFormat.Builder()
                                            .setSampleRate(sampleRate)
                                            .setChannelMask(channelMask)
                                            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                                            .build()
                                    )
                                    .build()
                            } catch (_: Exception) { null }
                            if (micRecord?.state == android.media.AudioRecord.STATE_INITIALIZED) {
                                attachAudioEffects(micRecord!!)
                                micRecord?.startRecording()
                                btLog("AudioRecord restarted successfully")
                            } else {
                                btErr("AudioRecord restart failed, giving up")
                                break
                            }
                            consecutiveZeroChunks = 0
                        }
                    } else {
                        consecutiveZeroChunks = 0
                    }

                    // Broadcast audio levels for equalizer visualization during LISTENING or Telegram voice.
                    // Mic chunks are 1 s long (chunkFrames=16000) for power -- broadcasting once per chunk gives a 1 Hz envelope,
                    // which made bars react ~1 s behind speech. Split each chunk into LEVEL_SUBWINDOWS sub-windows (~50 ms each)
                    // and pack all of them into a single broadcast as a flat FloatArray. The view replays the burst at 50 ms
                    // intervals on the UI side so the bars track speech in real time without inflating IPC.
                    if (state == State.LISTENING || telegramVoiceActive) {
                        val bandCount = 32
                        val subWindows = LEVEL_SUBWINDOWS
                        val framesPerSub = frames / subWindows
                        if (framesPerSub > 0) {
                            val samplesPerBand = framesPerSub / bandCount
                            if (samplesPerBand > 0) {
                                val flat = FloatArray(subWindows * bandCount)
                                for (sw in 0 until subWindows) {
                                    val baseFrame = sw * framesPerSub
                                    val outBase = sw * bandCount
                                    for (b in 0 until bandCount) {
                                        var sum = 0.0
                                        val bandStart = baseFrame + b * samplesPerBand
                                        for (s in 0 until samplesPerBand) {
                                            val off = (bandStart + s) * 2
                                            val lo = monoBuffer[off].toInt() and 0xFF
                                            val hi = monoBuffer[off + 1].toInt()
                                            val sample = (hi shl 8) or lo
                                            sum += sample.toDouble() * sample.toDouble()
                                        }
                                        flat[outBase + b] = kotlin.math.sqrt(sum / samplesPerBand).toFloat() / 32768f
                                    }
                                }
                                sendBroadcast(Intent(ACTION_AUDIO_LEVELS).apply {
                                    setPackage(packageName)
                                    putExtra(EXTRA_AUDIO_LEVELS, flat)
                                    putExtra(EXTRA_AUDIO_LEVELS_BANDS, bandCount)
                                })
                                // Notification voice-reply overlay (service-owned)
                                // shows the SAME real mic envelope. The MainActivity
                                // visualizer is a different process via the broadcast
                                // above; the reply overlay lives here, so feed it
                                // directly with the real levels (was a fake sine loop).
                                if (notifReplyId != null) {
                                    notificationOverlay.pushVisualizerEnvelope(flat, bandCount)
                                }
                            }
                        }
                    }

                    val monoBytes = frames * 2
                    // BT live stream: gated by [streamMode]. Under LOCAL_ONLY the
                    // radio stays cold even if a socket happens to be connected.
                    val liveGateOpen = streamMode == StreamMode.LIVE_UTTERANCE
                    val phoneSid = phoneAudioSocketId
                    var rc = 0
                    val writeStart = SystemClock.elapsedRealtime()
                    if (liveGateOpen && phoneSid != null && phoneAudioConnected && !prebufferFlushInProgress) {
                        try {
                            if (opusEncoder.isAvailable()) {
                                // OpusEncoder.encode now throws IllegalArgumentException on a
                                // real range error. Catch it separately so a bad frame drops
                                // the frame rather than tearing down the BT socket.
                                val encoded = try {
                                    opusEncoder.encode(monoBuffer, 0, monoBytes)
                                } catch (e: IllegalArgumentException) {
                                    btErr("opusEncoder.encode range error: ${e.message} (dropping frame)")
                                    null
                                }
                                if (encoded != null) {
                                    synchronized(phoneAudioWriteLock) {
                                        // Re-read the socket id under the lock. During a
                                        // reconnect the captured phoneSid can be the OLD
                                        // socket while phoneAudioSocketId already advanced
                                        // (or was nulled). Writing a partial Opus frame onto
                                        // the dead/old socket desyncs the phone decoder
                                        // (bad frameLen). Only write to the CURRENT, still-
                                        // connected socket; otherwise drop -- the mic keeps
                                        // producing and the next frames go through once
                                        // reconnected (pendingAudioFrames covers the gap).
                                        val sid = phoneAudioSocketId
                                        if (sid != null && sid == phoneSid && phoneAudioConnected) {
                                            btManagerBridge.writeRfcomm(sid, encoded)
                                        }
                                    }
                                }
                            } else {
                                synchronized(phoneAudioWriteLock) {
                                    // Same stale-sid guard for the raw-PCM fallback path.
                                    val sid = phoneAudioSocketId
                                    if (sid != null && sid == phoneSid && phoneAudioConnected) {
                                        btManagerBridge.writeRfcomm(sid, monoBuffer.copyOf(monoBytes))
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            btLog("AudioSocket write failed: ${e.message}, reconnecting...")
                            disconnectPhoneAudio()
                            scheduleAudioReconnect("write-failed")
                            rc = -1
                        }
                    } else if (!liveGateOpen) {
                        rc = -3 // gated off
                    } else if (state == State.LISTENING || notifReplyId != null) {
                        // Gate open but socket not ready -- buffer encoded audio
                        // so the phone gets it once RFCOMM connects. AI-chat keys
                        // on State.LISTENING; a notif voice-reply never enters that
                        // state (notifReplyId != null instead), so without this OR
                        // its pre-connect frames would fall to the drop branch and
                        // the reply would silently send empty audio.
                        try {
                            val encoded = if (opusEncoder.isAvailable()) {
                                try { opusEncoder.encode(monoBuffer, 0, monoBytes) }
                                catch (_: IllegalArgumentException) { null }
                            } else { monoBuffer.copyOf(monoBytes) }
                            if (encoded != null) {
                                pendingAudioFrames.add(encoded)
                                // Dynamic cap: let the buffer grow across the whole
                                // pre-connect window (flushed in order on connect, so no
                                // first-word clipping) but bound it by total buffered
                                // audio DURATION (PENDING_AUDIO_MAX_MS) to prevent
                                // unbounded growth. Evict OLDEST (FIFO) past the cap.
                                if (pendingAudioFrames.size > PENDING_AUDIO_MAX_FRAMES) {
                                    while (pendingAudioFrames.size > PENDING_AUDIO_MAX_FRAMES) pendingAudioFrames.poll()
                                    if (!pendingAudioAtCapLogged) {
                                        pendingAudioAtCapLogged = true
                                        btLog("[NREPLY] pending audio buffer at cap (${PENDING_AUDIO_MAX_MS}ms), evicting oldest")
                                    }
                                } else {
                                    pendingAudioAtCapLogged = false
                                }
                                if (notifReplyId != null && state != State.LISTENING && micChunkCount % 5 == 0L) {
                                    btLog("[NREPLY] buffering mic frame pre-connect (pending=${pendingAudioFrames.size})")
                                }
                            }
                        } catch (_: Exception) {}
                        rc = -4 // buffered
                    } else {
                        rc = -2
                    }
                    val writeMs = SystemClock.elapsedRealtime() - writeStart

                    // PC path: same gate. PC wants live audio or it does not; while
                    // LOCAL_ONLY is active, we hold back.
                    var pcRc = 0
                    val pcSid = pcAudioSocketId
                    if (liveGateOpen && pcSid != null && pcAudioConnected) {
                        try {
                            extractChannelTo(rawBuffer, pcMonoBuffer, pcExtractChannel, pcMicGain, frames, bytesPerFrame)
                            if (pcOpusEncoder.isAvailable()) {
                                val pcEncoded = try {
                                    pcOpusEncoder.encode(pcMonoBuffer, 0, monoBytes)
                                } catch (e: IllegalArgumentException) {
                                    btErr("pcOpusEncoder.encode range error: ${e.message} (dropping frame)")
                                    null
                                }
                                if (pcEncoded != null) {
                                    btManagerBridge.writeRfcomm(pcSid, pcEncoded)
                                }
                            }
                        } catch (e: Exception) {
                            btLog("PcAudio write failed: ${e.message}")
                            pcAudioConnected = false
                            pcRc = -1
                        }
                    } else if (!liveGateOpen) {
                        pcRc = -3
                    } else {
                        pcRc = -2
                    }

                    // Log aggregation: first 10 chunks full, then one aggregated
                    // summary per minute. Keeps logcat usable on always-on devices.
                    if (micChunkCount <= 10) {
                        val phoneMode = if (phoneAudioConnected) "RFCOMM" else "no-conn"
                        val pcMode = if (pcAudioConnected) "PC" else "no-pc"
                        btLog("[MicBus] #$micChunkCount: ${frames}fr maxAmp=$chunkMaxAmp zeros=$monoZeroCount/$frames mode=$streamMode phone=$phoneMode($rc) pc=$pcMode($pcRc) subs=${MicBus.subscriberCount()} writeMs=$writeMs")
                    } else {
                        aggCount++
                        aggMaxAmpSum += chunkMaxAmp.toLong()
                        aggWriteMsSum += writeMs
                        aggWriteMsSamples.add(writeMs)
                        val nowMs = SystemClock.elapsedRealtime()
                        if (nowMs - aggStartMs >= aggWindowMs) {
                            val avgMaxAmp = if (aggCount > 0) aggMaxAmpSum / aggCount else 0L
                            val avgWriteMs = if (aggCount > 0) aggWriteMsSum / aggCount else 0L
                            val p99WriteMs = if (aggWriteMsSamples.isNotEmpty()) {
                                val sorted = aggWriteMsSamples.sorted()
                                val idx = ((sorted.size - 1) * 0.99).toInt()
                                sorted[idx]
                            } else 0L
                            val phoneMode = if (phoneAudioConnected) "RFCOMM" else "no-conn"
                            val pcMode = if (pcAudioConnected) "PC" else "no-pc"
                            btLog("[MicBus] agg/${aggWindowMs / 1000}s: $aggCount chunks, avgMaxAmp=$avgMaxAmp writeMs avg=${avgWriteMs}/p99=${p99WriteMs} mode=$streamMode phone=$phoneMode pc=$pcMode subs=${MicBus.subscriberCount()}")
                            aggCount = 0
                            aggMaxAmpSum = 0L
                            aggWriteMsSum = 0L
                            aggWriteMsSamples.clear()
                            aggStartMs = nowMs
                        }
                    }
                } else if (bytesRead < 0) {
                    btErr("MicRecord read error: $bytesRead")
                    break
                }
            }
            opusEncoder.release()
            pcOpusEncoder.release()
            aecm?.release()
            btLog("[MicBus] Mic stream thread exiting")
        }, "MicStream-Thread").apply {
            isDaemon = true
            start()
        }

        btLog("[MicBus] Mic pump started (MIC mono+24x gain, chunkFrames=$chunkFrames=${chunkFrames * 1000 / sampleRate}ms)")
    }

    /**
     * The mic pump is always-on for the lifetime of the service. This is only
     * called on service onDestroy. External stop triggers (screen-off,
     * BT-disconnect, pc-disconnect) route through [exitLiveUtteranceMode] and
     * leave the pump running so MicBus subscribers (local Opus archive,
     * on-glasses WakeWordPipeline) keep getting audio.
     */
    private fun stopMicStream(reason: String) {
        if (!micStreaming) return
        btLog("[MicBus] Stopping mic pump ($reason) after $micChunkCount chunks")
        micStreaming = false
        micStreamThread?.join(2000)
        micStreamThread = null
        MicBus.notifyStop()
        disconnectPhoneAudio()
        pendingAudioFrames.clear()
        pendingAudioAtCapLogged = false
        releaseAudioEffects()
        try {
            micRecord?.stop()
            micRecord?.release()
        } catch (_: Exception) {}
        micRecord = null
    }

    private fun attachAudioEffects(record: android.media.AudioRecord) {
        val sessionId = record.audioSessionId
        // AcousticEchoCanceler disabled (suppresses voice on Rokid 8-channel hardware)
        btLog("AcousticEchoCanceler skipped (Rokid hardware incompatible)")
        if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
            try {
                micNoiseSuppressor = android.media.audiofx.NoiseSuppressor.create(sessionId)
                micNoiseSuppressor?.enabled = true
                btLog("NoiseSuppressor enabled (session=$sessionId)")
            } catch (e: Exception) {
                btErr("NoiseSuppressor create failed: ${e.message}")
            }
        } else {
            btLog("NoiseSuppressor not available on this device")
        }
    }

    private fun releaseAudioEffects() {
        try { micEchoCanceler?.release() } catch (_: Exception) {}
        micEchoCanceler = null
        try { micNoiseSuppressor?.release() } catch (_: Exception) {}
        micNoiseSuppressor = null
    }

    // --- Mic diagnostic (temporary) ---

    private fun runMicDiagnostic() {
        Thread {
            btLog("=== MIC DIAGNOSTIC START (speak now!) ===")

            // Test 1: 4-channel mode at 16kHz (current config) - log ALL 4 channels
            testMicConfig("4CH-16k", android.media.MediaRecorder.AudioSource.MIC, 16000, 60, 4)

            // Test 2: 8-channel mode at 16kHz - log ALL 8 channels
            testMicConfig("8CH-16k", android.media.MediaRecorder.AudioSource.MIC, 16000, 6291708, 8)

            // Test 3: Back mic - MONO at 44100Hz (known to work via tuner app)
            testMicConfig("MONO-44k", android.media.MediaRecorder.AudioSource.MIC, 44100,
                android.media.AudioFormat.CHANNEL_IN_MONO, 1)

            // Test 4: Back mic - MONO at 48000Hz
            testMicConfig("MONO-48k", android.media.MediaRecorder.AudioSource.MIC, 48000,
                android.media.AudioFormat.CHANNEL_IN_MONO, 1)

            // Test 5: VOICE_RECOGNITION source with 4-channel
            testMicConfig("4CH-16k-VR", android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, 60, 4)

            btLog("=== MIC DIAGNOSTIC END ===")

            // Resume normal audio streaming after diagnostic
            wantAudioStream = false
            signalAudioStart("post-diagnostic")
        }.start()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun testMicConfig(label: String, source: Int, sampleRate: Int,
                               channelConfig: Int, numChannels: Int) {
        try {
            val bytesPerFrame = numChannels * 2
            val chunkFrames = 2048
            val minBuf = android.media.AudioRecord.getMinBufferSize(
                sampleRate, channelConfig, android.media.AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                btLog("[$label] getMinBufferSize FAILED: $minBuf")
                return
            }
            val rec = android.media.AudioRecord(
                source, sampleRate, channelConfig,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, chunkFrames * bytesPerFrame)
            )
            if (rec.state != android.media.AudioRecord.STATE_INITIALIZED) {
                btLog("[$label] AudioRecord NOT initialized")
                rec.release()
                return
            }
            rec.startRecording()
            val buf = ByteArray(chunkFrames * bytesPerFrame)

            val testDurationSec = 3
            val totalChunks = (testDurationSec.toDouble() * sampleRate / chunkFrames).toInt()
            val channelPeaks = IntArray(numChannels)
            val channelRmsAccum = LongArray(numChannels)
            var totalFrames = 0L
            val logInterval = maxOf(1, sampleRate / chunkFrames / 2) // ~every 0.5s

            for (chunk in 0 until totalChunks) {
                val bytesRead = rec.read(buf, 0, buf.size)
                if (bytesRead <= 0) continue
                val frames = bytesRead / bytesPerFrame
                totalFrames += frames

                val chunkPeaks = IntArray(numChannels)
                for (f in 0 until frames) {
                    for (ch in 0 until numChannels) {
                        val off = f * bytesPerFrame + ch * 2
                        val sample = (buf[off + 1].toInt() shl 8) or (buf[off].toInt() and 0xFF)
                        val abs = kotlin.math.abs(sample)
                        if (abs > channelPeaks[ch]) channelPeaks[ch] = abs
                        if (abs > chunkPeaks[ch]) chunkPeaks[ch] = abs
                        channelRmsAccum[ch] += sample.toLong() * sample.toLong()
                    }
                }

                if (chunk % logInterval == 0) {
                    btLog("[$label] chunk=$chunk peaks=${chunkPeaks.toList()}")
                }
            }

            rec.stop()
            rec.release()

            val rmsValues = channelRmsAccum.map {
                if (totalFrames > 0) kotlin.math.sqrt(it.toDouble() / totalFrames) else 0.0
            }
            btLog("[$label] SUMMARY: peaks=${channelPeaks.toList()} rms=${rmsValues.map { "%.1f".format(it) }}")
            btLog("[$label] peaks_pct=${channelPeaks.map { "%.2f%%".format(it * 100.0 / 32768) }}")

        } catch (e: Exception) {
            btLog("[$label] EXCEPTION: ${e.message}")
        }
    }

    // --- ScreenStateReceiver.ScreenStateListener ---

    override fun onScreenOn() {
        btLog("Screen ON")
        signalAudioStart("screen on")
        try { btClient.sendScreenState(true) } catch (t: Throwable) {
            btErr("sendScreenState(on) failed: ${t.message}")
        }
    }

    override fun onScreenOff() {
        btLog("Screen OFF")
        stopGlassesAudioStream("screen off")
        if (state != State.IDLE) {
            cancelledRequestId = currentRequestId
            ttsPlayer.interrupt()
            transitionToIdle()
        }
        try { btClient.sendScreenState(false) } catch (t: Throwable) {
            btErr("sendScreenState(off) failed: ${t.message}")
        }
    }

    private val sensorLongPressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_SENSOR_LONG_PRESS) handleSensorLongPress()
        }
    }

    private fun handleSensorLongPress() {
        btLog("SENSOR LONG-PRESS: state=$state")
        // If an HFP-HF SCO link is currently live (real call OR PC HF mic
        // streaming), the wearer's intent on hold-tap is mute -- not summon
        // the assistant mid-conversation. Try the mute toggle first; it
        // returns false when no SCO is up, in which case we fall through to
        // the normal AI activation path. CallController.toggleHfMicMute does
        // a fresh snapshot query internally so a SCO session that came up
        // before our broadcast receiver registered is still detected.
        try {
            if (callController.toggleHfMicMute()) {
                btLog("SENSOR LONG-PRESS: routed to HF mic mute toggle")
                return
            }
        } catch (e: Exception) {
            btErr("toggleHfMicMute threw: ${e.message}")
        }
        when (state) {
            State.IDLE, State.LISTENING -> activateListening()
            State.RESPONDING -> {
                btLog("SENSOR LONG-PRESS: interrupting response")
                ttsPlayer.interrupt()
                currentRequestId?.let { btClient.sendTtsInterrupt(it) }
                currentRequestId = null
                activateListening()
            }
        }
    }

    // --- Activation (triggered by phone detecting wake word from glasses audio) ---

    private fun activateListening() {
        try {
            ensureActivityRunning(android.os.Bundle().apply {
                putBoolean("switch_to_chat", true)
            })
            cancelledRequestId = null
            streamingDelivered = false

            // Activation is an explicit user signal: reconcile the entire audio
            // path unconditionally. Mic-pump flag, phone-audio socket, live
            // gate, and wear sensor can all individually drift -- forced
            // signalAudioStart brings everything fresh, ignoring stale flags
            // and the wear gate (the user is hold-tapping; sensor lag is not
            // a reason to refuse).
            btLog("ACTIVATE: reconciling audio path")
            signalAudioStart("activate listening", force = true, durationMs = 0L)

            // Snapshot media state before SFX plays
            mediaPlayingSnapshot = mediaSessionMonitor.isPlaying
            btLog("ACTIVATE: mediaPlaying=$mediaPlayingSnapshot duck=$mediaPlayingSnapshot")

            transitionState(State.LISTENING, "phone activate")
            btClient.sendStatus("LISTENING")

            btLog("ACTIVATE: playing activate sound")
            try {
                activatePlayer?.let {
                    if (it.isPlaying) it.stop()
                    it.seekTo(0)
                    it.start()
                }
            } catch (e: Exception) {
                btErr("ACTIVATE: activate sound failed: ${e.message}")
            }

            broadcastState("LISTENING")
            memLog("activateComplete")
        } catch (e: Exception) {
            btErr("ACTIVATE CRASH: ${e.message}\n${e.stackTraceToString()}")
            transitionToIdle()
        }
    }

    private fun transitionToIdle() {
        transitionState(State.IDLE, "transition to idle")
        exitLiveUtteranceMode("conversation ended")
        currentRequestId = null
        streamingDelivered = false
        btClient.sendStatus("IDLE")
        broadcastState("IDLE")
        // Ensure mic stream is running for wake word detection
        if (!micStreaming) {
            signalAudioStart("post-idle restart")
        }
    }

    // --- GlassesBtClient.Listener ---

    override fun onConnected() {
        btLog("BT connected to companion phone")
        broadcastBtState(true)
        broadcastState("IDLE")
        // Authoritative state snapshot: phone reconciles its glassesAudioState to match,
        // so any CH_STATUS messages dropped while the relay was disconnected don't leave
        // the two sides out of sync. Sent on every (re)connect.
        try {
            btClient.sendStateSnapshot(state.name, currentRequestId)
            btLog("state snapshot sent on connect: state=$state requestId=$currentRequestId")
        } catch (t: Throwable) {
            btErr("sendStateSnapshot failed: ${t.message}")
        }
        // Reset audio state so signalAudioStart always triggers on reconnect
        wantAudioStream = false
        signalAudioStart("BT connect")
        // No periodic screen-state heartbeat: it would defeat bt-manager's RFCOMM idle teardown
        // and keep apss awake. Phone receives explicit screen_off / glasses_audio_open / LISTENING /
        // IDLE events on real transitions; bt-manager active-session ref-counting (G2) keeps the
        // socket pinned during real work, kernel RFCOMM detects dead peers in ~30-60s.
        // Emit HELLO so phone sees our current sync-state hash
        try { syncChannelHandler?.onBtConnected() } catch (e: Exception) { btErr("sync onBtConnected failed: ${e.message}") }
        // Seed phone with current wear state so it can gate its own pre-duck.
        audioRouting?.state?.let { ws ->
            val worn = ws == WearState.ON_HEAD || ws == WearState.TRANSITIONING_ON
            try { btClient.sendWearState(worn) } catch (t: Throwable) {
                btErr("initial sendWearState failed: ${t.message}")
            }
        }
        // Seed phone with current screen state so the map streamer knows
        // whether the HUD is visible.
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            btClient.sendScreenState(pm.isInteractive)
        } catch (t: Throwable) {
            btErr("initial sendScreenState failed: ${t.message}")
        }
        // Lone mode: (re)assert the companion phone's MAC as a trusted peer so our own
        // pair never counts as a foreign device. No-op if lone mode is inactive.
        loneController?.setTrustedPeer(btClient.companionAddress)
    }

    override fun onDisconnected() {
        wantAudioStream = false
        stopGlassesAudioStream("BT disconnect")

        // A reply waiting on the phone's result can never be confirmed once BT
        // drops: disarm the timeout and stop awaiting so a late result/timeout
        // can't fire into a torn-down or future reply.
        notifReplyAwaitingResult = null
        notifHandler.removeCallbacks(replyResultTimeoutRunnable)

        // Stop translation recorders so they don't keep sending into a dead
        // RFCOMM channel. On reconnect the phone re-sends start_translation
        // which creates fresh recorder instances.
        stopFrontMicForTranslation()
        BeamformController.setScene(BeamformController.SCENE_IDLE)

        btLog("BT disconnected from companion phone")
        broadcastBtState(false)
        broadcastState("IDLE")

        // Clear music tab: phone is the source of truth for now-playing, so when
        // BT drops there is no current track. Emit an empty ACTION_MEDIA_STATE.
        try {
            sendBroadcast(Intent(ACTION_MEDIA_STATE).apply {
                setPackage(packageName)
                putExtra(EXTRA_MEDIA_TRACK, "")
                putExtra(EXTRA_MEDIA_PLAYING, false)
            })
        } catch (e: Exception) {
            btErr("clear now-playing broadcast failed: ${e.message}")
        }

        // Clear navigation state: phone is the source of truth for journey data.
        // Without this, a stale minimap frame persists indefinitely after disconnect.
        try {
            com.repository.glasses.listener.NavStepState.reset()
            sendBroadcast(Intent(ACTION_MAP_MINIMAP).apply {
                setPackage(packageName)
                putExtra("visible", false)
            })
        } catch (e: Exception) {
            btErr("clear navigation state on disconnect failed: ${e.message}")
        }
    }

    override fun onResponse(requestId: String, text: String, status: String, tokenCount: Int) {
        btLog("Response ($requestId) status=$status tokens=$streamingDelivered: ${text.take(100)}")

        if (requestId == cancelledRequestId) {
            btLog("Response rejected: request $requestId was cancelled")
            return
        }

        ensureActivityRunning()

        // Only add ASSISTANT message if streaming didn't already deliver it
        if (!streamingDelivered) {
            broadcastChatMessage(requestId, "ASSISTANT", text)
        }

        // Broadcast metadata to attach to the existing ASSISTANT message
        if (audioSentTimestamp > 0) {
            val responseTimeMs = SystemClock.elapsedRealtime() - audioSentTimestamp
            audioSentTimestamp = 0
            broadcastResponseMeta(requestId, responseTimeMs, tokenCount)
        }
    }

    override fun onTtsAudio(requestId: String, audioBase64: String, sentenceIndex: Int, totalSentences: Int, text: String, isFinal: Boolean) {
        btLog("TTS audio received: req=$requestId sentence=$sentenceIndex/$totalSentences final=$isFinal state=$state text='${text.take(50)}'")
        lastTtsReceivedTime = SystemClock.elapsedRealtime()

        // Reject TTS for cancelled requests (prevents re-entering RESPONDING after cancel)
        if (requestId == cancelledRequestId) {
            btLog("TTS rejected: request $requestId was cancelled")
            return
        }

        // Force RESPONDING state if needed
        if (state != State.RESPONDING) {
            currentRequestId = requestId
            transitionState(State.RESPONDING, "TTS audio arrived")
            broadcastState(state.name)
        }

        if (callController.scoActive) {
            btLog("TTS suppressed: HFP session active (scoActive=true) req=$requestId")
            return
        }

        try {
            ttsPlayer.enqueue(requestId, audioBase64, isFinal)
            btLog("TTS audio enqueued successfully")
        } catch (e: Exception) {
            btLog("TTS enqueue failed: ${e.message}")
        }
    }

    override fun onCommand(type: String, requestId: String, paramsJson: String) {
        btLog("Command received: type=$type requestId=$requestId params=${paramsJson.take(100)}")
        val params = try { JSONObject(paramsJson) } catch (_: Exception) { JSONObject() }

        when (type) {
            "lone_start" -> {
                val trustedArr = params.optJSONArray("trusted")
                val trusted = mutableListOf<String>()
                if (trustedArr != null) for (i in 0 until trustedArr.length()) {
                    trustedArr.optString(i).takeIf { it.isNotBlank() }?.let { trusted.add(it) }
                }
                val glassesMac = params.optString("glasses_mac").ifBlank { null }
                val namesArr = params.optJSONArray("trusted_names")
                val pairNames = mutableListOf<String>()
                if (namesArr != null) for (i in 0 until namesArr.length()) {
                    namesArr.optString(i).takeIf { it.isNotBlank() }?.let { pairNames.add(it) }
                }
                // Our own BT name covers the case where the glasses are discovered by their own
                // rotating BLE MAC via the phone's scan feed.
                try { android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.name?.let { pairNames.add(it) } } catch (_: Exception) {}
                val ctrl = ensureLoneController()
                ctrl.start(trusted, glassesMac, pairNames)
                btClient.companionAddress?.let { ctrl.setTrustedPeer(it) }
                // The glasses' own paired/bonded devices are trusted by default too.
                try {
                    android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.forEach {
                        ctrl.setTrustedPeer(it.address)
                    }
                } catch (_: Exception) {}
            }
            "lone_stop" -> {
                loneController?.stop()
            }
            "lone_trust_update" -> {
                val addr = params.optString("address")
                val trusted = params.optBoolean("trusted", false)
                if (addr.isNotBlank()) ensureLoneController().onTrustUpdate(addr, trusted)
            }
            "lone_devices" -> {
                ensureLoneController().onPhoneDevices(paramsJson)
            }
            "capture_image", "take_photo" -> {
                requestPhotoFile { file ->
                    if (file == null || !file.exists()) {
                        btClient.sendCommandResult(requestId, JSONObject().apply {
                            put("text", "Camera capture failed")
                            put("success", false)
                        }.toString())
                        return@requestPhotoFile
                    }
                    val bytes = try { file.readBytes() } catch (t: Throwable) {
                        btErr("take_photo: read file failed: ${t.message}"); null
                    }
                    val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    if (bytes == null || bitmap == null) {
                        btClient.sendCommandResult(requestId, JSONObject().apply {
                            put("text", "Camera capture failed: decode error")
                            put("success", false)
                        }.toString())
                        return@requestPhotoFile
                    }
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    val thumbB64 = generateThumbnailBase64(bitmap)
                    lastPhotoThumbBase64 = thumbB64
                    lastPhotoThumbTimestamp = SystemClock.elapsedRealtime()
                    broadcastToolThumbnail(requestId, thumbB64, "take_photo")
                    val resized = if (maxOf(bitmap.width, bitmap.height) > 1920) {
                        btLog("take_photo: resizing ${bitmap.width}x${bitmap.height} -> 1920 max")
                        resizeImageForBt(bitmap, maxDim = 1920, quality = 85)
                    } else { base64 }
                    bitmap.recycle()
                    btClient.sendCommandResult(requestId, JSONObject().apply {
                        put("imageBase64", resized)
                        put("success", true)
                    }.toString())
                }
            }
            "identify_capture" -> {
                // Use orchestrator requestId for thumbnail (matches TOOL message in chat)
                val thumbRequestId = params.optString("orchestratorRequestId", requestId)
                val recentBase64 = getRecentDcimPhoto(5 * 60 * 1000L)
                if (recentBase64 != null) {
                    btLog("identify_capture: using recent DCIM photo, extracting face")
                    extractFaceFromImage(recentBase64) { faceCrop, thumb ->
                        if (faceCrop != null) {
                            if (thumb != null) broadcastToolThumbnail(thumbRequestId, thumb, "recent_photo")
                            btClient.sendCommandResult(requestId, JSONObject().apply {
                                put("imageBase64", faceCrop)
                                put("source", "recent_photo")
                                put("success", true)
                            }.toString())
                        } else {
                            btClient.sendCommandResult(requestId, JSONObject().apply {
                                put("text", "No face detected in photo")
                                put("success", false)
                            }.toString())
                        }
                    }
                } else {
                    btLog("identify_capture: no recent photo, capturing live")
                    requestPhotoFile { file ->
                        if (file == null || !file.exists()) {
                            btClient.sendCommandResult(requestId, JSONObject().apply {
                                put("text", "Camera capture failed")
                                put("success", false)
                            }.toString())
                            return@requestPhotoFile
                        }
                        val base64 = try {
                            Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                        } catch (t: Throwable) {
                            btErr("identify_capture: read file failed: ${t.message}")
                            btClient.sendCommandResult(requestId, JSONObject().apply {
                                put("text", "Camera capture failed: read error")
                                put("success", false)
                            }.toString())
                            return@requestPhotoFile
                        }
                        extractFaceFromImage(base64) { faceCrop, thumb ->
                            if (faceCrop != null) {
                                if (thumb != null) broadcastToolThumbnail(thumbRequestId, thumb, "live_capture")
                                btClient.sendCommandResult(requestId, JSONObject().apply {
                                    put("imageBase64", faceCrop)
                                    put("source", "live_capture")
                                    put("success", true)
                                }.toString())
                            } else {
                                btClient.sendCommandResult(requestId, JSONObject().apply {
                                    put("text", "No face detected in captured photo")
                                    put("success", false)
                                }.toString())
                            }
                        }
                    }
                }
            }
            "record_audio" -> {
                val duration = params.optInt("duration_seconds", 10)
                glassesAudioRecorder.record(duration) { filePath ->
                    btClient.sendCommandResult(requestId, JSONObject().apply {
                        put("file_path", filePath ?: "")
                        put("duration_ms", duration * 1000)
                        put("format", "wav")
                        put("success", filePath != null)
                    }.toString())
                }
            }
            "record_video" -> {
                val duration = params.optInt("duration_seconds", 10)
                val manualStop = params.optBoolean("manual_stop", false)
                val orientation = params.optString("orientation", "portrait").lowercase()
                activeRecordingOrientation = if (orientation == "landscape") "landscape" else "portrait"
                btLog("VIDEO_REC: duration=${duration}s manual=$manualStop orientation=$activeRecordingOrientation requestId=$requestId")

                val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                // Plain video record output -> DCIM/Repository/ so it
                // appears in the phone's filesync catalogue.
                val camFile = java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DCIM),
                    "Repository/vid_${ts}.mp4"
                )
                camFile.parentFile?.mkdirs()
                if (arVideoRecorder == null) {
                    arVideoRecorder = com.repository.glasses.listener.capture.ArVideoRecorder(this)
                    arVideoRecorder!!.remoteLog = { btLog(it) }
                }
                // Start audio capture from mic ch1 alongside video
                val audioFile = java.io.File(camFile.parent, camFile.nameWithoutExtension + "_audio.wav")
                videoAudioMixEcho = false
                startVideoAudioCapture(audioFile)

                btLog("VIDEO_REC: starting camera recorder -> ${camFile.name}")
                // Disable camera LED auto-fire BEFORE openCamera (the property
                // is read by cameraserver during open). Restored to default
                // in the stop callback.
                setCameraLedEnabled(false)
                arVideoRecorder!!.start(camFile, activeRecordingOrientation) { camOk ->
                    btLog("VIDEO_REC: camera started=$camOk")
                    if (camOk) {
                        broadcastRecordingState(RECORDING_STATE_ACTIVE)
                    } else {
                        setCameraLedEnabled(true)
                        btClient.sendCommandResult(requestId, JSONObject().apply {
                            put("success", false)
                            put("error", "Camera recording failed to start")
                        }.toString())
                    }
                }

                activeRecordingRequestId = requestId
                activeRecordingCameraType = "video_record"

                if (manualStop || duration <= 0) {
                    btLog("VIDEO_REC: manual stop mode -- waiting for stop_recording")
                } else {
                    btLog("VIDEO_REC: scheduling auto-stop in ${duration}s")
                    scheduleAutoStop(requestId, "video_record", duration)
                }
            }
            "capture_raw" -> {
                val numFrames = params.optInt("num_frames", 5)
                val exposureMs = params.optLong("exposure_ms", 0)
                val boost = params.optInt("boost", 0)
                val exposureNs = if (exposureMs > 0) exposureMs * 1_000_000 else 0L
                btLog("RAW_CAPTURE: frames=$numFrames exposure=${exposureMs}ms boost=$boost")

                rawFrameCapturer.capture(numFrames, exposureNs, boost) { success, paths, error ->
                    btClient.sendCommandResult(requestId, JSONObject().apply {
                        put("success", success)
                        put("num_saved", paths.size)
                        put("paths", org.json.JSONArray(paths))
                        if (error != null) put("error", error)
                    }.toString())
                }
            }
            "clear_raw" -> {
                val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "nightvision_raw")
                var deleted = 0
                if (dir.exists()) {
                    dir.listFiles()?.forEach { it.delete(); deleted++ }
                }
                btLog("CLEAR_RAW: deleted $deleted files from ${dir.absolutePath}")
                btClient.sendCommandResult(requestId, JSONObject().apply {
                    put("success", true)
                    put("deleted", deleted)
                }.toString())
            }
            "record_ar_screen" -> {
                val duration = params.optInt("duration_seconds", 10)
                val manualStop = params.optBoolean("manual_stop", false)
                val orientation = params.optString("orientation", "portrait").lowercase()
                activeRecordingOrientation = if (orientation == "landscape") "landscape" else "portrait"
                btLog("=== AR RECORDING START (v7: ArVideoRecorder + ViewRecorder) ===")
                btLog("AR[1] params: duration=${duration}s manual=$manualStop orientation=$activeRecordingOrientation requestId=$requestId")

                val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(java.util.Date())

                // Step 1: Start off-screen camera recording
                val camFile = java.io.File(
                    android.os.Environment.getExternalStorageDirectory(),
                    "ScreenRecorder/cam_${ts}.mp4"
                )
                if (arVideoRecorder == null) {
                    arVideoRecorder = com.repository.glasses.listener.capture.ArVideoRecorder(this)
                    arVideoRecorder!!.remoteLog = { btLog(it) }
                }
                activeArVideoFile = camFile
                // AR audio: mic ch1 + averaged ch5/6/7 acoustic echo of speaker
                // playback. CAPTURE_AUDIO_OUTPUT (REMOTE_SUBMIX) is gated by
                // role-managed permission on this OS and not grantable via
                // privapp-permissions alone, so the clean digital tap isn't
                // available.
                val arAudioFile = java.io.File(camFile.parent, camFile.nameWithoutExtension + "_audio.wav")
                videoAudioMixEcho = true
                startVideoAudioCapture(arAudioFile)

                btLog("AR[2] starting camera recorder -> ${camFile.name}")
                // Disable camera LED for phone-driven AR recording.
                setCameraLedEnabled(false)
                arVideoRecorder!!.start(camFile, activeRecordingOrientation) { camOk ->
                    btLog("AR[3] camera started=$camOk")
                    if (camOk) broadcastRecordingState(RECORDING_STATE_ACTIVE)
                    else setCameraLedEnabled(true)
                }

                // Step 2: Start UI recording via Activity's ViewRecorder
                val screenFile = java.io.File(
                    android.os.Environment.getExternalStorageDirectory(),
                    "ScreenRecorder/screen_${ts}.mp4"
                )
                screenFile.parentFile?.mkdirs()
                activeScreenRecordFile = screenFile
                ensureActivityRunning()
                Handler(Looper.getMainLooper()).postDelayed({
                    btLog("AR[4] requesting UI recording -> ${screenFile.name}")
                    sendBroadcast(Intent(ACTION_UI_RECORD).apply {
                        setPackage(packageName)
                        putExtra("action", "start")
                        putExtra("output", screenFile.absolutePath)
                        putExtra("duration", duration)
                    })
                }, 1500)

                activeRecordingRequestId = requestId
                activeRecordingCameraType = "ar_offscreen"
                btLog("AR[5] activeRecording set")

                if (manualStop || duration <= 0) {
                    btLog("AR[6] manual stop mode")
                } else {
                    btLog("AR[6] scheduling auto-stop in ${duration}s")
                    scheduleAutoStop(requestId, "ar_offscreen", duration)
                }
            }
            "list_storage" -> {
                btLog("=== LIST STORAGE ===")
                val counts = listAllMediaStorage()
                btClient.sendCommandResult(requestId, counts.toString())
                btLog("=== LIST STORAGE END ===")
            }
            "diag_ar" -> {
                btLog("=== DIAG AR START ===")
                val diagDuration = params.optInt("duration_seconds", 5)
                val diagMethod = params.optString("method", "all")
                btLog("DIAG: duration=$diagDuration method=$diagMethod")
                btLog("DIAG: rokidBridge.isBound=${rokidBridge.isBound}")

                // 1. List storage before
                btLog("DIAG STEP 1: Storage before")
                val before = listAllMediaStorage()
                btLog("DIAG: counts_before=$before")

                // 2. Try scene control
                if (diagMethod == "all" || diagMethod == "scene") {
                    btLog("DIAG STEP 2a: Testing scene control mix_record=true")
                    try {
                        rokidBridge.sendSceneControl("mix_record", true)
                        btLog("DIAG: sendSceneControl OK")
                    } catch (e: Exception) {
                        btErr("DIAG: sendSceneControl FAILED: ${e.message}")
                    }
                    Handler(Looper.getMainLooper()).postDelayed({
                        btLog("DIAG STEP 2b: scene control mix_record=false (after ${diagDuration}s)")
                        try {
                            rokidBridge.sendSceneControl("mix_record", false)
                            btLog("DIAG: sendSceneControl stop OK")
                        } catch (e: Exception) {
                            btErr("DIAG: sendSceneControl stop FAILED: ${e.message}")
                        }

                        // Check storage after scene control
                        Handler(Looper.getMainLooper()).postDelayed({
                            btLog("DIAG STEP 2c: Storage after scene control (+3s)")
                            val afterScene = listAllMediaStorage()
                            btLog("DIAG: counts_after_scene=$afterScene")

                            // 3. Try AIDL
                            if (diagMethod == "all" || diagMethod == "aidl") {
                                btLog("DIAG STEP 3a: Testing AIDL video_with_ui")
                                try {
                                    rokidBridge.startVideoRecord("video_with_ui")
                                    btLog("DIAG: startVideoRecord OK")
                                } catch (e: Exception) {
                                    btErr("DIAG: startVideoRecord FAILED: ${e.message}")
                                }
                                Handler(Looper.getMainLooper()).postDelayed({
                                    btLog("DIAG STEP 3b: AIDL stop (after ${diagDuration}s)")
                                    try {
                                        rokidBridge.stopVideoRecord("video_with_ui")
                                        btLog("DIAG: stopVideoRecord OK")
                                    } catch (e: Exception) {
                                        btErr("DIAG: stopVideoRecord FAILED: ${e.message}")
                                    }

                                    Handler(Looper.getMainLooper()).postDelayed({
                                        btLog("DIAG STEP 3c: Storage after AIDL (+3s)")
                                        val afterAidl = listAllMediaStorage()
                                        btLog("DIAG: counts_after_aidl=$afterAidl")

                                        // 4. Try SCREENRECORD_ON broadcast
                                        if (diagMethod == "all" || diagMethod == "broadcast") {
                                            btLog("DIAG STEP 4a: Testing SCREENRECORD_ON broadcast")
                                            try {
                                                val screenIntent = Intent("com.rokid.yodaos.action.SCREENRECORD_ON").apply {
                                                    component = android.content.ComponentName(
                                                        "com.rokid.os.master.screenstream",
                                                        "com.rokid.os.master.screenstream.receiver.ScreenRecordReceiver"
                                                    )
                                                    putExtra("FileName", "diag_screen_${System.currentTimeMillis()}")
                                                    putExtra("MaxTime", diagDuration.toLong() * 1000L)
                                                }
                                                sendBroadcast(screenIntent)
                                                btLog("DIAG: SCREENRECORD_ON sent")
                                            } catch (e: Exception) {
                                                btErr("DIAG: SCREENRECORD_ON FAILED: ${e.message}")
                                            }

                                            Handler(Looper.getMainLooper()).postDelayed({
                                                btLog("DIAG STEP 4b: Sending SCREENRECORD_OFF (after ${diagDuration}s)")
                                                try {
                                                    val stopIntent = Intent("com.rokid.yodaos.action.SCREENRECORD_OFF").apply {
                                                        component = android.content.ComponentName(
                                                            "com.rokid.os.master.screenstream",
                                                            "com.rokid.os.master.screenstream.receiver.ScreenRecordReceiver"
                                                        )
                                                        putExtra("DeleteFile", false)
                                                    }
                                                    sendBroadcast(stopIntent)
                                                    btLog("DIAG: SCREENRECORD_OFF sent")
                                                } catch (e: Exception) {
                                                    btErr("DIAG: SCREENRECORD_OFF FAILED: ${e.message}")
                                                }

                                                Handler(Looper.getMainLooper()).postDelayed({
                                                    btLog("DIAG STEP 4c: Storage after broadcast (+3s)")
                                                    val afterBcast = listAllMediaStorage()
                                                    btLog("DIAG: counts_after_broadcast=$afterBcast")
                                                    btLog("=== DIAG AR COMPLETE ===")
                                                    btClient.sendCommandResult(requestId, JSONObject().apply {
                                                        put("before", before)
                                                        put("after_scene", afterScene)
                                                        put("after_aidl", afterAidl)
                                                        put("after_broadcast", afterBcast)
                                                    }.toString())
                                                }, 3000)
                                            }, diagDuration * 1000L)
                                        } else {
                                            btLog("=== DIAG AR COMPLETE ===")
                                            btClient.sendCommandResult(requestId, JSONObject().apply {
                                                put("before", before)
                                                put("after_scene", afterScene)
                                                put("after_aidl", afterAidl)
                                            }.toString())
                                        }
                                    }, 3000)
                                }, diagDuration * 1000L)
                            } else {
                                btLog("=== DIAG AR COMPLETE ===")
                                btClient.sendCommandResult(requestId, JSONObject().apply {
                                    put("before", before)
                                    put("after_scene", afterScene)
                                }.toString())
                            }
                        }, 3000)
                    }, diagDuration * 1000L)
                } else if (diagMethod == "aidl" || diagMethod == "broadcast") {
                    // Skip scene, go directly to aidl or broadcast
                    btLog("DIAG: Skipping scene control, testing $diagMethod")
                    if (diagMethod == "aidl") {
                        btLog("DIAG STEP 2a: Testing AIDL video_with_ui")
                        try {
                            rokidBridge.startVideoRecord("video_with_ui")
                            btLog("DIAG: startVideoRecord OK")
                        } catch (e: Exception) {
                            btErr("DIAG: startVideoRecord FAILED: ${e.message}")
                        }
                        Handler(Looper.getMainLooper()).postDelayed({
                            btLog("DIAG STEP 2b: AIDL stop")
                            try {
                                rokidBridge.stopVideoRecord("video_with_ui")
                                btLog("DIAG: stopVideoRecord OK")
                            } catch (e: Exception) {
                                btErr("DIAG: stopVideoRecord FAILED: ${e.message}")
                            }
                            Handler(Looper.getMainLooper()).postDelayed({
                                btLog("DIAG STEP 2c: Storage after AIDL (+3s)")
                                val afterAidl = listAllMediaStorage()
                                btLog("DIAG: counts_after=$afterAidl")
                                btLog("=== DIAG AR COMPLETE ===")
                                btClient.sendCommandResult(requestId, JSONObject().apply {
                                    put("before", before)
                                    put("after_aidl", afterAidl)
                                }.toString())
                            }, 3000)
                        }, diagDuration * 1000L)
                    } else {
                        btLog("DIAG STEP 2a: Testing SCREENRECORD_ON broadcast")
                        try {
                            val screenIntent = Intent("com.rokid.yodaos.action.SCREENRECORD_ON").apply {
                                component = android.content.ComponentName(
                                    "com.rokid.os.master.screenstream",
                                    "com.rokid.os.master.screenstream.receiver.ScreenRecordReceiver"
                                )
                                putExtra("FileName", "diag_bcast_${System.currentTimeMillis()}")
                                putExtra("MaxTime", diagDuration.toLong() * 1000L)
                            }
                            sendBroadcast(screenIntent)
                            btLog("DIAG: SCREENRECORD_ON sent")
                        } catch (e: Exception) {
                            btErr("DIAG: SCREENRECORD_ON FAILED: ${e.message}")
                        }
                        Handler(Looper.getMainLooper()).postDelayed({
                            btLog("DIAG STEP 2b: SCREENRECORD_OFF")
                            try {
                                val stopIntent = Intent("com.rokid.yodaos.action.SCREENRECORD_OFF").apply {
                                    component = android.content.ComponentName(
                                        "com.rokid.os.master.screenstream",
                                        "com.rokid.os.master.screenstream.receiver.ScreenRecordReceiver"
                                    )
                                    putExtra("DeleteFile", false)
                                }
                                sendBroadcast(stopIntent)
                                btLog("DIAG: SCREENRECORD_OFF sent")
                            } catch (e: Exception) {
                                btErr("DIAG: SCREENRECORD_OFF FAILED: ${e.message}")
                            }
                            Handler(Looper.getMainLooper()).postDelayed({
                                btLog("DIAG STEP 2c: Storage after broadcast (+3s)")
                                val afterBcast = listAllMediaStorage()
                                btLog("DIAG: counts_after=$afterBcast")
                                btLog("=== DIAG AR COMPLETE ===")
                                btClient.sendCommandResult(requestId, JSONObject().apply {
                                    put("before", before)
                                    put("after_broadcast", afterBcast)
                                }.toString())
                            }, 3000)
                        }, diagDuration * 1000L)
                    }
                }
            }
            "diag_screen_record" -> {
                btLog("=== DIAG SCREEN RECORD START ===")
                val diagDur = params.optInt("duration_seconds", 5)

                // 1. Check if package is installed
                btLog("DSR[1] checking com.rokid.os.master.screenstream package...")
                var pkgInstalled = false
                try {
                    val pi = packageManager.getPackageInfo("com.rokid.os.master.screenstream", 0)
                    pkgInstalled = true
                    btLog("DSR[1] INSTALLED: version=${pi.versionName} versionCode=${pi.longVersionCode}")
                } catch (e: Exception) {
                    btErr("DSR[1] NOT INSTALLED: ${e.message}")
                }

                // 2. Check free disk space
                btLog("DSR[2] checking free disk space...")
                var freeMb = 0L
                try {
                    val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
                    freeMb = stat.availableBytes / 1024 / 1024
                    btLog("DSR[2] free space: ${freeMb}MB (need >400MB for recording)")
                } catch (e: Exception) {
                    btErr("DSR[2] StatFs failed: ${e.message}")
                }

                // 3. Check BOTH Settings.Global keys for screen record state
                btLog("DSR[3] checking system state...")
                try {
                    val key1 = Settings.Global.getInt(contentResolver, "rokid_yodaos_screen_record", -1)
                    val key2 = Settings.Global.getInt(contentResolver, "rokid_os_in_screen_record", -1)
                    btLog("DSR[3] rokid_yodaos_screen_record=$key1 rokid_os_in_screen_record=$key2")
                } catch (e: Exception) {
                    btErr("DSR[3] Settings.Global read failed: ${e.message}")
                }

                // 4. List ScreenRecorder directory
                btLog("DSR[4] listing /storage/emulated/0/ScreenRecorder/...")
                val screenRecDir = File(Environment.getExternalStorageDirectory(), "ScreenRecorder")
                if (screenRecDir.exists()) {
                    val files = screenRecDir.listFiles()
                    btLog("DSR[4] dir exists, ${files?.size ?: 0} files:")
                    files?.sortedByDescending { it.lastModified() }?.take(5)?.forEach { f ->
                        btLog("DSR[4]   ${f.name} size=${f.length()} modified=${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(f.lastModified()))}")
                    }
                } else {
                    btLog("DSR[4] /storage/emulated/0/ScreenRecorder/ does NOT exist")
                }

                // 5. Force-reset: send SCREENRECORD_OFF first to clear any stuck engine state
                btLog("DSR[5] sending SCREENRECORD_OFF to reset any stuck engine state...")
                try {
                    val resetIntent = Intent("com.rokid.yodaos.action.SCREENRECORD_OFF").apply {
                        component = ComponentName(
                            "com.rokid.os.master.screenstream",
                            "com.rokid.os.master.screenstream.receiver.ScreenRecordReceiver"
                        )
                        putExtra("DeleteFile", true)
                        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    }
                    sendBroadcast(resetIntent)
                    btLog("DSR[5] reset SCREENRECORD_OFF sent OK")
                } catch (e: Exception) {
                    btErr("DSR[5] reset FAILED: ${e.message}")
                }

                // 6. Wait 2s for reset, then try SCREENRECORD_ON with explicit + implicit
                Handler(Looper.getMainLooper()).postDelayed({
                    // Re-check engine state after reset
                    try {
                        val key1 = Settings.Global.getInt(contentResolver, "rokid_yodaos_screen_record", -1)
                        btLog("DSR[6] after reset: rokid_yodaos_screen_record=$key1")
                    } catch (_: Exception) {}

                    val testFileName = "diag_sr_${System.currentTimeMillis()}.mp4"
                    val testMaxTimeMs = diagDur * 1000L

                    // Try A: explicit component broadcast
                    btLog("DSR[7a] EXPLICIT broadcast SCREENRECORD_ON: fileName=$testFileName maxTimeMs=$testMaxTimeMs")
                    try {
                        val screenIntent = Intent("com.rokid.yodaos.action.SCREENRECORD_ON").apply {
                            component = ComponentName(
                                "com.rokid.os.master.screenstream",
                                "com.rokid.os.master.screenstream.receiver.ScreenRecordReceiver"
                            )
                            putExtra("FileName", testFileName)
                            putExtra("MaxTime", testMaxTimeMs)
                            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                        }
                        sendBroadcast(screenIntent)
                        btLog("DSR[7a] explicit broadcast sent OK")
                    } catch (e: Exception) {
                        btErr("DSR[7a] explicit broadcast FAILED: ${e.message}")
                    }

                    // Also try B: implicit broadcast (no component)
                    btLog("DSR[7b] IMPLICIT broadcast SCREENRECORD_ON (no component)...")
                    try {
                        val implicitIntent = Intent("com.rokid.yodaos.action.SCREENRECORD_ON").apply {
                            putExtra("FileName", testFileName)
                            putExtra("MaxTime", testMaxTimeMs)
                            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                        }
                        sendBroadcast(implicitIntent)
                        btLog("DSR[7b] implicit broadcast sent OK")
                    } catch (e: Exception) {
                        btErr("DSR[7b] implicit broadcast FAILED: ${e.message}")
                    }

                    // Check Settings.Global after sending ON
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            val key1 = Settings.Global.getInt(contentResolver, "rokid_yodaos_screen_record", -1)
                            btLog("DSR[8] 2s after ON: rokid_yodaos_screen_record=$key1 (should be 1 if recording)")
                        } catch (_: Exception) {}

                        val testFile = File(screenRecDir, testFileName)
                        val tmpFile = File(screenRecDir, "ScreenRecord.tmp")
                        btLog("DSR[8] testFile exists=${testFile.exists()} size=${if (testFile.exists()) testFile.length() else -1}")
                        btLog("DSR[8] tmpFile exists=${tmpFile.exists()} size=${if (tmpFile.exists()) tmpFile.length() else -1}")
                        if (screenRecDir.exists()) {
                            val nowFiles = screenRecDir.listFiles()
                            btLog("DSR[8] dir now has ${nowFiles?.size ?: 0} files")
                            nowFiles?.filter { it.lastModified() > System.currentTimeMillis() - 15000 }?.forEach { f ->
                                btLog("DSR[8] RECENT: ${f.name} size=${f.length()}")
                            }
                        }
                    }, 2000)

                    // Wait for recording duration, then send SCREENRECORD_OFF
                    Handler(Looper.getMainLooper()).postDelayed({
                        btLog("DSR[9] sending SCREENRECORD_OFF after ${diagDur}s...")
                        try {
                            val stopIntent = Intent("com.rokid.yodaos.action.SCREENRECORD_OFF").apply {
                                component = ComponentName(
                                    "com.rokid.os.master.screenstream",
                                    "com.rokid.os.master.screenstream.receiver.ScreenRecordReceiver"
                                )
                                putExtra("DeleteFile", false)
                                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                            }
                            sendBroadcast(stopIntent)
                            btLog("DSR[9] SCREENRECORD_OFF sent OK")
                        } catch (e: Exception) {
                            btErr("DSR[9] SCREENRECORD_OFF FAILED: ${e.message}")
                        }

                        // Final check 3s after stop
                        Handler(Looper.getMainLooper()).postDelayed({
                            btLog("DSR[10] 3s after stop: final check...")
                            try {
                                val key1 = Settings.Global.getInt(contentResolver, "rokid_yodaos_screen_record", -1)
                                btLog("DSR[10] rokid_yodaos_screen_record=$key1 (should be 0 after stop)")
                            } catch (_: Exception) {}
                            val testFile = File(screenRecDir, testFileName)
                            val tmpFile = File(screenRecDir, "ScreenRecord.tmp")
                            btLog("DSR[10] testFile exists=${testFile.exists()} size=${if (testFile.exists()) testFile.length() else -1}")
                            btLog("DSR[10] tmpFile exists=${tmpFile.exists()}")
                            if (screenRecDir.exists()) {
                                val allFiles = screenRecDir.listFiles()
                                btLog("DSR[10] dir has ${allFiles?.size ?: 0} files total:")
                                allFiles?.sortedByDescending { it.lastModified() }?.take(5)?.forEach { f ->
                                    btLog("DSR[10]   ${f.name} size=${f.length()} modified=${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(f.lastModified()))}")
                                }
                            }
                            btLog("=== DIAG SCREEN RECORD COMPLETE ===")
                            btClient.sendCommandResult(requestId, JSONObject().apply {
                                put("package_installed", pkgInstalled)
                                put("free_space_mb", freeMb)
                                put("test_file", testFileName)
                                put("test_file_exists", testFile.exists())
                                put("test_file_size", if (testFile.exists()) testFile.length() else 0)
                            }.toString())
                        }, 3000)
                    }, diagDur * 1000L)
                }, 2000)
            }
            "diag_screen_record_v2" -> {
                btLog("=== DSR2 START ===")
                val diagDur = params.optInt("duration_seconds", 5)

                // 1. Check if ScreenRecord process is running
                btLog("DSR2[1] checking ScreenRecord app process...")
                try {
                    val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ps -A | grep screenstream"))
                    val psOut = proc.inputStream.bufferedReader().readText().trim()
                    proc.waitFor()
                    if (psOut.isNotEmpty()) {
                        btLog("DSR2[1] process RUNNING: $psOut")
                    } else {
                        btLog("DSR2[1] process NOT RUNNING")
                    }
                } catch (e: Exception) {
                    btErr("DSR2[1] ps failed: ${e.message}")
                }

                // 2. Check SELinux mode
                btLog("DSR2[2] checking SELinux...")
                try {
                    val proc = Runtime.getRuntime().exec(arrayOf("getenforce"))
                    val seOut = proc.inputStream.bufferedReader().readText().trim()
                    proc.waitFor()
                    btLog("DSR2[2] SELinux=$seOut")
                } catch (e: Exception) {
                    btErr("DSR2[2] getenforce failed: ${e.message}")
                }

                // 3. Check our own UID and the ScreenRecord app UID
                btLog("DSR2[3] checking UIDs...")
                try {
                    val myUid = android.os.Process.myUid()
                    btLog("DSR2[3] our UID=$myUid (${applicationInfo.processName})")
                    val srPkg = packageManager.getPackageInfo("com.rokid.os.master.screenstream", 0)
                    val srUid = srPkg.applicationInfo?.uid ?: -1
                    btLog("DSR2[3] ScreenRecord UID=$srUid")
                } catch (e: Exception) {
                    btErr("DSR2[3] UID check failed: ${e.message}")
                }

                // 4. Try Runtime.exec("am broadcast") to send SCREENRECORD_ON
                btLog("DSR2[4] trying am broadcast via Runtime.exec...")
                val testFileName = "diag_sr2_${System.currentTimeMillis()}.mp4"
                val testMaxTimeMs = diagDur * 1000L
                try {
                    val cmd = arrayOf("sh", "-c",
                        "am broadcast -a com.rokid.yodaos.action.SCREENRECORD_ON " +
                        "-n com.rokid.os.master.screenstream/.receiver.ScreenRecordReceiver " +
                        "--es FileName $testFileName --el MaxTime $testMaxTimeMs")
                    btLog("DSR2[4] cmd: ${cmd[2]}")
                    val proc = Runtime.getRuntime().exec(cmd)
                    val amOut = proc.inputStream.bufferedReader().readText().trim()
                    val amErr = proc.errorStream.bufferedReader().readText().trim()
                    proc.waitFor()
                    btLog("DSR2[4] am stdout: $amOut")
                    if (amErr.isNotEmpty()) btLog("DSR2[4] am stderr: $amErr")
                    btLog("DSR2[4] am exit: ${proc.exitValue()}")
                } catch (e: Exception) {
                    btErr("DSR2[4] am broadcast failed: ${e.message}")
                }

                // 5. Also send via sendBroadcast API for comparison
                btLog("DSR2[5] sending via sendBroadcast API...")
                try {
                    val screenIntent = Intent("com.rokid.yodaos.action.SCREENRECORD_ON").apply {
                        component = ComponentName(
                            "com.rokid.os.master.screenstream",
                            "com.rokid.os.master.screenstream.receiver.ScreenRecordReceiver"
                        )
                        putExtra("FileName", testFileName)
                        putExtra("MaxTime", testMaxTimeMs)
                        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    }
                    sendBroadcast(screenIntent)
                    btLog("DSR2[5] sendBroadcast OK")
                } catch (e: Exception) {
                    btErr("DSR2[5] sendBroadcast failed: ${e.message}")
                }

                // 6. Check after 3s
                Handler(Looper.getMainLooper()).postDelayed({
                    btLog("DSR2[6] 3s after sends: checking...")
                    try {
                        val key1 = Settings.Global.getInt(contentResolver, "rokid_yodaos_screen_record", -1)
                        btLog("DSR2[6] rokid_yodaos_screen_record=$key1")
                    } catch (_: Exception) {}

                    // Check ScreenRecord process again
                    try {
                        val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ps -A | grep screenstream"))
                        val psOut = proc.inputStream.bufferedReader().readText().trim()
                        proc.waitFor()
                        btLog("DSR2[6] ScreenRecord process: ${if (psOut.isNotEmpty()) psOut else "NOT RUNNING"}")
                    } catch (_: Exception) {}

                    val screenRecDir = File(Environment.getExternalStorageDirectory(), "ScreenRecorder")
                    val testFile = File(screenRecDir, testFileName)
                    btLog("DSR2[6] testFile exists=${testFile.exists()}")
                    if (screenRecDir.exists()) {
                        val nowFiles = screenRecDir.listFiles()
                        btLog("DSR2[6] dir has ${nowFiles?.size ?: 0} files")
                        nowFiles?.filter { it.lastModified() > System.currentTimeMillis() - 10000 }?.forEach { f ->
                            btLog("DSR2[6] RECENT: ${f.name} size=${f.length()}")
                        }
                    }

                    // Send SCREENRECORD_OFF
                    btLog("DSR2[7] sending SCREENRECORD_OFF...")
                    try {
                        val stopIntent = Intent("com.rokid.yodaos.action.SCREENRECORD_OFF").apply {
                            component = ComponentName(
                                "com.rokid.os.master.screenstream",
                                "com.rokid.os.master.screenstream.receiver.ScreenRecordReceiver"
                            )
                            putExtra("DeleteFile", false)
                        }
                        sendBroadcast(stopIntent)
                        btLog("DSR2[7] SCREENRECORD_OFF sent OK")
                    } catch (_: Exception) {}

                    btLog("=== DSR2 COMPLETE ===")
                    btClient.sendCommandResult(requestId, JSONObject().apply {
                        put("test_file", testFileName)
                        put("test_file_exists", testFile.exists())
                    }.toString())
                }, 3000)
            }
            "diag_screenrecord_cmd" -> {
                btLog("=== DSR3 START (screenrecord shell cmd test) ===")
                val diagDur = params.optInt("duration_seconds", 5)
                val outFile = "/storage/emulated/0/ScreenRecorder/diag_cmd_${System.currentTimeMillis()}.mp4"

                // 1. Check if screenrecord binary exists
                btLog("DSR3[1] checking screenrecord binary...")
                try {
                    val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "which screenrecord && ls -la \$(which screenrecord)"))
                    val out = proc.inputStream.bufferedReader().readText().trim()
                    val err = proc.errorStream.bufferedReader().readText().trim()
                    proc.waitFor()
                    btLog("DSR3[1] which: $out")
                    if (err.isNotEmpty()) btLog("DSR3[1] err: $err")
                } catch (e: Exception) {
                    btErr("DSR3[1] failed: ${e.message}")
                }

                // 2. Check ScreenRecord app stopped/enabled state
                btLog("DSR3[2] checking app state...")
                try {
                    val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c",
                        "dumpsys package com.rokid.os.master.screenstream | grep -E 'stopped|enabled|packageFlags|flags='"))
                    val out = proc.inputStream.bufferedReader().readText().trim()
                    proc.waitFor()
                    out.lines().take(10).forEach { btLog("DSR3[2] $it") }
                } catch (e: Exception) {
                    btErr("DSR3[2] failed: ${e.message}")
                }

                // 3. Try to start screenrecord via shell
                btLog("DSR3[3] starting screenrecord cmd: output=$outFile duration=${diagDur}s...")
                var recordProc: Process? = null
                try {
                    recordProc = Runtime.getRuntime().exec(arrayOf("sh", "-c",
                        "screenrecord --time-limit $diagDur --size 1280x720 $outFile"))
                    btLog("DSR3[3] screenrecord process started, pid=${recordProc.toString()}")
                } catch (e: Exception) {
                    btErr("DSR3[3] screenrecord launch FAILED: ${e.message}")
                }

                // 4. Check process after 2s
                Handler(Looper.getMainLooper()).postDelayed({
                    btLog("DSR3[4] 2s check...")
                    if (recordProc != null) {
                        try {
                            val exitCode = recordProc.exitValue()
                            btLog("DSR3[4] process already exited: code=$exitCode")
                            val stderr = recordProc.errorStream.bufferedReader().readText().trim()
                            if (stderr.isNotEmpty()) btLog("DSR3[4] stderr: $stderr")
                        } catch (e: IllegalThreadStateException) {
                            btLog("DSR3[4] process still running (good)")
                        }
                    }
                    val f = File(outFile)
                    btLog("DSR3[4] file exists=${f.exists()} size=${if (f.exists()) f.length() else -1}")
                }, 2000)

                // 5. Final check after duration + 3s
                Handler(Looper.getMainLooper()).postDelayed({
                    btLog("DSR3[5] final check after ${diagDur + 3}s...")
                    if (recordProc != null) {
                        try {
                            val exitCode = recordProc.exitValue()
                            btLog("DSR3[5] exit code: $exitCode")
                            val stderr = recordProc.errorStream.bufferedReader().readText().trim()
                            if (stderr.isNotEmpty()) btLog("DSR3[5] stderr: $stderr")
                        } catch (e: IllegalThreadStateException) {
                            btLog("DSR3[5] process STILL running, destroying...")
                            recordProc.destroy()
                        }
                    }
                    val f = File(outFile)
                    btLog("DSR3[5] file exists=${f.exists()} size=${if (f.exists()) f.length() else -1}")
                    btLog("=== DSR3 COMPLETE ===")
                    btClient.sendCommandResult(requestId, JSONObject().apply {
                        put("output_file", outFile)
                        put("file_exists", f.exists())
                        put("file_size", if (f.exists()) f.length() else 0)
                    }.toString())
                }, (diagDur + 3) * 1000L)
            }
            "diag_mix_record_trace" -> {
                btLog("=== DSR5 START (mix_record + process polling) ===")
                val diagDur = params.optInt("duration_seconds", 10)

                // 1. Check ScreenRecord process before
                btLog("DSR5[1] before: checking ScreenRecord process...")
                try {
                    val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ps -A | grep screenstream"))
                    val psOut = proc.inputStream.bufferedReader().readText().trim()
                    proc.waitFor()
                    btLog("DSR5[1] process: ${if (psOut.isNotEmpty()) psOut else "NOT RUNNING"}")
                } catch (_: Exception) {}

                // 2. Check Settings.Global before
                try {
                    val key1 = Settings.Global.getInt(contentResolver, "rokid_yodaos_screen_record", -1)
                    btLog("DSR5[1] rokid_yodaos_screen_record=$key1")
                } catch (_: Exception) {}

                // 3. Trigger control_scene mix_record open
                btLog("DSR5[2] sending control_scene mix_record open...")
                try {
                    val intent = Intent("com.rokid.os.master.assist.server.cmd").apply {
                        putExtra("cmd_type", "control_scene")
                        putExtra("scene", "mix_record")
                        putExtra("open", "true")
                    }
                    sendBroadcast(intent)
                    btLog("DSR5[2] broadcast sent OK")
                } catch (e: Exception) {
                    btErr("DSR5[2] broadcast failed: ${e.message}")
                }

                // 4. Poll process state every 2 seconds for duration
                val pollHandler = Handler(Looper.getMainLooper())
                var pollCount = 0
                val maxPolls = diagDur / 2
                val pollRunnable = object : Runnable {
                    override fun run() {
                        pollCount++
                        val elapsed = pollCount * 2
                        Log.v(TAG_DSR, "event=dsr5_poll n=$pollCount elapsed_s=$elapsed")
                        GT.counter("svc.dsr5_poll_iterations", pollCount.toLong())
                        try {
                            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ps -A | grep screenstream"))
                            val psOut = proc.inputStream.bufferedReader().readText().trim()
                            proc.waitFor()
                            val key1 = Settings.Global.getInt(contentResolver, "rokid_yodaos_screen_record", -1)
                            btLog("DSR5[3] +${elapsed}s: process=${if (psOut.isNotEmpty()) "RUNNING" else "not_running"} screenrec_key=$key1")
                        } catch (_: Exception) {}

                        // Check ScreenRecorder dir for new files
                        val screenRecDir = File(Environment.getExternalStorageDirectory(), "ScreenRecorder")
                        if (screenRecDir.exists()) {
                            val recent = screenRecDir.listFiles()?.filter {
                                it.lastModified() > System.currentTimeMillis() - 30000
                            }
                            if (recent != null && recent.isNotEmpty()) {
                                recent.forEach { f ->
                                    btLog("DSR5[3] +${elapsed}s: RECENT FILE: ${f.name} size=${f.length()}")
                                }
                            }
                        }

                        if (pollCount < maxPolls) {
                            pollHandler.postDelayed(this, 2000)
                        } else {
                            // Done polling - close scene and report
                            btLog("DSR5[4] closing mix_record scene...")
                            try {
                                val closeIntent = Intent("com.rokid.os.master.assist.server.cmd").apply {
                                    putExtra("cmd_type", "control_scene")
                                    putExtra("scene", "mix_record")
                                    putExtra("open", "false")
                                }
                                sendBroadcast(closeIntent)
                                btLog("DSR5[4] close sent OK")
                            } catch (_: Exception) {}

                            // Final check after 3s
                            pollHandler.postDelayed({
                                btLog("DSR5[5] final check 3s after close...")
                                try {
                                    val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ps -A | grep screenstream"))
                                    val psOut = proc.inputStream.bufferedReader().readText().trim()
                                    proc.waitFor()
                                    btLog("DSR5[5] process: ${if (psOut.isNotEmpty()) psOut else "NOT RUNNING"}")
                                } catch (_: Exception) {}
                                try {
                                    val key1 = Settings.Global.getInt(contentResolver, "rokid_yodaos_screen_record", -1)
                                    btLog("DSR5[5] rokid_yodaos_screen_record=$key1")
                                } catch (_: Exception) {}
                                val screenRecDir2 = File(Environment.getExternalStorageDirectory(), "ScreenRecorder")
                                if (screenRecDir2.exists()) {
                                    val recent = screenRecDir2.listFiles()?.filter {
                                        it.lastModified() > System.currentTimeMillis() - 60000
                                    }
                                    btLog("DSR5[5] recent files in ScreenRecorder: ${recent?.size ?: 0}")
                                    recent?.forEach { f ->
                                        btLog("DSR5[5] ${f.name} size=${f.length()}")
                                    }
                                }
                                // Also check Camera dir
                                val cameraDir = File(Environment.getExternalStorageDirectory(), "Movies/Camera")
                                if (cameraDir.exists()) {
                                    val recent = cameraDir.listFiles()?.filter {
                                        it.lastModified() > System.currentTimeMillis() - 60000
                                    }
                                    btLog("DSR5[5] recent files in Camera: ${recent?.size ?: 0}")
                                    recent?.forEach { f ->
                                        btLog("DSR5[5] ${f.name} size=${f.length()}")
                                    }
                                }
                                btLog("=== DSR5 COMPLETE ===")
                                btClient.sendCommandResult(requestId, JSONObject().apply {
                                    put("polls", pollCount)
                                }.toString())
                            }, 3000)
                        }
                    }
                }
                pollHandler.postDelayed(pollRunnable, 2000)
            }
            "diag_wake_screenrecord" -> {
                btLog("=== DSR4 START (wake ScreenRecord + send broadcast) ===")
                val diagDur = params.optInt("duration_seconds", 5)

                // 1. Check FLAG_STOPPED via PackageManager API
                btLog("DSR4[1] checking FLAG_STOPPED state...")
                var isStopped = false
                try {
                    val ai = packageManager.getApplicationInfo("com.rokid.os.master.screenstream", 0)
                    isStopped = (ai.flags and android.content.pm.ApplicationInfo.FLAG_STOPPED) != 0
                    val isSystem = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    btLog("DSR4[1] FLAG_STOPPED=$isStopped FLAG_SYSTEM=$isSystem flags=0x${ai.flags.toString(16)} uid=${ai.uid}")
                } catch (e: Exception) {
                    btErr("DSR4[1] getApplicationInfo failed: ${e.message}")
                }

                // 2. Try to clear stopped state
                btLog("DSR4[2] clearing force-stopped state...")
                try {
                    // Sending an explicit intent to the receiver should clear the stopped state
                    // First try startService to wake the app
                    val wakeIntent = Intent().apply {
                        component = ComponentName(
                            "com.rokid.os.master.screenstream",
                            "com.rokid.os.master.screenstream.service.ScreenRecordService"
                        )
                    }
                    try {
                        startService(wakeIntent)
                        btLog("DSR4[2] startService OK (ScreenRecordService)")
                    } catch (e: Exception) {
                        btLog("DSR4[2] startService failed: ${e.message}")
                    }

                    // Also try startForegroundService
                    try {
                        startForegroundService(wakeIntent)
                        btLog("DSR4[2] startForegroundService OK")
                    } catch (e: Exception) {
                        btLog("DSR4[2] startForegroundService failed: ${e.message}")
                    }
                } catch (e: Exception) {
                    btErr("DSR4[2] wake attempt failed: ${e.message}")
                }

                // 3. Wait 2s then check process state
                Handler(Looper.getMainLooper()).postDelayed({
                    btLog("DSR4[3] checking process after wake attempt...")
                    try {
                        val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ps -A | grep screenstream"))
                        val psOut = proc.inputStream.bufferedReader().readText().trim()
                        proc.waitFor()
                        btLog("DSR4[3] process: ${if (psOut.isNotEmpty()) psOut else "NOT RUNNING"}")
                    } catch (_: Exception) {}

                    // 4. Now try SCREENRECORD_ON
                    val testFileName = "diag_sr4_${System.currentTimeMillis()}.mp4"
                    val testMaxTimeMs = diagDur * 1000L
                    btLog("DSR4[4] sending SCREENRECORD_ON: $testFileName, ${testMaxTimeMs}ms")
                    try {
                        val screenIntent = Intent("com.rokid.yodaos.action.SCREENRECORD_ON").apply {
                            component = ComponentName(
                                "com.rokid.os.master.screenstream",
                                "com.rokid.os.master.screenstream.receiver.ScreenRecordReceiver"
                            )
                            putExtra("FileName", testFileName)
                            putExtra("MaxTime", testMaxTimeMs)
                            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                        }
                        sendBroadcast(screenIntent)
                        btLog("DSR4[4] broadcast sent OK")
                    } catch (e: Exception) {
                        btErr("DSR4[4] broadcast failed: ${e.message}")
                    }

                    // 5. Check after 3s
                    Handler(Looper.getMainLooper()).postDelayed({
                        btLog("DSR4[5] 3s after broadcast...")
                        try {
                            val key1 = Settings.Global.getInt(contentResolver, "rokid_yodaos_screen_record", -1)
                            btLog("DSR4[5] rokid_yodaos_screen_record=$key1")
                        } catch (_: Exception) {}
                        try {
                            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ps -A | grep screenstream"))
                            val psOut = proc.inputStream.bufferedReader().readText().trim()
                            proc.waitFor()
                            btLog("DSR4[5] process: ${if (psOut.isNotEmpty()) psOut else "NOT RUNNING"}")
                        } catch (_: Exception) {}
                        val screenRecDir = File(Environment.getExternalStorageDirectory(), "ScreenRecorder")
                        val testFile = File(screenRecDir, testFileName)
                        btLog("DSR4[5] testFile exists=${testFile.exists()}")

                        // Stop
                        try {
                            val stopIntent = Intent("com.rokid.yodaos.action.SCREENRECORD_OFF").apply {
                                component = ComponentName(
                                    "com.rokid.os.master.screenstream",
                                    "com.rokid.os.master.screenstream.receiver.ScreenRecordReceiver"
                                )
                            }
                            sendBroadcast(stopIntent)
                        } catch (_: Exception) {}

                        btLog("=== DSR4 COMPLETE ===")
                        btClient.sendCommandResult(requestId, JSONObject().apply {
                            put("test_file", testFileName)
                            put("test_file_exists", testFile.exists())
                        }.toString())
                    }, 3000)
                }, 2000)
            }
            "stop_recording" -> {
                btLog("MANUAL_STOP[1] enter: requestId=$requestId")
                val recRequestId = activeRecordingRequestId
                val recCameraType = activeRecordingCameraType
                btLog("MANUAL_STOP[2] activeRecording: reqId=$recRequestId type=$recCameraType autoStop=${autoStopRunnable != null}")
                if (recRequestId == null) {
                    btLog("MANUAL_STOP[3] NO active recording -- returning error")
                    btClient.sendCommandResult(requestId, JSONObject().apply {
                        put("success", false)
                        put("error", "No active recording to stop")
                    }.toString())
                    return
                }
                // Cancel pending auto-stop if any
                if (autoStopRunnable != null) {
                    btLog("MANUAL_STOP[4] cancelling pending auto-stop runnable")
                    watchdogHandler.removeCallbacks(autoStopRunnable!!)
                    autoStopRunnable = null
                    btLog("MANUAL_STOP[5] auto-stop cancelled")
                } else {
                    btLog("MANUAL_STOP[4] no auto-stop to cancel")
                }
                btLog("MANUAL_STOP[6] calling stopRokidRecordingAndReport(req=$recRequestId type=$recCameraType)")
                stopRokidRecordingAndReport(recRequestId, recCameraType ?: "mix_record")
                btLog("MANUAL_STOP[7] stopRokidRecordingAndReport returned")
            }
            "start_translation" -> {
                btLog("Starting custom translation: front mic (8ch dual-extract)")
                BeamformController.remoteLog = { btLog(it) }
                BeamformController.init(this)
                BeamformController.setScene(BeamformController.SCENE_CARDIOID)
                // Re-broadcast the langs config alongside the state change. The
                // separate translation_config BT message can lose its race with
                // translation_state on first arm (different BT channels, different
                // delivery threads on the receiver), which left the
                // "RU -> EN" header blank in the UI. Sending the config here
                // guarantees MainActivity sees from/to before the translate tab activates.
                val from = params.optString("from_language", "")
                val to = params.optString("to_language", "")
                val fontSize = params.optInt("font_size", 14)
                val audioSource = params.optString("audio_source", "glasses")
                val provider = params.optString("provider", "default")
                val twoWay = params.optBoolean("two_way", false)
                // Persist translation config so glasses can restart translation locally
                GlassesConfig.setTranslationFromLanguage(this, from)
                GlassesConfig.setTranslationToLanguage(this, to)
                GlassesConfig.setTranslationFontSize(this, fontSize)
                GlassesConfig.setTranslationAudioSource(this, audioSource)
                GlassesConfig.setTranslationProvider(this, provider)
                GlassesConfig.setTranslationTwoWay(this, twoWay)
                // Front mic recorder extracts both front (CAE beam) and inward (ch0)
                // from the same 8ch AudioRecord -- no MicBus dependency for two-way.
                startFrontMicForTranslation(twoWay)
                ensureActivityRunning(android.os.Bundle().apply {
                    putBoolean("start_translation", true)
                })
                if (from.isNotEmpty() && to.isNotEmpty()) {
                    val cfgJson = JSONObject().apply {
                        put("fromLanguage", from)
                        put("toLanguage", to)
                        put("fontSize", fontSize)
                    }.toString()
                    sendBroadcast(Intent(ACTION_TRANSLATION_CONFIG).apply {
                        setPackage(packageName)
                        putExtra(EXTRA_TRANSLATION_CONFIG, cfgJson)
                    })
                }
                sendBroadcast(Intent(ACTION_TRANSLATION_STATE).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_TRANSLATION_ACTIVE, true)
                })
                btClient.sendCommandResult(requestId, """{"status":"started"}""")
            }
            "stop_translation" -> {
                btLog("Stopping custom translation")
                BeamformController.setScene(BeamformController.SCENE_IDLE)
                stopFrontMicForTranslation()
                sendBroadcast(Intent(ACTION_TRANSLATION_STATE).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_TRANSLATION_ACTIVE, false)
                })
                btClient.sendCommandResult(requestId, """{"status":"stopped"}""")
            }
            "start_assistant" -> {
                // Cache the last-used assistant config so a glasses-initiated start
                // can reuse it. The phone sends the fully resolved config on every
                // start (both phone-tile and glasses-initiated paths), so this is
                // always the most recent actual configuration. Fall back to the
                // current cached value per field so a missing field never wipes it.
                GlassesConfig.setAssistantWearerLang(this, params.optString("wearer_lang", GlassesConfig.getAssistantWearerLang(this)))
                GlassesConfig.setAssistantInterlocutorLang(this, params.optString("interlocutor_lang", GlassesConfig.getAssistantInterlocutorLang(this)))
                GlassesConfig.setAssistantInterlocutorSource(this, params.optString("interlocutor_source", GlassesConfig.getAssistantInterlocutorSource(this)))
                GlassesConfig.setAssistantModel(this, params.optString("model", GlassesConfig.getAssistantModel(this)))
                btLog("Starting assistant: front mic (8ch dual-extract, two-way)")
                BeamformController.remoteLog = { btLog(it) }
                BeamformController.init(this)
                BeamformController.setScene(BeamformController.SCENE_CARDIOID)
                // Assistant always uses dual-mic capture: front beam = interlocutor,
                // inward ch0 = wearer. Same recorder path as two-way translation.
                startFrontMicForTranslation(twoWay = true)
                assistantCardOverlay.hideAll()
                assistantActive = true
                btClient.sendCommandResult(requestId, """{"status":"started"}""")
            }
            "stop_assistant" -> {
                btLog("Stopping assistant")
                BeamformController.setScene(BeamformController.SCENE_IDLE)
                stopFrontMicForTranslation()
                assistantCardOverlay.hideAll()
                assistantActive = false
                btClient.sendCommandResult(requestId, """{"status":"stopped"}""")
            }
            "assistant_show_card" -> {
                val id = params.optString("id", "")
                val kind = params.optString("kind", "note")
                val heard = params.optString("heard", "")
                val note = params.optString("note", params.optString("text", ""))
                val why = params.optString("why", "")
                if (id.isNotEmpty() && note.isNotBlank()) {
                    assistantCardOverlay.showCard(id, kind, heard, note, why)
                }
                btClient.sendCommandResult(requestId, """{"status":"ok"}""")
            }
            "assistant_pending" -> {
                val id = params.optString("id", "")
                if (id.isNotEmpty()) {
                    assistantCardOverlay.showPending(id)
                }
                btClient.sendCommandResult(requestId, """{"status":"ok"}""")
            }
            "assistant_resolve" -> {
                assistantCardOverlay.resolvePending(
                    params.optString("id", ""),
                    params.optString("real_id", ""),
                    params.optString("kind", "note"),
                    params.optString("heard", ""),
                    params.optString("note", ""),
                    params.optString("why", "")
                )
                btClient.sendCommandResult(requestId, """{"status":"ok"}""")
            }
            "assistant_pending_cancel" -> {
                val id = params.optString("id", "")
                if (id.isNotEmpty()) {
                    assistantCardOverlay.cancelPending(id)
                }
                btClient.sendCommandResult(requestId, """{"status":"ok"}""")
            }
            "assistant_dismiss_card" -> {
                val id = params.optString("id", "")
                if (id.isNotEmpty()) {
                    assistantCardOverlay.dismissCard(id)
                }
                btClient.sendCommandResult(requestId, """{"status":"ok"}""")
            }
            "start_mouse" -> {
                val sensX = params.optDouble("sensitivity_x", 1800.0).toFloat()
                val sensY = params.optDouble("sensitivity_y", 4200.0).toFloat()
                btLog("Starting mouse (sensX=$sensX sensY=$sensY)")
                // Post to main handler -- HeadTracker needs a Looper thread for sensor callbacks
                rfcommMouseHandler.post { startRfcommMouse(sensX, sensY) }
                // Start BLE HID mouse in default process (unchanged)
                ensureActivityRunning(android.os.Bundle().apply {
                    putBoolean("start_mouse", true)
                    putFloat(EXTRA_MOUSE_SENSITIVITY_X, sensX)
                    putFloat(EXTRA_MOUSE_SENSITIVITY_Y, sensY)
                })
                sendBroadcast(Intent(ACTION_MOUSE_STATE).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_MOUSE_ACTIVE, true)
                    putExtra(EXTRA_MOUSE_SENSITIVITY_X, sensX)
                    putExtra(EXTRA_MOUSE_SENSITIVITY_Y, sensY)
                })
                btClient.sendCommandResult(requestId, """{"status":"started"}""")
            }
            "stop_mouse" -> {
                btLog("Stopping mouse")
                // Post to main handler -- must match the thread that registered sensor listener
                rfcommMouseHandler.post { stopRfcommMouse() }
                sendBroadcast(Intent(ACTION_MOUSE_STATE).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_MOUSE_ACTIVE, false)
                })
                btClient.sendCommandResult(requestId, """{"status":"stopped"}""")
            }
            "confirm" -> {
                btClient.sendCommandResponse(requestId, "confirm", text = "yes")
            }
            "choose" -> {
                btClient.sendCommandResponse(requestId, "choose", text = "")
            }
            "start_teleprompter" -> {
                val text = params.optString("text", "")
                val fontSize = params.optInt("font_size", 22)
                val speechTracking = params.optBoolean("speech_tracking", false)
                btLog("Teleprompter start: textLen=${text.length} fontSize=$fontSize speechTracking=$speechTracking")
                activeTeleprompterRequestId = requestId
                try {
                    val intent = Intent(this, Class.forName("com.repository.glasses.listener.MainActivity")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("start_teleprompter", true)
                        putExtra("teleprompter_text", text)
                        putExtra("teleprompter_font_size", fontSize)
                        putExtra("teleprompter_speech_tracking", speechTracking)
                        putExtra("teleprompter_request_id", requestId)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    btErr("Failed to launch Activity for teleprompter: ${e.message}")
                }
            }
            "teleprompter_control" -> {
                val action = params.optString("action", "")
                val scrollAmount = params.optInt("scroll_amount", 0)
                val wordIndex = params.optInt("word_index", -1)
                btLog("Teleprompter control: action=$action scrollAmount=$scrollAmount wordIndex=$wordIndex")
                sendBroadcast(Intent(ACTION_TELEPROMPTER).apply {
                    setPackage(packageName)
                    putExtra("action", action)
                    putExtra("scroll_amount", scrollAmount)
                    putExtra("word_index", wordIndex)
                })
            }
            "nav_minimap_start" -> {
                btLog("Navigation minimap: showing")
                com.repository.glasses.listener.NavStepState.setMinimapVisible(true)
                sendBroadcast(Intent(ACTION_MAP_MINIMAP).apply {
                    setPackage(packageName)
                    putExtra("visible", true)
                })
            }
            "nav_minimap_stop" -> {
                btLog("Navigation minimap: hiding")
                com.repository.glasses.listener.NavStepState.reset()
                sendBroadcast(Intent(ACTION_MAP_MINIMAP).apply {
                    setPackage(packageName)
                    putExtra("visible", false)
                })
            }
            "nav_steps" -> {
                btLog("Navigation steps received")
                com.repository.glasses.listener.NavStepState.recordSteps(paramsJson)
                sendBroadcast(Intent(ACTION_NAV_STEPS).apply {
                    setPackage(packageName)
                    putExtra("steps_json", paramsJson)
                })
            }
            "nav_step_index" -> {
                val idx = try { JSONObject(paramsJson).getInt("index") } catch (_: Exception) { -1 }
                btLog("Navigation step index: $idx")
                com.repository.glasses.listener.NavStepState.recordStepIndex(idx, "phone")
                sendBroadcast(Intent(ACTION_NAV_STEP_INDEX).apply {
                    setPackage(packageName)
                    putExtra("step_index_json", paramsJson)
                })
            }
            "nav_goal_status" -> {
                // Diagnostic: returns a snapshot of the navigation step state
                // the glasses think they're displaying, plus a history of
                // recent step changes. Used by the phone test harness to
                // validate goal-switch timing against the journey list.
                val snap = com.repository.glasses.listener.NavStepState.snapshotJson()
                btClient.sendCommandResult(requestId, snap.toString())
            }
            "restart_audio" -> {
                btLog("restart_audio: hard-restart mic pump + re-open live stream")
                stopMicStream("restart_audio")
                wantAudioStream = false
                // Hard-restart from the companion phone is an explicit reconcile
                // request: bypass the wear gate (sensor lag shouldn't suppress an
                // explicit operator action) and cycle the audio socket.
                // If in LISTENING, preserve the conversation-length duration.
                val dur = if (state == State.LISTENING) 0L else 30_000L
                signalAudioStart("phone request", force = true, durationMs = dur)
            }
            "fetch_dcim_photo" -> {
                val progressBroadcast = { pct: Int ->
                    sendBroadcast(Intent(ACTION_PHOTO_PROGRESS).apply {
                        setPackage(packageName)
                        putExtra("progress", pct)
                    })
                }
                Thread {
                    // Acquire the send lock to prevent interleaving chunks with
                    // a concurrent proactive push from handleNewDcimPhoto.
                    // If a push is in flight, wait for it -- the phone's deferred
                    // will be completed by the push's recent_photo delivery.
                    if (!recentPhotoSendLock.tryLock()) {
                        btLog("fetch_dcim_photo: proactive push in progress, skipping send")
                        btClient.sendCommandResult(requestId, JSONObject().apply {
                            put("success", true)
                            put("push_in_progress", true)
                        }.toString())
                        return@Thread
                    }
                    try {

                    // Check recency: use max_age from params or default 60s
                    val maxAgeMs = params.optLong("max_age_ms", 60_000)
                    val cacheAge = SystemClock.elapsedRealtime() - lastPhotoThumbTimestamp

                    // Use pre-cached photo from FileObserver if recent enough
                    val cached = if (cacheAge < maxAgeMs) cachedDcimPhotoBase64 else null
                    if (cached != null) {
                        btLog("fetch_dcim_photo: using cached photo (${cached.length} chars, ${cacheAge / 1000}s old)")
                        progressBroadcast(0)
                        btClient.sendChunked("recent_photo", cached, progressBroadcast)
                        btClient.sendCommandResult(requestId, JSONObject().apply {
                            put("success", true)
                            put("size", cached.length)
                        }.toString())
                        return@Thread
                    }
                    // Fallback: read from DCIM with recency check
                    val base64 = getRecentDcimPhoto(maxAgeMs)
                    if (base64 != null) {
                        val bytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) {
                            val thumbB64 = generateThumbnailBase64(bitmap)
                            lastPhotoThumbBase64 = thumbB64
                            lastPhotoThumbTimestamp = SystemClock.elapsedRealtime()
                            val resized = resizeImageForBt(bitmap, maxDim = 1280, quality = 40)
                            bitmap.recycle()
                            cachedDcimPhotoBase64 = resized
                            btLog("fetch_dcim_photo: sending ${resized.length} chars")
                            progressBroadcast(0)
                            btClient.sendChunked("recent_photo", resized, progressBroadcast)
                            btClient.sendCommandResult(requestId, JSONObject().apply {
                                put("success", true)
                                put("size", resized.length)
                            }.toString())
                        } else {
                            btClient.sendCommandResult(requestId, JSONObject().apply {
                                put("success", false)
                                put("text", "Failed to decode DCIM photo")
                            }.toString())
                        }
                    } else {
                        btClient.sendCommandResult(requestId, JSONObject().apply {
                            put("success", false)
                            put("text", "No DCIM photo found")
                        }.toString())
                    }

                    } finally {
                        recentPhotoSendLock.unlock()
                    }
                }.start()
            }
            else -> {
                btErr("Unknown command type: $type")
            }
        }
    }

    override fun onSettings(settingsJson: String) {
        btLog("Applying settings: ${settingsJson.take(100)}")
        val prevPadding = GlassesConfig.bottomPaddingPx
        val prevChatFontSize = GlassesConfig.chatFontSize
        GlassesConfig.applySettings(this, settingsJson)
        val newPadding = GlassesConfig.bottomPaddingPx
        if (newPadding != prevPadding) {
            btLog("Bottom padding changed: $prevPadding -> $newPadding")
            getSharedPreferences("display_settings", MODE_PRIVATE)
                .edit().putInt("bottom_padding_px", newPadding).apply()
            sendBroadcast(Intent(ACTION_BOTTOM_PADDING).apply {
                setPackage(packageName)
                putExtra(EXTRA_PADDING, newPadding)
            })
        }
        val newChatFontSize = GlassesConfig.chatFontSize
        if (newChatFontSize != prevChatFontSize) {
            btLog("Chat font size changed: $prevChatFontSize -> $newChatFontSize")
            getSharedPreferences("display_settings", MODE_PRIVATE)
                .edit().putFloat("chat_font_size", newChatFontSize).apply()
            sendBroadcast(Intent(ACTION_CHAT_FONT_SIZE).apply {
                setPackage(packageName)
                putExtra(EXTRA_CHAT_FONT_SIZE, newChatFontSize)
            })
        }
        // wakeword_enabled flag may have flipped; reconcile pipeline + mic stream
        // immediately so toggling takes effect without waiting for the next
        // wear/phone-audio event.
        try {
            reconcileWakeWord("setting-changed")
            // Order matters when on_demand_recording_active toggles off: the opus
            // writer's rotateNow needs the mic still feeding MicBus during the close
            // so the on-demand segment captures the last frames before we hand off
            // to a fresh always-record segment (or stop entirely). Enqueue the opus
            // reconcile BEFORE touching the mic so the IO executor processes the
            // close+rotate while AudioRecord is still running.
            postReconcileLocalOpusWriter("settings")
            reconcileMicStream("setting-changed")
        } catch (t: Throwable) {
            btErr("reconcile after settings failed: ${t.message}")
        }
    }

    override fun onToolStatus(requestId: String, toolName: String, status: String, toolArgsJson: String, toolCallId: String) {
        btLog("Tool status ($requestId): $toolName -> $status")
        val json = JSONObject().apply {
            put("requestId", requestId)
            put("toolName", toolName)
            put("status", status)
            if (toolArgsJson.isNotEmpty()) {
                try { put("toolArgs", JSONObject(toolArgsJson)) } catch (_: Exception) {}
            }
            if (toolCallId.isNotEmpty()) put("toolCallId", toolCallId)
        }.toString()
        sendBroadcast(Intent(ACTION_TOOL_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_TOOL_STATUS, json)
        })
    }

    override fun onDismissSession() {
        btLog("Dismiss session received from phone")
        if (state != State.IDLE) {
            cancelledRequestId = currentRequestId
            ttsPlayer.interrupt()
            transitionToIdle()
        }
    }

    override fun onTranslationResult(resultJson: String) {
        btLog("Translation result: ${resultJson.take(100)}")
        sendBroadcast(Intent(ACTION_TRANSLATION_RESULT).apply {
            setPackage(packageName)
            putExtra(EXTRA_TRANSLATION_RESULT, resultJson)
        })
    }

    override fun onTranslationConfig(configJson: String) {
        btLog("Translation config: $configJson")
        // Persist so glasses always reflect the latest phone config
        try {
            val obj = org.json.JSONObject(configJson)
            val from = obj.optString("fromLanguage", "")
            val to = obj.optString("toLanguage", "")
            if (from.isNotEmpty()) GlassesConfig.setTranslationFromLanguage(this, from)
            if (to.isNotEmpty()) GlassesConfig.setTranslationToLanguage(this, to)
            val fs = obj.optInt("fontSize", -1)
            if (fs > 0) GlassesConfig.setTranslationFontSize(this, fs)
            GlassesConfig.setTranslationTwoWay(this, obj.optBoolean("twoWay", false))
        } catch (e: Exception) {
            btErr("Failed to persist translation config: ${e.message}")
        }
        sendBroadcast(Intent(ACTION_TRANSLATION_CONFIG).apply {
            setPackage(packageName)
            putExtra(EXTRA_TRANSLATION_CONFIG, configJson)
        })
    }

    // Translation audio: 8ch AudioRecord extracts both front (CAE beamformed, sent
    // on CH_AUDIO_DATA) and inward (ch0 post-algorithm, sent on CH_AUDIO_DATA_INWARD
    // when twoWay=true) from the same capture -- no MicBus dependency for two-way.
    private fun startFrontMicForTranslation(twoWay: Boolean = false) {
        if (translationFrontMicRecorder != null) {
            btLog("FrontMic: stopping existing recorder before restart")
            stopFrontMicForTranslation()
        }
        translationFrontMicRecorder = com.repository.glasses.listener.capture.TranslationFrontMicRecorder(btClient).apply {
            remoteLog = { btLog(it) }
            start(twoWay)
        }
    }

    private fun stopFrontMicForTranslation() {
        translationFrontMicRecorder?.stop()
        translationFrontMicRecorder = null
    }

    override fun onRokidCommand(action: String, paramsJson: String) {
        btLog("Rokid command: action=$action params=${paramsJson.take(100)}")
        try {
            val params = JSONObject(paramsJson)
            when (action) {
                "start_translation" -> {
                    btLog("Rokid start_translation: front mic (twoWay from 8ch)")
                    BeamformController.remoteLog = { btLog(it) }
                    BeamformController.init(this@ListenerService)
                    BeamformController.setScene(BeamformController.SCENE_CARDIOID)
                    val twoWay = params.optBoolean("two_way",
                        GlassesConfig.getTranslationTwoWay(this@ListenerService))
                    startFrontMicForTranslation(twoWay)
                    ensureActivityRunning(android.os.Bundle().apply {
                        putBoolean("start_translation", true)
                    })
                    sendBroadcast(Intent(ACTION_TRANSLATION_STATE).apply {
                        setPackage(packageName)
                        putExtra(EXTRA_TRANSLATION_ACTIVE, true)
                    })
                }
                "stop_translation" -> {
                    btLog("Rokid stop_translation")
                    BeamformController.setScene(BeamformController.SCENE_IDLE)
                    stopFrontMicForTranslation()
                    sendBroadcast(Intent(ACTION_TRANSLATION_STATE).apply {
                        setPackage(packageName)
                        putExtra(EXTRA_TRANSLATION_ACTIVE, false)
                    })
                }
                "start_navigation" -> navigationCtrl.startNavigation(
                    params.getString("destination"),
                    params.optInt("naviType", 0)
                )
                "stop_navigation" -> navigationCtrl.stopNavigation()
                else -> btLog("Unknown rokid command: $action")
            }
        } catch (e: Exception) {
            btErr("Failed to handle rokid command: ${e.message}")
        }
    }

    override fun onPhoneActivate() {
        btLog("PHONE ACTIVATE: state=$state wantAudio=$wantAudioStream")
        if (!wantAudioStream) {
            btLog("PHONE ACTIVATE: rejected -- wantAudioStream=false")
            return
        }
        when (state) {
            State.IDLE, State.LISTENING -> activateListening()
            State.RESPONDING -> {
                btLog("PHONE ACTIVATE: interrupting response")
                ttsPlayer.interrupt()
                currentRequestId?.let { btClient.sendTtsInterrupt(it) }
                currentRequestId = null
                activateListening()
            }
        }
    }

    override fun onMapArrow(normX: Float, normY: Float, headingDeg: Float) {
        sendBroadcast(Intent(ACTION_MAP_ARROW).apply {
            setPackage(packageName)
            putExtra(EXTRA_ARROW_X, normX)
            putExtra(EXTRA_ARROW_Y, normY)
            putExtra(EXTRA_ARROW_HEADING, headingDeg)
        })
    }

    // Map base frame arrives as raw WEBP bytes on the dedicated map RFCOMM socket
    // (MAP_UUID / CH_MAP_BITMAP_BIN), routed here from mapRelay.onBinaryMessage. No base64.
    private fun onMapBitmapBytes(payload: ByteArray) {
        sendBroadcast(Intent(ACTION_MAP_BITMAP).apply {
            setPackage(packageName)
            putExtra(EXTRA_MAP_BITMAP_BYTES, payload)
        })
        // Update overlay if active
        mapOverlayView?.let { iv ->
            overlayHandler.post {
                try {
                    val bmp = BitmapFactory.decodeByteArray(payload, 0, payload.size) ?: return@post
                    val green = BitmapUtils.toMonochromeGreen(bmp)
                    bmp.recycle()
                    val old = (iv.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    iv.setImageBitmap(green)
                    old?.recycle()
                } catch (e: Exception) {
                    btLog("Map overlay bitmap update failed: ${e.message}")
                }
            }
        }
    }

    // --- Lone mode (foreign-device proximity alarm) ---

    // @Volatile: read on the BT MessageRelay thread (onCommand/onConnected) and on the main
    // thread (onDestroy), assigned from MessageRelay thread in ensureLoneController.
    @Volatile private var loneController: com.repository.glasses.listener.lone.LoneModeController? = null
    private var loneAlertPlayer: android.media.MediaPlayer? = null

    private fun broadcastLoneIndicator(active: Boolean, count: Int) {
        sendBroadcast(Intent(ACTION_LONE_INDICATOR).apply {
            setPackage(packageName)
            putExtra(EXTRA_LONE_ACTIVE, active)
            putExtra(EXTRA_LONE_COUNT, count)
        })
    }

    private fun ensureLoneController(): com.repository.glasses.listener.lone.LoneModeController {
        loneController?.let { return it }
        val ctrl = com.repository.glasses.listener.lone.LoneModeController(
            context = this,
            onActive = { active -> broadcastLoneIndicator(active, 0) },
            onCount = { count -> broadcastLoneIndicator(true, count) },
            onAlert = { playLoneAlert() },
            pushToPhone = { json -> btClient.sendChunked("lone_devices_update", json) },
            log = { btLog("LoneMode: $it") }
        )
        loneController = ctrl
        return ctrl
    }

    @Volatile private var loneAlertPlaying = false
    @Volatile private var loneAlertLastMs = 0L

    private fun playLoneAlert() {
        // At most ONE chime per 2s window: a burst of new foreign devices must not stack alerts.
        // The cooldown also lets the current clip finish to the end without being cut off.
        val now = SystemClock.elapsedRealtime()
        if (loneAlertPlaying || now - loneAlertLastMs < 2_000L) return
        loneAlertLastMs = now
        try {
            val mp = android.media.MediaPlayer.create(this, com.repository.glasses.listener.R.raw.lone_alert) ?: return
            mp.isLooping = false
            mp.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setOnCompletionListener { p ->
                loneAlertPlaying = false
                try { p.release() } catch (_: Exception) {}
                if (loneAlertPlayer === p) loneAlertPlayer = null
                updateDuckState()
            }
            mp.setOnErrorListener { p, _, _ ->
                loneAlertPlaying = false
                try { p.release() } catch (_: Exception) {}
                if (loneAlertPlayer === p) loneAlertPlayer = null
                updateDuckState()
                true
            }
            loneAlertPlayer = mp
            loneAlertPlaying = true
            // SFX renders locally on the glasses, so duck the incoming A2DP music
            // for the duration of the clip (mirrors the TTS/notification duck path).
            updateDuckState()
            mp.start()
        } catch (e: Exception) {
            loneAlertPlaying = false
            btErr("LoneMode: alert playback failed: ${e.message}")
            updateDuckState()
        }
    }

    // --- Map overlay (persistent minimap) ---

    private var mapPinned = false
    private var mapTabActive = false

    private fun reconcileMapOverlay() {
        if (mapPinned && !mapTabActive) showMapOverlay() else hideMapOverlay()
    }

    private val mapPinReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            mapPinned = intent.getBooleanExtra("pinned", false)
            reconcileMapOverlay()
        }
    }

    private val mapTabVisibleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            mapTabActive = intent.getBooleanExtra("visible", false)
            reconcileMapOverlay()
        }
    }

    @Volatile private var currentTabId: String = "CHAT"

    private val tabChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            currentTabId = intent.getStringExtra(EXTRA_TAB_ID) ?: "CHAT"
        }
    }

    private val translationToggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val translationActive = translationFrontMicRecorder != null
            if (!translationActive) {
                // Start: read saved config and request from phone
                val ctx = this@ListenerService
                val fromLang = GlassesConfig.getTranslationFromLanguage(ctx).ifEmpty { "en" }
                val toLang = GlassesConfig.getTranslationToLanguage(ctx).ifEmpty { "ru" }
                val params = JSONObject().apply {
                    put("from_language", fromLang)
                    put("to_language", toLang)
                    put("font_size", GlassesConfig.getTranslationFontSize(ctx))
                    put("audio_source", GlassesConfig.getTranslationAudioSource(ctx))
                    put("provider", GlassesConfig.getTranslationProvider(ctx))
                    put("two_way", GlassesConfig.getTranslationTwoWay(ctx))
                }
                btClient.sendGlassesCommand("request_start_translation", params)
                btLog("Translation toggle: requesting start (from=$fromLang to=$toLang)")
            } else {
                // Stop
                btClient.sendGlassesCommand("request_stop_translation")
                btLog("Translation toggle: requesting stop")
            }
        }
    }

    private val assistantToggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!assistantActive) {
                val ctx = this@ListenerService
                val params = JSONObject().apply {
                    put("wearer_lang", GlassesConfig.getAssistantWearerLang(ctx))
                    put("interlocutor_lang", GlassesConfig.getAssistantInterlocutorLang(ctx))
                    put("interlocutor_source", GlassesConfig.getAssistantInterlocutorSource(ctx))
                    put("model", GlassesConfig.getAssistantModel(ctx))
                }
                btClient.sendGlassesCommand("request_start_assistant", params)
                btLog("Assistant toggle: requesting start")
            } else {
                btClient.sendGlassesCommand("request_stop_assistant")
                btLog("Assistant toggle: requesting stop")
            }
        }
    }

    private val stopJourneyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            btLog("Stop journey requested from glasses")
            navigationCtrl.stopNavigation()
            btClient.sendCommand("stop_journey")
        }
    }

    private val navZoomReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val fraction = intent.getFloatExtra(EXTRA_ZOOM_FRACTION, -1f)
            if (fraction < 0f) {
                btLog("nav_zoom: missing or invalid fraction")
                return
            }
            btLog("nav_zoom: forwarding fraction=$fraction to phone")
            btClient.sendCommand("nav_zoom", fraction.toString())
        }
    }

    private val mediaCommandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val command = intent.getStringExtra(EXTRA_MEDIA_COMMAND) ?: return
            btLog("Media command: $command")
            // Route through BtMediaSource: it owns the MediaController bound to
            // BluetoothMediaBrowserService, which forwards the transport command
            // to the phone over standard AVRCP. Fall back to local session
            // monitor only if BtMediaSource isn't up.
            val handled = btMediaSource?.sendCommand(command) == true
            if (!handled) mediaSessionMonitor.dispatchCommand(command)
        }
    }

    private fun showMapOverlay() {
        if (mapOverlayView != null) return
        if (!Settings.canDrawOverlays(this)) {
            btLog("Map overlay: SYSTEM_ALERT_WINDOW not granted, skipping")
            return
        }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val width = (288 * density).toInt()
        val height = (144 * density).toInt()
        val params = WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = (26 * density).toInt()
        }
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        wm.addView(iv, params)
        mapOverlayView = iv
        btLog("Map overlay: shown")
    }

    private fun hideMapOverlay() {
        mapOverlayView?.let {
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
            } catch (_: Exception) {}
            mapOverlayView = null
            btLog("Map overlay: hidden")
        }
    }

    override fun onChatListResponse(chatsJson: String) {
        btLog("Chat list response received: ${chatsJson.length} chars")
        sendBroadcast(Intent(ACTION_CHAT_LIST).apply {
            setPackage(packageName)
            putExtra(EXTRA_CHAT_LIST, chatsJson)
        })
    }

    override fun onChatHistory(conversationId: String, turnsJson: String) {
        btLog("Chat history received for $conversationId: ${turnsJson.length} chars")
        sendBroadcast(Intent(ACTION_CHAT_HISTORY_LOADED).apply {
            setPackage(packageName)
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
            putExtra(EXTRA_CHAT_HISTORY, turnsJson)
        })
    }

    override fun onTodoListResponse(json: String) {
        btLog("Todo list response received: ${json.length} chars")
        sendBroadcast(Intent(ACTION_TODO_LIST_LOADED).apply {
            setPackage(packageName)
            putExtra(EXTRA_TODO_JSON, json)
        })
    }

    override fun onAlarmListResponse(json: String) {
        btLog("Alarm list response: ${json.length} chars")
        sendBroadcast(Intent(ACTION_ALARM_LIST_LOADED).apply {
            setPackage(packageName)
            putExtra(EXTRA_ALARM_JSON, json)
        })
    }

    override fun onJobListResponse(json: String) {
        btLog("Job list response: ${json.length} chars")
        sendBroadcast(Intent(ACTION_JOB_LIST_LOADED).apply {
            setPackage(packageName)
            putExtra(EXTRA_JOB_JSON, json)
        })
    }

    override fun onTgChatListResponse(json: String) {
        btLog("TG chat list response: ${json.length} chars")
        if (json.length > 200_000) {
            try {
                val file = java.io.File(filesDir, "tg_chatlist_payload.json")
                file.writeText(json)
                sendBroadcast(Intent(ACTION_TG_CHAT_LIST).apply {
                    setPackage(packageName)
                    putExtra("tg_chatlist_file", file.absolutePath)
                })
                return
            } catch (e: Exception) {
                btErr("TG chat list file write failed: ${e.message}")
            }
        }
        sendBroadcast(Intent(ACTION_TG_CHAT_LIST).apply {
            setPackage(packageName)
            putExtra(EXTRA_TG_CHAT_LIST_JSON, json)
        })
    }

    override fun onTgMessagesResponse(chatId: String, json: String) {
        btLog("TG messages response: chatId=$chatId ${json.length} chars")
        // Large JSON (with images/long threads) can exceed the 1MB Binder transaction limit
        // for broadcast intents, causing CannotDeliverBroadcastException. Write to a shared
        // file and pass the path instead. The chatId always rides along so the UI can route
        // the response by WHICH chat it answers (chatId="me" = Saved sub-tab, else chat browser)
        // instead of guessing from a transient in-flight flag.
        if (json.length > 200_000) {
            try {
                val file = java.io.File(filesDir, "tg_messages_payload.json")
                file.writeText(json)
                sendBroadcast(Intent(ACTION_TG_MESSAGES).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_TG_CHAT_ID, chatId)
                    putExtra("tg_messages_file", file.absolutePath)
                })
                return
            } catch (e: Exception) {
                btErr("TG messages file write failed: ${e.message}")
            }
        }
        sendBroadcast(Intent(ACTION_TG_MESSAGES).apply {
            setPackage(packageName)
            putExtra(EXTRA_TG_CHAT_ID, chatId)
            putExtra(EXTRA_TG_MESSAGES_JSON, json)
        })
    }

    override fun onTgTopicsResponse(chatId: String, json: String) {
        btLog("TG topics response: chatId=$chatId ${json.length} chars")
        sendBroadcast(Intent(ACTION_TG_TOPICS).apply {
            setPackage(packageName)
            putExtra(EXTRA_TG_CHAT_ID, chatId)
            putExtra(EXTRA_TG_TOPICS_JSON, json)
        })
    }

    override fun onTgSendResult(json: String) {
        btLog("TG send result: ${json.take(80)}")
        sendBroadcast(Intent(ACTION_TG_SEND_RESULT).apply {
            setPackage(packageName)
            putExtra(EXTRA_TG_SEND_RESULT_JSON, json)
        })
    }

    override fun onTgNewMessage(json: String) {
        btLog("TG new message: ${json.take(80)}")
        // Check if notification should be suppressed for open chat
        val openChat = currentOpenTgChatTitle
        if (openChat != null) {
            try {
                val obj = JSONObject(json)
                val chatTitle = obj.optString("chatTitle", "")
                val sender = obj.optString("sender", "")
                if (chatTitle == openChat || sender == openChat) {
                    btLog("TG new message in open chat -- suppressing notification")
                }
            } catch (_: Exception) {}
        }
        sendBroadcast(Intent(ACTION_TG_NEW_MESSAGE).apply {
            setPackage(packageName)
            putExtra(EXTRA_TG_NEW_MESSAGE_JSON, json)
        })
    }

    override fun onSyncMessage(msgType: String, sessionId: String, payload: List<String>) {
        try { syncChannelHandler?.onMessage(msgType, sessionId, payload) }
        catch (e: Exception) { btErr("onSyncMessage failed: ${e.message}") }
    }

    @Volatile private var currentOpenTgChatTitle: String? = null

    @Volatile private var notifWokeScreen = false
    // True while an UNTIMED notifScreenLock is held for the duration of a
    // hold-to-reply (reply-start -> overlay leaves screen). Prevents the timed
    // FULL_WAKE_LOCK from expiring mid-reply and blinking the screen.
    @Volatile private var notifReplyHoldingScreen = false
    private var notifScreenLock: android.os.PowerManager.WakeLock? = null

    // Notification completion tracking -- glasses signals phone when both overlay + TTS are done.
    // Per-id tracking so overlapping notifications each complete independently.
    private val notifHandler = Handler(Looper.getMainLooper())
    private val notifLatchLock = Any()
    private val pendingNotifIds = LinkedHashSet<String>()
    private val notifTtsDoneIds = HashSet<String>()
    private val notifOverlayDoneIds = HashSet<String>()
    // Id currently loaded into the notification TTS player. Set at enqueue time and
    // used by the player callbacks (which carry no id) to mark the right notification.
    @Volatile private var notifTtsPlayingId: String? = null
    private val notifNoTtsTimeout = Runnable {
        val nid = activeNotifId ?: return@Runnable
        var alreadyTts = false
        synchronized(notifLatchLock) { alreadyTts = notifTtsDoneIds.contains(nid) }
        if (!alreadyTts) {
            btLog("[Notif] No TTS within 2s for $nid, marking TTS done")
            synchronized(notifLatchLock) { notifTtsDoneIds.add(nid) }
            checkNotifComplete(nid)
        }
    }
    @Volatile private var activeNotifId: String? = null
    // Content-based dedup: "sender:textHash" -> timestamp. Drops identical
    // notifications re-sent within 10s (NLS rebind replay, transport retry).
    private val recentNotifContent = LinkedHashMap<String, Long>(16, 0.75f, true)

    private fun checkNotifComplete(nid: String) {
        val complete: Boolean
        synchronized(notifLatchLock) {
            val ttsDone = notifTtsDoneIds.contains(nid)
            val overlayDone = notifOverlayDoneIds.contains(nid)
            btLog("[Notif] Check complete: $nid ttsDone=$ttsDone overlayDone=$overlayDone")
            complete = ttsDone && overlayDone
            if (complete) {
                pendingNotifIds.remove(nid)
                notifTtsDoneIds.remove(nid)
                notifOverlayDoneIds.remove(nid)
            }
        }
        if (complete) {
            btLog("[Notif] Fully done: $nid -> sending CH_NOTIFICATION_DONE")
            btClient.sendNotificationDone(nid)
        }
    }

    private val notifLockScreenRunnable = Runnable {
        sendBroadcast(Intent(ScreenOffAccessibilityService.ACTION_LOCK_SCREEN).apply {
            setPackage(packageName)
        })
        btLog("Screen lock requested after notification")
    }

    private fun wakeScreenForNotification(repliable: Boolean) {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        // No early-return when the screen is already interactive: always re-arm the lock
        // so a later notification extends the on-screen window past the prior one.
        // Off-head: no display to wake. Skip the FULL_WAKE_LOCK entirely.
        if (lastWornState == false) {
            btLog("[Notif] skipping FULL_WAKE_LOCK -- off-head, no display to wake")
            return
        }
        // A reply in progress holds an UNTIMED notifScreenLock. A second
        // notification arriving mid-reply must NOT release+re-acquire it as a
        // timed lock -- that would re-introduce the mid-reply screen blink.
        // Preserve the untimed lock; it is released in onAllDismissed.
        if (notifReplyHoldingScreen) {
            btLog("[Notif] keeping untimed reply lock, skipping timed re-arm")
            return
        }
        notifWokeScreen = true
        try {
            // Release any previously-held lock before acquiring a fresh one (no leak)
            notifScreenLock?.let { if (it.isHeld) it.release() }
            // Repliable notifications stay on screen ~12s (matching the overlay's
            // repliableDurationMs) so the screen must not drop mid-window before
            // the user can start the hold-to-reply gesture.
            val windowMs = if (repliable) 12000L else GlassesConfig.notificationDurationMs
            @Suppress("DEPRECATION")
            notifScreenLock = pm.newWakeLock(
                android.os.PowerManager.FULL_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "GlassesListener::NotifScreen"
            ).apply { acquire(windowMs + 2000L) } // duration + 2s safety
            btLog("Notification woke screen")
        } catch (e: Exception) {
            btLog("Notification screen wake failed: ${e.message}")
            notifWokeScreen = false
        }
    }

    override fun onNotification(notifId: String, sender: String, text: String, chat: String, repliable: Boolean) {
        btLog("[Notif] Received: notifId=$notifId sender=$sender text=${text.take(60)} repliable=$repliable")
        // Solo decision: use DISPLAY STATE (not isInteractive, which lies in DOZE/ON_SUSPEND).
        // Only enter the solo path when the screen was genuinely OFF and the glasses are worn,
        // i.e. when this notification is the thing that wakes the panel.
        val screenWasOff = try {
            notifDisplayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.state ==
                android.view.Display.STATE_OFF
        } catch (e: Exception) {
            btErr("[NSOLO] display-state read failed: ${e.message}")
            false
        }
        val solo = screenWasOff && (lastWornState == true)
        btLog("[NSOLO] onNotification screenWasOff=$screenWasOff worn=${lastWornState} -> solo=$solo")
        // Suppress RC "Done" when user is already looking at chat or chat list
        if (notifId.startsWith("rcfinish-") && currentTabId in setOf("CHAT", "CHAT_LIST")) {
            btLog("[Notif] Suppressed rcfinish on tab $currentTabId")
            btClient.sendNotificationDone(notifId)
            return
        }
        // Suppress notification for currently open Telegram chat
        val openChat = currentOpenTgChatTitle
        if (openChat != null && (sender == openChat || chat == openChat)) {
            btLog("[Notif] Suppressed for open TG chat: $openChat")
            btClient.sendNotificationDone(notifId)
            return
        }
        if (lastWornState == false) {
            btLog("[Notif] Suppressed (folded)")
            btClient.sendNotificationDone(notifId)
            return
        }
        // Content-based dedup -- drop identical sender+text within cooldown
        val contentKey = "$sender:$text"
        val now = System.currentTimeMillis()
        synchronized(recentNotifContent) {
            val lastSeen = recentNotifContent[contentKey]
            if (lastSeen != null && now - lastSeen < NOTIF_DEDUP_MS) {
                btLog("[Notif] Dropped duplicate: $sender (${now - lastSeen}ms ago)")
                btClient.sendNotificationDone(notifId)
                return
            }
            recentNotifContent[contentKey] = now
            recentNotifContent.entries.removeAll { now - it.value > NOTIF_DEDUP_MS }
        }
        // Same-sender merging is owned entirely by the phone now: it keeps the
        // per-message segments, sorts them by timestamp, and pushes the full
        // recomputed body via CH_NOTIFICATION_SETTEXT. Each CH_NOTIFICATION frame
        // the phone sends here is therefore an independent notification (a fresh
        // same-sender notifId the phone chose NOT to merge), shown/queued normally.
        notifHandler.removeCallbacks(notifNoTtsTimeout)
        // Cancel any pending screen-off from a previous drain (500ms window) so a
        // freshly-arriving notification does not get its screen blinked off.
        notifHandler.removeCallbacks(notifLockScreenRunnable)
        activeNotifId = notifId
        synchronized(notifLatchLock) { pendingNotifIds.add(notifId) }
        btLog("[Notif] Active set: $notifId")
        wakeScreenForNotification(repliable)
        notificationOverlay.show(NotificationOverlay.NotificationData(notifId, sender, text, chat, repliable), solo)
        if (solo) {
            notifSoloSessionActive = true
            btLog("[NSOLO] solo armed -- broadcasting SOLO_SHOW id=$notifId")
            sendBroadcast(Intent(ACTION_NOTIFICATION_SOLO_SHOW).apply {
                setPackage(packageName)
                putExtra(EXTRA_NOTIF_ID, notifId)
            })
        }
        if (repliable) {
            sendBroadcast(Intent(ACTION_NOTIFICATION_SHOWN).apply {
                setPackage(packageName)
                putExtra(EXTRA_NOTIF_ID, notifId)
                putExtra(EXTRA_NOTIF_REPLIABLE, true)
            })
        }
        // If no TTS arrives within 2s, assume no TTS for this notification
        notifHandler.postDelayed(notifNoTtsTimeout, 2000)
    }

    override fun onNotificationSetText(notifId: String, fullText: String) {
        btLog("[Notif] SetText: notifId=$notifId full=${fullText.take(60)}")
        // The phone re-sorted the merged same-sender segments by timestamp and pushed
        // the full recomputed body. We REPLACE the on-screen text -- the merged message
        // was never enqueued separately, so there is no separate DONE pending and no
        // latch change here.
        val set = notificationOverlay.setCurrentTextById(notifId, fullText)
        if (set) {
            btLog("[Notif] set text for current id=$notifId (${fullText.count { it == '\n' } + 1} lines)")
            // Re-arm the screen for the extended on-screen window (no-ops off-head
            // and while a reply holds the untimed lock).
            wakeScreenForNotification(notificationOverlay.currentData?.repliable ?: false)
        } else {
            btLog("[Notif] set-text miss id=$notifId (not current)")
        }
    }

    override fun onNotifReplyResult(notifId: String, ok: Boolean) {
        btLog("[Notif] Reply result: $notifId ok=$ok awaiting=${notifReplyAwaitingResult ?: "?"}")
        // Ignore stale/late results for a reply we are no longer awaiting (e.g. a
        // second result, or one that arrives after a timeout already finalized).
        if (notifId != notifReplyAwaitingResult) {
            btLog("[Notif] reply result ignored (not awaited)")
            return
        }
        notifHandler.post { finalizeReplyResult(ok) }
    }

    private val notifTtsBuffer = StringBuilder()
    private var notifTtsBufferId = ""

    override fun onNotificationTtsAudio(notifId: String, audioBase64: String, isFinal: Boolean) {
        btLog("[Notif] TTS chunk: $notifId (+${audioBase64.length} chars, final=$isFinal)")
        if (audioBase64.isEmpty()) return

        if (!GlassesConfig.notificationSoundEnabled) {
            btLog("[Notif] TTS skipped (sound disabled): $notifId")
            synchronized(notifLatchLock) { notifTtsDoneIds.add(notifId) }
            checkNotifComplete(notifId)
            return
        }

        if (lastWornState == false) {
            btLog("[Notif] TTS skipped (folded): $notifId")
            synchronized(notifLatchLock) { notifTtsDoneIds.add(notifId) }
            checkNotifComplete(notifId)
            return
        }

        if (callController.scoActive) {
            btLog("[Notif] TTS suppressed: HFP session active (scoActive=true): $notifId")
            notifTtsBuffer.clear()
            notifTtsBufferId = ""
            synchronized(notifLatchLock) { notifTtsDoneIds.add(notifId) }
            checkNotifComplete(notifId)
            return
        }

        // New notifId -> reset buffer
        if (notifId != notifTtsBufferId) {
            notifTtsBuffer.clear()
            notifTtsBufferId = notifId
        }
        notifTtsBuffer.append(audioBase64)
        btLog("[Notif] TTS chunk: $notifId (buffered=${notifTtsBuffer.length} chars)")

        if (isFinal) {
            val completeAudio = notifTtsBuffer.toString()
            notifTtsBuffer.clear()
            notifTtsBufferId = ""
            btLog("[Notif] TTS complete: $notifId (${completeAudio.length} chars) -> enqueue to player")
            notifTtsPlayingId = notifId
            notificationTtsPlayer.enqueue(notifId, completeAudio, true)
        }
    }

    override fun onStreamingText(requestId: String, partialText: String, isFinal: Boolean) {
        if (requestId == cancelledRequestId) {
            btLog("Streaming rejected: request $requestId was cancelled")
            return
        }

        if (!streamingDelivered) {
            streamingDelivered = true
            currentRequestId = requestId
            btLog("First streaming chunk for $requestId, state=$state")
            if (state == State.LISTENING) {
                transitionState(State.RESPONDING, "first streaming chunk")
                broadcastState(state.name)
            }
        }

        val json = JSONObject().apply {
            put("requestId", requestId)
            put("partialText", partialText)
            put("isFinal", isFinal)
        }.toString()
        sendBroadcast(Intent(ACTION_STREAMING_TEXT).apply {
            setPackage(packageName)
            putExtra(EXTRA_STREAMING_TEXT, json)
        })
    }

    override fun onPartialText(text: String) {
        if (state != State.LISTENING && !telegramVoiceActive) return
        // During a notification reply, the live transcript renders inside the
        // notification overlay rectangle (not in any MainActivity overlay).
        if (notifReplyId != null) {
            notificationOverlay.setReplyTranscript(text)
        }
        sendBroadcast(Intent(ACTION_PARTIAL_TEXT).apply {
            setPackage(packageName)
            putExtra(EXTRA_PARTIAL_TEXT, text)
        })
    }

    override fun onUserText(requestId: String, text: String) {
        btLog("User text: $requestId -> ${text.take(80)}")
        // Notification reply final transcript: drive the overlay through SENDING ->
        // (RemoteInput send) -> SENT, then dismiss. This is the release-to-send
        // completion path; the reply never enters the chat tab.
        val activeNotifReply = notifReplyId
        if (activeNotifReply != null && requestId != "pending") {
            // End-of-speech: the phone's VAD finalized the transcript. Recording
            // is done, so tear the audio path down -- but do NOT fire the reply
            // yet. The final transcript opens a 3s double-tap-to-cancel window
            // owned by MainActivity; it broadcasts ACTION_NOTIF_REPLY_SEND when the
            // window elapses, or ACTION_NOTIF_REPLY_CANCEL on a double-tap.
            val finalText = text
            telegramVoiceActive = false
            stopGlassesAudioStream("notif-reply-final")
            updateDuckState()
            if (state == State.LISTENING) {
                transitionState(State.IDLE, "notif reply transcript final")
                broadcastState(state.name)
            }
            // Tell MainActivity the final transcript arrived so it opens the
            // SENDING window (or tears down on a blank transcript).
            sendBroadcast(Intent(ACTION_USER_TEXT).apply {
                setPackage(packageName)
                putExtra(EXTRA_USER_TEXT_REQUEST_ID, requestId)
                putExtra(EXTRA_USER_TEXT, finalText)
            })
            if (finalText.isBlank()) {
                // Nothing captured (no speech / empty transcript): cancel rather
                // than sending an empty reply. Show CANCELLED, then dismiss.
                btLog("[NREPLY] svc empty final text -> CANCELLED")
                notifReplyId = null
                // Reply terminal: both wantedAudio flags are now clear
                // (stopGlassesAudioStream above cleared wantAudioStream, this clears
                // notifReplyId). Re-evaluate mic demand so the mic stops promptly when
                // nothing else wants it, instead of lingering until an unrelated event.
                reconcileMicStream("reply-end")
                // Reply terminal: release the untimed reply lock and re-arm a timed
                // one for the brief CANCELLED display (clear flag first to allow it).
                notifReplyHoldingScreen = false
                wakeScreenForNotification(notificationOverlay.currentData?.repliable ?: false)
                notificationOverlay.setReplyPhase(NotificationOverlay.ReplyPhase.CANCELLED)
                notifHandler.postDelayed({
                    notificationOverlay.unfreezeDismiss()
                    notificationOverlay.dismiss()
                }, 1500L)
            } else {
                // Show the final transcript and start the visual 3s countdown. The
                // RemoteInput is NOT fired here -- it waits for the SEND broadcast.
                notificationOverlay.setReplyTranscript(finalText)
                notificationOverlay.setReplyPhase(NotificationOverlay.ReplyPhase.SENDING)
            }
            return
        }
        // Track the real requestId (skip "pending" placeholder)
        if (requestId != "pending") {
            currentRequestId = requestId
        }
        // Transition out of LISTENING as soon as user text arrives (recording is done on phone side)
        if (state == State.LISTENING) {
            transitionState(State.RESPONDING, "user text received")
            broadcastState(state.name)
        }
        // Check for recent photo thumbnail to attach to USER message (skip "pending" placeholder)
        val photoThumb = if (requestId != "pending" && SystemClock.elapsedRealtime() - lastPhotoThumbTimestamp < 60_000) lastPhotoThumbBase64 else null
        if (photoThumb != null) {
            lastPhotoThumbBase64 = null
            lastPhotoThumbTimestamp = 0
            btLog("Auto-attaching cached photo thumbnail to user message $requestId")
        }
        sendBroadcast(Intent(ACTION_USER_TEXT).apply {
            setPackage(packageName)
            putExtra(EXTRA_USER_TEXT_REQUEST_ID, requestId)
            putExtra(EXTRA_USER_TEXT, text)
            if (photoThumb != null) putExtra(EXTRA_USER_PHOTO_THUMB, photoThumb)
        })
    }

    override fun onReidResult(trackingId: String, recognized: Boolean, personUid: String, displayName: String, score: Float) {
        btLog("Reid result from phone: tid=$trackingId recognized=$recognized uid=$personUid")
        reidController?.onReidResult(trackingId, recognized, personUid, displayName, score)
    }

    override fun onReidMerge(sourcePersonId: String, targetPersonId: String, targetDisplayName: String) {
        btLog("Reid merge: $sourcePersonId -> $targetPersonId ($targetDisplayName)")
        reidController?.onReidMerge(sourcePersonId, targetPersonId, targetDisplayName)
    }

    override fun onReidBestThumb(personUid: String, imageBase64: String) {
        btLog("Reid best thumb: uid=$personUid (${imageBase64.length} chars)")
        sendBroadcast(Intent(ACTION_REID_BEST_THUMB).apply {
            putExtra(EXTRA_REID_PERSON_UID, personUid)
            putExtra(EXTRA_REID_BEST_THUMB_DATA, imageBase64)
            setPackage(packageName)
        })
    }

    override fun onReidPersonResponse(personUid: String, personJson: String) {
        btLog("Reid person response: uid=$personUid (${personJson.length} chars)")
        sendBroadcast(Intent(ACTION_REID_PERSON_RESPONSE).apply {
            putExtra(EXTRA_REID_PERSON_UID, personUid)
            putExtra(EXTRA_REID_PERSON_JSON, personJson)
            setPackage(packageName)
        })
    }

    override fun onOrchestratorStatus(connected: Boolean) {
        btLog("Orchestrator status: ${if (connected) "connected" else "disconnected"}")
        sendBroadcast(Intent(ACTION_ORCHESTRATOR_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_ORCHESTRATOR_CONNECTED, connected)
        })
        // Orchestrator drove ReID frames; without it, frames go nowhere and
        // the UI/LED state can drift. Tear ReID down so the STOPPED status
        // broadcast flips the icon back to play and the LED gate restores.
        if (!connected && reidController?.isRunning == true) {
            stopReid("orchestrator_disconnected")
        }
    }

    override fun onTimeSync(epochMillis: Long, tzId: String) {
        // Hand off to the root-capable power daemon via a sentinel file.
        // Format: "<epochMillis>\n<tzId>\n". Power daemon's inotify picks it up
        // and calls clock_settime + setprop persist.sys.timezone.
        btLog("onTimeSync: epochMs=$epochMillis tz='$tzId'")
        try {
            // Same dir the power daemon watches for glasses-power.conf. App
            // (UID u0_a*) cannot write to /data/local/tmp/ on stock Android 12.
            // Ensure parent exists -- on a fresh reflash the daemon may not
            // have run yet to create the dir.
            val f = java.io.File("/data/local/diy-overlay/glasses-time.sync")
            f.parentFile?.mkdirs()
            f.writeText("$epochMillis\n$tzId\n")
        } catch (e: Exception) {
            btErr("onTimeSync write failed: ${e.message}")
        }
    }

    override fun onWeatherUpdate(icon: String, tempC: String, location: String) {
        btLog("onWeatherUpdate icon=$icon temp=$tempC loc=$location")
        sendBroadcast(Intent(ACTION_WEATHER_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_WEATHER_ICON, icon)
            putExtra(EXTRA_WEATHER_TEMP, tempC)
            putExtra(EXTRA_WEATHER_LOCATION, location)
        })
    }

    override fun onContactsHash(agMac: String, hash: String) {
        try {
            if (contactsCache.isFresh(agMac, hash)) {
                btLog("Contacts cache fresh for agMac=$agMac (no resync)")
            } else {
                btLog("Contacts cache stale or missing for agMac=$agMac -> requesting full sync")
                btClient.requestContactsFull(agMac)
            }
            // Tell the call controller about the active AG so it can lookup names.
            callController.setActiveAg(agMac, contactsCache)
        } catch (e: Exception) {
            btErr("onContactsHash failed: ${e.message}")
        }
    }

    override fun onContactsList(agMac: String, hash: String, json: String) {
        try {
            contactsCache.replace(agMac, hash, json)
            btLog("Contacts cache replaced for agMac=$agMac (${json.length} chars)")
            callController.setActiveAg(agMac, contactsCache)
        } catch (e: Exception) {
            btErr("onContactsList failed: ${e.message}")
        }
    }

    // --- Broadcasting ---

    private fun broadcastBtState(connected: Boolean) {
        sendBroadcast(Intent(ACTION_BT_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_BT_CONNECTED, connected)
        })
    }

    private fun broadcastDebugStatus(status: String) {
        sendBroadcast(Intent(ACTION_DEBUG_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_DEBUG_STATUS, status)
        })
    }

    private fun broadcastChatMessage(requestId: String, role: String, text: String) {
        val json = JSONObject().apply {
            put("requestId", requestId)
            put("role", role)
            put("text", text)
        }.toString()
        sendBroadcast(Intent(ACTION_CHAT_MESSAGE).apply {
            setPackage(packageName)
            putExtra(EXTRA_CHAT_MESSAGE, json)
        })
    }

    private fun broadcastResponseMeta(requestId: String, responseTimeMs: Long, tokenCount: Int) {
        sendBroadcast(Intent(ACTION_RESPONSE_META).apply {
            setPackage(packageName)
            putExtra(EXTRA_RESPONSE_META, JSONObject().apply {
                put("requestId", requestId)
                put("responseTimeMs", responseTimeMs)
                put("tokenCount", tokenCount)
            }.toString())
        })
    }

    // --- Foreground Service ---

    private fun promoteToForeground() {
        val channel = NotificationChannel(
            FG_CHANNEL_ID, "Background Service",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Keeps service alive"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)

        val notif = NotificationCompat.Builder(this, FG_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setShowWhen(false)
            .setSilent(true)
            .build()
        Log.d(TAG_FG, "event=foreground_start id=$FG_NOTIF_ID")
        // API 30+ requires the runtime call to declare the same fg service type as the manifest.
        // Without explicit MICROPHONE here, AudioFlinger silently delivers zero-PCM samples while
        // MainActivity isn't in the foreground (Android 12+ mic privacy gate caches RECORD_AUDIO
        // appop as "foreground only" at the UID). The manifest also declares only "microphone";
        // the Rokid Android 12 build's manifest parser silently drops the "specialUse" token from
        // a combined "specialUse|microphone" enum-flag, so we keep only what we actually need.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(
                FG_NOTIF_ID,
                notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(FG_NOTIF_ID, notif)
        }
    }

    // --- Wake Lock ---
    //
    // The service wakelock is now CONDITIONAL. Held only when we actually need to
    // keep the kernel awake to do work or react quickly:
    //   - worn AND a peer (phone/pc audio) is connected,
    //   - TTS playback is active (foreground or notification),
    //   - wake-word pipeline is running (worn + phone, gated elsewhere),
    //   - a live-utterance session is open.
    // Off-head with no peer and no TTS / WW / live session: release the wakelock
    // and let the kernel suspend. Any incoming BLE GATT notify wakes the kernel
    // briefly to deliver the callback; the handler can re-acquire if needed.

    private var wakeLock: PowerManager.WakeLock? = null

    private fun ensureServiceWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlassesListener::Service").apply {
                setReferenceCounted(false)
            }
        }
    }

    private fun reconcileServiceWakeLock(reason: String) {
        ensureServiceWakeLock()
        val needed = isServiceWakeNeeded()
        val held = wakeLock?.isHeld == true
        btLog("[SvcWake] reason=$reason needed=$needed held=$held")
        if (needed && !held) {
            try {
                wakeLock?.acquire()
                Log.d(TAG_WAKE, "event=wakelock_acquire type=PARTIAL reason=$reason")
            } catch (t: Throwable) {
                btErr("[SvcWake] acquire failed: ${t.message}")
            }
        } else if (!needed && held) {
            try {
                wakeLock?.release()
                Log.d(TAG_WAKE, "event=wakelock_release reason=$reason")
            } catch (t: Throwable) {
                btErr("[SvcWake] release failed: ${t.message}")
            }
        }
        GT.counter("svc.wakelock.held", if (needed) 1L else 0L)
    }

    private fun isServiceWakeNeeded(): Boolean {
        val worn = lastWornState != false   // null treated as worn (safer default)
        val phone = phoneAudioConnected
        val pc = pcAudioConnected
        val tts = ttsIsPlaying
        val notifTts = notifTtsPlaying
        val ww = ::wakeWordPipeline.isInitialized && wakeWordPipeline.isRunning()
        val live = streamMode == StreamMode.LIVE_UTTERANCE
        return (worn && (phone || pc)) || tts || notifTts || ww || live
    }

    private fun broadcastState(state: String) {
        sendBroadcast(Intent(ACTION_STATE_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATE, state)
        })
    }

    // --- Chat list relay ---

    private val newChatRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            btLog("New chat request from UI -- sending to phone")
            btClient.sendNewChat()
        }
    }

    private val chatListRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            btLog("Chat list request from UI -- sending to phone")
            btClient.sendChatListRequest()
        }
    }

    private val switchChatReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val conversationId = intent.getStringExtra("conversation_id") ?: return
            btLog("Switch chat request from UI: $conversationId")
            btClient.sendSwitchChat(conversationId)
        }
    }

    // --- Todo / Telegram relay ---

    private val todoListRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            btLog("Todo list request from UI -- sending to phone")
            btClient.sendTodoListRequest()
        }
    }

    private val todoToggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getStringExtra(EXTRA_TODO_ID) ?: return
            btLog("Todo toggle from UI: $id")
            btClient.sendTodoToggle(id)
        }
    }

    private val todoAddReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val text = intent.getStringExtra(EXTRA_TODO_TEXT) ?: return
            btLog("Todo add from UI: ${text.take(40)}")
            btClient.sendTodoAdd(text)
        }
    }

    private val todoRemoveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getStringExtra(EXTRA_TODO_ID) ?: return
            btLog("Todo remove from UI: $id")
            btClient.sendTodoRemove(id)
        }
    }

    private val alarmListRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            btClient.sendAlarmListRequest()
        }
    }

    private val jobListRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            btClient.sendJobListRequest()
        }
    }

    // --- Telegram chat receivers ---

    private val tgChatListRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val limit = intent.getIntExtra(EXTRA_TG_LIMIT, 20)
            android.util.Log.d("TG_DEBUG", "ListenerService received TG chat list request (limit=$limit)")
            btLog("TG chat list request from UI (limit=$limit)")
            btClient.sendTgChatListRequest(limit)
        }
    }

    private val tgMessagesRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val chatId = intent.getStringExtra(EXTRA_TG_CHAT_ID) ?: run {
                android.util.Log.e("TG_DEBUG", "tgMessagesRequest: EXTRA_TG_CHAT_ID is null!")
                return
            }
            val limit = intent.getIntExtra(EXTRA_TG_LIMIT, 30)
            val topicId = intent.getIntExtra(EXTRA_TG_TOPIC_ID, 0)
            val offsetId = intent.getIntExtra(EXTRA_TG_OFFSET_ID, 0)
            android.util.Log.d("TG_DEBUG", "ListenerService: TG messages request chatId=$chatId limit=$limit offsetId=$offsetId")
            btLog("TG messages request from UI: chatId=$chatId limit=$limit offsetId=$offsetId")
            btClient.sendTgMessagesRequest(chatId, limit, topicId, offsetId)
        }
    }

    private val tgTopicsRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val chatId = intent.getStringExtra(EXTRA_TG_CHAT_ID) ?: return
            btLog("TG topics request from UI: chatId=$chatId")
            btClient.sendTgTopicsRequest(chatId)
        }
    }

    private val tgSendMsgReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val chatId = intent.getStringExtra(EXTRA_TG_CHAT_ID) ?: return
            val text = intent.getStringExtra(EXTRA_TG_TEXT) ?: return
            val topicId = intent.getIntExtra(EXTRA_TG_TOPIC_ID, 0)
            btLog("TG send message from UI: chatId=$chatId topicId=$topicId text=${text.take(40)}")
            // Ensure voice session is ended (covers all send paths)
            if (telegramVoiceActive) {
                telegramVoiceActive = false
                updateDuckState()
                btClient.sendTgVoiceStop()
            }
            btClient.sendTgSendRequest(chatId, text, topicId)
        }
    }

    private val tgSubscribeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            btLog("TG subscribe from UI")
            btClient.sendTgSubscribe()
        }
    }

    private val tgUnsubscribeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            btLog("TG unsubscribe from UI")
            btClient.sendTgUnsubscribe()
        }
    }

    private val tgOpenChatReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val chatId = intent.getStringExtra(EXTRA_TG_CHAT_ID) ?: return
            val chatTitle = intent.getStringExtra(EXTRA_TG_CHAT_TITLE) ?: ""
            btLog("TG open chat from UI: $chatId ($chatTitle)")
            currentOpenTgChatTitle = chatTitle
            btClient.sendTgOpenChat(chatId, chatTitle)
        }
    }

    private val tgCloseChatReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            btLog("TG close chat from UI")
            currentOpenTgChatTitle = null
            btClient.sendTgCloseChat()
        }
    }

    @Volatile private var telegramVoiceActive = false

    private val tgVoiceStartReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val chatId = intent.getStringExtra(EXTRA_TG_CHAT_ID) ?: return
            btLog("TG voice start from UI: chatId=$chatId")
            mediaPlayingSnapshot = mediaSessionMonitor.isPlaying
            telegramVoiceActive = true
            updateDuckState()
            btClient.sendTgVoiceStart(chatId)
        }
    }

    private val tgVoiceStopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            btLog("TG voice stop from UI")
            telegramVoiceActive = false
            updateDuckState()
            btClient.sendTgVoiceStop()
        }
    }

    // --- Notification-solo backdrop ---

    // Whether the currently-displayed notification session entered the solo path
    // (screen-was-off backdrop). Set when SOLO_SHOW is broadcast, cleared when the
    // session ends. Drives whether onAllDismissed broadcasts SOLO_END to MainActivity.
    @Volatile private var notifSoloSessionActive = false

    private val notifDisplayManager by lazy {
        getSystemService(DISPLAY_SERVICE) as android.hardware.display.DisplayManager
    }

    // MainActivity -> service: user pressed a key during a solo notification. MainActivity
    // has already restored its own content (it owns the blackout); this just marks the solo
    // session over so onAllDismissed does not re-broadcast a redundant SOLO_END race.
    private val notifSoloRevealReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            btLog("[NSOLO] SOLO_REVEAL received -- solo session revealed by user")
            notifSoloSessionActive = false
        }
    }

    // --- Notification hold-to-reply ---

    @Volatile private var notifReplyId: String? = null

    // Reply-result correlation: after the glasses ask the phone to send a reply we
    // enter a SENDING state and wait for CH_NOTIF_REPLY_RESULT (or a timeout) before
    // committing the overlay to SENT / FAILED. notifReplyAwaitingResult holds the
    // notifId we are waiting on; a result for any other id is stale/ignored. The
    // result callback and the timeout both route through finalizeReplyResult(), which
    // is guarded so only the first one wins.
    @Volatile private var notifReplyAwaitingResult: String? = null
    private val REPLY_RESULT_TIMEOUT_MS = 6000L
    private val replyResultTimeoutRunnable = Runnable {
        btLog("[Notif] Reply result timeout for ${notifReplyAwaitingResult ?: "?"} -> FAILED")
        finalizeReplyResult(false)
    }

    // Hold-to-arm, hands-free reply: a ~0.3s hold fills the arm bar; at 100% the
    // gesture commits into LISTENING and the finger may release -- recording runs
    // hands-free until the phone's VAD detects end-of-speech. The final transcript
    // then opens a 3s double-tap-to-cancel SENDING window (owned by MainActivity)
    // before the RemoteInput actually fires. All reply visuals live inside the
    // notification overlay rectangle.

    private val notifHoldProgressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Single one-shot signal: start the self-animated hold fill in the
            // overlay over the arm duration. Avoids the 60Hz cross-process
            // broadcast storm that made the bar jitter. The LISTENING transition
            // is driven separately by ACTION_NOTIF_REPLY_START on commit.
            val durationMs = intent.getLongExtra(EXTRA_HOLD_DURATION, 1000L)
            btLog("[NREPLY] svc hold-fill start, durationMs=$durationMs")
            notificationOverlay.startHoldFill(durationMs)
        }
    }

    private val notifHoldFreezeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val freeze = intent.getBooleanExtra(EXTRA_FREEZE, false)
            if (freeze) {
                notificationOverlay.freezeDismiss()
            } else {
                // Early release before commit: drop back to IDLE, resume dismiss.
                notificationOverlay.cancelHoldFill()
                notificationOverlay.setReplyPhase(NotificationOverlay.ReplyPhase.IDLE)
                notificationOverlay.setReplyProgress(0f)
                notificationOverlay.unfreezeDismiss()
            }
        }
    }

    private val notifReplyStartReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val notifId = intent.getStringExtra(EXTRA_NOTIF_ID) ?: return
            btLog("[NREPLY] svc Reply START: $notifId state=$state micStreaming=$micStreaming phoneAudioConnected=$phoneAudioConnected")
            notifReplyId = notifId
            // A hold-to-reply runs 5-21s, outliving the timed FULL_WAKE_LOCK that
            // wakeScreenForNotification() acquired (14s hard cap for repliables).
            // Swap it for an UNTIMED lock so the screen stays on until the overlay
            // actually leaves the screen (released in onAllDismissed). Only do this
            // when on-head (a display exists to keep awake).
            if (lastWornState != false) {
                try {
                    val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    notifScreenLock?.let { if (it.isHeld) it.release() }
                    @Suppress("DEPRECATION")
                    notifScreenLock = pm.newWakeLock(
                        android.os.PowerManager.FULL_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "GlassesListener::NotifScreen"
                    ).apply { acquire() }
                    notifWokeScreen = true
                    notifReplyHoldingScreen = true
                    btLog("[NREPLY] acquired untimed screen lock for reply")
                } catch (e: Exception) {
                    btLog("[NREPLY] untimed screen lock acquire failed: ${e.message}")
                }
            }
            mediaPlayingSnapshot = mediaSessionMonitor.isPlaying
            telegramVoiceActive = true
            updateDuckState()
            // Open the glasses->phone audio path so the phone transcriber
            // actually receives speech. tg-chat voice can skip this because a
            // chat session already has the live stream up; a notif reply may
            // start from idle/screen-off with no stream, so force it open just
            // like activateListening() does for AI chat. Without this the phone
            // gets zero audio and the release "sends" an empty transcript.
            signalAudioStart("notif-reply", force = true, durationMs = 0L)
            // Do NOT prepend a prebuffer snapshot here. The prebuffer holds the ~4s
            // BEFORE reply-start (ambient + TTS tail) -- it contains none of the
            // user's reply and, flushed realtime-paced, it injected seconds of stale
            // audio at the head of the utterance: Azure spent the first ~4-8s on junk,
            // dropping the user's first sentences and delaying partials. The user's
            // actual first words live in the live stream and, during any cold-connect
            // gap, in pendingAudioFrames (drained on connect) -- never in the
            // pre-reply rolling buffer. Clear any stale pending snapshot so a prior
            // wake-word capture can't leak into this reply.
            notifReplyPrebufferFlushed = true
            pendingPrebufferSnapshot.set(null)
            // The overlay owns all reply visuals: enter LISTENING and animate.
            notificationOverlay.freezeDismiss()
            notificationOverlay.setReplyPhase(NotificationOverlay.ReplyPhase.LISTENING)
            notificationOverlay.setReplyTranscript("")
            btClient.sendNotifReplyStart(notifId)
        }
    }

    private val notifReplySendReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val notifId = intent.getStringExtra(EXTRA_NOTIF_ID) ?: notifReplyId ?: return
            val text = intent.getStringExtra(EXTRA_REPLY_TEXT) ?: ""
            btLog("[Notif] Reply send (3s window elapsed): $notifId -> ${text.take(40)}")
            // Ask the phone to fire the RemoteInput, but do NOT claim SENT yet. The
            // phone replies on CH_NOTIF_REPLY_RESULT once it actually fired (or
            // failed). We show a brief SENDING state and wait for that result (or a
            // timeout) before committing to SENT / FAILED.
            btClient.sendNotifReplySend(notifId, text)
            // Stop streaming our mic -- the utterance is captured. Keep the screen
            // lock held and notifReplyId SET until the result lands so the teardown
            // (lock re-arm, queue advance) runs exactly once in finalizeReplyResult.
            telegramVoiceActive = false
            stopGlassesAudioStream("notif-reply-send")
            updateDuckState()
            notifReplyAwaitingResult = notifId
            notificationOverlay.freezeDismiss()
            notificationOverlay.setReplyTranscript(text)
            notificationOverlay.setReplyPhase(NotificationOverlay.ReplyPhase.SENDING)
            // Arm the result timeout. If the phone never confirms, treat as FAILED.
            notifHandler.removeCallbacks(replyResultTimeoutRunnable)
            notifHandler.postDelayed(replyResultTimeoutRunnable, REPLY_RESULT_TIMEOUT_MS)
        }
    }

    /**
     * Commit the reply outcome to the overlay exactly once. Called by either the
     * phone's CH_NOTIF_REPLY_RESULT callback or the result timeout. Guarded on
     * notifReplyAwaitingResult so a late result and the timeout cannot both run.
     */
    private fun finalizeReplyResult(ok: Boolean) {
        val awaited = notifReplyAwaitingResult ?: return
        notifReplyAwaitingResult = null
        notifHandler.removeCallbacks(replyResultTimeoutRunnable)
        btLog("[Notif] finalize reply $awaited ok=$ok")
        notifReplyId = null
        // Reply terminal: wantAudioStream was already cleared when the audio path was
        // torn down (notif-reply-send / cancel / final). With notifReplyId now null,
        // both wantedAudio flags are clear -- re-evaluate mic demand so the mic stops
        // promptly when nothing else wants it.
        reconcileMicStream("reply-end")
        // Reply is ending: hand the untimed reply screen lock back to a normal
        // TIMED lock for the overlay's remaining on-screen time. Clearing the flag
        // first lets wakeScreenForNotification re-arm (it would otherwise
        // early-return on notifReplyHoldingScreen). wakeScreenForNotification
        // releases the old lock before acquiring, so there is no double-hold.
        notifReplyHoldingScreen = false
        wakeScreenForNotification(notificationOverlay.currentData?.repliable ?: false)
        if (ok) {
            notificationOverlay.setReplyPhase(NotificationOverlay.ReplyPhase.SENT)
        } else {
            notificationOverlay.setReplyPhase(NotificationOverlay.ReplyPhase.FAILED)
        }
        // Keep the result visible briefly, then end the notification fully: unfreeze
        // then dismiss so the overlay's onItemDismissed fires and the notification
        // done path advances the queue. FAILED lingers a touch longer to be read.
        val lingerMs = if (ok) 1200L else 1500L
        notifHandler.postDelayed({
            notificationOverlay.unfreezeDismiss()
            notificationOverlay.dismiss()
        }, lingerMs)
    }

    private val notifReplyCancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val notifId = intent.getStringExtra(EXTRA_NOTIF_ID) ?: notifReplyId ?: return
            btLog("[Notif] Reply cancel: $notifId")
            btClient.sendNotifReplyCancel(notifId)
            telegramVoiceActive = false
            stopGlassesAudioStream("notif-reply-cancel")
            updateDuckState()
            // A user cancel (swipe / double-tap in SENDING) wins over any pending
            // reply-result: disarm the timeout and stop awaiting so a late result
            // can't fire into the torn-down reply.
            notifReplyAwaitingResult = null
            notifHandler.removeCallbacks(replyResultTimeoutRunnable)
            notifReplyId = null
            // Reply terminal: stopGlassesAudioStream above cleared wantAudioStream and
            // this clears notifReplyId, so both wantedAudio flags are now clear.
            // Re-evaluate mic demand so the mic stops promptly when nothing else wants it.
            reconcileMicStream("reply-end")
            // Reply terminal: swap the untimed reply lock back to a timed lock for
            // the remaining overlay window (clear flag first so the re-arm runs).
            notifReplyHoldingScreen = false
            wakeScreenForNotification(notificationOverlay.currentData?.repliable ?: false)
            // Cancelled (mid-listening swipe, or double-tap in the send window):
            // show CANCELLED briefly then dismiss so the queue advances.
            notificationOverlay.setReplyPhase(NotificationOverlay.ReplyPhase.CANCELLED)
            notifHandler.postDelayed({
                notificationOverlay.unfreezeDismiss()
                notificationOverlay.dismiss()
            }, 1200L)
        }
    }

    // TEST-ONLY: inject a notification through the REAL onNotification() path so the
    // production solo decision (screenWasOff && worn) actually runs on-device without a
    // phone. Registered NOT_EXPORTED (see registration site) -- fire it IN-PROCESS only,
    // e.g. from an instrumented androidTest in this UID:
    //   context.sendBroadcast(Intent(ACTION_NOTIFICATION_TEST)
    //       .setPackage("com.repository.glasses.listener")
    //       .putExtra("sender", "Ana K").putExtra("text", "test")
    //       .putExtra("repliable", false).putExtra("force_worn", true))
    private val notificationTestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val sender = intent.getStringExtra("sender") ?: "Test Sender"
            val text = intent.getStringExtra("text") ?: "Test notification body"
            val chat = intent.getStringExtra("chat") ?: sender
            val repliable = intent.getBooleanExtra("repliable", false)
            // TEST-ONLY: optionally force the canonical worn signal so the solo guard
            // (screenWasOff && lastWornState==true) can be exercised on a desk device
            // where fold detection may not have fired. Real builds never carry this extra.
            if (intent.hasExtra("force_worn")) {
                lastWornState = intent.getBooleanExtra("force_worn", true)
                btLog("[NSOLO][TEST] forced lastWornState=$lastWornState")
            }
            val notifId = "test-" + System.currentTimeMillis()
            btLog("[NSOLO][TEST] injecting onNotification id=$notifId sender=$sender repliable=$repliable")
            onNotification(notifId, sender, text, chat, repliable)
        }
    }

    // --- Debug demo: scripts the overlay through all reply phases ------------
    // Purely for recording the look without real speech. No BT / transcriber
    // calls are made; everything runs on notifHandler with sample data.
    private val notifReplyDemoHandler = Handler(Looper.getMainLooper())
    private val notifReplyDemoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            btLog("[Notif] Reply DEMO triggered")
            notifReplyDemoHandler.removeCallbacksAndMessages(null)
            val h = notifReplyDemoHandler

            // One scripted reply pass on the overlay. cancel=true simulates a
            // double-tap inside the send window (-> CANCELLED); cancel=false lets
            // the 3s window elapse (-> SENT). Returns the wall time it finishes.
            fun runPass(base: Long, sender: String, msg: String, reply: String, cancel: Boolean): Long {
                val demoId = "demo-" + System.currentTimeMillis() + "-" + base
                h.postDelayed({
                    notificationOverlay.show(NotificationOverlay.NotificationData(demoId, sender, msg, sender, true), false)
                    notificationOverlay.freezeDismiss()
                    notificationOverlay.setReplyPhase(NotificationOverlay.ReplyPhase.IDLE)
                }, base)
                // HOLDING: ramp progress 0 -> 1 over 0.9s, starting 1.2s in. The
                // mic + bar fade in as the hold begins (overlay-side).
                val holdStart = base + 1200L
                val steps = 18
                for (i in 0..steps) {
                    val frac = i / steps.toFloat()
                    h.postDelayed({
                        notificationOverlay.setReplyPhase(NotificationOverlay.ReplyPhase.HOLDING)
                        notificationOverlay.setReplyProgress(frac)
                    }, holdStart + (900L * i / steps))
                }
                // LISTENING; type out the reply.
                val listenStart = holdStart + 900L
                h.postDelayed({
                    notificationOverlay.setReplyPhase(NotificationOverlay.ReplyPhase.LISTENING)
                    notificationOverlay.setReplyTranscript("")
                }, listenStart)
                val typeDuration = 2600L
                for (i in 1..reply.length) {
                    val partial = reply.substring(0, i)
                    h.postDelayed({ notificationOverlay.setReplyTranscript(partial) },
                        listenStart + (typeDuration * i / reply.length))
                }
                // Release -> SENDING. The overlay self-runs the 3s countdown
                // bar + number ("DOUBLE-TAP TO CANCEL  N").
                val sendStart = listenStart + typeDuration + 200L
                h.postDelayed({
                    notificationOverlay.setReplyTranscript(reply)
                    notificationOverlay.setReplyPhase(NotificationOverlay.ReplyPhase.SENDING)
                }, sendStart)
                val resolveStart: Long
                if (cancel) {
                    // Double-tap ~1.4s into the window -> CANCELLED indicator.
                    resolveStart = sendStart + 1400L
                    h.postDelayed({
                        notificationOverlay.setReplyPhase(NotificationOverlay.ReplyPhase.CANCELLED)
                    }, resolveStart)
                } else {
                    // Window elapses (3s) with no cancel -> SENT.
                    resolveStart = sendStart + 3000L
                    h.postDelayed({
                        notificationOverlay.setReplyPhase(NotificationOverlay.ReplyPhase.SENT)
                    }, resolveStart)
                }
                val endAt = resolveStart + 1800L
                h.postDelayed({
                    notificationOverlay.unfreezeDismiss()
                    notificationOverlay.dismiss()
                }, endAt)
                return endAt
            }

            // Pass 1: the happy path -- countdown elapses -> SENT.
            val firstEnd = runPass(
                0L,
                "ANA K.",
                "are you still coming to the kickoff? we pushed it to thursday 10am and need your slides",
                "on my way - bringing the slides",
                cancel = false)

            // Pass 2: double-tap inside the window -> CANCELLED.
            runPass(
                firstEnd + 600L,
                "DScene crew",
                "drop the new mix tonight? the floor is asking for it",
                "give me an hour - mastering now",
                cancel = true)
        }
    }

    // --- Debug: reproduce the "2nd notification dimmed + cut off" render bug ---
    // Fires two telegram-style notifications through the REAL onNotification path
    // and dumps the overlay view tree after each settles. The gap between them is
    // controllable via the "gap_ms" extra so both timings can be exercised:
    //   gap < first dismiss delay  -> 2nd is QUEUED (dismissCurrent -> showNext recycle path)
    //   gap > first dismiss delay  -> 2nd shows fresh after 1st auto-dismissed
    // Default gap 1500ms = queued-while-showing (the reported repro).
    // --- Teleprompter ---

    private val teleprompterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val reqId = activeTeleprompterRequestId ?: return
            val stateJson = intent.getStringExtra("state_json") ?: return
            btLog("Teleprompter state relay: $stateJson")
            btClient.sendCommandResult(reqId, stateJson)
            // Clear on terminal states
            val state = try { JSONObject(stateJson).optString("state", "") } catch (_: Exception) { "" }
            if (state in setOf("stopped", "finished")) {
                btLog("Teleprompter terminal state: $state -- clearing active request")
                activeTeleprompterRequestId = null
            }
        }
    }

    // ReID start/stop wrappers. The listener no longer owns the camera (frames are streamed
    // from the capture process over AIDL), so there is no camera LED to gate here -- the capture
    // process owns the privacy light for its own camera session.
    private fun startReid() {
        reidController?.start(this@ListenerService)
    }

    private fun stopReid(reason: String) {
        val wasRunning = reidController?.isRunning == true
        if (wasRunning) btLog("Reid stop: reason=$reason")
        reidController?.stop()
    }

    private val reidStartReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            btLog("Reid start requested by activity")
            if (androidx.core.content.ContextCompat.checkSelfPermission(this@ListenerService, android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                btLog("Camera permission missing, requesting from activity")
                sendBroadcast(Intent(ACTION_REQUEST_CAMERA_PERMISSION).apply { setPackage(packageName) })
                return
            }
            startReid()
        }
    }

    private val cameraPermGrantedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            btLog("Camera permission granted, starting reid")
            startReid()
        }
    }

    private val reidStopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            btLog("Reid stop requested by activity")
            stopReid("user")
        }
    }

    private val reidPersonRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val uid = intent?.getStringExtra(EXTRA_REID_PERSON_UID) ?: return
            btLog("Reid person request from UI: $uid")
            btClient.sendReidPersonRequest(uid)
        }
    }

    private val cancelSessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            btLog("Cancel session from UI, state=$state")
            // Always notify phone of cancel (covers CONFIRMING state on phone side)
            btClient.sendStatus("CANCEL_CONFIRM")
            when (state) {
                State.LISTENING -> {
                    // Don't stop mic stream on cancel -- it needs to keep running
                    // in IDLE for wake word detection
                    transitionToIdle()
                }
                State.RESPONDING -> {
                    cancelledRequestId = currentRequestId
                    ttsPlayer.interrupt()
                    currentRequestId?.let { btClient.sendTtsInterrupt(it) }
                    transitionToIdle()
                }
                State.IDLE -> {
                    // May be CONFIRMING on phone side -- force IDLE broadcast to reset glasses UI
                    broadcastState("IDLE")
                }
            }
        }
    }

    // --- RFCOMM Mouse (head-tracking via phone -> PC BT Classic HID) ---

    private var rfcommMouseTracking = false

    private val rfcommMouseEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!rfcommMouseActive) return
            val toggle = intent.getBooleanExtra("toggle", false)
            if (toggle) {
                toggleRfcommMouseTracking()
                return
            }
            if (!rfcommMouseTracking) return
            val click = intent.getIntExtra("click", 0)
            val scroll = intent.getIntExtra("scroll", 0)
            if (click != 0) {
                rfcommMouseButtons = click
                rfcommMouseDirty = true
                rfcommMouseHandler.removeCallbacks(rfcommMouseFlushRunnable)
                flushRfcommMouse()
                rfcommMouseHandler.postDelayed({
                    rfcommMouseButtons = 0
                    rfcommMouseDirty = true
                    flushRfcommMouse()
                }, 50)
            }
            if (scroll != 0) {
                rfcommMouseScroll += scroll
                if (!rfcommMouseDirty) {
                    rfcommMouseDirty = true
                    rfcommMouseHandler.postDelayed(rfcommMouseFlushRunnable, 32)
                }
            }
        }
    }

    private fun startRfcommMouse(sensX: Float, sensY: Float) {
        if (rfcommMouseActive) stopRfcommMouse()
        rfcommMouseActive = true
        rfcommMouseTracking = false
        val tracker = com.repository.glasses.listener.mouse.HeadTracker(this)
        tracker.sensitivityX = sensX
        tracker.sensitivityY = sensY
        tracker.listener = object : com.repository.glasses.listener.mouse.HeadTracker.Listener {
            override fun onHeadMove(dx: Float, dy: Float) {
                if (!rfcommMouseTracking) return
                rfcommMouseDx += dx
                rfcommMouseDy += dy
                if (!rfcommMouseDirty) {
                    rfcommMouseDirty = true
                    rfcommMouseHandler.postDelayed(rfcommMouseFlushRunnable, 32)
                }
            }
        }
        // Don't start sensor yet -- tracking begins on toggle (tap on glasses)
        rfcommMouseTracker = tracker
        registerReceiver(rfcommMouseEventReceiver, IntentFilter(ACTION_RFCOMM_MOUSE_EVENT), null, rfcommMouseHandler)
        btLog("RFCOMM mouse started (sensX=$sensX sensY=$sensY), tracking=OFF (tap to start)")
    }

    private fun toggleRfcommMouseTracking() {
        rfcommMouseTracking = !rfcommMouseTracking
        if (rfcommMouseTracking) {
            rfcommMouseTracker?.start()
        } else {
            rfcommMouseTracker?.stop()
            rfcommMouseHandler.removeCallbacks(rfcommMouseFlushRunnable)
            rfcommMouseDx = 0f; rfcommMouseDy = 0f; rfcommMouseScroll = 0
            rfcommMouseButtons = 0; rfcommMouseDirty = false
        }
        btLog("RFCOMM mouse tracking ${if (rfcommMouseTracking) "ON" else "OFF"}")
    }

    private fun stopRfcommMouse() {
        rfcommMouseActive = false
        rfcommMouseTracking = false
        rfcommMouseTracker?.stop()
        rfcommMouseTracker = null
        rfcommMouseHandler.removeCallbacks(rfcommMouseFlushRunnable)
        rfcommMouseDx = 0f; rfcommMouseDy = 0f; rfcommMouseScroll = 0
        rfcommMouseButtons = 0; rfcommMouseDirty = false
        try { unregisterReceiver(rfcommMouseEventReceiver) } catch (_: Exception) {}
        btLog("RFCOMM mouse stopped")
    }

    private fun flushRfcommMouse() {
        if (!rfcommMouseDirty || !rfcommMouseActive) return
        val dx = rfcommMouseDx.toInt()
        val dy = rfcommMouseDy.toInt()
        rfcommMouseDx -= dx.toFloat()
        rfcommMouseDy -= dy.toFloat()
        val scroll = rfcommMouseScroll
        rfcommMouseScroll = 0
        val buttons = rfcommMouseButtons
        rfcommMouseDirty = false
        if (dx == 0 && dy == 0 && scroll == 0 && buttons == 0) return
        val report = buildMouseReport(buttons, dx.coerceIn(-32767, 32767), dy.coerceIn(-32767, 32767), scroll.coerceIn(-127, 127))
        val b64 = android.util.Base64.encodeToString(report, android.util.Base64.NO_WRAP)
        btClient.sendMouseReport(b64)
    }

    private fun buildMouseReport(buttons: Int, dx: Int, dy: Int, scroll: Int): ByteArray {
        val r = ByteArray(6)
        r[0] = (buttons and 0x07).toByte()
        r[1] = (dx and 0xFF).toByte()
        r[2] = ((dx shr 8) and 0xFF).toByte()
        r[3] = (dy and 0xFF).toByte()
        r[4] = ((dy shr 8) and 0xFF).toByte()
        r[5] = scroll.toByte()
        return r
    }

    // --- Lifecycle ---

    override fun onDestroy() = GT.section("svc.onDestroy") {
        Log.d(TAG_LIFE, "event=onDestroy")
        btLog("Service destroying")
        stopRfcommMouse()
        for (obs in dcimObservers) { try { obs.stopWatching() } catch (_: Throwable) {} }
        dcimObservers.clear()
        notifHandler.removeCallbacks(notifLockScreenRunnable)
        // Force-release service wakelock on destroy regardless of state.
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        wakeLock = null
        // Force-release the notification screen lock (timed or untimed) on destroy.
        try { notifScreenLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        notifScreenLock = null
        notifWokeScreen = false
        notifReplyHoldingScreen = false
        autoStopRunnable?.let { watchdogHandler.removeCallbacks(it) }
        watchdogHandler.removeCallbacks(watchdogRunnable)
        recordingStatusResyncHandler.removeCallbacks(recordingStatusResyncRunnable)
        ttsPlayer.release()
        notificationTtsPlayer.release()
        try { unregisterReceiver(newChatRequestReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(chatListRequestReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(switchChatReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(mapPinReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(mapTabVisibleReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(tabChangedReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(translationToggleReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(assistantToggleReceiver) } catch (_: Exception) {}
        try { if (assistantActive) assistantCardOverlay.hideAll() } catch (_: Exception) {}
        try { unregisterReceiver(stopJourneyReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(mediaCommandReceiver) } catch (_: Exception) {}
        try { btMediaSource?.stop() } catch (_: Exception) {}
        btMediaSource = null
        try { mediaSessionMonitor.stop() } catch (_: Exception) {}
        try { audioRouting?.stop() } catch (_: Exception) {}
        hideMapOverlay()
        try { loneController?.release() } catch (_: Exception) {}
        loneController = null
        try { loneAlertPlayer?.release() } catch (_: Exception) {}
        loneAlertPlayer = null
        try { unregisterReceiver(teleprompterStateReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(configChangedReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(foldChangedReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(nativeLegReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(bondStateReceiver) } catch (_: Exception) {}
        try { pairingHandler.removeCallbacks(pairingTimeoutRunnable) } catch (_: Exception) {}
        try { foldPollRunnable?.let { heartbeatHandler.removeCallbacks(it) } } catch (_: Exception) {}
        try { unregisterReceiver(cancelSessionReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(logRelayReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(screenRecordEventReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(testCommandReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(uiRecordStoppedReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(screenStateReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(reidStartReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(reidPersonRequestReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(reidStopReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(cameraPermGrantedReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(todoListRequestReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(todoToggleReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(todoAddReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(todoRemoveReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(alarmListRequestReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(jobListRequestReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(tgChatListRequestReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(tgMessagesRequestReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(tgTopicsRequestReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(tgSendMsgReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(tgSubscribeReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(tgUnsubscribeReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(tgOpenChatReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(tgCloseChatReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(tgVoiceStartReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(tgVoiceStopReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(notifHoldProgressReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(notifHoldFreezeReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(notifReplyStartReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(notifReplySendReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(notifReplyCancelReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(notifSoloRevealReceiver) } catch (_: Exception) {}
        notifReplyAwaitingResult = null
        notifHandler.removeCallbacks(replyResultTimeoutRunnable)
        try { unregisterReceiver(notifReplyDemoReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(notificationTestReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(batteryLedReceiver) } catch (_: Exception) {}
        try { batteryLedArmer?.stop() } catch (_: Exception) {}
        stopReid("onDestroy")
        stopPcAudioListener()
        disconnectPhoneAudio()
        // Unregister audio-pipeline receivers (wake-word hit, audio-archive sync,
        // debug test) BEFORE stopping the pipelines so a late-arriving broadcast does not try to
        // call into a released pipeline.
        try { unregisterReceiver(wakeWordHitReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(audioArchiveSyncReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(wakeWordTestReceiver) } catch (_: Exception) {}
        // Stop the on-glasses pipelines BEFORE the mic pump so they unsubscribe from
        // MicBus while the producer thread is still alive (clean shutdown order).
        try { if (::wakeWordPipeline.isInitialized) wakeWordPipeline.stop() } catch (_: Exception) {}
        try { if (::localOpusWriter.isInitialized) localOpusWriter.stop() } catch (_: Exception) {}
        try { reconcileExecutor.shutdownNow() } catch (_: Exception) {}
        // Clean shutdown of always-on mic pump + live-utterance timer.
        try { liveUtteranceHandler.removeCallbacks(liveUtteranceRevertRunnable) } catch (_: Exception) {}
        try { stopMicStream("onDestroy") } catch (_: Exception) {}
        try { btClient.shutdown() } catch (_: Exception) {}
        try { mapRelay?.let { it.listener = null; it.stop() } } catch (_: Exception) {}
        try { syncChannelHandler?.detach() } catch (_: Exception) {}
        try { fileSyncBridge.unbind() } catch (_: Exception) {}
        try { unregisterReceiver(fnKeyReceiver) } catch (_: Exception) {}
        try { if (::captureBridge.isInitialized) captureBridge.unbind() } catch (_: Exception) {}
        try { photoCallbackExecutor.shutdownNow() } catch (_: Exception) {}
        try { photoPreviewOverlay?.destroy() } catch (_: Exception) {}
        try { unregisterReceiver(callControlReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(debugCallReceiver) } catch (_: Exception) {}
        try { callController.stop() } catch (_: Exception) {}
        try { batteryReporter?.stop() } catch (_: Exception) {}
        try { glassesBatteryMonitor?.stop(this) } catch (_: Exception) {}
        try { btManagerBridge.unbind() } catch (_: Exception) {}
        try { rokidBridge.unbind() } catch (_: Exception) {}
        try { assistantSuppressor.release() } catch (_: Exception) {}
        try { unregisterReceiver(sensorLongPressReceiver) } catch (_: Exception) {}
        btClient.release()
        activatePlayer?.release()
        activatePlayer = null
        super.onDestroy()
    }
}
