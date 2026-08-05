package com.repository.glasses.listener.input.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The gate is the security boundary for remote input, so these tests are written as REFUSAL proofs:
 * every dangerous action a physical touchpad can reach is asserted unreachable from a remote source.
 * A test that only checked the happy paths would still pass if the allowlist were replaced by
 * `return ALLOWED`.
 */
class RemoteActionGateTest {

    private fun snap(
        focus: String,
        service: String = "IDLE",
        folded: Boolean = false,
        todoLevel: Int = 0,
        replyArming: Boolean = false,
        activeReply: Boolean = false,
        sendPending: Boolean = false,
        repliable: Boolean = false,
        translationStarting: Boolean = false,
        translationActive: Boolean = false,
        mouseTracking: Boolean = false,
        nvLocked: Boolean = false,
    ) = RemoteActionGate.UiInputSnapshot(
        focusState = focus,
        serviceState = service,
        foldedState = folded,
        todoFocusLevel = todoLevel,
        replyArming = replyArming,
        hasActiveReply = activeReply,
        replySendPending = sendPending,
        notificationRepliable = repliable,
        translationStarting = translationStarting,
        translationActive = translationActive,
        mouseTracking = mouseTracking,
        nvSliderLocked = nvLocked,
    )

    private fun allowed(s: RemoteActionGate.UiInputSnapshot, a: RemoteAction) =
        RemoteActionGate.evaluate(s, a) == RemoteActionGate.Denial.ALLOWED

    /** Every FocusState value, so a newly added state cannot quietly default to permitted. */
    private val allStates = listOf(
        "TAB_NAV", "CHAT_FOCUSED", "LIST_FOCUSED", "MAP_FOCUSED", "MAP_ZOOM_FOCUSED",
        "STOP_MODAL", "STEPS_MODAL", "TRANSLATE_FOCUSED", "TELEPROMPTER_FOCUSED",
        "REID_FOCUSED", "REID_FACES_FOCUSED", "REID_INTEL_MODAL", "TODO_FOCUSED",
        "NIGHTVISION_FOCUSED", "MOUSE_FOCUSED", "MUSIC_FOCUSED", "TELEGRAM_LIST_FOCUSED",
        "TELEGRAM_TOPICS_FOCUSED", "TELEGRAM_CHAT_FOCUSED", "TELEGRAM_RECORDING",
        "TELEGRAM_PREVIEW", "NOTIFICATION_REPLY", "CALL_INCOMING", "CALL_ACTIVE",
    )

    // --- Wholesale refusal states ---

    @Test
    fun `calls refuse every action`() {
        for (focus in listOf("CALL_INCOMING", "CALL_ACTIVE")) {
            for (action in RemoteAction.values()) {
                assertEquals(
                    "$action must be refused in $focus",
                    RemoteActionGate.Denial.REFUSED_STATE,
                    RemoteActionGate.evaluate(snap(focus), action),
                )
            }
        }
    }

    @Test
    fun `reply and recording states refuse every action`() {
        for (focus in listOf("NOTIFICATION_REPLY", "TELEGRAM_RECORDING", "TELEGRAM_PREVIEW")) {
            for (action in RemoteAction.values()) {
                assertEquals(
                    "$action must be refused in $focus",
                    RemoteActionGate.Denial.REFUSED_STATE,
                    RemoteActionGate.evaluate(snap(focus), action),
                )
            }
        }
    }

    @Test
    fun `folded refuses every action in every state`() {
        for (focus in allStates) {
            for (action in RemoteAction.values()) {
                assertEquals(
                    RemoteActionGate.Denial.REFUSED_FOLDED,
                    RemoteActionGate.evaluate(snap(focus, folded = true), action),
                )
            }
        }
    }

    // --- Armed-but-not-yet-transitioned windows (the TOCTOU findings) ---

    @Test
    fun `reply arming refuses even while focus still reads as safe`() {
        // startReplyArm() sets replyArming BEFORE focusState becomes NOTIFICATION_REPLY. A tap
        // landing in that window would reach the reply machinery from a permitted-looking state.
        val s = snap("TAB_NAV", replyArming = true)
        for (action in RemoteAction.values()) {
            assertEquals(RemoteActionGate.Denial.REFUSED_ARMED, RemoteActionGate.evaluate(s, action))
        }
    }

