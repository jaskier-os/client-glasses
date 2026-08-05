package com.repository.glasses.listener

import android.Manifest
import com.repository.glasses.listener.config.GlassesConfig
import android.animation.ValueAnimator
import android.os.SystemClock
import android.graphics.drawable.GradientDrawable
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.view.KeyEvent
import android.view.TextureView
import com.repository.glasses.listener.input.TouchpadAbsListener
import com.repository.glasses.listener.input.remote.InputOrigin
import com.repository.glasses.listener.input.remote.RemoteAction
import com.repository.glasses.listener.input.remote.RemoteActionGate
import com.repository.glasses.listener.input.remote.RemoteInputBridgeClient
import com.repository.glasses.listener.input.remote.RemoteInputEvent
import com.repository.glasses.listener.input.remote.RemoteInputSink
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.graphics.Outline
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.repository.glasses.listener.capture.ArCameraPreview
import com.repository.glasses.listener.nightvision.NightVisionML
import com.repository.glasses.listener.nightvision.NightVisionPreview
import com.repository.glasses.listener.service.ListenerService
import com.repository.glasses.tracing.GT
import com.repository.glasses.listener.util.LogCollector
import com.repository.glasses.listener.ui.Anim
import com.repository.glasses.listener.ui.BitmapUtils
import com.repository.glasses.listener.ui.ChatAdapter
import com.repository.glasses.listener.ui.ScrollDrainer
import com.repository.glasses.listener.ui.ChatListAdapter
import com.repository.glasses.listener.ui.ChatMessage
import com.repository.glasses.listener.ui.ChatSummaryItem
import com.repository.glasses.listener.ui.Lum
import com.repository.glasses.listener.ui.TabHighlightView
import com.repository.glasses.listener.ui.VerticalHighlightView
import com.repository.glasses.listener.ui.MessageItemAnimator
import com.repository.glasses.listener.ui.TabLoaderController
import com.repository.glasses.listener.ui.TeleprompterController
import com.repository.glasses.listener.ui.TodoChecklistAdapter
import com.repository.glasses.listener.ui.AlarmDisplayAdapter
import com.repository.glasses.listener.ui.AlarmDisplayItem
import com.repository.glasses.listener.ui.JobDisplayAdapter
import com.repository.glasses.listener.ui.JobDisplayItem
import com.repository.glasses.listener.ui.SelectableAdapter
import com.repository.glasses.listener.ui.NotificationOverlay
import com.repository.glasses.listener.ui.TelegramSavedAdapter
import com.repository.glasses.listener.ui.TelegramChatListAdapter
import com.repository.glasses.listener.ui.TelegramChatAdapter
import com.repository.glasses.listener.ui.TelegramChat
import com.repository.glasses.listener.ui.TodoItem
import com.repository.glasses.listener.ui.TelegramMessage
import android.graphics.Typeface
import android.util.TypedValue
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.AbsoluteSizeSpan
import android.text.Spanned
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import com.repository.glasses.listener.mouse.DpadInputHandler
import com.repository.glasses.listener.mouse.HidMouseService
import java.util.UUID

class MainActivity : AppCompatActivity(), RemoteInputSink {

    companion object {
        private const val TAG = "MainActivityUI"
        private const val FOLD_LEG_ACTION = "com.rokid.sprite.ACTION_LEG_STATUS_CHANGED"
        private const val DOUBLE_TAP_THRESHOLD_MS = 400L
        // Pre-daemon the stock touchpad fired one event per swipe so we
        // needed a throttle against nothing. With rokid-touchpad-daemon the
        // daemon already scales event count to finger velocity, so any
        // app-side throttle just swallows legitimate events during fast
        // swipes. Set to 0 -- daemon is the sole pacing authority.
        private const val SCROLL_THROTTLE_MS = 0L

        /**
         * Hard ceiling on how far ONE remote event may scroll, regardless of the detent count it
         * carries. Coalescing legitimately merges a few detents into one event, but the cap is
         * enforced by the receiver rather than by trusting the producer -- a source that claims a
         * large magnitude must not be able to fling the list past everything the user can track.
         */
        private const val MAX_REMOTE_SCROLL_STEPS = 8

        /** How long the "remote active" glyph lingers after the last acted-on remote event. */
        private const val REMOTE_GLYPH_LINGER_MS = 1500L
        private const val TAB_ICON_SCALE_SELECTED = 1.3f
        private const val TAB_ICON_SCALE_DEFAULT = 1.0f
        const val TAB_SLOT_DP = 29
        const val TAB_ICON_DP = 13
        const val TAB_BAR_COMPACT_DP = 30
        const val TAB_BAR_EXPANDED_DP = 50
        // The "expanded" layout keeps the tab pill pinned to the top of the
        // tab bar and moves status widgets to the bottom so they never
        // overlap. Keep it enabled unconditionally so the pill position is
        // stable regardless of how many tabs are currently active.
        const val TAB_OVERFLOW_THRESHOLD = -1
        private val CHAT_SCROLL_STEP_PX: Int by lazy { 80.dpToPx() }

        private fun Int.dpToPx(): Int =
            (this * Resources.getSystem().displayMetrics.density + 0.5f).toInt()
    }

    private fun uiLog(msg: String) {
        LogCollector.i(TAG, msg)
    }

    // --- Focus state machine ---
    enum class FocusState { TAB_NAV, CHAT_FOCUSED, LIST_FOCUSED, MAP_FOCUSED, MAP_ZOOM_FOCUSED, STOP_MODAL, STEPS_MODAL, TRANSLATE_FOCUSED, TELEPROMPTER_FOCUSED, REID_FOCUSED, REID_FACES_FOCUSED, REID_INTEL_MODAL, TODO_FOCUSED, NIGHTVISION_FOCUSED, MOUSE_FOCUSED, MUSIC_FOCUSED, TELEGRAM_LIST_FOCUSED, TELEGRAM_TOPICS_FOCUSED, TELEGRAM_CHAT_FOCUSED, TELEGRAM_RECORDING, TELEGRAM_PREVIEW, NOTIFICATION_REPLY, CALL_INCOMING, CALL_ACTIVE }

    private var focusState = FocusState.TAB_NAV

    // HFP call UI state (driven by broadcasts from CallController).
    private var callPhase: com.repository.glasses.listener.bt.CallController.CallPhase =
        com.repository.glasses.listener.bt.CallController.CallPhase.IDLE
    private var callStartedElapsedRealtime: Long = 0L
    private var callNumber: String = ""
    private var callName: String = ""
    private var previousFocusState: FocusState? = null
    // --- Notification hold-to-reply ---
    private var pendingNotifId: String? = null
    // Snapshot of the notification id taken when a reply starts. The whole reply
    // lifecycle (record/stop/send/cancel/abort) targets this id, not the live
    // pendingNotifId, so a newer repliable notification arriving mid-reply can
    // never redirect the in-flight reply to the wrong recipient.
    private var activeReplyNotifId: String? = null
    private var notificationRepliable = false
    private var notifReplyPrevFocus: FocusState? = null
    // Reply arming state machine (push-to-talk "release to send"). The touchpad
    // daemon emits a single momentary NUMPAD_3 when a press reaches 500ms (NOT a
    // sustained key), then a NUMPAD_2 when the finger lifts. A hold is modeled as:
    // NUMPAD_3 arms a progress animation; if a NUMPAD_2 (release) arrives before
    // the bar fills, cancel; if the bar fills first, commit into LISTENING. After
    // commit the recording is HANDS-FREE: the finger release (NUMPAD_2) is a no-op,
    // the user just speaks, and the phone's VAD auto-stops on end-of-speech. The
    // final transcript then drives the overlay into a 3s SENDING window where a
    // double-tap cancels before the RemoteInput actually fires.
    private var replyArming = false
    private val REPLY_ARM_MS = 300L
    private val replyArmHandler = android.os.Handler(android.os.Looper.getMainLooper())
    // Single delayed commit instead of a 16ms progress ticker. The overlay
    // self-animates the fill over REPLY_ARM_MS (told once at arm start), so we
    // only need one callback at the end to commit into LISTENING. This kills the
    // 60Hz cross-process broadcast storm that made the fill jitter.
    private val replyArmRunnable = object : Runnable {
        override fun run() {
            if (!replyArming) return
            commitReplyArm()
        }
    }
    // Post-transcript SENDING window. Once the phone's VAD finalizes the reply,
    // the overlay shows the final transcript and a 3s "DOUBLE-TAP TO CANCEL"
    // countdown. replySendPending marks that window; a double-tap during it
    // cancels, otherwise replySendRunnable fires the real send when it elapses.
    private var replySendPending = false
    private var pendingReplyText: String? = null
    private val REPLY_SEND_WINDOW_MS = 3000L
    private val replySendHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val replySendRunnable = Runnable { commitReplySend() }
    // Double-tap detection inside the SENDING window (independent of the TAB_NAV
    // screen-off double-tap, which only fires in TAB_NAV).
    private var lastReplyCancelTapMs: Long = 0L
    private var pendingCallAccept: Runnable? = null
    private var lastCallTapMs: Long = 0L
    private val callKeyHandler = android.os.Handler(android.os.Looper.getMainLooper())
    // Source of truth for the selected tab is the TabId, not its slot index.
    // The numeric index is derived on demand via activeTabs.indexOf, so any
    // dynamic add/remove (MUSIC at front, MAP/TRANSLATE in the middle,
    // TELEPROMPTER/MOUSE at the end) cannot shift the user's selection.
    private var currentTabId: TabId = TabId.CHAT
    private val currentTab: Int
        get() = activeTabs.indexOf(currentTabId).coerceAtLeast(0)
    private var lastCenterPressTime = 0L
    // Doubletap NUMPAD_2 (touchpad-release) in TAB_NAV: turn screen off.
    private var lastNumpad2Ms: Long = 0L

    // ---- Touchpad ABS_X -> tab pill drag + snap-on-release -----------------
    // The rokid-touchpad-daemon emits BTN_TOUCH + ABS_X (0..100) on its
    // uinput device every 10 ms. In TAB_NAV state we let the user *drag*
    // the pill highlight directly with their finger: pill X follows
    // delta(ABS_X) from the touch-down anchor, mapped 1:1 to the tab bar
    // (full slider span 0..100 = full tabbar width). On finger release we
    // snap the pill to the nearest tab and commit that selection via
    // switchToTab(animate=true).
    //
    // Mapping: 1 ABS_X unit  ==  pillContainer.width / 100  pixels.
    // So a finger swipe across the entire slider drags the pill across
    // the entire tab bar.
    private var touchpadAbsActive:    Boolean = false
    private var touchpadAbsStartPos:  Int     = -1     // ABS_X at touch-down
    private var touchpadAbsAnchorX:   Float   = 0f     // pill X at touch-down
    private val touchpadAbsListener = TouchpadAbsListener(
        onTouchChanged = { down -> onTouchpadAbsTouch(down) }
    )
    // Choreographer-driven pill drag: at most one pillHighlight.translationX
    // update per display frame, regardless of how fast the listener thread
    // pushes new positions. Also smoothly interpolates between the chip's
    // 5 quantized levels (HW emits ABS_X jumps of ~21 units), so the pill
    // glides instead of snapping. Lerp factor 0.30/frame ~ 7-frame
    // settle time @ 60 Hz, fast enough to feel direct, slow enough to
    // hide quantization steps.
    // The pill is driven by a SpringAnimation whose target is updated on
    // every ABS_X sample. Spring runs natively in the choreographer at
    // vsync, smoothly chasing without any of the snap/overshoot the old
    // first-order lerp produced.
    private var touchpadAbsSpring: androidx.dynamicanimation.animation.SpringAnimation? = null
    private var touchpadAbsTargetX: Float = 0f
    // Index of the tab the pill is currently hovering over during drag.
    // -1 = nothing painted yet this gesture (forces first repaint).
    private var touchpadAbsHoverIdx: Int = -1

    // ---- Mirror state for the TODO sub-tab capsule drag --------------------
    private var todoAbsActive: Boolean = false
    private var todoAbsStartPos: Int = -1
    private var todoAbsAnchorTx: Float = 0f
    private var todoAbsTargetTx: Float = 0f
    private var todoAbsHoverIdx: Int = -1
    private var todoAbsSpring: androidx.dynamicanimation.animation.SpringAnimation? = null
    private val touchpadAbsFrameCallback = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!touchpadAbsActive) return
            // Pull the latest raw HW sample and feed it to the spring.
            val rawPos = touchpadAbsListener.latestPosition.get()
            if (rawPos >= 0) recomputeTouchpadAbsTarget(rawPos)
            // Update which tab is being hovered over (using the live
            // spring-driven translationX) and animate icons when the
            // hovered index changes.
            val tabCount = activeTabs.size
            val barW = pillContainer.width.toFloat()
            if (tabCount > 0 && barW > 0f) {
                val tabW = barW / tabCount
                val pillCenter = pillHighlight.translationX + pillHighlight.width / 2f
                val rawIdx = (pillCenter / tabW).toInt().coerceIn(0, tabCount - 1)
                val cur = touchpadAbsHoverIdx
                // Hysteresis: keep the current hover until the pill clearly
                // enters the next slot (>= 20% past the boundary). Prevents
                // rapid back-and-forth flips at slot edges that visibly
                // flicker the icon scale/tint springs.
                val newHover = if (rawIdx == cur || cur < 0) rawIdx else {
                    val hyst = tabW * 0.20f
                    val passed = if (rawIdx > cur) pillCenter > rawIdx * tabW + hyst
                                 else pillCenter < (cur * tabW) - hyst
                    if (passed) rawIdx else cur
                }
                if (newHover != cur) {
                    touchpadAbsHoverIdx = newHover
                    animateTabHover(cur, newHover)
                }
            }
            android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun iconForTab(id: TabId): android.widget.ImageView? = when (id) {
        TabId.MUSIC -> tabMusic
        TabId.CHAT -> tabChat
        TabId.CHAT_LIST -> tabChatList
        TabId.TELEGRAM -> tabTelegram
        TabId.REID -> tabReid
        TabId.TODO -> tabTodo
        TabId.NIGHTVISION -> null
        TabId.TRANSLATE -> translateTabIcon
        TabId.MAP -> mapTabIcon
        TabId.TELEPROMPTER -> teleprompterTabIcon
        TabId.MOUSE -> mouseTabIcon
    }

    private val DOUBLE_TAP_NUMPAD2_MIN_MS = 40L
    private val DOUBLE_TAP_NUMPAD2_MAX_MS = 400L

    // Night vision ML slider state: -1 = no slider, 0 = exposure, 1 = amplification
    private var nvSliderIndex = -1
    private var nvSliderLocked = false  // true = adjusting value, false = selecting slider
    private var lastNvSwipeTime = 0L
    private val NV_SWIPE_DEBOUNCE_MS = 500L
    private var lastScrollTime = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingTapRunnable: Runnable? = null

    // Backend process health monitoring
    private var backendBound = false
    private val backendConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            backendBound = true
            // Remote input is produced in :backend and consumed here. This is the only moment a
            // live binder to it exists, so it is where the sink attaches.
            remoteInputBridge.onBackendConnected(service)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            backendBound = false
            remoteInputBridge.onBackendDisconnected()
            startForegroundService(Intent(this@MainActivity, ListenerService::class.java))
            mainHandler.postDelayed({ bindBackend() }, 2000)
        }
        override fun onBindingDied(name: ComponentName?) {
            // Distinct from onServiceDisconnected: the binding itself is dead and Android will NOT
            // auto-rebind it. Without an explicit unbind+rebind the UI never receives input again.
            backendBound = false
            remoteInputBridge.onBackendDisconnected()
            try { unbindService(this) } catch (_: Throwable) {}
            mainHandler.postDelayed({ bindBackend() }, 2000)
        }
    }

    /**
     * UI-process end of the remote-input bridge. Turns cross-process deliveries back into the plain
     * in-process [RemoteInputSink] callback this Activity implements, so nothing below this line
     * knows a process boundary exists.
     */
    private val remoteInputBridge by lazy {
        RemoteInputBridgeClient(mainHandler, this) { uiLog(it) }
    }

    // --- Views ---
    private lateinit var debugStatus: TextView
    private lateinit var chatRecycler: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var chatListRecycler: RecyclerView
    private lateinit var chatListAdapter: ChatListAdapter
    private lateinit var chatListLayoutManager: LinearLayoutManager
    private lateinit var statusArea: View
    private lateinit var statusBar: TextView
    private lateinit var doubleTapHint: View
    private var doubleTapHintRunnable: Runnable? = null
    private lateinit var remoteInputGlyph: TextView
    private lateinit var statusIcon: ImageView
    private lateinit var progressBar: TextView
    private lateinit var cameraPreview: TextureView
    private lateinit var chatContainer: View
    private lateinit var chatEmptyHint: TextView
    private lateinit var mainContentLayout: LinearLayout
    private lateinit var minimapView: ImageView
    private var minimapActive = false
    private lateinit var tabChat: ImageView
    private lateinit var tabChatList: ImageView
    private var teleprompterTabIcon: ImageView? = null
    private var teleprompterTabFrame: FrameLayout? = null
    private lateinit var pillHighlight: View
    private lateinit var pillContainer: View
    private lateinit var scrollIndicator: View

    // Gates touchpad keycodes: swallowed only while the glasses are folded.
    // Tracked from the Rokid leg fold/unfold broadcast so the read is a cheap
    // volatile (no per-keycode sysfs read, which would cost 5-50 ms and lag scroll).
    @Volatile private var foldedState: Boolean = false

    private var arCameraPreview: ArCameraPreview? = null

    // View-based UI recorder (draws view hierarchy to video)
    private var viewRecorder: com.repository.glasses.listener.capture.ViewRecorder? = null

    private val uiRecordReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.getStringExtra("action") ?: return
            runOnUiThread {
                when (action) {
                    "start" -> {
                        val outputPath = intent.getStringExtra("output") ?: return@runOnUiThread
                        val duration = intent.getIntExtra("duration", 10)
                        uiLog("UI_REC: start -> $outputPath duration=$duration")
                        if (viewRecorder == null) {
                            viewRecorder = com.repository.glasses.listener.capture.ViewRecorder()
                        }
                        val rootView = window.decorView.rootView
                        viewRecorder!!.start(rootView, java.io.File(outputPath), 30) { ok ->
                            uiLog("UI_REC: started=$ok")
                        }
                        if (duration > 0) {
                            mainHandler.postDelayed({
                                stopUiRecording()
                            }, duration * 1000L)
                        }
                    }
                    "stop" -> stopUiRecording()
                }
            }
        }
    }

    private fun stopUiRecording() {
        viewRecorder?.stop { path ->
            uiLog("UI_REC: stopped, path=$path")
            sendBroadcast(Intent(ListenerService.ACTION_UI_RECORD_STOPPED).apply {
                setPackage(packageName)
                putExtra("path", path ?: "")
            })
        }
    }
    private var nightVisionPreview: NightVisionPreview? = null
    private var nightVisionML: NightVisionML? = null
    private var teleprompterController: TeleprompterController? = null
    private lateinit var teleprompterContainer: FrameLayout
    // Mouse tab
    private var mouseTabIcon: ImageView? = null
    private var mouseTabFrame: FrameLayout? = null
    private lateinit var mouseContainer: LinearLayout
    private lateinit var mouseConnectionStatus: TextView
    private lateinit var mouseControlsHint: TextView
    private var mouseService: HidMouseService? = null
    private var mouseBound = false
    private var mouseSensX = 1800f
    private var mouseSensY = 4200f
    private val dpadHandler = DpadInputHandler()
    private val SCROLL_DELTA = 3

    // KEYCODE_CAMERA is now handled by ScreenOffAccessibilityService + ListenerService
    // (system-wide key filter in accessibility service consumes the event before it reaches
    // MainActivity, so the function button works regardless of which activity is foreground).

    private val mouseServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            mouseService = (binder as HidMouseService.LocalBinder).service
            mouseBound = true
            mouseService?.setSensitivity(mouseSensX, mouseSensY)
            // BLE advertising removed -- mouse now routes via RFCOMM -> phone -> BT Classic HID
            updateMouseUI()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            mouseService = null
            mouseBound = false
        }
    }
    private var btConnected = true
    private var orchConnected = true
    private lateinit var contentFrame: FrameLayout
    private lateinit var disconnectedOverlay: TextView
    // NotificationOverlay moved to ListenerService (WindowManager-based, activity-independent)
    private lateinit var timeText: TextView
    private lateinit var batteryText: TextView
    private lateinit var tabBar: FrameLayout
    private lateinit var weatherIcon: ImageView
    private lateinit var weatherTemp: TextView
    private lateinit var weatherRow: View
    private lateinit var loneIndicatorIcon: ImageView
    private lateinit var loneIndicatorCount: TextView
    private var recordingIndicator: ImageView? = null
    private var callIndicator: LinearLayout? = null
    private var callIndicatorLabel: ImageView? = null
    private var callDurationText: TextView? = null
    private lateinit var batteryIndicator: LinearLayout
    private lateinit var batteryFill: View
    private lateinit var wifiIndicator: ImageView
    private var micMuteIndicator: ImageView? = null
    @Volatile private var callMicMuted: Boolean = false
    @Volatile private var callScoActive: Boolean = false
    private val wifiStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION) return
            // Cross-check broadcast extra against Settings.Global.WIFI_ON --
            // on this device the broadcast EXTRA_WIFI_STATE can be stale while
            // the user toggle has flipped, due to P2P keeping wlan0 partially up.
            val on = try {
                android.provider.Settings.Global.getInt(
                    contentResolver,
                    android.provider.Settings.Global.WIFI_ON,
                    0,
                ) == 1
            } catch (_: Throwable) {
                intent.getIntExtra(
                    android.net.wifi.WifiManager.EXTRA_WIFI_STATE,
                    android.net.wifi.WifiManager.WIFI_STATE_UNKNOWN,
                ) == android.net.wifi.WifiManager.WIFI_STATE_ENABLED
            }
            runOnUiThread {
                if (::wifiIndicator.isInitialized) {
                    wifiIndicator.visibility = if (on) View.VISIBLE else View.GONE
                }
            }
        }
    }

    // --- Dynamic tab system ---
    enum class TabId { MUSIC, CHAT, CHAT_LIST, TELEGRAM, REID, TODO, NIGHTVISION, TRANSLATE, MAP, TELEPROMPTER, MOUSE }
    // Music is added dynamically when an A2DP source exposes a MediaSession.
    // CONTRACT: every mutation of this list (add / remove / reorder) MUST be
    // followed by exactly one afterTabsChanged() call. That hook resizes the
    // pill container and re-anchors the highlight on currentTabId, so the
    // pill stays under the selected tab even when its numeric index shifts.
    // Bypassing this hook desyncs the pill from the icon row.
    private val activeTabs = mutableListOf(TabId.TODO, TabId.CHAT, TabId.CHAT_LIST, TabId.TRANSLATE, TabId.TELEGRAM, TabId.REID)
    private val maxTab: Int get() = activeTabs.size - 1

    // --- Map tab (created programmatically) ---
    private var mapTabIcon: ImageView? = null
    private var mapTabFrame: FrameLayout? = null
    private lateinit var mapContainer: FrameLayout
    private lateinit var mapContentView: ImageView
    private lateinit var mapBaseFadeView: ImageView
    private lateinit var mapArrowView: ImageView
    private var lastBaseBitmap: Bitmap? = null
    // Rate-limit for the steady-state map decode log (see processLatestMapFrame).
    private var lastMapDebugMs: Long = 0L

    // Off-UI-thread minimap frame processing. Heavy work (Base64 decode, JPEG/WEBP
    // decode, per-pixel monochrome-green pass) runs on this single worker so it can
    // never stall the crossfade animation or the arrow Choreographer loop on the UI
    // thread. Frames are coalesced: only the latest base64 is kept, so a slow frame
    // can't build a backlog that lags the map behind reality.
    private var mapWorkerThread: android.os.HandlerThread? = null
    private var mapWorkerHandler: android.os.Handler? = null
    private val pendingMapFrame = java.util.concurrent.atomic.AtomicReference<ByteArray?>(null)

    private data class ArrowSample(val t: Long, val x: Float, val y: Float, val heading: Float)
    private val arrowSamples = ArrayDeque<ArrowSample>()
    private var arrowFrameCallback: android.view.Choreographer.FrameCallback? = null
    private val arrowRenderDelayMs: Long = 200L

    private lateinit var mapPinButton: ImageView
    private lateinit var mapStopButton: TextView
    private lateinit var mapStepsButton: TextView
    private lateinit var mapButtonColumn: LinearLayout
    private lateinit var mapZoomSliderContainer: FrameLayout
    private lateinit var mapZoomSliderTrack: View
    private lateinit var mapZoomSliderFill: View
    // The slider is an abstract 0..1 fraction; the phone maps it into whichever
    // provider's zoom range is active. The glasses stay provider-agnostic. The
    // fraction is quantized into ZOOM_STEPS discrete positions so a swipe moves one
    // notch. Default 0.8 matches the phone's DEFAULT_ZOOM_FRACTION.
    private val zoomSteps = 10
    private val zoomDefaultStep = 8
    private var currentZoomStep = 8
    private val currentZoomFraction: Float get() = currentZoomStep.toFloat() / zoomSteps
    private var zoomFillAnimator: ValueAnimator? = null
    private lateinit var mapStopModal: FrameLayout
    private lateinit var mapStepsModal: FrameLayout
    private lateinit var stepsScrollView: ScrollView
    private lateinit var stepsListContainer: LinearLayout
    private lateinit var mapStepStripClip: View
    private lateinit var mapStepStrip: LinearLayout
    private lateinit var mapStepRowPrev: TextView
    private lateinit var mapStepRowCurrent: TextView
    private lateinit var mapStepRowNext: TextView
    private lateinit var mapStepRowBuffer: TextView
    private var renderedStepIndex: Int = -1
    private var stepStripAnimator: android.animation.ValueAnimator? = null
    private lateinit var modalStopBtn: TextView
    private lateinit var modalCancelBtn: TextView
    private var mapPinned = false
    // 0=steps, 1=stop, 2=zoom-slider, 3=pin. zoom-slider entry pushes the
    // user into FocusState.MAP_ZOOM_FOCUSED; the other indices behave as
    // before.
    private var mapFocusedIndex = 3
    private val MAP_FOCUS_ZOOM = 2
    private val MAP_FOCUS_PIN = 3
    private val MAP_FOCUS_MAX = 3
    private var lastMapButtonScrollTime = 0L
    private var modalSelectedIndex = 1  // 0=Stop, 1=Cancel
    private var journeyStepsJson: String? = null
    private var parsedSteps: JSONArray? = null
    private var currentNavStepIndex: Int = -1

    // --- Teleprompter controls ---
    private lateinit var tpScrollIndicator: View
    private lateinit var tpStopButton: TextView
    private var tpFocusedIndex = 1  // 0=stop, 1=content

    // --- Translate tab (created programmatically) ---
    private var translateTabIcon: ImageView? = null
    private var translateTabFrame: FrameLayout? = null
    private lateinit var translationContainer: View
    private lateinit var translationStatus: TextView
    private lateinit var translationChunksContainer: LinearLayout
    private lateinit var translationScrollView: android.widget.ScrollView
    private val translationSegments = mutableMapOf<Int, TranslationSegment>()
    private val MAX_VISIBLE_CHUNKS = 6
    private val FADE_OUT_MS = 15_000L
    private var translationFontSize = 14
    // Cache so the status label survives across sessions and tab switches.
    private var translationLangsLabel: String = ""

    private data class TranslationSegment(
        val id: Int,
        var sourceText: String = "",
        var translatedText: String = "",
        var isPartial: Boolean = true,
        var addedAt: Long = 0L
    )


    // --- REID tab ---
    private lateinit var reidContainer: View
    private lateinit var reidStartStopIcon: ImageView
    private lateinit var reidStartStopContainer: FrameLayout
    private lateinit var translationStartStopIcon: ImageView
    private lateinit var translationStartStopContainer: FrameLayout
    private lateinit var reidFaceBar: LinearLayout
    private lateinit var reidFaceIdLabel: TextView
    private lateinit var tabReid: ImageView
    private var reidRunning = false
    private var reidSelectedFaceIndex = -1
    private var reidVerifiedFaces = listOf<JSONObject>()
    // Live heart rate (BPM) of the face in view, pushed at ~1Hz from ListenerService
    // (which owns RppgEngine). <=0 = measuring / no confident reading.
    private var reidLiveBpm: Int = 0
    private var reidFaceDetectedAnimating = false
    private var reidFocusedElement = 0 // 0 = start/stop, 1 = face bar
    private var reidLastPersonIntel: JSONObject? = null // Last fetched intel (not cached across persons)
    private val reidBestThumbs = mutableMapOf<String, String>() // uid -> best thumbnail base64
    private lateinit var reidIntelModal: ScrollView
    private lateinit var reidIntelContent: LinearLayout

    // --- Music tab ---
    private lateinit var musicContainer: FrameLayout
    private lateinit var musicPlayerContent: View
    private lateinit var musicEmptyHint: TextView
    private lateinit var tabMusic: ImageView
    private lateinit var musicTrackName: TextView
    private lateinit var musicPlayPauseIcon: ImageView
    private lateinit var musicPlayPauseContainer: FrameLayout
    private lateinit var musicPrevContainer: FrameLayout
    private lateinit var musicNextContainer: FrameLayout
    private var musicIsPlaying = false
    private var musicMarqueeAnimator: ValueAnimator? = null
    private var lastMusicActionType = ""  // "skip" or "toggle"
    private var lastMusicActionTime = 0L
    private val MUSIC_CROSS_ACTION_COOLDOWN_MS = 1000L
    private lateinit var musicProgressBg: View
    private lateinit var musicProgressFill: View
    private var musicProgressMaxWidth = 0

    // --- Night vision tab ---
    private lateinit var nightvisionContainer: FrameLayout
    private lateinit var nightvisionPreview: ImageView
    // private lateinit var tabNightvision: ImageView  // Night vision tab commented out

    // --- TODO tab ---
    private lateinit var todoContainer: FrameLayout
    private lateinit var tabTodo: ImageView
    private lateinit var todoChecklistAdapter: TodoChecklistAdapter
    private lateinit var todoAlarmAdapter: AlarmDisplayAdapter
    private lateinit var todoJobAdapter: JobDisplayAdapter
    private lateinit var todoSavedAdapter: TelegramSavedAdapter
    private var todoChecklistRecycler: RecyclerView? = null
    // Overlay "cursor" dot for the TASKS list -- jumps vertically to the selected row's dot,
    // squeezing horizontally while it travels (vertical analog of the tab pill highlight).
    private var todoChecklistCursor: VerticalHighlightView? = null
    private var todoChecklistCursorSpring: androidx.dynamicanimation.animation.SpringAnimation? = null
    private var todoAlarmRecycler: RecyclerView? = null
    private var todoJobRecycler: RecyclerView? = null
    private var todoSavedRecycler: RecyclerView? = null
    // Saved sub-tab uses the paginated telegram_messages path against the "Saved Messages"
    // chat (chatId="me"). These flags mirror the chat-browser pagination model.
    private val SAVED_CHAT_ID = "me"
    private val SAVED_PAGE_SIZE = 20
    private var savedRequestInFlight = false        // a page (first or older) is being awaited
    private var savedLoadingOlder = false           // the in-flight page is an older page (append)
    private var savedNoMoreOlder = false            // last older page returned < SAVED_PAGE_SIZE
    private enum class TodoSubTab(val label: String, val iconRes: Int, val requestAction: String) {
        TASKS("Tasks", R.drawable.ic_checklist, ListenerService.ACTION_REQUEST_TODO_LIST),
        SAVED("Saved", R.drawable.ic_bookmark, ListenerService.ACTION_REQUEST_TG_MESSAGES),
        JOBS("Jobs", R.drawable.ic_schedule, ListenerService.ACTION_REQUEST_JOB_LIST),
        ALARMS("Alarms", R.drawable.ic_alarm, ListenerService.ACTION_REQUEST_ALARM_LIST);
    }
    private var todoSubTab: TodoSubTab = TodoSubTab.TASKS
    private val todoSubTabLabels = arrayOfNulls<ImageView>(TodoSubTab.entries.size)
    private var todoSubTabPill: LinearLayout? = null
    private var todoSubTabCapsule: TabHighlightView? = null
    // Tracks the last-applied sub-tab-circle visibility so we only fire the
    // fade-in/out (and the snap-to-rest on appear) on an actual transition.
    private var subtabCapsuleShown: Boolean = false
    // Tracks the last-applied bottom-bar circle visibility so the fade only
    // fires on an actual focus transition (updateFocusVisual runs on every
    // scroll/key event). The pill starts visible in the initial TAB_NAV state.
    private var pillHighlightShown: Boolean = true
    private var todoEmptyText: TextView? = null
    private var todoHasError: Boolean = false
    private var todoMessageOverlay: FrameLayout? = null
    private var todoMessageScrollView: android.widget.ScrollView? = null
    private var todoMessageTextView: TextView? = null
    private var todoMessageDetailShowing: Boolean = false
    private var todoFocusLevel: Int = 0  // 0=subtab nav, 1=content, 2=message detail

    // --- Telegram tab ---
    private lateinit var tabTelegram: ImageView
    private lateinit var telegramAuthContainer: LinearLayout
    private lateinit var telegramAuthPrompt: TextView
    private lateinit var telegramAuthStatus: TextView
    private lateinit var telegramChatListRecycler: RecyclerView
    private lateinit var telegramChatContainer: FrameLayout
    private lateinit var telegramChatRecycler: RecyclerView
    private lateinit var telegramVoiceOverlay: LinearLayout
    private lateinit var telegramRecordHint: TextView
    private var telegramChatListRetry: Runnable? = null
    private var telegramChatListRetryCount = 0
    private var telegramChatListRefreshRunnable: Runnable? = null
    private var telegramMessagesRetry: Runnable? = null
    private var telegramMessagesRetryCount = 0
    private var telegramChatListLoaded = false
    private var telegramMessagesLoaded = false
    private var telegramLoadingOlderMessages = false
    private var telegramNoMoreOlderMessages = false
    private lateinit var telegramVoicePreview: TextView
    private lateinit var telegramSendPreview: LinearLayout
    private lateinit var telegramSendText: TextView
    private lateinit var telegramSendCountdown: TextView
    private var telegramVoiceVisualizer: com.repository.glasses.listener.ui.AudioVisualizerView? = null
    private var telegramVoiceLevelsReceiver: BroadcastReceiver? = null
    private lateinit var telegramChatListAdapter: TelegramChatListAdapter
    private lateinit var telegramChatAdapter: TelegramChatAdapter
    private var telegramOpenChatId: String = ""
    private var telegramOpenChatTitle: String = ""
    private var telegramSendRunnable: Runnable? = null
    private var telegramOpenChatType: String = "user"
    private var telegramOpenTopicId: Int = 0
    private var telegramOpenChatIsForum: Boolean = false
    private lateinit var telegramChatHeader: LinearLayout
    private lateinit var telegramChatHeaderName: TextView
    private lateinit var telegramChatHeaderStatus: TextView
    private lateinit var telegramChatHeaderAvatar: android.widget.ImageView
    private lateinit var telegramTopicListAdapter: com.repository.glasses.listener.ui.TelegramTopicListAdapter
    private lateinit var telegramTopicsRecycler: RecyclerView

    private var serviceState = "IDLE"
    private var audioVisualizerView: com.repository.glasses.listener.ui.AudioVisualizerView? = null
    private var audioLevelsReceiver: BroadcastReceiver? = null

    // --- Animation state ---
    private var tintActiveAnimator: ValueAnimator? = null
    private var tintInactiveAnimator: ValueAnimator? = null
    private var focusBorderAnimator: ValueAnimator? = null
    private var focusedDrawable: GradientDrawable? = null
    private var previousFocusedView: View? = null
    private lateinit var loaderCtl: TabLoaderController
    private var thinkingPulseAnimator: ValueAnimator? = null
    private var thinkingStartTime = 0L
    private var timerRunning = false
    private var scrollIndicatorHideRunnable: Runnable? = null
    private var tpScrollIndicatorHideRunnable: Runnable? = null
    private var tpStopBorderAnimator: ValueAnimator? = null

    // --- Loading dots (in-chat indicator, matches phone client pattern) ---
    private var partialsSuppressed = false
    private var loadingActive = false
    private var loadingIndex = 0
    private val loadingRunnable = object : Runnable {
        override fun run() {
            if (!loadingActive) return
            val dots = ".".repeat((loadingIndex % 3) + 1)
            loadingIndex++
            updateLoadingIndicator(dots)
            mainHandler.postDelayed(this, 500)
        }
    }

    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!timerRunning) return
            val elapsed = SystemClock.elapsedRealtime() - thinkingStartTime
            statusBar.text = "Thinking... %.1fs".format(elapsed / 1000.0)
            timerHandler.postDelayed(this, 100)
        }
    }

    private fun startTimer() {
        thinkingStartTime = SystemClock.elapsedRealtime()
        timerRunning = true
        timerHandler.post(timerRunnable)
        startThinkingPulse()
    }

    private fun stopTimer() {
        timerRunning = false
        timerHandler.removeCallbacks(timerRunnable)
        stopThinkingPulse()
    }

    private fun startThinkingPulse() {
        thinkingPulseAnimator?.cancel()
        thinkingPulseAnimator = Anim.pulse(statusBar, Lum.MID, Lum.GLOW, 1500L)
    }

    private fun stopThinkingPulse() {
        thinkingPulseAnimator?.cancel()
        thinkingPulseAnimator = null
        statusBar.setTextColor(Lum.MID)
    }

    // --- Loading dots in chat (phone client pattern) ---

    private fun startLoading() {
        if (loadingActive) return
        loadingActive = true
        loadingIndex = 0
        mainHandler.post(loadingRunnable)
    }

    private fun stopLoading() {
        loadingActive = false
        mainHandler.removeCallbacks(loadingRunnable)
        chatAdapter.removeLoadingMessages()
    }

    private fun updateLoadingIndicator(text: String) {
        val messages = chatAdapter.getMessages()
        val lastMsg = messages.lastOrNull()
        if (lastMsg != null && lastMsg.role == ChatMessage.Role.SYSTEM && lastMsg.requestId == "loading") {
            chatAdapter.updateLastMessage(text)
        } else {
            chatAdapter.addMessage(ChatMessage(
                id = UUID.randomUUID().toString(),
                role = ChatMessage.Role.SYSTEM,
                text = text,
                requestId = "loading"
            ))
        }
        scrollToBottom()
    }

    // --- Status updates with crossfade ---

    private fun setStatus(text: String, iconRes: Int? = null, iconColor: Int = Lum.MID) {
        Anim.crossfadeText(statusBar, text) {
            statusBar.setTextColor(Lum.MID)
        }
        if (iconRes != null) {
            val drawable = ContextCompat.getDrawable(this, iconRes)
            statusIcon.setImageDrawable(drawable)
            statusIcon.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN)
        }
    }

    // --- Broadcast receivers ---

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra(ListenerService.EXTRA_STATE) ?: return
            runOnUiThread {
                uiLog("STATE: $serviceState -> $state focus=$focusState sel=${chatAdapter.selectedPosition}")
                serviceState = state
                updateChatEmptyHint()
                when (state) {
                    "IDLE" -> {
                        stopTimer()
                        hideLoadingSpinner()
                        stopLoading()
                        stopThinkingPulse()
                        hideDoubleTapHint()
                        hideAudioVisualizer()
                        progressBar.visibility = View.GONE
                        uiLog("IDLE: before cleanup msgs=${chatAdapter.itemCount} roles=${chatAdapter.getMessages().map { "${it.role}:${it.requestId.take(8)}" }}")
                        // Clean ALL temporary messages
                        chatAdapter.removeMessagesByRequestId("listening")
                        chatAdapter.removeMessagesByRequestId("partial")
                        chatAdapter.removeMessagesByRequestId("pending")
                        uiLog("IDLE: after cleanup msgs=${chatAdapter.itemCount}")
                        chatAdapter.clearSelection()
                        statusArea.visibility = View.INVISIBLE
                    }
                    "LISTENING" -> {
                        stopTimer()
                        hideLoadingSpinner()
                        stopLoading()
                        partialsSuppressed = false
                        progressBar.visibility = View.GONE
                        // Clean slate for new session
                        chatAdapter.removeMessagesByRequestId("listening")
                        chatAdapter.removeMessagesByRequestId("partial")
                        chatAdapter.removeMessagesByRequestId("pending")
                        statusArea.visibility = View.VISIBLE
                        setStatus("Listening...", R.drawable.ic_mic, Lum.GLOW)
                        showDoubleTapHintPersistent()
                        showAudioVisualizer()
                        // Auto-focus chat when conversation starts
                        if (focusState != FocusState.CHAT_FOCUSED) {
                            uiLog("NAV: LISTENING auto-focus CHAT_FOCUSED (was $focusState tab=$currentTab/${activeTabs.getOrNull(currentTab)})")
                            focusState = FocusState.CHAT_FOCUSED
                            updateFocusVisual(focusState)
                        }
                    }
                    "RESPONDING" -> {
                        hideAudioVisualizer()
                        chatAdapter.removeMessagesByRequestId("listening")
                        chatAdapter.removeMessagesByRequestId("partial")
                        stopLoading()
                        statusArea.visibility = View.VISIBLE
                        showDoubleTapHintPersistent()
                        if (!timerRunning) {
                            startTimer()
                        }
                    }
                }
            }
        }
    }

    private val chatReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val json = intent.getStringExtra(ListenerService.EXTRA_CHAT_MESSAGE) ?: return
            try {
                val obj = JSONObject(json)
                val requestId = obj.getString("requestId")
                val roleStr = obj.getString("role")
                val text = obj.getString("text")

                val role = when (roleStr) {
                    "USER" -> ChatMessage.Role.USER
                    "ASSISTANT" -> ChatMessage.Role.ASSISTANT
                    "TOOL" -> ChatMessage.Role.TOOL
                    "SYSTEM" -> ChatMessage.Role.SYSTEM
                    else -> ChatMessage.Role.SYSTEM
                }

                runOnUiThread {
                    uiLog("CHAT: role=$roleStr reqId=${requestId.take(8)} state=$serviceState msgs=${chatAdapter.itemCount} text='${text.take(40)}'")
                    // Always display messages -- phone manages state and only sends valid responses.
                    // Previous IDLE drop caused missing chat text while TTS still played.
                    // Idempotent for ASSISTANT: update existing message if one already exists for this requestId
                    if (role == ChatMessage.Role.ASSISTANT) {
                        chatAdapter.stopToolAnimation(requestId)
                        stopLoading()
                        val idx = chatAdapter.findAssistantByRequestId(requestId)
                        if (idx >= 0) {
                            uiLog("CHAT: updated existing ASSISTANT at $idx")
                            chatAdapter.updateMessageAt(idx, text)
                            scrollToBottom()
                            return@runOnUiThread
                        }
                    }
                    // Idempotent for USER: real requestId replaces pending placeholder
                    if (role == ChatMessage.Role.USER) {
                        hideAudioVisualizer()
                        if (requestId != "pending" && requestId != "partial") {
                            uiLog("CHAT: removing pending/partial before adding real USER")
                            chatAdapter.removeMessagesByRequestId("pending")
                            chatAdapter.removeMessagesByRequestId("partial")
                        }
                        // Skip if same requestId USER message already exists
                        val existing = chatAdapter.findUserByRequestId(requestId)
                        if (existing >= 0) {
                            uiLog("CHAT: updated existing USER at $existing")
                            chatAdapter.updateMessageAt(existing, text)
                            scrollToBottom()
                            return@runOnUiThread
                        }
                    }
                    uiLog("CHAT: adding new $roleStr message (total=${chatAdapter.itemCount + 1})")
                    chatAdapter.addMessage(ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = role,
                        text = text,
                        requestId = requestId
                    ))
                    scrollToBottom()
                }
            } catch (_: Exception) {}
        }
    }

    private val streamingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val json = intent.getStringExtra(ListenerService.EXTRA_STREAMING_TEXT) ?: return
            try {
                val obj = JSONObject(json)
                val requestId = obj.getString("requestId")
                val partialText = obj.getString("partialText")

                runOnUiThread {
                    if (serviceState == "IDLE") return@runOnUiThread
                    hideAudioVisualizer()
                    hideLoadingSpinner()
                    stopLoading()
                    if (timerRunning) {
                        stopTimer()
                        val elapsed = SystemClock.elapsedRealtime() - thinkingStartTime
                        setStatus("%.1fs".format(elapsed / 1000.0), R.drawable.ic_status_dot, Lum.DIM)
                    }

                    // Find existing ASSISTANT message by requestId (survives TOOL messages in between)
                    val idx = chatAdapter.findAssistantByRequestId(requestId)
                    if (idx >= 0) {
                        chatAdapter.updateMessageAt(idx, partialText)
                    } else {
                        chatAdapter.addMessage(ChatMessage(
                            id = UUID.randomUUID().toString(),
                            role = ChatMessage.Role.ASSISTANT,
                            text = partialText,
                            requestId = requestId
                        ))
                    }
                    if (chatAdapter.selectedPosition >= 0) chatAdapter.clearSelection()
                    scrollToBottom()
                }
            } catch (_: Exception) {}
        }
    }

    // Notification-solo: armed while a screen-was-off solo notification is on screen.
    // Notification-solo "blackout": when a notification arrives while the screen was off
    // and the glasses are worn, the backend broadcasts SOLO_SHOW. A backend-owned overlay
    // window (TYPE_APPLICATION_OVERLAY from the :backend process) CANNOT occlude this
    // activity's window -- empirically the activity sits above any application-overlay in
    // z-order, so a backend backdrop bled through everywhere except under the card itself.
    // The only thing that can black out the activity-owned pixels is the activity hiding
    // its OWN content root. So on SOLO_SHOW we fade the content root to alpha 0 (transparent
    // -> black -> pixels off on the waveguide), leaving only the backend's notification card
    // (a separate overlay window) lit. The first key press reveals it again (fade alpha 1).
    //
    // Anti-stuck-black guarantees (the activity must NEVER be left invisible):
    //   - reveal restores alpha to 1 UNCONDITIONALLY and is driven by THREE independent
    //     triggers: the key-press in dispatchKeyEvent, the SOLO_END broadcast (card
    //     dismissed / queue drained), and a hard timeout failsafe armed at hide time.
    //   - hide is starvation-proof: alpha is set directly (not animator-dependent) so even
    //     if the fade animator is starved by the screen wake, the content still goes black;
    //     reveal likewise force-sets alpha=1 before/after the fade.
    @Volatile private var notifSoloArmed = false
    // True between ACTION_SCREEN_OFF and the next ACTION_SCREEN_ON. Lets a SOLO_END that
    // races slightly AFTER the screen-off still remove the cover instantly (no fade).
    @Volatile private var soloScreenOff = false
    private val soloHandler = android.os.Handler(android.os.Looper.getMainLooper())
    // Hard cap: even if every other reveal trigger is missed, restore the UI after this.
    private val soloBlackoutTimeoutMs = 20000L
    private val soloRevealFailsafe = Runnable {
        if (soloContentHidden) {
            activityLog("[NSOLO] blackout timeout failsafe -> force reveal")
            revealFromSolo("timeout")
        }
    }
    @Volatile private var soloContentHidden = false
    // The blackout is a full-screen opaque-black View added as a CHILD of this activity's
    // own content frame (android.R.id.content). It MUST be inside the activity window -- a
    // backend TYPE_APPLICATION_OVERLAY window sits BELOW this activity in z-order and cannot
    // occlude it (proven empirically). A freshly-added view also forces a layout+draw
    // traversal, which redraws the panel correctly after the notification's FLAG_TURN_SCREEN_ON
    // wakes it (plain alpha=0 on content left a stale pre-sleep framebuffer on screen).
    private var soloCoverView: View? = null

    private fun soloContentFrame(): android.view.ViewGroup? =
        try { window.decorView.findViewById<android.view.ViewGroup>(android.R.id.content) } catch (e: Exception) { null }

    /** Cover the activity with an opaque-black view so only the backend card is lit. */
    private fun hideForSolo() {
        runOnUiThread {
            try {
                val frame = soloContentFrame()
                if (frame == null) {
                    android.util.Log.i("MainActivityUI", "[NSOLO] hideForSolo: no content frame")
                    return@runOnUiThread
                }
                soloContentHidden = true
                var cover = soloCoverView
                if (cover == null) {
                    cover = View(this).apply {
                        setBackgroundColor(Color.BLACK)
                        isClickable = false
                        isFocusable = false
                    }
                    soloCoverView = cover
                }
                (cover.parent as? android.view.ViewGroup)?.removeView(cover)
                cover.animate().cancel()
                cover.alpha = 1f
                frame.addView(cover, android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT))
                cover.bringToFront()
                // The screen was OFF when this ran; the activity surface still holds the
                // stale pre-sleep frame. Force the ViewRootImpl to produce a fresh frame so
                // the cover actually reaches the panel once the card's TURN_SCREEN_ON wakes
                // it. A single invalidate isn't enough across the wake -- re-assert on the
                // next few frames via the Choreographer.
                forceSoloRedraw(frame, 6)
                android.util.Log.i("MainActivityUI", "[NSOLO] hideForSolo: cover attached childCount=${frame.childCount}")
                soloHandler.removeCallbacks(soloRevealFailsafe)
                soloHandler.postDelayed(soloRevealFailsafe, soloBlackoutTimeoutMs)
            } catch (e: Exception) {
                android.util.Log.e("MainActivityUI", "[NSOLO] hideForSolo failed", e)
            }
        }
    }

    /** Re-invalidate the cover/decor across [frames] consecutive frames so the activity's
     *  surface is redrawn after the screen wakes (the wake can land between our invalidate
     *  and the next traversal, leaving the stale frame on the panel). */
    private fun forceSoloRedraw(frame: android.view.ViewGroup, frames: Int) {
        if (frames <= 0) return
        soloCoverView?.bringToFront()
        soloCoverView?.invalidate()
        frame.invalidate()
        window.decorView.invalidate()
        android.view.Choreographer.getInstance().postFrameCallback {
            if (soloContentHidden) forceSoloRedraw(frame, frames - 1)
        }
    }

    /** Fade the black cover out over 300ms then remove it. Idempotent; never leaves it stuck. */
    private fun revealFromSolo(reason: String) {
        runOnUiThread {
            soloHandler.removeCallbacks(soloRevealFailsafe)
            notifSoloArmed = false
            val cover = soloCoverView
            if (cover == null || cover.parent == null) {
                soloContentHidden = false
                activityLog("[NSOLO] revealFromSolo($reason): no cover attached, noop")
                return@runOnUiThread
            }
            activityLog("[NSOLO] revealFromSolo($reason): fading black cover out")
            cover.animate().cancel()
            cover.animate()
                .alpha(0f)
                .setDuration(300L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction {
                    (cover.parent as? android.view.ViewGroup)?.removeView(cover)
                    cover.alpha = 1f
                    activityLog("[NSOLO] revealFromSolo($reason): cover removed")
                }
                .start()
            soloContentHidden = false
        }
    }

    /** Remove the black cover INSTANTLY (no fade) -- used when the panel is already dark
     *  (real screen-off ended the solo session), so the removal is invisible and there is
     *  no frame where the full UI is shown on a lit screen. Idempotent. */
    private fun removeSoloCoverInstant(reason: String) {
        runOnUiThread {
            soloHandler.removeCallbacks(soloRevealFailsafe)
            notifSoloArmed = false
            val cover = soloCoverView
            if (cover != null) {
                cover.animate().cancel()
                cover.alpha = 1f
                (cover.parent as? android.view.ViewGroup)?.removeView(cover)
            }
            soloContentHidden = false
            activityLog("[NSOLO] $reason")
        }
    }

    private val notificationSoloShowReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            notifSoloArmed = true
            uiLog("[NSOLO] SOLO_SHOW received -- armed + blacking out content")
            hideForSolo()
        }
    }

    private val notificationSoloEndReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Two reveal paths:
            //  - User already touched (notifSoloArmed == false): the key-press fade-reveal
            //    is in flight or done. Treat SOLO_END as a no-op (cover is gone/going).
            //  - No-touch / auto-dismiss path (notifSoloArmed == true): the screen is about
            //    to be locked at +500ms. DO NOT fade on a lit screen (that is the flash bug).
            //    If the screen is already off, remove instantly now; otherwise leave the
            //    cover up and let the ACTION_SCREEN_OFF receiver remove it when the panel
            //    actually goes dark.
            if (!notifSoloArmed) {
                uiLog("[NSOLO] SOLO_END received -- user already revealed, noop")
                return
            }
            if (soloScreenOff) {
                uiLog("[NSOLO] SOLO_END received (no-touch, screen already off) -- removing instantly")
                removeSoloCoverInstant("SOLO_END screen-off -> cover removed instantly (no flash)")
            } else {
                uiLog("[NSOLO] SOLO_END received (no-touch, screen still lit) -- holding cover until screen-off")
            }
        }
    }

    private val soloScreenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            soloScreenOff = true
            // Only act on the lock that ENDS this solo session: gate on soloContentHidden.
            // An unrelated earlier screen-off (no cover up) is ignored. The key-press path
            // sets notifSoloArmed=false but also tears the cover down, so soloContentHidden
            // will be false there and we won't fight it.
            if (soloContentHidden) {
                removeSoloCoverInstant("screen-off -> cover removed instantly (no flash)")
            }
        }
    }

    private val soloScreenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            soloScreenOff = false
        }
    }

    private val notificationShownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val notifId = intent.getStringExtra(ListenerService.EXTRA_NOTIF_ID) ?: return
            val repliable = intent.getBooleanExtra(ListenerService.EXTRA_NOTIF_REPLIABLE, false)
            runOnUiThread {
                pendingNotifId = notifId
                notificationRepliable = repliable
                uiLog("[NREPLY] notificationShown repliable=$repliable id=${notifId.take(12)} (set pendingNotifId)")
            }
        }
    }

    private val notificationHiddenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val notifId = intent.getStringExtra(ListenerService.EXTRA_NOTIF_ID) ?: return
            runOnUiThread {
                uiLog("[NREPLY] notificationHidden id=${notifId.take(12)} pendingNotifId=${pendingNotifId?.take(12)} focus=$focusState")
                if (notifId != pendingNotifId) { uiLog("[NREPLY] notificationHidden ignored (id mismatch)"); return@runOnUiThread }
                if (focusState == FocusState.NOTIFICATION_REPLY) {
                    // Mid-reply and the underlying notification went away: abort.
                    uiLog("[NREPLY] notificationHidden during reply -> abortNotifReply")
                    abortNotifReply()
                } else {
                    uiLog("[NREPLY] notificationHidden -> clearing pendingNotifId+repliable")
                    pendingNotifId = null
                    notificationRepliable = false
                    cancelReplyArm()
                }
            }
        }
    }

    private val partialTextReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val text = intent.getStringExtra(ListenerService.EXTRA_PARTIAL_TEXT) ?: return
            runOnUiThread {
                // Route to Telegram voice preview if recording.
                if (focusState == FocusState.TELEGRAM_RECORDING) {
                    if (text.isNotBlank()) telegramVoicePreview.text = text
                    return@runOnUiThread
                }
                // Notification reply: the live transcript renders inside the
                // notification overlay (service-owned), not here.
                if (focusState == FocusState.NOTIFICATION_REPLY) {
                    return@runOnUiThread
                }
                if (serviceState != "LISTENING") return@runOnUiThread
                if (partialsSuppressed) return@runOnUiThread
                if (text.isBlank()) return@runOnUiThread
                val partialIdx = chatAdapter.findUserByRequestId("partial")
                if (partialIdx >= 0) {
                    chatAdapter.updateMessageAt(partialIdx, text)
                } else {
                    // Remove "Listening..." system message and show user's words
                    chatAdapter.removeMessagesByRequestId("listening")
                    chatAdapter.addMessage(ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = ChatMessage.Role.USER,
                        text = text,
                        requestId = "partial"
                    ))
                }
                scrollToBottom()
            }
        }
    }

    private val userTextReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val requestId = intent.getStringExtra(ListenerService.EXTRA_USER_TEXT_REQUEST_ID) ?: return
            val text = intent.getStringExtra(ListenerService.EXTRA_USER_TEXT) ?: return
            runOnUiThread {
                // Route to Telegram send preview if recording
                if (focusState == FocusState.TELEGRAM_RECORDING) {
                    telegramShowSendPreview(text)
                    return@runOnUiThread
                }
                if (focusState == FocusState.NOTIFICATION_REPLY) {
                    // Final transcript arrived (phone VAD end-of-speech). A blank
                    // transcript means nothing was captured -> the service already
                    // drove the overlay to CANCELLED; just tear down. Otherwise open
                    // the 3s double-tap-to-cancel SENDING window; the actual send
                    // fires when it elapses (commitReplySend).
                    if (text.isBlank()) {
                        uiLog("[NREPLY] final blank -> endNotifReply")
                        endNotifReply()
                    } else {
                        beginReplySendWindow(text)
                    }
                    return@runOnUiThread
                }
                // "tg_voice" is the requestId for BOTH telegram-voice and
                // notification-reply finals. It is ONLY ever meaningful in the
                // TELEGRAM_RECORDING / NOTIFICATION_REPLY focus states (handled
                // above). If it lands here in any other focus, it is a stray or
                // duplicate that arrived just after the reply already tore down and
                // restored focus -- it must NOT be treated as an AI-chat user
                // message. Doing so adds a phantom USER bubble and starts a
                // "Thinking..." spinner that never clears (no AI response ever comes
                // for a tg_voice reply). Drop it.
                if (requestId == "tg_voice") {
                    uiLog("UTXT: ignoring stray tg_voice (focus=$focusState text='${text.take(20)}')")
                    return@runOnUiThread
                }
                uiLog("UTXT: reqId=${requestId.take(8)} state=$serviceState msgs=${chatAdapter.itemCount} text='${text.take(40)}'")
                // Always display user text -- phone manages state and only sends valid messages.
                hideAudioVisualizer()
                stopLoading()
                // Replace all temporary messages with the final user text
                chatAdapter.removeMessagesByRequestId("listening")
                chatAdapter.removeMessagesByRequestId("pending")
                chatAdapter.removeMessagesByRequestId("partial")
                if (requestId != "pending") {
                    partialsSuppressed = true
                }
                uiLog("UTXT: after cleanup msgs=${chatAdapter.itemCount}, adding USER reqId=${requestId.take(8)}")
                chatAdapter.addMessage(ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = ChatMessage.Role.USER,
                    text = text,
                    requestId = requestId
                ))
                // Attach photo thumbnail to USER message if present
                val photoThumb = intent.getStringExtra(ListenerService.EXTRA_USER_PHOTO_THUMB)
                if (!photoThumb.isNullOrEmpty() && requestId != "pending") {
                    try {
                        val bytes = Base64.decode(photoThumb, Base64.DEFAULT)
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) {
                            val green = BitmapUtils.toMonochromeGreen(bmp)
                            bmp.recycle()
                            chatAdapter.setThumbnail(requestId, green)
                            uiLog("UTXT: attached photo thumbnail to user message")
                        }
                    } catch (e: Exception) {
                        uiLog("UTXT: photo thumb error: ${e.message}")
                    }
                }
                scrollToBottom()

                // Only start the thinking indicator on real UUID text (not pending),
                // and only while a session is actually live (LISTENING/RESPONDING).
                // A final that lands after the session already went IDLE (e.g. it
                // was cancelled, or a stale/duplicate broadcast) must NOT start a
                // spinner -- no response will follow, so it would count up forever
                // (the same stuck-"Thinking..." failure mode as the stray tg_voice).
                if (requestId != "pending" && serviceState != "IDLE") {
                    statusArea.visibility = View.VISIBLE
                    setStatus("Thinking...", R.drawable.ic_status_dot, Lum.GLOW)
                    if (!timerRunning) {
                        startTimer()
                    }
                    startLoading()
                }
            }
        }
    }

    private val responseMetaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val json = intent.getStringExtra(ListenerService.EXTRA_RESPONSE_META) ?: return
            try {
                val obj = JSONObject(json)
                val requestId = obj.getString("requestId")
                val responseTimeMs = obj.getLong("responseTimeMs")
                val tokenCount = obj.getInt("tokenCount")
                runOnUiThread {
                    stopLoading()
                    val idx = chatAdapter.findAssistantByRequestId(requestId)
                    if (idx >= 0) {
                        chatAdapter.setMessageMeta(idx, responseTimeMs, tokenCount)
                    }
                    // Show in status bar
                    val timeSec = "%.1fs".format(responseTimeMs / 1000.0)
                    setStatus("$timeSec | $tokenCount tokens", R.drawable.ic_status_dot, Lum.DIM)
                }
            } catch (_: Exception) {}
        }
    }

    private val toolStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val json = intent.getStringExtra(ListenerService.EXTRA_TOOL_STATUS) ?: return
            try {
                val obj = JSONObject(json)
                val requestId = obj.getString("requestId")
                val toolName = obj.getString("toolName")
                val status = obj.optString("status", "")

                runOnUiThread {
                    if (status == "complete" || status == "error") {
                        chatAdapter.stopToolAnimation(requestId)
                    } else {
                        val toolArgs = obj.optJSONObject("toolArgs")
                        val toolCallId = obj.optString("toolCallId", "")
                        val argSummary = toolArgs?.let {
                            it.optString("query", "").ifEmpty { null }
                                ?: it.optString("url", "").ifEmpty { null }
                                ?: it.optString("command", "").ifEmpty { null }
                                ?: it.optString("prompt", "").ifEmpty { null }
                                ?: it.optString("path", "").ifEmpty { null }
                        }
                        val displayText = if (!argSummary.isNullOrEmpty()) {
                            "$toolName: ${argSummary.take(40)}"
                        } else toolName
                        val upsertId = toolCallId.ifEmpty { requestId }
                        chatAdapter.upsertToolMessage(upsertId, requestId, displayText)
                        scrollToBottom()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private val sessionResetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            runOnUiThread {
                stopTimer()
                hideLoadingSpinner()
                stopLoading()
                chatAdapter.clear()
                chatListAdapter.clear()
                focusState = FocusState.TAB_NAV
                updateFocusVisual(focusState)
                switchToTab(chatTabIndex(), animate = false)
                statusArea.visibility = View.INVISIBLE
            }
        }
    }

    private val mapBitmapReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val bytes = intent.getByteArrayExtra(ListenerService.EXTRA_MAP_BITMAP_BYTES) ?: return
            // Frame-drop coalescing: keep only the newest frame. If a task is already
            // queued/running it will pick up whatever is latest when it reaches the
            // swap, so older frames are discarded rather than queued.
            pendingMapFrame.set(bytes)
            mapWorkerHandler?.post { processLatestMapFrame() }
        }
    }

    // Runs on mapWorkerThread. Pulls the latest pending frame (dropping any older
    // ones), does all heavy decode + monochrome work here, then hops to the UI
    // thread only for view mutations + the crossfade animation.
    private fun processLatestMapFrame() = GT.section("ui.map.process") {
        val bytes = pendingMapFrame.getAndSet(null) ?: return@section
        try {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: run {
                dbg("mapBitmap: decode failed, ${bytes.size} bytes")
                return@section
            }
            // Steady-state is silent: at 10-15 FPS a per-frame log (plus the 3 getPixel
            // readbacks it used to do) would flood the persistent glasses log and stall
            // the decode. Emit a lightweight size line at most once per second; no getPixel.
            val now = SystemClock.elapsedRealtime()
            if (now - lastMapDebugMs >= 1000L) {
                lastMapDebugMs = now
                dbg("mapBitmap: ${bitmap.width}x${bitmap.height} ${bytes.size}B")
            }
            val green = BitmapUtils.toMonochromeGreen(bitmap)
            bitmap.recycle()
            // Build the minimap copy off-thread too; only setImageBitmap touches the UI.
            val minimapCopy = if (minimapActive) green.copy(green.config, false) else null
            runOnUiThread {
                setMapBaseBitmap(green)
                if (minimapCopy != null) {
                    if (minimapActive) {
                        minimapView.setImageBitmap(minimapCopy)
                    } else {
                        // Minimap was turned off between worker dispatch and UI hop.
                        minimapCopy.recycle()
                    }
                }
            }
        } catch (e: Exception) {
            dbg("mapBitmap ERROR: ${e.message}")
        }
    }

    private val mapArrowReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val intent = i ?: return
            val x = intent.getFloatExtra(ListenerService.EXTRA_ARROW_X, 0.5f)
            val y = intent.getFloatExtra(ListenerService.EXTRA_ARROW_Y, 0.5f)
            val h = intent.getFloatExtra(ListenerService.EXTRA_ARROW_HEADING, 0f)
            runOnUiThread { onArrowSample(x, y, h) }
        }
    }

    private fun setMapBaseBitmap(bmp: Bitmap) {
        // The map bitmap is shown flat and north-up; both the perspective keystone
        // and the screen-aligned edge-fade vignette are applied frame-fixed at draw
        // time by KeystoneFrame so they DON'T rotate with the map (which previously
        // baked the fade into the bitmap, making it spin with the heading -- the
        // "fog of war" look). So no per-bitmap processing remains here.
        // Direct swap, no crossfade: at 10-15 FPS a 250ms fade would perpetually thrash
        // and never settle. Cancel any in-flight fade and drop the fade view so only the
        // persistent content view drives the map. Rotation on mapContentView is owned by
        // applyArrow and is untouched here.
        mapBaseFadeView.animate().cancel()
        mapBaseFadeView.alpha = 0f
        // Defer the previous bitmap's recycle by one frame: the render thread may still
        // be drawing it while we swap in the new one at 15 FPS, so an immediate
        // synchronous recycle here can trip "trying to use a recycled bitmap". post()
        // runs after the current frame's draw, so the old bitmap is safe to free then.
        val prev = lastBaseBitmap
        mapContentView.setImageBitmap(bmp)
        lastBaseBitmap = bmp
        if (prev != null) {
            mapContentView.post {
                if (!prev.isRecycled) prev.recycle()
            }
        }
    }

    // Clear the minimap overlay and free the bitmap it was holding. minimapView is fed
    // independent green.copy() bitmaps (see processLatestMapFrame), so the view owns its
    // bitmap and it is safe to recycle once on every minimap-off / map-tab-replace path.
    // Exactly-once: setImageBitmap(null) drops the drawable so a second call finds none.
    private fun recycleMinimapBitmap() {
        val old = (minimapView.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        minimapView.setImageBitmap(null)
        if (old != null && !old.isRecycled) old.recycle()
    }

    private fun onArrowSample(x: Float, y: Float, h: Float) {
        arrowSamples.addLast(ArrowSample(SystemClock.elapsedRealtime(), x, y, h))
        while (arrowSamples.size > 4) arrowSamples.removeFirst()
        mapArrowView.visibility = View.VISIBLE
        if (arrowFrameCallback == null) {
            val cb = object : android.view.Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    drawArrowFrame()
                    if (arrowFrameCallback === this) {
                        android.view.Choreographer.getInstance().postFrameCallback(this)
                    }
                }
            }
            arrowFrameCallback = cb
            android.view.Choreographer.getInstance().postFrameCallback(cb)
        }
    }

    private fun drawArrowFrame() {
        val n = arrowSamples.size
        if (n == 0) return
        if (n < 2) {
            val s = arrowSamples.first()
            applyArrow(s.x, s.y, s.heading)
            return
        }
        val samples = arrowSamples.toList()
        val tr = SystemClock.elapsedRealtime() - arrowRenderDelayMs
        val last = samples.size - 1
        // Locate segment [i, i+1] containing tr.
        var i = 0
        if (tr <= samples[0].t) {
            applyArrow(samples[0].x, samples[0].y, samples[0].heading)
            return
        }
        if (tr >= samples[last].t) {
            applyArrow(samples[last].x, samples[last].y, samples[last].heading)
            return
        }
        while (i < last && !(samples[i].t <= tr && tr <= samples[i + 1].t)) i++
        val s1 = samples[i]
        val s2 = samples[i + 1]
        val span = (s2.t - s1.t).coerceAtLeast(1L)
        val tLocal = ((tr - s1.t).toDouble() / span.toDouble()).coerceIn(0.0, 1.0)
        val s0 = samples[(i - 1).coerceAtLeast(0)]
        val s3 = samples[(i + 2).coerceAtMost(last)]
        val ix = catmullRom(s0.x.toDouble(), s1.x.toDouble(), s2.x.toDouble(), s3.x.toDouble(), tLocal)
        val iy = catmullRom(s0.y.toDouble(), s1.y.toDouble(), s2.y.toDouble(), s3.y.toDouble(), tLocal)
        val ih = interpolateHeading(s0.heading, s1.heading, s2.heading, s3.heading, tLocal)
        applyArrow(ix.toFloat(), iy.toFloat(), ih)
    }

    private fun applyArrow(x: Float, y: Float, headingDeg: Float) {
        val w = mapContentView.width
        val h = mapContentView.height
        if (w <= 0 || h <= 0) return
        // Heading-up minimap: the arrow stays pinned dead-center pointing up
        // (it is centered via layout_gravity, so no translation), and the flat
        // map rotates underneath it by -heading. The interpolated x/y are unused
        // for placement -- only the smoothed heading drives the map rotation.
        mapArrowView.rotation = 0f
        mapContentView.rotation = -headingDeg
        mapBaseFadeView.rotation = -headingDeg
    }

    private fun catmullRom(p0: Double, p1: Double, p2: Double, p3: Double, t: Double): Double {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5 * (
            (2.0 * p1) +
            (-p0 + p2) * t +
            (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2 +
            (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3
        )
    }

    private fun interpolateHeading(h0: Float, h1: Float, h2: Float, h3: Float, t: Double): Float {
        // Unwrap successive deltas into (-180, 180] then Catmull-Rom in linear space.
        val u0 = h0.toDouble()
        val u1 = u0 + shortestDelta(h0, h1)
        val u2 = u1 + shortestDelta(h1, h2)
        val u3 = u2 + shortestDelta(h2, h3)
        var v = catmullRom(u0, u1, u2, u3, t)
        v = ((v % 360.0) + 360.0) % 360.0
        return v.toFloat()
    }

    private fun shortestDelta(a: Float, b: Float): Double {
        var d = (b - a).toDouble()
        while (d > 180.0) d -= 360.0
        while (d <= -180.0) d += 360.0
        return d
    }

    private fun clearArrowState() {
        arrowFrameCallback?.let { android.view.Choreographer.getInstance().removeFrameCallback(it) }
        arrowFrameCallback = null
        arrowSamples.clear()
        mapArrowView.visibility = View.GONE
        // Reset heading-up rotation so a stale angle doesn't persist on re-show.
        mapContentView.rotation = 0f
        mapBaseFadeView.rotation = 0f
    }

    private val toolThumbnailReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val requestId = intent.getStringExtra("requestId") ?: return
            val thumbBase64 = intent.getStringExtra("thumbBase64")
            if (thumbBase64.isNullOrEmpty()) return
            uiLog("TOOL_THUMB: received requestId=$requestId thumb=${thumbBase64.length} chars")
            runOnUiThread {
                try {
                    val bytes = Base64.decode(thumbBase64, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: run {
                        uiLog("TOOL_THUMB: bitmap decode failed")
                        return@runOnUiThread
                    }
                    val green = BitmapUtils.toMonochromeGreen(bitmap)
                    bitmap.recycle()
                    uiLog("TOOL_THUMB: calling setToolThumbnail for $requestId")
                    val found = chatAdapter.setToolThumbnail(requestId, green)
                    uiLog("TOOL_THUMB: setToolThumbnail found=$found")
                    scrollToBottom()
                } catch (e: Exception) {
                    uiLog("TOOL_THUMB ERROR: ${e.message}")
                }
            }
        }
    }

    private val photoProgressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pct = intent.getIntExtra("progress", 0)
            runOnUiThread {
                if (pct >= 100) {
                    progressBar.visibility = View.GONE
                } else {
                    statusArea.visibility = View.VISIBLE
                    progressBar.visibility = View.VISIBLE
                    progressBar.text = "Photo ${pct}%"
                }
            }
        }
    }

    private val mapMinimapReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val visible = intent.getBooleanExtra("visible", false)
            runOnUiThread {
                if (visible) {
                    minimapActive = true
                    showMapTab() // auto-focuses MAP via afterTabsChanged(switchToAdded)
                } else {
                    minimapActive = false
                    hideMapTab()
                    Anim.fadeOut(minimapView, 150L) {
                        recycleMinimapBitmap()
                    }
                }
            }
        }
    }

    private val navStepsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val json = intent.getStringExtra("steps_json") ?: return
            runOnUiThread {
                journeyStepsJson = json
                try {
                    parsedSteps = JSONArray(json)
                } catch (_: Exception) {
                    parsedSteps = null
                }
                populateStepsModal(json)
                if (currentNavStepIndex >= 0) updateStepStrip(currentNavStepIndex)
            }
        }
    }

    private val navStepIndexReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val json = intent.getStringExtra("step_index_json") ?: return
            runOnUiThread {
                try {
                    val obj = JSONObject(json)
                    val index = obj.getInt("index")
                    currentNavStepIndex = index
                    // Only update UI if we have steps data; otherwise just store the index
                    // and it will be applied when steps arrive via navStepsReceiver
                    if (parsedSteps != null && parsedSteps!!.length() > 0) {
                        val safeIndex = index.coerceIn(0, parsedSteps!!.length() - 1)
                        currentNavStepIndex = safeIndex
                        updateStepStrip(safeIndex)
                        if (journeyStepsJson != null) populateStepsModal(journeyStepsJson!!)
                    }
                } catch (e: Exception) {
                    uiLog("Failed to parse step index: ${e.message}")
                }
            }
        }
    }

    private val cameraPreviewReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.getStringExtra("action") ?: return
            runOnUiThread {
                when (action) {
                    "start" -> showCameraPreview()
                    "stop" -> hideCameraPreview()
                }
            }
        }
    }

    // Rokid PsensorObserver leg fold/unfold broadcast (glasses_leg_state
    // "1"=spread/unfolded, "0"=folded). Drives foldedState, which gates touchpad.
    private val foldStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != FOLD_LEG_ACTION) return
            when (intent.getStringExtra("glasses_leg_state")) {
                "1" -> foldedState = false
                "0" -> foldedState = true
            }
        }
    }

    private fun readFoldedProperty(): Boolean? = try {
        val cls = Class.forName("android.os.SystemProperties")
        val m = cls.getMethod("get", String::class.java, String::class.java)
        when (m.invoke(null, "vendor.rkd.glasses.is_spread", "") as String) {
            "1" -> false
            "0" -> true
            else -> null
        }
    } catch (_: Throwable) { null }

    private val teleprompterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.getStringExtra("action") ?: return
            runOnUiThread {
                when (action) {
                    "pause" -> teleprompterController?.pause()
                    "resume" -> teleprompterController?.resume()
                    "stop" -> stopTeleprompter()
                    "scroll" -> {
                        val amount = intent.getIntExtra("scroll_amount", 0)
                        teleprompterController?.scrollBy(amount)
                    }
                    "set_position" -> {
                        val wordIndex = intent.getIntExtra("word_index", -1)
                        if (wordIndex >= 0) {
                            teleprompterController?.setWordPosition(wordIndex)
                        }
                    }
                }
            }
        }
    }

    private val chatListReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val json = intent.getStringExtra(ListenerService.EXTRA_CHAT_LIST) ?: return
            runOnUiThread { parseChatListAndDisplay(json) }
        }
    }

    private val chatHistoryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val conversationId = intent.getStringExtra(ListenerService.EXTRA_CONVERSATION_ID) ?: return
            val turnsJson = intent.getStringExtra(ListenerService.EXTRA_CHAT_HISTORY) ?: return
            runOnUiThread { loadChatHistory(conversationId, turnsJson) }
        }
    }

    private val todoListReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val json = intent.getStringExtra(ListenerService.EXTRA_TODO_JSON) ?: return
            runOnUiThread { parseTodoListAndDisplay(json) }
        }
    }

    private val alarmListReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val json = intent.getStringExtra(ListenerService.EXTRA_ALARM_JSON) ?: return
            runOnUiThread { parseAlarmListAndDisplay(json) }
        }
    }

    private val jobListReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val json = intent.getStringExtra(ListenerService.EXTRA_JOB_JSON) ?: return
            runOnUiThread { parseJobListAndDisplay(json) }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val pct = (level * 100) / scale
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            runOnUiThread { updateBatteryUI(pct, charging) }
        }
    }

    private val timeTickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            runOnUiThread { updateTimeUI() }
        }
    }

    private val debugStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra(ListenerService.EXTRA_DEBUG_STATUS) ?: return
            runOnUiThread { debugStatus.text = status }
        }
    }

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val connected = intent.getBooleanExtra(ListenerService.EXTRA_BT_CONNECTED, false)
            runOnUiThread {
                btConnected = connected
                updateDebugLine(btConnected = connected)
                reidStartStopContainer.visibility = if (btConnected && orchConnected) View.VISIBLE else View.GONE
                if (connected) {
                    // Load data for the active tab now that BT is connected
                    val activeTab = activeTabs.getOrNull(currentTab)
                    if (activeTab == TabId.TODO) requestTodoData()
                    if (activeTab == TabId.CHAT_LIST) requestChatList()
                }
            }
        }
    }

    private val cameraPermRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            runOnUiThread {
                ActivityCompat.requestPermissions(
                    this@MainActivity,
                    arrayOf(Manifest.permission.CAMERA),
                    200
                )
            }
        }
    }

    private val orchestratorStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val connected = intent.getBooleanExtra(ListenerService.EXTRA_ORCHESTRATOR_CONNECTED, true)
            runOnUiThread {
                orchConnected = connected
                reidStartStopContainer.visibility = if (btConnected && orchConnected) View.VISIBLE else View.GONE
                if (!connected && btConnected) {
                    statusArea.visibility = View.VISIBLE
                    setStatus("Orchestrator disconnected", R.drawable.ic_status_dot, Lum.GHOST)
                } else if (connected && btConnected) {
                    statusArea.visibility = View.INVISIBLE
                }
            }
        }
    }

    private val recordingStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(
                ListenerService.EXTRA_RECORDING_STATE,
                ListenerService.RECORDING_STATE_IDLE,
            )
            runOnUiThread {
                val iv = recordingIndicator ?: return@runOnUiThread
                when (state) {
                    ListenerService.RECORDING_STATE_ACTIVE -> {
                        iv.setImageResource(R.drawable.ic_video_rec)
                        iv.visibility = View.VISIBLE
                    }
                    ListenerService.RECORDING_STATE_PAUSED -> {
                        iv.setImageResource(R.drawable.ic_video_rec_paused)
                        iv.visibility = View.VISIBLE
                    }
                    else -> iv.visibility = View.GONE
                }
            }
        }
    }

    private val weatherUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val icon = intent.getStringExtra(ListenerService.EXTRA_WEATHER_ICON) ?: ""
            val temp = intent.getStringExtra(ListenerService.EXTRA_WEATHER_TEMP) ?: ""
            LogCollector.i("MainActivity", "weatherUpdateReceiver icon=$icon temp=$temp")
            runOnUiThread {
                if (icon.isEmpty()) {
                    weatherIcon.visibility = View.GONE
                    weatherTemp.visibility = View.GONE
                } else {
                    weatherIcon.setImageResource(mapWeatherIcon(icon))
                    weatherTemp.text = "${temp}\u00B0"
                    weatherIcon.visibility = View.VISIBLE
                    weatherTemp.visibility = View.VISIBLE
                }
            }
        }
    }

    private val loneIndicatorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val active = intent.getBooleanExtra(ListenerService.EXTRA_LONE_ACTIVE, false)
            val count = intent.getIntExtra(ListenerService.EXTRA_LONE_COUNT, 0)
            runOnUiThread {
                if (active) {
                    loneIndicatorCount.text = count.toString()
                    loneIndicatorIcon.visibility = View.VISIBLE
                    loneIndicatorCount.visibility = View.VISIBLE
                } else {
                    loneIndicatorIcon.visibility = View.GONE
                    loneIndicatorCount.visibility = View.GONE
                }
            }
        }
    }

    private val callUiStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val phaseName = intent.getStringExtra("phase") ?: return
            val phase = try {
                com.repository.glasses.listener.bt.CallController.CallPhase.valueOf(phaseName)
            } catch (_: Exception) {
                com.repository.glasses.listener.bt.CallController.CallPhase.IDLE
            }
            val number = intent.getStringExtra("number") ?: ""
            val name = intent.getStringExtra("name") ?: ""
            val started = intent.getLongExtra("startedElapsedRealtime", 0L)
            val micMuted = intent.getBooleanExtra("micMuted", false)
            val scoActive = intent.getBooleanExtra("scoActive", false)
            LogCollector.i("MainActivity", "callUiState phase=$phase num='$number' name='$name' started=$started muted=$micMuted sco=$scoActive")
            runOnUiThread {
                callNumber = number
                callName = name
                callStartedElapsedRealtime = started
                val prev = callPhase
                callPhase = phase
                callMicMuted = micMuted
                callScoActive = scoActive
                updateMicMuteIndicator()
                when (phase) {
                    com.repository.glasses.listener.bt.CallController.CallPhase.INCOMING -> {
                        // An incoming call interrupts an in-flight notification reply.
                        // Tear it down first (cancels the send broadcast, drops the
                        // auto-send countdown, hides the voice overlay) so the reply
                        // UI and its countdown can't keep running under the call.
                        if (focusState == FocusState.NOTIFICATION_REPLY || activeReplyNotifId != null) {
                            abortNotifReply()
                        }
                        if (focusState != FocusState.CALL_INCOMING && focusState != FocusState.CALL_ACTIVE) {
                            previousFocusState = focusState
                        }
                        focusState = FocusState.CALL_INCOMING
                        updateFocusVisual(focusState)
                        updateCallIndicator()
                    }
                    com.repository.glasses.listener.bt.CallController.CallPhase.ACTIVE -> {
                        // Don't trap focus during an active call. The small call
                        // indicator stays visible and HFP-SCO audio runs independently
                        // of the UI, so the user can navigate the glasses app normally
                        // while talking. Hang-up is via the phone.
                        if (focusState == FocusState.CALL_INCOMING || focusState == FocusState.CALL_ACTIVE) {
                            val restore = previousFocusState ?: FocusState.TAB_NAV
                            previousFocusState = null
                            focusState = restore
                            updateFocusVisual(focusState)
                        }
                        updateCallIndicator()
                    }
                    com.repository.glasses.listener.bt.CallController.CallPhase.IDLE,
                    com.repository.glasses.listener.bt.CallController.CallPhase.ENDING -> {
                        // Restore previous focus. Drop any pending accept runnable.
                        pendingCallAccept?.let { callKeyHandler.removeCallbacks(it) }
                        pendingCallAccept = null
                        callNumber = ""
                        callName = ""
                        callStartedElapsedRealtime = 0L
                        val restore = previousFocusState ?: FocusState.TAB_NAV
                        previousFocusState = null
                        if (focusState == FocusState.CALL_INCOMING || focusState == FocusState.CALL_ACTIVE) {
                            focusState = restore
                            updateFocusVisual(focusState)
                        }
                        updateCallIndicator()
                    }
                    com.repository.glasses.listener.bt.CallController.CallPhase.OUTGOING_DIALING -> {
                        // No glasses UI for outgoing dialing yet.
                    }
                }
                if (prev != phase) {
                    uiLog("CALL: phase ${prev} -> ${phase} focus=$focusState")
                }
            }
        }
    }

    private val callDurationTick = object : Runnable {
        override fun run() {
            if (callPhase == com.repository.glasses.listener.bt.CallController.CallPhase.ACTIVE) {
                updateCallIndicator()
                callKeyHandler.postDelayed(this, 1000L)
            }
        }
    }

    private fun updateMicMuteIndicator() {
        val ind = micMuteIndicator ?: return
        // Show whenever HF audio is active (SCO up) and we're muted; covers both a
        // real HFP call and a PC AG that brings up SCO without an AG_CALL_CHANGED.
        val activeAudio = callScoActive ||
            callPhase == com.repository.glasses.listener.bt.CallController.CallPhase.ACTIVE
        val show = callMicMuted && activeAudio
        ind.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun updateCallIndicator() {
        val ind = callIndicator ?: return
        val label = callIndicatorLabel ?: return
        val dur = callDurationText ?: return
        when (callPhase) {
            com.repository.glasses.listener.bt.CallController.CallPhase.INCOMING -> {
                label.visibility = View.VISIBLE
                dur.text = "RING"
                ind.visibility = View.VISIBLE
                callKeyHandler.removeCallbacks(callDurationTick)
            }
            com.repository.glasses.listener.bt.CallController.CallPhase.ACTIVE -> {
                label.visibility = View.VISIBLE
                val elapsedMs = android.os.SystemClock.elapsedRealtime() - callStartedElapsedRealtime
                val totalSec = (elapsedMs / 1000L).coerceAtLeast(0L)
                val mm = totalSec / 60
                val ss = totalSec % 60
                dur.text = String.format("%02d:%02d", mm, ss)
                ind.visibility = View.VISIBLE
                // Ensure a ticker is running but never double-scheduled.
                callKeyHandler.removeCallbacks(callDurationTick)
                callKeyHandler.postDelayed(callDurationTick, 1000L)
            }
            else -> {
                ind.visibility = View.GONE
                dur.text = ""
                callKeyHandler.removeCallbacks(callDurationTick)
            }
        }
    }

    private fun mapWeatherIcon(tag: String): Int = when (tag) {
        "clear" -> R.drawable.weather_clear
        "cloudy" -> R.drawable.weather_cloudy
        "rain" -> R.drawable.weather_rain
        "snow" -> R.drawable.weather_snow
        "thunder" -> R.drawable.weather_thunder
        "fog" -> R.drawable.weather_fog
        else -> R.drawable.weather_cloudy
    }

    private val translationResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val json = intent.getStringExtra(ListenerService.EXTRA_TRANSLATION_RESULT) ?: return
            runOnUiThread {
                try {
                    val obj = JSONObject(json)
                    val id = obj.getInt("id")
                    val text = obj.optString("text", "")
                    val translation = obj.optString("translation", "")
                    val partial = obj.optBoolean("partial", true)
                    handleTranslationResult(id, text, translation, partial)
                } catch (_: Exception) {}
            }
        }
    }

    private val translationConfigReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val json = intent.getStringExtra(ListenerService.EXTRA_TRANSLATION_CONFIG) ?: return
            runOnUiThread {
                try {
                    val obj = JSONObject(json)
                    val from = obj.optString("fromLanguage", "?").uppercase()
                    val to = obj.optString("toLanguage", "?").uppercase()
                    translationFontSize = obj.optInt("fontSize", 14)
                    translationLangsLabel = "$from -> $to"
                    translationStatus.text = translationLangsLabel
                    translationStatus.setTextColor(Lum.MID)
                    translationSegments.clear()
                    translationChunksContainer.removeAllViews()
                } catch (_: Exception) {}
            }
        }
    }

    // Tracks whether translation is currently active (mic tap running on service side)
    @Volatile private var translationActive = false
    // Tracks whether translation is in the process of starting (between toggle request and state broadcast)
    private var translationStarting = false
    private val translationStartTimeoutRunnable = Runnable {
        if (translationStarting) {
            translationStarting = false
            runOnUiThread {
                // Reset to idle -- no state arrived within timeout
                translationStartStopIcon.alpha = 1.0f
                translationStartStopIcon.setImageResource(R.drawable.ic_play)
                val cfg = GlassesConfig.getTranslationFromLanguage(this@MainActivity)
                val cfgTo = GlassesConfig.getTranslationToLanguage(this@MainActivity)
                if (cfg.isNotEmpty() && cfgTo.isNotEmpty()) {
                    translationStatus.text = "${cfg.uppercase()} -> ${cfgTo.uppercase()}"
                } else {
                    translationStatus.text = ""
                }
                translationStatus.setTextColor(Lum.DIM)
            }
        }
    }

    private val translationStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val active = intent.getBooleanExtra(ListenerService.EXTRA_TRANSLATION_ACTIVE, false)
            runOnUiThread {
                if (active) {
                    translationStarting = false
                    mainHandler.removeCallbacks(translationStartTimeoutRunnable)
                    translationActive = true
                    translationSegments.clear()
                    translationChunksContainer.removeAllViews()
                    translationStartStopIcon.alpha = 1.0f
                    translationStartStopIcon.setImageResource(R.drawable.ic_stop)
                    // Update status label from cached config
                    if (translationLangsLabel.isNotEmpty()) {
                        translationStatus.text = translationLangsLabel
                        translationStatus.setTextColor(Lum.MID)
                    }
                    // Navigate to translate tab
                    if (currentTabId != TabId.TRANSLATE) {
                        switchToTab(TabId.TRANSLATE)
                    }
                } else {
                    translationStarting = false
                    mainHandler.removeCallbacks(translationStartTimeoutRunnable)
                    translationActive = false
                    translationSegments.clear()
                    translationChunksContainer.removeAllViews()
                    translationStartStopIcon.alpha = 1.0f
                    translationStartStopIcon.setImageResource(R.drawable.ic_play)
                    // Show idle state with saved language pair (or blank)
                    val cfg = GlassesConfig.getTranslationFromLanguage(this@MainActivity)
                    val cfgTo = GlassesConfig.getTranslationToLanguage(this@MainActivity)
                    if (cfg.isNotEmpty() && cfgTo.isNotEmpty()) {
                        translationStatus.text = "${cfg.uppercase()} -> ${cfgTo.uppercase()}"
                    } else {
                        translationStatus.text = ""
                    }
                    translationStatus.setTextColor(Lum.DIM)
                }
            }
        }
    }

    private val mouseStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val active = intent.getBooleanExtra(ListenerService.EXTRA_MOUSE_ACTIVE, false)
            if (active) {
                mouseSensX = intent.getFloatExtra(ListenerService.EXTRA_MOUSE_SENSITIVITY_X, 1800f)
                mouseSensY = intent.getFloatExtra(ListenerService.EXTRA_MOUSE_SENSITIVITY_Y, 4200f)
            }
            runOnUiThread {
                if (active) showMouseTab() else hideMouseTab()
            }
        }
    }

    private val mouseStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == HidMouseService.ACTION_STATUS) {
                val tracking = intent.getBooleanExtra(HidMouseService.EXTRA_TRACKING, false)
                dpadHandler.trackingEnabled = tracking || rfcommMouseTracking
                runOnUiThread { updateMouseUI() }
            }
        }
    }

    // Authoritative tracking state of the RFCOMM/stream mouse path (stream_mode). The HID path's
    // isTracking does not cover it, so the UI + gesture machine must read this too.
    @Volatile private var rfcommMouseActive = false
    @Volatile private var rfcommMouseTracking = false

    private val rfcommMouseTrackingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ListenerService.ACTION_RFCOMM_MOUSE_TRACKING) {
                rfcommMouseActive = intent.getBooleanExtra(ListenerService.EXTRA_RFCOMM_ACTIVE, false)
                rfcommMouseTracking = intent.getBooleanExtra(ListenerService.EXTRA_RFCOMM_TRACKING, false)
                // Drive the d-pad gesture machine (tap=click/scroll, hold=right-click) off the
                // real tracking state so it works in stream-mode where HID isTracking stays false.
                dpadHandler.trackingEnabled = rfcommMouseTracking ||
                    (mouseService?.isTracking ?: false)
                runOnUiThread { updateMouseUI() }
            }
        }
    }

    // --- Translation intent handling ---

    private fun handleTranslationIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("start_translation", false) != true) return
        intent.removeExtra("start_translation")
        // Navigate to translate tab
        switchToTab(TabId.TRANSLATE)
    }

    // --- Teleprompter with fade transitions ---

    private fun handleTeleprompterIntent(intent: Intent?, fromOnCreate: Boolean) {
        if (intent?.getBooleanExtra("start_teleprompter", false) != true) return
        val text = intent.getStringExtra("teleprompter_text") ?: return
        val fontSize = intent.getIntExtra("teleprompter_font_size", 22)
        val speechTracking = intent.getBooleanExtra("teleprompter_speech_tracking", false)
        val reqId = intent.getStringExtra("teleprompter_request_id") ?: return
        if (text.isEmpty() || reqId.isEmpty()) return

        intent.removeExtra("start_teleprompter")

        if (fromOnCreate) {
            Handler(Looper.getMainLooper()).postDelayed({
                startTeleprompter(reqId, text, fontSize.toFloat(), speechTracking)
            }, 300)
        } else {
            startTeleprompter(reqId, text, fontSize.toFloat(), speechTracking)
        }
    }

    private fun startTeleprompter(requestId: String, text: String, fontSize: Float = 22f, speechTracking: Boolean = false) {
        cameraPreview.visibility = View.GONE

        val controller = TeleprompterController(this) { _ -> }
        teleprompterController = controller

        val view = controller.createView()
        teleprompterContainer.removeAllViews()
        teleprompterContainer.addView(view)

        controller.onScrollChanged = { updateTeleprompterScrollIndicator() }
        tpStopButton.visibility = View.VISIBLE
        tpStopButton.setTextColor(Lum.DIM)
        tpFocusedIndex = 1

        showTeleprompterTab() // auto-focuses TELEPROMPTER via afterTabsChanged(switchToAdded)

        controller.start(requestId, text, fontSize, speechTracking) { state, progress ->
            val stateJson = JSONObject().apply {
                put("state", state)
                put("progress", progress)
            }.toString()
            sendBroadcast(Intent(ListenerService.ACTION_TELEPROMPTER_STATE).apply {
                setPackage(packageName)
                putExtra("state_json", stateJson)
            })
            if (state in setOf("stopped", "finished")) {
                runOnUiThread { terminateTeleprompter() }
            }
        }
    }

    private fun stopTeleprompter() {
        teleprompterController?.stop()
    }

    /** Fully destroy teleprompter (on stop/finished). Hides tab. */
    private fun terminateTeleprompter() {
        teleprompterController?.destroy(teleprompterContainer)
        teleprompterController = null
        teleprompterContainer.removeAllViews()
        tpStopButton.visibility = View.GONE
        tpStopBorderAnimator?.cancel()
        tpScrollIndicatorHideRunnable?.let { mainHandler.removeCallbacks(it) }
        tpScrollIndicator.animate().cancel()
        tpScrollIndicator.alpha = 0f
        hideTeleprompterTab()
        focusState = FocusState.TAB_NAV
        switchToTab(chatTabIndex(), animate = false)
        updateFocusVisual(focusState)
    }

    private fun showTeleprompterTab() {
        if (TabId.TELEPROMPTER in activeTabs) return
        val density = Resources.getSystem().displayMetrics.density

        val icon = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                (TAB_ICON_DP * density + 0.5f).toInt(),
                (TAB_ICON_DP * density + 0.5f).toInt()
            ).apply { gravity = android.view.Gravity.CENTER }
            setImageResource(R.drawable.ic_teleprompter)
            setColorFilter(Lum.SOFT, PorterDuff.Mode.SRC_IN)
            isFocusable = false
            isFocusableInTouchMode = false
            defaultFocusHighlightEnabled = false
        }
        teleprompterTabIcon = icon

        val frame = FrameLayout(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
            )
            addView(icon)
        }
        teleprompterTabFrame = frame

        val tabIconsRow = (pillContainer as ViewGroup).getChildAt(1) as ViewGroup
        tabIconsRow.addView(frame) // append at end

        uiLog("NAV: ADD TELEPROMPTER at end, cur=$currentTabId before=[${activeTabs.joinToString()}]")
        activeTabs.add(TabId.TELEPROMPTER) // always last
        // Starting teleprompter always navigates to the teleprompter tab.
        afterTabsChanged(switchToAdded = TabId.TELEPROMPTER)
        updateFocusVisual(focusState)
    }

    private fun hideTeleprompterTab() {
        val tpIndex = activeTabs.indexOf(TabId.TELEPROMPTER)
        if (tpIndex < 0) return

        val tabIconsRow = (pillContainer as ViewGroup).getChildAt(1) as ViewGroup
        val frame = teleprompterTabFrame
        if (frame != null) tabIconsRow.removeView(frame)

        uiLog("NAV: REMOVE TELEPROMPTER, cur=$currentTabId before=[${activeTabs.joinToString()}]")
        activeTabs.remove(TabId.TELEPROMPTER)
        teleprompterTabIcon = null
        teleprompterTabFrame = null
        afterTabsChanged(removedAt = tpIndex)
        updateFocusVisual(focusState)
    }

    // --- Mouse tab dynamic creation/removal ---

    private fun showMouseTab() {
        if (TabId.MOUSE in activeTabs) return
        val density = Resources.getSystem().displayMetrics.density

        val icon = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                (TAB_ICON_DP * density + 0.5f).toInt(),
                (TAB_ICON_DP * density + 0.5f).toInt()
            ).apply { gravity = android.view.Gravity.CENTER }
            setImageResource(R.drawable.ic_mouse)
            setColorFilter(Lum.SOFT, PorterDuff.Mode.SRC_IN)
            isFocusable = false
            isFocusableInTouchMode = false
            defaultFocusHighlightEnabled = false
        }
        mouseTabIcon = icon

        val frame = FrameLayout(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
            )
            addView(icon)
        }
        mouseTabFrame = frame

        val tabIconsRow = (pillContainer as ViewGroup).getChildAt(1) as ViewGroup
        tabIconsRow.addView(frame)

        uiLog("NAV: ADD MOUSE at end, cur=$currentTabId before=[${activeTabs.joinToString()}]")
        activeTabs.add(TabId.MOUSE)
        startMouseService()
        // MOUSE is the only dynamic tab that auto-focuses itself on add.
        afterTabsChanged(switchToAdded = TabId.MOUSE)

        focusState = FocusState.MOUSE_FOCUSED
        updateFocusVisual(focusState)
    }

    private fun showMusicTab() {
        if (TabId.MUSIC in activeTabs) return
        (tabMusic.parent as? View)?.visibility = View.VISIBLE
        uiLog("NAV: ADD MUSIC at idx=0, cur=$currentTabId before=[${activeTabs.joinToString()}]")
        activeTabs.add(0, TabId.MUSIC)
        afterTabsChanged()
    }

    private fun hideMusicTab() {
        val musicIndex = activeTabs.indexOf(TabId.MUSIC)
        if (musicIndex < 0) return
        uiLog("NAV: REMOVE MUSIC, cur=$currentTabId before=[${activeTabs.joinToString()}]")
        activeTabs.remove(TabId.MUSIC)
        (tabMusic.parent as? View)?.visibility = View.GONE
        musicContainer.visibility = View.GONE
        afterTabsChanged(removedAt = musicIndex)
    }

    private fun hideMouseTab() {
        val mouseIndex = activeTabs.indexOf(TabId.MOUSE)
        if (mouseIndex < 0) return

        stopMouseService()
        dpadHandler.reset()
        dpadHandler.trackingEnabled = false

        if (focusState == FocusState.MOUSE_FOCUSED) {
            focusState = FocusState.TAB_NAV
        }

        val tabIconsRow = (pillContainer as ViewGroup).getChildAt(1) as ViewGroup
        val frame = mouseTabFrame
        if (frame != null) tabIconsRow.removeView(frame)

        uiLog("NAV: REMOVE MOUSE, cur=$currentTabId before=[${activeTabs.joinToString()}]")
        activeTabs.remove(TabId.MOUSE)
        mouseTabIcon = null
        mouseTabFrame = null
        mouseContainer.visibility = View.GONE
        afterTabsChanged(removedAt = mouseIndex)
        updateFocusVisual(focusState)
    }

    private fun startMouseService() {
        val intent = Intent(this, HidMouseService::class.java)
        startForegroundService(intent)
        bindService(intent, mouseServiceConnection, BIND_AUTO_CREATE)
        dpadHandler.listener = object : DpadInputHandler.Listener {
            // Route each gesture to the ACTIVE mouse path only: RFCOMM/stream when active,
            // else the standalone BLE-HID service. Driving both would do dead GATT writes and
            // (for toggle) spawn a second HeadTracker in stream_mode.
            override fun onLeftClick() {
                if (rfcommMouseActive) sendRfcommMouseEvent(click = 1) else mouseService?.sendClick(0x01)
            }
            override fun onRightClick() {
                if (rfcommMouseActive) sendRfcommMouseEvent(click = 2) else mouseService?.sendClick(0x02)
            }
            override fun onToggleTracking() {
                if (rfcommMouseActive) sendRfcommMouseEvent(toggle = true) else mouseService?.toggleTracking()
            }
            override fun onScrollUp() {
                if (rfcommMouseActive) sendRfcommMouseEvent(scroll = SCROLL_DELTA) else mouseService?.accumulateScroll(SCROLL_DELTA)
            }
            override fun onScrollDown() {
                if (rfcommMouseActive) sendRfcommMouseEvent(scroll = -SCROLL_DELTA) else mouseService?.accumulateScroll(-SCROLL_DELTA)
            }
            override fun onScrollLeft() {
                if (rfcommMouseActive) sendRfcommMouseEvent(scroll = SCROLL_DELTA) else mouseService?.accumulateScroll(SCROLL_DELTA)
            }
            override fun onScrollRight() {
                if (rfcommMouseActive) sendRfcommMouseEvent(scroll = -SCROLL_DELTA) else mouseService?.accumulateScroll(-SCROLL_DELTA)
            }
        }
    }

    private fun stopMouseService() {
        dpadHandler.listener = null
        if (mouseBound) {
            unbindService(mouseServiceConnection)
            mouseBound = false
        }
        stopService(Intent(this, HidMouseService::class.java))
        mouseService = null
    }

    private fun sendRfcommMouseEvent(click: Int = 0, scroll: Int = 0, toggle: Boolean = false) {
        sendBroadcast(Intent(ListenerService.ACTION_RFCOMM_MOUSE_EVENT).apply {
            setPackage(packageName)
            if (click != 0) putExtra("click", click)
            if (scroll != 0) putExtra("scroll", scroll)
            if (toggle) putExtra("toggle", true)
        })
    }

    private fun updateMouseUI() {
        // Tracking is ON if EITHER path reports it: RFCOMM/stream (stream_mode) or BLE-HID.
        val tracking = rfcommMouseTracking || (mouseService?.isTracking ?: false)
        // "Connected" is shown whenever a mouse session exists on either path.
        val connected = rfcommMouseActive || mouseService != null

        if (!connected) {
            mouseConnectionStatus.text = "Ready (via phone)"
            mouseConnectionStatus.setTextColor(Lum.DIM)
            mouseControlsHint.text = "Tap to start tracking"
            mouseControlsHint.setTextColor(Lum.GHOST)
            return
        }

        mouseConnectionStatus.text = if (tracking) "Tracking" else "Connected (via phone)"
        mouseConnectionStatus.setTextColor(if (tracking) Lum.BRIGHT else Lum.MID)

        if (tracking) {
            mouseControlsHint.text = "Tap: click | Hold: right click | Swipe: scroll"
            mouseControlsHint.setTextColor(Lum.SOFT)
        } else {
            mouseControlsHint.text = "Tap to start tracking"
            mouseControlsHint.setTextColor(Lum.GHOST)
        }
    }

    // --- Map tab dynamic creation/removal ---

    private fun showMapTab() {
        if (TabId.MAP in activeTabs) return
        dbg("showMapTab: activeTabs=$activeTabs pillW=${pillContainer.width}")
        val density = Resources.getSystem().displayMetrics.density

        // Create icon (NO background -- must be transparent so pill background shows through)
        val icon = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                (TAB_ICON_DP * density + 0.5f).toInt(),
                (TAB_ICON_DP * density + 0.5f).toInt()
            ).apply { gravity = android.view.Gravity.CENTER }
            setImageResource(R.drawable.ic_map)
            setColorFilter(Lum.SOFT, PorterDuff.Mode.SRC_IN)
            isFocusable = false
            isFocusableInTouchMode = false
            defaultFocusHighlightEnabled = false
        }
        mapTabIcon = icon

        // Create frame (NO background -- must be transparent so pill background shows through)
        val frame = FrameLayout(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
            )
            addView(icon)
        }
        mapTabFrame = frame

        // Compute view-tree insert index from the predecessor in activeTabs,
        // so the visible order matches activeTabs even when MUSIC view slot
        // (always present at view-idx 0, GONE when MUSIC inactive) shifts the
        // mapping.
        val targetActiveIdx = 3 // after Todo(0), Chat(1), ChatList(2)
        insertTabFrameAt(targetActiveIdx, frame)

        // Insert MAP into activeTabs at index 3
        uiLog("NAV: ADD MAP at idx=3, cur=$currentTabId before=[${activeTabs.joinToString()}]")
        activeTabs.add(targetActiveIdx, TabId.MAP)
        // Auto-focus the new tab. Without switchToAdded, afterTabsChanged would
        // queue a switch back to the previous selection on the next layout pass,
        // racing with (and clobbering) the explicit switchToTab the caller does.
        afterTabsChanged(switchToAdded = TabId.MAP)

        // Initialize button visuals
        updatePinButtonVisual()
        mapStopButton.setTextColor(Lum.DIM)

        // Hide minimap overlay (map tab replaces it entirely)
        if (minimapView.visibility == View.VISIBLE) {
            Anim.fadeOut(minimapView, 150L) {
                recycleMinimapBitmap()
            }
        }
        // Post focus visual refresh after pill has been laid out at new size
        pillContainer.post {
            updateFocusVisual(focusState)
            dbg("showMapTab DONE: pillW=${pillContainer.width} activeTabs=$activeTabs")
        }
    }

    private fun hideMapTab() {
        val mapIndex = activeTabs.indexOf(TabId.MAP)
        if (mapIndex < 0) return

        // Dismiss modals if open
        mapStopModal.visibility = View.GONE
        mapStepsModal.visibility = View.GONE
        if (focusState == FocusState.STOP_MODAL || focusState == FocusState.STEPS_MODAL || focusState == FocusState.MAP_FOCUSED) {
            focusState = FocusState.TAB_NAV
        }
        mapFocusedIndex = MAP_FOCUS_PIN  // reset to pin for next time
        journeyStepsJson = null
        parsedSteps = null
        currentNavStepIndex = -1
        mapStepStripClip.visibility = View.GONE
        renderedStepIndex = -1
        stepStripAnimator?.cancel()
        stepStripAnimator = null
        stepsListContainer.removeAllViews()

        // Unpin if pinned -- prevent orphaned overlay
        if (mapPinned) {
            mapPinned = false
            updatePinButtonVisual()
            sendBroadcast(Intent(ListenerService.ACTION_MAP_PIN).apply {
                setPackage(packageName)
                putExtra("pinned", false)
            })
        }

        // Remove from LinearLayout
        val tabIconsRow = (pillContainer as ViewGroup).getChildAt(1) as ViewGroup
        val frame = mapTabFrame
        if (frame != null) tabIconsRow.removeView(frame)

        uiLog("NAV: REMOVE MAP, cur=$currentTabId before=[${activeTabs.joinToString()}]")
        activeTabs.remove(TabId.MAP)
        mapTabIcon = null
        mapTabFrame = null
        mapContainer.visibility = View.GONE
        mapContentView.setImageBitmap(null)
        clearArrowState()
        afterTabsChanged(removedAt = mapIndex)
        pillContainer.post { updateFocusVisual(focusState) }
    }

    // --- Map pin ---

    private fun toggleMapPin() {
        mapPinned = !mapPinned
        updatePinButtonVisual()
        // Tell service to show/hide overlay
        sendBroadcast(Intent(ListenerService.ACTION_MAP_PIN).apply {
            setPackage(packageName)
            putExtra("pinned", mapPinned)
        })
    }

    private fun updatePinButtonVisual() {
        mapPinButton.setColorFilter(
            if (mapPinned) Lum.GLOW else Lum.DIM,
            android.graphics.PorterDuff.Mode.SRC_IN
        )
    }

    private var pinBorderAnimator: ValueAnimator? = null
    private var stopBorderAnimator: ValueAnimator? = null

    private fun updatePinFocus(focused: Boolean) {
        pinBorderAnimator?.cancel()
        val density = Resources.getSystem().displayMetrics.density
        val thinStroke = (0.5f * density + 0.5f).toInt()
        val thickStroke = (2f * density + 0.5f).toInt()

        if (focused) {
            val drawable = GradientDrawable().apply {
                setColor(Lum.VOID)
                cornerRadius = 8f * density
                setStroke(thinStroke, Lum.GLOW)
            }
            mapPinButton.background = drawable
            pinBorderAnimator = ValueAnimator.ofInt(thinStroke, thickStroke).apply {
                duration = 150L
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { drawable.setStroke(it.animatedValue as Int, Lum.GLOW) }
                start()
            }
        } else {
            mapPinButton.setBackgroundColor(Lum.VOID)
        }
    }

    private var zoomSliderBorderAnimator: ValueAnimator? = null

    private fun computeFillRatio(step: Int): Float {
        return (step.toFloat() / zoomSteps).coerceIn(0f, 1f)
    }

    /** Smoothly animates the fill bar to the target ratio for currentZoomStep. */
    private fun animateZoomFill() {
        zoomFillAnimator?.cancel()
        val from = mapZoomSliderFill.scaleX
        val to = computeFillRatio(currentZoomStep)
        if (kotlin.math.abs(from - to) < 0.001f) {
            mapZoomSliderFill.scaleX = to
            return
        }
        zoomFillAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = 180L
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { mapZoomSliderFill.scaleX = it.animatedValue as Float }
            start()
        }
    }

    /**
     * Apply the same focus-border treatment as the other map buttons: thin
     * stroke that animates to a thick stroke when freshly focused. When
     * `zooming` (slider in active step mode) the stroke stays at the thick
     * width to mark the heightened state.
     */
    private fun updateZoomSliderFocus(focused: Boolean, zooming: Boolean = false) {
        zoomSliderBorderAnimator?.cancel()
        val density = Resources.getSystem().displayMetrics.density
        val thinStroke = (0.5f * density + 0.5f).toInt()
        val thickStroke = (2f * density + 0.5f).toInt()

        if (focused) {
            val drawable = GradientDrawable().apply {
                setColor(Lum.VOID)
                cornerRadius = 8f * density
                setStroke(thinStroke, Lum.GLOW)
            }
            mapZoomSliderContainer.background = drawable
            zoomSliderBorderAnimator = ValueAnimator.ofInt(thinStroke, thickStroke).apply {
                duration = 150L
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { drawable.setStroke(it.animatedValue as Int, Lum.GLOW) }
                start()
            }
            // Brighter fill when in active-zoom mode, normal GLOW otherwise.
            mapZoomSliderFill.setBackgroundColor(if (zooming) Lum.GLOW else Lum.MID)
            mapZoomSliderTrack.setBackgroundColor(0xFF003300.toInt())
        } else {
            mapZoomSliderContainer.setBackgroundColor(Lum.VOID)
            mapZoomSliderFill.setBackgroundColor(Lum.DIM)
            mapZoomSliderTrack.setBackgroundColor(0xFF002200.toInt())
        }
    }

    /**
     * Apply a delta to currentZoomStep, clamp, animate fill and send to
     * phone. Returns true if the level actually changed.
     */
    private fun stepZoom(delta: Int): Boolean {
        val newStep = (currentZoomStep + delta).coerceIn(0, zoomSteps)
        if (newStep == currentZoomStep) return false
        currentZoomStep = newStep
        animateZoomFill()
        // Service forwards via BT to phone. One-way, no result expected. The phone
        // maps this abstract 0..1 fraction into the active provider's zoom range.
        sendBroadcast(Intent(ListenerService.ACTION_NAV_ZOOM).apply {
            setPackage(packageName)
            putExtra(ListenerService.EXTRA_ZOOM_FRACTION, currentZoomFraction)
        })
        return true
    }

    private fun updateStopFocus(focused: Boolean) {
        stopBorderAnimator?.cancel()
        val density = Resources.getSystem().displayMetrics.density
        val thinStroke = (0.5f * density + 0.5f).toInt()
        val thickStroke = (2f * density + 0.5f).toInt()

        if (focused) {
            val drawable = GradientDrawable().apply {
                setColor(Lum.VOID)
                cornerRadius = 8f * density
                setStroke(thinStroke, Lum.GLOW)
            }
            mapStopButton.background = drawable
            stopBorderAnimator = ValueAnimator.ofInt(thinStroke, thickStroke).apply {
                duration = 150L
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { drawable.setStroke(it.animatedValue as Int, Lum.GLOW) }
                start()
            }
        } else {
            mapStopButton.setBackgroundColor(Lum.VOID)
        }
    }

    private fun updatePillSize() {
        val density = Resources.getSystem().displayMetrics.density
        val params = pillContainer.layoutParams
        val oldW = params.width
        params.width = (activeTabs.size * TAB_SLOT_DP * density + 0.5f).toInt()
        pillContainer.layoutParams = params

        // When too many tabs, expand tab bar: pill sits on top row, status line
        // (time / weather / battery) stays on the bottom row so the pill never
        // overlaps the indicators.
        val expanded = activeTabs.size > TAB_OVERFLOW_THRESHOLD
        val barHeight = if (expanded) TAB_BAR_EXPANDED_DP else TAB_BAR_COMPACT_DP
        tabBar.layoutParams = (tabBar.layoutParams).apply {
            height = (barHeight * density + 0.5f).toInt()
        }
        val pillGravity = if (expanded) (android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.TOP) else (android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.BOTTOM)
        val statusStartGravity = if (expanded) (android.view.Gravity.BOTTOM or android.view.Gravity.START) else (android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START)
        val statusEndGravity = if (expanded) (android.view.Gravity.BOTTOM or android.view.Gravity.END) else (android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.END)
        (pillContainer.layoutParams as FrameLayout.LayoutParams).gravity = pillGravity
        pillContainer.layoutParams = pillContainer.layoutParams
        (timeText.layoutParams as FrameLayout.LayoutParams).gravity = statusStartGravity
        timeText.layoutParams = timeText.layoutParams
        (weatherRow.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = statusStartGravity
            height = if (expanded) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT
        }
        weatherRow.layoutParams = weatherRow.layoutParams
        (batteryIndicator.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = statusEndGravity
            height = if (expanded) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT
        }
        batteryIndicator.layoutParams = batteryIndicator.layoutParams
        repositionChargingIcon()

        dbg("pillSize: ${oldW}->${params.width} actual=${pillContainer.width} tabs=${activeTabs.size} expanded=$expanded")
    }

    // --- Stop journey modal ---

    private fun showStopModal() {
        focusState = FocusState.STOP_MODAL
        modalSelectedIndex = 1  // Cancel selected by default
        mapStopModal.visibility = View.VISIBLE
        updateModalButtons()
        updateFocusVisual(focusState)
    }

    private fun hideStopModal() {
        mapStopModal.visibility = View.GONE
        focusState = FocusState.MAP_FOCUSED
        updateFocusVisual(focusState)
    }

    private fun updateModalButtons() {
        val density = Resources.getSystem().displayMetrics.density
        val stroke = (1.5f * density + 0.5f).toInt()
        val radius = 8f * density

        modalStopBtn.setTextColor(if (modalSelectedIndex == 0) Lum.GLOW else Lum.DIM)
        modalStopBtn.background = if (modalSelectedIndex == 0) {
            GradientDrawable().apply {
                setColor(Lum.VOID)
                cornerRadius = radius
                setStroke(stroke, Lum.GLOW)
            }
        } else {
            null
        }

        modalCancelBtn.setTextColor(if (modalSelectedIndex == 1) Lum.GLOW else Lum.DIM)
        modalCancelBtn.background = if (modalSelectedIndex == 1) {
            GradientDrawable().apply {
                setColor(Lum.VOID)
                cornerRadius = radius
                setStroke(stroke, Lum.GLOW)
            }
        } else {
            null
        }
    }

    private fun confirmStopJourney() {
        mapStopModal.visibility = View.GONE
        focusState = FocusState.TAB_NAV
        updateFocusVisual(focusState)
        sendBroadcast(Intent(ListenerService.ACTION_STOP_JOURNEY).apply {
            setPackage(packageName)
        })
    }

    // --- Steps modal ---

    private var stepsBorderAnimator: ValueAnimator? = null

    private fun updateStepsFocus(focused: Boolean) {
        stepsBorderAnimator?.cancel()
        val density = Resources.getSystem().displayMetrics.density
        val thinStroke = (0.5f * density + 0.5f).toInt()
        val thickStroke = (2f * density + 0.5f).toInt()

        if (focused) {
            val drawable = GradientDrawable().apply {
                setColor(Lum.VOID)
                cornerRadius = 8f * density
                setStroke(thinStroke, Lum.GLOW)
            }
            mapStepsButton.background = drawable
            stepsBorderAnimator = ValueAnimator.ofInt(thinStroke, thickStroke).apply {
                duration = 150L
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { drawable.setStroke(it.animatedValue as Int, Lum.GLOW) }
                start()
            }
        } else {
            mapStepsButton.setBackgroundColor(Lum.VOID)
        }
    }

    private fun showStepsModal() {
        if (journeyStepsJson == null) return
        focusState = FocusState.STEPS_MODAL
        mapStepsModal.visibility = View.VISIBLE
        stepsScrollView.scrollTo(0, 0)
        updateFocusVisual(focusState)
    }

    private fun hideStepsModal() {
        mapStepsModal.visibility = View.GONE
        focusState = FocusState.MAP_FOCUSED
        updateFocusVisual(focusState)
    }

    private fun populateStepsModal(json: String) {
        stepsListContainer.removeAllViews()
        try {
            val steps = JSONArray(json)
            val density = Resources.getSystem().displayMetrics.density
            val routeStartTime = System.currentTimeMillis()
            val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            val gutterW = (20 * density).toInt()
            val dotChar = "\u25CF"  // ●
            val lineChar = "\u2502" // │

            for (i in 0 until steps.length()) {
                val step = steps.getJSONObject(i)
                val type = step.optString("type", "")
                val cumEta = step.optLong("cumulativeEtaSeconds", 0) - step.optLong("durationSeconds", 0)
                val boardTime = timeFmt.format(java.util.Date(routeStartTime + cumEta * 1000))
                val arriveEta = step.optLong("cumulativeEtaSeconds", 0)
                val arriveTime = timeFmt.format(java.util.Date(routeStartTime + arriveEta * 1000))
                val isFirst = i == 0
                val isLast = i == steps.length() - 1

                // Step highlight colors
                val dotColor = when {
                    currentNavStepIndex < 0 -> Lum.MID
                    i < currentNavStepIndex -> Lum.DIM
                    i == currentNavStepIndex -> Lum.GLOW
                    else -> Lum.MID
                }
                val textColor = when {
                    currentNavStepIndex < 0 -> Lum.BRIGHT
                    i < currentNavStepIndex -> Lum.DIM
                    i == currentNavStepIndex -> Lum.GLOW
                    else -> Lum.MID
                }
                val subColor = when {
                    currentNavStepIndex < 0 -> Lum.DIM
                    i == currentNavStepIndex -> Lum.MID
                    else -> Lum.GHOST
                }

                if (type == "WALK") {
                    // --- WALK row: dot + walk info ---
                    addTimelineRow(stepsListContainer, density, gutterW,
                        dotChar, Lum.GHOST, !isFirst, !isLast,
                        mainText = "\u226B ${step.optString("durationFormatted", "")} ${step.optString("distanceFormatted", "")}",
                        mainColor = subColor, mainSize = 12f,
                        timeText = boardTime, timeColor = Lum.GHOST)
                } else {
                    val boardStop = step.optString("boardStop", "").trim()
                    val alightStop = step.optString("alightStop", "").trim()
                    val direction = step.optString("direction", "").trim()
                    val lineName = step.optString("lineName", "").trim()
                    val lineShortNames = parseLineShortNames(step)
                    val icon = stepStripIcon(type)
                    val duration = step.optString("durationFormatted", "")
                    val stopCount = step.optInt("stopCount", 0)

                    // --- BOARD row: dot + station + badge ---
                    val badgeText = if (lineName.isNotEmpty()) "$icon($lineName)" else icon
                    val dirText = direction.ifEmpty { alightStop }
                    val subLine = if (dirText.isNotEmpty()) "$badgeText \u2192 $dirText" else badgeText
                    addTimelineRow(stepsListContainer, density, gutterW,
                        dotChar, dotColor, !isFirst, true,
                        mainText = boardStop.ifEmpty { stepTypeLabel(step, type) },
                        mainColor = textColor, mainSize = 13f,
                        subText = subLine, subColor = subColor, subSize = 11f,
                        timeText = boardTime, timeColor = Lum.DIM)

                    // --- CHIPS row: equivalent line numbers (34, 34G, 34AS...) ---
                    if (lineShortNames.size > 1) {
                        addLineChipsRow(stepsListContainer, density, gutterW, lineShortNames, textColor)
                    }

                    // --- TRAVEL row: line + duration + stops ---
                    val intermediateStops = (stopCount - 2).coerceAtLeast(0)
                    val travelText = if (intermediateStops > 0) "$duration, $intermediateStops stops" else duration
                    addTimelineRow(stepsListContainer, density, gutterW,
                        lineChar, Lum.GHOST, false, false,
                        mainText = travelText,
                        mainColor = Lum.GHOST, mainSize = 10f,
                        isDot = false)

                    // --- ALIGHT row: dot + exit station ---
                    if (alightStop.isNotEmpty()) {
                        addTimelineRow(stepsListContainer, density, gutterW,
                            dotChar, dotColor, true, !isLast,
                            mainText = alightStop,
                            mainColor = textColor, mainSize = 12f,
                            timeText = arriveTime, timeColor = Lum.DIM)
                    }
                }
            }
        } catch (e: Exception) {
            uiLog("Failed to parse steps JSON: ${e.message}")
        }
    }

    private fun addTimelineRow(
        container: LinearLayout, density: Float, gutterW: Int,
        gutterSymbol: String, gutterColor: Int,
        showTopLine: Boolean, showBottomLine: Boolean,
        mainText: String, mainColor: Int, mainSize: Float,
        subText: String? = null, subColor: Int = Lum.GHOST, subSize: Float = 10f,
        timeText: String? = null, timeColor: Int = Lum.DIM,
        isDot: Boolean = true
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Lum.VOID)
            gravity = android.view.Gravity.TOP
            setPadding(0, (1 * density).toInt(), 0, (1 * density).toInt())
        }

        // Left gutter: single character (● or │) -- pure text, no View connectors
        row.addView(TextView(this).apply {
            text = gutterSymbol
            textSize = if (isDot) 12f else 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(gutterColor)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(gutterW, LinearLayout.LayoutParams.WRAP_CONTENT)
            setBackgroundColor(Lum.VOID)
        })

        // Content column
        val contentCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding((3 * density).toInt(), 0, 0, 0)
        }
        contentCol.addView(TextView(this).apply {
            text = mainText
            textSize = mainSize
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(mainColor)
            setBackgroundColor(Lum.VOID)
        })
        if (subText != null) {
            contentCol.addView(TextView(this).apply {
                text = subText
                textSize = subSize
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(subColor)
                setBackgroundColor(Lum.VOID)
            })
        }
        row.addView(contentCol)

        // Time column
        if (timeText != null && isDot) {
            row.addView(TextView(this).apply {
                text = timeText
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(timeColor)
                gravity = android.view.Gravity.END
                setBackgroundColor(Lum.VOID)
                setPadding(0, 0, (2 * density).toInt(), 0)
            })
        }

        container.addView(row)
    }

    /** Parse the equivalent line-number array from a nav step. Falls back to the
     *  single lineShortName when no array is present (e.g. Yandex provider). */
    private fun parseLineShortNames(step: JSONObject): List<String> {
        val arr = step.optJSONArray("lineShortNames")
        if (arr != null) {
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "").trim()
                if (v.isNotEmpty()) out.add(v)
            }
            if (out.isNotEmpty()) return out
        }
        val single = step.optString("lineShortName", "").trim()
        return if (single.isNotEmpty()) listOf(single) else emptyList()
    }

    /** Row of equivalent line-number chips for the expanded step list. Waveguide
     *  rules: black background, green border + text, monospace, no alpha. Indented
     *  under the timeline gutter to line up with the BOARD row content. */
    private fun addLineChipsRow(
        container: LinearLayout, density: Float, gutterW: Int,
        names: List<String>, chipColor: Int
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Lum.VOID)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(gutterW + (3 * density).toInt(), (1 * density).toInt(), 0, (2 * density).toInt())
        }
        for ((idx, name) in names.withIndex()) {
            val chip = TextView(this).apply {
                text = name
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(chipColor)
                gravity = android.view.Gravity.CENTER
                setBackgroundColor(Lum.VOID)
                val padH = (5 * density).toInt()
                val padV = (1 * density).toInt()
                setPadding(padH, padV, padH, padV)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 6 * density
                    setStroke((1 * density).toInt().coerceAtLeast(1), chipColor)
                    setColor(Lum.VOID)
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (idx > 0) lp.marginStart = (5 * density).toInt()
            row.addView(chip, lp)
        }
        container.addView(row)
    }

    private fun stepTypeSymbol(type: String): String = when (type) {
        "WALK" -> ">>"
        "BUS" -> "BUS"
        "METRO" -> "M"
        "TRAM" -> "TR"
        "TROLLEYBUS" -> "TB"
        "TRAIN" -> "RR"
        "SUBURBAN" -> "SR"
        "HIGH_SPEED_TRAIN" -> "HS"
        "FERRY" -> "FY"
        "CABLE_CAR" -> "CC"
        "FUNICULAR" -> "FN"
        "GONDOLA" -> "GN"
        "SHARE_TAXI" -> "TX"
        "OTHER" -> "--"
        else -> "--"
    }

    private fun stepTypeLabel(step: JSONObject, type: String): String {
        val lineName = step.optString("lineName", "")
        return when (type) {
            "WALK" -> "Walk"
            "BUS" -> "Bus $lineName"
            "METRO" -> "Metro $lineName"
            "TRAM" -> "Tram $lineName"
            "TROLLEYBUS" -> "Trolleybus $lineName"
            "TRAIN" -> "Train $lineName"
            "SUBURBAN" -> "Suburban $lineName"
            "HIGH_SPEED_TRAIN" -> "High-speed train $lineName"
            "FERRY" -> "Ferry $lineName"
            "CABLE_CAR" -> "Cable car $lineName"
            "FUNICULAR" -> "Funicular $lineName"
            "GONDOLA" -> "Gondola $lineName"
            "SHARE_TAXI" -> "Share taxi $lineName"
            "OTHER" -> "Transit $lineName"
            else -> step.optString("text", type)
        }.trim()
    }

    private fun stepStripIcon(type: String): String = when (type) {
        "WALK" -> "\u226B"          // ≫ double right angle
        "BUS" -> "\u24B7"           // Ⓑ circled B
        "METRO" -> "\u24C2"         // Ⓜ circled M
        "TRAM" -> "\u24C9"          // Ⓣ circled T
        "TROLLEYBUS" -> "\u24C9"    // Ⓣ circled T
        "TRAIN" -> "\u24C7"         // circled R
        "SUBURBAN" -> "\u24C8"      // circled S
        "HIGH_SPEED_TRAIN" -> "\u24BD" // circled H
        "FERRY" -> "\u24BB"         // circled F
        "CABLE_CAR" -> "\u24B8"     // circled C
        "FUNICULAR" -> "\u24BB"     // circled F
        "GONDOLA" -> "\u24BC"       // circled G
        "SHARE_TAXI" -> "\u24C9"    // circled T
        "OTHER" -> "\u2022"         // bullet
        else -> "\u2022"            // bullet
    }

    private fun parseLineColor(hex: String): Int? {
        if (hex.isEmpty() || !hex.startsWith("#")) return null
        return runCatching { android.graphics.Color.parseColor(hex) }.getOrNull()
    }

    private fun renderStepRow(row: TextView, step: JSONObject?) {
        if (step == null) {
            row.text = ""
            return
        }
        val type = step.optString("type", "")
        val isUnderground = step.optBoolean("isUnderground", false)
        val builder = android.text.SpannableStringBuilder()
        val S = android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        // Turn-by-turn driving/walking instructions carry their guidance in "text"
        // (e.g. "Continue straight", "Take exit right") and have no transit line
        // fields. The transit branches below build from lineName/board/alight and
        // would leave such a row as a bare bullet, so render the text directly.
        val instructionText = step.optString("text", "").trim()
        if (instructionText.isNotEmpty() && !step.has("lineName") && !step.has("lineShortName")) {
            builder.append("\u226B ").append(instructionText)
            row.text = builder
            return
        }
        if (type == "WALK") {
            val dist = step.optString("distanceFormatted", "").trim()
            val dur = step.optString("durationFormatted", "").trim()
            val tail = mutableListOf<String>()
            if (dur.isNotEmpty()) tail += dur
            if (isUnderground) tail += "underground"
            val parens = if (tail.isNotEmpty()) " (${tail.joinToString(", ")})" else ""
            val distStr = if (dist.isNotEmpty()) " $dist" else ""
            builder.append("\u226B Walk$distStr$parens")
        } else {
            val icon = stepStripIcon(type)
            val short = step.optString("lineShortName", "").trim().ifEmpty { step.optString("lineName", "").trim() }
            val board = step.optString("boardStop", "").trim()
            val alight = step.optString("alightStop", "").trim()
            val dur = step.optString("durationFormatted", "").trim()
            val stopCount = step.optInt("stopCount", 0)
            val lineColor = parseLineColor(step.optString("lineColor", ""))

            val badgeStart = builder.length
            builder.append(icon)
            if (short.isNotEmpty()) builder.append(" ").append(short)
            if (lineColor != null) {
                builder.setSpan(android.text.style.ForegroundColorSpan(lineColor), badgeStart, builder.length, S)
                builder.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), badgeStart, builder.length, S)
            }

            val stops = when {
                board.isNotEmpty() && alight.isNotEmpty() -> "$board - $alight"
                board.isNotEmpty() -> board
                alight.isNotEmpty() -> alight
                else -> ""
            }
            if (stops.isNotEmpty()) builder.append(" ").append(stops)
            val tail = mutableListOf<String>()
            if (dur.isNotEmpty()) tail += dur
            if (stopCount > 0) tail += "$stopCount stops"
            if (tail.isNotEmpty()) builder.append(" (${tail.joinToString(", ")})")
        }
        row.text = builder
    }

    private fun applyStepRowStyle(row: TextView, isCurrent: Boolean) {
        if (isCurrent) {
            row.textSize = 13.5f
            row.setTextColor(Lum.GLOW)
        } else {
            row.textSize = 11f
            row.setTextColor(Lum.DIM)
        }
    }

    private fun updateStepStrip(index: Int) {
        val steps = parsedSteps
        if (steps == null || steps.length() == 0 || index < 0 || index >= steps.length()) {
            mapStepStripClip.visibility = View.GONE
            renderedStepIndex = -1
            stepStripAnimator?.cancel()
            stepStripAnimator = null
            return
        }
        val canAnimateForward = renderedStepIndex >= 0 &&
            index - renderedStepIndex == 1 &&
            mapStepRowCurrent.height > 0 &&
            mapStepStripClip.visibility == View.VISIBLE
        if (canAnimateForward) {
            animateStepStripForward(index)
        } else {
            renderStepStripSnap(index)
        }
    }

    private fun renderStepStripSnap(index: Int) {
        stepStripAnimator?.cancel()
        stepStripAnimator = null
        val steps = parsedSteps ?: return
        val prev = if (index > 0) steps.optJSONObject(index - 1) else null
        val curr = steps.optJSONObject(index)
        val next = if (index + 1 < steps.length()) steps.optJSONObject(index + 1) else null

        renderStepRow(mapStepRowPrev, prev)
        renderStepRow(mapStepRowCurrent, curr)
        renderStepRow(mapStepRowNext, next)
        mapStepRowBuffer.text = ""
        applyStepRowStyle(mapStepRowPrev, false)
        applyStepRowStyle(mapStepRowCurrent, true)
        applyStepRowStyle(mapStepRowNext, false)
        applyStepRowStyle(mapStepRowBuffer, false)
        mapStepRowPrev.visibility = if (prev != null) View.VISIBLE else View.INVISIBLE
        mapStepRowNext.visibility = if (next != null) View.VISIBLE else View.INVISIBLE
        mapStepStrip.translationY = 0f
        mapStepStripClip.visibility = View.VISIBLE
        renderedStepIndex = index
        resizeStepStripClip()
    }

    private fun resizeStepStripClip() {
        // Clip = sum of the three visible rows. Buffer (4th child) sits just below the boundary.
        mapStepStrip.post {
            val prevH = mapStepRowPrev.height
            val currH = mapStepRowCurrent.height
            val nextH = mapStepRowNext.height
            val visible = prevH + currH + nextH
            if (visible <= 0) return@post
            val lp = mapStepStripClip.layoutParams
            if (lp.height != visible) {
                lp.height = visible
                mapStepStripClip.layoutParams = lp
            }
        }
    }

    private fun animateStepStripForward(newIndex: Int) {
        val steps = parsedSteps ?: return
        val newNext = if (newIndex + 1 < steps.length()) steps.optJSONObject(newIndex + 1) else null
        renderStepRow(mapStepRowBuffer, newNext)
        applyStepRowStyle(mapStepRowBuffer, false)
        mapStepRowBuffer.visibility = if (newNext != null) View.VISIBLE else View.INVISIBLE

        val rowHeightPx = mapStepRowCurrent.height.coerceAtLeast(1)
        val argbEval = android.animation.ArgbEvaluator()

        stepStripAnimator?.cancel()
        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 280L
        animator.interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        animator.addUpdateListener { va ->
            val t = va.animatedValue as Float
            mapStepStrip.translationY = -rowHeightPx * t
            mapStepRowCurrent.textSize = 13.5f + (11f - 13.5f) * t
            mapStepRowCurrent.setTextColor(argbEval.evaluate(t, Lum.GLOW, Lum.DIM) as Int)
            mapStepRowNext.textSize = 11f + (13.5f - 11f) * t
            mapStepRowNext.setTextColor(argbEval.evaluate(t, Lum.DIM, Lum.GLOW) as Int)
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                renderStepStripSnap(newIndex)
            }
            override fun onAnimationCancel(animation: android.animation.Animator) {
                renderStepStripSnap(newIndex)
            }
        })
        stepStripAnimator = animator
        animator.start()
    }

    // --- Translate tab (always visible, no dynamic add/remove) ---

    /**
     * Handle a translation result from the phone.
     * Simple 1:1 mapping: one segment = one view on screen.
     * Each segment is a sentence from the transcriber.
     */
    private fun handleTranslationResult(id: Int, text: String, translation: String, partial: Boolean) {
        var segment = translationSegments[id]
        if (segment == null) {
            segment = TranslationSegment(id, addedAt = System.currentTimeMillis())
            translationSegments[id] = segment
        }

        if (text.isNotEmpty()) segment.sourceText = text
        if (translation.isNotEmpty()) segment.translatedText = translation

        val wasPartial = segment.isPartial
        if (!partial) segment.isPartial = false

        // Find existing view for this segment
        val existingView = translationChunksContainer.findViewWithTag<LinearLayout>(id)

        if (existingView != null) {
            // Update in place
            updateSegmentView(existingView, segment)
        } else {
            // New segment: add view
            val view = createSegmentView(segment)
            translationChunksContainer.addView(view)
            Anim.slideUp(view, distanceDp = 12f, duration = 300L)
        }

        // Evict old segments (keep max visible)
        while (translationChunksContainer.childCount > MAX_VISIBLE_CHUNKS) {
            val oldView = translationChunksContainer.getChildAt(0)
            val oldId = oldView.tag as? Int
            translationChunksContainer.removeViewAt(0)
            if (oldId != null) {
                translationSegments.remove(oldId)
                mainHandler.removeCallbacksAndMessages(oldId)
            }
        }

        // Schedule fade-out for finalized segments
        if (!partial && wasPartial) {
            segment.addedAt = System.currentTimeMillis()
            scheduleFadeOut(id)
        }

        translationScrollView.post {
            translationScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun scheduleFadeOut(segId: Int) {
        // At 10s: start gradual fade to 50% over 5s
        mainHandler.postDelayed({
            val view = translationChunksContainer.findViewWithTag<LinearLayout>(segId) ?: return@postDelayed
            view.animate().alpha(0.5f).setDuration(5000L).start()
        }, 10_000L)
        // At 15s: quick fade out and remove
        mainHandler.postDelayed({
            val view = translationChunksContainer.findViewWithTag<LinearLayout>(segId) ?: return@postDelayed
            translationSegments.remove(segId)
            view.animate().alpha(0f).setDuration(500L).withEndAction {
                translationChunksContainer.removeView(view)
            }.start()
        }, FADE_OUT_MS)
    }

    private fun createSegmentView(seg: TranslationSegment): LinearLayout {
        val dp4 = (4 * Resources.getSystem().displayMetrics.density + 0.5f).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp4 }
            setBackgroundColor(0xFF000000.toInt())
            isFocusable = false
            isFocusableInTouchMode = false
            defaultFocusHighlightEnabled = false
            tag = seg.id
        }

        val sourceView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(Lum.DIM)
            setBackgroundColor(0xFF000000.toInt())
            isFocusable = false
            defaultFocusHighlightEnabled = false
            text = seg.sourceText.trim()
            visibility = if (seg.sourceText.isBlank()) View.GONE else View.VISIBLE
        }

        val transView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, translationFontSize.toFloat())
            setTextColor(if (seg.isPartial) Lum.MID else Lum.GLOW)
            setBackgroundColor(0xFF000000.toInt())
            isFocusable = false
            defaultFocusHighlightEnabled = false
            text = seg.translatedText.trim()
            visibility = if (seg.translatedText.isBlank()) View.GONE else View.VISIBLE
        }

        container.addView(sourceView)
        container.addView(transView)
        return container
    }

    private fun updateSegmentView(container: LinearLayout, seg: TranslationSegment) {
        val sourceView = container.getChildAt(0) as TextView
        val transView = container.getChildAt(1) as TextView
        sourceView.text = seg.sourceText.trim()
        sourceView.visibility = if (seg.sourceText.isBlank()) View.GONE else View.VISIBLE
        transView.text = seg.translatedText.trim()
        transView.setTextColor(if (seg.isPartial) Lum.MID else Lum.GLOW)
        transView.setTextSize(TypedValue.COMPLEX_UNIT_SP, translationFontSize.toFloat())
        transView.visibility = if (seg.translatedText.isBlank()) View.GONE else View.VISIBLE
    }

    // --- Permissions ---

    private fun requestAllPermissions() {
        val needed = mutableListOf<String>()
        val perms = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
        for (p in perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p)
            }
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
        }
    }

    // --- Camera preview ---

    private fun showCameraPreview() {
        stopNightVision()
        Anim.fadeOut(chatContainer, 150L) {
            cameraPreview.visibility = View.VISIBLE
            if (arCameraPreview == null) {
                arCameraPreview = ArCameraPreview(this@MainActivity)
            }
            arCameraPreview?.start(cameraPreview) {
                sendBroadcast(Intent(ListenerService.ACTION_CAMERA_PREVIEW_READY))
            }
        }
    }

    private fun hideCameraPreview() {
        arCameraPreview?.stop()
        cameraPreview.visibility = View.GONE
        Anim.fadeIn(chatContainer, 200L)
    }

    // --- Lifecycle ---

    override fun onCreate(savedInstanceState: Bundle?) = GT.section("ui.onCreate") {
        super.onCreate(null)
        LogCollector.setRelayMode(this)
        requestAllPermissions()
        try {
            window.setBackgroundDrawableResource(android.R.color.black)
            window.decorView.setBackgroundColor(Color.BLACK)

            setContentView(R.layout.activity_main)

            debugStatus = findViewById(R.id.debugStatus)
            chatRecycler = findViewById(R.id.chatRecycler)
            chatListRecycler = findViewById(R.id.chatListRecycler)
            statusArea = findViewById(R.id.statusArea)
            statusBar = findViewById(R.id.statusBar)
            remoteInputGlyph = findViewById(R.id.remoteInputGlyph)
            doubleTapHint = findViewById(R.id.doubleTapHint)
            statusIcon = findViewById(R.id.statusIcon)
            progressBar = findViewById(R.id.progressBar)
            cameraPreview = findViewById(R.id.cameraPreview)
            chatContainer = findViewById(R.id.chatContainer)
            mainContentLayout = findViewById<LinearLayout>(R.id.mainContentLayout).also {
                it.clipChildren = false
                it.clipToPadding = false
            }
            minimapView = findViewById(R.id.minimapView)
            mapContainer = findViewById(R.id.mapContainer)
            mapContentView = findViewById(R.id.mapContentView)
            mapBaseFadeView = findViewById(R.id.mapBaseFadeView)
            mapArrowView = findViewById(R.id.mapArrowView)
            mapArrowView.visibility = View.GONE
            mapPinButton = findViewById(R.id.mapPinButton)
            mapStopButton = findViewById(R.id.mapStopButton)
            mapStopModal = findViewById(R.id.mapStopModal)
            mapStepsButton = findViewById(R.id.mapStepsButton)
            mapButtonColumn = findViewById(R.id.mapButtonColumn)
            mapZoomSliderContainer = findViewById(R.id.mapZoomSliderContainer)
            mapZoomSliderTrack = findViewById(R.id.mapZoomSliderTrack)
            mapZoomSliderFill = findViewById(R.id.mapZoomSliderFill)
            mapZoomSliderFill.pivotX = 0f
            mapZoomSliderFill.scaleX = computeFillRatio(currentZoomStep)
            mapStepsModal = findViewById(R.id.mapStepsModal)
            stepsScrollView = findViewById(R.id.stepsScrollView)
            stepsListContainer = findViewById(R.id.stepsListContainer)
            mapStepStripClip = findViewById(R.id.mapStepStripClip)
            mapStepStrip = findViewById(R.id.mapStepStrip)
            mapStepRowPrev = findViewById(R.id.mapStepRowPrev)
            mapStepRowCurrent = findViewById(R.id.mapStepRowCurrent)
            mapStepRowNext = findViewById(R.id.mapStepRowNext)
            mapStepRowBuffer = findViewById(R.id.mapStepRowBuffer)
            modalStopBtn = findViewById(R.id.modalStopBtn)
            modalCancelBtn = findViewById(R.id.modalCancelBtn)
            translationContainer = findViewById(R.id.translationContainer)
            translationStatus = findViewById(R.id.translationStatus)
            translationChunksContainer = findViewById(R.id.translationChunksContainer)
            translationScrollView = findViewById(R.id.translationScrollView)
            teleprompterContainer = findViewById(R.id.teleprompterContainer)
            mouseContainer = findViewById(R.id.mouseContainer)
            mouseConnectionStatus = findViewById(R.id.mouseConnectionStatus)
            mouseControlsHint = findViewById(R.id.mouseControlsHint)
            tpScrollIndicator = findViewById(R.id.tpScrollIndicator)
            tpStopButton = findViewById(R.id.tpStopButton)
            contentFrame = findViewById<FrameLayout>(R.id.contentFrame)
            loaderCtl = TabLoaderController(this, contentFrame) { currentTabId }
            disconnectedOverlay = findViewById(R.id.disconnectedOverlay)
            tabChat = findViewById(R.id.tabChat)
            tabChatList = findViewById(R.id.tabChatList)
            pillHighlight = findViewById(R.id.pillHighlight)
            pillContainer = findViewById(R.id.pillContainer)
            scrollIndicator = findViewById(R.id.scrollIndicator)
            reidContainer = findViewById(R.id.reidContainer)
            reidStartStopIcon = findViewById(R.id.reidStartStopIcon)
            reidStartStopContainer = findViewById(R.id.reidStartStopContainer)
            translationStartStopIcon = findViewById(R.id.translationStartStopIcon)
            translationStartStopContainer = findViewById(R.id.translationStartStopContainer)
            reidFaceBar = findViewById(R.id.reidFaceBar)
            reidFaceIdLabel = findViewById(R.id.reidFaceIdLabel)
            reidIntelModal = findViewById(R.id.reidIntelModal)
            reidIntelContent = findViewById(R.id.reidIntelContent)
            tabReid = findViewById(R.id.tabReid)
            todoContainer = findViewById(R.id.todoContainer)
            tabTodo = findViewById(R.id.tabTodo)

            // Music tab
            musicContainer = findViewById(R.id.musicContainer)
            musicPlayerContent = findViewById(R.id.musicPlayerContent)
            musicEmptyHint = findViewById(R.id.musicEmptyHint)
            tabMusic = findViewById(R.id.tabMusic)
            musicTrackName = findViewById(R.id.musicTrackName)
            musicPlayPauseIcon = findViewById(R.id.musicPlayPauseIcon)
            musicPlayPauseContainer = findViewById(R.id.musicPlayPauseContainer)
            musicPrevContainer = findViewById(R.id.musicPrevContainer)
            musicNextContainer = findViewById(R.id.musicNextContainer)
            musicPlayPauseIcon.setColorFilter(Lum.SOFT, android.graphics.PorterDuff.Mode.SRC_IN)
            findViewById<ImageView>(R.id.musicPrevIcon).setColorFilter(Lum.SOFT, android.graphics.PorterDuff.Mode.SRC_IN)
            findViewById<ImageView>(R.id.musicNextIcon).setColorFilter(Lum.SOFT, android.graphics.PorterDuff.Mode.SRC_IN)
            musicTrackName.setTextColor(Lum.MID)
            musicProgressBg = findViewById(R.id.musicProgressBg)
            musicProgressFill = findViewById(R.id.musicProgressFill)
            // 192dp in pixels -- container is GONE during onCreate so can't measure
            musicProgressMaxWidth = (192 * resources.displayMetrics.density + 0.5f).toInt()
            // Music tab is hidden until an A2DP source exposes a MediaSession.
            // The ImageView sits inside a FrameLayout slot with layout_weight=1;
            // hide the slot (the parent) so LinearLayout's weight math redistributes
            // and the pill doesn't show an empty gap.
            // updatePillSize() call is deferred until after tabBar/timeText/
            // weatherRow are findViewById'd further down; otherwise we NPE on
            // their lateinit accessors and abort onCreate mid-setup.
            (tabMusic.parent as? View)?.visibility = View.GONE
            musicContainer.visibility = View.GONE
            // Default to empty-hint state. Player UI is revealed only when
            // ListenerService broadcasts a non-empty AVRCP track name.
            musicPlayerContent.visibility = View.GONE
            musicEmptyHint.visibility = View.VISIBLE

            // Build todo UI programmatically inside todoContainer
            buildTodoUI()

            // Telegram tab
            tabTelegram = findViewById(R.id.tabTelegram)
            telegramAuthContainer = findViewById(R.id.telegramAuthContainer)
            telegramAuthPrompt = findViewById(R.id.telegramAuthPrompt)
            telegramAuthStatus = findViewById(R.id.telegramAuthStatus)
            telegramChatListRecycler = findViewById(R.id.telegramChatListRecycler)
            telegramChatContainer = findViewById(R.id.telegramChatContainer)
            telegramChatRecycler = findViewById(R.id.telegramChatRecycler)
            telegramVoiceOverlay = findViewById(R.id.telegramVoiceOverlay)
            telegramVoicePreview = findViewById(R.id.telegramVoicePreview)
            telegramSendPreview = findViewById(R.id.telegramSendPreview)
            telegramSendText = findViewById(R.id.telegramSendText)
            telegramSendCountdown = findViewById(R.id.telegramSendCountdown)

            telegramChatListAdapter = TelegramChatListAdapter()
            telegramChatListRecycler.layoutManager = LinearLayoutManager(this)
            telegramChatListRecycler.adapter = telegramChatListAdapter
            telegramChatListRecycler.itemAnimator = null

            telegramChatAdapter = TelegramChatAdapter()
            telegramChatRecycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
            telegramChatRecycler.adapter = telegramChatAdapter
            telegramChatRecycler.itemAnimator = null
            // Bottom padding so messages don't render behind the record hint
            telegramChatRecycler.setPadding(0, 0, 0, 36.dpToPx())
            telegramChatRecycler.clipToPadding = false

            telegramChatRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (dy >= 0) return
                    val lm = rv.layoutManager as? LinearLayoutManager ?: return
                    if (lm.findFirstVisibleItemPosition() <= 3
                        && !telegramLoadingOlderMessages
                        && !telegramNoMoreOlderMessages
                        && telegramMessagesLoaded) {
                        loadOlderTelegramMessages()
                    }
                }
            })

            // Topics recycler (reuses same container area as chat list)
            telegramTopicsRecycler = RecyclerView(this).apply {
                setBackgroundColor(Lum.VOID)
                visibility = View.GONE
                overScrollMode = View.OVER_SCROLL_NEVER
                isFocusable = false
                isFocusableInTouchMode = false
            }
            telegramTopicListAdapter = com.repository.glasses.listener.ui.TelegramTopicListAdapter()
            telegramTopicsRecycler.layoutManager = LinearLayoutManager(this)
            telegramTopicsRecycler.adapter = telegramTopicListAdapter
            telegramTopicsRecycler.itemAnimator = null
            // Add topics recycler to the same parent as chat list
            (telegramChatListRecycler.parent as? android.view.ViewGroup)?.addView(telegramTopicsRecycler,
                telegramChatListRecycler.layoutParams)

            // Chat header bar (name + presence + avatar) at top of chat container
            telegramChatHeaderAvatar = android.widget.ImageView(this).apply {
                setBackgroundColor(Lum.VOID)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                visibility = View.GONE
            }
            telegramChatHeader = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundColor(Lum.VOID)
                setPadding(8.dpToPx(), 4.dpToPx(), 8.dpToPx(), 4.dpToPx())
                visibility = View.GONE
            }
            telegramChatHeaderName = TextView(this).apply {
                typeface = android.graphics.Typeface.MONOSPACE
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(Lum.BRIGHT)
                setBackgroundColor(Lum.VOID)
                maxLines = 1
            }
            telegramChatHeaderStatus = TextView(this).apply {
                typeface = android.graphics.Typeface.MONOSPACE
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
                setTextColor(Lum.DIM)
                setBackgroundColor(Lum.VOID)
                maxLines = 1
            }
            telegramChatHeader.addView(telegramChatHeaderAvatar, LinearLayout.LayoutParams(
                20.dpToPx(), 20.dpToPx()
            ).apply { marginEnd = 6.dpToPx() })
            telegramChatHeader.addView(telegramChatHeaderName, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            telegramChatHeader.addView(telegramChatHeaderStatus, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 8.dpToPx() })
            telegramChatContainer.addView(telegramChatHeader, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP
            ))

            // "Tap to record" hint at bottom of chat container
            telegramRecordHint = TextView(this).apply {
                typeface = android.graphics.Typeface.MONOSPACE
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Lum.GHOST)
                text = "Tap to record message"
                gravity = android.view.Gravity.CENTER
                setBackgroundColor(Lum.VOID)
                setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
            }
            telegramChatContainer.addView(telegramRecordHint, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM
            ))

            nightvisionContainer = findViewById(R.id.nightvisionContainer)
            nightvisionPreview = findViewById(R.id.nightvisionPreview)
            // tabNightvision = findViewById(R.id.tabNightvision)  // Night vision tab commented out

            // Status bar indicators
            tabBar = findViewById<FrameLayout>(R.id.tabBar).also {
                it.clipChildren = false
                it.clipToPadding = false
            }
            timeText = findViewById(R.id.timeText)
            batteryText = findViewById(R.id.batteryText)
            weatherIcon = findViewById(R.id.weatherIcon)
            weatherTemp = findViewById(R.id.weatherTemp)
            weatherRow = findViewById(R.id.weatherRow)
            loneIndicatorIcon = findViewById(R.id.loneIndicatorIcon)
            loneIndicatorCount = findViewById(R.id.loneIndicatorCount)
            recordingIndicator = findViewById(R.id.recordingIndicator)
            batteryFill = findViewById(R.id.batteryFill)
            batteryIndicator = findViewById(R.id.batteryIndicator)
            wifiIndicator = findViewById(R.id.wifiIndicator)
            // Seed initial state + subscribe to WIFI_STATE_CHANGED.
            // Use Settings.Global.WIFI_ON as the source of truth: WifiManager.isWifiEnabled()
            // can return true while the user-facing wifi toggle is OFF on this device,
            // because the P2P framework holds wlan0 partially up for filesync.
            try {
                val wifiOn = android.provider.Settings.Global.getInt(
                    contentResolver,
                    android.provider.Settings.Global.WIFI_ON,
                    0,
                ) == 1
                wifiIndicator.visibility = if (wifiOn) View.VISIBLE else View.GONE
                registerReceiver(
                    wifiStateReceiver,
                    IntentFilter(android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION),
                )
            } catch (_: Throwable) { /* non-fatal */ }
            callIndicator = findViewById(R.id.callIndicator)
            callIndicatorLabel = findViewById(R.id.callIndicatorLabel)
            callDurationText = findViewById(R.id.callDurationText)
            micMuteIndicator = findViewById(R.id.micMuteIndicator)

            // Create translate tab icon + frame programmatically (permanent default tab).
            run {
                val density = android.content.res.Resources.getSystem().displayMetrics.density
                val icon = ImageView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        (TAB_ICON_DP * density + 0.5f).toInt(),
                        (TAB_ICON_DP * density + 0.5f).toInt()
                    ).apply { gravity = android.view.Gravity.CENTER }
                    setImageResource(R.drawable.ic_translate)
                    setColorFilter(Lum.SOFT, PorterDuff.Mode.SRC_IN)
                    isFocusable = false
                    isFocusableInTouchMode = false
                    defaultFocusHighlightEnabled = false
                }
                translateTabIcon = icon
                val frame = FrameLayout(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
                    )
                    addView(icon)
                }
                translateTabFrame = frame
                // Insert into the tab icon row at the correct position (idx 3 in activeTabs)
                val tabIconsRow = (pillContainer as ViewGroup).getChildAt(1) as ViewGroup
                // Find predecessor (CHAT_LIST at idx 2) frame to place after it
                val predFrame = frameForTab(TabId.CHAT_LIST)
                val viewIdx = if (predFrame != null) tabIconsRow.indexOfChild(predFrame) + 1 else tabIconsRow.childCount
                tabIconsRow.addView(frame, viewIdx)
            }

            // Initialize translate tab idle state from saved config
            run {
                val fromLang = GlassesConfig.getTranslationFromLanguage(this)
                val toLang = GlassesConfig.getTranslationToLanguage(this)
                if (fromLang.isNotEmpty() && toLang.isNotEmpty()) {
                    translationLangsLabel = "${fromLang.uppercase()} -> ${toLang.uppercase()}"
                    translationStatus.text = translationLangsLabel
                } else {
                    translationStatus.text = ""
                }
                translationStatus.setTextColor(Lum.DIM)
            }

            // Pill size depends on pillContainer + tabBar + timeText + weatherRow,
            // all now initialized. Run here so the initial layout reflects the
            // real (MUSIC-hidden) tab count and uses the always-expanded layout.
            updatePillSize()

            // Charging lightning bolt icon (overlaid on battery via tabBar)
            chargingIcon = ImageView(this).apply {
                setImageResource(R.drawable.ic_charging)
                layoutParams = FrameLayout.LayoutParams(16.dpToPx(), 16.dpToPx())
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = View.GONE
            }
            tabBar.addView(chargingIcon)
            repositionChargingIcon()

            // Initialize pill + status-row layout for the default tab set so the
            // expanded branch fires on first render (not just after tab add/remove).
            updatePillSize()

            updateTimeUI()

            // Initial icon tints: all inactive except currentTab (CHAT)
            for ((i, id) in activeTabs.withIndex()) {
                val icon = when (id) {
                    TabId.MUSIC -> tabMusic
                    TabId.CHAT -> tabChat
                    TabId.CHAT_LIST -> tabChatList
                    TabId.TELEGRAM -> tabTelegram
                    TabId.REID -> tabReid
                    TabId.TODO -> tabTodo
                    TabId.NIGHTVISION -> null
                    TabId.TRANSLATE -> translateTabIcon
                    TabId.MAP -> mapTabIcon
                    TabId.TELEPROMPTER -> teleprompterTabIcon
                    TabId.MOUSE -> mouseTabIcon
                } ?: continue
                val color = if (i == currentTab) Lum.GLOW else Lum.SOFT
                icon.setColorFilter(color, PorterDuff.Mode.SRC_IN)
            }
            statusIcon.setColorFilter(Lum.DIM, PorterDuff.Mode.SRC_IN)

            chatAdapter = ChatAdapter()
            layoutManager = LinearLayoutManager(this).apply {
                stackFromEnd = true
            }
            chatRecycler.layoutManager = layoutManager
            chatRecycler.adapter = chatAdapter
            chatRecycler.itemAnimator = null
            chatEmptyHint = findViewById(R.id.chatEmptyHint)
            chatAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                    updateChatEmptyHint(); updateChatKeepScreenOnFlag()
                }
                override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                    updateChatEmptyHint(); updateChatKeepScreenOnFlag()
                }
                override fun onChanged() {
                    updateChatEmptyHint(); updateChatKeepScreenOnFlag()
                }
            })

            // Scroll indicator + center-item selection tracking
            chatRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    updateScrollIndicator(recyclerView)
                    if (focusState == FocusState.CHAT_FOCUSED) {
                        recyclerView.post { updateCenterSelection() }
                    }
                }
            })

            chatListAdapter = ChatListAdapter()
            chatListLayoutManager = LinearLayoutManager(this)
            chatListRecycler.layoutManager = chatListLayoutManager
            chatListRecycler.adapter = chatListAdapter
            chatListRecycler.itemAnimator = MessageItemAnimator()
            chatListRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    updateScrollIndicator(recyclerView)
                }
            })

            // Set initial focus visual on pill tab bar and switch to first tab
            updateFocusVisual(FocusState.TAB_NAV)
            switchToTab(currentTab, animate = false)

            registerReceiver(stateReceiver, IntentFilter(ListenerService.ACTION_STATE_UPDATE))
            registerReceiver(chatReceiver, IntentFilter(ListenerService.ACTION_CHAT_MESSAGE))
            registerReceiver(streamingReceiver, IntentFilter(ListenerService.ACTION_STREAMING_TEXT))
            registerReceiver(partialTextReceiver, IntentFilter(ListenerService.ACTION_PARTIAL_TEXT))
            registerReceiver(userTextReceiver, IntentFilter(ListenerService.ACTION_USER_TEXT))
            registerReceiver(responseMetaReceiver, IntentFilter(ListenerService.ACTION_RESPONSE_META))
            registerReceiver(toolStatusReceiver, IntentFilter(ListenerService.ACTION_TOOL_STATUS))
            registerReceiver(sessionResetReceiver, IntentFilter(ListenerService.ACTION_SESSION_RESET))
            registerReceiver(cameraPreviewReceiver, IntentFilter(ListenerService.ACTION_CAMERA_PREVIEW))
            registerReceiver(uiRecordReceiver, IntentFilter(ListenerService.ACTION_UI_RECORD))
            // Seed fold state from the property, then track the Rokid leg
            // fold/unfold broadcast. Touchpad keycodes are swallowed only while folded.
            foldedState = readFoldedProperty() ?: false
            registerReceiver(foldStateReceiver, IntentFilter(FOLD_LEG_ACTION))
            registerReceiver(teleprompterReceiver, IntentFilter(ListenerService.ACTION_TELEPROMPTER))
            if (mapWorkerThread == null) {
                val t = android.os.HandlerThread("MapBitmapWorker", android.os.Process.THREAD_PRIORITY_BACKGROUND)
                t.start()
                mapWorkerThread = t
                mapWorkerHandler = android.os.Handler(t.looper)
            }
            registerReceiver(mapBitmapReceiver, IntentFilter(ListenerService.ACTION_MAP_BITMAP))
            registerReceiver(mapArrowReceiver, IntentFilter(ListenerService.ACTION_MAP_ARROW))
            registerReceiver(toolThumbnailReceiver, IntentFilter(ListenerService.ACTION_TOOL_THUMBNAIL))
            registerReceiver(photoProgressReceiver, IntentFilter(ListenerService.ACTION_PHOTO_PROGRESS))
            registerReceiver(mapMinimapReceiver, IntentFilter(ListenerService.ACTION_MAP_MINIMAP))
            registerReceiver(navStepsReceiver, IntentFilter(ListenerService.ACTION_NAV_STEPS))
            registerReceiver(navStepIndexReceiver, IntentFilter(ListenerService.ACTION_NAV_STEP_INDEX))
            registerReceiver(chatListReceiver, IntentFilter(ListenerService.ACTION_CHAT_LIST))
            registerReceiver(chatHistoryReceiver, IntentFilter(ListenerService.ACTION_CHAT_HISTORY_LOADED))
            registerReceiver(debugStatusReceiver, IntentFilter(ListenerService.ACTION_DEBUG_STATUS))
            registerReceiver(btStateReceiver, IntentFilter(ListenerService.ACTION_BT_STATE))
            registerReceiver(orchestratorStateReceiver, IntentFilter(ListenerService.ACTION_ORCHESTRATOR_STATE))
            registerReceiver(weatherUpdateReceiver, IntentFilter(ListenerService.ACTION_WEATHER_UPDATE))
            registerReceiver(loneIndicatorReceiver, IntentFilter(ListenerService.ACTION_LONE_INDICATOR))
            registerReceiver(callUiStateReceiver, IntentFilter(ListenerService.ACTION_CALL_UI_STATE))
            registerReceiver(recordingStateReceiver, IntentFilter(ListenerService.ACTION_RECORDING_STATE))
            registerReceiver(cameraPermRequestReceiver, IntentFilter(ListenerService.ACTION_REQUEST_CAMERA_PERMISSION))
            registerReceiver(translationResultReceiver, IntentFilter(ListenerService.ACTION_TRANSLATION_RESULT))
            registerReceiver(translationConfigReceiver, IntentFilter(ListenerService.ACTION_TRANSLATION_CONFIG))
            registerReceiver(translationStateReceiver, IntentFilter(ListenerService.ACTION_TRANSLATION_STATE))
            registerReceiver(mouseStateReceiver, IntentFilter(ListenerService.ACTION_MOUSE_STATE))
            registerReceiver(mouseStatusReceiver, IntentFilter(HidMouseService.ACTION_STATUS))
            registerReceiver(rfcommMouseTrackingReceiver, IntentFilter(ListenerService.ACTION_RFCOMM_MOUSE_TRACKING))
            registerReceiver(reidFacesReceiver, IntentFilter(ListenerService.ACTION_REID_FACES))
            registerReceiver(reidStatsReceiver, IntentFilter(ListenerService.ACTION_REID_STATS))
            registerReceiver(reidBpmReceiver, IntentFilter(ListenerService.ACTION_REID_BPM))
            registerReceiver(reidStatusReceiver, IntentFilter(ListenerService.ACTION_REID_STATUS))
            registerReceiver(reidPersonResponseReceiver, IntentFilter(ListenerService.ACTION_REID_PERSON_RESPONSE))
            registerReceiver(reidBestThumbReceiver, IntentFilter(ListenerService.ACTION_REID_BEST_THUMB))
            registerReceiver(todoListReceiver, IntentFilter(ListenerService.ACTION_TODO_LIST_LOADED))
            registerReceiver(alarmListReceiver, IntentFilter(ListenerService.ACTION_ALARM_LIST_LOADED))
            registerReceiver(jobListReceiver, IntentFilter(ListenerService.ACTION_JOB_LIST_LOADED))
            // Notification overlay now managed by ListenerService via WindowManager
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            registerReceiver(timeTickReceiver, IntentFilter(Intent.ACTION_TIME_TICK))
            registerReceiver(bottomPaddingReceiver, IntentFilter(ListenerService.ACTION_BOTTOM_PADDING))
            registerReceiver(chatFontSizeReceiver, IntentFilter(ListenerService.ACTION_CHAT_FONT_SIZE))
            registerReceiver(mediaStateReceiver, IntentFilter(ListenerService.ACTION_MEDIA_STATE))
            registerReceiver(mediaProgressReceiver, IntentFilter(ListenerService.ACTION_MEDIA_PROGRESS))
            registerReceiver(
                a2dpSinkStateReceiver,
                IntentFilter("android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED"),
                android.content.Context.RECEIVER_EXPORTED,
            )
            queryInitialA2dpSinkState()
            registerReceiver(notificationShownReceiver, IntentFilter(ListenerService.ACTION_NOTIFICATION_SHOWN))
            registerReceiver(notificationHiddenReceiver, IntentFilter(ListenerService.ACTION_NOTIFICATION_HIDDEN))
            registerReceiver(notificationSoloShowReceiver, IntentFilter(ListenerService.ACTION_NOTIFICATION_SOLO_SHOW))
            registerReceiver(notificationSoloEndReceiver, IntentFilter(ListenerService.ACTION_NOTIFICATION_SOLO_END))
            registerReceiver(soloScreenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
            registerReceiver(soloScreenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
            registerReceiver(tgChatListResponseReceiver, IntentFilter(ListenerService.ACTION_TG_CHAT_LIST))
            registerReceiver(tgMessagesResponseReceiver, IntentFilter(ListenerService.ACTION_TG_MESSAGES))
            registerReceiver(tgNewMessageReceiver, IntentFilter(ListenerService.ACTION_TG_NEW_MESSAGE))
            registerReceiver(tgSendResultReceiver, IntentFilter(ListenerService.ACTION_TG_SEND_RESULT))
            registerReceiver(tgTopicsResponseReceiver, IntentFilter(ListenerService.ACTION_TG_TOPICS))

            // Restore bottom padding from SharedPreferences (cross-process persistence)
            val savedPadding = getSharedPreferences("display_settings", MODE_PRIVATE)
                .getInt("bottom_padding_px", 0)
            if (savedPadding > 0) applyBottomPadding(savedPadding)

            // Restore chat font size from SharedPreferences
            val savedChatFontSize = getSharedPreferences("display_settings", MODE_PRIVATE)
                .getFloat("chat_font_size", 14f)
            if (savedChatFontSize != 14f) {
                com.repository.glasses.listener.config.GlassesConfig.chatFontSize = savedChatFontSize
            }

            // Notification overlay managed by ListenerService (WindowManager-based)

            // Bind to backend process for crash detection + auto-restart
            bindBackend()

            // Log display metrics for debugging
            val dm = resources.displayMetrics
            dbg("DISPLAY: ${dm.widthPixels}x${dm.heightPixels}px density=${dm.density} dpi=${dm.densityDpi}")
            chatContainer.post {
                val cf = findViewById<View>(R.id.contentFrame)
                val sa = findViewById<View>(R.id.statusArea)
                val tb = findViewById<View>(R.id.tabBar)
                dbg("LAYOUT: cc=${chatContainer.width}x${(chatContainer as View).height} cf=${cf.width}x${cf.height} sa=${sa.width}x${sa.height} tb=${tb.width}x${tb.height}")
            }

            val missingPermissions = mutableListOf<String>()
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(Manifest.permission.RECORD_AUDIO)
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(Manifest.permission.CAMERA)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }

            if (missingPermissions.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 100)
            } else {
                startListenerService()
            }

            if (intent?.getBooleanExtra("start_camera_preview", false) == true) {
                Handler(Looper.getMainLooper()).postDelayed({ showCameraPreview() }, 300)
            }

            handleTeleprompterIntent(intent, fromOnCreate = true)
            handleTranslationIntent(intent)

            // Start reading rokid-touchpad-virt's ABS_X stream for live
            // tab selection in TAB_NAV mode.
            try { touchpadAbsListener.start() } catch (e: Exception) {
                uiLog("NAV: TouchpadAbsListener.start() failed: ${e.message}")
            }
        } catch (e: Exception) {
            GlassesListenerApp.writeCrashLog(this, "onCreate CRASHED: ${e.message}\n${e.stackTraceToString()}")
        }
    }

    // ---- Touchpad ABS_X handlers (drag-pill + snap-on-release) -------------
    // Touch down: latch the pill's current X as anchor. While finger is
    //   sliding, pill follows finger 1:1 across the tab bar (no tab
    //   commit yet). On release: snap to the nearest tab and commit.
    private fun onTouchpadAbsTouch(down: Boolean) {
        // Two drag modes share this listener:
        //   TAB_NAV                                 -> bottom tab pill drag
        //   TODO_FOCUSED + todoFocusLevel == 0      -> TODO sub-tab capsule drag
        val tabNav = focusState == FocusState.TAB_NAV
        val todoSub = focusState == FocusState.TODO_FOCUSED && todoFocusLevel == 0
        if (!tabNav && touchpadAbsActive) endTabNavDrag()
        if (!todoSub && todoAbsActive) endTodoSubDrag()
        if (todoSub) { onTodoSubAbsTouch(down); return }
        if (!tabNav) return
        if (down && !touchpadAbsActive) {
            touchpadAbsActive = true
            touchpadAbsAnchorX = pillHighlight.translationX
            touchpadAbsStartPos = -1
            touchpadAbsTargetX  = touchpadAbsAnchorX
            touchpadAbsHoverIdx = activeTabs.indexOf(currentTabId).coerceAtLeast(0)
            // Create a long-lived spring on translationX. Subsequent
            // ABS_X samples will call animateToFinalPosition() which
            // re-targets without canceling, giving continuous smooth
            // motion. Cancel any prior tab-switch spring so we don't
            // fight it.
            (pillHighlight.getTag(R.id.spring_translate_x) as? androidx.dynamicanimation.animation.SpringAnimation)?.cancel()
            touchpadAbsSpring = androidx.dynamicanimation.animation.SpringAnimation(
                pillHighlight, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_X, touchpadAbsAnchorX
            ).apply {
                spring = androidx.dynamicanimation.animation.SpringForce(touchpadAbsAnchorX)
                    .setStiffness(androidx.dynamicanimation.animation.SpringForce.STIFFNESS_LOW)
                    .setDampingRatio(androidx.dynamicanimation.animation.SpringForce.DAMPING_RATIO_NO_BOUNCY)
                pillHighlight.setTag(R.id.spring_translate_x, this)
                start()
            }
            // Keep the screen on while the user is interacting with the
            // touchpad — the system idle timer should not dim mid-gesture.
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // Tell the daemon to stop emitting NUMPAD scroll keycodes while
            // we drag — they would flood dispatchKeyEvent at ~100 Hz and
            // stall the UI thread, causing long-drag freeze.
            try { java.io.File("/sdcard/rokid-touchpad-keys-off").createNewFile() } catch (_: Throwable) {}
            android.view.Choreographer.getInstance().postFrameCallback(touchpadAbsFrameCallback)
        } else if (!down && touchpadAbsActive) {
            touchpadAbsActive = false
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            try { java.io.File("/sdcard/rokid-touchpad-keys-off").delete() } catch (_: Throwable) {}
            android.view.Choreographer.getInstance().removeFrameCallback(touchpadAbsFrameCallback)
            val tabCount = activeTabs.size.coerceAtLeast(1)
            val tabWidth = pillContainer.width.toFloat() / tabCount
            if (tabWidth <= 0f) {
                touchpadAbsSpring?.cancel(); touchpadAbsSpring = null
                return
            }
            val curX = pillHighlight.translationX
            val nearestIdx = ((curX + tabWidth / 2f) / tabWidth)
                .toInt().coerceIn(0, tabCount - 1)
            uiLog("NAV: TPAD-ABS release; snap pillX=$curX -> tab $nearestIdx")
            // Cancel the live drag spring so the bouncy snap spring isn't
            // fighting it.
            touchpadAbsSpring?.cancel()
            touchpadAbsSpring = null
            // Bouncy snap: spring with low damping ratio so the pill
            // overshoots the slot a touch before settling.
            val finalX = nearestIdx * tabWidth
            (pillHighlight.getTag(R.id.spring_translate_x) as? androidx.dynamicanimation.animation.SpringAnimation)?.cancel()
            androidx.dynamicanimation.animation.SpringAnimation(
                pillHighlight, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_X, finalX
            ).apply {
                spring = androidx.dynamicanimation.animation.SpringForce(finalX)
                    .setStiffness(androidx.dynamicanimation.animation.SpringForce.STIFFNESS_MEDIUM)
                    .setDampingRatio(androidx.dynamicanimation.animation.SpringForce.DAMPING_RATIO_LOW_BOUNCY)
                pillHighlight.setTag(R.id.spring_translate_x, this)
                start()
            }
            // Force every tab icon to its correct final state. Cancels any
            // in-flight hover animator per icon so an overlapping run
            // can't leave a neighbour stuck at GLOW.
            settleTabIconsAfterDrag(nearestIdx)
            // Commit the selection (content visibility, broadcasts) but
            // skip switchToTab's pill translation + icon repaints — we've
            // already handled those.
            switchToTab(nearestIdx, animate = false, skipTabIconAnims = true, skipPillAnim = true)
        }
    }

    private fun recomputeTouchpadAbsTarget(rawPos: Int) {
        if (focusState != FocusState.TAB_NAV) return
        if (touchpadAbsStartPos < 0) {
            touchpadAbsStartPos = rawPos
            return
        }
        val delta = rawPos - touchpadAbsStartPos
        val barW  = pillContainer.width.toFloat()
        if (barW <= 0f) return
        val deltaPx = delta * (barW / 100f)
        val pillW   = pillHighlight.width.toFloat()
        val maxX    = (barW - pillW).coerceAtLeast(0f)
        val newTarget = (touchpadAbsAnchorX + deltaPx).coerceIn(0f, maxX)
        if (newTarget == touchpadAbsTargetX) return
        touchpadAbsTargetX = newTarget
        // Reuse the live spring; animateToFinalPosition smoothly retargets
        // without canceling, so 100 Hz target updates produce smooth
        // continuous motion (no per-sample snap).
        touchpadAbsSpring?.animateToFinalPosition(newTarget)
    }

    // Per-icon tint animator + last-applied color tracking. Hovering over
    // tabs at >5 Hz means a previous tintColor animator can still be in
    // flight when the next one starts; without cancellation they race
    // and the icon can land on the wrong color (the source of the
    // "tab beside the selected stays highlighted" glitch).
    private fun setTabTint(icon: android.widget.ImageView, fromColor: Int, toColor: Int, duration: Long) {
        (icon.getTag(R.id.tab_hover_tint_anim) as? android.animation.ValueAnimator)?.cancel()
        val anim = Anim.tintColor(icon, fromColor, toColor, duration)
        icon.setTag(R.id.tab_hover_tint_anim, anim)
        icon.setTag(R.id.tab_hover_target_color, toColor)
    }
    private fun lastTabTint(icon: android.widget.ImageView, default: Int): Int =
        (icon.getTag(R.id.tab_hover_target_color) as? Int) ?: default

    private fun animateTabHover(prevIdx: Int, newIdx: Int) {
        // Cancel any in-flight tint/scale from a prior switchToTab so we
        // don't fight it.
        tintActiveAnimator?.cancel(); tintActiveAnimator = null
        tintInactiveAnimator?.cancel(); tintInactiveAnimator = null
        if (prevIdx in activeTabs.indices) {
            iconForTab(activeTabs[prevIdx])?.let { icon ->
                setTabTint(icon, lastTabTint(icon, Lum.GLOW), Lum.SOFT, 180L)
                Anim.springScale(icon, TAB_ICON_SCALE_DEFAULT)
            }
        }
        if (newIdx in activeTabs.indices) {
            iconForTab(activeTabs[newIdx])?.let { icon ->
                setTabTint(icon, lastTabTint(icon, Lum.SOFT), Lum.GLOW, 180L)
                Anim.springScale(icon, TAB_ICON_SCALE_SELECTED)
            }
        }
    }

    // Force every tab icon to its final correct state at drag release —
    // selected = GLOW + SELECTED scale, others = SOFT + DEFAULT scale.
    // Cancels any in-flight hover animator per icon and animates from
    // the icon's last-known color so there's no flash.
    private fun settleTabIconsAfterDrag(selectedIdx: Int) {
        for ((i, id) in activeTabs.withIndex()) {
            val icon = iconForTab(id) ?: continue
            val isSelected = (i == selectedIdx)
            val targetColor = if (isSelected) Lum.GLOW else Lum.SOFT
            val targetScale = if (isSelected) TAB_ICON_SCALE_SELECTED else TAB_ICON_SCALE_DEFAULT
            val current = lastTabTint(icon, targetColor)
            if (current != targetColor) {
                setTabTint(icon, current, targetColor, 180L)
            }
            Anim.springScale(icon, targetScale)
        }
    }

    // ---- TAB_NAV / TODO sub drag cleanup helpers ---------------------------
    private fun endTabNavDrag() {
        touchpadAbsActive = false
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try { java.io.File("/sdcard/rokid-touchpad-keys-off").delete() } catch (_: Throwable) {}
        android.view.Choreographer.getInstance().removeFrameCallback(touchpadAbsFrameCallback)
        touchpadAbsSpring?.cancel(); touchpadAbsSpring = null
    }

    // ---- TODO sub-tab capsule drag -----------------------------------------
    // Same model as the bottom tab pill, applied to the TODO sub-tab capsule
    // (slot count = TodoSubTab.entries.size). The capsule's leftMargin is
    // ALWAYS 0 and its width is exactly one slot; position is driven purely
    // by translationX via a SpringAnimation. On release we snap translationX
    // to nearestIdx * slotW with a bouncy spring -- leftMargin/width are never
    // touched, so the capsule never jumps.

    private fun onTodoSubAbsTouch(down: Boolean) {
        val capsule = todoSubTabCapsule ?: return
        val pill = todoSubTabPill ?: return
        if (down && !todoAbsActive) {
            todoAbsActive = true
            todoAbsAnchorTx = capsule.translationX
            todoAbsTargetTx = todoAbsAnchorTx
            todoAbsStartPos = -1
            todoAbsHoverIdx = todoSubTab.ordinal
            (capsule.getTag(R.id.spring_translate_x) as? androidx.dynamicanimation.animation.SpringAnimation)?.cancel()
            todoAbsSpring = androidx.dynamicanimation.animation.SpringAnimation(
                capsule, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_X, todoAbsAnchorTx
            ).apply {
                spring = androidx.dynamicanimation.animation.SpringForce(todoAbsAnchorTx)
                    .setStiffness(androidx.dynamicanimation.animation.SpringForce.STIFFNESS_LOW)
                    .setDampingRatio(androidx.dynamicanimation.animation.SpringForce.DAMPING_RATIO_NO_BOUNCY)
                capsule.setTag(R.id.spring_translate_x, this)
                start()
            }
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            try { java.io.File("/sdcard/rokid-touchpad-keys-off").createNewFile() } catch (_: Throwable) {}
            android.view.Choreographer.getInstance().postFrameCallback(todoSubAbsFrameCallback)
        } else if (!down && todoAbsActive) {
            todoAbsActive = false
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            try { java.io.File("/sdcard/rokid-touchpad-keys-off").delete() } catch (_: Throwable) {}
            // NOTE: do NOT remove the frame callback yet. The bouncy
            // snap spring continues to drive translationX after release;
            // the callback re-evaluates which icon sits under the
            // capsule each frame and animates that one to GLOW. This
            // prevents the inactive (GHOST) icon from disappearing into
            // the same-coloured capsule during overshoot. The callback
            // self-terminates once the spring has settled.
            val tabCount = TodoSubTab.entries.size
            val pillW = pill.width.toFloat()
            if (tabCount <= 0 || pillW <= 0f) {
                todoAbsSpring?.cancel(); todoAbsSpring = null
                return
            }
            // Compute nearest slot from the live (spring-driven) translationX.
            // leftMargin is always 0, so the capsule's center is purely
            // translationX + width/2 -- same model as the bottom pill.
            val slotW = pillW / tabCount
            val curX = capsule.translationX
            val nearestIdx = ((curX + slotW / 2f) / slotW).toInt().coerceIn(0, tabCount - 1)
            uiLog("NAV: TODO-SUB-ABS release; snap capsuleX=$curX -> sub $nearestIdx")
            // Cancel the live drag spring so the bouncy snap spring isn't
            // fighting it.
            todoAbsSpring?.cancel(); todoAbsSpring = null
            // Bouncy snap: spring translationX to the slot's rest position.
            // leftMargin/width are NEVER touched -- only translationX moves,
            // exactly like the bottom pill, so the capsule never jumps.
            val finalX = nearestIdx * slotW
            (capsule.getTag(R.id.spring_translate_x) as? androidx.dynamicanimation.animation.SpringAnimation)?.cancel()
            androidx.dynamicanimation.animation.SpringAnimation(
                capsule, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_X, finalX
            ).apply {
                spring = androidx.dynamicanimation.animation.SpringForce(finalX)
                    .setStiffness(androidx.dynamicanimation.animation.SpringForce.STIFFNESS_MEDIUM)
                    .setDampingRatio(androidx.dynamicanimation.animation.SpringForce.DAMPING_RATIO_LOW_BOUNCY)
                capsule.setTag(R.id.spring_translate_x, this)
                start()
            }
            todoSubTab = TodoSubTab.entries[nearestIdx]
            // The frame callback (still posted) will animate the icon
            // currently under the capsule to GLOW each time hover index
            // changes during the bouncy overshoot, so we don't snap-set
            // them here.
            updateTodoSubTabLabels(skipIconAnims = true, skipCapsuleAnim = true)
        }
    }

    private fun endTodoSubDrag() {
        todoAbsActive = false
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try { java.io.File("/sdcard/rokid-touchpad-keys-off").delete() } catch (_: Throwable) {}
        android.view.Choreographer.getInstance().removeFrameCallback(todoSubAbsFrameCallback)
        todoAbsSpring?.cancel(); todoAbsSpring = null
    }

    private fun recomputeTodoAbsTarget(rawPos: Int) {
        val capsule = todoSubTabCapsule ?: return
        val pill = todoSubTabPill ?: return
        if (todoAbsStartPos < 0) {
            todoAbsStartPos = rawPos
            return
        }
        val delta = rawPos - todoAbsStartPos
        val pillW = pill.width.toFloat()
        if (pillW <= 0f) return
        val deltaPx = delta * (pillW / 100f)
        // leftMargin is always 0; translationX alone positions the capsule.
        // Clamp so the capsule stays within the pill: [0, pillW - capsuleW].
        val maxTx = (pillW - capsule.width).coerceAtLeast(0f)
        val newTarget = (todoAbsAnchorTx + deltaPx).coerceIn(0f, maxTx)
        if (newTarget == todoAbsTargetTx) return
        todoAbsTargetTx = newTarget
        todoAbsSpring?.animateToFinalPosition(newTarget)
    }

    private val todoSubAbsFrameCallback = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val capsule = todoSubTabCapsule
            val pill = todoSubTabPill
            if (capsule == null || pill == null) return
            // Pull a new target from the raw HW stream only while the
            // finger is actually down. After release the bouncy spring
            // owns translationX and we keep this callback alive solely
            // to repaint the icon under the capsule.
            if (todoAbsActive) {
                val rawPos = touchpadAbsListener.latestPosition.get()
                if (rawPos >= 0) recomputeTodoAbsTarget(rawPos)
            }
            val tabCount = TodoSubTab.entries.size
            val pillW = pill.width.toFloat()
            if (tabCount > 0 && pillW > 0f) {
                // leftMargin is always 0; center is translationX + width/2.
                val visualCenter = capsule.translationX + capsule.width / 2f
                val slotW = pillW / tabCount
                val rawIdx = (visualCenter / slotW).toInt().coerceIn(0, tabCount - 1)
                val cur = todoAbsHoverIdx
                val newHover = if (rawIdx == cur || cur < 0) rawIdx else {
                    val hyst = slotW * 0.20f
                    val passed = if (rawIdx > cur) visualCenter > rawIdx * slotW + hyst
                                 else visualCenter < (cur * slotW) - hyst
                    if (passed) rawIdx else cur
                }
                if (newHover != cur) {
                    todoAbsHoverIdx = newHover
                    animateTodoSubHover(cur, newHover)
                }
            }
            // Self-terminate once the bouncy snap spring has settled (the
            // capsule now rests at nearestIdx * slotW via translationX, NOT
            // at 0) AND the user is no longer touching the pad.
            val spring = capsule.getTag(R.id.spring_translate_x) as? androidx.dynamicanimation.animation.SpringAnimation
            val settled = spring?.isRunning != true
            if (!todoAbsActive && settled) return
            android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun animateTodoSubHover(prevIdx: Int, newIdx: Int) {
        if (prevIdx in TodoSubTab.entries.indices) {
            todoSubTabLabels[prevIdx]?.let { icon ->
                setTabTint(icon, lastTabTint(icon, Lum.GLOW), Lum.GHOST, 180L)
            }
        }
        if (newIdx in TodoSubTab.entries.indices) {
            todoSubTabLabels[newIdx]?.let { icon ->
                setTabTint(icon, lastTabTint(icon, Lum.GHOST), Lum.GLOW, 180L)
            }
        }
    }

    private fun settleTodoSubIconsAfterDrag(selectedIdx: Int) {
        for (i in TodoSubTab.entries.indices) {
            val icon = todoSubTabLabels[i] ?: continue
            val target = if (i == selectedIdx) Lum.GLOW else Lum.GHOST
            val current = lastTabTint(icon, target)
            if (current != target) {
                setTabTint(icon, current, target, 180L)
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra("start_camera_preview", false) == true) {
            showCameraPreview()
        }
        if (intent?.getBooleanExtra("switch_to_chat", false) == true) {
            intent.removeExtra("switch_to_chat")
            val chatIndex = activeTabs.indexOf(TabId.CHAT)
            if (chatIndex >= 0) switchToTab(chatIndex, animate = false)
        }

        handleTeleprompterIntent(intent, fromOnCreate = false)
        handleTranslationIntent(intent)

        if (intent?.getBooleanExtra("start_mouse", false) == true) {
            intent.removeExtra("start_mouse")
            mouseSensX = intent.getFloatExtra(ListenerService.EXTRA_MOUSE_SENSITIVITY_X, 1800f)
            mouseSensY = intent.getFloatExtra(ListenerService.EXTRA_MOUSE_SENSITIVITY_Y, 4200f)
            showMouseTab()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Home app -- do not move to back (Sprite Launcher would show)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            val audioIndex = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
            val audioGranted = audioIndex >= 0 && grantResults[audioIndex] == PackageManager.PERMISSION_GRANTED
            if (audioGranted || audioIndex < 0) {
                startListenerService()
            }
        } else if (requestCode == 200) {
            val camIndex = permissions.indexOf(Manifest.permission.CAMERA)
            if (camIndex >= 0 && grantResults[camIndex] == PackageManager.PERMISSION_GRANTED) {
                sendBroadcast(Intent(ListenerService.ACTION_CAMERA_PERMISSION_GRANTED).apply { setPackage(packageName) })
            }
        }
    }

    private fun startListenerService() {
        try {
            val intent = Intent(this, ListenerService::class.java)
            startForegroundService(intent)
        } catch (e: Exception) {
            GlassesListenerApp.writeCrashLog(this, "Failed to start ListenerService: ${e.message}\n${e.stackTraceToString()}")
        }
    }

    private fun scrollToBottom() {
        val itemCount = chatAdapter.itemCount
        if (itemCount > 0) {
            chatRecycler.scrollToPosition(itemCount - 1)
        }
    }

    private fun scrollChatToCenter(pos: Int) {
        val offset = chatRecycler.height / 3
        layoutManager.scrollToPositionWithOffset(pos, offset)
    }

    /** Select whichever message is closest to the viewport center (skip SYSTEM). */
    private fun updateCenterSelection() {
        val centerY = chatRecycler.height / 2
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return

        var bestPos = -1
        var bestDist = Int.MAX_VALUE
        for (i in first..last) {
            val child = layoutManager.findViewByPosition(i) ?: continue
            val childCenter = (child.top + child.bottom) / 2
            val dist = kotlin.math.abs(childCenter - centerY)
            if (dist < bestDist) {
                bestDist = dist
                bestPos = i
            }
        }
        if (bestPos < 0) return

        // Skip SYSTEM messages -- pick nearest selectable neighbor
        val messages = chatAdapter.getMessages()
        if (bestPos in messages.indices && messages[bestPos].role == ChatMessage.Role.SYSTEM) {
            val above = chatAdapter.nextSelectablePosition(bestPos, -1)
            val below = chatAdapter.nextSelectablePosition(bestPos, 1)
            bestPos = when {
                above == bestPos && below == bestPos -> return
                above == bestPos -> below
                below == bestPos -> above
                else -> {
                    val av = layoutManager.findViewByPosition(above)
                    val bv = layoutManager.findViewByPosition(below)
                    if (av != null && bv != null) {
                        val ad = kotlin.math.abs((av.top + av.bottom) / 2 - centerY)
                        val bd = kotlin.math.abs((bv.top + bv.bottom) / 2 - centerY)
                        if (ad <= bd) above else below
                    } else above
                }
            }
        }

        if (bestPos != chatAdapter.selectedPosition) {
            chatAdapter.selectPosition(bestPos)
        }
    }

    // --- Loading spinner ---

    private fun showLoadingSpinner() {
        loaderCtl.show()
    }

    private fun hideLoadingSpinner() {
        loaderCtl.hide()
    }

    // --- Focus visual indicator ---

    private fun updateFocusVisual(state: FocusState) {
        // Cancel previous animation
        focusBorderAnimator?.cancel()

        // The bottom-bar selection circle is the focus cursor for the tab row,
        // so it is only ever visible in TAB_NAV. The moment focus drills into
        // any tab's content it fades out (mirroring how the TODO sub-tab capsule
        // fades when the sub-tab row stops being the active nav target). Guarded
        // so the fade fires once per transition, not on every scroll/key event.
        val pillShouldShow = state == FocusState.TAB_NAV
        if (pillShouldShow != pillHighlightShown) {
            pillHighlightShown = pillShouldShow
            if (pillShouldShow) fadeInHighlight(pillHighlight) else fadeOutHighlight(pillHighlight)
        }

        // Reset previous focused view to default thin stroke
        val prevView = previousFocusedView
        if (prevView != null) {
            val pillContainer = findViewById<View>(R.id.pillContainer)
            if (prevView === pillContainer) {
                prevView.setBackgroundColor(Lum.VOID)
            } else if (prevView === reidStartStopContainer || prevView === translationStartStopContainer) {
                prevView.setBackgroundColor(Lum.VOID)
            } else {
                prevView.setBackgroundColor(Lum.VOID)
            }
            previousFocusedView = null
            focusedDrawable = null
        }

        // Clear chat message selection when leaving CHAT_FOCUSED
        if (state != FocusState.CHAT_FOCUSED) {
            chatAdapter.clearSelection()
        }

        // Only show chat list selection outline when list is focused
        chatListAdapter.setFocused(state == FocusState.LIST_FOCUSED)

        val pillContainer = findViewById<View>(R.id.pillContainer)
        val density = Resources.getSystem().displayMetrics.density
        val thinStroke = (0.5f * density + 0.5f).toInt()
        val thickStroke = (2f * density + 0.5f).toInt()

        if (state == FocusState.TAB_NAV) {
            // No outline -- the moving circular highlight is the only focus cue.
            // Transparent (not VOID/opaque-black) so the pill never occludes the
            // weather/clock status row it overlaps; on the waveguide both read as
            // pixels-off, but transparent lets the sibling green show through.
            pillContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        } else {
            // No pill outline in any state.
            pillContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT)

            // MAP_FOCUSED: focus the active button by mapFocusedIndex
            // (0=steps, 1=stop, 2=zoom-slider, 3=pin)
            if (state == FocusState.MAP_FOCUSED) {
                updateStepsFocus(mapFocusedIndex == 0)
                updateStopFocus(mapFocusedIndex == 1)
                updateZoomSliderFocus(mapFocusedIndex == MAP_FOCUS_ZOOM, zooming = false)
                updatePinFocus(mapFocusedIndex == MAP_FOCUS_PIN)
                updateTpStopFocus(false)
                return
            }

            // MAP_ZOOM_FOCUSED: slider is in active mode; DPAD_L/R steps zoom.
            if (state == FocusState.MAP_ZOOM_FOCUSED) {
                updateStepsFocus(false)
                updateStopFocus(false)
                updatePinFocus(false)
                updateZoomSliderFocus(focused = true, zooming = true)
                updateTpStopFocus(false)
                return
            }

            // STOP_MODAL / STEPS_MODAL: no button focus, modal handles its own visuals
            if (state == FocusState.STOP_MODAL || state == FocusState.STEPS_MODAL) {
                updateStepsFocus(false)
                updatePinFocus(false)
                updateStopFocus(false)
                updateZoomSliderFocus(focused = false)
                updateTpStopFocus(false)
                return
            }

            // TELEPROMPTER_FOCUSED: stop button + content area
            if (state == FocusState.TELEPROMPTER_FOCUSED) {
                updatePinFocus(false)
                updateStopFocus(false)
                updateTpStopFocus(tpFocusedIndex == 0)
                if (tpFocusedIndex == 1) {
                    // Content focused: draw border on contentFrame
                    val drawable = GradientDrawable().apply {
                        setColor(Lum.VOID)
                        cornerRadius = 8f * density
                        setStroke(thinStroke, Lum.GHOST)
                    }
                    focusedDrawable = drawable
                    previousFocusedView = contentFrame
                    contentFrame.background = drawable
                    focusBorderAnimator = ValueAnimator.ofInt(thinStroke, thickStroke).apply {
                        duration = 150L
                        interpolator = android.view.animation.DecelerateInterpolator()
                        addUpdateListener {
                            drawable.setStroke(it.animatedValue as Int, Lum.GHOST)
                        }
                        start()
                    }
                }
                return
            }

            // MUSIC_FOCUSED: outline on play/pause button
            if (state == FocusState.MUSIC_FOCUSED) {
                updatePinFocus(false)
                updateStopFocus(false)
                updateTpStopFocus(false)
                val drawable = GradientDrawable().apply {
                    setColor(Lum.VOID)
                    cornerRadius = 4f * density
                    setStroke(thinStroke, Lum.GLOW)
                }
                musicPlayPauseContainer.background = drawable
                focusedDrawable = drawable
                previousFocusedView = musicPlayPauseContainer
                focusBorderAnimator = ValueAnimator.ofInt(thinStroke, thickStroke).apply {
                    duration = 150L
                    interpolator = android.view.animation.DecelerateInterpolator()
                    addUpdateListener {
                        drawable.setStroke(it.animatedValue as Int, Lum.GLOW)
                    }
                    start()
                }
                return
            }

            // TRANSLATE_FOCUSED: outline on start/stop button
            if (state == FocusState.TRANSLATE_FOCUSED) {
                updatePinFocus(false)
                updateStopFocus(false)
                updateTpStopFocus(false)
                val drawable = GradientDrawable().apply {
                    setColor(Lum.VOID)
                    cornerRadius = 4f * density
                    setStroke(thinStroke, Lum.GLOW)
                }
                translationStartStopContainer.background = drawable
                focusedDrawable = drawable
                previousFocusedView = translationStartStopContainer
                focusBorderAnimator = ValueAnimator.ofInt(thinStroke, thickStroke).apply {
                    duration = 150L
                    interpolator = android.view.animation.DecelerateInterpolator()
                    addUpdateListener {
                        drawable.setStroke(it.animatedValue as Int, Lum.GLOW)
                    }
                    start()
                }
                return
            }

            // REID_FOCUSED (depth 1): focus either start/stop or face bar
            if (state == FocusState.REID_FOCUSED) {
                updatePinFocus(false)
                updateStopFocus(false)
                updateTpStopFocus(false)
                val targetView = if (reidFocusedElement == 0) reidStartStopContainer else reidFaceBar
                val otherView = if (reidFocusedElement == 0) reidFaceBar else reidStartStopContainer
                val cornerR = if (reidFocusedElement == 0) 4f * density else 8f * density
                otherView.background = null
                val drawable = GradientDrawable().apply {
                    setColor(Lum.VOID)
                    cornerRadius = cornerR
                    setStroke(thinStroke, Lum.GLOW)
                }
                targetView.background = drawable
                focusedDrawable = drawable
                previousFocusedView = targetView
                focusBorderAnimator = ValueAnimator.ofInt(thinStroke, thickStroke).apply {
                    duration = 150L
                    interpolator = android.view.animation.DecelerateInterpolator()
                    addUpdateListener {
                        drawable.setStroke(it.animatedValue as Int, Lum.GLOW)
                    }
                    start()
                }
                return
            }

            // REID_FACES_FOCUSED (depth 2): no container border, face selection in updateReidFaceBar
            if (state == FocusState.REID_FACES_FOCUSED) {
                updatePinFocus(false)
                updateStopFocus(false)
                updateTpStopFocus(false)
                reidStartStopContainer.background = null
                reidFaceBar.background = null
                previousFocusedView = null
                focusedDrawable = null
                return
            }

            // REID_INTEL_MODAL (depth 3): no focus visual, modal is full-screen
            if (state == FocusState.REID_INTEL_MODAL) {
                updatePinFocus(false)
                updateStopFocus(false)
                updateTpStopFocus(false)
                previousFocusedView = null
                focusedDrawable = null
                return
            }

            // NIGHTVISION_FOCUSED: subtle border on preview container
            if (state == FocusState.NIGHTVISION_FOCUSED) {
                updatePinFocus(false)
                updateStopFocus(false)
                updateTpStopFocus(false)
                val drawable = GradientDrawable().apply {
                    setColor(Lum.VOID)
                    cornerRadius = 8f * density
                    setStroke(thinStroke, Lum.GHOST)
                }
                nightvisionContainer.background = drawable
                focusedDrawable = drawable
                previousFocusedView = nightvisionContainer
                focusBorderAnimator = ValueAnimator.ofInt(thinStroke, thickStroke).apply {
                    duration = 150L
                    interpolator = android.view.animation.DecelerateInterpolator()
                    addUpdateListener {
                        drawable.setStroke(it.animatedValue as Int, Lum.GHOST)
                    }
                    start()
                }
                return
            }

            // TELEGRAM_RECORDING / TELEGRAM_PREVIEW / NOTIFICATION_REPLY: no focus border, overlay is visible
            if (state == FocusState.TELEGRAM_RECORDING || state == FocusState.TELEGRAM_PREVIEW ||
                state == FocusState.NOTIFICATION_REPLY) {
                updatePinFocus(false)
                updateStopFocus(false)
                updateTpStopFocus(false)
                previousFocusedView = null
                focusedDrawable = null
                return
            }

            // Telegram list/topics/chat: set adapter focus state
            telegramChatListAdapter.setFocused(state == FocusState.TELEGRAM_LIST_FOCUSED)
            telegramTopicListAdapter.setFocused(state == FocusState.TELEGRAM_TOPICS_FOCUSED)

            // Clear button focus when leaving map/teleprompter
            updatePinFocus(false)
            updateStopFocus(false)
            updateTpStopFocus(false)

            // Content focus: animate stroke width on contentFrame
            val drawable = GradientDrawable().apply {
                setColor(Lum.VOID)
                cornerRadius = 8f * density
                setStroke(thinStroke, Lum.GHOST)
            }
            focusedDrawable = drawable
            previousFocusedView = contentFrame
            contentFrame.background = drawable
            focusBorderAnimator = ValueAnimator.ofInt(thinStroke, thickStroke).apply {
                duration = 150L
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener {
                    drawable.setStroke(it.animatedValue as Int, Lum.GHOST)
                }
                start()
            }

            // Scroll to last message when entering CHAT_FOCUSED; center selection follows
            if (state == FocusState.CHAT_FOCUSED && serviceState == "IDLE") {
                val last = chatAdapter.lastSelectablePosition()
                if (last >= 0) {
                    scrollChatToCenter(last)
                    chatRecycler.post { updateCenterSelection() }
                }
            }
        }
    }

    // --- Scroll indicator ---

    private fun updateScrollIndicator(recyclerView: RecyclerView) {
        val contentFrame = scrollIndicator.parent as? ViewGroup ?: return
        val frameHeight = contentFrame.height
        if (frameHeight <= 0) return

        val range = recyclerView.computeVerticalScrollRange()
        val extent = recyclerView.computeVerticalScrollExtent()
        val offset = recyclerView.computeVerticalScrollOffset()

        if (range <= extent) {
            // All content visible, hide indicator
            scrollIndicator.animate().alpha(0f).setDuration(200L).start()
            return
        }

        // Calculate indicator size and position
        val indicatorHeight = ((extent.toFloat() / range) * frameHeight).toInt().coerceAtLeast(8.dpToPx())
        val indicatorTop = ((offset.toFloat() / range) * frameHeight).toInt()

        val lp = scrollIndicator.layoutParams as FrameLayout.LayoutParams
        lp.height = indicatorHeight
        lp.topMargin = indicatorTop
        lp.gravity = android.view.Gravity.END
        scrollIndicator.layoutParams = lp

        // Show indicator
        scrollIndicator.animate().alpha(1f).setDuration(100L).start()

        // Hide after 1s of inactivity
        scrollIndicatorHideRunnable?.let { mainHandler.removeCallbacks(it) }
        val hideRunnable = Runnable {
            scrollIndicator.animate().alpha(0f).setDuration(300L).start()
        }
        scrollIndicatorHideRunnable = hideRunnable
        mainHandler.postDelayed(hideRunnable, 1000L)
    }

    // --- Teleprompter scroll indicator ---

    private fun updateTeleprompterScrollIndicator() {
        val metrics = teleprompterController?.getScrollMetrics() ?: return
        val (range, extent, offset) = metrics
        val frameHeight = (tpScrollIndicator.parent as? ViewGroup)?.height ?: return
        if (frameHeight <= 0 || range <= extent) {
            tpScrollIndicator.animate().alpha(0f).setDuration(200L).start()
            return
        }

        val indicatorHeight = ((extent.toFloat() / range) * frameHeight).toInt().coerceAtLeast(8.dpToPx())
        val indicatorTop = ((offset.toFloat() / range) * frameHeight).toInt()
            .coerceAtMost(frameHeight - indicatorHeight)

        val lp = tpScrollIndicator.layoutParams as FrameLayout.LayoutParams
        lp.height = indicatorHeight
        lp.topMargin = indicatorTop
        lp.gravity = android.view.Gravity.END
        tpScrollIndicator.layoutParams = lp

        tpScrollIndicator.animate().alpha(1f).setDuration(100L).start()

        tpScrollIndicatorHideRunnable?.let { mainHandler.removeCallbacks(it) }
        val hideRunnable = Runnable {
            tpScrollIndicator.animate().alpha(0f).setDuration(300L).start()
        }
        tpScrollIndicatorHideRunnable = hideRunnable
        mainHandler.postDelayed(hideRunnable, 1000L)
    }

    // --- Teleprompter stop button focus ---

    private fun updateTpStopFocus(focused: Boolean) {
        tpStopBorderAnimator?.cancel()
        val density = Resources.getSystem().displayMetrics.density
        val thinStroke = (0.5f * density + 0.5f).toInt()
        val thickStroke = (2f * density + 0.5f).toInt()

        if (focused) {
            val drawable = GradientDrawable().apply {
                setColor(Lum.VOID)
                cornerRadius = 8f * density
                setStroke(thinStroke, Lum.GLOW)
            }
            tpStopButton.background = drawable
            tpStopBorderAnimator = ValueAnimator.ofInt(thinStroke, thickStroke).apply {
                duration = 150L
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { drawable.setStroke(it.animatedValue as Int, Lum.GLOW) }
                start()
            }
        } else {
            tpStopButton.setBackgroundColor(Lum.VOID)
        }
    }

    // --- Night vision camera ---

    private fun startNightVision() {
        // Use ML night vision (U-Net inference on RAW frames)
        if (nightVisionML == null) {
            nightVisionML = NightVisionML(this@MainActivity).apply {
                remoteLog = { uiLog(it) }
            }
        }
        if (nightVisionML?.isRunning != true) {
            try {
                // NightVision UNet (31 MB) lives in app-private external storage, not in
                // the APK, so the APK stays small. Deploy the file once via adb push to
                // /sdcard/Android/data/com.repository.glasses.listener/files/nightvision_unet.onnx
                // See sthal/deploy-nightvision.sh. If the file is absent we gracefully
                // fall through to the pure-Kotlin EMA pipeline.
                val modelFile = java.io.File(getExternalFilesDir(null), "nightvision_unet.onnx")
                if (!modelFile.exists() || modelFile.length() < 1_000_000L) {
                    uiLog("NightVisionML: model not found at ${modelFile.absolutePath}; falling back to EMA")
                    startNightVisionEMA()
                } else {
                    val modelBytes = modelFile.readBytes()
                    nightVisionML?.start(nightvisionPreview, modelBytes) {}
                }
            } catch (e: Exception) {
                uiLog("NightVisionML: Failed to load model, falling back to EMA: ${e.message}")
                startNightVisionEMA()
            }
        }

        // Register broadcast receivers for slider adjustments
        registerNvSliderReceivers()

        // Show sliders immediately for debugging
        ensureNvSliderViews()
        nvSliderBar?.visibility = android.view.View.VISIBLE
    }

    private fun startNightVisionEMA() {
        // Fallback: original EMA+gamma pipeline
        if (nightVisionPreview == null) {
            nightVisionPreview = NightVisionPreview(this@MainActivity).apply {
                remoteLog = { uiLog(it) }
            }
        }
        nightVisionPreview?.start(nightvisionPreview) {}
    }

    private fun stopNightVision() {
        nightVisionML?.stop()
        nightVisionPreview?.stop()
        nvSliderIndex = -1
        nvSliderLocked = false
        updateNvSliderVisuals()
        unregisterNvSliderReceivers()
    }

    private var nvExposureReceiver: android.content.BroadcastReceiver? = null
    private var nvAmplificationReceiver: android.content.BroadcastReceiver? = null

    private fun registerNvSliderReceivers() {
        nvExposureReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                val dir = intent?.getIntExtra("direction", 0) ?: return
                nightVisionML?.adjustExposure(dir * 50)
            }
        }
        nvAmplificationReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                val dir = intent?.getIntExtra("direction", 0) ?: return
                nightVisionML?.adjustAmplification(dir * 100f)
            }
        }
        registerReceiver(nvExposureReceiver, android.content.IntentFilter("NV_ADJUST_EXPOSURE"), android.content.Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(nvAmplificationReceiver, android.content.IntentFilter("NV_ADJUST_AMPLIFICATION"), android.content.Context.RECEIVER_NOT_EXPORTED)
    }

    private fun unregisterNvSliderReceivers() {
        nvExposureReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        nvAmplificationReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        nvExposureReceiver = null
        nvAmplificationReceiver = null
    }

    // --- Tab switching with pill animation ---

    private fun chatTabIndex(): Int = activeTabs.indexOf(TabId.CHAT).coerceAtLeast(0)

    // AI Chat tab inhibits screen-off only when the conversation has at least
    // one assistant reply -- an empty chat (just opened, awaiting first user
    // turn) shouldn't peg the display. Re-evaluated on tab switch and on every
    // chatAdapter mutation via the registered AdapterDataObserver.
    private fun updateChatKeepScreenOnFlag() {
        val onChatTab = currentTabId == TabId.CHAT
        val hasAiMsg = onChatTab && chatAdapter.getMessages().any {
            it.role == ChatMessage.Role.ASSISTANT
        }
        if (hasAiMsg) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** Returns the FrameLayout that wraps the tab icon for [tabId], or null
     *  if the tab has no representation (e.g. NIGHTVISION pill icon is removed). */
    private fun frameForTab(tabId: TabId): View? = when (tabId) {
        TabId.MUSIC -> tabMusic.parent as? View
        TabId.TODO -> tabTodo.parent as? View
        TabId.CHAT -> tabChat.parent as? View
        TabId.CHAT_LIST -> tabChatList.parent as? View
        TabId.TELEGRAM -> tabTelegram.parent as? View
        TabId.REID -> tabReid.parent as? View
        TabId.TRANSLATE -> translateTabFrame
        TabId.MAP -> mapTabFrame
        TabId.TELEPROMPTER -> teleprompterTabFrame
        TabId.MOUSE -> mouseTabFrame
        TabId.NIGHTVISION -> null
    }

    /**
     * Add [frame] to the tab icon row at the correct view-tree position so
     * that the visible order of children matches the order in activeTabs
     * AFTER [activeIdx] has been inserted.
     *
     * The tab icon row contains static children that are always present in
     * the view tree even when their tab is not in activeTabs (e.g. MUSIC at
     * view-idx 0 is always there but is GONE when MUSIC is inactive). So
     * raw activeIdx is NOT a valid view-tree index. We compute the position
     * by finding the predecessor tab's frame.
     *
     * IMPORTANT: must be called BEFORE inserting tabId into activeTabs at
     * activeIdx; we look at activeTabs[activeIdx - 1] to find the predecessor.
     */
    private fun insertTabFrameAt(activeIdx: Int, frame: View) {
        val tabIconsRow = (pillContainer as ViewGroup).getChildAt(1) as ViewGroup
        val viewIdx = if (activeIdx <= 0) {
            0
        } else {
            val predecessor = activeTabs.getOrNull(activeIdx - 1)
            val predView = predecessor?.let { frameForTab(it) }
            if (predView != null) tabIconsRow.indexOfChild(predView) + 1
            else tabIconsRow.childCount
        }
        tabIconsRow.addView(frame, viewIdx)
    }

    /**
     * Call after any mutation of activeTabs + the pill icon row.
     *
     *  - removedAt:      pass the index a tab USED to occupy (before removal),
     *                    so we can pick the nearest survivor when the active
     *                    tab itself was removed. Rule: snap to nearest next
     *                    index, else previous, else CHAT.
     *  - switchToAdded:  pass a TabId to force-switch to a freshly added tab
     *                    (e.g. MOUSE auto-focuses on add). Null = stay on the
     *                    current selection.
     *
     * After this call, the pill highlight is guaranteed to sit under the icon
     * matching currentTabId, regardless of where the mutation happened.
     */
    private fun afterTabsChanged(removedAt: Int? = null, switchToAdded: TabId? = null) {
        updatePillSize()
        val target: TabId = when {
            switchToAdded != null && switchToAdded in activeTabs -> switchToAdded
            currentTabId in activeTabs -> currentTabId
            removedAt != null -> activeTabs.getOrNull(removedAt)
                ?: activeTabs.getOrNull(removedAt - 1)
                ?: TabId.CHAT
            else -> activeTabs.firstOrNull() ?: TabId.CHAT
        }
        // Pill width / slot count have just changed -- defer the re-anchor
        // until the LinearLayout has had one layout pass at its new width,
        // otherwise tabWidth = pillContainer.width / tabCount is stale.
        // animate=true so the pill glides to its new slot when a tab is
        // inserted/removed in front of currentTabId (visual continuity);
        // when the index doesn't shift, Anim.translateX is a no-op.
        pillContainer.post { switchToTab(target, animate = true) }
    }

    private fun switchToTab(tabId: TabId, animate: Boolean = true) {
        val targetIdx = activeTabs.indexOf(tabId)
        if (targetIdx < 0) {
            uiLog("NAV: switchToTab IGNORED tabId=$tabId not in activeTabs=[${activeTabs.joinToString()}]")
            return
        }
        switchToTab(targetIdx, animate)
    }

    private fun switchToTab(index: Int, animate: Boolean = true, skipTabIconAnims: Boolean = false, skipPillAnim: Boolean = false) {
        val safeIndex = index.coerceIn(0, activeTabs.size - 1)
        val prevTabId = currentTabId
        val prevTab = activeTabs.indexOf(prevTabId).coerceAtLeast(0)
        val newTabId = activeTabs[safeIndex]
        uiLog("NAV: switchToTab idx=$safeIndex tabId=$newTabId prev=$prevTab($prevTabId) tabs=${activeTabs.size} animate=$animate focus=$focusState")
        currentTabId = newTabId
        updateChatKeepScreenOnFlag()
        sendBroadcast(Intent(ListenerService.ACTION_TAB_CHANGED).apply {
            setPackage(packageName)
            putExtra(ListenerService.EXTRA_TAB_ID, newTabId.name)
        })
        if (newTabId == TabId.MAP || prevTabId == TabId.MAP) {
            sendBroadcast(Intent(ListenerService.ACTION_MAP_TAB_VISIBLE).apply {
                setPackage(packageName)
                putExtra("visible", newTabId == TabId.MAP)
            })
        }
        if (newTabId != TabId.MAP) {
            mapStepStripClip.visibility = View.GONE
        } else if (renderedStepIndex >= 0 && parsedSteps != null && parsedSteps!!.length() > 0) {
            mapStepStripClip.visibility = View.VISIBLE
        }

        // Unfocus telegram chat list when switching tabs
        telegramChatListAdapter.setFocused(false)

        // Reset scroll indicator
        scrollIndicatorHideRunnable?.let { mainHandler.removeCallbacks(it) }
        scrollIndicator.animate().cancel()
        scrollIndicator.alpha = 0f

        // Cancel any in-flight content animations to prevent stale callbacks
        chatRecycler.animate().cancel()
        chatListRecycler.animate().cancel()

        // Icon tints (spring animations cancel themselves via view tags)
        tintActiveAnimator?.cancel()
        tintInactiveAnimator?.cancel()
        tintActiveAnimator = null
        tintInactiveAnimator = null

        val applyPillAndTints = {
            // Re-resolve index inside the post() to survive any concurrent mutation
            // of activeTabs between switchToTab() being called and the layout pass.
            val resolvedIndex = activeTabs.indexOf(currentTabId).coerceAtLeast(0)
            val tabCount = activeTabs.size.coerceAtLeast(1)
            // Sanity check: container width must match activeTabs.size * TAB_SLOT_DP.
            // If it doesn't, someone mutated activeTabs without calling
            // afterTabsChanged() and the pill is about to land in the wrong slot.
            val density = Resources.getSystem().displayMetrics.density
            val expectedW = (tabCount * TAB_SLOT_DP * density + 0.5f).toInt()
            if (pillContainer.width == 0 || kotlin.math.abs(pillContainer.width - expectedW) > 1) {
                uiLog("NAV: pill width mismatch -- tabs=$tabCount actualW=${pillContainer.width} expectedW=$expectedW (missing afterTabsChanged?)")
            }
            val tabWidth = pillContainer.width.toFloat() / tabCount
            val targetX = resolvedIndex * tabWidth
            // Resize highlight to match actual slot width
            val lp = pillHighlight.layoutParams
            lp.width = tabWidth.toInt()
            pillHighlight.layoutParams = lp
            if (skipPillAnim) {
                // Caller is running its own pill animation (e.g. drag-release
                // bouncy snap); leave translationX alone.
            } else if (animate) {
                Anim.translateX(pillHighlight, targetX)
            } else {
                pillHighlight.translationX = targetX
            }
            for ((i, id) in activeTabs.withIndex()) {
                val icon = when (id) {
                    TabId.MUSIC -> tabMusic
                    TabId.CHAT -> tabChat
                    TabId.CHAT_LIST -> tabChatList
                    TabId.TELEGRAM -> tabTelegram
                    TabId.REID -> tabReid
                    TabId.TODO -> tabTodo
                    TabId.NIGHTVISION -> null // night vision tab commented out
                    TabId.TRANSLATE -> translateTabIcon
                    TabId.MAP -> mapTabIcon
                    TabId.TELEPROMPTER -> teleprompterTabIcon
                    TabId.MOUSE -> mouseTabIcon
                } ?: continue
                if (animate && !skipTabIconAnims) {
                    if (i == resolvedIndex) {
                        tintActiveAnimator = Anim.tintColor(icon, Lum.SOFT, Lum.GLOW, 150L)
                        icon.animate().scaleX(TAB_ICON_SCALE_SELECTED).scaleY(TAB_ICON_SCALE_SELECTED).setDuration(150L).start()
                    } else if (id == prevTabId) {
                        tintInactiveAnimator = Anim.tintColor(icon, Lum.GLOW, Lum.SOFT, 150L)
                        icon.animate().scaleX(TAB_ICON_SCALE_DEFAULT).scaleY(TAB_ICON_SCALE_DEFAULT).setDuration(150L).start()
                    } else {
                        icon.setColorFilter(Lum.SOFT, android.graphics.PorterDuff.Mode.SRC_IN)
                        icon.scaleX = TAB_ICON_SCALE_DEFAULT
                        icon.scaleY = TAB_ICON_SCALE_DEFAULT
                    }
                } else {
                    val color = if (i == resolvedIndex) Lum.GLOW else Lum.SOFT
                    val scale = if (i == resolvedIndex) TAB_ICON_SCALE_SELECTED else TAB_ICON_SCALE_DEFAULT
                    icon.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
                    icon.scaleX = scale
                    icon.scaleY = scale
                }
            }
        }

        // Post to ensure pillContainer has laid out at its new size after tab add/remove
        pillContainer.post(applyPillAndTints)

        val tabId = newTabId

        dbg("switchToTab($safeIndex) tabId=$tabId prev=$prevTab")

        // Set all content visibility immediately (no animation chains)
        val showMap = tabId == TabId.MAP
        val showTranslate = tabId == TabId.TRANSLATE
        val showTeleprompter = tabId == TabId.TELEPROMPTER
        val showChat = tabId == TabId.CHAT
        val showChatList = tabId == TabId.CHAT_LIST
        val showReid = tabId == TabId.REID
        val showTodo = tabId == TabId.TODO
        val showNightvision = tabId == TabId.NIGHTVISION
        val showMusic = tabId == TabId.MUSIC
        val showMouse = tabId == TabId.MOUSE
        val showTelegram = tabId == TabId.TELEGRAM

        // Map uses its own layout slot (outside contentFrame)
        contentFrame.visibility = if (showMap) View.GONE else View.VISIBLE
        mapContainer.visibility = if (showMap) View.VISIBLE else View.GONE
        mapContainer.alpha = 1f
        // Show/hide map buttons when on map tab
        mapPinButton.visibility = if (showMap) View.VISIBLE else View.GONE
        mapButtonColumn.visibility = if (showMap) View.VISIBLE else View.GONE
        mapZoomSliderContainer.visibility = if (showMap) View.VISIBLE else View.GONE
        if (showMap) mapZoomSliderFill.scaleX = computeFillRatio(currentZoomStep)

        // Content views inside contentFrame
        translationContainer.visibility = if (showTranslate) View.VISIBLE else View.GONE
        translationContainer.alpha = 1f
        teleprompterContainer.visibility = if (showTeleprompter) View.VISIBLE else View.GONE
        teleprompterContainer.alpha = 1f
        // Show TP stop button only when TP tab is active and controller exists
        val showTpControls = showTeleprompter && teleprompterController != null
        tpStopButton.visibility = if (showTpControls) View.VISIBLE else View.GONE
        if (!showTeleprompter) {
            tpScrollIndicatorHideRunnable?.let { mainHandler.removeCallbacks(it) }
            tpScrollIndicator.animate().cancel()
            tpScrollIndicator.alpha = 0f
        }
        chatRecycler.visibility = if (showChat) View.VISIBLE else View.GONE
        chatRecycler.alpha = 1f
        chatEmptyHint.visibility = if (showChat && chatAdapter.itemCount == 0 && serviceState == "IDLE") View.VISIBLE else View.GONE
        chatListRecycler.visibility = if (showChatList) View.VISIBLE else View.GONE
        chatListRecycler.alpha = 1f
        reidContainer.visibility = if (showReid) View.VISIBLE else View.GONE
        if (showReid) hideLoadingSpinner()
        if (!showReid && reidRunning) {
            sendBroadcast(Intent(ListenerService.ACTION_REID_STOP).apply { setPackage(packageName) })
        }
        todoContainer.visibility = if (showTodo) View.VISIBLE else View.GONE
        if (showTodo) {
            requestTodoData()
            startTodoPollIfNeeded()
        } else {
            stopTodoPoll()
        }
        nightvisionContainer.visibility = if (showNightvision) View.VISIBLE else View.GONE
        mouseContainer.visibility = if (showMouse) View.VISIBLE else View.GONE
        musicContainer.visibility = if (showMusic) View.VISIBLE else View.GONE
        if (showMusic) {
            startMusicMarquee()
        } else {
            stopMusicMarquee()
        }

        // Telegram containers: hide all when not on telegram tab
        if (!showTelegram) {
            telegramAuthContainer.visibility = View.GONE
            telegramChatListRecycler.visibility = View.GONE
            telegramChatContainer.visibility = View.GONE
            telegramVoiceOverlay.visibility = View.GONE
            telegramSendPreview.visibility = View.GONE
            hideTelegramVoiceVisualizer()
            telegramSendRunnable?.let { mainHandler.removeCallbacks(it) }
            telegramSendRunnable = null
        }

        // Telegram subscription management
        if (showTelegram) {
            sendBroadcast(Intent(ListenerService.ACTION_TG_SUBSCRIBE).apply { setPackage(packageName) })
        } else if (prevTabId == TabId.TELEGRAM) {
            sendBroadcast(Intent(ListenerService.ACTION_TG_UNSUBSCRIBE).apply { setPackage(packageName) })
            sendBroadcast(Intent(ListenerService.ACTION_TG_CLOSE_CHAT).apply { setPackage(packageName) })
            telegramOpenChatId = ""
            telegramOpenChatTitle = ""
        }

        // Night vision camera lifecycle
        if (showNightvision) {
            if (reidRunning) {
                sendBroadcast(Intent(ListenerService.ACTION_REID_STOP).apply { setPackage(packageName) })
            }
            arCameraPreview?.stop()
            startNightVision()
        } else {
            stopNightVision()
        }

        if (showMap) {
            // Force ImageView to recalculate fitCenter matrix after layout at real bounds
            mapContentView.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    mapContentView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    val bmp = (mapContentView.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    dbg("MAP onLayout: mv=${mapContentView.width}x${mapContentView.height} bmp=${bmp?.width}x${bmp?.height} matrix=${mapContentView.imageMatrix}")
                    if (bmp != null) {
                        mapContentView.setImageDrawable(null)
                        mapContentView.setImageBitmap(bmp)
                        dbg("MAP postReset: matrix=${mapContentView.imageMatrix}")
                    }
                }
            })
        }

        if (showChatList) {
            showLoadingSpinner()
            requestChatList()
        }

        if (showTodo) {
            requestTodoData()
        }

        if (showTelegram) {
            // Show chat list and request data, but stay in TAB_NAV so user can
            // swipe to other tabs. DPAD_CENTER enters TELEGRAM_LIST_FOCUSED.
            telegramVoiceOverlay.visibility = View.GONE
            telegramSendPreview.visibility = View.GONE
            telegramChatContainer.visibility = View.GONE
            telegramAuthContainer.visibility = View.GONE
            telegramChatListRecycler.visibility = View.VISIBLE
            // Pre-load chat list data so it's ready when user enters focused mode
            showLoadingSpinner()
            telegramChatListLoaded = false
            telegramChatListRetryCount = 0
            sendTelegramChatListRequest()
        }

        loaderCtl.onTabSwitched()
    }

    // --- Music player helpers ---

    private fun sendMusicCommand(command: String) {
        uiLog("MUSIC: sendCommand=$command")
        sendBroadcast(Intent(ListenerService.ACTION_MEDIA_COMMAND).apply {
            setPackage(packageName)
            putExtra(ListenerService.EXTRA_MEDIA_COMMAND, command)
        })
    }

    private fun startMusicMarquee() {
        stopMusicMarquee()
        musicTrackName.post {
            val textWidth = musicTrackName.paint.measureText(musicTrackName.text.toString())
            val containerWidth = (musicTrackName.parent as? View)?.width?.toFloat() ?: return@post
            if (textWidth <= containerWidth) {
                musicTrackName.translationX = 0f
                return@post
            }
            val overflow = textWidth - containerWidth + 20f  // small padding
            val speed = 40f  // dp per second
            val density = resources.displayMetrics.density
            val durationMs = ((overflow / (speed * density)) * 1000).toLong().coerceAtLeast(2000L)

            musicMarqueeAnimator = ValueAnimator.ofFloat(0f, -overflow).apply {
                duration = durationMs
                interpolator = android.view.animation.LinearInterpolator()
                repeatMode = ValueAnimator.RESTART
                repeatCount = ValueAnimator.INFINITE
                startDelay = 1500L  // pause before first scroll
                addUpdateListener { musicTrackName.translationX = it.animatedValue as Float }
                start()
            }
        }
    }

    private fun stopMusicMarquee() {
        musicMarqueeAnimator?.cancel()
        musicMarqueeAnimator = null
        musicTrackName.translationX = 0f
    }

    private fun updateMusicState(trackName: String?, playing: Boolean) {
        val hasTrack = !trackName.isNullOrEmpty()
        val changed = musicTrackName.text.toString() != (trackName ?: "")
        if (hasTrack) {
            musicTrackName.text = trackName
            // Expand TextView width to full text so it doesn't ellipsize
            val fullWidth = musicTrackName.paint.measureText(trackName).toInt() + musicTrackName.paddingStart + musicTrackName.paddingEnd + 2
            musicTrackName.layoutParams = musicTrackName.layoutParams.apply { width = fullWidth }
        }
        musicPlayerContent.visibility = if (hasTrack) View.VISIBLE else View.GONE
        musicEmptyHint.visibility = if (hasTrack) View.GONE else View.VISIBLE
        musicIsPlaying = playing
        musicPlayPauseIcon.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        musicPlayPauseIcon.setColorFilter(Lum.SOFT, android.graphics.PorterDuff.Mode.SRC_IN)
        if (changed && musicContainer.visibility == View.VISIBLE && hasTrack) {
            startMusicMarquee()
        }
    }

    // --- Telegram helpers ---


    private fun showTelegramVoiceVisualizer() {
        hideTelegramVoiceVisualizer()
        val vis = com.repository.glasses.listener.ui.AudioVisualizerView(this)
        val dp = resources.displayMetrics.density
        val contentFrame = findViewById<FrameLayout>(R.id.contentFrame)
        val params = LinearLayout.LayoutParams(
            (contentFrame.width * 0.35).toInt(),
            (24 * dp).toInt()
        ).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        telegramVoiceOverlay.addView(vis, 0, params)
        telegramVoiceVisualizer = vis

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val levels = intent.getFloatArrayExtra(ListenerService.EXTRA_AUDIO_LEVELS) ?: return
                val bands = intent.getIntExtra(ListenerService.EXTRA_AUDIO_LEVELS_BANDS, levels.size)
                runOnUiThread { telegramVoiceVisualizer?.pushEnvelope(levels, bands) }
            }
        }
        registerReceiver(receiver, android.content.IntentFilter(ListenerService.ACTION_AUDIO_LEVELS))
        telegramVoiceLevelsReceiver = receiver
    }

    private fun hideTelegramVoiceVisualizer() {
        telegramVoiceLevelsReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            telegramVoiceLevelsReceiver = null
        }
        telegramVoiceVisualizer?.let {
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            telegramVoiceVisualizer = null
        }
    }

    private fun telegramEnterChatList() {
        android.util.Log.d("TG_DEBUG", "telegramEnterChatList() called, sending REQUEST_TG_CHAT_LIST")
        telegramAuthContainer.visibility = View.GONE
        telegramChatContainer.visibility = View.GONE
        telegramChatListRecycler.visibility = View.VISIBLE
        telegramChatListAdapter.selectPosition(0)
        telegramChatListRecycler.scrollToPosition(0)
        focusState = FocusState.TELEGRAM_LIST_FOCUSED
        updateFocusVisual(focusState)

        showLoadingSpinner()
        telegramChatListLoaded = false
        telegramChatListRetryCount = 0
        sendTelegramChatListRequest()
    }

    private fun sendTelegramChatListRequest() {
        telegramChatListRetry?.let { mainHandler.removeCallbacks(it) }
        sendBroadcast(Intent(ListenerService.ACTION_REQUEST_TG_CHAT_LIST).apply {
            setPackage(packageName)
            putExtra(ListenerService.EXTRA_TG_LIMIT, 20)
        })
        // Retry if no response in 5s (up to 3 retries for WS drops).
        // Allow retries in both TAB_NAV (pre-load) and TELEGRAM_LIST_FOCUSED (entered).
        telegramChatListRetry = Runnable {
            if (!telegramChatListLoaded && telegramChatListRetryCount < 3) {
                telegramChatListRetryCount++
                android.util.Log.d("TG_DEBUG", "Chat list retry #$telegramChatListRetryCount")
                sendTelegramChatListRequest()
            } else if (!telegramChatListLoaded) {
                // Retries exhausted -- hide spinner so user isn't stuck
                hideLoadingSpinner()
                android.util.Log.d("TG_DEBUG", "Chat list retries exhausted")
            }
        }
        mainHandler.postDelayed(telegramChatListRetry!!, 5000)
    }

    private fun sendTelegramMessagesRequest() {
        try {
            android.util.Log.e("TG_CRASH", "sendTelegramMessagesRequest chatId=$telegramOpenChatId topicId=$telegramOpenTopicId retryCount=$telegramMessagesRetryCount")
            telegramMessagesRetry?.let { mainHandler.removeCallbacks(it) }
            sendBroadcast(Intent(ListenerService.ACTION_REQUEST_TG_MESSAGES).apply {
                setPackage(packageName)
                putExtra(ListenerService.EXTRA_TG_CHAT_ID, telegramOpenChatId)
                putExtra(ListenerService.EXTRA_TG_LIMIT, 30)
                if (telegramOpenTopicId > 0) putExtra(ListenerService.EXTRA_TG_TOPIC_ID, telegramOpenTopicId)
            })
            telegramMessagesRetry = Runnable {
                try {
                    if (!telegramMessagesLoaded && focusState == FocusState.TELEGRAM_CHAT_FOCUSED && telegramMessagesRetryCount < 3) {
                        telegramMessagesRetryCount++
                        android.util.Log.e("TG_CRASH", "Messages retry #$telegramMessagesRetryCount")
                        telegramRecordHint.text = "Loading messages... (retry ${telegramMessagesRetryCount})"
                        sendTelegramMessagesRequest()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("TG_CRASH", "retry EXCEPTION", e)
                    GlassesListenerApp.writeCrashLog(this@MainActivity, "retry: ${e.stackTraceToString()}")
                }
            }
            mainHandler.postDelayed(telegramMessagesRetry!!, 5000)
        } catch (e: Exception) {
            android.util.Log.e("TG_CRASH", "sendTelegramMessagesRequest EXCEPTION", e)
            GlassesListenerApp.writeCrashLog(this, "sendTelegramMessagesRequest: ${e.stackTraceToString()}")
        }
    }

    private fun loadOlderTelegramMessages() {
        val oldestId = telegramChatAdapter.getOldestMessageId()
        if (oldestId <= 0) return
        telegramLoadingOlderMessages = true
        val intent = Intent(ListenerService.ACTION_REQUEST_TG_MESSAGES).apply {
            setPackage(packageName)
            putExtra(ListenerService.EXTRA_TG_CHAT_ID, telegramOpenChatId)
            putExtra(ListenerService.EXTRA_TG_LIMIT, 30)
            putExtra(ListenerService.EXTRA_TG_OFFSET_ID, oldestId)
            if (telegramOpenTopicId > 0) putExtra(ListenerService.EXTRA_TG_TOPIC_ID, telegramOpenTopicId)
        }
        sendBroadcast(intent)
    }

    private fun telegramOpenTopic(topic: com.repository.glasses.listener.ui.TelegramTopic) {
        try {
            android.util.Log.e("TG_CRASH", "telegramOpenTopic START id=${topic.id} title=${topic.title}")
            telegramOpenTopicId = topic.id
            telegramTopicsRecycler.visibility = View.GONE
            telegramChatContainer.visibility = View.VISIBLE
            telegramChatRecycler.visibility = View.VISIBLE
            telegramVoiceOverlay.visibility = View.GONE
            telegramSendPreview.visibility = View.GONE
            telegramRecordHint.visibility = View.GONE
            focusState = FocusState.TELEGRAM_CHAT_FOCUSED
            android.util.Log.e("TG_CRASH", "telegramOpenTopic before submitList")
            telegramChatAdapter.submitList(emptyList(), "group")
            android.util.Log.e("TG_CRASH", "telegramOpenTopic after submitList")

            telegramChatHeaderName.text = topic.title
            telegramChatHeaderStatus.text = telegramOpenChatTitle
            telegramChatHeaderStatus.setTextColor(Lum.DIM)
            telegramChatHeaderAvatar.visibility = View.GONE
            telegramChatHeader.visibility = View.VISIBLE
            telegramChatRecycler.setPadding(0, 28.dpToPx(), 0, 36.dpToPx())

            telegramRecordHint.text = "Loading messages..."
            telegramRecordHint.setTextColor(Lum.DIM)
            telegramRecordHint.visibility = View.VISIBLE

            telegramLoadingOlderMessages = false
            telegramNoMoreOlderMessages = false
            telegramMessagesLoaded = false
            telegramMessagesRetryCount = 0
            android.util.Log.e("TG_CRASH", "telegramOpenTopic before sendRequest")
            sendTelegramMessagesRequest()
            android.util.Log.e("TG_CRASH", "telegramOpenTopic DONE")
        } catch (e: Exception) {
            android.util.Log.e("TG_CRASH", "telegramOpenTopic EXCEPTION", e)
            GlassesListenerApp.writeCrashLog(this, "telegramOpenTopic: ${e.stackTraceToString()}")
        }
    }

    private fun telegramOpenChat(chat: TelegramChat) {
        android.util.Log.d("TG_DEBUG", "telegramOpenChat: chatId=${chat.chatId} title=${chat.title} type=${chat.chatType} forum=${chat.isForum}")
        telegramOpenChatId = chat.chatId
        telegramOpenChatTitle = chat.title
        telegramOpenChatType = chat.chatType
        telegramOpenChatIsForum = chat.isForum
        telegramOpenTopicId = 0
        telegramChatListRecycler.visibility = View.GONE
        hideLoadingSpinner()
        telegramTopicsRecycler.visibility = View.GONE

        // Forum groups: show topics list first
        if (chat.isForum) {
            telegramChatContainer.visibility = View.GONE
            telegramTopicsRecycler.visibility = View.VISIBLE
            telegramTopicListAdapter.submitList(emptyList())
            focusState = FocusState.TELEGRAM_TOPICS_FOCUSED
            updateFocusVisual(focusState)
            // Request topics from backend
            sendBroadcast(Intent(ListenerService.ACTION_REQUEST_TG_TOPICS).apply {
                setPackage(packageName)
                putExtra(ListenerService.EXTRA_TG_CHAT_ID, chat.chatId)
            })
            sendBroadcast(Intent(ListenerService.ACTION_TG_OPEN_CHAT).apply {
                setPackage(packageName)
                putExtra(ListenerService.EXTRA_TG_CHAT_ID, chat.chatId)
                putExtra(ListenerService.EXTRA_TG_CHAT_TITLE, chat.title)
            })
            return
        }

        telegramChatContainer.visibility = View.VISIBLE
        telegramChatRecycler.visibility = View.VISIBLE
        telegramVoiceOverlay.visibility = View.GONE
        telegramSendPreview.visibility = View.GONE
        telegramRecordHint.visibility = View.GONE
        focusState = FocusState.TELEGRAM_CHAT_FOCUSED
        telegramChatAdapter.submitList(emptyList(), telegramOpenChatType)

        // Show chat header with name, presence, and avatar for DMs
        telegramChatHeaderName.text = chat.title
        // DM avatar
        if (chat.chatType == "user" && chat.avatar != null) {
            try {
                val bytes = android.util.Base64.decode(chat.avatar, android.util.Base64.DEFAULT)
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    telegramChatHeaderAvatar.setImageBitmap(bmp)
                    telegramChatHeaderAvatar.visibility = View.VISIBLE
                } else {
                    telegramChatHeaderAvatar.visibility = View.GONE
                }
            } catch (_: Exception) { telegramChatHeaderAvatar.visibility = View.GONE }
        } else {
            telegramChatHeaderAvatar.visibility = View.GONE
        }
        if (chat.chatType == "user") {
            if (chat.isOnline) {
                telegramChatHeaderStatus.text = "online"
                telegramChatHeaderStatus.setTextColor(Lum.GLOW)
            } else if (chat.lastSeen != null) {
                val statusText = when {
                    chat.lastSeen == "recently" -> "last seen recently"
                    chat.lastSeen == "within a week" -> "last seen within a week"
                    chat.lastSeen == "within a month" -> "last seen within a month"
                    chat.lastSeen.contains("T") && chat.lastSeen.length >= 16 -> try {
                        val utcFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                        val localFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                        "last seen ${localFormat.format(utcFormat.parse(chat.lastSeen.substring(0, 19))!!)}"
                    } catch (_: Exception) { "" }
                    else -> ""
                }
                telegramChatHeaderStatus.text = statusText
                telegramChatHeaderStatus.setTextColor(Lum.DIM)
            } else {
                telegramChatHeaderStatus.text = ""
            }
        } else {
            telegramChatHeaderStatus.text = chat.chatType
            telegramChatHeaderStatus.setTextColor(Lum.DIM)
        }
        telegramChatHeader.visibility = View.VISIBLE
        telegramChatRecycler.setPadding(0, 28.dpToPx(), 0, 36.dpToPx())

        // Show loading text
        telegramRecordHint.text = "Loading messages..."
        telegramRecordHint.setTextColor(Lum.DIM)
        telegramRecordHint.visibility = View.VISIBLE

        telegramLoadingOlderMessages = false
        telegramNoMoreOlderMessages = false
        telegramMessagesLoaded = false
        telegramMessagesRetryCount = 0
        sendTelegramMessagesRequest()
        sendBroadcast(Intent(ListenerService.ACTION_TG_OPEN_CHAT).apply {
            setPackage(packageName)
            putExtra(ListenerService.EXTRA_TG_CHAT_ID, chat.chatId)
            putExtra(ListenerService.EXTRA_TG_CHAT_TITLE, chat.title)
        })
    }

    private fun telegramCloseChat() {
        telegramOpenChatId = ""
        telegramOpenChatTitle = ""
        telegramOpenTopicId = 0
        telegramOpenChatIsForum = false
        telegramChatContainer.visibility = View.GONE
        telegramChatHeader.visibility = View.GONE
        telegramChatHeaderAvatar.visibility = View.GONE
        telegramTopicsRecycler.visibility = View.GONE
        telegramVoiceOverlay.visibility = View.GONE
        telegramSendPreview.visibility = View.GONE
        hideTelegramVoiceVisualizer()
        telegramSendRunnable?.let { mainHandler.removeCallbacks(it) }
        telegramSendRunnable = null
        sendBroadcast(Intent(ListenerService.ACTION_TG_CLOSE_CHAT).apply { setPackage(packageName) })
        telegramEnterChatList()
    }

    private fun telegramStartVoice() {
        // Clean up any stale voice session before starting fresh
        sendBroadcast(Intent(ListenerService.ACTION_TG_VOICE_STOP).apply { setPackage(packageName) })
        telegramRecordHint.visibility = View.GONE
        telegramVoiceOverlay.visibility = View.VISIBLE
        telegramVoicePreview.text = ""
        focusState = FocusState.TELEGRAM_RECORDING
        showTelegramVoiceVisualizer()
        sendBroadcast(Intent(ListenerService.ACTION_TG_VOICE_START).apply {
            setPackage(packageName)
            putExtra(ListenerService.EXTRA_TG_CHAT_ID, telegramOpenChatId)
        })
    }

    private fun telegramStopVoice() {
        hideTelegramVoiceVisualizer()
        sendBroadcast(Intent(ListenerService.ACTION_TG_VOICE_STOP).apply { setPackage(packageName) })
        // Stay in TELEGRAM_RECORDING until partial/final text arrives or timeout
    }

    private fun telegramShowSendPreview(text: String) {
        // Ensure voice session is stopped before showing preview
        hideTelegramVoiceVisualizer()
        sendBroadcast(Intent(ListenerService.ACTION_TG_VOICE_STOP).apply { setPackage(packageName) })
        if (text.isBlank()) {
            // Nothing to send, go back to chat
            telegramVoiceOverlay.visibility = View.GONE
            telegramSendPreview.visibility = View.GONE
            telegramRecordHint.visibility = View.VISIBLE
            focusState = FocusState.TELEGRAM_CHAT_FOCUSED
            return
        }
        telegramVoiceOverlay.visibility = View.GONE
        telegramSendPreview.visibility = View.VISIBLE
        telegramSendText.text = text
        focusState = FocusState.TELEGRAM_PREVIEW
        // Auto-send countdown (5 seconds)
        var countdown = 5
        telegramSendCountdown.text = "${countdown}s"
        telegramSendRunnable?.let { mainHandler.removeCallbacks(it) }
        val countdownRunnable = object : Runnable {
            override fun run() {
                countdown--
                if (countdown <= 0) {
                    telegramConfirmSend()
                } else {
                    telegramSendCountdown.text = "${countdown}s"
                    telegramSendRunnable = this
                    mainHandler.postDelayed(this, 1000L)
                }
            }
        }
        telegramSendRunnable = countdownRunnable
        mainHandler.postDelayed(countdownRunnable, 1000L)
    }

    /** Text of the last optimistic send, used to dedup tgNewMessage echoes */
    private var telegramPendingSendText: String? = null

    private fun telegramConfirmSend() {
        telegramSendRunnable?.let { mainHandler.removeCallbacks(it) }
        telegramSendRunnable = null
        val text = telegramSendText.text.toString()
        if (text.isNotBlank() && telegramOpenChatId.isNotEmpty()) {
            // Send exactly once - never retry sends
            telegramPendingSendText = text
            sendBroadcast(Intent(ListenerService.ACTION_TG_SEND_MSG).apply {
                setPackage(packageName)
                putExtra(ListenerService.EXTRA_TG_CHAT_ID, telegramOpenChatId)
                putExtra(ListenerService.EXTRA_TG_TEXT, text)
                if (telegramOpenTopicId > 0) putExtra(ListenerService.EXTRA_TG_TOPIC_ID, telegramOpenTopicId)
            })
            // Optimistic: add to adapter with id=-1 (pending marker), status=sending
            telegramChatAdapter.addMessage(TelegramMessage(
                id = -1,
                sender = "",
                text = text,
                date = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).format(java.util.Date()),
                isOutgoing = true,
                chatId = telegramOpenChatId,
                sendStatus = "sending"
            ))
        }
        telegramSendPreview.visibility = View.GONE
        telegramRecordHint.visibility = View.VISIBLE
        focusState = FocusState.TELEGRAM_CHAT_FOCUSED
    }

    private fun telegramCancelSend() {
        telegramSendRunnable?.let { mainHandler.removeCallbacks(it) }
        telegramSendRunnable = null
        telegramSendPreview.visibility = View.GONE
        telegramRecordHint.visibility = View.VISIBLE
        focusState = FocusState.TELEGRAM_CHAT_FOCUSED
    }

    // --- Notification hold-to-reply ---

    /** One-shot: tell the overlay to self-animate the hold fill over [durationMs]. */
    private fun sendNotifHoldStart(durationMs: Long) {
        sendBroadcast(Intent(ListenerService.ACTION_NOTIF_HOLD_PROGRESS).apply {
            setPackage(packageName)
            putExtra(ListenerService.EXTRA_HOLD_DURATION, durationMs)
        })
    }

    private fun sendNotifHoldFreeze(freeze: Boolean) {
        sendBroadcast(Intent(ListenerService.ACTION_NOTIF_HOLD_FREEZE).apply {
            setPackage(packageName)
            putExtra(ListenerService.EXTRA_FREEZE, freeze)
        })
    }

    /**
     * NUMPAD_3 (press reached 500ms) on a repliable notification: arm the reply.
     * Starts the progress animation; freezes the overlay so it cannot dismiss
     * mid-arm. Re-entrancy guarded so a second NUMPAD_3 does not start two ticks.
     */
    private fun startReplyArm() {
        if (replyArming) return
        uiLog("[NREPLY] startReplyArm: pendingNotifId=${pendingNotifId?.take(12)} focus=$focusState")
        replyArming = true
        sendNotifHoldFreeze(true)
        // One-shot: overlay self-animates the fill over REPLY_ARM_MS. We only
        // schedule a single commit callback at the end (no 16ms ticker).
        sendNotifHoldStart(REPLY_ARM_MS)
        replyArmHandler.removeCallbacks(replyArmRunnable)
        replyArmHandler.postDelayed(replyArmRunnable, REPLY_ARM_MS)
    }

    /** Released (NUMPAD_2) before the bar filled, or notification gone: cancel. */
    private fun cancelReplyArm() {
        if (!replyArming) return
        uiLog("[NREPLY] cancelReplyArm")
        replyArming = false
        replyArmHandler.removeCallbacks(replyArmRunnable)
        sendNotifHoldFreeze(false)
    }

    /**
     * Progress bar reached 100% with no release: commit into LISTENING. The
     * recording runs while the finger stays down; the next finger-release
     * (NUMPAD_2) is the SEND trigger (handled by the NOTIFICATION_REPLY branch).
     */
    private fun commitReplyArm() {
        if (!replyArming) return
        uiLog("[NREPLY] commitReplyArm: bar full -> beginNotifReply (finger may already be released!)")
        replyArming = false
        replyArmHandler.removeCallbacks(replyArmRunnable)
        beginNotifReply()
    }

    /**
     * Hold reached 100%: enter the reply. All reply visuals live inside the
     * notification overlay rectangle (service-owned); MainActivity does NOT show
     * its own telegramVoiceOverlay/visualizer/preview here. We only set the focus
     * state (needed for input routing) and tell the service to start the phone
     * transcriber.
     */
    private fun beginNotifReply() {
        val notifId = pendingNotifId ?: return
        activeReplyNotifId = notifId
        notifReplyPrevFocus = focusState
        focusState = FocusState.NOTIFICATION_REPLY
        sendBroadcast(Intent(ListenerService.ACTION_NOTIF_REPLY_START).apply {
            setPackage(packageName)
            putExtra(ListenerService.EXTRA_NOTIF_ID, notifId)
        })
        uiLog("NOTIF: reply listening started for ${notifId.take(12)}")
    }

    /**
     * Final transcript arrived from the phone's VAD end-of-speech. Open the 3s
     * SENDING window: the overlay (service-driven) shows the final transcript and
     * a "DOUBLE-TAP TO CANCEL" countdown. If the window elapses without a
     * double-tap, commitReplySend fires the real RemoteInput.
     */
    private fun beginReplySendWindow(finalText: String) {
        val notifId = activeReplyNotifId ?: return
        uiLog("[NREPLY] beginReplySendWindow: ${notifId.take(12)} text='${finalText.take(40)}'")
        pendingReplyText = finalText
        replySendPending = true
        lastReplyCancelTapMs = 0L
        replySendHandler.removeCallbacks(replySendRunnable)
        replySendHandler.postDelayed(replySendRunnable, REPLY_SEND_WINDOW_MS)
    }

    /** The 3s cancel window elapsed without a double-tap: fire the real send. */
    private fun commitReplySend() {
        if (!replySendPending) return
        val notifId = activeReplyNotifId ?: return
        val text = pendingReplyText ?: ""
        uiLog("[NREPLY] commitReplySend: ${notifId.take(12)} -> ${text.take(40)}")
        replySendPending = false
        pendingReplyText = null
        sendBroadcast(Intent(ListenerService.ACTION_NOTIF_REPLY_SEND).apply {
            setPackage(packageName)
            putExtra(ListenerService.EXTRA_NOTIF_ID, notifId)
            putExtra(ListenerService.EXTRA_REPLY_TEXT, text)
        })
        endNotifReply()
    }

    /** User cancelled the reply (swipe / BACK, or notification disappeared). */
    private fun notifReplyCancel() {
        val notifId = activeReplyNotifId
        if (notifId != null) {
            sendBroadcast(Intent(ListenerService.ACTION_NOTIF_REPLY_CANCEL).apply {
                setPackage(packageName)
                putExtra(ListenerService.EXTRA_NOTIF_ID, notifId)
            })
        }
        endNotifReply()
    }

    /** The underlying notification vanished while replying: tear down silently. */
    private fun abortNotifReply() {
        // No-op when no reply is active: safe to call from interrupt paths
        // (e.g. incoming call) without checking focusState first.
        if (activeReplyNotifId == null && focusState != FocusState.NOTIFICATION_REPLY) return
        val notifId = activeReplyNotifId
        if (notifId != null) {
            sendBroadcast(Intent(ListenerService.ACTION_NOTIF_REPLY_CANCEL).apply {
                setPackage(packageName)
                putExtra(ListenerService.EXTRA_NOTIF_ID, notifId)
            })
        }
        endNotifReply()
    }

    private fun endNotifReply() {
        // IMPORTANT: do NOT clear pendingNotifId / notificationRepliable here.
        // Those represent "which repliable notification is currently on screen"
        // and are owned exclusively by the SHOWN/HIDDEN lifecycle. The overlay is
        // still visibly showing the notification while a reply finishes (SENT
        // window) or after a cancel (it stays up until auto-dismiss), so clearing
        // them here left the visible notification with no pendingNotifId -- the
        // next hold then couldn't find it and AI chat hijacked the long-press.
        // notificationHiddenReceiver clears them when the overlay actually goes.
        uiLog("[NREPLY] endNotifReply: ended=${activeReplyNotifId?.take(12)} pendingNotifId(kept)=${pendingNotifId?.take(12)} restoreFocus=$notifReplyPrevFocus")
        // Clear any lingering arm state so leaving a reply can never leave a
        // dangling tick armed for the next unrelated tap.
        replyArming = false
        replyArmHandler.removeCallbacks(replyArmRunnable)
        // Clear the post-transcript send window too.
        replySendPending = false
        pendingReplyText = null
        lastReplyCancelTapMs = 0L
        replySendHandler.removeCallbacks(replySendRunnable)
        val restore = notifReplyPrevFocus ?: FocusState.TAB_NAV
        notifReplyPrevFocus = null
        activeReplyNotifId = null
        focusState = restore
        updateFocusVisual(focusState)
    }

    // --- Todo UI builder ---

    private fun buildTodoUI() {
        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Lum.VOID)
        }

        // Sub-tab container (FrameLayout to allow capsule overlay behind labels).
        // No outline -- the moving circular highlight is the only selection cue,
        // matching the bottom tab bar. clipChildren=false lets a fast move
        // stretch the blob past its slot into a streak.
        val pillWidthDp = TodoSubTab.entries.size * 27
        val pillWrapper = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                pillWidthDp.dpToPx(),
                22.dpToPx()
            ).apply {
                topMargin = 2.dpToPx()
                bottomMargin = 2.dpToPx()
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            clipChildren = false
            setBackgroundColor(Lum.VOID)
        }

        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // Transparent: the icon row sits ON TOP of the highlight circle, so
            // an opaque background would hide the moving blob behind it.
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            clipChildren = false
            setPadding(0, 0, 0, 0)
        }
        todoSubTabPill = pill

        // Moving circular selection highlight (flattens with speed). Hidden
        // until the sub-tab row becomes the active nav target -- it is the
        // counterpart to the bottom bar's circle, never lit at the same time.
        val capsule = TabHighlightView(this).apply {
            layoutParams = FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Lum.VOID)
            visibility = View.INVISIBLE
        }
        todoSubTabCapsule = capsule
        // leftMargin is ALWAYS 0; position is driven purely by translationX,
        // exactly like the bottom tab pill (pillHighlight).

        for (i in TodoSubTab.entries.indices) {
            val slot = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            }
            val icon = ImageView(this).apply {
                setImageResource(TodoSubTab.entries[i].iconRes)
                layoutParams = FrameLayout.LayoutParams(13.dpToPx(), 13.dpToPx()).apply {
                    gravity = android.view.Gravity.CENTER
                }
                // Initial/idle tint: uniform GHOST for every icon. The selected icon only
                // brightens to GLOW once the sub-tab row is the active nav target
                // (updateTodoSubTabLabels handles that). No phantom selection at rest.
                setColorFilter(Lum.GHOST, android.graphics.PorterDuff.Mode.SRC_IN)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
            todoSubTabLabels[i] = icon
            slot.addView(icon)
            pill.addView(slot)
        }

        // Layer: capsule behind, pill (with labels) on top
        pillWrapper.addView(capsule)
        pillWrapper.addView(pill)

        // Position capsule after layout: width = one slot, leftMargin = 0,
        // position via translationX = ordinal * slotW (mirrors pillHighlight).
        pillWrapper.post {
            val tabCount = TodoSubTab.entries.size
            val slotW = pill.width.toFloat() / tabCount
            if (slotW <= 0f) return@post
            (capsule.layoutParams as FrameLayout.LayoutParams).apply {
                width = slotW.toInt()
                leftMargin = 0
            }
            capsule.translationX = todoSubTab.ordinal * slotW
            capsule.requestLayout()
        }

        // Content area (FrameLayout to overlay RecyclerViews and empty text)
        val contentArea = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setBackgroundColor(Lum.VOID)
        }

        // Primary checklist RecyclerView
        val checklistRecycler = RecyclerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Lum.VOID)
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isFocusable = false
            isFocusableInTouchMode = false
            defaultFocusHighlightEnabled = false
        }
        todoChecklistAdapter = TodoChecklistAdapter()
        checklistRecycler.layoutManager = LinearLayoutManager(this)
        checklistRecycler.adapter = todoChecklistAdapter
        // Animate move/remove so a completed task slides to the end (drag-drop style,
        // other rows shifting to react) and the list collapses smoothly on removal.
        // Change + add animations are disabled: the strike rebind and the new-data
        // reload must NOT cross-fade (would flicker on the waveguide). Only move and
        // remove are animated, which is exactly the completion choreography.
        checklistRecycler.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
            supportsChangeAnimations = false
            moveDuration = 320L
            removeDuration = 220L
            addDuration = 0L
            changeDuration = 0L
        }
        todoChecklistRecycler = checklistRecycler
        contentArea.addView(checklistRecycler)

        // Alarm RecyclerView (initially hidden)
        val alarmRecycler = RecyclerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Lum.VOID)
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isFocusable = false
            isFocusableInTouchMode = false
            defaultFocusHighlightEnabled = false
            visibility = View.GONE
        }
        todoAlarmAdapter = AlarmDisplayAdapter()
        alarmRecycler.layoutManager = LinearLayoutManager(this)
        alarmRecycler.adapter = todoAlarmAdapter
        alarmRecycler.itemAnimator = null
        todoAlarmRecycler = alarmRecycler
        contentArea.addView(alarmRecycler)

        // Job RecyclerView (initially hidden)
        val jobRecycler = RecyclerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Lum.VOID)
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isFocusable = false
            isFocusableInTouchMode = false
            defaultFocusHighlightEnabled = false
            visibility = View.GONE
        }
        todoJobAdapter = JobDisplayAdapter()
        jobRecycler.layoutManager = LinearLayoutManager(this)
        jobRecycler.adapter = todoJobAdapter
        jobRecycler.itemAnimator = null
        todoJobRecycler = jobRecycler
        contentArea.addView(jobRecycler)

        // Saved messages RecyclerView (initially hidden)
        val savedRecycler = RecyclerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Lum.VOID)
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isFocusable = false
            isFocusableInTouchMode = false
            defaultFocusHighlightEnabled = false
            visibility = View.GONE
        }
        todoSavedAdapter = TelegramSavedAdapter()
        savedRecycler.layoutManager = LinearLayoutManager(this)
        savedRecycler.adapter = todoSavedAdapter
        savedRecycler.itemAnimator = null
        todoSavedRecycler = savedRecycler
        contentArea.addView(savedRecycler)

        // Empty/error state text (centered, initially GONE)
        val emptyText = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER
            }
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Lum.DIM)
            setBackgroundColor(Lum.VOID)
            visibility = View.GONE
        }
        todoEmptyText = emptyText
        contentArea.addView(emptyText)

        // Overlay cursor for the TASKS list -- a single bright circle that springs vertically
        // to the selected row's dot with a bouncy/fluid snap, squeezing horizontally while it
        // travels (vertical analog of the bottom tab pill). Added last so it draws on top. No
        // solid background: it self-draws only the oval, so it never paints a black box over text.
        // The view is WIDE/TALL enough to give the squeeze room without clipping; the resting
        // blob is rest-scaled down to a small dot inside it.
        // +30% over the previous 8dp dot column width -> larger resting circle (8 * 1.3 ~ 10).
        val cursorWidthPx = 10.dpToPx()   // resting dot diameter ~ min(w,h)*restScale
        val cursorHeightPx = 32.dpToPx()  // vertical headroom for the elongation streak
        val cursorBlob = VerticalHighlightView(this).apply {
            visibility = View.INVISIBLE
            // Dot column center X = row paddingLeft(6dp) + dotWidth/2(2dp) = 8dp from recycler left.
            translationX = 8.dpToPx().toFloat() - cursorWidthPx / 2f
        }
        contentArea.addView(cursorBlob, FrameLayout.LayoutParams(cursorWidthPx, cursorHeightPx))
        todoChecklistCursor = cursorBlob

        // Keep the cursor glued to the selected row's dot while the list scrolls.
        checklistRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                updateTodoChecklistCursor(animate = false)
            }
        })
        // Reposition on any selection change (covers code paths that don't call it explicitly).
        todoChecklistAdapter.onSelectionChanged = {
            checklistRecycler.post { updateTodoChecklistCursor(animate = true) }
        }

        root.addView(contentArea)

        // Sub-tab pill at bottom (right above main tab bar)
        root.addView(pillWrapper)

        todoContainer.addView(root)

        // Message detail overlay (full-screen, scrollable, initially hidden)
        val overlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Lum.VOID)
            visibility = View.GONE
        }
        val scrollView = android.widget.ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFocusable = false
            isFocusableInTouchMode = false
            defaultFocusHighlightEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Lum.VOID)
        }
        val msgText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Lum.MID)
            setBackgroundColor(Lum.VOID)
            setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
        }
        scrollView.addView(msgText)
        overlay.addView(scrollView)
        todoContainer.addView(overlay)
        todoMessageOverlay = overlay
        todoMessageScrollView = scrollView
        todoMessageTextView = msgText
    }

    private fun showMessageDetail(text: String) {
        todoMessageTextView?.text = text
        todoMessageScrollView?.scrollTo(0, 0)
        todoMessageOverlay?.let {
            it.visibility = View.VISIBLE
            it.alpha = 0f
            Anim.fadeIn(it, 200L)
        }
        todoMessageDetailShowing = true
    }

    private fun hideMessageDetail() {
        todoMessageOverlay?.let { Anim.fadeOut(it, 150L) }
        todoMessageDetailShowing = false
    }

    // --- Todo data ---

    private var todoSavedHasData = false
    private val todoPollRunnable = object : Runnable {
        override fun run() {
            if (!todoSavedHasData && todoSubTab == TodoSubTab.SAVED) {
                requestTodoData()
            }
            if (!todoSavedHasData) {
                mainHandler.postDelayed(this, 15_000L)
            }
        }
    }

    private fun getActiveTodoAdapter(): SelectableAdapter = when (todoSubTab) {
        TodoSubTab.TASKS -> todoChecklistAdapter
        TodoSubTab.SAVED -> todoSavedAdapter
        TodoSubTab.JOBS -> todoJobAdapter
        TodoSubTab.ALARMS -> todoAlarmAdapter
    }

    private fun getActiveTodoRecycler(): RecyclerView? = when (todoSubTab) {
        TodoSubTab.TASKS -> todoChecklistRecycler
        TodoSubTab.SAVED -> todoSavedRecycler
        TodoSubTab.JOBS -> todoJobRecycler
        TodoSubTab.ALARMS -> todoAlarmRecycler
    }

    private fun getTodoRecyclerFor(tab: TodoSubTab): RecyclerView? = when (tab) {
        TodoSubTab.TASKS -> todoChecklistRecycler
        TodoSubTab.SAVED -> todoSavedRecycler
        TodoSubTab.JOBS -> todoJobRecycler
        TodoSubTab.ALARMS -> todoAlarmRecycler
    }

    /**
     * Reposition the TASKS overlay cursor so it sits on the selected row's dot.
     * Only visible for the TASKS sub-tab while content is focused (level 1).
     */
    private fun updateTodoChecklistCursor(animate: Boolean) {
        val cursor = todoChecklistCursor ?: return
        val recycler = todoChecklistRecycler ?: run {
            cursor.visibility = View.INVISIBLE
            return
        }
        val sel = todoChecklistAdapter.selectedPosition
        val show = todoSubTab == TodoSubTab.TASKS &&
            focusState == FocusState.TODO_FOCUSED &&
            todoFocusLevel == 1 &&
            sel >= 0
        if (!show) {
            cursor.visibility = View.INVISIBLE
            return
        }

        val vh = recycler.findViewHolderForAdapterPosition(sel)
        if (vh == null) {
            // Row not laid out yet (off-screen). The scroll listener will reposition once
            // the layout pass settles it.
            cursor.visibility = View.INVISIBLE
            return
        }

        val cursorHeight = cursor.height.toFloat().takeIf { it > 0f } ?: 28.dpToPx().toFloat()
        // Row is gravity CENTER_VERTICAL with a vertically-centered dot, so the row's
        // vertical center == the dot center. The oval is centered in the (tall) cursor
        // view, so center the view on the dot.
        val dotCenterY = vh.itemView.top + vh.itemView.height / 2f
        val targetY = dotCenterY - cursorHeight / 2f

        val wasVisible = cursor.visibility == View.VISIBLE
        cursor.visibility = View.VISIBLE
        if (animate && wasVisible) {
            // Bouncy/fluid snap matching the bottom tab pill's drag-release spring:
            // MEDIUM stiffness + LOW_BOUNCY damping. The view squeezes horizontally as it
            // travels (driven by its own translationY sampling) and relaxes on settle.
            todoChecklistCursorSpring?.cancel()
            todoChecklistCursorSpring = androidx.dynamicanimation.animation.SpringAnimation(
                cursor,
                androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_Y,
                targetY
            ).apply {
                spring = androidx.dynamicanimation.animation.SpringForce(targetY).apply {
                    stiffness = androidx.dynamicanimation.animation.SpringForce.STIFFNESS_MEDIUM
                    dampingRatio = androidx.dynamicanimation.animation.SpringForce.DAMPING_RATIO_LOW_BOUNCY
                }
                start()
            }
        } else {
            // First appear (or scroll-follow): teleport without registering false speed.
            todoChecklistCursorSpring?.cancel()
            cursor.translationY = targetY
            cursor.snapToRest()
        }
    }

    private fun requestTodoData() {
        if (todoSubTab == TodoSubTab.SAVED) {
            requestSavedFirstPage()
            return
        }
        sendBroadcast(Intent(todoSubTab.requestAction).apply { setPackage(packageName) })
    }

    /**
     * Saved sub-tab first-page load via the paginated telegram_messages path: chatId="me",
     * limit=20, offsetId=0. Resets pagination state and shows the loader spinner.
     */
    private fun requestSavedFirstPage() {
        savedLoadingOlder = false
        savedNoMoreOlder = false
        savedRequestInFlight = true
        loaderCtl.show(TabId.TODO)
        sendBroadcast(Intent(ListenerService.ACTION_REQUEST_TG_MESSAGES).apply {
            setPackage(packageName)
            putExtra(ListenerService.EXTRA_TG_CHAT_ID, SAVED_CHAT_ID)
            putExtra(ListenerService.EXTRA_TG_LIMIT, SAVED_PAGE_SIZE)
            putExtra(ListenerService.EXTRA_TG_OFFSET_ID, 0)
        })
    }

    /**
     * Saved sub-tab next-page load (older messages). offsetId = oldest loaded id; Telegram
     * returns messages with id < offsetId. Guards against duplicate in-flight requests and
     * end-of-list. Shows the spinner while the older page is awaited.
     */
    private fun requestSavedOlderPage() {
        if (savedRequestInFlight || savedNoMoreOlder) return
        val oldestId = todoSavedAdapter.getOldestMessageId()
        if (oldestId <= 0) return
        savedLoadingOlder = true
        savedRequestInFlight = true
        loaderCtl.show(TabId.TODO)
        sendBroadcast(Intent(ListenerService.ACTION_REQUEST_TG_MESSAGES).apply {
            setPackage(packageName)
            putExtra(ListenerService.EXTRA_TG_CHAT_ID, SAVED_CHAT_ID)
            putExtra(ListenerService.EXTRA_TG_LIMIT, SAVED_PAGE_SIZE)
            putExtra(ListenerService.EXTRA_TG_OFFSET_ID, oldestId)
        })
    }

    private fun startTodoPollIfNeeded() {
        if (!todoSavedHasData && todoSubTab == TodoSubTab.SAVED) {
            mainHandler.removeCallbacks(todoPollRunnable)
            mainHandler.postDelayed(todoPollRunnable, 15_000L)
        }
    }

    private fun stopTodoPoll() {
        mainHandler.removeCallbacks(todoPollRunnable)
    }

    // --- Tab-highlight circle fade in/out (cross-fades the active cursor
    //     between the bottom bar and the TODO sub-tab row) ---
    private fun fadeInHighlight(view: View) {
        view.animate().cancel()
        if (view.visibility != View.VISIBLE) view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate().alpha(1f).setDuration(150L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun fadeOutHighlight(view: View) {
        view.animate().cancel()
        view.animate().alpha(0f).setDuration(150L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction { view.visibility = View.INVISIBLE }
            .start()
    }

    // Place the sub-tab capsule directly under the active sub-tab -- width is
    // one slot, leftMargin is 0, and position is driven by translationX, exactly
    // like the bottom pill. The clean rest position used the moment before it
    // fades in.
    private fun seatSubtabCapsule(cap: View) {
        val pill = todoSubTabPill ?: return
        val tabCount = TodoSubTab.entries.size
        val slotW = pill.width.toFloat() / tabCount
        if (slotW <= 0f) return
        (cap.layoutParams as FrameLayout.LayoutParams).apply {
            width = slotW.toInt()
            leftMargin = 0
        }
        cap.translationX = todoSubTab.ordinal * slotW
        cap.requestLayout()
    }

    private fun updateTodoSubTabLabels(skipIconAnims: Boolean = false, skipCapsuleAnim: Boolean = false) {
        // "Sub-tabs selected" == the sub-tab row is the active nav target.
        val subtabsActive = todoFocusLevel == 0 && focusState == FocusState.TODO_FOCUSED

        // The sub-tab highlight circle is the focus cursor for this row: it
        // appears exactly when the sub-tab row is the active nav target and
        // fades out otherwise. The bottom-bar circle is handled centrally in
        // updateFocusVisual (visible only in TAB_NAV), so this path owns only
        // its own capsule.
        if (subtabsActive != subtabCapsuleShown) {
            subtabCapsuleShown = subtabsActive
            if (subtabsActive) {
                todoSubTabCapsule?.let { cap ->
                    // Re-seat the capsule under the active sub-tab BEFORE it
                    // becomes visible, and clear motion state, so its first
                    // visible frames don't read the re-appear jump as speed.
                    seatSubtabCapsule(cap)
                    cap.snapToRest()
                    fadeInHighlight(cap)
                }
            } else {
                todoSubTabCapsule?.let { fadeOutHighlight(it) }
            }
        }

        // Tones:
        //  - Row IS the active nav target: selected icon GLOWs, the rest sit at GHOST.
        //  - Row is NOT the target (deselected to TAB_NAV, or drilled into content):
        //    ALL icons return to the uniform default GHOST -- no phantom per-tab
        //    highlight, and nothing drops to the near-invisible TRACE.
        if (!skipIconAnims) {
            for (i in todoSubTabLabels.indices) {
                val targetColor = if (subtabsActive) {
                    if (i == todoSubTab.ordinal) Lum.GLOW else Lum.GHOST
                } else {
                    Lum.GHOST
                }
                todoSubTabLabels[i]?.let { icon ->
                    ValueAnimator.ofArgb(icon.imageTintList?.defaultColor ?: Lum.GHOST, targetColor).apply {
                        duration = 200L
                        interpolator = android.view.animation.DecelerateInterpolator()
                        addUpdateListener { icon.setColorFilter(it.animatedValue as Int, android.graphics.PorterDuff.Mode.SRC_IN) }
                        start()
                    }
                }
            }
        }

        // Slide capsule to the active slot via translationX only (mirrors the
        // bottom pill's applyPillAndTints): width is one slot, leftMargin stays
        // 0, position = ordinal * slotW. Skipped when the caller (drag-release)
        // is running its own bouncy spring on translationX.
        if (!skipCapsuleAnim) {
            todoSubTabPill?.let { pill ->
                todoSubTabCapsule?.let { capsule ->
                    val tabCount = TodoSubTab.entries.size
                    val slotW = pill.width.toFloat() / tabCount
                    if (slotW > 0f) {
                        capsule.layoutParams.width = slotW.toInt()
                        capsule.requestLayout()
                        Anim.translateX(capsule, todoSubTab.ordinal * slotW)
                    }
                }
            }
        }

        // Capsule has no border -- just a filled GHOST background

        // Hide empty text when switching tabs
        todoEmptyText?.visibility = View.GONE
        todoHasError = false

        // Fade content switch (150ms out, 200ms in)
        for (tab in TodoSubTab.entries) {
            val rv = getTodoRecyclerFor(tab) ?: continue
            if (tab == todoSubTab) {
                rv.visibility = View.VISIBLE
                rv.alpha = 0f
                Anim.fadeIn(rv, 200L)
            } else if (rv.visibility == View.VISIBLE) {
                Anim.fadeOut(rv, 150L)
            }
        }
    }

    private fun parseTodoListAndDisplay(json: String) {
        try {
            val arr = JSONArray(json)
            val items = mutableListOf<TodoItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                items.add(TodoItem(
                    id = obj.getString("id"),
                    text = obj.getString("text"),
                    completed = obj.optBoolean("completed", false),
                    createdAt = obj.optLong("createdAt", 0L)
                ))
            }
            todoChecklistAdapter.submitList(items)
        } catch (e: Exception) {
            activityLog("parseTodoListAndDisplay failed: ${e.message}")
        }
    }

    private fun parseAlarmListAndDisplay(json: String) {
        try {
            val arr = JSONArray(json)
            val items = mutableListOf<AlarmDisplayItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                items.add(AlarmDisplayItem(
                    id = obj.optInt("id"),
                    hour = obj.optInt("hour"),
                    minute = obj.optInt("minute"),
                    title = obj.optString("title", ""),
                    enabled = obj.optBoolean("enabled", true),
                    triggerTimeMillis = obj.optLong("triggerTimeMillis")
                ))
            }
            todoAlarmAdapter.submitList(items)
        } catch (e: Exception) {
            activityLog("parseAlarmListAndDisplay failed: ${e.message}")
        }
    }

    private fun parseJobListAndDisplay(json: String) {
        try {
            val arr = JSONArray(json)
            val items = mutableListOf<JobDisplayItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                items.add(JobDisplayItem(
                    id = obj.optString("id", ""),
                    name = obj.optString("name", ""),
                    prompt = obj.optString("prompt", ""),
                    scheduledAt = obj.optLong("scheduledAt"),
                    status = obj.optString("status", "pending"),
                    result = if (obj.isNull("result")) null else obj.optString("result"),
                    error = if (obj.isNull("error")) null else obj.optString("error")
                ))
            }
            todoJobAdapter.submitList(items)
        } catch (e: Exception) {
            activityLog("parseJobListAndDisplay failed: ${e.message}")
        }
    }

    /**
     * Parse a telegram_messages page for the Saved sub-tab and render it. First page replaces
     * (submitList); older pages append + de-dupe (appendOlder). Keeps newest-first ordering and
     * hides the loader spinner. Per-message shape: {id, sender, text, date}.
     */
    private fun parseSavedMessagesAndDisplay(json: String) {
        val wasOlder = savedLoadingOlder
        savedRequestInFlight = false
        savedLoadingOlder = false
        loaderCtl.hide(TabId.TODO)
        try {
            // Check if the response is an error object
            val trimmed = json.trim()
            if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                if (obj.has("error")) {
                    val errorMsg = obj.getString("error")
                    activityLog("Telegram saved error: $errorMsg")
                    if (!wasOlder) {
                        todoSavedAdapter.submitList(emptyList())
                        todoHasError = true
                        if (todoSubTab == TodoSubTab.SAVED) {
                            todoEmptyText?.let { tv ->
                                tv.text = errorMsg
                                if (tv.visibility != View.VISIBLE) {
                                    tv.visibility = View.VISIBLE
                                    tv.alpha = 0f
                                    Anim.fadeIn(tv, 200L)
                                }
                            }
                        }
                    }
                    return
                }
            }

            val arr = JSONArray(json)
            val messages = mutableListOf<TelegramMessage>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                messages.add(TelegramMessage(
                    id = obj.optInt("id", 0),
                    sender = obj.optString("sender", ""),
                    text = obj.optString("text", ""),
                    date = obj.optString("date", "")
                ))
            }
            // The agent returns oldest-first; show newest at the top (descending by date)
            // since the list reads top-down.
            messages.sortByDescending { it.date }

            if (wasOlder) {
                // Older page: append at the bottom, de-duped. A short page means end-of-list.
                if (messages.size < SAVED_PAGE_SIZE) savedNoMoreOlder = true
                todoSavedAdapter.appendOlder(messages)
                return
            }

            // First page.
            if (messages.isEmpty()) {
                todoSavedAdapter.submitList(emptyList())
                todoHasError = false
                savedNoMoreOlder = true
                if (todoSubTab == TodoSubTab.SAVED) {
                    todoEmptyText?.let { tv ->
                        tv.text = "No saved messages"
                        if (tv.visibility != View.VISIBLE) {
                            tv.visibility = View.VISIBLE
                            tv.alpha = 0f
                            Anim.fadeIn(tv, 200L)
                        }
                    }
                }
                return
            }

            // Hide empty/error text
            todoHasError = false
            if (todoSubTab == TodoSubTab.SAVED) {
                todoEmptyText?.let { tv ->
                    if (tv.visibility == View.VISIBLE) {
                        Anim.fadeOut(tv, 150L)
                    }
                }
            }
            if (messages.size < SAVED_PAGE_SIZE) savedNoMoreOlder = true
            todoSavedAdapter.submitList(messages)
            todoSavedHasData = true
            stopTodoPoll()
        } catch (e: Exception) {
            activityLog("parseSavedMessagesAndDisplay failed: ${e.message}")
            if (!wasOlder) {
                todoHasError = true
                todoEmptyText?.let { tv ->
                    tv.text = "Failed to parse messages"
                    if (tv.visibility != View.VISIBLE) {
                        tv.visibility = View.VISIBLE
                        tv.alpha = 0f
                        Anim.fadeIn(tv, 200L)
                    }
                }
            }
        }
    }

    // --- Chat list data ---

    private fun requestChatList() {
        sendBroadcast(Intent(ListenerService.ACTION_REQUEST_CHAT_LIST).apply {
            setPackage(packageName)
        })
    }

    private fun openSelectedChat() {
        val item = chatListAdapter.getSelectedItem() ?: return
        showLoadingSpinner()
        sendBroadcast(Intent(ListenerService.ACTION_SWITCH_CHAT).apply {
            setPackage(packageName)
            putExtra("conversation_id", item.id)
        })
    }

    private fun updateChatEmptyHint() {
        val show = chatRecycler.visibility == View.VISIBLE && chatAdapter.itemCount == 0 && serviceState == "IDLE"
        chatEmptyHint.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun openNewChat() {
        sendBroadcast(Intent(ListenerService.ACTION_REQUEST_NEW_CHAT).apply { setPackage(packageName) })
        chatAdapter.clear()
        switchToTab(chatTabIndex(), animate = true)
        focusState = FocusState.CHAT_FOCUSED
        updateFocusVisual(focusState)
    }

    private fun openAssistant() {
        // Ask the backend (ListenerService) to start/stop the assistant pipeline.
        // The service tracks active state and toggles accordingly.
        sendBroadcast(Intent(ListenerService.ACTION_TOGGLE_ASSISTANT).apply { setPackage(packageName) })
        activityLog("Assistant toggle requested from chat list")
    }

    private fun parseChatListAndDisplay(json: String) {
        hideLoadingSpinner()
        try {
            val arr = JSONArray(json)
            val items = mutableListOf<ChatSummaryItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                items.add(ChatSummaryItem(
                    id = obj.getString("id"),
                    title = obj.optString("title", "(no messages)"),
                    relativeTime = obj.optString("relativeTime", ""),
                    turnCount = obj.optInt("turnCount", 0),
                    isActive = obj.optBoolean("isActive", false),
                    deviceType = obj.optString("deviceType", "")
                ))
            }
            chatListAdapter.submitList(items)
            if (chatListAdapter.selectedPosition < 0 && items.isNotEmpty()) {
                chatListAdapter.selectPosition(0)
            }
        } catch (e: Exception) {
            activityLog("parseChatListAndDisplay failed: ${e.message}")
        }
    }

    private fun loadChatHistory(conversationId: String, turnsJson: String) {
        hideLoadingSpinner()
        try {
            chatAdapter.clear()
            val arr = JSONArray(turnsJson)
            for (i in 0 until arr.length()) {
                val turn = arr.getJSONObject(i)
                val userText = if (turn.has("userText") && !turn.isNull("userText")) turn.getString("userText") else null
                val responseText = if (turn.has("responseText") && !turn.isNull("responseText")) turn.getString("responseText") else null

                if (!userText.isNullOrEmpty()) {
                    chatAdapter.addMessage(ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = ChatMessage.Role.USER,
                        text = userText,
                        requestId = turn.optString("requestId", "")
                    ))
                }
                val toolCallsArr = turn.optJSONArray("toolCalls")
                if (toolCallsArr != null) {
                    for (j in 0 until toolCallsArr.length()) {
                        val tc = toolCallsArr.getJSONObject(j)
                        val tcName = tc.optString("name", "")
                        val args = tc.optJSONObject("arguments")
                        val argSummary = args?.let {
                            it.optString("query", "").ifEmpty { null }
                                ?: it.optString("url", "").ifEmpty { null }
                                ?: it.optString("command", "").ifEmpty { null }
                                ?: it.optString("prompt", "").ifEmpty { null }
                                ?: it.optString("path", "").ifEmpty { null }
                        }
                        val displayText = if (!argSummary.isNullOrEmpty()) "$tcName: ${argSummary.take(60)}" else tcName
                        chatAdapter.addMessage(ChatMessage(
                            id = tc.optString("id", UUID.randomUUID().toString()),
                            role = ChatMessage.Role.TOOL,
                            text = displayText,
                            requestId = turn.optString("requestId", ""),
                            isAnimating = false
                        ))
                    }
                }
                if (!responseText.isNullOrEmpty()) {
                    chatAdapter.addMessage(ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = ChatMessage.Role.ASSISTANT,
                        text = responseText,
                        requestId = turn.optString("requestId", "")
                    ))
                }
            }
            scrollToBottom()
            switchToTab(chatTabIndex(), animate = false)
            focusState = FocusState.CHAT_FOCUSED
            updateFocusVisual(focusState)
        } catch (_: Exception) {}
    }

    // --- Debug overlay ---

    private var lastBtConnected: Boolean? = null

    private fun updateDebugLine(keyCode: Int? = null, btConnected: Boolean? = null) {
        if (btConnected != null) lastBtConnected = btConnected
        val parts = mutableListOf<String>()
        parts.add("F:${focusState.name.take(4)}")
        parts.add("T:$currentTab")
        if (lastBtConnected != null) parts.add("BT:${if (lastBtConnected == true) "Y" else "N"}")
        if (keyCode != null) parts.add("K:$keyCode")
        debugStatus.text = parts.joinToString(" ")
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // Notification-solo reveal: the first key DOWN during an armed solo notification
        // restores the activity's blacked-out content (fade alpha back to 1). Fire once and
        // DO NOT consume, so hold-to-reply (NUMPAD_3 -> startReplyArm) and DOWN/UP pairing
        // are untouched. Restoring is local to the activity (it owns its content root). We
        // also tell the backend so it can end the solo session lifecycle cleanly.
        if (notifSoloArmed && event.action == android.view.KeyEvent.ACTION_DOWN) {
            notifSoloArmed = false
            activityLog("[NSOLO] key DOWN while armed -> revealing content (not consuming)")
            revealFromSolo("key-press")
            sendBroadcast(Intent(ListenerService.ACTION_NOTIFICATION_SOLO_REVEAL).apply {
                setPackage(packageName)
            })
        }
        val actionName = when (event.action) {
            android.view.KeyEvent.ACTION_DOWN -> "DOWN"
            android.view.KeyEvent.ACTION_UP -> "UP"
            android.view.KeyEvent.ACTION_MULTIPLE -> "MULTI"
            else -> "?${event.action}"
        }
        activityLog("dispatchKeyEvent $actionName keyCode=${event.keyCode} scanCode=${event.scanCode} repeat=${event.repeatCount}")
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            updateDebugLine(keyCode = event.keyCode)
        }
        return super.dispatchKeyEvent(event)
    }

    // --- Focus state machine: key handling ---

    private fun turnScreenOff() {
        sendBroadcast(Intent(com.repository.glasses.listener.service.ScreenOffAccessibilityService.ACTION_LOCK_SCREEN).apply {
            setPackage(packageName)
        })
        uiLog("Screen off: lock screen requested via accessibility service")
    }

    private fun isDoubleTap(): Boolean {
        // A remote tap carries the interval the SOURCE measured, so it must not be timed by arrival
        // here, and it must not disturb the touchpad's timestamp: sharing one would let a remote tap
        // make the user's next physical tap read as a double, and vice versa.
        pendingRemoteDoubleTap?.let { remote ->
            pendingRemoteDoubleTap = null
            return remote
        }
        val now = SystemClock.elapsedRealtime()
        val isDouble = (now - lastCenterPressTime) < DOUBLE_TAP_THRESHOLD_MS
        lastCenterPressTime = now
        return isDouble
    }

    /**
     * Verdict for the remote tap currently being dispatched, consumed by the next [isDoubleTap].
     *
     * Set immediately before synthesizing the key and cleared as it is read, so it cannot leak into
     * an unrelated physical press. Null whenever the touchpad is the origin.
     */
    private var pendingRemoteDoubleTap: Boolean? = null

    /**
     * Arm the double-tap window for the NEXT press, as the tab-entry branches do.
     *
     * A no-op for remote input. The window is a touchpad-clock quantity, and a remote tap that
     * stamped it would make the user's next PHYSICAL tap read as a double -- exactly the cross-talk
     * the origin split exists to prevent. Remote taps carry their own interval from the source, so
     * they need no armed window here.
     */
    private fun armDoubleTapWindow() {
        if (currentInputOrigin == InputOrigin.REMOTE) return
        lastCenterPressTime = SystemClock.elapsedRealtime()
    }

    /**
     * Origin of the key currently being dispatched.
     *
     * A field rather than an `onKeyDown` parameter because `onKeyDown` is an Android override whose
     * signature is not ours to change, and every real hardware press arrives through it. It is only
     * ever [InputOrigin.REMOTE] for the duration of one synchronous synthesized dispatch on the main
     * thread, and is restored in a `finally`, so a handler that throws cannot leave it stuck.
     */
    private var currentInputOrigin = InputOrigin.TOUCHPAD

    /**
     * Decide single-versus-double for a remote tap using the SOURCE's own clock.
     *
     * `sinceLastMs` was stamped when the finger landed on the remote device, so it survives every
     * kind of transport jitter -- coalescing, a queue stall, a connection interval. Timing the same
     * two taps by arrival here would routinely stretch a deliberate 350 ms double tap past the
     * 400 ms threshold and deliver two singles instead.
     *
     * The 40 ms floor the touchpad applies is deliberately NOT enforced: it debounces capacitive
     * hardware that can report one physical touch twice, which is not a failure mode a remote
     * source has.
     */
    private fun remoteTapIsDouble(sinceLastMs: Int): Boolean =
        sinceLastMs != RemoteInputEvent.NO_PREDECESSOR &&
            sinceLastMs < DOUBLE_TAP_THRESHOLD_MS

    // --- Night Vision ML slider controls ---
    // Design system: monospace, dim text, no decorative borders, focus = glow text + 0.5->1.5dp border animated

    private var nvExpLabel: android.widget.TextView? = null
    private var nvAmpLabel: android.widget.TextView? = null
    private var nvSliderBar: android.widget.LinearLayout? = null

    private fun ensureNvSliderViews() {
        if (nvSliderBar != null) return
        val container = findViewById<android.widget.FrameLayout>(R.id.nightvisionContainer)
        val density = resources.displayMetrics.density

        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setBackgroundColor(com.repository.glasses.listener.ui.Lum.VOID)
            setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
        }
        val lp = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM
        )
        container.addView(bar, lp)

        val makeTv = { text: String ->
            android.widget.TextView(this).apply {
                this.text = text
                setTextColor(com.repository.glasses.listener.ui.Lum.DIM)
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setBackgroundColor(com.repository.glasses.listener.ui.Lum.VOID)
                setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            }
        }

        nvExpLabel = makeTv("EXP 313ms")
        nvAmpLabel = makeTv("AMP x300")

        bar.addView(nvExpLabel, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(nvAmpLabel, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        nvSliderBar = bar
    }

    private fun updateNvSliderVisuals() {
        ensureNvSliderViews()
        val expLabel = nvExpLabel ?: return
        val ampLabel = nvAmpLabel ?: return
        val density = resources.displayMetrics.density

        val styleLabel = { view: android.widget.TextView, selected: Boolean, locked: Boolean ->
            if (selected) {
                // Focus: glow text, thin trace border -> animate to 1.5dp
                view.setTextColor(if (locked) com.repository.glasses.listener.ui.Lum.GLOW else com.repository.glasses.listener.ui.Lum.BRIGHT)
                val d = android.graphics.drawable.GradientDrawable().apply {
                    setColor(com.repository.glasses.listener.ui.Lum.VOID)
                    setStroke(if (locked) (1.5f * density).toInt() else (0.5f * density).toInt(), com.repository.glasses.listener.ui.Lum.GHOST)
                    cornerRadius = 8f * density
                }
                view.background = d
            } else {
                // Unfocused: dim text, no border
                view.setTextColor(com.repository.glasses.listener.ui.Lum.DIM)
                view.setBackgroundColor(com.repository.glasses.listener.ui.Lum.VOID)
            }
        }

        styleLabel(expLabel, nvSliderIndex == 0, nvSliderLocked && nvSliderIndex == 0)
        styleLabel(ampLabel, nvSliderIndex == 1, nvSliderLocked && nvSliderIndex == 1)
    }

    private fun sendNvSliderAdjust(sliderIndex: Int, direction: Int) {
        val action = if (sliderIndex == 0) "NV_ADJUST_EXPOSURE" else "NV_ADJUST_AMPLIFICATION"
        val intent = android.content.Intent(action)
        intent.putExtra("direction", direction)
        sendBroadcast(intent)

        if (sliderIndex == 0) {
            val label = nvExpLabel ?: return
            val current = label.text.toString().replace(Regex("[^0-9]"), "").toIntOrNull() ?: 313
            val newVal = (current + direction * 50).coerceIn(50, 313)
            label.text = "EXP ${newVal}ms"
        } else {
            val label = nvAmpLabel ?: return
            val current = label.text.toString().replace(Regex("[^0-9]"), "").toIntOrNull() ?: 300
            val newVal = (current + direction * 100).coerceIn(100, 1000)
            label.text = "AMP x${newVal}"
        }
    }

    private fun showDoubleTapHintPersistent() {
        doubleTapHintRunnable?.let { mainHandler.removeCallbacks(it) }
        doubleTapHintRunnable = null
        doubleTapHint.visibility = View.VISIBLE
    }

    private fun hideDoubleTapHint() {
        doubleTapHintRunnable?.let { mainHandler.removeCallbacks(it) }
        doubleTapHintRunnable = null
        doubleTapHint.visibility = View.GONE
    }

    private fun showAudioVisualizer() {
        hideAudioVisualizer()
        val vis = com.repository.glasses.listener.ui.AudioVisualizerView(this)
        val dp = resources.displayMetrics.density
        val contentFrame = findViewById<FrameLayout>(R.id.contentFrame)
        val params = FrameLayout.LayoutParams(
            (contentFrame.width * 0.35).toInt(),
            (24 * dp).toInt()
        ).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            bottomMargin = (8 * dp).toInt()
        }
        contentFrame.addView(vis, params)
        audioVisualizerView = vis

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val levels = intent.getFloatArrayExtra(ListenerService.EXTRA_AUDIO_LEVELS) ?: return
                val bands = intent.getIntExtra(ListenerService.EXTRA_AUDIO_LEVELS_BANDS, levels.size)
                runOnUiThread { audioVisualizerView?.pushEnvelope(levels, bands) }
            }
        }
        registerReceiver(receiver, android.content.IntentFilter(ListenerService.ACTION_AUDIO_LEVELS))
        audioLevelsReceiver = receiver
    }

    private fun hideAudioVisualizer() {
        audioLevelsReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            audioLevelsReceiver = null
        }
        audioVisualizerView?.let {
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            audioVisualizerView = null
        }
    }

    private fun isScrollThrottled(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastScrollTime < SCROLL_THROTTLE_MS) return true
        lastScrollTime = now
        return false
    }

    // --- Remote input sink (watch bezel and any future InputSource) ---

    private var remoteGlyphHideRunnable: Runnable? = null

    /**
     * Flash the "remote is driving this" indicator.
     *
     * Deliberately driven by ACTED-ON events rather than by session state: a session can be open
     * while every event is being refused, and an indicator that is lit in that case tells the user
     * the opposite of the truth.
     */
    private fun showRemoteActiveGlyph() {
        if (!::remoteInputGlyph.isInitialized) return
        remoteGlyphHideRunnable?.let { mainHandler.removeCallbacks(it) }
        remoteInputGlyph.visibility = View.VISIBLE
        val hide = Runnable { remoteInputGlyph.visibility = View.GONE }
        remoteGlyphHideRunnable = hide
        mainHandler.postDelayed(hide, REMOTE_GLYPH_LINGER_MS)
    }

    /**
     * Snapshot every input the gate needs, read on the main thread at dispatch time.
     *
     * Taken as one value rather than read piecemeal inside the gate so the decision and the
     * dispatch cannot straddle a state change -- several of these flags flip from broadcast
     * callbacks that also run on this thread.
     */
    private fun remoteInputSnapshot() = RemoteActionGate.UiInputSnapshot(
        nightVisionSliderLocked = nvSliderLocked,
        focusState = focusState.name,
        serviceState = serviceState,
        foldedState = foldedState,
        todoFocusLevel = todoFocusLevel,
        replyArming = replyArming,
        hasActiveReply = activeReplyNotifId != null,
        replySendPending = replySendPending,
        translationStarting = translationStarting,
        translationActive = translationActive,
        mouseTracking = dpadHandler.trackingEnabled || rfcommMouseActive,
        callPhase = callPhase.name,
    )

    /**
     * Act on one authenticated remote event. Always on the main thread.
     *
     * This method owns EVERY glasses-UI decision about remote input -- which keycode an action
     * becomes and whether it is permitted -- because the layer below it
     * ([com.repository.glasses.listener.input.remote.RemoteInputRouter]) is deliberately free of
     * keycodes and focus states. Adding a new input device therefore changes nothing here.
     *
     * Note what is NOT reachable: `KEYCODE_NUMPAD_3` (the touchpad hold) is never synthesized from
     * any remote source. On the physical pad it arms a notification voice reply that records the
     * microphone and sends a message to a real contact, and it launches the assistant. There is no
     * code path from a remote event to that keycode, by construction rather than by a check.
     */
    override fun onRemoteInput(e: RemoteInputEvent) {
        val acted = when (e.action) {
            RemoteAction.SCROLL_STEP -> dispatchRemoteScroll(e.delta)
            // A tap is DPAD_CENTER, the keycode the physical tap actually proxies through as.
            // NUMPAD_2 would be wrong: it is consumed by the release/double-tap branches at the top
            // of onKeyDown and never reaches the focus dispatch at all, so it selects nothing.
            RemoteAction.TAP -> {
                // Disambiguate on the source's clock, before dispatch, so the handler's own
                // isDoubleTap() sees the user's real intent rather than the arrival interval.
                if (remoteTapIsDouble(e.sinceLastMs)) {
                    // The SECOND tap of a remote double tap is the user asking to LEAVE, so it is
                    // gated and dispatched as BACK rather than as another TAP.
                    //
                    // This is what makes the exit exist at all. The watch sends only raw taps
                    // (EventType.SELECT) and nothing anywhere produces EventType.BACK, so before
                    // this a double tap was gated as TAP -- and in every TAP_REACHES_HAZARD state
                    // the user could ENTER from the watch and then neither select nor leave, with
                    // the physical touchpad the only way out.
                    //
                    // Deciding it here rather than on the watch is deliberate: the glasses own
                    // double-tap disambiguation for the touchpad too, so both sources keep the
                    // same feel and cannot drift apart.
                    dispatchRemoteAction(RemoteAction.BACK, KeyEvent.KEYCODE_BACK)
                } else {
                    pendingRemoteDoubleTap = false
                    val dispatched =
                        dispatchRemoteAction(e.action, KeyEvent.KEYCODE_DPAD_CENTER)
                    // Refused, or consumed by a branch that never calls isDoubleTap(): drop the
                    // verdict rather than let it apply to some later press.
                    pendingRemoteDoubleTap = null
                    dispatched
                }
            }
            RemoteAction.BACK -> dispatchRemoteAction(e.action, KeyEvent.KEYCODE_BACK)
        }
        if (acted) showRemoteActiveGlyph()
    }

    /**
     * Gate one action against the CURRENT state, then dispatch it if permitted.
     *
     * The gate is re-evaluated per synthesized key rather than once per event: a key changes the UI,
     * and the next key in the same burst is judged against the state the previous one produced.
     * Evaluating once up front would let a permitted first key walk the UI into a state where the
     * rest of the burst is no longer permitted, and dispatch them anyway.
     *
     * @return true if the key was dispatched.
     */
    private fun dispatchRemoteAction(action: RemoteAction, keyCode: Int): Boolean {
        val verdict = RemoteActionGate.evaluate(remoteInputSnapshot(), action)
        if (verdict != RemoteActionGate.Denial.ALLOWED) {
            uiLog("[RemoteInput] refused $action in $focusState: $verdict")
            return false
        }
        dispatchRemoteKey(keyCode)
        return true
    }

    /**
     * Feed a scroll as the same repeated keycodes the touchpad daemon emits, so remote scrolling
     * lands in the identical per-focus-state handlers and inherits `ScrollDrainer` pacing.
     *
     * The magnitude is bounded independently of anything the producer claimed: a coalesced event
     * legitimately carries several detents, but a single frame must never be able to fling the UI.
     */
    private fun dispatchRemoteScroll(delta: Int): Boolean {
        if (delta == 0) return false
        val keyCode =
            if (delta > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        val steps = kotlin.math.min(kotlin.math.abs(delta), MAX_REMOTE_SCROLL_STEPS)
        var acted = false
        // Stop at the first refusal: a burst must not carry on after an earlier key moved the UI
        // somewhere a remote source is not allowed to act.
        repeat(steps) {
            if (!dispatchRemoteAction(RemoteAction.SCROLL_STEP, keyCode)) return acted
            acted = true
        }
        return acted
    }

    /**
     * Route a synthesized keycode through the ordinary handler.
     *
     * Reusing `onKeyDown` rather than duplicating its logic is the point: remote input and the
     * touchpad stay behaviourally identical for free, and they cannot drift apart later.
     */
    private fun dispatchRemoteKey(keyCode: Int) {
        // Save and restore rather than resetting to TOUCHPAD: if a handler ever synthesizes another
        // key, restoring a literal would silently misattribute the rest of the outer dispatch.
        val previous = currentInputOrigin
        currentInputOrigin = InputOrigin.REMOTE
        try {
            onKeyDown(keyCode, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        } finally {
            currentInputOrigin = previous
        }
    }

    /**
     * Touchpad key handling, lifted verbatim out of [onKeyDown].
     *
     * Sees EVERY keycode, not just the NUMPAD_* ones the touchpad daemon emits. That is not
     * an oversight: the final `lastNumpad2Ms = 0L` is a fall-through reached by non-touchpad
     * keys (DPAD_*, CAMERA, BACK, CENTER), and it is what makes any other key break the
     * double-tap chain. Gating this function on NUMPAD_* would silently stop those keys from
     * breaking the chain and turn unrelated presses into phantom double-taps.
     *
     * @return non-null when the event was fully handled here and [onKeyDown] must return it;
     *   null when the key should fall through to the focus dispatch. A plain Boolean cannot
     *   express that difference -- `false` already means "not handled, call super".
     */
    private fun handleTouchpadKey(keyCode: Int, event: KeyEvent?, origin: InputOrigin): Boolean? {
        // Everything below this line is touchpad gesture decoding: the hold/release state machine,
        // the fold gate, and the NUMPAD_2 double-tap chain that ends in turnScreenOff(). A remote
        // source produces none of those raw gestures -- it sends decided actions -- and must never
        // reach this machinery, most of all the screen-off branch, which would pause the UI process,
        // drop the sink, and strand the session with no way back from the remote device.
        if (origin == InputOrigin.REMOTE) return null
        // rokid-touchpad-daemon emits synthetic keycodes on its virtual input
        // device instead of the raw DPAD keys from the PSoC touchpad, so we
        // get finer-grained, velocity-scaled scroll steps and the AI-assistant
        // misfire (KEY_PROG1) is suppressed when the user is actually dragging.
        // Remap them to the DPAD keycodes the rest of this method already
        // handles. In tab-nav mode swipes map to LEFT/RIGHT (switch tab); in
        // any focused content mode they map to UP/DOWN (scroll pixels).
        // KEYCODE_NUMPAD_2 (touch-released) has no payload and is consumed.
        // Always remap to DPAD_RIGHT/DPAD_LEFT. The glasses listener pairs
        // RIGHT+DOWN and LEFT+UP throughout its when() blocks so a single
        // horizontal keycode covers both tab-switching (native LEFT/RIGHT)
        // and vertical scrolling (handlers accept LEFT/RIGHT as aliases).
        // Gate touchpad keycodes on fold state: swallow only when folded so the
        // glasses don't react to pocket/case contact while put away. Doesn't
        // affect real buttons (KEYCODE_CAMERA, DPAD_*) since those only arrive on
        // genuine hardware events, not from the touchpad daemon's synthetic uinput.
        if ((keyCode == KeyEvent.KEYCODE_NUMPAD_0 ||
                keyCode == KeyEvent.KEYCODE_NUMPAD_1 ||
                keyCode == KeyEvent.KEYCODE_NUMPAD_2 ||
                keyCode == KeyEvent.KEYCODE_NUMPAD_3) && foldedState) {
            lastNumpad2Ms = 0L
            return true
        }
        // Notification hold-to-reply intercept. The touchpad daemon emits a
        // single momentary NUMPAD_3 when a press reaches 500ms (there is no
        // sustained key). When a repliable notification is on screen and we are
        // not already in a reply/call/modal, that hold arms a voice reply to the
        // notification instead of opening AI chat. Consume so it does NOT fall
        // through to the ACTION_SENSOR_LONG_PRESS branch below. When no repliable
        // notification is present, NUMPAD_3 falls through and AI listening still
        // fires -- the two coexist without conflict.
        // Mouse mode: a touchpad HOLD (NUMPAD_3) is a RIGHT-CLICK while tracking is active.
        // Intercept before the notification-reply / AI-chat long-press paths below so the hold
        // never summons the assistant. Double-tap stays as the tracking toggle (BACK handler).
        if (keyCode == KeyEvent.KEYCODE_NUMPAD_3 &&
            focusState == FocusState.MOUSE_FOCUSED && dpadHandler.trackingEnabled) {
            uiLog("[Mouse] HOLD -> right click")
            dpadHandler.listener?.onRightClick()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_NUMPAD_3) {
            uiLog("[NREPLY] NUMPAD_3 down: repliable=$notificationRepliable pendingNotifId=${pendingNotifId?.take(12)} activeReplyNotifId=${activeReplyNotifId?.take(12)} focus=$focusState replyArming=$replyArming")
        }
        if (keyCode == KeyEvent.KEYCODE_NUMPAD_3 &&
            notificationRepliable && pendingNotifId != null &&
            focusState !in setOf(
                FocusState.NOTIFICATION_REPLY,
                FocusState.TELEGRAM_RECORDING,
                FocusState.TELEGRAM_PREVIEW,
                FocusState.CALL_INCOMING,
                FocusState.CALL_ACTIVE,
                FocusState.STOP_MODAL
            )) {
            uiLog("[NREPLY] -> arm branch (startReplyArm, replyArming was $replyArming)")
            if (!replyArming) startReplyArm()
            return true
        }
        // Time-based long-press from touchpad-daemon: finger on the pad for
        // 500ms with no motion -> KEY_KP3 (KEYCODE_NUMPAD_3). Forward to
        // ListenerService to activate AI listening -- unless an HFP call is
        // currently ACTIVE, in which case the same gesture toggles the HF mic
        // mute (you don't want to summon the assistant mid-call when you're
        // really trying to silence yourself).
        if (keyCode == KeyEvent.KEYCODE_NUMPAD_3) {
            // Never let a stray long-press summon AI chat while a notification
            // reply is in-flight (arming, LISTENING, or the post-release
            // SENDING/SENT window where pendingNotifId is briefly null but the
            // reply is not yet finished). This was the path that made AI chat
            // kick in on a second hold while the notification was still shown.
            if (replyArming || focusState == FocusState.NOTIFICATION_REPLY ||
                activeReplyNotifId != null) {
                uiLog("[NREPLY] NUMPAD_3 consumed by in-flight-reply guard (NOT forwarding to AI)")
                return true
            }
            // Always forward to ListenerService. The service decides whether to
            // toggle HF mic mute (when SCO is live, with or without a call) or
            // activate AI listening. Gating server-side avoids stale snapshot
            // races: MainActivity's callScoActive lags by a broadcast and would
            // miss the case where SCO came up before the listener registered.
            uiLog("[NREPLY] NUMPAD_3 -> AI chat path (SENSOR_LONG_PRESS). repliable=$notificationRepliable pendingNotifId=${pendingNotifId?.take(12)}")
            sendBroadcast(Intent(com.repository.glasses.listener.service.ListenerService.ACTION_SENSOR_LONG_PRESS).apply {
                setPackage(packageName)
            })
            return true
        }
        // NUMPAD_2 (finger lifted / tap) during reply arming or NOTIFICATION_REPLY.
        // Handled before tap/double-tap/screen-off so a release that belongs to
        // the hold gesture never registers as an unrelated tap.
        if (keyCode == KeyEvent.KEYCODE_NUMPAD_2) {
            if (replyArming || focusState == FocusState.NOTIFICATION_REPLY) {
                uiLog("[NREPLY] NUMPAD_2 replyArming=$replyArming focus=$focusState sendPending=$replySendPending activeReplyNotifId=${activeReplyNotifId?.take(12)}")
            }
            // Released before the progress bar filled -> cancel the arm.
            if (replyArming) { uiLog("[NREPLY] release -> cancelReplyArm (bar not full)"); cancelReplyArm(); return true }
            if (focusState == FocusState.NOTIFICATION_REPLY) {
                // In the 3s post-transcript SENDING window a DOUBLE-tap cancels the
                // pending send; a single tap is ignored. While still LISTENING
                // (hands-free recording driven by the phone's VAD) every release is
                // a no-op -- the user just speaks and the VAD auto-stops.
                if (replySendPending) {
                    val now = SystemClock.uptimeMillis()
                    val dt = now - lastReplyCancelTapMs
                    if (lastReplyCancelTapMs != 0L &&
                        dt in DOUBLE_TAP_NUMPAD2_MIN_MS..DOUBLE_TAP_NUMPAD2_MAX_MS) {
                        uiLog("[NREPLY] double-tap in send window -> cancel send")
                        lastReplyCancelTapMs = 0L
                        notifReplyCancel()
                    } else {
                        lastReplyCancelTapMs = now
                    }
                }
                return true
            }
        }
        // NUMPAD_2 doubletap in TAB_NAV -> turn screen off. Any other non-
        // NUMPAD_2 key breaks the chain (scroll via NUMPAD_0/1 included).
        if (keyCode == KeyEvent.KEYCODE_NUMPAD_2) {
            val now = SystemClock.uptimeMillis()
            val dt = now - lastNumpad2Ms
            if (focusState == FocusState.TAB_NAV &&
                lastNumpad2Ms != 0L &&
                dt in DOUBLE_TAP_NUMPAD2_MIN_MS..DOUBLE_TAP_NUMPAD2_MAX_MS) {
                uiLog("NUMPAD_2 doubletap in TAB_NAV -> screen off")
                turnScreenOff()
                lastNumpad2Ms = 0L
            } else {
                lastNumpad2Ms = now
            }
            return true
        }
        // Any non-NUMPAD_2 key (including the scroll remaps below) breaks the
        // doubletap chain.
        lastNumpad2Ms = 0L
        // Swipe-to-cancel during a notification reply: a horizontal swipe
        // (NUMPAD_0/1 from the daemon) while LISTENING cancels the reply instead
        // of scrolling. Consume so it never reaches the scroll remap below.
        if (focusState == FocusState.NOTIFICATION_REPLY &&
            (keyCode == KeyEvent.KEYCODE_NUMPAD_0 ||
             keyCode == KeyEvent.KEYCODE_NUMPAD_1)) {
            notifReplyCancel()
            return true
        }
        // In TAB_NAV mode the new ABS_X path (TouchpadAbsListener -> drag pill
        // -> snap on release) owns tab selection. The kmsg-driven NUMPAD_0/1
        // scroll keycodes still arrive from the daemon (they remain useful in
        // CHAT/LIST/etc. focused states for in-tab scrolling), but in TAB_NAV
        // they would race the ABS path and snap the tab mid-drag. Drop them.
        if (focusState == FocusState.TAB_NAV &&
            (keyCode == KeyEvent.KEYCODE_NUMPAD_0 ||
             keyCode == KeyEvent.KEYCODE_NUMPAD_1)) {
            return true
        }
        return null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = GT.section("ui.onKeyDown") {
        GT.counter("ui.keycode", keyCode.toLong())
        handleTouchpadKey(keyCode, event, currentInputOrigin)?.let { return@section it }
        val remapped = when (keyCode) {
            KeyEvent.KEYCODE_NUMPAD_0 -> KeyEvent.KEYCODE_DPAD_RIGHT   // fwd / next
            KeyEvent.KEYCODE_NUMPAD_1 -> KeyEvent.KEYCODE_DPAD_LEFT    // back / prev
            else -> keyCode
        }
        @Suppress("NAME_SHADOWING") val keyCode = remapped
        // `origin` is logged because without it this line cannot distinguish a remote
        // key from a physical touchpad one: the NUMPAD_0/1 remap immediately above
        // rewrites a touchpad swipe into the SAME DPAD code a remote scroll
        // synthesizes. Diagnosing the remote path then rests on correlating another
        // device's log by timestamp, which is coincidence, not attribution.
        uiLog("KEY: code=$keyCode origin=$currentInputOrigin state=$serviceState focus=$focusState tab=$currentTab/${activeTabs.size} tabId=${activeTabs.getOrNull(currentTab)} sel=${chatAdapter.selectedPosition}")
        // KEYCODE_CAMERA path: forward the event to ListenerService's FunctionButtonHandler
        // via broadcast so CaptureBridge can own the camera pipeline (single source of truth).
        // This also makes the broadcast driveable from adb + e2e harness for testing.
        if (keyCode == KeyEvent.KEYCODE_CAMERA) {
            sendBroadcast(Intent(com.repository.glasses.listener.service.ScreenOffAccessibilityService.ACTION_FN_KEY).apply {
                setPackage(packageName)
                putExtra(com.repository.glasses.listener.service.ScreenOffAccessibilityService.EXTRA_EVENT_ACTION, "DOWN")
                putExtra(com.repository.glasses.listener.service.ScreenOffAccessibilityService.EXTRA_REPEAT, event?.repeatCount ?: 0)
            })
            return true
        }

        // HFP call handling gets priority over BACK / other focus handlers so
        // an ongoing call can never be navigated away from by accident.
        if (focusState == FocusState.CALL_INCOMING) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (foldedState) {
                    // Folded: consume so stray taps don't reach other handlers,
                    // but do not accept or decline. User must unfold the glasses first.
                    uiLog("CALL: tap ignored (folded)")
                    return true
                }
                val nowMs = android.os.SystemClock.uptimeMillis()
                val pending = pendingCallAccept
                if (pending != null && nowMs - lastCallTapMs < DOUBLE_TAP_THRESHOLD_MS) {
                    // Second tap within DOUBLE_TAP_THRESHOLD_MS -> decline.
                    callKeyHandler.removeCallbacks(pending)
                    pendingCallAccept = null
                    sendBroadcast(Intent(ListenerService.ACTION_CALL_DECLINE).setPackage(packageName))
                    uiLog("CALL: decline (double-tap)")
                } else {
                    lastCallTapMs = nowMs
                    val runnable = Runnable {
                        pendingCallAccept = null
                        sendBroadcast(Intent(ListenerService.ACTION_CALL_ACCEPT).setPackage(packageName))
                        uiLog("CALL: accept (single-tap fired)")
                    }
                    pendingCallAccept = runnable
                    callKeyHandler.postDelayed(runnable, DOUBLE_TAP_THRESHOLD_MS)
                }
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                // Consume BACK during incoming: use double-tap to decline.
                return true
            }
        }
        if (focusState == FocusState.CALL_ACTIVE) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                sendBroadcast(Intent(ListenerService.ACTION_CALL_TERMINATE).setPackage(packageName))
                uiLog("CALL: terminate (tap)")
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                // Don't leave the screen mid-call.
                return true
            }
        }

        // BACK navigates up: cancel session -> unfocus -> hide app
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (focusState == FocusState.STOP_MODAL) {
                hideStopModal()
                return true
            }
            if (focusState == FocusState.STEPS_MODAL) {
                hideStepsModal()
                return true
            }
            // Cancel active session FIRST, regardless of focus state
            if (serviceState in listOf("LISTENING", "RESPONDING")) {
                uiLog("KEY: BACK cancel session (was focus=$focusState)")
                sendBroadcast(Intent(ListenerService.ACTION_CANCEL_SESSION).apply {
                    setPackage(packageName)
                })
                if (focusState != FocusState.TAB_NAV) {
                    focusState = FocusState.TAB_NAV
                    updateFocusVisual(focusState)
                }
                return true
            }
            // Normal focus navigation (no active session)
            if (focusState == FocusState.REID_INTEL_MODAL) {
                hideReidIntelModal()
                return true
            }
            if (focusState == FocusState.REID_FACES_FOCUSED) {
                focusState = FocusState.REID_FOCUSED
                updateReidFaceBar()
                updateFocusVisual(focusState)
                return true
            }
            if (focusState == FocusState.REID_FOCUSED) {
                focusState = FocusState.TAB_NAV
                reidFocusedElement = 0
                reidFaceBar.background = null
                reidStartStopContainer.background = null
                updateFocusVisual(focusState)
                return true
            }
            if (focusState == FocusState.TODO_FOCUSED) {
                when (todoFocusLevel) {
                    2 -> { todoFocusLevel = 1; hideMessageDetail() }
                    1 -> { todoFocusLevel = 0; todoChecklistAdapter.setFocused(false); todoSavedAdapter.setFocused(false); updateTodoSubTabLabels() }
                    else -> { focusState = FocusState.TAB_NAV; updateTodoSubTabLabels(); updateFocusVisual(focusState) }
                }
                return true
            }
            if (focusState == FocusState.NIGHTVISION_FOCUSED) {
                focusState = FocusState.TAB_NAV
                updateFocusVisual(focusState)
                return true
            }
            if (focusState == FocusState.MUSIC_FOCUSED) {
                focusState = FocusState.TAB_NAV
                musicPlayPauseContainer.setBackgroundColor(Lum.VOID)
                updateFocusVisual(focusState)
                return true
            }
            if (focusState == FocusState.MOUSE_FOCUSED) {
                if (dpadHandler.trackingEnabled) {
                    // Double-tap (BACK) toggles tracking OFF on the ACTIVE path only -- never both,
                    // or two HeadTrackers (RFCOMM + HID) would run at once.
                    if (rfcommMouseActive) sendRfcommMouseEvent(toggle = true)
                    else mouseService?.toggleTracking()
                    return true
                }
                focusState = FocusState.TAB_NAV
                updateFocusVisual(focusState)
                return true
            }
            if (focusState == FocusState.TELEGRAM_PREVIEW) {
                telegramCancelSend()
                return true
            }
            if (focusState == FocusState.TELEGRAM_RECORDING) {
                telegramStopVoice()
                telegramVoiceOverlay.visibility = View.GONE
                focusState = FocusState.TELEGRAM_CHAT_FOCUSED
                return true
            }
            if (focusState == FocusState.NOTIFICATION_REPLY) {
                // BACK during a reply (recording or the 3s send window) aborts it.
                notifReplyCancel()
                return true
            }
            if (focusState == FocusState.TELEGRAM_CHAT_FOCUSED) {
                if (telegramOpenChatIsForum && telegramOpenTopicId > 0) {
                    // Back from topic messages → topics list
                    telegramOpenTopicId = 0
                    telegramChatContainer.visibility = View.GONE
                    telegramChatHeader.visibility = View.GONE
                    telegramTopicsRecycler.visibility = View.VISIBLE
                    focusState = FocusState.TELEGRAM_TOPICS_FOCUSED
                    telegramTopicListAdapter.setFocused(true)
                } else {
                    telegramCloseChat()
                }
                return true
            }
            if (focusState == FocusState.TELEGRAM_TOPICS_FOCUSED) {
                // Back from topics → chat list
                telegramTopicsRecycler.visibility = View.GONE
                telegramOpenChatIsForum = false
                telegramEnterChatList()
                return true
            }
            if (focusState == FocusState.TELEGRAM_LIST_FOCUSED) {
                focusState = FocusState.TAB_NAV
                updateFocusVisual(focusState)
                return true
            }
            if (focusState != FocusState.TAB_NAV) {
                uiLog("NAV: BACK catch-all $focusState -> TAB_NAV tab=$currentTab/${activeTabs.getOrNull(currentTab)}")
                if (focusState == FocusState.TELEPROMPTER_FOCUSED) {
                    teleprompterController?.pause()
                    tpFocusedIndex = 1
                }
                focusState = FocusState.TAB_NAV
                updateFocusVisual(focusState)
                return true
            }
            // Home app -- turn screen off instead of showing Sprite Launcher
            uiLog("NAV: BACK screen-off tab=$currentTab/${activeTabs.getOrNull(currentTab)} tabs=[${activeTabs.joinToString()}]")
            turnScreenOff()
            return true
        }

        // Double-tap to cancel listening or TTS playback
        // First tap is consumed (shows hint), second tap cancels
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            && serviceState in listOf("LISTENING", "RESPONDING")) {
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - lastCenterPressTime
            uiLog("KEY: CENTER/ENTER state=$serviceState focus=$focusState elapsed=${elapsed}ms threshold=$DOUBLE_TAP_THRESHOLD_MS")
            if (elapsed < DOUBLE_TAP_THRESHOLD_MS) {
                uiLog("KEY: DOUBLE-TAP CANCEL sending ACTION_CANCEL_SESSION")
                sendBroadcast(Intent(ListenerService.ACTION_CANCEL_SESSION).apply {
                    setPackage(packageName)
                })
                lastCenterPressTime = 0L
                hideDoubleTapHint()
                return true
            }
            uiLog("KEY: first tap consumed, showing hint")
            lastCenterPressTime = now
            showDoubleTapHintPersistent()
            return true
        }

        // Camera preview mode: no navigation (unless on a tab with its own containers)
        val onTelegramTab = focusState in listOf(
            FocusState.TELEGRAM_LIST_FOCUSED,
            FocusState.TELEGRAM_TOPICS_FOCUSED, FocusState.TELEGRAM_CHAT_FOCUSED,
            FocusState.TELEGRAM_RECORDING, FocusState.TELEGRAM_PREVIEW
        )
        val hasOwnHandler = focusState in listOf(
            FocusState.TAB_NAV, FocusState.MUSIC_FOCUSED, FocusState.MOUSE_FOCUSED,
            FocusState.NIGHTVISION_FOCUSED, FocusState.MAP_FOCUSED, FocusState.MAP_ZOOM_FOCUSED,
            FocusState.TRANSLATE_FOCUSED, FocusState.TELEPROMPTER_FOCUSED,
            FocusState.TODO_FOCUSED, FocusState.REID_FOCUSED, FocusState.REID_FACES_FOCUSED,
            FocusState.REID_INTEL_MODAL, FocusState.STOP_MODAL
        )
        if (chatContainer.visibility != View.VISIBLE && !onTelegramTab && !hasOwnHandler) {
            uiLog("NAV: GATE BLOCKED key=$keyCode focus=$focusState chatVis=${chatContainer.visibility} tab=$currentTab/${activeTabs.size}")
            return super.onKeyDown(keyCode, event)
        }

        when (focusState) {
            FocusState.TAB_NAV -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val throttled = isScrollThrottled()
                        val next = (currentTab + 1).coerceAtMost(maxTab)
                        uiLog("TAB_NAV RIGHT: cur=$currentTab next=$next max=$maxTab throttled=$throttled")
                        if (!throttled && next != currentTab) switchToTab(next)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> {
                        val throttled = isScrollThrottled()
                        val prev = (currentTab - 1).coerceAtLeast(0)
                        uiLog("TAB_NAV LEFT: cur=$currentTab prev=$prev max=$maxTab throttled=$throttled")
                        if (!throttled && prev != currentTab) switchToTab(prev)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        when (activeTabs.getOrNull(currentTab)) {
                            TabId.TELEPROMPTER -> {
                                armDoubleTapWindow()
                                tpFocusedIndex = 1  // start at content
                                focusState = FocusState.TELEPROMPTER_FOCUSED
                                teleprompterController?.resume()
                                updateFocusVisual(focusState)
                            }
                            TabId.MAP -> {
                                armDoubleTapWindow()
                                focusState = FocusState.MAP_FOCUSED
                                updateFocusVisual(focusState)
                            }
                            TabId.TRANSLATE -> {
                                armDoubleTapWindow()
                                focusState = FocusState.TRANSLATE_FOCUSED
                                updateFocusVisual(focusState)
                            }
                            TabId.REID -> {
                                armDoubleTapWindow()
                                focusState = FocusState.REID_FOCUSED
                                updateFocusVisual(focusState)
                            }
                            TabId.TODO -> {
                                armDoubleTapWindow()
                                focusState = FocusState.TODO_FOCUSED
                                todoFocusLevel = 0  // start at sub-tab switcher
                                todoChecklistAdapter.setFocused(false)
                                todoSavedAdapter.setFocused(false)
                                updateTodoSubTabLabels()
                                updateFocusVisual(focusState)
                            }
                            TabId.NIGHTVISION -> {
                                armDoubleTapWindow()
                                focusState = FocusState.NIGHTVISION_FOCUSED
                                updateFocusVisual(focusState)
                            }
                            TabId.MOUSE -> {
                                focusState = FocusState.MOUSE_FOCUSED
                                updateFocusVisual(focusState)
                            }
                            TabId.MUSIC -> {
                                armDoubleTapWindow()
                                focusState = FocusState.MUSIC_FOCUSED
                                updateFocusVisual(focusState)
                            }
                            TabId.CHAT -> {
                                if (chatAdapter.itemCount > 0) {
                                    armDoubleTapWindow()
                                    focusState = FocusState.CHAT_FOCUSED
                                    updateFocusVisual(focusState)
                                }
                            }
                            TabId.CHAT_LIST -> {
                                armDoubleTapWindow()
                                focusState = FocusState.LIST_FOCUSED
                                updateFocusVisual(focusState)
                            }
                            TabId.TELEGRAM -> {
                                armDoubleTapWindow()
                                telegramEnterChatList()
                                updateFocusVisual(focusState)
                            }
                            else -> {}
                        }
                        return true
                    }
                }
            }
            FocusState.CHAT_FOCUSED -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (!isScrollThrottled()) {
                            if (!chatRecycler.canScrollVertically(1)) {
                                // At bottom edge -- push selection to last message
                                val last = chatAdapter.lastSelectablePosition()
                                if (last >= 0 && last != chatAdapter.selectedPosition) {
                                    chatAdapter.selectPosition(last)
                                }
                            } else {
                                ScrollDrainer.enqueueY(chatRecycler, CHAT_SCROLL_STEP_PX)
                            }
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> {
                        if (!isScrollThrottled()) {
                            if (!chatRecycler.canScrollVertically(-1)) {
                                // At top edge -- push selection to first message
                                val first = chatAdapter.nextSelectablePosition(-1, 1)
                                if (first >= 0 && first != chatAdapter.selectedPosition) {
                                    chatAdapter.selectPosition(first)
                                }
                            } else {
                                ScrollDrainer.enqueueY(chatRecycler, -CHAT_SCROLL_STEP_PX)
                            }
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        uiLog("KEY: CENTER in CHAT_FOCUSED state=$serviceState isDoubleTap=${(SystemClock.elapsedRealtime() - lastCenterPressTime) < DOUBLE_TAP_THRESHOLD_MS}")
                        if (isDoubleTap()) {
                            uiLog("KEY: CHAT_FOCUSED double-tap -> TAB_NAV")
                            chatAdapter.clearSelection()
                            focusState = FocusState.TAB_NAV
                            updateFocusVisual(focusState)
                            lastCenterPressTime = 0L
                        }
                        return true
                    }
                }
            }
            FocusState.LIST_FOCUSED -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (!isScrollThrottled()) {
                            chatListAdapter.moveSelectionDown()
                            ensureSelectedVisible()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> {
                        if (!isScrollThrottled()) {
                            chatListAdapter.moveSelectionUp()
                            ensureSelectedVisible()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (isDoubleTap()) {
                            pendingTapRunnable?.let { mainHandler.removeCallbacks(it) }
                            pendingTapRunnable = null
                            focusState = FocusState.TAB_NAV
                            updateFocusVisual(focusState)
                            lastCenterPressTime = 0L
                        } else {
                            pendingTapRunnable?.let { mainHandler.removeCallbacks(it) }
                            // Capture the origin NOW. This runnable fires 400 ms later,
                            // long after dispatchRemoteKey's finally has restored
                            // currentInputOrigin, so reading it inside the runnable would
                            // always say TOUCHPAD and the guard below would never fire.
                            val tapOrigin = currentInputOrigin
                            val runnable = Runnable {
                                if (chatListAdapter.isNewChatSelected()) {
                                    openNewChat()
                                } else if (chatListAdapter.isAssistantSelected()) {
                                    // The one dangerous row in this state: it toggles the
                                    // assistant, which starts the microphone. Checked HERE
                                    // rather than in the gate, because the selection can
                                    // move during the 400 ms this waits -- a scroll later
                                    // in the same burst would otherwise re-aim a tap that
                                    // was judged safe onto this row.
                                    if (tapOrigin == InputOrigin.REMOTE) {
                                        uiLog("[RemoteInput] refused TAP on the Assistant row (starts the mic)")
                                    } else {
                                        openAssistant()
                                    }
                                } else {
                                    openSelectedChat()
                                }
                            }
                            pendingTapRunnable = runnable
                            mainHandler.postDelayed(runnable, DOUBLE_TAP_THRESHOLD_MS)
                        }
                        return true
                    }
                }
            }
            FocusState.MAP_FOCUSED -> {
                // 0=steps, 1=stop, 2=zoom-slider, 3=pin
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> {
                        val now = System.currentTimeMillis()
                        if (now - lastMapButtonScrollTime < 400) return true
                        lastMapButtonScrollTime = now
                        if (mapFocusedIndex > 0) {
                            mapFocusedIndex--
                            updateFocusVisual(focusState)
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val now = System.currentTimeMillis()
                        if (now - lastMapButtonScrollTime < 400) return true
                        lastMapButtonScrollTime = now
                        if (mapFocusedIndex < MAP_FOCUS_MAX) {
                            mapFocusedIndex++
                            updateFocusVisual(focusState)
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (isDoubleTap()) {
                            pendingTapRunnable?.let { mainHandler.removeCallbacks(it) }
                            pendingTapRunnable = null
                            mapFocusedIndex = MAP_FOCUS_PIN  // reset to pin for next entry
                            focusState = FocusState.TAB_NAV
                            updateFocusVisual(focusState)
                            lastCenterPressTime = 0L
                        } else {
                            pendingTapRunnable?.let { mainHandler.removeCallbacks(it) }
                            val runnable = Runnable {
                                when (mapFocusedIndex) {
                                    0 -> showStepsModal()
                                    1 -> showStopModal()
                                    MAP_FOCUS_ZOOM -> {
                                        focusState = FocusState.MAP_ZOOM_FOCUSED
                                        updateFocusVisual(focusState)
                                    }
                                    MAP_FOCUS_PIN -> toggleMapPin()
                                }
                            }
                            pendingTapRunnable = runnable
                            mainHandler.postDelayed(runnable, DOUBLE_TAP_THRESHOLD_MS)
                        }
                        return true
                    }
                }
            }
            FocusState.MAP_ZOOM_FOCUSED -> {
                // Slider mode: DPAD_L/R steps zoom and immediately refreshes
                // the bitmap (phone-side). DPAD_CENTER returns to MAP_FOCUSED
                // so the user can move on to other buttons.
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> {
                        val now = System.currentTimeMillis()
                        if (now - lastMapButtonScrollTime < 200) return true
                        lastMapButtonScrollTime = now
                        stepZoom(-1)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val now = System.currentTimeMillis()
                        if (now - lastMapButtonScrollTime < 200) return true
                        lastMapButtonScrollTime = now
                        stepZoom(1)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        focusState = FocusState.MAP_FOCUSED
                        updateFocusVisual(focusState)
                        return true
                    }
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                        focusState = FocusState.MAP_FOCUSED
                        updateFocusVisual(focusState)
                        return true
                    }
                }
            }
            FocusState.STOP_MODAL -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> {
                        modalSelectedIndex = 0
                        updateModalButtons()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        modalSelectedIndex = 1
                        updateModalButtons()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (isDoubleTap()) {
                            hideStopModal()
                            lastCenterPressTime = 0L
                        } else {
                            if (modalSelectedIndex == 0) confirmStopJourney() else hideStopModal()
                        }
                        return true
                    }
                }
            }
            FocusState.STEPS_MODAL -> {
                val scrollAmount = (200 * Resources.getSystem().displayMetrics.density).toInt()
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        ScrollDrainer.enqueueY(stepsScrollView, scrollAmount)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> {
                        ScrollDrainer.enqueueY(stepsScrollView, -scrollAmount)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        hideStepsModal()
                        return true
                    }
                }
            }
            FocusState.TRANSLATE_FOCUSED -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (isDoubleTap()) {
                            focusState = FocusState.TAB_NAV
                            updateFocusVisual(focusState)
                            lastCenterPressTime = 0L
                        } else {
                            // Toggle translation start/stop
                            if (!translationActive && !translationStarting) {
                                // Starting -- show loading indicator (text-based, no graphical spinner)
                                translationStarting = true
                                translationStatus.text = "Starting..."
                                translationStatus.setTextColor(Lum.MID)
                                translationStartStopIcon.alpha = 0.3f
                                mainHandler.removeCallbacks(translationStartTimeoutRunnable)
                                mainHandler.postDelayed(translationStartTimeoutRunnable, 10_000L)
                            }
                            sendBroadcast(Intent(ListenerService.ACTION_REQUEST_TRANSLATION_TOGGLE).apply { setPackage(packageName) })
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        ScrollDrainer.enqueueY(translationScrollView, 200)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> {
                        ScrollDrainer.enqueueY(translationScrollView, -200)
                        return true
                    }
                }
            }
            FocusState.REID_FOCUSED -> {
                // Depth 1: scroll between start/stop (element 0) and face bar (element 1)
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (isDoubleTap()) {
                            focusState = FocusState.TAB_NAV
                            updateFocusVisual(focusState)
                            lastCenterPressTime = 0L
                        } else {
                            if (reidFocusedElement == 0) {
                                toggleReid()
                            } else {
                                // Enter face bar (depth 2) if faces exist
                                if (reidVerifiedFaces.isNotEmpty()) {
                                    focusState = FocusState.REID_FACES_FOCUSED
                                    if (reidSelectedFaceIndex < 0) reidSelectedFaceIndex = 0
                                    updateReidFaceBar()
                                    requestPersonIntel()
                                    updateFocusVisual(focusState)
                                }
                            }
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (reidFocusedElement == 0 && reidVerifiedFaces.isNotEmpty()) {
                            reidFocusedElement = 1
                            updateFocusVisual(focusState)
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> {
                        if (reidFocusedElement == 1) {
                            reidFocusedElement = 0
                            updateFocusVisual(focusState)
                        }
                        return true
                    }
                }
            }
            FocusState.REID_FACES_FOCUSED -> {
                // Depth 2: scroll individual faces
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (isDoubleTap()) {
                            // Back to depth 1
                            focusState = FocusState.REID_FOCUSED
                            updateReidFaceBar()
                            updateFocusVisual(focusState)
                            lastCenterPressTime = 0L
                        } else {
                            // Open intel modal (depth 3). OSINT-gated: when
                            // ENABLE_REID_OSINT is off the intel modal is not
                            // offered (core face re-id remains usable).
                            val face = reidVerifiedFaces.getOrNull(reidSelectedFaceIndex)
                            val uid = face?.optString("uid", "") ?: ""
                            if (BuildConfig.ENABLE_REID_OSINT && uid.isNotEmpty()) {
                                showReidIntelModal(reidLastPersonIntel)
                            }
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (reidVerifiedFaces.isNotEmpty()) {
                            reidSelectedFaceIndex = (reidSelectedFaceIndex + 1) % reidVerifiedFaces.size
                            updateReidFaceBar()
                            requestPersonIntel()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> {
                        if (reidVerifiedFaces.isNotEmpty()) {
                            reidSelectedFaceIndex = (reidSelectedFaceIndex - 1 + reidVerifiedFaces.size) % reidVerifiedFaces.size
                            updateReidFaceBar()
                            requestPersonIntel()
                        }
                        return true
                    }
                }
            }
            FocusState.REID_INTEL_MODAL -> {
                // Depth 3: scroll intel modal
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        ScrollDrainer.enqueueY(reidIntelModal, (80 * resources.displayMetrics.density).toInt())
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> {
                        ScrollDrainer.enqueueY(reidIntelModal, -(80 * resources.displayMetrics.density).toInt())
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (isDoubleTap()) {
                            hideReidIntelModal()
                            lastCenterPressTime = 0L
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        hideReidIntelModal()
                        return true
                    }
                }
            }
            FocusState.TODO_FOCUSED -> {
                when (todoFocusLevel) {
                    // Level 0: Sub-tab switcher (Tasks / Alarms / Jobs / Saved)
                    // LEFT/RIGHT switches sub-tabs, tap enters content, double-tap -> TAB_NAV
                    0 -> when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            val entries = TodoSubTab.entries
                            val idx = todoSubTab.ordinal
                            if (idx > 0) {
                                todoSubTab = entries[idx - 1]
                                updateTodoSubTabLabels()
                                requestTodoData()
                                if (todoSubTab == TodoSubTab.SAVED) startTodoPollIfNeeded()
                            }
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            val entries = TodoSubTab.entries
                            val idx = todoSubTab.ordinal
                            if (idx < entries.size - 1) {
                                todoSubTab = entries[idx + 1]
                                updateTodoSubTabLabels()
                                requestTodoData()
                                if (todoSubTab == TodoSubTab.SAVED) startTodoPollIfNeeded()
                            }
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            if (isDoubleTap()) {
                                focusState = FocusState.TAB_NAV
                                updateTodoSubTabLabels()
                                updateFocusVisual(focusState)
                                lastCenterPressTime = 0L
                            } else {
                                val adapter = getActiveTodoAdapter()
                                when (todoSubTab) {
                                    TodoSubTab.TASKS, TodoSubTab.JOBS, TodoSubTab.ALARMS -> {
                                        if (adapter.adapterItemCount == 0) return true
                                        todoFocusLevel = 1
                                        updateTodoSubTabLabels()
                                        adapter.setFocused(true)
                                        if (adapter.selectedPosition < 0) adapter.selectPosition(0)
                                        if (todoSubTab == TodoSubTab.TASKS) {
                                            todoChecklistRecycler?.post { updateTodoChecklistCursor(animate = false) }
                                        }
                                    }
                                    TodoSubTab.SAVED -> {
                                        if (todoSavedAdapter.itemCount == 0 && !todoHasError) return true
                                        val wasError = todoHasError
                                        todoFocusLevel = 1
                                        updateTodoSubTabLabels()
                                        if (wasError) {
                                            todoEmptyText?.let { Anim.fadeOut(it, 150L) }
                                            todoHasError = false
                                            requestSavedFirstPage()
                                        }
                                        adapter.setFocused(true)
                                        if (adapter.selectedPosition < 0) adapter.selectPosition(0)
                                    }
                                }
                            }
                            return true
                        }
                        KeyEvent.KEYCODE_BACK -> {
                            focusState = FocusState.TAB_NAV
                            updateTodoSubTabLabels()
                            updateFocusVisual(focusState)
                            return true
                        }
                    }

                    // Level 1: Content focused (items with selection)
                    // The touchpad only emits horizontal swipes (mapped to
                    // DPAD_LEFT/RIGHT), so pair them with UP/DOWN as aliases:
                    // back-swipe (LEFT/UP) = previous item, forward-swipe
                    // (RIGHT/DOWN) = next item. Tap acts on item, double-tap -> level 0.
                    1 -> when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> {
                            val adapter = getActiveTodoAdapter()
                            adapter.moveSelectionUp()
                            getActiveTodoRecycler()?.scrollToPosition(adapter.selectedPosition)
                            if (todoSubTab == TodoSubTab.TASKS) {
                                todoChecklistRecycler?.post { updateTodoChecklistCursor(animate = true) }
                            }
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            val adapter = getActiveTodoAdapter()
                            adapter.moveSelectionDown()
                            getActiveTodoRecycler()?.scrollToPosition(adapter.selectedPosition)
                            if (todoSubTab == TodoSubTab.TASKS) {
                                todoChecklistRecycler?.post { updateTodoChecklistCursor(animate = true) }
                            }
                            // Saved: when selection nears the bottom, fetch the next (older) page.
                            if (todoSubTab == TodoSubTab.SAVED &&
                                adapter.selectedPosition >= adapter.adapterItemCount - 3) {
                                requestSavedOlderPage()
                            }
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            if (isDoubleTap()) {
                                todoFocusLevel = 0
                                getActiveTodoAdapter().setFocused(false)
                                updateTodoChecklistCursor(animate = false)
                                updateTodoSubTabLabels()
                                lastCenterPressTime = 0L
                            } else when (todoSubTab) {
                                TodoSubTab.TASKS -> {
                                    val item = todoChecklistAdapter.getSelectedItem()
                                    if (item != null) {
                                        // Tapping a still-struck (not yet fading) item un-marks it:
                                        // cancel the completion choreography and toggle it back. Each
                                        // tap (mark or un-mark) sends the same backend toggle so the
                                        // server stays in sync with the optimistic local state.
                                        if (todoChecklistAdapter.isUndoable(item.id)) {
                                            todoChecklistAdapter.toggleUndoIfPending(item.id)
                                        } else if (!item.completed) {
                                            // Optimistically strike + run the animation now so it
                                            // plays even if the backend echo is slow/absent.
                                            todoChecklistAdapter.markCompletedLocally(item.id)
                                        }
                                        sendBroadcast(Intent(ListenerService.ACTION_TODO_TOGGLE).apply {
                                            setPackage(packageName)
                                            putExtra(ListenerService.EXTRA_TODO_ID, item.id)
                                        })
                                    }
                                }
                                TodoSubTab.SAVED -> {
                                    val msg = todoSavedAdapter.getSelectedMessage()
                                    if (msg != null) {
                                        todoFocusLevel = 2
                                        showMessageDetail(msg.text)
                                    }
                                }
                                TodoSubTab.JOBS -> {
                                    val job = todoJobAdapter.getSelectedItem()
                                    if (job != null) {
                                        val detail = buildString {
                                            append(job.name)
                                            append("\n\nStatus: ${job.status}")
                                            if (!job.prompt.isNullOrBlank()) append("\n\nPrompt: ${job.prompt}")
                                            if (!job.result.isNullOrBlank()) append("\n\nResult: ${job.result}")
                                            if (!job.error.isNullOrBlank()) append("\n\nError: ${job.error}")
                                        }
                                        todoFocusLevel = 2
                                        showMessageDetail(detail)
                                    }
                                }
                                TodoSubTab.ALARMS -> { /* read-only, no action on tap */ }
                            }
                            return true
                        }
                        KeyEvent.KEYCODE_BACK -> {
                            todoFocusLevel = 0
                            getActiveTodoAdapter().setFocused(false)
                            updateTodoChecklistCursor(animate = false)
                            updateTodoSubTabLabels()
                            return true
                        }
                    }

                    // Level 2: Message detail (full-view overlay)
                    // Horizontal swipes (LEFT/RIGHT) alias UP/DOWN to scroll text.
                    2 -> when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> {
                            todoMessageScrollView?.let { ScrollDrainer.enqueueY(it, -80) }
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            todoMessageScrollView?.let { ScrollDrainer.enqueueY(it, 80) }
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> {
                            todoFocusLevel = 1
                            hideMessageDetail()
                            return true
                        }
                    }
                }
            }
            FocusState.NIGHTVISION_FOCUSED -> {
                val now = SystemClock.elapsedRealtime()
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (isDoubleTap()) {
                            // Double-tap: exit to TAB_NAV
                            nvSliderIndex = -1
                            nvSliderLocked = false
                            focusState = FocusState.TAB_NAV
                            updateNvSliderVisuals()
                            updateFocusVisual(focusState)
                            lastCenterPressTime = 0L
                        } else {
                            // Tap: toggle lock on current slider (default to 0 if none selected)
                            if (nvSliderIndex == -1) nvSliderIndex = 0
                            nvSliderLocked = !nvSliderLocked
                            updateNvSliderVisuals()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (now - lastNvSwipeTime < NV_SWIPE_DEBOUNCE_MS) return true
                        lastNvSwipeTime = now
                        if (nvSliderLocked) {
                            sendNvSliderAdjust(nvSliderIndex, +1)
                        } else {
                            // Cycle slider selection: -1 -> 0 -> 1 -> -1
                            nvSliderIndex = if (nvSliderIndex >= 1) -1 else nvSliderIndex + 1
                            updateNvSliderVisuals()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> {
                        if (now - lastNvSwipeTime < NV_SWIPE_DEBOUNCE_MS) return true
                        lastNvSwipeTime = now
                        if (nvSliderLocked) {
                            sendNvSliderAdjust(nvSliderIndex, -1)
                        } else {
                            // Cycle slider selection: -1 -> 1 -> 0 -> -1
                            nvSliderIndex = if (nvSliderIndex <= -1) 1 else nvSliderIndex - 1
                            updateNvSliderVisuals()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        if (nvSliderLocked) {
                            nvSliderLocked = false
                            updateNvSliderVisuals()
                        } else {
                            nvSliderIndex = -1
                            updateNvSliderVisuals()
                            focusState = FocusState.TAB_NAV
                            updateFocusVisual(focusState)
                        }
                        return true
                    }
                }
            }
            FocusState.MUSIC_FOCUSED -> {
                val now = SystemClock.elapsedRealtime()
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (isDoubleTap()) {
                            focusState = FocusState.TAB_NAV
                            musicPlayPauseContainer.setBackgroundColor(Lum.VOID)
                            updateFocusVisual(focusState)
                            lastCenterPressTime = 0L
                        } else {
                            if (lastMusicActionType == "skip" && now - lastMusicActionTime < MUSIC_CROSS_ACTION_COOLDOWN_MS) {
                                return true // cooldown: ignore toggle right after skip
                            }
                            lastMusicActionType = "toggle"
                            lastMusicActionTime = now
                            musicIsPlaying = !musicIsPlaying
                            musicPlayPauseIcon.setImageResource(
                                if (musicIsPlaying) R.drawable.ic_pause else R.drawable.ic_play
                            )
                            musicPlayPauseIcon.setColorFilter(Lum.SOFT, android.graphics.PorterDuff.Mode.SRC_IN)
                            Anim.pulseButton(musicPlayPauseContainer)
                            sendMusicCommand(if (musicIsPlaying) "play" else "pause")
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (lastMusicActionType == "toggle" && now - lastMusicActionTime < MUSIC_CROSS_ACTION_COOLDOWN_MS) {
                            return true
                        }
                        lastMusicActionType = "skip"
                        lastMusicActionTime = now
                        Anim.pulseButton(musicNextContainer)
                        sendMusicCommand("next")
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> {
                        if (lastMusicActionType == "toggle" && now - lastMusicActionTime < MUSIC_CROSS_ACTION_COOLDOWN_MS) {
                            return true
                        }
                        lastMusicActionType = "skip"
                        lastMusicActionTime = now
                        Anim.pulseButton(musicPrevContainer)
                        sendMusicCommand("prev")
                        return true
                    }
                }
            }
            FocusState.MOUSE_FOCUSED -> {
                if (dpadHandler.trackingEnabled) {
                    if (dpadHandler.onKeyDown(keyCode, event)) return true
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    // Not yet tracking: a tap starts tracking on the ACTIVE path only (never both).
                    if (rfcommMouseActive) sendRfcommMouseEvent(toggle = true)
                    else mouseService?.toggleTracking()
                    return true
                }
                // Consume all DPAD in mouse mode to prevent tab switching
                if (keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
                    return true
                }
            }
            FocusState.TELEPROMPTER_FOCUSED -> {
                val ctrl = teleprompterController
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (isDoubleTap()) {
                            ctrl?.pause()
                            tpFocusedIndex = 1
                            focusState = FocusState.TAB_NAV
                            updateFocusVisual(focusState)
                            lastCenterPressTime = 0L
                        } else {
                            if (tpFocusedIndex == 0) {
                                // Stop button: stop teleprompter
                                stopTeleprompter()
                            } else {
                                ctrl?.togglePause()
                            }
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (tpFocusedIndex == 0) {
                            // Navigate from stop button to content
                            tpFocusedIndex = 1
                            updateFocusVisual(focusState)
                        } else {
                            ctrl?.scrollBy(200)
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> {
                        if (tpFocusedIndex == 1) {
                            // Navigate from content to stop button
                            tpFocusedIndex = 0
                            updateFocusVisual(focusState)
                        }
                        return true
                    }
                }
            }
            FocusState.TELEGRAM_LIST_FOCUSED -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (!isScrollThrottled()) {
                            telegramChatListAdapter.moveSelectionDown()
                            telegramChatListRecycler.smoothScrollToPosition(telegramChatListAdapter.selectedPosition.coerceAtLeast(0))
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> {
                        if (!isScrollThrottled()) {
                            telegramChatListAdapter.moveSelectionUp()
                            telegramChatListRecycler.smoothScrollToPosition(telegramChatListAdapter.selectedPosition.coerceAtLeast(0))
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        val chat = telegramChatListAdapter.getSelectedChat()
                        if (chat != null) {
                            android.util.Log.d("TG_DEBUG", "Opening chat: id=${chat.chatId} title=${chat.title}")
                            telegramOpenChat(chat)
                        } else {
                            android.util.Log.d("TG_DEBUG", "No chat selected, selectedPosition=${telegramChatListAdapter.selectedPosition}")
                        }
                        return true
                    }
                }
            }
            FocusState.TELEGRAM_TOPICS_FOCUSED -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (!isScrollThrottled()) {
                            telegramTopicListAdapter.moveSelectionDown()
                            telegramTopicsRecycler.smoothScrollToPosition(telegramTopicListAdapter.selectedPosition.coerceAtLeast(0))
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> {
                        if (!isScrollThrottled()) {
                            telegramTopicListAdapter.moveSelectionUp()
                            telegramTopicsRecycler.smoothScrollToPosition(telegramTopicListAdapter.selectedPosition.coerceAtLeast(0))
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        val topic = telegramTopicListAdapter.getSelectedTopic()
                        if (topic != null) {
                            telegramOpenTopic(topic)
                        }
                        return true
                    }
                }
            }
            FocusState.TELEGRAM_CHAT_FOCUSED -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (!isScrollThrottled()) {
                            ScrollDrainer.enqueueY(telegramChatRecycler, CHAT_SCROLL_STEP_PX)
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> {
                        if (!isScrollThrottled()) {
                            ScrollDrainer.enqueueY(telegramChatRecycler, -CHAT_SCROLL_STEP_PX)
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        // Start voice recording for dictation
                        telegramStartVoice()
                        return true
                    }
                }
            }
            FocusState.TELEGRAM_RECORDING -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        // Stop recording, wait for transcription
                        telegramStopVoice()
                        return true
                    }
                }
                // Consume all other keys during recording
                return true
            }
            FocusState.TELEGRAM_PREVIEW -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        // Confirm send immediately
                        telegramConfirmSend()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> {
                        // Cancel send
                        telegramCancelSend()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        // Re-record
                        telegramCancelSend()
                        telegramStartVoice()
                        return true
                    }
                }
            }
            FocusState.NOTIFICATION_REPLY -> {
                // Release-to-send model: sending is driven by the finger-release
                // (NUMPAD_2) and cancel by a swipe (NUMPAD_0/1) / BACK, all handled
                // earlier in onKeyDown. Consume any remaining keys during reply.
                return true
            }
            FocusState.CALL_INCOMING, FocusState.CALL_ACTIVE -> {
                // Call overlay handles its own input; fall through to default.
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (focusState == FocusState.CHAT_FOCUSED &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_LEFT)) {
            chatAdapter.clear()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_CAMERA) {
            sendBroadcast(Intent(com.repository.glasses.listener.service.ScreenOffAccessibilityService.ACTION_FN_KEY).apply {
                setPackage(packageName)
                putExtra(com.repository.glasses.listener.service.ScreenOffAccessibilityService.EXTRA_EVENT_ACTION, "UP")
                putExtra(com.repository.glasses.listener.service.ScreenOffAccessibilityService.EXTRA_REPEAT, 0)
            })
            return true
        }
        if (focusState == FocusState.MOUSE_FOCUSED) {
            if (dpadHandler.onKeyUp(keyCode, event)) return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun ensureSelectedVisible() {
        val pos = chatListAdapter.selectedPosition
        if (pos >= 0) {
            chatListRecycler.scrollToPosition(pos)
        }
    }

    // --- Utility ---

    /** Debug log: writes to debugStatus text on screen */
    private fun dbg(msg: String) {
        runOnUiThread { debugStatus.text = msg }
    }

    private fun activityLog(msg: String) {
        GlassesListenerApp.writeCrashLog(this, "ACTIVITY: $msg")
    }

    private fun bindBackend() {
        bindService(
            Intent(this, ListenerService::class.java),
            backendConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onResume() = GT.section("ui.onResume") {
        super.onResume()
        // Cancel launch notification if it brought us here
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(9999)
        } catch (_: Exception) {}
        // Anti-stuck-black: if we resume while the black cover is still attached but the solo
        // session is NO LONGER armed, force it visible -- that means the blackout outlived its
        // notification (e.g. user navigated away and back). We must NOT reveal while the solo is
        // still armed: the notification's FLAG_TURN_SCREEN_ON wakes the panel and triggers
        // onResume DURING a legitimate blackout, and clearing it here would defeat the feature.
        // The armed blackout stays protected from getting stuck by the key-press reveal, the
        // SOLO_END broadcast, and the 20s timeout failsafe.
        if (!notifSoloArmed && (soloContentHidden || soloCoverView?.parent != null)) {
            revealFromSolo("onResume-safety")
        }
        activityLog("onResume")
    }

    override fun onPause() = GT.section("ui.onPause") {
        activityLog("onPause")
        // Discard any in-flight KEYCODE_CAMERA press -- screen off / focus change means we'll
        // never see a matching onKeyUp, which would freeze the state machine for the next press.
        // FunctionButtonHandler lives in ListenerService now; nothing to reset here.
        super.onPause()
    }

    override fun onStop() = GT.section("ui.onStop") {
        activityLog("onStop isFinishing=${isFinishing} isChangingConfigurations=${isChangingConfigurations}")
        super.onStop()
    }

    // --- Bottom padding receiver (display position from phone) ---

    private val mediaStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val track = intent?.getStringExtra(ListenerService.EXTRA_MEDIA_TRACK) ?: ""
            val playing = intent?.getBooleanExtra(ListenerService.EXTRA_MEDIA_PLAYING, false) ?: false
            val position = intent?.getLongExtra(ListenerService.EXTRA_MEDIA_POSITION, -1L) ?: -1L
            val duration = intent?.getLongExtra(ListenerService.EXTRA_MEDIA_DURATION, -1L) ?: -1L
            val positionTs = intent?.getLongExtra(ListenerService.EXTRA_MEDIA_POSITION_TS, 0L) ?: 0L
            runOnUiThread {
                // Tab visibility is tied ONLY to the A2DP sink connection
                // state (driven by a2dpSinkStateReceiver). Track text may go
                // empty transiently during AVRCP state flaps (e.g. rapid
                // playing<->paused); don't flip the tab on that -- it would
                // thrash the pill layout and interrupt audio focus.
                updateMusicState(track, playing)
                updateMusicProgressSample(position, duration, positionTs, playing)
            }
        }
    }

    // A2DP sink connection state. Music tab is only valid while at least one
    // phone is actively connected as an A2DP source; once ALL sources
    // disconnect the AOSP BluetoothMediaBrowserService session may linger
    // with stale metadata, so we use the profile connection state as the gate.
    //
    // We track the set of connected device addresses rather than a single
    // boolean because the OS auto-connects to every paired A2DP source on
    // BT-up (post-wear/unfold). When a remembered-but-absent source
    // (e.g. a desktop that's paired but powered off) fails its connect
    // attempt, it emits a CONNECTING -> DISCONNECTED transition. With a
    // single boolean we'd treat that as "the sink is gone" and hide the
    // music tab while the actual phone is still streaming. Per-device
    // tracking lets us flip a2dpSinkConnected only when the set is empty.
    @Volatile private var a2dpSinkConnected = false
    private val a2dpSinkConnectedDevices = mutableSetOf<String>()

    private val tabVisibilityHandler = Handler(Looper.getMainLooper())
    private var pendingTabVisibilityRunnable: Runnable? = null

    private fun setMusicTabVisible(visible: Boolean) {
        pendingTabVisibilityRunnable?.let { tabVisibilityHandler.removeCallbacks(it) }
        val r = Runnable { if (visible) showMusicTab() else hideMusicTab() }
        pendingTabVisibilityRunnable = r
        tabVisibilityHandler.postDelayed(r, 300L)
    }

    private fun queryInitialA2dpSinkState() {
        // A2DP_SINK profile id = 11 (hidden constant).
        try {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return
            val listener = object : android.bluetooth.BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile?) {
                    if (profile != 11 || proxy == null) return
                    val devices = try { proxy.connectedDevices?.map { it.address } ?: emptyList() } catch (_: Throwable) { emptyList() }
                    uiLog("A2DP sink initial connectedDevices=${if (devices.isEmpty()) "none" else devices.joinToString()}")
                    runOnUiThread {
                        a2dpSinkConnectedDevices.clear()
                        a2dpSinkConnectedDevices.addAll(devices)
                        a2dpSinkConnected = a2dpSinkConnectedDevices.isNotEmpty()
                        if (!a2dpSinkConnected) hideMusicTab()
                    }
                    try { adapter.closeProfileProxy(11, proxy) } catch (_: Throwable) {}
                }
                override fun onServiceDisconnected(profile: Int) {}
            }
            adapter.getProfileProxy(this, listener, 11)
        } catch (t: Throwable) {
            uiLog("A2DP sink initial query failed: ${t.message}")
        }
    }

    private val a2dpSinkStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED") return
            val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val addr = device?.address ?: "unknown"
            // 0 DISCONNECTED, 1 CONNECTING, 2 CONNECTED, 3 DISCONNECTING
            runOnUiThread {
                val wasConnected = a2dpSinkConnected
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> a2dpSinkConnectedDevices.add(addr)
                    BluetoothProfile.STATE_DISCONNECTED -> a2dpSinkConnectedDevices.remove(addr)
                    // CONNECTING and DISCONNECTING are transient (AVRCP renegotiation
                    // emits DISCONNECTING -> CONNECTED transitions); wait for terminal state.
                }
                a2dpSinkConnected = a2dpSinkConnectedDevices.isNotEmpty()
                uiLog("A2DP sink state=$state device=$addr connected=$a2dpSinkConnected set=[${a2dpSinkConnectedDevices.joinToString()}]")
                if (a2dpSinkConnected && !wasConnected) {
                    setMusicTabVisible(true)
                } else if (!a2dpSinkConnected && wasConnected) {
                    musicProgressPlaying = false
                    musicProgressHandler.removeCallbacks(musicProgressTicker)
                    setMusicTabVisible(false)
                }
            }
        }
    }

    // Music progress extrapolation. The BroadcastReceiver samples discrete
    // positions (at most once per state change); the ticker interpolates
    // between samples while playing so the progress bar moves smoothly.
    @Volatile private var musicBasePositionMs = -1L
    @Volatile private var musicDurationMs = -1L
    @Volatile private var musicBaseTs = 0L
    @Volatile private var musicProgressPlaying = false
    private val musicProgressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val musicProgressTicker: Runnable = object : Runnable {
        override fun run() {
            renderMusicProgress()
            if (musicProgressPlaying) musicProgressHandler.postDelayed(this, 500L)
        }
    }

    private fun renderMusicProgress() {
        val dur = musicDurationMs
        val base = musicBasePositionMs
        if (dur <= 0 || base < 0 || musicProgressMaxWidth <= 0) return
        val live = if (musicProgressPlaying && musicBaseTs > 0L) {
            base + (android.os.SystemClock.elapsedRealtime() - musicBaseTs)
        } else {
            base
        }
        val fraction = (live.toFloat() / dur).coerceIn(0f, 1f)
        val fillWidth = (musicProgressMaxWidth * fraction).toInt().coerceAtLeast(1)
        val lp = musicProgressFill.layoutParams
        if (lp.width != fillWidth) {
            lp.width = fillWidth
            musicProgressFill.layoutParams = lp
        }
    }

    private fun updateMusicProgressSample(position: Long, duration: Long, positionTs: Long, playing: Boolean) {
        musicBasePositionMs = position
        musicDurationMs = duration
        musicBaseTs = positionTs
        musicProgressPlaying = playing && position >= 0 && duration > 0
        musicProgressHandler.removeCallbacks(musicProgressTicker)
        renderMusicProgress()
        if (musicProgressPlaying) musicProgressHandler.postDelayed(musicProgressTicker, 500L)
    }

    private val mediaProgressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val position = intent?.getLongExtra(ListenerService.EXTRA_MEDIA_POSITION, -1L) ?: -1L
            val duration = intent?.getLongExtra(ListenerService.EXTRA_MEDIA_DURATION, -1L) ?: -1L
            val positionTs = intent?.getLongExtra(ListenerService.EXTRA_MEDIA_POSITION_TS, 0L) ?: 0L
            // Playing state isn't in this broadcast -- keep the current one. When the
            // state receiver fires first we already have musicProgressPlaying set.
            runOnUiThread {
                updateMusicProgressSample(position, duration, positionTs, musicProgressPlaying)
            }
        }
    }

    private val bottomPaddingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val padding = intent?.getIntExtra(ListenerService.EXTRA_PADDING, 0) ?: 0
            runOnUiThread { applyBottomPadding(padding) }
        }
    }

    private val chatFontSizeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val size = intent?.getFloatExtra(ListenerService.EXTRA_CHAT_FONT_SIZE, 0f) ?: 0f
            if (size in 8f..24f) {
                com.repository.glasses.listener.config.GlassesConfig.chatFontSize = size
            }
            runOnUiThread { chatAdapter.notifyDataSetChanged() }
        }
    }

    private fun applyBottomPadding(px: Int) {
        val basePaddingBottom = (8 * resources.displayMetrics.density).toInt()
        mainContentLayout.setPadding(
            mainContentLayout.paddingLeft,
            mainContentLayout.paddingTop,
            mainContentLayout.paddingRight,
            basePaddingBottom + px
        )
    }

    // --- Telegram broadcast receivers ---

    private val tgTopicsResponseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val json = intent?.getStringExtra(ListenerService.EXTRA_TG_TOPICS_JSON) ?: return
            runOnUiThread {
                try {
                    val arr = org.json.JSONArray(json)
                    val topics = mutableListOf<com.repository.glasses.listener.ui.TelegramTopic>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        topics.add(com.repository.glasses.listener.ui.TelegramTopic(
                            id = obj.optInt("id", 0),
                            title = obj.optString("title", ""),
                            unreadCount = obj.optInt("unreadCount", 0),
                            lastMessageDate = obj.optString("lastMessageDate", "")
                        ))
                    }
                    telegramTopicListAdapter.submitList(topics)
                    telegramTopicListAdapter.setFocused(true)
                } catch (e: Exception) {
                    uiLog("TG: topics parse error: ${e.message}")
                }
            }
        }
    }

    private val tgChatListResponseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val json = intent?.getStringExtra(ListenerService.EXTRA_TG_CHAT_LIST_JSON)
                ?: intent?.getStringExtra("tg_chatlist_file")?.let { path ->
                    try { java.io.File(path).readText() } catch (_: Exception) { null }
                }
                ?: return
            android.util.Log.d("TG_DEBUG", "Chat list response received: ${json.take(200)}")
            runOnUiThread {
                try {
                    val arr = org.json.JSONArray(json)
                    val chats = mutableListOf<TelegramChat>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        chats.add(TelegramChat(
                            chatId = obj.optString("chatId", obj.optString("id", "")),
                            title = obj.optString("title", ""),
                            lastMessage = obj.optString("lastMessage", ""),
                            lastMessageDate = obj.optString("lastMessageDate", ""),
                            lastMessageSender = obj.optString("lastMessageSender", ""),
                            unreadCount = obj.optInt("unreadCount", 0),
                            chatType = obj.optString("chatType", "user"),
                            isForum = obj.optBoolean("isForum", false),
                            isOnline = obj.optBoolean("isOnline", false),
                            lastSeen = if (obj.has("lastSeen") && !obj.isNull("lastSeen")) obj.optString("lastSeen") else null,
                            avatar = if (obj.has("avatar") && !obj.isNull("avatar")) obj.optString("avatar") else null
                        ))
                    }
                    telegramChatListLoaded = true
                    telegramChatListRetry?.let { mainHandler.removeCallbacks(it) }
                    hideLoadingSpinner()
                    telegramChatListAdapter.submitList(chats)
                } catch (e: Exception) {
                    uiLog("TG: chatList parse error: ${e.message}")
                }
            }
        }
    }

    private val tgMessagesResponseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Support both inline JSON and file-based payload (for large responses)
            val json = intent?.getStringExtra(ListenerService.EXTRA_TG_MESSAGES_JSON)
                ?: intent?.getStringExtra("tg_messages_file")?.let { path ->
                    try { java.io.File(path).readText() } catch (_: Exception) { null }
                }
                ?: return
            android.util.Log.d("TG_DEBUG", "Messages response received: ${json.take(200)}")
            // The Saved sub-tab shares this telegram_messages channel. Route by the chatId the
            // response actually answers -- chatId="me" is the Saved sub-tab, anything else is the
            // Telegram chat browser. This is reliable regardless of timing (no dependence on a
            // transient in-flight flag, which dropped Saved responses when it had already cleared).
            val respChatId = intent?.getStringExtra(ListenerService.EXTRA_TG_CHAT_ID) ?: ""
            if (respChatId == SAVED_CHAT_ID) {
                runOnUiThread { parseSavedMessagesAndDisplay(json) }
                return
            }
            val chatType = telegramOpenChatType
            runOnUiThread {
                try {
                    val arr = org.json.JSONArray(json)
                    android.util.Log.d("TG_DEBUG", "Parsed ${arr.length()} messages, chatType=$chatType")
                    val msgs = mutableListOf<TelegramMessage>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val isOutgoing = obj.optBoolean("isOutgoing", false)
                        val isRead = if (obj.has("isRead") && !obj.isNull("isRead")) obj.optBoolean("isRead", false) else null
                        val sendStatus = when {
                            isOutgoing && isRead == true -> "read"
                            isOutgoing && isRead == false -> "sent"
                            else -> ""
                        }
                        msgs.add(TelegramMessage(
                            id = obj.optInt("id", 0),
                            sender = obj.optString("sender", ""),
                            text = obj.optString("text", ""),
                            date = obj.optString("date", ""),
                            isOutgoing = isOutgoing,
                            chatId = obj.optString("chatId", ""),
                            sendStatus = sendStatus,
                            imageBase64 = if (obj.has("imageBase64") && !obj.isNull("imageBase64")) obj.optString("imageBase64") else null
                        ))
                    }
                    if (telegramLoadingOlderMessages) {
                        telegramLoadingOlderMessages = false
                        if (msgs.isEmpty() || msgs.size < 30) {
                            telegramNoMoreOlderMessages = true
                        }
                        if (msgs.isNotEmpty()) {
                            telegramChatAdapter.prependMessages(msgs)
                        }
                    } else {
                        telegramMessagesLoaded = true
                        telegramMessagesRetry?.let { mainHandler.removeCallbacks(it) }
                        telegramRecordHint.text = "Tap to record message"
                        telegramRecordHint.setTextColor(Lum.GHOST)
                        telegramRecordHint.visibility = View.VISIBLE
                        telegramChatAdapter.submitList(msgs, chatType)
                    }
                } catch (e: Exception) {
                    uiLog("TG: messages parse error: ${e.message}")
                }
            }
        }
    }

    private val tgNewMessageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val json = intent?.getStringExtra(ListenerService.EXTRA_TG_NEW_MESSAGE_JSON) ?: return
            runOnUiThread {
                try {
                    val obj = org.json.JSONObject(json)
                    // Handle user status updates (online/offline)
                    if (obj.optString("type") == "user_status") {
                        val userId = obj.optString("userId", "")
                        val isOnline = obj.optBoolean("isOnline", false)
                        val lastSeen = if (obj.has("lastSeen") && !obj.isNull("lastSeen")) obj.optString("lastSeen") else null
                        // TODO: update chat list item for this userId with new presence
                        return@runOnUiThread
                    }
                    val chatId = obj.optString("chatId", "")
                    val msg = TelegramMessage(
                        id = obj.optInt("id", obj.optInt("messageId", 0)),
                        sender = obj.optString("sender", ""),
                        text = obj.optString("text", ""),
                        date = obj.optString("date", ""),
                        isOutgoing = obj.optBoolean("isOutgoing", false),
                        chatId = chatId
                    )
                    // If this message is for the currently open chat, add to adapter
                    // Dedup: skip outgoing echo if we already show it optimistically
                    if (chatId == telegramOpenChatId) {
                        val pending = telegramPendingSendText
                        if (msg.isOutgoing && pending != null && msg.text == pending) {
                            // This is the echo of our optimistic send -- skip, already displayed
                            telegramPendingSendText = null
                        } else {
                            telegramChatAdapter.addMessage(msg)
                        }
                    }
                    // Update chat list preview if visible
                    if (telegramChatListRecycler.visibility == View.VISIBLE) {
                        val unread = if (chatId == telegramOpenChatId) 0 else null
                        telegramChatListAdapter.updateChat(chatId, msg.text, msg.sender, unread)
                    }
                } catch (e: Exception) {
                    uiLog("TG: newMessage parse error: ${e.message}")
                }
            }
        }
    }

    private val tgSendResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val json = intent?.getStringExtra(ListenerService.EXTRA_TG_SEND_RESULT_JSON) ?: return
            runOnUiThread {
                try {
                    val obj = org.json.JSONObject(json)
                    val success = !obj.has("error")
                    if (success) {
                        // Confirmed: clear pending marker, update optimistic message with real id
                        val realId = obj.optInt("id", 0)
                        telegramChatAdapter.confirmPendingSend(realId)
                        telegramPendingSendText = null
                    } else {
                        val error = obj.optString("error", "unknown")
                        uiLog("TG: send failed: $error")
                        // Remove optimistic message on failure
                        telegramChatAdapter.removePendingSend()
                        telegramPendingSendText = null
                    }
                } catch (e: Exception) {
                    uiLog("TG: sendResult parse error: ${e.message}")
                }
            }
        }
    }


    // --- REID broadcast receivers ---

    private val reidPersonResponseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val uid = intent?.getStringExtra(ListenerService.EXTRA_REID_PERSON_UID) ?: return
            val json = intent.getStringExtra(ListenerService.EXTRA_REID_PERSON_JSON) ?: return
            runOnUiThread {
                try {
                    val obj = JSONObject(json)
                    reidLastPersonIntel = obj
                    // Update label if still viewing this face
                    val currentFace = reidVerifiedFaces.getOrNull(reidSelectedFaceIndex)
                    val currentUid = currentFace?.optString("uid", "") ?: ""
                    if (currentUid == uid && focusState in listOf(FocusState.REID_FACES_FOCUSED, FocusState.REID_INTEL_MODAL)) {
                        updateReidFaceLabel(obj)

                        // If modal is showing loading state, refresh it
                        if (focusState == FocusState.REID_INTEL_MODAL && reidIntelContent.childCount == 1) {
                            showReidIntelModal(obj)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private val reidBestThumbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val uid = intent?.getStringExtra(ListenerService.EXTRA_REID_PERSON_UID) ?: return
            val b64 = intent.getStringExtra(ListenerService.EXTRA_REID_BEST_THUMB_DATA) ?: return
            runOnUiThread {
                reidBestThumbs[uid] = b64
                val face = reidVerifiedFaces.find { it.optString("uid", "") == uid }
                if (face != null) {
                    face.put("data", b64)
                    updateReidFaceBar()
                }
            }
        }
    }

    private fun requestPersonIntel() {
        // OSINT person-intel lookups are gated. Core face re-id is unaffected.
        if (!BuildConfig.ENABLE_REID_OSINT) return
        val face = reidVerifiedFaces.getOrNull(reidSelectedFaceIndex) ?: return
        val uid = face.optString("uid", "")
        if (uid.isEmpty()) {
            reidFaceIdLabel.text = "Unknown"
            return
        }

        // Always fetch fresh from phone (OSINT data may have been updated)
        // Show name from face data while loading
        val name = face.optString("name", "")
        reidFaceIdLabel.text = if (name.isNotEmpty() && name != "null") "$name ..." else "..."

        // Send BT request to phone
        sendBroadcast(Intent(ListenerService.ACTION_REID_PERSON_REQUEST).apply {
            putExtra(ListenerService.EXTRA_REID_PERSON_UID, uid)
            setPackage(packageName)
        })
    }

    private val reidFacesReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val json = intent?.getStringExtra(ListenerService.EXTRA_REID_FACES) ?: return
            try {
                val arr = JSONArray(json)
                val faces = mutableListOf<JSONObject>()
                for (i in 0 until arr.length()) {
                    val face = arr.getJSONObject(i)
                    // Apply stored best thumbnails over camera crops
                    val uid = face.optString("uid", "")
                    val bestThumb = reidBestThumbs[uid]
                    if (bestThumb != null) face.put("data", bestThumb)
                    faces.add(face)
                }
                // Preserve selection by matching uid across list updates
                val previousUid = reidVerifiedFaces.getOrNull(reidSelectedFaceIndex)
                    ?.optString("uid", "") ?: ""
                reidVerifiedFaces = faces
                if (previousUid.isNotEmpty()) {
                    val matchIdx = faces.indexOfFirst { it.optString("uid", "") == previousUid }
                    reidSelectedFaceIndex = if (matchIdx >= 0) matchIdx else reidSelectedFaceIndex
                }
                if (reidSelectedFaceIndex < 0 && faces.isNotEmpty()) {
                    reidSelectedFaceIndex = 0
                }
                if (reidSelectedFaceIndex >= faces.size) {
                    reidSelectedFaceIndex = (faces.size - 1).coerceAtLeast(-1)
                }
                updateReidFaceBar()
            } catch (_: Exception) {}
        }
    }

    private val reidBpmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val bpm = intent?.getIntExtra(ListenerService.EXTRA_REID_BPM, 0) ?: 0
            runOnUiThread {
                if (reidLiveBpm == bpm) return@runOnUiThread
                reidLiveBpm = bpm
                if (reidRunning) updateReidFaceBar()
            }
        }
    }

    private val reidStatsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val stats = intent?.getStringExtra(ListenerService.EXTRA_REID_STATS) ?: return
            runOnUiThread {
                if (!reidRunning) return@runOnUiThread
                // Parse face count from stats string (format: "#N | X faces | ...")
                val faceCount = Regex("""(\d+)\s*faces?""").find(stats)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (faceCount > 0 && !reidFaceDetectedAnimating) {
                    reidFaceDetectedAnimating = true
                    statusArea.visibility = View.VISIBLE
                    statusBar.text = "Face detected"
                    Anim.fadeIn(statusBar, 300L) {
                        mainHandler.postDelayed({
                            Anim.fadeOut(statusBar, 500L, gone = false) {
                                reidFaceDetectedAnimating = false
                                statusBar.text = "Scanning"
                                statusBar.setTextColor(Lum.DIM)
                                statusBar.alpha = 1f
                            }
                        }, 1000L)
                    }
                }
            }
        }
    }

    private val reidStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(ListenerService.EXTRA_REID_STATUS) ?: return
            runOnUiThread {
                reidRunning = status != "STOPPED"
                reidStartStopIcon.setImageResource(if (reidRunning) R.drawable.ic_stop else R.drawable.ic_play)
                if (reidRunning) {
                    if (activeTabs.getOrNull(currentTab) == TabId.NIGHTVISION) {
                        stopNightVision()
                    }
                    statusArea.visibility = View.VISIBLE
                    setStatus(status, null, Lum.DIM)
                } else {
                    statusArea.visibility = View.INVISIBLE
                    reidFaceDetectedAnimating = false
                    // Keep faces visible after stopping scan
                }
            }
        }
    }

    private fun toggleReid() {
        val action = if (reidRunning) ListenerService.ACTION_REID_STOP else ListenerService.ACTION_REID_START
        sendBroadcast(Intent(action).apply { setPackage(packageName) })
    }

    private fun updateReidFaceBar() {
        reidFaceBar.removeAllViews()
        val faces = reidVerifiedFaces
        if (faces.isEmpty()) {
            reidFaceIdLabel.text = ""
            return
        }

        val density = Resources.getSystem().displayMetrics.density
        val thumbSize = (56 * density + 0.5f).toInt()
        val margin = (4 * density + 0.5f).toInt()
        val borderWidth = (2 * density + 0.5f).toInt()
        val thumbCornerRadius = 8 * density
        val maxVisible = 7

        // Left-aligned sliding window that follows selection
        val sel = reidSelectedFaceIndex.coerceIn(0, faces.size - 1)
        var start = 0
        if (sel >= maxVisible) {
            start = sel - maxVisible + 1
        }
        var end = (start + maxVisible).coerceAtMost(faces.size)

        // Live heart rate of the face in view (one face at conversational distance).
        // Shown under the selected face's thumbnail: heart icon on the left, BPM on
        // the right. <=0 means measuring/unknown -> "--". Fed from ListenerService
        // (the backend process owns ReidController/RppgEngine) via the REID_FACES
        // broadcast extra; MainActivity cannot call across the process boundary.
        val liveBpm: Int = reidLiveBpm

        for (i in start until end) {
            val face = faces[i]
            val iv = ImageView(this).apply {
                val lp = LinearLayout.LayoutParams(thumbSize, thumbSize)
                lp.setMargins(margin, 0, margin, 0)
                layoutParams = lp
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.BLACK)
                isFocusable = false
                isFocusableInTouchMode = false
                defaultFocusHighlightEnabled = false
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, thumbCornerRadius)
                    }
                }
            }

            // Decode thumbnail from base64
            val thumbB64 = face.optString("data", "")
            if (thumbB64.isNotEmpty()) {
                try {
                    val bytes = Base64.decode(thumbB64, Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    iv.setImageBitmap(bmp)
                } catch (_: Exception) {
                    iv.setBackgroundColor(0xFF333333.toInt())
                }
            }

            // Green border on selected face only when in face-level focus (depth 2+)
            if (i == sel && focusState in listOf(FocusState.REID_FACES_FOCUSED, FocusState.REID_INTEL_MODAL)) {
                val border = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    setStroke(borderWidth, Lum.GLOW)
                    cornerRadius = thumbCornerRadius
                }
                iv.foreground = border
            }

            // Per-face vertical container: thumbnail on top, pulse row beneath.
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setBackgroundColor(Color.BLACK)
                isFocusable = false
                defaultFocusHighlightEnabled = false
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                layoutParams = lp
            }
            item.addView(iv)

            // Pulse row: heart icon (left) + BPM number (right). Shown under EVERY
            // verified face without requiring selection -- the glasses see one face
            // at conversational distance, so the single live reading applies to it.
            // "--" while measuring / low-confidence.
            val pulseRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.CENTER_HORIZONTAL
                setBackgroundColor(Color.BLACK)
                isFocusable = false
                defaultFocusHighlightEnabled = false
                val lp = LinearLayout.LayoutParams(thumbSize, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = (2 * density + 0.5f).toInt()
                layoutParams = lp
            }
            val heartSize = (12 * density + 0.5f).toInt()
            val heart = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(heartSize, heartSize)
                setImageResource(R.drawable.ic_heart)
                isFocusable = false
                defaultFocusHighlightEnabled = false
            }
            val bpmText = TextView(this).apply {
                text = if (liveBpm > 0) "$liveBpm" else "--"
                setTextColor(0xFF00FF00.toInt())
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setBackgroundColor(Color.BLACK)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                lp.leftMargin = (3 * density + 0.5f).toInt()
                layoutParams = lp
                isFocusable = false
                defaultFocusHighlightEnabled = false
            }
            pulseRow.addView(heart)
            pulseRow.addView(bpmText)
            // Always visible under every verified face; the number reads "--" while
            // measuring / low-confidence and the live BPM once locked.
            item.addView(pulseRow)

            reidFaceBar.addView(item)
        }

        // Update label based on state
        val selectedFace = faces.getOrNull(sel)
        if (focusState in listOf(FocusState.REID_FACES_FOCUSED, FocusState.REID_INTEL_MODAL)) {
            val name = selectedFace?.optString("name", "") ?: ""
            reidFaceIdLabel.text = if (name.isNotEmpty() && name != "null") name else "..."
        } else {
            reidFaceIdLabel.text = ""
        }
    }

    private fun updateReidFaceLabel(person: JSONObject) {
        val name = person.optString("name", "").takeIf { it.isNotEmpty() && it != "null" && it != "Unknown" }
        val phone = person.optString("phone", "").takeIf { it.isNotEmpty() && it != "null" }
        val age = person.optInt("age", -1).takeIf { it > 0 }

        val parts = mutableListOf<String>()
        if (name != null) parts.add("\u2022 $name")       // bullet for name
        if (age != null) parts.add("\u29D7 ${age}y")       // hourglass for age
        if (phone != null) parts.add("\u260E $phone")      // phone icon

        reidFaceIdLabel.text = if (parts.isNotEmpty()) parts.joinToString("  ") else "..."
    }

    private fun showReidIntelModal(person: JSONObject?) {
        focusState = FocusState.REID_INTEL_MODAL
        reidIntelContent.removeAllViews()
        reidIntelModal.scrollTo(0, 0)

        val density = resources.displayMetrics.density
        fun Int.dp() = (this * density + 0.5f).toInt()

        if (person == null || person.has("error")) {
            reidIntelContent.addView(TextView(this).apply {
                text = if (person?.has("error") == true) "No intel available" else "Loading..."
                setTextColor(Lum.DIM)
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                gravity = android.view.Gravity.CENTER
                setBackgroundColor(Lum.VOID)
                setPadding(0, 40.dp(), 0, 0)
            })
            reidIntelModal.alpha = 0f
            reidIntelModal.translationY = 8.dp().toFloat()
            reidIntelModal.visibility = View.VISIBLE
            reidIntelModal.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(250)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
            return
        }

        // Header: person name
        val name = person.optString("name", "Unknown")
        reidIntelContent.addView(TextView(this).apply {
            text = name
            setTextColor(Lum.GLOW)
            textSize = 14f
            setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            setBackgroundColor(Lum.VOID)
            setPadding(0, 4.dp(), 0, 8.dp())
        })

        // Phone if available
        val phone = person.optString("phone", "")
        if (phone.isNotEmpty()) {
            reidIntelContent.addView(TextView(this).apply {
                text = phone
                setTextColor(Lum.BRIGHT)
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                setBackgroundColor(Lum.VOID)
                setPadding(0, 0, 0, 8.dp())
            })
        }

        // Sections
        val sections = person.optJSONArray("sections")
        if (sections != null) {
            for (s in 0 until sections.length()) {
                val section = sections.optJSONObject(s) ?: continue
                val title = section.optString("title", "")
                val items = section.optJSONArray("items") ?: continue
                if (items.length() == 0) continue

                // Section header
                reidIntelContent.addView(TextView(this).apply {
                    text = title.uppercase()
                    setTextColor(Lum.DIM)
                    textSize = 10f
                    setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                    setBackgroundColor(Lum.VOID)
                    setPadding(0, 8.dp(), 0, 4.dp())
                })

                // Items
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val label = item.optString("label", "")
                    val value = item.optString("value", "")
                    val confidence = item.optInt("confidence", -1)

                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setBackgroundColor(Lum.VOID)
                        setPadding(0, 2.dp(), 0, 2.dp())
                    }

                    row.addView(TextView(this).apply {
                        text = "$label: "
                        setTextColor(Lum.DIM)
                        textSize = 11f
                        typeface = android.graphics.Typeface.MONOSPACE
                        setBackgroundColor(Lum.VOID)
                    })

                    row.addView(TextView(this).apply {
                        text = value
                        setTextColor(Lum.MID)
                        textSize = 11f
                        typeface = android.graphics.Typeface.MONOSPACE
                        setBackgroundColor(Lum.VOID)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })

                    if (confidence >= 0) {
                        val badgeColor = when {
                            confidence >= 80 -> Lum.GLOW
                            confidence >= 60 -> Lum.BRIGHT
                            else -> Lum.DIM
                        }
                        row.addView(TextView(this).apply {
                            text = "${confidence}%"
                            setTextColor(badgeColor)
                            textSize = 10f
                            setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                            setBackgroundColor(Lum.VOID)
                        })
                    }

                    reidIntelContent.addView(row)
                }
            }
        }

        // Animate in
        reidIntelModal.alpha = 0f
        reidIntelModal.translationY = 8.dp().toFloat()
        reidIntelModal.visibility = View.VISIBLE
        reidIntelModal.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(250)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun hideReidIntelModal() {
        reidIntelModal.animate()
            .alpha(0f)
            .translationY((8 * resources.displayMetrics.density).toInt().toFloat())
            .setDuration(250)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                reidIntelModal.visibility = View.GONE
                reidIntelContent.removeAllViews()
            }
            .start()
        focusState = FocusState.REID_FACES_FOCUSED
        updateFocusVisual(focusState)
    }

    private var chargingIcon: ImageView? = null

    private fun repositionChargingIcon() {
        val icon = chargingIcon ?: return
        batteryIndicator.post {
            // Find the battery-body FrameLayout by type. We can't use getChildAt(0)
            // because the wifi indicator was inserted at the head of batteryIndicator;
            // the battery body is now at index >= 1.
            val vg = batteryIndicator as ViewGroup
            var body: View? = null
            for (i in 0 until vg.childCount) {
                val c = vg.getChildAt(i)
                if (c is FrameLayout) { body = c; break }
            }
            body = body ?: return@post
            val cx = batteryIndicator.left + body.left + body.width / 2
            val cy = batteryIndicator.top + body.top + body.height / 2
            val sz = 16.dpToPx()
            (icon.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = cx - sz / 2
                topMargin = cy - sz / 2
            }
            icon.requestLayout()
        }
    }

    private fun updateBatteryUI(pct: Int, charging: Boolean = false) {
        batteryText.text = "$pct%"
        chargingIcon?.visibility = if (charging) View.VISIBLE else View.GONE
        if (charging) repositionChargingIcon()
        val container = batteryFill.parent as FrameLayout
        val margin = 2.dpToPx()
        val innerWidth = container.width - 2 * margin
        if (innerWidth > 0) {
            val lp = batteryFill.layoutParams
            lp.width = (innerWidth * pct / 100).coerceAtLeast(0)
            batteryFill.layoutParams = lp
        } else {
            container.post {
                val w = container.width - 2 * margin
                if (w > 0) {
                    val lp = batteryFill.layoutParams
                    lp.width = (w * pct / 100).coerceAtLeast(0)
                    batteryFill.layoutParams = lp
                }
            }
        }
    }

    private fun updateTimeUI() {
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val newTime = sdf.format(java.util.Date())
        if (timeText.text?.toString() != newTime) {
            timeText.text = newTime
        }
    }

    override fun onDestroy() = GT.section("ui.onDestroy") {
        activityLog("onDestroy isFinishing=${isFinishing} isChangingConfigurations=${isChangingConfigurations}")
        try { touchpadAbsListener.stop() } catch (_: Throwable) {}
        try {
            mapWorkerHandler?.removeCallbacksAndMessages(null)
            mapWorkerThread?.quitSafely()
            mapWorkerThread = null
            mapWorkerHandler = null
            pendingMapFrame.set(null)
        } catch (_: Throwable) {}
        try { unregisterReceiver(wifiStateReceiver) } catch (_: Throwable) {}
        // captureBridge lives in ListenerService now; its onDestroy handles unbind.
        remoteGlyphHideRunnable?.let { mainHandler.removeCallbacks(it) }
        remoteGlyphHideRunnable = null
        if (backendBound) {
            // Detach the sink BEFORE dropping the binding, while the binder is still alive. After
            // unbindService the backend only learns of this via its death recipient.
            runCatching { remoteInputBridge.unregister() }
            unbindService(backendConnection)
            backendBound = false
        }
        if (mouseBound) {
            unbindService(mouseServiceConnection)
            mouseBound = false
        }
        stopTimer()
        thinkingPulseAnimator?.cancel()
        tintActiveAnimator?.cancel()
        tintInactiveAnimator?.cancel()
        focusBorderAnimator?.cancel()
        pinBorderAnimator?.cancel()
        stepsBorderAnimator?.cancel()
        tpStopBorderAnimator?.cancel()
        loaderCtl.hideAll()
        pendingTapRunnable?.let { mainHandler.removeCallbacks(it) }
        mainHandler.removeCallbacks(translationStartTimeoutRunnable)
        scrollIndicatorHideRunnable?.let { mainHandler.removeCallbacks(it) }
        tpScrollIndicatorHideRunnable?.let { mainHandler.removeCallbacks(it) }
        listOf(
            stateReceiver, chatReceiver, streamingReceiver, partialTextReceiver,
            userTextReceiver, responseMetaReceiver, toolStatusReceiver, sessionResetReceiver,
            cameraPreviewReceiver, teleprompterReceiver, mapBitmapReceiver, mapArrowReceiver,
            mapMinimapReceiver, navStepsReceiver, navStepIndexReceiver, toolThumbnailReceiver, photoProgressReceiver, chatListReceiver, chatHistoryReceiver,
            debugStatusReceiver, btStateReceiver, orchestratorStateReceiver,
            weatherUpdateReceiver, loneIndicatorReceiver, recordingStateReceiver,
            translationResultReceiver, translationConfigReceiver,
            translationStateReceiver, reidFacesReceiver, reidStatsReceiver, reidBpmReceiver,
            reidStatusReceiver, reidPersonResponseReceiver, reidBestThumbReceiver, cameraPermRequestReceiver,
            todoListReceiver, alarmListReceiver, jobListReceiver,
            batteryReceiver, timeTickReceiver, bottomPaddingReceiver, chatFontSizeReceiver,
            mediaStateReceiver, mediaProgressReceiver, a2dpSinkStateReceiver, uiRecordReceiver,
            tgChatListResponseReceiver, tgMessagesResponseReceiver,
            tgNewMessageReceiver, tgSendResultReceiver, tgTopicsResponseReceiver,
            notificationShownReceiver, notificationHiddenReceiver,
            notificationSoloShowReceiver, notificationSoloEndReceiver,
            soloScreenOffReceiver, soloScreenOnReceiver,
            callUiStateReceiver, rfcommMouseTrackingReceiver
        ).forEach {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        hideTelegramVoiceVisualizer()
        telegramSendRunnable?.let { mainHandler.removeCallbacks(it) }
        callKeyHandler.removeCallbacks(callDurationTick)
        pendingCallAccept?.let { callKeyHandler.removeCallbacks(it) }
        pendingCallAccept = null
        stopMusicMarquee()
        teleprompterController?.destroy(teleprompterContainer)
        arCameraPreview?.stop()
        nightVisionPreview?.stop()
        try { unregisterReceiver(foldStateReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
