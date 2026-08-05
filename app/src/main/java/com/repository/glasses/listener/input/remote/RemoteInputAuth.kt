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

    /** Blank key -> fail closed. Every verification returns false rather than silently trusting. */
    private val keySpec: SecretKeySpec? =
        if (key.isEmpty()) null else SecretKeySpec(key, HMAC_ALGORITHM)

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

    /** Test/tooling helper: produce the tag this verifier would accept for [message]. */
    fun sign(message: String): String {
        val spec = keySpec ?: return ""
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(spec)
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
            .copyOf(TAG_BYTES)
            .joinToString("") { "%02x".format(it) }
    }

    private fun decodeHex(s: String): ByteArray? {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(s[i * 2], 16)
            val lo = Character.digit(s[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
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

        /** Source ids are constrained so they cannot smuggle a field separator into the digest. */
        private val SOURCE_ID_RE = Regex("^[a-z0-9_]{1,16}$")

        fun isValidSourceId(src: String): Boolean = SOURCE_ID_RE.matches(src)

        /**
         * The canonical signed string. Both sides MUST build it with this exact function.
         *
         * Length-prefixed rather than a bare `"a|b|c"` join: with a plain separator the only
         * variable-length field (`src`) could move bytes across a field boundary and produce the
         * same digest for two different frames. Every field here is preceded by its byte length, so
         * the encoding is injective.
         *
         * [nonce] is reserved for the glasses-issued per-connection challenge that will close the
         * session-replay hole. It is the empty string in v1 and MUST be empty on both sides until
         * the wire contract is revised together with Workstream B.
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
                v.toString(), src, sid.toString(), seq.toString(), type, steps.toString(),
                wms.toString(), nonce,
            )) {
                // UTF-8 byte length, not char count, so the prefix is unambiguous for any input.
                sb.append('|').append(field.toByteArray(Charsets.UTF_8).size).append(':').append(field)
            }
            return sb.toString()
        }
    }
}
