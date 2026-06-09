package com.repository.glasses.listener.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Fixed-size window for the heading-up minimap. Applies TWO frame-fixed effects
 * to its rotating child map, both in this view's own coordinate space so neither
 * rotates with the map:
 *
 *  1. A trapezoid perspective (keystone): the top edge is pulled inward so the
 *     map looks tilted into the ground plane with a vanishing point at the top.
 *  2. A 4-edge fade-to-black vignette that softens the window border.
 *
 * Both are drawn in dispatchDraw AFTER each child renders to its own render node,
 * so a child's View.rotation (the heading-up map spin) happens BELOW these in the
 * transform stack. Previously the fade was baked into the bitmap and rotated with
 * the map while the keystone stayed frame-fixed -- the inconsistent reference
 * frames produced a "fog of war" look. Keeping both here makes them consistent.
 *
 * The child map is oversized (a square larger than the window diagonal) and uses
 * centerCrop so that, however it rotates about the window center, it always fully
 * covers the window -- the rotation never exposes black corners. The window clips
 * the overflow.
 */
class KeystoneFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val keystone = Matrix()
    private var hasKeystone = false

    // Reused fade paint; shader is swapped per edge.
    private val fadePaint = Paint()
    private var fadeBand = 0f
    private var fw = 0f
    private var fh = 0f

    init {
        setWillNotDraw(false)
        // We clip the rotating oversized child to the window ourselves (via the
        // canvas clip in dispatchDraw), so leave child clipping off here.
        clipChildren = false
        clipToPadding = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) {
            hasKeystone = false
            return
        }
        fw = w.toFloat()
        fh = h.toFloat()
        val inset = fw * 0.18f
        val srcPts = floatArrayOf(
            0f, 0f,
            fw, 0f,
            fw, fh,
            0f, fh
        )
        val dstPts = floatArrayOf(
            inset, 0f,
            fw - inset, 0f,
            fw, fh,
            0f, fh
        )
        keystone.setPolyToPoly(srcPts, 0, dstPts, 0, 4)
        fadeBand = (minOf(fw, fh) * 0.18f).coerceAtLeast(1f)
        hasKeystone = true
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (!hasKeystone) {
            super.dispatchDraw(canvas)
            return
        }
        val save = canvas.save()
        canvas.concat(keystone)
        // Clip the oversized rotating map to the window (in keystone space, so the
        // clip is the trapezoid). The map square always covers this region.
        canvas.clipRect(0f, 0f, fw, fh)
        super.dispatchDraw(canvas)
        // Frame-fixed edge fade, drawn in this view's coordinate space under the
        // same keystone concat -- so it warps identically to the window and does
        // NOT rotate with the map.
        drawEdgeFade(canvas)
        canvas.restoreToCount(save)
    }

    private fun drawEdgeFade(canvas: Canvas) {
        val b = fadeBand
        // Top
        fadePaint.shader = LinearGradient(
            0f, 0f, 0f, b, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, fw, b, fadePaint)
        // Bottom
        fadePaint.shader = LinearGradient(
            0f, fh, 0f, fh - b, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, fh - b, fw, fh, fadePaint)
        // Left
        fadePaint.shader = LinearGradient(
            0f, 0f, b, 0f, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, b, fh, fadePaint)
        // Right
        fadePaint.shader = LinearGradient(
            fw, 0f, fw - b, 0f, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawRect(fw - b, 0f, fw, fh, fadePaint)
    }
}
