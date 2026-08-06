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

    /** Enters [id]'s thread from scratch. A re-open re-requests the tail, so rows must not persist. */
    fun open(id: String) {
        sessionId = id
        _rows.clear()
        moreAbove = false
    }

    /** Leaves the thread. Later frames for the session are refused rather than buffered. */
    fun close() {
        sessionId = null
        _rows.clear()
        moreAbove = false
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
        _rows.forEach { out.add(RcThreadItem.Row(it)) }
        return out
    }

    /**
     * The prompt the session is waiting on, or null when it is not waiting.
     *
     * A prompt blocks only while it is the LAST row: any later row proves the agent moved on, so
     * the prompt was resolved (on the phone, or by this device). While one is present the
     * microphone is refused -- a prompt is answered by picking an option, never by dictation.
     */
    fun blockingPrompt(): RcThreadRow? = _rows.lastOrNull()?.takeIf { it.role == ROLE_PROMPT }

    private fun parseRow(obj: JSONObject): RcThreadRow? {
        if (!obj.has("q")) return null
        val seq = obj.optLong("q", -1L)
        if (seq < 0) return null
        val role = obj.optString("r", "")
        if (role.isEmpty()) return null
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
        const val ROLE_PROMPT = "prompt"
        const val MAX_ROWS = 60
    }
}
