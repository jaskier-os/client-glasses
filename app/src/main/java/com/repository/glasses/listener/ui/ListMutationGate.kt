package com.repository.glasses.listener.ui

/**
 * Decides what a freshly-built row list is allowed to do to a list the user is currently steering.
 *
 * The phone pushes a full snapshot on every RC event, at arbitrary times. Applying a set change
 * (insert / remove / re-sort) while the caret is live yanks rows out from under the user's next
 * keypress. Applying a CONTENT change (spinner on, unread bar off) moves nothing and must be live,
 * because the whole point of the mirror is that the status is true.
 *
 * So: while focused, set changes are held and the newest one is flushed on focus exit or after
 * [holdMs], whichever comes first. The hold is bounded because a caret that never moves must not
 * be able to strand the list on a stale set forever.
 *
 * Pure and clock-injectable; all policy lives here rather than in the adapter.
 */
class ListMutationGate(
    private val holdMs: Long = HOLD_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    sealed class Decision {
        /** Nothing changed. */
        object None : Decision()

        /** Free to swap the whole list. */
        data class Apply(val rows: List<ChatRow>) : Decision()

        /**
         * The row SET is unchanged; only per-row content moved. Rebind in place, never re-submit --
         * a full swap would cancel the caret's border animation and restart every icon animation.
         */
        data class ContentOnly(val rows: List<ChatRow>) : Decision()

        /**
         * A set change was held back. [rows] is what stays on screen: the visible set, carrying any
         * content updates the new snapshot had for rows that survive in it.
         */
        data class Deferred(val rows: List<ChatRow>) : Decision()
    }

    private var pending: List<ChatRow>? = null
    private var pendingSince: Long = 0L

    fun submit(current: List<ChatRow>, next: List<ChatRow>, focused: Boolean): Decision {
        if (!focused) {
            // The caret is not live, so nothing can be yanked from under it. Any earlier deferral is
            // stale by definition -- this snapshot is newer and authoritative.
            clearPending()
            return if (current == next) Decision.None else Decision.Apply(next)
        }

        val sameSet = current.map { it.key } == next.map { it.key }
        if (sameSet) {
            // Every push is a full snapshot, so a snapshot whose set matches what is on screen means
            // any previously-held change has been superseded and must not replay later.
            clearPending()
            return if (current == next) Decision.None else Decision.ContentOnly(next)
        }

        if (pending == null) pendingSince = nowMs()
        pending = next
        return Decision.Deferred(mergeContentInto(current, next))
    }

    /** Focus left the list. Returns the newest held snapshot, or null if none was held. */
    fun release(): List<ChatRow>? = takePending()

    /** Returns the held snapshot once [holdMs] has elapsed since the FIRST deferral, else null. */
    fun tick(): List<ChatRow>? {
        val held = pending ?: return null
        if (nowMs() - pendingSince < holdMs) return null
        clearPending()
        return held
    }

    val hasPending: Boolean get() = pending != null

    private fun takePending(): List<ChatRow>? {
        val p = pending
        clearPending()
        return p
    }

    private fun clearPending() {
        pending = null
        pendingSince = 0L
    }

    /**
     * Keeps the visible row ORDER and MEMBERSHIP, but takes each row's content from the new snapshot
     * where that row still exists. This is what makes "defer the set, apply the status" true.
     */
    private fun mergeContentInto(current: List<ChatRow>, next: List<ChatRow>): List<ChatRow> {
        val byKey = next.associateBy { it.key }
        return current.map { byKey[it.key] ?: it }
    }

    companion object {
        /**
         * How long a set change may be held while the caret is live. Long enough to cover a burst of
         * scrolling, short enough that a user who walks away with the list focused still sees a
         * current list.
         */
        const val HOLD_MS = 8_000L
    }
}
