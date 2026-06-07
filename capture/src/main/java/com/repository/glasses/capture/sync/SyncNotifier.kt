package com.repository.glasses.capture.sync

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.repository.glasses.filesync.INewFile
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Binds to filesync's INewFile and reports a newly-written capture file.
 * Binds per-notify, unbinds on result. Cheap enough -- capture events are rare.
 */
class SyncNotifier(private val context: Context) {

    companion object {
        private const val TAG = "Cap:File"
        private const val FILESYNC_PKG = "com.repository.glasses.filesync"
        private const val BIND_ACTION = "com.repository.glasses.filesync.BIND_NEW_FILE"
    }

    fun notify(file: File, sha256: String, kind: String) {
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
        val bound = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        if (!bound) {
            Log.w(TAG, "bindService failed -- filesync not installed?")
            return
        }
        try { latch.await(5, TimeUnit.SECONDS) } catch (_: Exception) {}
        try { context.unbindService(conn) } catch (_: Exception) {}
        Log.i(TAG, "notify exit kind=$kind ok=${result[0]} durMs=${android.os.SystemClock.elapsedRealtime() - t0}")
    }
}
