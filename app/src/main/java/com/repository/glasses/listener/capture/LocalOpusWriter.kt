package com.repository.glasses.listener.capture

import android.content.Context
import android.os.Environment
import android.util.Log
import com.repository.glasses.listener.audio.OpusEncoder
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Subscribes to [MicBus] and writes rotating Opus files to
 * /sdcard/DCIM/Repository/audio-archive/<yyyy-MM-dd>/rec_<epochStartMillis>.opus
 * (the same shared-storage rootDir filesync uses for video/photo). Writing into
 * the listener's app-private filesDir made the files unreadable from the
 * filesync APK (separate UID), so notifyNew was rejected with "file missing".
 * DCIM/Repository is world-readable on this device and is the canonical sync
 * surface for both apps. Files use the
 * same 2-byte LE length-prefix framing the phone-side decoder already uses
 * for live BT audio (`[len_lo, len_hi, opus_bytes] x N`).
 *
 * Rotation rules:
 *   - rotate when the current file is >= 60 minutes old, OR
 *   - rotate when the current file has reached ~7 MB,
 *   whichever triggers first.
 *
 * On rotation: fsync + close the current file, then scan the archive root
 * and delete anything with mtime older than 24 h. A new file with a fresh
 * epoch-start millis name is opened.
 *
 * On [stop]: flush + fsync + close the current file and unsubscribe from
 * [MicBus]. Never appends on resume -- [start] always opens a fresh file.
 *
 * Threading: MicBus delivers PCM on the mic emit thread. This class does
 * not block that thread; it copies the slice into a pooled ShortArray,
 * hands it to a dedicated single-thread IO executor, and returns. All
 * encoder + disk work happens on the IO executor.
 */
