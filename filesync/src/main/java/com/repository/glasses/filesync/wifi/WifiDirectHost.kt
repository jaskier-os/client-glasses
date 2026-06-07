package com.repository.glasses.filesync.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.repository.glasses.tracing.GT
import org.json.JSONObject

/**
 * Glasses-side WiFi Direct group owner. One group at a time.
 *
 * Observed Rokid AR Lite quirks driving the choreography below:
 * - `p2p_no_group_iface=1` in `/data/vendor/wifi/wpa/p2p_supplicant.conf` -> the GO reuses wlan0;
 *   there is no separate p2p0 netns and wpa_cli can't drive it.
 * - CN regulatory domain disables most 5 GHz channels -> force 2.4 GHz.
 * - `createGroup` returns generic ERROR (0) unless the live p2p-service device name is set;
 *   writing only to Settings.Global is not enough, we must call @hide [setDeviceName] via
 *   reflection on the live channel.
 * - P2P state machine sleeps into P2pDisabledState when nobody has [initialize] active,
 *   so we [requestP2pState] first and wait for WIFI_P2P_STATE_CHANGED_ACTION=ENABLED before
 *   any other P2P call.
 */
@SuppressLint("MissingPermission")
class WifiDirectHost(
    private val context: Context,
    private val onReady: (JSONObject) -> Unit,
    private val onClosed: () -> Unit,
    private val onError: (String) -> Unit = {},
) {

    companion object {
        private const val TAG = "FSync:Wifi"
        const val HTTP_PORT = 8849
        private const val P2P_ENABLE_TIMEOUT_MS = 10_000L
        private const val GROUP_FORMATION_TIMEOUT_MS = 20_000L
    }

    private val p2p = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val channel = GT.section("fs.wifi.init") { p2p.initialize(context, Looper.getMainLooper(), null) }
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var active = false
    // Tracks whether filesync's call to setWifiEnabled(true) actually turned the radio on. Set
    // when the radio was OFF at session start and we enabled it; cleared when ensureWifiEnabled
    // saw it already on (user-owned). teardownQuietly disables the radio iff this is true so
    // the system Wi-Fi indicator doesn't linger after sync.
    @Volatile private var wifiTurnedOnByUs = false
    @Volatile private var currentDetails: JSONObject? = null
    @Volatile private var connReceiverRegistered = false
    @Volatile private var p2pStateReceiverRegistered = false
    @Volatile private var groupFormationTimeoutTask: Runnable? = null
    @Volatile private var p2pEnableTimeoutTask: Runnable? = null

    // Connection-changed receiver -- fires once the group forms, gives us the GO IP.
    private val connReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return
            if (!active) return
            p2p.requestConnectionInfo(channel) { info: WifiP2pInfo? ->
                if (info == null || !info.groupFormed) return@requestConnectionInfo
                if (!info.isGroupOwner) {
                    Log.w(TAG, "group formed but we are not the owner -- unexpected")
                    return@requestConnectionInfo
                }
                val ip = info.groupOwnerAddress?.hostAddress ?: return@requestConnectionInfo
                p2p.requestGroupInfo(channel) { group: WifiP2pGroup? ->
                    val ssid = group?.networkName ?: currentDetails?.optString("ssid", "") ?: ""
                    val pass = group?.passphrase ?: currentDetails?.optString("passphrase", "") ?: ""
                    val ownerAddr = group?.owner?.deviceAddress ?: ""
                    val details = JSONObject().apply {
                        put("ssid", ssid)
                        put("passphrase", pass)
                        put("ip", ip)
                        put("port", HTTP_PORT)
                        // Group owner MAC -- lets the phone's WifiP2pConfig.Builder pin us
                        // as the exact peer, avoiding any discovery-phase ambiguity.
                        put("deviceAddress", ownerAddr)
                    }
                    currentDetails = details
                    Log.i(TAG, "group formed: ssid=$ssid ip=$ip goMac=$ownerAddr")
                    cancelGroupTimeout()
                    onReady(details)
                }
            }
        }
    }

    // P2P-state receiver -- fires when the p2p-service finishes its InactiveState<->Disabled transitions.
    private val p2pStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION) return
            val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_DISABLED)
            Log.i(TAG, "WIFI_P2P_STATE_CHANGED -> $state")
            if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED && active && currentDetails == null) {
                cancelP2pEnableTimeout()
                unregisterP2pStateReceiver()
                proceedAfterP2pEnabled()
            }
        }
    }

    fun open() {
        Log.i(TAG, "open: enter active=$active")
        if (active) {
            currentDetails?.let(onReady)
            return
        }
        active = true

        if (!ensureWifiEnabled(10_000L)) {
            failWith("wifi_disabled")
            return
        }

        // connection-changed receiver goes up before anything else so we don't miss a fast
        // "group formed" broadcast.
        registerConnReceiver()

        // Check P2P state; if already ENABLED, proceed. Otherwise wait for the state broadcast.
        p2p.requestP2pState(channel) { state ->
            Log.i(TAG, "initial P2P state=$state (ENABLED=2)")
            if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                proceedAfterP2pEnabled()
            } else {
                // initialize() should have kicked the state machine towards enabled. Wait for it.
                registerP2pStateReceiver()
                p2pEnableTimeoutTask = Runnable {
                    if (active && currentDetails == null) {
                        Log.e(TAG, "P2P did not reach ENABLED within ${P2P_ENABLE_TIMEOUT_MS}ms")
                        failWith("p2p_disable_timeout")
                    }
                }.also { mainHandler.postDelayed(it, P2P_ENABLE_TIMEOUT_MS) }
            }
        }

        // Overall group-formation deadline -- covers every step.
        groupFormationTimeoutTask = Runnable {
            if (active && currentDetails?.optString("ip").isNullOrEmpty()) {
                Log.e(TAG, "group formation timeout after ${GROUP_FORMATION_TIMEOUT_MS}ms")
                failWith("group_formation_timeout")
            }
        }.also { mainHandler.postDelayed(it, GROUP_FORMATION_TIMEOUT_MS) }
    }

    fun close() = GT.section("fs.wifi.close") {
        Log.i(TAG, "close: enter active=$active")
        if (!active) return@section
        teardownQuietly()
    }

    // ----- pipeline -----

    private fun proceedAfterP2pEnabled() {
        val deviceName = "REPO-${lastSixStable()}"
        setDeviceNameThen(deviceName) {
            removeGroupThen {
                attemptCreateGroup(useConfig = false, attempt = 1)
            }
        }
    }

    /**
     * Call @hide [WifiP2pManager.setDeviceName] via reflection. Waits for onSuccess/onFailure
     * then invokes [next]. On a hidden-API policy block we proceed anyway (empty name may
     * still work for some paths).
     */
    private fun setDeviceNameThen(name: String, next: () -> Unit) {
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "setDeviceName('$name') ok")
                next()
            }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "setDeviceName reason=$reason -- continuing")
                next()
            }
        }
        try {
            val method = WifiP2pManager::class.java.getDeclaredMethod(
                "setDeviceName",
                WifiP2pManager.Channel::class.java,
                String::class.java,
                WifiP2pManager.ActionListener::class.java,
            )
            method.invoke(p2p, channel, name, listener)
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "setDeviceName method not accessible: ${e.message} -- skipping")
            next()
        } catch (e: Throwable) {
            Log.w(TAG, "setDeviceName reflection threw: ${e.javaClass.simpleName}: ${e.message} -- skipping")
            next()
        }
    }

    private fun removeGroupThen(next: () -> Unit) {
        try {
            p2p.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { Log.i(TAG, "pre-emptive removeGroup ok"); next() }
                override fun onFailure(reason: Int) { Log.i(TAG, "pre-emptive removeGroup reason=$reason (expected if no prior group)"); next() }
            })
        } catch (e: Exception) {
            Log.w(TAG, "pre-emptive removeGroup threw: ${e.message}"); next()
        }
    }

    /**
     * createGroup with a 3-strategy escalation:
     *  1. no-config ephemeral group (simplest path, supplicant picks SSID)
     *  2. Builder config forcing 2.4 GHz band
     *  3. persistent no-config group
     */
    private fun attemptCreateGroup(useConfig: Boolean, attempt: Int) {
        val ssidHint = "REPO-${lastSixStable()}"
        val passphrase = randomPassphrase()
        // Stash the hint. The actual SSID/pass populate from requestGroupInfo after group forms.
        currentDetails = JSONObject().apply {
            put("ssid", "DIRECT-xx-$ssidHint")
            put("passphrase", passphrase)
            put("port", HTTP_PORT)
        }

        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "createGroup accepted (attempt=$attempt useConfig=$useConfig); waiting for CONNECTION_CHANGED")
            }
            override fun onFailure(reason: Int) {
                val name = when (reason) { 0 -> "ERROR"; 1 -> "P2P_UNSUPPORTED"; 2 -> "BUSY"; 3 -> "NO_SERVICE_REQUESTS"; else -> "code=$reason" }
                Log.e(TAG, "createGroup failed ($name) attempt=$attempt useConfig=$useConfig")
                when {
                    attempt == 1 && reason != 1 -> {
                        // Try with explicit Builder config forcing 2.4 GHz.
                        mainHandler.postDelayed({ attemptCreateGroup(useConfig = true, attempt = 2) }, 1200)
                    }
                    attempt == 2 && reason == 2 -> {
                        // BUSY -> some stale state. Try a removeGroup then retry persistent.
                        removeGroupThen { mainHandler.postDelayed({ attemptCreateGroup(useConfig = false, attempt = 3) }, 1500) }
                    }
                    else -> failWith("create_group_failed_$name")
                }
            }
        }

        GT.section("fs.wifi.createGroup") {
            try {
                if (!useConfig) {
                    // Simplest path: no config. Supplicant generates a DIRECT-xy-<name> SSID.
                    @Suppress("DEPRECATION")
                    p2p.createGroup(channel, listener)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val config = WifiP2pConfig.Builder()
                        .setNetworkName("DIRECT-xx-$ssidHint")
                        .setPassphrase(passphrase)
                        .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                        .enablePersistentMode(false)
                        .build()
                    p2p.createGroup(channel, config, listener)
                } else {
                    @Suppress("DEPRECATION")
                    p2p.createGroup(channel, listener)
                }
            } catch (e: Exception) {
                Log.e(TAG, "createGroup threw: ${e.message}")
                failWith("create_group_exception")
            }
        }
    }

    /** Emit an error (distinct from a normal close) and tear down. onClosed is also
     *  fired via teardownQuietly so bridges that only listen for close still cleanup. */
    private fun failWith(reason: String) {
        try { onError(reason) } catch (_: Exception) {}
        teardownQuietly()
    }

    // ----- receivers + timeouts -----

    private fun registerConnReceiver() {
        if (connReceiverRegistered) return
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(connReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(connReceiver, filter)
        }
        connReceiverRegistered = true
    }

    private fun registerP2pStateReceiver() {
        if (p2pStateReceiverRegistered) return
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(p2pStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(p2pStateReceiver, filter)
        }
        p2pStateReceiverRegistered = true
    }

    private fun unregisterP2pStateReceiver() {
        if (!p2pStateReceiverRegistered) return
        try { context.unregisterReceiver(p2pStateReceiver) } catch (_: Exception) {}
        p2pStateReceiverRegistered = false
    }

    private fun cancelGroupTimeout() {
        groupFormationTimeoutTask?.let { mainHandler.removeCallbacks(it) }
        groupFormationTimeoutTask = null
    }

    private fun cancelP2pEnableTimeout() {
        p2pEnableTimeoutTask?.let { mainHandler.removeCallbacks(it) }
        p2pEnableTimeoutTask = null
    }

    private fun teardownQuietly() {
        active = false
        cancelGroupTimeout()
        cancelP2pEnableTimeout()
        try {
            p2p.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { Log.i(TAG, "removeGroup ok") }
                override fun onFailure(reason: Int) { Log.w(TAG, "removeGroup failed $reason") }
            })
        } catch (e: Exception) {
            Log.w(TAG, "removeGroup threw: ${e.message}")
        }
        if (connReceiverRegistered) {
            try { context.unregisterReceiver(connReceiver) } catch (_: Exception) {}
            connReceiverRegistered = false
        }
        unregisterP2pStateReceiver()
        currentDetails = null
        // Drop the radio if filesync turned it on for this session. Skipped automatically when
        // the user already had Wi-Fi enabled before the session started.
        disableWifiRadioIfWeEnabledIt()
        onClosed()
    }

    // ----- helpers -----

    private fun lastSixStable(): String {
        val androidId = try {
            android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        } catch (_: Exception) { null }
        val cleaned = androidId?.replace(Regex("[^a-zA-Z0-9]"), "")?.takeLast(6)
        if (!cleaned.isNullOrBlank()) return cleaned.uppercase()
        val prefs = context.getSharedPreferences("filesync_wifi", Context.MODE_PRIVATE)
        var id = prefs.getString("stable_id6", null)
        if (id == null) {
            val rnd = java.security.SecureRandom()
            val chars = "ABCDEFGHIJKLMNPQRSTUVWXYZ23456789"
            val sb = StringBuilder(6)
            for (i in 0 until 6) sb.append(chars[rnd.nextInt(chars.length)])
            id = sb.toString()
            prefs.edit().putString("stable_id6", id).apply()
        }
        return id
    }

    /**
     * Turn the WiFi radio on via WifiManager.setWifiEnabled(true). Requires
     * the signature|privileged NETWORK_SETTINGS permission, which filesync
     * obtains by living in /system/priv-app and being whitelisted in
     * privapp-permissions-com.repository.glasses.filesync.xml.
     * Radio flip is asynchronous; caller polls isWifiEnabled to confirm.
     */
    private fun enableWifiRadio(): Boolean {
        return try {
            val ok = wifi.setWifiEnabled(true)
            if (ok) Log.i(TAG, "enableWifiRadio: setWifiEnabled(true) ok (priv-app)")
            else Log.e(TAG, "enableWifiRadio: setWifiEnabled(true) returned false (priv-app perm not granted?)")
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "enableWifiRadio: setWifiEnabled threw: ${t.message}")
            false
        }
    }

    /**
     * Ensure the WiFi radio is enabled, blocking up to [timeoutMs] for the
     * state flip. Returns true iff WifiManager.isWifiEnabled is true on exit.
     * Idempotent -- a no-op when WiFi is already on.
     */
    private fun ensureWifiEnabled(timeoutMs: Long): Boolean {
        if (wifi.isWifiEnabled) {
            // Wi-Fi was already on; the user (or another component) owns it. Don't disable on close.
            wifiTurnedOnByUs = false
            return true
        }
        Log.w(TAG, "ensureWifiEnabled: radio OFF; attempting to enable (timeoutMs=$timeoutMs)")
        if (!enableWifiRadio()) {
            Log.e(TAG, "ensureWifiEnabled: enableWifiRadio returned false")
            return false
        }
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (!wifi.isWifiEnabled && android.os.SystemClock.elapsedRealtime() < deadline) {
            try { Thread.sleep(100L) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        val ok = wifi.isWifiEnabled
        if (ok) {
            Log.i(TAG, "ensureWifiEnabled: WiFi radio is ENABLED")
            // We turned it on; close() must turn it off so the user doesn't see the indicator
            // sticking on after the sync session ended.
            wifiTurnedOnByUs = true
        } else Log.e(TAG, "ensureWifiEnabled: WiFi did NOT come ENABLED within ${timeoutMs}ms")
        return ok
    }

    /**
     * Symmetric counterpart of [enableWifiRadio]: only invoked when filesync was the component
     * that turned the radio on (tracked by [wifiTurnedOnByUs]). Without this, the radio stays on
     * indefinitely after a sync session and the system Wi-Fi indicator misleads the user into
     * thinking some app is actively using Wi-Fi.
     */
    private fun disableWifiRadioIfWeEnabledIt() {
        if (!wifiTurnedOnByUs) return
        wifiTurnedOnByUs = false
        try {
            val ok = wifi.setWifiEnabled(false)
            if (ok) Log.i(TAG, "disableWifiRadio: setWifiEnabled(false) ok (priv-app)")
            else Log.w(TAG, "disableWifiRadio: setWifiEnabled(false) returned false")
        } catch (t: Throwable) {
            Log.e(TAG, "disableWifiRadio: setWifiEnabled(false) threw: ${t.message}")
        }
    }

    private fun randomPassphrase(): String {
        val rnd = java.security.SecureRandom()
        val chars = "abcdefghijkmnpqrstuvwxyz23456789"
        val sb = StringBuilder(12)
        for (i in 0 until 12) sb.append(chars[rnd.nextInt(chars.length)])
        return sb.toString()
    }
}
