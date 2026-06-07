package com.repository.glasses.listener.wakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import com.repository.glasses.listener.capture.MicBus
import com.repository.glasses.listener.capture.MicSubscriber
import com.repository.glasses.tracing.GT
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * On-glasses wake-word detector.
 *
 * Port of the phone-side WakeWordDetector (phone
 * AI/clients/phone/app/src/main/java/com/repository/listener/audio/WakeWordDetector.kt) +
 * OpenWakeWordEngine.kt + VadEngine.kt, preserving:
 *
 *   - silero VAD gating with VAD_HANGOVER_MS
 *   - mel (32x76) -> embedding (96) -> sireneviy classifier chain
 *   - 16-frame embedding circular buffer
 *   - OWW sliding-window scoring (3 frames, 1 hit required), sigmoid threshold 0.5
 *   - 1500 ms wake cooldown
 *   - RMS gate at 0.002 to skip silence
 *
 * Thresholds/shapes MUST track the phone. See phone file:lines cited in the companion
 * constants below.
 *
 * ORT execution provider: onnxruntime-android-qnn 1.20.0 with the QNN EP (HTP backend)
 * requested first; falls back to CPU if registration throws.
 *
 * Subscribes to MicBus. Each PCM frame handoff is dispatched to a single-thread executor
 * so the mic thread never blocks on ONNX inference.
 *
 * On a hit the pipeline fires a sendBroadcast with action [ACTION_WAKE_WORD_HIT]; the
 * wiring into ListenerService.enterLiveUtteranceMode happens in a later step (a receiver
 * is not registered here).
 *
 * Native ACD path (Task 5):
 *   When /vendor/lib64/libpalclient.so is dlopen'able and
 *   /vendor/etc/models/acd/speech.eai is present, [start] arms a PAL ACD
 *   stream via [AcdNativeDetector] (JNI). PAL runs the ACD engine on the
 *   SoC DSP at LPI; the AP only sees a callback when SPEECH is detected,
 *   so audioserver can drop the AudioIn wakelock during silence. On a
 *   detection we open a confirm window: subscribe to MicBus, accumulate
 *   ~2 s of PCM, run the OWW chain ONCE, and fire the wake broadcast only
 *   if the actual phrase is confirmed. Outside the confirm window MicBus
 *   is NOT subscribed.
 *
 *   The native path is a pure optimisation -- every failure (no PAL client,
 *   speech.eai missing, ACD open fails) falls back to the ARM-ONNX path with
 *   no observable behaviour change. The decision is made once at [start];
 *   no per-frame switching.
 *
 *   Replaced (Task 5): the previous SoundTriggerManager + sthal HAL reflection
 *   probe was retired in favour of [AcdNativeDetector], a JNI shim around
 *   /vendor/lib64/libpalclient.so. ACD runs on the SoC DSP via PAL, so the AP
 *   can release AudioRecord while ACD is armed -- audioserver drops the
 *   AudioIn wakelock during silence. On a SPEECH detection ACD fires a
 *   callback; we then run the OWW chain ONCE on a freshly-captured 2 s LAB
 *   from MicBus to confirm the actual wake phrase.
 */
