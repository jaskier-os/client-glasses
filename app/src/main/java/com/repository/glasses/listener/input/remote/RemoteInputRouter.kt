package com.repository.glasses.listener.input.remote

import java.util.concurrent.atomic.AtomicReference

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
 * - [onFrame] runs on whatever thread the source's transport delivers on, and transports deliver on
 *   thread POOLS: two frames from one source can be in flight at once.
 * - Session state is mutated only under [sessionLock], and admission and enqueue happen together
 *   inside it, so delivery order matches admission order. Ordering the sequence check without
 *   ordering the hand-off would let a SELECT overtake the scroll that preceded it, and the SELECT
 *   would then act on something other than what the user saw.
 * - The sink is invoked on the UI thread via [MainThreadEventQueue], and its reference is read only
 *   at delivery time, never at ingress.
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
    private val store: SessionStore = InMemorySessionStore(),
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
        var lastSeq: Long,
    ) {
        var lastEventMs: Long = 0

        /** The source's own clock stamp on the previous ACTION, for jitter-free tap timing. */
        var lastActionWms: Long = -1L
    }

    private class SourceState(val source: InputSource) {
        /**
         * The one live session, or null.
         *
         * Exactly one, because a source's session id is a persisted monotonically increasing
         * counter: a newer session always supersedes the previous one, so there is never a second
         * to keep. This also means an attacker cannot grow any per-session structure.
         */
        var session: Session? = null

        /**
         * Sequence floor already written durably. Runs AHEAD of [appliedSeq] by up to
         * [SEQ_RESERVATION], so it is only ever consulted after a restart -- never to gate a live
         * session, which would refuse the source's next 256 frames and stall it completely.
         */
        var reservedSeq = 0L

        /**
         * Highest sequence actually applied for the current sid, retained across session expiry.
         *
         * A session that expires on the idle timer and is then resumed must NOT rewind: this is the
         * in-process half of the same guarantee [reservedSeq] provides across a restart.
         */
        var appliedSeq = 0L

        /** The sid [appliedSeq] belongs to. */
        var appliedSid = SessionStore.NO_SID

        var rateWindowStartMs = 0L
        var rateWindowCount = 0
        var lifecycleWindowStartMs = 0L
        var lifecycleWindowCount = 0
        var dropped = 0L
        var lastRejectLogMs = 0L
        var suppressedRejectLogs = 0L
    }

    private val sessionLock = Any()
    private val sources = LinkedHashMap<String, SourceState>()

    /** Global across all sources, so a source cannot be starved by a chatty sibling. */
    private var globalRateWindowStartMs = 0L
    private var globalRateWindowCount = 0

    /**
     * Compare-and-set rather than a plain field: `clearSink` must not be able to null a sink that
     * was installed between its comparison and its store, which is exactly the late-teardown race
     * it exists to prevent.
     */
    private val sinkRef = AtomicReference<RemoteInputSink?>(null)

    private val queue = MainThreadEventQueue(post = post).also { q ->
        q.setDeliverer { event -> sinkRef.get()?.onRemoteInput(event) }
    }

    // --- source registration: THE extension point ---

    /**
     * Register [source] and start receiving its frames.
     *
     * Registration is also the source allowlist: a frame naming an unregistered source is rejected.
     * That is deliberate -- it means adding a device never involves editing a hard-coded set here.
     */
    fun registerSource(source: InputSource) {
        require(InputSource.isValidSourceId(source.sourceId)) {
            "invalid sourceId '${source.sourceId}': must match ${InputSource.SOURCE_ID_PATTERN}"
        }
        synchronized(sessionLock) {
            require(!sources.containsKey(source.sourceId)) {
                "source '${source.sourceId}' already registered"
            }
            require(sources.size < MAX_SOURCES) { "too many input sources registered" }
            sources[source.sourceId] = SourceState(source).apply {
                // Resume the durable floor written before the last restart. Without this the very
                // first session after a reboot would accept a captured burst in full.
                reservedSeq = store.seqFloor(source.sourceId)
                appliedSeq = reservedSeq
                appliedSid = store.highestSid(source.sourceId)
            }
        }
        source.attach { frame -> onFrame(frame) }
        log("remote input: source '${source.sourceId}' registered")
    }

    fun unregisterSource(source: InputSource) {
        source.detach()
        synchronized(sessionLock) { sources.remove(source.sourceId) }
        log("remote input: source '${source.sourceId}' unregistered")
    }

    // --- sink attach / detach ---

    fun setSink(s: RemoteInputSink) {
        sinkRef.set(s)
        publishStatusAll()
    }

    /**
     * Announce to every source whether a delivered event would actually be acted on.
     *
     * Separate from [setSink]/[clearSink] because holding the router's sink and having a live UI
     * are not the same thing once the sink is a cross-process bridge: the bridge stays installed
     * for the service's whole life while the UI process comes and goes. The owner of that
     * distinction is the bridge, so it drives this.
     */
    /**
     * Whether a delivered event would REALLY be acted on.
     *
     * Not simply `sinkRef != null`. When the sink is a cross-process bridge it installs itself once
     * and stays installed for the service's whole life, so `sinkRef` is permanently non-null and
     * says nothing about whether a UI process exists. Left as an overridable hook so the component
     * that owns that knowledge supplies it, and so the status channel and the dedicated sink-state
     * channel cannot report contradictory values.
     */
    @Volatile
    var sinkReallyAttached: () -> Boolean = { sinkRef.get() != null }

    fun publishSinkAttached(attached: Boolean) {
        val targets = synchronized(sessionLock) { sources.values.map { it.source } }
        for (source in targets) {
            try {
                source.onSinkAttached(attached)
            } catch (e: Exception) {
                log("remote input: sink-state push failed for '${source.sourceId}': ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Clear the sink, but only if [s] is the one currently installed.
     *
     * A bare `setSink(null)` from an outgoing screen's teardown can run AFTER the incoming screen
     * has installed its own sink, unregistering the live one. Taking the instance, and swapping
     * atomically, makes that impossible.
     */
    fun clearSink(s: RemoteInputSink) {
        if (sinkRef.compareAndSet(s, null)) {
            // Drop the backlog. Replaying it into whatever attaches next produces a burst of stale
            // actions, which is precisely what the staleness rules exist to prevent.
            queue.clear()
            publishStatusAll()
        }
    }

    // --- ingress ---

    /** Called by a source for every frame it decodes. Never throws. */
    private fun onFrame(frame: RemoteInputFrame) {
        try {
            handleFrame(frame)
        } catch (e: Exception) {
            log("remote input: frame handling failed: ${e.javaClass.simpleName}")
        }
    }

    private fun handleFrame(frame: RemoteInputFrame) {
        val now = clock()
        var statusTarget: InputSource? = null

        synchronized(sessionLock) {
            if (frame.v != PROTOCOL_VERSION) {
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

            when (frame) {
                is RemoteInputFrame.Lifecycle -> {
                    // Lifecycle frames draw on their own small budget rather than the action one.
                    // Sharing a budget lets an action flood starve a source's own keepalives, which
                    // expires the user's session -- a denial of service driven by the victim's
                    // own traffic.
                    if (!admitLifecycleRateLocked(state, now)) {
                        state.dropped++
                        rejectLocked(state, "lifecycle rate limit exceeded", now)
                        return
                    }
                    handleLifecycleLocked(state, frame, now)
                    statusTarget = state.source
                }
                is RemoteInputFrame.Action -> {
                    if (!admitRateLocked(state, now)) {
                        state.dropped++
                        rejectLocked(state, "rate limit exceeded", now)
                        return
                    }
                    val event = admitActionLocked(state, frame, now)
                    if (event == null) {
                        state.dropped++
                    } else {
                        // Enqueued INSIDE the lock so delivery order matches admission order.
                        // MainThreadEventQueue takes only its own lock and posts afterwards, and
                        // nothing it calls reaches back into sessionLock, so this cannot deadlock.
                        queue.enqueue(event)
                    }
                }
            }
        }

        statusTarget?.let { publishStatus(it) }
    }

    private fun handleLifecycleLocked(
        state: SourceState,
        frame: RemoteInputFrame.Lifecycle,
        now: Long,
    ) {
        expireLocked(state, now)
        val srcId = state.source.sourceId
        when (frame.kind) {
            RemoteLifecycle.OPEN -> {
                if (frame.sid == SessionStore.NO_SID) {
                    rejectLocked(state, "session id 0 is reserved", now)
                    return
                }
                val highest = store.highestSid(srcId)
                val sidDiff = seqDiff(frame.sid, highest)
                when {
                    highest != SessionStore.NO_SID && sidDiff < 0 -> {
                        // A session id older than one already accepted. The source's counter only
                        // ever increases, so this is a captured OPEN being replayed.
                        rejectLocked(state, "replayed OPEN sid=${frame.sid} (highest $highest)", now)
                        return
                    }
                    highest != SessionStore.NO_SID && sidDiff == 0 -> {
                        // Same session, reopened: a retransmitted OPEN, or a resume after the idle
                        // timer expired the session while the source still considered it live.
                        // Accept, but PRESERVE the sequence high-water mark -- resetting it here is
                        // exactly what would let a captured burst replay in full.
                        //
                        // The floor comes from what was actually APPLIED, not from the write-ahead
                        // reservation: gating a live session on the reservation would refuse the
                        // source's next SEQ_RESERVATION frames and stall it.
                        val applied =
                            if (state.appliedSid == frame.sid) state.appliedSeq else frame.seq
                        val floor = if (seqDiff(applied, frame.seq) > 0) applied else frame.seq
                        val existing = state.session
                        if (existing != null && existing.sid == frame.sid) {
                            existing.lastEventMs = now
                            if (seqDiff(floor, existing.lastSeq) > 0) existing.lastSeq = floor
                        } else {
                            state.session = Session(frame.sid, now, frame.wms, floor)
                                .apply { lastEventMs = now }
                            log("remote input: session resume src=$srcId sid=${frame.sid}")
                        }
                        state.appliedSid = frame.sid
                        state.appliedSeq = floor
                        return
                    }
                }
                // A genuinely new session. Persist BEFORE the first event is acted on: a crash
                // between accepting and persisting would reopen the window this closes.
                store.adoptSession(srcId, frame.sid, frame.seq)
                state.reservedSeq = frame.seq
                state.appliedSid = frame.sid
                state.appliedSeq = frame.seq
                state.session = Session(frame.sid, now, frame.wms, frame.seq)
                    .apply { lastEventMs = now }
                log("remote input: session open src=$srcId sid=${frame.sid}")
            }
            RemoteLifecycle.CLOSE -> {
                val session = state.session
                if (session == null || session.sid != frame.sid) return
                if (seqDiff(frame.seq, session.lastSeq) <= 0) {
                    // A replayed CLOSE would otherwise end the user's live session on demand.
                    rejectLocked(state, "stale CLOSE seq ${frame.seq}", now)
                    return
                }
                session.lastSeq = frame.seq
                persistSeqLocked(state, frame.seq)
                state.session = null
                log("remote input: session close src=$srcId sid=${frame.sid}")
            }
            RemoteLifecycle.PING -> {
                val session = state.session
                if (session == null || session.sid != frame.sid) {
                    rejectLocked(state, "PING for unknown sid ${frame.sid}", now)
                    return
                }
                if (seqDiff(frame.seq, session.lastSeq) > 0) {
                    session.lastSeq = frame.seq
                    persistSeqLocked(state, frame.seq)
                }
                session.lastEventMs = now
            }
        }
    }

    /**
     * Advance the durable sequence floor, in reservations rather than per event.
     *
     * The reservation runs AHEAD of what has actually been applied, so a crash can only make the
     * router refuse frames it might already have accepted -- never accept one twice. That asymmetry
     * is deliberate: the worst case is a few lost detents inside one session, never a replayed tap.
     * A synchronous write per scroll detent would be felt on this hot path.
     */
    private fun persistSeqLocked(state: SourceState, seq: Long) {
        if (seqDiff(seq, state.appliedSeq) > 0) state.appliedSeq = seq
        if (seqDiff(seq, state.reservedSeq) <= 0) return
        val floor = seq + SEQ_RESERVATION
        state.reservedSeq = floor
        store.reserveSeq(state.source.sourceId, floor)
    }

    /** Returns the event to deliver, or null if the frame was dropped. */
    private fun admitActionLocked(
        state: SourceState,
        frame: RemoteInputFrame.Action,
        now: Long,
    ): RemoteInputEvent? {
        expireLocked(state, now)
        val session = state.session
        if (session == null || session.sid != frame.sid) {
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

        session.lastSeq = frame.seq
        session.lastEventMs = now
        persistSeqLocked(state, frame.seq)

        val ageMs = ageOf(session, frame.wms, now)
        if (ageMs > ttlMs) {
            rejectLocked(state, "stale by ${ageMs}ms (ttl $ttlMs)", now)
            // The stamp still advances: a dropped event is still an event the user produced, and
            // the NEXT one must be timed from it, not from whatever preceded the drop.
            session.lastActionWms = frame.wms
            return null
        }

        // Interval on the SOURCE's clock, so transport jitter cannot turn a double tap into two
        // singles. Clamped at zero because a source is only promised to be monotonic, not strictly
        // increasing, and two events stamped in the same millisecond are legitimate.
        val sinceLastMs = if (session.lastActionWms < 0) RemoteInputEvent.NO_PREDECESSOR
        else u32Delta(frame.wms, session.lastActionWms)
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()
        session.lastActionWms = frame.wms

        return RemoteInputEvent(
            action = frame.action,
            delta = frame.delta,
            sourceId = frame.sourceId,
            sid = frame.sid,
            seq = frame.seq,
            ageMs = ageMs,
            sinceLastMs = sinceLastMs,
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

    // --- session lifetime ---

    private fun expireLocked(state: SourceState, now: Long) {
        val session = state.session ?: return
        // Re-checked under the lock, so an event that arrived moments before this ran cannot have
        // its session killed out from under it.
        if (now - session.lastEventMs < sessionExpiryMs) return
        // The sequence floor is already durable, so a later resume of this same sid cannot rewind.
        persistSeqLocked(state, session.lastSeq)
        state.session = null
        log("remote input: session expired src=${state.source.sourceId} sid=${session.sid}")
    }

    /**
     * Drop live session state, e.g. when the transport dropped and frames may have been lost.
     *
     * The durable sid and sequence floor are deliberately NOT cleared: a burst captured before the
     * drop must still be unreplayable after it, and the source reopening with the same sid resumes
     * from the retained floor rather than from zero.
     */
    fun clearAllSessions(reason: String) {
        synchronized(sessionLock) {
            var had = false
            for (state in sources.values) {
                val session = state.session ?: continue
                had = true
                persistSeqLocked(state, session.lastSeq)
                state.session = null
            }
            if (had) log("remote input: sessions cleared ($reason)")
        }
        queue.clear()
        publishStatusAll()
    }

    /**
     * Drop ONE source's live session, e.g. when that source's transport dropped.
     *
     * A transport loss belongs to the device that suffered it. Using [clearAllSessions] for this
     * would mean one device's Bluetooth drop silently ended every other device's live session --
     * invisible while there is only one source, and a real defect the moment a second one exists.
     *
     * As in [clearAllSessions], the durable sid and sequence floor survive, so a burst captured
     * before the drop stays unreplayable after it.
     */
    fun clearSession(sourceId: String, reason: String) {
        synchronized(sessionLock) {
            val state = sources[sourceId] ?: return
            val session = state.session ?: return
            persistSeqLocked(state, session.lastSeq)
            state.session = null
            log("remote input: session cleared for '$sourceId' ($reason)")
        }
        publishStatusAll()
    }

    /**
     * Forget a source's durable replay state.
     *
     * The only legitimate need for this is a source device that was factory reset, regressing the
     * counter the whole defence rests on. It MUST stay reachable only from a local physical action
     * on the glasses: exposing it over the transport would reinstate the replay hole verbatim, since
     * an attacker's first move would be to call it.
     */
    fun forgetSourceIdentity(sourceId: String) {
        synchronized(sessionLock) {
            sources[sourceId]?.let {
                it.session = null
                it.reservedSeq = 0L
            }
            store.forget(sourceId)
        }
        log("remote input: durable identity cleared for '$sourceId' (local action)")
    }

    /** True while any source holds an open session. Drives the "remote active" UI indicator. */
    fun anyOpenSession(): Boolean = synchronized(sessionLock) {
        val now = clock()
        sources.values.any { expireLocked(it, now); it.session != null }
    }

    // --- rate limiting ---

    /**
     * Action budget.
     *
     * Sized well above physical reality: the measured bezel produces 9-30 detents/s, which the
     * relaying phone coalesces into at most ~10 frames/s, so the limit only engages on traffic no
     * hand can generate. It is a flood guard, not the per-source scroll throttle that was
     * deliberately removed -- that one dropped whole coalesced events during ordinary use.
     * An over-limit frame IS dropped rather than carried; that is the one stage that loses
     * distance, and it is unreachable by real input.
     */
    private fun admitRateLocked(state: SourceState, now: Long): Boolean {
        if (now - globalRateWindowStartMs >= RATE_WINDOW_MS) {
            globalRateWindowStartMs = now
            globalRateWindowCount = 0
        }
        if (now - state.rateWindowStartMs >= RATE_WINDOW_MS) {
            state.rateWindowStartMs = now
            state.rateWindowCount = 0
        }
        // The global ceiling is deliberately BELOW the sum of the per-source allowances, so it can
        // actually bind. A ceiling equal to that sum would never engage and would be decoration.
        // Computed in Long: the product overflows Int for a large per-source allowance, and a
        // negative ceiling would silently reject every frame.
        val globalCeiling =
            (maxEventsPerSecond.toLong() * MAX_SOURCES * GLOBAL_BUDGET_PERCENT) / 100
        if (globalRateWindowCount >= globalCeiling) return false
        if (state.rateWindowCount >= maxEventsPerSecond) return false
        globalRateWindowCount++
        state.rateWindowCount++
        return true
    }

    /** Keepalive budget, kept separate so an action flood cannot expire the user's own session. */
    private fun admitLifecycleRateLocked(state: SourceState, now: Long): Boolean {
        if (now - state.lifecycleWindowStartMs >= RATE_WINDOW_MS) {
            state.lifecycleWindowStartMs = now
            state.lifecycleWindowCount = 0
        }
        if (state.lifecycleWindowCount >= MAX_LIFECYCLE_PER_SECOND) return false
        state.lifecycleWindowCount++
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
        // Snapshot the queue's counter BEFORE taking sessionLock: reading it while holding
        // sessionLock would nest the two locks in the opposite order to the ingress path.
        val queueDropped = queue.dropped
        val sinkAttached = sinkReallyAttached()
        val status = synchronized(sessionLock) {
            val state = sources[source.sourceId] ?: return
            RemoteInputStatus(
                sessionOpen = state.session != null,
                sinkAttached = sinkAttached,
                droppedTotal = state.dropped + queueDropped,
            )
        }
        try {
            source.onStatus(status)
        } catch (e: Exception) {
            log("remote input: status push failed: ${e.javaClass.simpleName}")
        }
    }

    // --- test visibility ---

    @androidx.annotation.VisibleForTesting
    internal fun registeredSourceIds(): Set<String> =
        synchronized(sessionLock) { sources.keys.toSet() }

    @androidx.annotation.VisibleForTesting
    internal fun droppedFor(sourceId: String): Long =
        synchronized(sessionLock) { sources[sourceId]?.dropped ?: 0L }

    @androidx.annotation.VisibleForTesting
    internal fun hasOpenSession(sourceId: String): Boolean = synchronized(sessionLock) {
        val state = sources[sourceId] ?: return false
        expireLocked(state, clock())
        state.session != null
    }

    @androidx.annotation.VisibleForTesting
    internal val hasSink: Boolean get() = sinkRef.get() != null

    companion object {
        /** The only protocol version this router accepts. */
        const val PROTOCOL_VERSION = 1

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

        /** Keepalives per source per second. A source PINGs every 10 s, so this is ample. */
        const val MAX_LIFECYCLE_PER_SECOND = 5

        /** Registration ceiling, so the global rate budget is a fixed known quantity. */
        const val MAX_SOURCES = 4

        /**
         * The global ceiling as a percentage of the summed per-source allowances. Below 100 so it
         * binds before every source can simultaneously max out.
         */
        const val GLOBAL_BUDGET_PERCENT = 60

        /**
         * How far ahead of the applied sequence the durable floor is reserved.
         *
         * Trades one synchronous write per this many events against the number of frames a crash
         * can cost inside a single session. At the ~10 frames/s a real bezel produces, 256 is a
         * write roughly every 25 s of continuous scrolling.
         */
        const val SEQ_RESERVATION = 256L

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
