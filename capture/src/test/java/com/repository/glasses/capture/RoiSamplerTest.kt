package com.repository.glasses.capture

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * JVM unit tests for [RoiSampler]. Pure Kotlin, no Android framework. Synthetic
 * images are fed through the [RoiSampler.RgbImage] accessor so geometry, the
 * frontal/min-size gates, and the YCbCr skin mask can be exercised without a
 * Bitmap or a camera.
 *
 * Skin color used: (200,150,120) -- a tan/beige that passes the production
 * YCbCr gate (Cb in [77,127], Cr in [133,173]). Background: pure green
 * (0,255,0), which the mask rejects, so even a slightly overspilling ROI yields
 * the skin mean.
 */
class RoiSamplerTest {

    private val skinR = 200
    private val skinG = 150
    private val skinB = 120
    private val skin = pack(skinR, skinG, skinB)
    private val bgGreen = pack(0, 255, 0)

    private fun pack(r: Int, g: Int, b: Int): Int = (r shl 16) or (g shl 8) or b

    /** Image that is [fill] everywhere except an oriented rectangle painted [block]. */
    private fun orientedImage(
        cx: Double,
        cy: Double,
        raxX: Double,
        raxY: Double,
        uaxX: Double,
        uaxY: Double,
        halfR: Double,
        halfU: Double,
        block: Int,
        fill: Int,
    ): RoiSampler.RgbImage = RoiSampler.RgbImage { x, y ->
        val dx = x - cx
        val dy = y - cy
        val projR = dx * raxX + dy * raxY
        val projU = dx * uaxX + dy * uaxY
        if (kotlin.math.abs(projR) <= halfR && kotlin.math.abs(projU) <= halfU) block else fill
    }

    /** 5 SCRFD keypoints (10 floats) for an upright (no-roll) frontal face. */
    private fun uprightKps(
        eyeCx: Double,
        eyeCy: Double,
        d: Double,
        noseDrop: Double = 0.5,
    ): FloatArray {
        val half = d / 2.0
        val rex = eyeCx - half // subject right eye = image left
        val lex = eyeCx + half
        val ey = eyeCy
        val nx = eyeCx
        val ny = eyeCy + d * noseDrop
        return floatArrayOf(
            rex.toFloat(), ey.toFloat(),
            lex.toFloat(), ey.toFloat(),
            nx.toFloat(), ny.toFloat(),
            (eyeCx - d * 0.25).toFloat(), (ny + d * 0.3).toFloat(),
            (eyeCx + d * 0.25).toFloat(), (ny + d * 0.3).toFloat(),
        )
    }

    @Test
    fun foreheadSkinIsSampled() {
        val w = 400
        val h = 400
        val eyeCx = 200.0
        val eyeCy = 220.0
        val d = 80.0
        val kps = uprightKps(eyeCx, eyeCy, d)
        // Forehead center: E + uax*(0.6 d). uax points up (negative y) since nose
        // is below eyes. E=(200,220), so center=(200, 220 - 0.6*80)=(200,172).
        val cx = eyeCx
        val cy = eyeCy - 0.6 * d
        // Paint a generous skin block over the whole forehead ROI extent.
        val img = orientedImage(
            cx, cy,
            1.0, 0.0, 0.0, -1.0,
            0.6 * d + 4, 0.25 * d + 4,
            skin, bgGreen,
        )
        val s = RoiSampler.sampleForehead(img, w, h, kps)
        assertNotNull(s)
        s!!
        assertTrue(s.pixelCount > 0)
        assertTrue("r near skin", kotlin.math.abs(s.r - skinR) < 3f)
        assertTrue("g near skin", kotlin.math.abs(s.g - skinG) < 3f)
        assertTrue("b near skin", kotlin.math.abs(s.b - skinB) < 3f)
    }

