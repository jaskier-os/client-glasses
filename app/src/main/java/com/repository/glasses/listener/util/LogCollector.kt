package com.repository.glasses.listener.util

import android.content.Context
import android.content.Intent
import android.os.Environment
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * In-memory log collector that captures app logs for the current session.
 * Mirrors android.util.Log API -- call L.i/e/w/d instead of Log.i/e/w/d
 * to capture logs both to logcat and to the in-memory buffer.
 * Thread-safe, capped at MAX_ENTRIES to prevent OOM.
 *
 * Call [init] once from Application.onCreate() to enable persistent file logging.
 * Logs are written to filesDir/logs/latest.log with session rotation.
 * On each init, the previous latest.log is compressed into a timestamped .zip archive.
 * Only the last 14 archives are kept (FIFO). Latest.log is capped at 50MB (FIFO, keeps newest 25MB).
 */
object LogCollector {

    const val ACTION_LOG_RELAY = "com.repository.glasses.listener.LOG_RELAY"
    const val EXTRA_LOG_LINE = "line"

    private const val MAX_ENTRIES = 2000
    private const val MAX_SESSIONS = 14
    private const val EXTERNAL_MAX_SIZE = 2L * 1024 * 1024 // 2MB
    private const val MAX_LOG_SIZE = 50L * 1024 * 1024
    private const val FIFO_KEEP_SIZE = 25L * 1024 * 1024
    private val entries = ConcurrentLinkedDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val sessionFmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    private val writerLock = Any()
    private var writer: BufferedWriter? = null
    private var externalWriter: BufferedWriter? = null
    private var externalFile: File? = null
    private var latestLogFile: File? = null
    private var writeCount = 0

    // Relay mode: main process sends logs to backend via broadcast
    private var relayContext: Context? = null

    fun setRelayMode(context: Context) {
        relayContext = context.applicationContext
    }

    fun init(context: Context) {
        val logsDir = File(context.filesDir, "logs")
        logsDir.mkdirs()
        try {
            val latest = File(logsDir, "latest.log")
            if (latest.exists() && latest.length() > 0) {
                val ts = sessionFmt.format(Date())
                val zipFile = File(logsDir, "$ts.zip")
                zipToArchive(latest, "$ts.log", zipFile)
                latest.delete()
            }
            pruneOldSessions(logsDir)
            latestLogFile = latest
            writer = BufferedWriter(FileWriter(latest, true))
        } catch (e: Exception) {
            android.util.Log.e("LogCollector", "Failed to init internal log: ${e.message}")
        }

        // External log file served by Rokid HTTP server on port 8848
        try {
            val extDir = File(Environment.getExternalStorageDirectory(), "Download")
            if (!extDir.exists()) extDir.mkdirs()
            val extFile = File(extDir, "glasses-client.log")
            externalFile = extFile
            externalWriter = BufferedWriter(FileWriter(extFile, false))
            externalWriter?.write("=== SESSION START ${sessionFmt.format(Date())} ===")
            externalWriter?.newLine()
            externalWriter?.flush()
            android.util.Log.i("LogCollector", "External log opened: ${extFile.absolutePath} (exists=${extFile.exists()})")
        } catch (e: Exception) {
            android.util.Log.e("LogCollector", "Failed to init external log: ${e.message}")
        }
    }

    private fun zipToArchive(source: File, entryName: String, dest: File) {
        try {
            ZipOutputStream(FileOutputStream(dest)).use { zos ->
                zos.putNextEntry(ZipEntry(entryName))
                FileInputStream(source).use { fis -> fis.copyTo(zos) }
                zos.closeEntry()
            }
        } catch (_: Exception) {}
    }

    private fun pruneOldSessions(dir: File) {
        val archives = dir.listFiles { f -> f.name.endsWith(".zip") } ?: return
        if (archives.size > MAX_SESSIONS) {
            archives.sortedByDescending { it.lastModified() }
                .drop(MAX_SESSIONS)
                .forEach { it.delete() }
        }
    }

