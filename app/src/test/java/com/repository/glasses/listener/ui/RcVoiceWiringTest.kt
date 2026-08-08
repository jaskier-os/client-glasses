package com.repository.glasses.listener.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level checks on how MainActivity WIRES the dictation lifecycle.
 *
 * MainActivity cannot be instantiated in a JVM unit test, and the defects this file guards against
 * are all "a call site that should exist does not" -- exactly the shape of the original bug, where
 * one of three teardowns simply had no stop in it. Reading the source is the only way to assert
 * that from here, so each test also pins the ANCHOR it searches within: without that, a rename
 * would widen the search to the whole 10 000-line file, where every identifier appears somewhere,
 * and every assertion below would pass vacuously.
 */
class RcVoiceWiringTest {

    private val root = File("src/main/java/com/repository/glasses/listener")

    private fun read(rel: String): String {
        val f = File(root, rel)
        assertTrue("missing source file: ${f.absolutePath}", f.isFile)
        return f.readText()
    }

    /** The body of a function, bounded so a rename cannot silently widen it. */
    private fun body(src: String, signature: String, maxLen: Int = 4000): String {
        val after = src.substringAfter(signature, "")
        assertTrue(
            "could not locate '$signature'; it has been renamed and this test would otherwise " +
                "pass vacuously against unrelated code",
            after.isNotBlank()
        )
        // Cut at whichever declaration comes FIRST. substringBefore-chaining is wrong here: if the
        // next declaration is an `override fun` the `private fun` pass already truncated to the
        // one after it, and a later chained cut can then land at index 0 and return "".
        val end = listOf("\n    private fun ", "\n    override fun ", "\n    fun ", "\n    val ")
            .mapNotNull { d -> after.indexOf(d).takeIf { it >= 0 } }
            .minOrNull() ?: after.length
        val fn = after.substring(0, end)
        assertTrue("body of '$signature' looks wrong (${fn.length} chars)", fn.length in 1..maxLen)
        return fn
    }

    // --- The stop lives in ONE place ---

    /**
     * The whole point of RcVoiceLifecycle. If MainActivity broadcasts ACTION_RC_VOICE_STOP from
     * anywhere else, the hand-copied teardowns are back and so is the bug.
     */
    @Test
    fun mainActivityBroadcastsTheVoiceStopFromExactlyOnePlace() {
        val main = read("MainActivity.kt")
        // Only real broadcasts, not the comments that explain why there is only one of them.
        val occurrences = main.lineSequence()
            .filter { it.contains("ACTION_RC_VOICE_STOP") }
            .count { it.trimStart().let { l -> !l.startsWith("//") && !l.startsWith("*") } }
        assertTrue(
            "ACTION_RC_VOICE_STOP appears $occurrences times in MainActivity; it must appear " +
                "exactly once, inside the RcVoiceLifecycle callback, or the per-exit copies " +
                "that caused the original leak are back",
            occurrences == 1
        )
        // ...and that one occurrence is inside the lifecycle's stop callback.
        val callback = main.substringAfter("RcVoiceLifecycle {", "").substringBefore("\n    }")
        assertTrue(
            "the single ACTION_RC_VOICE_STOP is not inside the RcVoiceLifecycle stop callback",
            callback.contains("ACTION_RC_VOICE_STOP")
        )
    }

    /** Likewise, nothing may reach around the lifecycle to poke the raw capture/window objects. */
    @Test
    fun mainActivityOwnsNoRawCaptureOrSendWindow() {
        val main = read("MainActivity.kt")
        assertTrue(
            "MainActivity still constructs its own RcCapture; the lifecycle owns it now",
            !main.contains("RcCapture()")
        )
        assertTrue(
            "MainActivity still constructs its own RcSendWindow; the lifecycle owns it now",
            !main.contains("RcSendWindow()")
        )
    }

    // --- Every lifecycle exit ---

    /**
     * Backgrounding mid-dictation is the leak reached by a different door: the UI that would have
     * shown the transcript and offered the undo is gone, and nothing else can close the session.
     */
    @Test
    fun onStopEndsARunningDictation() {
        val fn = body(read("MainActivity.kt"), "override fun onStop()")
        assertTrue(
            "onStop does not end a running dictation; the phone-side voice session would stay " +
                "open with the UI gone: $fn",
            fn.contains("rcVoice.busy") && fn.contains("rcCancelCapture(")
        )
        assertTrue(
            "onStop must not tear down across a configuration change; the Activity is coming " +
                "straight back: $fn",
            fn.contains("isChangingConfigurations")
        )
    }

