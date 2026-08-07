package com.repository.glasses.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan task 2.3 -- validating the on-device model against the manifest shipped in
 * the APK.
 *
 * The 231 MB QNN context binary is delivered OUT OF BAND (it is far too big to
 * ship in the APK), so the APK and the blob can disagree in ways that are not
 * visible until inference produces nonsense. Every mismatch must resolve to "not
 * available" and route the utterance remotely -- NEVER to a regeneration attempt
 * on the glasses, which would take minutes on a 1.7 GB device and would fail
 * anyway if the SoC did not match.
 *
 * The QNN 2.27-vs-2.47 clash is the concrete precedent: a context binary built
 * with the wrong QAIRT version is rejected by the deserializer at load with
 * QNN_CONTEXT_ERROR_BINARY_VERSION. Catching that in the manifest is cheaper and
 * far clearer than catching it 21 seconds into a cold load.
 */
class SttManifestTest {

    private val good = """
        {"modelVersion":"v3_e2e_rnnt","ctxSha256":"abc123","ctxSizeBytes":242221056,
         "qnnVersion":"2.47","socId":579}
    """.trimIndent()

    @Test
    fun aWellFormedManifestParses() {
        val m = SttManifest.parse(good)!!
        assertEquals("v3_e2e_rnnt", m.modelVersion)
        assertEquals("abc123", m.ctxSha256)
        assertEquals(242221056L, m.ctxSizeBytes)
        assertEquals("2.47", m.qnnVersion)
        assertEquals(579, m.socId)
    }

    @Test
    fun aMalformedManifestIsNullRatherThanThrowing() {
        // This is read during service start. A throw here would take the capture
        // process down and with it the camera, for a feature that is optional.
        assertNull(SttManifest.parse("{not json"))
        assertNull(SttManifest.parse(""))
    }

    @Test
    fun aManifestMissingAFieldIsRefused() {
        // A partially-written manifest must not validate by defaulting the
        // missing field to something plausible.
        assertNull(SttManifest.parse("""{"modelVersion":"v3_e2e_rnnt","socId":579}"""))
    }

    @Test
    fun aBlobMatchingTheManifestIsAvailable() {
        val m = SttManifest.parse(good)!!
        assertTrue(m.matches(exists = true, sizeBytes = 242221056L, sha256 = "abc123",
            deviceSocId = 579, runtimeQnnVersion = "2.47"))
    }

    @Test
    fun aMissingBlobIsUnavailable() {
        val m = SttManifest.parse(good)!!
        assertFalse(m.matches(exists = false, sizeBytes = 242221056L, sha256 = "abc123",
            deviceSocId = 579, runtimeQnnVersion = "2.47"))
    }

    @Test
    fun aBlobTruncatedByOneByteIsUnavailable() {
        // The size check is the cheap one and runs before the hash, so a
        // half-delivered blob is caught without reading 231 MB.
        val m = SttManifest.parse(good)!!
        assertFalse(m.matches(exists = true, sizeBytes = 242221055L, sha256 = "abc123",
            deviceSocId = 579, runtimeQnnVersion = "2.47"))
    }

    @Test
    fun aBlobWithTheRightSizeButTheWrongContentIsUnavailable() {
        val m = SttManifest.parse(good)!!
        assertFalse(m.matches(exists = true, sizeBytes = 242221056L, sha256 = "deadbeef",
            deviceSocId = 579, runtimeQnnVersion = "2.47"))
    }

    @Test
    fun aDifferentSocIsUnavailable() {
        // A context binary is compiled for one SoC. Running it elsewhere is not a
        // degraded result, it is a load failure or garbage.
        val m = SttManifest.parse(good)!!
        assertFalse(m.matches(exists = true, sizeBytes = 242221056L, sha256 = "abc123",
            deviceSocId = 580, runtimeQnnVersion = "2.47"))
    }

    @Test
    fun aDifferentQnnRuntimeIsUnavailable() {
        // 2.27's deserializer rejects a 2.47 context binary outright
        // (QNN_CONTEXT_ERROR_BINARY_VERSION). Catch it here, not 21 s into a load.
        val m = SttManifest.parse(good)!!
        assertFalse(m.matches(exists = true, sizeBytes = 242221056L, sha256 = "abc123",
            deviceSocId = 579, runtimeQnnVersion = "2.27"))
    }

    @Test
    fun theHashComparisonIsCaseInsensitive() {
        // sha256sum and MessageDigest.toHexString disagree on case; a case
        // mismatch would disable the feature permanently and look like a
        // corrupted download.
        val m = SttManifest.parse(
            """{"modelVersion":"v","ctxSha256":"ABC123","ctxSizeBytes":1,"qnnVersion":"2.47","socId":579}"""
        )!!
        assertTrue(m.matches(exists = true, sizeBytes = 1L, sha256 = "abc123",
            deviceSocId = 579, runtimeQnnVersion = "2.47"))
    }
}
