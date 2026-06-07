package com.repository.glasses.listener.sync

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.repository.glasses.filesync.IFileSync
import com.repository.glasses.filesync.IFileSyncCallback
import java.util.concurrent.CopyOnWriteArrayList

/**
 * AIDL client to the standalone filesync APK.
 */
class FileSyncBridge(private val context: Context) {

    companion object {
        private const val TAG = "App:FileSync"
        private const val PKG = "com.repository.glasses.filesync"
        private const val SVC = "com.repository.glasses.filesync.FileSyncService"
    }

    interface Listener {
        fun onManifestChanged(newStateHash: String) {}
        fun onWifiDirectReady(detailsJson: String) {}
        fun onWifiDirectClosed() {}
        fun onWifiDirectError(reason: String) {}
        fun onFileDeleted(fileId: String) {}
    }

    var remoteLog: ((String) -> Unit)? = null
    private fun logMsg(msg: String) {
        android.util.Log.i(TAG, msg)
        remoteLog?.invoke(msg)
    }
    var isBound = false
        private set

    private var api: IFileSync? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val onBoundListeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile private var stopped = false
    @Volatile private var bindPending: Runnable? = null
    @Volatile private var currentBinder: IBinder? = null

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }
    fun addOnBoundListener(l: () -> Unit) {
        onBoundListeners.add(l)
        if (isBound) l.invoke()
    }

    private val callback = object : IFileSyncCallback.Stub() {
        override fun onManifestChanged(newStateHash: String) {
            logMsg("FileSync: manifest changed, hash=${newStateHash.take(12)}")
            listeners.forEach { it.onManifestChanged(newStateHash) }
        }
        override fun onWifiDirectReady(detailsJson: String) {
            logMsg("FileSync: wifi ready $detailsJson")
            listeners.forEach { it.onWifiDirectReady(detailsJson) }
        }
        override fun onWifiDirectClosed() {
            logMsg("FileSync: wifi closed")
            listeners.forEach { it.onWifiDirectClosed() }
        }
        override fun onWifiDirectError(reason: String) {
            logMsg("FileSync: wifi ERROR reason=$reason")
            listeners.forEach { it.onWifiDirectError(reason) }
        }
        override fun onFileDeleted(fileId: String) {
            logMsg("FileSync: file deleted $fileId")
            listeners.forEach { it.onFileDeleted(fileId) }
        }
    }

    private val deathRecipient: IBinder.DeathRecipient = object : IBinder.DeathRecipient {
        override fun binderDied() {
            logMsg("FileSync: binder DIED -- rebinding")
            api = null
            isBound = false
            val old = currentBinder
            currentBinder = null
            try { old?.unlinkToDeath(this, 0) } catch (_: Exception) {}
            val c = connection
            if (c != null) try { context.unbindService(c) } catch (_: Exception) {}
            if (!stopped) scheduleBind(1500)
        }
    }

    private var connection: ServiceConnection? = null

    init {
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                logMsg("FileSync: connected to $name")
                api = IFileSync.Stub.asInterface(service)
                isBound = true
                currentBinder = service
                try { service?.linkToDeath(deathRecipient, 0) } catch (_: Exception) {}
                try { api?.registerCallback(callback) } catch (e: Exception) {
                    logMsg("FileSync: registerCallback failed: ${e.message}")
                }
                onBoundListeners.forEach {
                    try { it.invoke() } catch (e: Exception) { logMsg("onBound failed: ${e.message}") }
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                logMsg("FileSync: DISCONNECTED -- rebinding")
                api = null
                isBound = false
                currentBinder = null
                val c = connection
                if (c != null) try { context.unbindService(c) } catch (_: Exception) {}
                if (!stopped) scheduleBind(1500)
            }
        }
    }

    private fun scheduleBind(delayMs: Long) {
        bindPending?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { if (!stopped) bind() }
        bindPending = r
        mainHandler.postDelayed(r, delayMs)
    }

    fun bind() {
        if (stopped) return
        bindPending = null
        try {
            val component = ComponentName(PKG, SVC)
            val c = connection ?: return
            try {
                val startIntent = Intent().apply {
                    this.component = component
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                }
                Log.d(TAG, "event=sync_active")
                context.startForegroundService(startIntent)
            } catch (e: Exception) {
                logMsg("FileSync: startForegroundService failed: ${e.message}")
            }
            val bindIntent = Intent().apply { this.component = component }
            val ok = context.bindService(bindIntent, c, Context.BIND_AUTO_CREATE)
            logMsg("FileSync: bindService=$ok")
            if (!ok) scheduleBind(2000)
        } catch (e: Exception) {
            logMsg("FileSync: bind exception: ${e.message}")
            scheduleBind(2000)
        }
    }

    fun unbind() {
        stopped = true
        bindPending?.let { mainHandler.removeCallbacks(it) }
        bindPending = null
        try { api?.unregisterCallback(callback) } catch (_: Exception) {}
        try { connection?.let { context.unbindService(it) } } catch (_: Exception) {}
        api = null
        isBound = false
    }

    // -- API --

    fun getStateHash(): String = try { api?.stateHash ?: "" } catch (_: Exception) { "" }
    fun getManifestJson(): String = try { api?.manifestJson ?: "[]" } catch (_: Exception) { "[]" }

    fun openWifiDirectForSync(): String =
        try { api?.openWifiDirectForSync() ?: "{}" } catch (e: Exception) {
            logMsg("FileSync: openWifi failed: ${e.message}"); "{}"
        }

    fun closeWifiDirect() {
        try { api?.closeWifiDirect() } catch (e: Exception) {
            logMsg("FileSync: closeWifi failed: ${e.message}")
        }
    }

    fun deleteFile(fileId: String): Boolean =
        try { api?.deleteFile(fileId) ?: false } catch (e: Exception) {
            logMsg("FileSync: deleteFile failed: ${e.message}"); false
        }
}
