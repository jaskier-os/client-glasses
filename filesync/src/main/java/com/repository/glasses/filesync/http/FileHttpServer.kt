package com.repository.glasses.filesync.http

import android.util.Log
import com.repository.glasses.tracing.GT
import com.repository.glasses.filesync.FileEntry
import com.repository.glasses.filesync.Manifest
import com.repository.glasses.filesync.ScanJob
import org.json.JSONArray
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors

/**
 * Minimal HTTP server serving /manifest and /file/<id>. Read-only.
 * Listens on 0.0.0.0:PORT (group owner IP is assigned by Android).
 *
 * Tracks currently-served fileIds in `activeServing` so FileSyncService can refuse
 * deletes for a file that's mid-stream.
 */
class FileHttpServer(
    private val port: Int,
    private val manifestProvider: () -> Manifest,
    private val onHit: () -> Unit,   // called per request; used to reset idle timer
) {

    companion object {
        private const val TAG = "FSync:Http"
        private const val DELETE_LOCK_PREFIX = "\u0000del:"
    }

    private val threadIdx = java.util.concurrent.atomic.AtomicInteger(0)
    // Bumped 4 -> 6 to match the phone's PULL_PARALLELISM and avoid
    // queueing on the server side during multi-file pulls.
    //
    // CRITICAL: this MUST be a `var` and recreated on every start(). The
    // previous version was a `val` initialised once; stop() called
    // pool.shutdownNow() (terminal), so on the next session start() only
    // the ServerSocket was recreated and pool was permanently dead.
    // Every accepted client connection then threw
    // RejectedExecutionException at submit() time, producing the
    // "pool rejected (shutting down)" log line and zero progress on
    // pulls -- the phone read-loop just sat at PULL_READ_TIMEOUT_MS=10s
    // per file. With 28 files that's 280 s of dead-pool timeouts the user
    // saw as "pulling forever".
    private fun newPool() = Executors.newFixedThreadPool(6) { r ->
        Thread(r, "FileHttp-${threadIdx.incrementAndGet()}")
    }
    private var pool: java.util.concurrent.ExecutorService = newPool()
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    private val activeServing = java.util.Collections.synchronizedSet(HashSet<String>())
    /** Lock that delete + respondFile share so they can't interleave on the same id. */
    private val serveLock = Any()

    fun isServing(fileId: String): Boolean = activeServing.contains(fileId)

    /**
     * Atomically check+reserve for delete. Caller holds a delete intent; returns true if the
     * id was NOT being served (and it's now locked against new /file/<id> starts until the caller
     * finishes). Callers MUST call [releaseDeleteLock] when done.
     */
    fun tryLockForDelete(fileId: String): Boolean {
        synchronized(serveLock) {
            if (activeServing.contains(fileId)) return false
            // Mark as "locked for delete" by a sentinel prefix; respondFile checks this.
            activeServing.add(DELETE_LOCK_PREFIX + fileId)
            return true
        }
    }

    fun releaseDeleteLock(fileId: String) {
        synchronized(serveLock) { activeServing.remove(DELETE_LOCK_PREFIX + fileId) }
    }

    private fun isLockedForDelete(fileId: String): Boolean =
        activeServing.contains(DELETE_LOCK_PREFIX + fileId)

    fun start() = GT.section("fs.http.start") {
        if (running) return@section
        running = true
        // Re-create the pool on each start: stop() shutdownNow's it, and a
        // shutdown ExecutorService is permanently rejecting -- never reuse.
        if (pool.isShutdown) pool = newPool()
        Log.i(TAG, "start: port=$port")
        val thread = Thread({ acceptLoop() }, "FileHttp-accept")
        thread.isDaemon = true
        thread.start()
    }

    fun stop() = GT.section("fs.http.stop") {
        Log.i(TAG, "stop: port=$port active=${activeServing.size}")
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        pool.shutdownNow()
    }

    private fun acceptLoop() {
        try {
            GT.section("fs.http.bind") {
                serverSocket = ServerSocket(port)
                Log.i(TAG, "listening on :$port")
            }
            while (running) {
                val client = try { serverSocket!!.accept() } catch (e: SocketException) {
                    if (!running) return
                    Log.w(TAG, "accept: ${e.message}"); continue
                }
                if (!running) { try { client.close() } catch (_: Exception) {}; return }
                try { pool.submit { handle(client) } } catch (e: java.util.concurrent.RejectedExecutionException) {
                    Log.w(TAG, "pool rejected (shutting down)")
                    try { client.close() } catch (_: Exception) {}
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "acceptLoop: ${e.message}")
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 15_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val line = reader.readLine() ?: return
            val parts = line.split(' ')
            if (parts.size < 2) { respond404(client.getOutputStream()); return }
            val method = parts[0]
            val path = parts[1]
            // Drain headers
            while (true) {
                val h = reader.readLine() ?: break
                if (h.isEmpty()) break
            }
            if (method != "GET") { respond405(client.getOutputStream()); return }
            onHit()
            when {
                path == "/manifest" -> respondManifest(client.getOutputStream())
                path.startsWith("/file/") -> respondFile(client.getOutputStream(), path.removePrefix("/file/"))
                else -> respond404(client.getOutputStream())
            }
        } catch (e: Exception) {
            Log.w(TAG, "handle: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun respondManifest(out: OutputStream) {
        val mf = manifestProvider()
        val arr: JSONArray = mf.toJsonArray()
        val body = arr.toString().toByteArray(Charsets.UTF_8)
        val etag = mf.stateHash()
        val head = StringBuilder()
            .append("HTTP/1.1 200 OK\r\n")
            .append("Content-Type: application/json; charset=utf-8\r\n")
            .append("Content-Length: ${body.size}\r\n")
            .append("ETag: \"$etag\"\r\n")
            .append("Connection: close\r\n")
            .append("\r\n")
            .toString()
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun respondFile(out: OutputStream, rawId: String) {
        // Decode URL-encoded ids and reject empty strings.
        val id = try { java.net.URLDecoder.decode(rawId, "UTF-8") } catch (_: Exception) { rawId }
        if (id.isEmpty() || id.contains('/') || id.contains('\\')) { respond404(out); return }
        val mf = manifestProvider()
        val entry: FileEntry? = mf.get(id)
        if (entry == null) { respond404(out); return }
        val file = File(ScanJob.rootDir, entry.relPath)
        // Canonicalise + prefix-check to stop a crafted manifest entry from escaping rootDir.
        val canonicalFile = try { file.canonicalFile } catch (_: Exception) { respond404(out); return }
        val canonicalRoot = try { ScanJob.rootDir.canonicalFile.absolutePath } catch (_: Exception) { respond404(out); return }
        if (!canonicalFile.absolutePath.startsWith(canonicalRoot + File.separator)) {
            Log.w(TAG, "refusing file outside root: ${canonicalFile.absolutePath}")
            respond404(out); return
        }
        if (!canonicalFile.exists()) { respond404(out); return }

        // Atomic reserve: if a delete just locked this id, refuse; otherwise mark as serving.
        synchronized(serveLock) {
            if (isLockedForDelete(id)) { respond404(out); return }
            activeServing.add(id)
        }
        try {
            val head = StringBuilder()
                .append("HTTP/1.1 200 OK\r\n")
                .append("Content-Type: application/octet-stream\r\n")
                .append("Content-Length: ${canonicalFile.length()}\r\n")
                .append("ETag: \"${entry.sha256}\"\r\n")
                .append("Connection: close\r\n")
                .append("\r\n")
                .toString()
            out.write(head.toByteArray(Charsets.US_ASCII))
            val startMs = System.currentTimeMillis()
            val fileLen = canonicalFile.length()
            Log.i(TAG, "respondFile: enter id=$id size=$fileLen")
            var totalBytes = 0L
            var chunkCount = 0
            FileInputStream(canonicalFile).use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    totalBytes += n
                    chunkCount++
                    if (chunkCount % 16 == 0) {
                        val elapsedMs = System.currentTimeMillis() - startMs
                        Log.i(TAG, "respondFile: progress id=$id chunks=$chunkCount bytes=$totalBytes elapsedMs=$elapsedMs")
                    }
                }
            }
            out.flush()
            val durMs = System.currentTimeMillis() - startMs
            Log.i(TAG, "respondFile: exit id=$id chunks=$chunkCount bytes=$totalBytes durMs=$durMs")
        } catch (e: IOException) {
            Log.w(TAG, "respondFile $id: ${e.message}")
        } finally {
            activeServing.remove(id)
        }
    }

    private fun respond404(out: OutputStream) {
        val body = "not found".toByteArray()
        val head = "HTTP/1.1 404 Not Found\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun respond405(out: OutputStream) {
        val body = "method not allowed".toByteArray()
        val head = "HTTP/1.1 405 Method Not Allowed\r\nAllow: GET\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }
}
