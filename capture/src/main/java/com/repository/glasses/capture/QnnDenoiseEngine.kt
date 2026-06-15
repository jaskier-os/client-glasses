package com.repository.glasses.capture

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Hexagon V73 NPU denoise engine via the Qualcomm QNN C API directly
 * (libqnn_denoise.so -> [QnnNative]).
 *
 * ## Why the raw QNN C API (not ORT-QNN)
 * ONNX Runtime's QNN execution provider (onnxruntime-android-qnn 1.20) FAILS on
 * this AR1 silicon (soc_model 58, Hexagon v73): every HTP device-creation attempt
 * logs `QNN_DEVICE_ERROR_INVALID_CONFIG: Failed to create device`, then ORT
 * silently runs on CPU (~1.7s/tile). Root cause: this chip requires the HTP device
 * be created on the UNSIGNED process domain (`fastrpc_shell_unsigned_3`). ORT-QNN
 * 1.20 exposes no unsigned-PD / pd_session knob, so it always uses the signed PD
 * the chip rejects. The QNN HTP backend's DEFAULT PD is unsigned, so creating the
 * device through the C API with a default (NULL) config gives us exactly that.
 *
 * The native shim: dlopen libQnnHtp.so + libQnnSystem.so from nativeLibraryDir,
 * create backend, create device (NULL config = unsigned PD), load the int8 DLC via
 * QnnContext_createFromBinary (which auto-detects DLC vs cached context and
 * prepares on-device), retrieve graph `splitternet_constpad`, bind I/O and execute.
 * On first run it prepares from the DLC (~12s) and caches the prepared context to
 * filesDir; subsequent runs load the cache (~0.6s).
 *
 * Tile contract is identical to the CPU path: NHWC float32 [1,256,256,3] in [0,1].
 */
class QnnDenoiseEngine private constructor(
    private val handle: Long,
    val fromCache: Boolean,
) {
    /** Run one [TILE]x[TILE]x3 tile. [inFloats] is NHWC [0,1] (length TILE*TILE*3);
     *  result is written into [outFloats] (same length, same layout). */
    fun runTile(inFloats: FloatArray, outFloats: FloatArray) {
        if (!QnnNative.nativeRunTile(handle, inFloats, outFloats)) {
            throw RuntimeException("QNN nativeRunTile failed")
        }
    }

    fun close() {
        try { QnnNative.nativeClose(handle) } catch (e: Throwable) {
            Log.w(TAG, "QNN close failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "Cap:Splitter"
        const val TILE = 256
        /** int8 SplitterNet DLC (const-pad variant), QAIRT 2.47-built. */
        private const val DLC_ASSET = "ml/qnn/splitternet_w8a8.dlc"
        /** Copied-out DLC on the filesystem (QNN needs a real path / blob). */
        private const val DLC_FILE = "splitternet_w8a8.dlc"
        /** Prebuilt HTP context binary prepared on THIS SoC (correct hardware VTCM,
         *  QAIRT 2.47). Preferred over on-device DLC prepare, which requests a 4MB
         *  VTCM the AR1 silicon rejects (err 0x138d). */
        private const val CTX_ASSET = "ml/qnn/splitternet_htp_v73.bin"
        private const val CTX_FILE = "splitternet_htp_v73_prebuilt.bin"
        /** Prepared-context cache written by the native shim. */
        private const val CACHE_FILE = "splitternet_htp_v73.qnnctx"
        /** Warmup-tile ceiling that proves the HTP DSP actually engaged. HTP is
         *  ~70ms/tile; anything CPU-like (>400ms) means the NPU did not engage. */
        private const val HTP_WARMUP_CEILING_MS = 400L

        /**
         * Build a QNN-HTP engine. Returns null on ANY failure so [SplitterDenoiser]
         * can fall back to the CPU path -- denoise must never hard-fail. After init,
         * runs one warmup tile; if it exceeds [HTP_WARMUP_CEILING_MS] the HTP did not
         * engage, so we close, drop any poisoned cache, and return null.
         */
        fun tryCreate(context: Context): QnnDenoiseEngine? {
            return try {
                val t0 = android.os.SystemClock.elapsedRealtime()
                val backendLibDir = context.applicationInfo.nativeLibraryDir
                val cacheDir = context.filesDir.absolutePath

                val dlcFile = File(context.filesDir, DLC_FILE)
                if (!dlcFile.exists() || dlcFile.length() <= 0) {
                    context.assets.open(DLC_ASSET).use { input ->
                        dlcFile.outputStream().use { out -> input.copyTo(out) }
                    }
                    Log.i(TAG, "copied DLC asset to ${dlcFile.absolutePath} (${dlcFile.length()} bytes)")
                }

                // Prebuilt HTP context binary (prepared on this SoC). Copy out so QNN
                // has a filesystem path. Optional -- if the asset is missing the native
                // side falls back to on-device DLC prepare.
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
                    Log.w(TAG, "no prebuilt context asset ($CTX_ASSET): ${e.message}; will prepare from DLC")
                }

                val handle = QnnNative.nativeInit(
                    backendLibDir, dlcFile.absolutePath, cacheDir, prebuiltPath)
                if (handle == 0L) {
                    Log.e(TAG, "QNN nativeInit returned 0 (HTP init failed); falling back to CPU")
                    return null
                }
                val fromCache = QnnNative.nativeFromCache(handle)
                val engine = QnnDenoiseEngine(handle, fromCache)

                // PROBE: time one warmup tile. HTP ~70ms; CPU masquerade ~hundreds+.
                val warmIn = FloatArray(TILE * TILE * 3)
                val warmOut = FloatArray(TILE * TILE * 3)
                val wt0 = android.os.SystemClock.elapsedRealtime()
                try {
                    engine.runTile(warmIn, warmOut)
                } catch (e: Throwable) {
                    Log.e(TAG, "QNN warmup tile threw, falling back to CPU: ${e.message}")
                    engine.close()
                    return null
                }
                val warmMs = android.os.SystemClock.elapsedRealtime() - wt0
                val htpEngaged = warmMs <= HTP_WARMUP_CEILING_MS
                Log.i(TAG, "QNN warmupMs=$warmMs htpEngaged=$htpEngaged " +
                    "cache=${if (fromCache) "load" else "prepare"} " +
                    "initMs=${android.os.SystemClock.elapsedRealtime() - t0}")

                if (htpEngaged) {
                    Log.i(TAG, "QNN engine init: HTP engaged (warmupMs=$warmMs)")
                    return engine
                }

                // HTP did not engage -> close, drop any cache built in this state.
                engine.close()
                File(context.filesDir, CACHE_FILE).delete()
                Log.e(TAG, "QNN HTP did not engage (warmupMs=$warmMs > ${HTP_WARMUP_CEILING_MS}ms); CPU fallback")
                null
            } catch (e: Throwable) {
                Log.w(TAG, "QNN engine init failed, will fall back to CPU: ${e.message}", e)
                null
            }
        }
    }
}
