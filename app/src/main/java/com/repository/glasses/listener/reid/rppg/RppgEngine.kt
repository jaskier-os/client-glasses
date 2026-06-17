package com.repository.glasses.listener.reid.rppg

/**
 * Drives a [TrackBuffer] per active face track and exposes the latest smoothed BPM
 * per track.
 *
 * Fed once per camera frame from the capture process (via the AIDL onRppgSamples
 * batch) through [onSamples], and recomputed about once per second through [tick].
 * [bpmFor] reads the latest computed value for display; [activeTrackIds] is a
 * diagnostics/UI view of the live tracks.
 *
 * Lifecycle of a track:
 *   - first sample for a trackingId creates its [TrackBuffer] (via [bufferFactory])
 *     and records lastSeen.
 *   - subsequent samples append and refresh lastSeen.
 *   - [tick] drops any track not seen within [trackTimeoutMs] (measured against the
 *     newest sample timestamp ever observed, NOT wall-clock -- the sample clock and
 *     the tick clock are the same elapsedRealtime domain), otherwise recomputes its
 *     BPM and stores the result (null while the buffer is still measuring).
 *
 * Threading: [onSamples] runs on the binder/Listener thread; [tick] + [bpmFor] +
 * [activeTrackIds] run on the UI/driver thread. All three maps are guarded by a
 * single [lock]; the per-track compute inside [tick] holds the lock for the whole
 * pass, which is acceptable because the buffers are small (a 10 s window) and there
 * are only a handful of tracks. No buffer is shared across tracks, so there is no
 * finer-grained locking to gain.
 */
class RppgEngine(
    private val trackTimeoutMs: Long = 2_000L,
    private val bufferFactory: () -> TrackBuffer = { TrackBuffer() },
) {
    private val lock = Any()
    private val buffers = HashMap<Long, TrackBuffer>()
    private val lastSeenMs = HashMap<Long, Long>()
    private val bpm = HashMap<Long, Float>()

    /** Newest sample timestamp ever observed across all tracks; the timeout anchor. */
    private var newestSampleMs: Long = Long.MIN_VALUE

    /**
     * Feed one frame's batch of per-track RGB means. [trackingIds] and [rgb] are
     * parallel: track i has mean RGB at rgb[i*3 .. i*3+2]; [tMs] is the frame
     * timestamp (elapsedRealtime ms). A malformed batch (trackingIds.size*3 !=
     * rgb.size) is skipped without mutating state.
     */
    fun onSamples(trackingIds: LongArray, rgb: FloatArray, tMs: Long) {
        if (trackingIds.size * 3 != rgb.size) return
        synchronized(lock) {
            if (tMs > newestSampleMs) newestSampleMs = tMs
            for (i in trackingIds.indices) {
                val id = trackingIds[i]
                val buf = buffers.getOrPut(id) { bufferFactory() }
                buf.addSample(tMs, rgb[i * 3], rgb[i * 3 + 1], rgb[i * 3 + 2])
                lastSeenMs[id] = tMs
            }
        }
    }

    /**
     * Recompute BPM for every live track. Drops any track not fed within
     * [trackTimeoutMs] of the newest sample seen (passing [nowMs] only advances that
     * anchor; sample timestamps still win if they are ahead). Call about once per
     * second.
     */
    fun tick(nowMs: Long) {
        synchronized(lock) {
            val anchor = maxOf(nowMs, newestSampleMs)
            val it = buffers.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                val id = entry.key
                val seen = lastSeenMs[id] ?: Long.MIN_VALUE
                if (anchor - seen > trackTimeoutMs) {
                    it.remove()
                    lastSeenMs.remove(id)
                    bpm.remove(id)
                    continue
                }
                val computed = entry.value.compute()
                if (computed != null) bpm[id] = computed else bpm.remove(id)
            }
        }
    }

    /** Latest smoothed BPM for a track, or null if measuring / unknown / dropped. */
    fun bpmFor(trackingId: Long): Float? = synchronized(lock) { bpm[trackingId] }

    /** Live track ids (diagnostics / UI). */
    fun activeTrackIds(): Set<Long> = synchronized(lock) { buffers.keys.toSet() }

    fun reset() {
        synchronized(lock) {
            buffers.clear()
            lastSeenMs.clear()
            bpm.clear()
            newestSampleMs = Long.MIN_VALUE
        }
    }
}
