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
        // A cancel(), or a chain started after this callback was queued, bumped the generation.
        // This chain is dead; another may be live, so do not touch ownership.
        if (!p.isOwnedBy(generation)) return
        if (p.isEmpty) {
            p.endChain(generation)
            return
        }
        // A detached view never runs its animation callbacks (postOnAnimation falls back to the
        // deferred HandlerActionQueue, which only drains on re-attach). Continuing would strand the
        // chain and make the view permanently un-drainable, so drop the motion instead.
        if (!view.isAttachedToWindow) {
            p.clear()
            return
        }
        val (dx, dy) = p.takeSlice()
        try {
            view.scrollBy(dx, dy)
        } catch (e: IllegalStateException) {
            // RecyclerView.scrollBy throws when called during layout or scroll computation, and
            // scrollBy dispatches listeners synchronously so re-entrancy is reachable. Abandoning
            // the buffer here is what keeps the chain from dying with ownership still held.
            p.clear()
            return
        }
        if (!p.isEmpty && p.isOwnedBy(generation)) {
            view.postOnAnimation { drain(view, generation) }
        } else {
            p.endChain(generation)
        }
    }

    fun enqueue(view: View, dx: Int, dy: Int) {
        val p = bufferOf(view)
        // Nothing will ever drain a detached view, so do not accumulate motion it cannot render.
        if (!view.isAttachedToWindow) {
            p.clear()
            return
        }
        p.add(dx, dy)
        if (!p.isDraining) {
            val generation = p.startChain()
            view.postOnAnimation { drain(view, generation) }
        }
    }

    fun enqueueY(view: View, dy: Int) = enqueue(view, 0, dy)
    fun enqueueX(view: View, dx: Int) = enqueue(view, dx, 0)

    /**
     * Drop any pending motion for [view] (e.g. on finger release).
     *
     * Also abandons any chain in flight: a callback already queued under the old generation becomes
     * a no-op, and the next [enqueue] is free to start a fresh chain.
     */
    fun cancel(view: View) {
        (view.getTag(R.id.scroll_drainer_pending) as? ScrollBuffer)?.clear()
    }
}
