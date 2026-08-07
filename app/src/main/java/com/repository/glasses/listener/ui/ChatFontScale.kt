package com.repository.glasses.listener.ui

import com.repository.glasses.listener.config.GlassesConfig

/**
 * Turns a design size into the size to actually render, honouring the wearer's chat font setting.
 *
 * The setting ([GlassesConfig.chatFontSize], 8..24sp, pushed from the phone over CH_SETTINGS) is
 * applied as a RATIO against [BASE_SP] rather than as an absolute size. Applying it absolutely --
 * setting every view to `chatFontSize` -- would flatten the design's hierarchy: a session title, the
 * body of a reply, and the workDir line underneath it would all render at one size, and the
 * subordinate line would stop reading as subordinate. Scaling keeps the ratios the sketch was
 * approved with at every setting.
 *
 * Read live on every call. Nothing caches the ratio, so a setting change that arrives while a
 * screen is open takes effect on the next bind -- which is what lets the existing
 * `chatFontSizeReceiver` fix things up with a plain invalidate.
 */
object ChatFontScale {

    /**
     * The size the RC mirror and the chat list were drawn at, and the default `chatFontSize`
     * carries. At the default setting every design size renders exactly as drawn.
     */
    const val BASE_SP = 14f

    /**
     * The largest setting the RC mirror will honour.
     *
     * The wearer's slider runs to 24sp, and the chat message body still goes all the way there --
     * this cap is scoped to what [sp] derives, i.e. the RC mirror's own surfaces. At 24sp the RC
     * session title renders at 22.3sp, which fits roughly eight monospace characters across the
     * 480x640 waveguide; everything that distinguishes one session from another lies past
     * character eight, so the row grew without becoming more readable. 18sp is where a title still
     * carries enough characters to be told apart.
     */
    const val CAP_SP = 18f

    /**
     * The size to actually render [designSp] at.
     *
     * Below [BASE_SP] the plain ratio pushes the sketch's smaller elements (10-13sp) down to
     * 5.7-7.4sp, which nothing in this app has ever rendered and which the 480x640 waveguide cannot
     * resolve. The floor that stops it is the wearer's own `chatFontSize` -- not an invented
     * constant, but exactly the size `ChatAdapter` already gives the message body at every setting.
     * So the guarantee is "no RC text is smaller than the chat body beside it", which restates
     * existing behaviour instead of layering a second rule over it.
     *
     * The floor only ever LIFTS: a design size already below the setting is left alone, so the
     * smallest setting can never render an element larger than the default does.
     *
     * @param designSp the size as drawn in the approved sketch.
     */
    fun sp(designSp: Float): Float {
        // The cap is applied to the SETTING, before the ratio, deliberately. Clamping each
        // resulting size to CAP_SP independently would push the 14sp body and the 13sp title onto
        // the same number at high settings and flatten exactly the hierarchy this object exists to
        // preserve. Capping the input keeps every gap open, just stops widening them.
        val setting = minOf(GlassesConfig.chatFontSize, CAP_SP)
        val scaled = designSp * (setting / BASE_SP)
        return maxOf(scaled, minOf(setting, designSp))
    }
}
