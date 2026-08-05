package com.repository.glasses.listener.input.remote

/**
 * Owns every policy that is common to all remote input devices: session lifecycle, sequence
 * ordering, staleness, rate limiting, and the hand-off to the UI.
 *
 * A class, not an object: a static mutable sink and session map could not be reset between the unit
 * tests this design depends on, and it matches how `CaptureBridge` and `FunctionButtonHandler` are
 * already owned by `ListenerService`.
 *
 * **This class contains no keycode, no `FocusState`, and no Android UI type**, and it must stay that
 * way. Everything it forwards is a [RemoteInputEvent]; what that event MEANS is decided entirely by
 * the sink. The moment the router starts mapping actions to keycodes or special-casing a focus
 * state, the abstraction has become decoration and a second input device stops being free.
 *
 * ## Threading
 * - [onFrame] runs on whatever thread the source's transport delivers on. All session state is
 *   mutated only there, only under [sessionLock].
 * - The sink is invoked on the UI thread via [MainThreadEventQueue].
 * - The sink reference is read only at delivery time, never at ingress, so an event cannot be
 *   admitted against a sink that has since gone away.
 *
 * @param clock monotonic milliseconds. Injected so tests can drive staleness and expiry
 *        deterministically instead of sleeping.
 * @param post schedules a runnable on the UI thread.
 * @param log one-line diagnostics. Rate-limited internally for anything an attacker can trigger.
 */
