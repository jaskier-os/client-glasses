package com.repository.glasses.listener.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays step 13b of `RcThreadInstrumentedTest` against the real [RcCapture] / [RcSendWindow],
 * driven through a model of MainActivity's key ladder.
 *
 * Why this exists next to an instrumented test that asserts the same property: the on-device run
 * needs the glasses UNFOLDED and WORN (the fold gate swallows NUMPAD_3, the wear gate the mic), so
 * on a folded pair the whole voice section is skipped by `assumeTrue` and 13b proves nothing that
 * day. This runs the identical SEQUENCE with no hardware, so the discard property is guarded on
 * every build rather than only on the days someone is wearing the device.
 *
 * What this file DOES and DOES NOT cover, stated plainly so nobody reads it as more than it is.
 * The model transcribes MainActivity's ladder; it cannot run it (the activity needs a device).
 *
 * COVERED -- the discard POLICY, because every decision below delegates to the real RcCapture and
 * RcSendWindow rather than reimplementing them. Breaking RcVoiceGate.kt fails these tests, twice
 * confirmed by mutation: `acceptTranscript` ignoring owedDiscards fails all four; `cancel()`
 * incrementing owedDiscards on an inactive capture fails the last one.
 *
 * NOT COVERED, and left to the instrumented `RcThreadInstrumentedTest` step 13b:
 *  - the WIRING. Deleting the `acceptTranscript()` gate from MainActivity's userTextReceiver, or
 *    the `rcCancelCapture()` from its BACK arm, leaves this file green.
 *  - the ROUTING gate on that receiver -- `focusState == RC_THREAD_FOCUSED && requestId ==
 *    "tg_voice"`. This model has no focus state and no request ids.
 *  - the COMMIT path. `windowElapses()` stands in for the posted rcCommitSend Runnable; it does
 *    not prove MainActivity ever posts it, cancels it, or emits the CH_RC_SEND_REQ broadcast.
 *  - whether BACK leaves the thread. See [Thread.back].
 *
 * The instrumented test is the only thing that covers those, and it is skipped by `assumeTrue`
 * when the glasses are off-head -- which is exactly why the policy half is guarded here too.
 */
class RcThreadStaleTranscriptSequenceTest {

    /** MainActivity's RC voice state, as far as the 13b sequence can observe it. */
    private class Thread {
        val capture = RcCapture()
        val window = RcSendWindow()
        var voiceBarVisible = false
        var countdownVisible = false
        val wire = mutableListOf<String>()

        /** NUMPAD_3: rcStartCapture, refused while the gate reads Busy. */
        fun hold() {
            if (capture.active || window.pending) return
            capture.start()
            voiceBarVisible = true
        }

        /**
         * BACK inside RC_THREAD_FOCUSED, restricted to the arm this file is about: undoing a live
         * capture or a pending send.
         *
         * The "else leave the thread" arm is deliberately NOT modelled. Whether BACK closes the
         * thread is decided by MainActivity's focus ladder, which this file cannot run, so a
         * `threadOpen` flag here would only ever assert that this class's own `if` is written the
         * way this class wrote it. That belongs to the instrumented test, which presses the real
         * key at the real activity -- and does assert it, twice, in 13 and 13b.
         */
        fun back() {
            require(window.pending || capture.active) {
                "this model only covers the undo arm of BACK; nothing was live to undo"
            }
            capture.cancel()
            window.cancel()
            countdownVisible = false
            voiceBarVisible = false
        }

        /** userTextReceiver -> rcOnFinalTranscript, gated by the capture's identity. */
        fun finalTranscript(text: String) {
            if (!capture.acceptTranscript()) return
            if (window.arm(text)) {
                countdownVisible = true
                voiceBarVisible = true
            } else {
                voiceBarVisible = false
            }
        }

        /** The 3 s window elapsing -> rcCommitSend. */
        fun windowElapses() {
            window.commit()?.let { wire.add("SEND|$it") }
            countdownVisible = false
            voiceBarVisible = false
        }

        fun sends() = wire.filter { it.startsWith("SEND|") }
    }

    /** Drives the sequence up to the moment the stale transcript has just been delivered. */
    private fun throughStaleTranscript(): Thread {
        val t = Thread()

        // 13: a capture, its own transcript, the window, and BACK undoing it.
        t.hold()
        t.finalTranscript("yes deploy it and watch the logcat")
        assertTrue("13 must open the send window", t.countdownVisible)
        t.back()

        // 13b: abandon a capture that is ACTUALLY RUNNING. This is the whole point -- cancel()
        // early-returns on an inactive capture, so a BACK pressed with nothing live owes no
        // discard and the "stale" transcript below would be adopted entirely legitimately.
        t.hold()
        assertTrue("the capture must be live before it can be abandoned", t.capture.active)
        t.back()
        assertFalse("the abandoned capture must be over", t.capture.active)

        // The next capture. The phone now delivers the ABANDONED one's transcript into it.
        t.hold()
        assertTrue("the fresh capture must be running for adoption to be possible at all",
            t.capture.active)
        t.finalTranscript("this belonged to the cancelled capture")
        return t
    }

    @Test
    fun staleTranscriptDoesNotArmTheNextCapturesWindow() {
        val t = throughStaleTranscript()
        assertFalse("the abandoned utterance must not arm the new capture's window",
            t.countdownVisible)
    }

    @Test
    fun staleTranscriptLeavesTheRunningCaptureListening() {
        val t = throughStaleTranscript()
        assertTrue("dropping a stale transcript must not tear down the running capture",
            t.capture.active)
        assertTrue("nor hide its voice bar", t.voiceBarVisible)
    }

    @Test
    fun staleTranscriptNeverReachesTheWire() {
        val t = throughStaleTranscript()
        // Let a full window elapse: a late commit must not be able to hide behind the clock, which
        // is precisely how the old on-device 13b passed while actually SENDING the stale words.
        t.windowElapses()
        assertEquals("words from an abandoned capture must never reach the wire",
            emptyList<String>(), t.sends())
    }

    @Test
    fun theDiscardDoesNotDeafenTheCaptureThatSurvivedIt() {
        val t = throughStaleTranscript()
        t.finalTranscript("yes deploy it and watch the logcat")
        assertTrue("the capture that survived the discard must accept its OWN transcript",
            t.countdownVisible)
        t.windowElapses()
        assertEquals("and that one is the only thing that may be sent",
            listOf("SEND|yes deploy it and watch the logcat"), t.sends())
    }
}
