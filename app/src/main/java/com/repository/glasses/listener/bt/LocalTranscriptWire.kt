package com.repository.glasses.listener.bt

/**
 * Encoding of a locally-produced transcript on its way to the phone, and of the
 * mode announcement that precedes the session.
 *
 * Kept as pure functions on BOTH devices, with identical tests, because the two
 * halves are in separate repositories and nothing else forces them to agree.
 *
 * The decoders NEVER throw and NEVER fail open. They run on a Binder thread,
 * where an uncaught exception kills the service, and a garbled frame that
 * decoded as a successful transcript would put arbitrary text in front of the
 * wearer as though the recogniser had produced it.
 */
object LocalTranscriptWire {

    /** ["ok"|"fail"] -- did local recognition produce a final at all. */
    const val STATUS_OK = "ok"
    const val STATUS_FAIL = "fail"

    /**
     * @param text the final transcript. "" is MEANINGFUL and must survive as an
     *   empty string: it is the wearer cancelling, and the phone clears a pending
     *   notification reply on it. Collapsing it to a missing argument leaves that
     *   reply hanging in SENDING forever.
     */
    data class Message(val tag: String, val status: String, val text: String) {
        /** Only an exact "ok" counts. Anything else is a failure, never a success. */
        val isOk: Boolean get() = status == STATUS_OK
    }

    fun encode(tag: String, status: String, text: String): List<String> =
        listOf(tag, status, text)

    /** @return null when the frame is too short to interpret. Extra args are ignored. */
    fun decode(args: List<String>): Message? {
        if (args.size < 3) return null
        return Message(args[0], args[1], args[2])
    }
}

/**
 * The glasses tell the phone which recogniser will handle the coming session,
 * BEFORE any audio flows, so the phone can decline to open its transcriber
 * stream at all.
 */
object SttModeWire {

    const val MODE_LOCAL = "local"
    const val MODE_REMOTE = "remote"

    data class Message(val mode: String, val sessionTag: String) {
        /**
         * Only an exact "local" hands the session over. Anything unrecognised
         * means the phone keeps doing exactly what it does today -- failing the
         * other way would leave NOBODY transcribing.
         */
        val isLocal: Boolean get() = mode == MODE_LOCAL
    }

    fun encode(mode: String, sessionTag: String): List<String> = listOf(mode, sessionTag)

    fun decode(args: List<String>): Message? {
        if (args.size < 2) return null
        return Message(args[0], args[1])
    }
}
