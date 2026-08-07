package com.repository.glasses.listener.ui

import com.repository.glasses.listener.config.GlassesConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The wearer's chat font size is one number pushed from the phone (CH_SETTINGS
 * `settings_chat_font_size` -> `GlassesConfig.chatFontSize`, 8..24sp). Every text surface the
 * wearer reads has to be derived from it, or a single screen ends up half-scaled -- which is worse
 * than not honouring the setting at all, because the hierarchy inverts: a "smaller" secondary line
 * can end up larger than the body it is subordinate to.
 *
 * The RC mirror was drawn at a base of 14sp (the same default `chatFontSize` carries), so the
 * setting is applied as a RATIO against that base rather than as an absolute. That keeps the
 * sketch's relative hierarchy -- title over workDir, body over the collapsed tool row -- intact at
 * every setting instead of flattening everything to one size.
 *
 * Below the default the ratio alone would push the sketch's 10-13sp elements to 5.7-7.4sp, which
 * nothing in this app has ever rendered. The floor that stops it is NOT a new invented constant:
 * it is `chatFontSize` itself, because that is exactly the size `ChatAdapter` already gives the
 * message body at every setting. So "no RC text is smaller than the wearer's own setting" restates
 * existing behaviour rather than layering a second rule on top of it.
 */
class ChatFontScaleTest {

    private val original = GlassesConfig.chatFontSize

    @After
    fun restore() {
        GlassesConfig.chatFontSize = original
    }

    @Test
    fun `base setting renders the design sizes unchanged`() {
        GlassesConfig.chatFontSize = ChatFontScale.BASE_SP
        assertEquals(14f, ChatFontScale.sp(14f), 0.001f)
        assertEquals(13f, ChatFontScale.sp(13f), 0.001f)
        assertEquals(11f, ChatFontScale.sp(11f), 0.001f)
        assertEquals(10f, ChatFontScale.sp(10f), 0.001f)
    }

    @Test
    fun `largest setting scales every design size by the capped ratio`() {
        GlassesConfig.chatFontSize = 24f
        val ratio = ChatFontScale.CAP_SP / ChatFontScale.BASE_SP
        assertEquals(14f * ratio, ChatFontScale.sp(14f), 0.001f)
        assertEquals(13f * ratio, ChatFontScale.sp(13f), 0.001f)
        assertEquals(11f * ratio, ChatFontScale.sp(11f), 0.001f)
        assertEquals(10f * ratio, ChatFontScale.sp(10f), 0.001f)
    }

    /**
     * The cap is the whole point of this feature. Past 18sp the RC surfaces stop growing: a
     * 480x640 waveguide running a 22sp session title fits roughly eight monospace characters per
     * line, and everything the wearer needs to tell one session from another is past character
     * eight. The wearer's own setting is not overridden -- it still drives the chat message body
     * through `ChatAdapter` -- it is only the RC mirror's derived sizes that stop here.
     */
    @Test
    fun `the cap is 18sp and every setting above it renders identically`() {
        assertEquals(18f, ChatFontScale.CAP_SP, 0.001f)
        GlassesConfig.chatFontSize = ChatFontScale.CAP_SP
        val atCap = listOf(14f, 13f, 12f, 11f, 10f).map { ChatFontScale.sp(it) }
        var sz = ChatFontScale.CAP_SP
        while (sz <= 24f) {
            GlassesConfig.chatFontSize = sz
            listOf(14f, 13f, 12f, 11f, 10f).forEachIndexed { i, design ->
                assertEquals(
                    "sp($design) at setting $sz must match the capped rendering",
                    atCap[i], ChatFontScale.sp(design), 0.001f
                )
            }
            sz += 1f
        }
    }

    /**
     * Capping must not FLATTEN. The reason the scale is a ratio rather than an absolute is that
     * title, body and workDir have to stay rankable; a cap that clamped each size to 18sp
     * independently would collapse the 14sp body and the 13sp title onto the same number and undo
     * that. Capping the SETTING and then scaling keeps every gap open.
     */
    @Test
    fun `the cap preserves the hierarchy instead of clamping sizes together`() {
        GlassesConfig.chatFontSize = 24f
        val body = ChatFontScale.sp(14f)
        val title = ChatFontScale.sp(13f)
        val tool = ChatFontScale.sp(12f)
        val folder = ChatFontScale.sp(11f)
        assert(body > title) { "body $body did not outrank title $title under the cap" }
        assert(title > tool) { "title $title did not outrank tool $tool under the cap" }
        assert(tool > folder) { "tool $tool did not outrank folder $folder under the cap" }
        // And the largest thing on screen is exactly the cap, not something above it.
        assertEquals(ChatFontScale.CAP_SP, body, 0.001f)
    }

    /**
     * Below the cap nothing changes: the settings the wearer is most likely to sit on must behave
     * exactly as they did before the cap existed, or this becomes a silent shrink for everyone.
     */
    @Test
    fun `settings below the cap are untouched by it`() {
        var sz = 8f
        while (sz < ChatFontScale.CAP_SP) {
            GlassesConfig.chatFontSize = sz
            listOf(14f, 13f, 11f, 10f).forEach { design ->
                val expected = maxOf(design * (sz / ChatFontScale.BASE_SP), minOf(sz, design))
                assertEquals(
                    "the cap must not alter setting $sz", expected, ChatFontScale.sp(design), 0.001f
                )
            }
            sz += 1f
        }
    }

    /** Nothing the RC mirror renders may exceed the cap, at any setting or any design size. */
    @Test
    fun `no design size ever renders above the cap`() {
        var sz = 8f
        while (sz <= 24f) {
            GlassesConfig.chatFontSize = sz
            listOf(10f, 11f, 12f, 13f, 14f).forEach { design ->
                val got = ChatFontScale.sp(design)
                assert(got <= ChatFontScale.CAP_SP + 0.001f) {
                    "sp($design)=$got exceeds the ${ChatFontScale.CAP_SP}sp cap at setting $sz"
                }
            }
            sz += 1f
        }
    }

