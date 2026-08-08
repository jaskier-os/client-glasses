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

    private class Recorder(var now: Long = 10_000L) {
        val stops = ArrayList<String>()
        val lifecycle = RcVoiceLifecycle(clock = { now }, stopVoiceSession = { stops.add(it) })

        /** Advance the clock, in seconds, between steps of a scenario. */
        fun elapse(seconds: Long) { now += seconds * 1000L }
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
        // Another feature takes the microphone; our capture is handed over. The debt is recorded
        // HERE, before the wearer has spoken a word of their notification reply.
        r.lifecycle.forgetCaptureWithoutStopping()

        // Times measured from that instant, not from the end of the reply: an ordinary spoken
        // reply, the phone's VAD silence window, and transcription. The debt must still be owed
        // when that final lands -- with the ABANDON ttl (4 s) it would have expired mid-reply.
        r.elapse(8)   // the wearer speaks their reply
        r.elapse(2)   // VAD end-of-speech + transcription

        // The wearer returns to the thread and starts a new dictation...
        r.lifecycle.start()
        // ...and the OTHER feature's final lands first.
        assertFalse(
            "the foreign transcript was adopted by this capture; the other feature's words " +
                "would be sent to the coding agent, which would act on them",
            r.lifecycle.acceptTranscript()
        )
        // The debt is paid exactly once. The capture that paid it is still live -- its own final
        // is what arrives next -- and that one is the wearer's to send.
        assertTrue(
            "the debt must be paid exactly once, not swallow every later dictation",
            r.lifecycle.acceptTranscript()
        )
    }

    /**
     * Paying a debt must also END the capture that was running.
     *
     * The wire delivers exactly ONE final per utterance, so a capture whose final was consumed to
     * pay a debt will never receive another. Leaving it active hangs the recording UI until the
     * watchdog fires -- and the watchdog cancels, which owes a FRESH discard, which eats the next
     * dictation, which re-arms the watchdog. The debt oscillates 1 -> 0 -> 1 forever and the thread
     * is permanently deaf; MAX_PENDING_DISCARDS never engages because the debt never grows.
     *
     * This walks that loop for real: one dictation per round, one final per dictation, as the wire
     * actually behaves.
     */
    /**
     * EVERY cancel owes a discard, the watchdog included.
     *
     * A watchdog timeout does not mean no transcript is coming -- only that none has come YET. The
     * phone can already be committed to delivering one: its VAD ends the utterance a second before
     * our 30 s expires, it launches transcription, and the final lands a few seconds later. If the
     * wearer re-holds in between (the natural reaction to "no speech"), an unpaid watchdog would
     * let those words be adopted by the new capture and sent to the coding agent.
     */
    @Test
    fun everyCancelIncludingTheWatchdogOwesADiscard() {
        for (exit in RcVoiceLifecycle.Exit.values().filter {
            it != RcVoiceLifecycle.Exit.FINAL_TRANSCRIPT &&
                it != RcVoiceLifecycle.Exit.SEND_COMMIT &&
                it != RcVoiceLifecycle.Exit.SEND_WITHDRAWN
        }) {
            val r = Recorder()
            r.lifecycle.start()
            r.lifecycle.cancelCapture(exit)
            // The abandoned utterance's final lands moments later, into the next capture.
            r.elapse(2)
            r.lifecycle.start()
            assertFalse(
                "$exit abandoned a capture whose transcript may still be in flight; without a " +
                    "discard those words would be sent as the NEXT capture's message",
                r.lifecycle.acceptTranscript()
            )
        }
    }

    /**
     * A discard EXPIRES. This is what stops the debt sustaining itself.
     *
     * The debt guards a real but SHORT race: the seconds between abandoning a capture and its
     * in-flight final arriving. Held indefinitely, it instead eats a dictation the wearer makes
     * minutes later -- and that eaten capture then hangs to its own watchdog, which owes a fresh
     * debt, which eats the next one, forever. Expiry bounds the guard to the window it exists for.
     */
    @Test
    fun aDiscardExpiresSoItCannotEatAMuchLaterDictation() {
        val r = Recorder()
        r.lifecycle.start()
        r.lifecycle.cancelCapture(RcVoiceLifecycle.Exit.WATCHDOG)

        // Absolute seconds, deliberately NOT derived from DISCARD_TTL_MS. A test that elapses
        // `TTL + n` scales with the constant and therefore cannot fail on a wrong one -- which is
        // exactly how a 15 s TTL survived a round of auditing.
        r.elapse(10)
        r.lifecycle.start()
        assertTrue(
            "a stale debt ate a dictation made long after any in-flight final could arrive; " +
                "that eaten capture then hangs to its watchdog, which owes again, forever",
            r.lifecycle.acceptTranscript()
        )
    }

    /**
     * THE upper bound, at the fastest speed a human can actually produce: an instant re-hold, a
     * one-word utterance, and the phone's fixed tail. If a debt survives this, it eats a
     * legitimate dictation and the self-sustaining loop is back.
     */
    @Test
    fun aDebtExpiresBeforeEvenTheFastestPossibleRedictation() {
        val r = Recorder()
        r.lifecycle.start()
        r.lifecycle.cancelCapture(RcVoiceLifecycle.Exit.BACK)

        r.now += 500    // the wearer re-holds almost immediately
        r.lifecycle.start()
        r.now += 1_000  // one word
        r.now += 1_500  // the phone's VAD silence window
        r.now += 1_500  // transcription

        assertTrue(
            "the debt outlived the fastest realistic re-dictation (4.5 s) and ate it; the " +
                "abandon TTL must be below that, not merely below a comfortable pause",
            r.lifecycle.acceptTranscript()
        )
    }

    /**
     * THE loop, walked at realistic speed rather than with a convenient gap.
     *
     * A debt is owed, the abandoned utterance is dropped phone-side so it is never paid, and the
     * wearer reacts to "no speech" the way people actually do: re-hold almost immediately, speak
     * briefly, and the phone's final lands a few seconds later. If the TTL outlasts that cycle,
     * the debt eats a LEGITIMATE dictation -- whose capture then waits for a final it has already
     * consumed, hangs to the 30 s watchdog, and the watchdog owes again. Forever, just slowly.
     *
     * Timings are the fastest realistic ones, because those are the ones that break it.
     */
    @Test
    fun aDebtCannotEatTheDictationTheWearerMakesRightAfterIt() {
        val r = Recorder()
        r.lifecycle.start()
        r.lifecycle.cancelCapture(RcVoiceLifecycle.Exit.WATCHDOG)

        repeat(6) { round ->
            r.elapse(2)          // the wearer reacts to "no speech" and re-holds
            r.lifecycle.start()
            r.elapse(4)          // a short utterance
            r.elapse(2)          // the phone transcribes it
            assertTrue(
                "dictation ${round + 1} was eaten by a debt that outlived the re-hold cycle; " +
                    "that capture then hangs to its watchdog, which owes again -- the loop, at a " +
                    "slower cadence",
                r.lifecycle.acceptTranscript()
            )
        }
    }

    /**
     * THE lower bound, again in absolute seconds. The wearer has stopped speaking by the time an
     * abandon fires, so what is left is the phone's VAD silence window plus transcription -- about
     * 3.5 s. A debt that expires inside that lets the abandoned words reach the coding agent.
     */
    @Test
    fun aDiscardIsStillOwedWhileALateFinalCanStillArrive() {
        val r = Recorder()
        r.lifecycle.start()
        r.lifecycle.cancelCapture(RcVoiceLifecycle.Exit.WATCHDOG)

        r.now += 1_500  // the phone's VAD silence window
        r.now += 2_000  // transcription

        r.lifecycle.start()
        assertFalse(
            "the debt expired inside the window where the abandoned utterance's final can still " +
                "arrive; those words would be sent to the coding agent, which acts on them",
            r.lifecycle.acceptTranscript()
        )
    }

    /**
     * The two TTLs measure from different instants and must not be collapsed into one.
     *
     * An abandon is recorded after the wearer stopped speaking, so only the pipeline tail is left.
     * A handover is recorded before the new owner has said a word, so their whole utterance is
     * still to come. One number cannot be right for both: short enough for the abandon bound is
     * far too short for the handover, and long enough for the handover eats re-dictations.
     */
    @Test
    fun theHandoverDebtOutlastsAWholeForeignUtterance() {
        val handover = Recorder()
        handover.lifecycle.start()
        handover.lifecycle.forgetCaptureWithoutStopping()
        handover.elapse(12)  // a long-ish reply, then VAD and transcription
        handover.lifecycle.start()
        assertFalse(
            "the handover debt expired mid-reply; the reply's final would be adopted by this " +
                "capture and sent to the coding agent",
            handover.lifecycle.acceptTranscript()
        )

        val abandon = Recorder()
        abandon.lifecycle.start()
        abandon.lifecycle.cancelCapture(RcVoiceLifecycle.Exit.BACK)
        abandon.elapse(12)
        abandon.lifecycle.start()
        assertTrue(
            "the abandon debt is as long as the handover one; it will eat re-dictations",
            abandon.lifecycle.acceptTranscript()
        )
    }

    /**
     * The loop R7 found, walked with expiry in place: one dictation per round, one final each, and
     * a watchdog between them -- as the wire actually behaves.
     */
    @Test
    fun theDiscardDebtCannotSustainItselfAcrossDictations() {
        val r = Recorder()
        r.lifecycle.start()
        r.lifecycle.forgetCaptureWithoutStopping()

        // The handover debt is deliberately long (it must outlast a whole foreign utterance), so
        // the FIRST dictation after one may legitimately be eaten. What must not happen is the
        // LOOP: that eaten capture hangs to its watchdog, the watchdog owes again, and every
        // dictation after it is eaten too.
        r.elapse(30)
        r.lifecycle.start()
        r.lifecycle.acceptTranscript()
        r.lifecycle.cancelCapture(RcVoiceLifecycle.Exit.WATCHDOG)

        repeat(6) { round ->
            // The wearer notices, and re-dictates -- but not instantly.
            r.elapse(10)
            r.lifecycle.start()
            assertTrue(
                "dictation ${round + 1} was swallowed; the discard debt is self-sustaining and " +
                    "the thread is permanently deaf",
                r.lifecycle.acceptTranscript()
            )
        }
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
