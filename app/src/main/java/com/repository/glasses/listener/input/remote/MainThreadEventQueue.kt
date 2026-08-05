package com.repository.glasses.listener.input.remote

/**
 * Hand-off buffer between a producer thread and the UI thread.
 *
 * This is deliberately an explicit deque rather than a bare `Handler.post` per event: `Handler`
 * exposes no queue to inspect, so an overflow policy cannot be implemented on top of it at all, and
 * a burst would post an unbounded number of runnables.
 *
 * The policy is **conserve scroll distance, never silently discard motion**:
 * - Consecutive same-direction scrolls merge by SUMMING their deltas. Merging is not dropping --
 *   the user's scroll distance is preserved exactly, it just arrives in one event.
 * - Only past a hard entry cap, and only for non-scroll events, is anything dropped. Discrete
 *   actions are at-most-once by contract anyway, so a dropped one is safer than a late one.
 *
 * Pure Kotlin with no Android dependency so the ordering and merge rules are unit-testable. The
 * caller supplies the thread hop.
 *
 * @param maxEntries hard ceiling on queued entries.
 * @param mergeWindow how many trailing entries a new scroll may merge into. One entry, i.e. only
 *        the tail, keeps merging strictly order-preserving.
 * @param post schedules [drain] on the UI thread. Called at most once per drain cycle.
 */
class MainThreadEventQueue(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val mergeWindow: Int = 1,
    private val post: (Runnable) -> Unit,
) {
    private val lock = Any()
    private val queue = ArrayDeque<RemoteInputEvent>()

    /** True while a drain runnable is scheduled but has not yet finished. Guarded by [lock]. */
    private var posted = false

    private var droppedCount = 0L
    private var mergedCount = 0L

    val dropped: Long get() = synchronized(lock) { droppedCount }
    val merged: Long get() = synchronized(lock) { mergedCount }
    val size: Int get() = synchronized(lock) { queue.size }

    /**
     * Enqueue [event] and schedule a drain if one is not already pending.
     *
     * Safe to call from any thread, including from inside a [drain] delivery (the drain loop
     * re-checks the queue under the lock before exiting, so a re-entrant enqueue cannot be lost).
     */
    fun enqueue(event: RemoteInputEvent) {
        var needsPost = false
        synchronized(lock) {
            val last = queue.lastOrNull()
            if (last != null && mergeWindow > 0 && canMerge(last, event)) {
                queue.removeLast()
                // Replace rather than mutate: the drain loop may already hold a reference to the
                // entry it polled, and events are immutable for exactly this reason.
                queue.addLast(last.copy(delta = last.delta + event.delta, seq = event.seq))
                mergedCount++
            } else if (queue.size >= maxEntries) {
                // Full. Never drop a scroll: fold it into the newest scroll if there is one, so the
                // distance survives even when the UI thread is stalled.
                val idx = queue.indexOfLast { it.action == RemoteAction.SCROLL_STEP }
                if (event.action == RemoteAction.SCROLL_STEP && idx >= 0) {
                    val target = queue[idx]
                    queue[idx] = target.copy(delta = target.delta + event.delta)
                    mergedCount++
                } else {
                    // A discrete action arriving into a full queue, or a scroll with nothing to
                    // fold into. Discrete actions are at-most-once by contract, so dropping the
                    // OLDEST discrete entry keeps the most recent user intent.
                    val victim = queue.indexOfFirst { it.action != RemoteAction.SCROLL_STEP }
                    if (victim >= 0) queue.removeAt(victim) else queue.removeFirst()
                    droppedCount++
                    queue.addLast(event)
                }
            } else {
                queue.addLast(event)
            }
            if (!posted) {
                posted = true
                needsPost = true
            }
        }
        if (needsPost) post(Runnable { drain() })
    }

    /**
     * Deliver every queued event to [deliver] on the calling (UI) thread.
     *
     * Must be installed via [setDeliverer] before the first drain.
     */
    private var deliver: ((RemoteInputEvent) -> Unit)? = null

    fun setDeliverer(d: (RemoteInputEvent) -> Unit) {
        synchronized(lock) { deliver = d }
    }

    /**
     * Drop everything queued. Used when the UI sink goes away: delivering a backlog to a screen the
     * user has since left produces a burst of stale actions, which is exactly what the staleness
     * rules exist to prevent.
     */
    fun clear() {
        synchronized(lock) { queue.clear() }
    }

    private fun drain() {
        val d = synchronized(lock) { deliver }
        while (true) {
            val event: RemoteInputEvent
            synchronized(lock) {
                val next = queue.removeFirstOrNull()
                if (next == null) {
                    // Clearing `posted` and observing the empty queue happen in ONE critical
                    // section. Split them and a concurrent enqueue either sees a stale `posted` and
                    // declines to post (lost wakeup, event stuck until the next arrival) or posts a
                    // second drain that interleaves with this one.
                    posted = false
                    return
                }
                event = next
            }
            // Delivery runs OUTSIDE the lock. Holding it here would let the UI thread stall a
            // producer thread for the length of a layout pass, and would deadlock on re-entrant
            // enqueue from a listener that delivery synchronously triggers.
            try {
                d?.invoke(event)
            } catch (_: Exception) {
                // One bad event must not kill the drain loop and strand the rest of the queue.
            }
        }
    }

    companion object {
        /**
         * Hard entry ceiling. At the ~25 events/s channel limit this is over a second of backlog,
         * which is already far more than a stalled UI thread can usefully replay.
         */
        const val DEFAULT_MAX_ENTRIES = 32
    }

    private fun canMerge(a: RemoteInputEvent, b: RemoteInputEvent): Boolean =
        a.action == RemoteAction.SCROLL_STEP &&
            b.action == RemoteAction.SCROLL_STEP &&
            a.sourceId == b.sourceId &&
            a.sid == b.sid &&
            sameDirection(a.delta, b.delta)

    private fun sameDirection(a: Int, b: Int): Boolean = (a > 0 && b > 0) || (a < 0 && b < 0)
}
