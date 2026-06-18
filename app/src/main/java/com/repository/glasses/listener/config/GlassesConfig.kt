package com.repository.glasses.listener.config

import android.content.Context
import android.content.Intent

object GlassesConfig {

    private const val PREFS = "glasses_config"

    const val ACTION_GLASSES_CONFIG_CHANGED = "com.repository.glasses.listener.action.GLASSES_CONFIG_CHANGED"
    const val EXTRA_BRIGHTNESS = "brightness"
    const val EXTRA_SCREEN_TIMEOUT_S = "screen_timeout_s"
    const val EXTRA_POWER_TIMEOUT_MIN = "power_timeout_min"

    // Translation config persistence keys
    private const val KEY_TRANSLATION_FROM_LANG = "translation_from_language"
    private const val KEY_TRANSLATION_TO_LANG = "translation_to_language"
    private const val KEY_TRANSLATION_FONT_SIZE = "translation_font_size"
    private const val KEY_TRANSLATION_AUDIO_SOURCE = "translation_audio_source"
    private const val KEY_TRANSLATION_PROVIDER = "translation_provider"
    private const val KEY_TRANSLATION_TWO_WAY = "translation_two_way"

    // Last-used assistant config persistence keys. Reused when the assistant is
    // started from the glasses so the most recent configuration (pushed by the
    // phone on the previous start) is honored instead of hardcoded defaults.
    private const val KEY_ASSISTANT_WEARER_LANG = "assistant_wearer_lang"
    private const val KEY_ASSISTANT_INTERLOCUTOR_LANG = "assistant_interlocutor_lang"
    private const val KEY_ASSISTANT_INTERLOCUTOR_SOURCE = "assistant_interlocutor_source"
    private const val KEY_ASSISTANT_MODEL = "assistant_model"

    @Volatile var model: String = "sonnet"
    @Volatile var deviceId: String = "glasses-01"
    @Volatile var notificationDurationMs: Long = 5000L
    @Volatile var notificationSoundEnabled: Boolean = true
    @Volatile var bottomPaddingPx: Int = 0
    @Volatile var chatFontSize: Float = 14f
    @Volatile var brightness: Int = 8
    @Volatile var screenTimeoutSec: Int = 300
    @Volatile var powerTimeoutMin: Int = 60
    @Volatile var wakewordEnabled: Boolean = false
    @Volatile var alwaysRecordEnabled: Boolean = true
    @Volatile var onDemandRecordingActive: Boolean = false
    @Volatile var batteryPct: Int = 100
    @Volatile var voiceControl: String = ""        // "on" | "off" | ""
    @Volatile var longPressFun: String = "audio"   // "picture" | "video" | "audio"
    // Phone-controlled gate for the sideload-through-phone deploy path. When true the
    // filesync HTTP server accepts the POST /sideload/* routes and the CH_SIDELOAD BT
    // channel will open WiFi Direct. Defaults OFF; only flipped on by the phone for a
    // deploy session.
    @Volatile var sideloadingEnabled: Boolean = false

