package com.repository.glasses.capture

/**
 * JNI bridge to libgigaam_enc.so: log-mel front end + the GigaAM v3_e2e_rnnt
 * conformer encoder (w8a16) on the Hexagon V73 NPU via the raw QNN C API.
 *
 * Ported from the benchmark project (android-stt-test). Only the package and the
 * JNI symbol prefix changed; the C++ is otherwise byte-identical.
 *
 * This lives in the capture process, not the listener: the listener is a
 * /system/priv-app whose linker namespace cannot dlopen libcdsprpc.so, so it
 * cannot reach the CDSP at all. Verified on-device -- the listener :backend maps
 * zero QNN or cdsprpc libraries while capture maps 16.
 *
 * THREAD SAFETY / LIFETIME. Two separate hazards, both handled here because the
 * caller is a Binder thread pool:
 *
 *  1. Reentrancy. The encoder's ION input/output buffers are allocated once and
 *     reused, so two concurrent graphExecute calls corrupt each other's tensors.
 *  2. Use-after-free. The native side frees the engine on close without nulling
 *     or validating the pointer, and its internal guard (`!e || e->idxAudio < 0`)
 *     does NOT reject a dangling pointer. A close racing an in-flight encode, or
 *     any encode after close, would read freed memory.
 *
 * Both are closed by making this object the SOLE owner of the handle: the native
 * methods are private (JNI binds by symbol, so private still resolves), the
 * handle never escapes to callers, and every entry point is synchronized on this
 * object. Callers therefore cannot construct a stale-handle call at all.
 */
object GigaAmNative {
    init { System.loadLibrary("gigaam_enc") }

    /** Live engine pointer, or 0 when not loaded. Guarded by this object's monitor. */
    private var handle: Long = 0L

    /**
     * @param backendLibDir app nativeLibraryDir (holds libQnnHtp.so, libQnnSystem.so,
     *   libQnnHtpV73Stub.so, libQnnHtpV73Skel.so)
     * @param ctxPath prebuilt context binary
     * @param melFb 64*161 filterbank, mel-major
     * @return opaque handle, 0 on failure
     */
    private external fun nativeInit(backendLibDir: String, ctxPath: String, melFb: FloatArray): Long

    /**
     * Encode up to 5 s of float32 mono 16 kHz PCM.
     * @return float[768*125 + 3]: encoded (dim-major, index = d*125 + t),
     *   then encodedLen, featMs, execMs. null on execute failure.
     */
    private external fun nativeEncode(handle: Long, pcm: FloatArray): FloatArray?

    /**
     * Self-test entrypoint: run the encoder on a PRECOMPUTED [64,500] log-mel
     * block, bypassing the C++ front end. Isolates encoder correctness from mic
     * capture (usable with the glasses off-head).
     */
    private external fun nativeEncodeFeats(handle: Long, feats: FloatArray, frames: Int): FloatArray?

    private external fun nativeClose(handle: Long)

    /** True when the encoder is loaded and a call would attempt inference. */
    @Synchronized
    fun isLoaded(): Boolean = handle != 0L

    /**
     * Load the encoder. Idempotent: a second call while already loaded is a no-op
     * returning true, so concurrent prepare requests cannot leak a second engine
     * (each engine is ~203 MB resident on a 1.77 GB device).
     *
     * @return true when the encoder is loaded and usable.
     */
    @Synchronized
    fun load(backendLibDir: String, ctxPath: String, melFb: FloatArray): Boolean {
        if (handle != 0L) return true
        handle = nativeInit(backendLibDir, ctxPath, melFb)
        return handle != 0L
    }

    /** @return null when the encoder is not loaded or the execute failed. */
    @Synchronized
    fun encode(pcm: FloatArray): FloatArray? {
        val h = handle
        if (h == 0L) return null
        return nativeEncode(h, pcm)
    }

    /** @return null when the encoder is not loaded or the execute failed. */
    @Synchronized
    fun encodeFeats(feats: FloatArray, frames: Int): FloatArray? {
        val h = handle
        if (h == 0L) return null
        return nativeEncodeFeats(h, feats, frames)
    }

    /**
     * Release the encoder. Idempotent. Because this is synchronized on the same
     * monitor as encode(), a close cannot interleave with an in-flight encode: it
     * waits for the encode to return, then frees. The handle is zeroed BEFORE the
     * free so no later call can observe a dangling pointer even if the native free
     * throws.
     */
    @Synchronized
    fun close() {
        val h = handle
        if (h == 0L) return
        handle = 0L
        nativeClose(h)
    }
}
