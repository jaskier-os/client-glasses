package com.repository.glasses.headlockoverlay.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * Custom view that renders the head-locked panel mockup.
 *
 * This is a see-through AR combiner: pure black reads as transparent to the eye, so panels are
 * drawn as dark-but-opaque cards with BRIGHT borders/text to stay readable against the world.
 * On a normal screen recording the near-black background is visibly dark (not pure black) so the
 * layout is still legible when verified off-device.
 *
 * The activity pushes fresh pose state into the public fields and calls [invalidate].
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // --- state driven by the activity ---
    var offsetYawDeg = 0f
    var offsetPitchDeg = 0f
    var headYawDeg = 0f
    var headPitchDeg = 0f
    var refYawDeg = 0f

    val projection = Projection()

    // Debug-readout mirrors of the lazy-follow tunables (display only).
    var followRate = 0.6f
    var deadzoneDeg = 6f
    var selectedTunable: String = "k"

    var debugEnabled = true

    /** Convenience one-shot update from the activity. Caller still invokes [invalidate]. */
    fun updateFrame(
        offsetYaw: Float,
        offsetPitch: Float,
        headYaw: Float,
        headPitch: Float,
        refYaw: Float,
    ) {
        offsetYawDeg = offsetYaw
        offsetPitchDeg = offsetPitch
        headYawDeg = headYaw
        headPitchDeg = headPitch
        refYawDeg = refYaw
    }

    // --- Paints allocated once (never in onDraw) ---
    private val bgPaint = Paint().apply { color = Color.rgb(6, 6, 10) }

    private val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.argb(120, 120, 160, 200)
    }

    private val cardFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(210, 18, 20, 28)
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 235, 245, 255)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 200, 215, 230)
    }

    private val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 180, 255, 180)
        textSize = 20f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }

    private val cardRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w == 0 || h == 0) return

        // Background (near-black, not pure black).
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        // Center reticle.
        val ccx = w / 2f
        val ccy = h / 2f
        val arm = 14f
        canvas.drawLine(ccx - arm, ccy, ccx + arm, ccy, reticlePaint)
        canvas.drawLine(ccx, ccy - arm, ccx, ccy + arm, reticlePaint)

        // Panels.
        for (panel in MockPanels.PANELS) {
            val cx = projection.screenX(panel.yawDeg, offsetYawDeg, w)
            val cy = projection.screenY(panel.pitchDeg, offsetPitchDeg, w, h)
            drawPanel(canvas, panel, cx, cy, w, h)
        }

        if (debugEnabled) drawDebug(canvas)
    }

    private fun drawPanel(canvas: Canvas, panel: Panel, cx: Float, cy: Float, w: Int, h: Int) {
        val (cardW, cardH) = cardSize(panel.role)
        val left = cx - cardW / 2f
        val top = cy - cardH / 2f
        val right = cx + cardW / 2f
        val bottom = cy + cardH / 2f

        // Cull fully off-screen cards so panels only appear as you turn.
        if (right < 0f || left > w || bottom < 0f || top > h) return

        cardRect.set(left, top, right, bottom)
        val corner = 14f
        canvas.drawRoundRect(cardRect, corner, corner, cardFillPaint)

        borderPaint.color = borderColor(panel.role)
        canvas.drawRoundRect(cardRect, corner, corner, borderPaint)

        val (titleSize, bodySize) = textSizes(panel.role)
        val pad = 14f

        // Title near the top of the card.
        titlePaint.textSize = titleSize
        var textY = top + pad + titleSize
        canvas.drawText(panel.title, left + pad, textY, titlePaint)

        // Body lines below the title.
        bodyPaint.textSize = bodySize
        val lineH = bodySize + 6f
        textY += lineH + 4f
        for (line in panel.lines) {
            canvas.drawText(line, left + pad, textY, bodyPaint)
            textY += lineH
        }
    }

    private fun drawDebug(canvas: Canvas) {
        val x = 12f
        var y = 28f
        val lineH = 24f
        val lines = listOf(
            "head y=%.1f p=%.1f".format(headYawDeg, headPitchDeg),
            "ref  y=%.1f".format(refYawDeg),
            "off  y=%.1f p=%.1f".format(offsetYawDeg, offsetPitchDeg),
            tunablesLine(),
        )
        for (line in lines) {
            canvas.drawText(line, x, y, debugPaint)
            y += lineH
        }
    }

    private fun tunablesLine(): String {
        fun mark(key: String) = if (selectedTunable == key) ">" else " "
        return "%sk=%.2f %sD=%.1f %sfov=%.1f".format(
            mark("k"), followRate,
            mark("D"), deadzoneDeg,
            mark("fov"), projection.horizontalFovDeg,
        )
    }

    private fun cardSize(role: PanelRole): Pair<Float, Float> = when (role) {
        PanelRole.CENTER -> 260f to 150f
        PanelRole.PRIMARY -> 220f to 140f
        PanelRole.SECONDARY -> 180f to 120f
        PanelRole.FAR -> 170f to 120f
    }

    private fun textSizes(role: PanelRole): Pair<Float, Float> = when (role) {
        PanelRole.CENTER -> 30f to 22f
        PanelRole.PRIMARY -> 24f to 18f
        PanelRole.SECONDARY -> 20f to 18f
        PanelRole.FAR -> 20f to 18f
    }

    private fun borderColor(role: PanelRole): Int = when (role) {
        PanelRole.CENTER -> Color.argb(255, 120, 220, 255)
        PanelRole.PRIMARY -> Color.argb(255, 120, 200, 255)
        PanelRole.SECONDARY -> Color.argb(255, 150, 170, 210)
        PanelRole.FAR -> Color.argb(255, 200, 180, 120)
    }
}
