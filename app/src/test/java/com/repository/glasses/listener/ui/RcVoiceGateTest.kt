package com.repository.glasses.listener.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every reason the microphone may or may not run inside an RC thread, and the whole life of the
 * 3 s send window, decided in one pure place.
 *
 * The rule this encodes: dictating into a session that cannot receive it is the worst failure in
 * this feature -- worse than refusing -- so every refusal is explicit and carries a reason the HUD
 * can show. A silent block is forbidden.
 */
class RcVoiceGateTest {

    private fun gate(
        wsConnected: Boolean = true,
        turning: Boolean = false,
        ended: Boolean = false,
        blockingPrompt: Boolean = false,
        capturing: Boolean = false,
        sendPending: Boolean = false,
        sendInFlight: Boolean = false,
    ) = RcVoiceGate.evaluate(
        wsConnected = wsConnected,
        turning = turning,
        ended = ended,
        blockingPrompt = blockingPrompt,
        capturing = capturing,
        sendPending = sendPending,
        sendInFlight = sendInFlight,
    )

    @Test
    fun anIdleLiveSessionAllowsTheMicrophone() {
        assertEquals(RcVoiceGate.Verdict.Allowed, gate())
    }

    @Test
    fun aTurningSessionRefusesAndSaysWhy() {
        assertEquals(RcVoiceGate.Verdict.Turning, gate(turning = true))
    }

    @Test
    fun aDeadOrchestratorLinkRefuses() {
        assertEquals(RcVoiceGate.Verdict.Offline, gate(wsConnected = false))
    }

    @Test
    fun anEndedSessionRefuses() {
        assertEquals(RcVoiceGate.Verdict.Ended, gate(ended = true))
    }

    @Test
    fun aBlockingPromptRefusesBecausePromptsAreAnsweredByPickingAnOption() {
        assertEquals(RcVoiceGate.Verdict.PromptOpen, gate(blockingPrompt = true))
    }

    @Test
    fun aCaptureAlreadyRunningRefusesRatherThanStartingASecondOne() {
        assertEquals(RcVoiceGate.Verdict.Busy, gate(capturing = true))
    }

    @Test
    fun anUnconfirmedSendRefusesSoTheWindowIsNotSteppedOn() {
        assertEquals(RcVoiceGate.Verdict.Busy, gate(sendPending = true))
    }

    @Test
    fun aSendStillInFlightRefuses() {
        assertEquals(RcVoiceGate.Verdict.Busy, gate(sendInFlight = true))
    }

    @Test
    fun theReasonShownIsTheOneTheUserCanActOnFirst() {
        // All of these refuse; the assertions pin WHICH reason surfaces, because a HUD that says
        // "working" while the orchestrator is down sends the user to fix the wrong thing.
        assertEquals(RcVoiceGate.Verdict.Offline,
            gate(wsConnected = false, ended = true, turning = true, blockingPrompt = true,
                capturing = true))
        assertEquals(RcVoiceGate.Verdict.Ended,
            gate(ended = true, blockingPrompt = true, turning = true, capturing = true))
        assertEquals(RcVoiceGate.Verdict.PromptOpen,
            gate(blockingPrompt = true, turning = true, capturing = true))
        assertEquals(RcVoiceGate.Verdict.Turning,
            gate(turning = true, capturing = true, sendPending = true, sendInFlight = true))
    }

    @Test
    fun everyRefusalReasonHasTheWordingTheSpecMandates() {
        // The strings are the whole visible half of "the block is visible, never silent".
        assertEquals("agent offline", RcVoiceGate.Verdict.Offline.hudText)
        assertEquals("session ended", RcVoiceGate.Verdict.Ended.hudText)
        assertEquals("working", RcVoiceGate.Verdict.Turning.hudText)
        assertEquals("answer the prompt", RcVoiceGate.Verdict.PromptOpen.hudText)
        assertEquals("busy", RcVoiceGate.Verdict.Busy.hudText)
        assertEquals("", RcVoiceGate.Verdict.Allowed.hudText)
    }

    @Test
    fun theUndoWindowIsThreeSeconds() {
        // Asserted literally: this duration is the entire reason the window was kept.
        assertEquals(3000L, RcSendWindow.WINDOW_MS)
    }

