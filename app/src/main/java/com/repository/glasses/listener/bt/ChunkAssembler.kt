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
     * @param prefixIndex index of the prefix arg, or -1 when the channel carries no prefix.
     * @return the completed payload when this chunk was final, else null.
     */
    @Synchronized
    fun accept(channel: String, args: List<String>, prefixIndex: Int): Completed? {
        sweep()
        // Index reads mirror handleChunkedJson exactly: getOrElse "" on every position.
        val prefix = if (prefixIndex >= 0) args.getOrElse(prefixIndex) { "" } else null
        val chunkIdx = if (prefixIndex >= 0) prefixIndex + 1 else 0
        val chunk = args.getOrElse(chunkIdx) { "" }
        val isFinal = args.getOrElse(chunkIdx + 1) { "" } == "1"

        val partial = buffers.getOrPut(channel) { Partial(StringBuilder(), nowMs()) }
        partial.sb.append(chunk)
        partial.touchedAt = nowMs()
        evictOverflow(keep = channel)

        if (!isFinal) return null
        buffers.remove(channel)
        // The prefix comes from THIS chunk, matching handleChunkedJson's read-it-every-time behaviour.
        return Completed(prefix, partial.sb.toString())
    }

    @Synchronized
    fun clear() = buffers.clear()

    /** Drops partial streams that have received nothing for STALE_MS (clock runs from last chunk). */
    private fun sweep() {
        val cutoff = nowMs() - STALE_MS
        val it = buffers.entries.iterator()
        while (it.hasNext()) if (it.next().value.touchedAt <= cutoff) it.remove()
    }

    /** Drops the least recently touched streams until at most MAX_STREAMS remain. */
    private fun evictOverflow(keep: String) {
        while (buffers.size > MAX_STREAMS) {
            val oldest = buffers.entries
                .filter { it.key != keep }
                .minByOrNull { it.value.touchedAt } ?: return
            buffers.remove(oldest.key)
        }
    }

    companion object {
        const val STALE_MS = 60_000L
        const val MAX_STREAMS = 16
    }
}
