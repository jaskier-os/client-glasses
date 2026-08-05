package com.repository.glasses.listener.input.remote

/**
 * A test [InputSource] that is not the watch.
 *
 * Its existence is itself part of the proof that the abstraction holds: the router's tests drive it
 * with no watch, no Bluetooth and no Android, and a second instance with a different id exercises
 * the per-source isolation a future device depends on.
 */
class FakeInputSource(
    override val sourceId: String,
    /**
     * The source's own monotonic clock. Defaults to the test clock so a frame is "fresh" unless a
     * test deliberately makes it stale -- otherwise every test that advances time would trip the
     * TTL, which measures exactly the divergence between these two clocks.
     */
    private val clock: () -> Long = { 0L },
) : InputSource {
    private var sink: ((RemoteInputFrame) -> Unit)? = null
    var attachCount = 0
        private set
    var detachCount = 0
        private set
    val statuses = mutableListOf<RemoteInputStatus>()

    override fun attach(sink: (RemoteInputFrame) -> Unit) {
        this.sink = sink
        attachCount++
    }

    override fun detach() {
        sink = null
        detachCount++
    }

    override fun onStatus(status: RemoteInputStatus) {
        statuses.add(status)
    }

    /** Every sink attach/detach announcement, in order. */
    val sinkStates = mutableListOf<Boolean>()

    override fun onSinkAttached(attached: Boolean) {
        sinkStates.add(attached)
    }

    private var seq = 0L

    fun open(sid: Long, wms: Long = clock()): RemoteInputFrame.Lifecycle =
        RemoteInputFrame.Lifecycle(1, sourceId, sid, ++seq, wms, RemoteLifecycle.OPEN)
            .also { emit(it) }

    fun close(sid: Long, wms: Long = clock()): RemoteInputFrame.Lifecycle =
        RemoteInputFrame.Lifecycle(1, sourceId, sid, ++seq, wms, RemoteLifecycle.CLOSE)
            .also { emit(it) }

    fun ping(sid: Long, wms: Long = clock()): RemoteInputFrame.Lifecycle =
        RemoteInputFrame.Lifecycle(1, sourceId, sid, ++seq, wms, RemoteLifecycle.PING)
            .also { emit(it) }

    fun scroll(sid: Long, steps: Int, wms: Long = clock(), seqOverride: Long? = null) {
        val s = seqOverride ?: ++seq
        emit(RemoteInputFrame.Action(1, sourceId, sid, s, wms, RemoteAction.SCROLL_STEP, steps))
    }

    fun tap(sid: Long, wms: Long = clock(), seqOverride: Long? = null) {
        val s = seqOverride ?: ++seq
        emit(RemoteInputFrame.Action(1, sourceId, sid, s, wms, RemoteAction.TAP, 0))
    }

    fun back(sid: Long, wms: Long = clock(), seqOverride: Long? = null) {
        val s = seqOverride ?: ++seq
        emit(RemoteInputFrame.Action(1, sourceId, sid, s, wms, RemoteAction.BACK, 0))
    }

    /** Emit a fully hand-built frame, for the malformed / adversarial cases. */
    fun emit(frame: RemoteInputFrame) {
        sink?.invoke(frame)
    }

    fun nextSeq(): Long = ++seq
}

/** Collects everything the router delivers to the UI. */
class RecordingSink : RemoteInputSink {
    val events = mutableListOf<RemoteInputEvent>()
    var thrower: ((RemoteInputEvent) -> Unit)? = null

    override fun onRemoteInput(e: RemoteInputEvent) {
        events.add(e)
        thrower?.invoke(e)
    }

    val actions: List<RemoteAction> get() = events.map { it.action }
    val deltas: List<Int> get() = events.map { it.delta }
    fun totalDelta(): Int = events.filter { it.action == RemoteAction.SCROLL_STEP }.sumOf { it.delta }
    fun clear() = events.clear()
}

/** Runs posted runnables synchronously on demand, standing in for the main-thread Handler. */
class ManualPoster {
    private val pending = ArrayDeque<Runnable>()

    val post: (Runnable) -> Unit = { pending.addLast(it) }

    /** Run every runnable currently queued, including ones they post themselves. */
    fun runAll(limit: Int = 10_000) {
        var n = 0
        while (pending.isNotEmpty() && n++ < limit) pending.removeFirst().run()
    }

    val pendingCount: Int get() = pending.size
}
