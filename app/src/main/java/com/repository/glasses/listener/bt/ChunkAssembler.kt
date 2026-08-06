package com.repository.glasses.listener.bt

/**
 * Chunk reassembly for the RFCOMM wrapper channels. Pure and Android-free so it is JVM-testable.
 *
 * Two layouts are understood:
 *   new    [prefix?][chunk][isFinal][streamId][seq]  -- keyed by streamId, detects lost chunks
 *   legacy [prefix?][chunk][isFinal]                 -- keyed by channel, cannot detect a gap
 *
 * The legacy path is TRANSITIONAL, present only to protect the one deploy window in which the
 * phone runs this build and the glasses do not (or vice versa). It is deleted in task 37 of
 * docs/plans/2026-08-06-glasses-rc-mirror-plan.md, together with its tests. Do not extend it.
 *
 * Streams are bounded by silence (STALE_MS), by concurrent count (MAX_STREAMS) and by accumulated
 * size (MAX_STREAM_CHARS). Every drop is REPORTED as [Outcome.Failed] rather than logged, because
 * a silently dropped stream leaves the waiting screen spinning forever.
 *
 * Threading: every entry point is @Synchronized and there is no async hop. MessageRelay dispatches
 * on a single reader thread today, and this class must not rely on that staying true.
 */
class ChunkAssembler(private val nowMs: () -> Long = System::currentTimeMillis) {

    sealed class Outcome {
        data class Completed(val prefix: String?, val json: String) : Outcome()
        data class Failed(val channel: String, val reason: String) : Outcome()
        object None : Outcome()
    }

    private class Partial(
        val channel: String,
        val prefix: String?,
        val sb: StringBuilder,
        var touchedAt: Long,
        var nextSeq: Int
    )

    /** New-format streams, keyed by streamId. */
    private val streams = LinkedHashMap<String, Partial>()

    /** Legacy channel-keyed streams. Transitional; removed with the legacy branch in task 37. */
    private val legacy = LinkedHashMap<String, Partial>()

    /**
     * Keys (streamId, or channel on the legacy path) whose stream was dropped while the peer was
     * still sending. Without this, the next chunk would start a fresh buffer and the stream's own
     * final chunk would complete a TRUNCATED payload, which is worse than not completing at all.
     */
    private val dropped = LinkedHashMap<String, Long>()

    /** Failures raised by evictions, waiting to be reported to their channels. */
    private val pending = ArrayList<Outcome.Failed>()

    /**
     * @param prefixIndex index of the prefix arg, or -1 when the channel carries no prefix.
     * @return [Outcome.Completed] when this chunk finished a payload, [Outcome.Failed] when a
     *         stream was dropped and its waiting screen must be resolved, else [Outcome.None].
     */
    @Synchronized
    fun acceptFrame(channel: String, args: List<String>, prefixIndex: Int): Outcome {
        val now = nowMs()
        sweep(now)

        val chunkIdx = if (prefixIndex >= 0) prefixIndex + 1 else 0
        val outcome =
            if (isNewFormat(args, chunkIdx)) acceptNew(channel, args, chunkIdx, prefixIndex, now)
            else acceptLegacy(channel, args, chunkIdx, prefixIndex, now)

        // A sweep or overflow failure for ANOTHER stream must not be lost behind this frame's own
        // result, so it is queued rather than returned. The caller drains it.
        if (outcome is Outcome.Failed) return outcome
        return drainOne() ?: outcome
    }

    /** @return every failure raised by evictions since the last drain, oldest first. */
    @Synchronized
    fun drainFailures(): List<Outcome.Failed> {
        val out = pending.toList()
        pending.clear()
        return out
    }

    private fun drainOne(): Outcome? = if (pending.isEmpty()) null else pending.removeAt(0)

    private fun raise(channel: String, reason: String) {
        pending.add(Outcome.Failed(channel, reason))
        while (pending.size > MAX_PENDING) pending.removeAt(0)
    }

    /**
     * New-format iff all three hold: exactly two args beyond isFinal, a sentinel-prefixed streamId,
     * and a decimal seq. Legacy frames have arity exactly chunkIdx + 2, so the arity test alone
     * already separates the two worlds; the other two are belts.
     */
    private fun isNewFormat(args: List<String>, chunkIdx: Int): Boolean =
        args.size == chunkIdx + 4 &&
            args[args.size - 2].startsWith(SENTINEL) &&
            args[args.size - 1].isNotEmpty() &&
            args[args.size - 1].all { it in '0'..'9' }

