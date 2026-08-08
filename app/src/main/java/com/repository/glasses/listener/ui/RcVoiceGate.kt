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
        /**
         * The wording a Telegram chat uses for the same gesture on the same hardware. Icons alone
         * did not teach it: the wearer could see a microphone but not learn how to reach it.
         */
        Allowed(true, "Tap to record message"),

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
 * Identity for one dictation.
 *
 * The phone finalises an utterance asynchronously, so a transcript can land after the user has
 * abandoned the capture it belongs to. Without an identity that late transcript would arm the NEXT
 * capture's send window and ship words the user explicitly cancelled straight into a coding agent.
 * Each capture therefore carries a token, and only the running capture's own token is accepted.
 */
class RcCapture {

    var active: Boolean = false
        private set

    /**
     * When each abandoned capture's transcript is still worth discarding, newest last.
     *
     * The phone's finals carry no id of their own and arrive in order, so counting the abandoned
     * ones is what tells them apart. Each debt also carries a DEADLINE, because it guards a short
     * race -- the seconds between abandoning a capture and its in-flight final landing -- and a
     * debt held past that eats a dictation the wearer makes much later instead.
     */
    private val owed = ArrayDeque<Long>()

    /**
     * Drop debts whose in-flight transcript can no longer be arriving.
     *
     * Sweeps the WHOLE deque, not just the head. Deadlines are absolute and the two TTLs differ by
     * a factor of five, so the deque is NOT sorted: a 20 s handover debt recorded first sits in
     * front of a 4 s abandon debt recorded second. A head-only sweep would stop at the handover,
     * strand the expired abandon behind it, and then let acceptTranscript pay the WRONG debt --
     * eating a legitimate dictation and restarting the self-sustaining loop.
     *
     * Payment order stays FIFO; only expiry is per-entry.
     */
    private fun expire(now: Long) {
        owed.removeAll { it <= now }
    }

    /** Begins a capture. A still-running one is abandoned, and owes a discard like any other. */
    fun start(now: Long) {
        if (active) cancel(now)
        expire(now)
        active = true
    }

    /**
     * The user abandoned this capture, or the watchdog gave up on it. The phone may still deliver
     * its transcript, so one is owed a discard.
     *
     * @return true when a capture was actually running.
     */
    fun cancel(now: Long, ttlMs: Long = DEFAULT_DISCARD_TTL_MS): Boolean {
        if (!active) return false
        active = false
        expire(now)
        owed.addLast(now + ttlMs)
        // Over the cap, the OLDEST-RECORDED debt is evicted -- by insertion order, which is the
        // head, NOT by earliest deadline.
        //
        // Evicting the earliest deadline looks equivalent and is not: the two TTLs differ by a
        // factor of five, so a freshly-recorded 4 s abandon has an earlier deadline than three
        // handovers recorded seconds before it and would evict ITSELF the moment it was added,
        // leaving that abandoned capture owing nothing and its late final adoptable. Insertion
        // order cannot do that -- the incoming debt is always the newest.
        while (owed.size > MAX_PENDING_DISCARDS) owed.removeFirst()
        return true
    }

    /**
     * Ends the capture WITHOUT owing a discard.
     *
     * Used when the capture's own final has arrived: the transcript this capture was going to
     * produce is the one in hand, so there is no later one to discard. Owing a discard here would
     * make the NEXT capture's legitimate transcript be thrown away.
     */
    fun cancelSilently() {
        active = false
    }

    /** Outstanding debts, after expiring any whose deadline has passed. */
    fun owedDiscards(now: Long): Int {
        expire(now)
        return owed.size
    }

    /**
     * A final transcript arrived.
     *
     * @return true when it belongs to the capture that is running -- and therefore may be sent.
     *         False means it belonged to an abandoned capture, or to no capture at all, and must
     *         be dropped on the floor.
     */
    fun acceptTranscript(now: Long): Boolean {
        expire(now)
        if (owed.isNotEmpty()) {
            owed.removeFirst()
            // The running capture stays ACTIVE. It is a different capture from the abandoned one
            // whose final this was, and its own final is still to come -- so tearing it down here
            // would deafen a capture the wearer is actively speaking into.
            return false
        }
        if (!active) return false
        active = false
        return true
    }

    companion object {
        /**
         * Repeated hold-and-abandon must not build a debt that deafens the thread indefinitely:
         * the phone drops most abandoned utterances outright (no speech detected), so a large debt
         * is far more likely to be wrong than right.
         */
        const val MAX_PENDING_DISCARDS = 3

        /**
         * How long an ABANDONED capture's transcript may still be arriving.
         *
         * READ THIS BEFORE CHANGING IT. Six audit rounds oscillated over this number because the
         * two bounds nearly touch and one of them is not a real bound at all:
         *
         *  - lower: the phone's VAD silence window (1.5 s) plus transcription. Transcription has
         *    NO upper limit -- it is a network call with a 120 s read timeout -- so on a slow link
         *    there is no value that covers it.
         *  - upper: the fastest possible re-dictation, ~4.5 s (instant re-hold, one word, the same
         *    tail). A debt outliving that eats a legitimate utterance.
         *
         * So a TTL cannot be correct in general, and the design does not ask it to be. What makes
         * this safe is that the two failures are NOT symmetric, and the expiry only decides which
         * one a rare race produces:
         *
         *  - too short: a very late final is adopted. But MainActivity gates on focus as well, and
         *    the wearer sees the words in the 3 s countdown before they are sent, and can withdraw.
         *  - too long: a legitimate dictation is silently eaten and, before the loop was fixed,
         *    the thread went deaf forever.
         *
         * 4 s covers the ordinary case (measured ~3.5 s) and stays under the fastest re-dictation.
         * The countdown is the real backstop for the tail; do not try to make this number cover it.
         *
         * The structurally correct fix is to put the capture token on the wire so the phone echoes
         * it on the final and the match is exact -- no clock, no deque, no cap. That needs a phone
         * change (CH_GLASSES_USER_TEXT carries a shared literal "tg_voice" for all three owners
         * today) and is the right next step if this ever misbehaves again.
         */
        const val DEFAULT_DISCARD_TTL_MS = 4_000L

        /**
         * How long a HANDED-OVER capture's transcript may still be arriving.
         *
         * Much longer, because the clock starts somewhere completely different. A handover is
         * recorded the instant another feature takes the microphone -- BEFORE the wearer has
         * spoken a word of their notification reply. What remains is the whole foreign utterance
         * (5-10 s is ordinary) plus the same ~3.5 s tail.
         *
         * Being long is also SAFE here in a way it is not for an abandon. A handover happens
         * because the wearer left the thread for something else, so they are not standing there
         * re-dictating into it. And if the debt does cost them a repeat, that is recoverable,
         * whereas a notification reply delivered to a coding agent that acts on it is not.
         */
        const val HANDOVER_DISCARD_TTL_MS = 20_000L
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
        // A tap made while no window is open is not remembered at all -- otherwise one stray touch
        // of the temple plus one deliberate one would read as a pair. Nothing to reset here: arm,
        // commit and cancel all leave the chain cleared, so it is already 0 in this branch.
        if (text == null) return false
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