    @Test
    fun `in-flight reply refuses from any focus state`() {
        for (focus in allStates) {
            val s = snap(focus, activeReply = true)
            assertNotEquals(
                RemoteActionGate.Denial.ALLOWED,
                RemoteActionGate.evaluate(s, RemoteAction.TAP),
            )
        }
    }

    @Test
    fun `pending send refuses`() {
        val s = snap("CHAT_FOCUSED", sendPending = true)
        assertEquals(RemoteActionGate.Denial.REFUSED_ARMED, RemoteActionGate.evaluate(s, RemoteAction.TAP))
    }

    @Test
    fun `translation starting window refuses`() {
        // translationStarting holds for seconds while focusState is still TRANSLATE_FOCUSED.
        val s = snap("TRANSLATE_FOCUSED", translationStarting = true)
        assertEquals(RemoteActionGate.Denial.REFUSED_ARMED, RemoteActionGate.evaluate(s, RemoteAction.SCROLL_STEP))
    }

    @Test
    fun `active translation refuses because the microphone is live`() {
        val s = snap("TRANSLATE_FOCUSED", translationActive = true)
        for (action in RemoteAction.values()) {
            assertEquals(RemoteActionGate.Denial.REFUSED_ARMED, RemoteActionGate.evaluate(s, action))
        }
    }

    // --- Voice session ---

    @Test
    fun `tap during a live voice session is refused so it cannot cancel the request`() {
        for (state in listOf("LISTENING", "RESPONDING")) {
            val s = snap("CHAT_FOCUSED", service = state)
            assertEquals(RemoteActionGate.Denial.REFUSED_BUSY, RemoteActionGate.evaluate(s, RemoteAction.TAP))
            assertEquals(RemoteActionGate.Denial.REFUSED_BUSY, RemoteActionGate.evaluate(s, RemoteAction.BACK))
        }
    }

    @Test
    fun `scrolling is still permitted while listening`() {
        // Reading the transcript as it arrives is exactly what a user does mid-request.
        assertEquals(
            RemoteActionGate.Denial.ALLOWED,
            RemoteActionGate.evaluate(snap("CHAT_FOCUSED", service = "LISTENING"), RemoteAction.SCROLL_STEP),
        )
    }

    // --- Specific dangerous reachability, per the security audit ---

    @Test
    fun `tap cannot reach the assistant microphone in LIST_FOCUSED`() {
        // LIST_FOCUSED tap -> openAssistant(), which starts the AI pipeline and the mic.
        assertEquals(false, allowed(snap("LIST_FOCUSED"), RemoteAction.TAP))
    }

    @Test
    fun `tap cannot start a telegram voice recording`() {
        // TELEGRAM_CHAT_FOCUSED tap -> telegramStartVoice(), which opens the microphone.
        assertEquals(false, allowed(snap("TELEGRAM_CHAT_FOCUSED"), RemoteAction.TAP))
    }

    @Test
    fun `tap cannot confirm sending a message to a contact`() {
        // TELEGRAM_PREVIEW tap -> telegramConfirmSend().
        assertEquals(false, allowed(snap("TELEGRAM_PREVIEW"), RemoteAction.TAP))
    }

    @Test
    fun `tap cannot toggle live translation`() {
        assertEquals(false, allowed(snap("TRANSLATE_FOCUSED"), RemoteAction.TAP))
    }

    @Test
    fun `tap cannot start reid camera capture or an osint lookup`() {
        assertEquals(false, allowed(snap("REID_FOCUSED"), RemoteAction.TAP))
        assertEquals(false, allowed(snap("REID_FACES_FOCUSED"), RemoteAction.TAP))
    }

    @Test
    fun `scroll cannot trigger an osint upload in the faces list`() {
        // REID_FACES_FOCUSED scroll -> requestPersonIntel(), which uploads an identifier.
        assertEquals(false, allowed(snap("REID_FACES_FOCUSED"), RemoteAction.SCROLL_STEP))
    }

    @Test
    fun `scroll cannot write persisted night vision settings`() {
        assertEquals(false, allowed(snap("NIGHTVISION_FOCUSED"), RemoteAction.SCROLL_STEP))
        assertEquals(false, allowed(snap("NIGHTVISION_FOCUSED", nvLocked = true), RemoteAction.SCROLL_STEP))
    }