    fun applySettings(ctx: Context, json: String) {
        try {
            val obj = org.json.JSONObject(json)
            if (obj.has("model")) model = obj.getString("model")
            if (obj.has("deviceId")) deviceId = obj.getString("deviceId")
            if (obj.has("settings_msg_notification_display_duration")) {
                val dur = obj.getString("settings_msg_notification_display_duration")
                dur.toLongOrNull()?.let { sec ->
                    // Phone sends seconds ("3", "5", "10", "15")
                    val ms = if (sec in 1..60) sec * 1000 else sec
                    if (ms in 1000..60000) notificationDurationMs = ms
                }
            }
            if (obj.has("settings_msg_notification_sound_enabled")) {
                notificationSoundEnabled = obj.getString("settings_msg_notification_sound_enabled").toBoolean()
            }
            if (obj.has("settings_screen_ui_bottom_margin")) {
                obj.getString("settings_screen_ui_bottom_margin").toIntOrNull()?.let { px ->
                    if (px in 0..300) bottomPaddingPx = px
                }
            }
            if (obj.has("settings_chat_font_size")) {
                obj.getString("settings_chat_font_size").toFloatOrNull()?.let { sz ->
                    if (sz in 8f..24f) chatFontSize = sz
                }
            }
            obj.optInt("settings_brightness", -1).takeIf { it in 0..15 }?.let { brightness = it }
            obj.optInt("settings_screen_timeout_s", -1).takeIf { it in 0..86400 }?.let { screenTimeoutSec = it }
            obj.optInt("settings_power_timeout_min", -1).takeIf { it in 0..1440 }?.let { powerTimeoutMin = it }
            if (obj.has("wakeword_enabled")) {
                wakewordEnabled = obj.optBoolean("wakeword_enabled", wakewordEnabled)
            }
            if (obj.has("always_record_enabled")) {
                alwaysRecordEnabled = obj.optBoolean("always_record_enabled", alwaysRecordEnabled)
            }
            if (obj.has("on_demand_recording_active")) {
                onDemandRecordingActive = obj.optBoolean("on_demand_recording_active", onDemandRecordingActive)
            }
            if (obj.has("enable_sideloading")) {
                sideloadingEnabled = obj.optBoolean("enable_sideloading", sideloadingEnabled)
            }
            // TODO(glasses): wire voiceControl + longPressFun into the Rokid OS
            // framework (former CxrApi setVoiceControl / setLongPressFun calls)
            // once the replacement HAL/framework binding is decided. For now we
            // just persist them so the phone-side setting survives a restart.
            if (obj.has("settings_voice_control")) voiceControl = obj.optString("settings_voice_control", voiceControl)
            if (obj.has("settings_long_press_fun")) longPressFun = obj.optString("settings_long_press_fun", longPressFun)
        } catch (_: Exception) {}

        // Persist so a phone disconnect / listener restart doesn't wipe these back
        // to defaults. UI-critical values like bottomPaddingPx MUST survive.
        save(ctx)

        try {
            ctx.sendBroadcast(
                Intent(ACTION_GLASSES_CONFIG_CHANGED)
                    .setPackage(ctx.packageName)
                    .putExtra(EXTRA_BRIGHTNESS, brightness)
                    .putExtra(EXTRA_SCREEN_TIMEOUT_S, screenTimeoutSec)
                    .putExtra(EXTRA_POWER_TIMEOUT_MIN, powerTimeoutMin)
            )
        } catch (_: Exception) {}
    }

    /**
     * Load persisted settings into the in-memory vars. Call from
     * GlassesListenerApp.onCreate() so that preview padding + other UI config
     * survive phone disconnect / glasses restart. Without this, the defaults
     * kick in until the phone re-pushes settings via BT.
     */
    fun load(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        model = sp.getString("model", model) ?: model
        deviceId = sp.getString("deviceId", deviceId) ?: deviceId
        notificationDurationMs = sp.getLong("notificationDurationMs", notificationDurationMs)
        notificationSoundEnabled = sp.getBoolean("notificationSoundEnabled", notificationSoundEnabled)
        bottomPaddingPx = sp.getInt("bottomPaddingPx", bottomPaddingPx)
        chatFontSize = sp.getFloat("chatFontSize", chatFontSize)
        brightness = sp.getInt("brightness", brightness)
        screenTimeoutSec = sp.getInt("screenTimeoutSec", screenTimeoutSec)
        powerTimeoutMin = sp.getInt("powerTimeoutMin", powerTimeoutMin)
        wakewordEnabled = sp.getBoolean("wakeword_enabled", wakewordEnabled)
        alwaysRecordEnabled = sp.getBoolean("always_record_enabled", alwaysRecordEnabled)
        sideloadingEnabled = sp.getBoolean("enable_sideloading", sideloadingEnabled)
        voiceControl = sp.getString("voiceControl", voiceControl) ?: voiceControl
        longPressFun = sp.getString("longPressFun", longPressFun) ?: longPressFun
    }

