package com.repository.glasses.listener.ui

/**
 * Swallows the ONE touchpad release that trails the double tap which just cancelled a dictation.
 *
 * WHY THIS EXISTS. A double tap on this pad is not two events the app pairs up -- the PSoC firmware
 * classifies it and sends `KEYCODE_BACK`, while the touchpad daemon separately emits a `NUMPAD_2`
 * for the finger coming off. Two different keycodes, from two different producers, for one gesture.
 * The BACK arrives first and cancels; the NUMPAD_2 arrives ~25 ms later, finds an idle thread, and
 * is a perfectly valid request to start dictating:
 *
 * ```
 * 21:21:21.791 RC: BACK cancels the pending voice send
 * 21:21:21.802 RC: capture cancelled (BACK)
 * 21:21:21.827 RC: tap -> start dictation
 * ```
 *
 * The wearer sees the dictation they just threw away come back in the next frame.
 *
 * No amount of inspecting `dictating` / `pending` can fix this: by the time the tap lands those are
 * correctly idle, and the tap is indistinguishable from a deliberate fresh one. The only thing that
 * tells them apart is that a cancel happened one double-tap-gap ago.
 *
 * Exactly ONE tap is swallowed, not the whole window. Someone who cancelled because they want to
 * say it again will tap again immediately -- the most likely next action -- and eating that would
 * trade one visible bug for another.
 *
 * Pure Kotlin, no Android APIs: a gesture rule that can only be checked by wearing the glasses is
 * not a rule.
 */
class TapAfterCancelGuard {

    /** When the trailing release stops being attributable to the cancel. 0 = not armed. */
    private var armedUntil: Long = 0L

    /** A dictation was just cancelled by a touchpad gesture. */
    fun onCancel(now: Long) {
        armedUntil = now + WINDOW_MS
    }

    /**
     * @return true when this tap is the tail of the cancelling double tap and must be dropped.
     *   Disarms either way -- on a match because only one release is owed, and on a miss because a
     *   stale arming left lying around would eat an unrelated tap much later.
     */
    fun swallows(now: Long): Boolean {
        if (armedUntil == 0L) return false
        val within = now <= armedUntil
        armedUntil = 0L
        return within
    }

    companion object {
        /**
         * The same gap the rest of the app pairs taps over ([RcSendWindow.DOUBLE_TAP_MAX_MS], which
         * equals MainActivity's DOUBLE_TAP_NUMPAD2_MAX_MS). Same finger, same pad, so deliberately
         * not a new number.
         */
        const val WINDOW_MS = RcSendWindow.DOUBLE_TAP_MAX_MS
    }
}
