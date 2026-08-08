package com.repository.glasses.listener.ui

/**
 * The one-line label for a collapsed tool run in an RC thread.
 *
 * The names of the tools stay on the phone -- on a 480x640 waveguide they would push the answer,
 * the only thing the wearer came for, off the screen. What the glasses show is the wrench glyph
 * and this count.
 */
object RcToolLabel {

    fun of(toolCount: Int): String {
        val n = toolCount.coerceAtLeast(0)
        // "1 tool called", not "1 tools called".
        val noun = if (n == 1) "tool" else "tools"
        return "$n $noun called"
    }
}
