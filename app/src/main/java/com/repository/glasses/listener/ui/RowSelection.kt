package com.repository.glasses.listener.ui

/**
 * The caret over a [ChatRow] list.
 *
 * Extracted out of `ChatListAdapter` so the property that must never break -- an asynchronously
 * arriving RC session cannot move the caret onto a row the user did not aim at -- is provable in a
 * JVM test rather than only on a device.
 *
 * The caret is a KEY. The index is always derived. Every list swap re-resolves the key; when the
 * key has vanished the fallback is deterministic and never lands on a row that would act on the
 * next keypress (see [ChatRow.fallbackSafe]).
 */
class RowSelection {

    enum class Change {
        /** The caret did not move and still points at the same row. */
        NONE,

        /** The caret still points at the same row, which now sits at a different index. */
        MOVED,

        /** The caret's row vanished and it fell back to a different row. */
        REHOMED,

        /** The caret was removed entirely. */
        DROPPED,
    }

    var rows: List<ChatRow> = emptyList()
        private set

    var key: String? = null
        private set

    val index: Int
        get() = key?.let { k -> rows.indexOfFirst { it.key == k } } ?: -1

    /**
     * Point at a new list.
     *
     * @param dropSelection end with no caret at all, and report [Change.DROPPED] so the caller can
     *        rebind the old row and clear its border.
     */
    fun onRowsReplaced(next: List<ChatRow>, dropSelection: Boolean = false): Change {
        val previousKey = key
        val previousIndex = index
        rows = next

        if (dropSelection || previousKey == null) {
            key = null
            return if (previousKey == null) Change.NONE else Change.DROPPED
        }

        val resolved = ChatRowBuilder.resolveSelection(rows, previousKey, previousIndex)
        key = rows.getOrNull(resolved)?.key
        return when {
            key != previousKey -> Change.REHOMED
            resolved != previousIndex -> Change.MOVED
            else -> Change.NONE
        }
    }

    /** Refused when the key is not present: a caret is never guessed. */
    fun select(newKey: String?): Boolean {
        if (newKey == null) { key = null; return true }
        val row = rows.firstOrNull { it.key == newKey } ?: return false
        if (!row.selectable) return false
        key = newKey
        return true
    }

    fun selectIndex(pos: Int): Boolean = select(rows.getOrNull(pos)?.key)

    fun moveDown() {
        var i = index + 1
        while (i <= rows.lastIndex) {
            if (rows[i].selectable) { select(rows[i].key); return }
            i++
        }
    }

    fun moveUp() {
        val from = index
        // With no caret, an up-press claims the first row rather than silently swallowing the key.
        if (from < 0) { moveDown(); return }
        var i = from - 1
        while (i >= 0) {
            if (rows[i].selectable) { select(rows[i].key); return }
            i--
        }
    }

    fun selectedRow(): ChatRow? = rows.getOrNull(index)

    fun isNewChatSelected(): Boolean = selectedRow() is ChatRow.NewChat

    fun isAssistantSelected(): Boolean = selectedRow() is ChatRow.Assistant

    fun selectedConversation(): ChatSummaryItem? =
        (selectedRow() as? ChatRow.Conversation)?.summary

    /** Null for an ended session: it is shown, but it is not enterable. */
    fun selectedRcSession(): ChatRow.RcSession? =
        (selectedRow() as? ChatRow.RcSession)?.takeIf { it.enterable }
}