    private fun acceptNew(
        channel: String,
        args: List<String>,
        chunkIdx: Int,
        prefixIndex: Int,
        now: Long
    ): Outcome {
        val streamId = args[args.size - 2]
        val seq = args[args.size - 1].toIntOrNull() ?: return Outcome.None
        val chunk = args[chunkIdx]
        val isFinal = args[chunkIdx + 1] == "1"
        val prefix = if (prefixIndex >= 0) args.getOrElse(prefixIndex) { "" } else null

        if (dropped.containsKey(streamId)) {
            // seq 0 is a genuinely new stream (the phone reused the id after a restart), so it
            // reclaims the key instead of being swallowed for the rest of the tombstone's life.
            if (seq == 0) dropped.remove(streamId)
            else {
                if (isFinal) dropped.remove(streamId) else dropped[streamId] = now
                return Outcome.None
            }
        }

        val partial = streams[streamId] ?: run {
            if (seq != 0) {
                // Joined mid-flight: the prefix is unrecoverable, so completing it would route the
                // payload to the wrong chat. Drop it and say so.
                markDropped(streamId, now)
                return Outcome.Failed(channel, REASON_ORPHAN)
            }
            // A new stream retires any torn legacy remnant on this channel, so the two cannot merge.
            legacy.remove(channel)
            Partial(channel, prefix, StringBuilder(), now, 0).also { streams[streamId] = it }
        }

        if (seq != partial.nextSeq) {
            // No reordering exists on this transport, so a gap is a lost frame, never a late one.
            streams.remove(streamId)
            markDropped(streamId, now)
            return Outcome.Failed(channel, REASON_GAP)
        }

        partial.sb.append(chunk)
        partial.nextSeq = seq + 1
        partial.touchedAt = now

        if (partial.sb.length > MAX_STREAM_CHARS) {
            streams.remove(streamId)
            if (!isFinal) markDropped(streamId, now)
            return Outcome.Failed(channel, REASON_TOO_LARGE)
        }
        evictOverflow(streams, keep = streamId, now = now)

        if (!isFinal) return Outcome.None
        streams.remove(streamId)
        // The prefix is the one observed at seq 0; later chunks repeat it and are ignored.
        return Outcome.Completed(partial.prefix, partial.sb.toString())
    }

    private fun acceptLegacy(
        channel: String,
        args: List<String>,
        chunkIdx: Int,
        prefixIndex: Int,
        now: Long
    ): Outcome {
        // Index reads mirror the original handleChunkedJson exactly: getOrElse "" on every position.
        val prefix = if (prefixIndex >= 0) args.getOrElse(prefixIndex) { "" } else null
        val chunk = args.getOrElse(chunkIdx) { "" }
        val isFinal = args.getOrElse(chunkIdx + 1) { "" } == "1"

        if (dropped.containsKey(channel)) {
            if (isFinal) dropped.remove(channel) else dropped[channel] = now
            return Outcome.None
        }

        val partial = legacy.getOrPut(channel) { Partial(channel, prefix, StringBuilder(), now, 0) }
        partial.sb.append(chunk)
        partial.touchedAt = now

        if (partial.sb.length > MAX_STREAM_CHARS) {
            legacy.remove(channel)
            if (!isFinal) markDropped(channel, now)
            return Outcome.Failed(channel, REASON_TOO_LARGE)
        }
        evictOverflow(legacy, keep = channel, now = now)

        if (!isFinal) return Outcome.None
        legacy.remove(channel)
        // The legacy path reads the prefix from THIS chunk, matching the original behaviour.
        return Outcome.Completed(prefix, partial.sb.toString())
    }

    @Synchronized
    fun clear() {
        streams.clear()
        legacy.clear()
        dropped.clear()
    }

    /** Drops streams silent for STALE_MS. Every drop is queued as a failure, none are lost. */
    private fun sweep(now: Long) {
        val cutoff = now - STALE_MS
        listOf(streams, legacy).forEach { map ->
            map.entries.filter { it.value.touchedAt <= cutoff }.toList().forEach { (key, partial) ->
                map.remove(key)
                markDropped(key, now)
                raise(partial.channel, REASON_STALE)
            }
        }
        // Tombstones expire on the same clock, so a key is never deaf forever.
        dropped.entries.filter { it.value <= cutoff }.map { it.key }.forEach { dropped.remove(it) }
    }

    /** Drops least-recently-touched streams until at most MAX_STREAMS remain. */
    private fun evictOverflow(map: LinkedHashMap<String, Partial>, keep: String, now: Long) {
        while (map.size > MAX_STREAMS) {
            val oldest = map.entries
                .filter { it.key != keep }
                .minByOrNull { it.value.touchedAt } ?: return
            map.remove(oldest.key)
            markDropped(oldest.key, now)
            raise(oldest.value.channel, REASON_OVERFLOW)
        }
    }

    private fun markDropped(key: String, now: Long) {
        dropped[key] = now
        while (dropped.size > MAX_DROPPED) dropped.remove(dropped.keys.first())
    }

    companion object {
        /**
         * Marks a stream id. Must be a control character: chunks are raw JSON substrings and
         * prefixes are chat ids, so any printable sentinel could occur naturally in either.
         */
        const val SENTINEL = "\u0001cs#"

        const val STALE_MS = 60_000L
        const val MAX_STREAMS = 16
        const val MAX_DROPPED = 64
        const val MAX_PENDING = 32

        /** Ceiling on one stream's payload, so a peer that never sends a final chunk cannot grow
         *  a StringBuilder without limit. */
        const val MAX_STREAM_CHARS = 4_000_000

        const val REASON_ORPHAN = "chunk_orphan"
        const val REASON_GAP = "chunk_gap"
        const val REASON_STALE = "chunk_stale"
        const val REASON_OVERFLOW = "chunk_overflow"
        const val REASON_TOO_LARGE = "chunk_too_large"
    }
}
