package com.repository.glasses.listener.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The invariant this suite exists for: NO path out of an RC dictation may leave the phone-side
 * voice session open.
 *
 * The live defect was exactly this. `rcCommitSend` was the one exit of three that never broadcast
 * ACTION_RC_VOICE_STOP, so after a send the microphone kept running, the VAD kept chewing room
 * noise into empty utterances, and the recording UI never wore off. Three hand-written copies of
 * the same teardown is how one of them came to be missed, so the teardown now lives in ONE place
 * and these tests enumerate every exit rather than the three someone happened to remember.
 */
class RcVoiceLifecycleTest {

    private class Recorder {
        val stops = ArrayList<String>()
        val lifecycle = RcVoiceLifecycle { reason -> stops.add(reason) }
    }

    // --- The enumerated-exit invariant ---

    /**
     * Drives EVERY exit in [RcVoiceLifecycle.Exit] from a live capture and asserts the voice
     * session was closed. Adding an exit that forgets to close fails here without anyone having to
     * remember to add a test for it -- which is the whole point, given the defect was a forgotten
     * call site.
     */
    @Test
    fun everyExitFromALiveCaptureStopsTheVoiceSession() {
        for (exit in RcVoiceLifecycle.Exit.values()) {
            val r = Recorder()
            r.lifecycle.start()
            assertTrue("$exit: precondition, session must be open", r.lifecycle.voiceSessionOpen)
            driveTo(r.lifecycle, exit)
            assertFalse(
                "$exit left the phone-side voice session open (the microphone would keep running)",
                r.lifecycle.voiceSessionOpen
            )
            assertEquals(
                "$exit must broadcast exactly one stop",
                1, r.stops.size
            )
            assertEquals("$exit must name itself in the stop reason", exit.name, r.stops[0])
        }
    }

    /**
     * The full happy path -- dictate, transcript arrives, the 3 s window elapses, the message is
     * sent -- must stop the microphone exactly ONCE, and must have stopped it by the time the send
     * leaves. This is the live scenario verbatim.
     */
    @Test
    fun theSendPathStopsTheMicrophoneAndDoesNotStopItTwice() {
        val r = Recorder()
        r.lifecycle.start()
        assertTrue(r.lifecycle.acceptTranscript())
        assertTrue(r.lifecycle.onFinalTranscript("hello there", RcVoiceLifecycle.Exit.FINAL_TRANSCRIPT))
        assertFalse("the mic must be off the moment the final lands", r.lifecycle.voiceSessionOpen)
        assertEquals(1, r.stops.size)

        assertEquals("hello there", r.lifecycle.commit(RcVoiceLifecycle.Exit.SEND_COMMIT))
        assertEquals("the send must not emit a second, redundant stop", 1, r.stops.size)
        assertFalse(r.lifecycle.busy)
    }

    /**
     * A send reached WITHOUT a preceding final (a stale posted runnable, the path the live code
     * could take) must still close the session. Otherwise the exact leak returns by another door.
     */
    @Test
    fun aSendWithoutAPrecedingFinalStillStopsTheMicrophone() {
        val r = Recorder()
        r.lifecycle.start()
        // No onFinalTranscript: the window is armed directly, as a resumed/stale path would.
        r.lifecycle.armForTest("stale text")
        r.lifecycle.commit(RcVoiceLifecycle.Exit.SEND_COMMIT)
        assertFalse(r.lifecycle.voiceSessionOpen)
        assertEquals(listOf(RcVoiceLifecycle.Exit.SEND_COMMIT.name), r.stops)
    }

    // --- Idempotence: a stop must never be broadcast for a session that is not open ---

    @Test
    fun exitsWithNoOpenSessionBroadcastNothing() {
        for (exit in RcVoiceLifecycle.Exit.values()) {
            val r = Recorder()
            driveTo(r.lifecycle, exit)
            assertEquals("$exit broadcast a stop with no session open", 0, r.stops.size)
        }
    }