class RemoteInputRouter(
    private val ttlMs: Int = DEFAULT_TTL_MS,
    private val sessionExpiryMs: Long = DEFAULT_SESSION_EXPIRY_MS,
    private val maxEventsPerSecond: Int = DEFAULT_MAX_EVENTS_PER_SECOND,
    private val clock: () -> Long,
    post: (Runnable) -> Unit,
    private val log: (String) -> Unit = {},
) {

    /** Per-source session state. Mutated only under [sessionLock]. */
    private class Session(
        val sid: Long,
        /** Router-clock time at which this session's OPEN arrived: the age baseline. */
        val openedAtMs: Long,
        /** The source's own clock reading in its OPEN frame: the other half of the age baseline. */
        val openWms: Long,
    ) {
        var lastSeq: Long = 0
        var lastEventMs: Long = 0
        var seenOpen: Boolean = false
    }

    private class SourceState(val source: InputSource) {
        val sessions = LinkedHashMap<Long, Session>()
        /** sids retired recently, refused re-adoption so a captured OPEN cannot resurrect one. */
        val recentSids = LinkedHashMap<Long, Long>()
        var rateWindowStartMs = 0L
        var rateWindowCount = 0
        var dropped = 0L
        var lastRejectLogMs = 0L
        var suppressedRejectLogs = 0L
    }

    private val sessionLock = Any()
    private val sources = LinkedHashMap<String, SourceState>()

    /** Global across all sources, so a source cannot be starved by a chatty sibling. */
    private var globalRateWindowStartMs = 0L
    private var globalRateWindowCount = 0

    @Volatile
    private var sink: RemoteInputSink? = null

    private val queue = MainThreadEventQueue(post = post).also { q ->
        q.setDeliverer { event -> sink?.onRemoteInput(event) }
    }

    // --- source registration: THE extension point ---

    /**
     * Register [source] and start receiving its frames.
     *
     * Registration is also the source allowlist: a frame naming an unregistered source is rejected.
     * That is deliberate -- it means adding a device never involves editing a hard-coded set here.
     */
    fun registerSource(source: InputSource) {
        require(RemoteInputAuth.isValidSourceId(source.sourceId)) {
            "invalid sourceId '${source.sourceId}': must match [a-z0-9_]{1,16}"
        }
        synchronized(sessionLock) {
            require(!sources.containsKey(source.sourceId)) {
                "source '${source.sourceId}' already registered"
            }
            sources[source.sourceId] = SourceState(source)
        }
        source.attach { frame -> onFrame(frame) }
        log("remote input: source '${source.sourceId}' registered")
    }

    fun unregisterSource(source: InputSource) {
        source.detach()
        synchronized(sessionLock) { sources.remove(source.sourceId) }
        log("remote input: source '${source.sourceId}' unregistered")
    }

    fun registeredSourceIds(): Set<String> = synchronized(sessionLock) { sources.keys.toSet() }

    // --- sink attach / detach ---

    fun setSink(s: RemoteInputSink) {
        sink = s
        publishStatusAll()
    }

    /**
     * Clear the sink, but only if [s] is the one currently installed.
     *
     * A bare `setSink(null)` from an outgoing screen's teardown can run AFTER the incoming screen
     * has installed its own sink, unregistering the live one. Taking the instance makes that
     * impossible.
     */
    fun clearSink(s: RemoteInputSink) {
        if (sink === s) {
            sink = null
            // Drop the backlog. Replaying it into whatever attaches next produces a burst of stale
            // actions, which is precisely what the staleness rules exist to prevent.
            queue.clear()
            publishStatusAll()
        }
    }

    val hasSink: Boolean get() = sink != null

    // --- ingress ---

    /** Called by a source for every frame it decodes. Never throws. */
    fun onFrame(frame: RemoteInputFrame) {
        try {
            handleFrame(frame)
        } catch (e: Exception) {
            log("remote input: frame handling failed: ${e.javaClass.simpleName}")
        }
    }

    private fun handleFrame(frame: RemoteInputFrame) {
        val now = clock()
        var deliverable: RemoteInputEvent? = null
        var statusTarget: InputSource? = null

        synchronized(sessionLock) {
            if (frame.v != RemoteInputEvent.PROTOCOL_VERSION) {
                rejectLocked(null, "version ${frame.v} not supported", now)
                return
            }
            val state = sources[frame.sourceId]
            if (state == null) {
                // Unregistered source id. Rejected before any per-source bookkeeping exists, so an
                // attacker cannot grow the state map by inventing ids.
                rejectLocked(null, "unknown source '${frame.sourceId}'", now)
                return
            }
            if (!admitRateLocked(state, now)) {
                state.dropped++
                rejectLocked(state, "rate limit exceeded", now)
                return
            }

            when (frame) {
                is RemoteInputFrame.Lifecycle -> {
                    handleLifecycleLocked(state, frame, now)
                    statusTarget = state.source
                }
                is RemoteInputFrame.Action -> {
                    deliverable = admitActionLocked(state, frame, now)
                    if (deliverable == null) state.dropped++
                }
            }
        }

        deliverable?.let { queue.enqueue(it) }
        statusTarget?.let { publishStatus(it) }
    }

    private fun handleLifecycleLocked(
        state: SourceState,
        frame: RemoteInputFrame.Lifecycle,
        now: Long,
    ) {
        when (frame.kind) {
            RemoteLifecycle.OPEN -> {
                if (state.sessions[frame.sid]?.seenOpen == true ||
                    state.recentSids.containsKey(frame.sid)
                ) {
                    // A session id is used once. Without this a captured OPEN could be replayed to
                    // reset the sequence baseline and re-admit a whole captured burst.
                    rejectLocked(state, "duplicate OPEN for sid ${frame.sid}", now)
                    return
                }
                expireLocked(state, now)
                while (state.sessions.size >= MAX_SESSIONS_PER_SOURCE) {
                    val oldest = state.sessions.keys.first()
                    state.sessions.remove(oldest)
                    rememberSidLocked(state, oldest, now)
                }
                state.sessions[frame.sid] = Session(frame.sid, now, frame.wms).apply {
                    seenOpen = true
                    lastSeq = frame.seq
                    lastEventMs = now
                }
                log("remote input: session open src=${frame.sourceId} sid=${frame.sid}")
            }
            RemoteLifecycle.CLOSE -> {
                if (state.sessions.remove(frame.sid) != null) {
                    rememberSidLocked(state, frame.sid, now)
                    log("remote input: session close src=${frame.sourceId} sid=${frame.sid}")
                }
            }
            RemoteLifecycle.PING -> {
                val session = state.sessions[frame.sid]
                if (session == null) {
                    rejectLocked(state, "PING for unknown sid ${frame.sid}", now)
                    return
                }
                if (seqDiff(frame.seq, session.lastSeq) > 0) session.lastSeq = frame.seq
                session.lastEventMs = now
            }
        }
        expireLocked(state, now)
    }

    /** Returns the event to deliver, or null if the frame was dropped. */
    private fun admitActionLocked(
        state: SourceState,
        frame: RemoteInputFrame.Action,
        now: Long,
    ): RemoteInputEvent? {
        expireLocked(state, now)
        val session = state.sessions[frame.sid]
        if (session == null) {
            // No OPEN, or the session expired. Never adopt a session implicitly: doing so would let
            // a replayed burst establish its own baseline.
            rejectLocked(state, "action for unknown sid ${frame.sid}", now)
            return null
        }

        val diff = seqDiff(frame.seq, session.lastSeq)
        if (diff <= 0) {
            // Duplicate or reordered. Scrolls are idempotent by sequence; discrete actions become
            // at-most-once, which is the correct choice -- a replayed SELECT could confirm
            // something the user never chose.
            rejectLocked(state, "stale seq ${frame.seq} (last ${session.lastSeq})", now)
            return null
        }
        if (diff > 1) {
            // A gap means lost frames. Apply anyway and resynchronize: lost scroll is lost
            // distance, and a lost discrete action must never be synthesized.
            log("remote input: seq gap ${diff - 1} src=${frame.sourceId} sid=${frame.sid}")
        }

        val ageMs = ageOf(session, frame.wms, now)
        if (ageMs > ttlMs) {
            rejectLocked(state, "stale by ${ageMs}ms (ttl $ttlMs)", now)
            session.lastSeq = frame.seq
            session.lastEventMs = now
            return null
        }

        session.lastSeq = frame.seq
        session.lastEventMs = now
        return RemoteInputEvent(
            v = frame.v,
            action = frame.action,
            delta = frame.delta,
            sourceId = frame.sourceId,
            sid = frame.sid,
            seq = frame.seq,
            ageMs = ageMs,
        )
    }

    /**
     * In-flight time, computed entirely on the SOURCE's clock.
     *
     * `(now - openedAt)` is how long the session has existed by our clock; `(wms - openWms)` is how
     * far into the session the source says this event was produced. The difference is the delay,
     * with no cross-device clock comparison, and no dependence on a timestamp a transport could
     * freeze while queueing.
     */
    private fun ageOf(session: Session, wms: Long, now: Long): Int {
        val sinceOpenLocal = now - session.openedAtMs
        val sinceOpenRemote = u32Delta(wms, session.openWms)
        val age = sinceOpenLocal - sinceOpenRemote
        return age.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    // --- session expiry ---

    private fun expireLocked(state: SourceState, now: Long) {
        val it = state.sessions.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            // Re-checked under the lock so an event that arrived moments before this ran cannot
            // have its session killed out from under it.
            if (now - e.value.lastEventMs >= sessionExpiryMs) {
                it.remove()
                rememberSidLocked(state, e.key, now)
                log("remote input: session expired src=${state.source.sourceId} sid=${e.key}")
            }
        }
        val rit = state.recentSids.entries.iterator()
        while (rit.hasNext()) {
            if (now - rit.next().value >= RECENT_SID_TTL_MS) rit.remove()
        }
    }

    private fun rememberSidLocked(state: SourceState, sid: Long, now: Long) {
        state.recentSids[sid] = now
        while (state.recentSids.size > MAX_RECENT_SIDS) {
            state.recentSids.remove(state.recentSids.keys.first())
        }
    }

    /** Drop all session state, e.g. when the transport dropped and frames may have been lost. */
    fun clearAllSessions(reason: String) {
        synchronized(sessionLock) {
            var had = false
            for (state in sources.values) {
                if (state.sessions.isNotEmpty()) had = true
                state.sessions.clear()
            }
            if (had) log("remote input: sessions cleared ($reason)")
        }
        queue.clear()
        publishStatusAll()
    }

    fun hasOpenSession(sourceId: String): Boolean = synchronized(sessionLock) {
        val state = sources[sourceId] ?: return false
        expireLocked(state, clock())
        state.sessions.isNotEmpty()
    }

    fun anyOpenSession(): Boolean = synchronized(sessionLock) {
        val now = clock()
        sources.values.any { expireLocked(it, now); it.sessions.isNotEmpty() }
    }

    // --- rate limiting ---

    private fun admitRateLocked(state: SourceState, now: Long): Boolean {
        if (now - globalRateWindowStartMs >= RATE_WINDOW_MS) {
            globalRateWindowStartMs = now
            globalRateWindowCount = 0
        }
        if (now - state.rateWindowStartMs >= RATE_WINDOW_MS) {
            state.rateWindowStartMs = now
            state.rateWindowCount = 0
        }
        // The global limit is checked as well as the per-source one. A per-source limit alone is no
        // limit at all when the source field is attacker-supplied.
        if (globalRateWindowCount >= maxEventsPerSecond * MAX_SOURCES_FOR_GLOBAL_BUDGET) return false
        if (state.rateWindowCount >= maxEventsPerSecond) return false
        globalRateWindowCount++
        state.rateWindowCount++
        return true
    }

    // --- rejection logging ---

    /**
     * Log a rejection at most once per second per source. Logging every rejected frame is itself a
     * denial of service: the persistent log lives on internal storage and an attacker controls the
     * frame rate.
     */
    private fun rejectLocked(state: SourceState?, reason: String, now: Long) {
        if (state == null) {
            if (now - unknownRejectLogMs < REJECT_LOG_INTERVAL_MS) {
                suppressedUnknownRejects++
                return
            }
            unknownRejectLogMs = now
            val suppressed = suppressedUnknownRejects
            suppressedUnknownRejects = 0
            log("remote input: rejected -- $reason" + suffix(suppressed))
            return
        }
        if (now - state.lastRejectLogMs < REJECT_LOG_INTERVAL_MS) {
            state.suppressedRejectLogs++
            return
        }
        state.lastRejectLogMs = now
        val suppressed = state.suppressedRejectLogs
        state.suppressedRejectLogs = 0
        log("remote input: rejected src=${state.source.sourceId} -- $reason" + suffix(suppressed))
    }

    private fun suffix(suppressed: Long) = if (suppressed > 0) " (+$suppressed suppressed)" else ""

    private var unknownRejectLogMs = 0L
    private var suppressedUnknownRejects = 0L

    // --- status backchannel ---

    private fun publishStatusAll() {
        val targets = synchronized(sessionLock) { sources.values.map { it.source } }
        targets.forEach { publishStatus(it) }
    }

    private fun publishStatus(source: InputSource) {
        val status = synchronized(sessionLock) {
            val state = sources[source.sourceId] ?: return
            RemoteInputStatus(
                sessionOpen = state.sessions.isNotEmpty(),
                sinkAttached = sink != null,
                droppedTotal = state.dropped + queue.dropped,
            )
        }
        try {
            source.onStatus(status)
        } catch (e: Exception) {
            log("remote input: status push failed: ${e.javaClass.simpleName}")
        }
    }

    fun droppedFor(sourceId: String): Long =
        synchronized(sessionLock) { sources[sourceId]?.dropped ?: 0L }

    companion object {
        /**
         * Maximum in-flight age for an actionable event.
         *
         * Floor value from the plan. The real figure is meant to come from the Data Layer latency
         * measurement (plan task 0.4), which belongs to the watch/phone workstream; until that lands
         * this stays at the floor. Raising it is a one-line change here.
         */
        const val DEFAULT_TTL_MS = 400

        /** A session with no event and no PING for this long is gone. */
        const val DEFAULT_SESSION_EXPIRY_MS = 20_000L

        const val DEFAULT_MAX_EVENTS_PER_SECOND = 25
        const val RATE_WINDOW_MS = 1000L

        /** Headroom for the global budget relative to a single source's allowance. */
        const val MAX_SOURCES_FOR_GLOBAL_BUDGET = 2

        /** Per-source session cap. Bounded so a peer cannot grow the map by minting session ids. */
        const val MAX_SESSIONS_PER_SOURCE = 2

        const val MAX_RECENT_SIDS = 16
        const val RECENT_SID_TTL_MS = 5 * 60_000L
        const val REJECT_LOG_INTERVAL_MS = 1000L

        /**
         * Wrap-safe comparison of two uint32 sequence numbers.
         *
         * A plain `a <= b` deadlocks the source forever when the counter wraps past 2^32: every
         * subsequent frame looks stale. Taking the difference as a signed 32-bit value gives the
         * correct answer across the wrap.
         */
        fun seqDiff(seq: Long, lastSeq: Long): Int = (seq - lastSeq).toInt()

        /** Elapsed time between two uint32 clock readings, correct across the wrap. */
        fun u32Delta(now: Long, then: Long): Long = ((now - then) and 0xFFFFFFFFL)
    }
}
