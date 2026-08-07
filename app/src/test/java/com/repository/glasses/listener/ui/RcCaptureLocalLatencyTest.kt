package com.repository.glasses.listener.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan task 4.5 -- the RC dictation ordering that on-glasses recognition changes.
 *
 * The existing gate was written against the REMOTE transcriber, where a final
 * always arrives well after the user released the hold: the phone had to stream
 * the audio to a server and wait. Recognising on the glasses removes that round
 * trip, so a final can now land BEFORE the stop event that ends the capture.
 *
 * That is not a hypothetical reordering: the whole point of the feature is lower
 * latency, and the two events travel by different routes (the transcript over
 * the local Binder + BT, the stop from the touchpad through the input stack).
 *
 * The gate must accept such a transcript EXACTLY ONCE, and -- the part that
 * actually bites -- the stop that follows must not then owe a discard, because
 * that debt would silently eat the NEXT dictation the wearer speaks.
 *
 * These extend the existing coverage rather than relaxing it: every remote-order
 * assertion still holds.
 */
class RcCaptureLocalLatencyTest {

    @Test
    fun aTranscriptArrivingBeforeTheStopIsAcceptedExactlyOnce() {
        val c = RcCapture()
        c.start()
        assertTrue("the running capture's own transcript must be accepted", c.acceptTranscript())
        assertFalse(
            "a second delivery of the same final must not send the words twice",
            c.acceptTranscript()
        )
    }

    @Test
    fun aStopAfterAnAlreadyAcceptedTranscriptDoesNotDeafenTheNextDictation() {
        // THE regression local latency introduces. Order: transcript, then stop.
        // If the stop counts as an abandoned capture it owes a discard, and that
        // debt is paid by swallowing the NEXT dictation's transcript -- the
        // wearer speaks, sees nothing happen, and nothing is logged as wrong.
        val c = RcCapture()
        c.start()
        assertTrue(c.acceptTranscript())

        // The stop event lands afterwards. The capture is already finished, so
        // there is nothing left to abandon.
        assertFalse("a completed capture must not be counted as abandoned", c.cancel())

        c.start()
        assertTrue(
            "the next dictation must not be swallowed by a debt from the previous one",
            c.acceptTranscript()
        )
    }

    @Test
    fun theRemoteOrderingStillHolds() {
        // Stop first, transcript second: the original path, unchanged.
        val c = RcCapture()
        c.start()
        assertTrue(c.cancel())
        assertFalse("an abandoned capture's transcript is still discarded", c.acceptTranscript())
    }

    @Test
    fun aTranscriptWithNoCaptureRunningIsStillRefused() {
        // Faster local delivery must not become an excuse to accept a final that
        // belongs to no capture at all.
        assertFalse(RcCapture().acceptTranscript())
    }

    @Test
    fun anAbandonedCaptureStillEatsExactlyOneLateTranscript() {
        val c = RcCapture()
        c.start()
        c.cancel()
        c.start()
        assertFalse("the abandoned capture's late final is discarded", c.acceptTranscript())
        assertTrue("the new capture's own final is then accepted", c.acceptTranscript())
    }
}
