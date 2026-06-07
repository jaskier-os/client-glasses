package com.repository.glasses.listener.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * Animated equalizer bars for audio visualization on Rokid waveguide.
 * 32 vertical bars with smooth height transitions and rounded container outline.
 */
class AudioVisualizerView(context: Context) : View(context) {

    private val bandCount = 32
    private val currentLevels = FloatArray(bandCount)
    private val targetLevels = FloatArray(bandCount)
    private val density = resources.displayMetrics.density
    private val cornerRadius = 12f * density
    private val outlineWidth = 1f * density
    private val barGap = 1f * density
    private val sidePadding = 8f * density
    private val minBarHeight = 2f * density
    private var animating = false

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Lum.BRIGHT
        style = Paint.Style.FILL
    }

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Lum.DIM
        style = Paint.Style.STROKE
        strokeWidth = outlineWidth
    }

    private val clipPath = Path()
    private val bounds = RectF()
    private val outlineBounds = RectF()
    private val pendingEnvelope = ArrayList<Runnable>()

    // Geometry cached on size change. onDraw must not allocate or rebuild Path/RectF.
    private var hInset = 0f
    private var vInset = 0f
    private var barWidth = 0f
    private var barAreaHalfHeight = 0f
    private var centerY = 0f

    fun updateLevels(levels: FloatArray) {
        for (i in 0 until minOf(levels.size, bandCount)) {
            targetLevels[i] = levels[i].coerceIn(0f, 1f)
        }
        if (!animating) {
            animating = true
            invalidate()
        }
    }

    /**
     * Accepts a flat envelope of [subWindowCount] x [bandsPerWindow] floats covering
     * a single audio chunk and replays it through [updateLevels] at [intervalMs]
     * spacing so the bars animate continuously between mic chunks.
     */
    fun pushEnvelope(flat: FloatArray, bandsPerWindow: Int, intervalMs: Long = 50L) {
        if (bandsPerWindow <= 0) return
        cancelPendingEnvelope()
        val subCount = flat.size / bandsPerWindow
        if (subCount <= 0) return
        // Apply the first window immediately so there is no startup gap.
        updateLevels(extractWindow(flat, 0, bandsPerWindow))
        for (i in 1 until subCount) {
            val window = extractWindow(flat, i, bandsPerWindow)
            val r = Runnable { updateLevels(window) }
            pendingEnvelope.add(r)
            postDelayed(r, intervalMs * i)
        }
    }

    private fun extractWindow(flat: FloatArray, index: Int, bands: Int): FloatArray {
        val out = FloatArray(bands)
        val base = index * bands
        for (b in 0 until bands) out[b] = flat[base + b]
        return out
    }

    private fun cancelPendingEnvelope() {
        if (pendingEnvelope.isEmpty()) return
        for (r in pendingEnvelope) removeCallbacks(r)
        pendingEnvelope.clear()
    }

    fun clear() {
        cancelPendingEnvelope()
        targetLevels.fill(0f)
    }

    override fun onDetachedFromWindow() {
        cancelPendingEnvelope()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val wf = w.toFloat()
        val hf = h.toFloat()
        bounds.set(0f, 0f, wf, hf)
        clipPath.rewind()
        clipPath.addRoundRect(bounds, cornerRadius, cornerRadius, Path.Direction.CW)
        val half = outlineWidth / 2f
        outlineBounds.set(half, half, wf - half, hf - half)
        hInset = outlineWidth + sidePadding
        vInset = outlineWidth + barGap
        val barAreaWidth = wf - hInset * 2f
        val barAreaHeight = hf - vInset * 2f
        barWidth = (barAreaWidth - barGap * (bandCount - 1)) / bandCount
        barAreaHalfHeight = barAreaHeight / 2f
        centerY = hf / 2f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawColor(Lum.VOID)

        val maxHalf = barAreaHalfHeight
        val minHalf = minBarHeight / 2f
        val span = maxHalf - minHalf

        for (i in 0 until bandCount) {
            // Ease-in-out: fast attack (rising), slow decay (falling)
            val diff = targetLevels[i] - currentLevels[i]
            val factor = if (diff > 0f) 0.8f else 0.15f
            currentLevels[i] += diff * factor

            val level = currentLevels[i]
            val halfBar = (minHalf + level * span).coerceAtMost(maxHalf)
            val left = hInset + i * (barWidth + barGap)
            val right = left + barWidth
            val top = centerY - halfBar
            val bottom = centerY + halfBar

            barPaint.color = when {
                level > 0.6f -> Lum.GLOW
                level > 0.3f -> Lum.BRIGHT
                level > 0.1f -> Lum.MID
                else -> Lum.DIM
            }
            canvas.drawRect(left, top, right, bottom, barPaint)
        }

        canvas.restore()

        canvas.drawRoundRect(outlineBounds, cornerRadius, cornerRadius, outlinePaint)

        // Continue animation if any bar is still moving
        var stillMoving = false
        for (i in 0 until bandCount) {
            if (kotlin.math.abs(currentLevels[i] - targetLevels[i]) > 0.005f) {
                stillMoving = true
                break
            }
        }
        if (stillMoving) {
            postInvalidateOnAnimation()
        } else {
            animating = false
        }
    }
}
