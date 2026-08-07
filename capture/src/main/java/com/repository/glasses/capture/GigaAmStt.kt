package com.repository.glasses.capture

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Owns the on-glasses Russian recogniser: availability, residency, and the
 * transcribe call itself.
 *
 * This process is the only one whose linker namespace reaches the CDSP, so the
 * NPU work has to live here. The PCM stays in the LISTENER until end-of-speech
 * and crosses in one synchronous call, which is what makes a capture death cost
 * only the in-flight utterance -- the listener still holds the audio and falls
 * back to the remote transcriber.
 *
 * Residency policy, driven by the numbers measured on this device (task 0.3):
 * cold load 20988 ms, first execute 1131 ms, steady execute 1122 ms per 5 s
 * window, ~203 MB resident.
 *
 *  - Cold load is 21 s, so the FIRST utterance after a cold start cannot be
 *    served locally and is expected to fall back. prepareStt exists to move that
 *    cost off the critical path.
 *  - First execute is 9 ms over steady, so there is NO SCRFD-style graph
 *    finalize here and IDLE_UNLOAD_MS stays at 90 s.
 *  - 203 MB on a 1.7 GB device is not something to hold idle: the model is
 *    dropped after 90 s without an utterance.
 *
 * The wake lock is held around the encode+decode ONLY. The benchmark held one
 * for its service's whole life, which would stop the SoC ever sleeping.
 */
class GigaAmStt(private val context: Context) {

    companion object {
        private const val TAG = "GigaAmStt"

        /** Drop the model after this long with no utterance. See the class kdoc. */
        const val IDLE_UNLOAD_MS = 90_000L

        /** The only language the model was trained for. */
        private const val LANG_RU = "ru"

        /** Where the out-of-band-delivered context binary lives. */
        private const val MODEL_DIR = "ml/gigaam"

        private const val ASSET_DECODER = "ml/gigaam/v3_e2e_rnnt_decoder.onnx"
        private const val ASSET_JOINT = "ml/gigaam/v3_e2e_rnnt_joint.onnx"
        private const val ASSET_MELFB = "ml/gigaam/melfb_64x161.f32"
        private const val ASSET_VOCAB = "ml/gigaam/spm_vocab.json"

        /** This device. Compared against the manifest so a blob built elsewhere is refused. */
        const val SOC_ID = 579

        /** The QNN runtime bundled in this APK's jniLibs. */
        const val QNN_VERSION = "2.47"

        /**
         * Compared case-insensitively against the primary subtag, so "RU" and
         * "ru-RU" both count. The phone pushes whatever its dropdown holds and a
         * region tag must not silently disable the feature.
         */
        fun isRussian(tag: String?): Boolean =
            tag != null && tag.lowercase(Locale.ROOT).substringBefore('-') == LANG_RU
    }

    private val lock = Any()

    /** Set while an utterance is being transcribed, so a release waits for it. */
    @Volatile private var transcribing = false

    @Volatile private var decoder: RnntDecoder? = null
    @Volatile private var lastUseMs = 0L

    /** Parsed once; a device whose APK has no manifest can never be available. */
    private val manifest: SttManifest? by lazy {
        try {
            context.assets.open(SttManifest.ASSET_PATH).use {
                SttManifest.parse(String(it.readBytes()))
            }
        } catch (e: Exception) {
            Log.w(TAG, "no model manifest in assets: ${e.message}")
            null
        }
    }

    private fun ctxFile(): File? {
        val m = manifest ?: return null
        return File(File(context.filesDir, MODEL_DIR), "${m.ctxSha256}.bin")
    }

    /**
     * Cheap availability check: does NOT load the model.
     *
     * The hash is only computed when the size already matches, so the common
     * "blob absent" and "blob half-delivered" cases cost a stat, not a 231 MB
     * read. A mismatch NEVER triggers a regeneration: an on-device prepare takes
     * minutes here and cannot succeed at all when the SoC is what mismatched.
     */
    fun isAvailable(): Boolean {
        val m = manifest ?: return false
        val f = ctxFile() ?: return false
        if (!f.isFile) return false
        if (f.length() != m.ctxSizeBytes) {
            Log.w(TAG, "context binary size ${f.length()} != manifest ${m.ctxSizeBytes}")
            return false
        }
        val sha = try {
            Sha256.ofFile(f)
        } catch (e: Exception) {
            Log.w(TAG, "context binary unreadable: ${e.message}")
            return false
        }
        return m.matches(
            exists = true,
            sizeBytes = f.length(),
            sha256 = sha,
            deviceSocId = SOC_ID,
            runtimeQnnVersion = QNN_VERSION,
        )
    }

    /**
     * Load the encoder and the ONNX decoder if they are not resident.
     * Idempotent. Returns false when the model is unavailable or the load failed,
     * in which case the caller routes remotely.
     */
    fun ensureLoaded(): Boolean = synchronized(lock) {
        if (GigaAmNative.isLoaded() && decoder != null) return true
        if (!isAvailable()) return false
        val f = ctxFile() ?: return false
        return try {
            val t0 = SystemClock.elapsedRealtime()
            val ok = GigaAmNative.load(
                context.applicationInfo.nativeLibraryDir, f.absolutePath, loadMelFb()
            )
            if (!ok) {
                Log.w(TAG, "encoder init returned 0; local STT unavailable")
                return false
            }
            if (decoder == null) decoder = buildDecoder()
            Log.i(TAG, "stt loaded in ${SystemClock.elapsedRealtime() - t0}ms")
            true
        } catch (t: Throwable) {
            // Catch Throwable, not Exception: a 203 MB allocation on a 1.7 GB
            // device fails as OutOfMemoryError, and an optional feature must not
            // take the camera process with it.
            Log.w(TAG, "stt load failed: ${t.javaClass.simpleName}: ${t.message}")
            releaseLocked("load failed")
            false
        }
    }

