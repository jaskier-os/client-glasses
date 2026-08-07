package com.repository.glasses.listener.ui

import org.json.JSONArray
import org.json.JSONObject

/**
 * One rendered row of an RC thread, exactly as the phone projected it.
 *
 * [seq] is minted phone-side and is the only ordering there is: the glasses never invent a row, so
 * an optimistic local echo cannot exist and cannot be matched away.
 */
data class RcThreadRow(
    val seq: Long,
    val role: String,                        // user | assistant | tools | prompt
    val text: String,
    val toolCount: Int = 0,
    val options: List<String> = emptyList(), // non-empty only for an answerable prompt
    val requestId: String = "",
)

/** What the thread screen lays out, top to bottom. */
sealed class RcThreadItem {
    /** Older rows exist but live only on the phone. Rendered as a leading marker. */
    object EarlierOnPhone : RcThreadItem()
    data class Row(val row: RcThreadRow) : RcThreadItem()
}

/**
 * Merges CH_RC_MESSAGES_RESP frames into a thread.
 *
 * There is one channel for both the reply to a request and the live delta, because the two carry
 * the same body: `seq` monotonicity decides append-versus-replace, so a second channel would only
 * duplicate this handler.
 *
 * Two belts against rendering one session's rows under another session's name: the backend drops a
 * frame with no session id, and this model drops any frame whose session id is not the open one.
 * That second belt also covers a live row push racing a navigation, which is the general form.
 *
 * Not thread-safe by design: every caller is the UI thread.
 */
class RcThreadModel {

    /** The session whose thread is open, or null when the user is back in the list. */
    var sessionId: String? = null
        private set

    private val _rows = ArrayList<RcThreadRow>()
    val rows: List<RcThreadRow> get() = _rows

    /**
     * True when rows older than the ones held exist on the phone. Latches: once history has been
     * shed it does not come back, and a live delta carrying `more:false` is a statement about the
     * delta, not about history.
     */
    var moreAbove: Boolean = false
        private set

    /**
     * The seq of a prompt THIS device already answered, or -1 when none.
     *
     * A confirm is an unacknowledged one-way frame, and the only proof it landed is the next row --
     * a whole round trip away. Without this, a second DPAD_CENTER inside that gap writes a second
     * answer for the same prompt. The phone's registry refuses the duplicate, but a frame that
     * should never have been written is not made correct by being discarded at the far end.
     *
     * Keyed on seq rather than request id because seq is what the renderer keys on, and it is
     * re-minted per [open], so a stale value cannot mute a genuinely new prompt.
     */
    private var answeredSeq: Long = -1L

    /** Enters [id]'s thread from scratch. A re-open re-requests the tail, so rows must not persist. */
    fun open(id: String) {
        sessionId = id
        _rows.clear()
        moreAbove = false
        answeredSeq = -1L
    }

    /** Leaves the thread. Later frames for the session are refused rather than buffered. */
    fun close() {
        sessionId = null
        _rows.clear()
        moreAbove = false
        // answeredSeq is deliberately NOT reset here. With no rows there is nothing it can match,
        // and the only way back to a thread is through open(), which resets it. Clearing it here
        // too would be unreachable code that reads as a second, load-bearing guard.
    }

    /** This device just answered the prompt at [seq]; it stops blocking without awaiting a row. */
    fun markAnswered(seq: Long) {
        answeredSeq = seq
    }

    /**
     * Merges one CH_RC_MESSAGES_RESP body.
     *
     * @return true when something actually changed, so the caller can skip a repaint.
     */
    fun accept(frameSessionId: String, json: String): Boolean {
        if (sessionId == null || frameSessionId != sessionId) return false
        val root = try {
            JSONObject(json)
        } catch (t: Throwable) {
            return false
        }
        val arr: JSONArray = root.optJSONArray("rows") ?: return false

        var changed = false
        if (root.optBoolean("more", false) && !moreAbove) {
            moreAbove = true
            changed = true
        }

        val highest = _rows.lastOrNull()?.seq ?: -1L
        val incoming = ArrayList<RcThreadRow>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val row = parseRow(obj) ?: continue
            // Strictly newer only. An overlapping frame re-sends rows already rendered, and
            // replacing them would let a stale copy overwrite what the user is reading.
            if (row.seq <= highest) continue
            incoming.add(row)
        }
        if (incoming.isEmpty()) return changed

