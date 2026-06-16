package com.repository.glasses.capture

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Samples the MEAN skin RGB of a per-face FOREHEAD region for the rPPG (camera
 * heart-rate) pipeline. Given the 5 SCRFD keypoints of one detected face and a
 * pixel accessor over the source frame, it places an ORIENTED forehead rectangle
 * in a FACE-AXIS frame (so it rotates with head roll), gates out non-frontal and
 * too-small faces, applies a cheap YCbCr skin mask, and returns the mean R,G,B of
 * the kept skin pixels.
 *
 * Pure Kotlin (kotlin.math only). No Android, no Bitmap -- the frame is read
 * through [RgbImage] so the JVM unit tests can feed synthetic images and the real
 * camera path can wrap a YUV/Bitmap behind the same interface in a later device
 * task.
 *
 * Keypoint layout (10 floats, pixel coords, standard SCRFD order, matching
 * [ScrfdFaceDetector.FaceDet.kps]):
 *   kps[0],kps[1] = right eye (subject's right = image LEFT)
 *   kps[2],kps[3] = left eye
 *   kps[4],kps[5] = nose
 *   kps[6],kps[7] = right mouth corner (unused here)
 *   kps[8],kps[9] = left mouth corner (unused here)
 *
 * Geometry:
 *   E   = midpoint(rEye, lEye)
 *   d   = dist(rEye, lEye)                 (inter-ocular distance)
 *   rax = normalize(lEye - rEye)           (unit right axis along the eye line)
 *   uax = normalize(E - nose)              (unit up axis, nose -> forehead)
 *   ROI center = E + uax * (0.6 * d)
 *   half-extents: halfAlongR = 0.6 * d (width 1.2 d along rax),
 *                 halfAlongU = 0.25 * d (height 0.5 d along uax)
 * Sampling iterates the axis-aligned bounding box of the oriented rectangle
 * (clamped to the image) and keeps a pixel only when its projection onto rax/uax
 * lies within the half-extents -- so the ROI rotates with head roll for free.
 *
 * Gates (return null):
 *   - Frontal: noseProjR = dot(nose - E, rax); if abs(noseProjR) > 0.25 * d the
 *     face is yawed (turned) -> skip.
 *   - Min size: if d < [minInterOcular] the face is too small to sample reliably.
 *   - ROI fully out of bounds (empty clamped bbox).
 *   - Too few skin pixels kept (< [minSkinPixels]) -> unreliable.
 *
 * Skin mask (BT.601 YCbCr): keep a pixel only if Cb in [77,127] and Cr in
 * [133,173]. The mean R,G,B is taken over KEPT pixels only.
 */
object RoiSampler {

    /** Minimum inter-ocular distance in pixels for a face to be sampled. */
    const val MIN_INTER_OCULAR: Float = 12f

    /** Minimum number of kept skin pixels for a reliable mean. */
    const val MIN_SKIN_PIXELS: Int = 10

    private const val FOREHEAD_UP = 0.6 // ROI center offset along uax, in units of d
    private const val HALF_R = 0.6 // half-extent along rax, in units of d
    private const val HALF_U = 0.25 // half-extent along uax, in units of d
    private const val YAW_FRAC = 0.25 // max abs(noseProjR)/d for a frontal face

    private const val CB_LO = 77
    private const val CB_HI = 127
    private const val CR_LO = 133
    private const val CR_HI = 173

    /**
     * Functional pixel source over the source frame. Returns packed 0xRRGGBB (no
     * alpha) for pixel (x,y); the caller guarantees the coordinate is in-bounds.
     */
    fun interface RgbImage {
        fun rgbAt(x: Int, y: Int): Int
    }

    /** Mean R,G,B (0..255 floats) of the kept skin pixels plus the kept count. */
    data class RoiSample(val r: Float, val g: Float, val b: Float, val pixelCount: Int)

    /**
     * Compute the forehead skin mean for one face. Returns null when the face is
     * not frontal, too small, the ROI is fully out of bounds, or too few skin
     * pixels are kept.
     */
    fun sampleForehead(img: RgbImage, width: Int, height: Int, kps: FloatArray): RoiSample? {
        if (kps.size < 10 || width <= 0 || height <= 0) return null

        val rex = kps[0].toDouble()
        val rey = kps[1].toDouble()
        val lex = kps[2].toDouble()
        val ley = kps[3].toDouble()
        val nx = kps[4].toDouble()
        val ny = kps[5].toDouble()

        val ex = (rex + lex) / 2.0
        val ey = (rey + ley) / 2.0

        val eyeDx = lex - rex
        val eyeDy = ley - rey
        val d = sqrt(eyeDx * eyeDx + eyeDy * eyeDy)
        // Min-size gate.
        if (d < MIN_INTER_OCULAR) return null

        // Right axis: unit vector along the eye line.
        val raxX = eyeDx / d
        val raxY = eyeDy / d

        // Up axis: from nose toward the eye center (points up to the forehead).
        val upX = ex - nx
        val upY = ey - ny
        val upLen = sqrt(upX * upX + upY * upY)
        if (upLen <= 0.0) return null
        val uaxX = upX / upLen
        val uaxY = upY / upLen

        // Frontal gate: nose projection onto rax relative to E.
        val noseProjR = (nx - ex) * raxX + (ny - ey) * raxY
        if (abs(noseProjR) > YAW_FRAC * d) return null

        // Oriented ROI center and half-extents.
        val cx = ex + uaxX * (FOREHEAD_UP * d)
        val cy = ey + uaxY * (FOREHEAD_UP * d)
        val halfR = HALF_R * d
        val halfU = HALF_U * d

        // Axis-aligned bounding box of the oriented rectangle. The 4 corners are
        // cx +/- raxX*halfR +/- uaxX*halfU (and likewise for y), so the AABB is
        // symmetric about the center with half-widths = sum of the absolute
        // per-axis contributions. This is exactly what the 4-corner min/max scan
        // computed, but branch-free and allocation-free.
        val halfBBx = abs(raxX * halfR) + abs(uaxX * halfU)
        val halfBBy = abs(raxY * halfR) + abs(uaxY * halfU)
        val minX = cx - halfBBx
        val maxX = cx + halfBBx
        val minY = cy - halfBBy
        val maxY = cy + halfBBy

        val x0 = max(0, floor(minX).toInt())
        val y0 = max(0, floor(minY).toInt())
        val x1 = min(width - 1, floor(maxX).toInt())
        val y1 = min(height - 1, floor(maxY).toInt())
        if (x0 > x1 || y0 > y1) return null

        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var kept = 0
        for (y in y0..y1) {
            for (x in x0..x1) {
                val dx = x - cx
                val dy = y - cy
                val projR = dx * raxX + dy * raxY
                val projU = dx * uaxX + dy * uaxY
                if (abs(projR) > halfR || abs(projU) > halfU) continue

                val rgb = img.rgbAt(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (!isSkin(r, g, b)) continue

                sumR += r
                sumG += g
                sumB += b
                kept++
            }
        }

        if (kept < MIN_SKIN_PIXELS) return null
        return RoiSample(
            (sumR / kept).toFloat(),
            (sumG / kept).toFloat(),
            (sumB / kept).toFloat(),
            kept,
        )
    }

    /** BT.601 YCbCr skin test: Cb in [77,127] and Cr in [133,173]. */
    private fun isSkin(r: Int, g: Int, b: Int): Boolean {
        val cb = 128.0 - 0.168736 * r - 0.331264 * g + 0.5 * b
        val cr = 128.0 + 0.5 * r - 0.418688 * g - 0.081312 * b
        return cb >= CB_LO && cb <= CB_HI && cr >= CR_LO && cr <= CR_HI
    }
}
