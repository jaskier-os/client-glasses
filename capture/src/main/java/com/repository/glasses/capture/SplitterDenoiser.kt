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
    private val interpreters: List<Interpreter>,
    private val delegateTag: String,
) {

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

    companion object {
        private const val TAG = "Cap:Splitter"
        private const val ASSET = "ml/splitternet.tflite"
        private const val TILE = 256
        private const val OVERLAP = 16

        /** Pool size for parallel tile denoise on the CPU path. Glasses SoC is
         *  4x Cortex-A55. Measured configs:
         *    - 1x4 (original): ~67 s
         *    - 2x2: ~29 s  <-- sweet spot, chosen here
         *    - 4x1: slower than 2x2 under load (model is intra-op friendly,
         *      too-small intra-op splits serialize on the single dispatcher). */
        // 2x2 is the wall-clock sweet spot on this A55 quad-core (~29s
        // vs 1x4 at ~3+ minutes -- TFLite intra-op parallelism plateaus
        // hard past 2 threads). To stay under the LMK threshold the
        // camera warm pool is explicitly closed by PhotoCapturer's denoise
        // executor before this runs, freeing the YUV reader buffers and
        // camera session memory.
        private const val CPU_INTERPRETERS = 2
        private const val CPU_INTRAOP_THREADS = 2

        @Volatile private var instance: SplitterDenoiser? = null

        fun get(context: Context): SplitterDenoiser {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val created = create(context.applicationContext)
                instance = created
                return created
            }
        }

        private fun create(context: Context): SplitterDenoiser {
            val model = loadModel(context)
            runCatching {
                val compat = CompatibilityList()
                if (compat.isDelegateSupportedOnThisDevice) {
                    // GPU delegate isn't thread-safe; stick to a single
                    // interpreter. The GPU does its own internal parallelism.
                    val opts = Interpreter.Options().apply { addDelegate(GpuDelegate()) }
                    val it = Interpreter(model, opts)
                    Log.i(TAG, "interpreter init: GPU delegate (1 worker)")
                    return SplitterDenoiser(listOf(it), "GPU")
                }
                Log.w(TAG, "GPU delegate not supported, falling back to CPU")
            }.onFailure { e ->
                Log.w(TAG, "GPU delegate init failed: ${e.message}, falling back to CPU")
            }
            // CPU path: 2 interpreters x 2 intra-op threads = 4 total cores used
            // (same CPU budget as the single 4-thread interpreter, but two tiles
            // can run concurrently, roughly halving wall-clock for the tile loop).
            // A55-quad on this device cannot sustainably run 4 interpreters at
            // once -- intra-op thread contention wipes out the benefit past 2.
            val cpuInterpretersCount = CPU_INTERPRETERS
            val cpuIntraOpThreads = CPU_INTRAOP_THREADS
            val interpreters = (0 until cpuInterpretersCount).map {
                val opts = Interpreter.Options().apply { setNumThreads(cpuIntraOpThreads) }
                Interpreter(model, opts)
            }
            Log.i(TAG, "interpreter init: CPU ${cpuInterpretersCount}x interpreters x ${cpuIntraOpThreads}-thread")
            return SplitterDenoiser(interpreters, "CPU${cpuInterpretersCount}x${cpuIntraOpThreads}")
        }

        private fun loadModel(context: Context): MappedByteBuffer {
            val afd = context.assets.openFd(ASSET)
            return FileInputStream(afd.fileDescriptor).use { fis ->
                fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
        }
    }
}
