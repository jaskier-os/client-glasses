package com.repository.glasses.listener.ui

/**
 * One remote-control session as the phone described it in a CH_RC_STATE_PUSH snapshot.
 * Pure data straight off the wire -- every rendering decision is derived in [ChatRowBuilder],
 * never here, so the rules are testable without a device.
 */
data class RcSessionState(
    val id: String,
    val name: String,
    val folder: String,
    val turning: Boolean,
    val unread: Boolean,
    val ended: Boolean,
    val lastSeq: Long,
)

/**
 * A full authoritative snapshot of the RC session list. There are no deltas: the sessions this
 * carries are exactly the sessions that exist, so absence from the next snapshot IS the removal
 * instruction.
 *
 * [wsConnected] is the phone's orchestrator link. It defaults to false because the fail-safe is to
 * dim the rows and refuse the microphone rather than invite a dictation into a void.
 */
data class RcState(
    val wsConnected: Boolean = false,
    val sessions: List<RcSessionState> = emptyList(),
) {
    companion object {
        val EMPTY = RcState()
    }
}

/**
 * Every row the chat list can show. The list is heterogeneous, so the row TYPE -- not the row
 * POSITION -- decides the view type. Positional arithmetic (`items[position - HEADER_COUNT]`) is
 * what made an asynchronously-arriving RC row able to re-aim the caret onto a different chat.
 *
 * [key] is stable across rebuilds and unique within a list. Selection is held by key and
 * re-resolved after every submit, so nothing that happens to the SET of rows can move the caret
 * off the row the user is looking at.
 */
sealed class ChatRow {
    abstract val key: String

    object NewChat : ChatRow() {
        override val key: String = "hdr:new"
    }

    /** Marks the start of the pinned RC section. A desktop glyph, no text label, no rule. */
    object RcGroup : ChatRow() {
        override val key: String = "hdr:rc"
    }

    data class RcSession(
        val id: String,
        val name: String,
        val folder: String,
        val turning: Boolean,
        val unread: Boolean,
        val ended: Boolean,
        val dim: Boolean,
        val enterable: Boolean,
        val voiceAllowed: Boolean,
    ) : ChatRow() {
        override val key: String = "rc:$id"
    }

    data class Conversation(val summary: ChatSummaryItem) : ChatRow() {
        override val key: String = "conv:${summary.id}"
    }

    /** True for rows the caret may rest on. The RC group marker opens nothing, so it may not. */
    val selectable: Boolean
        get() = this !is RcGroup

    /**
     * True for rows the caret may be moved onto WITHOUT the user aiming at them.
     *
     * Kept as its own concept even though every selectable row now qualifies: it existed because
     * the Assistant row armed the microphone, and any future row with a side effect must be
     * excluded here rather than discovering the hazard again.
     */
    val fallbackSafe: Boolean
        get() = selectable
}

/**
 * Builds the row list and resolves selection. Pure, Android-free, JVM-tested.
 */
object ChatRowBuilder {

    /**
     * The pinned RC section is capped so a burst of sessions cannot push every normal chat off a
     * 480x640 waveguide. The phone already truncates to 8; this is the render-side floor that keeps
     * conversations reachable regardless of what the phone sends.
     */
    const val MAX_PINNED_RC = 5

    fun build(rc: RcState, conversations: List<ChatSummaryItem>): List<ChatRow> {
        val rows = ArrayList<ChatRow>(conversations.size + rc.sessions.size + 3)
        rows.add(ChatRow.NewChat)

        // sortedByDescending is stable in Kotlin, so sessions the phone listed earlier (i.e. more
        // recently active) keep their relative order within each group.
        val pinned = rc.sessions
            .sortedByDescending { it.turning && !it.ended }
            .take(MAX_PINNED_RC)

        if (pinned.isNotEmpty()) {
            rows.add(ChatRow.RcGroup)
            pinned.forEach { rows.add(toRow(it, rc.wsConnected)) }
        }

        conversations.forEach { rows.add(ChatRow.Conversation(it)) }
        return rows
    }

    private fun toRow(s: RcSessionState, wsConnected: Boolean): ChatRow.RcSession {
        // An ended session is a corpse: it shows its last state, dim, and nothing about it may
        // suggest ongoing work. The phone clears both flags when it marks a session ended, but the
        // renderer refuses to trust that -- a stale snapshot must not produce a lying spinner.
        val turning = s.turning && !s.ended
        // turning and unread share the same right-aligned slot, so they can never co-render.
        val unread = s.unread && !s.ended && !turning
        return ChatRow.RcSession(
            id = s.id,
            name = s.name,
            folder = s.folder,
            turning = turning,
            unread = unread,
            ended = s.ended,
            dim = s.ended || !wsConnected,
            // Reading stale content is fine; dictating into a void is not.
            enterable = !s.ended,
            voiceAllowed = wsConnected && !s.ended && !turning,
        )
    }

    /**
     * Where the caret belongs after a submit.
     *
     * @param selectedKey the key of the row that was selected BEFORE the submit.
     * @param previousIndex where that row used to sit, used only as the fallback anchor when the
     *        key has vanished. Pass -1 when unknown.
     * @return an index into [rows] that is always in range and always selectable.
     */
    fun resolveSelection(rows: List<ChatRow>, selectedKey: String?, previousIndex: Int = -1): Int {
        if (rows.isEmpty()) return -1
        if (selectedKey != null) {
            val exact = rows.indexOfFirst { it.key == selectedKey }
            if (exact >= 0) return exact
        }
        // The key is gone. Land as close as possible to where the user was looking, deterministically,
        // and never on a row that would act on the next keypress without them having aimed at it.
        val anchor = if (previousIndex < 0) 0 else previousIndex.coerceAtMost(rows.lastIndex)
        return nearestFallbackSafe(rows, anchor)
    }

    private fun nearestFallbackSafe(rows: List<ChatRow>, anchor: Int): Int {
        if (rows[anchor].fallbackSafe) return anchor
        var down = anchor + 1
        var up = anchor - 1
        while (down <= rows.lastIndex || up >= 0) {
            if (down <= rows.lastIndex) {
                if (rows[down].fallbackSafe) return down
                down++
            }
            if (up >= 0) {
                if (rows[up].fallbackSafe) return up
                up--
            }
        }
        // Unreachable in practice: NewChat is always present and always fallback-safe.
        return 0
    }
}
