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
    fun aDeadLinkOutranksAnEndedSessionAndBothOutrankTurning() {
        // Order matters only for which reason is shown; all three refuse. Offline is reported
        // first because it is the one the user can act on.
        assertEquals(RcVoiceGate.Verdict.Offline,
            gate(wsConnected = false, ended = true, turning = true, blockingPrompt = true))
        assertEquals(RcVoiceGate.Verdict.Ended, gate(ended = true, turning = true))
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
