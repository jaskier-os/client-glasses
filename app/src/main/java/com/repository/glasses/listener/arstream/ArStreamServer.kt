package com.repository.glasses.listener.arstream

import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Serves the live AR stream to the phone over the WiFi-Direct link.
 *
 * Two sockets, deliberately: video backpressure on a congested link must never stall voice.
 * The glasses are the WiFi-Direct group owner, so they listen and the phone connects.
 */
class ArStreamServer(
    private val compositor: LiveArCompositor,
    private val audio: ArAudioBridge,
    private val log: ((String) -> Unit)? = null
) {

    /** Invoked when the phone asks to end the session. */
    var onStopRequested: (() -> Unit)? = null

    /** Invoked when the phone mutes/unmutes its own mic, so we can stop playback. */
    var onPhoneMicMuted: ((Boolean) -> Unit)? = null

    private var videoServer: ServerSocket? = null
    private var audioServer: ServerSocket? = null
    private val running = AtomicBoolean(false)

    @Volatile private var videoOut: OutputStream? = null
    @Volatile private var audioOut: OutputStream? = null

    // Sockets are tracked, not just their streams: without a reference the previous client's fd
    // and its blocked reader thread survive every reconnect and every stop().
    @Volatile private var videoSocket: Socket? = null
    @Volatile private var audioSocket: Socket? = null

    /** Set when a client connected before the encoder had produced its config frame. */
    @Volatile private var primePending = false

    // Traffic counters so a silent link can be told apart from a stalled producer.
    @Volatile private var encodedCount = 0L
    @Volatile private var downlinkCount = 0L

    /** Bounded so a stalled client drops frames instead of growing the heap without limit. */
    private val videoQueue = LinkedBlockingQueue<ByteArray>(VIDEO_QUEUE_CAPACITY)
    private val audioQueue = LinkedBlockingQueue<ByteArray>(AUDIO_QUEUE_CAPACITY)

    fun start(): Boolean {
        if (running.get()) return true
        return try {
            videoServer = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(ArStreamProtocol.VIDEO_PORT))
            }
            audioServer = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(ArStreamProtocol.AUDIO_PORT))
            }
            running.set(true)

            thread(name = "ArStream-videoAccept") { acceptVideoLoop() }
            thread(name = "ArStream-audioAccept") { acceptAudioLoop() }
            thread(name = "ArStream-videoSend") {
                sendLoop(videoQueue, { videoOut }) {
                    videoOut = null
                    closeQuietly(videoSocket)
                    videoSocket = null
                    videoQueue.clear()
                }
            }
            thread(name = "ArStream-audioSend") {
                sendLoop(audioQueue, { audioOut }) {
                    audioOut = null
                    closeQuietly(audioSocket)
                    audioSocket = null
                    audioQueue.clear()
                }
            }

            thread(name = "ArStream-peerWatch") { peerWatchLoop() }

            // Wire producers.
            audio.onUplinkAudio = { pcm, len ->
                offer(audioQueue, ArStreamProtocol.frameAudio(pcm, len))
            }

            log?.invoke("ArStreamServer: listening on ${ArStreamProtocol.VIDEO_PORT}/${ArStreamProtocol.AUDIO_PORT}")
            true
        } catch (e: Exception) {
            log?.invoke("ArStreamServer: start failed: ${e.message}")
            stop()
            false
        }
    }

    /** Called by the compositor for every encoded frame. */
    fun onEncodedFrame(payload: ByteArray, keyframe: Boolean, config: Boolean) {
        encodedCount++
        if (encodedCount == 1L || config || encodedCount % 150L == 0L) {
            log?.invoke(
                "ArStreamServer: encoded #$encodedCount len=${payload.size} key=$keyframe " +
                    "config=$config client=${videoOut != null}"
            )
        }
        if (videoOut == null) return
        // A client that connected before the encoder emitted its first config frame got nothing to
        // prime with; send it the moment it exists, or that client decodes nothing forever.
        if (config && primePending) {
            primePending = false
            compositor.requestKeyframe()
        }
        offer(videoQueue, ArStreamProtocol.frameVideo(payload, keyframe, config, System.currentTimeMillis()))
    }

    private fun acceptVideoLoop() {
        while (running.get()) {
            val socket = try {
                videoServer?.accept() ?: break
            } catch (e: Exception) {
                if (running.get()) log?.invoke("ArStreamServer: video accept failed: ${e.message}")
                break
            }
            try {
                socket.tcpNoDelay = true
                // Replace, don't accumulate: the old client's socket would otherwise stay open.
                closeQuietly(videoSocket)
                videoSocket = socket
                videoOut = socket.getOutputStream()
                videoQueue.clear()
                log?.invoke("ArStreamServer: video client connected")
                // A new client has no SPS/PPS and the phone decoder caches none, so replay the
                // cached config and force an IDR. Without this the phone shows a black surface
                // indefinitely and logs nothing.
                primeClient()
            } catch (e: Exception) {
                log?.invoke("ArStreamServer: video client setup failed: ${e.message}")
                closeQuietly(socket)
            }
        }
    }

    /**
     * Ends the session when the phone stops being a live peer.
     *
     * Every teardown path other than this one requires the phone to send CTRL_STOP.
     * A force-stopped phone app, a BT/WiFi drop, or a half-open TCP connection sends
     * nothing, so without this the glasses would stream to nobody -- camera, mics,
     * P2P group and the A2DP hold all still up -- until the user noticed. The
     * grace period covers the gap between the servers binding and the phone's first
     * connect, and any brief mid-session reconnect.
     */
    private fun peerWatchLoop() {
        val startedAt = System.currentTimeMillis()
        var lastSeenPeer = startedAt
        while (running.get()) {
            try { Thread.sleep(PEER_WATCH_TICK_MS) } catch (_: InterruptedException) { return }
            if (!running.get()) return
            val connected = videoSocket?.isConnected == true && videoSocket?.isClosed == false ||
                audioSocket?.isConnected == true && audioSocket?.isClosed == false
            val now = System.currentTimeMillis()
            if (connected) {
                lastSeenPeer = now
                continue
            }
            if (now - lastSeenPeer >= PEER_GRACE_MS) {
                log?.invoke("ArStreamServer: no peer for ${(now - lastSeenPeer) / 1000}s -- stopping session")
                try { onStopRequested?.invoke() } catch (_: Exception) {}
                return
            }
        }
    }

    private fun primeClient() {
        val config = compositor.configFrame
        if (config != null) {
            offer(videoQueue, ArStreamProtocol.frameVideo(config, keyframe = false, config = true, timestampMs = 0L))
            compositor.requestKeyframe()
        } else {
            // Encoder has not produced SPS/PPS yet; onEncodedFrame will finish the priming.
            primePending = true
        }
    }

    private fun acceptAudioLoop() {
        while (running.get()) {
            val socket = try {
                audioServer?.accept() ?: break
            } catch (e: Exception) {
                if (running.get()) log?.invoke("ArStreamServer: audio accept failed: ${e.message}")
                break
            }
            try {
                socket.tcpNoDelay = true
                // Closing the old socket also unblocks its reader thread's readInt().
                closeQuietly(audioSocket)
                audioSocket = socket
                audioOut = socket.getOutputStream()
                audioQueue.clear()
                log?.invoke("ArStreamServer: audio client connected")
                thread(name = "ArStream-audioRecv") { receiveLoop(socket) }
            } catch (e: Exception) {
                log?.invoke("ArStreamServer: audio client setup failed: ${e.message}")
                closeQuietly(socket)
            }
        }
    }

    private fun receiveLoop(socket: Socket) {
        val input = DataInputStream(socket.getInputStream().buffered())
        while (running.get()) {
            try {
                val len = input.readInt()
                if (len <= 0 || len > ArStreamProtocol.MAX_FRAME_BYTES) {
                    log?.invoke("ArStreamServer: bad frame length $len, dropping client")
                    break
                }
                val body = ByteArray(len)
                input.readFully(body)

                when (body[0]) {
                    ArStreamProtocol.MSG_AUDIO -> {
                        val pcm = ArStreamProtocol.decodeAudio(body)
                        downlinkCount++
                        if (downlinkCount == 1L || downlinkCount % 200L == 0L) {
                            var peak = 0
                            for (s in pcm) { val a = kotlin.math.abs(s.toInt()); if (a > peak) peak = a }
                            log?.invoke("ArStreamServer: downlink #$downlinkCount samples=${pcm.size} peak=$peak")
                        }
                        audio.playDownlink(pcm, pcm.size)
                    }
                    ArStreamProtocol.MSG_CONTROL -> handleControl(body)
                    else -> {}
                }
            } catch (e: Exception) {
                if (running.get()) log?.invoke("ArStreamServer: audio recv ended: ${e.message}")
                break
            }
        }
        // Only clear if this reader still owns the current socket: a superseded thread exiting
        // late must not null out the replacement client's output stream.
        if (socket === audioSocket) audioOut = null
    }

    private fun handleControl(body: ByteArray) {
        if (body.size < 3) return
        val opcode = body[1]
        val on = body[2].toInt() != 0
        when (opcode) {
            ArStreamProtocol.CTRL_MUTE_GLASSES_MIC -> audio.setGlassesMicMuted(on)
            ArStreamProtocol.CTRL_MUTE_PHONE_MIC -> {
                // Phone stops sending; also stop playback so nothing lingers in the buffer.
                audio.setPlaybackMuted(on)
                onPhoneMicMuted?.invoke(on)
            }
            ArStreamProtocol.CTRL_REQUEST_KEYFRAME -> primeClient()
            ArStreamProtocol.CTRL_STOP -> onStopRequested?.invoke()
        }
    }

    private fun sendLoop(
        queue: LinkedBlockingQueue<ByteArray>,
        out: () -> OutputStream?,
        onBroken: () -> Unit,
    ) {
        // A single failed write is not a dead client: a WiFi-Direct hiccup for one frame is
        // routine, and dropping the socket on the first one closed the stream, which the phone
        // saw as EOF and turned into finish() -- the whole session died from one bad frame.
        // Only a RUN of failures means the peer is really gone.
        var consecutiveFailures = 0
        while (running.get()) {
            val frame = try {
                queue.poll(POLL_MS, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
            } catch (_: InterruptedException) {
                break
            }
            val stream = out() ?: continue
            try {
                stream.write(frame)
                stream.flush()
                consecutiveFailures = 0
            } catch (e: Exception) {
                consecutiveFailures++
                if (running.get()) {
                    log?.invoke(
                        "ArStreamServer: send failed ($consecutiveFailures/$MAX_CONSECUTIVE_SEND_FAILURES): ${e.message}"
                    )
                }
                if (consecutiveFailures >= MAX_CONSECUTIVE_SEND_FAILURES) {
                    // Drop the client rather than writing to a broken stream 30x a second (which
                    // floods the log AND blocks the next client from ever being primed).
                    if (running.get()) log?.invoke("ArStreamServer: onBroken after $consecutiveFailures consecutive send failures")
                    consecutiveFailures = 0
                    onBroken()
                }
            }
        }
    }

    /** Drop the oldest frame when the consumer cannot keep up, rather than blocking the encoder. */
    private fun offer(queue: LinkedBlockingQueue<ByteArray>, frame: ByteArray) {
        if (!queue.offer(frame)) {
            queue.poll()
            queue.offer(frame)
        }
    }

    fun stop() {
        running.set(false)
        audio.onUplinkAudio = null
        try { videoServer?.close() } catch (_: Exception) {}
        try { audioServer?.close() } catch (_: Exception) {}
        videoServer = null
        audioServer = null
        // Close the CLIENT sockets too: receiveLoop is parked in readInt() on a live socket and
        // only closing it unblocks that thread. Otherwise it survives the session and keeps
        // feeding playDownlink into a stopped audio bridge.
        closeQuietly(videoSocket)
        closeQuietly(audioSocket)
        videoSocket = null
        audioSocket = null
        videoOut = null
        audioOut = null
        primePending = false
        videoQueue.clear()
        audioQueue.clear()
        log?.invoke("ArStreamServer: stopped")
    }

    private fun closeQuietly(socket: Socket?) {
        try { socket?.close() } catch (_: Exception) {}
    }

    private companion object {
        const val PEER_WATCH_TICK_MS = 2_000L

        /**
         * How long the session survives with no connected peer. Long enough for the
         * phone's initial connect after start_ar_stream (and a brief reconnect), short
         * enough that a dead phone does not strand the glasses streaming to nobody.
         */
        const val PEER_GRACE_MS = 20_000L

        const val VIDEO_QUEUE_CAPACITY = 30
        const val AUDIO_QUEUE_CAPACITY = 100
        const val POLL_MS = 200L

        /** Consecutive failed writes before a client counts as gone. */
        const val MAX_CONSECUTIVE_SEND_FAILURES = 3
    }
}
