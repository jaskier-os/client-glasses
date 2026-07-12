package com.repository.glasses.headlockoverlay.orientation

import com.repository.glasses.headlockoverlay.math.wrap180
import kotlin.math.abs

/**
 * A "lazy-follow" orientation reference.
 *
 * Near the current reference direction (within [deadzoneDeg]) the viewport stays
 * locked in front of the user (offset = 0), so content feels head-locked and stable.
 * Beyond the deadzone panels are world-fixed and slide into view (offset = -(head - ref)).
 * When a new heading is held, the reference slowly chases the head at [followRate]
 * per second, so content eventually re-centers.
 */
class LazyFollowReference(
    var followRate: Float = 0.6f,   // per-second; how fast ref chases head when outside deadzone
    var deadzoneDeg: Float = 6f,
) {
    var refYaw: Float = 0f
        private set
    var refPitch: Float = 0f
        private set

    data class Offset(val yawDeg: Float, val pitchDeg: Float)

    /** Advance by dt seconds given current head angles (deg). Returns viewport offset (deg). */
    fun update(headYaw: Float, headPitch: Float, dt: Float): Offset {
        val step = if (dt > 0f) dt else 0f
        val offsetYaw = axisOffset(headYaw, refYaw)
        val offsetPitch = axisOffset(headPitch, refPitch)
        refYaw = axisFollow(headYaw, refYaw, step)
        refPitch = axisFollow(headPitch, refPitch, step)
        return Offset(offsetYaw, offsetPitch)
    }

    /** Snap reference to current head (immediate recenter). */
    fun recenter(headYaw: Float, headPitch: Float) {
        refYaw = wrap180(headYaw)
        refPitch = wrap180(headPitch)
    }

    private fun axisOffset(head: Float, ref: Float): Float {
        val err = wrap180(head - ref)
        val drawErr = if (abs(err) < deadzoneDeg) 0f else err
        return -drawErr
    }

    private fun axisFollow(head: Float, ref: Float, dt: Float): Float {
        val err = wrap180(head - ref)
        return if (abs(err) >= deadzoneDeg) {
            wrap180(ref + followRate * err * dt)
        } else {
            ref
        }
    }
}
