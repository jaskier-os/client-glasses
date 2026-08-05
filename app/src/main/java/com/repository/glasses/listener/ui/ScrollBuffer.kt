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
     * True while a drain chain is already scheduled for this buffer.
     *
     * Without this flag every [add] starts an independent, self-sustaining `postOnAnimation` chain
     * over the SAME buffer, so N enqueues in one main-thread turn produce N `scrollBy()` calls and
     * N layout passes per frame. A burst of remote input events makes that pathological on the
     * glasses SoC.
     */
    var posted: Boolean = false

    /**
     * Incremented every time a chain is abandoned ([clear]). A drain callback captures the
     * generation it was posted under and exits immediately if it no longer matches, so a Runnable
     * that is already sitting in the Choreographer queue when [clear] runs cannot resurrect itself
     * or race a freshly started chain.
     */
    var generation: Int = 0
        private set

    val isEmpty: Boolean get() = dx == 0 && dy == 0

    fun add(dx: Int, dy: Int) {
        this.dx += dx
        this.dy += dy
    }

    /** Abandon the pending motion AND invalidate any drain callback already in flight. */
    fun clear() {
        dx = 0
        dy = 0
        posted = false
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
