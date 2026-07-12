package com.repository.glasses.headlockoverlay

import android.hardware.SensorManager
import android.os.Bundle
import android.view.View
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.repository.glasses.headlockoverlay.orientation.LazyFollowReference
import com.repository.glasses.headlockoverlay.sensor.HeadOrientationTracker
import com.repository.glasses.headlockoverlay.ui.OverlayView

class MainActivity : AppCompatActivity() {

    private lateinit var overlay: OverlayView
    private lateinit var tracker: HeadOrientationTracker
    private val lazyFollow = LazyFollowReference()

    // Last pose timestamp (ns) for computing dt between sensor callbacks.
    private var lastPoseNanos = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        overlay = OverlayView(this)
        setContentView(overlay)

        val sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        tracker = HeadOrientationTracker(sensorManager)
        tracker.onPose = { yaw, pitch, _ ->
            val now = System.nanoTime()
            val dt = if (lastPoseNanos == 0L) 0f
                else ((now - lastPoseNanos) / 1e9f).coerceIn(0f, 0.1f)
            lastPoseNanos = now
            val off = lazyFollow.update(yaw, pitch, dt)
            pushFrame(off.yawDeg, off.pitchDeg, yaw, pitch)
        }

        enableImmersiveFullscreen(overlay)
    }

    /** Push offset + head + ref into the view on the UI thread, then repaint. */
    private fun pushFrame(offsetYaw: Float, offsetPitch: Float, headYaw: Float, headPitch: Float) {
        overlay.post {
            overlay.updateFrame(offsetYaw, offsetPitch, headYaw, headPitch, lazyFollow.refYaw)
            overlay.refYawDeg = lazyFollow.refYaw
            overlay.followRate = lazyFollow.followRate
            overlay.deadzoneDeg = lazyFollow.deadzoneDeg
            overlay.invalidate()
        }
    }

    override fun onResume() {
        super.onResume()
        lastPoseNanos = 0L
        tracker.start()
    }

    override fun onPause() {
        super.onPause()
        tracker.stop()
    }

    /**
     * Test hook: drive the same pose pipeline as [HeadOrientationTracker.onPose] with a fixed dt,
     * letting an instrumented test render without hardware sensors.
     */
    @VisibleForTesting
    fun testInjectPose(yawDeg: Float, pitchDeg: Float) {
        val off = lazyFollow.update(yawDeg, pitchDeg, 0.016f)
        pushFrame(off.yawDeg, off.pitchDeg, yawDeg, pitchDeg)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableImmersiveFullscreen(window.decorView)
        }
    }

    private fun enableImmersiveFullscreen(anchor: View) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, anchor)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
