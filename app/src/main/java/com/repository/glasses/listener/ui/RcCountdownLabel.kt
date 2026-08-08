package com.repository.glasses.listener.ui

/**
 * The seconds readout beside the RC send countdown bar.
 *
 * Split out of the animator so the rounding is testable without a Choreographer. The bar itself
 * animates per frame; this label deliberately does NOT show tenths -- a digit flickering at 60 Hz
 * on a monochrome waveguide is unreadable. It CEILS, so a window with any time left never reads
 * "0s" while it is still cancellable.
 */
object RcCountdownLabel {

    fun of(remainingMs: Long): String {
        val clamped = remainingMs.coerceAtLeast(0L)
        val secs = (clamped + 999L) / 1000L
        return "${secs}s"
    }
}
