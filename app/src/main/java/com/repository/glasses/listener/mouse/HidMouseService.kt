package com.repository.glasses.listener.mouse

import android.os.SystemClock
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.repository.glasses.listener.bt.BtManagerBridge

class HidMouseService : Service(), BluetoothHidMouse.Listener, HeadTracker.Listener {

    companion object {
        private const val TAG = "GlassesMouse"
        private const val CHANNEL_ID = "glasses_mouse"
        private const val NOTIFICATION_ID = 42
        private const val BATCH_INTERVAL_MS = 32L // ~30fps

        const val ACTION_STATUS = "com.repository.glasses.listener.mouse.STATUS"
        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_REGISTERED = "registered"
        const val EXTRA_TRACKING = "tracking"
    }

    inner class LocalBinder : Binder() {
        val service: HidMouseService get() = this@HidMouseService
    }

    private val binder = LocalBinder()
    lateinit var btMouse: BluetoothHidMouse
        private set
    lateinit var headTracker: HeadTracker
        private set
    private lateinit var btManagerBridge: BtManagerBridge
    private val mainHandler = Handler(Looper.getMainLooper())

    // Batched mouse accumulation
    private var pendingDx = 0f
    private var pendingDy = 0f
    private var pendingScroll = 0
    private var pendingButtons = 0
    private var mouseDirty = false
    var isTracking = false
        private set
    private var gattReady = false
    private var advertisingRequested = false

    // Stats
    private var rawEventCount = 0
    private var flushCount = 0
    private var lastStatsTime = SystemClock.elapsedRealtime()

    private val flushRunnable = Runnable { flushMouseBatch() }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID, "Glasses Mouse", NotificationManager.IMPORTANCE_MIN
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Glasses Mouse")
            .setContentText("HID mouse active")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        btManagerBridge = BtManagerBridge(this)
        btManagerBridge.addBondListener(object : BtManagerBridge.BondListener {
            override fun onBondStateChanged(address: String, name: String, prevState: Int, newState: Int) {
                if (newState == BluetoothDevice.BOND_BONDED) {
                    btManagerBridge.stopAdvertising("hid_mouse")
                } else if (newState == BluetoothDevice.BOND_NONE) {
                    if (advertisingRequested && gattReady) {
                        btManagerBridge.startAdvertising(
                            "hid_mouse",
                            BluetoothHidMouse.HID_SERVICE_UUID.toString(),
                            true
                        )
                    }
                }
            }
        })
        btManagerBridge.bind()

        btMouse = BluetoothHidMouse(this)
        btMouse.listener = this
        btMouse.init()

        headTracker = HeadTracker(this)
        headTracker.listener = this

        Log.i(TAG, "HidMouseService started")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        headTracker.stop()
        mainHandler.removeCallbacks(flushRunnable)
        btMouse.destroy()
        btManagerBridge.unbind()
        Log.i(TAG, "HidMouseService stopped")
        super.onDestroy()
    }

    // -- BluetoothHidMouse.Listener --

    override fun onRegistered(success: Boolean) {
        Log.i(TAG, "HID registered=$success")
        gattReady = success
        if (success && advertisingRequested) {
            btManagerBridge.startAdvertising(
                "hid_mouse",
                BluetoothHidMouse.HID_SERVICE_UUID.toString(),
                true
            )
        }
        broadcastStatus()
    }

    override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
        if (btMouse.isConnected) {
            btManagerBridge.stopAdvertising("hid_mouse")
        } else if (advertisingRequested && gattReady) {
            btManagerBridge.startAdvertising(
                "hid_mouse",
                BluetoothHidMouse.HID_SERVICE_UUID.toString(),
                true
            )
        }
        broadcastStatus()
    }

    // -- HeadTracker.Listener --

    override fun onHeadMove(dx: Float, dy: Float) {
        if (!isTracking) return
        accumulateMove(dx, dy)
    }

    // -- Mouse movement accumulation --

    fun accumulateMove(dx: Float, dy: Float) {
        pendingDx += dx
        pendingDy += dy
        rawEventCount++
        scheduleFlush()
    }

    fun accumulateScroll(delta: Int) {
        pendingScroll += delta
        rawEventCount++
        scheduleFlush()
    }

    fun sendClick(button: Int) {
        if (mouseDirty) {
            mainHandler.removeCallbacks(flushRunnable)
            flushMouseBatch()
        }
        btMouse.sendMouseReport(button, 0, 0, 0)
        mainHandler.postDelayed({
            btMouse.sendMouseReport(0, 0, 0, 0)
        }, 50)
        Log.d(TAG, "Click button=$button")
    }

    private fun scheduleFlush() {
        if (!mouseDirty) {
            mouseDirty = true
            mainHandler.postDelayed(flushRunnable, BATCH_INTERVAL_MS)
        }
    }

    private fun flushMouseBatch() {
        if (!mouseDirty) return
        val dx = pendingDx.toInt()
        val dy = pendingDy.toInt()
        val scroll = pendingScroll
        pendingDx -= dx.toFloat()
        pendingDy -= dy.toFloat()
        pendingScroll = 0
        mouseDirty = false

        if (dx == 0 && dy == 0 && scroll == 0) return

        btMouse.sendMouseReport(pendingButtons, dx, dy, scroll)
        flushCount++

        val now = SystemClock.elapsedRealtime()
        if (now - lastStatsTime >= 2000) {
            val elapsed = (now - lastStatsTime) / 1000.0
            Log.i(TAG, "[mouse] $rawEventCount raw -> $flushCount flush in ${String.format("%.1f", elapsed)}s (${String.format("%.1f", flushCount / elapsed)} flush/s)")
            rawEventCount = 0
            flushCount = 0
            lastStatsTime = now
        }
    }

    fun startBleAdvertising() {
        advertisingRequested = true
        if (gattReady) {
            btManagerBridge.startAdvertising(
                "hid_mouse",
                BluetoothHidMouse.HID_SERVICE_UUID.toString(),
                true
            )
        }
    }

    fun setSensitivity(x: Float, y: Float) {
        headTracker.sensitivityX = x
        headTracker.sensitivityY = y
    }

    fun toggleTracking() {
        isTracking = !isTracking
        if (isTracking) {
            headTracker.start()
        } else {
            headTracker.stop()
            mainHandler.removeCallbacks(flushRunnable)
            pendingDx = 0f; pendingDy = 0f; pendingScroll = 0; mouseDirty = false
        }
        Log.i(TAG, "Tracking ${if (isTracking) "ON" else "OFF"}")
        broadcastStatus()
    }

    private fun broadcastStatus() {
        sendBroadcast(Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_CONNECTED, btMouse.isConnected)
            putExtra(EXTRA_DEVICE_NAME, btMouse.deviceName ?: "")
            putExtra(EXTRA_REGISTERED, true)
            putExtra(EXTRA_TRACKING, isTracking)
            setPackage(packageName)
        })
    }
}
