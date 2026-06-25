package com.repository.glasses.capture

import android.annotation.SuppressLint
import android.content.Context
import com.repository.glasses.tracing.GT
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.view.Surface
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Replacement for the HAL-JPEG still path. Captures a burst of RAW_SENSOR
 * frames at manual ISO + exposure (which the Qualcomm HAL honors on the
 * RAW path, unlike the JPEG path), averages them in RAW space for
 * denoising, runs our own bilinear demosaic + WB + sRGB gamma, then
 * encodes a JPEG.
 *
 * Output JPEG is written to [FileNamer.photoFile]. The on-disk JPEG is
 * full sensor resolution (4032x3024) so downstream phone sync has the
 * same filename convention the HAL path produced.
 */
@SuppressLint("MissingPermission")
class RawStillCapturer(
    private val context: Context,
    private val cameraSession: CameraSession,
    /**
     * Invoked after a RESUMED photo (one recovered from disk on startup, with no
     * live [takePhoto] callback) has been demosaiced + denoised and its full-res
     * JPEG written back over the gray preview. CaptureService wires this to its
     * filesync notify so the phone -- which currently holds the gray preview --
     * re-syncs the corrected color image. Not called for live photos; those use
     * their own onFinal. Default no-op so the class compiles standalone.
     */
    private val onResumedPhotoProcessed: (File) -> Unit = {},
) {

    companion object {
        private const val TAG = "Cap:RawStill"

        /** Magic + version for the pending RAW sidecar header. Lets the processor
         *  reject a truncated/corrupt file instead of demosaicing garbage. */
        private const val RAW_MAGIC = 0x52415731 // "RAW1"
        /** v4 carries the 3 per-shot HAL WB gains (wbR,wbG,wbB). Exposure is no
         *  longer persisted: the demosaic computes its own percentile auto-level
         *  from the RAW itself, so there is no gain to store. Older versions are
         *  rejected on read (a transient in-flight raw is just discarded). */
        private const val RAW_VERSION = 4
        /** magic(4) + version(4) + w(4) + h(4) + blackLevel(4 float) + whiteLevel(4 float)
         *  + wbR(4 float) + wbG(4 float) + wbB(4 float) = 36 bytes. */
        private const val RAW_HEADER_BYTES = 36
        /** Hidden subdir under FileNamer.rootDir holding pending RAW sidecars. The
         *  leading dot keeps them out of the gallery; filesync only ever notifies
         *  the sibling .jpg so the raws never sync to the phone. */
        private const val PENDING_DIR_NAME = ".pending"
        /** Sanity bound: more than this many queued raws means something is wrong
         *  (a wedged processor). We still process them, just log a warning. */
        private const val PENDING_RUNAWAY_WARN = 20

        /** Hard upper bound on AE convergence wait. The actual capture fires as
         *  soon as CONTROL_AE_STATE reaches CONVERGED, so this only kicks in
         *  for scenes where the HAL never reports convergence. */
        // Max wait for AE to converge during warmup. The await returns the INSTANT
        // AE settles, so a warm camera still finishes in a few hundred ms; this cap
        // only matters on a COLD camera (fresh boot) where AE must ramp from the
        // dark cold-start exposure -- 1.5s was too short for that and left the first
        // photo dark/black. 4s gives cold AE room to converge while still bounding a
        // genuinely stuck HAL.
        private const val AE_WARMUP_MS = 4000L

        // Exposure correction is no longer an absolute-target DRO gain. This Rokid
        // HAL exposes RAW conservatively (a well-lit scene's green sits at codes
        // ~78-94 of 1023), so an absolute target/mean clamped to 8x on nearly every
        // indoor scene and just amplified noise. Instead the demosaic applies a
        // PERCENTILE auto-level computed from the frame itself (see RawDemosaic):
        // it drives the high percentile of the linear luma to near-white, so the
        // scene is correctly exposed regardless of the low absolute codes, and a
        // bright/clipping scene gets ~1x. Nothing exposure-related is persisted --
        // the demosaic recomputes its own level from the RAW.

        /** Tiny PRIVATE SurfaceTexture preview size. RAW + PRIVATE(preview) is the
         *  known-good combo on this HAL (the ZSLPreviewRaw usecase); the preview
         *  surface only drives AE warmup frames -- it is never read. */
        private const val PREVIEW_TEX_W = 640
        private const val PREVIEW_TEX_H = 480

        /** Number of RAW frames averaged per capture. sqrt(N) noise reduction. */
        private const val BURST_N = 3

        /** Upper bound on the exclusive device borrow. The body runs
         *  synchronously on CameraSession's handler thread and the caller
         *  blocks for up to this long; if it is SHORTER than the body's
         *  worst-case runtime the caller wrongly reports "camera busy" while
         *  the capture is still proceeding. The body's in-lambda awaits sum to:
         *    session configure   3s  (line ~293)
         *    AE warmup           1.5s (AE_WARMUP_MS, line ~334)
         *    burst capture      10s  (line ~368)
         *    raw burst merge    30s  (line ~372)
         *  ~= 44.5s worst case. 50s gives a few seconds of margin over that. */
        private const val BORROW_TIMEOUT_MS = 50000L

        /** JPEG output quality. */
        private const val JPEG_QUALITY = 95

        /** DEBUG (until-reboot) system property. When set to "1" the ~60-107s
         *  SplitterDenoiser pass is skipped and the demosaiced full-res JPEG (with
         *  the percentile auto-level exposure already applied) becomes the final
         *  file -- so exposure iteration is fast. Set via
         *  `adb shell setprop debug.glasses.capture.skip_denoise 1`. It is a
         *  NON-persist prop, so it is automatically reset on reboot; there is no UI
         *  and no persistence. Read via SystemProperties reflection ([skipDenoise]). */
        private const val SKIP_DENOISE_PROP = "debug.glasses.capture.skip_denoise"

        /** Long edge of the first-pass preview JPEG. Matches the listener overlay's
         *  TARGET_LONG_EDGE_PX so the overlay doesn't even need to downsample. Keeps
         *  the preview encode well under 200 ms on this SoC. Full-res denoised JPEG
         *  overwrites the file at the same path later, so sync still gets full fidelity. */
        private const val PREVIEW_LONG_EDGE_PX = 960

        /** Lower quality is fine for preview -- the denoised full-res takes over soon
         *  and the overlay renders at a tiny rendered size anyway. */
        private const val PREVIEW_JPEG_QUALITY = 80

        /** FALLBACK white-balance gains. Used ONLY when the HAL does not report
         *  COLOR_CORRECTION_GAINS in the capture result (some HALs return null).
         *  These are fixed gains tuned for one lighting condition; boosting both R
         *  and B ~1.85x vs G pushes neutrals magenta under other lighting, which is
         *  why the live path now prefers the HAL's per-shot AWB gains (read from
         *  CaptureResult.COLOR_CORRECTION_GAINS) and only falls back to these. */
        private const val WB_R = 1.88f
        private const val WB_G = 1.00f
        private const val WB_B = 1.83f

        /** Frames averaged for the ReID still. ReID needs speed over the sqrt(N)
         *  noise reduction the photo path wants, so a single frame is enough --
         *  ML Kit tolerates the extra read noise and the lower latency lets the
         *  periodic driver cycle faster. */
        private const val REID_BURST_N = 1

        /** RGGB super-pixel stride for the ReID demosaic. The photo path's
         *  [RawDemosaic.binToBitmap] is half-res (2016x1512) and the per-pixel
         *  Math.pow sRGB encode over ~3M pixels cost ~16s. ReID does not need
         *  full resolution, so [RawDemosaic.binToBitmapFast] strides 4 source
         *  pixels per output pixel: 4032x3024 -> 1008x756 (~1008px long edge,
         *  in the wanted ~720-1008 band) and uses a gamma LUT instead of pow.
         *  Demosaic time drops to well under 2s. */
        private const val REID_DOWNSAMPLE = 4

        /** JPEG quality for the ReID still. Lower than the photo path -- the
         *  bytes are decoded immediately by ML Kit and discarded, never archived. */
        private const val REID_JPEG_QUALITY = 85

        /** Exclusive device borrow bound for the ReID still. Far shorter than the
         *  photo path's because there is no SplitterDenoiser pass: warmup (<=1.5s)
         *  + 1-frame burst + demosaic + JPEG encode all fit well under this. */
        private const val REID_BORROW_TIMEOUT_MS = 12000L

        /** Bounded in-session retries for a demosaic that threw (path b in
         *  [enqueueProcess]). A demosaic failure is almost always transient RAM/CPU
         *  contention (a 2nd photo + the rPPG stream), so a short delay then a retry
         *  on the same raw usually succeeds and lets the FULL-COLOR image sync
         *  WITHOUT ever shipping the gray preview. Capped so a permanently-failing
         *  raw (e.g. genuinely corrupt) cannot re-enqueue forever -- after the cap we
         *  leave the raw sidecar on disk so the next process-start [resumePending]
         *  picks it up, rather than burning the CPU in a tight retry loop. */
        private const val DEMOSAIC_RETRY_MAX = 3

        /** Delay before a bounded demosaic retry, giving the contending denoise /
         *  rPPG / 2nd-photo work time to drain before we re-attempt off the same raw. */
        private const val DEMOSAIC_RETRY_DELAY_MS = 4000L
    }

    // Camera callbacks must not share a thread with the executor (doCapture
    // blocks on latches; callback handler deadlocks if shared).
    private val handlerThread = HandlerThread("RawStill-cb").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "RawStill-exec") }
    // Off-camera worker that runs the heavy full-resolution demosaic (~17s) THEN
    // the SplitterNet denoise (~60s) per photo. The camera executor PERSISTS the
    // accumulated RAW burst to a disk sidecar the instant the burst is captured,
    // frees the in-RAM ShortArray, and enqueues ONLY the sidecar path here. This
    // worker then loads exactly ONE raw from disk at a time, processes it, and
    // deletes the sidecar -- so RAM holds at most one raw + its working bitmaps
    // regardless of how many photos are queued (the backlog lives on disk, not in
    // RAM). Heavy work for successive photos serializes here (one thread), but it
    // NEVER blocks the camera executor.
    private val processExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "RawStill-process") }

    /** Schedules the bounded in-session demosaic retry (path b in [enqueueProcess]).
     *  A separate scheduled executor only DELAYS the re-enqueue; the actual retry
     *  still runs on the single-threaded [processExecutor], so RAM stays bounded to
     *  one raw at a time. Exists so a transiently-failed demosaic re-attempts the
     *  FULL-COLOR conversion in-session instead of waiting for the next process
     *  restart's [resumePending] -- without that, a gray preview would otherwise be
     *  the phone's only copy for a long time (and we must NEVER sync the gray one). */
    private val retryScheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "RawStill-retry") }

    /** Dedicated thread for the fast-preview path so it doesn't contend with the
     *  camera callback thread or the denoise worker. Keeps preview latency low
     *  even when a previous denoise is still running. */
    private val previewExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "RawStill-preview") }
    private val busy = AtomicBoolean(false)

    /**
     * Count of heavy SplitterNet denoise passes currently running on
     * [processExecutor]. A COUNTER, not a bool, so concurrent resume jobs and a
     * live photo can't clear each other's pause prematurely (the counter only
     * reaches 0 when the LAST denoise finishes). Incremented before the denoise
     * block and decremented in a finally that always runs.
     *
     * The ~67-105s denoise pins all 4 A55 cores; running it WHILE the live rPPG
     * YUV stream is up starves the in-flight photo's demosaic/burst and is the
     * contention that silently aborts a back-to-back photo. So while this is > 0
     * CameraSession drops the rPPG YUV reader (see [onDenoiseStateChanged] /
     * denoiseInFlightProvider) and rebuilds it once denoise completes.
     */
    private val denoiseInFlight = java.util.concurrent.atomic.AtomicInteger(0)

    /** True while >= 1 heavy denoise is running. CameraSession consults this (via
     *  denoiseInFlightProvider) to decide whether the rPPG stream may run. */
    fun isDenoiseInFlight(): Boolean = denoiseInFlight.get() > 0

    /**
     * Notified on every [denoiseInFlight] transition (both increment and
     * decrement) so the camera owner can re-evaluate the rPPG stream off the
     * denoise critical section. Wired by CaptureService to
     * CameraSession.onDenoiseStateChanged(); kept as a plain lambda so this class
     * has no compile-time dependency on CameraSession's concrete type. Default
     * no-op so the class compiles standalone.
     */
    @Volatile var onDenoiseStateChanged: () -> Unit = {}

    /**
     * Count of func-button photos that have been requested but whose camera/session
     * work has not yet completed. Set on the binder thread the instant [takePhoto]
     * is called, BEFORE the work is posted, and cleared the moment the RAW burst
     * is captured (the camera is released then). The heavy demosaic + ~60s denoise
     * tail runs on [processExecutor] and does NOT hold the camera, so it is not
     * counted here.
     *
     * Func-button photos are user actions and take priority: a periodic ReID frame
     * is droppable, so [captureReidFrame] checks this and yields (returns an error,
     * which the app-side driver treats as "retry next tick") whenever a photo is
     * pending or in progress. This guarantees a photo never queues behind a stream
     * of ReID frames -- at most ONE already-in-flight ReID frame finishes before the
     * photo drains. Photos themselves ALWAYS enqueue (FIFO) and never abort.
     */
    @Volatile private var photoPending = 0
    private val photoPendingLock = Any()

    /**
     * Two-phase capture:
     *   [onPreview] fires once the undenoised JPEG is on disk (a few
     *       seconds after button press). Caller should kick off the local
     *       preview animation here — the file is viewable even before
     *       SplitterNet runs.
     *   [onFinal]   fires after SplitterNet denoising finishes and the
     *       JPEG has been overwritten in place with the cleaner output.
     *       Caller should sync to the phone here, NOT on [onPreview].
     *
     * On error, [onPreview] fires with (null, err) and [onFinal] is not
     * called. If denoise fails, [onFinal] fires with the preview file +
     * the throwable so callers can still sync the undenoised version if
     * they want.
     */
    fun takePhoto(
        onPreview: (File?, Throwable?) -> Unit,
        onFinal: (File, Throwable?) -> Unit = { _, _ -> },
        // Fired the instant the RAW burst is fully acquired -- the camera no longer
        // needs the scene held still (the subsequent demosaic/denoise work off the
        // captured frames). Drives the "photo taken, you can move now" checkmark.
        onShutterDone: () -> Unit = {},
    ) = GT.section("cap.raw.capture") {
        // PRIORITY: mark a photo pending on the binder thread, BEFORE posting, so any
        // captureReidFrame call racing in right now already sees photoPending > 0 and
        // yields instead of enqueuing ahead of this photo. Photos always enqueue on the
        // FIFO executor and NEVER abort on busy -- at worst the photo waits for the single
        // ReID frame already in flight (busy set by it), then runs; no new ReID frame can
        // be admitted while photoPending > 0.
        synchronized(photoPendingLock) { photoPending++ }
        Log.i(TAG, "takePhoto entry busy=${busy.get()} photoPending=$photoPending")
        executor.execute {
            // Acquire busy by waiting, not aborting. On the single-thread FIFO executor
            // any in-flight ReID frame has already run to completion before this body
            // starts, so compareAndSet succeeds immediately. The spin is a belt-and-braces
            // guard for the theoretical case where busy is held by a non-executor path.
            while (!busy.compareAndSet(false, true)) {
                try { Thread.sleep(5) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
            }
            val t0 = android.os.SystemClock.elapsedRealtime()
            val burst: BurstResult
            // Wrap onPreview so it fires exactly once -- either from the fast-path
            // (frame 0 arrives) inside captureBurst, or from here if the burst
            // fails before the fast path had a chance to run.
            val previewFired = java.util.concurrent.atomic.AtomicBoolean(false)
            val onPreviewOnce: (File?, Throwable?) -> Unit = { f, e ->
                if (previewFired.compareAndSet(false, true)) onPreview(f, e)
            }
            try {
                burst = captureBurst(onPreviewOnce, t0, onShutterDone)
            } catch (e: Throwable) {
                Log.e(TAG, "takePhoto burst failed: ${e.message}")
                onPreviewOnce(null, e)
                // Camera work for this photo is over (failed). Release BOTH the software
                // lock and the pending mark so a stuck flag can never permanently starve
                // ReID frames or future photos. No heavy work was posted, so nothing else
                // to clean up.
                busy.set(false)
                synchronized(photoPendingLock) { if (photoPending > 0) photoPending-- }
                return@execute
            }
            // The RAW burst is captured and accumulated; the camera device was already
            // released when borrowDeviceExclusive returned inside captureBurst. Release
            // the SOFTWARE lock + the priority mark NOW, BEFORE the heavy demosaic, so
            // the next func-button photo or ReID frame can immediately re-acquire the
            // camera and start its own burst. The full demosaic + denoise run entirely
            // off the camera executor on processExecutor below.
            busy.set(false)
            synchronized(photoPendingLock) { if (photoPending > 0) photoPending-- }
            // Persist the RAW to disk and FREE it from RAM immediately, then enqueue
            // ONLY the lightweight sidecar path. This is the OOM fix: the avg
            // ShortArray (~24MB) is written-then-dropped here, so no more than one
            // raw is ever resident -- the queue backlog lives on disk, not in RAM.
            val rawFile: File
            try {
                rawFile = writePendingRaw(burst)
            } catch (e: Throwable) {
                Log.e(TAG, "takePhoto persist raw failed: ${e.message}")
                // The gray preview JPEG already exists at burst.out; report failure so
                // the caller is not left waiting. busy/photoPending already released.
                onPreviewOnce(burst.out, null)
                onFinal(burst.out, e)
                return@execute
            }
            // `burst` (holding avg) is now unreferenced past this point and GC-eligible;
            // the disk sidecar is the only copy of the raw from here on.
            enqueueProcess(rawFile, onPreviewOnce, onFinal, t0)
        }
    }

    /**
     * The accumulated RAW burst plus the sensor metadata needed to demosaic it.
     * Produced by [captureBurst] on the camera executor (camera already released);
     * its `avg` is immediately serialized to a disk sidecar by [writePendingRaw]
     * and then freed, so it never lingers in RAM across the heavy processing.
     */
    private class BurstResult(
        val out: File,
        val avg: ShortArray,
        val w: Int,
        val h: Int,
        val blackLevel: Float,
        val whiteLevel: Float,
        /** Per-shot white-balance gains. When the HAL reported
         *  COLOR_CORRECTION_GAINS for this shot these hold the AWB-estimated
         *  multipliers (R & B >= 1, G ~1); otherwise they default to the WB_*
         *  fallback constants. Persisted into the raw sidecar (v2 header) so the
         *  disk-backed demosaic applies the same gains the live capture saw. */
        val wbR: Float,
        val wbG: Float,
        val wbB: Float,
    )

    /** Pending-raw directory under the photo root, created on demand. */
    private fun pendingDir(): File =
        File(FileNamer.ensureRoot(), PENDING_DIR_NAME).apply { if (!exists()) mkdirs() }

    /** Map a pending RAW sidecar (.../.pending/IMG_<ts>.raw) to its sibling JPEG
     *  in the photo root (.../IMG_<ts>.jpg). The JPEG already holds the gray
     *  preview; processing overwrites it with the full-res color image. */
    private fun jpegForRaw(rawFile: File): File =
        File(FileNamer.ensureRoot(), rawFile.nameWithoutExtension + ".jpg")

    /**
     * Serialize the burst-averaged RAW + the metadata needed to demosaic it later
     * into a single binary sidecar in the [pendingDir]. Fixed little-endian header
     * (magic, version, w, h, blackLevel, whiteLevel) followed by the ShortArray
     * bytes. The sidecar name mirrors the photo's JPEG name so [jpegForRaw] can
     * recover the output path. Written to a .tmp then atomically renamed so a
     * crash mid-write never leaves a half-written sidecar the resume path would
     * try to process.
     */
    private fun writePendingRaw(burst: BurstResult): File {
        val out = File(pendingDir(), burst.out.nameWithoutExtension + ".raw")
        val tmp = File(out.parentFile, out.name + ".tmp")
        val avg = burst.avg
        val bb = java.nio.ByteBuffer.allocate(RAW_HEADER_BYTES + avg.size * 2)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.putInt(RAW_MAGIC)
        bb.putInt(RAW_VERSION)
        bb.putInt(burst.w)
        bb.putInt(burst.h)
        bb.putFloat(burst.blackLevel)
        bb.putFloat(burst.whiteLevel)
        bb.putFloat(burst.wbR)
        bb.putFloat(burst.wbG)
        bb.putFloat(burst.wbB)
        // asShortBuffer() is a view at bb's CURRENT position; writing into it does
        // NOT advance bb's own position. Advance bb past the payload manually, then
        // rewind so channel.write writes the WHOLE buffer (header + payload) from 0.
        // Without this, write() starts at position=RAW_HEADER_BYTES and the header
        // never lands on disk -> readPendingRaw sees a bad magic and discards it.
        bb.asShortBuffer().put(avg)
        bb.position(bb.position() + avg.size * 2)
        bb.flip()
        FileOutputStream(tmp).use { it.channel.write(bb) }
        if (!tmp.renameTo(out)) {
            tmp.delete()
            throw java.io.IOException("rename ${tmp.name} -> ${out.name} failed")
        }
        Log.i(TAG, "pending raw written ${out.absolutePath} bytes=${out.length()} (avg freed from RAM)")
        return out
    }

    /**
     * Reconstruct a [BurstResult] from a pending RAW sidecar. Returns null (and
     * deletes the sidecar) if the file is truncated, has a bad magic/version, or
     * the declared dimensions don't match the payload -- so the processor skips
     * a corrupt raw rather than crashing on it.
     */
    private fun readPendingRaw(rawFile: File): BurstResult? {
        return try {
            val bytes = rawFile.readBytes()
            if (bytes.size < RAW_HEADER_BYTES) {
                Log.w(TAG, "pending raw ${rawFile.name} too small (${bytes.size}B); discarding")
                rawFile.delete(); return null
            }
            val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val magic = bb.int
            val version = bb.int
            // Only the current version is accepted. A raw from an older build is a
            // transient in-flight artifact (the queue drains in seconds), so an old
            // version is just discarded rather than carried with a compat shim.
            if (magic != RAW_MAGIC || version != RAW_VERSION) {
                Log.w(TAG, "pending raw ${rawFile.name} bad magic/version ($magic/$version); discarding")
                rawFile.delete(); return null
            }
            val w = bb.int
            val h = bb.int
            val blackLevel = bb.float
            val whiteLevel = bb.float
            val wbR = bb.float; val wbG = bb.float; val wbB = bb.float
            val expectedShorts = w.toLong() * h.toLong()
            val actualShorts = (bytes.size - RAW_HEADER_BYTES).toLong() / 2
            if (w <= 0 || h <= 0 || actualShorts < expectedShorts) {
                Log.w(TAG, "pending raw ${rawFile.name} payload mismatch w=$w h=$h want=$expectedShorts have=$actualShorts; discarding")
                rawFile.delete(); return null
            }
            val avg = ShortArray(w * h)
            bb.asShortBuffer().get(avg)
            BurstResult(jpegForRaw(rawFile), avg, w, h, blackLevel, whiteLevel, wbR, wbG, wbB)
        } catch (e: Throwable) {
            Log.w(TAG, "pending raw ${rawFile.name} read failed: ${e.message}; discarding")
            try { rawFile.delete() } catch (_: Exception) {}
            null
        }
    }

    /**
     * Enqueue ONE processing job: load the raw sidecar from disk, demosaic,
     * denoise, overwrite the JPEG, delete the sidecar, fire callbacks. Because
     * [processExecutor] is single-threaded and reads from disk one job at a time,
     * RAM holds at most one raw + its working bitmaps no matter how deep the
     * queue is. [onPreviewOnce]/[onFinal]/[onResumed] are the live-photo hooks;
     * resumed photos pass no-op preview/final and route through [onResumed].
     */
    private fun enqueueProcess(
        rawFile: File,
        onPreviewOnce: (File?, Throwable?) -> Unit,
        onFinal: (File, Throwable?) -> Unit,
        t0: Long,
        onResumed: ((File) -> Unit)? = null,
        attempt: Int = 0,
    ) {
        processExecutor.execute process@{
            val burst = readPendingRaw(rawFile) ?: run {
                // Corrupt/missing raw already logged + deleted in readPendingRaw.
                // Color is UNRECOVERABLE (no raw left to demosaic), and the on-disk
                // JPEG holds only the GRAYSCALE preview. The phone must NEVER receive
                // a gray image, so we do NOT sync it -- better no photo on the phone
                // than a black-and-white one. Fire onPreviewOnce(null,err) only (a UX
                // error broadcast, no filesync notify); onFinal must NOT be called
                // here because CaptureService wires onFinal -> notifyPhotoSync.
                onPreviewOnce(null, IllegalStateException("pending raw unreadable"))
                return@process
            }
            val file = burst.out
            val binned: Bitmap
            try {
                binned = GT.section("cap.raw.demosaic") { demosaicBurst(burst) }
                // Fast preview should already have fired from frame 0 for live photos.
                // If it did not (fast-preview path threw), deliver it now from the
                // freshly written full-res file so every photo still gets one onPreview.
                onPreviewOnce(file, null)
            } catch (e: Throwable) {
                Log.e(TAG, "process demosaic failed (attempt=$attempt): ${e.message}")
                // Demosaic aborted (RAM/CPU contention from a 2nd photo + rPPG). The
                // on-disk JPEG still holds only the GRAYSCALE preview. The phone must
                // NEVER receive the gray image, so this path syncs NOTHING: we fire
                // neither onFinal (-> notifyPhotoSync) nor an onPreview-error. The raw
                // sidecar is intentionally LEFT on disk so the full COLOR image can be
                // produced and synced later.
                //
                // To guarantee the phone eventually gets the color image WITHOUT
                // shipping gray -- and without waiting for the next capture-process
                // restart's resumePending -- schedule a BOUNDED in-session retry of
                // this same raw. Bounded so a permanently-failing raw can't re-enqueue
                // forever; after the cap we give up and leave the raw for the next
                // process-start resume.
                if (attempt + 1 < DEMOSAIC_RETRY_MAX) {
                    Log.w(TAG, "scheduling demosaic retry ${attempt + 2}/$DEMOSAIC_RETRY_MAX for ${rawFile.name} in ${DEMOSAIC_RETRY_DELAY_MS}ms")
                    try {
                        retryScheduler.schedule({
                            enqueueProcess(rawFile, onPreviewOnce, onFinal, t0, onResumed, attempt + 1)
                        }, DEMOSAIC_RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
                    } catch (re: Throwable) {
                        // Scheduler shut down (service tearing down): leave the raw on
                        // disk for the next process-start resumePending.
                        Log.w(TAG, "demosaic retry schedule failed: ${re.message}")
                    }
                } else {
                    Log.e(TAG, "demosaic retries exhausted for ${rawFile.name}; leaving raw for next process-start resume")
                }
                return@process
            }
            // DEBUG (until-reboot): when SKIP_DENOISE_PROP is "1", short-circuit the
            // ~60-107s SplitterDenoiser pass entirely. The already-written demosaiced
            // full-res JPEG (binToBitmap output, carrying the percentile auto-level
            // exposure) is left in place as the final file. We still write the upright
            // full-res demosaic over the gray preview, fire onFinal/onResumed, and
            // delete the sidecar -- so filesync, callbacks and cleanup all behave
            // exactly as the denoise path, just without the denoise overwrite. The
            // flag is a non-persist sysprop, so it resets on reboot.
            if (skipDenoise()) {
                try {
                    FileOutputStream(file).use { binned.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                    binned.recycle()
                    stampExifOrientationNormal(file)
                    if (!rawFile.delete()) Log.w(TAG, "pending raw delete failed ${rawFile.absolutePath}")
                    Log.i(TAG, "skip_denoise=1: using undenoised demosaic as final ${file.absolutePath} totalMs=${android.os.SystemClock.elapsedRealtime() - t0}")
                    onFinal(file, null)
                    onResumed?.invoke(file)
                } catch (e: Throwable) {
                    Log.e(TAG, "skip_denoise write failed: ${e.message}")
                    if (!binned.isRecycled) binned.recycle()
                    onFinal(file, e)
                }
                return@process
            }
            // PAUSE rPPG ACROSS THE HEAVY DENOISE ONLY. The ~67-105s SplitterNet
            // pass saturates all 4 A55 cores; overlapping it with the live rPPG
            // YUV stream starves an in-flight photo's burst/demosaic and is the
            // contention that aborts a back-to-back photo. Increment BEFORE the
            // denoise + notify so CameraSession drops the rPPG reader; the finally
            // ALWAYS decrements (success, denoise-failure, or any exception/OOM-
            // survivable error) + notifies so the stream rebuilds afterwards. The
            // ~17s demosaic above is deliberately NOT gated.
            denoiseInFlight.incrementAndGet()
            try { onDenoiseStateChanged() } catch (_: Throwable) {}
            try {
            GT.section("cap.raw.denoise") {
                try {
                    val tD = android.os.SystemClock.elapsedRealtime()
                    val denoised = SplitterDenoiser.get(context).denoise(binned)
                    FileOutputStream(file).use { denoised.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                    denoised.recycle()
                    stampExifOrientationNormal(file)
                    // Cleanup: the full-res color JPEG is on disk, so the raw backlog
                    // entry is done. Delete the sidecar so it is not reprocessed.
                    if (!rawFile.delete()) Log.w(TAG, "pending raw delete failed ${rawFile.absolutePath}")
                    Log.i(TAG, "process done ${file.absolutePath} denoiseMs=${android.os.SystemClock.elapsedRealtime() - tD} totalMs=${android.os.SystemClock.elapsedRealtime() - t0}")
                    onFinal(file, null)
                    onResumed?.invoke(file)
                } catch (e: Throwable) {
                    Log.e(TAG, "process denoise failed: ${e.message}")
                    // Denoise failed but demosaic succeeded -- the gray preview is still on
                    // disk. Leave the raw so a future resume can retry rather than stranding
                    // the photo as gray forever.
                    onFinal(file, e)
                }
            }
            } finally {
                // Always clear the pause + notify so the rPPG stream resumes,
                // even if denoise threw or the process was killed mid-write.
                denoiseInFlight.decrementAndGet()
                try { onDenoiseStateChanged() } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Crash-resume entry point. Called from CaptureService.onCreate. Scans the
     * pending dir for RAW sidecars left over from a previous run (e.g. an OOM
     * kill mid-queue -- the very failure that left 3 gray photos) and enqueues
     * each one for full demosaic + denoise. Each recovered photo's JPEG (which
     * currently holds only the gray fast-preview) is overwritten with the
     * full-res color image and then RE-SYNCED to the phone via
     * [onResumedPhotoProcessed], so the gray previews self-correct. Corrupt
     * sidecars are skipped + deleted inside the processor.
     */
    fun resumePending() {
        val dir = File(FileNamer.rootDir, PENDING_DIR_NAME)
        val raws = dir.listFiles { f -> f.isFile && f.name.endsWith(".raw") }
        if (raws.isNullOrEmpty()) {
            Log.i(TAG, "resumePending: no leftover raws")
            return
        }
        if (raws.size > PENDING_RUNAWAY_WARN) {
            Log.w(TAG, "resumePending: ${raws.size} pending raws (> $PENDING_RUNAWAY_WARN) -- possible runaway backlog")
        }
        Log.i(TAG, "resumePending: re-enqueueing ${raws.size} leftover raw(s)")
        // Oldest first so recovered photos finish in capture order.
        for (rawFile in raws.sortedBy { it.lastModified() }) {
            val t0 = android.os.SystemClock.elapsedRealtime()
            enqueueProcess(
                rawFile,
                onPreviewOnce = { _, _ -> },
                onFinal = { _, _ -> },
                t0 = t0,
                onResumed = { file ->
                    Log.i(TAG, "resumePending: re-syncing recovered photo ${file.absolutePath}")
                    onResumedPhotoProcessed(file)
                },
            )
        }
    }

    /**
     * Run the RAW burst on the camera executor and return the accumulated,
     * burst-averaged RAW frame plus sensor metadata. The heavy full-resolution
     * demosaic is NOT done here -- it runs later in [demosaicBurst] off the camera
     * executor so the camera frees up immediately after the burst. The fast preview
     * (frame 0 -> coarse grayscale JPEG -> [onEarlyPreview]) still runs from inside
     * here on [previewExecutor], independent of the camera executor.
     */
    private fun captureBurst(
        onEarlyPreview: (File?, Throwable?) -> Unit = { _, _ -> },
        takePhotoStartMs: Long = android.os.SystemClock.elapsedRealtime(),
        onShutterDone: () -> Unit = {},
    ): BurstResult = GT.section("cap.raw.burst") {
        val out = FileNamer.photoFile()
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = findRawCapableCamera(manager) ?: throw IllegalStateException("no RAW camera")
        val chars = manager.getCameraCharacteristics(cameraId)
        val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("no stream config")
        val rawSize = streamMap.getOutputSizes(ImageFormat.RAW_SENSOR)?.firstOrNull()
            ?: throw IllegalStateException("no RAW sizes")
        val w = rawSize.width
        val h = rawSize.height
        val blackLevel = (chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            ?.getOffsetForIndex(0, 0) ?: 64).toFloat()
        val whiteLevel = (chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toFloat()
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
        Log.i(TAG, "doCapture ${w}x${h} burstN=$BURST_N black=$blackLevel white=$whiteLevel (AE_ON warmup then LOCK)")

        // Accumulator for burst averaging. Each sample <= whiteLevel (~1023),
        // BURST_N=3 -> sum <= 3069, fits comfortably in int32.
        val acc = IntArray(w * h)
        val received = java.util.concurrent.atomic.AtomicInteger(0)
        val imageLatch = CountDownLatch(1)
        val frameErr = arrayOfNulls<Throwable>(1)

        // RAW reader holds the BURST_N burst frames. No separate metering frame:
        // metering off a distinct RAW frame faults the HAL FD pipeline (~20% photo
        // failure), so the burst captures at comp=0 (always-works) and DRO is measured
        // from the burst's own averaged RAW after the fact (zero extra frames).
        val reader = ImageReader.newInstance(w, h, ImageFormat.RAW_SENSOR, BURST_N)
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireNextImage() ?: run {
                Log.w(TAG, "acquireNextImage null")
                return@setOnImageAvailableListener
            }
            try {
                val buf = img.planes[0].buffer
                val shorts = ShortArray(buf.remaining() / 2)
                buf.asShortBuffer().get(shorts)
                // Accumulate in int. We require the buffer to be exactly w*h.
                if (shorts.size < w * h) throw IllegalStateException("raw plane size ${shorts.size} < ${w*h}")
                // FAST PREVIEW: as soon as frame 0 is in memory, spawn a worker that
                // does a coarse grayscale demosaic + scale + encode from this single
                // frame and fires onEarlyPreview. Total preview latency drops from
                // ~4 s to well under 1 s. The remaining burst frames continue to
                // accumulate for the full-fidelity denoised result.
                val isFirst = received.get() == 0
                if (isFirst) {
                    val frame0 = shorts.copyOf()
                    previewExecutor.execute {
                        try {
                            val tPrev = android.os.SystemClock.elapsedRealtime()
                            // No explicit DRO gain on the fast preview: fastPreviewToBitmap
                            // already percentile-stretches (p2..p98) its grayscale output,
                            // which inherently lifts a dark scene to a visible range -- so the
                            // preview is already roughly as bright as the DRO-corrected final.
                            // The final color JPEG (binToBitmap) carries the precise measured
                            // gain; the preview just gives an instant, already-bright proxy.
                            val gray = RawDemosaic.fastPreviewToBitmap(
                                frame0, w, h, blackLevel, whiteLevel,
                            )
                            val rotM = android.graphics.Matrix().apply { postRotate(-90f) }
                            val rotated = Bitmap.createBitmap(gray, 0, 0, gray.width, gray.height, rotM, false)
                            if (rotated !== gray) gray.recycle()
                            val longE = maxOf(rotated.width, rotated.height)
                            val thumb: Bitmap = if (longE > PREVIEW_LONG_EDGE_PX) {
                                val scale = PREVIEW_LONG_EDGE_PX.toFloat() / longE.toFloat()
                                val tw = (rotated.width * scale).toInt().coerceAtLeast(1)
                                val th = (rotated.height * scale).toInt().coerceAtLeast(1)
                                Bitmap.createScaledBitmap(rotated, tw, th, true)
                            } else rotated
                            FileOutputStream(out).use { thumb.compress(Bitmap.CompressFormat.JPEG, PREVIEW_JPEG_QUALITY, it) }
                            if (thumb !== rotated && !thumb.isRecycled) thumb.recycle()
                            rotated.recycle()
                            stampExifOrientationNormal(out)
                            Log.i(TAG, "fastPreview done ${out.absolutePath} bytes=${out.length()} previewMs=${android.os.SystemClock.elapsedRealtime() - takePhotoStartMs} pathMs=${android.os.SystemClock.elapsedRealtime() - tPrev}")
                            onEarlyPreview(out, null)
                        } catch (e: Throwable) {
                            Log.e(TAG, "fastPreview failed: ${e.message}")
                            // Do NOT fire onEarlyPreview on failure; the legacy slow
                            // path after the full-burst demosaic will still deliver
                            // the preview from the freshly written full-res file.
                        }
                    }
                }
                for (i in 0 until w * h) {
                    acc[i] += (shorts[i].toInt() and 0xFFFF)
                }
                val done = received.incrementAndGet()
                Log.i(TAG, "burst RAW $done/$BURST_N accumulated")
                if (done == BURST_N) imageLatch.countDown()
            } catch (e: Throwable) {
                frameErr[0] = e
                imageLatch.countDown()
            } finally {
                img.close()
            }
        }, handler)

        // AE-warmup target: a tiny PRIVATE SurfaceTexture preview surface. RAW +
        // PRIVATE(preview) is the only RAW-plus-second-stream combo this HAL accepts
        // (its ZSLPreviewRaw usecase). The surface only gives the HAL AE frames to
        // converge on so the burst lands at a real exposure; it is never read.
        val previewTex = SurfaceTexture(0).apply { setDefaultBufferSize(PREVIEW_TEX_W, PREVIEW_TEX_H) }
        val previewSurface = Surface(previewTex)

        // The capture body runs on CameraSession's single camera thread with an
        // open, exclusively-borrowed CameraDevice. It builds its OWN session,
        // runs the burst synchronously (blocking until imageLatch trips), and
        // closes its own session before returning. It must NOT close the device.
        val bodyErr = arrayOfNulls<Throwable>(1)
        // Per-shot WB gains, hoisted out of the borrow lambda so they survive past
        // the burst for the BurstResult. Default = fallback constants until the
        // last burst frame's result overwrites them.
        val wbGainsOut = floatArrayOf(WB_R, WB_G, WB_B)
        val ran = cameraSession.borrowDeviceExclusive(BORROW_TIMEOUT_MS) { camera ->
            var session: CameraCaptureSession? = null
            try {
                val sessLatch = CountDownLatch(1)
                val sessOut = arrayOfNulls<CameraCaptureSession>(1)
                val sessErr = arrayOfNulls<Throwable>(1)
                @Suppress("DEPRECATION")
                camera.createCaptureSession(listOf(reader.surface, previewSurface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { sessOut[0] = s; sessLatch.countDown() }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        sessErr[0] = IllegalStateException("session configure failed"); sessLatch.countDown()
                    }
                }, handler)
                if (!sessLatch.await(3, TimeUnit.SECONDS)) throw IllegalStateException("session configure timeout")
                sessErr[0]?.let { throw it }
                session = sessOut[0]!!

                // Hand exposure off to the HAL's standard AE. AE warmup on the PRIVATE
                // preview surface (precapture trigger + converge wait, AE_WARMUP_MS cap)
                // gives the HAL a base exposure, then the burst captures at comp=0 (pure
                // HAL-metered, always-works -- no separate metering frame that would fault
                // the HAL FD pipeline). The HAL under-meters outdoors; we correct that
                // brightness DIGITALLY (DRO) in the demosaic from the burst's own luma.
                run {
                    // AE warmup: let the HAL's auto-exposure settle on the PRIVATE preview
                    // surface before the burst. CRITICAL: the PRECAPTURE_TRIGGER must be sent
                    // exactly ONCE (a single capture()), NOT inside the repeating request.
                    // A repeating request carrying TRIGGER_START re-arms the precapture
                    // metering sequence every frame, so CONTROL_AE_STATE stays pinned at
                    // PRECAPTURE and NEVER transitions to CONVERGED -- the old code did this,
                    // so the latch always timed out and the burst fired at whatever exposure
                    // the HAL happened to hold. On a COLD camera (fresh boot) that is the
                    // dark cold-start exposure => black first photo, degenerate preview, and
                    // (because the black RAW then fails demosaic) no sync. Warm cameras only
                    // "worked" because the free-running AE had already settled.
                    val baseBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(previewSurface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        // AWB auto so the HAL converges + reports COLOR_CORRECTION_GAINS.
                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    }
                    val converged = CountDownLatch(1)
                    val lastExp = java.util.concurrent.atomic.AtomicLong(0L)
                    val lastIso = java.util.concurrent.atomic.AtomicInteger(0)
                    val lastAeState = java.util.concurrent.atomic.AtomicInteger(-1)
                    val cb = object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            s: CameraCaptureSession,
                            req: CaptureRequest,
                            result: android.hardware.camera2.TotalCaptureResult,
                        ) {
                            result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExp.set(it) }
                            result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso.set(it) }
                            val state = result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE)
                            if (state != null) lastAeState.set(state)
                            // Settle = the precapture sequence has finished and AE picked an
                            // exposure: CONVERGED, LOCKED, or FLASH_REQUIRED. (LOCKED can occur
                            // if a prior session left AE locked.) PRECAPTURE/SEARCHING/INACTIVE
                            // mean still metering -> keep waiting.
                            if (state == android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                                state == android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_LOCKED ||
                                state == android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED) {
                                converged.countDown()
                            }
                        }
                    }
                    // 1) Run a plain AE-auto preview (NO trigger) so AE can free-run.
                    session.setRepeatingRequest(baseBuilder.build(), cb, handler)
                    // 2) Kick the precapture metering sequence exactly ONCE.
                    val triggerReq = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(previewSurface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                    }
                    session.capture(triggerReq.build(), cb, handler)
                    val convergedOk = converged.await(AE_WARMUP_MS, TimeUnit.MILLISECONDS)
                    session.stopRepeating()
                    Log.i(TAG, "AE warmup: converged=$convergedOk aeState=${lastAeState.get()} exp=${lastExp.get() / 1_000_000.0}ms iso=${lastIso.get()}")
                }
                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    // AWB auto so the result carries the HAL's AWB-estimated gains.
                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    // comp=0: pure HAL-metered. Brightness is lifted digitally (DRO).
                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
                }
                Log.i(TAG, "initial capture: AE_MODE_ON AWB_AUTO comp=0 (${AE_WARMUP_MS}ms warmup, digital DRO)")
                val burstRequests = List(BURST_N) { builder.build() }
                val captureLatch = CountDownLatch(BURST_N)
                val captureErr = arrayOfNulls<Throwable>(1)
                // WB gains read from the last burst frame's result (stable across the
                // 3-frame burst), written into the hoisted wbGainsOut so they survive
                // the borrow; persisted with the raw so the disk demosaic matches.
                val tBurst = android.os.SystemClock.elapsedRealtime()
                session.captureBurst(burstRequests, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        s: CameraCaptureSession,
                        req: CaptureRequest,
                        result: android.hardware.camera2.TotalCaptureResult
                    ) {
                        val exp = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)
                        val isoAct = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)
                        val (gr, gg, gb) = wbGainsFromResult(result)
                        wbGainsOut[0] = gr; wbGainsOut[1] = gg; wbGainsOut[2] = gb
                        Log.i(TAG, "RAW frame completed exp=${exp?.let { it / 1_000_000.0 }}ms iso=$isoAct remaining=${captureLatch.count - 1}")
                        captureLatch.countDown()
                    }
                    override fun onCaptureFailed(
                        s: CameraCaptureSession,
                        req: CaptureRequest,
                        failure: android.hardware.camera2.CaptureFailure
                    ) {
                        captureErr[0] = IllegalStateException("capture failed reason=${failure.reason}")
                        captureLatch.countDown()
                    }
                }, handler)
                if (!captureLatch.await(10, TimeUnit.SECONDS))
                    throw IllegalStateException("burst capture timeout (remaining=${captureLatch.count})")
                captureErr[0]?.let { throw it }
                Log.i(TAG, "burst captured durMs=${android.os.SystemClock.elapsedRealtime() - tBurst}")
                // All RAW frames are in hand -- the scene no longer needs to be held
                // still. Signal "photo taken" so the UI can show the captured checkmark
                // (the remaining demosaic/denoise work off the buffered frames).
                try { onShutterDone() } catch (_: Throwable) {}
                if (!imageLatch.await(30, TimeUnit.SECONDS))
                    throw IllegalStateException("raw burst merge timeout (received=${received.get()}/$BURST_N)")
                frameErr[0]?.let { throw it }
            } catch (e: Throwable) {
                bodyErr[0] = e
                throw e
            } finally {
                try { session?.close() } catch (_: Exception) {}
                try { reader.close() } catch (_: Exception) {}
                try { previewSurface.release() } catch (_: Exception) {}
                try { previewTex.release() } catch (_: Exception) {}
            }
        }
        bodyErr[0]?.let { throw it }
        if (!ran) throw IllegalStateException("camera busy (borrowDeviceExclusive refused)")

        // Average into a ShortArray (same semantic as a single frame). The camera
        // device is already released (borrowDeviceExclusive returned). takePhoto
        // persists this avg to a disk sidecar and frees it before any heavy work,
        // so it never piles up in RAM across the demosaic/denoise tail.
        val avg = ShortArray(w * h)
        for (i in 0 until w * h) {
            avg[i] = (acc[i] / BURST_N).toShort()
        }
        // Exposure is corrected by the demosaic's own percentile auto-level from this
        // RAW, so nothing exposure-related is measured or persisted here.
        Log.i(TAG, "burst accumulated+averaged ${w}x${h} burstN=$BURST_N wb=[${wbGainsOut[0]},${wbGainsOut[1]},${wbGainsOut[2]}] (camera free, demosaic offloaded)")
        BurstResult(out, avg, w, h, blackLevel, whiteLevel, wbGainsOut[0], wbGainsOut[1], wbGainsOut[2])
    }

    /**
     * Full-resolution demosaic of an accumulated RAW burst. Runs on [processExecutor]
     * (NOT the camera executor) because [RawDemosaic.binToBitmap]'s per-pixel CCM +
     * sRGB encode costs ~17s. Identical pipeline (WB + CCM + saturation + sRGB) and
     * identical -90 pixel rotation as before -- only WHERE it runs changed, so the
     * final saved image quality is unchanged.
     */
    private fun demosaicBurst(burst: BurstResult): Bitmap {
        val tProc = android.os.SystemClock.elapsedRealtime()
        val raw = RawDemosaic.binToBitmap(
            burst.avg, burst.w, burst.h, burst.blackLevel, burst.whiteLevel,
            burst.wbR, burst.wbG, burst.wbB,
        )
        Log.i(TAG, "demosaic wb=[${burst.wbR},${burst.wbG},${burst.wbB}] (percentile autolevel)")
        val tDemosaic = android.os.SystemClock.elapsedRealtime()
        // Physically rotate 90° CCW. EXIF-only rotation is unreliable once
        // we overwrite the file after denoise, so bake the rotation into
        // the pixels and stamp ORIENTATION_NORMAL below.
        val rotMatrix = android.graphics.Matrix().apply { postRotate(-90f) }
        val binned = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, rotMatrix, false)
        if (binned !== raw) raw.recycle()
        val tRot = android.os.SystemClock.elapsedRealtime()

        // Preview file was already written by fastPreviewToBitmap from frame 0.
        // Denoise will overwrite `out` later with the full-res denoised JPEG.
        Log.i(TAG, "full-burst demosaic done source=${binned.width}x${binned.height} demosaicMs=${tDemosaic - tProc} rotMs=${tRot - tDemosaic} totalProcMs=${tRot - tProc}")
        return binned
    }

    /**
     * Capture ONE correctly-exposed still for ReID and hand back the upright
     * JPEG bytes in memory (no disk write, no SplitterDenoiser).
     *
     * Reuses the photo path's known-good exposed-capture recipe: borrow the
     * device exclusively, run an AE warmup with a precapture trigger until
     * CONTROL_AE_STATE reaches CONVERGED (cap AE_WARMUP_MS), meter the scene from
     * one RAW frame (mean green + clip) and fire a single TEMPLATE_STILL_CAPTURE
     * RAW frame at the adaptive per-scene EV. The RAW
     * frame is demosaiced with WB gains via [RawDemosaic.binToBitmap], rotated
     * -90 (sensor 270, matching the photo path so the face is upright), and
     * JPEG-encoded.
     *
     * Rotation is BAKED into the bitmap, so [onJpeg] is invoked with
     * rotationDeg = 0. The ReID consumer must NOT rotate again.
     *
     * Runs on the same single-threaded [executor] as [takePhoto], so ReID
     * stills and func-button photos serialize; [CameraSession.borrowDeviceExclusive]
     * additionally guarantees no concurrent camera open.
     */
    fun captureReidFrame(
        onJpeg: (ByteArray, Int, Int, Int) -> Unit,
        onError: (Throwable) -> Unit,
    ) = GT.section("cap.raw.reid_frame") {
        // YIELD TO PHOTO: a ReID frame is periodic and disposable. If a func-button
        // photo is pending or in progress, bail BEFORE enqueuing so this frame never
        // queues ahead of or delays the photo. The app-side ReID driver treats this
        // error as "retry on the next ~1.5s tick", so frames simply resume once the
        // photo's camera work completes (photoPending drops back to 0). Checked here
        // on the binder thread to avoid even posting the work.
        if (photoPending > 0) {
            onError(IllegalStateException("yield to photo"))
            return@section
        }
        executor.execute {
            // Re-check on the executor: a photo may have been requested between the
            // binder-thread check above and now. If so, yield rather than take the
            // camera ahead of the queued photo.
            if (photoPending > 0) {
                onError(IllegalStateException("yield to photo"))
                return@execute
            }
            if (!busy.compareAndSet(false, true)) {
                onError(IllegalStateException("raw still busy"))
                return@execute
            }
            try {
                val t0 = android.os.SystemClock.elapsedRealtime()
                val jpeg = captureReidJpeg()
                Log.i(TAG, "captureReidFrame done bytes=${jpeg.size} totalMs=${android.os.SystemClock.elapsedRealtime() - t0}")
                onJpeg(jpeg, jpegW, jpegH, 0)
            } catch (e: Throwable) {
                Log.e(TAG, "captureReidFrame failed: ${e.message}")
                onError(e)
            } finally {
                busy.set(false)
            }
        }
    }

    // Dimensions of the most recently encoded ReID JPEG (upright). Written on the
    // executor thread inside captureReidJpeg before onJpeg fires; read only there.
    @Volatile private var jpegW = 0
    @Volatile private var jpegH = 0

    /**
     * Synchronous single-frame exposed RAW capture -> upright JPEG bytes. Throws
     * on any failure. Mirrors [captureBurst]'s session/warmup/burst recipe but
     * with [REID_BURST_N]=1, no fast-preview, no disk write, no denoise.
     */
    private fun captureReidJpeg(): ByteArray = GT.section("cap.raw.reid_capture") {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = findRawCapableCamera(manager) ?: throw IllegalStateException("no RAW camera")
        val chars = manager.getCameraCharacteristics(cameraId)
        val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("no stream config")
        val rawSize = streamMap.getOutputSizes(ImageFormat.RAW_SENSOR)?.firstOrNull()
            ?: throw IllegalStateException("no RAW sizes")
        val w = rawSize.width
        val h = rawSize.height
        val blackLevel = (chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            ?.getOffsetForIndex(0, 0) ?: 64).toFloat()
        val whiteLevel = (chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toFloat()

        val frame = ShortArray(w * h)
        val imageLatch = CountDownLatch(1)
        val frameErr = arrayOfNulls<Throwable>(1)
        // Per-shot WB gains from the single ReID frame's result; default to the
        // fallback constants until the capture result populates them.
        val wbGainsOut = floatArrayOf(WB_R, WB_G, WB_B)

        // RAW reader holds the single ReID frame. No separate metering frame (would
        // fault the HAL FD pipeline); the frame is captured at comp=0 and brightness
        // is corrected digitally (DRO) from this frame's own luma after the fact.
        val reader = ImageReader.newInstance(w, h, ImageFormat.RAW_SENSOR, REID_BURST_N)
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
            try {
                val buf = img.planes[0].buffer
                buf.asShortBuffer().get(frame, 0, minOf(frame.size, buf.remaining() / 2))
                imageLatch.countDown()
            } catch (e: Throwable) {
                frameErr[0] = e
                imageLatch.countDown()
            } finally {
                img.close()
            }
        }, handler)

        // AE-warmup target: tiny PRIVATE SurfaceTexture preview (RAW + PRIVATE is
        // the only RAW-plus-stream combo this HAL accepts). Never read; brightness
        // is metered off a RAW frame.
        val previewTex = SurfaceTexture(0).apply { setDefaultBufferSize(PREVIEW_TEX_W, PREVIEW_TEX_H) }
        val previewSurface = Surface(previewTex)

        val bodyErr = arrayOfNulls<Throwable>(1)
        val ran = cameraSession.borrowDeviceExclusive(REID_BORROW_TIMEOUT_MS) { camera ->
            var session: CameraCaptureSession? = null
            try {
                val sessLatch = CountDownLatch(1)
                val sessOut = arrayOfNulls<CameraCaptureSession>(1)
                val sessErr = arrayOfNulls<Throwable>(1)
                @Suppress("DEPRECATION")
                camera.createCaptureSession(listOf(reader.surface, previewSurface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { sessOut[0] = s; sessLatch.countDown() }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        sessErr[0] = IllegalStateException("session configure failed"); sessLatch.countDown()
                    }
                }, handler)
                if (!sessLatch.await(3, TimeUnit.SECONDS)) throw IllegalStateException("session configure timeout")
                sessErr[0]?.let { throw it }
                session = sessOut[0]!!

                // AE warmup on the PRIVATE preview surface (precapture-trigger +
                // converge-wait, so the still lands at a real exposure, not the
                // sensor's near-zero default). The frame then captures at comp=0;
                // brightness is corrected digitally (DRO) from its own luma.
                run {
                    // Same correct AE warmup as the photo path: send PRECAPTURE_TRIGGER
                    // ONCE via capture(), NOT in the repeating request (a repeating trigger
                    // pins AE at PRECAPTURE forever and never converges -> the latch always
                    // timed out and the still landed at the cold/near-zero exposure).
                    val baseBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(previewSurface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    }
                    val converged = CountDownLatch(1)
                    val cb = object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            s: CameraCaptureSession,
                            req: CaptureRequest,
                            result: android.hardware.camera2.TotalCaptureResult,
                        ) {
                            val state = result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE)
                            if (state == android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                                state == android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_LOCKED ||
                                state == android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED) {
                                converged.countDown()
                            }
                        }
                    }
                    session.setRepeatingRequest(baseBuilder.build(), cb, handler)
                    val triggerReq = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(previewSurface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                    }
                    session.capture(triggerReq.build(), cb, handler)
                    val convergedOk = converged.await(AE_WARMUP_MS, TimeUnit.MILLISECONDS)
                    session.stopRepeating()
                    Log.i(TAG, "reid AE warmup converged=$convergedOk")
                }

                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    // comp=0: pure HAL-metered. Brightness lifted digitally (DRO).
                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
                }
                val captureLatch = CountDownLatch(1)
                val captureErr = arrayOfNulls<Throwable>(1)
                session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        s: CameraCaptureSession,
                        req: CaptureRequest,
                        result: android.hardware.camera2.TotalCaptureResult
                    ) {
                        val (gr, gg, gb) = wbGainsFromResult(result)
                        wbGainsOut[0] = gr; wbGainsOut[1] = gg; wbGainsOut[2] = gb
                        captureLatch.countDown()
                    }
                    override fun onCaptureFailed(
                        s: CameraCaptureSession,
                        req: CaptureRequest,
                        failure: android.hardware.camera2.CaptureFailure
                    ) {
                        captureErr[0] = IllegalStateException("capture failed reason=${failure.reason}")
                        captureLatch.countDown()
                    }
                }, handler)
                if (!captureLatch.await(5, TimeUnit.SECONDS))
                    throw IllegalStateException("reid capture timeout")
                captureErr[0]?.let { throw it }
                if (!imageLatch.await(5, TimeUnit.SECONDS))
                    throw IllegalStateException("reid frame timeout")
                frameErr[0]?.let { throw it }
            } catch (e: Throwable) {
                bodyErr[0] = e
                throw e
            } finally {
                try { session?.close() } catch (_: Exception) {}
                try { reader.close() } catch (_: Exception) {}
                try { previewSurface.release() } catch (_: Exception) {}
                try { previewTex.release() } catch (_: Exception) {}
            }
        }
        bodyErr[0]?.let { throw it }
        if (!ran) throw IllegalStateException("camera busy (borrowDeviceExclusive refused)")

        // Exposure is corrected by the demosaic's own percentile auto-level from this
        // RAW frame; nothing exposure-related is measured here.
        Log.i(TAG, "reid demosaic wb=[${wbGainsOut[0]},${wbGainsOut[1]},${wbGainsOut[2]}] (percentile autolevel)")
        val raw = RawDemosaic.binToBitmapFast(
            frame, w, h, blackLevel, whiteLevel,
            wbGainsOut[0], wbGainsOut[1], wbGainsOut[2], downsample = REID_DOWNSAMPLE,
        )
        // Bake the -90 rotation into the pixels so the face is upright and the
        // listener does NOT rotate again (onJpeg passes rotationDeg=0).
        val rotMatrix = android.graphics.Matrix().apply { postRotate(-90f) }
        val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, rotMatrix, false)
        if (upright !== raw) raw.recycle()
        jpegW = upright.width
        jpegH = upright.height
        val bos = java.io.ByteArrayOutputStream()
        upright.compress(Bitmap.CompressFormat.JPEG, REID_JPEG_QUALITY, bos)
        upright.recycle()
        bos.toByteArray()
    }

    /**
     * Read the HAL's per-shot AWB color gains from a still capture result and map
     * them to (wbR, wbG, wbB) for the demosaic. The HAL reports
     * [CaptureResult.COLOR_CORRECTION_GAINS] as an [RggbChannelVector] of absolute
     * multipliers (red & blue >= 1, green ~1) -- exactly the channelwise WB gains
     * [RawDemosaic.binToBitmap] expects. Green collapses the two Bayer greens to
     * their average (the demosaic's gGain already halves wbG to account for summing
     * G1+G2, so passing avg-green keeps a neutral grey neutral).
     *
     * Returns the WB_* fallback constants (and logs) when the HAL returns null,
     * which some HALs do unless COLOR_CORRECTION_MODE is set. We request AWB AUTO,
     * under which most HALs populate the AWB-estimated gains here.
     */
    private fun wbGainsFromResult(result: android.hardware.camera2.TotalCaptureResult): Triple<Float, Float, Float> {
        val gains = result.get(android.hardware.camera2.CaptureResult.COLOR_CORRECTION_GAINS)
        if (gains == null) {
            Log.w(TAG, "WB: COLOR_CORRECTION_GAINS null on this HAL -- falling back to constants R=$WB_R G=$WB_G B=$WB_B")
            return Triple(WB_R, WB_G, WB_B)
        }
        val gAvg = (gains.greenEven + gains.greenOdd) / 2f
        Log.i(TAG, "WB: HAL gains R=${gains.red} Geven=${gains.greenEven} Godd=${gains.greenOdd} B=${gains.blue} -> wbR=${gains.red} wbG=$gAvg wbB=${gains.blue}")
        return Triple(gains.red, gAvg, gains.blue)
    }

    /**
     * Read the DEBUG [SKIP_DENOISE_PROP] non-persist system property via
     * SystemProperties reflection (same pattern as [LedController]). Returns true
     * when it is "1"/"true", false otherwise (including when unset or unreadable).
     * Non-persist props reset on reboot, so this is a temporary until-reboot flag.
     */
    private fun skipDenoise(): Boolean {
        return try {
            val sp = Class.forName("android.os.SystemProperties")
            val v = sp.getMethod("get", String::class.java).invoke(null, SKIP_DENOISE_PROP) as? String
            v == "1" || v == "true"
        } catch (e: Exception) {
            Log.w(TAG, "skipDenoise read failed: ${e.message}")
            false
        }
    }

    private fun findRawCapableCamera(manager: CameraManager): String? {
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
            if (caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) return id
        }
        return null
    }

    /**
     * Pixels are already rotated 90° CCW in [demosaicBurst]; mark EXIF
     * as NORMAL so viewers and downstream encoders don't re-rotate.
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

    /** No-op warmup. The RAW capture path opens the device + runs its own AE
     *  warmup transparently per [takePhoto], so there is nothing to pre-warm
     *  here. Kept so the AIDL warmUp() entrypoint has a single capture engine
     *  to call (mirrors takePhoto routing through this class). */
    fun warmUp() {}

    fun shutdown() {
        handlerThread.quitSafely()
        executor.shutdown()
        processExecutor.shutdown()
        retryScheduler.shutdown()
    }
}