    /**
     * At the smallest setting the raw ratio would put the sketch's smaller elements at 5.7-7.4sp.
     * The floor lifts them to the wearer's own 8sp -- the same size `ChatAdapter` renders the
     * message body at this setting, so the RC mirror is no smaller than the chat beside it.
     */
    @Test
    fun `smallest setting bottoms out on the wearer's own size, not the raw ratio`() {
        GlassesConfig.chatFontSize = 8f
        assertEquals(8f, ChatFontScale.sp(14f), 0.001f)
        assertEquals(8f, ChatFontScale.sp(13f), 0.001f)
        assertEquals(8f, ChatFontScale.sp(11f), 0.001f)
        assertEquals(8f, ChatFontScale.sp(10f), 0.001f)
    }

    /**
     * The hierarchy is the point. An absolute-size mechanism (setting every view to
     * `chatFontSize`) would collapse body, tool row and workDir to one size and lose the ranking
     * the sketch relies on. Scaling preserves it.
     *
     * It may never INVERT at any setting; it stays STRICT wherever the floor is not biting. At the
     * very smallest settings the floor deliberately flattens the smallest sizes together --
     * legibility outranks hierarchy when the alternative is text nobody can read.
     */
    @Test
    fun `relative hierarchy never inverts and stays strict above the floor`() {
        // The floor bites when the plain ratio drops a design size below the wearer's setting,
        // which is exactly when `setting < BASE_SP`. At or above the default the ratio is pure and
        // the hierarchy must be STRICT; below it, sizes are allowed to flatten together but must
        // still never invert. Guarding the strict branch on the setting rather than on a computed
        // size keeps the branch reachable -- an earlier form tested `sp(11f) > setting`, which is
        // false at every setting in range, so the strict assertions never ran at all.
        var strictChecked = 0
        var sz = 8f
        while (sz <= 24f) {
            GlassesConfig.chatFontSize = sz
            val body = ChatFontScale.sp(14f)
            val tool = ChatFontScale.sp(12f)
            val folder = ChatFontScale.sp(11f)
            assert(body >= tool) { "body $body inverted below tool $tool at setting $sz" }
            assert(tool >= folder) { "tool $tool inverted below folder $folder at setting $sz" }
            if (sz >= ChatFontScale.BASE_SP) {
                assert(body > tool) { "body $body not > tool $tool at setting $sz" }
                assert(tool > folder) { "tool $tool not > folder $folder at setting $sz" }
                strictChecked++
            }
            sz += 1f
        }
        assert(strictChecked >= 11) { "strict hierarchy branch never ran ($strictChecked settings)" }
    }

    /**
     * No RC text may render smaller than the wearer's own setting, at any setting. This is the
     * property that keeps the RC thread as legible as the existing chat body already is.
     */
    @Test
    fun `no text ever renders below the wearer's setting`() {
        var sz = 8f
        while (sz <= 24f) {
            GlassesConfig.chatFontSize = sz
            listOf(10f, 11f, 12f, 13f, 14f).forEach { design ->
                val got = ChatFontScale.sp(design)
                assert(got >= minOf(sz, design)) {
                    "sp($design)=$got is below the wearer's setting $sz"
                }
            }
            sz += 1f
        }
    }

    /**
     * The floor LIFTS text the ratio pushed too small; it may never make text BIGGER than the size
     * it was drawn at. Otherwise the smallest setting would render some elements larger than the
     * default does -- the opposite of what the wearer asked for.
     */
    @Test
    fun `the floor never inflates text past its design size`() {
        var sz = 8f
        while (sz < ChatFontScale.BASE_SP) {
            GlassesConfig.chatFontSize = sz
            listOf(10f, 11f, 12f, 13f, 14f).forEach { design ->
                assert(ChatFontScale.sp(design) <= design) {
                    "sp($design)=${ChatFontScale.sp(design)} exceeds the design size at setting $sz"
                }
            }
            sz += 1f
        }
    }

    /**
     * Between the default and the cap the mechanism is a pure ratio -- the floor must not leak
     * into normal use, and neither must the cap. The band checked stops at [ChatFontScale.CAP_SP]
     * because that is exactly where growth is supposed to stop; above it the identity is asserted
     * by `the cap is 18sp and every setting above it renders identically`.
     */
    @Test
    fun `neither the floor nor the cap bites between the default and the cap`() {
        var sz = ChatFontScale.BASE_SP
        while (sz <= ChatFontScale.CAP_SP) {
            GlassesConfig.chatFontSize = sz
            val ratio = sz / ChatFontScale.BASE_SP
            listOf(10f, 11f, 12f, 13f, 14f).forEach { design ->
                assertEquals(design * ratio, ChatFontScale.sp(design), 0.001f)
            }
            sz += 1f
        }
    }

    /** A change to the setting is visible immediately; nothing caches the ratio. */
    @Test
    fun `scale tracks a live change to the setting`() {
        GlassesConfig.chatFontSize = 14f
        assertEquals(14f, ChatFontScale.sp(14f), 0.001f)
        GlassesConfig.chatFontSize = 17f
        assertEquals(17f, ChatFontScale.sp(14f), 0.001f)
        GlassesConfig.chatFontSize = 9f
        assertEquals(9f, ChatFontScale.sp(14f), 0.001f)
        // And a live change from below the cap to above it lands on the cap, not on the setting.
        GlassesConfig.chatFontSize = 24f
        assertEquals(ChatFontScale.CAP_SP, ChatFontScale.sp(14f), 0.001f)
    }
}