    private fun writeToFile(line: String) {
        synchronized(writerLock) {
            try {
                writer?.let { w ->
                    w.write(line)
                    w.newLine()
                    w.flush()
                }
            } catch (_: Exception) {}
            writeExternalLocked(line)
            writeCount++
            if (writeCount % 500 == 0) truncateInternalIfNeeded()
        }
    }

    private fun writeExternalLocked(line: String) {
        try {
            externalWriter?.let { w ->
                w.write(line)
                w.newLine()
                w.flush()
            }
            truncateExternalIfNeeded()
        } catch (_: Exception) {}
    }

    private fun truncateExternalIfNeeded() {
        val file = externalFile ?: return
        if (file.length() <= EXTERNAL_MAX_SIZE) return
        try {
            // Keep last 1MB
            val keepBytes = 1024L * 1024
            val raf = RandomAccessFile(file, "r")
            raf.seek(file.length() - keepBytes)
            // Skip to next newline to avoid partial line
            raf.readLine()
            val remaining = ByteArray((file.length() - raf.filePointer).toInt())
            raf.readFully(remaining)
            raf.close()

            externalWriter?.close()
            file.writeBytes(remaining)
            externalWriter = BufferedWriter(FileWriter(file, true))
        } catch (_: Exception) {}
    }

    private fun truncateInternalIfNeeded() {
        val file = latestLogFile ?: return
        if (!file.exists() || file.length() <= MAX_LOG_SIZE) return
        try {
            val raf = RandomAccessFile(file, "r")
            raf.seek(file.length() - FIFO_KEEP_SIZE)
            raf.readLine()
            val remaining = ByteArray((file.length() - raf.filePointer).toInt())
            raf.readFully(remaining)
            raf.close()
            writer?.close()
            file.writeBytes(remaining)
            writer = BufferedWriter(FileWriter(file, true))
        } catch (_: Exception) {}
    }

    /**
     * Write directly to the external log file (for btLog/btErr).
     * Persists even if BT fails.
     */
    fun writeExternal(line: String) {
        synchronized(writerLock) {
            writeExternalLocked(line)
        }
    }

    fun shutdown() {
        synchronized(writerLock) {
            try { writer?.flush(); writer?.close() } catch (_: Exception) {}
            writer = null
            try { externalWriter?.flush(); externalWriter?.close() } catch (_: Exception) {}
            externalWriter = null
        }
    }

    fun clear() {
        entries.clear()
    }

    /**
     * Write a pre-formatted log line relayed from the UI process.
     * Called by the backend's LOG_RELAY broadcast receiver.
     */
    fun writeRelayed(line: String) {
        writeToFile(line)
    }

    private fun append(tag: String, level: String, message: String) {
        val ts = fmt.format(Date())
        val line = "$ts $level/$tag: $message"
        entries.addLast(line)
        while (entries.size > MAX_ENTRIES) {
            entries.pollFirst()
        }
        val ctx = relayContext
        if (ctx != null) {
            // Main process: relay to backend via broadcast
            try {
                ctx.sendBroadcast(Intent(ACTION_LOG_RELAY).apply {
                    setPackage(ctx.packageName)
                    putExtra(EXTRA_LOG_LINE, line)
                })
            } catch (_: Exception) {}
        } else {
            writeToFile(line)
        }
    }

    fun i(tag: String, message: String) {
        android.util.Log.i(tag, message)
        append(tag, "I", message)
    }

    fun e(tag: String, message: String) {
        android.util.Log.e(tag, message)
        append(tag, "E", message)
    }

    fun w(tag: String, message: String) {
        android.util.Log.w(tag, message)
        append(tag, "W", message)
    }

    fun d(tag: String, message: String) {
        android.util.Log.d(tag, message)
        append(tag, "D", message)
    }

    fun getText(): String = entries.joinToString("\n")

    fun getEntryCount(): Int = entries.size

    fun getWriterStatus(): String {
        return "internal=${writer != null}, external=${externalWriter != null}, " +
                "extFile=${externalFile?.absolutePath ?: "null"}, " +
                "extExists=${externalFile?.exists()}, extSize=${externalFile?.length() ?: -1}, " +
                "relayMode=${relayContext != null}"
    }
}
