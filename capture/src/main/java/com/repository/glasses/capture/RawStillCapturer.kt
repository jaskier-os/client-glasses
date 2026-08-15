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
        // Bounded retry for a transient cold-camera burst failure (HAL sensor/CSIPHY
        // resource momentarily exhausted during bring-up -> onCaptureFailed reason=0).
        // The resource frees within ~1s, so 3 attempts with a short backoff reliably
        // recovers the first-photo-after-boot drop. Each attempt fully releases the
        // camera borrow and rebuilds the capture session.
        private const val BURST_RETRY_MAX = 3
        private const val BURST_RETRY_BACKOFF_MS = 350L

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

        /** Default/maximum RAW frames averaged per capture (~sqrt(N) noise
         *  reduction). The ACTUAL count is chosen per shot by [burstNForScene]
         *  from the metered scene; this is the ceiling it picks under, and the
         *  ImageReader's buffer depth. */
        private const val BURST_N = 3

        /** Upper clamp for the [BURST_N_PROP] override. Capped at [BURST_N]: the
         *  prop only ever LOWERS the ceiling (raising it would allocate RAW
         *  buffers the adaptive choice can never use). */
        private const val BURST_N_MAX = BURST_N

        /** DEBUG override for burst length (non-persist, resets on reboot). Acts as
         *  a CAP on the scene-adaptive choice, so `setprop ... 1` forces single-frame
         *  and the default 3 lets [burstNForScene] pick 1..3 from the metered scene. */
        private const val BURST_N_PROP = "debug.glasses.capture.burst_n"

        /** How long to wait for the metered number of RAW frames. Generous: this
         *  covers the whole burst on the slowest (dimmest, longest-exposure) path. */
        private const val BURST_FRAMES_TIMEOUT_MS = 30_000L

        /** Scales the raw ambient/(exp*iso) ratio into a readable range. Purely
         *  cosmetic -- it moves SCENE_LIGHT_* off tiny decimals. */
        private const val SCENE_LIGHT_SCALE = 1_000_000.0

        /** Scene-light thresholds for [burstNForScene], in the scaled units above.
         *  Calibrated on-device against the same room at two light levels, both
         *  of which AE reported IDENTICALLY as 30ms/ISO800 (which is precisely
         *  why AE metadata could not drive this decision):
         *
         *    lights off, monitor only : median 0.00104 @30ms -> light 0.04 (n=3)
         *    lights on, dim/medium    : median 0.00313 @30ms -> light 0.13 (n=2)
         *    lights on, full          : median 0.01460 @20ms -> light 0.91 (n=1)
         *
         *  Note the dark and dim rooms are INDISTINGUISHABLE by AE (both 30ms /
         *  ISO 800) yet differ 3x in metered light -- the reason this decision
         *  reads pixels instead of capture metadata.
         *
         *  Each threshold sits at the geometric mean of its neighbours, giving
         *  ~1.7-2.6x margin on both sides, so ordinary scene variation cannot
         *  flip the burst length between consecutive shots. */
        private const val MID_LIGHT = 0.08
        private const val BRIGHT_LIGHT = 0.34

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

        /** How long a completed warmUp keeps the HAL considered warm. Collapses
         *  the onCreate warmup and the per-bind AIDL warmUp() into one on boot. */
        private const val WARMUP_VALID_MS = 60_000L

        /** Cap on the warmup's throwaway RAW frame. Short on purpose: a cold-HAL
         *  drop here is the expected case we are absorbing, not something to wait
         *  out. Real captures use their own (much longer) burst timeout. */
        private const val WARMUP_RAW_TIMEOUT_MS = 3000L

        /** AE budget for the warmup only (vs [AE_WARMUP_MS] for a real capture).
         *  The warmup blocks the shared camera handler for its whole duration, so
         *  it is deliberately capped well below the real one. */
        private const val WARMUP_AE_MS = 1500L

        /** Bounded in-session retries for a demosaic that threw (path b in
         *  [enqueueProcess]). A demosaic failure is almost always transient RAM/CPU
         *  contention (a 2nd photo + the rPPG stream), so a short delay then a retry
         *  on the same raw usually succeeds and lets the FULL-COLOR image sync
         *  WITHOUT ever shipping the gray preview. Capped so a permanently-failing
         *  raw (e.g. genuinely corrupt) cannot re-enqueue forever -- after the cap we
         *  leave the raw sidecar on disk so the next process-start [resumePending]
         *  picks it up, rather than burning the CPU in a tight retry loop. */
        /** Max photos queued (in flight + waiting) before further presses are
         *  rejected.
         *
         *  Sized from measured peaks, not intuition. Each queued photo carries
         *  ~120MB of pixel arrays through demosaic+denoise, and GC cannot keep
         *  up when passes overlap:
         *    queue 6 -> peaked 248MB, killed (lost ALL six)
         *    queue 3 -> peaked 302MB, killed ~2-3 runs in 10
         *    queue 2 -> peaks ~200MB, survives
         *  lmkd starts taking this priv-app (adj 100) around 270MB on this
         *  1.8GB device, so 2 is the honest ceiling. Rejecting the third press
         *  costs one photo; not rejecting it costs the process and every photo
         *  already queued. */
        private const val MAX_PENDING_PHOTOS = 2

        /** Cap on waiting for the previous capture to release its camera
         *  buffers. A capture is ~5s (burst + RAW write), so this covers a full
         *  queue; past it we proceed rather than drop the user's photo. */
        private const val CAMERA_SERIALISE_MAX_MS = 20_000L

        /** Cap on waiting for the camera software lock before giving up on a
         *  photo. Never proceed without it: two concurrent camera bodies are far
         *  worse than one dropped shot. */
        private const val BUSY_ACQUIRE_MAX_MS = 15_000L

        /** Cap on how long the BACKGROUND processor defers to the capture queue.
         *
         *  Generous ON PURPOSE. A capture is ~4-5s, and the cap must cover a
         *  full queue of them plus the camera settling, or processing resumes
         *  mid-queue and its ~85MB working set lands on top of a capture's
         *  ~120MB -- measured 291-331MB peaks and lmkd kills at ~270MB.
         *
         *  Waiting is nearly free here: the RAW is already durable on disk, so
         *  the only cost of deferring is that the final denoised JPEG appears
         *  later. Being killed costs the photo outright. */
        private const val PROCESS_YIELD_MAX_MS = 120_000L
        private const val PROCESS_YIELD_POLL_MS = 100L

        /** Wait before touching a crash-resume backlog, so recovery does not land
         *  on the process-start memory peak (camera warmup + SCRFD/QNN init) and
         *  re-trigger the kill that created the backlog. Shorter now that
         *  [yieldToCapture] also protects the live path -- this only has to clear
         *  startup, not an arbitrary capture. */
        private const val RESUME_START_DELAY_MS = 6_000L

        /** Spacing between multiple recovered photos, so a backlog drains one at
         *  a time instead of dogpiling. One full pass is ~23s. */
        private const val RESUME_STAGGER_MS = 25_000L

        /** Bounded in-session retries for a failed denoise (OOM being the case
         *  this exists for). Same shape as the demosaic retry: the raw sidecar
         *  stays on disk, so a retry can still produce the colour photo instead
         *  of stranding it until the next process start. */
        private const val DENOISE_RETRY_MAX = 3

        /** Longer than the demosaic backoff: a denoise failure usually means the
         *  device is out of memory, and the retry needs time for the pressure
         *  (typically a concurrent capture) to clear. */
        private const val DENOISE_RETRY_DELAY_MS = 8000L

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

    /** Photos enqueued for demosaic+denoise and not yet finished. No longer
     *  gates the shutter (capture has absolute priority), but it DOES gate the
     *  post-capture heap collection -- collecting while work is still queued
     *  would stall it. Incremented at ENQUEUE so queued-but-not-started work is
     *  visible. */
    private val processBacklog = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Captures currently holding camera buffers (~170MB each).
     *
     * The background demosaic/denoise worker checks this BETWEEN its stages and
     * pauses while it is non-zero, so heavy processing never overlaps a capture
     * on this 1.8GB device. The capture itself never waits: the user is holding
     * still with the shutter pressed, so the deferrable side is the background.
     */
    private val captureInFlight = java.util.concurrent.atomic.AtomicInteger(0)

    /** True while a shutter-initiated capture is holding camera buffers. */
    private fun isCaptureInFlight(): Boolean = captureInFlight.get() > 0

    /**
     * Burst length the LAST capture actually metered, used to predict the next
     * capture's ImageReader depth.
     *
     * Depth cannot be chosen from the scene (the reader must exist before AE
     * runs), but each buffer is 24.4MB of DMA-BUF -- so always reserving the
     * maximum wastes ~49MB per capture in the common single-frame case. The
     * previous shot is a good predictor: lighting rarely changes between two
     * presses seconds apart. Starts at the full default so the very first
     * capture after start is never under-provisioned.
     */
    private val lastMeteredBurstN = java.util.concurrent.atomic.AtomicInteger(BURST_N)

    /**
     * Park the background processor while a capture is in flight, so a heavy
     * stage never allocates on top of the capture's ~170MB.
     *
     * Called BETWEEN processing stages, never inside one -- a partially
     * completed demosaic cannot be suspended. Bounded so a stuck capture flag
     * can never wedge the queue permanently; past the cap we proceed and accept
     * the memory pressure rather than strand the user's photo forever.
     */
    private fun yieldToCapture(stage: String) {
        // Wait for the ENTIRE capture queue to drain, not just the one capture
        // currently holding buffers.
        //
        // This is the user's stated requirement: "all user's presses must flush
        // and go through initial capture first in high priority before others
        // get their demosaics". It is also the only thing that fits the memory
        // budget. Measured: one capture peaks ~200MB, but a capture overlapping
        // another photo's processing peaks 302-331MB, and lmkd takes this
        // priv-app (adj 100) somewhere around 270MB on this 1.8GB device.
        //
        // photoPending counts presses whose camera work has not finished, so
        // waiting on it serialises "all captures, then all processing" -- the
        // user holds still once, and the heavy work happens afterwards.
        //
        // Also covers warmUp, which holds a full-res RAW reader plus the
        // SCRFD/QNN init (~228MB native at process start).
        fun busy(): Boolean = isCaptureInFlight() || warmUpInFlight || photoPending > 0
        if (!busy()) return
        val t0 = android.os.SystemClock.elapsedRealtime()
        while (busy() &&
            android.os.SystemClock.elapsedRealtime() - t0 < PROCESS_YIELD_MAX_MS
        ) {
            try { Thread.sleep(PROCESS_YIELD_POLL_MS) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); return }
        }
        Log.i(TAG, "process yielded ${android.os.SystemClock.elapsedRealtime() - t0}ms " +
            "to capture before $stage (pending=$photoPending inFlight=${captureInFlight.get()})")
    }

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
     * Bracket a silent (no privacy-LED) camera open. Wired by CaptureService to
     * its ref-counted cameraLedGate; kept as plain lambdas so this class has no
     * compile-time dependency on CaptureService. Used by [warmUp], which opens
     * the camera with no user action behind it and so must not light the LED.
     * Defaults are no-ops so the class compiles standalone.
     */
    @Volatile var acquireSilentLed: () -> Unit = {}
    @Volatile var releaseSilentLed: () -> Unit = {}

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

    /** elapsedRealtime of the last accepted [warmUp], 0 = never. Guarded by
     *  [warmUpLock] so concurrent binder + onCreate calls collapse to one. */
    private var lastWarmUpMs = 0L
    private val warmUpLock = Any()

    /** True while [warmUpBody] holds its RAW ImageReader + camera session.
     *  Startup warmup peaks the process at ~228MB native; a capture starting in
     *  that window adds ~170MB on top and gets the process OOM-killed (measured:
     *  357MB, lmkd kill, preview vanished mid-flight). The gate in takePhoto
     *  waits this out. */
    @Volatile private var warmUpInFlight = false


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
        // BOUND THE QUEUE. Each queued photo will allocate ~120-170MB of camera
        // buffers when it runs, and nothing else caps how many can be waiting:
        // 6 presses in 1.5s stacked photoPending to 6, drove the process to
        // 248MB and lmkd killed it -- losing ALL SIX. Dropping the excess press
        // costs the user one photo; not dropping it costs them every photo plus
        // the process. Rejected presses return an error so the UI can clear its
        // placeholder instead of spinning forever.
        val queued = synchronized(photoPendingLock) {
            if (photoPending >= MAX_PENDING_PHOTOS) {
                -1
            } else {
                ++photoPending
            }
        }
        if (queued < 0) {
            Log.w(TAG, "takePhoto REJECTED: $MAX_PENDING_PHOTOS photos already queued")
            try { onPreview(null, IllegalStateException("capture queue full")) } catch (_: Throwable) {}
            return@section
        }
        Log.i(TAG, "takePhoto entry busy=${busy.get()} photoPending=$photoPending")
        executor.execute {
            // Acquire busy by waiting, not aborting. On the single-thread FIFO executor
            // any in-flight ReID frame has already run to completion before this body
            // starts, so compareAndSet succeeds immediately. The spin is a belt-and-braces
            // guard for the theoretical case where busy is held by a non-executor path.
            // Do NOT break out of this on interrupt: proceeding without owning
            // `busy` would let two camera bodies run concurrently. Bounded, then
            // fail the photo cleanly rather than racing the camera.
            var busyWaitMs = 0L
            while (!busy.compareAndSet(false, true)) {
                if (busyWaitMs >= BUSY_ACQUIRE_MAX_MS) {
                    Log.e(TAG, "takePhoto: busy not released after ${busyWaitMs}ms; dropping photo")
                    synchronized(photoPendingLock) { if (photoPending > 0) photoPending-- }
                    onPreview(null, IllegalStateException("camera busy"))
                    return@execute
                }
                try { Thread.sleep(5) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                busyWaitMs += 5
            }
            // SERIALISE THE CAMERA BUFFERS.
            //
            // Each capture's ImageReader reserves burstCap full-res RAW buffers
            // -- 24.4MB of DMA-BUF each, so 73MB at depth 3 (which a dark scene
            // genuinely needs: measured `scene light=0.0` -> n=3). Two captures
            // holding readers at once is ~146MB of DMA-BUF before a single Java
            // array is counted, and the process is lmkd-killed at ~260-300MB.
            //
            // Measured repeatedly: the kill lands the instant a second
            // doCapture() starts while the first still owns its reader, with NO
            // processing running at all. So the second capture waits for the
            // first to release -- which is quick (~5s: the burst plus writing
            // the RAW to disk), and is exactly the "flush captures first"
            // ordering the user asked for.
            //
            // This does NOT make the user hold still longer for their own shot:
            // the wait is between shots, and the shutter for press #2 was always
            // going to queue behind press #1's camera access anyway.
            val tCam = android.os.SystemClock.elapsedRealtime()
            while (isCaptureInFlight() &&
                android.os.SystemClock.elapsedRealtime() - tCam < CAMERA_SERIALISE_MAX_MS
            ) {
                try { Thread.sleep(20) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
            }
            val camWaitMs = android.os.SystemClock.elapsedRealtime() - tCam
            if (camWaitMs > 20) Log.i(TAG, "capture waited ${camWaitMs}ms for previous capture's buffers")

            // CAPTURE HAS ABSOLUTE PRIORITY over background PROCESSING.
            //
            // The user is standing still with the shutter pressed: every second
            // spent here is a second they must hold the scene. An earlier version
            // gated the capture on the processing backlog to bound memory, and it
            // made the user wait 15.8s -- unacceptable, and backwards. The right
            // trade is the opposite: the BACKGROUND yields to the capture.
            //
            // Memory is still bounded, but from the other side: captureInFlight
            // makes the demosaic/denoise worker pause between stages (see the
            // yieldToCapture() calls in enqueueProcess), so the heavy
            // ~170MB capture allocation does not overlap a heavy processing
            // stage. Processing is deferrable; the user's shutter is not.
            captureInFlight.incrementAndGet()
            try {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val burst: BurstResult
            // Wrap onPreview so it fires exactly once -- either from the fast-path
            // (frame 0 arrives) inside captureBurst, or from here if the burst
            // fails before the fast path had a chance to run.
            val previewFired = java.util.concurrent.atomic.AtomicBoolean(false)
            val onPreviewOnce: (File?, Throwable?) -> Unit = { f, e ->
                if (previewFired.compareAndSet(false, true)) onPreview(f, e)
            }
            // Once-guarded ACROSS retries. onShutterDone fires after captureLatch
            // but BEFORE the merge await, so a merge timeout can still throw and
            // retry after the checkmark already showed -- without this guard the
            // UI would get a second "photo taken, you can move now" for the same
            // user-visible shot.
            val shutterFired = java.util.concurrent.atomic.AtomicBoolean(false)
            val onShutterOnce: () -> Unit = {
                if (shutterFired.compareAndSet(false, true)) onShutterDone()
            }
            // BOUNDED BURST RETRY. On a cold camera (first capture after boot) the
            // Qualcomm HAL transiently runs out of a sensor/CSIPHY resource during
            // bring-up (camxresourcemanager: SensorHw AvailableResource=0) and drops
            // one frame of the burst with onCaptureFailed reason=0 (REASON_ERROR),
            // which aborts the whole photo. The resource frees within ~1s -- the very
            // next capture always succeeds. So retry the self-contained captureBurst()
            // a few times with a short backoff: each attempt fully releases the borrow
            // and recreates the reader/accumulator, giving the HAL room to recover.
            // onEarlyPreview from a FAILED attempt is suppressed (preview swallowed) so
            // only a successful attempt's preview/photo propagates; the once-guard then
            // makes onPreviewOnce idempotent for the success path.
            var attempt = 0
            var captured: BurstResult? = null
            var lastErr: Throwable? = null
            while (attempt < BURST_RETRY_MAX) {
                val isLast = attempt == BURST_RETRY_MAX - 1
                // Only let the preview/shutter callbacks escape on the final attempt OR
                // once an attempt is succeeding; a failed early attempt must not fire a
                // preview (it would be from a partial/aborted capture).
                val attemptPreview: (File?, Throwable?) -> Unit = { f, e ->
                    if (e == null) onPreviewOnce(f, null)         // success preview -> propagate
                    else if (isLast) onPreviewOnce(null, e)       // final failure -> propagate error
                    // else: swallow this attempt's failure, we will retry
                }
                try {
                    captured = captureBurst(attemptPreview, t0, onShutterOnce)
                    break
                } catch (e: Throwable) {
                    lastErr = e
                    attempt++
                    Log.w(TAG, "takePhoto burst attempt $attempt/$BURST_RETRY_MAX failed: ${e.message}" +
                        if (attempt < BURST_RETRY_MAX) " -- retrying after ${BURST_RETRY_BACKOFF_MS}ms" else " -- giving up")
                    if (attempt < BURST_RETRY_MAX) {
                        try { Thread.sleep(BURST_RETRY_BACKOFF_MS) }
                        catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
                    }
                }
            }
            if (captured == null) {
                Log.e(TAG, "takePhoto burst failed after $attempt attempts: ${lastErr?.message}")
                onPreviewOnce(null, lastErr ?: IllegalStateException("burst failed"))
                // Camera work for this photo is over (failed). Release BOTH the software
                // lock and the pending mark so a stuck flag can never permanently starve
                // ReID frames or future photos. No heavy work was posted, so nothing else
                // to clean up.
                busy.set(false)
                synchronized(photoPendingLock) { if (photoPending > 0) photoPending-- }
                return@execute
            }
            burst = captured
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
            //
            // RECLAIM IT NOW rather than waiting for the collector to notice.
            // This capture just churned ~120MB of short/int arrays through the
            // Java heap; the next queued press allocates its own set within
            // milliseconds. Measured mid-burst: Dalvik heap at 146MB against a
            // 102MB capacity -- i.e. the previous capture's arrays were still
            // resident when the next one started, and lmkd killed the process.
            // An explicit collect between captures costs a few ms on a thread
            // the user is not waiting on (the RAW is already safely on disk) and
            // removes that overlap.
            System.gc()
            enqueueProcess(rawFile, onPreviewOnce, onFinal, t0)
            } finally {
                // Camera-side work for this photo is done (success or failure), so
                // the background processor may resume its heavy stages. Must cover
                // every exit path or processing would stall until the next capture.
                captureInFlight.decrementAndGet()
            }
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
        // Counted at ENQUEUE, not at execution start: the memory gate has to see
        // work that is queued-but-not-yet-running, otherwise several captures
        // race past the gate while the executor is still on the first one.
        processBacklog.incrementAndGet()
        processExecutor.execute process@{
            try {
            // Yield BEFORE reading the raw (~24MB) -- the first heavy allocation
            // of this pass. A capture in flight owns the memory budget.
            yieldToCapture("raw read")
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
                // Demosaic is the longest stage (~15s) and allocates the full-res
                // bitmaps. Yield again here: a shutter press that arrived while
                // the raw was being read must not have this land on top of it.
                yieldToCapture("demosaic")
                // Reclaim the PREVIOUS photo's arrays before allocating this
                // one's. Collecting only at the very end of a pass was not
                // enough: two overlapping photos each peaked (live measured at
                // 148MB) before anything was freed, and lmkd killed the process
                // at 275-281MB. This is the cheapest point to do it -- the raw
                // is on disk, the bitmaps are not allocated yet.
                HeapTrimmer.collect()
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
                    val ok = writeJpegAtomic(file, binned, JPEG_QUALITY)
                    binned.recycle()
                    if (!ok) throw IllegalStateException("skip_denoise publish failed")
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
            // Last yield before the denoise: it holds the QNN engine plus two
            // full-res bitmaps, so it is the worst stage to overlap a capture.
            yieldToCapture("denoise")
            // Collect the demosaic's intermediates BEFORE the denoise allocates
            // its output bitmap and tile buffers. Measured: at the peak of a
            // single pass, live is 121MB but a collect takes it to 49MB -- i.e.
            // ~72MB of the 202MB peak is garbage ART simply had not got to yet.
            // Freeing it here is what keeps the pass under lmkd's threshold
            // (~270MB for this adj-100 priv-app on a 1.8GB device).
            HeapTrimmer.collect()
            denoiseInFlight.incrementAndGet()
            try { onDenoiseStateChanged() } catch (_: Throwable) {}
            try {
            GT.section("cap.raw.denoise") {
                try {
                    val tD = android.os.SystemClock.elapsedRealtime()
                    val denoiser = SplitterDenoiser.get(context)
                    // Let the denoise pause BETWEEN TILES while a capture holds
                    // camera buffers. Yielding only before the stage was not
                    // enough: a denoise already running when the shutter is
                    // pressed would otherwise overlap the capture's ~170MB.
                    denoiser.pauseCheck = { yieldToCapture("denoise tile") }
                    val denoised = try {
                        denoiser.denoise(binned)
                    } finally {
                        denoiser.pauseCheck = null
                    }
                    val ok = writeJpegAtomic(file, denoised, JPEG_QUALITY)
                    denoised.recycle()
                    if (!ok) throw IllegalStateException("final publish failed")
                    // Cleanup: the full-res color JPEG is on disk, so the raw backlog
                    // entry is done. Delete the sidecar so it is not reprocessed.
                    if (!rawFile.delete()) Log.w(TAG, "pending raw delete failed ${rawFile.absolutePath}")
                    Log.i(TAG, "process done ${file.absolutePath} denoiseMs=${android.os.SystemClock.elapsedRealtime() - tD} totalMs=${android.os.SystemClock.elapsedRealtime() - t0}")
                    onFinal(file, null)
                    onResumed?.invoke(file)
                } catch (e: Throwable) {
                    Log.e(TAG, "process denoise failed (attempt=$attempt): ${e.message}")
                    // Denoise failed but demosaic succeeded -- the gray preview is still
                    // on disk. NEVER sync that (the phone must not get a gray image);
                    // leave the raw so the colour photo can still be produced.
                    //
                    // Retry in-session, bounded, mirroring the demosaic path. An OOM
                    // kill is the motivating case: the raw survives on disk, but
                    // waiting for the next process start to notice it can strand the
                    // photo indefinitely if the user never shoots again. Free the
                    // denoise engine first so the retry starts from a clean heap
                    // rather than re-failing against the same exhausted memory.
                    if (!binned.isRecycled) binned.recycle()
                    SplitterDenoiser.release()
                    if (attempt + 1 < DENOISE_RETRY_MAX) {
                        Log.w(TAG, "scheduling denoise retry ${attempt + 2}/$DENOISE_RETRY_MAX for ${rawFile.name} in ${DENOISE_RETRY_DELAY_MS}ms")
                        try {
                            retryScheduler.schedule({
                                enqueueProcess(rawFile, onPreviewOnce, onFinal, t0, onResumed, attempt + 1)
                            }, DENOISE_RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
                        } catch (re: Throwable) {
                            Log.w(TAG, "denoise retry schedule failed: ${re.message}")
                            onFinal(file, e)
                        }
                    } else {
                        Log.e(TAG, "denoise retries exhausted for ${rawFile.name}; leaving raw for next process-start resume")
                        onFinal(file, e)
                    }
                }
            }
            } finally {
                // Always clear the pause + notify so the rPPG stream resumes,
                // even if denoise threw or the process was killed mid-write.
                denoiseInFlight.decrementAndGet()
                try { onDenoiseStateChanged() } catch (_: Throwable) {}
            }
            } finally {
                // Release the memory gate on EVERY exit path (success, unreadable
                // raw, demosaic failure, retry scheduled), or a capture would
                // wait out the full gate timeout for work that already finished.
                processBacklog.decrementAndGet()
                // Hand the now-garbage pixel arrays back to the OS.
                //
                // This is what makes the process survivable between shots. ART
                // grows the Dalvik heap to fit a capture (~120MB of short/int
                // arrays) and then KEEPS that capacity: measured 134MB of heap
                // held while IDLE with only 25MB actually live. lmkd scores
                // adj*size and this priv-app runs at adj 100, so an idle process
                // that still looks like 178MB is picked off the moment the
                // device tightens -- which is the residual "sometimes crashes"
                // after all the correctness fixes. System.gc() alone collects
                // but does NOT return the pages; the trim call does.
                if (processBacklog.get() == 0 && !isCaptureInFlight()) {
                    HeapTrimmer.collect()
                }
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
        Log.i(TAG, "resumePending: re-enqueueing ${raws.size} leftover raw(s) " +
            "after ${RESUME_START_DELAY_MS}ms settle")
        // Oldest first so recovered photos finish in capture order.
        //
        // DELAYED + STAGGERED, deliberately. A resume runs at process start,
        // which is exactly when the process is at its heaviest (camera warmup +
        // SCRFD/QNN init, ~228MB native). Enqueuing a demosaic+denoise straight
        // into that window pushed the process back over lmkd's limit and it was
        // killed again -- which left the raw on disk, so the NEXT start resumed
        // it and died again. Measured as a restart cascade that also took down
        // the listener and filesync each round.
        //
        // Letting startup settle first, and spacing multiple recoveries, keeps
        // recovery off the startup peak. Recovery is inherently not urgent: the
        // photo is already safe on disk.
        var delayMs = RESUME_START_DELAY_MS
        for (rawFile in raws.sortedBy { it.lastModified() }) {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val thisDelay = delayMs
            delayMs += RESUME_STAGGER_MS
            try {
                retryScheduler.schedule({
                    enqueueProcessResume(rawFile, t0)
                }, thisDelay, TimeUnit.MILLISECONDS)
            } catch (e: Throwable) {
                Log.w(TAG, "resume schedule failed: ${e.message}")
            }
        }
    }

    /** The actual resume enqueue, deferred by [resumePending]. */
    private fun enqueueProcessResume(rawFile: File, t0: Long) {
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
        // Burst length is chosen AFTER the AE warmup, from the exposure the HAL
        // settles on (see [burstNForScene]) -- a bright scene needs no averaging,
        // a dim one does. But the ImageReader has to exist BEFORE that, because
        // its surface is part of the capture session AE runs on. So allocate for
        // the worst case and only request as many frames as the scene needs.
        // (Buffer count matches the old fixed default, so no memory regression.)
        // Reader depth = how many full-res RAW buffers we reserve. Each is
        // 24.4MB of DMA-BUF, so a depth-3 reader costs 73MB per capture EVEN
        // WHEN THE SCENE ONLY NEEDS ONE FRAME -- and in any decent light
        // burstNForScene picks n=1, so two of the three are never written.
        //
        // Two overlapping captures at depth 3 is ~146MB of DMA-BUF alone, which
        // is what pushed the process to 284MB and got it lmkd-killed with no
        // processing even running (measured 20:14:56).
        //
        // The scene cannot be metered before the reader exists, so use the
        // PREVIOUS capture's answer as the prediction: lighting rarely changes
        // between two presses seconds apart. Reserve one spare frame above it
        // for headroom, clamped to the configured cap. If the prediction is too
        // low the burst simply captures fewer frames than ideal (slightly more
        // noise) -- never a failure, and far better than an OOM kill.
        val cap = burstNCap()
        val predicted = (lastMeteredBurstN.get() + 1).coerceIn(1, cap)
        val burstCap = predicted
        // Actual frame count for this shot; set once AE has reported. The image
        // listener latches on this, and the divisor is the count actually summed.
        val burstTarget = java.util.concurrent.atomic.AtomicInteger(burstCap)
        // AE's settled exposure/gain, published by the warmup for the metering
        // step below. 0 = AE never converged -> keep the full burst.
        val aeIso = java.util.concurrent.atomic.AtomicInteger(0)
        val aeExpNs = java.util.concurrent.atomic.AtomicLong(0L)
        Log.i(TAG, "doCapture ${w}x${h} burstCap=$burstCap black=$blackLevel white=$whiteLevel (AE_ON warmup then LOCK)" +
            " isoRange=${chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)}" +
            " expRange=${chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)}")

        // Burst accumulation, allocated LAZILY.
        //
        // The common case on this device is burstN==1 (any decently lit scene),
        // and for a single frame an int32 accumulator is pure waste: a 48.8MB
        // copy of a 24.4MB frame that is then divided by 1. On a 1.8GB device
        // that alone was enough to get the capture process OOM-killed mid-shot.
        //
        // So: keep frame 0 as a ShortArray, and only materialise the int32
        // accumulator if a second frame actually turns up. Peak drops from
        // ~195MB to ~122MB for the single-frame path.
        var first: ShortArray? = null      // frame 0, kept verbatim
        var acc: IntArray? = null          // sum of frames, only if burstN > 1
        val received = java.util.concurrent.atomic.AtomicInteger(0)
        val imageLatch = CountDownLatch(1)
        val frameErr = arrayOfNulls<Throwable>(1)

        // RAW reader holds all burstN burst frames. No separate metering frame:
        // metering off a distinct RAW frame faults the HAL FD pipeline (~20% photo
        // failure), so the burst captures at comp=0 (always-works) and DRO is measured
        // from the burst's own averaged RAW after the fact (zero extra frames).
        val reader = ImageReader.newInstance(w, h, ImageFormat.RAW_SENSOR, burstCap)
        // Set once the body is done with this reader. The listener checks it
        // before acquiring: after abortCaptures() the HAL can still deliver
        // surplus frames, and acquireNextImage() on a CLOSED ImageReader throws
        // IllegalStateException rather than returning null. That throw lands on
        // the RawStill-cb HandlerThread with no handler above it, which kills
        // the whole capture process -- the exact "two presses in 1s crashes"
        // report: press #2 short-circuits press #1's burst, the abort fires, and
        // the surplus frames race reader.close().
        val readerClosed = java.util.concurrent.atomic.AtomicBoolean(false)
        // Callbacks currently touching the reader's NATIVE buffers. The teardown
        // waits for this to hit zero before closing, so the buffer can never be
        // unmapped while a memcpy is running inside it (see the SIGSEGV note in
        // the listener body).
        val callbacksInFlight = java.util.concurrent.atomic.AtomicInteger(0)
        reader.setOnImageAvailableListener({ r ->
            // INCREMENT BEFORE CHECKING -- this ordering is the whole point.
            //
            // Checking first and incrementing after leaves a hole: this thread
            // could read readerClosed=false, be preempted, the teardown then
            // observes callbacksInFlight==0 and closes the reader, and we resume
            // into a memcpy on unmapped memory. Incrementing first makes it a
            // Dekker-style handshake -- either the teardown sees our increment
            // and waits, or we see its flag and bail. One of the two must hold.
            callbacksInFlight.incrementAndGet()
            try {
            if (readerClosed.get()) return@setOnImageAvailableListener
            // EVERYTHING from acquire onwards is guarded: a throw here is fatal
            // to the process, and nothing this listener does is worth that.
            val img = try {
                r.acquireNextImage()
            } catch (e: Throwable) {
                Log.w(TAG, "acquireNextImage failed (reader closing?): ${e.message}")
                null
            } ?: run {
                Log.w(TAG, "acquireNextImage null")
                return@setOnImageAvailableListener
            }
            try {
                // NATIVE USE-AFTER-FREE GUARD.
                //
                // A surplus frame's callback can be mid-`get()` while the body's
                // finally closes the reader. The ImageReader's native buffer is
                // then unmapped underneath the copy and memcpy segfaults inside
                // SetShortArrayRegion -- an unrecoverable SIGSEGV, not a Java
                // exception, so no catch block can save the process. Confirmed by
                // tombstone: `signal 11 (SIGSEGV) ... __memcpy ... ShortBuffer.get
                // ... captureBurst$lambda`, on thread RawStill-cb.
                //
                // Re-check the flag immediately before touching native memory:
                // the acquire above may have succeeded microseconds before the
                // close. This is a race we can narrow but not eliminate in Kotlin
                // alone, which is why the teardown ALSO drops the listener and
                // marks readerClosed before calling reader.close().
                if (readerClosed.get()) {
                    Log.w(TAG, "reader closed mid-callback; dropping frame before native read")
                    return@setOnImageAvailableListener
                }
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
                    // Decide the burst length from THIS frame's actual pixels
                    // combined with AE's exposure/gain. Must happen before the
                    // accumulate below, so the latch target is correct by the
                    // time `done` is compared against it.
                    val iso = aeIso.get()
                    val expNs = aeExpNs.get()
                    // Clamped to burstCap = the reader's actual depth. The depth
                    // is a PREDICTION from the previous shot, so a scene that
                    // suddenly got darker could meter more frames than we have
                    // buffers for -- and waiting for a frame the reader can
                    // never deliver would hang the capture until its timeout.
                    // Fewer frames means slightly more noise; a hang means no
                    // photo at all.
                    val chosen = if (iso > 0 && expNs > 0L) {
                        val amb = RawDemosaic.ambientLevel(shorts, w, h, blackLevel, whiteLevel)
                        burstNForScene(iso, expNs, amb, burstCap)
                    } else {
                        burstCap  // AE never converged -> use everything we reserved
                    }
                    burstTarget.set(chosen)
                    // Feed the next capture's reader-depth prediction.
                    lastMeteredBurstN.set(chosen)
                    Log.i(TAG, "burst length: n=$chosen readerDepth=$burstCap")
                    // No copy: `shorts` was freshly allocated from this Image's
                    // buffer above and nothing else retains it, so the preview
                    // worker can own it directly. Saves a 24.4MB duplicate on
                    // the capture critical path.
                    val frame0 = shorts
                    previewExecutor.execute {
                        try {
                            val tPrev = android.os.SystemClock.elapsedRealtime()
                            // No explicit DRO gain on the fast preview: fastPreviewToBitmap
                            // already percentile-stretches (p2..p98) its grayscale output,
                            // which inherently lifts a dark scene to a visible range -- so the
                            // preview is already roughly as bright as the DRO-corrected final.
                            // The final color JPEG (binToBitmap) carries the precise measured
                            // gain; the preview just gives an instant, already-bright proxy.
                            val tDem = android.os.SystemClock.elapsedRealtime()
                            val gray = RawDemosaic.fastPreviewToBitmap(
                                frame0, w, h, blackLevel, whiteLevel,
                            )
                            val demMs = android.os.SystemClock.elapsedRealtime() - tDem
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
                            val tEnc = android.os.SystemClock.elapsedRealtime()
                            val published = writeJpegAtomic(out, thumb, PREVIEW_JPEG_QUALITY)
                            if (thumb !== rotated && !thumb.isRecycled) thumb.recycle()
                            rotated.recycle()
                            val encMs = android.os.SystemClock.elapsedRealtime() - tEnc
                            if (!published) throw IllegalStateException("preview publish failed")
                            Log.i(TAG, "fastPreview done ${out.absolutePath} bytes=${out.length()} previewMs=${android.os.SystemClock.elapsedRealtime() - takePhotoStartMs} pathMs=${android.os.SystemClock.elapsedRealtime() - tPrev} demosaicMs=$demMs encodeMs=$encMs")
                            onEarlyPreview(out, null)
                        } catch (e: Throwable) {
                            Log.e(TAG, "fastPreview failed: ${e.message}")
                            // Do NOT fire onEarlyPreview on failure; the legacy slow
                            // path after the full-burst demosaic will still deliver
                            // the preview from the freshly written full-res file.
                        }
                    }
                }
                // Ignore anything beyond what the scene needs. The burst always
                // REQUESTS the full count (the scene can only be metered from
                // frame 0), so surplus frames are expected here; summing one
                // without counting it -- or racing the divisor read -- would
                // brighten the whole photo. Dropped here; the enclosing finally
                // still closes the Image.
                val target = burstTarget.get()
                if (received.get() >= target) {
                    Log.i(TAG, "burst RAW surplus frame ignored (have ${received.get()}/$target)")
                } else {
                    if (received.get() == 0) {
                        // Frame 0: keep it verbatim. No accumulator yet -- if the
                        // scene metered to a single frame, none is ever needed.
                        first = shorts
                    } else {
                        // A second frame exists, so we really are averaging.
                        // Materialise the accumulator now and seed it with frame 0.
                        var a = acc
                        if (a == null) {
                            a = IntArray(w * h)
                            val f = first
                            if (f != null) {
                                for (i in 0 until w * h) a[i] = f[i].toInt() and 0xFFFF
                            }
                            acc = a
                            first = null   // released; the sum owns the data now
                        }
                        for (i in 0 until w * h) a[i] += (shorts[i].toInt() and 0xFFFF)
                    }
                    val done = received.incrementAndGet()
                    Log.i(TAG, "burst RAW $done/$target accumulated")
                    if (done >= target) imageLatch.countDown()
                }
            } catch (e: Throwable) {
                frameErr[0] = e
                imageLatch.countDown()
            } finally {
                // close() can itself throw if the reader was closed underneath
                // us (surplus frame racing teardown). Swallow: an unhandled
                // throw on this HandlerThread kills the capture process.
                try { img.close() } catch (e: Throwable) {
                    Log.w(TAG, "image close failed: ${e.message}")
                }
            }
            } catch (e: Throwable) {
                // Last line of defence. This lambda runs on a bare HandlerThread
                // with nothing above it, so ANY escaping throwable kills the
                // capture process outright.
                Log.e(TAG, "image callback threw: ${e.message}", e)
                frameErr[0] = e
                imageLatch.countDown()
            } finally {
                // Diagnostic only: the close is posted to this same looper, so
                // it cannot overlap a callback regardless of this count.
                callbacksInFlight.decrementAndGet()
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
                    // NOTE: do NOT shorten this on a "warm" camera. Tried it
                    // (700ms budget when a capture converged recently): AE then
                    // reported converged=false / aeState=5 (PRECAPTURE) and the
                    // burst fired on an unsettled exposure -- exactly the
                    // black-photo failure documented above. AE needs the full
                    // budget to re-run its precapture sequence even when the
                    // sensor is warm, because each capture builds a NEW session.
                    // The preview latency win has to come from elsewhere (the
                    // fast-preview demosaic, which is where it actually was).
                    val convergedOk = converged.await(AE_WARMUP_MS, TimeUnit.MILLISECONDS)
                    session.stopRepeating()
                    Log.i(TAG, "AE warmup: converged=$convergedOk aeState=${lastAeState.get()} exp=${lastExp.get() / 1_000_000.0}ms iso=${lastIso.get()}")

                    // Publish AE's settled exposure/gain for the metering step.
                    // The burst length is NOT decided here: AE metadata alone
                    // cannot separate a lit room from a dark one on this HAL (see
                    // burstNForScene). It is decided in the image listener, from
                    // the first RAW frame's own pixels, once both halves of the
                    // light equation are in hand.
                    if (convergedOk) {
                        aeIso.set(lastIso.get())
                        aeExpNs.set(lastExp.get())
                    }
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
                // Always REQUEST the full burst: the scene can only be metered
                // from the first frame's pixels, which do not exist yet. The
                // listener decides the real target the moment frame 0 lands and
                // the extra frames are aborted below, so a bright scene still
                // costs only its one frame of hold-still time.
                val nFrames = burstCap
                val burstRequests = List(nFrames) { builder.build() }
                val captureLatch = CountDownLatch(nFrames)
                val captureErr = arrayOfNulls<Throwable>(1)
                // WB gains read from the last burst frame's result (stable across the
                // burst, whatever its length), written into the hoisted wbGainsOut so
                // they survive the borrow; persisted with the raw so the disk
                // demosaic matches.
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
                        // If every request is now accounted for and the frames the
                        // scene needs still have not arrived, no further image can
                        // come -- wake the body so it rethrows the REAL failure
                        // immediately instead of sitting out the full frames
                        // timeout and then reporting the wrong error.
                        if (captureLatch.count == 0L && received.get() < burstTarget.get()) {
                            imageLatch.countDown()
                        }
                    }
                }, handler)
                // Wait only for the frames this scene actually needs. imageLatch
                // trips as soon as `received` reaches the metered target, so a
                // bright scene stops here after frame 1 instead of sitting
                // through all three -- that wait IS the user's hold-still time.
                // It ALSO trips on a capture failure that can no longer produce
                // the missing frames, so a dead burst fails fast instead of
                // sitting out the full timeout.
                if (!imageLatch.await(BURST_FRAMES_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                    throw IllegalStateException("burst frames timeout (received=${received.get()}/${burstTarget.get()})")
                frameErr[0]?.let { throw it }
                // Did we get everything the scene needed? Decide BEFORE aborting:
                // once enough frames are accumulated the shot is a success, and
                // the abort below deliberately induces failures we must not
                // mistake for real ones.
                val gotEnough = received.get() >= burstTarget.get()
                if (!gotEnough) captureErr[0]?.let { throw it }
                // Drop any still-outstanding frames of the requested burst. The
                // accumulator already has everything it will use; aborting stops
                // the sensor now rather than making the user hold still for
                // frames that would be discarded.
                //
                // NOTE: aborted requests come back through onCaptureFailed with
                // REASON_FLUSHED and set captureErr. That is expected here and
                // must NOT be rethrown -- doing so would fail a good photo and
                // trigger a full BURST_RETRY. Hence the gotEnough check above,
                // and no captureErr check after this point.
                if (captureLatch.count > 0L) {
                    try { session.abortCaptures() } catch (e: Exception) {
                        Log.w(TAG, "abortCaptures failed: ${e.message}")
                    }
                    Log.i(TAG, "burst short-circuited: used ${received.get()}/$nFrames frames")
                }
                Log.i(TAG, "burst captured durMs=${android.os.SystemClock.elapsedRealtime() - tBurst}")
                // All RAW frames are in hand -- the scene no longer needs to be held
                // still. Signal "photo taken" so the UI can show the captured checkmark
                // (the remaining demosaic/denoise work off the buffered frames).
                try { onShutterDone() } catch (_: Throwable) {}
            } catch (e: Throwable) {
                bodyErr[0] = e
                throw e
            } finally {
                // Order matters. Mark closed and DROP the listener before
                // closing the reader, so a surplus frame still queued on the
                // handler cannot call acquireNextImage() on a dead reader.
                readerClosed.set(true)
                try { reader.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
                try { session?.close() } catch (_: Exception) {}
                // CLOSE ON THE LISTENER'S OWN LOOPER.
                //
                // reader.close() unmaps the native buffers; doing that while a
                // callback is mid-memcpy is a SIGSEGV that kills the process
                // (tombstone: __memcpy <- SetShortArrayRegion <- ShortBuffer.get,
                // thread RawStill-cb). Image callbacks are delivered serially on
                // `handler`, so a close POSTED to that same looper is guaranteed
                // to run after any in-progress callback has returned -- no
                // busy-wait, no timeout, and no residual race.
                //
                // An earlier version polled callbacksInFlight from this (camera)
                // thread and closed anyway on timeout, which re-opened the exact
                // crash window precisely when the system was slowest. The
                // counter is kept only as a diagnostic.
                val inFlight = callbacksInFlight.get()
                if (inFlight > 0) Log.i(TAG, "reader close deferred behind $inFlight in-flight callback(s)")
                try {
                    handler.post {
                        try { reader.close() } catch (e: Throwable) {
                            Log.w(TAG, "deferred reader close failed: ${e.message}")
                        }
                    }
                } catch (e: Throwable) {
                    // Looper gone (shutdown): nothing can be running on it either,
                    // so closing inline is safe here.
                    Log.w(TAG, "could not post reader close (${e.message}); closing inline")
                    try { reader.close() } catch (_: Exception) {}
                }
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
        // Divide by the number of frames ACTUALLY summed, not the number
        // requested. imageLatch only trips at the target, so these normally
        // agree -- but dividing by a request count the accumulator never
        // reached would darken the whole photo, so read it from the counter.
        val summed = received.get().coerceAtLeast(1)
        val accLocal = acc
        val avg: ShortArray = if (accLocal == null) {
            // Single-frame burst: frame 0 IS the result. No accumulator was ever
            // allocated and no division is needed -- hand the buffer straight on.
            first ?: throw IllegalStateException("burst produced no frames")
        } else {
            val out = ShortArray(w * h)
            for (i in 0 until w * h) {
                out[i] = (accLocal[i] / summed).toShort()
            }
            out
        }
        // Exposure is corrected by the demosaic's own percentile auto-level from this
        // RAW, so nothing exposure-related is measured or persisted here.
        Log.i(TAG, "burst accumulated+averaged ${w}x${h} burstN=$summed wb=[${wbGainsOut[0]},${wbGainsOut[1]},${wbGainsOut[2]}] (camera free, demosaic offloaded)")
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
            // Guarded acquire: on a closed reader this throws ISE rather than
            // returning null, and an unhandled throw on this HandlerThread kills
            // the capture process. Same hazard as the burst listener.
            val img = try {
                r.acquireNextImage()
            } catch (e: Throwable) {
                Log.w(TAG, "reid acquireNextImage failed (reader closing?): ${e.message}")
                null
            } ?: return@setOnImageAvailableListener
            try {
                val buf = img.planes[0].buffer
                buf.asShortBuffer().get(frame, 0, minOf(frame.size, buf.remaining() / 2))
                imageLatch.countDown()
            } catch (e: Throwable) {
                frameErr[0] = e
                imageLatch.countDown()
            } finally {
                // close() can itself throw if the reader was closed underneath
                // us (surplus frame racing teardown). Swallow: an unhandled
                // throw on this HandlerThread kills the capture process.
                try { img.close() } catch (e: Throwable) {
                    Log.w(TAG, "image close failed: ${e.message}")
                }
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
                // Same native use-after-free hazard as the burst path: this
                // capture has a 5s frame timeout that throws straight into this
                // finally, so a late frame can be mid-memcpy when we close. Post
                // the close to the listener's own looper -- callbacks are
                // serialized there, so it provably runs after any in-progress
                // one. Rapid presses interleave photo and ReID frames, which is
                // exactly the reachable case.
                try {
                    reader.setOnImageAvailableListener(null, null)
                    handler.post {
                        try { reader.close() } catch (e: Throwable) {
                            Log.w(TAG, "deferred reid reader close failed: ${e.message}")
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "could not post reid reader close (${e.message}); closing inline")
                    try { reader.close() } catch (_: Exception) {}
                }
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

    /**
     * Burst length for this shot: [BURST_N] unless the DEBUG [BURST_N_PROP]
     * non-persist property overrides it (clamped to 1..[BURST_N_MAX]). Exists to
     * A/B the noise-vs-shutter-latency tradeoff on-device without a rebuild --
     * each extra frame adds ~1s of "hold still" but averages down read noise.
     * Read ONCE per capture into a local, so a prop change mid-burst can never
     * desync the accumulator from the divisor.
     */
    /**
     * Choose the burst length for the scene AE just metered.
     *
     * Rationale: burst averaging beats down sensor noise (~sqrt(N); measured on
     * this sensor: flat-field noise 6.16 / 5.22 / 4.21 for N = 1 / 2 / 3), and
     * every extra frame costs the user ~1s of holding still. Spend frames only
     * where the noise is.
     *
     * WHY NOT AE METADATA: exposure/ISO alone cannot do this on this HAL. AE pins
     * ISO at 800 (sensor range is [50, 12680]) and quantizes exposure to 10/20/
     * 30ms, so across a MEASURED 13x change in scene light (emitter coverage 7%
     * -> 0.55%) it moved exactly one step, with the lit and dark rooms overlapping
     * at 20ms. AE output is also already compensated -- it is the RESULT of
     * solving for the scene, not a measurement of it.
     *
     * WHAT THIS USES: the actual photon rate, from the burst's own first RAW
     * frame. [RawDemosaic.ambientLevel] gives sensor response of the ROOM (a low
     * percentile, so a monitor or lamp filling part of the frame cannot make a
     * dark room read as bright). Dividing by exposure x gain undoes AE's
     * compensation and leaves a quantity proportional to real incident light:
     *
     *     light = ambient / (exp_ms * iso)
     *
     * This works in BOTH regimes -- when AE has headroom the denominator moves,
     * and when AE is saturated (the indoor case here) the numerator does.
     */
    private fun burstNForScene(iso: Int, expNs: Long, ambient: Float, cap: Int): Int {
        // Missing/garbage metadata -> full burst (safe, higher quality).
        if (iso <= 0 || expNs <= 0L) {
            Log.i(TAG, "scene light: no AE metadata (iso=$iso expNs=$expNs) -> n=$cap")
            return cap
        }
        val expMs = expNs / 1_000_000.0
        // A zero median means a scene darker than the sensor can register at all
        // -- unambiguously the dim end, so take the full burst. (Not an error
        // case: it is the correct answer, just one the ratio cannot express.)
        if (ambient <= 0f) {
            Log.i(TAG, "scene light: median=0 (below sensor floor) exp=${expMs}ms iso=$iso -> n=$cap")
            return cap
        }
        // Scale is arbitrary (units are response per ms per ISO); SCENE_LIGHT_*
        // are calibrated against measured captures on this device.
        val light = ambient / (expMs * iso) * SCENE_LIGHT_SCALE
        val n = when {
            light >= BRIGHT_LIGHT -> 1   // well-lit: single frame is clean
            light >= MID_LIGHT -> 2      // moderately lit
            else -> 3                     // dim: averaging clearly pays
        }
        Log.i(TAG, "scene light=${"%.1f".format(light)} (ambient=${"%.4f".format(ambient)} " +
            "exp=${expMs}ms iso=$iso) -> n=$n")
        return n.coerceAtMost(cap)
    }

    private fun burstNCap(): Int {
        return try {
            val sp = Class.forName("android.os.SystemProperties")
            val v = sp.getMethod("get", String::class.java).invoke(null, BURST_N_PROP) as? String
            val n = v?.trim()?.toIntOrNull() ?: return BURST_N
            n.coerceIn(1, BURST_N_MAX)
        } catch (e: Exception) {
            Log.w(TAG, "burstN cap read failed: ${e.message}")
            BURST_N
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
    /**
     * Write a JPEG so that any concurrent reader sees either the OLD complete
     * file or the NEW complete file -- never a partially written one.
     *
     * Necessary because this JPEG is published to other processes (the preview
     * overlay in :backend decodes it, filesync ships it to the phone) while this
     * process rewrites the SAME path up to three times: fast preview, then the
     * demosaiced full-res, then the denoised final. Writing in place -- and
     * especially [stampExifOrientationNormal], which reopens and rewrites the
     * file after the pixels are down -- gave the reader a window onto a torn
     * file, and BitmapFactory returned null ("decode returned null" on device),
     * so the preview silently vanished.
     *
     * Encode + EXIF-stamp happen on a sibling temp file; the rename at the end
     * is atomic within a directory, which is what makes publication safe. The
     * temp is removed on any failure so a crash cannot leave litter behind.
     *
     * @return true if the file was published.
     */
    private fun writeJpegAtomic(target: File, bmp: Bitmap, quality: Int): Boolean {
        // Same directory: rename() is only atomic within a filesystem.
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            // Stamp while still private. Doing this after publication would
            // reintroduce exactly the torn-read window this method exists to close.
            stampExifOrientationNormal(tmp)
            if (!tmp.renameTo(target)) {
                Log.e(TAG, "atomic publish failed (rename) ${tmp.absolutePath} -> ${target.absolutePath}")
                tmp.delete()
                return false
            }
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "atomic publish failed: ${e.message}")
            tmp.delete()
            return false
        }
    }

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

    /**
     * Pre-open the camera and run one throwaway RAW+preview session so the FIRST
     * real [takePhoto] does not pay cold-HAL bring-up.
     *
     * On a cold camera the Qualcomm HAL transiently exhausts a sensor/CSIPHY
     * resource during bring-up and drops a burst frame with reason=0 (see the
     * BURST_RETRY loop in [takePhoto]). That retry re-runs session configure AND
     * a fresh AE warmup -- measured ~5.2 s, paid once per boot, entirely inside
     * the window where the user is staring at the capture preview overlay.
     *
     * This configures the exact stream combo the real burst uses (RAW_SENSOR +
     * PRIVATE preview), drives AE to convergence, and pulls ONE throwaway RAW
     * frame -- so the resource churn, the first-AE search, and the bring-up
     * frame drop all happen at service start instead. The RAW frame is not
     * optional: the reason=0 failure is a FRAME error, so configuring the
     * stream without requesting a frame would leave the flake for the first
     * real photo.
     *
     * Best-effort: any failure is logged and swallowed. A failed warmup must
     * never prevent a later real capture (which still has its own retry loop).
     */
    fun warmUp() {
        // The AIDL warmUp() is called on every listener bind (CaptureBridge), and
        // CaptureService also calls it at onCreate -- on boot both land within
        // seconds. Warming twice just doubles the camera-open cost it exists to
        // avoid, so collapse repeats inside the validity window.
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(warmUpLock) {
            if (lastWarmUpMs != 0L && now - lastWarmUpMs < WARMUP_VALID_MS) {
                Log.i(TAG, "warmUp skipped (warmed ${now - lastWarmUpMs}ms ago)")
                return
            }
            // Provisional claim: prevents a concurrent caller from starting a
            // second warmup while this one runs. Cleared again if the attempt
            // does not actually warm anything, so a failure does not suppress
            // retries for the whole validity window.
            lastWarmUpMs = now
        }
        // Serialize with real captures on the same single-thread executor
        // takePhoto uses, so a warmup can never sit in front of a user shutter
        // press on CameraSession's handler queue.
        executor.execute { warmUpBody() }
    }

    private fun warmUpBody() {
        // A shutter press that arrived while this was queued wins outright --
        // warming is pure optimization and the real capture warms the HAL anyway.
        if (photoPending > 0) {
            // A real capture warms the HAL far better than this does, so this is
            // not a failure -- but nothing was warmed by US, and the real photo
            // may be a ReID frame rather than a full still, so allow a retry.
            Log.i(TAG, "warmUp skipped: photoPending=$photoPending")
            synchronized(warmUpLock) { lastWarmUpMs = 0L }
            return
        }
        val t0 = android.os.SystemClock.elapsedRealtime()
        var reader: ImageReader? = null
        var previewTex: SurfaceTexture? = null
        var previewSurface: Surface? = null
        warmUpInFlight = true
        // Silent open: no user action is behind this, so the firmware privacy LED
        // must stay dark. Gate at the source BEFORE the device opens -- Rokid's
        // cameraserver fires CAMERA_OPEN(2014) on open and reads the prop then.
        acquireSilentLed()
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = findRawCapableCamera(manager) ?: run {
                Log.w(TAG, "warmUp: no RAW camera")
                return
            }
            val chars = manager.getCameraCharacteristics(cameraId)
            val rawSize = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.RAW_SENSOR)?.firstOrNull() ?: run {
                Log.w(TAG, "warmUp: no RAW sizes")
                return
            }
            reader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 1)
            previewTex = SurfaceTexture(0).apply { setDefaultBufferSize(PREVIEW_TEX_W, PREVIEW_TEX_H) }
            previewSurface = Surface(previewTex)
            val rdr = reader
            val surf = previewSurface
            val tex = previewTex

            // borrowDeviceExclusive can return WITHOUT ever running the body --
            // it refuses outright while recording, and swallows an open failure
            // internally. In those cases no exception reaches us and the body's
            // finally never runs, so ownership of the reader/surface/texture
            // stays here. This flag is what tells the two cases apart.
            val bodyEntered = java.util.concurrent.atomic.AtomicBoolean(false)
            val ran = cameraSession.borrowDeviceExclusive(BORROW_TIMEOUT_MS) { camera ->
                bodyEntered.set(true)
                var session: CameraCaptureSession? = null
                try {
                    val sessLatch = CountDownLatch(1)
                    val sessOut = arrayOfNulls<CameraCaptureSession>(1)
                    @Suppress("DEPRECATION")
                    camera.createCaptureSession(
                        listOf(rdr.surface, surf),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                sessOut[0] = s; sessLatch.countDown()
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                sessLatch.countDown()
                            }
                        },
                        handler,
                    )
                    if (!sessLatch.await(3, TimeUnit.SECONDS)) {
                        Log.w(TAG, "warmUp: session configure timeout")
                        return@borrowDeviceExclusive
                    }
                    session = sessOut[0] ?: run {
                        Log.w(TAG, "warmUp: session configure failed")
                        return@borrowDeviceExclusive
                    }

                    // Same one-shot precapture pattern as the real burst: free-running
                    // AE preview + exactly ONE trigger. A trigger inside the repeating
                    // request pins AE at PRECAPTURE and never converges.
                    val base = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surf)
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
                            val st = result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE)
                            if (st == android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                                st == android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_LOCKED ||
                                st == android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                            ) converged.countDown()
                        }
                    }
                    session.setRepeatingRequest(base.build(), cb, handler)
                    val trigger = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surf)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                        set(
                            CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                            CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START,
                        )
                    }
                    session.capture(trigger.build(), cb, handler)
                    // Shorter AE budget than a real capture. Warmup only needs AE
                    // to have RUN once (so the HAL holds a sane exposure and the
                    // next capture's search starts warm); it does not need a
                    // publishable exposure. Every ms spent here is a ms the camera
                    // handler is blocked to a shutter press arriving right behind
                    // us, which is the exact window this change exists to protect.
                    val ok = converged.await(WARMUP_AE_MS, TimeUnit.MILLISECONDS)
                    try { session.stopRepeating() } catch (_: Exception) {}
                    Log.i(TAG, "warmUp: AE converged=$ok")

                    // Pull ONE throwaway RAW frame. Configuring the stream alone
                    // does not exercise the RAW buffer path, and the reason=0
                    // bring-up drop we are trying to absorb is a FRAME failure on
                    // the ZSLPreviewRaw usecase -- so the frame has to actually be
                    // requested here or the flake just moves to the first real
                    // photo. Failure is fine and expected on a cold HAL: absorbing
                    // it here is the entire point.
                    val rawLatch = CountDownLatch(1)
                    val rawFailed = booleanArrayOf(false)
                    rdr.setOnImageAvailableListener({ r ->
                        try { r.acquireNextImage()?.close() } catch (_: Exception) {}
                        rawLatch.countDown()
                    }, handler)
                    val still = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(rdr.surface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    }
                    session.capture(still.build(), object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureFailed(
                            s: CameraCaptureSession,
                            req: CaptureRequest,
                            failure: android.hardware.camera2.CaptureFailure,
                        ) {
                            rawFailed[0] = true
                            rawLatch.countDown()
                        }
                    }, handler)
                    val rawOk = rawLatch.await(WARMUP_RAW_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    Log.i(TAG, "warmUp: raw frame ok=$rawOk failed=${rawFailed[0]}")
                } catch (e: Throwable) {
                    Log.w(TAG, "warmUp body failed: ${e.message}")
                } finally {
                    // Close inside the body, not after borrowDeviceExclusive
                    // returns: that call can return false with the body still
                    // running on the camera handler, which would otherwise pull
                    // the reader out from under a live session.
                    //
                    // Drop the listener FIRST. It fires on the RawStill handler
                    // thread while this finally runs on the camera handler, so a
                    // frame arriving after the RAW timeout could otherwise be
                    // inside acquireNextImage() as close() frees the native
                    // reader -- a use-after-free no catch block would save us
                    // from.
                    try { rdr.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
                    try { session?.close() } catch (_: Exception) {}
                    try { rdr.close() } catch (_: Exception) {}
                    try { surf.release() } catch (_: Exception) {}
                    try { tex.release() } catch (_: Exception) {}
                }
            }
            if (!bodyEntered.get()) {
                // Body never ran (borrow refused / device open failed): we still
                // own these, and nothing was warmed, so allow an immediate retry.
                Log.w(TAG, "warmUp: borrow did not run body")
                synchronized(warmUpLock) { lastWarmUpMs = 0L }
                try { reader?.close() } catch (_: Exception) {}
                try { previewSurface?.release() } catch (_: Exception) {}
                try { previewTex?.release() } catch (_: Exception) {}
            }
            Log.i(TAG, "warmUp done ran=$ran bodyRan=${bodyEntered.get()} ms=${android.os.SystemClock.elapsedRealtime() - t0}")
        } catch (e: Throwable) {
            Log.w(TAG, "warmUp failed: ${e.message}")
            synchronized(warmUpLock) { lastWarmUpMs = 0L }
            // Only reached if setup threw BEFORE the borrow body took ownership;
            // once the body runs it closes these in its own finally.
            try { reader?.close() } catch (_: Exception) {}
            try { previewSurface?.release() } catch (_: Exception) {}
            try { previewTex?.release() } catch (_: Exception) {}
        } finally {
            warmUpInFlight = false
            releaseSilentLed()
        }
    }

    fun shutdown() {
        handlerThread.quitSafely()
        executor.shutdown()
        processExecutor.shutdown()
        retryScheduler.shutdown()
    }
}
