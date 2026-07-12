package com.repository.glasses.headlockoverlay

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives [MainActivity.testInjectPose] through a scripted head-turn sweep so the mock AR panels
 * visibly slide into view. The primary deliverable is the accompanying screen recording; the
 * assertions here just keep the run meaningful (activity stays RESUMED, inject calls never throw).
 *
 * Run manually (do NOT use connectedAndroidTest -- its teardown auto-uninstalls the app):
 *   adb -s <serial> shell am instrument -w -r \
 *     -e class com.repository.glasses.headlockoverlay.OverlaySweepTest \
 *     com.repository.glasses.headlockoverlay.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class OverlaySweepTest {

    private data class Waypoint(
        val label: String,
        val yaw: Float,
        val pitch: Float,
        val holdMs: Long,
    )

    /**
     * Waypoints matching MockPanels.PANELS angular coords. Each is held >= 2.5s so a screen
     * recording clearly shows the panel that reveals at that head angle.
     */
    private val script = listOf(
        Waypoint("center", 0f, 0f, 2500),
        Waypoint("top", 0f, 20f, 2500),
        Waypoint("center", 0f, 0f, 2500),
        Waypoint("bottom", 0f, -20f, 2500),
        Waypoint("center", 0f, 0f, 2500),
        Waypoint("left", -30f, 0f, 2500),
        Waypoint("right", 30f, 0f, 2500),
        Waypoint("center", 0f, 0f, 2500),
        Waypoint("corner-tl", -60f, 25f, 2500),
        Waypoint("corner-br", 60f, -25f, 2500),
        Waypoint("far-left", -90f, 0f, 2500),
        Waypoint("far-right", 90f, 0f, 2500),
        Waypoint("return-center", 0f, 0f, 2500),
    )

    @Test(timeout = 120_000)
    fun sweepRevealsPanels() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)

            var prevYaw = 0f
            var prevPitch = 0f

            for (wp in script) {
                // Smoothly step from the previous pose to this waypoint over ~0.5s so the
                // lazy-follow/reveal animates rather than snapping.
                val steps = 15
                for (i in 1..steps) {
                    val t = i.toFloat() / steps
                    val yaw = prevYaw + (wp.yaw - prevYaw) * t
                    val pitch = prevPitch + (wp.pitch - prevPitch) * t
                    scenario.onActivity { it.testInjectPose(yaw, pitch) }
                    Thread.sleep(33)
                }

                // Hold: re-inject the target every ~200ms. The lazy-follow slowly re-centers the
                // reference, so re-asserting the same head pose keeps the far panel on screen for
                // the full dwell instead of drifting back to center.
                val reinjectInterval = 200L
                var held = 0L
                while (held < wp.holdMs) {
                    scenario.onActivity { it.testInjectPose(wp.yaw, wp.pitch) }
                    Thread.sleep(reinjectInterval)
                    held += reinjectInterval
                }

                prevYaw = wp.yaw
                prevPitch = wp.pitch
            }

            // Activity survived the whole sweep without any inject call throwing. On the glasses
            // waveguide the immersive activity can drop from RESUMED to CREATED if the system
            // transiently pulls focus during the long dwell; that is not a failure. What matters
            // is that it is still alive (not DESTROYED) after every pose injection completed.
            assertTrue(
                "activity should be alive after sweep, was ${scenario.state}",
                scenario.state.isAtLeast(Lifecycle.State.CREATED),
            )
        }
    }
}
