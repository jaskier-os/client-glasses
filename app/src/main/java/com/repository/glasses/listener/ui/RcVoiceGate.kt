package com.repository.glasses.listener.ui

/**
 * Decides whether hold-to-speak may run inside an RC thread, and says why when it may not.
 *
 * Every refusal carries HUD text. A silently swallowed hold is forbidden: the user would believe
 * they had dictated into their coding agent when nothing was captured, which is the worst failure
 * this feature can produce.
 */
object RcVoiceGate {

    enum class Verdict(val allowed: Boolean, val hudText: String) {
        Allowed(true, ""),

        /** The phone's orchestrator link is down. Reading stale rows is fine, dictating is not. */
        Offline(false, "agent offline"),

        /** The CLI is gone. Nothing can consume a message. */
        Ended(false, "session ended"),

        /** A turn is running. The HUD shows the spinner, so the block is visible. */
        Turning(false, "working"),

        /** A prompt is waiting. Prompts are answered by picking an option, never by voice. */
        PromptOpen(false, "answer the prompt"),

        /** A capture, an unconfirmed send or a send in flight already owns the microphone. */
        Busy(false, "busy"),
    }

    /**
     * Reasons are checked in the order the user can act on them: a dead link first (reconnect),
     * then a dead session (nothing to do here), then transient states.
     */
    fun evaluate(
        wsConnected: Boolean,
        turning: Boolean,
        ended: Boolean,
        blockingPrompt: Boolean,
        capturing: Boolean,
        sendPending: Boolean,
        sendInFlight: Boolean,
    ): Verdict = when {
        !wsConnected -> Verdict.Offline
        ended -> Verdict.Ended
        blockingPrompt -> Verdict.PromptOpen
        turning -> Verdict.Turning
        capturing || sendPending || sendInFlight -> Verdict.Busy
        else -> Verdict.Allowed
    }
}

/**
 * The 3 s undo between "the transcript arrived" and "the agent receives it".
 *
 * Deliberately a plain field on the activity rather than a FocusState: it changes nothing about
 * input routing, and one more state would mean one more BACK arm to keep in step.
 *
 * The window exists because a misrecognised dictation goes straight into a coding agent that will
 * act on it. Three seconds and a double tap is the whole cost of not shipping that.
 */
class RcSendWindow {

    var text: String? = null
        private set

    val pending: Boolean get() = text != null

    private var lastTapMs: Long = 0L

    /**
     * Opens the window on a transcript. A blank one is rejected before the window opens at all:
     * a hold that captured nothing must tear down, not offer to send whitespace.
     *
     * @return true when the window is now open.
     */
    fun arm(transcript: String): Boolean {
        val trimmed = transcript.trim()
        if (trimmed.isEmpty()) return false
        text = trimmed
        lastTapMs = 0L
        return true
    }

    /** The window elapsed. @return the text to send, or null when there is nothing to send. */
    fun commit(): String? {
        val out = text
        text = null
        lastTapMs = 0L
        return out
    }

    /** @return true when a pending send was actually cancelled. */
    fun cancel(): Boolean {
        if (text == null) return false
        text = null
        lastTapMs = 0L
        return true
    }

    /**
     * A touchpad tap while the window is open. Cancelling takes a DOUBLE tap, matching the
     * assistant and notification-reply flows: a single tap is far too easy to produce by brushing
     * the temple, and it would silently discard a dictation the user meant to send.
     *
     * @return true when this tap completed a cancel.
     */
    fun tapCancel(now: Long): Boolean {
        if (text == null) {
            // Do not remember a tap made before the window opened; it would let one stray touch
            // plus one deliberate one read as a pair.
            lastTapMs = 0L
            return false
        }
        val previous = lastTapMs
        if (previous != 0L && (now - previous) in DOUBLE_TAP_MIN_MS..DOUBLE_TAP_MAX_MS) {
            return cancel()
        }
        lastTapMs = now
        return false
    }

    companion object {
        /** Matches MainActivity.DOUBLE_TAP_NUMPAD2_MIN_MS / MAX_MS -- the same finger, same pad. */
        const val DOUBLE_TAP_MIN_MS = 40L
        const val DOUBLE_TAP_MAX_MS = 400L

        /** How long the user has to change their mind. Same as the notification-reply window. */
        const val WINDOW_MS = 3000L
    }
}
