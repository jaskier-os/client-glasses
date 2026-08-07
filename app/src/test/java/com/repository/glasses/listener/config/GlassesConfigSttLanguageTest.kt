package com.repository.glasses.listener.config

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Plan task 1.3 -- the glasses must know the STT language WITHOUT the phone.
 *
 * Today the language lives on the phone only (AppConfig.KEY_STT_LANGUAGE, pushed
 * as "sttLanguage" over CH_SETTINGS). GlassesConfig.applySettings did not parse
 * it at all, so the glasses had no notion of STT language and could never decide
 * to run the local recogniser.
 *
 * Two halves are tested differently and deliberately:
 *
 *  - the PARSE is pure (org.json is on the unit-test classpath precisely so wire
 *    parsers are testable off-device), so it is exercised directly;
 *  - the PERSISTENCE goes through SharedPreferences, which needs a device. What
 *    matters there is not the SharedPreferences API but the invariant that the
 *    value is written in save() and read back in load() under the SAME key. A
 *    key present in only one of the two is silently write-only or read-only, and
 *    the failure mode -- language reverts to "en" after a glasses restart with
 *    the phone away, so Russian speech silently goes remote forever -- is
 *    invisible at runtime. That is asserted against the source.
 */
class GlassesConfigSttLanguageTest {

    private fun parse(json: String, current: String): String =
        SttLanguageSetting.parse(json, current)

    @Test
    fun aPushedRussianLanguageIsParsed() {
        assertEquals("ru", parse("{\"sttLanguage\":\"ru\"}", "en"))
    }

    @Test
    fun anAbsentKeyLeavesTheCachedValueAlone() {
        // The phone pushes CH_SETTINGS for many unrelated reasons. A settings
        // blob that does not mention the language must not reset it to the
        // default, or every unrelated setting change would silently disable
        // local STT.
        assertEquals("ru", parse("{\"model\":\"sonnet\"}", "ru"))
    }

    @Test
    fun malformedJsonLeavesTheCachedValueAlone() {
        assertEquals("ru", parse("{not json", "ru"))
    }

    @Test
    fun anEmptyValueIsRefusedRatherThanCached() {
        // An empty tag would make isRussian() false forever with no way to tell
        // it apart from a deliberate "en".
        assertEquals("ru", parse("{\"sttLanguage\":\"\"}", "ru"))
    }

    @Test
    fun aRegionTaggedValueIsKeptVerbatimForTheRouterToNormalise() {
        // Normalising here would hide what the phone actually sent; SttRouter
        // already compares the primary subtag case-insensitively.
        assertEquals("ru-RU", parse("{\"sttLanguage\":\"ru-RU\"}", "en"))
    }

    @Test
    fun theDefaultMirrorsThePhoneDefault() {
        // The phone's AppConfig default is "en". A never-connected pair must
        // behave identically on both sides.
        assertEquals("en", SttLanguageSetting.DEFAULT)
    }

    @Test
    fun theLanguageIsBothSavedAndReloadedSoItSurvivesThePhoneGoingAway() {
        val src = File(
            "src/main/java/com/repository/glasses/listener/config/GlassesConfig.kt"
        ).readText()
        val save = src.substringAfter("private fun save(ctx: Context)")
            .substringBefore("// --- Translation config")
        val load = src.substringAfter("fun load(ctx: Context)").substringBefore("private fun save(")
        assertEquals(
            "save() must persist the STT language under KEY_STT_LANGUAGE",
            true, save.contains("KEY_STT_LANGUAGE")
        )
        assertEquals(
            "load() must restore the STT language under the SAME key, or the " +
                "glasses forget it whenever the phone is away",
            true, load.contains("KEY_STT_LANGUAGE")
        )
        assertEquals(
            "applySettings must feed the parser",
            true, src.contains("SttLanguageSetting.parse(")
        )
    }
}
