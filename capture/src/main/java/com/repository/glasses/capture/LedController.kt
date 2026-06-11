package com.repository.glasses.capture

import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Thin reflection-based wrapper around Rokid's `lights_ctrl` AIDL binder to drive the white
 * (privacy/camera) LED on the glasses. Used by CaptureService to signal photo capture + active
 * video recording. No manifest permission required -- the service does no permission check.
 *
 * Contract:
 *   - photo taken     -> [pulseWhite] (short flash)
 *   - video started   -> [turnOnWhite]
 *   - video paused    -> [flashWhite] (slow blink to indicate paused-but-armed)
 *   - video resumed   -> [turnOnWhite]
 *   - video stopped   -> [turnOffWhite]
 *
 * Uses transaction codes 1/3/4/6 directly -- bypasses sendEvent (code 8) which plays predefined
 * animations owned by EventFactoryV4 and could be overridden elsewhere.
 */
object LedController {

    private const val TAG = "LedCtrl"
    private const val DESCRIPTOR = "com.rokid.light.ILightsCtrl"
    private const val LIGHT_WHITE = 8

    private const val TXN_SET_BRIGHTNESS = 1
    private const val TXN_SET_FLASHING = 2
    private const val TXN_PULSE = 3
    private const val TXN_TURN_ON = 4
    private const val TXN_TURN_OFF = 6
    private const val TXN_SEND_EVENT = 8
    private const val TXN_CANCEL_EVENT = 9

    // Rokid's camera HAL auto-fires this high-priority event (priority 8000) whenever the
    // camera is opened, lighting the white LED regardless of our turnOff calls. It must be
    // explicitly cancelled for the LED to actually go dark while paused.
    private const val EVENT_TYPE_WARN = 2
    private const val EVENT_WARN_ID_CAMERA_OPEN = 2014

    private fun getBinder(): IBinder? = try {
        val sm = Class.forName("android.os.ServiceManager")
        sm.getMethod("getService", String::class.java).invoke(null, "lights_ctrl") as? IBinder
    } catch (e: Exception) {
        Log.w(TAG, "getService(lights_ctrl) failed: ${e.message}")
        null
    }

    fun turnOnWhite() { simpleCall(TXN_TURN_ON, LIGHT_WHITE) }

    /** Re-assert the Rokid CAMERA_OPEN event so the LED lights at its natural priority. */
    fun assertCameraOpenEvent() { eventCall(TXN_SEND_EVENT, EVENT_TYPE_WARN, EVENT_WARN_ID_CAMERA_OPEN) }

    /** Cancel the Rokid CAMERA_OPEN event (priority 8000) that keeps the LED lit. */
    fun cancelCameraOpenEvent() { eventCall(TXN_CANCEL_EVENT, EVENT_TYPE_WARN, EVENT_WARN_ID_CAMERA_OPEN) }

    /**
     * Gate the firmware camera privacy LED at its SOURCE. Rokid's cameraserver
     * reads the system property `vendor.rkd.camera.led.enable` and, when it is
     * "0", does NOT fire the CAMERA_OPEN(2014) event at all on camera open -- so
     * the white LED never lights (verified on-device: with the prop 0, no
     * `send event 2,2014` is emitted and `/sys/class/leds/white/brightness`
     * stays 0 through a full capture). Set false around a silent capture (ReID),
     * restore true afterwards. This is the clean gate; it avoids racing the
     * cameraserver-fired event with cancels.
     */
    fun setCameraLedEnabled(enabled: Boolean) {
        setProp(CAMERA_LED_ENABLE_PROP, if (enabled) "1" else "0")
    }

    private const val CAMERA_LED_ENABLE_PROP = "vendor.rkd.camera.led.enable"

    private fun setProp(key: String, value: String) {
        try {
            val sp = Class.forName("android.os.SystemProperties")
            sp.getMethod("set", String::class.java, String::class.java).invoke(null, key, value)
        } catch (e: Exception) {
            Log.w(TAG, "setProp $key=$value failed: ${e.message}")
        }
    }

    private fun eventCall(txn: Int, eventType: Int, eventId: Int) {
        val b = getBinder() ?: return
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(eventType)
            data.writeInt(eventId)
            b.transact(txn, data, reply, 0)
            reply.readException()
        } catch (e: Exception) {
            Log.w(TAG, "eventCall txn=$txn type=$eventType id=$eventId failed: ${e.message}")
        } finally { data.recycle(); reply.recycle() }
    }

    /**
     * Force the white LED off. Rokid's lights_ctrl can leave the LED in a residual lit state
     * after turnOn if a prior setFlashing/pulse/event is still active (turnOff alone just
     * clears the "on" flag, not the underlying brightness or animation). Belt-and-braces:
     * cancel flashing via setFlashing(0,0), then setBrightness(0), then turnOff.
     */
    fun turnOffWhite() {
        try {
            val b = getBinder() ?: return
            // Cancel any active blink by configuring 0/0.
            run {
                val data = Parcel.obtain(); val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeInt(LIGHT_WHITE)
                    data.writeInt(0)
                    data.writeInt(0)
                    b.transact(TXN_SET_FLASHING, data, reply, 0)
                    reply.readException()
                } catch (_: Exception) {
                } finally { data.recycle(); reply.recycle() }
            }
            // Drop brightness to 0. Some firmware ignores turnOff while brightness > 0.
            run {
                val data = Parcel.obtain(); val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeInt(LIGHT_WHITE)
                    data.writeFloat(0f)
                    b.transact(TXN_SET_BRIGHTNESS, data, reply, 0)
                    reply.readException()
                } catch (_: Exception) {
                } finally { data.recycle(); reply.recycle() }
            }
            simpleCall(TXN_TURN_OFF, LIGHT_WHITE)
        } catch (e: Exception) {
            Log.w(TAG, "turnOffWhite failed: ${e.message}")
        }
    }

    /** Short pulse. durationMs applies to a single fade in+out cycle. */
    fun pulseWhite(durationMs: Int = 300) {
        val b = getBinder() ?: return
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(LIGHT_WHITE)
            data.writeInt(durationMs)
            b.transact(TXN_PULSE, data, reply, 0)
            reply.readException()
        } catch (e: Exception) {
            Log.w(TAG, "pulseWhite failed: ${e.message}")
        } finally { data.recycle(); reply.recycle() }
    }

    /** Slow blink to indicate paused-recording state. */
    fun flashWhite(onMs: Int = 400, offMs: Int = 400) {
        val b = getBinder() ?: return
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(LIGHT_WHITE)
            data.writeInt(onMs)
            data.writeInt(offMs)
            b.transact(TXN_SET_FLASHING, data, reply, 0)
            reply.readException()
        } catch (e: Exception) {
            Log.w(TAG, "flashWhite failed: ${e.message}")
        } finally { data.recycle(); reply.recycle() }
    }

    private fun simpleCall(txn: Int, lightId: Int) {
        val b = getBinder() ?: return
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(lightId)
            b.transact(txn, data, reply, 0)
            reply.readException()
        } catch (e: Exception) {
            Log.w(TAG, "txn=$txn lightId=$lightId failed: ${e.message}")
        } finally { data.recycle(); reply.recycle() }
    }
}
