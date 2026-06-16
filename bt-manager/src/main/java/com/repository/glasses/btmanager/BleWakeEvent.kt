package com.repository.glasses.btmanager

/**
 * Wake-event byte codes shared with the phone (mirrored in
 * clients/glasses/app/.../bt/BleWakeEvent.kt and clients/phone/.../bt/BleWakeEvent.kt).
 * Keep in sync.
 */
object BleWakeEvent {
    // glasses -> phone
    const val WAKE_WORD: Byte            = 0x01
    const val BUTTON_PRESS: Byte         = 0x02
    const val WEAR_CHANGED: Byte         = 0x03

    // either direction (generic)
    const val RFCOMM_REQUEST: Byte       = 0x10

    // phone -> glasses: cold-start relaunch of the listener foreground service
    // when it has been force-stopped/killed. bt-manager (priv-app) starts the
    // listener cross-package on receipt. 0x07 is the next free phone->glasses code.
    const val LAUNCH_LISTENER: Byte      = 0x07

    // phone -> glasses
    const val TTS_PENDING: Byte          = 0x11
    const val NOTIFICATION_PENDING: Byte = 0x12

    // Liveness / handshake
    const val BLE_PING: Byte             = 0x20  // phone -> glasses, written on CHAR_RX
    const val BLE_PONG: Byte             = 0x21  // glasses -> phone, NOTIFY on CHAR_TX
    const val BLE_HELLO: Byte            = 0x22  // glasses -> phone, NOTIFY on connect/boot

    // glasses -> phone, telemetry. byte[1] of payload carries SoC% (0..100).
    const val BATTERY_LEVEL: Byte        = 0x30
}
