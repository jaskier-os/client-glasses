package com.repository.glasses.listener.ui

/**
 * The ONE description of how dictation feels, shared by the regular AI chat and the RC thread.
 *
 *   tap -> start dictating; the VAD ends the utterance by itself; a 3 s countdown runs; a DOUBLE
 *   tap in that window withdraws it; doing nothing sends.
 *
 * WHY THIS EXISTS. The two surfaces had drifted into two different gestures for the same act: the
 * chat started on a 500 ms hold and shipped the transcript the instant it arrived, the thread
 * started on a tap and offered a 3 s undo. The wearer had to remember which conversation they
 * were in. The decision table lives here so neither surface can quietly evolve its own.
 *
 * Pure Kotlin: a UX contract that can only be verified by putting glasses on is not a contract.
 */
object DictationUx {

    /** What a touchpad tap means, given what is currently running. */
    enum class TapAction {
        /** Nothing is running: begin a dictation. */
        START,

        /** Deliberately nothing. Either the VAD owns the ending, or one tap is not a withdrawal. */
        IGNORE,

        /** A double tap inside the countdown: take the message back. */
        WITHDRAW,
    }

    /**
     * The hold gesture does NOT start a dictation on either surface.
     *
     * It is spoken for: NUMPAD_3 opens the AI chat from anywhere, and a notification hold arms a
     * reply. Overloading it here is what let the two surfaces disagree.
     */
    const val HOLD_STARTS_DICTATION = false

    /**
     * How long the wearer has to change their mind in an RC thread. The glasses own this window,
     * so the countdown and the send are timed from the same number.
     */
    const val WINDOW_MS = RcSendWindow.WINDOW_MS

    /**
     * How long the wearer has to change their mind in the regular AI chat.
     *
     * This one is NOT ours: the PHONE owns the chat's pre-send confirm window (`confirmRunnable`,
     * posted at 2000 ms in the phone's ListenerService) and the glasses only draw over it. The bar
     * must therefore animate the phone's duration, not the RC one -- at 3000 ms it would still be
     * a third full when the message had already gone, and a "cancel" made at 2.4 s while the bar
     * still said there was time would land after the send.
     *
     * Kept as its own constant rather than folded into [WINDOW_MS] precisely because the two are
     * owned by different sides and must be changed independently. If the phone's window changes,
     * change this to match.
     */
    const val CHAT_WINDOW_MS = 2000L

    fun onTap(dictating: Boolean, sendPending: Boolean, doubleTap: Boolean): TapAction = when {
        // The countdown is checked first: a transcript is in hand and it is the only state where
        // a tap does anything at all.
        sendPending -> if (doubleTap) TapAction.WITHDRAW else TapAction.IGNORE
        // While speaking the wearer simply stops talking. Nothing the finger does ends an
        // utterance -- letting it would produce a different endpoint than the VAD's on one
        // surface and not the other, which is precisely the divergence being removed.
        dictating -> TapAction.IGNORE
        else -> TapAction.START
    }
}
