package com.repository.glasses.listener.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.repository.glasses.listener.config.GlassesConfig
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.sin

/**
 * WindowManager-based notification overlay. Runs from the service,
 * independent of any activity. Draws over everything using SYSTEM_ALERT_WINDOW.
 *
 * Visual design: a bordered rectangle pinned to the top of the lens, pure-black
 * (transparent on the waveguide) fill with a green border that carries phase.
 * ALL reply UI lives inside it. The reply affordance area swaps by phase
 * (IDLE / HOLDING / LISTENING / SENDING / SENT / CANCELLED). Green-only
 * foreground via Lum.*, every view focusable=false.
 */
class NotificationOverlay(private val context: Context) {

    data class NotificationData(
        val notifId: String,
        val sender: String,
        val text: String,
        val chat: String,
        val repliable: Boolean
    )

    /** Reply affordance phase. Drives which sub-views are shown inside the box. */
    enum class ReplyPhase { IDLE, HOLDING, LISTENING, SENDING, SENT, CANCELLED, FAILED }

    var remoteLog: ((String) -> Unit)? = null
    var onAllDismissed: (() -> Unit)? = null
    var onItemDismissed: ((notifId: String) -> Unit)? = null
    /** Fired when an item actually becomes the visible one (incl. queued items
     *  that surface after an earlier one finishes). Carries (notifId, repliable). */
    var onItemShown: ((notifId: String, repliable: Boolean) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ConcurrentLinkedQueue<NotificationData>()
    private var isShowing = false
    private var isAttached = false
    private var current: NotificationData? = null
    private var frozen = false

    private var phase: ReplyPhase = ReplyPhase.IDLE
    private var renderedPhase: ReplyPhase? = null
    private var progressFraction: Float = 0f
    private var transcript: String = ""

    /** The release-to-send cancel window (also drives the visual countdown). */
    private val sendWindowMs = 3000L

    /** Data of the currently shown item, or null when nothing is on screen. */
    val currentData: NotificationData? get() = current

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
    private val dp = Resources.getSystem().displayMetrics.density
    private val strokePx = maxOf(1, (1 * dp).toInt())

    // --- view tree -----------------------------------------------------------
    private val root: LinearLayout
    private val box: LinearLayout
    private val boxBg: GradientDrawable

    // header row
    private val tgMark: TgMarkView
    private val headerDot: View
    private val senderLabel: TextView

    // message
    private val messageLabel: TextView

    // divider
    private val divider: View

    // affordance container (swaps content by phase)
    private val affordance: LinearLayout

    // IDLE / HOLDING views
    private val holdRow: LinearLayout
    private val holdMic: MicGlyphView
    private val holdHint: TextView
    private val progressTrack: LinearLayout
    private val progressTrackBg: GradientDrawable
    private val progressFill: View
    private val progressFillBg: GradientDrawable

    // LISTENING / SENDING / SENT / CANCELLED shared transcript + visualizer
    private val listenRow: LinearLayout
    private val listenMic: MicGlyphView
    private lateinit var visualizer: AudioVisualizerView
    private val transcriptLabel: TextView
    private lateinit var captionLabel: TextView
    private val sentRow: LinearLayout
    private val sentTick: TickGlyphView
    private val sentLabel: TextView
    private val cancelRow: LinearLayout
    private val cancelCross: CrossGlyphView
    private val cancelLabel: TextView

    private val displayDurationMs: Long get() = GlassesConfig.notificationDurationMs
    private val repliableDurationMs: Long = 12000L
    private val currentDismissDelayMs: Long
        get() = if (current?.repliable == true) repliableDurationMs else displayDurationMs
    private val dismissRunnable = Runnable { dismissCurrent() }

    // blink + synthetic visualizer animation driver
    private var blinkOn = true
    private val blinkRunnable = object : Runnable {
        override fun run() {
            blinkOn = !blinkOn
            applyBlink()
            handler.postDelayed(this, 500L)
        }
    }
    // SENDING gentle pulse: caption brightness oscillates MID <-> GLOW on a
    // ~1500ms cycle (system.md slow-breathe), conveying life not static waiting.
    private var pulseT = 0.0
    private val pulseRunnable = object : Runnable {
        override fun run() {
            if (phase != ReplyPhase.SENDING) return
            pulseT += 0.18
            val t = (sin(pulseT) * 0.5 + 0.5).toFloat()
            captionLabel.setTextColor(lerpLum(Lum.DIM, Lum.GLOW, t))
            handler.postDelayed(this, 60L)
        }
    }
    // The visualizer is fed REAL mic envelopes by the service (see
    // pushVisualizerEnvelope), not a synthetic sine loop. When no audio arrives
    // the bars decay to the idle floor on their own.

    // SENDING countdown: tick 3 -> 2 -> 1, refreshing the cancel caption.
    private var sendSecondsLeft = 0
    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (phase != ReplyPhase.SENDING) return
            sendSecondsLeft -= 1
            if (sendSecondsLeft < 0) sendSecondsLeft = 0
            captionLabel.text = "DOUBLE-TAP TO CANCEL    $sendSecondsLeft"
            if (sendSecondsLeft > 0) handler.postDelayed(this, 1000L)
        }
    }

    private var progressAnimator: ValueAnimator? = null

    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        x = 0
        // lg top offset (16dp); horizontal sm inset applied as root padding below
        y = (16 * dp).toInt()
    }

    init {
        // Container: transparent (Lum.VOID = pixels off) fill with a 12dp rounded
        // green BORDER that carries phase (DIM at rest -> GLOW when active). No
        // fill colour -- the lens shows through, only the border + text glow.
        boxBg = GradientDrawable().apply {
            setColor(Lum.VOID)
            cornerRadius = 12 * dp
            setStroke(strokePx, Lum.DIM)
        }

        box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = boxBg
            isFocusable = false
            isFocusableInTouchMode = false
            // md internal padding (12dp)
            val pad = (12 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            // NOTE: no box-level LayoutTransition. Animating the whole card height
            // on a content swap (e.g. switching to a queued 2nd notification)
            // clipped the bottom mid-animation. The affordance sub-tree keeps its
            // own CHANGING transition for the mic/bar grow, which is enough.
        }

        // Root wrapper -- holds the card with an sm (8dp) horizontal inset.
        // Opaque black fill so the glasses app UI behind the overlay window is
        // fully occluded (no bleed-through around/under the card).
        root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = false
            isFocusableInTouchMode = false
            setBackgroundColor(Lum.VOID)
            val inset = (8 * dp).toInt()
            setPadding(inset, 0, inset, 0)
            addView(box, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        // ---- header row -----------------------------------------------------
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = false
        }
        tgMark = TgMarkView(context, dp).apply { setColorTo(Lum.BRIGHT) }
        headerDot = dotView(3f, Lum.DIM)
        // Sender name: BRIGHT identity, but ~1.5x smaller than before so more of
        // the conversation fits.
        senderLabel = mono(11f, Lum.BRIGHT).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val gap8 = (8 * dp).toInt()
        headerRow.addView(tgMark, sizeDp(13f, 13f))
        headerRow.addView(headerDot, dotParams(3f, gap8))
        headerRow.addView(senderLabel, marginStart(gap8))

        // ---- message --------------------------------------------------------
        // ~1.5x smaller (was 14sp) + up to 4 lines so merged same-sender
        // messages (each appended on its own line) stay visible.
        messageLabel = mono(9.5f, Lum.MID).apply {
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, (4 * dp).toInt(), 0, 0)
        }

        // ---- divider --------------------------------------------------------
        divider = View(context).apply { setBackgroundColor(Lum.GHOST) }

        // ---- affordance -----------------------------------------------------
        // No LayoutTransition here: the CHANGING animation made the affordance
        // grow to full height over 180ms, but the WRAP_CONTENT overlay window is
        // measured synchronously at show time -- so the window got sized to the
        // mid-animation (shorter) height, clipping the bottom border and the
        // "HOLD TO REPLY" row until the animation settled. The only entry
        // animation we actually want (mic + progress bar fading in on HOLD) is
        // done manually via alpha animations in applyPhase(), so the transition
        // was redundant as well as harmful.
        affordance = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = false
            minimumHeight = (24 * dp).toInt()
            setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
        }

        // IDLE / HOLDING row. At rest only the hint shows; the mic + progress bar
        // are GONE and fade in when the hold begins.
        holdRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = false
        }
        holdMic = MicGlyphView(context, dp).apply {
            setColorTo(Lum.GLOW)
            visibility = View.GONE
        }
        holdHint = mono(10f, Lum.MID).apply {
            text = "HOLD TO REPLY"
            letterSpacing = 0.22f
        }
        holdRow.addView(holdMic, sizeDp(13f, 13f))
        holdRow.addView(holdHint, marginStart(gap8))

        // Progress / countdown track + fill: 3dp tall, rounded ends. Reused for
        // HOLDING (fills 0->1) and SENDING (depletes 1->0 over the cancel window).
        // 5dp-tall pill bar; radius = half height so the caps are fully rounded.
        progressFillBg = GradientDrawable().apply {
            setColor(Lum.GLOW)
            cornerRadius = 2.5f * dp
        }
        progressFill = View(context).apply {
            background = progressFillBg
            // Fill spans the full track and is squeezed via scaleX (pivot left)
            // so progress never relayouts the row -> no flicker.
            pivotX = 0f
            scaleX = 0f
        }
        progressTrackBg = GradientDrawable().apply {
            setColor(Lum.GHOST)
            cornerRadius = 2.5f * dp
        }
        progressTrack = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = progressTrackBg
            isFocusable = false
            visibility = View.GONE
            addView(progressFill, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (5 * dp).toInt()))
        }

        // LISTENING row (mic + visualizer)
        listenRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = false
        }
        listenMic = MicGlyphView(context, dp).apply { setColorTo(Lum.GLOW) }
        visualizer = AudioVisualizerView(context).apply {
            isFocusable = false
        }
        listenRow.addView(listenMic, sizeDp(14f, 14f))
        listenRow.addView(visualizer, LinearLayout.LayoutParams(
            0, (18 * dp).toInt(), 1f).apply { marginStart = (10 * dp).toInt() })

        // ~1.5x smaller transcript (my reply), wraps freely.
        // minLines pins the row height to 2 lines so growth from 1->2 lines never
        // reflows the affordance and triggers the CHANGING sibling-nudge animation.
        transcriptLabel = mono(9.5f, Lum.BRIGHT).apply {
            setPadding(0, (8 * dp).toInt(), 0, 0)
            minLines = 2
            gravity = Gravity.TOP
        }
        captionLabel = mono(10f, Lum.DIM).apply {
            letterSpacing = 0.2f
            setPadding(0, (8 * dp).toInt(), 0, 0)
        }

        sentRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = false
            setPadding(0, (8 * dp).toInt(), 0, 0)
        }
        sentTick = TickGlyphView(context, dp).apply { setColorTo(Lum.GLOW) }
        sentLabel = mono(10f, Lum.GLOW).apply { letterSpacing = 0.2f }
        sentRow.addView(sentTick, sizeDp(11f, 11f))
        sentRow.addView(sentLabel, marginStart((6 * dp).toInt()))

        // CANCELLED row: the contrary of SENT -- a cross glyph + dim label.
        cancelRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = false
            setPadding(0, (8 * dp).toInt(), 0, 0)
        }
        cancelCross = CrossGlyphView(context, dp).apply { setColorTo(Lum.DIM) }
        cancelLabel = mono(10f, Lum.DIM).apply { letterSpacing = 0.2f }
        cancelRow.addView(cancelCross, sizeDp(11f, 11f))
        cancelRow.addView(cancelLabel, marginStart((6 * dp).toInt()))

        affordance.addView(holdRow, matchWrap())
        affordance.addView(progressTrack, matchWrap().apply { topMargin = (8 * dp).toInt() })
        affordance.addView(listenRow, matchWrap())
        affordance.addView(transcriptLabel, matchWrap())
        affordance.addView(captionLabel, matchWrap())
        affordance.addView(sentRow, matchWrap())
        affordance.addView(cancelRow, matchWrap())

        // ---- assemble -------------------------------------------------------
        box.addView(headerRow, matchWrap())
        box.addView(messageLabel, matchWrap())
        box.addView(divider, matchWrap().apply {
            topMargin = (8 * dp).toInt(); height = maxOf(1, (0.5f * dp).toInt())
        })
        box.addView(affordance, matchWrap())
    }

    // --- small layout helpers ------------------------------------------------
    private fun mono(sizeSp: Float, color: Int): TextView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = Typeface.MONOSPACE
        setTextColor(color)
        isFocusable = false
    }

    private fun dotView(sizeDp: Float, color: Int): View = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        isFocusable = false
    }

    private fun sizeDp(w: Float, h: Float) =
        LinearLayout.LayoutParams((w * dp).toInt(), (h * dp).toInt())

    private fun dotParams(d: Float, startMargin: Int) =
        LinearLayout.LayoutParams((d * dp).toInt(), (d * dp).toInt()).apply { marginStart = startMargin }

    private fun marginStart(m: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = m }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    // --- queue / show / dismiss ---------------------------------------------
    // The `solo` flag means "a notification arrived while the screen was off and the glasses
    // were worn". The actual blackout (occluding the rest of the UI so only this card is lit)
    // is done by MainActivity hiding its OWN content root -- a backend overlay window cannot
    // occlude the activity window. The service drives that via the SOLO_SHOW/REVEAL/END
    // broadcasts; this overlay just renders the card. `solo` is accepted for symmetry/logging.
    fun show(data: NotificationData, solo: Boolean) {
        remoteLog?.invoke("NotifOverlay: queued ${data.notifId} from ${data.sender} isShowing=$isShowing queueSize=${queue.size} solo=$solo (${if (isShowing) "QUEUED behind current" else "showing now"})")
        queue.add(data)
        if (!isShowing) handler.post { showNext() }
    }

    fun dismiss() {
        handler.removeCallbacks(dismissRunnable)
        queue.clear()
        if (isShowing) handler.post { dismissCurrent() }
    }


    private fun showNext() {
        val data = queue.poll()
        if (data == null) {
            isShowing = false
            onAllDismissed?.invoke()
            return
        }

        isShowing = true
        current = data
        remoteLog?.invoke("NotifOverlay: showing ${data.notifId} repliable=${data.repliable} textLen=${data.text.length} isAttached=$isAttached queueRemaining=${queue.size}")

        senderLabel.text = if (data.chat.isNotEmpty() && data.chat != data.sender) {
            "${data.sender} -- ${data.chat}"
        } else {
            data.sender
        }
        messageLabel.text = data.text

        // Reset ALL reply state for the new item -- a previous reply may have
        // left the shared views dirty (scaleX mid-fill, affordance alpha at 0.4,
        // running animators, frozen dismiss). Without this a queued 2nd
        // notification renders dim/half-animated and its hold is dead.
        handler.removeCallbacks(countdownRunnable)
        handler.removeCallbacks(pulseRunnable)
        handler.removeCallbacks(blinkRunnable)
        visualizer.clear()
        progressAnimator?.cancel()
        frozen = false
        phase = ReplyPhase.IDLE
        renderedPhase = null
        progressFraction = 0f
        transcript = ""
        progressFill.scaleX = 0f
        progressTrack.visibility = View.GONE
        holdMic.visibility = View.GONE
        affordance.alpha = 1f
        affordance.visibility = if (data.repliable) View.VISIBLE else View.GONE
        applyPhase()

        // Re-assert this item's identity to listeners. The service broadcasts
        // SHOWN at queue time, which a still-in-flight earlier reply can clobber;
        // firing here (on actual display) lets MainActivity refresh
        // pendingNotifId/repliable so the hold-to-reply arm works for item N>1.
        onItemShown?.invoke(data.notifId, data.repliable)

        try {
            if (isAttached) {
                wm.updateViewLayout(root, layoutParams)
            } else {
                wm.addView(root, layoutParams)
                isAttached = true
            }
            root.visibility = View.VISIBLE
            // Materialize: fade 0 -> 1 over 250ms. NOTE: no translationY rise (a downward
            // start offset clipped the WRAP_CONTENT bottom border during the animation).
            root.translationY = 0f
            root.alpha = 0f
            root.animate().cancel()
            // CRITICAL: start the fade on the FIRST DRAW, not synchronously here. show()
            // runs right after an async screen wake (Powering on display group fromAsleep);
            // a ViewPropertyAnimator started before the window has a live surface can be
            // starved -- its frame callbacks never fire and withEndAction never runs, so
            // alpha stays stranded at ~0 -> window present on a lit screen but INVISIBLE
            // (the intermittent "screen on, no notification" bug). doOnFirstDraw guarantees
            // the animator only starts once the ViewRootImpl is compositing frames. The
            // post-delay fallback force-sets alpha=1 in case the draw/animator is missed
            // entirely, so the card can NEVER end up invisible.
            startEntranceFade()
        } catch (e: Exception) {
            remoteLog?.invoke("NotifOverlay: addView failed: ${e.message}")
            isShowing = false
            return
        }

        handler.removeCallbacks(dismissRunnable)
        if (!frozen) handler.postDelayed(dismissRunnable, currentDismissDelayMs)

        // Steady-state snapshot ~1s after show: if the card was visible-then-gone vs
        // never-rendered, this distinguishes them (and catches an early dismiss).
        handler.removeCallbacks(steadyStateSnapshot)
        handler.postDelayed(steadyStateSnapshot, 1000L)
    }

    private val steadyStateSnapshot = Runnable { snapshotRenderState("steady-1s") }

    /**
     * Run the entrance alpha fade, but only once the window is actually drawing, so the
     * animator can't be starved by the async screen wake (see showNext). A pre-draw
     * listener fires on the first composited frame; we start the fade there. A handler
     * fallback force-sets alpha=1 shortly after the fade should have finished, so an
     * interrupted/never-started animator can never leave the card invisible.
     */
    private fun startEntranceFade() {
        handler.removeCallbacks(entranceFadeFailsafe)
        val vto = root.viewTreeObserver
        vto.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                root.viewTreeObserver.removeOnPreDrawListener(this)
                snapshotRenderState("first-draw")
                root.animate().cancel()
                root.alpha = 0f
                root.animate()
                    .alpha(1f)
                    .setDuration(250L)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction { root.alpha = 1f; snapshotRenderState("fade-end") }
                    .start()
                return true
            }
        })
        // Failsafe: if the pre-draw never fires or the animator is starved, guarantee
        // full opacity. 250ms fade + margin.
        handler.postDelayed(entranceFadeFailsafe, 500L)
    }
    private val entranceFadeFailsafe = Runnable {
        snapshotRenderState("failsafe-500ms")
        if (isShowing) {
            root.alpha = 1f
            remoteLog?.invoke("NotifOverlay[failsafe-500ms]: forced alpha=1 (isShowing=true)")
        } else {
            remoteLog?.invoke("NotifOverlay[failsafe-500ms]: SKIPPED force (isShowing=false -- item already dismissed?)")
        }
    }

    /**
     * Log the overlay's ACTUAL render state. The intermittent "screen on but no
     * notification visible" bug shows the code path running fine (showing / item shown)
     * while the pixels never reach the panel. This captures the real view/window state
     * so the NEXT occurrence self-diagnoses WHICH layer is wrong: alpha stranded < 1,
     * zero width/height (not laid out / window not sized), detached from window, no
     * window token (surface not created), or shown=false (composed off-screen).
     */
    private fun snapshotRenderState(tag: String) {
        try {
            val lp = root.layoutParams as? WindowManager.LayoutParams
            // Panel/display power: distinguishes "view looks fine but panel was off/dozing
            // at composite" from a genuine view-layer fault. displayState: 1=OFF 2=ON 3=DOZE
            // 4=DOZE_SUSPEND 6=ON_SUSPEND.
            val displayState = try { displayManager.getDisplay(0)?.state ?: -1 } catch (e: Exception) { -1 }
            val interactive = try { powerManager.isInteractive } catch (e: Exception) { false }
            remoteLog?.invoke(
                "NotifOverlay[$tag]: id=${current?.notifId} isShowing=$isShowing isAttached=$isAttached " +
                "alpha=${root.alpha} transY=${root.translationY} vis=${root.visibility} " +
                "w=${root.width} h=${root.height} measuredH=${root.measuredHeight} " +
                "attachedToWindow=${root.isAttachedToWindow} hasWindowToken=${root.windowToken != null} " +
                "isShown=${root.isShown} lpAlpha=${lp?.alpha} lpY=${lp?.y} lpFlags=${lp?.flags?.let { Integer.toHexString(it) }} " +
                "msgVis=${messageLabel.visibility} msgAlpha=${messageLabel.alpha} " +
                "displayState=$displayState interactive=$interactive"
            )
        } catch (e: Exception) {
            remoteLog?.invoke("NotifOverlay[$tag]: snapshot failed: ${e.message}")
        }
    }

    // --- phase API (main thread) --------------------------------------------

    /** Set the reply affordance phase and rebuild the affordance sub-tree. */
    fun setReplyPhase(newPhase: ReplyPhase) {
        handler.post {
            // Idempotent: re-asserting the same phase must not re-run animations
            // (that caused the HOLD label / affordance to flicker on every
            // progress tick).
            if (phase == newPhase) return@post
            if (phase == ReplyPhase.IDLE && newPhase != ReplyPhase.IDLE) progressFraction = 0f
            phase = newPhase
            applyPhase()
        }
    }

    /** Set the hold progress fill (0..1). Implicitly drives HOLDING visuals. */
    fun setReplyProgress(fraction: Float) {
        handler.post {
            progressFraction = fraction.coerceIn(0f, 1f)
            if (phase == ReplyPhase.IDLE) {
                phase = ReplyPhase.HOLDING
                applyPhase()
            } else {
                applyProgressFill()
            }
        }
    }

    /**
     * Begin the hold-to-arm fill, self-animated over [durationMs] with a single
     * animator. Avoids the per-frame cross-process broadcast storm (MainActivity
     * used to ping progress every 16ms) that made the bar jitter. The fill runs
     * 0 -> 1; if the user keeps holding to the end the arm commits independently
     * (MainActivity's timer drives the LISTENING transition).
     */
    fun startHoldFill(durationMs: Long) {
        handler.post {
            if (phase == ReplyPhase.IDLE) {
                phase = ReplyPhase.HOLDING
                applyPhase()
            }
            setProgressScaleImmediate(0f)
            animateProgressScale(1f, durationMs, AccelerateDecelerateInterpolator())
        }
    }

    /** Cancel an in-progress hold fill (early release before commit). */
    fun cancelHoldFill() {
        handler.post { setProgressScaleImmediate(0f) }
    }

    /** Set the live transcript shown during LISTENING / SENDING / SENT. */
    fun setReplyTranscript(text: String) {
        handler.post {
            transcript = text
            transcriptLabel.text =
                if (phase == ReplyPhase.LISTENING) caretTranscript(text, blinkOn) else text
        }
    }

    private fun applyPhase() {
        val armed = phase == ReplyPhase.LISTENING || phase == ReplyPhase.SENDING ||
            phase == ReplyPhase.SENT || phase == ReplyPhase.CANCELLED || phase == ReplyPhase.FAILED
        val holding = phase == ReplyPhase.HOLDING
        val data = current

        // Border carries phase: DIM at rest, GLOW while active, DIM again when the
        // reply did not go out (cancelled / failed) -- the contrary of a sent reply.
        val border = when (phase) {
            ReplyPhase.IDLE -> Lum.DIM
            ReplyPhase.CANCELLED -> Lum.DIM
            ReplyPhase.FAILED -> Lum.DIM
            else -> Lum.GLOW
        }
        boxBg.setStroke(strokePx, border)

        val accent = if (armed || holding) Lum.GLOW else Lum.BRIGHT
        val accentRest =
            if (phase == ReplyPhase.CANCELLED || phase == ReplyPhase.FAILED) Lum.DIM else accent
        tgMark.setColorTo(accentRest)
        messageLabel.setTextColor(if (armed) Lum.DIM else Lum.MID)

        if (data?.repliable != true) {
            affordance.visibility = View.GONE
            stopBlink(); stopVisualizer(); stopPulse(); stopCountdown()
            renderedPhase = phase
            return
        }
        affordance.visibility = View.VISIBLE

        val showHold = phase == ReplyPhase.IDLE || phase == ReplyPhase.HOLDING
        holdRow.visibility = if (showHold) View.VISIBLE else View.GONE
        // Mic only appears once the hold begins; progress/countdown bar shows in
        // HOLDING and SENDING.
        holdMic.visibility = if (holding) View.VISIBLE else View.GONE
        progressTrack.visibility =
            if (holding || phase == ReplyPhase.SENDING) View.VISIBLE else View.GONE
        listenRow.visibility = if (phase == ReplyPhase.LISTENING) View.VISIBLE else View.GONE
        transcriptLabel.visibility = if (armed) View.VISIBLE else View.GONE
        captionLabel.visibility =
            if (phase == ReplyPhase.LISTENING || phase == ReplyPhase.SENDING) View.VISIBLE else View.GONE
        sentRow.visibility = if (phase == ReplyPhase.SENT) View.VISIBLE else View.GONE
        // cancelRow doubles as the FAILED row (cross glyph + dim label, relabeled).
        cancelRow.visibility =
            if (phase == ReplyPhase.CANCELLED || phase == ReplyPhase.FAILED) View.VISIBLE else View.GONE

        // Strike the transcript when the reply did not go out (cancelled / failed);
        // plain otherwise.
        transcriptLabel.paintFlags = if (phase == ReplyPhase.CANCELLED || phase == ReplyPhase.FAILED) {
            transcriptLabel.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            transcriptLabel.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }

        when (phase) {
            ReplyPhase.IDLE -> {
                holdHint.setTextColor(Lum.MID)
                holdHint.text = "HOLD TO REPLY"
                progressFillBg.setColor(Lum.GLOW)
            }
            ReplyPhase.HOLDING -> {
                holdMic.setColorTo(Lum.GLOW)
                holdHint.setTextColor(Lum.GLOW)
                holdHint.text = "KEEP HOLDING"
                progressFillBg.setColor(Lum.GLOW)
                // Fade the mic + bar in as the hold starts.
                if (renderedPhase != ReplyPhase.HOLDING) {
                    holdMic.alpha = 0f; holdMic.animate().alpha(1f).setDuration(150L).start()
                    progressTrack.alpha = 0f; progressTrack.animate().alpha(1f).setDuration(150L).start()
                }
            }
            ReplyPhase.LISTENING -> {
                transcriptLabel.setTextColor(Lum.BRIGHT)
                transcriptLabel.text = caretTranscript(transcript, blinkOn)
                captionLabel.setTextColor(Lum.DIM)
                captionLabel.text = "SPEAK NOW  SWIPE TO CANCEL"
            }
            ReplyPhase.SENDING -> {
                transcriptLabel.setTextColor(Lum.BRIGHT)
                transcriptLabel.text = transcript
                captionLabel.setTextColor(Lum.DIM)
                sendSecondsLeft = (sendWindowMs / 1000L).toInt()
                captionLabel.text = "DOUBLE-TAP TO CANCEL    $sendSecondsLeft"
                progressFillBg.setColor(Lum.DIM)
            }
            ReplyPhase.SENT -> {
                transcriptLabel.setTextColor(Lum.MID)
                transcriptLabel.text = transcript
                sentLabel.text = "SENT TO ${data.sender.uppercase()}"
            }
            ReplyPhase.CANCELLED -> {
                transcriptLabel.setTextColor(Lum.DIM)
                transcriptLabel.text = transcript
                cancelLabel.setTextColor(Lum.DIM)
                cancelLabel.text = "SEND CANCELLED"
            }
            ReplyPhase.FAILED -> {
                // Reuse the cancel row visuals (cross glyph + DIM label) so the
                // state reads as "not sent"; only the caption differs.
                transcriptLabel.setTextColor(Lum.DIM)
                transcriptLabel.text = transcript
                cancelCross.setColorTo(Lum.DIM)
                cancelLabel.setTextColor(Lum.DIM)
                cancelLabel.text = "SEND FAILED"
            }
        }

        // Progress / countdown bar behaviour.
        when (phase) {
            ReplyPhase.HOLDING -> applyProgressFill()
            ReplyPhase.SENDING -> startCountdownBar()
            else -> { applyProgressFill() }
        }

        if (phase == ReplyPhase.LISTENING) { startBlink(); startVisualizer() }
        else { stopBlink(); stopVisualizer() }

        if (phase == ReplyPhase.SENDING) { startPulse(); startCountdown() }
        else { stopPulse(); stopCountdown() }

        // Phase-change crossfade of the affordance content (~150ms).
        affordance.alpha = 0.4f
        affordance.animate().alpha(1f).setDuration(150L).setInterpolator(DecelerateInterpolator()).start()

        // SENT / CANCELLED rows materialize: fade in + 8dp rise.
        if (phase == ReplyPhase.SENT) materialize(sentRow)
        if (phase == ReplyPhase.CANCELLED || phase == ReplyPhase.FAILED) materialize(cancelRow)

        renderedPhase = phase
    }

    private fun materialize(v: View) {
        v.alpha = 0f
        v.translationY = 8 * dp
        v.animate().alpha(1f).translationY(0f).setDuration(250L)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun startPulse() {
        handler.removeCallbacks(pulseRunnable)
        pulseT = 0.0
        handler.post(pulseRunnable)
    }

    private fun stopPulse() {
        handler.removeCallbacks(pulseRunnable)
    }

    private fun startCountdown() {
        handler.removeCallbacks(countdownRunnable)
        sendSecondsLeft = (sendWindowMs / 1000L).toInt()
        handler.postDelayed(countdownRunnable, 1000L)
    }

    private fun stopCountdown() {
        handler.removeCallbacks(countdownRunnable)
    }

    /** Linear interpolate between two Lum greens by green-channel value. */
    private fun lerpLum(from: Int, to: Int, t: Float): Int {
        val gf = (from shr 8) and 0xFF
        val gt = (to shr 8) and 0xFF
        val g = (gf + (gt - gf) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (g shl 8)
    }

    private fun applyProgressFill() {
        // The hold progress already arrives at ~60fps from MainActivity's arm
        // ticker, so each update IS one animation frame. Set scaleX directly --
        // frame-synced and smooth. Spawning a 150ms ValueAnimator per tick (the
        // old behaviour) meant every 16ms a fresh easing started toward a moving
        // target and was cancelled before completing -> perpetual chase = jank.
        setProgressScaleImmediate(progressFraction)
    }

    /** Depleting countdown bar: full -> 0 over the cancel window. */
    private fun startCountdownBar() {
        setProgressScaleImmediate(1f)
        animateProgressScale(0f, sendWindowMs, LinearInterpolator())
    }

    private fun setProgressScaleImmediate(target: Float) {
        progressAnimator?.cancel()
        progressFill.scaleX = target.coerceIn(0f, 1f)
    }

    /**
     * Smoothly animate the progress fill via scaleX (pivot-left). scaleX never
     * triggers a relayout, so the parent LayoutTransition stays quiet and the
     * affordance no longer flickers on every progress tick. Cancel-safe.
     */
    private fun animateProgressScale(target: Float, durationMs: Long, interp: android.view.animation.Interpolator) {
        val to = target.coerceIn(0f, 1f)
        val from = progressFill.scaleX
        if (from == to) return
        progressAnimator?.cancel()
        progressAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            interpolator = interp
            addUpdateListener { progressFill.scaleX = it.animatedValue as Float }
            start()
        }
    }

    // Build the LISTENING transcript with a trailing caret cell that is ALWAYS
    // present (constant string length => constant monospace wrap/height). The
    // caret is shown by recoloring its cell GLOW (on) or VOID (off, invisible
    // against the black box background) rather than appending/removing the glyph.
    private fun caretTranscript(text: String, on: Boolean): CharSequence {
        val full = "$text\u25AE"
        val span = SpannableString(full)
        val color = if (on) Lum.GLOW else Lum.VOID
        span.setSpan(
            ForegroundColorSpan(color),
            full.length - 1, full.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return span
    }

    private fun applyBlink() {
        if (phase != ReplyPhase.LISTENING) return
        listenMic.setColorTo(if (blinkOn) Lum.GLOW else Lum.VOID)
        transcriptLabel.text = caretTranscript(transcript, blinkOn)
    }

    private fun startBlink() {
        handler.removeCallbacks(blinkRunnable)
        blinkOn = true
        applyBlink()
        handler.postDelayed(blinkRunnable, 500L)
    }

    private fun stopBlink() {
        handler.removeCallbacks(blinkRunnable)
        listenMic.setColorTo(Lum.GLOW)
    }

    private fun startVisualizer() {
        // No synthetic loop: the bars are driven by real mic envelopes pushed from
        // the service via pushVisualizerEnvelope while the reply is LISTENING.
        handler.post { visualizer.clear() }
    }

    private fun stopVisualizer() {
        handler.post { visualizer.clear() }
    }

    /**
     * Feed a real mic-audio envelope (a flat [subWindows * bandsPerWindow] RMS
     * array, same packing the service broadcasts as EXTRA_AUDIO_LEVELS) into the
     * reply visualizer. The view replays the sub-windows at [intervalMs] so the
     * bars track speech. No-op unless we are actually showing the LISTENING phase.
     */
    fun pushVisualizerEnvelope(flat: FloatArray, bandsPerWindow: Int, intervalMs: Long = 50L) {
        handler.post {
            if (phase != ReplyPhase.LISTENING) return@post
            visualizer.pushEnvelope(flat, bandsPerWindow, intervalMs)
        }
    }

    /** REPLACE the currently-displayed notification's body IF its notifId matches. Returns true if set. */
    fun setCurrentTextById(notifId: String, fullText: String): Boolean {
        val cur = current ?: return false
        if (!isShowing) return false
        if (cur.notifId != notifId) return false
        handler.post {
            // Re-validate on the overlay thread: the live item may have changed
            // or dismissed between the caller's snapshot check and here.
            val live = current ?: return@post
            if (live.notifId != notifId) return@post
            // REPLACE the body with the phone's full recomputed text (do NOT concatenate).
            current = live.copy(text = fullText)
            messageLabel.text = fullText
            // A merge can land during the show fade (alpha 0 -> 1). The relayout below
            // can cancel that animator, so snap the card to full opacity here -- never
            // leave it transparent (TTS plays, no UI). Also end any in-flight fade.
            root.animate().cancel()
            root.alpha = 1f
            // The overlay window is WRAP_CONTENT: replacing 1-row text with multi-row
            // text grows the view but NOT the window surface, so the extra rows + bottom
            // border get clipped. Re-measure and resize the window to fit the new height
            // (the initial showNext path does this via updateViewLayout; the merge path
            // must too). Post after the text change so measurement uses the new content.
            if (isAttached) {
                root.post {
                    try {
                        wm.updateViewLayout(root, layoutParams)
                    } catch (e: Exception) {
                        remoteLog?.invoke("NotifOverlay: set-text relayout failed: ${e.message}")
                    }
                }
            }
            // Reply owns the timer when frozen -- set text only, leave timer.
            if (!frozen) {
                handler.removeCallbacks(dismissRunnable)
                handler.postDelayed(dismissRunnable, currentDismissDelayMs)
            }
            remoteLog?.invoke("NotifOverlay: set text for current id=$notifId")
        }
        return true
    }

    // --- freeze / dismiss ----------------------------------------------------
    fun freezeDismiss() {
        handler.post {
            handler.removeCallbacks(dismissRunnable)
            frozen = true
        }
    }

    fun unfreezeDismiss() {
        handler.post {
            if (frozen) {
                frozen = false
                handler.postDelayed(dismissRunnable, currentDismissDelayMs)
            }
        }
    }

    private fun dismissCurrent() {
        snapshotRenderState("dismiss")
        handler.removeCallbacks(dismissRunnable)
        handler.removeCallbacks(steadyStateSnapshot)
        stopBlink(); stopVisualizer(); stopPulse(); stopCountdown()
        progressAnimator?.cancel()
        frozen = false
        var removed = false
        try {
            if (isAttached) {
                wm.removeView(root)
                removed = true
            }
        } catch (e: Exception) {
            remoteLog?.invoke("NotifOverlay: removeView failed: ${e.message}")
        } finally {
            if (removed) isAttached = false
        }
        isShowing = false
        phase = ReplyPhase.IDLE
        renderedPhase = null
        transcript = ""
        progressFraction = 0f
        val dismissed = current
        current = null
        if (dismissed != null) {
            onItemDismissed?.invoke(dismissed.notifId)
        }
        if (queue.isNotEmpty()) {
            handler.post { showNext() }
        } else {
            onAllDismissed?.invoke()
        }
    }

    // --- monochrome stroke glyphs (no image assets) --------------------------

    /** Telegram paper-plane mark, stroked path scaled into the view bounds. */
    private class TgMarkView(context: Context, private val dp: Float) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * dp
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Lum.BRIGHT
        }
        private val path = Path()
        init { isFocusable = false }
        fun setColorTo(c: Int) { paint.color = c; invalidate() }
        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            val s = minOf(w, h) / 24f
            fun x(v: Float) = v * s
            fun y(v: Float) = v * s
            path.reset()
            path.moveTo(x(3f), y(11f))
            path.lineTo(x(20f), y(5f))
            path.lineTo(x(17f), y(19f))
            path.lineTo(x(12f), y(17f))
            path.lineTo(x(10f), y(21f))
            path.lineTo(x(9f), y(16f))
            path.lineTo(x(18f), y(9f))
            path.lineTo(x(7f), y(14f))
            path.close()
        }
        override fun onDraw(canvas: Canvas) {
            canvas.drawPath(path, paint)
        }
    }

    /** Simple microphone glyph (capsule + stand). Stroked, monochrome. */
    private class MicGlyphView(context: Context, private val dp: Float) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * dp
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Lum.DIM
        }
        init { isFocusable = false }
        fun setColorTo(c: Int) { paint.color = c; invalidate() }
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val capW = w * 0.34f
            val capLeft = (w - capW) / 2f
            val capTop = h * 0.12f
            val capBottom = h * 0.58f
            val r = capW / 2f
            canvas.drawRoundRect(capLeft, capTop, capLeft + capW, capBottom, r, r, paint)
            val cradleTop = h * 0.40f
            val cradleBottom = h * 0.72f
            canvas.drawArc(w * 0.26f, cradleTop, w * 0.74f, cradleBottom, 0f, 180f, false, paint)
            canvas.drawLine(w / 2f, cradleBottom, w / 2f, h * 0.86f, paint)
            canvas.drawLine(w * 0.36f, h * 0.90f, w * 0.64f, h * 0.90f, paint)
        }
    }

    /** Check-mark tick glyph. Stroked, monochrome. */
    private class TickGlyphView(context: Context, private val dp: Float) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * dp
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Lum.GLOW
        }
        private val path = Path()
        init { isFocusable = false }
        fun setColorTo(c: Int) { paint.color = c; invalidate() }
        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            path.reset()
            path.moveTo(w * 0.16f, h * 0.54f)
            path.lineTo(w * 0.42f, h * 0.80f)
            path.lineTo(w * 0.86f, h * 0.22f)
        }
        override fun onDraw(canvas: Canvas) {
            canvas.drawPath(path, paint)
        }
    }

    /** Cross / X glyph -- the contrary of the tick, used for CANCELLED. */
    private class CrossGlyphView(context: Context, private val dp: Float) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * dp
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Lum.DIM
        }
        init { isFocusable = false }
        fun setColorTo(c: Int) { paint.color = c; invalidate() }
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            canvas.drawLine(w * 0.22f, h * 0.22f, w * 0.78f, h * 0.78f, paint)
            canvas.drawLine(w * 0.78f, h * 0.22f, w * 0.22f, h * 0.78f, paint)
        }
    }
}
