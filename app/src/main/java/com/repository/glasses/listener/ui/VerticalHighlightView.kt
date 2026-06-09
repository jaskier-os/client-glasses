package com.repository.glasses.listener.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View

/**
 * The vertical counterpart of [TabHighlightView]: a bright GLOW-filled circle
 * cursor that JUMPS between list rows. Whatever animates the view's translationY
 * (the bouncy snap spring in MainActivity) drives the motion; this view samples
 * its own translationY each frame and stretches the circle into a VERTICAL
 * ellipse proportional to speed -- so a fast jump elongates along the travel
 * direction and squeezes in horizontally. At rest it relaxes back to a circle.
 *
 * Rest size is slightly smaller than the view bounds (restScale) so the settled
 * cursor reads as a small bright dot sitting on the row's static dot.
 */
class VerticalHighlightView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Lum.GLOW
    }

    // Speed (px/ms) -> elongation along the vertical travel axis.
    private val elongGain = 2.55f
    private val maxElong = 4.5f
    // Horizontal squeeze: how much of the elongation eats into the width. Near 1
    // means the blob collapses to a thin vertical sliver while moving.
    private val squashShare = 0.92f
    private val speedSmoothing = 0.35f
    // Settled cursor is a bit smaller than its bounds (the "shrinks a bit" cue).
    private val restScale = 0.82f

    private var lastY = Float.NaN
    private var lastFrameNanos = 0L
    private var smoothedSpeed = 0f
    private var elong = 0f

    // Idle when not moving: no frame callbacks, no invalidate.
    private var running = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val keepGoing = tick(frameTimeNanos)
            if (keepGoing && isAttachedToWindow) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                running = false
            }
        }
    }

    private fun wake() {
        if (running || !isAttachedToWindow) return
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /** @return true to keep animating next frame, false to go idle. */
    private fun tick(frameTimeNanos: Long): Boolean {
        val y = top.toFloat() + translationY
        if (lastY.isNaN() || lastFrameNanos == 0L) {
            lastY = y
            lastFrameNanos = frameTimeNanos
            return true
        }
        val dtMs = (frameTimeNanos - lastFrameNanos) / 1_000_000f
        lastFrameNanos = frameTimeNanos
        if (dtMs <= 0f) return true
        val instSpeed = kotlin.math.abs(y - lastY) / dtMs
        lastY = y
        smoothedSpeed += (instSpeed - smoothedSpeed) * speedSmoothing
        val targetElong = (smoothedSpeed * elongGain).coerceIn(0f, maxElong)
        elong += (targetElong - elong) * 0.5f
        if (elong < 0.01f && smoothedSpeed < 0.01f) {
            if (elong != 0f) {
                elong = 0f
                invalidate()
            }
            return false
        }
        invalidate()
        return true
    }

    /** Reset motion tracking so a teleport (appear at a new row) is not read as speed. */
    fun snapToRest() {
        lastY = Float.NaN
        lastFrameNanos = 0L
        smoothedSpeed = 0f
        if (elong != 0f) {
            elong = 0f
            invalidate()
        }
    }

    override fun setTranslationY(translationY: Float) {
        val changed = translationY != this.translationY
        super.setTranslationY(translationY)
        if (changed) wake()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val baseR = (kotlin.math.min(w, h) / 2f) * restScale
        if (baseR <= 0f) return
        // Vertical stretch along travel, horizontal squeeze inward.
        val ry = baseR * (1f + elong)
        val rx = (baseR * (1f - elong * squashShare)).coerceAtLeast(baseR * 0.12f)
        canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, fill)
    }
}