    @Test
    fun repeatedCancelsBroadcastOneStop() {
        val r = Recorder()
        r.lifecycle.start()
        r.lifecycle.cancelCapture(RcVoiceLifecycle.Exit.CANCEL)
        r.lifecycle.cancelCapture(RcVoiceLifecycle.Exit.CANCEL)
        r.lifecycle.cancelCapture(RcVoiceLifecycle.Exit.CANCEL)
        assertEquals(1, r.stops.size)
    }

    // --- Behaviour preserved from the pieces this class now owns ---

    @Test
    fun aBlankFinalDoesNotArmTheWindowButStillStopsTheMicrophone() {
        val r = Recorder()
        r.lifecycle.start()
        assertFalse(r.lifecycle.onFinalTranscript("   ", RcVoiceLifecycle.Exit.FINAL_TRANSCRIPT))
        assertFalse(r.lifecycle.pending)
        assertFalse(r.lifecycle.voiceSessionOpen)
        assertEquals(1, r.stops.size)
    }

    @Test
    fun aTranscriptFromAnAbandonedCaptureIsRefused() {
        val r = Recorder()
        r.lifecycle.start()
        r.lifecycle.cancelCapture(RcVoiceLifecycle.Exit.CANCEL)
        assertFalse("a cancelled capture's transcript must never be adopted",
            r.lifecycle.acceptTranscript())
    }

    @Test
    fun restartingAfterAnExitReopensTheSession() {
        val r = Recorder()
        r.lifecycle.start()
        r.lifecycle.cancelCapture(RcVoiceLifecycle.Exit.CANCEL)
        r.lifecycle.start()
        assertTrue(r.lifecycle.voiceSessionOpen)
        assertTrue(r.lifecycle.active)
        r.lifecycle.cancelCapture(RcVoiceLifecycle.Exit.THREAD_CLOSED)
        assertEquals(listOf("CANCEL", "THREAD_CLOSED"), r.stops)
    }

    @Test
    fun busyCoversBothTheCaptureAndTheSendWindow() {
        val r = Recorder()
        assertFalse(r.lifecycle.busy)
        r.lifecycle.start()
        assertTrue("a running capture is busy", r.lifecycle.busy)
        r.lifecycle.acceptTranscript()
        r.lifecycle.onFinalTranscript("words", RcVoiceLifecycle.Exit.FINAL_TRANSCRIPT)
        assertFalse(r.lifecycle.active)
        assertTrue("a pending send is busy too", r.lifecycle.busy)
        r.lifecycle.commit(RcVoiceLifecycle.Exit.SEND_COMMIT)
        assertFalse(r.lifecycle.busy)
    }

    @Test
    fun aDoubleTapInTheWindowWithdrawsTheSendAndStopsTheMicrophone() {
        val r = Recorder()
        r.lifecycle.start()
        r.lifecycle.acceptTranscript()
        r.lifecycle.onFinalTranscript("words", RcVoiceLifecycle.Exit.FINAL_TRANSCRIPT)
        r.stops.clear()
        assertFalse("a single tap must not withdraw", r.lifecycle.tapCancel(1_000L))
        assertTrue(r.lifecycle.pending)
        assertTrue("the second tap withdraws", r.lifecycle.tapCancel(1_200L))
        assertFalse(r.lifecycle.pending)
        // The session was already closed by the final; withdrawing must not emit a second stop.
        assertEquals(0, r.stops.size)
    }

    // --- Handing the microphone to another feature ---

    /**
     * A notification reply or a Telegram voice message takes the microphone over on the SAME tag
     * and the SAME channels. Our watchdog must NOT broadcast a stop then: it would tear down their
     * live capture and their transcript would never arrive.
     */
    @Test
    fun forgettingACaptureForAnotherOwnerBroadcastsNoStop() {
        val r = Recorder()
        r.lifecycle.start()
        r.lifecycle.forgetCaptureWithoutStopping()
        assertEquals(
            "stopping here would tear down the feature that now owns the microphone",
            0, r.stops.size
        )
        assertFalse(r.lifecycle.active)
        assertFalse(r.lifecycle.pending)
        assertFalse(
            "the session must be considered gone, so a LATER exit cannot stop it either",
            r.lifecycle.voiceSessionOpen
        )
    }

