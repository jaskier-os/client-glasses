package com.repository.glasses.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Takes a single JPEG via the shared [CameraSession] (which owns Camera2
 * device 0), optionally reroutes dark scenes through the low-light RAW path,
 * runs a background ML denoise pass, then saves to disk and invokes callbacks.
 *
 * This class no longer opens a camera of its own: [CameraSession.requestStill]
 * grabs one frame from the live stream (or opens a transient stream when the
 * camera is closed) and hands back the raw sensor-oriented HAL JPEG. All
 * camera ownership, warmup, and the camera-LED state machine live in
 * [CameraSession] / [CaptureService] now.
 */
@SuppressLint("MissingPermission")
class PhotoCapturer(
    private val context: Context,
    private val cameraSession: CameraSession,
    /**
     * Optional low-light path. When the captured JPEG is darker than
     * [LOW_LIGHT_LUMA_THRESHOLD] and this is non-null, the JPEG is discarded
     * and [LowLightCapturer] takes an amplified RAW capture via the SID U-Net.
     */
    private val lowLight: LowLightCapturer? = null
) {

    companion object {
        private const val TAG = "Cap:Photo"
        /** Quality tuned for size + latency, not archival. */
        private const val JPEG_QUALITY: Int = 85

        /**
         * Route through the RAW + SID U-Net path when the JPEG is dim. A
         * normal indoor room at evening with overhead lights off measures
         * ~30-60 mean Y; daylight scenes sit at 100+. 50 catches "dim room"
         * without firing on properly exposed scenes. Value is mean Y out of
         * 255.
         */
        private const val LOW_LIGHT_LUMA_THRESHOLD = 50f
    }

    // Worker executor that drains the requestStill callbacks + disk writes.
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "PhotoCap-exec") }
    // Background thread for denoise + filesync push. Decoupled from the
    // shutter path so the preview overlay fires the moment the JPEG bytes
    // are on disk; denoise happens silently afterwards and overwrites the
    // same file in place. Single-threaded so back-to-back shots serialize
    // their denoise instead of fighting for CPU.
    private val denoiseExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PhotoCap-denoise").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    // [isBusy] is only advisory. All [takePhoto] calls are queued on the
    // shared session's single thread -- rapid-fire button presses never get
    // rejected; they just wait their turn.
    private val busy = AtomicBoolean(false)
    private val pending = java.util.concurrent.atomic.AtomicInteger(0)

    fun isBusy(): Boolean = busy.get()
    fun queuedCount(): Int = pending.get()

    /**
     * Warmup is obsolete under the shared session: [CameraSession] keeps the
     * camera open while any holder (frame subscriber or recorder) exists, and
     * [requestStill] handles the cold-open transparently. Kept as a no-op so
     * the AIDL warmUp path still compiles and is a cheap call when invoked.
     */
    fun warmUp() {}

    /**
     * [onPreview] fires the moment the un-denoised JPEG hits disk. It drives
     * the on-glasses preview overlay and the AIDL onPhotoTaken broadcast.
     *
     * [onDenoised] fires after a background SplitterDenoiser pass overwrites
     * the same file with cleaner bytes. Use it to push the denoised version
     * to the phone via filesync. Default no-op so callers that don't care
     * about denoise can ignore it.
     */
    fun takePhoto(
        onPreview: (File?, Throwable?) -> Unit,
        onDenoised: (File) -> Unit = {},
    ) {
        val q = pending.incrementAndGet()
        Log.i(TAG, "takePhoto enqueued (queued=$q)")
        busy.set(true)
        val tCap = android.os.SystemClock.elapsedRealtime()
        cameraSession.requestStill(
            onJpeg = { jpeg, w, h, rotationDeg ->
                // Serviced on CameraSession's single thread; hop to our own
                // executor so disk write + low-light reroute don't block the
                // camera thread (and so back-to-back stills queue cleanly).
                executor.execute {
                    var capturedFile: File? = null
                    try {
                        capturedFile = writeAndRoute(jpeg, rotationDeg, onPreview)
                    } finally {
                        busy.set(false)
                        pending.decrementAndGet()
                        Log.i(TAG, "still handled totalMs=${android.os.SystemClock.elapsedRealtime() - tCap}")
                    }
                    val file = capturedFile ?: return@execute
                    scheduleDenoise(file, onDenoised)
                }
            },
            onError = { err ->
                busy.set(false)
                pending.decrementAndGet()
                Log.e(TAG, "requestStill failed: ${err.message}")
                onPreview(null, err)
            },
        )
    }

    /**
     * Write the HAL JPEG upright to disk, optionally reroute dark scenes to
     * the low-light RAW path, then fire [onPreview]. Returns the file to be
     * denoised, or null if the low-light path produced the final image (it
     * runs its own denoise) or the write failed.
     *
     * Orientation: the shared-session JPEG is raw sensor-oriented (rotationDeg
     * is the sensor orientation, typically 270). We DECODE, physically rotate
     * the bitmap, and re-encode -- matching [RawStillCapturer], which also
     * bakes rotation into pixels and stamps EXIF NORMAL. EXIF-only orientation
     * is unreliable here because the background denoise pass re-encodes the
     * file and would drop a bare orientation tag, leaving consumers with a
     * sideways image. Baking the rotation survives that overwrite.
     */
    private fun writeAndRoute(
        jpeg: ByteArray,
        rotationDeg: Int,
        onPreview: (File?, Throwable?) -> Unit,
    ): File? {
        val out = FileNamer.photoFile()
        return try {
            val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                ?: throw IllegalStateException("decode of HAL JPEG failed")
            val upright = rotateBitmap(decoded, rotationDeg)
            FileOutputStream(out).use {
                upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
            }
            if (upright !== decoded) decoded.recycle()
            upright.recycle()
            stampExifOrientationNormal(out)
            Log.i(TAG, "photo saved: ${out.absolutePath} bytes=${out.length()} rotationDeg=$rotationDeg")

            // Low-light reroute: if the scene is dark and a low-light path is
            // wired, discard this JPEG and capture an amplified RAW instead.
            // The returned file is the one the preview should reference.
            val finalFile = maybeRouteLowLight(out)
            onPreview(finalFile, null)
            // If the low-light path swapped in its own (already denoised)
            // image, don't run our denoise over it again.
            if (finalFile.absolutePath != out.absolutePath) null else finalFile
        } catch (e: Throwable) {
            Log.e(TAG, "photo capture failed: ${e.message}")
            if (out.exists() && out.length() == 0L) out.delete()
            onPreview(null, e)
            null
        }
    }

    private fun rotateBitmap(src: Bitmap, rotationDeg: Int): Bitmap {
        if (rotationDeg == 0) return src
        // Sensor orientation is the clockwise rotation needed to make the
        // image upright. postRotate uses clockwise-positive degrees.
        val m = android.graphics.Matrix().apply { postRotate(rotationDeg.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, false)
    }

    private fun scheduleDenoise(file: File, onDenoised: (File) -> Unit) {
        denoiseExecutor.execute {
            val tD = android.os.SystemClock.elapsedRealtime()
            try {
                val srcBmp = BitmapFactory.decodeFile(file.absolutePath)
                if (srcBmp == null) {
                    Log.w(TAG, "denoise: decode null for ${file.absolutePath}")
                    onDenoised(file)
                    return@execute
                }
                val denoised = SplitterDenoiser.get(context).denoise(srcBmp)
                FileOutputStream(file).use {
                    denoised.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
                }
                denoised.recycle()
                // The denoise re-encode strips EXIF; re-stamp NORMAL so the
                // already-baked pixel rotation isn't double-applied downstream.
                stampExifOrientationNormal(file)
                Log.i(TAG, "denoise done ${file.name} bytes=${file.length()} durMs=${android.os.SystemClock.elapsedRealtime() - tD}")
                onDenoised(file)
            } catch (e: Throwable) {
                Log.e(TAG, "denoise failed: ${e.message} -- pushing un-denoised JPEG so phone still gets photo")
                onDenoised(file)
            }
        }
    }

    /**
     * Permanent teardown -- called from CaptureService.onDestroy. Stops the
     * executors. The camera itself is owned and released by [CameraSession].
     */
    fun shutdown() {
        executor.shutdown()
        denoiseExecutor.shutdown()
    }

    /**
     * If a low-light path is wired and the captured JPEG is dark, discard the
     * JPEG and do a RAW+SID capture instead. Returns the file that should be
     * reported back to the client.
     */
    private fun maybeRouteLowLight(jpegFile: File): File {
        val ll = lowLight
        if (ll == null) {
            Log.i(TAG, "low-light routing skipped: lowLight=null (not wired into PhotoCapturer)")
            return jpegFile
        }
        if (!ll.isReady()) {
            Log.i(TAG, "low-light routing skipped: LowLightCapturer not ready")
            return jpegFile
        }
        val luma = meanLuma(jpegFile) ?: return jpegFile
        Log.i(TAG, "scene mean luma=${"%.1f".format(luma)} (threshold=$LOW_LIGHT_LUMA_THRESHOLD)")
        if (luma >= LOW_LIGHT_LUMA_THRESHOLD) return jpegFile

        Log.i(TAG, "routing to low-light RAW path")
        // Keep a sibling copy of the original JPEG for debugging.
        try {
            val debugCopy = File(jpegFile.parent, jpegFile.nameWithoutExtension + ".original.jpg")
            jpegFile.copyTo(debugCopy, overwrite = true)
            Log.i(TAG, "original JPEG preserved at ${debugCopy.absolutePath}")
        } catch (e: Throwable) {
            Log.w(TAG, "failed to preserve original JPEG: ${e.message}")
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        val result = arrayOfNulls<File>(1)
        val err = arrayOfNulls<Throwable>(1)
        ll.capture { f, e ->
            result[0] = f; err[0] = e; latch.countDown()
        }
        if (!latch.await(60, java.util.concurrent.TimeUnit.SECONDS)) {
            Log.w(TAG, "low-light capture timed out -- keeping original JPEG")
            return jpegFile
        }
        val llFile = result[0]
        if (llFile == null) {
            Log.w(TAG, "low-light capture failed (${err[0]?.message}) -- keeping original JPEG")
            return jpegFile
        }
        try {
            if (llFile.absolutePath != jpegFile.absolutePath) {
                jpegFile.delete()
                if (!llFile.renameTo(jpegFile)) {
                    return llFile
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "swap failed: ${e.message}")
            return llFile
        }
        return jpegFile
    }

    /** Mean luma of the decoded JPEG, downsampled to ~64 px. Null on decode failure. */
    private fun meanLuma(file: File): Float? {
        return try {
            val opts = BitmapFactory.Options().apply { inSampleSize = 32 } // aggressive downsample
            val bmp: Bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: run {
                Log.w(TAG, "meanLuma: decodeFile returned null for ${file.absolutePath} (exists=${file.exists()}, size=${file.length()})")
                return null
            }
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            bmp.recycle()
            var sum = 0.0
            var minR = 255; var maxR = 0
            var minG = 255; var maxG = 0
            var minB = 255; var maxB = 0
            for (c in px) {
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r < minR) minR = r; if (r > maxR) maxR = r
                if (g < minG) minG = g; if (g > maxG) maxG = g
                if (b < minB) minB = b; if (b > maxB) maxB = b
                // Standard Rec. 709 luma weights.
                sum += 0.2126 * r + 0.7152 * g + 0.0722 * b
            }
            val mean = (sum / px.size).toFloat()
            val firstPx = if (px.isNotEmpty()) String.format("%08x", px[0]) else "n/a"
            Log.i(TAG, "meanLuma: size=${w}x${h} sampled=${px.size} meanY=${"%.1f".format(mean)} R[${minR}..${maxR}] G[${minG}..${maxG}] B[${minB}..${maxB}] first=${firstPx}")
            mean
        } catch (e: Throwable) {
            Log.w(TAG, "meanLuma failed: ${e.message}")
            null
        }
    }

    /**
     * Pixels are baked upright in [writeAndRoute]; mark EXIF as NORMAL so
     * viewers and downstream encoders don't re-rotate. Matches
     * [RawStillCapturer]'s orientation convention.
     */
    private fun stampExifOrientationNormal(file: File) {
        try {
            val exif = androidx.exifinterface.media.ExifInterface(file.absolutePath)
            exif.setAttribute(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL.toString(),
            )
            exif.saveAttributes()
        } catch (e: Exception) {
            Log.w(TAG, "EXIF stamp failed: ${e.message}")
        }
    }
}
