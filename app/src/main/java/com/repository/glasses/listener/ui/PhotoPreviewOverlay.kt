package com.repository.glasses.listener.ui

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.exifinterface.media.ExifInterface
import com.repository.glasses.listener.config.GlassesConfig

/**
 * Shutter-confirmation overlay. Multi-instance, two-phase:
 *
 *  1. [requestPlaceholder] (called the moment the FN button is released)
 *     adds a [LoadingPreviewView] -- black-filled rounded frame with a
 *     spinning arc + a 0..1 progress bar at the bottom. Bounces in right
 *     away so the user gets instant visual feedback even though the camera
 *     capture takes ~2 s.
 *  2. [show] (called when the JPEG file lands) decodes the bitmap, swaps
 *     the LoadingPreviewView for an ImageView holding the photo, and
 *     starts the standard dwell timer.
 *
 * Photos are pre-processed into a new bitmap with rounded corners + a
 * grayscale + high-contrast color matrix baked in (mirrors Rokid's
 * `ImageUtil.getRoundedCornerBitmap` SRC_IN pattern). Without this the
 * photo looks washed-out / semi-transparent on the green waveguide.
 */
class PhotoPreviewOverlay(private val context: Context) {

    var remoteLog: ((String) -> Unit)? = null

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dp = Resources.getSystem().displayMetrics.density
    private val screenW = Resources.getSystem().displayMetrics.widthPixels
    private val screenH = Resources.getSystem().displayMetrics.heightPixels

    private val main = Handler(Looper.getMainLooper())
    private val ioThread = HandlerThread("PhotoPreviewOverlay.io").apply { start() }
    private val io = Handler(ioThread.looper)

    private val active = LinkedHashSet<Showing>()
    private val pendingPlaceholders = ArrayDeque<Showing>()

    private class Showing(
        val root: FrameLayout,
        var content: View,
        var bitmap: Bitmap?,
        var dwellRunnable: Runnable,
    )

    fun requestPlaceholder() {
        main.post { attachPlaceholder() }
    }

    /**
     * Cancel the most recently added placeholder (e.g. when the capture fails
     * with ERR_BUSY before any photo is produced). Without this a failed
     * capture leaves the spinner on screen until the long placeholder
     * timeout runs out.
     */
    fun clearPendingPlaceholder() {
        main.post {
            val orphan = pendingPlaceholders.removeFirstOrNull() ?: return@post
            if (active.contains(orphan)) {
                android.util.Log.i("PhotoPreview", "clearPendingPlaceholder: removing spinner after capture error")
                clear(orphan, "capture_error")
            }
        }
    }

    fun show(absPath: String) {
        android.util.Log.i("PhotoPreview", "show: enter absPath=$absPath")
        remoteLog?.invoke("PhotoPreview: show enter $absPath")
        io.post {
            val decoded = decodeWithExif(absPath)
            if (decoded == null) {
                android.util.Log.w("PhotoPreview", "show: decode returned null for $absPath")
                remoteLog?.invoke("PhotoPreview: decode null for $absPath")
                main.post {
                    val orphan = pendingPlaceholders.removeFirstOrNull()
                    if (orphan != null && active.contains(orphan)) {
                        clear(orphan, "decode_failed")
                    }
                }
                return@post
            }
            android.util.Log.i("PhotoPreview", "show: decoded ${decoded.bitmap.width}x${decoded.bitmap.height} rot=${decoded.rotationDeg}")
            val processed = processForWaveguide(decoded.bitmap)
            if (decoded.bitmap !== processed && !decoded.bitmap.isRecycled) {
                decoded.bitmap.recycle()
            }
            main.post {
                // The progress bar drives the swap timing: we ask the
                // LoadingPreviewView to complete its bar, and only when the
                // bar visually reaches 100% do we destroy the placeholder
                // window and attach the real photo. Result: preview always
                // appears at the moment the bar finishes, not before, not
                // after, regardless of how long the JPEG actually took.
                val placeholder = pendingPlaceholders.removeFirstOrNull()
                val loading = (placeholder?.content as? LoadingPreviewView)
                val swap = Runnable {
                    if (placeholder != null && active.contains(placeholder)) {
                        android.util.Log.i("PhotoPreview", "show: clearing placeholder to replace with image")
                        clear(placeholder, "replaced_by_image")
                    }
                    attachAndAnimate(processed, decoded.rotationDeg)
                }
                if (loading != null && active.contains(placeholder!!)) {
                    loading.complete(swap)
                } else {
                    swap.run()
                }
            }
        }
    }