        // The phone emits in seq order, but sorting here means a reordered frame renders correctly
        // instead of wedging the append cursor at the wrong high-water mark.
        incoming.sortBy { it.seq }
        var last = highest
        for (row in incoming) {
            if (row.seq <= last) continue
            _rows.add(row)
            last = row.seq
        }
        trim()
        return true
    }

    /** The highest seq currently rendered, -1 when none. This is the read acknowledgement. */
    fun seenSeq(): Long = _rows.lastOrNull()?.seq ?: -1L

    /** The rendered list, with the earlier-messages marker in front when history was shed. */
    fun items(): List<RcThreadItem> {
        val out = ArrayList<RcThreadItem>(_rows.size + 1)
        if (moreAbove) out.add(RcThreadItem.EarlierOnPhone)
        _rows.forEach { row ->
            // An answered prompt keeps its text as history but loses its options: an option still
            // on screen after a confirm reads as a keypress that did nothing.
            val shown = if (row.seq == answeredSeq && row.options.isNotEmpty()) {
                row.copy(options = emptyList())
            } else row
            out.add(RcThreadItem.Row(shown))
        }
        return out
    }

    /**
     * The prompt this device can still ANSWER, or null. Drives the option list and the confirm.
     *
     * A prompt blocks only while it is the LAST row: any later row proves the agent moved on, so
     * the prompt was resolved (on the phone, or by this device). It also stops being answerable the
     * moment this device answers it, which is what [answeredSeq] records.
     */
    fun blockingPrompt(): RcThreadRow? = _rows.lastOrNull()
        ?.takeIf { it.role == ROLE_PROMPT && it.seq != answeredSeq }

    /**
     * True while a prompt is UNRESOLVED, including one this device has already answered. Drives the
     * microphone, which stays refused across the whole round trip.
     *
     * Deliberately separate from [blockingPrompt]: answering does not unblock the session, only the
     * agent moving on does. Collapsing the two would re-open the mic the instant a confirm is
     * written -- letting the wearer dictate into a session the agent has not resumed -- and a prompt
     * is answered by picking an option, never by dictation.
     */
    fun promptAwaitingReply(): Boolean = _rows.lastOrNull()?.role == ROLE_PROMPT

    private fun parseRow(obj: JSONObject): RcThreadRow? {
        // A missing "q" and a negative "q" are the same defect -- an unorderable row -- and one
        // check covers both, because optLong's default IS the sentinel.
        val seq = obj.optLong("q", -1L)
        if (seq < 0) return null
        // A role the renderer has no view for is dropped rather than rendered as something it is
        // not. Both apps ship from this branch, so an unknown role is a defect, not a newer peer.
        val role = obj.optString("r", "")
        if (role !in ROLES) return null
        val options = obj.optJSONArray("o")?.let { raw ->
            (0 until raw.length()).mapNotNull { raw.optString(it, "").ifEmpty { null } }
        } ?: emptyList()
        return RcThreadRow(
            seq = seq,
            role = role,
            text = obj.optString("x", ""),
            toolCount = obj.optInt("c", 0),
            options = options,
            requestId = obj.optString("i", ""),
        )
    }

    /**
     * Caps the thread. The phone already tails to 20 rows, but live deltas append without bound
     * over a long-lived session, and this HUD has no memory to spare. Dropping the oldest rows is
     * the same loss `more` reports, so it sets the same flag.
     */
    private fun trim() {
        while (_rows.size > MAX_ROWS) {
            _rows.removeAt(0)
            moreAbove = true
        }
    }

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_TOOLS = "tools"
        const val ROLE_PROMPT = "prompt"

        /** The four roles the phone projects. Anything else is a defect and is dropped. */
        val ROLES = setOf(ROLE_USER, ROLE_ASSISTANT, ROLE_TOOLS, ROLE_PROMPT)

        const val MAX_ROWS = 60
    }
}
