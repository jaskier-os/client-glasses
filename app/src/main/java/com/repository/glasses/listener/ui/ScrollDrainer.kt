package com.repository.glasses.listener.ui

import android.view.View
import com.repository.glasses.listener.R

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
 *
 * Main-thread only. The buffer hangs off the View itself as a tag rather than living in a map:
 * a map keyed on `view.hashCode()` let two live views with colliding hashes share one buffer and
 * kept entries alive after the owning activity was destroyed. A tag has neither problem, and it
 * dies with the View.
 */
object ScrollDrainer {

    private fun bufferOf(view: View): ScrollBuffer =
        (view.getTag(R.id.scroll_drainer_pending) as? ScrollBuffer)
            ?: ScrollBuffer().also { view.setTag(R.id.scroll_drainer_pending, it) }

    private fun drain(view: View, generation: Int) {
        val p = view.getTag(R.id.scroll_drainer_pending) as? ScrollBuffer ?: return
        // A cancel() between the post and this callback bumped the generation. This chain is dead;
        // a new one may already be running, so do not touch `posted`.
        if (p.generation != generation) return
        if (p.isEmpty) {
            p.posted = false
            return
        }
        val (dx, dy) = p.takeSlice()
        view.scrollBy(dx, dy)
        if (!p.isEmpty) {
            // Keep `posted` true: this chain continues, and enqueue() must not start a second one.
            view.postOnAnimation { drain(view, generation) }
        } else {
            p.posted = false
        }
    }

    fun enqueue(view: View, dx: Int, dy: Int) {
        val p = bufferOf(view)
        p.add(dx, dy)
        if (!p.posted) {
            p.posted = true
            val generation = p.generation
            view.postOnAnimation { drain(view, generation) }
        }
    }

    fun enqueueY(view: View, dy: Int) = enqueue(view, 0, dy)
    fun enqueueX(view: View, dx: Int) = enqueue(view, dx, 0)

    /**
     * Drop any pending motion for [view] (e.g. on finger release).
     *
     * Clears `posted` as well: an in-flight drain chain has already captured [view], and leaving the
     * flag set after emptying the buffer would make the view permanently un-drainable (the chain
     * exits on the empty buffer, and every later enqueue sees `posted == true` and never re-posts).
     */
    fun cancel(view: View) {
        (view.getTag(R.id.scroll_drainer_pending) as? ScrollBuffer)?.clear()
    }
}
