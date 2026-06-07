package com.repository.glasses.listener

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.repository.glasses.tracing.GT
import com.repository.glasses.listener.service.UiKeepaliveService
import java.io.File

class GlassesListenerApp : Application() {

    companion object {
        private const val TAG = "GlassesListenerApp"

        fun writeCrashLog(context: Context, text: String) {
            try {
                val file = File(context.filesDir, "crash.log")
                file.appendText("${System.currentTimeMillis()}: $text\n")
            } catch (_: Exception) {}
        }

        /**
         * Unlock hidden-API reflection for every process in this app.
         *
         * Android blocks reflective access to `@hide` framework APIs for
         * non-platform-signed apps. Our listener is privapp but not
         * platform-signed, so we still need to flip the per-process
         * exemption list. HiddenApiBypass does that via VMRuntime internals
         * at API 28+. On earlier versions this is a no-op (the enforcement
         * mechanism doesn't exist yet).
         *
         * Currently retained as a defensive no-op for any future hidden-API
         * reflection added later. The previous SoundTrigger reflection path
         * (NativeHalDetector) was retired in favour of the JNI-based
         * AcdNativeDetector, which talks to libpalclient.so directly and
         * does not need framework hidden APIs.
         *
         * The "L" argument passes a package prefix filter ("L" matches every
         * JVM class descriptor since they all begin with "L"). A more
         * specific prefix also works; "L" avoids having to enumerate
         * targets.
         */
        fun unlockHiddenApis() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
            try {
                org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L")
                Log.i(TAG, "HiddenApiBypass: exemptions installed (SDK=${Build.VERSION.SDK_INT})")
            } catch (t: Throwable) {
                // Non-fatal: if bypass fails we simply stay on the ARM-ONNX
                // wake-word fallback path.
                Log.w(TAG, "HiddenApiBypass.addHiddenApiExemptions failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    override fun onCreate() = GT.section("app.onCreate") {
        super.onCreate()

        // Defensive: unlock hidden-API reflection for any future framework
        // access. Not strictly required for the current ACD JNI path.
        unlockHiddenApis()

        // Pin the UI process at FGS oom_adj tier. ListenerService and the
        // capture priv-app already have their own foreground services in
        // their own processes; without this anchor, the UI process drops
        // to HOME tier and gets evicted by lowmemorykiller during recording.
        if (isUiProcess()) {
            val intent = Intent(this, UiKeepaliveService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                else startService(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "UiKeepaliveService start failed: ${t.message}")
            }
        }

        // Hydrate GlassesConfig from SharedPreferences so UI settings
        // (preview padding, etc.) survive phone disconnect / listener restart.
        com.repository.glasses.listener.config.GlassesConfig.load(this)

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val msg = "FATAL ${thread.name}: ${throwable.message}\n${throwable.stackTraceToString()}"
            android.util.Log.e("TG_CRASH", msg)
            writeCrashLog(this, msg)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun isUiProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            try {
                val pid = android.os.Process.myPid()
                val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
            } catch (_: Throwable) { null }
        }
        return name == packageName
    }
}
