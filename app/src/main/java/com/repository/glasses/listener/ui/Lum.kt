package com.repository.glasses.listener.ui

/**
 * Luminance palette for Rokid AR waveguide.
 * Hierarchy through brightness alone -- brighter = more important.
 * Black (VOID) = pixels OFF = transparent on the waveguide.
 */
object Lum {
    const val GLOW   = 0xFF00FF00.toInt()  // Primary text, active indicators, focus
    const val BRIGHT = 0xFF00DD00.toInt()  // User messages, secondary interactive
    const val MID    = 0xFF00AA00.toInt()  // Assistant content, body text
    const val DIM    = 0xFF007700.toInt()  // Metadata, timestamps, status
    const val SOFT   = 0xFF008800.toInt()  // Inactive tab icons
    const val GHOST  = 0xFF004400.toInt()  // Structural hints, highlight fills
    const val TRACE  = 0xFF002200.toInt()  // Ultra-subtle hairline borders, bubble fills
    const val VOID   = 0xFF000000.toInt()  // Background (transparent on waveguide)
}
