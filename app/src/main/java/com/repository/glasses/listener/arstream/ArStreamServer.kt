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
        audioOut = null
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
            } catch (e: Exception) {
                if (running.get()) log?.invoke("ArStreamServer: send failed: ${e.message}")
                // Drop the client rather than writing to a broken stream 30x a second (which
                // floods the log AND blocks the next client from ever being primed).
                onBroken()
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
        const val VIDEO_QUEUE_CAPACITY = 30
        const val AUDIO_QUEUE_CAPACITY = 100
        const val POLL_MS = 200L
    }
}
