package com.repository.glasses.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two invariants a mutation audit found NO test was guarding.
 *
 * Both are silent-failure shaped: neither throws, neither logs anything unusual,
 * and both simply make the on-glasses recogniser refuse forever while the
 * remote path quietly keeps working -- so nobody would notice until someone
 * asked why local STT never engages.
 */
class SttLanguageAdmissionTest {

    // ---- the language gate ----

    @Test
    fun plainRussianIsAccepted() {
        assertTrue(GigaAmStt.isRussian("ru"))
    }

    @Test
    fun aRegionTaggedRussianIsAccepted() {
        // The phone pushes whatever its config dropdown holds. A region subtag
        // must not silently disable the feature, which is exactly what comparing
        // the whole tag would do.
        assertTrue(GigaAmStt.isRussian("ru-RU"))
        assertTrue(GigaAmStt.isRussian("ru_RU".replace('_', '-')))
    }

    @Test
    fun caseDoesNotMatter() {
        assertTrue(GigaAmStt.isRussian("RU"))
        assertTrue(GigaAmStt.isRussian("Ru-ru"))
    }

    @Test
    fun otherLanguagesAreRefused() {
        assertFalse(GigaAmStt.isRussian("en"))
        assertFalse(GigaAmStt.isRussian("en-US"))
        // "rus" is a different tag, not a longer spelling of "ru": accepting a
        // prefix match would also accept unrelated tags.
        assertFalse(GigaAmStt.isRussian("rus"))
    }

    @Test
    fun anAbsentOrEmptyLanguageIsRefusedRatherThanAssumedRussian() {
        // A missing tag means the caller did not say. Guessing Russian would run
        // a Russian model over English speech and return confident nonsense.
        assertFalse(GigaAmStt.isRussian(null))
        assertFalse(GigaAmStt.isRussian(""))
    }

    // ---- manifest field sanity ----

    @Test
    fun aManifestClaimingAZeroSizedBlobIsRefused() {
        // A zero size would make the size check pass against a zero-length file,
        // handing a truncated download to the QNN deserializer.
        assertNull(SttManifest.parse(
            """{"modelVersion":"v","ctxSha256":"a","ctxSizeBytes":0,"qnnVersion":"2.47","socId":579}"""
        ))
    }

    @Test
    fun aManifestClaimingANegativeSizeIsRefused() {
        assertNull(SttManifest.parse(
            """{"modelVersion":"v","ctxSha256":"a","ctxSizeBytes":-1,"qnnVersion":"2.47","socId":579}"""
        ))
    }

    @Test
    fun aManifestWithABlankHashIsRefused() {
        // A blank hash would compare equal to a blank computed hash on any read
        // failure path, validating a blob nobody ever checked.
        assertNull(SttManifest.parse(
            """{"modelVersion":"v","ctxSha256":"","ctxSizeBytes":1,"qnnVersion":"2.47","socId":579}"""
        ))
    }
}
