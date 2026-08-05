package com.repository.glasses.listener.ui

/**
 * The pure accumulate-and-slice state of [ScrollDrainer], with no Android dependency so it can be
 * unit-tested on the JVM.
 *
 * One instance per scrollable View. Not thread-safe by design: it is main-thread-only state, exactly
 * like the `View.postOnAnimation` machinery that drives it.
 */
class ScrollBuffer {
    var dx: Int = 0
        private set
    var dy: Int = 0
        private set

    /**
     * Monotonic chain id. Incremented every time a drain chain is started or abandoned.
     *
     * A callback captures the generation it was posted under and does nothing if it no longer
     * matches, so a Runnable still sitting in the Choreographer queue when the chain is abandoned
     * cannot resurrect itself or interleave with a freshly started chain.
     */
    var generation: Int = 0
        private set

    /**
     * The generation of the chain that currently owns this buffer, or null when no chain is running.
     *
     * This is an owning id rather than a bare boolean so that only the chain that actually started
     * can release it. A bare boolean let an unrelated code path clear the flag while a chain was
     * still live (two chains over one buffer), or leave it set after a chain died (the buffer became
     * permanently un-drainable, because every later enqueue saw the flag and declined to post).
     */
    var owner: Int? = null
        private set

    val isEmpty: Boolean get() = dx == 0 && dy == 0

    val isDraining: Boolean get() = owner != null

    fun add(dx: Int, dy: Int) {
        this.dx += dx
        this.dy += dy
    }

    /** Claim the buffer for a new drain chain and return that chain's generation. */
    fun startChain(): Int {
        generation++
        owner = generation
        return generation
    }

    /** True if [generation] identifies the chain that currently owns the buffer. */
    fun isOwnedBy(generation: Int): Boolean = owner == generation

    /** Release ownership, but only if [generation] is the owning chain. */
    fun endChain(generation: Int) {
        if (owner == generation) owner = null
    }

    /**
     * Abandon the pending motion AND any chain in flight. Callbacks already queued under the old
     * generation become no-ops, and the next [add] is free to start a fresh chain.
     */
    fun clear() {
        dx = 0
        dy = 0
        owner = null
        generation++
    }

    /**
     * Take the next frame's slice out of the buffer and return it as `(sliceX, sliceY)`.
     * The buffer is decremented by exactly what is returned, so no pixel is ever lost or applied
     * twice.
     */
    fun takeSlice(): Pair<Int, Int> {
        val sx = sliceDelta(dx)
        val sy = sliceDelta(dy)
        dx -= sx
        dy -= sy
        return sx to sy
    }

    companion object {
        /** Drain ~25% of the remaining delta each frame, clamped to [3, 60] px. */
        fun sliceDelta(remaining: Int): Int {
            if (remaining == 0) return 0
            val sign = if (remaining > 0) 1 else -1
            val mag = kotlin.math.abs(remaining)
            val slice = ((mag * 25) / 100).coerceIn(3, 60).coerceAtMost(mag)
            return sign * slice
        }
    }
}
