package com.repository.glasses.filesync.http

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import com.repository.glasses.tracing.GT
import com.repository.glasses.filesync.FileEntry
import com.repository.glasses.filesync.Manifest
import com.repository.glasses.filesync.ScanJob
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
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
    // Live gate for the POST /sideload/* routes. The listener app flips the backing
    // flag (GlassesConfig.sideloadingEnabled) over AIDL; we read it per-request so a
    // mid-session flip-off immediately starts rejecting with 403.
    private val sideloadEnabled: () -> Boolean,
    // Absolute path of the upload staging dir. Must be writable by the filesync app uid
    // -- /data/local/tmp is shell:shell 0771 so a priv_app can't mkdir there. The service
    // passes its own private files dir (e.g. /data/data/<pkg>/files/sideload); appsud
    // (root) can still read it to push staged files into /system during a deploy.
    private val stagingDirPath: String,
) {

    companion object {
        private const val TAG = "FSync:Http"
        private const val DELETE_LOCK_PREFIX = "\u0000del:"

        // Staging directory for sideload uploads. Wiped after every exec, on
        // teardown, and whenever sideloading is disabled -- uploaded tmp files
        // must never persist.
        // Abstract unix socket of the appsud root daemon (baked into firmware, launched
        // by init in diy-overlay.rc as a real root process). We send a shell command and
        // it runs it as root, returning stdout/stderr/rc. This daemon model is required
        // because app processes have an empty capability bounding set, so no setuid binary
        // can elevate them -- only a separate already-root process can run things as root.
        private const val APPSUD_SOCKET = "rokid_appsud"
        // World-readable scratch dir for sideload APK installs. PackageManager (system_server,
        // uid 1000) cannot read the filesync app-private staging dir, so an APK to be installed is
        // first copied here (root-writable, world-readable) then `pm install`ed. Root-owned, so
        // this uid cannot delete it -- wipeStaging() clears it via the appsud root daemon on every
        // session teardown so no install copy ever persists.
        const val INSTALL_SCRATCH_DIR = "/data/local/tmp/sideload-stage"
        // Hard ceiling on a single uploaded file so a malformed/huge body can't fill
        // the 19 GB partition. Deploy APKs are tens of MB; 512 MiB is generous.
        private const val MAX_UPLOAD_BYTES = 512L * 1024 * 1024
        // Per-stream cap on captured exec output (stdout and stderr each).
        private const val MAX_EXEC_OUTPUT_BYTES = 1024 * 1024
        // Per-stream rolling cap for an async ExecJob (stdout and stderr each). Generous so a
        // long, chatty command keeps a useful tail; the desktop drains incrementally anyway.
        private const val MAX_JOB_OUTPUT_BYTES = 16L * 1024 * 1024
        // Idle TTL for a finished/abandoned job before the reaper drops it.
        private const val JOB_IDLE_TTL_MS = 10L * 60 * 1000
        // Wall-clock cap for the legacy SYNCHRONOUS /sideload/exec (it pins a pool thread).
        // Long commands must use the async /sideload/exec/start + /poll endpoints.
        private const val SYNC_EXEC_CAP_MS = 120_000L
        // Hard ceiling on a POST body that is read into memory (exec/cleanup). The exec
        // body is just {"cmd":"..."}; reject anything larger so an attacker-supplied huge
        // Content-Length can't pin a pool thread trickle-reading megabytes.
        private const val MAX_REQUEST_BODY_BYTES = 256L * 1024
        // Bound on how long a single su -c command may run before we give up and
        // forcibly kill it, so a hung child can't permanently starve the 6-thread pool.
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

    /** Async exec jobs by id (start/poll model for long-running root commands). */
    private val execJobs = java.util.concurrent.ConcurrentHashMap<String, ExecJob>()
    private val execJobSeq = java.util.concurrent.atomic.AtomicLong(0)

    /** Drop finished/abandoned jobs that the desktop stopped polling, so they don't leak. */
    private fun reapStaleJobs() {
        val now = System.currentTimeMillis()
        val it = execJobs.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.value.finished && now - e.value.lastTouchMs > JOB_IDLE_TTL_MS) {
                it.remove()
            }
        }
    }

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
        // Clean slate: wipe leftover staged uploads from a prior deploy that aborted
        // mid-sequence. Done AFTER the accept loop is spawned so it does not block the
        // caller (onWifiReady runs on the main Looper; wipeStaging -> wipeRootInstallScratch
        // opens a synchronous appsud socket that can block up to soTimeout=5s, which would
        // freeze the main thread and delay the WIFI_READY broadcast to the phone).
        Thread({ try { wipeStaging() } catch (e: Exception) { Log.w(TAG, "start wipeStaging: ${e.message}") } }, "FileHttp-wipe-start").start()
    }

    /**
     * Stop the HTTP server socket. This is also called on a TRANSIENT WiFi-Direct close (firmware
     * idle teardown), so it deliberately does NOT kill running async exec jobs: an ExecJob holds an
     * appsud LocalSocket that is independent of the WiFi link, so the root command keeps running and
     * buffering, and the desktop can resume polling the SAME job id once the link re-opens. Killing
     * jobs here would SIGKILL an in-flight `cat`/`pm install`/grant mid-deploy. Jobs are killed only
     * on actual disable/cleanup (killAllJobs). On Linux, wipeStaging() unlinking a staged file an
     * in-flight `cat` already opened is safe -- the open fd keeps the inode alive until close.
     */
    fun stop() = GT.section("fs.http.stop") {
        Log.i(TAG, "stop: port=$port active=${activeServing.size} jobs=${execJobs.size}")
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        pool.shutdownNow()
        // Await + wipe on a background thread so the caller (onWifiClosed, which runs on
        // the main Looper) is not blocked. pool.awaitTermination can stall up to 5 s when
        // respondFile threads are stuck in OutputStream.write to dead WiFi sockets (regular
        // Java sockets are NOT interruptible by Thread.interrupt; the write only unblocks
        // when the kernel TCP stack detects the broken peer). Blocking the main Looper that
        // long delays the onWifiDirectClosed callback to the listener app and the deferred
        // onManifestChanged broadcast, which stalls the phone's sync FSM.
        Thread({
            try { pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) {}
            try { wipeStaging() } catch (e: Exception) { Log.w(TAG, "stop wipeStaging: ${e.message}") }
        }, "FileHttp-wipe-stop").start()
    }

    /** Kill every running async exec job + drop them. Called only on a true teardown (sideloading
     *  disabled, or explicit cleanup) -- NOT on a transient WiFi close, so a long command survives
     *  a p2p blip. */
    fun killAllJobs() {
        execJobs.values.forEach { try { it.kill() } catch (_: Exception) {} }
        execJobs.clear()
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
            // Read the request line + headers at the byte level so the unread body
            // bytes stay available on the same stream for POST routes. A BufferedReader
            // would over-read into its char buffer and lose the body.
            val ins = client.getInputStream()
            val out = client.getOutputStream()
            val requestLine = readLine(ins) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) { respond404(out); return }
            val method = parts[0]
            val path = parts[1]
            // Parse headers (case-insensitive keys) until the blank line.
            val headers = HashMap<String, String>()
            while (true) {
                val h = readLine(ins) ?: break
                if (h.isEmpty()) break
                val idx = h.indexOf(':')
                if (idx > 0) {
                    val k = h.substring(0, idx).trim().lowercase()
                    val v = h.substring(idx + 1).trim()
                    headers[k] = v
                }
            }
            onHit()
            when (method) {
                "GET" -> when {
                    path == "/manifest" -> respondManifest(out)
                    path.startsWith("/file/") -> respondFile(out, path.removePrefix("/file/"))
                    else -> respond404(out)
                }
                "POST" -> when (path) {
                    "/sideload/upload" -> handleSideloadUpload(ins, out, headers)
                    "/sideload/exec" -> handleSideloadExec(ins, out, headers)
                    "/sideload/exec/start" -> handleSideloadExecStart(ins, out, headers)
                    "/sideload/exec/poll" -> handleSideloadExecPoll(ins, out, headers)
                    "/sideload/cleanup" -> handleSideloadCleanup(ins, out, headers)
                    else -> respond404(out)
                }
                else -> respond405(out)
            }
        } catch (e: Exception) {
            Log.w(TAG, "handle: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    /**
     * Read a single CRLF-terminated line from the raw stream as ASCII, returning the
     * content without the terminator. Returns null at EOF before any byte. A bare LF is
     * also accepted as a terminator (defensive). Caps the line length so a body sent
     * without CRLFs can't blow up memory before we even classify the request.
     */
    private fun readLine(ins: InputStream): String? {
        val buf = ByteArrayOutputStream(128)
        var sawAny = false
        while (true) {
            val b = ins.read()
            if (b < 0) return if (sawAny) buf.toString("US-ASCII") else null
            sawAny = true
            if (b == '\n'.code) {
                var s = buf.toString("US-ASCII")
                if (s.endsWith('\r')) s = s.substring(0, s.length - 1)
                return s
            }
            if (buf.size() > 16 * 1024) {
                // Header/request line absurdly long -- treat as malformed.
                throw IOException("request line too long")
            }
            buf.write(b)
        }
    }

    // --- Sideload routes (POST). Gated on sideloadEnabled() -- 403 when off. ---

    private fun handleSideloadUpload(ins: InputStream, out: OutputStream, headers: Map<String, String>) = GT.section("fs.http.sideload.upload") {
        if (!sideloadEnabled()) { respond403(out); return@section }
        val rawName = headers["x-upload-name"]
        val name = sanitizeUploadName(rawName)
        if (name == null) {
            Log.w(TAG, "sideload upload: bad X-Upload-Name=${rawName}")
            respondJson(out, 400, "{\"ok\":false,\"error\":\"bad_name\"}")
            return@section
        }
        val contentLength = headers["content-length"]?.toLongOrNull()
        if (contentLength == null || contentLength < 0 || contentLength > MAX_UPLOAD_BYTES) {
            Log.w(TAG, "sideload upload: bad Content-Length=${headers["content-length"]}")
            respondJson(out, 400, "{\"ok\":false,\"error\":\"bad_length\"}")
            return@section
        }
        val dir = ensureStagingDir()
        if (dir == null) { respondJson(out, 500, "{\"ok\":false,\"error\":\"mkdir_failed\"}"); return@section }
        val dest = File(dir, name)
        // Defense in depth on top of sanitizeUploadName: the resolved path must stay inside
        // the staging dir. Mirrors the canonical prefix-check respondFile uses.
        val canonRoot = try { dir.canonicalFile.absolutePath } catch (_: Exception) { dir.absolutePath }
        val canonDest = try { dest.canonicalFile.absolutePath } catch (_: Exception) { "" }
        if (!canonDest.startsWith(canonRoot + File.separator)) {
            Log.w(TAG, "sideload upload: dest escaped staging dir: $canonDest")
            respondJson(out, 400, "{\"ok\":false,\"error\":\"bad_name\"}")
            return@section
        }
        val md = MessageDigest.getInstance("SHA-256")
        var written = 0L
        try {
            FileOutputStream(dest).use { fos ->
                val bos = BufferedOutputStream(fos)
                val buf = ByteArray(64 * 1024)
                while (written < contentLength) {
                    val want = minOf(buf.size.toLong(), contentLength - written).toInt()
                    val n = ins.read(buf, 0, want)
                    if (n < 0) break
                    bos.write(buf, 0, n)
                    md.update(buf, 0, n)
                    written += n
                }
                bos.flush()
            }
        } catch (e: IOException) {
            Log.w(TAG, "sideload upload: write failed ${e.message}")
            try { dest.delete() } catch (_: Exception) {}
            respondJson(out, 500, "{\"ok\":false,\"error\":\"write_failed\"}")
            return@section
        }
        if (written != contentLength) {
            Log.w(TAG, "sideload upload: short body got=$written want=$contentLength")
            try { dest.delete() } catch (_: Exception) {}
            respondJson(out, 400, "{\"ok\":false,\"error\":\"short_body\"}")
            return@section
        }
        try { Runtime.getRuntime().exec(arrayOf("chmod", "600", dest.absolutePath)).waitFor() } catch (_: Exception) {}
        val sha = md.digest().joinToString("") { String.format("%02x", it) }
        Log.i(TAG, "sideload upload: ${dest.absolutePath} size=$written sha=${sha.take(12)}")
        val body = JSONObject()
            .put("ok", true)
            .put("path", dest.absolutePath)
            .put("size", written)
            .put("sha256", sha)
            .toString()
        respondJson(out, 200, body)
    }

    private fun handleSideloadExec(ins: InputStream, out: OutputStream, headers: Map<String, String>) = GT.section("fs.http.sideload.exec") {
        if (!sideloadEnabled()) { respond403(out); return@section }
        val bodyStr = readBody(ins, headers)
        if (bodyStr == null) { respondJson(out, 400, "{\"rc\":-1,\"stdout\":\"\",\"stderr\":\"bad_body\",\"truncated\":false}"); return@section }
        val cmd = try { JSONObject(bodyStr).optString("cmd", "") } catch (_: Exception) { "" }
        if (cmd.isEmpty()) { respondJson(out, 400, "{\"rc\":-1,\"stdout\":\"\",\"stderr\":\"missing_cmd\",\"truncated\":false}"); return@section }
        Log.i(TAG, "sideload exec: ${cmd.take(200)}")
        try {
            // Root exec via the appsud daemon (see runViaAppsud). Direct setuid is impossible
            // from an app: zygote gives app processes an empty capability bounding set, so even
            // a setuid-root binary cannot regain CAP_SETUID/SETGID (setresgid -> EPERM). appsud
            // runs as a real root init service and executes commands on our behalf over an
            // abstract unix socket -- the same daemon model Magisk uses.
            val result = runViaAppsud(cmd)
            val body = JSONObject()
                .put("rc", result.rc)
                .put("stdout", result.stdout)
                .put("stderr", result.stderr)
                .put("truncated", result.truncated)
                .toString()
            respondJson(out, 200, body)
        } catch (e: Exception) {
            Log.w(TAG, "sideload exec failed: ${e.message}")
            val body = JSONObject()
                .put("rc", -1)
                .put("stdout", "")
                .put("stderr", "exec_failed: ${e.message}")
                .put("truncated", false)
                .toString()
            respondJson(out, 500, body)
        } finally {
            // Tmp hygiene: uploaded staged files must not persist past the op that consumed them.
            wipeStaging()
        }
    }

    /**
     * Start an async root command. Body {"cmd":"..."} -> {"job":"<id>"}. The command runs on a
     * background thread (no time limit); the desktop drains it with /sideload/exec/poll. Unlike
     * the sync /sideload/exec, this does NOT wipe staging (a long deploy command may still need
     * uploaded files); staging is wiped on cleanup / session close / poll-after-finish.
     */
    private fun handleSideloadExecStart(ins: InputStream, out: OutputStream, headers: Map<String, String>) = GT.section("fs.http.sideload.exec.start") {
        if (!sideloadEnabled()) { respond403(out); return@section }
        reapStaleJobs()
        val bodyStr = readBody(ins, headers)
        if (bodyStr == null) { respondJson(out, 400, "{\"ok\":false,\"error\":\"bad_body\"}"); return@section }
        val cmd = try { JSONObject(bodyStr).optString("cmd", "") } catch (_: Exception) { "" }
        if (cmd.isEmpty()) { respondJson(out, 400, "{\"ok\":false,\"error\":\"missing_cmd\"}"); return@section }
        val id = "j${execJobSeq.incrementAndGet()}-${System.currentTimeMillis() % 100000}"
        Log.i(TAG, "sideload exec/start job=$id: ${cmd.take(200)}")
        val job = ExecJob(id, cmd)
        execJobs[id] = job
        job.start()
        respondJson(out, 200, JSONObject().put("ok", true).put("job", id).toString())
    }

    /**
     * Poll an async job. Body {"job":"<id>","stdoutFrom":N,"stderrFrom":M} -> incremental
     * stdout/stderr from those byte offsets + running/rc/totals. When the job has finished AND
     * the desktop has drained all output (offsets == totals), staging is wiped and the job dropped.
     */
    private fun handleSideloadExecPoll(ins: InputStream, out: OutputStream, headers: Map<String, String>) = GT.section("fs.http.sideload.exec.poll") {
        if (!sideloadEnabled()) { respond403(out); return@section }
        val bodyStr = readBody(ins, headers)
        if (bodyStr == null) { respondJson(out, 400, "{\"ok\":false,\"error\":\"bad_body\"}"); return@section }
        val obj = try { JSONObject(bodyStr) } catch (_: Exception) { JSONObject() }
        val id = obj.optString("job", "")
        if (id.isEmpty()) { respondJson(out, 400, "{\"ok\":false,\"error\":\"missing_job\"}"); return@section }
        val job = execJobs[id]
        if (job == null) { respondJson(out, 404, "{\"ok\":false,\"error\":\"unknown_job\"}"); return@section }
        val stdoutFrom = obj.optInt("stdoutFrom", 0)
        val stderrFrom = obj.optInt("stderrFrom", 0)
        val p = job.poll(stdoutFrom, stderrFrom)
        val body = JSONObject()
            .put("ok", true)
            .put("running", p.running)
            .put("rc", p.rc ?: JSONObject.NULL)
            .put("stdoutB64", p.stdoutB64)
            .put("stderrB64", p.stderrB64)
            .put("stdoutTotal", p.stdoutTotal)
            .put("stderrTotal", p.stderrTotal)
            .put("truncated", p.truncated)
            .put("error", p.error ?: JSONObject.NULL)
            .toString()
        respondJson(out, 200, body)
        // Once finished and the desktop has consumed everything, retire the job + wipe staging.
        if (!p.running && stdoutFrom >= p.stdoutTotal && stderrFrom >= p.stderrTotal) {
            execJobs.remove(id)
            wipeStaging()
        }
    }

    private fun handleSideloadCleanup(ins: InputStream, out: OutputStream, headers: Map<String, String>) = GT.section("fs.http.sideload.cleanup") {
        if (!sideloadEnabled()) { respond403(out); return@section }
        // Drain any body so the socket close is clean.
        readBody(ins, headers)
        wipeStaging()
        respondJson(out, 200, "{\"ok\":true}")
    }

    /**
     * Validate X-Upload-Name: must be a bare basename. Rejects empty, path separators,
     * "." / "..", NUL and control chars. Returns the safe name or null.
     */
    private fun sanitizeUploadName(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        if (raw.length > 255) return null
        if (raw == "." || raw == "..") return null
        for (c in raw) {
            if (c == '/' || c == '\\' || c == '\u0000') return null
            if (c.code < 0x20) return null
        }
        return raw
    }

    /** Create the staging dir 0700 if missing. Returns the File or null on failure. */
    private fun ensureStagingDir(): File? {
        val dir = File(stagingDirPath)
        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                Log.w(TAG, "ensureStagingDir: mkdirs failed for ${dir.absolutePath}")
                if (!dir.isDirectory) return null
            }
        }
        try { Runtime.getRuntime().exec(arrayOf("chmod", "700", dir.absolutePath)).waitFor() } catch (_: Exception) {}
        return dir
    }

    /** Delete every file under the staging dir (and the dir itself). Idempotent. */
    fun wipeStaging() {
        // 1) App-private upload staging (where /sideload/upload writes; deletable by this uid).
        val dir = File(stagingDirPath)
        if (dir.exists()) {
            try {
                dir.listFiles()?.forEach { f -> deleteRecursively(f) }
                dir.delete()
                Log.i(TAG, "wipeStaging: cleared ${dir.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "wipeStaging: ${e.message}")
            }
        }
        // 2) Root-owned install scratch dir. Sideload APK installs must copy the staged APK to a
        // world-readable path (PackageManager/system_server cannot read our app-private dir), and
        // those copies are root-owned so this uid cannot delete them. The desktop removes them
        // inline after each install, but if a job is killed mid-install (session teardown) the copy
        // would leak permanently in /data/local/tmp. Force-clear it via the root daemon so NO
        // uploaded/derived file ever persists past a sideload session. Best-effort + non-blocking.
        wipeRootInstallScratch()
    }

    /**
     * Remove the world-readable install scratch dir as root. Done with a single, short-lived,
     * SYNCHRONOUS appsud connection (no ExecJob, no detached thread) so it cannot leak past a
     * session teardown -- it is called from wipeStaging(), itself called from stop(). A
     * single-flight guard prevents the many wipe call sites (every exec finally, every poll-finish,
     * cleanup, open, stop) from piling up concurrent root connections.
     */
    private val installScratchWipeInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun wipeRootInstallScratch() {
        if (!installScratchWipeInFlight.compareAndSet(false, true)) return
        try {
            val sock = LocalSocket()
            try {
                sock.connect(LocalSocketAddress(APPSUD_SOCKET, LocalSocketAddress.Namespace.ABSTRACT))
                sock.soTimeout = 5000
                sock.outputStream.write("rm -rf $INSTALL_SCRATCH_DIR".toByteArray(Charsets.UTF_8))
                sock.outputStream.flush()
                sock.shutdownOutput()
                // Drain frames to completion (small/empty output) so appsud finishes the rm before
                // we close; ignore the content. Bounded by soTimeout.
                val ins = sock.inputStream
                val buf = ByteArray(4096)
                while (ins.read(buf) >= 0) { /* drain until EOF */ }
            } finally {
                try { sock.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "wipeRootInstallScratch: ${e.message}")
        } finally {
            installScratchWipeInFlight.set(false)
        }
    }

    private fun deleteRecursively(f: File) {
        if (f.isDirectory) f.listFiles()?.forEach { deleteRecursively(it) }
        try { f.delete() } catch (_: Exception) {}
    }

    private data class ExecResult(val rc: Int, val stdout: String, val stderr: String, val truncated: Boolean)

    /**
     * A long-running root command executed via the appsud daemon. The command runs on a
     * background thread that holds the appsud socket open and consumes streamed frames into
     * rolling stdout/stderr byte buffers. The HTTP layer never blocks on the command: the
     * desktop polls /sideload/exec/poll for incremental output and the final exit code.
     *
     * Frame protocol from appsud: [1 byte type][4 byte BE len][payload]
     *   type 1 = stdout chunk, 2 = stderr chunk, 3 = exit (payload = BE int32 rc), 4 = error.
     *
     * Buffers are append-only (so byte offsets the desktop already read stay stable) and
     * capped: once a stream passes MAX_JOB_OUTPUT_BYTES we stop appending and set truncated.
     */
    inner class ExecJob(val id: String, private val cmd: String) {
        private val stdoutBuf = ByteArrayOutputStream()
        private val stderrBuf = ByteArrayOutputStream()
        private val lock = Any()
        @Volatile var finished = false; private set
        @Volatile var rc: Int = -1; private set
        @Volatile var error: String? = null; private set
        @Volatile var truncated = false; private set
        @Volatile var lastTouchMs = System.currentTimeMillis(); private set
        // @Volatile so kill() (pool/stop thread) always observes the socket run() assigned and can
        // unblock its read(); a plain field could read null and leak the blocked reader + socket.
        @Volatile private var sock: LocalSocket? = null
        private val thread = Thread({ run() }, "appsud-job-$id").apply { isDaemon = true }

        fun start() { thread.start() }

        private fun run() {
            val s = LocalSocket()
            sock = s
            try {
                s.connect(LocalSocketAddress(APPSUD_SOCKET, LocalSocketAddress.Namespace.ABSTRACT))
                val os = s.outputStream
                os.write(cmd.toByteArray(Charsets.UTF_8))
                os.flush()
                s.shutdownOutput()
                val ins = s.inputStream
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val type = ins.read()
                    if (type < 0) { if (!finished) { rc = -1; error = error ?: "appsud closed early" }; break }
                    val len = readU32(ins).toLong() and 0xFFFFFFFFL
                    when (type) {
                        1, 2 -> {
                            var remaining = len
                            while (remaining > 0) {
                                val want = minOf(chunk.size.toLong(), remaining).toInt()
                                val n = ins.read(chunk, 0, want)
                                if (n < 0) throw IOException("appsud: short read on chunk")
                                remaining -= n
                                appendCapped(if (type == 1) stdoutBuf else stderrBuf, chunk, n)
                            }
                            lastTouchMs = System.currentTimeMillis()
                        }
                        3 -> {
                            val rcBytes = ByteArray(4)
                            var got = 0; while (got < 4) { val n = ins.read(rcBytes, got, 4 - got); if (n < 0) break; got += n }
                            rc = ((rcBytes[0].toInt() and 0xFF) shl 24) or ((rcBytes[1].toInt() and 0xFF) shl 16) or
                                 ((rcBytes[2].toInt() and 0xFF) shl 8) or (rcBytes[3].toInt() and 0xFF)
                            finished = true
                            break
                        }
                        4 -> {
                            val eb = ByteArray(len.toInt().coerceAtMost(4096))
                            var got = 0; while (got < eb.size) { val n = ins.read(eb, got, eb.size - got); if (n < 0) break; got += n }
                            error = String(eb, 0, got, Charsets.UTF_8)
                        }
                        else -> { /* skip unknown frame */ skipFully(ins, len) }
                    }
                }
            } catch (e: Exception) {
                if (!finished) { rc = -1; error = error ?: "appsud job error: ${e.message}" }
            } finally {
                finished = true
                lastTouchMs = System.currentTimeMillis()
                try { s.close() } catch (_: Exception) {}
            }
        }

        private fun appendCapped(buf: ByteArrayOutputStream, data: ByteArray, n: Int) {
            synchronized(lock) {
                val already = buf.size().toLong()
                if (already >= MAX_JOB_OUTPUT_BYTES) { truncated = true; return }
                val take = minOf(n.toLong(), MAX_JOB_OUTPUT_BYTES - already).toInt()
                buf.write(data, 0, take)
                if (take < n) truncated = true
            }
        }

        /**
         * Snapshot of output bytes from the given byte offsets, returned BASE64-encoded so the
         * transport is binary-clean: the desktop concatenates raw bytes before decoding, so a
         * multibyte UTF-8 char (or arbitrary binary output) split across two polls is never
         * corrupted. Only the requested tail [from, size) is copied (no full-buffer re-copy),
         * so frequent polling of a huge-output command stays O(new bytes), not O(total).
         */
        fun poll(stdoutFrom: Int, stderrFrom: Int): JobPoll {
            lastTouchMs = System.currentTimeMillis()
            synchronized(lock) {
                val outTotal = stdoutBuf.size()
                val errTotal = stderrBuf.size()
                val outSlice = sliceTail(stdoutBuf, stdoutFrom, outTotal)
                val errSlice = sliceTail(stderrBuf, stderrFrom, errTotal)
                return JobPoll(
                    running = !finished,
                    rc = if (finished) rc else null,
                    stdoutB64 = android.util.Base64.encodeToString(outSlice, android.util.Base64.NO_WRAP),
                    stderrB64 = android.util.Base64.encodeToString(errSlice, android.util.Base64.NO_WRAP),
                    stdoutTotal = outTotal,
                    stderrTotal = errTotal,
                    truncated = truncated,
                    error = error,
                )
            }
        }

        /** Copy only [from, total) of a ByteArrayOutputStream without materializing the whole
         *  buffer. ByteArrayOutputStream has no range accessor, so we snapshot once and slice;
         *  the snapshot is unavoidable but we only do it when there are new bytes to return. */
        private fun sliceTail(buf: ByteArrayOutputStream, from: Int, total: Int): ByteArray {
            if (from >= total || from < 0) return ByteArray(0)
            val all = buf.toByteArray()
            return all.copyOfRange(from, total)
        }

        fun kill() {
            try { sock?.close() } catch (_: Exception) {}
        }
    }

    data class JobPoll(
        val running: Boolean, val rc: Int?, val stdoutB64: String, val stderrB64: String,
        val stdoutTotal: Int, val stderrTotal: Int, val truncated: Boolean, val error: String?,
    )

    private fun skipFully(ins: InputStream, len: Long) {
        var remaining = len
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val n = ins.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            remaining -= n
        }
    }

    /**
     * Synchronous convenience wrapper: run a command to completion and collect all output.
     * Used by the legacy /sideload/exec route for short commands. Built on the streaming
     * ExecJob so there is a single appsud code path and no 120s cap (it waits as long as the
     * command runs). Output is bounded by ExecJob's per-stream cap.
     */
    private fun runViaAppsud(cmd: String): ExecResult {
        val job = ExecJob("sync-${System.nanoTime()}", cmd)
        job.start()
        // Block this pool thread until the command finishes, but CAP the wait: the sync route
        // holds one of only 6 pool threads, so an unbounded sync command could starve the
        // server. Long commands must use the async start/poll endpoints instead.
        val deadline = System.currentTimeMillis() + SYNC_EXEC_CAP_MS
        while (!job.finished) {
            if (System.currentTimeMillis() > deadline) {
                job.kill()
                val pt = job.poll(0, 0)
                return ExecResult(
                    pt.rc ?: -1,
                    String(android.util.Base64.decode(pt.stdoutB64, android.util.Base64.NO_WRAP), Charsets.UTF_8),
                    "sync exec exceeded ${SYNC_EXEC_CAP_MS}ms cap; use /sideload/exec/start for long commands\n" +
                        String(android.util.Base64.decode(pt.stderrB64, android.util.Base64.NO_WRAP), Charsets.UTF_8),
                    true,
                )
            }
            Thread.sleep(20)
        }
        val p = job.poll(0, 0)
        return ExecResult(
            p.rc ?: -1,
            String(android.util.Base64.decode(p.stdoutB64, android.util.Base64.NO_WRAP), Charsets.UTF_8),
            String(android.util.Base64.decode(p.stderrB64, android.util.Base64.NO_WRAP), Charsets.UTF_8),
            p.truncated,
        )
    }

    private fun readU32(ins: InputStream): Int {
        val b = ByteArray(4)
        var read = 0
        while (read < 4) {
            val n = ins.read(b, read, 4 - read)
            if (n < 0) throw IOException("appsud: short read on u32 (got $read)")
            read += n
        }
        return ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
               ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
    }

    /**
     * Read the request body into a UTF-8 string, bounded by Content-Length (capped at
     * MAX_EXEC_OUTPUT_BYTES so an oversized exec body can't exhaust memory). Returns null
     * if Content-Length is absent or malformed.
     */
    private fun readBody(ins: InputStream, headers: Map<String, String>): String? {
        val len = headers["content-length"]?.toLongOrNull() ?: return null
        // Reject absent/negative AND oversized bodies outright. Without the upper bound a
        // malicious Content-Length would force the read loop to run far past the captured
        // cap, pinning a pool thread (the read loop, not the capture, is the cost).
        if (len < 0 || len > MAX_REQUEST_BODY_BYTES) return null
        val buf = ByteArrayOutputStream(minOf(len, 8192L).toInt())
        var read = 0L
        val tmp = ByteArray(8192)
        while (read < len) {
            val want = minOf(tmp.size.toLong(), len - read).toInt()
            val n = ins.read(tmp, 0, want)
            if (n < 0) break
            buf.write(tmp, 0, n)
            read += n
        }
        return buf.toString("UTF-8")
    }

    /**
     * Bounded get on a drain future. Even after destroyForcibly, a daemonized grandchild can
     * keep the inherited stdout/stderr fds open so the drain thread never hits EOF; cap the
     * wait so the pool thread is always released. The leaked daemon drain thread will exit
     * whenever the fds finally close.
     */
    private fun futureGet(f: java.util.concurrent.Future<Pair<String, Boolean>>): Pair<String, Boolean> = try {
        f.get(5, java.util.concurrent.TimeUnit.SECONDS)
    } catch (_: Exception) {
        Pair("", true)
    }

    /**
     * Drain a process stream on a daemon thread, capping captured bytes. Returns a future
     * yielding (capturedString, wasTruncated). Always reads the stream to EOF even after the
     * cap so the child never blocks on a full pipe.
     */
    private fun drainAsync(stream: InputStream): java.util.concurrent.Future<Pair<String, Boolean>> {
        val task = java.util.concurrent.FutureTask {
            val buf = ByteArrayOutputStream()
            var truncated = false
            val tmp = ByteArray(16 * 1024)
            stream.use { s ->
                while (true) {
                    val n = s.read(tmp)
                    if (n < 0) break
                    if (buf.size() < MAX_EXEC_OUTPUT_BYTES) {
                        val take = minOf(n, MAX_EXEC_OUTPUT_BYTES - buf.size())
                        buf.write(tmp, 0, take)
                        if (take < n) truncated = true
                    } else {
                        truncated = true
                    }
                }
            }
            Pair(buf.toString("UTF-8"), truncated)
        }
        Thread(task, "sideload-drain-${threadIdx.incrementAndGet()}").apply { isDaemon = true }.start()
        return task
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
        val head = "HTTP/1.1 405 Method Not Allowed\r\nAllow: GET, POST\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun respond403(out: OutputStream) {
        val body = "{\"ok\":false,\"error\":\"sideloading_disabled\"}".toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 403 Forbidden\r\nContent-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun respondJson(out: OutputStream, status: Int, json: String) {
        val body = json.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 500 -> "Internal Server Error"
            else -> "Status"
        }
        val head = "HTTP/1.1 $status $reason\r\nContent-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }
}