    /**
     * Release the model. A transcribe already in flight completes first: the
     * whole method is under the same monitor the transcribe holds.
     */
    fun release(reason: String) = synchronized(lock) { releaseLocked(reason) }

    private fun releaseLocked(reason: String) {
        if (!GigaAmNative.isLoaded() && decoder == null) return
        Log.i(TAG, "releasing stt: $reason")
        try { decoder?.close() } catch (_: Throwable) {}
        decoder = null
        try { GigaAmNative.close() } catch (_: Throwable) {}
    }

    /** True once the model has been idle past [IDLE_UNLOAD_MS]. */
    fun isIdlePastUnloadWindow(nowMs: Long = SystemClock.elapsedRealtime()): Boolean =
        GigaAmNative.isLoaded() && !transcribing && lastUseMs != 0L &&
            nowMs - lastUseMs >= IDLE_UNLOAD_MS

    /** Drop the model if it has gone idle. Called from the capture worker tick. */
    fun unloadIfIdle() {
        if (isIdlePastUnloadWindow()) release("idle ${IDLE_UNLOAD_MS}ms")
    }

    /**
     * Transcribe ONE utterance. See ICapture.transcribeUtterance for the contract.
     *
     * @return the transcript, "" for an explicit empty final (the caller's cancel
     *   signal), or null when local STT is unavailable and the caller must fall
     *   back to the remote transcriber.
     */
    fun transcribe(pcm16leMono16k: ByteArray?, lang: String?, utteranceId: Long): String? {
        if (pcm16leMono16k == null) return null
        if (!isRussian(lang)) {
            Log.i(TAG, "utt=$utteranceId lang=$lang not supported locally")
            return null
        }
        if (UtteranceChunker.isTooLarge(pcm16leMono16k.size)) {
            // Refused, not truncated: truncating would silently drop the end of
            // what the user said and hand back a confident partial sentence.
            Log.w(TAG, "utt=$utteranceId payload ${pcm16leMono16k.size}B over limit")
            return null
        }
        val windows = UtteranceChunker.windows(pcm16leMono16k.size)
        if (windows.isEmpty()) return ""

        synchronized(lock) {
            if (!ensureLoaded()) return null
            val dec = decoder ?: return null
            transcribing = true
            val wl = acquireWakeLock()
            try {
                val t0 = SystemClock.elapsedRealtime()
                val texts = ArrayList<String?>(windows.size)
                for ((start, end) in windows) {
                    texts += encodeAndDecodeWindow(dec, pcm16leMono16k, start, end)
                }
                val out = UtteranceChunker.bankOrNull(texts)
                Log.i(
                    TAG,
                    "utt=$utteranceId windows=${windows.size} " +
                        "ms=${SystemClock.elapsedRealtime() - t0} " +
                        (if (out == null) "FAILED" else "chars=${out.length}")
                )
                return out
            } catch (t: Throwable) {
                Log.w(TAG, "utt=$utteranceId transcribe failed: ${t.message}")
                return null
            } finally {
                // Released here, not at service scope: a service-lifetime wake
                // lock would stop the SoC ever sleeping.
                try { wl?.release() } catch (_: Throwable) {}
                transcribing = false
                lastUseMs = SystemClock.elapsedRealtime()
            }
        }
    }

    /** @return the window's text, or null when the encode failed (poisons the utterance). */
    private fun encodeAndDecodeWindow(
        dec: RnntDecoder, pcm: ByteArray, start: Int, end: Int,
    ): String? {
        val samples = (end - start) / UtteranceChunker.BYTES_PER_SAMPLE
        val buf = ByteBuffer.wrap(pcm, start, end - start).order(ByteOrder.LITTLE_ENDIAN)
        val f = FloatArray(samples)
        for (i in 0 until samples) f[i] = buf.short / 32768f
        val enc = GigaAmNative.encode(f) ?: return null
        // The native contract is float[768*125 + 3]: encoded, then encodedLen,
        // featMs, execMs. A short array means the JNI contract drifted; treat it
        // as a failure rather than reading past the end.
        val expect = RnntDecoder.ENC_DIM * RnntDecoder.ENC_FRAMES
        if (enc.size < expect + 3) return null
        val encodedLen = enc[expect].toInt()
        if (encodedLen <= 0) return ""
        return dec.decode(enc, encodedLen)
    }

    private fun loadMelFb(): FloatArray {
        val bytes = context.assets.open(ASSET_MELFB).use { it.readBytes() }
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }

    private fun buildDecoder(): RnntDecoder {
        val dir = File(context.cacheDir, "gigaam").apply { mkdirs() }
        val decFile = extractAsset(ASSET_DECODER, File(dir, "decoder.onnx"))
        val jointFile = extractAsset(ASSET_JOINT, File(dir, "joint.onnx"))
        return RnntDecoder(decFile, jointFile, loadVocab())
    }

    private fun extractAsset(asset: String, dest: File): File {
        if (dest.isFile && dest.length() > 0L) return dest
        context.assets.open(asset).use { ins ->
            dest.outputStream().use { outs -> ins.copyTo(outs) }
        }
        return dest
    }

    private fun loadVocab(): Array<String> {
        val json = context.assets.open(ASSET_VOCAB).use { String(it.readBytes()) }
        val arr = org.json.JSONArray(json)
        return Array(arr.length()) { arr.getString(it) }
    }

    private fun acquireWakeLock(): PowerManager.WakeLock? = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "glasses:stt").apply {
            setReferenceCounted(false)
            // Bounded so a wedged encode cannot pin the SoC awake indefinitely.
            acquire(30_000L)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "wake lock unavailable: ${t.message}")
        null
    }
}
