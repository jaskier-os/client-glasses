package com.repository.glasses.listener.input.remote

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 verification of remote input frames.
 *
 * The tag is computed by the SOURCE DEVICE (the watch), not by the relaying phone, so a forged
 * phone-local intent cannot mint a valid one.
 *
 * ## The key is per-source, not global
 * Each [InputSource] supplies its own key. One shared key would mean compromising any future device
 * compromises all of them, and it would make this class need editing every time a device is added.
 * It also means tests inject their own key and the real one never appears in a test vector.
 *
 * ## What this actually buys us -- stated honestly
 * The watch's key rides in `BuildConfig` from a gitignored `local.properties`. On a rooted,
 * SELinux-permissive, adb-reachable device -- which is exactly what these glasses are -- extracting
 * a symmetric key from an installed APK is trivial. So the HMAC defends against:
 *  - a bonded-but-unauthorized Bluetooth peer that opens the input UUID and injects frames,
 *  - a passive sniffer who tries to synthesize NEW input,
 *  - any local app on the phone forging a Wear Data Layer intent.
 * It defends against NOTHING once an attacker has adb on either device. Per-device keys negotiated
 * at pairing and stored in `GlassesConfig` are the correct long-term answer and are deliberate
 * follow-up work, not shipped here.
 *
 * ## Replay
 * Replay resistance currently comes from the router's monotonic-sequence rule alone, which does NOT
 * cover an attacker who captures a whole session (OPEN plus its events) and replays it verbatim
 * later: the OPEN resets the sequence baseline and re-baselines the age clock. Closing that hole
 * needs a glasses-issued per-connection nonce folded into the signed string, which is a wire
 * contract change and is therefore not made unilaterally here. [canonicalMessage] already carries
 * the nonce field so adding it is a one-line change on both sides; today it is the empty string.
 */
class RemoteInputAuth(key: ByteArray) {

    /**
     * Absent or too-short key -> fail closed. Every verification returns false rather than
     * silently trusting. A one-byte or whitespace key is treated as unconfigured: accepting it
     * would give the appearance of authentication with none of the substance.
     */
    private val keySpec: SecretKeySpec? =
        if (key.size < MIN_KEY_BYTES) null else SecretKeySpec(key, HMAC_ALGORITHM)

    val isConfigured: Boolean get() = keySpec != null

    /**
     * Constant-time verification of [tagHex] over [message].
     *
     * Returns false for a missing key, a malformed tag, or any crypto failure. Never throws:
     * this runs on a transport callback thread where an uncaught exception kills the service.
     */
    fun verify(message: String, tagHex: String): Boolean {
        val spec = keySpec ?: return false
        if (tagHex.length != TAG_HEX_CHARS) return false
        val provided = decodeHex(tagHex) ?: return false
        return try {
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            mac.init(spec)
            val full = mac.doFinal(message.toByteArray(Charsets.UTF_8))
            // MessageDigest.isEqual is documented constant-time for equal-length inputs.
            MessageDigest.isEqual(full.copyOf(TAG_BYTES), provided)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Produce the tag this verifier would accept for [message]. Test and golden-vector tooling
     * only -- nothing on the receive path signs anything, and unlike [verify] this can throw.
     */
    @androidx.annotation.VisibleForTesting
    internal fun sign(message: String): String {
        val spec = keySpec ?: return ""
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(spec)
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
            .copyOf(TAG_BYTES)
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Strict ASCII hex. Deliberately not `Character.digit`, which also resolves Unicode decimal
     * digits (fullwidth, Devanagari, ...) and would make more than one string decode to the same
     * tag, destroying the canonicality the wire format depends on.
     */
    private fun decodeHex(s: String): ByteArray? {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = hexNibble(s[i * 2])
            val lo = hexNibble(s[i * 2 + 1])
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun hexNibble(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    companion object {
        private const val HMAC_ALGORITHM = "HmacSHA256"

        /**
         * Truncation length. 64 bits against an online attacker who is rate-limited to tens of
         * frames per second is an expected ~2^63 attempts; the limit is not the tag width.
         */
        const val TAG_BYTES = 8
        const val TAG_HEX_CHARS = TAG_BYTES * 2

        /** Domain tag for input events. Prevents a captured event tag being replayed as a status. */
        const val DOMAIN_EVENT = "ri1:evt"

        /**
         * Shorter than this and the key is treated as absent. 16 bytes is the floor for a value
         * that is supposed to resist offline guessing.
         */
        const val MIN_KEY_BYTES = 16

        /**
         * Render a uint32 wire field for the digest: plain decimal in `0..4294967295`, no sign, no
         * leading zeros.
         *
         * `sid`, `seq` and `wms` are unsigned 32-bit on the wire. A sender holding them in a signed
         * 32-bit int renders `-1234` where a receiver holding them in a 64-bit long renders
         * `4294966062`, and the two digests never match. `wms` (a monotonic clock's low 32 bits)
         * crosses the sign bit routinely, so this is a matter of when, not if. Both sides MUST run
         * their value through this function.
         */
        fun u32(value: Long): String = (value and 0xFFFFFFFFL).toString()

        /**
         * Render the `steps` field for the digest: plain signed decimal, e.g. `3`, `-3`, `0`.
         * Never a leading `+`, never leading zeros. The sign carries the direction (positive =
         * forward/down); it is not a literal character in the wire format.
         */
        fun steps(value: Int): String = value.toString()

        /**
         * The canonical signed string. Both sides MUST build it with this exact function.
         *
         * Length-prefixed rather than a bare `"a|b|c"` join: with a plain separator the only
         * variable-length field (`src`) could move bytes across a field boundary and produce the
         * same digest for two different frames. Every field here is preceded by its byte length, so
         * the encoding is injective.
         *
         * Field rendering is pinned, because every one of these is a silent cross-device desync
         * waiting to happen:
         * - [sid], [seq], [wms] are rendered by [u32]: unsigned decimal, no sign, no leading zeros.
         * - [steps] is rendered by [steps]: signed decimal, no leading `+`.
         * - [type] is the wire NAME (`SCROLL`, `SELECT`, `BACK`, `OPEN`, `CLOSE`, `PING`), never the
         *   numeric opcode the watch->phone encoding uses on the wire. A sender that signs `"1"`
         *   where the receiver signs `"SCROLL"` gets every frame rejected with no clue why.
         *
         * [nonce] is reserved for the glasses-issued per-connection challenge that will close the
         * session-replay hole. It is the empty string in v1 and MUST be empty on both sides until
         * the wire contract is revised together with Workstream B. When it becomes non-empty the
         * domain tag must move to `ri2:evt` so a v1 tag can never be replayed into v2.
         */
        fun canonicalMessage(
            v: Int,
            src: String,
            sid: Long,
            seq: Long,
            type: String,
            steps: Int,
            wms: Long,
            nonce: String = "",
        ): String {
            val sb = StringBuilder(96)
            sb.append(DOMAIN_EVENT)
            for (field in listOf(
                v.toString(), src, u32(sid), u32(seq), type, steps(steps), u32(wms), nonce,
            )) {
                // UTF-8 byte length, not char count, so the prefix is unambiguous for any input.
                sb.append('|').append(field.toByteArray(Charsets.UTF_8).size).append(':').append(field)
            }
            return sb.toString()
        }
    }
}
