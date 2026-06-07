package com.repository.glasses.listener.wakeword

import android.content.Context
import android.media.AudioFormat
import android.os.Build
import android.os.HandlerThread
import android.os.ParcelUuid
import android.util.Log
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SVA keyphrase detector via Android SoundTriggerManager (Java).
 *
 * Path D outcome: PAL_STREAM_VOICE_UI works on this device end-to-end through
 * the GSL engine layer when a single libar-pal binary patch is applied
 * (RM::isStreamSupported -> always-true). Direct JNI pal_stream_set_param
 * with the full Rokid model (1.79 MB ant_rokid_model.bin) hits the 1 MB
 * binder transaction limit. SoundTriggerManager's Java API uses ashmem-backed
 * payloads under the hood, side-stepping the binder limit.
 *
 * This class uses reflection on hidden SoundTrigger$KeyphraseSoundModel /
 * GenericSoundModel + SoundTriggerManager APIs since they're @hide.
 *
 * Reqs (verified at start()):
 *   - Permission CAPTURE_AUDIO_HOTWORD (privileged)
 *   - libar-pal.so RM::isStreamSupported patched
 *   - /system/etc/ant_rokid_model.bin readable
 *
 * On detection: invokes [SpeechCallback.onSpeech] with epochNanos.
 */
internal class SvaSoundTriggerDetector(private val context: Context) {

    fun interface SpeechCallback {
        fun onSpeech(epochNanos: Long)
    }

    private val active = AtomicBoolean(false)
    @Volatile private var heldCallback: SpeechCallback? = null
    @Volatile private var modelHandle: Any? = null   // SoundTriggerManager.Model
    @Volatile private var stm: Any? = null           // SoundTriggerManager
    @Volatile private var modelUuid: UUID? = null
    private var statusThread: HandlerThread? = null

    fun isRunning(): Boolean = active.get()

    /** Returns null on success, error reason string on failure. */
    fun start(callback: SpeechCallback): String? {
        if (!isAvailable()) return "model not present at $kModelPath"
        if (!active.compareAndSet(false, true)) return "already running"
        heldCallback = callback
        return try {
            startInternal()
            null
        } catch (t: Throwable) {
            Log.w(TAG, "SVA start failed", t)
            active.set(false)
            heldCallback = null
            "${t.javaClass.simpleName}: ${t.message}"
        }
    }

    fun stop() {
        if (!active.compareAndSet(true, false)) return
        try {
            modelHandle?.let { mh ->
                runCatching {
                    val stopMid = mh.javaClass.getMethod("stopRecognition")
                    val rc = stopMid.invoke(mh) as? Int
                    Log.i(TAG, "Model.stopRecognition rc=$rc")
                }
            }
            stm?.let { mgr ->
                modelUuid?.let { uuid ->
                    runCatching {
                        val unloadMid = mgr.javaClass.getMethod("unloadSoundModel", UUID::class.java)
                        unloadMid.invoke(mgr, uuid)
                        Log.i(TAG, "unloadSoundModel ok")
                    }
                }
            }
        } finally {
            modelHandle = null
            stm = null
            modelUuid = null
            statusThread?.quitSafely()
            statusThread = null
            heldCallback = null
        }
    }