    @Test
    fun onlyAllowedIsPermissiveEverythingElseRefuses() {
        val refusals = listOf(
            gate(wsConnected = false), gate(ended = true), gate(turning = true),
            gate(blockingPrompt = true), gate(capturing = true),
            gate(sendPending = true), gate(sendInFlight = true),
        )
        assertTrue(refusals.none { it.allowed })
        assertTrue(refusals.all { it.hudText.isNotEmpty() })
        assertTrue(RcVoiceGate.Verdict.Allowed.allowed)
    }

    // -- capture identity --

    @Test
    fun aTranscriptForACancelledCaptureIsSwallowedNotAdoptedByTheNextOne() {
        // The phone finalises an utterance asynchronously and the transcript carries no identity of
        // its own, so the ONLY way to tell them apart is to count the abandoned ones. Without this,
        // words the user explicitly cancelled would arm the next capture's send window.
        val c = RcCapture()
        c.start()
        c.cancel()
        c.start()
        assertTrue("a capture IS running", c.active)
        assertFalse("but this transcript belongs to the abandoned one", c.acceptTranscript())
        assertTrue("the next one is this capture's", c.acceptTranscript())
    }

    @Test
    fun everyAbandonedCaptureSwallowsExactlyOneTranscript() {
        val c = RcCapture()
        c.start(); c.cancel()
        c.start(); c.cancel()
        c.start()
        assertFalse(c.acceptTranscript())
        assertFalse(c.acceptTranscript())
        assertTrue(c.acceptTranscript())
    }

    @Test
    fun aTranscriptArrivingWithNoCaptureRunningIsRefused() {
        val c = RcCapture()
        assertFalse(c.acceptTranscript())
        c.start()
        assertTrue(c.acceptTranscript())
        assertFalse("the capture ended with that final; a duplicate must not be adopted",
            c.acceptTranscript())
        assertFalse(c.active)
    }

    @Test
    fun acceptingATranscriptEndsTheCapture() {
        val c = RcCapture()
        c.start()
        assertTrue(c.active)
        c.acceptTranscript()
        assertFalse(c.active)
    }

    @Test
    fun cancelOnAnIdleCaptureIsANoOpAndOwesNoSwallow() {
        val c = RcCapture()
        assertFalse(c.cancel())
        c.start()
        assertTrue(c.cancel())
        assertFalse("a second cancel of the same capture must not owe a second swallow",
            c.cancel())
        c.start()
        assertFalse("exactly one transcript is owed", c.acceptTranscript())
        assertTrue(c.acceptTranscript())
    }

    @Test
    fun aTimedOutCaptureAlsoSwallowsItsLateTranscript() {
        // The watchdog gives up on a silent capture; if the phone then delivers late, that text is
        // still from the abandoned utterance.
        val c = RcCapture()
        c.start()
        assertTrue(c.cancel())
        assertFalse(c.acceptTranscript())
    }

    @Test
    fun theSwallowDebtIsBoundedSoAThrashedCaptureCannotDeafenTheThreadForever() {
        val c = RcCapture()
        repeat(RcCapture.MAX_PENDING_DISCARDS + 5) { c.start(); c.cancel() }
        c.start()
        var swallowed = 0
        while (!c.acceptTranscript()) {
            swallowed++
            if (swallowed > 100) break
        }
        assertEquals(RcCapture.MAX_PENDING_DISCARDS, swallowed)
    }

    // -- the 3 s send window --

    @Test
    fun aBlankTranscriptNeverOpensTheWindow() {
        val w = RcSendWindow()
        assertFalse(w.arm("   "))
        assertFalse(w.pending)
        assertNull(w.text)
        assertFalse(w.arm(""))
        assertFalse(w.arm("\n\t "))
    }

    @Test
    fun aRealTranscriptOpensTheWindowTrimmed() {
        val w = RcSendWindow()
        assertTrue(w.arm("  deploy it  "))
        assertTrue(w.pending)
        assertEquals("deploy it", w.text)
    }

