package com.repository.glasses.listener.ui

import android.view.View

/**
 * Pixel-buffer scroll helper for burst input.
 *
 * When the touchpad daemon fires a rapid burst of scroll keys
 * (`KEYCODE_NUMPAD_0/1`), each `View.smoothScrollBy()` call would cancel the
 * prior animation and lose most of the motion. Instead we deposit pixels into
 * a per-view buffer and drain them at 60 Hz via `postOnAnimation`, guaranteeing
 * every pixel is rendered and the scroll feels continuous across bursts.
 *
 * Usage: call [enqueueX] / [enqueueY] instead of `view.smoothScrollBy(dx, dy)`.
 */
object ScrollDrainer {
    private data class Pending(var dx: Int, var dy: Int)

    private val buffers = mutableMapOf<Int, Pending>()

    private fun drain(view: View) {
        val p = buffers[view.hashCode()] ?: return
        if (p.dx == 0 && p.dy == 0) { buffers.remove(view.hashCode()); return }
        val dx = sliceDelta(p.dx); val dy = sliceDelta(p.dy)
        when (view) {
            is androidx.recyclerview.widget.RecyclerView -> view.scrollBy(dx, dy)
            is android.widget.ScrollView                  -> view.scrollBy(dx, dy)
            is android.widget.HorizontalScrollView        -> view.scrollBy(dx, dy)
            else                                          -> view.scrollBy(dx, dy)
        }
        p.dx -= dx; p.dy -= dy
        if (p.dx != 0 || p.dy != 0) view.postOnAnimation { drain(view) } else buffers.remove(view.hashCode())
    }

    /** Drain ~25% of the remaining delta each frame, clamped to [3, 60] px. */
    private fun sliceDelta(remaining: Int): Int {
        if (remaining == 0) return 0
        val sign = if (remaining > 0) 1 else -1
        val mag  = kotlin.math.abs(remaining)
        val slice = ((mag * 25) / 100).coerceIn(3, 60).coerceAtMost(mag)
        return sign * slice
    }

    fun enqueue(view: View, dx: Int, dy: Int) {
        val key = view.hashCode()
        val p = buffers.getOrPut(key) { Pending(0, 0) }
        p.dx += dx; p.dy += dy
        view.postOnAnimation { drain(view) }
    }

    fun enqueueY(view: View, dy: Int) = enqueue(view, 0, dy)
    fun enqueueX(view: View, dx: Int) = enqueue(view, dx, 0)

    /** Drop any pending motion for [view] (e.g. on finger release). */
    fun cancel(view: View) { buffers.remove(view.hashCode()) }
}
