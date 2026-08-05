package com.repository.glasses.listener.input.remote

/**
 * Decides whether a remote action may act on the UI right now.
 *
 * ## Deny by default
 *
 * The physical touchpad reaches, among other things: starting and stopping the microphone, sending
 * a voice message to a real contact, answering and terminating phone calls, taking photos, turning
 * the screen off, and launching the assistant. A remote source that inherited the touchpad's full
 * reach would inherit all of that. So this gate enumerates what is ALLOWED and refuses everything
 * else -- adding a UI capability does not silently widen the remote surface, because a new state is
 * simply absent from the allowlist and is therefore denied.
 *
 * ## Why a (focusState, action) pair is not enough
 *
 * Several dangerous paths are entered from a state that is still nominally safe:
 * - `serviceState` LISTENING/RESPONDING intercepts taps ahead of the focus dispatch entirely.
 * - `replyArming` is set BEFORE `focusState` becomes `NOTIFICATION_REPLY`, so a tap landing in that
 *   window sees a permitted state and still reaches the reply machinery.
 * - `translationStarting` holds for seconds while `focusState` is still `TRANSLATE_FOCUSED`.
 * - `todoFocusLevel` makes one focus state mean three different action sets.
 *
 * The gate therefore takes a full [UiInputSnapshot], captured on the main thread immediately before
 * dispatch. Evaluating at enqueue time would be stale by construction, since the queue coalesces.
 *
 * ## Device-agnostic on purpose
 *
 * Nothing here refers to a watch, a source id, or a transport. Every registered [InputSource] is
 * gated identically, which is what makes a future device zero-change for the UI.
 */
object RemoteActionGate {

    /**
     * Everything the gate needs to judge an action. Deliberately a value snapshot rather than live
     * reads: the decision and the dispatch must see the same state.
     *
     * `focusState` is an ordinal-free String (the enum's `name`) so this file keeps its promise of
     * not referencing `MainActivity`.
     */
    data class UiInputSnapshot(
        val focusState: String,
        val serviceState: String,
        val foldedState: Boolean,
        val todoFocusLevel: Int,
        val replyArming: Boolean,
        val hasActiveReply: Boolean,
        val replySendPending: Boolean,
        val translationStarting: Boolean,
        val translationActive: Boolean,
        val mouseTracking: Boolean,
        /**
         * The call state machine's own phase, NOT `focusState`.
         *
         * Checking `focusState == CALL_ACTIVE` would be worthless: on `CallPhase.ACTIVE` the UI
         * deliberately RESTORES the previous focus so the user can keep navigating while talking,
         * so `focusState` is never `CALL_ACTIVE` during a live call. The phase is the only field
         * that actually tells the truth about a call being up.
         */
        val callPhase: String,
    )

    /** Why an action was refused. Reported to the source so it can explain the silence. */
    enum class Denial {
        ALLOWED,

        /** A call, a recording, or a reply is on screen; remote input is refused wholesale. */
        REFUSED_STATE,

        /** Something dangerous is armed but the focus state has not caught up yet. */
        REFUSED_ARMED,

        /** The glasses are folded; the touchpad path swallows input here too. */
        REFUSED_FOLDED,

        /** A voice session owns the tap; a remote tap would cancel it. */
        REFUSED_BUSY,

        /** This action is simply not on the allowlist for this state. */
        REFUSED_NOT_ALLOWED,
    }

    /**
     * Focus states where remote input is refused outright, whatever the action.
     *
     * Calls, an in-flight notification reply and an in-flight voice message all have the property
     * that every key is load-bearing and a mistimed one is irreversible (a call answered into a
     * room, a message sent to a contact). The user is holding the glasses in these states anyway.
     */
    private val REFUSED_STATES = setOf(
        "CALL_INCOMING",
        "CALL_ACTIVE",
        "NOTIFICATION_REPLY",
        "TELEGRAM_RECORDING",
        "TELEGRAM_PREVIEW",
    )

    /**
     * States where a remote SCROLL is permitted. Scrolling is the benign action, but not
     * universally: in `REID_FACES_FOCUSED` a scroll issues an OSINT lookup that uploads an
     * identifier, and in `NIGHTVISION_FOCUSED` it writes persisted camera settings. Neither is
     * scrolling.
     */
    private val SCROLL_ALLOWED = setOf(
        "TAB_NAV",
        "CHAT_FOCUSED",
        "LIST_FOCUSED",
        "MAP_ZOOM_FOCUSED",
        "STEPS_MODAL",
        "TRANSLATE_FOCUSED",
        "REID_FOCUSED",
        "REID_INTEL_MODAL",
        "TODO_FOCUSED",
        "TELEGRAM_TOPICS_FOCUSED",
        "TELEGRAM_CHAT_FOCUSED",
    )