    private data class Decoded(val bitmap: Bitmap, val rotationDeg: Float)

    private fun decodeWithExif(path: String): Decoded? {
        return try {
            val exif = ExifInterface(path)
            val w = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            val h = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
            val longEdge = maxOf(w, h)
            var sample = 1
            if (longEdge > 0) {
                while (longEdge / sample > TARGET_LONG_EDGE_PX) sample *= 2
            } else {
                sample = 16
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeFile(path, opts) ?: return null
            // RawStillCapturer bakes a 90 CCW rotation into the JPEG pixels
            // and stamps ORIENTATION_NORMAL (see feedback_glasses_photo_rotation).
            // Any other value here reflects a legacy file; honor it so old
            // captures still render upright.
            val rot = when (exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED,
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            Decoded(bmp, rot)
        } catch (e: Exception) {
            remoteLog?.invoke("PhotoPreview: decode exception: ${e.message}")
            null
        }
    }

    private fun processForWaveguide(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val cornerPx = CORNER_RADIUS_DP * dp
        // Step 1: remap source to a black->green luminance gradient with a
        // strong gamma+contrast curve so midtones read clearly on the
        // monochrome waveguide. Per-pixel pass -- small 540px preview so
        // this costs <5 ms.
        val gradient = luminanceToGreen(src)
        // Step 2: draw through a rounded-rect mask for the same preview
        // corner radius as before.
        val out: Bitmap = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (e: Throwable) {
            remoteLog?.invoke("PhotoPreview: createBitmap failed: ${e.message}")
            return gradient
        }
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        paint.color = Color.WHITE
        canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(gradient, 0f, 0f, paint)
        if (gradient !== src && !gradient.isRecycled) gradient.recycle()
        return out
    }

    /**
     * Remap each source pixel to an opaque black->green gradient:
     *   - R = 0, B = 0
     *   - G = gamma-corrected, contrast-stretched luminance
     *
     * Why a manual pass instead of ColorMatrix: the waveguide is very dark
     * at the low end, so a linear luminance ramp reads as near-invisible
     * for shadows. Applying gamma (~0.5) before contrast lifts midtones
     * into the visible range while preserving smooth tonality (no 1-bit
     * dither). Small preview (~540px long edge) so the cost is a few ms.
     */
    private fun luminanceToGreen(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val out: Bitmap = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (e: Throwable) {
            remoteLog?.invoke("PhotoPreview: luma createBitmap failed: ${e.message}")
            return src
        }
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // Per-image auto-level. The capture pipeline often hands us very dim
        // JPEGs (observed min=1 max=20 mean=2 in low-light / wrong-gain
        // scenes) -- a fixed input range would map all that signal to black.
        // We measure the actual min/max here and feed them as the LUT's
        // black/white anchors, so whatever dynamic range exists gets stretched
        // to the full output. Saturated/blown frames still map cleanly.
        var inMin = 255
        var inMax = 0
        run {
            var sum = 0L
            val n = w * h
            for (i in 0 until n) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val l = (r * 77 + g * 150 + b * 29) shr 8
                if (l < inMin) inMin = l
                if (l > inMax) inMax = l
                sum += l
            }
            android.util.Log.i("PhotoPreview", "luminanceToGreen in: min=$inMin max=$inMax mean=${sum / n}")
        }
        // Guard against degenerate (flat) frames; widen so we never divide
        // by ~0 and never produce a fully-flat green field.
        if (inMax - inMin < 8) {
            inMin = (inMin - 4).coerceAtLeast(0)
            inMax = (inMax + 4).coerceAtMost(255)
        }

        // Precomputed LUT: input 0..255 -> output 0..255.
        // Three-stage curve so the waveguide shows distinguishable tonality
        // across the full luminance range instead of crushing one end:
        //   1. Gamma (<1) lifts shadows out of the noise floor and remaps the
        //      sRGB-encoded input into a perceptually linear domain.
        //   2. Wide linear stretch with a small black/white crop, so true
        //      black is clean and highlights are not pre-clipped (previous
        //      white=0.45 was the bug -- everything above 45% input went to
        //      full green, killing all highlight detail).
        //   3. Sigmoid (logistic) S-curve: steepens midtone contrast for
        //      "punch" while rolling shadows and highlights off smoothly
        //      so neither end clips. k controls steepness.
        val lut = IntArray(256)
        val gamma = 0.55f       // perceptualize input after auto-stretch
        val k = 6.0f            // sigmoid steepness -- midtone contrast
        val inSpan = (inMax - inMin).coerceAtLeast(1)
        // Normalize sigmoid so x=0 -> 0 and x=1 -> 1 exactly.
        val sigLo = 1f / (1f + Math.exp((k * 0.5f).toDouble()).toFloat())
        val sigHi = 1f / (1f + Math.exp((-k * 0.5f).toDouble()).toFloat())
        val sigSpan = sigHi - sigLo
        for (v in 0..255) {
            // Auto-level: stretch [inMin, inMax] to [0, 1] before tonal curve.
            var y = (v - inMin).toFloat() / inSpan
            if (y < 0f) y = 0f else if (y > 1f) y = 1f
            // Gamma lifts shadows out of the noise floor.
            y = Math.pow(y.toDouble(), gamma.toDouble()).toFloat()
            // Sigmoid: punchy midtones, smooth shoulders -- no end clipping.
            val s = 1f / (1f + Math.exp((-k * (y - 0.5f)).toDouble()).toFloat())
            val mapped = (s - sigLo) / sigSpan
            lut[v] = (mapped.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        }

        var idx = 0
        for (i in 0 until w * h) {
            val p = pixels[idx]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            // BT.601 luminance, integer.
            val lum = (r * 77 + g * 150 + b * 29) shr 8
            val mapped = lut[lum]
            pixels[idx] = (0xFF shl 24) or (mapped shl 8)   // R=0, G=mapped, B=0
            idx++
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    // ---- placeholder path -----------------------------------------------

    private fun attachPlaceholder() {
        while (active.size >= MAX_CONCURRENT) {
            val oldest = active.iterator().next()
            clear(oldest, "evicted")
        }

        // Visual bounds match what the photo will occupy after its rotation
        // (default sensor 4:3 + 270 deg rotation = ~landscape preview box).
        val targetW = (PREVIEW_WIDTH_DP * dp).toInt()
        val targetH = (targetW * DEFAULT_ASPECT).toInt().coerceAtLeast(1)

        val loading = LoadingPreviewView(
            context = context,
            estimatedDurationMs = ESTIMATED_CAPTURE_MS,
            dp = dp,
        )

        val showing = createAndAnimate(
            content = loading,
            contentW = targetW,
            contentH = targetH,
            visibleH = targetH,
            isPlaceholder = true,
        )
        if (showing != null) {
            pendingPlaceholders.addLast(showing)
        }
    }

    /**
     * Replace the LoadingPreviewView with an ImageView holding the bitmap.
     * The container's visible dimensions stay the same -- the IV's
     * layout-w/h is sized for its rotation so the visible bounding box
     * matches what the placeholder occupied.
     */
    private fun attachPhotoToShowing(s: Showing, bmp: Bitmap, rotationDeg: Float) {
        android.util.Log.i("PhotoPreview", "attachPhotoToShowing: enter bmp=${bmp.width}x${bmp.height} rot=$rotationDeg")
        val rotated = rotationDeg % 180f != 0f
        val srcW = if (rotated) bmp.height else bmp.width
        val srcH = if (rotated) bmp.width else bmp.height
        val targetW = (PREVIEW_WIDTH_DP * dp).toInt()
        val aspect = srcH.toFloat() / srcW.toFloat()
        val targetH = (targetW * aspect).toInt().coerceAtLeast(1)
        val ivLayoutW = if (rotated) targetH else targetW
        val ivLayoutH = if (rotated) targetW else targetH
        android.util.Log.i("PhotoPreview", "attachPhotoToShowing: ivLayout=${ivLayoutW}x${ivLayoutH} rootSize=${s.root.width}x${s.root.height}")

        val borderPx = (BORDER_DP * dp).toInt()
        val cornerPx = CORNER_RADIUS_DP * dp
        val borderDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            // Solid black fill so other UI underneath can't bleed through the
            // dim / black pixels of the gradient preview. Without this, the
            // overlay stack composites our preview's shadow tones with whatever
            // window sits behind it, which looks like translucent "bleed".
            setColor(Color.BLACK)
            setStroke(borderPx, BORDER_COLOR)
            cornerRadius = cornerPx
        }

        val iv = ImageView(context).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            rotation = rotationDeg
            background = borderDrawable
            setPadding(borderPx, borderPx, borderPx, borderPx)
            alpha = 1f
            layoutParams = FrameLayout.LayoutParams(ivLayoutW, ivLayoutH, Gravity.CENTER)
        }

        // Stop the placeholder's animation loop, swap views.
        val prevContent = s.content
        (prevContent as? LoadingPreviewView)?.stop()
        s.root.removeView(prevContent)
        s.root.addView(iv)
        s.content = iv
        s.bitmap = bmp
        android.util.Log.i("PhotoPreview", "attachPhotoToShowing: swap done, root children=${s.root.childCount}")

        // Replace the long placeholder timeout with the standard dwell.
        main.removeCallbacks(s.dwellRunnable)
        val dwell = Runnable { animateExit(s) }
        s.dwellRunnable = dwell
        main.postDelayed(dwell, DWELL_MS)
    }

    // ---- bitmap-known path (no placeholder, e.g. ADB-triggered) --------

    private fun attachAndAnimate(bmp: Bitmap, rotationDeg: Float) {
        android.util.Log.i("PhotoPreview", "attachAndAnimate: ENTER bmp=${bmp.width}x${bmp.height} rot=$rotationDeg active.size=${active.size}")
        while (active.size >= MAX_CONCURRENT) {
            val oldest = active.iterator().next()
            android.util.Log.i("PhotoPreview", "attachAndAnimate: evicting oldest to make room")
            clear(oldest, "evicted")
        }

        val rotated = rotationDeg % 180f != 0f
        val srcW = if (rotated) bmp.height else bmp.width
        val srcH = if (rotated) bmp.width else bmp.height
        val targetW = (PREVIEW_WIDTH_DP * dp).toInt()
        val aspect = srcH.toFloat() / srcW.toFloat()
        val targetH = (targetW * aspect).toInt().coerceAtLeast(1)
        val ivLayoutW = if (rotated) targetH else targetW
        val ivLayoutH = if (rotated) targetW else targetH

        val borderPx = (BORDER_DP * dp).toInt()
        val cornerPx = CORNER_RADIUS_DP * dp
        val borderDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            // Solid black fill so other UI underneath can't bleed through the
            // dim / black pixels of the gradient preview. Without this, the
            // overlay stack composites our preview's shadow tones with whatever
            // window sits behind it, which looks like translucent "bleed".
            setColor(Color.BLACK)
            setStroke(borderPx, BORDER_COLOR)
            cornerRadius = cornerPx
        }

        val iv = ImageView(context).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            rotation = rotationDeg
            background = borderDrawable
            setPadding(borderPx, borderPx, borderPx, borderPx)
            alpha = 1f
        }

        android.util.Log.i("PhotoPreview", "attachAndAnimate: calling createAndAnimate ivLayout=${ivLayoutW}x${ivLayoutH} visibleH=$targetH")
        val showing = createAndAnimate(
            content = iv,
            contentW = ivLayoutW,
            contentH = ivLayoutH,
            visibleH = targetH,
            isPlaceholder = false,
        )
        android.util.Log.i("PhotoPreview", "attachAndAnimate: createAndAnimate returned showing=${showing != null}")
        if (showing != null) showing.bitmap = bmp
    }

