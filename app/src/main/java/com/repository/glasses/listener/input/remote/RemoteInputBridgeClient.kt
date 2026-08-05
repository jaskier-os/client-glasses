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

    /** Rolling IPC latency samples, in microseconds. Guarded by [statsLock]. */
    private val statsLock = Any()
    private var latencyCount = 0L
    private var latencySumUs = 0L
    private var latencyMaxUs = 0L

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
        synchronized(statsLock) {
            latencyCount++
            latencySumUs += totalUs
            if (totalUs > latencyMaxUs) latencyMaxUs = totalUs
        }
        if (latencyCount % LATENCY_LOG_EVERY == 0L) {
            log("[RemoteInput] ipc latency: $latencySummary (last transport=${transportUs}us)")
        }
    }

    /** Human-readable IPC latency summary: binder emit -> main-thread execution. */
    val latencySummary: String
        get() = synchronized(statsLock) {
            if (latencyCount == 0L) "n=0"
            else "n=$latencyCount mean=${latencySumUs / latencyCount}us max=${latencyMaxUs}us"
        }

    /** Called from the UI process's `onServiceConnected`. */
    fun onBackendConnected(service: IBinder?) {
        val b = IRemoteInputBridge.Stub.asInterface(service) ?: run {
            log("[RemoteInput] backend returned no bridge binder")
            return
        }
        bridge = b
        try {
            b.registerSink(stub)
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

    /** Called on an orderly UI teardown, while the binder is still alive. */
    fun unregister() {
        val b = bridge ?: return
        try {
            b.unregisterSink(stub)
            log("[RemoteInput] sink unregistered")
        } catch (_: RemoteException) {
            // Backend already gone; its death recipient covers this.
        }
        bridge = null
    }

    private companion object {
        const val LATENCY_LOG_EVERY = 200L
    }
}
