package com.repository.glasses.listener.ui

import android.animation.ValueAnimator
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The 3 s "you can still take it back" countdown, as ONE view used by every dictation surface.
 *
 * Both the regular AI chat and the RC thread show this, so the wearer sees the same thing however
 * they got here. It was previously inlined into the RC thread's layout only, which is how the two
 * surfaces came to disagree about whether a dictation could be withdrawn at all.
 *
 * The bar drains with a [ValueAnimator], not a posted tick: the old 100 ms handler stepped it in
 * visible jumps on a 60 Hz waveguide. The COMMIT is deliberately NOT this animator's job -- see
 * [start].
 */
class SendCountdownBar @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val track: FrameLayout
    private val fill: View
    private val row: LinearLayout
    private val hint: TextView
    private val secs: TextView

    private var animator: ValueAnimator? = null

    private fun Int.px(): Int = (this * resources.displayMetrics.density + 0.5f).toInt()

    init {
        orientation = VERTICAL
        setBackgroundColor(Lum.VOID)
        visibility = GONE

        // The fill sits INSIDE the track so the two read as one 2dp line whose lit portion
        // shrinks -- not as two stacked lines.
        fill = View(context).apply {
            setBackgroundColor(Lum.GLOW)
            pivotX = 0f
        }
        track = FrameLayout(context).apply {
            setBackgroundColor(Lum.SOFT)
            addView(fill, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 2.px()
            ))
        }
        addView(track, LayoutParams(LayoutParams.MATCH_PARENT, 2.px()).apply {
            topMargin = 9.px()
        })

        hint = TextView(context).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            text = CANCEL_HINT
            setTextColor(Lum.MID)
            letterSpacing = 0.18f
            setBackgroundColor(Lum.VOID)
        }
        secs = TextView(context).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Lum.GLOW)
            setBackgroundColor(Lum.VOID)
        }
        row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Lum.VOID)
            addView(hint, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(secs, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = 6.px()
        })

        applyFontScale()
    }

    /** Re-reads [ChatFontScale]. Call when the wearer changes the font setting. */
    fun applyFontScale() {
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, ChatFontScale.sp(HINT_SP))
        secs.setTextSize(TypedValue.COMPLEX_UNIT_SP, ChatFontScale.sp(SECS_SP))
    }

    /**
     * Show the bar and drain it over [durationMs].
     *
     * The caller still owns the actual send, on its own timer. An animation can be cancelled,
     * paused when the window loses focus, or scaled to zero duration by the system's animator
     * setting -- none of which may decide whether a message reaches an agent.
     */
    fun start(durationMs: Long = DictationUx.WINDOW_MS) {
        stop()
        visibility = VISIBLE
        fill.pivotX = 0f
        fill.scaleX = 1f
        secs.text = RcCountdownLabel.of(durationMs)
        animator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val fraction = a.animatedValue as Float
                fill.scaleX = fraction
                secs.text = RcCountdownLabel.of((fraction * durationMs).toLong())
            }
            start()
        }
    }

    /** Cancel the animation and hide the bar. Idempotent. */
    fun stop() {
        animator?.cancel()
        animator = null
        visibility = GONE
    }

    /**
     * A running ValueAnimator is held by the process-global AnimationHandler and its update
     * listener captures this view's children, so an animator left running past detach keeps
     * ticking against a dead hierarchy and pins the Activity. Nothing outside can be relied on to
     * remember; the view cleans up after itself.
     */
    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    companion object {
        const val CANCEL_HINT = "DOUBLE-TAP TO CANCEL"
        private const val HINT_SP = 9f
        private const val SECS_SP = 10f
    }
}
