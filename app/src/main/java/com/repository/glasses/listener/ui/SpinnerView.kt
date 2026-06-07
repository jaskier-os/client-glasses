package com.repository.glasses.listener.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Minimal spinning arc indicator for the waveguide display.
 * Draws a green arc that rotates continuously.
 */
class SpinnerView(context: Context, private val sizeDp: Int = 16) : View(context) {

    private val sizePx = (sizeDp * Resources.getSystem().displayMetrics.density).toInt()
    private val strokePx = (1.5f * Resources.getSystem().displayMetrics.density)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        strokeCap = Paint.Cap.ROUND
        color = Lum.DIM
    }

    private val rect = RectF()
    private var rotation = 0f
    private var animator: ValueAnimator? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sizePx, sizePx)
    }

    override fun onDraw(canvas: Canvas) {
        val inset = strokePx / 2f
        rect.set(inset, inset, width - inset, height - inset)
        canvas.rotate(rotation, width / 2f, height / 2f)
        canvas.drawArc(rect, 0f, 270f, false, paint)
    }

    fun start() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 800L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                rotation = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
    }
}
