package com.repository.glasses.listener.input

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reads /dev/input/event* directly to consume the EV_ABS / BTN_TOUCH
 * events emitted by the rokid-touchpad-daemon's uinput device.
 *
 * Position events arrive at ~100 Hz from the daemon. Naively posting
 * each one to the main thread floods the UI queue and freezes long
 * drags. Instead, this class exposes `latestPosition` as an
 * AtomicInteger that the listener thread writes lock-free; the UI
 * layer is expected to drive its reads from a Choreographer frame
 * callback (one read per vsync, ~60-90 Hz). Touch-state transitions
 * are rare (1-2 per gesture) and are delivered on the main thread via
 * `onTouchChanged`.
 */
class TouchpadAbsListener(
    private val onTouchChanged: (down: Boolean) -> Unit
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    /** Latest ABS_X (0..100), or -1 if no sample yet. UI reads this on each frame. */
    val latestPosition = AtomicInteger(-1)

    fun start() {
        if (running) return
        running = true
        thread = Thread({ runLoop() }, "touchpad-abs-listener").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun runLoop() {
        while (running) {
            val path = findDevicePath()
            if (path == null) {
                Log.w(TAG, "rokid-touchpad-virt with EV_ABS not found; retry in 2s")
                Thread.sleep(2000)
                continue
            }
            try {
                Log.i(TAG, "opening $path")
                FileInputStream(path).use { stream ->
                    readEvents(stream)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "permission denied on $path: ${e.message}")
                Thread.sleep(5000)
            } catch (e: Exception) {
                if (running) Log.e(TAG, "read loop ended: ${e.message}")
                Thread.sleep(1000)
            }
        }
    }

    private fun readEvents(stream: FileInputStream) {
        var eventSize = detectEventSize(stream) ?: return
        Log.i(TAG, "input_event size=$eventSize bytes")

        val buf = ByteArray(eventSize * 16)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        while (running) {
            var read = 0
            while (read < eventSize) {
                val n = stream.read(buf, read, buf.size - read)
                if (n < 0) return
                read += n
            }
            var off = 0
            while (off + eventSize <= read) {
                bb.position(off + eventSize - 8)
                val type = bb.short.toInt() and 0xFFFF
                val code = bb.short.toInt() and 0xFFFF
                val value = bb.int
                handleEvent(type, code, value)
                off += eventSize
            }
        }
    }

    private fun detectEventSize(stream: FileInputStream): Int? {
        val buf = ByteArray(24)
        var read = 0
        while (read < buf.size) {
            val n = stream.read(buf, read, buf.size - read)
            if (n < 0) return null
            read += n
        }
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val type24 = bb.getShort(16).toInt() and 0xFFFF
        val type16 = bb.getShort(8).toInt() and 0xFFFF
        val size = when {
            type24 in 0..0x1F -> 24
            type16 in 0..0x1F -> 16
            else -> return null
        }
        bb.position(size - 8)
        val type  = bb.short.toInt() and 0xFFFF
        val code  = bb.short.toInt() and 0xFFFF
        val value = bb.int
        handleEvent(type, code, value)
        return size
    }

    private fun handleEvent(type: Int, code: Int, value: Int) {
        when {
            type == EV_KEY && code == BTN_TOUCH -> {
                // Touch transitions are rare; deliver on main thread.
                main.post { onTouchChanged(value == 1) }
            }
            type == EV_ABS && code == ABS_X -> {
                // Lock-free write; UI reads on next frame.
                latestPosition.set(value)
            }
        }
    }

    companion object {
        private const val TAG = "TouchpadAbsListener"
        private const val EV_KEY = 0x01
        private const val EV_ABS = 0x03
        private const val ABS_X  = 0x00
        private const val BTN_TOUCH = 0x14a

        fun findDevicePath(): String? {
            val text = try {
                File("/proc/bus/input/devices").readText()
            } catch (_: Exception) { return null }

            var bestHandler: String? = null
            var inBlock = false
            var nameMatch = false
            var handler: String? = null
            var evBits = 0L
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty()) {
                    if (nameMatch && handler != null && (evBits and (1L shl 3)) != 0L) {
                        bestHandler = handler
                    }
                    inBlock = false; nameMatch = false; handler = null; evBits = 0L
                    continue
                }
                when {
                    line.startsWith("N:") -> {
                        inBlock = true
                        nameMatch = line.contains("rokid-touchpad-virt")
                    }
                    inBlock && line.startsWith("H:") -> {
                        val m = Regex("event\\d+").find(line)
                        if (m != null) handler = "/dev/input/${m.value}"
                    }
                    inBlock && line.startsWith("B: EV=") -> {
                        evBits = line.removePrefix("B: EV=").trim().toLong(16)
                    }
                }
            }
            return bestHandler
        }
    }
}