    @Test
    fun rolledFaceStillSamplesSkin() {
        // Rotate the whole face 25 degrees about the eye center. The oriented ROI
        // must rotate with it; an axis-aligned box would miss the rotated block.
        val w = 400
        val h = 400
        val eyeCx = 200.0
        val eyeCy = 220.0
        val d = 80.0
        val theta = Math.toRadians(25.0)
        val cosT = cos(theta)
        val sinT = sin(theta)

        // Upright kps, then rotate each point about (eyeCx,eyeCy).
        val up = uprightKps(eyeCx, eyeCy, d)
        val kps = FloatArray(10)
        for (i in 0 until 5) {
            val px = up[i * 2] - eyeCx
            val py = up[i * 2 + 1] - eyeCy
            kps[i * 2] = (eyeCx + px * cosT - py * sinT).toFloat()
            kps[i * 2 + 1] = (eyeCy + px * sinT + py * cosT).toFloat()
        }
        // Rotated axes. Upright rax=(1,0), uax=(0,-1). Rotate both by +theta.
        val raxX = cosT
        val raxY = sinT
        val uaxX = sinT
        val uaxY = -cosT
        // Forehead center along the rotated up axis.
        val cx = eyeCx + uaxX * (0.6 * d)
        val cy = eyeCy + uaxY * (0.6 * d)
        val img = orientedImage(
            cx, cy,
            raxX, raxY, uaxX, uaxY,
            0.6 * d + 4, 0.25 * d + 4,
            skin, bgGreen,
        )
        val s = RoiSampler.sampleForehead(img, w, h, kps)
        assertNotNull("rolled face must still find skin", s)
        s!!
        assertTrue(s.pixelCount > 0)
        assertTrue("r near skin", kotlin.math.abs(s.r - skinR) < 3f)
        assertTrue("g near skin", kotlin.math.abs(s.g - skinG) < 3f)
        assertTrue("b near skin", kotlin.math.abs(s.b - skinB) < 3f)
    }

    @Test
    fun yawedFaceReturnsNull() {
        // Nose pushed far off the eye-center axis -> noseProjR > 0.25 d -> not frontal.
        val w = 400
        val h = 400
        val eyeCx = 200.0
        val eyeCy = 220.0
        val d = 80.0
        val kps = uprightKps(eyeCx, eyeCy, d)
        // Move nose x far to the right of E along rax: offset = 0.5 d > 0.25 d.
        kps[4] = (eyeCx + 0.5 * d).toFloat()
        // Fill forehead with skin so only the gate (not skin scarcity) can cause null.
        val cx = eyeCx
        val cy = eyeCy - 0.6 * d
        val img = orientedImage(
            cx, cy,
            1.0, 0.0, 0.0, -1.0,
            0.6 * d + 4, 0.25 * d + 4,
            skin, bgGreen,
        )
        assertNull(RoiSampler.sampleForehead(img, w, h, kps))
    }

    @Test
    fun tooSmallFaceReturnsNull() {
        val w = 400
        val h = 400
        val d = 6.0 // below the min inter-ocular threshold
        val kps = uprightKps(200.0, 220.0, d)
        val img = RoiSampler.RgbImage { _, _ -> skin } // all skin -> only size gate matters
        assertNull(RoiSampler.sampleForehead(img, w, h, kps))
    }

    @Test
    fun nonSkinForeheadReturnsNull() {
        // Geometrically valid forehead, but it is bright green -> mask rejects all
        // pixels -> too few kept -> null.
        val w = 400
        val h = 400
        val eyeCx = 200.0
        val eyeCy = 220.0
        val d = 80.0
        val kps = uprightKps(eyeCx, eyeCy, d)
        val img = RoiSampler.RgbImage { _, _ -> bgGreen }
        assertNull(RoiSampler.sampleForehead(img, w, h, kps))
    }

    @Test
    fun outOfBoundsRoiDoesNotCrash() {
        // Eyes near the top edge so the forehead ROI is mostly above the image.
        val w = 200
        val h = 200
        val eyeCx = 100.0
        val eyeCy = 4.0
        val d = 80.0
        val kps = uprightKps(eyeCx, eyeCy, d)
        // All skin, but the ROI is almost entirely off-image (y < 0 clipped).
        val img = RoiSampler.RgbImage { _, _ -> skin }
        // Must not crash; result is either null (too few in-bounds pixels) or a
        // valid sample. Either is acceptable; the assertion is just no exception.
        val s = RoiSampler.sampleForehead(img, w, h, kps)
        if (s != null) assertTrue(s.pixelCount >= 0)
    }

    @Test
    fun fullyOffImageRoiReturnsNull() {
        // Eyes above the top edge entirely; forehead is fully off-image -> null.
        val w = 200
        val h = 200
        val eyeCy = -100.0
        val d = 80.0
        val kps = uprightKps(100.0, eyeCy, d)
        val img = RoiSampler.RgbImage { _, _ -> skin }
        assertNull(RoiSampler.sampleForehead(img, w, h, kps))
    }
}
