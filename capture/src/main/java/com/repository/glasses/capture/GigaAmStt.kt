package com.repository.glasses.capture

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.concurrent.withLock

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

        /**
         * Keep the model mapped for the life of the process.
         *
         * The load is ~21 s of QNN graph deserialisation and NPU programming --
         * not I/O, so it cannot be shortened, only amortised. With idle eviction
         * the model was cold for nearly every utterance and the wearer waited
         * every time. Resident costs ~235 MB in a 1.7 GB device, which is the
         * accepted trade; if lmkd takes the process the next call reloads.
         */
        const val KEEP_RESIDENT_DEFAULT = true

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

    /**
     * Guards load / transcribe / release. A ReentrantLock rather than a
     * `synchronized` block because callers need tryLock: a 21 s cold load or a
     * 3.4 s transcribe holds this, and both the Binder threads and the main
     * thread must be able to decline rather than block on it.
     */
    private val lock = java.util.concurrent.locks.ReentrantLock()

    /** Set while an utterance is being transcribed, so a release waits for it. */
    @Volatile private var transcribing = false

    @Volatile private var decoder: RnntDecoder? = null
    @Volatile private var lastUseMs = 0L

    /** See [KEEP_RESIDENT_DEFAULT]. Settable so a test can exercise eviction. */
    @Volatile var keepResident: Boolean = KEEP_RESIDENT_DEFAULT

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
     * Identity of the blob whose hash we last verified: size and mtime. Hashing
     * 231 MB takes seconds, and isAvailable() is called from a synchronous Binder
     * thread on every session, so the result is cached against this and only
     * recomputed when the file underneath actually changes.
     */
    @Volatile private var verifiedStamp: Pair<Long, Long>? = null
    @Volatile private var verifiedResult = false

    /**
     * Availability check. Does NOT load the model, and does not re-hash a blob it
     * has already verified.
     *
     * Order matters and is cheapest-first: absent, then wrong size, then (only if
     * both pass and the file is not already verified) the 231 MB hash. A mismatch
     * NEVER triggers a regeneration -- an on-device prepare takes minutes here and
     * cannot succeed at all when the SoC is what mismatched.
     */
    fun isAvailable(): Boolean {
        // Availability is the single most common reason the router picks REMOTE,
        // and every failure below used to be either silent or indistinguishable
        // from the others. Each now names the exact thing that was wrong.
        val m = manifest
        if (m == null) {
            SttTrace.w("isAvailable=false: no model manifest in assets")
            return false
        }
        val f = ctxFile()
        if (f == null) {
            SttTrace.w("isAvailable=false: no context-binary path (no external files dir)")
            return false
        }
        if (!f.isFile) {
            SttTrace.w("isAvailable=false: context binary missing at ${f.absolutePath}")
            return false
        }
        val size = f.length()
        if (size != m.ctxSizeBytes) {
            Log.w(TAG, "context binary size $size != manifest ${m.ctxSizeBytes}")
            SttTrace.w("isAvailable=false: context binary size=$size != manifest ${m.ctxSizeBytes}")
            return false
        }
        val stamp = size to f.lastModified()
        verifiedStamp?.let { if (it == stamp) return verifiedResult }
        val sha = try {
            Sha256.ofFile(f)
        } catch (e: Exception) {
            Log.w(TAG, "context binary unreadable: ${e.message}")
            return false
        }
        val ok = m.matches(
            exists = true,
            sizeBytes = size,
            sha256 = sha,
            deviceSocId = SOC_ID,
            runtimeQnnVersion = QNN_VERSION,
        )
        verifiedResult = ok
        verifiedStamp = stamp
        if (!ok) {
            Log.w(TAG, "context binary does not match the manifest; local STT off")
            // socId and QNN version are printed because a context binary is
            // built for one specific SoC and runtime: a mismatch here means the
            // wrong .bin shipped, not that the file is corrupt.
            SttTrace.w(
                "isAvailable=false: manifest mismatch (socId=$SOC_ID qnn=$QNN_VERSION sha=${sha.take(12)})"
            )
        } else {
            SttTrace.i("isAvailable=true: context binary verified (${size}B)")
        }
        return ok
    }

    /**
     * Load the encoder and the ONNX decoder if they are not resident.
     * Idempotent. Returns false when the model is unavailable or the load failed,
     * in which case the caller routes remotely.
     */
    fun ensureLoaded(): Boolean = lock.withLock {
        // "Resident" vs "cold" is the difference between a ~1 s utterance and a
        // ~21 s one, i.e. between a transcript and a Binder timeout. Whether the
        // model was already loaded is therefore the first thing to know when an
        // utterance times out.
        if (GigaAmNative.isLoaded() && decoder != null) {
            SttTrace.i("model RESIDENT (no load needed)")
            return true
        }
        if (!isAvailable()) return false
        val f = ctxFile() ?: return false
        return try {
            val t0 = SystemClock.elapsedRealtime()
            SttTrace.i("model COLD: load start from ${f.name}")
            val ok = GigaAmNative.load(
                context.applicationInfo.nativeLibraryDir, f.absolutePath, loadMelFb()
            )
            if (!ok) {
                Log.w(TAG, "encoder init returned 0; local STT unavailable")
                SttTrace.w("model load FAILED: encoder init returned 0 after ${SystemClock.elapsedRealtime() - t0}ms")
                return false
            }
            if (decoder == null) decoder = buildDecoder()
            Log.i(TAG, "stt loaded in ${SystemClock.elapsedRealtime() - t0}ms")
            SttTrace.i("model LOADED in ${SystemClock.elapsedRealtime() - t0}ms")
            true
        } catch (t: Throwable) {
            SttTrace.w("model load THREW ${t.javaClass.simpleName}: ${t.message}")
            // Catch Throwable, not Exception: a 203 MB allocation on a 1.7 GB
            // device fails as OutOfMemoryError, and an optional feature must not
            // take the camera process with it.
            Log.w(TAG, "stt load failed: ${t.javaClass.simpleName}: ${t.message}")
            releaseLocked("load failed")
            false
        }
    }

    /**
     * Release the model. A transcribe already in flight completes first: this
     * takes the same lock the transcribe holds.
     *
     * BLOCKS for as long as that transcribe runs (up to 3.4 s, or 21 s behind a
     * cold load), so it must never be called from the main thread. Use
     * [releaseIfIdle] there.
     */
    fun release(reason: String) = lock.withLock { releaseLocked(reason) }

    /**
     * Release only if the recogniser is not busy; otherwise do nothing and say so.
     *
     * This is the variant safe to call from the main thread (service teardown,
     * memory pressure). Blocking there on an in-flight 21 s cold load would ANR
     * the capture process and take the camera down with it -- for a feature that
     * is optional and whose memory the kernel will reclaim on process death
     * anyway.
     *
     * @return true when the model was released or was already gone.
     */
    fun releaseIfIdle(reason: String): Boolean {
        if (!lock.tryLock()) {
            Log.i(TAG, "release '$reason' skipped: transcribe in flight")
            return false
        }
        try { releaseLocked(reason) } finally { lock.unlock() }
        return true
    }

    private fun releaseLocked(reason: String) {
        if (!GigaAmNative.isLoaded() && decoder == null) return
        Log.i(TAG, "releasing stt: $reason")
        try { decoder?.close() } catch (_: Throwable) {}
        decoder = null
        try { GigaAmNative.close() } catch (_: Throwable) {}
    }

    /**
     * True once the model has been idle past [IDLE_UNLOAD_MS].
     *
     * Only consulted when [keepResident] is off. The wearer chose to keep the
     * model loaded: the 21 s is QNN deserialising the graph and programming the
     * NPU (reading the 231 MB file is 0.1 s -- it is page-cached at 1.9 GB/s), so
     * it cannot be made faster, only paid less often. Unloading on idle meant
     * nearly every utterance paid it.
     */
    fun isIdlePastUnloadWindow(nowMs: Long = SystemClock.elapsedRealtime()): Boolean =
        !keepResident && GigaAmNative.isLoaded() && !transcribing && lastUseMs != 0L &&
            nowMs - lastUseMs >= IDLE_UNLOAD_MS

    /**
     * Drop the model if it has gone idle. Driven by [startIdleReaper].
     *
     * The idle test is RE-CHECKED under the lock: testing it outside and then
     * blocking on the lock would let a transcribe start in between, and we would
     * unload a model that is about to be used -- turning a 1.1 s utterance into a
     * 21 s cold load.
     */
    fun unloadIfIdle() {
        if (!isIdlePastUnloadWindow()) return
        if (!lock.tryLock()) return
        try {
            if (isIdlePastUnloadWindow()) releaseLocked("idle ${IDLE_UNLOAD_MS}ms")
        } finally {
            lock.unlock()
        }
    }

    /**
     * Start the periodic idle check that actually enforces the residency policy.
     *
     * Without this the 203 MB stays resident until an explicit releaseStt or
     * process death, which on a 1.7 GB device is what gets the capture process
     * lmkd-killed. The tick is deliberately coarse (a third of the window): it is
     * a memory-reclaim timer, not a deadline.
     */
    fun startIdleReaper(scheduler: java.util.concurrent.ScheduledExecutorService) {
        val periodMs = IDLE_UNLOAD_MS / 3
        scheduler.scheduleWithFixedDelay({
            try { unloadIfIdle() } catch (t: Throwable) {
                Log.w(TAG, "idle reaper: ${t.message}")
            }
        }, periodMs, periodMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /**
     * Transcribe ONE utterance. See ICapture.transcribeUtterance for the contract.
     *
     * @return the transcript, "" for an explicit empty final (the caller's cancel
     *   signal), or null when local STT is unavailable and the caller must fall
     *   back to the remote transcriber.
     */
    fun transcribe(pcm16leMono16k: ByteArray?, lang: String?, utteranceId: Long): String? {
        val tEntry = SystemClock.elapsedRealtime()
        if (pcm16leMono16k == null) {
            SttTrace.w("u$utteranceId AIDL entry with null pcm; returning null")
            return null
        }
        SttTrace.i("u$utteranceId AIDL entry bytes=${pcm16leMono16k.size} lang=$lang")
        if (!isRussian(lang)) {
            Log.i(TAG, "utt=$utteranceId lang=$lang not supported locally")
            SttTrace.i("u$utteranceId REFUSED: lang=$lang not supported locally -> null")
            return null
        }
        if (UtteranceChunker.isTooLarge(pcm16leMono16k.size)) {
            // Refused, not truncated: truncating would silently drop the end of
            // what the user said and hand back a confident partial sentence.
            Log.w(TAG, "utt=$utteranceId payload ${pcm16leMono16k.size}B over limit")
            SttTrace.w("u$utteranceId REFUSED: payload ${pcm16leMono16k.size}B over limit -> null")
            return null
        }
        // Availability is checked BEFORE the empty-payload shortcut: a device with
        // no model must answer "unavailable", not "the model heard nothing".
        // Answering "" there would be read as a deliberate cancel.
        if (!isAvailable()) {
            SttTrace.i("u$utteranceId REFUSED: model unavailable -> null (caller falls back to remote)")
            return null
        }
        val windows = UtteranceChunker.windows(pcm16leMono16k.size)
        if (windows.isEmpty()) {
            // "" not null: the model exists and simply had nothing to chew on.
            // The caller reads "" as the wearer cancelling, which is correct here.
            SttTrace.i("u$utteranceId no windows for ${pcm16leMono16k.size}B -> empty final (intentional)")
            return ""
        }

        // Busy-reject rather than queue. Each utterance holds the NPU for
        // 1-3.4 s (21 s on a cold load), and transcribeUtterance runs on a
        // Binder thread from a finite pool: queueing callers would pin threads
        // and, worse, deliver a transcript long after the session it belonged to
        // ended. The contract already allows null for "NPU busy".
        if (!lock.tryLock()) {
            Log.i(TAG, "utt=$utteranceId refused: recogniser busy")
            SttTrace.i("u$utteranceId REFUSED: recogniser busy (NPU held by another utterance) -> null")
            return null
        }
        try {
            val tLoad = SystemClock.elapsedRealtime()
            if (!ensureLoaded()) {
                SttTrace.w("u$utteranceId ensureLoaded failed after ${SystemClock.elapsedRealtime() - tLoad}ms -> null")
                return null
            }
            val loadMs = SystemClock.elapsedRealtime() - tLoad
            val dec = decoder ?: run {
                SttTrace.w("u$utteranceId decoder null after load -> null")
                return null
            }
            transcribing = true
            val wl = acquireWakeLock()
            try {
                val t0 = SystemClock.elapsedRealtime()
                val texts = ArrayList<String?>(windows.size)
                var encodeMsTotal = 0L
                var decodeMsTotal = 0L
                windows.forEachIndexed { i, (start, end) ->
                    val tw = SystemClock.elapsedRealtime()
                    val r = encodeAndDecodeWindow(dec, pcm16leMono16k, start, end)
                    // Per-window timing is what localises a hang: an utterance
                    // that dies inside window 2 of 3 is an encoder problem, one
                    // that never prints window 1 never reached the NPU at all.
                    SttTrace.i(
                        "u$utteranceId window ${i + 1}/${windows.size} " +
                            "ms=${SystemClock.elapsedRealtime() - tw} " +
                            (if (r == null) "FAILED" else "chars=${r.length}")
                    )
                    encodeMsTotal += lastEncodeMs
                    decodeMsTotal += lastDecodeMs
                    texts += r
                }
                val out = UtteranceChunker.bankOrNull(texts)
                val totalMs = SystemClock.elapsedRealtime() - t0
                Log.i(
                    TAG,
                    "utt=$utteranceId windows=${windows.size} " +
                        "ms=$totalMs " +
                        (if (out == null) "FAILED" else "chars=${out.length}")
                )
                SttTrace.i(
                    "u$utteranceId SUMMARY windows=${windows.size} loadMs=$loadMs " +
                        "encodeMs=$encodeMsTotal decodeMs=$decodeMsTotal inferMs=$totalMs " +
                        "aidlMs=${SystemClock.elapsedRealtime() - tEntry} " +
                        (if (out == null) "chars=0 outcome=fail" else "chars=${out.length} outcome=ok")
                )
                return out
            } catch (t: Throwable) {
                Log.w(TAG, "utt=$utteranceId transcribe failed: ${t.message}")
                SttTrace.w("u$utteranceId transcribe THREW ${t.javaClass.simpleName}: ${t.message} -> null")
                return null
            } finally {
                // Released here, not at service scope: a service-lifetime wake
                // lock would stop the SoC ever sleeping.
                try { wl?.release() } catch (_: Throwable) {}
                transcribing = false
                lastUseMs = SystemClock.elapsedRealtime()
            }
        } finally {
            lock.unlock()
        }
    }

    /**
     * Split encoder vs decoder time for the last window. Two very different
     * subsystems (Hexagon NPU vs the ONNX LSTM on CPU) whose costs a single
     * total would conflate -- and they fail for unrelated reasons.
     */
    @Volatile private var lastEncodeMs = 0L
    @Volatile private var lastDecodeMs = 0L

    /** @return the window's text, or null when the encode failed (poisons the utterance). */
    private fun encodeAndDecodeWindow(
        dec: RnntDecoder, pcm: ByteArray, start: Int, end: Int,
    ): String? {
        lastEncodeMs = 0L
        lastDecodeMs = 0L
        val samples = (end - start) / UtteranceChunker.BYTES_PER_SAMPLE
        val buf = ByteBuffer.wrap(pcm, start, end - start).order(ByteOrder.LITTLE_ENDIAN)
        val f = FloatArray(samples)
        for (i in 0 until samples) f[i] = buf.short / 32768f
        val tEnc = SystemClock.elapsedRealtime()
        val enc = GigaAmNative.encode(f)
        lastEncodeMs = SystemClock.elapsedRealtime() - tEnc
        if (enc == null) {
            SttTrace.w("encoder returned null after ${lastEncodeMs}ms (NPU encode failed)")
            return null
        }
        // The native contract is float[768*125 + 3]: encoded, then encodedLen,
        // featMs, execMs. A short array means the JNI contract drifted; treat it
        // as a failure rather than reading past the end.
        val expect = RnntDecoder.ENC_DIM * RnntDecoder.ENC_FRAMES
        if (enc.size < expect + 3) {
            SttTrace.w("encoder returned ${enc.size} floats, expected >= ${expect + 3}; JNI contract drifted")
            return null
        }
        val encodedLen = enc[expect].toInt()
        // A non-positive encodedLen is a native ANOMALY, not "the model heard
        // nothing": the encoder always emits frames for a non-empty window.
        // Returning "" would bank an empty final, which the phone reads as a
        // deliberate CANCEL -- silently swallowing what the user said. Failing to
        // null sends the utterance to the remote transcriber instead.
        if (encodedLen <= 0) {
            Log.w(TAG, "encoder returned encodedLen=$encodedLen; treating as failure")
            SttTrace.w("encoder returned encodedLen=$encodedLen after ${lastEncodeMs}ms; treating as failure -> null")
            return null
        }
        val tDec = SystemClock.elapsedRealtime()
        val text = dec.decode(enc, encodedLen)
        lastDecodeMs = SystemClock.elapsedRealtime() - tDec
        SttTrace.i(
            "encode ${lastEncodeMs}ms (encodedLen=$encodedLen featMs=${enc[expect + 1].toInt()} " +
                "execMs=${enc[expect + 2].toInt()}) decode ${lastDecodeMs}ms chars=${text.length}"
        )
        return text
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
