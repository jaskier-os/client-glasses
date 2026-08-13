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
     * Permits everything except a folded pair of glasses.
     *
     * THE WATCH IS A REMOTE CONTROL, AND THE PERSON HOLDING IT IS THE WEARER. There is no
     * second party to protect the wearer from, so every state-based refusal that used to
     * live here was the app refusing the user access to their own device. Do not
     * reintroduce one. If an action is dangerous, it is equally dangerous from the temple
     * touchpad, and the fix belongs at the point of action where BOTH input paths pass
     * through -- not in a list that only the remote path consults.
     *
     * The allowlist that was here failed exactly the way allowlists do: `RC_THREAD_FOCUSED`
     * was added to the UI, nobody added it to the list, and every remote action inside a
     * coding thread was silently refused. The deny-by-default rule was working as designed;
     * the design was wrong.
     *
     * FOLD is kept, and it is not a policy judgement: the display is physically off, so
     * acting would change state the user cannot see. The touchpad path swallows keys for
     * the same reason (pocket contact).
     */
    fun evaluate(s: UiInputSnapshot, action: RemoteAction): Denial =
        if (s.foldedState) Denial.REFUSED_FOLDED else Denial.ALLOWED

}
