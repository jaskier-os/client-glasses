package com.repository.glasses.listener.arstream

import android.graphics.Canvas
import android.graphics.Color
import android.view.Choreographer
import android.view.Surface
import android.view.View

/**
 * Draws the Activity's view hierarchy into a Surface owned by the :backend compositor.
 *
 * Same technique as ViewRecorder, except the target Surface belongs to another process (it
 * arrives over binder from LiveArCompositor). The HUD is the overlay layer; the compositor's
 * luma-as-alpha shader turns black pixels transparent, which matches the waveguide convention
 * where black means "pixel off".
 */
class HudSurfaceDrawer(private val log: ((String) -> Unit)? = null) {

    private var surface: Surface? = null
    private var rootView: View? = null
    private var targetWidth = 0
    private var targetHeight = 0

    @Volatile
    private var running = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            drawOnce()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Must be called on the UI thread (Choreographer is per-thread). */
    fun start(view: View, s: Surface, width: Int, height: Int) {
        if (running) stop()
        rootView = view
        surface = s
        targetWidth = width
        targetHeight = height
        running = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
        log?.invoke("HudSurfaceDrawer: started")
    }

    /** Must be called on the UI thread. */
    fun stop() {
        if (!running && surface == null) return
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        surface = null
        rootView = null
        log?.invoke("HudSurfaceDrawer: stopped")
    }

    private fun drawOnce() {
        val s = surface ?: return
        val v = rootView ?: return
        if (!s.isValid || v.width == 0 || v.height == 0) return

        var canvas: Canvas? = null
        try {
            canvas = s.lockCanvas(null)
            // Clear to black so untouched HUD area composites as fully transparent.
            canvas.drawColor(Color.BLACK)
            // Scale when the view and the buffer disagree, so the HUD fills the layer instead of
            // landing in a corner of it.
            if (targetWidth > 0 && targetHeight > 0 &&
                (v.width != targetWidth || v.height != targetHeight)
            ) {
                canvas.scale(
                    targetWidth.toFloat() / v.width,
                    targetHeight.toFloat() / v.height
                )
            }
            v.draw(canvas)
        } catch (e: Exception) {
            log?.invoke("HudSurfaceDrawer: draw failed: ${e.message}")
        } finally {
            if (canvas != null) {
                try { s.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
            }
        }
    }
}