    /**
     * Shared layout + bounce-in. [contentW]/[contentH] are the child view's
     * layout dimensions (which may differ from its visual extent if it's
     * a rotated ImageView). [visibleH] is the on-screen height the content
     * will actually occupy; used to compute the resting Y position.
     */
    private fun createAndAnimate(
        content: View,
        contentW: Int,
        contentH: Int,
        visibleH: Int,
        isPlaceholder: Boolean,
    ): Showing? {
        val root = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            // Opaque black backdrop sized to the content. Rounded-corner gaps of
            // the ImageView's borderDrawable let underlying windows leak through
            // without this; the backdrop covers those corner arcs with solid
            // black (which the waveguide renders as "off", i.e. genuine see-
            // through physics, but blocks any other UI window below us in the
            // overlay stack).
            val backdrop = View(context).apply {
                setBackgroundColor(Color.BLACK)
            }
            addView(backdrop, FrameLayout.LayoutParams(contentW, contentH, Gravity.CENTER))
            addView(content, FrameLayout.LayoutParams(contentW, contentH, Gravity.CENTER))
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                // Keep the display awake the entire time the placeholder OR the
                // final preview window is up. Without this the Rokid screen-off
                // timeout can fire mid-capture and cut the preview short.
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        )

        val bottomPad = if (GlassesConfig.bottomPaddingPx > 0) {
            GlassesConfig.bottomPaddingPx.toFloat()
        } else {
            DEFAULT_BOTTOM_PAD_DP * dp
        }
        // Clamp to on-screen: for tall previews (image vs placeholder), a large user
        // bottomPaddingPx can push restY above the top of the screen which makes the
        // whole preview invisible. Floor to 0 so the image is always on-screen.
        val restY = maxOf(0f, (screenH / 2f) - (visibleH / 2f) - bottomPad)
        val startY = (screenH / 2f) + visibleH

