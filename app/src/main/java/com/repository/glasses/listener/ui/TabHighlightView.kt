package com.repository.glasses.listener.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View

/**
 * The moving tab-selection highlight.
 *
 * Draws a solid GHOST-filled circle centered in its bounds. Whatever animates
 * the view's translationX (drag spring, snap spring, Anim.translateX) drives
 * the motion; this view samples its own translationX every frame and stretches
 * the circle into a horizontal ellipse proportional to speed -- the faster it
 * travels between tabs, the flatter it gets. At rest it relaxes back to a
 * perfect circle.
 *
 * No outline, no rounded-rect pill: just the blob.
 */
class TabHighlightView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Lum.GHOST
    }

    // Speed (px/ms) -> elongation. ~3x the previous squeeze so crossing tabs
    // flattens the blob hard into a thin horizontal streak.
    private val elongGain = 2.55f
    private val maxElong = 4.5f
    // Vertical squash: how much of the elongation eats into the height. Near 1
    // means the blob collapses to a thin horizontal bar while moving.
    private val squashShare = 0.92f
    // Low-pass on the measured speed so a single jumpy frame can't snap the
    // shape; also gives the "decay back to a circle" easing for free.
    private val speedSmoothing = 0.35f

    private var lastX = Float.NaN
    private var lastFrameNanos = 0L
    private var smoothedSpeed = 0f
    // Current elongation [0..maxElong]. 0 = perfect circle.
    private var elong = 0f

    // The frame loop only runs WHILE the blob is moving or relaxing back to a
    // circle. At rest it is fully idle -- no frame callbacks, no invalidate --
    // so a static tab bar costs zero per-frame work. Motion drivers wake it via
    // setTranslationX (bottom pill) or a layout/left change (TODO capsule).
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

    /** Start the per-frame loop if it isn't already running. */
    private fun wake() {
        if (running || !isAttachedToWindow) return
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /** @return true to keep animating next frame, false to go idle. */
    private fun tick(frameTimeNanos: Long): Boolean {
        // Track the absolute on-screen X so both kinds of motion flatten the
        // blob: the bottom tab pill animates translationX (left stays 0), while
        // the TODO sub-tab capsule animates its leftMargin (which moves `left`).
        val x = left.toFloat() + translationX
        if (lastX.isNaN() || lastFrameNanos == 0L) {
            lastX = x
            lastFrameNanos = frameTimeNanos
            return true
        }
        val dtMs = (frameTimeNanos - lastFrameNanos) / 1_000_000f
        lastFrameNanos = frameTimeNanos
        if (dtMs <= 0f) return true
        val moved = kotlin.math.abs(x - lastX)
        val instSpeed = moved / dtMs
        lastX = x
        smoothedSpeed += (instSpeed - smoothedSpeed) * speedSmoothing
        val targetElong = (smoothedSpeed * elongGain).coerceIn(0f, maxElong)
        elong += (targetElong - elong) * 0.5f
        if (elong < 0.01f && smoothedSpeed < 0.01f) {
            // Fully settled: snap to a clean circle, repaint once, go idle.
            if (elong != 0f) {
                elong = 0f
                invalidate()
            }
            return false
        }
        invalidate()
        return true
    }

    /**
     * Reset the motion tracking to a clean, at-rest circle. Call this whenever
     * the view is teleported to a new position (e.g. it re-appears at a
     * different slot than where it was last hidden) so the discontinuous jump
     * is NOT measured as a huge speed -- which would otherwise flatten the blob
     * to a streak for a few frames before it relaxes.
     */
    fun snapToRest() {
        lastX = Float.NaN
        lastFrameNanos = 0L
        smoothedSpeed = 0f
        if (elong != 0f) {
            elong = 0f
            invalidate()
        }
    }

    override fun setTranslationX(translationX: Float) {
        val changed = translationX != this.translationX
        super.setTranslationX(translationX)
        if (changed) wake()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // The TODO capsule moves by animating its leftMargin (-> new left).
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
        // Base radius: fill the bar height so the resting blob is a big circle.
        val baseR = h / 2f
        if (baseR <= 0f) return
        // Horizontal stretch is allowed to overflow the slot into the
        // clipChildren=false container so a fast move reads as a long streak.
        val rx = baseR * (1f + elong)
        val ry = (baseR * (1f - elong * squashShare)).coerceAtLeast(baseR * 0.12f)
        canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, fill)
    }
}
