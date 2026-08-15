package com.repository.glasses.headlockoverlay.orientation

import com.repository.glasses.headlockoverlay.math.wrap180
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

/**
 * A "lazy-follow" orientation reference.
 *
 * Within [deadzoneDeg] of the reference the viewport stays locked in front of the user
 * (offset = 0). Beyond it, panels are world-fixed and slide into view. The deadzone is SOFT:
 * the offset grows continuously from zero at the deadzone edge (offset = -sign*(|err|-D)),
 * so content never jumps -- when the reference re-centers, the offset glides smoothly to 0.
 *
 * The reference only starts chasing the head after the head has been held roughly still
 * (angular speed below [idleSpeedDegPerSec]) for [dwellSeconds]; then it eases in gradually
 * over [rampSeconds]. Any faster head movement resets both the dwell timer and the ease ramp,
 * so glancing around keeps panels world-fixed and only a settled heading re-centers content.
 */
class LazyFollowReference(
    var followRate: Float = 0.06f,  // per-second; peak chase rate once fully eased in
    var deadzoneDeg: Float = 6f,
    var dwellSeconds: Float = 2.0f, // head must be still this long before follow begins
    var rampSeconds: Float = 1.5f,  // follow eases 0 -> full over this long
    var idleSpeedDegPerSec: Float = 12f, // head speed below this counts as "still"
) {
    var refYaw: Float = 0f
        private set
    var refPitch: Float = 0f
        private set

    // Dwell / ease state.
    private var stillSeconds = 0f
    private var followSeconds = 0f

    data class Offset(val yawDeg: Float, val pitchDeg: Float)

    /**
     * Advance by [dt] seconds given current head angles (deg) and the current head angular
     * [speedDegPerSec] (measured upstream from the gyro rate -- NOT differentiated from angle,
     * so it is immune to sensor-event batching artifacts). Returns the viewport offset (deg).
     */
    fun update(headYaw: Float, headPitch: Float, speedDegPerSec: Float, dt: Float): Offset {
        val step = if (dt > 0f) dt else 0f

        // Offsets (what the viewport shows) are computed from the CURRENT ref, before it moves.
        val offsetYaw = axisOffset(headYaw, refYaw)
        val offsetPitch = axisOffset(headPitch, refPitch)

        // Decide moving vs still purely from the supplied gyro-derived speed.
        if (step > 0f) {
            if (speedDegPerSec > idleSpeedDegPerSec) {
                // Moving: cancel any pending / in-progress follow.
                stillSeconds = 0f
                followSeconds = 0f
            } else {
                stillSeconds += step
            }
        }

        // Follow only after the dwell, and ease the rate in over rampSeconds.
        if (stillSeconds >= dwellSeconds) {
            followSeconds += step
            val ease = smoothstep((followSeconds / rampSeconds).coerceIn(0f, 1f))
            val effRate = followRate * ease
            refYaw = axisFollow(headYaw, refYaw, effRate, step)
            refPitch = axisFollow(headPitch, refPitch, effRate, step)
        }

        return Offset(offsetYaw, offsetPitch)
    }

    /** Snap reference to current head (immediate recenter) and reset follow state. */
    fun recenter(headYaw: Float, headPitch: Float) {
        refYaw = wrap180(headYaw)
        refPitch = wrap180(headPitch)
        stillSeconds = 0f
        followSeconds = 0f
    }

    private fun axisOffset(head: Float, ref: Float): Float {
        val err = wrap180(head - ref)
        // Soft deadzone: zero at the edge, then grows continuously. No jump when re-entering.
        val soft = max(0f, abs(err) - deadzoneDeg) * sign(err)
        return -soft
    }

    private fun axisFollow(head: Float, ref: Float, rate: Float, dt: Float): Float {
        val err = wrap180(head - ref)
        return if (abs(err) >= deadzoneDeg) {
            wrap180(ref + rate * err * dt)
        } else {
            ref
        }
    }

    /** Smooth 0->1 ease (3t^2 - 2t^3) so follow starts and ends with zero jerk. */
    private fun smoothstep(t: Float): Float = t * t * (3f - 2f * t)
}