    private fun save(ctx: Context) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("model", model)
                .putString("deviceId", deviceId)
                .putLong("notificationDurationMs", notificationDurationMs)
                .putBoolean("notificationSoundEnabled", notificationSoundEnabled)
                .putInt("bottomPaddingPx", bottomPaddingPx)
                .putFloat("chatFontSize", chatFontSize)
                .putInt("brightness", brightness)
                .putInt("screenTimeoutSec", screenTimeoutSec)
                .putInt("powerTimeoutMin", powerTimeoutMin)
                .putBoolean("wakeword_enabled", wakewordEnabled)
                .putBoolean("always_record_enabled", alwaysRecordEnabled)
                .putBoolean("enable_sideloading", sideloadingEnabled)
                .putString("voiceControl", voiceControl)
                .putString("longPressFun", longPressFun)
                .apply()
        } catch (_: Exception) {}
    }

    // --- Translation config (persisted separately from the main settings
    // so they survive glasses restart and can be used to start translation
    // from the glasses without the phone pushing config first). ---

    fun getTranslationFromLanguage(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TRANSLATION_FROM_LANG, "") ?: ""

    fun setTranslationFromLanguage(ctx: Context, value: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TRANSLATION_FROM_LANG, value).apply()

    fun getTranslationToLanguage(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TRANSLATION_TO_LANG, "") ?: ""

    fun setTranslationToLanguage(ctx: Context, value: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TRANSLATION_TO_LANG, value).apply()

    fun getTranslationFontSize(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_TRANSLATION_FONT_SIZE, 14)

    fun setTranslationFontSize(ctx: Context, value: Int) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_TRANSLATION_FONT_SIZE, value).apply()

    fun getTranslationAudioSource(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TRANSLATION_AUDIO_SOURCE, "glasses") ?: "glasses"

    fun setTranslationAudioSource(ctx: Context, value: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TRANSLATION_AUDIO_SOURCE, value).apply()

    fun getTranslationProvider(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TRANSLATION_PROVIDER, "azure") ?: "azure"

    fun setTranslationProvider(ctx: Context, value: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TRANSLATION_PROVIDER, value).apply()

    fun getTranslationTwoWay(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TRANSLATION_TWO_WAY, false)

    fun setTranslationTwoWay(ctx: Context, value: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_TRANSLATION_TWO_WAY, value).apply()

    fun hasTranslationConfig(ctx: Context): Boolean =
        getTranslationFromLanguage(ctx).isNotEmpty() && getTranslationToLanguage(ctx).isNotEmpty()

    // --- Last-used assistant config (persisted separately so the glasses can
    // start the assistant with the most recent configuration). Getters coerce
    // null/blank to the default so a cleared or missing value never propagates. ---

    fun getAssistantWearerLang(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ASSISTANT_WEARER_LANG, null).orBlankDefault("en-US")

    fun setAssistantWearerLang(ctx: Context, value: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ASSISTANT_WEARER_LANG, value).apply()

    fun getAssistantInterlocutorLang(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ASSISTANT_INTERLOCUTOR_LANG, null).orBlankDefault("en-US")

    fun setAssistantInterlocutorLang(ctx: Context, value: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ASSISTANT_INTERLOCUTOR_LANG, value).apply()

    fun getAssistantInterlocutorSource(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ASSISTANT_INTERLOCUTOR_SOURCE, null).orBlankDefault("glasses")

    fun setAssistantInterlocutorSource(ctx: Context, value: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ASSISTANT_INTERLOCUTOR_SOURCE, value).apply()

    fun getAssistantModel(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ASSISTANT_MODEL, null).orBlankDefault("haiku")

    fun setAssistantModel(ctx: Context, value: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ASSISTANT_MODEL, value).apply()

    private fun String?.orBlankDefault(default: String): String =
        if (this.isNullOrBlank()) default else this
}