    @Test
    fun commitReturnsTheTextExactlyOnceAndClearsTheWindow() {
        val w = RcSendWindow()
        w.arm("deploy it")
        assertEquals("deploy it", w.commit())
        assertFalse(w.pending)
        assertNull("a second commit must not fire a second send", w.commit())
    }

    @Test
    fun cancelPreventsAnyLaterCommit() {
        val w = RcSendWindow()
        w.arm("deploy it")
        assertTrue(w.cancel())
        assertFalse(w.pending)
        assertNull("a commit after cancel must send nothing", w.commit())
    }

    @Test
    fun cancellingAnUnarmedWindowIsANoOp() {
        val w = RcSendWindow()
        assertFalse(w.cancel())
    }

    @Test
    fun armingAgainReplacesTheTextRatherThanQueueingASecondSend() {
        val w = RcSendWindow()
        w.arm("first")
        w.arm("second")
        assertEquals("second", w.commit())
        assertNull(w.commit())
    }

    @Test
    fun cancelNeedsTwoTapsInsideTheDoubleTapWindow() {
        val w = RcSendWindow()
        w.arm("deploy it")
        assertFalse("a single tap must not cancel", w.tapCancel(now = 1_000L))
        assertFalse("a slow second tap is a new first tap", w.tapCancel(now = 3_000L))
        assertTrue(w.tapCancel(now = 3_200L))
        assertFalse(w.pending)
    }

    @Test
    fun aTapOnAnUnarmedWindowIsIgnoredAndDoesNotArmTheChain() {
        val w = RcSendWindow()
        assertFalse(w.tapCancel(now = 1_000L))
        w.arm("deploy it")
        assertFalse("the pre-arm tap must not count as the first of a pair",
            w.tapCancel(now = 1_100L))
        assertTrue(w.pending)
    }

    @Test
    fun aTooFastSecondTapIsBounceNotIntent() {
        val w = RcSendWindow()
        w.arm("deploy it")
        w.tapCancel(now = 1_000L)
        assertFalse("contact bounce is not a double tap", w.tapCancel(now = 1_020L))
        assertTrue(w.pending)
    }

    @Test
    fun aFirstTapNearTheEpochIsStillOnlyAFirstTap() {
        // A zero lastTapMs means "no tap yet". If that sentinel were confused with a real
        // timestamp, the very first tap after a boot-adjacent uptime would cancel outright.
        val w = RcSendWindow()
        w.arm("deploy it")
        assertFalse(w.tapCancel(now = 200L))
        assertTrue(w.pending)
        assertTrue(w.tapCancel(now = 400L))
    }

    @Test
    fun aTapBeforeTheWindowNeverPairsWithOneAfterIt() {
        val w = RcSendWindow()
        w.tapCancel(now = 1_000L)      // stray touch, no window open
        w.arm("deploy it")
        assertFalse("a stray touch must not become half of a cancel", w.tapCancel(now = 1_100L))
        assertTrue(w.pending)
    }

    @Test
    fun reArmingWhilePendingAlsoResetsTheCancelChain() {
        // The one path where only arm() can clear the chain: a second transcript replaces the
        // first without any commit or cancel in between. A tap from the old window pairing with
        // one from the new would discard a dictation the user never tried to cancel.
        val w = RcSendWindow()
        w.arm("first")
        w.tapCancel(now = 1_000L)
        w.arm("second")
        assertFalse(w.tapCancel(now = 1_100L))
        assertTrue(w.pending)
        assertEquals("second", w.text)
    }

    @Test
    fun aTapAfterACommitDoesNotPairIntoTheNextWindow() {
        val w = RcSendWindow()
        w.arm("one")
        w.tapCancel(now = 1_000L)
        w.commit()
        w.arm("two")
        assertFalse("the tap that preceded the commit belongs to the old window",
            w.tapCancel(now = 1_100L))
        assertTrue(w.pending)
    }

    @Test
    fun theCancelChainResetsAfterASuccessfulCancel() {
        val w = RcSendWindow()
        w.arm("one")
        w.tapCancel(now = 1_000L)
        assertTrue(w.tapCancel(now = 1_200L))
        w.arm("two")
        assertFalse("the previous pair must not carry over into the new window",
            w.tapCancel(now = 1_300L))
        assertTrue(w.pending)
    }
}