class WakeWordPipeline(
    private val context: Context,
    /**
     * Native ACD (DSP-side VAD via PAL_STREAM_ACD). Default OFF on this build:
     * the pipeline is correctly wired and PAL accepts every set_param call,
     * but mic capture never engages because gpio6 is held by lpi_tdm1_pinctrl
     * from boot, blocking cdc_dmic01_pinctrl from powering up DMIC1. Fixing
     * requires a DTS patch (kernel/dtbo reflash) to drop tdm1's claim on
     * gpio6. Until then, ARM-ONNX always-on stays the working default. Flip
     * to true (with `setprop debug.glasses.ww.force_start 1` if no companion
     * phone RFCOMM peer is connected) for ACD-path testing once DTS is
     * patched.
     */
    private val useNativeAcd: Boolean = true,
) : MicSubscriber {

    companion object {
        private const val TAG = "WakeWordPipeline"

        // Broadcast contract -- consumed in a later wiring step by ListenerService.
        const val ACTION_WAKE_WORD_HIT = "com.repository.glasses.listener.ACTION_WAKE_WORD_HIT"
        const val ACTION_WAKE_WORD_TEST = "com.repository.glasses.listener.ACTION_WAKE_WORD_TEST"
        const val EXTRA_CONFIDENCE = "confidence"
        const val EXTRA_EPOCH_NANOS = "epochNanos"
        const val EXTRA_PCM_FILE_PATH = "pcm_file_path"

        // Asset file names -- present under
        // AI/clients/glasses/app/src/main/assets/ (copied from phone assets).
        private const val VAD_MODEL = "silero_vad.onnx"
        private const val MEL_MODEL = "melspectrogram.onnx"
        private const val EMB_MODEL = "embedding_model.onnx"
        private const val CLS_MODEL = "sireneviy.onnx"

        // ---- Constants mirrored from the phone reference. Do not "tune" in this pass. ----
        // Phone AppConfig.kt:85
        private const val VAD_WAKE_THRESHOLD = 0.40f
        // Phone AppConfig.kt:86 -- 1500 ms minimum between fires.
        private const val WAKE_COOLDOWN_MS = 1500L
        // Phone AppConfig.kt:69 -- sigmoid score threshold per frame.
        private const val OWW_THRESHOLD = 0.5f
        // Phone AppConfig.kt:70-71 -- need 1 hit within the last 3 scored frames.
        private const val OWW_WINDOW_SIZE = 3
        private const val OWW_REQUIRED_HITS = 1
        // Phone AppConfig.kt:80-81
        private const val ENABLE_RMS_GATE = true
        private const val RMS_THRESHOLD = 0.002f
        // Phone WakeWordDetector.kt:11 -- continue scoring 2s after VAD drops to cover
        // OWW's 1-2s classifier latency.
        private const val VAD_HANGOVER_MS = 2000L

        // VAD tensor shapes -- phone VadEngine.kt:15-16
        private const val VAD_STATE_DIM = 128
        private const val VAD_INPUT_DIM = 576

        // OWW tensor shapes -- phone OpenWakeWordEngine.kt:18-23
        private const val CHUNK_SIZE = 1280        // 80 ms at 16 kHz
        private const val MEL_BINS = 32
        private const val MEL_WINDOW = 76
        private const val EMBEDDING_DIM = 96
        private const val N_FRAMES = 16
        private const val MAX_MEL_BUFFER = 200

        // Verbose score-trace threshold mirrored from phone OpenWakeWordEngine.kt:152
        private const val SCORE_VERBOSE_THRESHOLD = 0.01f
        // Phone WakeWordDetector.kt:129 -- only start accumulating hits above 0.1.
        private const val SCORE_ACCUMULATE_THRESHOLD = 0.1f
    }

    // -------------------------- State --------------------------
    private val running = AtomicBoolean(false)
    private var env: OrtEnvironment? = null
    private var vadSession: OrtSession? = null
    private var melSession: OrtSession? = null
    private var embSession: OrtSession? = null
    private var clsSession: OrtSession? = null
    private var qnnActive: Boolean = false

    // Single-thread infer executor. Nulled out in stop() so a future onPcmFrame /
    // injectPcmFile that races with shutdown exits early instead of resurrecting the pool.
    @Volatile private var executor: ExecutorService? = newInferExecutor()

    private fun newInferExecutor(): ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "WakeWord-Infer").apply { isDaemon = true }
    }

    // VAD LSTM state (phone VadEngine.kt:23-24)
    private var vadH = FloatArray(VAD_STATE_DIM)
    private var vadC = FloatArray(VAD_STATE_DIM)
    // Ring buffer for VAD samples. Sized for ~2.3 s of 16 kHz audio so callers
    // handing us multi-second batches don't overflow.
    private val vadRing = FloatArray(VAD_INPUT_DIM * 64)
    private var vadRingWritePos = 0  // next write index
    private var vadRingFillCount = 0 // unread samples

    // OWW pipeline state
    private val audioAccumulator = ShortArray(CHUNK_SIZE)
    private var audioAccumPos = 0
    private val melBuffer = ArrayList<FloatArray>(MAX_MEL_BUFFER)
    private val embBuffer = ArrayList<FloatArray>(N_FRAMES)
    private var lastScore = 0f

    // Scoring state -- phone WakeWordDetector.kt:36-43
    private var lastDetectionTime = 0L
    private val scoreHistory = ArrayList<Boolean>(OWW_WINDOW_SIZE)
    private var vadSpeechActive = false
    private var vadLastSpeechTime = 0L

    // Diagnostics
    private val hitCount = AtomicLong(0)
    private val lastHitEpochNanos = AtomicLong(0)

    // VAD backend selection. Decided ONCE at start() based on
    // `setprop debug.glasses.vad.use_hbl 1` AND HblVadDetector.available. When
    // active, the silero ONNX VAD is bypassed and HblVad classifies each
    // 1280-sample CHUNK_SIZE chunk via 4 sub-frames of 320 samples (20 ms @
    // 16 kHz, mode 6). Same VAD_HANGOVER_MS semantics as silero.
    @Volatile private var hblVad: HblVadDetector? = null
    @Volatile private var useHblVad: Boolean = false

    // Native ACD path. acdActive=true means [start] armed PAL ACD on the DSP
    // and the ARM-ONNX always-on chain is NOT subscribed to MicBus -- the AP
    // can drop the AudioIn wakelock. On a SPEECH callback we open a confirm
    // window: subscribe to MicBus, accumulate ~2 s of PCM, run the OWW chain
    // once, then unsubscribe again.
    private val acdActive = AtomicBoolean(false)
    private var acdDetector: AcdNativeDetector? = null
    private var svaDetector: SvaSoundTriggerDetector? = null

    // Confirm-window state. Set by onAcdSpeech; cleared after the OWW one-shot
    // completes (whether or not it fired). Guards onPcmFrame against running
    // OWW when no ACD trigger is in flight.
    private val acdConfirmActive = AtomicBoolean(false)
    private var acdConfirmStartNanos = 0L
    // 2 s @ 16 kHz mono = 32000 samples. Phone OWW needs ~16 frames * 80 ms
    // chunks = 1.28 s minimum to score; 2 s gives one full classifier sweep
    // after the trigger lands.
    private val acdConfirmSamples = ShortArray(32_000)
    private var acdConfirmPos = 0
    // Hard cap so a stuck confirm doesn't keep MicBus pinned forever.
    private val acdConfirmTimeoutNanos = 3_000_000_000L

    // Dedicated single-thread executor for ACD-callback fan-out. The PAL
    // callback fires on a vendor thread (attached to the JVM via the JNI
    // shim); we must not run ONNX inference there. Marshal to this executor
    // instead. Paired with [executor] above but separate: the confirm-window
    // path can race with the always-on ONNX init on first start().
    @Volatile private var acdCallbackExecutor: ExecutorService? = null

    private fun newAcdCallbackExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "WakeWord-AcdCb").apply { isDaemon = true }
        }

    // -------------------------- Public API --------------------------

    fun start() {
        if (!running.compareAndSet(false, true)) {
            Log.i(TAG, "start() ignored -- already running")
            return
        }

        // Step 1 -- try the native ACD path. If the SoC DSP can host the
        // PAL ACD stream, the AP only sees a callback when SPEECH is detected
        // and audioserver can power-collapse the AudioIn wakelock during
        // silence. We still need ONNX sessions loaded for the post-trigger
        // OWW confirmation, but we do NOT subscribe to MicBus -- that happens
        // only inside the confirm window opened by onAcdSpeech.
        val acdArmed = useNativeAcd && tryStartNativeAcd()

        // Step 2 -- ONNX session init. Required by both paths:
        //   - ACD path: confirm-window OWW one-shot.
        //   - Fallback: full always-on chain.
        if (executor == null) executor = newInferExecutor()

        // Decide VAD backend ONCE per session. debug.glasses.vad.use_hbl=1
        // selects HblVad (low-power classical DSP); default keeps silero.
        // If the prop is set but HblVadDetector fails to load / create, log
        // a warning and fall through to silero rather than failing start().
        useHblVad = false
        hblVad = null
        if (readSysPropFlag("debug.glasses.vad.use_hbl")) {
            try {
                HblVadDetector.configure(context.filesDir.absolutePath)
            } catch (t: Throwable) {
                Log.w(TAG, "HblVadDetector.configure threw: ${t.message}")
            }
            val det = try { HblVadDetector(mode = 6) } catch (t: Throwable) {
                Log.w(TAG, "HblVadDetector ctor threw: ${t.message}")
                null
            }
            if (det != null && det.available) {
                hblVad = det
                useHblVad = true
                Log.i(TAG, "VAD: hbl (low-power classical DSP, librokid_agc.so)")
            } else {
                det?.close()
                Log.w(TAG, "VAD: hbl requested but unavailable, falling back to silero")
            }
        }
        if (!useHblVad) Log.i(TAG, "VAD: silero")

        Log.i(TAG, "start() loading ONNX sessions (acdArmed=$acdArmed, vad=${if (useHblVad) "hbl" else "silero"})")
        executor?.execute {
            try {
                initSessions()
                if (!acdArmed) {
                    // Fallback: subscribe to MicBus for the always-on ARM-ONNX chain.
                    MicBus.subscribe(this)
                    Log.i(TAG, "start() complete (qnn=$qnnActive, ARM-ONNX always-on)")
                } else {
                    Log.i(TAG, "start() complete (qnn=$qnnActive, ACD-gated; AudioIn idle)")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "start() failed: ${t.message}", t)
                running.set(false)
                releaseSessionsLocked()
                // Tear down the executor we just created so the next start()
                // builds a fresh one instead of leaking this thread alongside
                // a brand-new pool. shutdown() (not shutdownNow + await) lets
                // this catch block return cleanly, then the worker terminates.
                executor?.shutdown()
                executor = null
            }
        }
    }

    /**
     * Probe + arm the native PAL ACD detector. Returns true if acdActive was
     * set -- the caller must then skip MicBus subscription. Returns false on
     * any failure (libpalclient.so missing, speech.eai missing, pal_stream_open
     * rejected). No partial state is left behind on false.
     */
    private fun tryStartNativeAcd(): Boolean {
        // Native ACD (PAL_STREAM_ACD with QC_ACD vendor UUID + speech.eai).
        // Stock QTI HAL (extracted from yodaos-stock-full super_6.img) and
        // /vendor/etc/resourcemanager_neo_idp.xml acd_platform_info wire this
        // up. SPEECH context 0x08001335 fires when the LPAI Hexagon model
        // detects ambient speech.
        if (!AcdNativeDetector.isAvailable()) {
            Log.i(TAG, "Native ACD unavailable, using ARM-ONNX always-on (libpalclient or speech.eai missing)")
            return false
        }
        val detector = AcdNativeDetector()
        val err = detector.start { epochNanos -> onAcdSpeech(epochNanos) }
        if (err != null) {
            Log.i(TAG, "Native ACD unavailable, using ARM-ONNX always-on ($err)")
            detector.stop()
            return false
        }
        if (acdCallbackExecutor == null) acdCallbackExecutor = newAcdCallbackExecutor()
        acdDetector = detector
        acdActive.set(true)
        return true
    }

    /**
     * Apply the same shutdown pattern as [com.repository.glasses.listener.capture.LocalOpusWriter.stop]:
     * submit the session-release as the last task, shutdown(), awaitTermination(3s),
     * shutdownNow() on timeout. Null the executor reference so any racing onPcmFrame /
     * injectPcmFile exits early instead of resurrecting the pool.
     */
    fun stop() {
        // Debug override: setprop debug.glasses.acd.never_stop 1 keeps the ACD
        // detector armed indefinitely for live speech-detection bring-up. Read
        // fresh on every call so toggling the prop at runtime takes effect.
        try {
            val sp = Class.forName("android.os.SystemProperties")
            val get = sp.getMethod("get", String::class.java, String::class.java)
            val v = get.invoke(null, "debug.glasses.acd.never_stop", "0") as String
            if (v == "1" || v.equals("true", ignoreCase = true)) {
                Log.i(TAG, "stop() bypassed -- debug.glasses.acd.never_stop=1 (ACD stays armed)")
                return
            }
        } catch (t: Throwable) {
            Log.w(TAG, "stop() never_stop probe threw: ${t.message}")
        }
        if (!running.compareAndSet(true, false)) return
        Log.i(TAG, "stop() unsubscribing and releasing sessions")

        // Tear down the ACD detector if it was armed. The ACD path replaces
        // the always-on ARM-ONNX subscription, but ONNX sessions are still
        // loaded for confirm-window inference -- always fall through.
        if (acdActive.compareAndSet(true, false)) {
            try {
                svaDetector?.stop()
            } catch (t: Throwable) {
                Log.w(TAG, "SVA stop threw: ${t.message}")
            }
            try {
                acdDetector?.stop()
            } catch (t: Throwable) {
                Log.w(TAG, "ACD stop threw: ${t.message}")
            }
            svaDetector = null
            acdDetector = null
            acdConfirmActive.set(false)
            acdConfirmPos = 0
            val acdExec = acdCallbackExecutor
            if (acdExec != null) {
                acdExec.shutdown()
                try {
                    if (!acdExec.awaitTermination(3, TimeUnit.SECONDS)) {
                        Log.w(TAG, "stop() acdCallbackExecutor did not terminate within 3s -- forcing shutdownNow()")
                        acdExec.shutdownNow()
                    }
                } catch (_: InterruptedException) {
                    acdExec.shutdownNow()
                    Thread.currentThread().interrupt()
                }
                acdCallbackExecutor = null
            }
        }

        MicBus.unsubscribe(this)

        // Release the HblVad native handle if one was allocated this session.
        try { hblVad?.close() } catch (t: Throwable) {
            Log.w(TAG, "HblVad close threw: ${t.message}")
        }
        hblVad = null
        useHblVad = false

        val exec = executor ?: return
        exec.execute { releaseSessionsLocked() }
        exec.shutdown()
        try {
            if (!exec.awaitTermination(3, TimeUnit.SECONDS)) {
                Log.w(TAG, "stop() executor did not terminate within 3s -- forcing shutdownNow()")
                exec.shutdownNow()
            }
        } catch (_: InterruptedException) {
            exec.shutdownNow()
            Thread.currentThread().interrupt()
        }
        // Null AFTER awaitTermination/shutdownNow so a racing start() either sees
        // the still-draining executor or the null -- never a running==false +
        // executor==null transient that might let a caller enter with inconsistent state.
        executor = null
    }

    /**
     * Callback from [AcdNativeDetector] on a DSP-fired SPEECH detection. Runs
     * on a JVM-attached PAL callback thread -- must not block. ACD only tells
     * us "voice is present"; we still need to confirm the actual wake phrase,
     * so we open a confirm window: subscribe to MicBus, accumulate ~2 s of
     * PCM, run the OWW chain once, and either fire the wake broadcast or
     * close the window silently.
     *
     * Re-arming: PAL ACD with multi-trigger keeps firing on subsequent speech
     * onsets; we reset the buffer at the start of every confirm window.
     */
    private fun onAcdSpeech(epochNanos: Long) {
        if (!running.get() || !acdActive.get()) return
        val exec = acdCallbackExecutor ?: return
        try {
            exec.execute {
                if (!running.get() || !acdActive.get()) return@execute
                if (!acdConfirmActive.compareAndSet(false, true)) {
                    // Already confirming -- ignore the duplicate trigger.
                    return@execute
                }
                acdConfirmStartNanos = System.nanoTime()
                acdConfirmPos = 0
                Log.i(TAG, "ACD SPEECH detected, opening confirm window epochNanos=$epochNanos")
                // Reset detector state so the OWW chain starts fresh for this
                // window; no stale embeddings from the last confirmation.
                val infer = executor
                if (infer != null) {
                    try { infer.execute { resetDetectorState() } }
                    catch (_: java.util.concurrent.RejectedExecutionException) {}
                }
                try {
                    MicBus.subscribe(this)
                } catch (t: Throwable) {
                    Log.w(TAG, "MicBus.subscribe in confirm window failed: ${t.message}")
                    acdConfirmActive.set(false)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Executor shut down between null-check and submit; drop.
        }
    }

    /**
     * Close the confirm window: unsubscribe from MicBus, clear flags. Called
     * when the OWW chain has either fired the wake broadcast or exhausted
     * its confirmation budget without a hit.
     */
    private fun closeConfirmWindow() {
        if (!acdConfirmActive.compareAndSet(true, false)) return
        try {
            MicBus.unsubscribe(this)
        } catch (t: Throwable) {
            Log.w(TAG, "MicBus.unsubscribe in confirm window failed: ${t.message}")
        }
        acdConfirmPos = 0
        Log.i(TAG, "ACD confirm window closed")
    }

    fun isRunning(): Boolean = running.get()

    /** Total wake-word fires since start(), for diagnostics. */
    fun getHitCount(): Long = hitCount.get()

    /** epochNanos of the most recent fire (0 if none yet). */
    fun getLastHitEpochNanos(): Long = lastHitEpochNanos.get()

    /** True if the QNN EP accepted registration. False if we fell back to CPU. */
    fun isQnnActive(): Boolean = qnnActive

    /**
     * True when the current session is gated by the native PAL ACD detector
     * rather than the always-on ARM-ONNX pipeline. Intended for diagnostics
     * and the ListenerService's wake-reason logging.
     */
    fun isNativeAcdActive(): Boolean = acdActive.get()

    // -------------------------- MicSubscriber --------------------------

    override fun onPcmFrame(pcmMono16k: ShortArray, offset: Int, length: Int, epochNanos: Long) {
        if (!running.get()) return
        val exec = executor ?: return
        // MicBus re-uses the array next frame; copy the slice before handing off.
        val copy = ShortArray(length)
        System.arraycopy(pcmMono16k, offset, copy, 0, length)

        // Two routes:
        //   - acdActive: confirm window opened by onAcdSpeech. Feed the OWW
        //     chain until either it fires or we hit the timeout / sample
        //     budget, then close the window so MicBus stops pinning AudioIn.
        //   - !acdActive: ARM-ONNX always-on. Feed every frame indefinitely.
        if (acdActive.get()) {
            if (!acdConfirmActive.get()) return
            try {
                exec.execute {
                    if (!acdConfirmActive.get()) return@execute
                    try {
                        processChunkInternal(copy, epochNanos)
                    } catch (t: Throwable) {
                        Log.e(TAG, "confirm-window infer exception: ${t.message}", t)
                    }
                    // Track samples + timeout. Cap at 2 s of audio OR 3 s wall
                    // clock, whichever first. lastDetectionTime gets bumped by
                    // processScore on a real wake hit, so we exit early in
                    // that case too.
                    acdConfirmPos += copy.size
                    val elapsed = System.nanoTime() - acdConfirmStartNanos
                    val sawHit = lastHitEpochNanos.get() >= acdConfirmStartNanos
                    if (sawHit || acdConfirmPos >= acdConfirmSamples.size ||
                        elapsed >= acdConfirmTimeoutNanos) {
                        closeConfirmWindow()
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // Racing with stop(); drop.
            }
        } else {
            try {
                exec.execute {
                    try {
                        processChunkInternal(copy, epochNanos)
                    } catch (t: Throwable) {
                        Log.e(TAG, "infer exception: ${t.message}", t)
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // Racing with stop(); safe to drop this frame.
            }
        }
    }

    override fun onStreamStart() {
        Log.i(TAG, "mic stream start -- resetting detector state")
        val exec = executor ?: return
        try {
            exec.execute { resetDetectorState() }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Racing with stop().
        }
    }

    override fun onStreamStop() {
        Log.i(TAG, "mic stream stop")
    }

    // -------------------------- Init / teardown --------------------------

    private fun initSessions() {
        val ortEnv = OrtEnvironment.getEnvironment()
        env = ortEnv

        // A single QNN-registration attempt: use a trial options block to avoid repeating
        // the log message four times. If it throws we drop QNN and go CPU-only for all
        // sessions.
        qnnActive = probeQnnRegistration()

        vadSession = ortEnv.createSession(readAsset(VAD_MODEL), buildSessionOptions())
        melSession = ortEnv.createSession(readAsset(MEL_MODEL), buildSessionOptions())
        embSession = ortEnv.createSession(readAsset(EMB_MODEL), buildSessionOptions())
        clsSession = ortEnv.createSession(readAsset(CLS_MODEL), buildSessionOptions())

        resetDetectorState()
        Log.i(TAG, "ONNX sessions ready (qnn=$qnnActive)")
    }

    /**
     * Returns true if QNN EP registration succeeded on a throwaway SessionOptions. Logs
     * the failure exactly once and returns false on any throw.
     */
    private fun probeQnnRegistration(): Boolean {
        val opts = OrtSession.SessionOptions()
        return try {
            opts.addQnn(qnnOptionsMap())
            true
        } catch (t: Throwable) {
            Log.w(TAG, "QNN EP unavailable, falling back to CPU: ${t.message}")
            false
        } finally {
            try { opts.close() } catch (_: Throwable) {}
        }
    }

    private fun qnnOptionsMap(): HashMap<String, String> {
        val qnnOpts = HashMap<String, String>()
        // Qualcomm QNN backend shared-object. libonnxruntime in the AAR dlopens it at
        // registration time from the default linker paths (/vendor/lib64, /system/lib64).
        qnnOpts["backend_path"] = "libQnnHtp.so"
        qnnOpts["profiling_level"] = "off"
        // Performance profile for always-on listening.
        qnnOpts["htp_performance_mode"] = "burst"
        return qnnOpts
    }

    private fun buildSessionOptions(): OrtSession.SessionOptions {
        val opts = OrtSession.SessionOptions()
        if (!qnnActive) return opts
        return try {
            opts.addQnn(qnnOptionsMap())
            opts
        } catch (t: Throwable) {
            // Should not happen -- probe passed but per-session addQnn failed. Fall back
            // to CPU for this session.
            Log.w(TAG, "per-session QNN addQnn failed: ${t.message}")
            try { opts.close() } catch (_: Throwable) {}
            OrtSession.SessionOptions()
        }
    }

    private fun releaseSessionsLocked() {
        try { clsSession?.close() } catch (_: Throwable) {}
        try { embSession?.close() } catch (_: Throwable) {}
        try { melSession?.close() } catch (_: Throwable) {}
        try { vadSession?.close() } catch (_: Throwable) {}
        clsSession = null
        embSession = null
        melSession = null
        vadSession = null
        // Do NOT close OrtEnvironment.getEnvironment() -- it is a process singleton shared
        // with other ONNX consumers (e.g. nightvision_unet.onnx). Let the runtime manage it.
        env = null
    }

    private fun readAsset(name: String): ByteArray =
        context.assets.open(name).use { it.readBytes() }

    private fun resetDetectorState() {
        vadH = FloatArray(VAD_STATE_DIM)
        vadC = FloatArray(VAD_STATE_DIM)
        vadRingWritePos = 0
        vadRingFillCount = 0
        audioAccumPos = 0
        melBuffer.clear()
        embBuffer.clear()
        lastScore = 0f
        scoreHistory.clear()
        vadSpeechActive = false
        vadLastSpeechTime = 0L
    }

    // -------------------------- Inference pipeline --------------------------

    /**
     * Mirrors phone WakeWordDetector.feedAudio + OpenWakeWordEngine.processChunk fused
     * into one path (no WAKE/CANCEL/TTS_INTERRUPT mode machine -- the glasses-side
     * pipeline only runs when the service wants listening).
     */
    private fun processChunkInternal(samples: ShortArray, epochNanos: Long) {
        if (samples.isEmpty()) return

        // RMS gate -- phone WakeWordDetector.kt:90-93
        val rms = calculateRms(samples)
        if (ENABLE_RMS_GATE && rms < RMS_THRESHOLD) return

        // VAD gate first -- phone WakeWordDetector.kt:100-121
        // Two backends, decided once at start():
        //   - hbl: 1280 samples -> 4 sub-frames of 320; OR the per-frame
        //     decisions; voiceLive iff any sub-frame fired.
        //   - silero: ONNX session, returns a probability; voiceLive iff
        //     prob > VAD_WAKE_THRESHOLD.
        val now = System.currentTimeMillis()
        val voiceLive: Boolean = if (useHblVad) {
            GT.section("wakeword.vad.hbl") { runHblVad(samples) }
        } else {
            val floatSamples = FloatArray(samples.size) { samples[it] / 32768f }
            val vadProb = GT.section("wakeword.vad") { runVad(floatSamples) }
            vadProb > VAD_WAKE_THRESHOLD
        }
        val voiceActive = voiceLive ||
                (vadSpeechActive && (now - vadLastSpeechTime) <= VAD_HANGOVER_MS)

        // Logging-only: keep the silero-style probability around for verbose
        // traces in the silero path. HblVad is binary; we log a 0/1 marker.
        if (voiceLive) {
            // no-op; vadSpeechActive transition is logged below
        }

        GT.counter("wakeword.vad_active", if (voiceActive) 1 else 0)

        if (!voiceActive) {
            // Drive the VAD state machine for the silence -> end transition.
            if (vadSpeechActive && (now - vadLastSpeechTime) > VAD_HANGOVER_MS) {
                vadSpeechActive = false
                scoreHistory.clear()
                Log.d(TAG, "VAD speech end")
            }
            // OWW chain only runs while VAD is active; embBuffer rebuild after silence
            // adds ~1.3s latency to first wake-word -- acceptable trade-off for standby battery.
            return
        }

        // Voice present (live or hangover): run heavy chain + score.
        val score = GT.section("wakeword.oww") { processOwwAudio(samples) }
        if (voiceLive) {
            vadLastSpeechTime = now
            if (!vadSpeechActive) {
                vadSpeechActive = true
                val backend = if (useHblVad) "hbl" else "silero"
                Log.d(TAG, "VAD speech start backend=$backend rms=${"%.4f".format(rms)}")
            }
        }
        processScore(score, epochNanos)
    }

    /** phone WakeWordDetector.kt:124-167 */
    private fun processScore(score: Float, epochNanos: Long) {
        if (score > SCORE_VERBOSE_THRESHOLD) {
            Log.d(TAG, "OWW score=${"%.4f".format(score)}")
        }
        if (score <= SCORE_ACCUMULATE_THRESHOLD) return

        scoreHistory.add(score >= OWW_THRESHOLD)
        while (scoreHistory.size > OWW_WINDOW_SIZE) scoreHistory.removeAt(0)
        val hits = scoreHistory.count { it }
        if (hits < OWW_REQUIRED_HITS) return

        val nowMs = System.currentTimeMillis()
        if (nowMs - lastDetectionTime < WAKE_COOLDOWN_MS) return

        Log.i(TAG, "WAKE WORD FIRED score=${"%.4f".format(score)} hits=$hits/$OWW_WINDOW_SIZE " +
                "qnn=$qnnActive epochNanos=$epochNanos")
        lastDetectionTime = nowMs
        scoreHistory.clear()
        // Deliberately do NOT clear embBuffer (phone WakeWordDetector.kt:162-163 comment:
        // rebuild takes 16 frames / ~1.3s and causes missed detections).
        hitCount.incrementAndGet()
        lastHitEpochNanos.set(epochNanos)

        try {
            val intent = Intent(ACTION_WAKE_WORD_HIT).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_CONFIDENCE, score)
                putExtra(EXTRA_EPOCH_NANOS, epochNanos)
            }
            context.sendBroadcast(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "sendBroadcast(ACTION_WAKE_WORD_HIT) failed: ${t.message}")
        }
    }

    // -------------------------- VAD (HblVad, low-power) --------------------------

    /**
     * Slice a CHUNK_SIZE-sample chunk into 4 sub-frames of 320 samples and OR
     * the per-frame HblVad decisions. Any sub-frame speech -> the chunk is
     * considered active. Maintains identical timing semantics to silero
     * (VAD_HANGOVER_MS still applied by the caller).
     *
     * If [samples] is not an exact multiple of FRAME_SAMPLES (320), the tail
     * remainder is dropped. CHUNK_SIZE=1280 divides cleanly so this is a
     * no-op in the live path; the safety guard only matters for the debug
     * inject path.
     */
    private fun runHblVad(samples: ShortArray): Boolean {
        val det = hblVad ?: return false
        val frame = ShortArray(HblVadDetector.FRAME_SAMPLES)
        var any = false
        var off = 0
        while (off + HblVadDetector.FRAME_SAMPLES <= samples.size) {
            System.arraycopy(samples, off, frame, 0, HblVadDetector.FRAME_SAMPLES)
            if (det.isSpeech(frame)) any = true
            off += HblVadDetector.FRAME_SAMPLES
        }
        return any
    }

    /**
     * Read a debug system property as a 0/1 flag. Mirrors the reflection
     * pattern used in ListenerService for `debug.glasses.ww.force_start` /
     * `debug.glasses.acd.never_stop`. Returns false on any reflection failure.
     */
    private fun readSysPropFlag(key: String): Boolean = try {
        val sp = Class.forName("android.os.SystemProperties")
        val get = sp.getMethod("get", String::class.java, String::class.java)
        val v = get.invoke(null, key, "0") as String
        v == "1" || v.equals("true", ignoreCase = true)
    } catch (t: Throwable) {
        Log.w(TAG, "readSysPropFlag($key) threw: ${t.message}")
        false
    }

    // -------------------------- VAD (silero) --------------------------

    /** Returns the last speech probability for this chunk. phone VadEngine.kt:38-54 */
    private fun runVad(samples: FloatArray): Float {
        val ortEnv = env ?: return 0f
        val session = vadSession ?: return 0f

        // Append samples into ring; drop the oldest if the caller hands us more
        // than the ring can hold (will only happen on pathologically large bursts).
        if (samples.size > vadRing.size) {
            Log.w(TAG, "VAD ring overrun, dropped ${samples.size - vadRing.size} samples (in=${samples.size} ring=${vadRing.size})")
        }
        for (s in samples) {
            vadRing[vadRingWritePos] = s
            vadRingWritePos = (vadRingWritePos + 1) % vadRing.size
            if (vadRingFillCount < vadRing.size) {
                vadRingFillCount++
            }
        }

        var lastProb = 0f
        val frame = FloatArray(VAD_INPUT_DIM)
        while (vadRingFillCount >= VAD_INPUT_DIM) {
            // Compute the read start index from the write head and the current fill.
            val readStart = (vadRingWritePos - vadRingFillCount + vadRing.size) % vadRing.size
            // Copy VAD_INPUT_DIM samples in one or two memcpys (depending on wrap).
            if (readStart + VAD_INPUT_DIM <= vadRing.size) {
                System.arraycopy(vadRing, readStart, frame, 0, VAD_INPUT_DIM)
            } else {
                val first = vadRing.size - readStart
                System.arraycopy(vadRing, readStart, frame, 0, first)
                System.arraycopy(vadRing, 0, frame, first, VAD_INPUT_DIM - first)
            }
            vadRingFillCount -= VAD_INPUT_DIM
            lastProb = GT.section("wakeword.vad.frame") { runVadInference(ortEnv, session, frame) }
        }
        return lastProb
    }

    private fun runVadInference(
        ortEnv: OrtEnvironment,
        session: OrtSession,
        frame: FloatArray,
    ): Float {
        var inputTensor: OnnxTensor? = null
        var hTensor: OnnxTensor? = null
        var cTensor: OnnxTensor? = null
        return try {
            inputTensor = OnnxTensor.createTensor(
                ortEnv, FloatBuffer.wrap(frame), longArrayOf(1, VAD_INPUT_DIM.toLong()),
            )
            hTensor = OnnxTensor.createTensor(
                ortEnv, FloatBuffer.wrap(vadH), longArrayOf(1, 1, VAD_STATE_DIM.toLong()),
            )
            cTensor = OnnxTensor.createTensor(
                ortEnv, FloatBuffer.wrap(vadC), longArrayOf(1, 1, VAD_STATE_DIM.toLong()),
            )
            val inputs = mapOf("input" to inputTensor, "h" to hTensor, "c" to cTensor)
            session.run(inputs).use { results ->
                val probTensor = results.get("speech_probs").get() as OnnxTensor
                val prob = probTensor.floatBuffer.get(0)
                (results.get("hn").get() as OnnxTensor).floatBuffer.get(vadH)
                (results.get("cn").get() as OnnxTensor).floatBuffer.get(vadC)
                prob
            }
        } catch (e: Exception) {
            Log.e(TAG, "VAD inference error: ${e.message}")
            0f
        } finally {
            try { inputTensor?.close() } catch (_: Throwable) {}
            try { hTensor?.close() } catch (_: Throwable) {}
            try { cTensor?.close() } catch (_: Throwable) {}
        }
    }

    // -------------------------- openWakeWord chain --------------------------

    /** phone OpenWakeWordEngine.kt:121-157 */
    private fun processOwwAudio(samples: ShortArray): Float {
        val ortEnv = env ?: return 0f
        val mel = melSession ?: return 0f
        val emb = embSession ?: return 0f
        val cls = clsSession ?: return 0f

        var srcPos = 0
        while (srcPos < samples.size) {
            val toCopy = minOf(samples.size - srcPos, CHUNK_SIZE - audioAccumPos)
            System.arraycopy(samples, srcPos, audioAccumulator, audioAccumPos, toCopy)
            audioAccumPos += toCopy
            srcPos += toCopy
            if (audioAccumPos < CHUNK_SIZE) continue
            audioAccumPos = 0

            val floatSamples = FloatArray(CHUNK_SIZE) { audioAccumulator[it].toFloat() / 32768f }
            val melFrames = GT.section("wakeword.mel") { runMelspectrogram(ortEnv, mel, floatSamples) } ?: continue
            for (frame in melFrames) melBuffer.add(frame)
            while (melBuffer.size > MAX_MEL_BUFFER) melBuffer.removeAt(0)
            if (melBuffer.size < MEL_WINDOW) continue

            val embedding = GT.section("wakeword.emb") { runEmbedding(ortEnv, emb) } ?: continue
            embBuffer.add(embedding)
            if (embBuffer.size > N_FRAMES) embBuffer.removeAt(0)
            if (embBuffer.size < N_FRAMES) continue

            lastScore = GT.section("wakeword.cls") { runClassifier(ortEnv, cls) }
        }
        return lastScore
    }

    /** phone OpenWakeWordEngine.kt:246-267 */
    private fun runMelspectrogram(
        ortEnv: OrtEnvironment,
        session: OrtSession,
        audio: FloatArray,
    ): List<FloatArray>? {
        var inputTensor: OnnxTensor? = null
        return try {
            inputTensor = OnnxTensor.createTensor(
                ortEnv, FloatBuffer.wrap(audio), longArrayOf(1, audio.size.toLong()),
            )
            session.run(mapOf("input" to inputTensor)).use { results ->
                val output = results.get("output").get() as OnnxTensor
                val shape = output.info.shape
                val nFrames = shape[2].toInt()
                val rawData = output.floatBuffer
                val frames = ArrayList<FloatArray>(nFrames)
                for (i in 0 until nFrames) {
                    val frame = FloatArray(MEL_BINS)
                    for (j in 0 until MEL_BINS) {
                        // Phone applies /10 + 2 bias to the raw mel output.
                        frame[j] = rawData.get(i * MEL_BINS + j) / 10f + 2f
                    }
                    frames.add(frame)
                }
                frames
            }
        } catch (e: Exception) {
            Log.e(TAG, "Mel inference error: ${e.message}")
            null
        } finally {
            try { inputTensor?.close() } catch (_: Throwable) {}
        }
    }

    /** phone OpenWakeWordEngine.kt:269-288 */
    private fun runEmbedding(ortEnv: OrtEnvironment, session: OrtSession): FloatArray? {
        var inputTensor: OnnxTensor? = null
        return try {
            val startIdx = melBuffer.size - MEL_WINDOW
            val inputData = FloatArray(MEL_WINDOW * MEL_BINS)
            for (i in 0 until MEL_WINDOW) {
                System.arraycopy(melBuffer[startIdx + i], 0, inputData, i * MEL_BINS, MEL_BINS)
            }
            inputTensor = OnnxTensor.createTensor(
                ortEnv, FloatBuffer.wrap(inputData),
                longArrayOf(1, MEL_WINDOW.toLong(), MEL_BINS.toLong(), 1),
            )
            session.run(mapOf("input_1" to inputTensor)).use { results ->
                val output = results.get("conv2d_19").get() as OnnxTensor
                val embedding = FloatArray(EMBEDDING_DIM)
                output.floatBuffer.get(embedding)
                embedding
            }
        } catch (e: Exception) {
            Log.e(TAG, "Embedding inference error: ${e.message}")
            null
        } finally {
            try { inputTensor?.close() } catch (_: Throwable) {}
        }
    }

    /** phone OpenWakeWordEngine.kt:290-307 */
    private fun runClassifier(ortEnv: OrtEnvironment, session: OrtSession): Float {
        var inputTensor: OnnxTensor? = null
        return try {
            val inputData = FloatArray(N_FRAMES * EMBEDDING_DIM)
            for (i in 0 until N_FRAMES) {
                System.arraycopy(embBuffer[i], 0, inputData, i * EMBEDDING_DIM, EMBEDDING_DIM)
            }
            inputTensor = OnnxTensor.createTensor(
                ortEnv, FloatBuffer.wrap(inputData),
                longArrayOf(1, N_FRAMES.toLong(), EMBEDDING_DIM.toLong()),
            )
            session.run(mapOf("input" to inputTensor)).use { results ->
                val output = results.get("output").get() as OnnxTensor
                output.floatBuffer.get(0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Classifier inference error: ${e.message}")
            0f
        } finally {
            try { inputTensor?.close() } catch (_: Throwable) {}
        }
    }

    // -------------------------- Utilities --------------------------

    private fun calculateRms(audio: ShortArray): Float {
        var sum = 0.0
        for (s in audio) {
            val n = s.toFloat() / 32768f
            sum += (n * n).toDouble()
        }
        return kotlin.math.sqrt(sum / audio.size).toFloat()
    }

    /**
     * Debug-only: pump a PCM_S16LE 16 kHz mono file through the pipeline as if it were
     * coming from MicBus. The file is consumed in CHUNK_SIZE-sample slices to preserve
     * the phone pipeline's look-ahead assumptions.
     *
     * A caller can trigger this by:
     *   adb shell am broadcast \
     *     -a com.repository.glasses.listener.ACTION_WAKE_WORD_TEST \
     *     --es pcm_file_path /sdcard/Download/test_wake.pcm
     * The receiver is registered only in a debuggable build -- see
     * `ListenerService.wakeWordTestReceiver` (gated by FLAG_DEBUGGABLE at service init).
     *
     * IMPORTANT: this is a debug-only diagnostic. Because the injected PCM is mixed
     * into the same serialized executor as the live mic stream (shared audioAccumulator,
     * audioAccumPos, melBuffer, embBuffer, scoreHistory, vadH/vadC), the injected run
     * WILL briefly corrupt live-stream detection state. The method calls
     * [resetDetectorState] both before and after the file run to minimize the damage
     * window, but do not rely on continuous live detection while an inject is in flight.
     */
    fun injectPcmFile(path: String) {
        if (!isDebuggable()) {
            Log.w(TAG, "injectPcmFile denied: not a debuggable build")
            return
        }
        if (!running.get()) {
            Log.w(TAG, "injectPcmFile denied: pipeline not running")
            return
        }
        // Ship-state: ONNX sessions are always loaded (see start()), even when
        // the HAL is armed-passive. File inject is safe in both modes.
        val exec = executor ?: return
        try {
            exec.execute {
                try {
                    // Reset detector state before the file run so the injected PCM doesn't
                    // partially extend a live-stream look-ahead window.
                    resetDetectorState()
                    val file = File(path)
                    if (!file.exists()) {
                        Log.w(TAG, "injectPcmFile: no such file $path")
                        return@execute
                    }
                    FileInputStream(file).use { fis ->
                        val buf = ByteArray(CHUNK_SIZE * 2)
                        val t0 = System.nanoTime()
                        while (true) {
                            val read = fis.read(buf)
                            if (read <= 0) break
                            val shorts = ShortArray(read / 2)
                            val bb = ByteBuffer.wrap(buf, 0, read).order(ByteOrder.LITTLE_ENDIAN)
                            for (i in shorts.indices) shorts[i] = bb.short
                            processChunkInternal(shorts, System.nanoTime())
                        }
                        Log.i(TAG, "injectPcmFile done ($path) in ${(System.nanoTime() - t0) / 1_000_000} ms")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "injectPcmFile error: ${t.message}", t)
                } finally {
                    // Reset again so the next live-stream chunk starts from clean state
                    // rather than the tail of the injected file.
                    resetDetectorState()
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Executor shut down between the null check and the submit; nothing to do.
        }
    }

    private fun isDebuggable(): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
