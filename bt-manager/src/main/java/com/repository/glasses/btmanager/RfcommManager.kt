package com.repository.glasses.btmanager

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import android.util.Log
import com.repository.glasses.tracing.GT
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@SuppressLint("MissingPermission")
class RfcommManager(
    private val adapter: BluetoothAdapter?,
    private val listener: (socketId: String, event: Event, address: String, name: String, data: ByteArray?, error: String?) -> Unit
) {

    enum class Event { CONNECTED, DISCONNECTED, DATA, ERROR }

    companion object {
        private const val TAG = "BtMgr:Rfcomm"
        private const val TAG_ACCEPT = "BtMgr:Accept"
        private const val TAG_READ = "BtMgr:Read"
        private const val TAG_WRITE = "BtMgr:Write"
        private const val TAG_IDLE = "BtMgr:Idle"
        private const val READ_LOG_EVERY_N = 200
        private const val READ_LOG_INTERVAL_MS = 500L
        // Default per-connection idle timeout. After this many ms with no traffic in either
        // direction AND no active session label held, the connected socket is closed so the
        // SoC can deep-sleep. The server socket keeps accepting; the phone's auto-reconnect
        // re-establishes a fresh connection within ~5s when it has data.
        private const val DEFAULT_IDLE_TIMEOUT_MS = 90_000L
        // How often the idle watchdog wakes to check the deadline.
        private const val IDLE_CHECK_INTERVAL_MS = 5_000L
        private val counter = AtomicInteger(0)

        // Per-label safety timeouts. If a caller forgets to clearActiveSession after this
        // many ms, the entry is auto-removed so the watchdog can resume idle teardown.
        private val DEFAULT_SAFETY_TIMEOUT_MS = mapOf(
            "live_utterance" to 60_000L,
            "tts_playback" to 60_000L,
            "notification_tts" to 60_000L,
            "reid_streaming" to 300_000L,
            "ui_round_trip" to 30_000L,
            "transcription_pending" to 120_000L,
        )
        private const val DEFAULT_SAFETY_FALLBACK_MS = 60_000L
    }

    // Ref-counted active-session tracking. While any label is held, the idle-teardown
    // watchdog refuses to fire. Labels are scheduled for safety auto-clear after a per-
    // label timeout so a forgotten clearActiveSession can never permanently pin the
    // connection awake.
    private data class SessionEntry(val refcount: Int, val expiresAtMs: Long)
    private val activeSessions = HashMap<String, SessionEntry>()
    private val sessionsLock = Any()
    private val safetyHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun setActiveSession(label: String) = GT.section("bt.session.set") {
        val timeoutMs = DEFAULT_SAFETY_TIMEOUT_MS[label] ?: DEFAULT_SAFETY_FALLBACK_MS
        val newRefcount: Int
        val activeCount: Int
        synchronized(sessionsLock) {
            val current = activeSessions[label]
            val nextCount = (current?.refcount ?: 0) + 1
            activeSessions[label] = SessionEntry(nextCount, System.currentTimeMillis() + timeoutMs)
            newRefcount = nextCount
            activeCount = activeSessions.size
        }
        GT.counter("bt.active_sessions", activeCount.toLong())
        Log.i(TAG_IDLE, "event=session.set label=$label refcount=$newRefcount timeout_ms=$timeoutMs")
        safetyHandler.postDelayed({
            synchronized(sessionsLock) {
                val entry = activeSessions[label] ?: return@synchronized
                if (System.currentTimeMillis() >= entry.expiresAtMs) {
                    activeSessions.remove(label)
                    Log.w(TAG_IDLE, "event=session.safety_timeout label=$label refcount_was=${entry.refcount}")
                }
            }
        }, timeoutMs + 100)
    }

    fun clearActiveSession(label: String) = GT.section("bt.session.clear") {
        val newCount: Int
        val activeCount: Int
        synchronized(sessionsLock) {
            val current = activeSessions[label] ?: run {
                Log.d(TAG_IDLE, "event=session.clear.absent label=$label")
                return@section
            }
            val next = current.refcount - 1
            if (next <= 0) {
                activeSessions.remove(label)
                newCount = 0
            } else {
                activeSessions[label] = SessionEntry(next, current.expiresAtMs)
                newCount = next
            }
            activeCount = activeSessions.size
        }
        GT.counter("bt.active_sessions", activeCount.toLong())
        Log.i(TAG_IDLE, "event=session.clear label=$label refcount=$newCount")
    }

    fun isSessionActive(): Boolean = synchronized(sessionsLock) { activeSessions.isNotEmpty() }

    private class SocketEntry(
        val id: String,
        @Volatile var socket: BluetoothSocket?,
        @Volatile var serverSocket: BluetoothServerSocket?,
        @Volatile var outputStream: OutputStream?,
        @Volatile var address: String,
        @Volatile var name: String,
        val isServer: Boolean,
        @Volatile var connected: Boolean,
        val uuid: String = "",
        val writeLock: Any = Any(),
        // Idle-teardown bookkeeping. activeSince is set when the client connects, reset on
        // each new accept. lastActivityMs is updated on every non-heartbeat read/write.
        // idleTimeoutMs == 0 disables the idle teardown for this entry.
        val idleTimeoutMs: Long = 0L,
        @Volatile var activeSince: Long = 0L,
        @Volatile var lastActivityMs: Long = 0L,
        @Volatile var idleWatchdog: Thread? = null
    )

    private val sockets = ConcurrentHashMap<String, SocketEntry>()

    fun connectOutbound(deviceAddress: String, uuid: String): String {
        val socketId = "out_${counter.incrementAndGet()}"
        Log.d(TAG, "event=connectOutbound.request id=$socketId addr=$deviceAddress uuid=$uuid")
        Thread({
            val tStart = SystemClock.elapsedRealtime()
            try {
                Log.d(TAG, "event=connectOutbound.threadStart id=$socketId")
                val device = adapter?.getRemoteDevice(deviceAddress)
                if (device == null) {
                    Log.d(TAG, "event=connectOutbound.deviceNull id=$socketId addr=$deviceAddress")
                    listener(socketId, Event.ERROR, deviceAddress, "", null, "Device not found")
                    return@Thread
                }
                val socket = device.createRfcommSocketToServiceRecord(UUID.fromString(uuid))
                val tConnect = SystemClock.elapsedRealtime()
                Log.d(TAG, "event=connectOutbound.connecting id=$socketId addr=$deviceAddress prep_ms=${tConnect - tStart}")
                socket.connect()
                val tConnected = SystemClock.elapsedRealtime()
                Log.d(TAG, "event=connectOutbound.connected id=$socketId addr=$deviceAddress connect_ms=${tConnected - tConnect} total_ms=${tConnected - tStart}")
                val entry = SocketEntry(
                    id = socketId,
                    socket = socket,
                    serverSocket = null,
                    outputStream = socket.outputStream,
                    address = deviceAddress,
                    name = device.name ?: "",
                    isServer = false,
                    connected = true
                )
                sockets[socketId] = entry
                Log.i(TAG, "event=connectOutbound.ok id=$socketId name=${device.name ?: deviceAddress}")
                listener(socketId, Event.CONNECTED, deviceAddress, device.name ?: "", null, null)
                readLoop(socketId, socket.inputStream)
                Log.d(TAG, "event=connectOutbound.readLoopReturned id=$socketId uptime_ms=${SystemClock.elapsedRealtime() - tStart}")
            } catch (e: Exception) {
                Log.e(TAG, "event=connectOutbound.fail id=$socketId dt_ms=${SystemClock.elapsedRealtime() - tStart} err=${e.message}")
                listener(socketId, Event.ERROR, deviceAddress, "", null, e.message)
            }
        }, "Rfcomm-Out-$socketId").apply { isDaemon = true; start() }
        return socketId
    }

    fun listenInbound(
        serviceName: String,
        uuid: String,
        idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS
    ): String {
        Log.d(TAG, "event=listenInbound.request service=$serviceName uuid=$uuid idle_timeout_ms=$idleTimeoutMs")
        // Close any existing inbound listener on the same UUID first. This protects against
        // orphan server sockets when a client app was force-stopped without explicit cleanup:
        // the dead app's RemoteCallback is auto-removed by linkToDeath, but its server socket
        // stays listening here. Multiple listeners on one UUID cause SDP confusion (incoming
        // connections land on the old socket, messages get dropped).
        val staleIds = sockets.entries
            .filter { it.value.isServer && it.value.uuid == uuid }
            .map { it.key }
            .toList()
        for (staleId in staleIds) {
            Log.i(TAG, "Closing existing inbound listener $staleId before new listen on $uuid")
            close(staleId)
        }

        val socketId = "in_${counter.incrementAndGet()}"
        val entry = SocketEntry(
            id = socketId,
            socket = null,
            serverSocket = null,
            outputStream = null,
            address = "",
            name = "",
            isServer = true,
            connected = false,
            uuid = uuid,
            idleTimeoutMs = idleTimeoutMs
        )
        sockets[socketId] = entry
        val uuidObj = UUID.fromString(uuid)

        Thread({
            val tThreadStart = SystemClock.elapsedRealtime()
            Log.d(TAG, "event=listen.threadStart id=$socketId service=$serviceName")
            var backoffMs = 1000L
            val maxBackoff = 30000L
            var listenCycle = 0
            while (sockets.containsKey(socketId)) {
                listenCycle++
                var serverSocket: BluetoothServerSocket? = null
                val tCycle = SystemClock.elapsedRealtime()
                try {
                    GT.begin("bt.rfcomm.listen_setup")
                    Log.d(TAG, "event=listen.openServerSocket id=$socketId cycle=$listenCycle")
                    serverSocket = adapter?.listenUsingRfcommWithServiceRecord(serviceName, uuidObj)
                    if (serverSocket == null) {
                        GT.end()
                        Log.w(TAG, "event=listen.adapterNull id=$socketId cycle=$listenCycle backoff_ms=$backoffMs")
                        Thread.sleep(backoffMs)
                        backoffMs = minOf(backoffMs * 2, maxBackoff)
                        continue
                    }
                    entry.serverSocket = serverSocket
                    Log.i(TAG, "event=listen.ready id=$socketId service=$serviceName cycle=$listenCycle open_ms=${SystemClock.elapsedRealtime() - tCycle}")
                    backoffMs = 1000L  // reset after successful listen
                    GT.end()

                    var acceptIter = 0
                    // Accept loop on the current server socket
                    acceptLoop@ while (sockets.containsKey(socketId)) {
                        acceptIter++
                        val tAcceptStart = SystemClock.elapsedRealtime()
                        Log.d(TAG_ACCEPT, "event=accept.wait id=$socketId iter=$acceptIter")
                        try {
                            val clientSocket = serverSocket.accept()
                            val tAccepted = SystemClock.elapsedRealtime()
                            if (clientSocket != null) {
                                GT.begin("bt.rfcomm.accept")
                                val addr = clientSocket.remoteDevice?.address ?: ""
                                val devName = clientSocket.remoteDevice?.name ?: ""
                                entry.socket = clientSocket
                                entry.outputStream = clientSocket.outputStream
                                entry.address = addr
                                entry.name = devName
                                entry.connected = true
                                entry.activeSince = tAccepted
                                entry.lastActivityMs = tAccepted
                                Log.i(TAG_ACCEPT, "event=accept.ok id=$socketId iter=$acceptIter addr=$addr name=$devName wait_ms=${tAccepted - tAcceptStart} idle_timeout_ms=${entry.idleTimeoutMs}")
                                listener(socketId, Event.CONNECTED, addr, devName, null, null)
                                startIdleWatchdog(entry)
                                GT.end()
                                readLoop(socketId, clientSocket.inputStream)
                                val tReadEnd = SystemClock.elapsedRealtime()
                                Log.d(TAG_ACCEPT, "event=accept.sessionEnd id=$socketId iter=$acceptIter session_ms=${tReadEnd - tAccepted}")
                                stopIdleWatchdog(entry)
                                // Client disconnected, reset transient fields and keep listening
                                try { entry.outputStream?.close() } catch (_: Exception) {}
                                try { entry.socket?.close() } catch (_: Exception) {}
                                entry.socket = null
                                entry.outputStream = null
                                entry.address = ""
                                entry.name = ""
                                entry.connected = false
                                entry.activeSince = 0L
                                entry.lastActivityMs = 0L
                            } else {
                                Log.d(TAG_ACCEPT, "event=accept.nullSocket id=$socketId iter=$acceptIter wait_ms=${tAccepted - tAcceptStart}")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG_ACCEPT, "event=accept.error id=$socketId iter=$acceptIter wait_ms=${SystemClock.elapsedRealtime() - tAcceptStart} err=${e.message}")
                            break@acceptLoop  // break to outer loop -> rebuild server socket
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "event=listen.setupFail id=$socketId cycle=$listenCycle dt_ms=${SystemClock.elapsedRealtime() - tCycle} err=${e.message}")
                } finally {
                    try { serverSocket?.close() } catch (_: Exception) {}
                    entry.serverSocket = null
                    Log.d(TAG, "event=listen.cycleEnd id=$socketId cycle=$listenCycle cycle_ms=${SystemClock.elapsedRealtime() - tCycle}")
                }
                if (sockets.containsKey(socketId)) {
                    Log.w(TAG, "event=listen.rebuild id=$socketId cycle=$listenCycle backoff_ms=$backoffMs")
                    try { Thread.sleep(backoffMs) } catch (_: Exception) {}
                    backoffMs = minOf(backoffMs * 2, maxBackoff)
                }
            }
            Log.i(TAG, "event=listen.threadExit id=$socketId cycles=$listenCycle uptime_ms=${SystemClock.elapsedRealtime() - tThreadStart}")
        }, "Rfcomm-In-$socketId").apply { isDaemon = true; start() }
        return socketId
    }

    private fun readLoop(socketId: String, inputStream: InputStream) {
        val buffer = ByteArray(4096)
        val tStart = SystemClock.elapsedRealtime()
        var iter = 0L
        var totalBytes = 0L
        var lastLogMs = tStart
        var lastLogIter = 0L
        var lastLogBytes = 0L
        Log.d(TAG_READ, "event=readLoop.start id=$socketId bufSize=${buffer.size}")
        try {
            while (sockets[socketId]?.connected == true) {
                val tRead = SystemClock.elapsedRealtime()
                val bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) {
                    Log.d(TAG_READ, "event=readLoop.eof id=$socketId iter=$iter total_bytes=$totalBytes uptime_ms=${SystemClock.elapsedRealtime() - tStart}")
                    break
                }
                iter++
                totalBytes += bytesRead
                val now = SystemClock.elapsedRealtime()
                // Mark activity for idle teardown unconditionally. The heartbeat is gone
                // (replaced by setActiveSession ref-counting), so every read counts as
                // real traffic.
                sockets[socketId]?.lastActivityMs = now
                if (iter - lastLogIter >= READ_LOG_EVERY_N || now - lastLogMs >= READ_LOG_INTERVAL_MS) {
                    val windowMs = now - lastLogMs
                    val windowIter = iter - lastLogIter
                    val windowBytes = totalBytes - lastLogBytes
                    Log.v(
                        TAG_READ,
                        "event=readLoop.tick id=$socketId iter=$iter bytes=$bytesRead total_bytes=$totalBytes window_iter=$windowIter window_bytes=$windowBytes window_ms=$windowMs read_ms=${now - tRead}"
                    )
                    lastLogMs = now
                    lastLogIter = iter
                    lastLogBytes = totalBytes
                }
                val data = buffer.copyOf(bytesRead)
                listener(socketId, Event.DATA, "", "", data, null)
            }
        } catch (e: Exception) {
            Log.w(TAG_READ, "event=readLoop.exception id=$socketId iter=$iter total_bytes=$totalBytes uptime_ms=${SystemClock.elapsedRealtime() - tStart} err=${e.message}")
        }
        Log.i(TAG_READ, "event=readLoop.end id=$socketId iter=$iter total_bytes=$totalBytes uptime_ms=${SystemClock.elapsedRealtime() - tStart}")
        sockets[socketId]?.connected = false
        listener(socketId, Event.DISCONNECTED, "", "", null, null)
    }

    fun write(socketId: String, data: ByteArray) {
        val entry = sockets[socketId] ?: run {
            Log.d(TAG_WRITE, "event=write.noEntry id=$socketId bytes=${data.size}")
            return
        }
        if (!entry.connected) {
            Log.d(TAG_WRITE, "event=write.notConnected id=$socketId bytes=${data.size}")
            return
        }
        val out = entry.outputStream ?: run {
            Log.d(TAG_WRITE, "event=write.noStream id=$socketId bytes=${data.size}")
            return
        }
        val tStart = SystemClock.elapsedRealtime()
        try {
            val tLockWait: Long
            synchronized(entry.writeLock) {
                tLockWait = SystemClock.elapsedRealtime() - tStart
                val tWrite = SystemClock.elapsedRealtime()
                out.write(data)
                out.flush()
                val dt = SystemClock.elapsedRealtime() - tWrite
                // Mark activity for idle teardown unconditionally (heartbeat removed).
                entry.lastActivityMs = SystemClock.elapsedRealtime()
                Log.v(TAG_WRITE, "event=write.ok id=$socketId bytes=${data.size} lock_ms=$tLockWait io_ms=$dt")
            }
        } catch (e: Exception) {
            Log.e(TAG_WRITE, "event=write.fail id=$socketId bytes=${data.size} dt_ms=${SystemClock.elapsedRealtime() - tStart} err=${e.message}")
            entry.connected = false
            listener(socketId, Event.ERROR, entry.address, entry.name, null, "Write failed: ${e.message}")
        }
    }

    /**
     * Starts a per-connection watchdog that closes the connected socket if no non-heartbeat
     * traffic is observed for [SocketEntry.idleTimeoutMs]. The accept loop keeps the server
     * socket open so the phone's auto-reconnect can re-establish the channel on demand.
     *
     * Releases hal_bluetooth_lock / qup_uart wakelocks between bursts so the SoC AP can
     * deep-sleep. No-op if idleTimeoutMs <= 0.
     */
    private fun startIdleWatchdog(entry: SocketEntry) {
        val timeoutMs = entry.idleTimeoutMs
        if (timeoutMs <= 0L) return
        val watchdog = Thread({
            Log.d(TAG_IDLE, "event=watchdog.start id=${entry.id} timeout_ms=$timeoutMs")
            try {
                while (entry.connected && sockets.containsKey(entry.id)) {
                    Thread.sleep(IDLE_CHECK_INTERVAL_MS)
                    if (!entry.connected) break
                    GT.begin("bt.rfcomm.watchdog_tick")
                    val now = SystemClock.elapsedRealtime()
                    // While any caller holds an active session, refuse to tear down. Refresh
                    // lastActivityMs so the timer resumes counting from "now" once all
                    // sessions clear -- prevents an immediate teardown on the next tick.
                    if (isSessionActive()) {
                        entry.lastActivityMs = now
                        GT.end()
                        continue
                    }
                    val sinceActivity = now - entry.lastActivityMs
                    if (sinceActivity >= timeoutMs) {
                        val activeFor = now - entry.activeSince
                        Log.i(
                            TAG_IDLE,
                            "event=idle.teardown id=${entry.id} active_ms=$activeFor since_activity_ms=$sinceActivity timeout_ms=$timeoutMs reason=closing socket to release wakelocks"
                        )
                        try {
                            entry.outputStream?.close()
                        } catch (_: Exception) {}
                        try {
                            entry.socket?.close()
                        } catch (e: Exception) {
                            Log.w(TAG_IDLE, "event=idle.teardown.closeErr id=${entry.id} err=${e.message}")
                        }
                        // Don't null out fields here -- the accept loop's post-readLoop
                        // cleanup handles that. Just mark disconnected so this watchdog exits.
                        entry.connected = false
                        GT.end()
                        break
                    }
                    GT.end()
                }
            } catch (_: InterruptedException) {
                Log.d(TAG_IDLE, "event=watchdog.interrupted id=${entry.id}")
            }
            Log.d(TAG_IDLE, "event=watchdog.exit id=${entry.id}")
        }, "Rfcomm-Idle-${entry.id}").apply { isDaemon = true }
        entry.idleWatchdog = watchdog
        watchdog.start()
    }

    private fun stopIdleWatchdog(entry: SocketEntry) {
        val w = entry.idleWatchdog ?: return
        entry.idleWatchdog = null
        try { w.interrupt() } catch (_: Exception) {}
    }

    fun close(socketId: String) {
        Log.d(TAG, "event=close.request id=$socketId")
        val entry = sockets.remove(socketId) ?: run {
            Log.d(TAG, "event=close.notFound id=$socketId")
            return
        }
        entry.connected = false
        stopIdleWatchdog(entry)
        try { entry.outputStream?.close() } catch (_: Exception) {}
        try { entry.socket?.close() } catch (_: Exception) {}
        try { entry.serverSocket?.close() } catch (_: Exception) {}
        entry.outputStream = null
        entry.socket = null
        entry.serverSocket = null
        Log.i(TAG, "event=close.ok id=$socketId isServer=${entry.isServer}")
    }

    fun isConnected(socketId: String): Boolean = sockets[socketId]?.connected == true

    fun getActiveConnectionsJson(): String {
        val arr = JSONArray()
        sockets.forEach { (id, entry) ->
            arr.put(JSONObject().apply {
                put("id", id)
                put("address", entry.address)
                put("name", entry.name)
                put("connected", entry.connected)
                put("isServer", entry.isServer)
            })
        }
        return arr.toString()
    }

    fun closeAll() {
        val ids = sockets.keys.toList()
        ids.forEach { close(it) }
    }
}
