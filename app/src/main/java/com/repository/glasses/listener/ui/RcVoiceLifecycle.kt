package com.repository.glasses.listener.ui

/**
 * The ONE owner of an RC dictation's lifetime: the capture identity, the 3 s send window, and --
 * the part that was getting lost -- closing the phone-side voice session on the way out.
 *
 * WHY THIS EXISTS. The teardown used to be hand-copied at each exit in MainActivity, and one of
 * them (`rcCommitSend`) never got its copy. After a successful send the phone kept the voice
 * session open, the glasses kept streaming, the VAD kept emitting empty utterances from room
 * noise, and the recording UI never wore off. Three call sites is how a fourth comes to be missed,
 * so the stop is now emitted here, from a single place, for an ENUMERATED set of exits -- and the
 * test walks that enum rather than the exits someone happened to remember.
 *
 * Pure Kotlin, no Android APIs: the invariant is worth nothing if it can only be checked on a
 * device. [stopVoiceSession] is the single injected side effect.
 */
class RcVoiceLifecycle(
    /**
     * Broadcasts ACTION_RC_VOICE_STOP. Invoked at most once per opened session, never for a
     * session that is not open -- a stray stop would tear down a Telegram or notification-reply
     * capture that a different feature owns.
     */
    private val stopVoiceSession: (reason: String) -> Unit,
) {

    /**
     * Every way a dictation can end. Exhaustive on purpose: the test drives all of them, so a new
     * exit cannot be added without being proven to close the session.
     */
    enum class Exit {
        /** The user or a state change abandoned the capture. */
        CANCEL,

        /** No transcript arrived within the capture timeout. */
        WATCHDOG,

        /** The thread was left while a dictation was running. */
        THREAD_CLOSED,

        /** The session stopped being able to take a message mid-flight (offline, ended, prompt). */
        ABORTED,

        /** BACK pressed during a capture or a pending send. */
        BACK,

        /** The phone delivered a final transcript. */
        FINAL_TRANSCRIPT,

        /** The 3 s window elapsed and the message was sent. */
        SEND_COMMIT,

        /** A double tap (or BACK) withdrew a pending send. */
        SEND_WITHDRAWN,
    }

    private val capture = RcCapture()
    private val window = RcSendWindow()

    /**
     * Whether the phone is currently holding a voice session open for us. Tracked explicitly
     * rather than derived from [active], because the session must survive the moment between the
     * capture ending and the final being processed -- and must NOT be stopped twice.
     */
    var voiceSessionOpen: Boolean = false
        private set

    val active: Boolean get() = capture.active
    val pending: Boolean get() = window.pending
    val text: String? get() = window.text

    /** A capture or an unconfirmed send owns the microphone. Feeds RcVoiceGate's Busy verdict. */
    val busy: Boolean get() = capture.active || window.pending

    /** Opens a capture and, with it, the phone-side voice session. */
    fun start() {
        capture.start()
        voiceSessionOpen = true
    }

    /**
     * A final transcript arrived from the phone.
     *
     * @return true when the 3 s send window is now open. False means the transcript was blank --
     *   a capture that heard nothing -- and there is nothing to offer to send.
     */
    fun onFinalTranscript(text: String, exit: Exit = Exit.FINAL_TRANSCRIPT): Boolean {
        // The session closes either way. A blank final is still the end of the dictation, and
        // leaving the microphone up over it is precisely the live defect.
        closeSession(exit)
        capture.cancelSilently()
        return window.arm(text)
    }

    /** @return true when the arriving transcript belongs to the capture that is running. */
    fun acceptTranscript(): Boolean = capture.acceptTranscript()

    /**
     * Stops a capture without sending, and closes the session. Safe when none is running.
     *
     * @return true when a capture was actually cancelled.
     */
    fun cancelCapture(exit: Exit): Boolean {
        val was = capture.cancel()
        // Cleared alongside: a cancel that left a pending send behind would keep the countdown
        // running over a dictation the user just abandoned.
        window.cancel()
        closeSession(exit)
        return was
    }

    /** Withdraws a pending send. @return true when one was actually withdrawn. */
    fun cancelSendWindow(exit: Exit = Exit.SEND_WITHDRAWN): Boolean {
        val was = window.cancel()
        closeSession(exit)
        return was
    }

    /**
     * The window elapsed.
     *
     * Closes the session BEFORE handing back the text: [onFinalTranscript] normally closed it
     * already, but a send reached without one (a stale posted runnable) must not be the door the
     * leak comes back through.
     *
     * @return the text to send, or null when there is nothing pending.
     */
    fun commit(exit: Exit = Exit.SEND_COMMIT): String? {
        closeSession(exit)
        return window.commit()
    }

    /** A touchpad tap while the window is open. @return true when it completed a withdrawal. */
    fun tapCancel(now: Long): Boolean {
        if (!window.tapCancel(now)) return false
        closeSession(Exit.SEND_WITHDRAWN)
        return true
    }

    /**
     * Drop our capture WITHOUT closing the phone-side voice session.
     *
     * For the one case where another feature (a notification reply, a Telegram voice message) has
     * taken the microphone over on the SAME tag and the SAME channels. Stopping there would tear
     * down THEIR live capture and their transcript would never arrive -- so we forget ours and
     * leave the session to its new owner, who will close it.
     */
    fun forgetCaptureWithoutStopping() {
        // cancel(), which OWES a discard -- deliberately, and after getting this wrong in both
        // directions.
        //
        // The new owner's final carries the same "tg_voice" id as ours and is routed by FOCUS, not
        // by owner. So if the wearer leaves the reply and returns to the thread before it lands,
        // it is delivered into whatever RC capture is running and, without a debt, adopted: the
        // notification reply's words are sent to a coding agent that will act on them.
        //
        // The debt costs one spurious drop in the case where the foreign transcript never arrives.
        // That is a dictation the wearer repeats. The alternative is words they never addressed to
        // the agent being executed by it, which they cannot take back. The recoverable failure is
        // the correct one to choose.
        capture.cancel()
        window.cancel()
        voiceSessionOpen = false
    }

    /** Test hook: arm the window without a capture, to exercise the stale-send path. */
    fun armForTest(text: String): Boolean = window.arm(text)

    private fun closeSession(exit: Exit) {
        if (!voiceSessionOpen) return
        voiceSessionOpen = false
        stopVoiceSession(exit.name)
    }
}
