package com.repository.glasses.listener.input.remote

/**
 * Decoder for the `CH_REMOTE_INPUT` string-arg wire format.
 *
 * Separated from [WatchRelaySource] so it can be tested directly against the cross-repo golden
 * vectors, including the REJECT vectors. Accept-only vectors cannot catch a validation mismatch --
 * a receiver enforcing the wrong step cap reproduces every valid vector and passes.
 *
 * Every path is total: this runs on a Bluetooth transport callback thread, where an uncaught
 * exception on one malformed frame kills the whole service. Nothing here throws.
 */
object RemoteInputCodec {

    /** Wire opcodes. These, not the readable names, are what the HMAC covers. */
    const val TYPE_SCROLL = 1
    const val TYPE_SELECT = 2
    const val TYPE_BACK = 3
    const val TYPE_OPEN = 4
    const val TYPE_CLOSE = 5
    const val TYPE_PING = 6

    const val PROTOCOL_VERSION = 1

    /**
     * Maximum coalesced detents in one SCROLL.
     *
     * Enforced, never trusted: a producer is expected to chunk larger movements. Measured hardware
     * yields 1-4 with 8 as headroom, so anything above this is a bug or an attack, not a fast wrist.
     */
    const val MAX_STEPS = 8

    /** Minimum arg count. Extra trailing args are ignored for forward compatibility within v1. */
    const val MIN_ARGS = 8

    private const val IDX_VERSION = 0
    private const val IDX_SRC = 1
    private const val IDX_SID = 2
    private const val IDX_SEQ = 3
    private const val IDX_TYPE = 4
    private const val IDX_STEPS = 5
    private const val IDX_WMS = 6
    private const val IDX_TAG = 7

    private const val U32_MAX = 0xFFFFFFFFL

    sealed interface Result {
        data class Ok(val frame: RemoteInputFrame) : Result
        data class Rejected(val reason: String) : Result
    }

    /**
     * Decode and authenticate one frame.
     *
     * Validation order matters and is deliberate: shape, then the source id, then everything cheap
     * and structural, and only THEN the HMAC. Verifying first would let an unauthenticated peer
     * force a SHA-256 per frame at whatever rate it can write to the socket.
     */
    fun decode(args: List<String>, expectedSourceId: String, auth: RemoteInputAuth): Result {
        try {
            if (args.size < MIN_ARGS) return Result.Rejected("arity ${args.size} < $MIN_ARGS")

            val version = args[IDX_VERSION].toIntOrNull()
                ?: return Result.Rejected("version not an integer")
            if (version != PROTOCOL_VERSION) return Result.Rejected("version $version unsupported")

            val src = args[IDX_SRC]
            // Validated BEFORE it reaches the digest: the canonical string is separator-joined, so
            // an unconstrained source id could move bytes across a field boundary.
            if (!InputSource.isValidSourceId(src)) return Result.Rejected("malformed source id")
            if (src != expectedSourceId) return Result.Rejected("source id mismatch")

            val sid = parseU32(args[IDX_SID]) ?: return Result.Rejected("malformed sid")
            val seq = parseU32(args[IDX_SEQ]) ?: return Result.Rejected("malformed seq")
            val wms = parseU32(args[IDX_WMS]) ?: return Result.Rejected("malformed wms")

            val typeCode = parseTypeCode(args[IDX_TYPE])
                ?: return Result.Rejected("unknown type '${args[IDX_TYPE]}'")

            val steps = parseSteps(args[IDX_STEPS]) ?: return Result.Rejected("malformed steps")

            if (typeCode == TYPE_SCROLL) {
                if (steps == 0) return Result.Rejected("SCROLL with zero steps")
                // Kotlin's abs(Int.MIN_VALUE) is negative, so a naive magnitude check passes the
                // most extreme value of all. Compare the range directly instead.
                if (steps > MAX_STEPS || steps < -MAX_STEPS) {
                    return Result.Rejected("steps $steps outside +/-$MAX_STEPS")
                }
            } else if (steps != 0) {
                return Result.Rejected("non-SCROLL type $typeCode carries steps $steps")
            }

            val message = RemoteInputAuth.canonicalMessage(
                version, src, sid, seq, typeCode, steps, wms,
            )
            if (!auth.verify(message, args[IDX_TAG])) return Result.Rejected("bad tag")

            val frame = when (typeCode) {
                TYPE_SCROLL ->
                    RemoteInputFrame.Action(version, src, sid, seq, wms, RemoteAction.SCROLL_STEP, steps)
                TYPE_SELECT ->
                    RemoteInputFrame.Action(version, src, sid, seq, wms, RemoteAction.SELECT, 0)
                TYPE_BACK ->
                    RemoteInputFrame.Action(version, src, sid, seq, wms, RemoteAction.BACK, 0)
                TYPE_OPEN ->
                    RemoteInputFrame.Lifecycle(version, src, sid, seq, wms, RemoteLifecycle.OPEN)
                TYPE_CLOSE ->
                    RemoteInputFrame.Lifecycle(version, src, sid, seq, wms, RemoteLifecycle.CLOSE)
                TYPE_PING ->
                    RemoteInputFrame.Lifecycle(version, src, sid, seq, wms, RemoteLifecycle.PING)
                else -> return Result.Rejected("unknown type code $typeCode")
            }
            return Result.Ok(frame)
        } catch (e: Exception) {
            // A malformed frame must never propagate out of a transport callback.
            return Result.Rejected("decode failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Accept either the readable name or the numeric opcode on the wire, but note the HMAC always
     * covers the NUMERIC form.
     */
    fun parseTypeCode(raw: String): Int? = when (raw) {
        "SCROLL" -> TYPE_SCROLL
        "SELECT" -> TYPE_SELECT
        "BACK" -> TYPE_BACK
        "OPEN" -> TYPE_OPEN
        "CLOSE" -> TYPE_CLOSE
        "PING" -> TYPE_PING
        else -> raw.toIntOrNull()?.takeIf { it in TYPE_SCROLL..TYPE_PING }
    }

    /**
     * Strict unsigned 32-bit decimal: no sign, no leading zeros, no whitespace, in range.
     *
     * `toLongOrNull` alone would accept `+5`, `-1` and `007`, all of which produce a different
     * canonical string on the sender's side and so fail only at the HMAC -- the least diagnosable
     * possible failure.
     */
    fun parseU32(raw: String): Long? {
        if (raw.isEmpty() || raw.length > 10) return null
        if (raw.any { it !in '0'..'9' }) return null
        if (raw.length > 1 && raw[0] == '0') return null
        val value = raw.toLongOrNull() ?: return null
        return if (value in 0..U32_MAX) value else null
    }

    /** Strict signed decimal: an optional leading `-`, no `+`, no leading zeros, no whitespace. */
    fun parseSteps(raw: String): Int? {
        if (raw.isEmpty()) return null
        val negative = raw[0] == '-'
        val digits = if (negative) raw.substring(1) else raw
        if (digits.isEmpty() || digits.length > 10) return null
        if (digits.any { it !in '0'..'9' }) return null
        if (digits.length > 1 && digits[0] == '0') return null
        if (negative && digits == "0") return null   // "-0" is not canonical
        return raw.toIntOrNull()
    }
}
