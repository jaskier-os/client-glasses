package com.repository.glasses.capture

import android.graphics.Bitmap

/**
 * JNI bridge to the native SCRFD-10G QNN HTP shim (libscrfd_qnn.so), running in
 * the capture process. The capture APK is a normal /data/app install (NOT a
 * privileged /system/priv-app), so its classloader namespace can dlopen the
 * vendor public lib libcdsprpc.so (via the <uses-native-library> manifest grant)
 * to reach the CDSP -- the listener priv-app process cannot, which is why face
 * detection lives here next to the SplitterNet denoiser.
 *
 * Mirrors [QnnNative]. All methods static; the opaque handle from [nativeInit]
 * carries the backend/device/context/graph state. 0 means init failure.
 */
object ScrfdQnnNative {
    init {
        System.loadLibrary("scrfd_qnn")
    }

    external fun nativeInit(
        backendLibDir: String,
        dlcOrCachePath: String,
        cacheDir: String,
        prebuiltCtxPath: String,
    ): Long

    /**
     * Detect faces in [bitmap] (must be ARGB_8888). Returns a flat float array:
     *   [0] = face count N
     *   then per face (15 floats): score, x0,y0,x1,y1, kx0,ky0, ... kx4,ky4
     * Coordinates are in ORIGINAL bitmap pixel space. null on execute failure.
     */
    external fun nativeDetect(handle: Long, bitmap: Bitmap): FloatArray?

    external fun nativeFromCache(handle: Long): Boolean
    external fun nativeClose(handle: Long)
}
