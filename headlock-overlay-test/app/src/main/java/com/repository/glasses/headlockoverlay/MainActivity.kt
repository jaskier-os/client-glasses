package com.repository.glasses.headlockoverlay

import android.hardware.SensorManager
import android.os.Bundle
import android.view.KeyEvent
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
        overlay.selectedTunable = currentTunable()
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

    // --- Live touchpad tuning ------------------------------------------------

    /** Ordered tunables the touchpad cursor cycles through. */
    private val tunables = listOf("k", "D", "fov", "recenter")
    private var selectedIndex = 0

    private fun currentTunable() = tunables[selectedIndex]

    /**
     * On-device touchpad tuning. The Rokid glasses touchpad reports the keycodes below
     * (tap / hold / scroll). These are the observed mappings from prior work -- they may
     * need on-device confirmation and are easy to remap via the companion constants.
     *
     * - tap    : cycle the selection cursor over [tunables].
     * - scroll : adjust the selected tunable (forward = +, backward = -).
     * - hold   : context action -- recenter (when "recenter" is selected) else toggle debug.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KEY_TAP -> {
                selectedIndex = (selectedIndex + 1) % tunables.size
                overlay.selectedTunable = currentTunable()
                overlay.invalidate()
                return true
            }

            KEY_SCROLL_FWD -> {
                adjustSelected(+1)
                return true
            }

            KEY_SCROLL_BACK -> {
                adjustSelected(-1)
                return true
            }

            KEY_HOLD -> {
                if (currentTunable() == "recenter") {
                    lazyFollow.recenter(tracker.yawDeg, tracker.pitchDeg)
                } else {
                    overlay.debugEnabled = !overlay.debugEnabled
                }
                overlay.invalidate()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Nudge the currently selected tunable by [dir] (+1 forward / -1 backward). */
    private fun adjustSelected(dir: Int) {
        when (currentTunable()) {
            "k" -> {
                val v = (lazyFollow.followRate + dir * 0.05f).coerceIn(0.05f, 3.0f)
                lazyFollow.followRate = v
                overlay.followRate = v
            }
            "D" -> {
                val v = (lazyFollow.deadzoneDeg + dir * 1.0f).coerceIn(0.0f, 20.0f)
                lazyFollow.deadzoneDeg = v
                overlay.deadzoneDeg = v
            }
            "fov" -> {
                val v = (overlay.projection.horizontalFovDeg + dir * 1.0f).coerceIn(12.0f, 60.0f)
                overlay.projection.horizontalFovDeg = v
            }
            // "recenter": scrolling is a no-op.
        }
        overlay.invalidate()
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

    companion object {
        // Rokid glasses touchpad -> Android keycodes (observed; confirm on-device, easy to remap).
        private const val KEY_TAP = KeyEvent.KEYCODE_NUMPAD_2         // tap
        private const val KEY_HOLD = KeyEvent.KEYCODE_NUMPAD_3        // long hold (~500ms)
        private const val KEY_SCROLL_FWD = KeyEvent.KEYCODE_NUMPAD_0  // scroll forward
        private const val KEY_SCROLL_BACK = KeyEvent.KEYCODE_NUMPAD_1 // scroll backward
    }
}
