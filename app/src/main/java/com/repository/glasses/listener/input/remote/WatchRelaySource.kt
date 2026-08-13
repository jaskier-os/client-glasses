package com.repository.glasses.listener.input.remote

import com.repository.glasses.listener.bt.BtProtocol
import com.repository.glasses.listener.bt.MessageRelay

/**
 * The first [InputSource]: a Wear watch, relayed by the phone over the dedicated input RFCOMM
 * socket.
 *
 * Its whole job is turning `CH_REMOTE_INPUT` args into [RemoteInputFrame]s. It owns no session
 * state, no sequencing, no staleness policy and no rate limiting -- all of that lives in
 * [RemoteInputRouter], which is why a second device inherits it for free.
 *
 * Nothing here decides what an event MEANS. There is no keycode and no UI concept in this file.
 *
 * @param relay the dedicated input socket. Never the shared message socket: bulk frames there would
 *        head-of-line-block input for seconds.
 * @param log one-line diagnostics, rate-limited for anything a peer can trigger.
 */
class WatchRelaySource(
    private val relay: MessageRelay,
    private val auth: RemoteInputAuth,
    private val clock: () -> Long,
    private val log: (String) -> Unit = {},
) : InputSource {

    override val sourceId: String = SOURCE_ID

    @Volatile
    private var sink: ((RemoteInputFrame) -> Unit)? = null

    private val rejectLock = Any()
    private var lastRejectLogMs = 0L
    private var suppressedRejects = 0L

    private val relayListener = object : MessageRelay.Listener {
        override fun onConnected() {
            log("watch input: transport connected")
            // Re-announce on every reconnect. The sink state is only pushed on transitions, and any
            // transition that happened while the link was down was published into a dead socket and
            // lost. Without this the watch resumes on a stale value -- typically showing READY while
            // the glasses UI is gone -- until the next attach or detach, which may never come.
            onReconnected?.invoke()
        }

        override fun onDisconnected() {
            // Frames may have been lost across the gap, so the sequence continuity the router
            // relies on is no longer trustworthy. The router retains the durable floor, so the
            // source resuming the same session cannot rewind.
            log("watch input: transport disconnected")
            onTransportLost?.invoke()
        }

        override fun onMessage(channel: String, args: List<String>) {
            if (channel != BtProtocol.CH_REMOTE_INPUT) return
            handleFrame(args)
        }
    }

    /** Invoked when the transport drops, so the owner can clear live session state. */
    var onTransportLost: (() -> Unit)? = null

    /** Invoked when the transport comes back up, so current state can be re-published. */
    var onReconnected: (() -> Unit)? = null

    override fun attach(sink: (RemoteInputFrame) -> Unit) {
        this.sink = sink
        relay.listener = relayListener
    }

    override fun detach() {
        sink = null
        if (relay.listener === relayListener) relay.listener = null
    }

    override fun onSinkAttached(attached: Boolean) {
        // Best-effort, like onStatus: the input path must never depend on the back channel.
        try {
            relay.publish(BtProtocol.CH_REMOTE_INPUT_SINK, if (attached) "1" else "0")
        } catch (e: Exception) {
            log("watch input: sink-state publish failed: ${e.javaClass.simpleName}")
        }
    }

    override fun onStatus(status: RemoteInputStatus) {
        // Best-effort: the watch uses this to show why input is being ignored. A failure here must
        // never affect the input path.
        try {
            // The refusal fields are APPENDED, so a phone build that only reads the first
            // three positional args keeps working against a glasses build that sends five.
            // Absent refusal is sent as the empty reason plus a zero count rather than by
            // omitting the args, so the arity is fixed and the reader needs no special case.
            val refusal = status.refusal
            relay.publish(
                BtProtocol.CH_REMOTE_INPUT_STATUS,
                if (status.sessionOpen) "1" else "0",
                if (status.sinkAttached) "1" else "0",
                status.droppedTotal.toString(),
                refusal?.reason?.name ?: "",
                (refusal?.total ?: 0L).toString(),
                // Appended for the same reason as the refusal fields: a phone build that
                // reads only the first five args keeps working against a build that
                // sends six.
                status.holdMs.toString(),
                status.deviceState.toString(),
            )
        } catch (e: Exception) {
            log("watch input: status publish failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Runs on a Bluetooth transport callback thread. Total by construction: an uncaught exception
     * here would take down the service that owns the relay.
     */
    private fun handleFrame(args: List<String>) {
        try {
            when (val result = RemoteInputCodec.decode(args, sourceId, auth)) {
                is RemoteInputCodec.Result.Ok -> sink?.invoke(result.frame)
                is RemoteInputCodec.Result.Rejected -> logRejection(result.reason)
            }
        } catch (e: Exception) {
            logRejection("frame handling failed: ${e.javaClass.simpleName}")
        }
    }

    /** At most one line per second: a peer controls the frame rate, and the log is on flash. */
    private fun logRejection(reason: String) {
        val message = synchronized(rejectLock) {
            val now = clock()
            if (now - lastRejectLogMs < REJECT_LOG_INTERVAL_MS) {
                suppressedRejects++
                return
            }
            lastRejectLogMs = now
            val suppressed = suppressedRejects
            suppressedRejects = 0
            "watch input: rejected -- $reason" +
                if (suppressed > 0) " (+$suppressed suppressed)" else ""
        }
        log(message)
    }

    companion object {
        const val SOURCE_ID = "watch"
        private const val REJECT_LOG_INTERVAL_MS = 1000L
    }
}