    @Test
    fun aLaterExitAfterForgettingStillBroadcastsNothing() {
        val r = Recorder()
        r.lifecycle.start()
        r.lifecycle.forgetCaptureWithoutStopping()
        for (exit in RcVoiceLifecycle.Exit.values()) driveTo(r.lifecycle, exit)
        assertEquals(
            "no exit may resurrect a stop for a session handed to another owner",
            0, r.stops.size
        )
    }

    /**
     * Handing the microphone over MUST owe a discard, even though it costs a spurious drop.
     *
     * The new owner's final carries the same wire id as ours and is routed by FOCUS, not by owner.
     * If the wearer leaves the reply and returns to the thread before it lands, an unpaid handover
     * means it is delivered into the running RC capture and adopted -- the notification reply's
     * words sent to a coding agent that will act on them, with no way to take them back.
     *
     * The debt makes that impossible, at the price of dropping one dictation in the case where the
     * foreign transcript never arrives. A dictation the wearer repeats is the recoverable failure;
     * a message they never addressed to the agent being executed by it is not.
     */
    @Test
    fun aForeignTranscriptCannotBeAdoptedByTheNextRcCapture() {
        val r = Recorder()
        r.lifecycle.start()
        // Another feature takes the microphone; our capture is handed over.
        r.lifecycle.forgetCaptureWithoutStopping()
        // The wearer returns to the thread and starts a new dictation...
        r.lifecycle.start()
        // ...and the OTHER feature's final lands first.
        assertFalse(
            "the foreign transcript was adopted by this capture; the other feature's words " +
                "would be sent to the coding agent, which would act on them",
            r.lifecycle.acceptTranscript()
        )
        // The wearer's own words, arriving next, are still theirs to send.
        assertTrue(
            "the debt must be paid exactly once, not latch and swallow every later dictation",
            r.lifecycle.acceptTranscript()
        )
    }

    /** A genuine abandonment still owes its discard -- the distinction must not be flattened. */
    @Test
    fun aGenuineCancelStillOwesItsDiscard() {
        val r = Recorder()
        r.lifecycle.start()
        r.lifecycle.cancelCapture(RcVoiceLifecycle.Exit.CANCEL)
        r.lifecycle.start()
        assertFalse(
            "the abandoned capture's late transcript must still be refused, or words the wearer " +
                "cancelled would be sent as the new capture's message",
            r.lifecycle.acceptTranscript()
        )
    }

    @Test
    fun aCaptureStartedAfterForgettingIsANormalSessionAgain() {
        val r = Recorder()
        r.lifecycle.start()
        r.lifecycle.forgetCaptureWithoutStopping()
        r.lifecycle.start()
        assertTrue(r.lifecycle.voiceSessionOpen)
        r.lifecycle.cancelCapture(RcVoiceLifecycle.Exit.CANCEL)
        assertEquals(listOf("CANCEL"), r.stops)
    }

    /**
     * Every exit, driven the way MainActivity drives it. Kept exhaustive with a `when` over the
     * enum (no `else`), so a new exit will not compile until it is driven here.
     */
    private fun driveTo(l: RcVoiceLifecycle, exit: RcVoiceLifecycle.Exit) {
        when (exit) {
            RcVoiceLifecycle.Exit.CANCEL -> l.cancelCapture(exit)
            RcVoiceLifecycle.Exit.WATCHDOG -> l.cancelCapture(exit)
            RcVoiceLifecycle.Exit.THREAD_CLOSED -> l.cancelCapture(exit)
            RcVoiceLifecycle.Exit.ABORTED -> l.cancelCapture(exit)
            RcVoiceLifecycle.Exit.BACK -> l.cancelCapture(exit)
            RcVoiceLifecycle.Exit.FINAL_TRANSCRIPT -> l.onFinalTranscript("something", exit)
            RcVoiceLifecycle.Exit.SEND_COMMIT -> l.commit(exit)
            RcVoiceLifecycle.Exit.SEND_WITHDRAWN -> l.cancelSendWindow(exit)
        }
    }
}
