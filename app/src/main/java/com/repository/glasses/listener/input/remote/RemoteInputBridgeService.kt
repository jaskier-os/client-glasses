package com.repository.glasses.listener.input.remote

import android.os.IBinder
import android.os.RemoteException

/**
 * The `:backend` half of the cross-process remote-input bridge.
 *
 * ## Why this exists
 *
 * `MainActivity` runs in the default process; `ListenerService`, which owns the Bluetooth transport
 * and therefore the [RemoteInputRouter], runs in `:backend` (AndroidManifest, `.service.ListenerService`).
 * They share no memory. The obvious `router.setSink(activity)` is a compile-time-valid, runtime-total
 * failure: the router would hold a sink object from a process that will never see it, and 100 % of
 * events would be delivered into a dead reference. Every event has to cross a binder.
 *
 * ## Shape
 *
 * This class is itself a [RemoteInputSink]. That is the whole trick: the router keeps its existing,
 * single-method, device-agnostic sink contract and never learns that a process boundary exists. On
 * the far side, `MainActivity` keeps implementing the same [RemoteInputSink] interface. A future
 * input device still only implements [InputSource]; neither this class nor `MainActivity` changes.
 *
 * ## Identity, and why it is keyed on IBinder
 *
 * AIDL unmarshals a NEW `Stub.Proxy` wrapper for every transaction, so the `IRemoteInputSink` object
 * handed to [registerSink] and the one handed to [unregisterSink] are never `==` even when they name
 * the same remote object. Only `asBinder()` is canonical. Every comparison here therefore goes
 * through [IBinder], or unregistration silently no-ops and a dead sink is retained forever.
 *
 * ## The death race
 *
 * A `linkToDeath` callback is asynchronous: it can land AFTER the replacement UI process has already
 * registered. Clearing unconditionally on death would then kill the healthy new sink. Every
 * transition below is therefore conditional on the binder still being the current one.
 *
 * ## Why the router's sink is installed ONCE and never cleared
 *
 * The router's `clearSink` guards against a stale caller by comparing sink INSTANCES. That guard is
 * degenerate here, because this bridge is a singleton and hands the router `this` for every UI
 * process that ever attaches. A late death callback for a superseded process would therefore pass
 * the comparison and clear the sink a healthy successor had just installed -- permanently, and
 * silently, since every later event would simply count as dropped.
 *
 * So the bridge registers itself with the router once and stays registered for the service's whole
 * life. UI presence is tracked HERE, in [current], where the binder identity that distinguishes one
 * UI process from another is actually available.
 */
