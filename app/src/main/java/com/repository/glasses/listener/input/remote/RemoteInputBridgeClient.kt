package com.repository.glasses.listener.input.remote

import android.os.Handler
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock

/**
 * The UI-process half of the remote-input bridge.
 *
 * Holds the `IRemoteInputSink.Stub` that `:backend` calls, hops each delivery onto the main thread,
 * and hands it to a plain in-process [RemoteInputSink] -- which is what `MainActivity` implements.
 * `MainActivity` therefore never sees a binder, an AIDL type, or the fact that the producing code
 * runs in another process.
 *
 * Binding is NOT owned here. `MainActivity` already binds `ListenerService` for backend health
 * monitoring; this class is driven from that existing `ServiceConnection` so the app keeps exactly
 * one binding to `:backend` instead of racing two against each other.
 */
class RemoteInputBridgeClient(
    private val mainHandler: Handler,
    private val sink: RemoteInputSink,
    private val log: (String) -> Unit = {},
) {
    private val actions = RemoteAction.values()

    /**
     * IPC latency samples, in microseconds. Guarded by [statsLock].
     *
     * Kept as raw samples rather than a running mean, because this figure sits directly in the
     * scroll path where the TAIL is what the user feels: a mean hides the occasional stall that a
     * p99 exposes. Bounded so a long session cannot grow it without limit.
     */
    private val statsLock = Any()
    private val latencySamplesUs = ArrayDeque<Long>()
    private var latencyCount = 0L

    private var bridge: IRemoteInputBridge? = null

    /**
     * The object `:backend` calls. Created once and reused for the life of this process: re-creating
     * it per bind would leak a stale binder into the backend's registry on every reconnect.
     */
    private val stub = object : IRemoteInputSink.Stub() {
        override fun deliver(
            action: Int,
            delta: Int,
            sourceId: String?,
            sid: Long,
            seq: Long,
            ageMs: Int,
            sinceLastMs: Int,
            emitNanos: Long,
        ) {
            // Runs on a binder thread. Reject an out-of-range ordinal rather than crashing the UI
            // process on an index -- the two processes are versioned together, but a partial
            // upgrade is exactly the case where an unchecked ordinal would be fatal.
            val resolved = actions.getOrNull(action) ?: run {
                log("[RemoteInput] unknown action ordinal $action dropped")
                return
            }
            val transportUs = (SystemClock.elapsedRealtimeNanos() - emitNanos) / 1000L
            val event = RemoteInputEvent(
                action = resolved,
                delta = delta,
                sourceId = sourceId ?: return,
                sid = sid,
                seq = seq,
                ageMs = ageMs,
                sinceLastMs = sinceLastMs,
            )
            mainHandler.post {
                recordLatency(emitNanos, transportUs)
                sink.onRemoteInput(event)
            }
        }
    }

    private fun recordLatency(emitNanos: Long, transportUs: Long) {
        val totalUs = (SystemClock.elapsedRealtimeNanos() - emitNanos) / 1000L
        val count = synchronized(statsLock) {
            latencyCount++
            latencySamplesUs.addLast(totalUs)
            while (latencySamplesUs.size > MAX_LATENCY_SAMPLES) latencySamplesUs.removeFirst()
            latencyCount
        }
        if (count % LATENCY_LOG_EVERY == 0L) {
            log("[RemoteInput] ipc latency: $latencySummary (last transport=${transportUs}us)")
        }
    }

    /**
     * IPC latency distribution: binder emit -> main-thread execution, in microseconds.
     *
     * Percentiles rather than a mean, because the scroll path is judged on its worst frames.
     */
    val latencySummary: String
        get() {
            val (n, sorted) = synchronized(statsLock) {
                latencyCount to latencySamplesUs.sorted()
            }
            if (sorted.isEmpty()) return "n=0"
            fun pct(p: Int): Long = sorted[((sorted.size - 1) * p) / 100]
            val mean = sorted.sum() / sorted.size
            return "n=$n min=${sorted.first()}us p50=${pct(50)}us p90=${pct(90)}us " +
                "p95=${pct(95)}us p99=${pct(99)}us max=${sorted.last()}us mean=${mean}us"
        }

    /**
     * The binder stub `:backend` calls.
     *
     * Public because the instrumented latency test drives it directly: measuring through the real
     * stub keeps the figure honest, whereas a test-local copy would time a reimplementation. It is
     * also the object handed to [registerSink], so this is simply naming what already crosses the
     * process boundary rather than widening the surface for testing.
     */
    val sinkBinder: IRemoteInputSink get() = stub

    /** Called from the UI process's `onServiceConnected`. */
    fun onBackendConnected(service: IBinder?) {
        val b = IRemoteInputBridge.Stub.asInterface(service) ?: run {
            log("[RemoteInput] backend returned no bridge binder")
            return
        }
        bridge = b
        try {
            b.registerSink(sinkBinder)
            log("[RemoteInput] sink registered with backend")
        } catch (e: RemoteException) {
            bridge = null
            log("[RemoteInput] registerSink failed: ${e.message}")
        }
    }

    /**
     * Called from `onServiceDisconnected` / `onBindingDied`. Only forgets the local handle -- the
     * backend's own death recipient does the unregistration, and calling into a dead binder here
     * would just throw.
     */
    fun onBackendDisconnected() {
        bridge = null
        log("[RemoteInput] backend disconnected")
    }

    /**
     * Tell `:backend` the UI declined an action, so it can put the reason on the status
     * backchannel and the remote device can explain the silence.
     *
     * Silently dropped when the backend is not bound: with no backend there is no status
     * channel to carry the refusal anyway, and a refusal is not worth a crash.
     */
    fun reportRefusal(reason: RemoteRefusalReason) {
        val b = bridge ?: return
        try {
            b.reportRefusal(reason.ordinal)
        } catch (e: RemoteException) {
            log("[RemoteInput] reportRefusal failed: ${e.message}")
        }
    }

    /** Called on an orderly UI teardown, while the binder is still alive. */
    fun unregister() {
        val b = bridge ?: return
        try {
            b.unregisterSink(sinkBinder)
            log("[RemoteInput] sink unregistered")
        } catch (_: RemoteException) {
            // Backend already gone; its death recipient covers this.
        }
        bridge = null
    }

    private companion object {
        const val LATENCY_LOG_EVERY = 200L

        /** Bounded so a long-running session cannot grow the sample buffer without limit. */
        const val MAX_LATENCY_SAMPLES = 2_000
    }
}
