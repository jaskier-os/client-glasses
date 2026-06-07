package com.repository.glasses.listener.sync

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.repository.glasses.filesync.INewFile
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Binds to filesync's INewFile and tells it about a newly-written file in
 * /sdcard/DCIM/Repository/ (videos and photos at the root, opus archive in
 * /sdcard/DCIM/Repository/audio-archive/<date>/). Without this, a recording
 * dropped into the filesync rootDir is invisible until the next FileSyncService
 * restart (its only scan path runs in onCreate). The phone-side catalogue then
 * stays at "Videos: 1" forever -- the prior stale stub.
 *
 * Binds per-notify, unbinds on result. Cheap enough; recording events are
 * rare. Mirrors the capture-side SyncNotifier (same AIDL contract).
 */
class SyncNotifier(private val context: Context) {

    companion object {
        private const val TAG = "App:SyncNotif"
        private const val FILESYNC_PKG = "com.repository.glasses.filesync"
        private const val BIND_ACTION = "com.repository.glasses.filesync.BIND_NEW_FILE"
    }

    /**
     * Computes sha256 inline and notifies. Blocks up to 5s for the bind.
     * Safe to call from a background thread (BT recording-stop callbacks
     * are off-main already). kind is "video" or "photo".
     */
    fun notify(file: File, kind: String) {
        if (!file.exists() || file.length() == 0L) {
            Log.w(TAG, "skip empty/missing file: ${file.absolutePath}")
            return
        }
        val sha256 = try {
            sha256OfFile(file)
        } catch (e: Exception) {
            Log.w(TAG, "sha256 failed for ${file.name}: ${e.message}")
            return
        }
        Log.i(TAG, "notify entry kind=$kind path=${file.absolutePath} bytes=${file.length()}")
        val t0 = android.os.SystemClock.elapsedRealtime()
        val latch = CountDownLatch(1)
        val result = booleanArrayOf(false)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                try {
                    val api = INewFile.Stub.asInterface(binder)
                    val ok = api.notifyNew(file.absolutePath, sha256, file.length(), kind)
                    result[0] = ok
                    Log.i(TAG, "notifyNew $kind ${file.name} ok=$ok")
                } catch (e: Exception) {
                    Log.w(TAG, "notifyNew failed: ${e.message}")
                } finally {
                    latch.countDown()
                }
            }
            override fun onServiceDisconnected(name: ComponentName) { latch.countDown() }
        }
        val intent = Intent(BIND_ACTION).apply { `package` = FILESYNC_PKG }
        val bound = try { context.bindService(intent, conn, Context.BIND_AUTO_CREATE) }
                    catch (e: Exception) { Log.w(TAG, "bindService threw: ${e.message}"); false }
        if (!bound) {
            Log.w(TAG, "bindService failed -- filesync not installed/available?")
            return
        }
        try { latch.await(5, TimeUnit.SECONDS) } catch (_: Exception) {}
        try { context.unbindService(conn) } catch (_: Exception) {}
        Log.i(TAG, "notify exit kind=$kind ok=${result[0]} durMs=${android.os.SystemClock.elapsedRealtime() - t0}")
    }

    private fun sha256OfFile(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
