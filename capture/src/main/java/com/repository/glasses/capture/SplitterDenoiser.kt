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
        // Create the interpreter(s) for THIS denoise only; closed in finally.
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
        runCatching {
            val compat = CompatibilityList()
            if (compat.isDelegateSupportedOnThisDevice) {
                val opts = Interpreter.Options().apply { addDelegate(GpuDelegate()) }
                val it = Interpreter(model, opts)
                Log.i(TAG, "interpreter init: GPU delegate (1 worker)")
                return listOf(it)
            }
            Log.w(TAG, "GPU delegate not supported, falling back to CPU")
        }.onFailure { e ->
            Log.w(TAG, "GPU delegate init failed: ${e.message}, falling back to CPU")
        }
        val interpreters = (0 until CPU_INTERPRETERS).map {
            val opts = Interpreter.Options().apply { setNumThreads(CPU_INTRAOP_THREADS) }
            Interpreter(model, opts)
        }
        Log.i(TAG, "interpreter init: CPU ${CPU_INTERPRETERS}x interpreters x ${CPU_INTRAOP_THREADS}-thread")
        return interpreters
    }

    companion object {
        private const val TAG = "Cap:Splitter"
        private const val ASSET = "ml/splitternet.tflite"
        private const val TILE = 256
        private const val OVERLAP = 16

        /** ONE CPU interpreter with 4 intra-op threads. Glasses SoC is 4x
         *  Cortex-A55 with only ~1.8GB RAM. Each interpreter allocates its own
         *  ~300MB tensor arena, so the previous 2-interpreter config doubled the
         *  native footprint and was the dominant cause of the capture-process
         *  OOM (permanent ~472MB native floor -> lowmemorykiller). Going to 1
         *  interpreter ~halves the arena; combined with closing it after each
         *  denoise (see denoise()), the arena is no longer a permanent floor.
         *  Cost: 1x4 is slower than 2x2 (~67s vs ~29s) but the denoise runs in
         *  the background and never blocks the camera, so wall-clock here is not
         *  user-facing. Memory stability beats denoise speed on this device. */
        private const val CPU_INTERPRETERS = 1
        private const val CPU_INTRAOP_THREADS = 4

        // Only the model (a ~3MB MappedByteBuffer) is cached as a singleton; the
        // expensive interpreter arenas are created+closed per denoise() call.
        @Volatile private var instance: SplitterDenoiser? = null

        fun get(context: Context): SplitterDenoiser {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val created = SplitterDenoiser(loadModel(context.applicationContext))
                instance = created
                return created
            }
        }

        private fun loadModel(context: Context): MappedByteBuffer {
            val afd = context.assets.openFd(ASSET)
            return FileInputStream(afd.fileDescriptor).use { fis ->
                fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
        }
    }
}
