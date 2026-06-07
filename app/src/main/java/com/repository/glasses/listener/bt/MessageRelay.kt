package com.repository.glasses.listener.bt

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.repository.glasses.tracing.GT
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Framed message transport over RFCOMM via BtManagerBridge.
 *
 * Replaces CXR-S for glasses-phone messaging with a reliable direct RFCOMM channel.
 *
 * Frame format:
 *   [4B frame length (BE, excluding this header)]
 *   [1B channel name length]
 *   [N bytes channel name (UTF-8)]
 *   [1B arg count]
 *   For each arg:
 *     [4B arg length (BE)]
 *     [M bytes arg data (UTF-8)]
 *
 * Frame length = channel header + all args. Unlimited message size (bounded only by RFCOMM MTU
 * fragmentation, handled transparently by the kernel).
 */
class MessageRelay(private val bridge: BtManagerBridge, private val context: Context) {

    companion object {
        private const val TAG = "App:BtRelay"
        /** UUID for the glasses <-> phone message channel (RFCOMM SPP). */
        const val MESSAGE_UUID = "b2c3d4e5-f6a7-8901-bcde-f12345678901"
        const val SERVICE_NAME = "GlassesMessages"
        private const val MAX_FRAME_BYTES = 8 * 1024 * 1024  // 8MB sanity cap
    }

    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onMessage(channel: String, args: List<String>)
    }

    var listener: Listener? = null
    var remoteLog: ((String) -> Unit)? = null

    @Volatile
    private var socketId: String? = null
    @Volatile
    var isConnected = false
        private set
    @Volatile
    private var started: Boolean = false

    private val recvBuffer = ByteArrayOutputStream()
    private val recvLock = Any()

    private val onBridgeBound: () -> Unit = {
        // Bridge was (re)bound to bt-manager. Open a fresh inbound server socket.
        // Runs on main thread; do the AIDL call on a worker to avoid potential blocking.
        Log.d(TAG, "event=bridge_bound started=$started")
        Thread({
            if (started) {
                Log.d(TAG, "event=relay_reopen reason=bridge_bound")
                openServerSocket()
            }
        }, "MessageRelayRelisten").apply { isDaemon = true; start() }
    }

    /**
     * Receiver for BluetoothAdapter.ACTION_STATE_CHANGED. When the adapter comes back up
     * (STATE_ON) after being disabled (e.g. by fold receiver in ListenerService), the
     * previous inbound RFCOMM server socket is gone. Reopen it so the phone can reconnect.
     */
    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            Log.d(TAG, "event=bt_state_changed state=$state started=$started connected=$isConnected")
            remoteLog?.invoke("MessageRelay: BT adapter state changed -> $state")
            if (state == BluetoothAdapter.STATE_ON && started && !isConnected) {
                Log.d(TAG, "event=relay_reopen reason=state_on delay_ms=500")
                remoteLog?.invoke("MessageRelay: STATE_ON, reopening listener")
                Thread({
                    // Give the adapter + bt-manager a moment to settle before reopening.
                    try { Thread.sleep(500) } catch (_: InterruptedException) {}
                    if (started) openServerSocket()
                }, "MessageRelayBtStateOn").apply { isDaemon = true; start() }
            }
        }
    }
    @Volatile private var btStateReceiverRegistered = false

    // Totals for the trace counter (cumulative across the session).
    @Volatile private var txBytesTotal: Long = 0L
    @Volatile private var rxBytesTotal: Long = 0L
    @Volatile private var reopenCount: Long = 0L
    @Volatile private var sessionCount: Long = 0L
    /** BT address of the connected companion phone (set on RFCOMM connect, cleared on disconnect). */
    @Volatile var companionAddress: String? = null
        private set

    private val rfcommListener = object : BtManagerBridge.RfcommListener {
        override fun onConnected(socketId: String, address: String, name: String) {
            if (socketId == this@MessageRelay.socketId) {
                GT.beginAsync("bt.session", socketId.hashCode())
                sessionCount++
                isConnected = true
                companionAddress = address
                Log.d(TAG, "event=session_begin n=$sessionCount sid=$socketId")
                Log.i(TAG, "event=connected name=$name addr=$address sid=$socketId")
                remoteLog?.invoke("MessageRelay: connected to $name")
                // Tell the phone (over the BLE wake link) that RFCOMM is up.
                // Fire-and-forget; if no central is subscribed, bt-manager arms
                // a pending hello and flushes on the next CCCD-enable.
                try {
                    val ok = bridge.sendBleHello()
                    Log.d(TAG, "event=ble_hello.tx reason=rfcomm_connected ok=$ok")
                } catch (e: Exception) {
                    Log.w(TAG, "event=ble_hello.tx.fail err=${e.message}")
                }
                listener?.onConnected()
            }
        }

        override fun onDisconnected(socketId: String) {
            if (socketId == this@MessageRelay.socketId) {
                val wasConnected = isConnected
                isConnected = false
                companionAddress = null
                synchronized(recvLock) { recvBuffer.reset() }
                if (wasConnected) {
                    Log.i(TAG, "event=disconnected sid=$socketId")
                    remoteLog?.invoke("MessageRelay: disconnected")
                    listener?.onDisconnected()
                }
                GT.endAsync("bt.session", socketId.hashCode())
                // Re-arm the inbound listener so the phone can reconnect. Without
                // this, a dropped RFCOMM socket leaves the pipe permanently dead
                // (onBridgeBound only fires on bt-manager re-bind, and the adapter
                // state receiver only fires on full BT off/on cycles).
                scheduleReopen(800L, "onDisconnected")
            }
        }

        override fun onData(socketId: String, data: ByteArray) {
            if (socketId == this@MessageRelay.socketId) {
                GT.section("bt.recv") {
                    rxBytesTotal += data.size
                    GT.counter("bt.rx_bytes", rxBytesTotal)
                    handleIncomingBytes(data)
                }
            }
        }

        override fun onError(socketId: String, error: String) {
            if (socketId == this@MessageRelay.socketId) {
                Log.w(TAG, "event=rfcomm_error sid=$socketId err=$error")
                remoteLog?.invoke("MessageRelay: error: $error")
                val wasConnected = isConnected
                isConnected = false
                if (wasConnected) {
                    listener?.onDisconnected()
                    GT.endAsync("bt.session", socketId.hashCode())
                }
                scheduleReopen(800L, "onError")
            }
        }
    }

    private fun scheduleReopen(delayMs: Long, reason: String) {
        if (!started) return
        Log.d(TAG, "event=schedule_reopen reason=$reason delay_ms=$delayMs")
        Thread {
            try { Thread.sleep(delayMs) } catch (_: InterruptedException) { return@Thread }
            if (!started || isConnected) return@Thread
            reopenCount++
            Log.d(TAG, "event=relay_reopen n=$reopenCount reason=$reason")
            remoteLog?.invoke("MessageRelay: reopening listener (reason=$reason)")
            openServerSocket()
        }.apply {
            isDaemon = true
            name = "MessageRelay-reopen"
            start()
        }
    }

    /**
     * Start listening for inbound RFCOMM connection from phone. Idempotent.
     * Registers the bridge-bound listener so the server socket reopens whenever bt-manager
     * (re)binds (first-time or after a bt-manager process restart).
     */
    fun start(): Boolean {
        if (started) return true
        Log.d(TAG, "event=start")
        started = true
        bridge.addRfcommListener(rfcommListener)
        // addOnBoundListener fires immediately if already bound and again on each rebind,
        // so this covers both initial open and recovery after bt-manager death.
        bridge.addOnBoundListener(onBridgeBound)
        // Also reopen the inbound socket when the BT adapter itself cycles off/on
        // (ListenerService fold receiver toggles BluetoothAdapter.disable()/enable()).
        if (!btStateReceiverRegistered) {
            try {
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    ContextCompat.RECEIVER_EXPORTED else 0
                ContextCompat.registerReceiver(
                    context,
                    btStateReceiver,
                    IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                    flags
                )
                btStateReceiverRegistered = true
                remoteLog?.invoke("MessageRelay: btStateReceiver registered")
            } catch (e: Exception) {
                remoteLog?.invoke("MessageRelay: btStateReceiver register failed: ${e.message}")
                // Registration may have partially completed before throwing. Attempt
                // a defensive unregister so we don't leak a half-registered receiver,
                // then leave the flag false so a future start() can retry cleanly.
                try {
                    context.unregisterReceiver(btStateReceiver)
                    remoteLog?.invoke("MessageRelay: btStateReceiver defensive unregister ok")
                } catch (_: IllegalArgumentException) {
                    // Was not actually registered -- expected, ignore.
                } catch (e2: Exception) {
                    remoteLog?.invoke("MessageRelay: btStateReceiver defensive unregister failed: ${e2.message}")
                }
                btStateReceiverRegistered = false
            }
        }
        return true
    }

    /** (Re)open the inbound RFCOMM server socket. Safe to call multiple times. */
    @Synchronized
    private fun openServerSocket(): Boolean {
        if (!started) return false
        val old = socketId
        if (old != null) {
            // Close any stale listener (either previous bt-manager instance or previous socket).
            try { bridge.closeRfcommSocket(old) } catch (_: Exception) {}
            socketId = null
            isConnected = false
        }
        val sid = bridge.listenRfcommInbound(SERVICE_NAME, MESSAGE_UUID)
        if (sid != null) {
            socketId = sid
            remoteLog?.invoke("MessageRelay: listening (socketId=$sid${if (old != null) ", was=$old" else ""})")
            return true
        } else {
            remoteLog?.invoke("MessageRelay: failed to open listener (bridge not bound?)")
            return false
        }
    }

    fun stop() {
        Log.d(TAG, "event=stop")
        started = false
        val sid = socketId
        socketId = null
        isConnected = false
        bridge.removeRfcommListener(rfcommListener)
        bridge.removeOnBoundListener(onBridgeBound)
        if (btStateReceiverRegistered) {
            try { context.unregisterReceiver(btStateReceiver) } catch (_: Exception) {}
            btStateReceiverRegistered = false
        }
        if (sid != null) bridge.closeRfcommSocket(sid)
    }

    // Heartbeat + RX-stale watchdog removed in G2. Active-session ref-counting in
    // bt-manager (RfcommManager.setActiveSession) is the new keep-alive signal during
    // real work; idle-teardown handles dead-peer recovery on the glasses side. G3
    // (BLE wake) will replace this for cross-device dead-peer detection.

    /** Send a framed message to the connected peer. Returns false if not connected. */
    fun publish(channel: String, vararg args: String): Boolean {
        val sid = socketId ?: return false
        if (!isConnected) return false
        return GT.section("bt.send.$channel") {
            try {
                val frame = buildFrame(channel, args)
                bridge.writeRfcomm(sid, frame)
                txBytesTotal += frame.size
                GT.counter("bt.tx_bytes", txBytesTotal)
                true
            } catch (e: Exception) {
                remoteLog?.invoke("MessageRelay: publish($channel) failed: ${e.message}")
                false
            }
        }
    }

    // -- Framing --

    private fun buildFrame(channel: String, args: Array<out String>): ByteArray {
        val channelBytes = channel.toByteArray(Charsets.UTF_8)
        require(channelBytes.size <= 255) { "channel name too long: ${channelBytes.size}" }
        require(args.size <= 255) { "too many args: ${args.size}" }

        val payload = ByteArrayOutputStream()
        payload.write(channelBytes.size and 0xFF)
        payload.write(channelBytes)
        payload.write(args.size and 0xFF)
        for (arg in args) {
            val bytes = arg.toByteArray(Charsets.UTF_8)
            payload.write(ByteBuffer.allocate(4).putInt(bytes.size).array())
            payload.write(bytes)
        }

        val payloadBytes = payload.toByteArray()
        val frame = ByteArrayOutputStream(4 + payloadBytes.size)
        frame.write(ByteBuffer.allocate(4).putInt(payloadBytes.size).array())
        frame.write(payloadBytes)
        return frame.toByteArray()
    }

    private fun handleIncomingBytes(data: ByteArray) {
        synchronized(recvLock) {
            if (recvBuffer.size() + data.size > MAX_FRAME_BYTES) {
                remoteLog?.invoke("MessageRelay: recv buffer overflow (size=${recvBuffer.size()} + ${data.size} > $MAX_FRAME_BYTES); resetting")
                recvBuffer.reset()
            }
            recvBuffer.write(data)
            var buf = recvBuffer.toByteArray()
            var offset = 0
            while (buf.size - offset >= 4) {
                val len = ByteBuffer.wrap(buf, offset, 4).int
                if (len < 0 || len > MAX_FRAME_BYTES) {
                    remoteLog?.invoke("MessageRelay: invalid frame length=$len, resetting buffer")
                    recvBuffer.reset()
                    return
                }
                if (buf.size - offset - 4 < len) break
                try {
                    parseFrame(buf, offset + 4, len)
                } catch (e: Exception) {
                    remoteLog?.invoke("MessageRelay: frame parse failed: ${e.message}")
                }
                offset += 4 + len
            }
            if (offset > 0) {
                recvBuffer.reset()
                if (offset < buf.size) {
                    recvBuffer.write(buf, offset, buf.size - offset)
                }
            }
        }
    }

    private fun parseFrame(buf: ByteArray, start: Int, length: Int) {
        var p = start
        val end = start + length

        val chanLen = buf[p].toInt() and 0xFF; p++
        require(p + chanLen <= end) { "channel bytes overflow" }
        val channel = String(buf, p, chanLen, Charsets.UTF_8); p += chanLen

        val argCount = buf[p].toInt() and 0xFF; p++
        val args = ArrayList<String>(argCount)
        for (i in 0 until argCount) {
            require(p + 4 <= end) { "arg length overflow" }
            val argLen = ByteBuffer.wrap(buf, p, 4).int; p += 4
            require(argLen >= 0 && p + argLen <= end) { "arg bytes overflow: len=$argLen" }
            args.add(String(buf, p, argLen, Charsets.UTF_8))
            p += argLen
        }

        GT.section("bt.dispatch.$channel") {
            listener?.onMessage(channel, args)
        }
    }
}