class LocalOpusWriter(
    private val context: Context,
    private val remoteLog: ((String) -> Unit)? = null
) : MicSubscriber {

    companion object {
        private const val TAG = "LocalOpusWriter"
        private const val ARCHIVE_SUBDIR = "audio-archive"

        private const val ROTATE_AFTER_MILLIS = 60L * 60L * 1000L
        private const val ROTATE_AFTER_BYTES = 7L * 1024L * 1024L
        private const val RETAIN_MILLIS = 24L * 60L * 60L * 1000L
        private const val FSYNC_EVERY_NANOS = 5L * 1_000_000_000L

        private const val SAMPLE_RATE_HZ = 16000
        private const val BITRATE_BPS = 16000
    }

    // --- lifecycle state (guarded by `this`) -----------------------------------------

    private val started = AtomicBoolean(false)
    @Volatile private var ioExecutor: ExecutorService? = null
    @Volatile private var encoder: OpusEncoder? = null

    // --- output file state (only touched from the IO executor) -----------------------

    private var currentFile: File? = null
    private var currentOut: BufferedOutputStream? = null
    private var currentFd: FileOutputStream? = null
    private var currentFileOpenedAtMillis: Long = 0L
    private var currentFileBytes: Long = 0L
    private var lastFsyncNanos: Long = 0L

    @Volatile private var lastClosedFile: File? = null

    fun isRunning(): Boolean = started.get()

    fun lastClosedFileOrNull(): File? = lastClosedFile

    private val dayFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    // =================================================================================

    fun start() {
        synchronized(this) {
            if (started.get()) {
                log("start: already started -- no-op")
                return
            }
            if (!OpusEncoder.nativeLoaded) {
                logErr("start: native opus_jni not loaded (${OpusEncoder.nativeLoadError}); writer disabled")
                return
            }
            val enc = OpusEncoder(
                sampleRate = SAMPLE_RATE_HZ,
                bitrate = BITRATE_BPS,
                channels = 1,
                log = { msg -> log("encoder: $msg") }
            )
            if (!enc.initialize()) {
                logErr("start: OpusEncoder.initialize() failed; writer disabled")
                return
            }
            encoder = enc

            val exec = Executors.newSingleThreadExecutor { r ->
                Thread(r, "LocalOpusWriter-IO").apply { isDaemon = true }
            }
            ioExecutor = exec

            started.set(true)
            MicBus.subscribe(this)
            log("start: subscribed to MicBus (native libopus, ${SAMPLE_RATE_HZ}Hz, ${BITRATE_BPS}bps)")
        }
    }

    /**
     * Close the current file (fsync + close) and immediately open a fresh one.
     * Returns the just-closed File (now reflected in [lastClosedFileOrNull]) or
     * null if nothing was open. Runs synchronously on the IO executor; callers
     * should be on a different thread (e.g. reconcileExecutor).
     */
    fun rotateNow(reason: String): File? {
        if (!started.get()) return null
        val exec = ioExecutor ?: return null
        val task = exec.submit<File?> {
            val before = currentFile
            if (before == null) {
                log("rotateNow($reason): no current file -- noop")
                return@submit null
            }
            val closed = closeCurrentFile(reason = "rotateNow:$reason")
            // If the writer was concurrently stopped while we were closing, do not
            // reopen -- caller will see only the close happen, no orphan file.
            if (started.get()) {
                openNewFile()
            } else {
                log("rotateNow($reason): writer stopped during close; skipping openNewFile")
            }
            closed
        }
        return try {
            task.get(10, TimeUnit.SECONDS)
        } catch (t: java.util.concurrent.TimeoutException) {
            // Don't strand the task on a hung close. Cancel + warn; caller treats null
            // as "no file synced" so the on-demand file is not double-synced from a
            // stale path.
            task.cancel(false)
            logErr("rotateNow($reason): IO task timed out after 10s; cancelled. Caller must NOT notifyFileSync.")
            null
        } catch (t: Throwable) {
            logErr("rotateNow($reason) failed: ${t.message}")
            null
        }
    }

    fun stop() {
        val exec: ExecutorService?
        val enc: OpusEncoder?
        synchronized(this) {
            if (!started.compareAndSet(true, false)) {
                return
            }
            MicBus.unsubscribe(this)
            exec = ioExecutor
            enc = encoder
            ioExecutor = null
            encoder = null
        }
        if (exec != null) {
            exec.execute {
                try {
                    closeCurrentFile(reason = "stop")
                } catch (t: Throwable) {
                    logErr("stop: closeCurrentFile threw: ${t.message}")
                }
                try {
                    enc?.release()
                } catch (t: Throwable) {
                    logErr("stop: encoder.release threw: ${t.message}")
                }
            }
            exec.shutdown()
            try {
                if (!exec.awaitTermination(3, TimeUnit.SECONDS)) {
                    exec.shutdownNow()
                }
            } catch (_: InterruptedException) {
                exec.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }
        log("stop: done")
    }

    // --- MicSubscriber ---------------------------------------------------------------

    override fun onPcmFrame(pcmMono16k: ShortArray, offset: Int, length: Int, epochNanos: Long) {
        if (!started.get()) return
        if (length <= 0) return
        val exec = ioExecutor ?: return

        // MicBus re-uses pcmMono16k across calls, so copy the slice before we
        // hand it off to the IO executor.
        val copy = ShortArray(length)
        System.arraycopy(pcmMono16k, offset, copy, 0, length)

        exec.execute {
            try {
                handlePcmOnIo(copy)
            } catch (t: Throwable) {
                logErr("io: handlePcm threw ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    override fun onStreamStart() {
        log("MicBus stream start")
    }

    override fun onStreamStop() {
        val exec = ioExecutor ?: return
        exec.execute {
            // Lock-free access to currentOut / currentFd / lastFsyncNanos is safe here
            // because every mutator (openNewFile, closeCurrentFile, safeCloseOnError,
            // handlePcmOnIo, maybeFsync) runs on ioExecutor, which is a single-thread
            // executor. All operations are therefore serialized on one thread.
            try {
                // Flush whatever we have but keep the file open -- mic may resume.
                currentOut?.flush()
                currentFd?.fd?.sync()
                lastFsyncNanos = System.nanoTime()
            } catch (t: Throwable) {
                logErr("io: onStreamStop fsync threw: ${t.message}")
            }
        }
    }

    // --- IO thread body --------------------------------------------------------------

    private fun handlePcmOnIo(pcm: ShortArray) {
        val enc = encoder ?: return
        if (!enc.isAvailable()) return

        ensureCurrentFile()
        val out = currentOut ?: return

        // encodeShort throws IllegalArgumentException on a real range error (vs returning
        // null for "not enough samples yet"). The inputs here are always (0, pcm.size)
        // so range errors are not expected, but a defensive catch keeps the IO executor
        // alive if the contract ever drifts -- better to drop a frame than crash the
        // archive writer.
        val framed: ByteArray? = try {
            enc.encodeShort(pcm, 0, pcm.size)
        } catch (e: IllegalArgumentException) {
            logErr("io: encodeShort range error: ${e.message}")
            return
        }
        if (framed == null || framed.isEmpty()) return

        try {
            out.write(framed)
            currentFileBytes += framed.size.toLong()
        } catch (e: IOException) {
            logErr("io: write failed: ${e.message}")
            safeCloseOnError()
            return
        }

        maybeFsync()
        maybeRotate()
    }

    private fun ensureCurrentFile() {
        if (currentOut != null) return
        openNewFile()
    }

    private fun archiveRoot(): File {
        // Shared-storage rootDir: same DCIM/Repository the filesync APK serves
        // video/photo from. Cross-UID readable on this device.
        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        return File(File(dcim, "Repository"), ARCHIVE_SUBDIR)
    }

    private fun openNewFile() {
        val now = System.currentTimeMillis()
        val day = dayFormatter.format(Date(now))
        val root = archiveRoot()
        val dir = File(root, day)
        if (!dir.exists() && !dir.mkdirs()) {
            logErr("openNewFile: mkdirs failed for ${dir.absolutePath}")
            return
        }
        val file = File(dir, "rec_${now}.opus")
        try {
            val fos = FileOutputStream(file, /* append = */ false)
            val bos = BufferedOutputStream(fos, 8192)
            currentFd = fos
            currentOut = bos
            currentFile = file
            currentFileOpenedAtMillis = now
            currentFileBytes = 0L
            lastFsyncNanos = System.nanoTime()
            log("openNewFile: ${file.absolutePath}")
        } catch (e: IOException) {
            logErr("openNewFile: open failed: ${e.message}")
            currentFd = null
            currentOut = null
            currentFile = null
        }
    }

    private fun maybeFsync() {
        val nowNanos = System.nanoTime()
        if (nowNanos - lastFsyncNanos < FSYNC_EVERY_NANOS) return
        try {
            currentOut?.flush()
            currentFd?.fd?.sync()
            lastFsyncNanos = nowNanos
        } catch (e: IOException) {
            logErr("maybeFsync: ${e.message}")
        }
    }

    private fun maybeRotate() {
        val now = System.currentTimeMillis()
        val ageMs = now - currentFileOpenedAtMillis
        if (ageMs < ROTATE_AFTER_MILLIS && currentFileBytes < ROTATE_AFTER_BYTES) return
        log(
            "maybeRotate: rotating (ageMs=$ageMs bytes=$currentFileBytes " +
                "thresholdMs=$ROTATE_AFTER_MILLIS thresholdBytes=$ROTATE_AFTER_BYTES)"
        )
        closeCurrentFile(reason = "rotate")
        pruneOldFiles(now)
        openNewFile()
    }

    private fun closeCurrentFile(reason: String): File? {
        val bos = currentOut
        val fos = currentFd
        val file = currentFile
        currentOut = null
        currentFd = null
        currentFile = null
        currentFileOpenedAtMillis = 0L
        currentFileBytes = 0L
        if (bos == null || fos == null) return null
        try {
            bos.flush()
            fos.fd.sync()
        } catch (e: IOException) {
            logErr("closeCurrentFile($reason): fsync threw ${e.message}")
        }
        try {
            bos.close()
        } catch (e: IOException) {
            logErr("closeCurrentFile($reason): bos.close threw ${e.message}")
        }
        try {
            fos.close()
        } catch (e: IOException) {
            logErr("closeCurrentFile($reason): fos.close threw ${e.message}")
        }
        if (file != null) {
            lastClosedFile = file
        }
        log("closeCurrentFile($reason): ${file?.absolutePath}")
        return file
    }

    private fun safeCloseOnError() {
        val bos = currentOut
        val fos = currentFd
        currentOut = null
        currentFd = null
        currentFile = null
        currentFileOpenedAtMillis = 0L
        currentFileBytes = 0L
        try { bos?.close() } catch (_: IOException) {}
        try { fos?.close() } catch (_: IOException) {}
    }

    private fun pruneOldFiles(nowMillis: Long) {
        val root = archiveRoot()
        if (!root.isDirectory) return
        val cutoff = nowMillis - RETAIN_MILLIS
        var deletedFiles = 0
        val dayDirs = root.listFiles() ?: return
        for (dayDir in dayDirs) {
            if (!dayDir.isDirectory) continue
            val files = dayDir.listFiles() ?: continue
            for (f in files) {
                if (!f.isFile) continue
                val mtime = f.lastModified()
                if (mtime in 1 until cutoff) {
                    if (f.delete()) {
                        deletedFiles++
                    }
                }
            }
            // If the dir is now empty, remove it too (best-effort).
            val remaining = dayDir.listFiles()
            if (remaining == null || remaining.isEmpty()) {
                dayDir.delete()
            }
        }
        if (deletedFiles > 0) {
            log("pruneOldFiles: removed $deletedFiles files older than 24h")
        }
    }

    // --- logging ---------------------------------------------------------------------

    private fun log(msg: String) {
        Log.i(TAG, msg)
        remoteLog?.invoke("[$TAG] $msg")
    }

    private fun logErr(msg: String) {
        Log.e(TAG, msg)
        remoteLog?.invoke("[$TAG][ERR] $msg")
    }
}
