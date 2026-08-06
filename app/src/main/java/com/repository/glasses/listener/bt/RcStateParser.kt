package com.repository.glasses.listener.bt

import com.repository.glasses.listener.ui.RcSessionState
import com.repository.glasses.listener.ui.RcState
import org.json.JSONObject

/**
 * Reads a CH_RC_STATE_PUSH body.
 *
 * ```
 * {"ws":true,"s":[{"id":"..","n":"..","w":"..","st":"open|ended","t":true,"u":false,"q":12}]}
 * ```
 *
 * Every push is a FULL authoritative snapshot, so the array is the complete session list and a
 * session's absence from it is the instruction to remove that row.
 *
 * Two deliberate asymmetries:
 * - a missing or non-boolean `ws` reads as **false**. Fail safe: dim the rows and refuse the mic,
 *   because dictating into a dead orchestrator link loses the words silently;
 * - a frame that cannot be parsed at all returns **null**, meaning "no change". Blanking the list
 *   on one corrupt frame would throw away good state we cannot re-request (there is no list REQ
 *   channel by design -- the phone re-pushes on every event and on link-up).
 */
object RcStateParser {

    fun parse(json: String): RcState? {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return null
        }

        // optBoolean would coerce; the spec says ws is a boolean, and anything else is not a yes.
        val ws = root.opt("ws") as? Boolean ?: false

        val arr = root.optJSONArray("s") ?: return RcState(ws, emptyList())
        val sessions = ArrayList<RcSessionState>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            // A session with no id can never be opened, marked read or dictated into.
            if (id.isEmpty()) continue
            sessions.add(
                RcSessionState(
                    id = id,
                    name = o.optString("n", id),
                    folder = o.optString("w", ""),
                    turning = o.optBoolean("t", false),
                    unread = o.optBoolean("u", false),
                    // Only the exact token ends a session; an unrecognised status leaves it live
                    // rather than silently making it non-enterable.
                    ended = o.optString("st", "") == "ended",
                    lastSeq = o.optLong("q", -1L),
                )
            )
        }
        return RcState(ws, sessions)
    }
}
