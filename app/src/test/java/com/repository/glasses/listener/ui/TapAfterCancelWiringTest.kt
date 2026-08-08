package com.repository.glasses.listener.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * That MainActivity actually CONSULTS [TapAfterCancelGuard] on the start branch, and actually ARMS
 * it on every gesture cancel.
 *
 * The guard passing its own unit tests proves nothing about the live defect if the key path never
 * calls it -- and "the logic was right but nothing invoked it" is the failure mode this project has
 * shipped before. MainActivity cannot be instantiated in a JVM test, so the call sites are asserted
 * from the source, each within a bounded anchor so a rename fails loudly instead of widening the
 * search to a 10 000-line file where every identifier appears somewhere.
 */
class TapAfterCancelWiringTest {

    private val main: String by lazy {
        val f = File("src/main/java/com/repository/glasses/listener/MainActivity.kt")
        assertTrue("missing MainActivity.kt: ${f.absolutePath}", f.isFile)
        f.readText()
    }

    /** Text between two markers, failing loudly if either has been renamed away. */
    private fun between(from: String, to: String): String {
        val i = main.indexOf(from)
        assertTrue("anchor '$from' is gone; this test would pass vacuously", i >= 0)
        val j = main.indexOf(to, i)
        assertTrue("anchor '$to' is gone after '$from'; this test would pass vacuously", j > i)
        return main.substring(i, j)
    }

    /** Real code, not a comment that merely mentions the name. */
    private fun calls(src: String, needle: String): Boolean = src.lineSequence()
        .filter { it.contains(needle) }
        .any { it.trimStart().let { l -> !l.startsWith("//") && !l.startsWith("*") } }

    @Test
    fun aTapNeverStartsACaptureInAThread() {
        // Dictation starts on the HOLD, matching the AI chat. That is what makes the trailing
        // release of a cancelling double tap harmless: the guard this file was written for is
        // retired, and the property it protected is now structural. If a tap ever starts a capture
        // again, the 25 ms restart measured on the device comes back with it.
        val rc = main.substringAfter("FocusState.RC_THREAD_FOCUSED -> {", "")
            .substringBefore("FocusState.TAB_NAV -> {", "")
        assertTrue("could not locate the RC thread key branch", rc.isNotBlank())
        assertFalse(
            "a tap starts a capture in the RC thread; dictation must start on the hold so the " +
                "release trailing a cancelling double tap cannot restart it: $rc",
            rc.contains("rcStartCapture()")
        )
    }

    @Test
    fun theHoldStartsTheCapture() {
        val hold = main.substringAfter(
            "if (keyCode == KeyEvent.KEYCODE_NUMPAD_3 && focusState == FocusState.RC_THREAD_FOCUSED) {", ""
        ).substringBefore("return true", "")
        assertTrue("the hold does not start a capture in the RC thread", hold.contains("rcStartCapture()"))
    }

    @Test
    fun theBackPathArmsTheGuard() {
        // BACK is what the PSoC firmware emits FOR the double tap, so this is the path that
        // actually fired in the measured log.
        val back = between("RC: BACK cancels the pending voice send", "return true")
        assertTrue(
            "the BACK cancel does not arm the guard, so the NUMPAD_2 that follows the same " +
                "gesture will be read as a fresh start (measured at 25 ms)",
            calls(back, "rcCancelByGesture(")
        )
    }

    @Test
    fun theWithdrawPathArmsTheGuardToo() {
        val withdraw = between("DictationUx.TapAction.WITHDRAW -> {", "renderRcThreadChrome()")
        assertTrue(
            "a tap-driven withdrawal must arm the guard as well; the pad can deliver the pair " +
                "as two NUMPAD_2s rather than as BACK plus a release",
            calls(withdraw, "rcTapAfterCancel.onCancel(")
        )
    }

    @Test
    fun everyGestureCancelGoesThroughTheOneArmingHelper() {
        val helper = between("private fun rcCancelByGesture(", "\n    private fun ")
        assertTrue("rcCancelByGesture must arm the guard", calls(helper, "rcTapAfterCancel.onCancel("))
        assertTrue("rcCancelByGesture must perform the cancel", calls(helper, "rcCancelCapture("))
        assertTrue(
            "rcCancelByGesture must re-render, or the thread keeps the torn-down chrome",
            calls(helper, "renderRcThreadChrome()")
        )
    }

    @Test
    fun theGuardIsArmedBeforeTheCancelSoLoggingCannotMoveTheDeadline() {
        val helper = between("private fun rcCancelByGesture(", "\n    private fun ")
        val armed = helper.indexOf("rcTapAfterCancel.onCancel(")
        val cancelled = helper.indexOf("rcCancelCapture(")
        assertTrue("both calls must be present", armed >= 0 && cancelled >= 0)
        assertTrue(
            "arm first: the window is timed against the gesture, not against how long the " +
                "cancel's broadcasts and logging happen to take",
            armed < cancelled
        )
    }

    @Test
    fun aSingleTapDuringACaptureStillDoesNothingAtAll() {
        // Explicitly NOT changed by this fix, and the property the wearer cares about most: a
        // brush of the temple must neither discard a sentence nor restart one. The shared decision
        // table is the only thing that may decide it.
        assertTrue(
            "the RC thread must decide taps through DictationUx.onTap, not with its own logic",
            calls(between("KeyEvent.KEYCODE_NUMPAD_2,", "DictationUx.TapAction.WITHDRAW"),
                "DictationUx.onTap(")
        )
        assertTrue(
            "an ignored tap must still be logged, or a swallowed dictation is invisible",
            calls(main, "RC: tap ignored")
        )
    }
}
