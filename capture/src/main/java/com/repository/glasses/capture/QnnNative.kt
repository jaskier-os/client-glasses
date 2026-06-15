package com.repository.glasses.capture

/**
 * JNI bridge to the native QNN HTP denoise shim (libqnn_denoise.so).
 *
 * The native side dlopen's the bundled libQnnHtp.so / libQnnSystem.so from the
 * APK's nativeLibraryDir, creates the HTP device on the UNSIGNED process domain
 * (the QNN HTP backend default -- the only PD this AR1 silicon accepts), loads
 * the SplitterNet int8 DLC (or a prepared context cache), and runs one 256x256x3
 * tile per [nativeRunTile] call.
 *
 * All methods are static; the opaque handle returned by [nativeInit] carries the
 * backend/device/context/graph state. 0 means init failure.
 */
object QnnNative {
    init {
        System.loadLibrary("qnn_denoise")
    }

    /**
     * @param backendLibDir directory holding libQnnHtp.so + skel/stub/system libs
     *                      (the app's nativeLibraryDir).
     * @param dlcOrCachePath filesystem path to the SplitterNet int8 .dlc.
     * @param cacheDir directory to read/write the prepared-context cache
     *                 (splitternet_htp_v73.qnnctx).
     * @return opaque handle (>0) on success, 0 on any failure.
     */
    external fun nativeInit(
        backendLibDir: String,
        dlcOrCachePath: String,
        cacheDir: String,
        prebuiltCtxPath: String,
    ): Long

    /**
     * Run one [1,256,256,3] f32 RGB [0,1] tile. [inFloats]/[outFloats] must each be
     * length 256*256*3. Returns false on execute failure.
     */
    external fun nativeRunTile(handle: Long, inFloats: FloatArray, outFloats: FloatArray): Boolean

    /** True if the engine loaded a prepared context cache (vs. preparing fresh). */
    external fun nativeFromCache(handle: Long): Boolean

    /** Free the graph/context/device/backend and dlclose the QNN libs. */
    external fun nativeClose(handle: Long)
}
