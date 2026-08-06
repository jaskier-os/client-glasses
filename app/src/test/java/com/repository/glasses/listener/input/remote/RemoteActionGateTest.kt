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
        translationStarting: Boolean = false,
        translationActive: Boolean = false,
        mouseTracking: Boolean = false,
        callPhase: String = "IDLE",
        nightVisionSliderLocked: Boolean = false,
    ) = RemoteActionGate.UiInputSnapshot(
        focusState = focus,
        serviceState = service,
        foldedState = folded,
        todoFocusLevel = todoLevel,
        replyArming = replyArming,
        hasActiveReply = activeReply,
        replySendPending = sendPending,
        translationStarting = translationStarting,
        translationActive = translationActive,
        mouseTracking = mouseTracking,
        callPhase = callPhase,
        nightVisionSliderLocked = nightVisionSliderLocked,
    )

    private fun allowed(s: RemoteActionGate.UiInputSnapshot, a: RemoteAction) =
        RemoteActionGate.evaluate(s, a) == RemoteActionGate.Denial.ALLOWED

    /** Every FocusState value, so a newly added state cannot quietly default to permitted. */
    private val allStates = listOf(
        "TAB_NAV", "CHAT_FOCUSED", "LIST_FOCUSED", "MAP_FOCUSED", "MAP_ZOOM_FOCUSED",
        "STOP_MODAL", "STEPS_MODAL", "TRANSLATE_FOCUSED", "TELEPROMPTER_FOCUSED",
        "REID_FOCUSED", "REID_FACES_FOCUSED", "REID_INTEL_MODAL", "COPILOT_FOCUSED", "TODO_FOCUSED",
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
                RemoteActionGate.evaluate(s, RemoteAction.SELECT),
            )
        }
    }

    @Test
    fun `pending send refuses`() {
        val s = snap("CHAT_FOCUSED", sendPending = true)
        assertEquals(RemoteActionGate.Denial.REFUSED_ARMED, RemoteActionGate.evaluate(s, RemoteAction.SELECT))
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
            assertEquals(RemoteActionGate.Denial.REFUSED_BUSY, RemoteActionGate.evaluate(s, RemoteAction.SELECT))
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
    fun `LIST_FOCUSED is navigable, with the mic row refused at the point of action`() {
        // LIST_FOCUSED is the primary navigation state: the user lives here. Refusing
        // TAP across the whole state to protect ONE of its three rows is what made the
        // feature unusable in practice.
        //
        // Only the Assistant row reaches openAssistant() -> the mic. That row cannot be
        // judged here, because which row is selected can change during the 400 ms the
        // tap handler defers for double-tap detection -- so the refusal lives at the
        // point of action in MainActivity, keyed on the captured input origin.
        assertEquals(true, allowed(snap("LIST_FOCUSED"), RemoteAction.SCROLL_STEP))
        assertEquals(true, allowed(snap("LIST_FOCUSED"), RemoteAction.SELECT))
        assertEquals(true, allowed(snap("LIST_FOCUSED"), RemoteAction.BACK))
    }

    @Test
    fun `tap cannot start a telegram voice recording`() {
        // TELEGRAM_CHAT_FOCUSED tap -> telegramStartVoice(), which opens the microphone.
        assertEquals(false, allowed(snap("TELEGRAM_CHAT_FOCUSED"), RemoteAction.SELECT))
    }

    @Test
    fun `tap cannot confirm sending a message to a contact`() {
        // TELEGRAM_PREVIEW tap -> telegramConfirmSend().
        assertEquals(false, allowed(snap("TELEGRAM_PREVIEW"), RemoteAction.SELECT))
    }

    @Test
    fun `tap cannot toggle live translation`() {
        assertEquals(false, allowed(snap("TRANSLATE_FOCUSED"), RemoteAction.SELECT))
    }

    @Test
    fun `tap cannot toggle copilot, but the tab can still be left`() {
        // Starting Copilot opens the front mic on the wearer's surroundings, so SELECT is
        // refused for the same reason as translation. BACK must stay allowed or a remote
        // user who opened the tab would be stuck in it.
        assertEquals(false, allowed(snap("COPILOT_FOCUSED"), RemoteAction.SELECT))
        assertEquals(true, allowed(snap("COPILOT_FOCUSED"), RemoteAction.BACK))
        assertEquals(true, allowed(snap("COPILOT_FOCUSED"), RemoteAction.SCROLL_STEP))
    }

    @Test
    fun `reid is navigable, but a scroll may not issue an osint upload`() {
        // Face capture and the intel request are user-visible actions on the user's own
        // screen, and neither is on the never-list. What stays refused is the
        // REID_FACES_FOCUSED scroll, because there the SCROLL ITSELF issues an OSINT
        // lookup that uploads an identifier -- that is not navigation.
        assertEquals(true, allowed(snap("REID_FOCUSED"), RemoteAction.SELECT))
        assertEquals(true, allowed(snap("REID_FACES_FOCUSED"), RemoteAction.SELECT))
        assertEquals(false, allowed(snap("REID_FACES_FOCUSED"), RemoteAction.SCROLL_STEP))
    }

    @Test
    fun `scroll cannot trigger an osint upload in the faces list`() {
        // REID_FACES_FOCUSED scroll -> requestPersonIntel(), which uploads an identifier.
        assertEquals(false, allowed(snap("REID_FACES_FOCUSED"), RemoteAction.SCROLL_STEP))
    }

    @Test
    fun `night vision scroll is refused only while its slider is engaged`() {
        // Scroll WRITES persisted camera settings only when the slider is locked;
        // unlocked it merely moves between sliders, which is ordinary navigation.
        assertEquals(
            false,
            allowed(snap("NIGHTVISION_FOCUSED", nightVisionSliderLocked = true), RemoteAction.SCROLL_STEP),
        )
        assertEquals(
            true,
            allowed(snap("NIGHTVISION_FOCUSED", nightVisionSliderLocked = false), RemoteAction.SCROLL_STEP),
        )
    }

    @Test
    fun `the stop-journey modal is operable, because the remote user is the user`() {
        // Terminating navigation is destructive but it is not on the never-list, and a
        // remote control whose user cannot answer a modal that is on their own screen
        // is broken. BACK still dismisses it.
        assertEquals(true, allowed(snap("STOP_MODAL"), RemoteAction.SELECT))
        assertEquals(true, allowed(snap("STOP_MODAL"), RemoteAction.BACK))
    }

    @Test
    fun `the map is navigable`() {
        // Toggling a pin is a user-visible, user-reversible edit on the user's own
        // screen. Not on the never-list.
        assertEquals(true, allowed(snap("MAP_FOCUSED"), RemoteAction.SELECT))
        assertEquals(true, allowed(snap("MAP_FOCUSED"), RemoteAction.SCROLL_STEP))
    }

    @Test
    fun `tap cannot mutate a todo item`() {
        // Level 1 toggles the checklist item; levels 0 and 2 only navigate.
        assertEquals(false, allowed(snap("TODO_FOCUSED", todoLevel = 1), RemoteAction.SELECT))
        assertEquals(true, allowed(snap("TODO_FOCUSED", todoLevel = 0), RemoteAction.SELECT))
        assertEquals(true, allowed(snap("TODO_FOCUSED", todoLevel = 2), RemoteAction.SELECT))
    }

    @Test
    fun `media playback is controllable`() {
        // Play/pause and track skip are neither destructive nor on the never-list, and
        // controlling music from the wrist is an obvious use of a remote.
        assertEquals(true, allowed(snap("MUSIC_FOCUSED"), RemoteAction.SELECT))
        assertEquals(true, allowed(snap("MUSIC_FOCUSED"), RemoteAction.SCROLL_STEP))
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
    fun `mouse focused refuses the tap that toggles HID tracking but keeps its exit`() {
        // TAP toggles tracking, so it stays refused.
        assertEquals(false, allowed(snap("MOUSE_FOCUSED"), RemoteAction.SELECT))
        // BACK only toggles tracking on the branch where tracking is ALREADY on, and
        // that branch is unreachable from a remote source because mouseTracking is
        // refused as REFUSED_BUSY. With tracking off, BACK just returns to TAB_NAV --
        // it is the only way out of this state, since TAP is refused.
        assertEquals(true, allowed(snap("MOUSE_FOCUSED"), RemoteAction.BACK))
        assertEquals(
            RemoteActionGate.Denial.REFUSED_BUSY,
            RemoteActionGate.evaluate(snap("MOUSE_FOCUSED", mouseTracking = true), RemoteAction.BACK),
        )
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
        assertEquals(true, allowed(snap("TAB_NAV"), RemoteAction.SELECT))
        assertEquals(true, allowed(snap("CHAT_FOCUSED"), RemoteAction.SCROLL_STEP))
        assertEquals(true, allowed(snap("CHAT_FOCUSED"), RemoteAction.BACK))
        assertEquals(true, allowed(snap("TELEGRAM_TOPICS_FOCUSED"), RemoteAction.SCROLL_STEP))
        assertEquals(true, allowed(snap("TELEGRAM_TOPICS_FOCUSED"), RemoteAction.SELECT))
    }

    // --- Index hijack: scrolling that re-aims the user's own next tap ---

    @Test
    fun `scroll moves a selection wherever it is only a selection`() {
        // Formerly refused as "index hijack". See the teleprompter test for why that
        // reasoning does not apply to a remote control.
        assertEquals(true, allowed(snap("MAP_FOCUSED"), RemoteAction.SCROLL_STEP))
        assertEquals(true, allowed(snap("STOP_MODAL"), RemoteAction.SCROLL_STEP))
    }

    @Test
    fun `the teleprompter is controllable`() {
        // The "index hijack" argument -- that a remote scroll re-aims the user's next
        // tap -- does not survive the fact that this IS a remote control: the remote
        // user IS the user, and re-aiming your own selection is what scrolling is for.
        assertEquals(true, allowed(snap("TELEPROMPTER_FOCUSED"), RemoteAction.SCROLL_STEP))
        assertEquals(true, allowed(snap("TELEPROMPTER_FOCUSED"), RemoteAction.SELECT))
    }

    @Test
    fun `the telegram list is navigable, but a chat may not start a recording`() {
        // Choosing a contact is navigation. What must never happen is STARTING the
        // voice recording, which lives one state deeper.
        assertEquals(true, allowed(snap("TELEGRAM_LIST_FOCUSED"), RemoteAction.SCROLL_STEP))
        assertEquals(true, allowed(snap("TELEGRAM_LIST_FOCUSED"), RemoteAction.SELECT))
        assertEquals(false, allowed(snap("TELEGRAM_CHAT_FOCUSED"), RemoteAction.SELECT))
    }

    // --- Calls, checked on the phase rather than the focus state ---

    @Test
    fun `an active call refuses everything even though focusState is restored`() {
        // On CallPhase.ACTIVE the UI deliberately restores the previous focus so the user can keep
        // navigating while talking. focusState is therefore NEVER CALL_ACTIVE during a live call,
        // and a gate keyed on focusState alone would permit remote input throughout.
        for (phase in listOf("INCOMING", "ACTIVE", "ENDING")) {
            for (action in RemoteAction.values()) {
                assertEquals(
                    "$action must be refused during a $phase call",
                    RemoteActionGate.Denial.REFUSED_STATE,
                    RemoteActionGate.evaluate(snap("CHAT_FOCUSED", callPhase = phase), action),
                )
            }
        }
    }

    @Test
    fun `an idle call phase does not block ordinary input`() {
        assertEquals(true, allowed(snap("CHAT_FOCUSED", callPhase = "IDLE"), RemoteAction.SCROLL_STEP))
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

    /**
     * The never-list, asserted as one block.
     *
     * These are the outcomes the product owner named as the actual hazards, as opposed
     * to the screens that merely contain something editable. If the gate is ever
     * loosened again, this is the test that must not be touched.
     */
    @Test
    fun `the never-list stays unreachable after the allowlist rework`() {
        // Starting or confirming a voice recording.
        assertEquals(false, allowed(snap("TELEGRAM_CHAT_FOCUSED"), RemoteAction.SELECT))
        assertEquals(false, allowed(snap("TELEGRAM_RECORDING"), RemoteAction.SELECT))
        assertEquals(false, allowed(snap("TELEGRAM_PREVIEW"), RemoteAction.SELECT))
        // Toggling the translation microphone.
        assertEquals(false, allowed(snap("TRANSLATE_FOCUSED"), RemoteAction.SELECT))
        // Turning the screen off: BACK at the top level does that, which would drop the
        // sink and strand the session with no way back from the remote device.
        assertEquals(false, allowed(snap("TAB_NAV"), RemoteAction.BACK))
        // Taking ownership of the input device. Only TAP toggles tracking unconditionally;
        // the BACK toggle sits behind tracking already being on, which is REFUSED_BUSY.
        assertEquals(false, allowed(snap("MOUSE_FOCUSED"), RemoteAction.SELECT))
        assertEquals(
            false,
            allowed(snap("MOUSE_FOCUSED", mouseTracking = true), RemoteAction.BACK),
        )
        // An active call, a notification reply, a recording.
        for (state in listOf("CALL_INCOMING", "CALL_ACTIVE", "NOTIFICATION_REPLY")) {
            for (a in RemoteAction.entries) assertEquals(false, allowed(snap(state), a))
        }
        for (a in RemoteAction.entries) {
            assertEquals(false, allowed(snap("TAB_NAV", callPhase = "ACTIVE"), a))
            assertEquals(false, allowed(snap("TAB_NAV", replyArming = true), a))
            assertEquals(false, allowed(snap("TAB_NAV", translationActive = true), a))
        }
    }

    /**
     * The rework expressed the rules as denylists, which read as "permit unless named".
     * A newly added UI state must still arrive REFUSED rather than silently permitted --
     * that property is the whole reason the original design was an allowlist, and it is
     * preserved by the known-states backstop rather than by naming every action.
     */
    @Test
    fun `a state nobody has reviewed is still refused`() {
        for (a in RemoteAction.entries) {
            assertEquals(false, allowed(snap("SOME_FUTURE_TAB_FOCUSED"), a))
        }
    }

    /** The states the user actually navigates in must be fully operable. */
    @Test
    fun `the primary navigation states are usable`() {
        for (state in listOf("TAB_NAV", "LIST_FOCUSED", "CHAT_FOCUSED", "TODO_FOCUSED")) {
            assertEquals("scroll in " + state, true, allowed(snap(state), RemoteAction.SCROLL_STEP))
            assertEquals("tap in " + state, true, allowed(snap(state), RemoteAction.SELECT))
        }
        // ...and every state except the top level must be leaveable.
        for (state in listOf("LIST_FOCUSED", "CHAT_FOCUSED", "TODO_FOCUSED", "MAP_FOCUSED")) {
            assertEquals("back in " + state, true, allowed(snap(state), RemoteAction.BACK))
        }
    }

    /**
     * The invariant that would have caught B1 before hardware did.
     *
     * A remote user who can ENTER a state must be able to LEAVE it. The watch produces
     * only taps, so the exit is a double tap dispatched as BACK; if a state refuses
     * both TAP and BACK the user is stranded until they physically touch the glasses.
     * Asserted over every known state rather than a hand-kept list, so adding a UI
     * state cannot silently reintroduce the trap.
     *
     * The wholesale-refusal states are exempt: they refuse every action, and they are
     * entered by a call or by the phone rather than by a remote action, so no remote
     * user can be trapped in one.
     */
    @Test
    fun `every state a remote user can enter can also be left`() {
        val notEnterableByRemote = setOf(
            "CALL_INCOMING", "CALL_ACTIVE", "NOTIFICATION_REPLY",
            "TELEGRAM_RECORDING", "TELEGRAM_PREVIEW",
            // Refuses BACK on purpose (BACK turns the screen off); covered below.
            "TAB_NAV",
        )
        for (state in RemoteActionGate.knownStatesForTest - notEnterableByRemote) {
            assertEquals(
                "no exit from " + state + ": BACK is refused, so a remote user who " +
                    "entered it cannot leave without the physical touchpad",
                true,
                allowed(snap(state), RemoteAction.BACK),
            )
        }
    }

    /**
     * TAB_NAV is the one state that legitimately refuses BACK, because BACK there turns
     * the screen off and strands the session. It must therefore stay navigable by TAP
     * and scroll, or the top level would be a dead end in the other direction.
     */
    @Test
    fun `TAB_NAV refuses BACK but stays navigable`() {
        assertEquals(false, allowed(snap("TAB_NAV"), RemoteAction.BACK))
        assertEquals(true, allowed(snap("TAB_NAV"), RemoteAction.SELECT))
        assertEquals(true, allowed(snap("TAB_NAV"), RemoteAction.SCROLL_STEP))
    }
}
