package com.repository.glasses.listener.input.remote

/**
 * Where a key event came from.
 *
 * An enum rather than a boolean `remote` flag because the design explicitly anticipates more input
 * devices, and "not touchpad" is not a useful thing to branch on -- the interesting question is
 * always which properties the origin has, not whether it is the one hardcoded alternative.
 *
 * Note this is about the ORIGIN CLASS, not the device. Every remote device is [REMOTE]; they are
 * not distinguished here, because the moment the UI branches per device the compatibility layer has
 * failed. Per-device behaviour belongs in that device's [InputSource].
 */
enum class InputOrigin {
    /** The physical capacitive touchpad, via the daemon's synthetic keycodes. */
    TOUCHPAD,

    /** Any registered [InputSource] -- the watch today, another device tomorrow. */
    REMOTE,
}
