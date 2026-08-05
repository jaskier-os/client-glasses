package com.repository.glasses.listener.input.remote

/**
 * Durable replay-defence state, one record per source.
 *
 * Everything the router keeps in memory is erased by a restart, and an attacker who captured a
 * session can simply wait for one. Without persistence the whole sequence defence is only as strong
 * as the process's uptime, and a captured SELECT could be re-injected into the UI after a reboot.
 *
 * An interface so the router stays free of Android types and the rules stay unit-testable against an
 * in-memory implementation.
 */
interface SessionStore {
    /**
     * The highest session id ever accepted for [sourceId], or [NO_SID] if none.
     *
     * A single monotonically increasing number is the entire replay defence, which is why it is one
     * value and not a table: a bounded table of recent ids can be flushed by an attacker who simply
     * opens enough throwaway sessions, and then the captured id looks new again. A high-water mark
     * cannot be evicted.
     */
    fun highestSid(sourceId: String): Long

    /**
     * The sequence number below which frames must never be accepted again for [sourceId]'s current
     * session. See [reserveSeq] for why this is a reservation rather than an exact value.
     */
    fun seqFloor(sourceId: String): Long

    /**
     * Durably adopt [sid] and reset the sequence floor to [seqFloor].
     *
     * Must not return until the value would survive a power loss: this is called before the first
     * event of a session is acted on, and a crash in between would reopen the window it closes.
     * Rare -- once per session -- so the cost is irrelevant.
     */
    fun adoptSession(sourceId: String, sid: Long, seqFloor: Long)

    /**
     * Durably reserve sequence numbers up to [floor] for [sourceId].
     *
     * Called on a bounded cadence rather than per event, because this is the input hot path and a
     * synchronous write per scroll detent would be felt. The reservation runs AHEAD of the sequence
     * actually applied, so a crash can only ever cause the router to refuse frames it might already
     * have accepted -- never to accept one twice. That asymmetry is the point: the failure mode is
     * a few lost detents, not a replayed tap.
     */
    fun reserveSeq(sourceId: String, floor: Long)

    /**
     * Forget everything for [sourceId].
     *
     * Recovery for the one legitimate way a source can regress its counter: a factory reset of the
     * device. This MUST only ever be reachable from a local, physical action on the glasses. Exposing
     * it over the transport would reinstate the replay hole verbatim, since an attacker's first move
     * would be to call it.
     */
    fun forget(sourceId: String)

    companion object {
        /** Sentinel for "no session has ever been accepted". Session id 0 is reserved and invalid. */
        const val NO_SID = 0L
    }
}

/** Non-persistent [SessionStore]. Tests only -- it provides no replay defence across a restart. */
class InMemorySessionStore : SessionStore {
    private data class Record(var sid: Long, var seqFloor: Long)

    private val records = HashMap<String, Record>()

    var adoptCount = 0
        private set
    var reserveCount = 0
        private set

    override fun highestSid(sourceId: String): Long =
        synchronized(records) { records[sourceId]?.sid ?: SessionStore.NO_SID }

    override fun seqFloor(sourceId: String): Long =
        synchronized(records) { records[sourceId]?.seqFloor ?: 0L }

    override fun adoptSession(sourceId: String, sid: Long, seqFloor: Long) {
        synchronized(records) {
            records[sourceId] = Record(sid, seqFloor)
            adoptCount++
        }
    }

    override fun reserveSeq(sourceId: String, floor: Long) {
        synchronized(records) {
            val r = records[sourceId] ?: return
            if (RemoteInputRouter.seqDiff(floor, r.seqFloor) > 0) {
                r.seqFloor = floor
                reserveCount++
            }
        }
    }

    override fun forget(sourceId: String) {
        synchronized(records) { records.remove(sourceId) }
    }
}
