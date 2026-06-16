package com.repository.glasses.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * SCRFD-10G face detector on the Hexagon V73 NPU (HTP), running in the capture
 * process via the Qualcomm QNN C API directly (libscrfd_qnn.so -> [ScrfdQnnNative]).
 *
 * Replaces the ML Kit ACCURATE CPU detector the listener used for ReID
 * (~3200 ms/frame -> ~11 ms/frame on the NPU). It lives in the capture module
 * (not the listener) because the listener is a privileged /system/priv-app whose
 * linker namespace cannot dlopen the vendor public lib libcdsprpc.so; the capture
 * APK is a normal /data/app install that can (same as the SplitterNet denoiser).
 *
 * The HTP context binary is prepared on this exact SoC and shipped as the
 * [CTX_ASSET]; init loads it directly (<1s) and skips the on-device DLC prepare.
 * The DLC is kept as a fallback. Mirrors [QnnDenoiseEngine.tryCreate].
 *
 * Detection runs synchronously on the calling thread. The engine holds ONE set
 * of shared ION input/output buffers, so concurrent [detect] calls would clobber
 * each other mid-graphExecute. Binder dispatches detectFaces on a thread pool and
 * the eager warmup in CaptureService.onCreate runs on its own thread, so calls are
 * NOT inherently serialized -- [detect] takes [execLock] to guarantee one
 * inference at a time. Contention is negligible (ReID detects ~1 frame/1.5s).
 */
