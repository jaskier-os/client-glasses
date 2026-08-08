package com.repository.glasses.listener.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dictation contract, which the regular AI chat and the RC thread must BOTH obey:
 *
 *   tap -> start dictating; the VAD ends the utterance by itself; a 3 s countdown runs; a DOUBLE
 *   tap in that window withdraws it; doing nothing sends.
 *
 * The two surfaces used to differ -- the chat started on a 500 ms HOLD and had no countdown at
 * all, the thread started on a tap and had one -- so the wearer had to learn two gestures for the
 * same act. [DictationUx] is the single description of the contract; these tests pin it, and both
 * surfaces are driven from it.
 */
class DictationUxTest {

    // --- Starting ---

    @Test
    fun aTapStartsDictationWhenNothingIsRunning() {
        assertEquals(
            DictationUx.TapAction.START,
            DictationUx.onTap(dictating = false, sendPending = false, doubleTap = false)
        )
    }

    @Test
    fun aHoldNeverStartsDictationOnEitherSurface() {
        // The hold gesture is spoken for: it opens the AI chat from anywhere. Overloading it as
        // "start dictating" is what made the two surfaces disagree in the first place.
        assertFalse(DictationUx.HOLD_STARTS_DICTATION)
    }

    // --- While dictating: the VAD owns the ending, not the finger ---

    @Test
    fun aTapWhileDictatingDoesNothingBecauseTheVadEndsTheUtterance() {
        assertEquals(
            DictationUx.TapAction.IGNORE,
            DictationUx.onTap(dictating = true, sendPending = false, doubleTap = false)
        )
        assertEquals(
            "not even a double tap stops a capture early; the VAD decides",
            DictationUx.TapAction.IGNORE,
            DictationUx.onTap(dictating = true, sendPending = false, doubleTap = true)
        )
    }

    // --- The 3 s window ---

    @Test
    fun aSingleTapInTheWindowIsIgnoredSoABrushOfTheTempleCannotDiscardTheMessage() {
        assertEquals(
            DictationUx.TapAction.IGNORE,
            DictationUx.onTap(dictating = false, sendPending = true, doubleTap = false)
        )
    }

    @Test
    fun aDoubleTapInTheWindowWithdraws() {
        assertEquals(
            DictationUx.TapAction.WITHDRAW,
            DictationUx.onTap(dictating = false, sendPending = true, doubleTap = true)
        )
    }

    @Test
    fun theWindowIsThreeSecondsOnBothSurfaces() {
        assertEquals(3000L, DictationUx.WINDOW_MS)
        assertEquals(
            "the RC window and the shared contract must be the same number, not two copies",
            RcSendWindow.WINDOW_MS, DictationUx.WINDOW_MS
        )
    }

    // --- The countdown label both surfaces render ---

    @Test
    fun theCountdownCeilsSoAStillCancellableWindowNeverReadsZero() {
        assertEquals("3s", RcCountdownLabel.of(3000L))
        assertEquals("3s", RcCountdownLabel.of(2001L))
        assertEquals("2s", RcCountdownLabel.of(2000L))
        assertEquals("1s", RcCountdownLabel.of(1L))
        assertEquals("0s", RcCountdownLabel.of(0L))
    }

    @Test
    fun theCountdownClampsNegativeOvershootRatherThanRenderingIt() {
        // The animator can be invoked a frame late, so a negative remainder is normal, not a bug.
        assertEquals("0s", RcCountdownLabel.of(-250L))
    }

    // --- The double-tap timing is one definition, shared ---

    @Test
    fun bothSurfacesUseTheSameDoubleTapWindow() {
        val w = RcSendWindow()
        assertTrue(w.arm("hello"))
        assertFalse("too early to pair", w.tapCancel(1_000L))
        assertFalse(
            "a second tap below the minimum gap is contact bounce, not a double tap",
            w.tapCancel(1_000L + RcSendWindow.DOUBLE_TAP_MIN_MS - 1)
        )
        assertTrue(w.pending)

        val w2 = RcSendWindow()
        w2.arm("hello")
        w2.tapCancel(1_000L)
        assertFalse(
            "a second tap past the maximum gap is two separate taps",
            w2.tapCancel(1_000L + RcSendWindow.DOUBLE_TAP_MAX_MS + 1)
        )
        assertTrue(w2.pending)
    }

    @Test
    fun doingNothingLeavesTheWindowPendingSoTheTimerSends() {
        val w = RcSendWindow()
        w.arm("send me")
        assertTrue(w.pending)
        assertEquals("send me", w.commit())
        assertFalse(w.pending)
        assertNull("a committed window has nothing left to send", w.commit())
    }
}