        try {
            wm.addView(root, params)
            android.util.Log.i("PhotoPreview", "createAndAnimate: addView ok isPlaceholder=$isPlaceholder contentW=$contentW contentH=$contentH screenW=$screenW screenH=$screenH restY=$restY bottomPad=$bottomPad startY=$startY")
        } catch (e: Exception) {
            android.util.Log.e("PhotoPreview", "createAndAnimate: addView FAILED: ${e.message}", e)
            remoteLog?.invoke("PhotoPreview: addView failed: ${e.message}")
            return null
        }

        root.translationY = startY
        root.alpha = 1f
        root.scaleX = 1f
        root.scaleY = 1f

        var showingRef: Showing? = null
        val dwell = Runnable { showingRef?.let { animateExit(it) } }
        val showing = Showing(root, content, null, dwell)
        showingRef = showing
        active.add(showing)

        val postDwellMs = if (isPlaceholder) PLACEHOLDER_TIMEOUT_MS else DWELL_MS
        root.animate()
            .translationY(restY)
            .setDuration(BOUNCE_IN_MS)
            .setInterpolator(OvershootInterpolator(1.4f))
            .withEndAction { main.postDelayed(dwell, postDwellMs) }
            .start()

        return showing
    }

    private fun animateExit(s: Showing) {
        android.util.Log.i("PhotoPreview", "animateExit: enter active.size=${active.size}")
        pendingPlaceholders.remove(s)
        (s.content as? LoadingPreviewView)?.stop()

        val shrunkHalfW = (s.content.width / 2f) * EXIT_SCALE
        val shrunkHalfH = (s.content.height / 2f) * EXIT_SCALE
        val pad = 6f * dp
        val dx = (screenW / 2f) - shrunkHalfW - pad
        val dy = -((screenH / 2f) - shrunkHalfH - pad)

        s.root.animate()
            .alpha(0f)
            .scaleX(EXIT_SCALE)
            .scaleY(EXIT_SCALE)
            .translationX(dx)
            .translationY(dy)
            .setDuration(EXIT_MS)
            .setInterpolator(AccelerateInterpolator(1.3f))
            .withEndAction {
                // Destroy the preview window COMPLETELY. Belt-and-braces: even if
                // removeViewImmediate has any residual state on the Rokid ROM
                // that lets the window render one more frame, we set alpha=0,
                // scale=0, drop all children, and mark invisible first -- so the
                // "ghost pop-back" at the exit corner has nothing left to show.
                s.root.animate().cancel()
                s.root.visibility = View.INVISIBLE
                s.root.alpha = 0f
                s.root.scaleX = 0f
                s.root.scaleY = 0f
                (s.content as? LoadingPreviewView)?.stop()
                try { (s.root as? android.view.ViewGroup)?.removeAllViews() } catch (_: Exception) {}
                try { wm.removeViewImmediate(s.root) } catch (e: Exception) {
                    android.util.Log.w("PhotoPreview", "removeViewImmediate failed: ${e.message}")
                    try { wm.removeView(s.root) } catch (_: Exception) {}
                }
                active.remove(s)
                pendingPlaceholders.remove(s)
                val bmp = s.bitmap
                if (bmp != null && !bmp.isRecycled) bmp.recycle()
            }
            .start()
    }

    private fun clear(s: Showing, reason: String) {
        main.removeCallbacks(s.dwellRunnable)
        s.root.animate().cancel()
        (s.content as? LoadingPreviewView)?.stop()
        s.root.visibility = View.INVISIBLE
        clearViewOnly(s, reason)
        val bmp = s.bitmap
        if (bmp != null && !bmp.isRecycled) bmp.recycle()
    }

    private fun clearViewOnly(s: Showing, reason: String) {
        try {
            wm.removeView(s.root)
        } catch (e: Exception) {
            remoteLog?.invoke("PhotoPreview: removeView failed ($reason): ${e.message}")
        }
        active.remove(s)
        pendingPlaceholders.remove(s)
    }

    fun destroy() {
        val snapshot = active.toList()
        for (s in snapshot) clear(s, "destroy")
        ioThread.quitSafely()
    }

    /**
     * Loading placeholder: rounded black fill, white border, rotating arc
     * spinner in the center, progress bar across the bottom that animates
     * 0 -> 1 over [estimatedDurationMs]. Self-redraws via
     * postInvalidateOnAnimation while [running] is true.
     */
    private class LoadingPreviewView(
        context: Context,
        private val estimatedDurationMs: Long,
        private val dp: Float,
    ) : View(context) {

        private val borderPx = BORDER_DP * dp
        private val cornerPx = CORNER_RADIUS_DP * dp
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BORDER_COLOR
            style = Paint.Style.STROKE
            strokeWidth = borderPx
        }
        private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = SPINNER_STROKE_DP * dp
            strokeCap = Paint.Cap.ROUND
        }
        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val progressTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PROGRESS_TRACK_COLOR
            style = Paint.Style.FILL
        }

        private val startMs = SystemClock.uptimeMillis()
        @Volatile private var running = true
        // Two-phase progress: while capturing the bar fills toward
        // CAPTURING_CAP (never reaches 1.0). When the photo lands, complete()
        // animates from the current progress to 1.0 over COMPLETION_MS, and
        // only then does the caller swap in the photo. This is what makes
        // "preview appears exactly when the bar finishes" true regardless of
        // whether the JPEG was faster or slower than the estimated duration.
        @Volatile private var capturing = true
        private var completionStartMs: Long = 0L
        private var completionFromProgress: Float = 0f

        fun stop() {
            running = false
        }

        /**
         * Begin the bar's final fill from its current value to 1.0 over
         * COMPLETION_MS. Calls [onDone] on the view's handler when the bar
         * visually reaches 100%, at which point the caller should swap the
         * placeholder for the photo. Idempotent.
         */
        fun complete(onDone: Runnable) {
            if (!capturing) return
            capturing = false
            val now = SystemClock.uptimeMillis()
            val elapsed = (now - startMs).coerceAtLeast(0L)
            completionFromProgress = (elapsed.toFloat() / estimatedDurationMs)
                .coerceIn(0f, CAPTURING_CAP)
            completionStartMs = now
            invalidate()
            postDelayed(onDone, COMPLETION_MS)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            // Black rounded fill.
            val fillRect = RectF(0f, 0f, w, h)
            canvas.drawRoundRect(fillRect, cornerPx, cornerPx, fillPaint)

            // White rounded border. Inset by half stroke so the stroke
            // sits inside the view bounds (not clipped).
            val br = RectF(borderPx / 2f, borderPx / 2f, w - borderPx / 2f, h - borderPx / 2f)
            canvas.drawRoundRect(br, cornerPx, cornerPx, borderPaint)

            val now = SystemClock.uptimeMillis()
            val elapsed = (now - startMs).coerceAtLeast(0L)

            // Spinner: arc that rotates around the centre, 270 deg sweep.
            val spinnerR = minOf(w, h) * SPINNER_RADIUS_FRAC
            val cx = w / 2f
            val cy = h / 2f - SPINNER_OFFSET_DP * dp
            val arcRect = RectF(cx - spinnerR, cy - spinnerR, cx + spinnerR, cy + spinnerR)
            val sweepAngle = 270f
            val rotationAngle = (elapsed * 360f / SPINNER_PERIOD_MS) % 360f
            canvas.drawArc(arcRect, rotationAngle, sweepAngle, false, spinnerPaint)

            // Progress bar at bottom. Two-phase:
            //   - capturing: fills 0 -> CAPTURING_CAP over estimatedDurationMs,
            //     then sticks at CAPTURING_CAP until the JPEG arrives.
            //   - completing (after complete()): linearly interpolates from
            //     completionFromProgress -> 1.0 over COMPLETION_MS.
            val pb = if (capturing) {
                (elapsed.toFloat() / estimatedDurationMs).coerceIn(0f, CAPTURING_CAP)
            } else {
                val t = ((now - completionStartMs).toFloat() / COMPLETION_MS).coerceIn(0f, 1f)
                completionFromProgress + (1f - completionFromProgress) * t
            }
            val barH = PROGRESS_HEIGHT_DP * dp
            val barInsetX = PROGRESS_INSET_X_DP * dp
            val barInsetBottom = PROGRESS_INSET_BOTTOM_DP * dp
            val barLeft = borderPx + barInsetX
            val barRight = w - borderPx - barInsetX
            val barTop = h - borderPx - barInsetBottom - barH
            val barBottom = barTop + barH
            // Track first.
            val trackRect = RectF(barLeft, barTop, barRight, barBottom)
            canvas.drawRoundRect(trackRect, barH / 2f, barH / 2f, progressTrackPaint)
            // Filled portion.
            val fillEnd = barLeft + (barRight - barLeft) * pb
            if (fillEnd > barLeft) {
                val barFill = RectF(barLeft, barTop, fillEnd, barBottom)
                canvas.drawRoundRect(barFill, barH / 2f, barH / 2f, progressPaint)
            }

            if (running) postInvalidateOnAnimation()
        }

        companion object {
            private const val SPINNER_PERIOD_MS = 1200L
            private const val SPINNER_RADIUS_FRAC = 0.18f
            private const val SPINNER_STROKE_DP = 2.5f
            private const val SPINNER_OFFSET_DP = 4f
            private const val PROGRESS_HEIGHT_DP = 4f
            private const val PROGRESS_INSET_X_DP = 10f
            private const val PROGRESS_INSET_BOTTOM_DP = 10f
            // Dim white track so the unfilled portion is still visible on
            // the green waveguide. Reads as a faint outline.
            private val PROGRESS_TRACK_COLOR: Int = 0x55FFFFFF
            // While capturing the bar can never exceed this; the final 5%
            // is reserved for the post-arrival completion sweep so the user
            // sees the bar visibly finish right as the photo appears.
            private const val CAPTURING_CAP = 0.95f
            private const val COMPLETION_MS = 220L
        }
    }

    companion object {
        private const val TARGET_LONG_EDGE_PX = 540
        private const val PREVIEW_WIDTH_DP = 140
        private const val BOUNCE_IN_MS = 320L
        private const val DWELL_MS = 2500L
        private const val EXIT_MS = 520L
        private const val EXIT_SCALE = 0.30f
        private const val MAX_CONCURRENT = 8
        private const val BORDER_DP = 2
        private const val BORDER_COLOR: Int = 0xFFFFFFFF.toInt()
        private const val CORNER_RADIUS_DP = 8f
        private const val DEFAULT_BOTTOM_PAD_DP = 24f
        private const val DEFAULT_ASPECT = 0.75f

        // Long fallback dwell while a placeholder waits for its photo. If
        // capture fails / never lands, the placeholder still cleans itself
        // up after this window.
        // Worst observed PhotoCapturer cold-open + 3-frame burst + merge +
        // encode latency on this HW is ~16-18s. The placeholder must out-live
        // that or the spinner dismisses mid-capture and the real preview
        // attaches to nothing, leaving a frozen frame on screen.
        private const val PLACEHOLDER_TIMEOUT_MS = 25_000L
        // Roughly how long a cold capture takes end-to-end. The progress
        // bar reaches 100% at this elapsed time; if the photo arrives
        // sooner the bar is interrupted (replaced by the photo); if later
        // the bar caps at 100% and waits.
        // RawStillCapturer measured ~3.3-4.1s from shutter to preview JPEG
        // (RAW burst + demosaic + rotate + encode). Match the bar to the
        // upper end so the progress hits ~100% right as the photo swap
        // happens instead of finishing early and looking stuck.
        // Fast-preview path hands off ~700 ms after shutter (AE warmup ~300 ms +
        // first RAW frame + fast demosaic + encode). Progress bar should fill in
        // that window, not 4 s, otherwise the spinner lags the actual capture.
        private const val ESTIMATED_CAPTURE_MS = 900L
    }
}
