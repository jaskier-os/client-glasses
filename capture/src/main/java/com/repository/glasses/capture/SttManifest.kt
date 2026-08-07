package com.repository.glasses.capture

import java.util.Locale

/**
 * The model manifest shipped inside the capture APK, describing the QNN context
 * binary that is delivered SEPARATELY (231 MB, far too big for an APK).
 *
 * Because the two halves travel independently they can disagree, and a
 * disagreement is invisible until inference returns nonsense. Every mismatch
 * therefore resolves to "not available" and the utterance goes to the remote
 * transcriber. Nothing here ever tries to REGENERATE the context binary: an
 * on-device prepare takes minutes on a 1.7 GB device and cannot succeed at all
 * when the SoC is the thing that mismatched.
 */
data class SttManifest(
    val modelVersion: String,
    val ctxSha256: String,
    val ctxSizeBytes: Long,
    val qnnVersion: String,
    val socId: Int,
) {

    /**
     * @param exists whether the context binary is on disk.
     * @param sizeBytes its size; checked BEFORE the hash so a half-delivered blob
     *   is rejected without reading 231 MB.
     * @param sha256 its content hash, compared case-insensitively (sha256sum and
     *   MessageDigest disagree on case, and that mismatch would look exactly like
     *   a corrupted download).
     * @param deviceSocId the SoC this device actually is. A context binary is
     *   compiled for one SoC; elsewhere it does not degrade, it fails.
     * @param runtimeQnnVersion the QNN runtime actually bundled. 2.27's
     *   deserializer rejects a 2.47 binary outright with
     *   QNN_CONTEXT_ERROR_BINARY_VERSION, so catching it here saves a 21 s load
     *   that was always going to fail.
     */
    fun matches(
        exists: Boolean,
        sizeBytes: Long,
        sha256: String,
        deviceSocId: Int,
        runtimeQnnVersion: String,
    ): Boolean {
        if (!exists) return false
        if (sizeBytes != ctxSizeBytes) return false
        if (!sha256.equals(ctxSha256, ignoreCase = true)) return false
        if (deviceSocId != socId) return false
        if (runtimeQnnVersion != qnnVersion) return false
        return true
    }

    companion object {
        const val ASSET_PATH = "ml/gigaam/manifest.json"

        /**
         * @return null on anything unreadable. This is parsed during service
         * start; a throw would take the capture process down -- and with it the
         * camera -- for the sake of an optional feature. A manifest missing any
         * field is refused rather than defaulted, because a plausible default is
         * exactly how a partially-written manifest validates.
         */
        fun parse(json: String): SttManifest? = try {
            val o = org.json.JSONObject(json)
            val model = o.getString("modelVersion")
            val sha = o.getString("ctxSha256")
            val size = o.getLong("ctxSizeBytes")
            val qnn = o.getString("qnnVersion")
            val soc = o.getInt("socId")
            if (model.isBlank() || sha.isBlank() || qnn.isBlank() || size <= 0L) null
            else SttManifest(model, sha.lowercase(Locale.ROOT), size, qnn, soc)
        } catch (_: Exception) {
            null
        }
    }
}
