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
 * Replay resistance does NOT come from this class. A captured frame carries a valid tag forever.
 * It comes from the router's persisted monotonic session id plus its persisted sequence floor
 * (see `SessionStore`), which together make a captured burst unreplayable even across a reboot.
 * A challenge-response nonce was considered and rejected: it adds a round trip to the input path
 * and does not help the only residual case, a receiver that legitimately loses its state.
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
         * The canonical signed string: `v|src|sid|seq|typeCode|steps|wms`.
         *
         * Frozen by the wire contract and produced by the source device, so it is NOT negotiable
         * here -- the glasses must reproduce it byte for byte or nothing verifies. Checked against
         * the cross-repo golden vectors so drift on either side fails a test rather than the
         * hardware.
         *
         * Field rendering is pinned, because each of these is a silent cross-device desync waiting
         * to happen and all of them fail identically (every frame rejected, no diagnostic):
         * - [sid], [seq], [wms] are rendered by [u32]: unsigned decimal, no sign, no leading zeros.
         *   A sender holding `wms` in a signed 32-bit int would otherwise render `-1234` where the
         *   receiver renders `4294966062`, and `wms` crosses the sign bit routinely.
         * - [steps] is rendered by [steps]: signed decimal, no leading `+`, no leading zeros.
         * - [typeCode] is the NUMERIC opcode (`1=SCROLL 2=SELECT 3=BACK 4=OPEN 5=CLOSE 6=PING`), the
         *   same value the binary encoding carries, never the readable name.
         *
         * `src` is the only variable-length field, so a bare separator join is not injective in
         * general: bytes could be moved across a field boundary. That is contained instead by
         * validating `src` against [InputSource.SOURCE_ID_PATTERN] BEFORE hashing, which excludes
         * the separator entirely. Validate first; never hash an unvalidated source id.
         */
        fun canonicalMessage(
            v: Int,
            src: String,
            sid: Long,
            seq: Long,
            typeCode: Int,
            steps: Int,
            wms: Long,
        ): String = "$v|$src|${u32(sid)}|${u32(seq)}|$typeCode|${steps(steps)}|${u32(wms)}"
    }
}