    @Test
    fun `tap cannot confirm terminating navigation`() {
        assertEquals(false, allowed(snap("STOP_MODAL"), RemoteAction.TAP))
    }

    @Test
    fun `tap cannot toggle a persisted map pin`() {
        assertEquals(false, allowed(snap("MAP_FOCUSED"), RemoteAction.TAP))
    }

    @Test
    fun `tap cannot mutate a todo item`() {
        // Level 1 toggles the checklist item; levels 0 and 2 only navigate.
        assertEquals(false, allowed(snap("TODO_FOCUSED", todoLevel = 1), RemoteAction.TAP))
        assertEquals(true, allowed(snap("TODO_FOCUSED", todoLevel = 0), RemoteAction.TAP))
        assertEquals(true, allowed(snap("TODO_FOCUSED", todoLevel = 2), RemoteAction.TAP))
    }

    @Test
    fun `tap cannot drive media playback`() {
        assertEquals(false, allowed(snap("MUSIC_FOCUSED"), RemoteAction.TAP))
        assertEquals(false, allowed(snap("MUSIC_FOCUSED"), RemoteAction.SCROLL_STEP))
    }

    @Test
    fun `back cannot turn the screen off from the top level`() {
        // BACK in TAB_NAV calls turnScreenOff(), which pauses the UI process, drops the sink, and
        // strands the session with no recovery from the remote device.
        assertEquals(false, allowed(snap("TAB_NAV"), RemoteAction.BACK))
    }

    @Test
    fun `mouse tracking blocks all remote input`() {
        for (focus in allStates) {
            for (action in RemoteAction.values()) {
                assertNotEquals(
                    RemoteActionGate.Denial.ALLOWED,
                    RemoteActionGate.evaluate(snap(focus, mouseTracking = true), action),
                )
            }
        }
    }

    @Test
    fun `mouse focused refuses tap and back which toggle HID tracking`() {
        assertEquals(false, allowed(snap("MOUSE_FOCUSED"), RemoteAction.TAP))
        assertEquals(false, allowed(snap("MOUSE_FOCUSED"), RemoteAction.BACK))
    }

    // --- Deny-by-default ---

    @Test
    fun `an unknown focus state is denied for every action`() {
        for (action in RemoteAction.values()) {
            assertEquals(
                RemoteActionGate.Denial.REFUSED_NOT_ALLOWED,
                RemoteActionGate.evaluate(snap("SOME_FUTURE_STATE"), action),
            )
        }
    }

    // --- The permitted core actually works ---

    @Test
    fun `scrolling and tab entry work in the ordinary navigation states`() {
        assertEquals(true, allowed(snap("TAB_NAV"), RemoteAction.SCROLL_STEP))
        assertEquals(true, allowed(snap("TAB_NAV"), RemoteAction.TAP))
        assertEquals(true, allowed(snap("CHAT_FOCUSED"), RemoteAction.SCROLL_STEP))
        assertEquals(true, allowed(snap("CHAT_FOCUSED"), RemoteAction.BACK))
        assertEquals(true, allowed(snap("TELEGRAM_LIST_FOCUSED"), RemoteAction.SCROLL_STEP))
        assertEquals(true, allowed(snap("TELEGRAM_LIST_FOCUSED"), RemoteAction.TAP))
    }

    @Test
    fun `back leaves a focused state`() {
        for (focus in listOf("CHAT_FOCUSED", "LIST_FOCUSED", "MAP_FOCUSED", "TODO_FOCUSED")) {
            assertEquals("BACK should exit $focus", true, allowed(snap(focus), RemoteAction.BACK))
        }
    }

    /**
     * The gate must not be a device allowlist in disguise. Two sources in different states get the
     * same verdict for the same snapshot, which is the property that makes a future device
     * zero-change.
     */
    @Test
    fun `verdicts depend only on UI state, never on the source`() {
        val s = snap("CHAT_FOCUSED")
        val first = RemoteActionGate.evaluate(s, RemoteAction.SCROLL_STEP)
        val second = RemoteActionGate.evaluate(s.copy(), RemoteAction.SCROLL_STEP)
        assertEquals(first, second)
    }
}
