package com.repository.glasses.listener.capture

import android.content.Context
import android.util.Log
import java.io.File
import java.util.zip.ZipFile

/**
 * Controls the Rokid Glasses mic array beamforming via the native libaudigent.so.
 *
 * The library is extracted from RokidSpriteAssistServer.apk at first use.
 * It talks to the RT600 DSP chip via /dev/rt600_spidev (world-accessible).
 *
 * Scene IDs:
 *   0 = idle/interaction (omnidirectional)
 *   1 = translation (cardioid/directional - "face the speaker")
 *   2 = phone call (omni)
 *   3 = conference
 */
object BeamformController {
    private const val TAG = "BeamformController"
    private const val ASSIST_APK = "/product/app/RokidSpriteAssistServer/RokidSpriteAssistServer.apk"
    private const val LIB_NAME = "libaudigent.so"
    private const val LIB_ENTRY = "lib/arm64-v8a/libaudigent.so"

    const val SCENE_IDLE = 0
    const val SCENE_CARDIOID = 1   // directional, face-speaker
    const val SCENE_OMNI = 2
    const val SCENE_CONFERENCE = 3

    @Volatile private var ready = false
    var remoteLog: ((String) -> Unit)? = null

    private external fun nativeInit(libPath: String, initConfig: String): Boolean
    private external fun nativeSetScene(json: String): Boolean
    private external fun nativeDestroy()

    init {
        System.loadLibrary("beamform_jni")
    }

    fun init(ctx: Context): Boolean {
        if (ready) return true

        val libFile = File(ctx.filesDir, "native/$LIB_NAME")
        if (!libFile.exists()) {
            if (!extractLib(libFile)) return false
        }

        // Init config matches what Rokid's OffLineManager passes to RtInstructSdk.initSdk():
        // a JSON array with firmware control (scene 0 = idle) and power control items.
        val initConfig = """[{"cmd":"change_firmware","params":{"scene":0,"lang":"en","enable":false}},{"cmd":"lowpower_mode","params":{"scene":-1,"lang":"","enable":false}}]"""

        ready = nativeInit(libFile.absolutePath, initConfig)
        log(if (ready) "Beamform controller initialized" else "Beamform controller init FAILED")
        return ready
    }

    fun setScene(sceneId: Int): Boolean {
        if (!ready) {
            log("setScene($sceneId) called but not initialized")
            return false
        }
        val json = """{"cmd":"change_firmware","params":{"scene":$sceneId,"lang":"","enable":false}}"""
        val result = nativeSetScene(json)
        log("setScene($sceneId) -> $result")
        return result
    }

    fun destroy() {
        if (!ready) return
        nativeDestroy()
        ready = false
        log("Beamform controller destroyed")
    }

    private fun extractLib(target: File): Boolean {
        return try {
            target.parentFile?.mkdirs()
            val apk = File(ASSIST_APK)
            if (!apk.exists()) {
                log("AssistServer APK not found at $ASSIST_APK")
                return false
            }
            ZipFile(apk).use { zip ->
                val entry = zip.getEntry(LIB_ENTRY) ?: run {
                    log("$LIB_ENTRY not found in APK")
                    return false
                }
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            target.setExecutable(true)
            log("Extracted $LIB_NAME (${target.length()} bytes)")
            true
        } catch (e: Exception) {
            log("Failed to extract $LIB_NAME: ${e.message}")
            false
        }
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        remoteLog?.invoke("Beamform: $msg")
    }
}