    /**
     * States where scrolling only LOOKS harmless.
     *
     * Scrolling moves a selection index, and in these states the index is what a subsequent tap
     * acts on -- so a remote scroll re-aims the user's own next tap at something they did not
     * choose. `MAP_FOCUSED` scrolls onto the entry that opens the stop-journey modal, whose own
     * scroll then lands on "confirm"; `TELEPROMPTER_FOCUSED` scrolls onto the control that stops
     * playback; `TELEGRAM_LIST_FOCUSED` re-selects which contact a later voice reply is sent to.
     * These are excluded above and enumerated here so the omissions are not mistaken for oversights.
     */
    private val SCROLL_DENIED_INDEX_HIJACK = setOf(
        "MAP_FOCUSED",
        "STOP_MODAL",
        "TELEPROMPTER_FOCUSED",
        "TELEGRAM_LIST_FOCUSED",
        "NIGHTVISION_FOCUSED",
        "REID_FACES_FOCUSED",
        "MUSIC_FOCUSED",
    )

    /**
     * States where a remote TAP may act.
     *
     * Excluded, with reasons: `LIST_FOCUSED` opens the assistant and starts the mic;
     * `MAP_FOCUSED` toggles a persisted pin; `STOP_MODAL` confirms terminating navigation;
     * `TRANSLATE_FOCUSED` toggles live translation, i.e. the microphone; `REID_FOCUSED` starts
     * camera face capture; `REID_FACES_FOCUSED` triggers an OSINT upload; `MUSIC_FOCUSED` and
     * `NIGHTVISION_FOCUSED` drive media and persisted settings; `MOUSE_FOCUSED` toggles HID
     * tracking; `TELEGRAM_CHAT_FOCUSED` starts a voice recording.
     */
    private val TAP_ALLOWED = setOf(
        "TAB_NAV",
        "CHAT_FOCUSED",
        "MAP_ZOOM_FOCUSED",
        "STEPS_MODAL",
        "REID_INTEL_MODAL",
        "TELEGRAM_TOPICS_FOCUSED",
        "TODO_FOCUSED",
    )

    /**
     * States where a remote BACK may act.
     *
     * `TAB_NAV` is excluded because BACK at the top level turns the screen OFF, which pauses the
     * UI process, drops the sink, and strands the session with no way back from the remote device.
     * `MOUSE_FOCUSED` is excluded because BACK there toggles HID tracking.
     */
    private val BACK_ALLOWED = setOf(
        "CHAT_FOCUSED",
        "LIST_FOCUSED",
        "MAP_FOCUSED",
        "MAP_ZOOM_FOCUSED",
        "STOP_MODAL",
        "STEPS_MODAL",
        "TRANSLATE_FOCUSED",
        "TELEPROMPTER_FOCUSED",
        "REID_FOCUSED",
        "REID_FACES_FOCUSED",
        "REID_INTEL_MODAL",
        "TODO_FOCUSED",
        "NIGHTVISION_FOCUSED",
        "MUSIC_FOCUSED",
        "TELEGRAM_LIST_FOCUSED",
        "TELEGRAM_TOPICS_FOCUSED",
        "TELEGRAM_CHAT_FOCUSED",
    )

    /**
     * `TODO_FOCUSED` level 1 toggles a checklist item, i.e. it mutates the user's data. Levels 0
     * and 2 only navigate.
     */
    private const val TODO_LEVEL_MUTATES = 1

    fun evaluate(s: UiInputSnapshot, action: RemoteAction): Denial {
        // Folded: the touchpad path swallows keys here (pocket/case contact). A remote source has
        // no such excuse, but acting on a screen the user cannot see is worse, not better.
        if (s.foldedState) return Denial.REFUSED_FOLDED

        if (s.focusState in REFUSED_STATES) return Denial.REFUSED_STATE

        // A call in progress. Checked on the phase, because focusState is restored to whatever the
        // user was doing as soon as the call goes ACTIVE and so never reports the call.
        if (s.callPhase == "INCOMING" || s.callPhase == "ACTIVE" || s.callPhase == "ENDING") {
            return Denial.REFUSED_STATE
        }

        // Armed-but-not-yet-transitioned windows. Each of these reaches microphone or send
        // machinery while focusState still reads as something permitted.
        if (s.replyArming || s.hasActiveReply || s.replySendPending ||
            s.translationStarting || s.translationActive
        ) {
            return Denial.REFUSED_ARMED
        }

        // A live voice session intercepts taps ahead of the focus dispatch: a remote tap would
        // cancel the user's in-progress request rather than doing anything they asked for.
        if (s.serviceState == "LISTENING" || s.serviceState == "RESPONDING") {
            if (action != RemoteAction.SCROLL_STEP) return Denial.REFUSED_BUSY
        }

        // Mouse/HID tracking owns the input device while active.
        if (s.mouseTracking) return Denial.REFUSED_BUSY

        val allowed = when (action) {
            RemoteAction.SCROLL_STEP ->
                s.focusState in SCROLL_ALLOWED && s.focusState !in SCROLL_DENIED_INDEX_HIJACK

            RemoteAction.TAP ->
                s.focusState in TAP_ALLOWED &&
                    !(s.focusState == "TODO_FOCUSED" && s.todoFocusLevel == TODO_LEVEL_MUTATES)

            RemoteAction.BACK -> s.focusState in BACK_ALLOWED
        }
        return if (allowed) Denial.ALLOWED else Denial.REFUSED_NOT_ALLOWED
    }
}
