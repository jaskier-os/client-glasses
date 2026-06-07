package com.repository.glasses.listener.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView

class TeleprompterController(
    private val context: Context,
    private val remoteLog: (String) -> Unit
) {
    companion object {
        private const val TAG = "Teleprompter"
        private const val FRAME_INTERVAL_MS = 33L // ~30fps
        private const val RESUME_AFTER_MANUAL_SCROLL_MS = 2000L
        private const val DEFAULT_FONT_SIZE = 22f
        private const val SCROLL_PX_PER_TICK = 3
    }

    data class WordInfo(
        val startOffset: Int,
        val endOffset: Int
    )

    enum class State { IDLE, RUNNING, PAUSED }

    var state = State.IDLE
        private set

    private var requestId: String? = null
    private var stateCallback: ((state: String, progress: Float) -> Unit)? = null

    var onScrollChanged: (() -> Unit)? = null

    private var scrollView: ScrollView? = null
    private var textView: TextView? = null
    private var rootFrame: FrameLayout? = null
    private var scrollListener: ViewTreeObserver.OnScrollChangedListener? = null

    // Speech tracking
    private var speechTrackingEnabled = false
    private var words: List<WordInfo> = emptyList()
    private var currentWordIndex = -1
    private var originalText = ""

    // Smooth scroll animation state (lerp toward target instead of smoothScrollTo)
    private var targetScrollY = 0
    private var animScrollY = 0f
    private var smoothScrollActive = false

    private val handler = Handler(Looper.getMainLooper())
    private val resumeAfterManualScrollHandler = Handler(Looper.getMainLooper())

    private val scrollRunnable = object : Runnable {
        override fun run() {
            if (state != State.RUNNING) return
            if (speechTrackingEnabled) return // speech tracking: no auto-scroll
            val sv = scrollView ?: return
            val maxScroll = maxScroll()
            if (maxScroll <= 0) {
                reportState("finished", 1f)
                stop()
                return
            }
            sv.scrollBy(0, SCROLL_PX_PER_TICK)
            if (sv.scrollY >= maxScroll) {
                reportState("finished", 1f)
                stop()
            } else {
                handler.postDelayed(this, FRAME_INTERVAL_MS)
            }
        }
    }

    private val resumeRunnable = Runnable {
        if (state == State.PAUSED && requestId != null) {
            resume()
        }
    }

    private val smoothScrollRunnable = object : Runnable {
        override fun run() {
            val sv = scrollView ?: return
            val diff = targetScrollY - animScrollY
            if (kotlin.math.abs(diff) < 1f) {
                animScrollY = targetScrollY.toFloat()
                sv.scrollTo(0, targetScrollY)
                smoothScrollActive = false
                return
            }
            // Lerp 12% per frame (~30fps) = smooth ease-out, reaches 90% in ~6 frames (~200ms)
            animScrollY += diff * 0.12f
            sv.scrollTo(0, animScrollY.toInt())
            handler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    fun createView(): FrameLayout {
        remoteLog("$TAG: createView")
        val frame = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Lum.VOID)
        }

        val sv = ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Lum.VOID)
            isFocusable = false
            isFocusableInTouchMode = false
            defaultFocusHighlightEnabled = false
            overScrollMode = ScrollView.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
        }

        val tv = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(Lum.VOID)
            setTextColor(Lum.GLOW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, DEFAULT_FONT_SIZE)
            setLineSpacing(0f, 1.5f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.START or Gravity.TOP
            setPadding(24, 24, 24, 24)
            isFocusable = false
            defaultFocusHighlightEnabled = false
        }

        sv.addView(tv)
        frame.addView(sv)

        scrollView = sv
        textView = tv
        rootFrame = frame

        // Notify scroll changes for external scroll indicator
        val listener = ViewTreeObserver.OnScrollChangedListener {
            onScrollChanged?.invoke()
        }
        scrollListener = listener
        sv.viewTreeObserver.addOnScrollChangedListener(listener)

        return frame
    }

    /** Returns (range, extent, offset) for external scroll indicator calculations. */
    fun getScrollMetrics(): Triple<Int, Int, Int>? {
        val sv = scrollView ?: return null
        val child = sv.getChildAt(0) ?: return null
        return Triple(child.height, sv.height, sv.scrollY)
    }

    fun start(
        reqId: String,
        text: String,
        fontSize: Float,
        speechTracking: Boolean,
        callback: (state: String, progress: Float) -> Unit
    ) {
        remoteLog("$TAG: start reqId=$reqId fontSize=$fontSize speechTracking=$speechTracking textLen=${text.length}")
        requestId = reqId
        stateCallback = callback
        speechTrackingEnabled = speechTracking
        originalText = text
        currentWordIndex = -1

        val tv = textView ?: return

        // Apply font size
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)

        if (speechTracking) {
            // Parse words and render with SpannableString (all GLOW initially)
            words = parseWords(text)
            remoteLog("$TAG: parsed ${words.size} words for speech tracking")
            renderWords(-1)
        } else {
            words = emptyList()
            tv.text = text
        }

        scrollView?.scrollTo(0, 0)
        targetScrollY = 0
        animScrollY = 0f
        smoothScrollActive = false
        handler.removeCallbacks(smoothScrollRunnable)

        state = State.RUNNING
        reportState("running", 0f)

        if (!speechTracking) {
            // Traditional auto-scroll (non-speech mode only)
            handler.postDelayed(scrollRunnable, FRAME_INTERVAL_MS)
        }
        // Speech tracking mode: no auto-scroll at all. Only setWordPosition() moves text.
    }

    fun setWordPosition(index: Int) {
        if (!speechTrackingEnabled) return
        if (index <= currentWordIndex) return // monotonic only
        if (index >= words.size) return

        currentWordIndex = index

        // Re-render words with dimming
        renderWords(index)

        // Scroll to keep current word at center of viewport
        scrollToWord(index)

        reportState("running", currentProgress())
    }

    fun pause() {
        if (state != State.RUNNING) return
        remoteLog("$TAG: pause")
        state = State.PAUSED
        handler.removeCallbacks(scrollRunnable)
        reportState("paused", currentProgress())
    }

    fun resume() {
        if (state != State.PAUSED) return
        remoteLog("$TAG: resume")
        resumeAfterManualScrollHandler.removeCallbacks(resumeRunnable)
        state = State.RUNNING
        reportState("running", currentProgress())

        if (!speechTrackingEnabled) {
            handler.postDelayed(scrollRunnable, FRAME_INTERVAL_MS)
        }
        // Speech tracking mode: no auto-scroll. Only setWordPosition() moves text.
    }

    fun togglePause() {
        when (state) {
            State.RUNNING -> pause()
            State.PAUSED -> resume()
            else -> {}
        }
    }

    fun scrollBy(pixels: Int) {
        val sv = scrollView ?: return
        remoteLog("$TAG: scrollBy=$pixels")

        val wasRunning = state == State.RUNNING
        if (wasRunning && !speechTrackingEnabled) {
            state = State.PAUSED
            handler.removeCallbacks(scrollRunnable)
        }

        sv.scrollBy(0, pixels)

        if (!speechTrackingEnabled) {
            resumeAfterManualScrollHandler.removeCallbacks(resumeRunnable)
            if (wasRunning || state == State.PAUSED) {
                resumeAfterManualScrollHandler.postDelayed(resumeRunnable, RESUME_AFTER_MANUAL_SCROLL_MS)
            }
            reportState("paused", currentProgress())
        }
    }

    fun stop() {
        if (state == State.IDLE) return
        remoteLog("$TAG: stop")
        val cb = stateCallback
        val progress = currentProgress()
        state = State.IDLE
        stateCallback = null
        requestId = null
        handler.removeCallbacks(scrollRunnable)
        handler.removeCallbacks(smoothScrollRunnable)
        smoothScrollActive = false
        resumeAfterManualScrollHandler.removeCallbacks(resumeRunnable)
        speechTrackingEnabled = false
        words = emptyList()
        currentWordIndex = -1
        cb?.invoke("stopped", progress)
    }

    fun destroy(container: ViewGroup) {
        remoteLog("$TAG: destroy")
        stop()
        scrollListener?.let { scrollView?.viewTreeObserver?.removeOnScrollChangedListener(it) }
        scrollListener = null
        onScrollChanged = null
        rootFrame?.let { container.removeView(it) }
        scrollView = null
        textView = null
        rootFrame = null
    }

    private fun parseWords(text: String): List<WordInfo> {
        val result = mutableListOf<WordInfo>()
        val pattern = Regex("\\S+")
        for (match in pattern.findAll(text)) {
            result.add(WordInfo(match.range.first, match.range.last + 1))
        }
        return result
    }

    private fun renderWords(upToIndex: Int) {
        val tv = textView ?: return
        val spannable = SpannableString(originalText)

        for (i in words.indices) {
            val word = words[i]
            val color = if (i <= upToIndex) Lum.DIM else Lum.GLOW
            spannable.setSpan(
                ForegroundColorSpan(color),
                word.startOffset,
                word.endOffset,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        tv.text = spannable
    }

    private fun scrollToWord(index: Int) {
        val tv = textView ?: return
        val sv = scrollView ?: return
        val layout = tv.layout ?: return

        if (index < 0 || index >= words.size) return

        val charOffset = words[index].startOffset
        val line = layout.getLineForOffset(charOffset)
        val lineTop = layout.getLineTop(line)

        // Target: current word at ~1/3 viewport height (comfortable reading position)
        targetScrollY = (lineTop - sv.height / 3).coerceIn(0, maxScroll())

        // Start smooth lerp animation if not already running
        if (!smoothScrollActive) {
            smoothScrollActive = true
            animScrollY = sv.scrollY.toFloat()
            handler.post(smoothScrollRunnable)
        }
        // If already running, just updating targetScrollY is enough -- the runnable will lerp to it
    }

    private fun maxScroll(): Int {
        val sv = scrollView ?: return 0
        val child = sv.getChildAt(0) ?: return 0
        return (child.height - sv.height).coerceAtLeast(0)
    }

    private fun currentProgress(): Float {
        if (speechTrackingEnabled && words.isNotEmpty()) {
            return if (currentWordIndex < 0) 0f
            else (currentWordIndex + 1).toFloat() / words.size
        }
        val max = maxScroll()
        if (max <= 0) return 0f
        return (scrollView?.scrollY?.toFloat() ?: 0f) / max
    }

    private fun reportState(stateName: String, progress: Float) {
        stateCallback?.invoke(stateName, progress)
    }
}
