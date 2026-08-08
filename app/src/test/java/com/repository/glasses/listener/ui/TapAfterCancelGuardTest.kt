package com.repository.glasses.listener.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The measured defect this guards, taken from the device log verbatim:
 *
 * ```
 * 21:21:21.791 RC: BACK cancels the pending voice send
 * 21:21:21.802 RC: capture cancelled (BACK)
 * 21:21:21.827 RC: tap -> start dictation
 * 21:21:21.852 RC: capture started
 * ```
 *
 * 25 ms. The wearer double-tapped to throw a dictation away; the FIRST half cancelled it and the
 * SECOND half started a new one, so on the waveguide it blinked off and came straight back to
 * "Listening...". The two halves are not even the same keycode -- the PSoC firmware classifies the
 * double tap itself and sends KEYCODE_BACK, while the touchpad daemon separately emits a NUMPAD_2
 * for the finger lifting off. The cancel runs on the first, the restart on the second.
 *
 * So the state the tap is read against (idle) is CORRECT by the time it arrives, and no amount of
 * looking at `dictating`/`pending` can distinguish this tap from a deliberate fresh one. The only
 * thing that separates them is that a cancel happened a double-tap-gap ago, which is what this
 * remembers.
 */
class TapAfterCancelGuardTest {

    @Test
    fun theTailOfTheDoubleTapThatCancelledDoesNotStartANewDictation() {
        val g = TapAfterCancelGuard()
        g.onCancel(21_791L)
        assertTrue(
            "the NUMPAD_2 25 ms after the cancel is the finger of that same double tap",
            g.swallows(21_827L)
        )
    }

    @Test
    fun aTapWithNoCancelBehindItIsNeverSwallowed() {
        val g = TapAfterCancelGuard()
        assertFalse("nothing was cancelled; this is a plain first tap", g.swallows(1_000L))
    }

    @Test
    fun aTapPastTheDoubleTapWindowIsADeliberateNewDictation() {
        val g = TapAfterCancelGuard()
        g.onCancel(1_000L)
        assertFalse(
            "one millisecond past the window the finger cannot belong to the cancel any more",
            g.swallows(1_000L + RcSendWindow.DOUBLE_TAP_MAX_MS + 1)
        )
    }

    @Test
    fun theBoundaryItselfStillBelongsToTheCancel() {
        val g = TapAfterCancelGuard()
        g.onCancel(1_000L)
        assertTrue(g.swallows(1_000L + RcSendWindow.DOUBLE_TAP_MAX_MS))
    }

    @Test
    fun exactlyOneTapIsSwallowedSoTheWearerCanImmediatelyRedictate() {
        // The gesture has ONE trailing release. Swallowing the whole window would eat a genuine
        // restart made by someone who cancelled because they wanted to say it again -- the single
        // most likely next action, and the one they would find broken.
        val g = TapAfterCancelGuard()
        g.onCancel(1_000L)
        assertTrue(g.swallows(1_050L))
        assertFalse("the second tap after a cancel is the wearer starting over", g.swallows(1_100L))
    }

    @Test
    fun theGuardRearmsForEveryCancelRatherThanOnlyTheFirst() {
        // The log shows the defect repeating identically at 21:21:22.979, one second later. A guard
        // that only ever fired once would have fixed the first occurrence and left the rest.
        val g = TapAfterCancelGuard()
        g.onCancel(1_000L)
        assertTrue(g.swallows(1_050L))
        g.onCancel(2_979L)
        assertTrue(g.swallows(3_015L))
    }

    @Test
    fun anExpiredArmingDoesNotLingerToEatAMuchLaterTap() {
        val g = TapAfterCancelGuard()
        g.onCancel(1_000L)
        assertFalse(g.swallows(9_000L))
        // Already expired above; a tap right after must still be free.
        assertFalse("the expiry must clear the arming, not merely fail to match", g.swallows(9_010L))
    }

    @Test
    fun theWindowIsTheSameDoubleTapGapTheRestOfTheAppUses() {
        // Not a new invented number. The finger and the pad are the same ones the notification
        // reply and the screen-off gesture are timed against.
        assertTrue(TapAfterCancelGuard().let { it.onCancel(0L); it.swallows(400L) })
        assertFalse(TapAfterCancelGuard().let { it.onCancel(0L); it.swallows(401L) })
    }
}
