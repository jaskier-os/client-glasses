package com.repository.glasses.listener.bt

/**
 * Pure, Android-free chunk reassembly, extracted from GlassesBtClient.handleChunkedJson.
 *
 * The wire layout is UNCHANGED (buffers are still keyed by channel) so this build stays compatible
 * with the currently deployed phone. Two purely local bounds are added: partial streams are evicted
 * after STALE_MS, and at most MAX_STREAMS partial streams are held (oldest evicted first). Before
 * this, chunkBuffers was unbounded in both size and age and was cleared only on disconnect.
 *
 * Threading: every entry point is @Synchronized and there is no async hop. MessageRelay dispatches
 * on a single reader thread today, and this class must not rely on that staying true.
 */
class ChunkAssembler(private val nowMs: () -> Long = System::currentTimeMillis) {

    data class Completed(val prefix: String?, val json: String)

    private class Partial(val sb: StringBuilder, var touchedAt: Long)

    private val buffers = LinkedHashMap<String, Partial>()

    /**
     * Channels whose partial stream was dropped (age, overflow or size) while the peer was still
     * sending. Without this, the next chunk would start a fresh buffer and the stream's final chunk
     * would complete a TRUNCATED payload, which is worse than not completing at all. Tombstoned
     * chunks are discarded until the stream's own final chunk arrives and retires the tombstone.
     */
    private val dropped = LinkedHashMap<String, Long>()

    /**
     * @param prefixIndex index of the prefix arg, or -1 when the channel carries no prefix.
     * @return the completed payload when this chunk was final, else null.
     */
    @Synchronized
    fun accept(channel: String, args: List<String>, prefixIndex: Int): Completed? {
        val now = nowMs()
        sweep(now)
        // Index reads mirror handleChunkedJson exactly: getOrElse "" on every position.
        val prefix = if (prefixIndex >= 0) args.getOrElse(prefixIndex) { "" } else null
        val chunkIdx = if (prefixIndex >= 0) prefixIndex + 1 else 0
        val chunk = args.getOrElse(chunkIdx) { "" }
        val isFinal = args.getOrElse(chunkIdx + 1) { "" } == "1"

        if (dropped.containsKey(channel)) {
            // Mid-stream remnant of a dropped stream: never complete it truncated.
            if (isFinal) dropped.remove(channel) else dropped[channel] = now
            return null
        }

        val partial = buffers.getOrPut(channel) { Partial(StringBuilder(), now) }
        partial.sb.append(chunk)
        partial.touchedAt = now
        if (partial.sb.length > MAX_STREAM_CHARS) {
            drop(channel, now)
            // A final chunk ends the stream here and now, so it leaves no tombstone behind.
            if (isFinal) dropped.remove(channel)
            return null
        }
        evictOverflow(keep = channel, now = now)

        if (!isFinal) return null
        buffers.remove(channel)
        // The prefix comes from THIS chunk, matching handleChunkedJson's read-it-every-time behaviour.
        return Completed(prefix, partial.sb.toString())
    }

    @Synchronized
    fun clear() {
        buffers.clear()
        dropped.clear()
    }

    /** Drops partial streams that have received nothing for STALE_MS (clock runs from last chunk). */
    private fun sweep(now: Long) {
        val cutoff = now - STALE_MS
        buffers.entries.filter { it.value.touchedAt <= cutoff }.forEach { drop(it.key, now) }
        // Tombstones expire on the same silence clock, so a channel is never deaf forever.
        dropped.entries.filter { it.value <= cutoff }.map { it.key }.forEach { dropped.remove(it) }
    }

    /** Drops the least recently touched streams until at most MAX_STREAMS remain. */
    private fun evictOverflow(keep: String, now: Long) {
        while (buffers.size > MAX_STREAMS) {
            val oldest = buffers.entries
                .filter { it.key != keep }
                .minByOrNull { it.value.touchedAt } ?: return
            drop(oldest.key, now)
        }
    }

    private fun drop(channel: String, now: Long) {
        buffers.remove(channel)
        dropped[channel] = now
        while (dropped.size > MAX_STREAMS) dropped.remove(dropped.keys.first())
    }

    companion object {
        const val STALE_MS = 60_000L
        const val MAX_STREAMS = 16
        /** Hard ceiling on one stream's accumulated payload, so a peer that never sends a final
         *  chunk cannot grow a StringBuilder without limit. */
        const val MAX_STREAM_CHARS = 4_000_000
    }
}
