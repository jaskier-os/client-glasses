package com.repository.glasses.listener.config

/**
 * Parsing of the "sttLanguage" field the phone pushes over CH_SETTINGS.
 *
 * Split out of GlassesConfig because GlassesConfig needs a Context and this does
 * not: the parse is the part that decides whether Russian speech is recognised
 * on the glasses or shipped to the phone, so it is the part that must be tested.
 *
 * Every refusal path returns the CURRENT value rather than the default. A
 * settings blob that omits the field, or one that fails to parse, means "no
 * opinion", not "reset to English" -- the phone pushes CH_SETTINGS for many
 * unrelated reasons, and resetting on each would silently disable local STT.
 */
object SttLanguageSetting {

    /** Mirrors the phone's AppConfig default, so an unpaired device matches it. */
    const val DEFAULT = "en"

    const val KEY = "sttLanguage"

    /**
     * @param json a CH_SETTINGS payload.
     * @param current the cached language.
     * @return the new language, or [current] when the payload has no usable
     *   opinion. The value is kept VERBATIM (region tag included); normalising
     *   is the router's job and doing it here would hide what the phone sent.
     */
    fun parse(json: String, current: String): String = try {
        val obj = org.json.JSONObject(json)
        val v = if (obj.has(KEY)) obj.optString(KEY, "") else ""
        if (v.isBlank()) current else v
    } catch (_: Exception) {
        current
    }
}
