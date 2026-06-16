package com.repository.glasses.capture

import kotlin.math.max
import kotlin.math.min

/**
 * A detection input box in pixel coordinates. Half-open convention: the box
 * covers x in [x0, x1) and y in [y0, y1), so width = x1 - x0, height = y1 - y0.
 * Kept independent of the SCRFD FaceDet type so [FaceTracker] stays decoupled
 * and unit-testable with plain ints.
 */
data class TrackBox(val x0: Int, val y0: Int, val x1: Int, val y1: Int)

/** Result of associating one frame's detection: its stable id plus the box. */
data class TrackedBox(val trackingId: Long, val box: TrackBox)

/**
 * Assigns each per-frame detection box a STABLE tracking id across consecutive
 * camera frames, so a downstream consumer (e.g. an rPPG per-face color buffer)
 * can accumulate a multi-second window keyed by id.
 *
 * Association is greedy IoU matching with a centroid-distance tiebreak:
 *  - Every [update] call ages all existing tracks by one frame.
 *  - IoU is computed between each detection and each active track's last box.
 *  - The highest-IoU pair at or above [iouThreshold] is matched (detection adopts the
 *    track's id, the track's box is updated and its age reset); both are removed
 *    from consideration and the step repeats. On an exact IoU tie the pair with
 *    the smaller centroid distance wins, which keeps two equally-overlapping
 *    tracks from cross-assigning.
 *  - Unmatched detections start NEW tracks with the next monotonic id.
 *  - A track not matched in a frame ages; it is DROPPED once its age (frames
 *    since last seen) EXCEEDS [maxAgeFrames]. So maxAgeFrames misses are
 *    survivable, the (maxAgeFrames + 1)-th miss expires the track.
 *
 * ids start at 1 and only ever increment; an expired id is never reused.
 *
 * Pure/dependency-free (kotlin.math only). No Android, no velocity/Kalman
 * prediction -- IoU greedy association plus age-out is sufficient at ~15fps.
 * Not thread-safe; call [update] from a single frame-processing thread.
 *
 * @param iouThreshold minimum intersection-over-union for a detection to be
 *   considered the same face as an existing track (0..1). Default 0.3.
 * @param maxAgeFrames how many consecutive missed frames a track tolerates
 *   before it is dropped. Default 10 (~0.7s at 15fps).
 */
class FaceTracker(
    val iouThreshold: Float = 0.3f,
    val maxAgeFrames: Int = 10,
) {
    private class Track(var id: Long, var box: TrackBox, var age: Int)

    private val tracks = ArrayList<Track>()
    private var nextId = 1L

    /**
     * Associate one frame's [detections] with existing tracks. Returns one
     * [TrackedBox] per input detection, in input order.
     */
    fun update(detections: List<TrackBox>): List<TrackedBox> {
        // 1. Age every existing track by one frame.
        for (track in tracks) track.age++

        val assignedId = arrayOfNulls<Long>(detections.size)
        val detMatched = BooleanArray(detections.size)
        val trackMatched = BooleanArray(tracks.size)

        // 2 + 3. Greedy: repeatedly take the best (det, track) pair at or above threshold.
        while (true) {
            var bestDet = -1
            var bestTrack = -1
            var bestIou = iouThreshold
            var bestDist = Double.MAX_VALUE
            for (d in detections.indices) {
                if (detMatched[d]) continue
                for (tIdx in tracks.indices) {
                    if (trackMatched[tIdx]) continue
                    val iou = iou(detections[d], tracks[tIdx].box)
                    if (iou < bestIou) continue
                    val dist = centroidDist(detections[d], tracks[tIdx].box)
                    // Strictly better IoU, or equal IoU with a closer centroid.
                    if (iou > bestIou || dist < bestDist) {
                        bestIou = iou
                        bestDist = dist
                        bestDet = d
                        bestTrack = tIdx
                    }
                }
            }
            if (bestDet < 0) break
            val track = tracks[bestTrack]
            track.box = detections[bestDet]
            track.age = 0
            assignedId[bestDet] = track.id
            detMatched[bestDet] = true
            trackMatched[bestTrack] = true
        }

        // 4. Unmatched detections -> new tracks.
        for (d in detections.indices) {
            if (detMatched[d]) continue
            val id = nextId++
            tracks.add(Track(id, detections[d], 0))
            assignedId[d] = id
        }

        // 5. Drop tracks whose age exceeds the limit.
        tracks.removeAll { it.age > maxAgeFrames }

        // 6. Result for this frame's detections, in input order.
        return detections.mapIndexed { i, box -> TrackedBox(assignedId[i]!!, box) }
    }

    /** Intersection-over-union of two half-open boxes; 0 if they do not overlap. */
    private fun iou(a: TrackBox, b: TrackBox): Float {
        val ix0 = max(a.x0, b.x0)
        val iy0 = max(a.y0, b.y0)
        val ix1 = min(a.x1, b.x1)
        val iy1 = min(a.y1, b.y1)
        val iw = ix1 - ix0
        val ih = iy1 - iy0
        if (iw <= 0 || ih <= 0) return 0f
        val inter = iw.toLong() * ih.toLong()
        val areaA = area(a)
        val areaB = area(b)
        val union = areaA + areaB - inter
        if (union <= 0) return 0f
        return (inter.toDouble() / union.toDouble()).toFloat()
    }

    private fun area(b: TrackBox): Long {
        val w = (b.x1 - b.x0).toLong()
        val h = (b.y1 - b.y0).toLong()
        if (w <= 0 || h <= 0) return 0L
        return w * h
    }

    /** Euclidean distance between box centroids (squared, for tiebreak). */
    private fun centroidDist(a: TrackBox, b: TrackBox): Double {
        val acx = (a.x0 + a.x1) / 2.0
        val acy = (a.y0 + a.y1) / 2.0
        val bcx = (b.x0 + b.x1) / 2.0
        val bcy = (b.y0 + b.y1) / 2.0
        val dx = acx - bcx
        val dy = acy - bcy
        return dx * dx + dy * dy
    }
}
