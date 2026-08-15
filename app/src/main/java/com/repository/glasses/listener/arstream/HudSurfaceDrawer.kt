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
        var drewOk = false
        try {
            // lockCanvas, NOT lockHardwareCanvas.
            //
            // The hardware path handed back a buffer the external sampler then ran a BT.709
            // limited-range YUV->RGB matrix over, even though the Canvas had written RGB. Measured
            // on streamed frames: written black came back as (0,75,0) and written green as
            // (0,19,254) -- both match that matrix to within 2/255, and no channel permutation can
            // turn black into a non-zero value. The CPU lock forces a linear RGBA_8888 buffer, so
            // the sampler reads what was actually written, and it also avoids the recycled-slot
            // staleness that made the corruption flicker frame to frame.
            canvas = s.lockCanvas(null)

            // Clear to OPAQUE BLACK, exactly as ViewRecorder.drawFrame() does for the recorded
            // AR path. Black is the correct background here: the compositor's shader derives
            // alpha from luma, so black composites as a no-op and the camera shows through.
            //
            // Do NOT use PorterDuff.CLEAR. lockHardwareCanvas hands back a RECYCLED buffer from
            // the queue and only the damaged region is redrawn; CLEAR is not a reliable full-
            // buffer wipe on a hardware canvas, so a previous fully-lit frame survived and the
            // sampler read bright pixels across the whole quad -- the green wash. An ordinary
            // opaque fill overwrites every pixel unconditionally.
            canvas.drawColor(Color.BLACK)

            if (targetWidth > 0 && targetHeight > 0 &&
                (v.width != targetWidth || v.height != targetHeight)
            ) {
                canvas.scale(
                    targetWidth.toFloat() / v.width,
                    targetHeight.toFloat() / v.height
                )
            }

            // Draw the hierarchy as the wearer sees it, backgrounds and all.
            //
            // Measured from a device screencap: 98.6% of the panel is PURE black (max channel 0)
            // and only ~1.4% carries HUD content. So the buffer keys cleanly on brightness, and
            // the compositor's luma threshold does the transparency. Do NOT try to strip view
            // backgrounds here -- an earlier attempt did that and still produced a solid green
            // block, because opaque children (the chat RecyclerView) repaint regardless, and it
            // risks mutating live UI state on the way past.
            v.draw(canvas)
            drewOk = true
        } catch (e: Exception) {
            log?.invoke("HudSurfaceDrawer: draw failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            if (canvas != null) {
                // Post ONLY a buffer that actually got the hierarchy drawn into it.
                //
                // Posting unconditionally from the finally block meant a throwing v.draw() shipped
                // a buffer containing nothing but the black fill. The compositor's luma-as-alpha
                // shader turns that into alpha 0 everywhere, so the overlay VANISHES for that frame
                // -- one bad draw is one guaranteed HUD-missing frame in the stream. Abandoning the
                // buffer instead leaves the previous good one as the latest posted buffer, which
                // the compositor keeps sampling.
                if (drewOk) {
                    try {
                        s.unlockCanvasAndPost(canvas)
                    } catch (e: Exception) {
                        log?.invoke("HudSurfaceDrawer: post failed: ${e.message}")
                    }
                } else {
                    // No public "cancel" for a software-locked Canvas, so the lock is released by
                    // posting; the loud log makes the resulting black frame attributable instead of
                    // looking like compositor flicker.
                    log?.invoke("HudSurfaceDrawer: ABANDONING frame, draw threw -- posting black")
                    try { s.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
                }
            }
        }
    }
}
