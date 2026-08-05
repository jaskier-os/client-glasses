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

        /**
         * Night vision's slider is engaged, so a scroll writes persisted camera
         * settings instead of navigating. Only that sub-mode is refused; the state as
         * a whole is ordinary navigation.
         */
        val nightVisionSliderLocked: Boolean,
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
     * `TODO_FOCUSED` level 1 toggles a checklist item, i.e. it mutates the user's data. Levels 0
     * and 2 only navigate.
     */
    private const val TODO_LEVEL_MUTATES = 1

    /**
     * Every focus state that has been reviewed for remote input.
     *
     * This is the deny-by-default backstop. The per-action rules below are denylists,
     * so a state absent from this set would otherwise be permitted the moment it is
     * added to the UI, silently widening the remote surface -- the precise failure the
     * original allowlist prevented. Adding a UI state therefore still requires a
     * deliberate decision here, but the decision is now "is this state known" rather
     * than "is every action in it individually blessed".
     */
    private val KNOWN_STATES = setOf(
        "TAB_NAV",
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
        "MOUSE_FOCUSED",
        "TELEGRAM_LIST_FOCUSED",
        "TELEGRAM_TOPICS_FOCUSED",
        "TELEGRAM_CHAT_FOCUSED",
    ) + REFUSED_STATES

    fun evaluate(s: UiInputSnapshot, action: RemoteAction): Denial {
        // Folded: the touchpad path swallows keys here (pocket/case contact). A remote source has
        // no such excuse, but acting on a screen the user cannot see is worse, not better.
        if (s.foldedState) return Denial.REFUSED_FOLDED

        // An unrecognised focus state is denied. The gate is now expressed as denylists
        // per action, which reads as "permit unless named" -- so without this an
        // ADDED UI state would silently arrive permitted, which is exactly the
        // property the original deny-by-default design existed to guarantee. Keeping
        // it means a new state is still refused until someone considers it, while the
        // states we HAVE considered are permissive enough to be usable.
        if (s.focusState !in KNOWN_STATES) return Denial.REFUSED_NOT_ALLOWED

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
            // Scrolling is permitted wherever it only MOVES a selection. The previous
            // rule also refused the "index hijack" states, on the reasoning that a
            // remote scroll re-aims the user's next tap at something they did not
            // choose. That reasoning does not survive contact with what this is: a
            // REMOTE CONTROL, where the remote user IS the user. Re-aiming your own
            // selection is the point of scrolling. What remains genuinely excluded is
            // the handful of states where the scroll ITSELF performs an action rather
            // than moving an index -- see [SCROLL_ACTS_NOT_NAVIGATES].
            RemoteAction.SCROLL_STEP ->
                s.focusState !in SCROLL_ACTS_NOT_NAVIGATES &&
                    // Night vision scroll only writes persisted settings while its
                    // slider is locked; unlocked it is ordinary navigation.
                    !(s.focusState == "NIGHTVISION_FOCUSED" && s.nightVisionSliderLocked)

            // Taps are permitted except where the tap reaches a hazard. Note this is
            // now a DENYLIST of outcomes rather than an allowlist of screens: the old
            // rule refused TAP across whole states because SOME element in them was
            // dangerous, which cost the user selection in LIST_FOCUSED -- the primary
            // navigation state -- to protect one row out of three. Where the hazard
            // depends on which element is selected, the check belongs at the point of
            // action, not here; MainActivity re-checks inside its deferred tap
            // runnable, because the selection can move during the 400 ms it waits.
            RemoteAction.TAP ->
                s.focusState !in TAP_REACHES_HAZARD &&
                    !(s.focusState == "TODO_FOCUSED" && s.todoFocusLevel == TODO_LEVEL_MUTATES)

            RemoteAction.BACK -> s.focusState !in BACK_REACHES_HAZARD
        }
        return if (allowed) Denial.ALLOWED else Denial.REFUSED_NOT_ALLOWED
    }

    /**
     * States where a SCROLL performs an action instead of moving a selection.
     *
     * `REID_FACES_FOCUSED` issues an OSINT lookup that uploads an identifier.
     * `NIGHTVISION_FOCUSED` writes persisted camera settings, but ONLY while its
     * slider is locked -- the unlocked case is ordinary navigation, so the state is
     * gated on [UiInputSnapshot.nightVisionSliderLocked] below rather than refused
     * wholesale.
     */
    private val SCROLL_ACTS_NOT_NAVIGATES = setOf(
        "REID_FACES_FOCUSED",
    )

    /**
     * States where a TAP unavoidably reaches one of the hazards remote input must
     * never touch: starting or confirming a voice recording, or toggling the
     * translation microphone.
     *
     * `LIST_FOCUSED` is deliberately NOT here. Only one of its three tap outcomes is
     * dangerous (the Assistant row, which toggles the mic); the other two open a chat
     * and start a new one. Refusing the whole state to cover one row is what made the
     * feature unusable, since this is the state the user is in most of the time. The
     * Assistant row is refused at the point of action instead.
     */
    private val TAP_REACHES_HAZARD = setOf(
        // Toggles live translation, i.e. the microphone.
        "TRANSLATE_FOCUSED",
        // Starts a voice recording addressed to a real contact.
        "TELEGRAM_CHAT_FOCUSED",
        // Toggles HID tracking, which takes ownership of the input device.
        "MOUSE_FOCUSED",
    )

    /**
     * States where BACK reaches a hazard.
     *
     * `TAB_NAV` is the load-bearing one: BACK at the top level turns the screen OFF,
     * which pauses the UI process, drops the sink, and strands the session with no
     * way back from the remote device. `MOUSE_FOCUSED` BACK toggles HID tracking --
     * and the `mouseTracking` snapshot check above does not cover it, because that
     * only reports tracking that is ALREADY on.
     */
    private val BACK_REACHES_HAZARD = setOf(
        "TAB_NAV",
        "MOUSE_FOCUSED",
    )
}
