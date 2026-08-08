package com.repository.glasses.listener.stt

import java.util.Locale

/**
 * Decides, once per session, whether speech is transcribed on-glasses or by the
 * phone's remote transcriber.
 *
 * The decision is LATCHED for the whole session and must not re-evaluate
 * mid-utterance: the phone is told the mode BEFORE the session opens, and acts
 * on it by not opening a transcriber stream, not feeding its VAD and not arming
 * the no-speech watchdog. A mid-session flip would leave it half configured.
 *
 * Per-utterance FAILURE is a different axis and still falls back to remote (see
 * the failure matrix): that is handled by the caller, not by re-deciding here.
 *
 * All I/O is behind [State] so the decision table is JVM-testable.
 */
class SttRouter(private val state: State) {

    /** Everything the decision depends on, injected so it can be faked in tests. */
    interface State {
        /** Mirrors the phone's KEY_STT_LANGUAGE, cached on the glasses. */
        val sttLanguage: String

        /** Model present and validated against the manifest. */
        val sttAvailable: Boolean

        /** Video recording owns the NPU. */
        val videoActive: Boolean

        /** A denoise pass owns the NPU. */
        val denoiseActive: Boolean
    }

    enum class Mode { LOCAL, REMOTE }

    companion object {
        /** AI assistant (hold gesture) and the wake-word follow-on utterance. */
        const val TAG_ASSISTANT = "assistant"

        /** Telegram voice, notification reply and RC voice all share this tag. */
        const val TAG_TG_VOICE = "tg_voice"

        /** Only these sessions are in scope for local STT. */
        private val IN_SCOPE = setOf(TAG_ASSISTANT, TAG_TG_VOICE)

        /**
         * The only language the on-glasses model supports. Compared
         * case-insensitively and against the primary subtag, so "RU" and "ru-RU"
         * both count -- the phone pushes whatever its config dropdown holds and a
         * region tag must not silently disable the feature.
         */
        private const val LANG_RU = "ru"

        fun isRussian(tag: String): Boolean =
            tag.lowercase(Locale.ROOT).substringBefore('-') == LANG_RU
    }

    private var sessionTag: String? = null
    private var latched: Mode = Mode.REMOTE

    /**
     * WHY the last decision came out the way it did, in the form the trace
     * prints. A bare LOCAL/REMOTE is not debuggable: four independent inputs can
     * each force REMOTE, and the live incident could not distinguish "wrong
     * language" from "model missing" from "capture process dead". Kept as state
     * rather than logged inside [decide] so the decision table stays pure and
     * JVM-testable.
     */
    var lastReason: String = "no decision yet"
        private set

    /**
     * Open a session and latch its mode.
     *
     * Idempotent while a session is open: a duplicate begin (racing triggers,
     * e.g. a wake word immediately followed by a hold gesture) keeps the first
     * decision rather than re-deciding underneath a live session.
     */
    fun beginSession(tag: String): Mode {
        if (sessionTag != null) {
            lastReason = "kept latched=$latched: session '$sessionTag' already open"
            return latched
        }
        sessionTag = tag
        latched = decide(tag)
        return latched
    }

    /** The latched mode, or REMOTE when no session is open. */
    fun currentMode(): Mode = if (sessionTag == null) Mode.REMOTE else latched

    fun endSession() {
        sessionTag = null
        latched = Mode.REMOTE
    }

    /**
     * Each rejection records the INPUT that caused it, not just which rule
     * fired: "lang" alone still leaves you guessing whether the phone pushed
     * "en" or the config was never populated at all.
     */
    private fun decide(tag: String): Mode {
        if (tag !in IN_SCOPE) {
            lastReason = "tag '$tag' out of scope (in-scope: ${IN_SCOPE.joinToString(",")})"
            return Mode.REMOTE
        }
        val lang = state.sttLanguage
        if (!isRussian(lang)) {
            lastReason = "sttLanguage='$lang' is not '$LANG_RU'"
            return Mode.REMOTE
        }
        if (!state.sttAvailable) {
            lastReason = "sttAvailable=false (capture reports no usable model)"
            return Mode.REMOTE
        }
        if (state.videoActive) {
            lastReason = "videoActive=true (recording owns the NPU)"
            return Mode.REMOTE
        }
        if (state.denoiseActive) {
            lastReason = "denoiseActive=true (denoise owns the NPU)"
            return Mode.REMOTE
        }
        lastReason = "tag='$tag' lang='$lang' available=true npu=free"
        return Mode.LOCAL
    }
}