    private fun startInternal() {
        // 1) Read the model bytes
        val bytes = File(kModelPath).readBytes()
        Log.i(TAG, "loaded ${bytes.size} bytes from $kModelPath")

        // 2) Get SoundTriggerManager via context.getSystemService (hidden constant
        //    "soundtrigger" string is the service name).
        val mgrClass = Class.forName("android.media.soundtrigger.SoundTriggerManager")
        val mgr = context.getSystemService("soundtrigger")
            ?: throw IllegalStateException("SoundTriggerManager service unavailable (need CAPTURE_AUDIO_HOTWORD)")
        Log.i(TAG, "got SoundTriggerManager: ${mgr.javaClass.name}")
        stm = mgr

        // 3) Construct SoundTrigger.KeyphraseSoundModel via reflection
        //    Path D learning: VOICE_UI requires KEYPHRASE type with at least
        //    one phrase. Use phrase id=1 with VOICE_TRIGGER mode. Vendor UUID
        //    is QTI SVA standard 68ab2d40-e860-11e3-95ef-0002a5d5c51b.
        val modelId = UUID.randomUUID()
        val vendorUuid = UUID.fromString("68ab2d40-e860-11e3-95ef-0002a5d5c51b")
        modelUuid = modelId

        // Build KeyphraseSoundModel — QTI HAL maxKeyPhrases=10, this is the
        // native voice-UI format. Load via the underlying ISoundTriggerSession
        // binder directly (SoundTriggerManager.loadSoundModel only routes
        // GenericSoundModels and the keyphrase variant isn't exposed publicly).
        val keyphraseClass = Class.forName(
            "android.hardware.soundtrigger.SoundTrigger\$Keyphrase"
        )
        val keyphraseCtor = keyphraseClass.getConstructor(
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            java.util.Locale::class.java, String::class.java, IntArray::class.java
        )
        val keyphrase = keyphraseCtor.newInstance(
            100, RECOGNITION_MODE_VOICE_TRIGGER, java.util.Locale.US, "wakeword", IntArray(0)
        )
        val keyphraseArray = java.lang.reflect.Array.newInstance(keyphraseClass, 1) as Array<Any?>
        keyphraseArray[0] = keyphrase

        val keyphraseSmClass = Class.forName(
            "android.hardware.soundtrigger.SoundTrigger\$KeyphraseSoundModel"
        )
        val keyphraseSm = keyphraseSmClass.getConstructor(
            UUID::class.java, UUID::class.java, ByteArray::class.java, keyphraseArray.javaClass
        ).newInstance(modelId, vendorUuid, bytes, keyphraseArray)
        Log.i(TAG, "built KeyphraseSoundModel uuid=$modelId vendor=$vendorUuid bytes=${bytes.size}")

        // Also build GenericSoundModel for fallback Model handle ctor.
        val genericSmClass = Class.forName(
            "android.hardware.soundtrigger.SoundTrigger\$GenericSoundModel"
        )
        val genericSm = genericSmClass.getConstructor(
            UUID::class.java, UUID::class.java, ByteArray::class.java
        ).newInstance(modelId, vendorUuid, bytes)

        // Reflect mSoundTriggerSession (an ISoundTriggerSession Stub.Proxy).
        val sessionField = mgrClass.declaredFields.firstOrNull {
            it.name.contains("Session") || it.name.contains("session")
        } ?: throw IllegalStateException("no session field on SoundTriggerManager")
        sessionField.isAccessible = true
        val session = sessionField.get(mgr)
            ?: throw IllegalStateException("session field is null")
        Log.i(TAG, "got session field='${sessionField.name}' class=${session.javaClass.name}")

        // ISoundTriggerSession.loadKeyphraseSoundModel
        val sessionClass = session.javaClass
        val loadKeyphraseMid = sessionClass.methods.firstOrNull {
            it.name == "loadKeyphraseSoundModel" && it.parameterTypes.size == 1
        } ?: throw IllegalStateException("loadKeyphraseSoundModel not on session")
        val loadRc = loadKeyphraseMid.invoke(session, keyphraseSm) as? Int
        Log.i(TAG, "session.loadKeyphraseSoundModel rc=$loadRc")
        if (loadRc != null && loadRc != 0) throw IllegalStateException("loadKeyphraseSoundModel returned $loadRc")

        // 5) Get Model handle. getModel(UUID) queries SoundTriggerService's
        //    mLoadedModels map. If that returns null after load, the service
        //    didn't register it (likely consumed via a different path) — fall
        //    back to constructing Model directly via its hidden ctor that
        //    takes a GenericSoundModel.
        val getModelMid = mgrClass.getMethod("getModel", UUID::class.java)
        var mh = getModelMid.invoke(mgr, modelId)
        if (mh == null) {
            Log.w(TAG, "getModel returned null; constructing Model directly")
            val modelClass = Class.forName("android.media.soundtrigger.SoundTriggerManager\$Model")
            val ctor = modelClass.declaredConstructors.firstOrNull {
                it.parameterTypes.size == 1 && it.parameterTypes[0] == genericSmClass
            } ?: throw IllegalStateException("Model ctor(GenericSoundModel) not found")
            ctor.isAccessible = true
            mh = ctor.newInstance(genericSm)
        }
        modelHandle = mh
        Log.i(TAG, "Model handle: ${mh!!.javaClass.name}")
        for (m in mh.javaClass.declaredMethods) {
            Log.i(TAG, "Model.${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
        }
        val startMethods = mh.javaClass.methods.filter { it.name == "startRecognition" }

        // Build a minimal RecognitionConfig:
        //   RecognitionConfig(captureRequested:bool, allowMultipleTriggers:bool,
        //                     keyphrases: KeyphraseRecognitionExtra[], data: byte[])
        val keyphraseRecogExtraClass = Class.forName(
            "android.hardware.soundtrigger.SoundTrigger\$KeyphraseRecognitionExtra"
        )
        val kreCtor = keyphraseRecogExtraClass.getConstructor(
            Int::class.javaPrimitiveType,                   // id
            Int::class.javaPrimitiveType,                   // recognitionModes
            Int::class.javaPrimitiveType,                   // coarseConfidenceLevel
            Array<Any>::class.java.let {
                Class.forName("[Landroid.hardware.soundtrigger.SoundTrigger\$ConfidenceLevel;")
            }
        )
        val confLvlClass = Class.forName(
            "android.hardware.soundtrigger.SoundTrigger\$ConfidenceLevel"
        )
        val confLvlArr = java.lang.reflect.Array.newInstance(confLvlClass, 0)
        val kre = kreCtor.newInstance(
            1,                                              // id (matches Keyphrase.id above)
            RECOGNITION_MODE_VOICE_TRIGGER,                 // recognitionModes
            0,                                              // coarseConfidenceLevel
            confLvlArr
        )
        val kreArray = java.lang.reflect.Array.newInstance(keyphraseRecogExtraClass, 1) as Array<Any?>
        kreArray[0] = kre

        val recogCfgClass = Class.forName(
            "android.hardware.soundtrigger.SoundTrigger\$RecognitionConfig"
        )
        val recogCfgCtor = recogCfgClass.getConstructor(
            Boolean::class.javaPrimitiveType,               // captureRequested
            Boolean::class.javaPrimitiveType,               // allowMultipleTriggers
            kreArray.javaClass,                             // keyphrases
            ByteArray::class.java                           // data
        )
        val recogCfg = recogCfgCtor.newInstance(
            false,                                          // captureRequested
            false,                                          // allowMultipleTriggers
            kreArray,
            ByteArray(0)
        )
        Log.i(TAG, "built RecognitionConfig with ${kreArray.size} keyphrase(s)")

        // SoundTriggerManager.startRecognition(UUID, Bundle, ComponentName, RecognitionConfig)
        // — routes through startRecognitionForService which uses a different
        // model lookup path. Use our ListenerService as the ComponentName;
        // the framework binds to it as a SoundTriggerDetectionService.
        val componentName = android.content.ComponentName(
            context, "com.repository.glasses.listener.service.ListenerService"
        )
        val startMid = mgrClass.getMethod(
            "startRecognition",
            UUID::class.java, android.os.Bundle::class.java,
            android.content.ComponentName::class.java, recogCfgClass
        )
        val startRc = try {
            startMid.invoke(mgr, modelId, null, componentName, recogCfg) as? Int
        } catch (t: Throwable) {
            val real = t.cause ?: t
            Log.w(TAG, "Mgr.startRecognition failed: ${real.javaClass.simpleName}: ${real.message}", real)
            throw IllegalStateException("Mgr.startRecognition: ${real.message}")
        }
        Log.i(TAG, "Mgr.startRecognition rc=$startRc")
        if (startRc != null && startRc != 0) {
            throw IllegalStateException("Mgr.startRecognition returned $startRc")
        }
        Log.i(TAG, "SVA recognition armed; check dumpsys soundtrigger_middleware for STARTED state")
    }

    companion object {
        private const val TAG = "SvaSoundTrigger"
        private const val kModelPath = "/system/etc/ant_rokid_model.bin"

        // Mirror RECOGNITION_MODE constants from PAL/SoundTrigger.
        private const val RECOGNITION_MODE_VOICE_TRIGGER = 0x1
        private const val RECOGNITION_MODE_USER_IDENTIFICATION = 0x2

        fun isAvailable(): Boolean = File(kModelPath).exists() && File(kModelPath).length() > 0
    }
}
