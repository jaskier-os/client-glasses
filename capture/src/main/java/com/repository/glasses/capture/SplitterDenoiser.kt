package com.repository.glasses.capture

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * SplitterNet (CVPR 2024 mobile denoising) TFLite wrapper. Trained on MIDD.
 * Fully convolutional, any H/W divisible by 16 works. Input/output are
 * NHWC float [0,1] RGB.
 *
 * On the glasses (~1.7GB RAM, A55 CPU), running the full 2016x1504 frame
 * at once overflows the process (>500MB activations -> LMK). We therefore
 * tile at [TILE]x[TILE] with [OVERLAP] px of reflected padding on every
 * inner edge, and keep only the inner valid region per tile. Seams end up
 * within the 16-px margin which the deep U-Net's receptive field covers.
 */
class SplitterDenoiser private constructor(
    private val context: Context,
    private val model: MappedByteBuffer,
) {

    /**
     * Denoise [src]. The TFLite interpreter (and its ~300MB tensor arena) is
     * created HERE and CLOSED in the finally below, so the arena is resident
     * ONLY for the duration of this call -- never while idle or during ReID
     * scanning (which does no denoising). On this 1.8GB device a permanently
     * held arena was the dominant cost that tipped the capture FGS over the
     * lowmemorykiller threshold. Re-creating the interpreter costs ~130ms,
     * negligible against the ~60s denoise and the ~300MB it would otherwise pin.
     */
    /**
     * Called between tiles so the caller can pause the denoise while a camera
     * capture is in flight.
     *
     * Without this the ~236MB arena + two full-res bitmaps can coexist with a
     * new capture's ~170MB on this 1.8GB device and lmkd kills the process
     * mid-photo. Yielding only BETWEEN stages was not enough: a denoise already
     * running when the shutter is pressed cannot be preempted, which is exactly
     * the rapid-double-press case. Per-tile is the finest granularity available
     * without splitting the graph.
     */
    @Volatile var pauseCheck: (() -> Unit)? = null

    fun denoise(src: Bitmap): Bitmap {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val w0 = src.width
        val h0 = src.height
        val w = (w0 / 16) * 16
        val h = (h0 / 16) * 16
        if (w <= 0 || h <= 0) {
            Log.w(TAG, "input too small for splitter ${w0}x${h0}, returning raw")
            return src
        }

        // DEFAULT path: Hexagon V73 NPU via QNN-HTP. Hardware-accelerated, off-CPU
        // (the "GPU"/accelerator the product wants -- the Adreno GLES delegate
        // genuinely can't run this graph; the NPU is the device's real off-CPU
        // path). On ANY init failure we fall through to the CPU path below so
        // denoise never hard-fails. Engine selectable via a reboot-scoped prop:
        //   adb shell setprop debug.glasses.capture.denoise_engine npu|cpu
        val engine = engineProp()
        if (engine != "cpu") {
            val qnn = QnnDenoiseEngine.tryCreate(context)
            if (qnn != null) {
                try {
                    return runDenoiseQnn(src, w0, h0, w, h, qnn, t0)
                } catch (e: Throwable) {
                    Log.e(TAG, "QNN denoise failed mid-run, falling back to CPU: ${e.message}", e)
                    // src may have been recycled by runDenoiseQnn before failing;
                    // guard the CPU fallback against a recycled bitmap.
                    if (src.isRecycled) throw e
                } finally {
                    qnn.close()
                }
            } else {
                Log.w(TAG, "QNN engine unavailable, using CPU denoise")
            }
        }

        // FALLBACK path: CPU TFLite. Create the interpreter(s) for THIS denoise
        // only; closed in finally.
        val interpreters = createInterpreters()
        val delegateTag = "CPU${interpreters.size}x$CPU_INTRAOP_THREADS"
        try {
            return runDenoise(src, w0, h0, w, h, interpreters, delegateTag, t0)
        } finally {
            for (interp in interpreters) {
                try { interp.close() } catch (e: Exception) { Log.w(TAG, "interpreter close failed: ${e.message}") }
            }
        }
    }

    /**
     * QNN-HTP tiled denoise. Same tiling/overlap/stitch contract as [runDenoise],
     * but tiles run SERIALLY through the single HTP session (the NPU is a serial
     * accelerator; one in-flight graph at a time). Per-tile and full-photo timings
     * are logged so the on-device speedup is measurable.
     */
    private fun runDenoiseQnn(
        src: Bitmap, w0: Int, h0: Int, w: Int, h: Int,
        qnn: QnnDenoiseEngine, t0: Long,
    ): Bitmap {
        val base = if (w == w0 && h == h0) src else Bitmap.createBitmap(src, 0, 0, w, h)
        if (base !== src) src.recycle()

        val outPx = IntArray(w * h)
        val core = TILE - 2 * OVERLAP

        val inFloats = FloatArray(TILE * TILE * 3)
        val outFloats = FloatArray(TILE * TILE * 3)
        val tilePx = IntArray(TILE * TILE)
        val inv255 = 1f / 255f

        var tileCount = 0
        var tileMsSum = 0L
        var tileMsMax = 0L

        var y = 0
        while (y < h) {
            val coreH = minOf(core, h - y)
            val inY = (y - OVERLAP).coerceAtLeast(0).coerceAtMost(h - TILE)
            var x = 0
            while (x < w) {
                val coreW = minOf(core, w - x)
                val inX = (x - OVERLAP).coerceAtLeast(0).coerceAtMost(w - TILE)

                base.getPixels(tilePx, 0, TILE, inX, inY, TILE, TILE)
                var fi = 0
                for (i in 0 until TILE * TILE) {
                    val p = tilePx[i]
                    inFloats[fi++] = ((p shr 16) and 0xFF) * inv255
                    inFloats[fi++] = ((p shr 8) and 0xFF) * inv255
                    inFloats[fi++] = (p and 0xFF) * inv255
                }

                // Checkpoint: if a capture is in flight, park here (between
                // tiles) rather than allocating alongside it.
                try { pauseCheck?.invoke() } catch (_: Throwable) {}
                val tt0 = android.os.SystemClock.elapsedRealtime()
                qnn.runTile(inFloats, outFloats)
                val tdt = android.os.SystemClock.elapsedRealtime() - tt0
                tileMsSum += tdt
                if (tdt > tileMsMax) tileMsMax = tdt
                tileCount++

                val localX = x - inX
                val localY = y - inY
                for (ty in 0 until coreH) {
                    var srcIdx = ((localY + ty) * TILE + localX) * 3
                    val dstRow = (y + ty) * w + x
                    for (tx in 0 until coreW) {
                        val r = (outFloats[srcIdx].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                        val g = (outFloats[srcIdx + 1].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                        val b = (outFloats[srcIdx + 2].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                        outPx[dstRow + tx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        srcIdx += 3
                    }
                }
                x += core
            }
            y += core
        }
        base.recycle()
        val out = Bitmap.createBitmap(outPx, w, h, Bitmap.Config.ARGB_8888)
        val avgMs = if (tileCount > 0) tileMsSum / tileCount else 0
        Log.i(TAG, "splitter denoise ${w}x${h} tiles=$tileCount engine=QNN-HTP" +
            " cache=${if (qnn.fromCache) "load" else "prepare"}" +
            " tileAvgMs=$avgMs tileMaxMs=$tileMsMax durMs=${android.os.SystemClock.elapsedRealtime() - t0}")
        return out
    }

    /** Selected denoise engine ("npu" default, or "cpu"). Reboot-scoped prop. */
    private fun engineProp(): String {
        return try {
            val sp = Class.forName("android.os.SystemProperties")
            val v = sp.getMethod("get", String::class.java).invoke(null, PROP_ENGINE) as String
            if (v.isBlank()) DEFAULT_ENGINE else v.trim().lowercase()
        } catch (e: Exception) {
            DEFAULT_ENGINE
        }
    }

    private fun runDenoise(
        src: Bitmap, w0: Int, h0: Int, w: Int, h: Int,
        interpreters: List<Interpreter>, delegateTag: String, t0: Long,
    ): Bitmap {
        val base = if (w == w0 && h == h0) src else Bitmap.createBitmap(src, 0, 0, w, h)
        if (base !== src) src.recycle()

        val outPx = IntArray(w * h)
        val core = TILE - 2 * OVERLAP

        // Precompute tile coordinates. Each tile is independent (writes to a
        // disjoint core region of outPx) so they parallelize cleanly.
        data class TileWork(val x: Int, val y: Int, val inX: Int, val inY: Int, val coreW: Int, val coreH: Int)
        val work = ArrayList<TileWork>()
        run {
            var y = 0
            while (y < h) {
                val coreH = minOf(core, h - y)
                val inY = (y - OVERLAP).coerceAtLeast(0).coerceAtMost(h - TILE)
                var x = 0
                while (x < w) {
                    val coreW = minOf(core, w - x)
                    val inX = (x - OVERLAP).coerceAtLeast(0).coerceAtMost(w - TILE)
                    work.add(TileWork(x, y, inX, inY, coreW, coreH))
                    x += core
                }
                y += core
            }
        }
        val totalTiles = work.size
        val nextIdx = java.util.concurrent.atomic.AtomicInteger(0)

        // Spawn one worker per interpreter. Each worker owns its own
        // inBuf/outBuf/tilePx to avoid any cross-thread aliasing. Work-stealing
        // over `nextIdx` so workers keep busy even with uneven tile boundaries.
        val workerCount = interpreters.size.coerceAtLeast(1)
        val latch = java.util.concurrent.CountDownLatch(workerCount)
        for (worker in 0 until workerCount) {
            val interp = interpreters[worker]
            val inBuf = ByteBuffer.allocateDirect(4 * TILE * TILE * 3).order(ByteOrder.nativeOrder())
            val outBuf = ByteBuffer.allocateDirect(4 * TILE * TILE * 3).order(ByteOrder.nativeOrder())
            val tilePx = IntArray(TILE * TILE)
            val inv255 = 1f / 255f
            Thread({
                try {
                    while (true) {
                        val idx = nextIdx.getAndIncrement()
                        if (idx >= totalTiles) break
                        val tw = work[idx]
                        base.getPixels(tilePx, 0, TILE, tw.inX, tw.inY, TILE, TILE)
                        inBuf.clear()
                        for (i in 0 until TILE * TILE) {
                            val p = tilePx[i]
                            inBuf.putFloat(((p shr 16) and 0xFF) * inv255)
                            inBuf.putFloat(((p shr 8) and 0xFF) * inv255)
                            inBuf.putFloat((p and 0xFF) * inv255)
                        }
                        inBuf.rewind()
                        outBuf.clear()
                        interp.run(inBuf, outBuf)
                        outBuf.rewind()
                        val localX = tw.x - tw.inX
                        val localY = tw.y - tw.inY
                        for (ty in 0 until tw.coreH) {
                            val srcIdx = ((localY + ty) * TILE + localX) * 3 * 4
                            outBuf.position(srcIdx)
                            val dstRow = (tw.y + ty) * w + tw.x
                            for (tx in 0 until tw.coreW) {
                                val r = (outBuf.float.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                                val g = (outBuf.float.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                                val b = (outBuf.float.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                                outPx[dstRow + tx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                            }
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }, "SplitterDenoise-$worker").start()
        }
        latch.await()
        base.recycle()
        val out = Bitmap.createBitmap(outPx, w, h, Bitmap.Config.ARGB_8888)
        Log.i(TAG, "splitter denoise ${w}x${h} tiles=$totalTiles workers=$workerCount delegate=$delegateTag durMs=${android.os.SystemClock.elapsedRealtime() - t0}")
        return out
    }

    /** Build the interpreter(s) for a single denoise. Prefers the GPU delegate
     *  (one interpreter, internal parallelism); otherwise one CPU interpreter
     *  with [CPU_INTRAOP_THREADS] threads. Closed by [denoise] after the run. */
    private fun createInterpreters(): List<Interpreter> {
        // GPU delegate: offloads the whole inference onto the Adreno GPU, freeing
        // the 4 A55 CPU cores. The CPU path pins all 4 cores at max freq for ~67s
        // per photo; the GPU runs the same matmuls at far lower energy/op, which is
        // the real battery win (wall-clock parallelism cannot help -- CPU energy is
        // ~constant cores*time regardless of interp/thread split). gpuDelegate() is
        // gated behind a prop so it can be A/B-measured against CPU on-device; the
        // CompatibilityList allowlist is advisory and returns false on this Rokid
        // board even though the Adreno 621 + libtensorflowlite_gpu_jni.so are present.
        val gpuMode = intProp(PROP_GPU, if (DEFAULT_GPU) 1 else 0)
        if (gpuMode != 0) {
            runCatching {
                val supported = try { CompatibilityList().isDelegateSupportedOnThisDevice } catch (_: Throwable) { false }
                // GpuDelegate() internally references GpuDelegateFactory$Options,
                // which lives in tensorflow-lite-gpu-api -- that artifact must be an
                // explicit dependency (tensorflow-lite-gpu:2.14.0 does NOT pull it
                // transitively), else this fails at runtime with
                // "Failed resolution of: GpuDelegateFactory$Options" and falls back
                // to CPU. See capture/build.gradle.kts.
                val opts = Interpreter.Options().apply { addDelegate(GpuDelegate()) }
                val it = Interpreter(model, opts)
                Log.i(TAG, "interpreter init: GPU delegate (1 worker, allowlist=$supported forced=${gpuMode})")
                return listOf(it)
            }.onFailure { e ->
                Log.w(TAG, "GPU delegate init failed: ${e.message}, falling back to CPU")
            }
        }
        val nInterp = intProp(PROP_INTERPRETERS, CPU_INTERPRETERS).coerceIn(1, 4)
        val nThreads = intProp(PROP_THREADS, CPU_INTRAOP_THREADS).coerceIn(1, 4)
        val interpreters = (0 until nInterp).map {
            val opts = Interpreter.Options().apply { setNumThreads(nThreads) }
            Interpreter(model, opts)
        }
        Log.i(TAG, "interpreter init: CPU ${nInterp}x interpreters x ${nThreads}-thread")
        return interpreters
    }

    /** Read an int system property via reflection (same pattern as LedController /
     *  RawStillCapturer.skipDenoise). Lets the denoise parallelism be swept at
     *  runtime without a rebuild; falls back to [def] when unset/unreadable. */
    private fun intProp(name: String, def: Int): Int {
        return try {
            val sp = Class.forName("android.os.SystemProperties")
            val v = sp.getMethod("get", String::class.java).invoke(null, name) as String
            if (v.isBlank()) def else v.trim().toInt()
        } catch (e: Exception) {
            Log.w(TAG, "intProp $name read failed: ${e.message}")
            def
        }
    }

    companion object {
        private const val TAG = "Cap:Splitter"
        private const val ASSET = "ml/splitternet.tflite"
        private const val TILE = 256
        private const val OVERLAP = 16

        /** Runtime-tunable denoise parallelism (reflection SystemProperties, same
         *  pattern as skip_denoise). Lets configs be swept without a rebuild:
         *    adb shell setprop debug.glasses.capture.denoise_interp <1..4>
         *    adb shell setprop debug.glasses.capture.denoise_threads <1..4> */
        private const val PROP_INTERPRETERS = "debug.glasses.capture.denoise_interp"
        private const val PROP_THREADS = "debug.glasses.capture.denoise_threads"
        /** GPU delegate toggle: setprop debug.glasses.capture.denoise_gpu <0|1>. */
        private const val PROP_GPU = "debug.glasses.capture.denoise_gpu"
        /** Inference engine selector: setprop debug.glasses.capture.denoise_engine npu|cpu.
         *  DEFAULT is npu (Hexagon V73 HTP via QNN) -- the hardware-accelerated,
         *  off-CPU path. CPU is the automatic fallback when QNN init fails. */
        private const val PROP_ENGINE = "debug.glasses.capture.denoise_engine"
        private const val DEFAULT_ENGINE = "npu"
        /** Default OFF until the on-device GPU A/B confirms it is faster AND stable
         *  (the Adreno path can fall over on some ops; CPU 1x4 is the safe baseline). */
        private const val DEFAULT_GPU = false

        /** ONE CPU interpreter with 4 intra-op threads. Glasses SoC is only 4x
         *  Cortex-A55, so 4 threads already saturate every core -- the denoise is
         *  compute-bound, not parallelism-bound. Each interpreter allocates its
         *  own ~236MB tensor arena (TILE=256 is fixed: the model's bottleneck
         *  skip-connection is not resizable below 256). Running 2 interpreters
         *  therefore DOUBLES the native heap to ~473MB with NO wall-clock gain
         *  (measured 2x2 ~67s == 1x4 ~67s) and OOM-kills the FGS under a burst.
         *  Stay at 1x4; the arena is created+closed per denoise() so it is never
         *  an idle floor. */
        private const val CPU_INTERPRETERS = 1
        private const val CPU_INTRAOP_THREADS = 4

        // Only the model (a ~3MB MappedByteBuffer) is cached as a singleton; the
        // expensive interpreter arenas are created+closed per denoise() call.
        @Volatile private var instance: SplitterDenoiser? = null

        fun get(context: Context): SplitterDenoiser {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val appCtx = context.applicationContext
                val created = SplitterDenoiser(appCtx, loadModel(appCtx))
                instance = created
                return created
            }
        }

        /**
         * Drop the cached denoiser so the next [get] rebuilds it from scratch.
         *
         * Called after a denoise failure -- typically memory pressure on this
         * 1.8GB device. The QNN engine and its interpreter arenas are already
         * per-call (created and closed inside denoise()), so what this actually
         * releases is the singleton and its mapped model, letting the whole
         * object graph be collected before a retry runs. Cheap to rebuild
         * (the model is a ~3MB mmap) next to losing the photo.
         *
         * Safe to call with no instance. Callers run on the single-threaded
         * process executor, so this never races a live denoise.
         */
        fun release() {
            val had = synchronized(this) {
                val i = instance
                instance = null
                i != null
            }
            if (had) Log.i(TAG, "denoiser released (will rebuild on next use)")
        }

        private fun loadModel(context: Context): MappedByteBuffer {
            val afd = context.assets.openFd(ASSET)
            return FileInputStream(afd.fileDescriptor).use { fis ->
                fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
        }
    }
}
