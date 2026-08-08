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

    /**
     * Does [src] actually CALL [needle], as opposed to merely mentioning it?
     *
     * Commented-out code still satisfies `contains`, so a mutation that comments a call out
     * survives a `contains` assertion -- which is how the onDestroy test came to pass against a
     * mutation that re-created the original leak. Comment lines are excluded here.
     */
    private fun calls(src: String, needle: String): Boolean = src.lineSequence()
        .filter { it.contains(needle) }
        .any { it.trimStart().let { l -> !l.startsWith("//") && !l.startsWith("*") } }

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
            .minOrNull()
        // FAIL rather than fall back to the file tail. The last function in the file has no
        // following declaration, so a silent fallback hands back everything after it -- a window
        // wide enough that `contains(name)` matches something unrelated and the test passes
        // vacuously. (That is exactly what happened to onDestroy: a mutation commenting out its
        // rcCancelCapture call, which re-creates the original leak, went undetected.)
        assertTrue(
            "no declaration follows '$signature', so its body cannot be bounded; add an explicit " +
                "end marker rather than letting the window run to the end of the file",
            end != null
        )
        val fn = after.substring(0, end!!)
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
            calls(fn, "rcVoice.busy") && calls(fn, "rcCancelCapture(")
        )
        assertTrue(
            "onStop must not tear down across a configuration change; the Activity is coming " +
                "straight back: $fn",
            calls(fn, "isChangingConfigurations")
        )
    }

    /**
     * A posted rcSendRunnable that survives onDestroy fires rcCommitSend() against dead views and
     * ships a message the wearer can neither see nor withdraw.
     */
    @Test
    fun onDestroyCancelsTheDictationAndItsPostedWork() {
        // onDestroy is the LAST function in the file, so it has no following declaration to bound
        // it. Cut at its own super call instead: without an explicit end the window would run to
        // the end of the file and every assertion below would pass vacuously.
        val fn = read("MainActivity.kt")
            .substringAfter("override fun onDestroy()", "")
            .substringBefore("super.onDestroy()", "")
        assertTrue(
            "could not bound onDestroy(); it has been renamed or its super call removed, and " +
                "this test would otherwise pass vacuously against the rest of the file",
            fn.isNotBlank() && fn.length < 8000
        )
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
            assertTrue("onDestroy does not clean up '$required': $fn", calls(fn, required))
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
            calls(fn, "FocusState.NOTIFICATION_REPLY")
        )
        assertTrue(
            "the watchdog must use forgetCaptureWithoutStopping() on the foreign-owner path, " +
                "not a plain cancel (which broadcasts a stop): $fn",
            calls(fn, "forgetCaptureWithoutStopping()")
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
            calls(hideBranch, "rcOwnsListeningChrome = false")
        )
    }

    // --- Every exit leaves the SCREEN correct, not just the broadcast ---

    /**
     * The user's complaint was visual: "recording doesn't wear off". A path that closes the
     * session but leaves the chrome lit is the same bug with a different cause, so EVERY exit must
     * take the chrome down and re-render -- including the one where another feature stole the
     * microphone, which was missed. (Found by audit round 2 on exactly that path.)
     */
    @Test
    fun everyExitPathTakesTheRecordingChromeDown() {
        val main = read("MainActivity.kt")
        val paths = mapOf(
            "private fun rcCancelCapture(" to 4000,
            "private fun rcArmCaptureWatchdog()" to 4000,
            "private fun rcOnFinalTranscript(" to 4000,
            "private fun rcCommitSend()" to 5000,
        )
        for ((sig, max) in paths) {
            val fn = body(main, sig, maxLen = max)
            assertTrue(
                "$sig does not take the listening chrome down; the status line, visualizer, " +
                    "hint and mic meter would stay lit with no capture behind them",
                calls(fn, "rcShowListeningChrome(false)")
            )
            assertTrue(
                "$sig does not hide the voice bar",
                calls(fn, "rcThreadVoiceBar.visibility = View.GONE") ||
                    // rcOnFinalTranscript hides it only on the blank-transcript branch; the
                    // non-blank branch legitimately keeps the bar up for the send window.
                    sig.contains("rcOnFinalTranscript")
            )
        }
    }

    /**
     * The watchdog's stolen-microphone branch specifically. It returns early, so it does not fall
     * through to the normal teardown below it and must do its own -- including the re-render,
     * without which the footer stays hidden from the last pass and the wearer is left with no
     * affordance and no explanation.
     */
    @Test
    fun theStolenMicrophoneBranchAlsoRerendersTheChrome() {
        val fn = body(read("MainActivity.kt"), "private fun rcArmCaptureWatchdog()")
        val branch = fn.substringAfter("forgetCaptureWithoutStopping()", "")
            .substringBefore("return@Runnable", "")
        assertTrue("could not locate the stolen-microphone branch: $fn", branch.isNotBlank())
        assertTrue(
            "the stolen-microphone branch does not take the chrome down: $branch",
            calls(branch, "rcShowListeningChrome(false)")
        )
        assertTrue(
            "the stolen-microphone branch does not re-render, so the footer stays hidden from " +
                "the previous pass and the wearer gets no affordance at all: $branch",
            calls(branch, "renderRcThreadChrome()")
        )
    }

    /**
     * rcSendInFlight makes RcVoiceGate return Busy. Its result frame is matched against the OPEN
     * session and dropped once that is null, so a thread closed inside the send's round trip
     * latches Busy forever and refuses hold-to-speak in EVERY thread until the app restarts.
     */
    @Test
    fun closingAThreadReleasesTheSendInFlightGuard() {
        val fn = body(read("MainActivity.kt"), "private fun closeRcThread(")
        assertTrue(
            "closeRcThread does not clear rcSendInFlight; nothing else can, because the result " +
                "frame is dropped once rcOpenSession is null, so the voice gate latches Busy " +
                "forever: $fn",
            calls(fn, "rcSendInFlight = false")
        )
    }

    /**
     * The chat's own state broadcasts must not strip chrome an RC dictation is driving. These are
     * SHARED views now; the chat's turn ending says nothing about a coding-agent dictation that is
     * still running.
     */
    @Test
    fun theChatStateReceiverDoesNotStripAnRcDictationsChrome() {
        val main = read("MainActivity.kt")
        val receiver = main.substringAfter("private val stateReceiver", "")
            .substringBefore("\n    private val ", "")
        assertTrue("could not locate stateReceiver", receiver.isNotBlank() && receiver.length < 6000)
        for (branch in listOf("\"IDLE\" ->", "\"RESPONDING\" ->")) {
            val body = receiver.substringAfter(branch, "").substringBefore("\n                    \"")
            assertTrue("could not locate the $branch branch", body.isNotBlank())
            // A teardown counts as guarded if the ownership flag is on the SAME line (a one-line
            // `if (...) x`) or the line sits inside an `if (!rcOwnsListeningChrome) {` block,
            // which shows up as deeper indentation than that `if`. Matching only on the same line
            // would report the block form as unguarded -- it did, on the first run.
            val guardIndents = body.lineSequence()
                .filter { it.contains("if (!rcOwnsListeningChrome) {") }
                .map { it.takeWhile { c -> c == ' ' }.length }
                .toList()
            for (line in body.lineSequence().filter {
                it.contains("hideAudioVisualizer()") || it.contains("hideDoubleTapHint()") ||
                    it.contains("statusArea.visibility = View.INVISIBLE")
            }) {
                val indent = line.takeWhile { it == ' ' }.length
                val guarded = line.contains("rcOwnsListeningChrome") ||
                    guardIndents.any { indent > it }
                assertTrue(
                    "$branch tears down a SHARED listening view unguarded ('${line.trim()}'); an " +
                        "RC dictation would be left as a recording bar with no status behind it",
                    guarded
                )
            }
        }
    }

    /**
     * An RC dictation puts the SERVICE in LISTENING -- that is how the thread's capture is driven.
     * Auto-focusing the chat there orphans the thread's transcript, because the final is only
     * accepted while RC_THREAD_FOCUSED.
     */
    @Test
    fun listeningDoesNotStealFocusFromTheThreadsOwnDictation() {
        val main = read("MainActivity.kt")
        val listening = main.substringAfter("\"LISTENING\" -> {", "")
            .substringBefore("\n                    \"RESPONDING\"", "")
        assertTrue("could not locate the LISTENING branch", listening.isNotBlank())
        val guard = listening.substringBefore("focusState = FocusState.CHAT_FOCUSED", "")
        assertTrue(
            "LISTENING auto-focuses the chat with no exemption for a thread's own dictation; " +
                "the RC transcript would be discarded and the voice bar would hang until the " +
                "watchdog fired: $guard",
            calls(guard, "FocusState.RC_THREAD_FOCUSED") && calls(guard, "rcVoice.busy")
        )
    }

    // --- The unification actually reaches the wearer ---

    /**
     * A touchpad tap delivers NUMPAD_2, and an early branch consumes EVERY NUMPAD_2 and returns
     * before the focus dispatch runs. Both dictation surfaces must be exempted from it or their
     * tap handler is unreachable and the shared gesture is a no-op for anyone wearing the glasses.
     * (The RC thread was exempted; the AI chat was not, so bug 4 shipped dead.)
     */
    @Test
    fun bothDictationSurfacesAreExemptFromTheNumpad2Consumer() {
        val main = read("MainActivity.kt")
        val branch = main.substringAfter("if (keyCode == KeyEvent.KEYCODE_NUMPAD_2 &&", "")
            .substringBefore("val now = SystemClock.uptimeMillis()", "")
        assertTrue("could not locate the NUMPAD_2 screen-off branch", branch.isNotBlank())
        for (surface in listOf("FocusState.RC_THREAD_FOCUSED", "FocusState.CHAT_FOCUSED")) {
            assertTrue(
                "$surface is not exempt from the NUMPAD_2 consumer, so a touchpad tap there is " +
                    "swallowed before the focus dispatch and dictation can never start: $branch",
                calls(branch, surface)
            )
        }
    }

    /**
     * The countdown bar renders "DOUBLE-TAP TO CANCEL". On the chat surface a real tap arrives as
     * NUMPAD_2, which the global cancel branch does not match, so without a handler here the hint
     * is a lie off the remote.
     */
    @Test
    fun theChatCountdownHasAWithdrawHandler() {
        val main = read("MainActivity.kt")
        val chat = main.substringAfter("FocusState.CHAT_FOCUSED -> {", "")
            .substringBefore("FocusState.LIST_FOCUSED -> {", "")
        assertTrue("could not locate the CHAT_FOCUSED key branch", chat.isNotBlank())
        assertTrue(
            "the chat draws a countdown that says DOUBLE-TAP TO CANCEL but wires no withdrawal " +
                "to a touchpad tap: $chat",
            calls(chat, "chatSendCountdown") && calls(chat, "ACTION_CANCEL_SESSION")
        )
        assertTrue(
            "a touchpad tap (NUMPAD_2) must reach the chat's tap handler, not only DPAD/ENTER",
            calls(chat, "KeyEvent.KEYCODE_NUMPAD_2")
        )
    }

    /**
     * The chat's pre-send window is owned by the PHONE, and the glasses only draw over it. A bar
     * animating our own longer window would still be a third full after the message had gone, and
     * a "cancel" made while it still showed time left would land after the send.
     */
    @Test
    fun theChatCountdownAnimatesThePhonesWindowNotTheRcOne() {
        val main = read("MainActivity.kt")
        assertTrue(
            "the chat bar must be started with CHAT_WINDOW_MS (the phone's confirm window), not " +
                "the RC window or the bar's own default",
            calls(main, "chatSendCountdown?.start(DictationUx.CHAT_WINDOW_MS)")
        )
        val ux = read("ui/DictationUx.kt")
        assertTrue("DictationUx has no CHAT_WINDOW_MS", calls(ux, "CHAT_WINDOW_MS"))
        assertTrue(
            "the two windows must be distinct constants: they are owned by different sides and " +
                "collapsing them re-introduces the mismatch",
            !ux.contains("CHAT_WINDOW_MS = RcSendWindow.WINDOW_MS") &&
                !ux.contains("CHAT_WINDOW_MS = WINDOW_MS")
        )
    }

    /**
     * A pending tap must be CANCELLED when a new one replaces it. Merely overwriting the field
     * leaves the old runnable posted, so two taps the wearer meant as one gesture apiece both fire.
     */
    @Test
    fun armingANewDeferredTapCancelsThePreviousOne() {
        val fn = body(read("MainActivity.kt"), "private fun runAfterTapWindow(")
        assertTrue(
            "runAfterTapWindow overwrites pendingTapRunnable without removing the old callback: $fn",
            fn.contains("removeCallbacks") &&
                fn.indexOf("removeCallbacks") < fn.indexOf("pendingTapRunnable = runnable")
        )
    }

    // --- The countdown cannot outlive its view ---

    @Test
    fun theCountdownBarStopsItsAnimatorOnDetach() {
        val bar = read("ui/SendCountdownBar.kt")
        assertTrue(
            "SendCountdownBar has no onDetachedFromWindow; a running ValueAnimator is held by " +
                "the global AnimationHandler and would tick against a dead hierarchy",
            calls(bar, "override fun onDetachedFromWindow()")
        )
        val fn = bar.substringAfter("override fun onDetachedFromWindow()", "")
            .substringBefore("\n    }")
        assertTrue("onDetachedFromWindow does not stop the animator: $fn", calls(fn, "stop()"))
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
            calls(fn, "mainHandler.postDelayed") && calls(fn, "rcCommitSend()")
        )
        assertTrue(
            "the bar must be driven by SendCountdownBar.start(), not a re-implemented animator: $fn",
            calls(fn, "rcSendCountdown?.start(") && !calls(fn, "ValueAnimator")
        )
    }
}
