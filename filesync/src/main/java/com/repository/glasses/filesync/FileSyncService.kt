package com.repository.glasses.filesync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.IBinder
import android.os.RemoteCallbackList
import android.os.SystemClock
import android.util.Log
import com.repository.glasses.filesync.http.FileHttpServer
import com.repository.glasses.filesync.wifi.WifiDirectHost
import com.repository.glasses.tracing.GT
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class FileSyncService : Service() {

    companion object {
        private const val TAG = "FSync:Svc"
        private const val TAG_IDLE = "FSync:Idle"
        private const val TAG_ARCHIVE = "FSync:Archive"
        private const val TAG_IPC = "FSync:IPC"
        private const val CHANNEL_ID = "filesync"
        private const val NOTIFICATION_ID = 201
        private const val IDLE_TIMEOUT_MS = 120_000L
        private const val OPEN_TIMEOUT_MS = 20_000L

        // Idle-check cadence: tight while busy (any non-IDLE state), back off when nothing is
        // happening so we stop waking the CPU every 10 s for no reason.
        private const val IDLE_TICK_BUSY_MS = 10_000L
        private const val IDLE_TICK_QUIET_MS = 60_000L

        // --- Periodic audio-archive sync trigger ---
        // Scan cadence for the shared DCIM/Repository/audio-archive/ directory.
        // Each tick counts completed Opus files and -- if any -- emits a broadcast the
        // listener ListenerService consumes to send REQUEST_SYNC_NOW to the phone over BT.
        private const val ARCHIVE_SCAN_PERIOD_MS = 60L * 60L * 1000L
        // Initial delay after onCreate before the first scan (lets the listener app come up first).
        private const val ARCHIVE_SCAN_INITIAL_DELAY_MS = 60L * 1000L
        // LocalOpusWriter rotates on 60 min or 7 MB. Anything whose mtime is older than this
        // is definitely not the currently-appending file and is safe to offer for sync.
        private const val ARCHIVE_COMPLETE_FILE_AGE_MS = 2L * 60L * 1000L

        // Listener package that receives the sync nudge broadcast.
        private const val LISTENER_PACKAGE = "com.repository.glasses.listener"
        // Subdir under DCIM/Repository where LocalOpusWriter rotates .opus files.
        private const val ARCHIVE_SUBDIR = "audio-archive"
        // Signature-level permission that gates ACTION_REQUEST_AUDIO_ARCHIVE_SYNC. Declared
        // in the listener app manifest. Only same-signature apps (this one and the listener)
        // receive the auto-grant; any third-party caller is blocked at the OS layer.
        private const val AUDIO_ARCHIVE_SYNC_PERMISSION =
            "com.repository.glasses.listener.permission.AUDIO_ARCHIVE_SYNC"

        /**
         * Broadcast emitted when the listener's audio-archive directory has one or more
         * completed (rotated) .opus files waiting to be synced to the phone. The glasses
         * ListenerService registers a receiver for this action and forwards it to the
         * phone over BT so the phone-side GlassesSyncClient can kick off a pull.
         *
         * Extras:
         *   EXTRA_PENDING_FILE_COUNT (Int)  -- number of completed .opus files visible
         *   EXTRA_ARCHIVE_DIR        (String) -- absolute path to the audio-archive dir
         */
        const val ACTION_REQUEST_AUDIO_ARCHIVE_SYNC =
            "com.repository.glasses.listener.ACTION_REQUEST_AUDIO_ARCHIVE_SYNC"
        const val EXTRA_PENDING_FILE_COUNT = "pending_file_count"
        const val EXTRA_ARCHIVE_DIR = "archive_dir"
    }

    enum class State { IDLE, OPENING_WIFI, SERVING, CLOSING }

    private lateinit var store: ManifestStore
    private val manifest = Manifest()
    private val manifestLock = Any()

    private lateinit var wifi: WifiDirectHost
    private lateinit var http: FileHttpServer

    private val callbacks = RemoteCallbackList<IFileSyncCallback>()
    private val callbackLock = Any()

    @Volatile private var state: State = State.IDLE
    @Volatile private var lastHitMs: Long = 0
    /**
     * Set when the manifest mutates while SERVING/OPENING_WIFI. We delay emitting
     * onManifestChanged until after CLOSING->IDLE so the phone doesn't kick off a
     * new HELLO mid-transfer (plan's "dirty flag during SERVING" rule).
     */
    @Volatile private var dirtyDuringServe: Boolean = false
    @Volatile private var currentWifiDetailsJson: String? = null
    // Sideload gate. Set by the listener app over AIDL from GlassesConfig.sideloadingEnabled.
    // FileHttpServer reads it per-request via a live getter so a mid-session flip-off
    // immediately starts rejecting POST /sideload/* with 403.
    @Volatile private var sideloadEnabled: Boolean = false
    private lateinit var idleChecker: ScheduledExecutorService
    private lateinit var archiveScheduler: ScheduledExecutorService
    @Volatile private var archiveTask: java.util.concurrent.ScheduledFuture<*>? = null

    private val idleTickRunnable: Runnable = object : Runnable {
        override fun run() {
            GT.section("fs.idle.tick") {
                try { checkIdle() } catch (e: Exception) { Log.w(TAG, "checkIdle: ${e.message}") }
                val nextDelay = if (state == State.IDLE) IDLE_TICK_QUIET_MS else IDLE_TICK_BUSY_MS
                try {
                    if (!idleChecker.isShutdown) {
                        idleChecker.schedule(this, nextDelay, TimeUnit.MILLISECONDS)
                    }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    // scheduler shut down between the check and the schedule call -- benign on teardown.
                }
            }
        }
    }

    private fun startArchiveScheduler() {
        if (archiveTask?.isCancelled == false) return
        archiveTask = archiveScheduler.scheduleAtFixedRate(
            {
                GT.section("fs.archive.tick") {
                    try { maybeRequestAudioArchiveSync() } catch (e: Exception) { Log.w(TAG, "maybeRequestAudioArchiveSync: ${e.message}") }
                }
            },
            ARCHIVE_SCAN_INITIAL_DELAY_MS,
            ARCHIVE_SCAN_PERIOD_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun stopArchiveScheduler() {
        archiveTask?.cancel(false)
        archiveTask = null
    }

    private val binder = object : IFileSync.Stub() {
        override fun registerCallback(cb: IFileSyncCallback) { callbacks.register(cb) }
        override fun unregisterCallback(cb: IFileSyncCallback) { callbacks.unregister(cb) }

        override fun getStateHash(): String = synchronized(manifestLock) { manifest.stateHash() }

        override fun getManifestJson(): String = synchronized(manifestLock) { manifest.toJsonArray().toString() }

        override fun openWifiDirectForSync(): String {
            if (state == State.SERVING) {
                // Another sync session is starting and we already have a live group. Re-emit
                // WIFI_READY so the new phone session gets the credentials -- otherwise it
                // stalls in OPENING_WIFI forever. The IFileSync.openWifiDirectForSync return
                // value isn't observed by callers (they wait for the callback), so the broadcast
                // is the path that actually carries creds to the peer.
                val cached = currentWifiDetailsJson
                if (cached != null) {
                    Log.i(TAG, "openWifiDirectForSync: already SERVING -- re-emitting WIFI_READY")
                    broadcast { it.onWifiDirectReady(cached) }
                } else {
                    Log.w(TAG, "openWifiDirectForSync: SERVING but no cached details -- re-opening")
                    transitionTo(State.CLOSING)
                    wifi.close()
                    // onWifiClosed will transition to IDLE; the next OPEN_WIFI from phone will fire fresh.
                }
                return cached ?: "{}"
            }
            if (state == State.OPENING_WIFI) {
                Log.i(TAG, "openWifiDirectForSync: already OPENING_WIFI, awaiting onWifiDirectReady")
                return "{}"
            }
            transitionTo(State.OPENING_WIFI)
            openingWifiStartedAtMs = SystemClock.elapsedRealtime()
            wifi.open()
            // Details arrive asynchronously via onWifiReady -> onWifiDirectReady callback.
            return "{}"
        }

        override fun closeWifiDirect() {
            if (state == State.IDLE) return
            transitionTo(State.CLOSING)
            wifi.close()
            // state will settle to IDLE in onClosed
        }

        override fun deleteFile(fileId: String): Boolean {
            // Atomic: if HTTP is mid-stream for this id, refuse. Otherwise reserve the id so a
            // new /file/<id> request landing during the delete can't serve a stale copy.
            if (state == State.SERVING && !http.tryLockForDelete(fileId)) {
                Log.w(TAG, "deleteFile($fileId) refused: currently being served")
                return false
            }
            try {
                synchronized(manifestLock) {
                    val entry = manifest.get(fileId) ?: return false
                    val file = File(ScanJob.rootDir, entry.relPath)
                    val deleted = file.delete() || !file.exists()
                    if (!deleted) {
                        Log.w(TAG, "deleteFile: failed to delete ${file.absolutePath}")
                        return false
                    }
                    manifest.remove(fileId)
                    store.save(manifest.snapshot())
                }
            } finally {
                if (state == State.SERVING) http.releaseDeleteLock(fileId)
            }
            val newHash = synchronized(manifestLock) { manifest.stateHash() }
            broadcast { it.onFileDeleted(fileId) }
            // Defer the manifest-change broadcast until post-CLOSING if we're serving,
            // for the same reason notifyNew defers.
            if (state == State.SERVING || state == State.OPENING_WIFI || state == State.CLOSING) {
                dirtyDuringServe = true
            } else {
                broadcast { it.onManifestChanged(newHash) }
            }
            return true
        }

        override fun ack(fileId: String) {
            // reserved
        }

        override fun setSideloadEnabled(enabled: Boolean) {
            if (sideloadEnabled == enabled) return
            sideloadEnabled = enabled
            Log.i(TAG, "setSideloadEnabled: $enabled")
            // Flipping off must immediately kill any running exec jobs (a true teardown, unlike a
            // transient WiFi close) and purge staged uploads -- tmp files must never persist once
            // the gate closes.
            if (!enabled) {
                try { http.killAllJobs() } catch (e: Exception) { Log.w(TAG, "killAllJobs on disable: ${e.message}") }
                try { http.wipeStaging() } catch (e: Exception) { Log.w(TAG, "wipeStaging on disable: ${e.message}") }
            }
        }
    }

    private val newFileBinder = object : INewFile.Stub() {
        override fun notifyNew(absPath: String, sha256: String, sizeBytes: Long, kind: String): Boolean {
            Log.i(TAG_IPC, "notifyNew: enter kind=$kind size=$sizeBytes path=$absPath")
            val root = ScanJob.ensureRoot()
            val f = File(absPath)
            if (!f.exists()) {
                Log.w(TAG, "notifyNew: file missing: $absPath")
                return false
            }
            val entry = synchronized(manifestLock) {
                val e = manifest.upsert(root, absPath, sha256, sizeBytes, f.lastModified(), kind)
                store.save(manifest.snapshot())
                e
            }
            Log.i(TAG, "notifyNew: ${kind} ${entry.relPath} sha=${sha256.take(10)} size=$sizeBytes")
            // Per plan: if SERVING/OPENING_WIFI, set dirty and DO NOT emit onManifestChanged.
            // The phone is mid-pull on a prior snapshot; changing the manifest hash now would
            // force it to restart a session mid-transfer. We fire the emit after CLOSING->IDLE.
            if (state == State.SERVING || state == State.OPENING_WIFI || state == State.CLOSING) {
                dirtyDuringServe = true
                Log.i(TAG, "notifyNew during ${state} -- deferring onManifestChanged")
                return true
            }
            val newHash = synchronized(manifestLock) { manifest.stateHash() }
            broadcast { it.onManifestChanged(newHash) }
            return true
        }
    }

    override fun onCreate() {
        super.onCreate()

        val channel = NotificationChannel(CHANNEL_ID, "File Sync", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("File Sync")
            .setContentText("Ready")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .build()
        // Claim LOCATION + DATA_SYNC foreground-service types at runtime so the WifiP2pManager
        // FINE_LOCATION appops check sees us as location-foreground (required to createGroup
        // from a non-priv app -- appops UID mode is otherwise locked to "foreground"-only
        // which means UI foreground, not ServiceInfo.FOREGROUND).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val root = ScanJob.ensureRoot()
        store = ManifestStore(root)
        synchronized(manifestLock) {
            manifest.replaceAll(store.load())
            try { ScanJob.reconcile(manifest) } catch (e: Exception) {
                if (isEperm(e)) {
                    Log.w(TAG, "reconcile hit EPERM (MANAGE_EXTERNAL_STORAGE not granted?); service continues: ${e.message}")
                } else {
                    // Anything else is an actual bug we want visible. Rethrow.
                    throw e
                }
            }
            // Initial save may fail on first boot if MANAGE_EXTERNAL_STORAGE isn't granted
            // yet; service must still come up so the AIDL binder is available.
            try { store.save(manifest.snapshot()) } catch (e: Exception) {
                if (isEperm(e)) {
                    Log.w(TAG, "manifest save EPERM (non-fatal, service continuing): ${e.message}")
                } else {
                    throw e
                }
            }
        }
        Log.i(TAG, "manifest loaded: ${manifest.snapshot().size} entries, hash=${manifest.stateHash().take(12)}")

        wifi = WifiDirectHost(
            context = this,
            onReady = { details -> onWifiReady(details) },
            onClosed = { onWifiClosed() },
            onError = { reason -> onWifiError(reason) },
        )
        http = FileHttpServer(
            port = WifiDirectHost.HTTP_PORT,
            // Lock-protected manifest accessors. The HTTP pool threads must never read the manifest
            // without manifestLock -- reconcileNow()/delete mutate it under that lock, so a lock-free
            // iteration would race -> ConcurrentModificationException. Body+etag are produced together
            // in one locked block so the served JSON and its ETag are a consistent snapshot.
            manifestJsonProvider = {
                synchronized(manifestLock) {
                    Pair(manifest.toJsonArray().toString().toByteArray(Charsets.UTF_8), manifest.stateHash())
                }
            },
            manifestEntryProvider = { id -> synchronized(manifestLock) { manifest.get(id) } },
            // Safety net for a dropped capture-side notifyNew: the phone diffs the FULL manifest
            // on every handshake, so reconciling on each /manifest GET makes any file that exists
            // on disk but was never reported (post-processing aborted before notifyNew) appear in
            // the very response the phone is about to diff -- it heals with no extra notify plumbing.
            reconcileBeforeServe = { reconcileNow() },
            onHit = { lastHitMs = SystemClock.elapsedRealtime() },
            sideloadEnabled = { sideloadEnabled },
            // App-private dir: writable by this priv_app uid (/data/local/tmp is shell-only),
            // and readable by the root appsud daemon when it pushes staged files into /system.
            stagingDirPath = File(filesDir, "sideload").absolutePath,
        )

        idleChecker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "FileSync-idle") }
        // Self-rescheduling tick so we can stretch the period to IDLE_TICK_QUIET_MS while in
        // State.IDLE and snap back to IDLE_TICK_BUSY_MS the moment we transition to
        // OPENING_WIFI/SERVING/CLOSING. Replaces the old fixed-rate 10 s wake-up.
        idleChecker.schedule(idleTickRunnable, IDLE_TICK_BUSY_MS, TimeUnit.MILLISECONDS)

        // Periodic audio-archive sync trigger. Runs on its own scheduler so the idle-check
        // cadence doesn't get tangled up with the hourly archive nudge. The task is started
        // lazily on the first onWifiReady (peer connect) and cancelled on onWifiClosed so we
        // don't burn CPU scanning the listener filesDir while no phone is around to consume it.
        archiveScheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "FileSync-archive") }

        // Emit initial manifest hash so listener bridge sees it.
        val initial = synchronized(manifestLock) { manifest.stateHash() }
        broadcast { it.onManifestChanged(initial) }
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG_IPC, "onBind: action=${intent?.action}")
        return when (intent?.action) {
            "com.repository.glasses.filesync.BIND_NEW_FILE" -> newFileBinder
            else -> binder
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        Log.i(TAG, "destroying")
        try { http.stop() } catch (_: Exception) {}
        try { wifi.close() } catch (_: Exception) {}
        idleChecker.shutdownNow()
        if (::archiveScheduler.isInitialized) archiveScheduler.shutdownNow()
        callbacks.kill()
        super.onDestroy()
    }

    /**
     * Scan DCIM/Repository/audio-archive/ for completed (rotated) .opus files. If any
     * exist, broadcast an intent the glasses ListenerService consumes to nudge the phone
     * over BT. Called on a 60-min schedule by [archiveScheduler].
     *
     * The archive lives in shared external storage (same DCIM/Repository rootDir filesync
     * already serves video/photo from), so this and the listener APK share read access
     * without any cross-UID gymnastics.
     *
     * A file is considered "completed" (safe to sync) when its mtime is older than
     * [ARCHIVE_COMPLETE_FILE_AGE_MS] -- LocalOpusWriter rotates on 60 min / 7 MB, so this
     * age check reliably excludes the currently-appending file.
     */
    private fun maybeRequestAudioArchiveSync() {
        val startMs = System.currentTimeMillis()
        Log.i(TAG_ARCHIVE, "archiveScan: enter")
        val archiveDir = resolveListenerAudioArchiveDir()
        if (archiveDir == null) {
            // resolveListenerAudioArchiveDir already logged an ERROR if it failed.
            Log.w(TAG_ARCHIVE, "archiveScan: skipping -- archive dir unresolved durMs=${System.currentTimeMillis()-startMs}")
            return
        }
        if (!archiveDir.isDirectory) {
            Log.i(TAG_ARCHIVE, "archiveScan: exit dirMissing path=${archiveDir.absolutePath} durMs=${System.currentTimeMillis()-startMs}")
            return
        }
        val nowMs = System.currentTimeMillis()
        val cutoff = nowMs - ARCHIVE_COMPLETE_FILE_AGE_MS
        var completedCount = 0
        var filesScanned = 0
        // Distinguish null (I/O / permission failure) from empty (legitimately no dirs).
        val dayDirsRaw = archiveDir.listFiles()
        if (dayDirsRaw == null) {
            Log.w(TAG_ARCHIVE, "archiveScan: exit listNull path=${archiveDir.absolutePath} durMs=${System.currentTimeMillis()-startMs}")
            return
        }
        // Bound the scan to the newest N day-dirs, derived from LocalOpusWriter's retention.
        // Source: LocalOpusWriter.RETAIN_MILLIS = 24h (see
        // app/src/main/java/com/repository/glasses/listener/capture/LocalOpusWriter.kt:53).
        // retention / DAY + 1 covers the midnight-rollover grace window. More than this
        // indicates pruneOldFiles is regressing; we surface a WARN instead of traversing
        // unbounded. Deletion itself remains LocalOpusWriter's job.
        val nonEmptyDayDirs = dayDirsRaw
            .filter { it.isDirectory }
            .sortedByDescending { it.lastModified() }
        val retainMillis = 24L * 60L * 60L * 1000L
        val dayMs = 24L * 60L * 60L * 1000L
        val scanBudget = (retainMillis / dayMs).toInt() + 1
        if (nonEmptyDayDirs.size > scanBudget) {
            val oldest = nonEmptyDayDirs.last()
            Log.w(TAG, "maybeRequestAudioArchiveSync: found ${nonEmptyDayDirs.size} day-dirs (expected <=$scanBudget); " +
                "oldest=${oldest.absolutePath} mtime=${oldest.lastModified()} -- pruneOldFiles may be regressing. Skipping beyond the newest $scanBudget.")
        }
        for (dayDir in nonEmptyDayDirs.take(scanBudget)) {
            val files = dayDir.listFiles() ?: continue
            for (f in files) {
                filesScanned++
                if (!f.isFile) continue
                if (!f.name.endsWith(".opus")) continue
                if (f.lastModified() in 1 until cutoff) {
                    completedCount++
                }
            }
        }
        if (completedCount <= 0) {
            Log.i(TAG_ARCHIVE, "archiveScan: exit noCompleted scanned=$filesScanned dayDirs=${nonEmptyDayDirs.size} durMs=${System.currentTimeMillis()-startMs}")
            return
        }
        Log.i(TAG_ARCHIVE, "archiveScan: exit completed=$completedCount scanned=$filesScanned dayDirs=${nonEmptyDayDirs.size} durMs=${System.currentTimeMillis()-startMs} -- broadcasting")
        val intent = Intent(ACTION_REQUEST_AUDIO_ARCHIVE_SYNC).apply {
            setPackage(LISTENER_PACKAGE)
            putExtra(EXTRA_PENDING_FILE_COUNT, completedCount)
            putExtra(EXTRA_ARCHIVE_DIR, archiveDir.absolutePath)
        }
        try {
            // Signature-level permission ensures no third-party app receives this intent
            // even if they somehow register for the action. Combined with setPackage()
            // above the broadcast is fully confined to the listener app.
            sendBroadcast(intent, AUDIO_ARCHIVE_SYNC_PERMISSION)
        } catch (e: Exception) {
            Log.w(TAG, "maybeRequestAudioArchiveSync: sendBroadcast failed: ${e.message}")
        }
    }

    private fun resolveListenerAudioArchiveDir(): File? {
        // Same shared rootDir as ScanJob.rootDir + audio-archive subdir. Co-located with
        // video/photo so cross-UID reads from filesync work the same way they do for the
        // rest of the sync surface.
        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        return File(File(dcim, "Repository"), ARCHIVE_SUBDIR)
    }

    private fun onWifiReady(details: JSONObject) {
        // HTTP needs to bind AFTER group is formed (address is in details).
        try {
            http.start()
            lastHitMs = SystemClock.elapsedRealtime()
            val detailsJson = details.toString()
            currentWifiDetailsJson = detailsJson
            transitionTo(State.SERVING)
            // Peer is up: (re)start the archive scan. Idle tick will already be at BUSY cadence
            // because state is no longer IDLE.
            startArchiveScheduler()
            Log.i(TAG, "WIFI_READY $detailsJson")
            broadcast { it.onWifiDirectReady(detailsJson) }
        } catch (e: Exception) {
            Log.e(TAG, "http.start failed: ${e.message}")
            wifi.close()
        }
    }

    private fun onWifiError(reason: String) {
        // Fires BEFORE onWifiClosed (see WifiDirectHost.failWith). Lets the listener
        // bridge surface a concrete reason up the stack instead of a blind close.
        Log.w(TAG, "WIFI_ERROR reason=$reason")
        broadcast { it.onWifiDirectError(reason) }
    }

    private fun onWifiClosed() {
        try { http.stop() } catch (_: Exception) {}
        currentWifiDetailsJson = null
        transitionTo(State.IDLE)
        // Peer is gone: stop the archive scan until the phone reconnects. Idle tick will
        // self-stretch to IDLE_TICK_QUIET_MS on its next reschedule.
        stopArchiveScheduler()
        Log.i(TAG, "wifi closed -> IDLE")
        broadcast { it.onWifiDirectClosed() }
        // Flush any manifest changes we deferred during SERVING so the phone learns about them.
        if (dirtyDuringServe) {
            dirtyDuringServe = false
            val newHash = synchronized(manifestLock) { manifest.stateHash() }
            Log.i(TAG, "flushing deferred manifest change post-CLOSING hash=${newHash.take(12)}")
            broadcast { it.onManifestChanged(newHash) }
        }
    }

    @Volatile private var openingWifiStartedAtMs: Long = 0

    /**
     * Filesync-side safety net for a captured file that exists on disk but never made it into
     * the manifest (capture app aborted post-processing before calling notifyNew, so the file
     * never syncs). Re-scans the watched dir and upserts any unreported media file. Called on
     * every phone handshake (FileHttpServer.reconcileBeforeServe) and -- as a backstop for the
     * no-phone-ever-connects case -- on the coarse idle tick.
     *
     * Cheap by design: ScanJob.reconcile only stats unchanged files (size+mtime match) and
     * sha256s only genuinely new/changed ones, so running it per /manifest GET is acceptable.
     * Guarded by the SAME isEperm filter as the boot reconcile so a transient storage-permission
     * race can't crash the service.
     */
    fun reconcileNow() {
        synchronized(manifestLock) {
            val before = manifest.stateHash()
            try {
                // NOTE: ScanJob.reconcile() sha256s any new/changed files while we hold manifestLock.
                // A cold backlog of large un-notified files would hash here on the HTTP serving thread
                // (called via reconcileBeforeServe) and stall concurrent binder getManifestJson/delete
                // for the hash duration. We accept that: the phone diffs the post-reconcile manifest in
                // the SAME response, so the scan MUST complete before we serialize -- moving it off-lock
                // would break that ordering guarantee. In practice the backlog is small (notifyNew is
                // the normal path; this only heals dropped notifies).
                ScanJob.reconcile(manifest)
            } catch (e: Exception) {
                if (isEperm(e)) {
                    Log.w(TAG, "reconcileNow hit EPERM (MANAGE_EXTERNAL_STORAGE not granted?); skipping: ${e.message}")
                    return
                }
                throw e
            }
            val after = manifest.stateHash()
            if (after == before) return
            try { store.save(manifest.snapshot()) } catch (e: Exception) {
                if (isEperm(e)) {
                    Log.w(TAG, "reconcileNow save EPERM (non-fatal): ${e.message}")
                } else {
                    throw e
                }
            }
            // Mirror notifyNew's deferral rule: if a sync is in flight, don't change the hash the
            // phone is mid-pull on -- flag it and let onWifiClosed()/checkIdle flush the broadcast
            // after the session ends. When IDLE, notify listeners directly.
            if (state == State.SERVING || state == State.OPENING_WIFI || state == State.CLOSING) {
                dirtyDuringServe = true
                Log.i(TAG, "reconcileNow added entries during ${state} -- deferring onManifestChanged hash=${after.take(12)}")
            } else {
                Log.i(TAG, "reconcileNow added entries -- broadcasting hash=${after.take(12)}")
                broadcast { it.onManifestChanged(after) }
            }
        }
    }

    private fun checkIdle() {
        Log.i(TAG_IDLE, "tick state=$state")
        // Safety net for a STRANDED deferred manifest notification. notifyNew (and
        // deleteFile) set dirtyDuringServe and skip the onManifestChanged broadcast
        // while SERVING/OPENING_WIFI/CLOSING; that deferral is normally flushed by
        // onWifiClosed() on the return to IDLE. But if the WiFi-Direct group tears
        // down via the firmware 36s idle timeout WITHOUT delivering the close
        // broadcast (a documented quirk), onWifiClosed never runs and the flag is
        // stranded -- the phone is never told, so photos only appear after the next
        // filesync restart (reconcile). Flush it here so the phone learns within one
        // idle tick instead of waiting for a reboot.
        if (state == State.IDLE) {
            if (dirtyDuringServe) {
                dirtyDuringServe = false
                val newHash = synchronized(manifestLock) { manifest.stateHash() }
                Log.i(TAG, "idle-tick flushing stranded dirty manifest hash=${newHash.take(12)}")
                broadcast { it.onManifestChanged(newHash) }
            }
            // Backstop for the case where NO phone ever connects (so the handshake reconcile
            // never fires): pick up any file dropped from notifyNew within one idle tick. The
            // idle tick runs at IDLE_TICK_QUIET_MS (60 s) while IDLE -- a coarse-enough cadence,
            // and reconcileNow is cheap (stats unchanged files, hashes only new ones).
            reconcileNow()
            return
        }
        // Idle timeout while SERVING (no HTTP hits for IDLE_TIMEOUT_MS).
        if (state == State.SERVING) {
            val sinceHit = SystemClock.elapsedRealtime() - lastHitMs
            if (sinceHit >= IDLE_TIMEOUT_MS) {
                Log.i(TAG, "serving idle ${sinceHit}ms -> closing WiFi")
                transitionTo(State.CLOSING)
                wifi.close()
            }
            return
        }
        // Open-timeout: group never formed (firmware quirk, no CONNECTION_CHANGED broadcast).
        if (state == State.OPENING_WIFI) {
            val sinceOpen = SystemClock.elapsedRealtime() - openingWifiStartedAtMs
            if (sinceOpen >= OPEN_TIMEOUT_MS) {
                Log.w(TAG, "OPENING_WIFI stuck ${sinceOpen}ms -> tearing down")
                transitionTo(State.CLOSING)
                wifi.close()
            }
        }
    }

    private fun transitionTo(next: State) {
        Log.i(TAG, "state: $state -> $next")
        state = next
    }

    /**
     * Returns true if [e] (or any cause in its chain) is an `EPERM` (Operation not permitted)
     * filesystem failure. Used to suppress storage-permission races during service startup
     * while still surfacing unrelated exceptions.
     */
    private fun isEperm(e: Throwable): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            if (cur is android.system.ErrnoException && cur.errno == android.system.OsConstants.EPERM) return true
            val msg = cur.message ?: ""
            if (msg.contains("EPERM") || msg.contains("Operation not permitted")) return true
            cur = cur.cause
        }
        return false
    }

    private fun broadcast(action: (IFileSyncCallback) -> Unit) {
        synchronized(callbackLock) {
            val n = callbacks.beginBroadcast()
            try {
                for (i in 0 until n) {
                    try { action(callbacks.getBroadcastItem(i)) }
                    catch (e: Exception) { Log.w(TAG, "callback failed: ${e.message}") }
                }
            } finally {
                callbacks.finishBroadcast()
            }
        }
    }
}