    /**
     * A posted rcSendRunnable that survives onDestroy fires rcCommitSend() against dead views and
     * ships a message the wearer can neither see nor withdraw.
     */
    @Test
    fun onDestroyCancelsTheDictationAndItsPostedWork() {
        val fn = body(read("MainActivity.kt"), "override fun onDestroy()", maxLen = 8000)
        for (required in listOf(
            "rcCancelCapture(",
            "rcCaptureWatchdog",
            "rcSendRunnable",
            "rcSendCountdown?.stop()",
            "chatSendCountdown?.stop()",
            // showAudioVisualizer registers an ACTION_AUDIO_LEVELS receiver that only
            // hideAudioVisualizer unregisters, and it is not in the bulk unregister list.
            "hideAudioVisualizer()",
        )) {
            assertTrue("onDestroy does not clean up '$required': $fn", fn.contains(required))
        }
    }

    // --- Not stealing another feature's microphone ---

    /**
     * A notification reply and a Telegram voice message use the SAME tag and the SAME channels. An
     * RC watchdog that fires while one of those owns the microphone must forget its own capture
     * silently rather than broadcasting a stop that tears down theirs.
     */
    @Test
    fun theWatchdogDoesNotStopACaptureAnotherFeatureNowOwns() {
        val fn = body(read("MainActivity.kt"), "private fun rcArmCaptureWatchdog()")
        assertTrue(
            "the watchdog has no owner check; it would tear down a notification reply's live " +
                "capture: $fn",
            fn.contains("FocusState.NOTIFICATION_REPLY")
        )
        assertTrue(
            "the watchdog must use forgetCaptureWithoutStopping() on the foreign-owner path, " +
                "not a plain cancel (which broadcasts a stop): $fn",
            fn.contains("forgetCaptureWithoutStopping()")
        )
    }

    // --- Not stealing the AI chat's shared chrome ---

    /**
     * statusArea / the visualizer / the double-tap hint are the AI CHAT's views, reused so the two
     * surfaces cannot drift. That sharing means an RC teardown must hide only what IT put up.
     */
    @Test
    fun theRcTeardownOnlyHidesChromeItOwns() {
        val fn = body(read("MainActivity.kt"), "private fun rcShowListeningChrome(")
        // The HIDE branch specifically. Asserting the flag is merely MENTIONED is vacuous: the
        // show branch sets it, so deleting the guard entirely still leaves the name in the body.
        // (Caught by mutation -- this test passed against exactly that deletion.)
        val hideBranch = fn.substringAfter("} else {", "")
        assertTrue("could not locate the hide branch of rcShowListeningChrome: $fn",
            hideBranch.isNotBlank())
        for (shared in listOf("hideAudioVisualizer()", "hideDoubleTapHint()", "statusArea")) {
            val line = hideBranch.lineSequence().first { it.contains(shared) }
            assertTrue(
                "rcShowListeningChrome hides the SHARED chat view '$shared' without checking it " +
                    "owns it; an RC exit during a live AI chat LISTENING would strip the chat's " +
                    "own chrome, with nothing to restore it until the next state broadcast",
                // Guarded means indented inside the `if (rcOwnsListeningChrome)` block.
                line.takeWhile { it == ' ' }.length > hideBranch.lineSequence()
                    .first { it.contains("if (rcOwnsListeningChrome)") }
                    .takeWhile { it == ' ' }.length
            )
        }
        assertTrue(
            "the hide branch must clear the ownership flag, or the next AI-chat LISTENING would " +
                "still be torn down by a stale RC claim: $hideBranch",
            hideBranch.contains("rcOwnsListeningChrome = false")
        )
    }

    // --- The countdown cannot outlive its view ---

    @Test
    fun theCountdownBarStopsItsAnimatorOnDetach() {
        val bar = read("ui/SendCountdownBar.kt")
        assertTrue(
            "SendCountdownBar has no onDetachedFromWindow; a running ValueAnimator is held by " +
                "the global AnimationHandler and would tick against a dead hierarchy",
            bar.contains("override fun onDetachedFromWindow()")
        )
        val fn = bar.substringAfter("override fun onDetachedFromWindow()", "")
            .substringBefore("\n    }")
        assertTrue("onDetachedFromWindow does not stop the animator: $fn", fn.contains("stop()"))
    }

    /**
     * The animation must never be what decides whether a message is sent: it can be cancelled,
     * paused by a lost window focus, or scaled to zero duration by the system animator setting.
     */
    @Test
    fun theSendIsAPostedRunnableNotAnAnimationCallback() {
        val fn = body(read("MainActivity.kt"), "private fun rcStartSendCountdown()")
        assertTrue(
            "the send must be posted on the handler, independent of the animation: $fn",
            fn.contains("mainHandler.postDelayed") && fn.contains("rcCommitSend()")
        )
        assertTrue(
            "the bar must be driven by SendCountdownBar.start(), not a re-implemented animator: $fn",
            fn.contains("rcSendCountdown?.start(") && !fn.contains("ValueAnimator")
        )
    }
}