class RemoteInputBridgeService(
    private val router: RemoteInputRouter,
    private val log: (String) -> Unit = {},
) : IRemoteInputBridge.Stub(), RemoteInputSink {

    init {
        // Installed once, for the service's whole life. See the class doc: per-UI-process identity
        // is tracked here, not in the router, because only this class can see the binder.
        router.setSink(this)
        // Because of that, the router's own sinkRef is permanently non-null and cannot answer
        // "is a UI actually there". Point its status at the value that can, so the periodic status
        // channel and the dedicated sink-state channel can never contradict each other.
        router.sinkReallyAttached = { sinkAttached }
    }

    private val lock = Any()

    /** The currently registered UI-process sink, or null when no UI is attached. Guarded by [lock]. */
    private var current: IRemoteInputSink? = null

    /** `current.asBinder()`, cached so death comparisons never re-enter a dead proxy. Guarded by [lock]. */
    private var currentBinder: IBinder? = null

    private var deathRecipient: IBinder.DeathRecipient? = null

    /**
     * Events that arrived with no UI sink attached.
     *
     * Deliberately DROPPED rather than queued. A scroll replayed after the UI finally attaches moves
     * a list the user is no longer looking at, seconds after they stopped turning the bezel; a
     * SELECT replayed then activates whatever happens to be focused now. Both are worse than
     * nothing. The count is surfaced through the status backchannel so the watch can say "glasses UI
     * not ready" instead of appearing broken.
     */
    @Volatile
    var droppedNoSink: Long = 0L
        private set

    /** Total events handed to the binder. Paired with [droppedNoSink] to make loss auditable. */
    @Volatile
    var delivered: Long = 0L
        private set

    /** Events lost because the binder transaction itself failed (peer died mid-call). */
    @Volatile
    var droppedTransactionFailed: Long = 0L
        private set

    /** True when a UI process is attached and events will actually be acted on. */
    val sinkAttached: Boolean get() = synchronized(lock) { current != null }

    // --- IRemoteInputBridge (called on a binder thread from the UI process) ---

    override fun registerSink(sink: IRemoteInputSink?) {
        if (sink == null) return
        val binder = sink.asBinder() ?: return
        synchronized(lock) {
            if (binder == currentBinder) return
            detachCurrentLocked("replaced")
            val recipient = IBinder.DeathRecipient { onSinkDied(binder) }
            try {
                binder.linkToDeath(recipient, 0)
            } catch (e: RemoteException) {
                // The peer died between marshalling and here. Nothing to attach, and the router
                // stays pointed at this bridge, which will now count events as droppedNoSink.
                log("[RemoteInput] registerSink: peer already dead (${e.message})")
                return
            }
            current = sink
            currentBinder = binder
            deathRecipient = recipient
            log("[RemoteInput] sink attached (binder=${System.identityHashCode(binder)})")
        }
        announceSinkState()
    }

    override fun unregisterSink(sink: IRemoteInputSink?) {
        val binder = sink?.asBinder() ?: return
        synchronized(lock) {
            if (binder != currentBinder) {
                // A departing screen tearing down after its replacement already registered. Ignore.
                log("[RemoteInput] unregisterSink ignored: not the current sink")
                return
            }
            detachCurrentLocked("unregistered")
        }
        announceSinkState()
    }

    private fun onSinkDied(dead: IBinder) {
        synchronized(lock) {
            // Only act if the dead binder is STILL the current one. A late death callback for a
            // superseded process must not detach the UI its successor just attached.
            if (dead != currentBinder) {
                log("[RemoteInput] death of a superseded sink ignored")
                return
            }
            detachCurrentLocked("died")
        }
        announceSinkState()
    }

    /**
     * Tell every source whether an event sent right now would be acted on.
     *
     * Called from every transition that changes [current], and nowhere else, so the announced state
     * and the real one cannot drift. Read outside the lock deliberately: the push does transport IO,
     * and a source blocking on its socket must not stall a registration.
     */
    private fun announceSinkState() {
        router.publishSinkAttached(sinkAttached)
    }

    /** Must hold [lock]. Idempotent. */
    private fun detachCurrentLocked(reason: String) {
        val binder = currentBinder ?: return
        deathRecipient?.let { runCatching { binder.unlinkToDeath(it, 0) } }
        current = null
        currentBinder = null
        deathRecipient = null
        log("[RemoteInput] sink detached ($reason)")
    }

    /**
     * Drop the binding without touching the router. Used by `ListenerService.onDestroy`, where the
     * router is being torn down anyway and calling back into it would be pointless.
     */
    fun shutdown() {
        synchronized(lock) { detachCurrentLocked("shutdown") }
        announceSinkState()
        router.clearSink(this)
    }

    // --- RemoteInputSink (called by the router's drain thread) ---

    override fun onRemoteInput(e: RemoteInputEvent) {
        val sink = synchronized(lock) { current }
        if (sink == null) {
            droppedNoSink++
            return
        }
        try {
            sink.deliver(
                e.action.ordinal,
                e.delta,
                e.sourceId,
                e.sid,
                e.seq,
                e.ageMs,
                e.sinceLastMs,
                android.os.SystemClock.elapsedRealtimeNanos(),
            )
            delivered++
        } catch (ex: RemoteException) {
            // oneway calls surface a dead peer here; the death recipient will follow but has not
            // necessarily run yet, so count the loss rather than assuming the sink is still good.
            droppedTransactionFailed++
            log("[RemoteInput] deliver failed: ${ex.message}")
        }
    }
}
