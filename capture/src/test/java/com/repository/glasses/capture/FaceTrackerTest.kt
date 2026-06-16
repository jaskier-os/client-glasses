package com.repository.glasses.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [FaceTracker] IoU/centroid track association. Pure Kotlin,
 * no Android framework. Drives [FaceTracker.update] with plain [TrackBox] lists
 * and asserts trackingId stability, new-track creation, greedy best-match
 * (no id crossing), and age-out behavior.
 */
class FaceTrackerTest {

    /** Single TrackedBox for a one-detection frame. */
    private fun one(t: FaceTracker, b: TrackBox): TrackedBox {
        val r = t.update(listOf(b))
        assertEquals(1, r.size)
        return r[0]
    }

    @Test
    fun overlappingBoxKeepsSameId() {
        val t = FaceTracker()
        val id1 = one(t, TrackBox(0, 0, 100, 100)).trackingId
        // Slightly moved but heavily overlapping box -> same track.
        val id2 = one(t, TrackBox(10, 10, 110, 110)).trackingId
        assertEquals(id1, id2)
    }

    @Test
    fun disjointBoxGetsNewId() {
        val t = FaceTracker()
        val id1 = one(t, TrackBox(0, 0, 100, 100)).trackingId
        // Far away, zero overlap -> brand new track.
        val id2 = one(t, TrackBox(500, 500, 600, 600)).trackingId
        assertNotEquals(id1, id2)
    }

    @Test
    fun twoBoxesGetTwoDistinctStableIds() {
        val t = FaceTracker()
        val a = TrackBox(0, 0, 100, 100)
        val b = TrackBox(400, 400, 500, 500)
        val r1 = t.update(listOf(a, b))
        assertEquals(2, r1.size)
        val idA = r1[0].trackingId
        val idB = r1[1].trackingId
        assertNotEquals(idA, idB)

        // Both moved a bit but each still overlaps its own prior box.
        val r2 = t.update(listOf(TrackBox(8, 8, 108, 108), TrackBox(408, 408, 508, 508)))
        assertEquals(2, r2.size)
        assertEquals(idA, r2[0].trackingId)
        assertEquals(idB, r2[1].trackingId)
    }

    @Test
    fun greedyMatchPicksBestOverlapNoIdSwap() {
        // Two tracks established; next frame detections supplied in SWAPPED input
        // order so first-come-first-served would cross-assign. Greedy-by-IoU must
        // still map each detection to its nearest (highest-overlap) track.
        val t = FaceTracker()
        val r1 = t.update(listOf(TrackBox(0, 0, 100, 100), TrackBox(300, 0, 400, 100)))
        val idLeft = r1[0].trackingId
        val idRight = r1[1].trackingId

        // Detection order: RIGHT-ish first, LEFT-ish second.
        val r2 = t.update(
            listOf(
                TrackBox(305, 5, 405, 105), // belongs to right track
                TrackBox(5, 5, 105, 105),   // belongs to left track
            ),
        )
        assertEquals(2, r2.size)
        // r2[0] is the right detection -> right id; r2[1] left -> left id. No swap.
        assertEquals(idRight, r2[0].trackingId)
        assertEquals(idLeft, r2[1].trackingId)
    }

    @Test
    fun trackExpiresAfterMaxAgeFrames() {
        val t = FaceTracker(maxAgeFrames = 3)
        val box = TrackBox(0, 0, 100, 100)
        val id1 = one(t, box).trackingId
        // Feed empty frames beyond the age limit (4 misses > maxAge 3).
        repeat(4) { assertTrue(t.update(emptyList()).isEmpty()) }
        // Same box reappears -> the old track is gone -> NEW id.
        val id2 = one(t, box).trackingId
        assertNotEquals(id1, id2)
    }

    @Test
    fun trackSurvivesWithinMaxAgeFrames() {
        val t = FaceTracker(maxAgeFrames = 3)
        val box = TrackBox(0, 0, 100, 100)
        val id1 = one(t, box).trackingId
        // 3 misses == maxAge, still alive (dropped only when age EXCEEDS maxAge).
        repeat(3) { t.update(emptyList()) }
        val id2 = one(t, box).trackingId
        assertEquals(id1, id2)
    }

    @Test
    fun idsAreMonotonicAndNeverReused() {
        val t = FaceTracker(maxAgeFrames = 0)
        val id1 = one(t, TrackBox(0, 0, 100, 100)).trackingId
        // maxAge 0: one miss drops it.
        t.update(emptyList())
        val id2 = one(t, TrackBox(0, 0, 100, 100)).trackingId
        val id3 = one(t, TrackBox(500, 500, 600, 600)).trackingId
        // Strictly increasing, never reused.
        assertTrue(id2 > id1)
        assertTrue(id3 > id2)
    }

    @Test
    fun emptyFrameDoesNotCrashAndReturnsEmpty() {
        val t = FaceTracker()
        assertTrue(t.update(emptyList()).isEmpty())
        // Establish a track, then an empty frame still returns empty for that frame.
        one(t, TrackBox(0, 0, 100, 100))
        assertTrue(t.update(emptyList()).isEmpty())
    }
}