class ScrfdFaceDetector private constructor(
    private val handle: Long,
    val fromCache: Boolean,
) {
    /** Serializes [detect] over the engine's single shared ION I/O buffer set. */
    private val execLock = Any()
    /**
     * Detect faces in a JPEG. Returns a flat int array: [count, then per face
     * x0,y0,x1,y1] in JPEG pixel coordinates. Empty (count 0) if no faces or on a
     * transient HTP failure. The keypoints are dropped at the AIDL boundary (the
     * downstream crop path uses only the box; ML Kit produced only a box too).
     */
    fun detect(jpeg: ByteArray): IntArray {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return intArrayOf(0)
        val argb = if (bmp.config == Bitmap.Config.ARGB_8888) bmp
                   else bmp.copy(Bitmap.Config.ARGB_8888, false)
        val recycleArgb = argb !== bmp
        try {
            // Serialize the native inference: the engine's ION I/O buffers are
            // shared and not reentrant (see execLock).
            val raw = synchronized(execLock) {
                ScrfdQnnNative.nativeDetect(handle, argb)
            } ?: return intArrayOf(0)
            if (raw.isEmpty()) return intArrayOf(0)
            val n = raw[0].toInt()
            if (n <= 0) return intArrayOf(0)
            val w = bmp.width
            val h = bmp.height
            val out = ArrayList<Int>(1 + n * 4)
            var kept = 0
            for (i in 0 until n) {
                val o = 1 + i * 15
                if (o + 5 > raw.size) break
                val x0 = raw[o + 1].coerceIn(0f, w.toFloat()).toInt()
                val y0 = raw[o + 2].coerceIn(0f, h.toFloat()).toInt()
                val x1 = raw[o + 3].coerceIn(0f, w.toFloat()).toInt()
                val y1 = raw[o + 4].coerceIn(0f, h.toFloat()).toInt()
                if (x1 <= x0 || y1 <= y0) continue
                out.add(x0); out.add(y0); out.add(x1); out.add(y1)
                kept++
            }
            val arr = IntArray(1 + kept * 4)
            arr[0] = kept
            for (i in 0 until kept * 4) arr[1 + i] = out[i]
            return arr
        } finally {
            if (recycleArgb && !argb.isRecycled) argb.recycle()
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    fun close() {
        try { ScrfdQnnNative.nativeClose(handle) } catch (e: Throwable) {
            Log.w(TAG, "SCRFD close failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "Cap:Scrfd"

        private const val DLC_ASSET = "ml/qnn/scrfd_10g_w8a8.dlc"
        private const val DLC_FILE = "scrfd_10g_w8a8.dlc"
        private const val CTX_ASSET = "ml/qnn/scrfd_htp_v73_w8a8.bin"
        private const val CTX_FILE = "scrfd_htp_v73_w8a8_prebuilt.bin"
        private const val CACHE_FILE = "scrfd_htp_v73_w8a8.qnnctx"
        // The FIRST HTP execute pays a one-time on-device graph finalize/JIT
        // (~12-13s on this V73). Steady-state inference is ~11ms. So we run two
        // warmups and judge HTP engagement on the SECOND (steady-state) latency.
        private const val HTP_FIRST_CEILING_MS = 30_000L  // allow the one-time finalize
        private const val HTP_STEADY_CEILING_MS = 400L    // steady-state must be NPU-fast

        @Volatile private var shared: ScrfdFaceDetector? = null
        @Volatile private var triedInit = false

        /** Shared HTP detector; null if HTP could not be engaged (caller then
         *  signals the listener to use its ML Kit CPU fallback). One init attempt. */
        @Synchronized
        fun shared(context: Context): ScrfdFaceDetector? {
            shared?.let { return it }
            if (triedInit) return null
            triedInit = true
            val engine = tryCreate(context)
            shared = engine
            return engine
        }

        private fun tryCreate(context: Context): ScrfdFaceDetector? {
            return try {
                val t0 = SystemClock.elapsedRealtime()
                val backendLibDir = context.applicationInfo.nativeLibraryDir
                val cacheDir = context.filesDir.absolutePath

                val dlcFile = File(context.filesDir, DLC_FILE)
                if (!dlcFile.exists() || dlcFile.length() <= 0) {
                    context.assets.open(DLC_ASSET).use { input ->
                        dlcFile.outputStream().use { out -> input.copyTo(out) }
                    }
                    Log.i(TAG, "copied DLC asset to ${dlcFile.absolutePath} (${dlcFile.length()} bytes)")
                }

                var prebuiltPath = ""
                try {
                    val ctxFile = File(context.filesDir, CTX_FILE)
                    if (!ctxFile.exists() || ctxFile.length() <= 0) {
                        context.assets.open(CTX_ASSET).use { input ->
                            ctxFile.outputStream().use { out -> input.copyTo(out) }
                        }
                        Log.i(TAG, "copied prebuilt context to ${ctxFile.absolutePath} (${ctxFile.length()} bytes)")
                    }
                    prebuiltPath = ctxFile.absolutePath
                } catch (e: Throwable) {
                    Log.i(TAG, "no prebuilt context asset ($CTX_ASSET): will prepare from DLC on-device")
                }

                val handle = ScrfdQnnNative.nativeInit(
                    backendLibDir, dlcFile.absolutePath, cacheDir, prebuiltPath)
                if (handle == 0L) {
                    Log.e(TAG, "SCRFD nativeInit returned 0 (HTP init failed)")
                    return null
                }
                val fromCache = ScrfdQnnNative.nativeFromCache(handle)
                val engine = ScrfdFaceDetector(handle, fromCache)

                val warm = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
                try {
                    // First execute: one-time on-device graph finalize (~12-13s).
                    val w1 = SystemClock.elapsedRealtime()
                    val ok1 = ScrfdQnnNative.nativeDetect(handle, warm) != null
                    val ms1 = SystemClock.elapsedRealtime() - w1
                    // Second execute: steady-state -- this is the real per-frame cost.
                    val w2 = SystemClock.elapsedRealtime()
                    val ok2 = ScrfdQnnNative.nativeDetect(handle, warm) != null
                    val ms2 = SystemClock.elapsedRealtime() - w2
                    val htpEngaged = ok1 && ok2 &&
                        ms1 <= HTP_FIRST_CEILING_MS && ms2 <= HTP_STEADY_CEILING_MS
                    Log.i(TAG, "SCRFD warmup firstMs=$ms1 steadyMs=$ms2 htpEngaged=$htpEngaged " +
                        "cache=${if (fromCache) "load" else "prepare"} " +
                        "initMs=${SystemClock.elapsedRealtime() - t0}")
                    if (htpEngaged) {
                        Log.i(TAG, "SCRFD engine init: HTP engaged (steadyMs=$ms2)")
                        return engine
                    }
                    engine.close()
                    File(context.filesDir, CACHE_FILE).delete()
                    Log.e(TAG, "SCRFD HTP did not engage (firstMs=$ms1 steadyMs=$ms2)")
                    null
                } catch (e: Throwable) {
                    Log.e(TAG, "SCRFD warmup threw: ${e.message}")
                    engine.close(); null
                } finally {
                    warm.recycle()
                }
            } catch (e: Throwable) {
                Log.w(TAG, "SCRFD init exception: ${e.message}", e)
                null
            }
        }
    }
}
