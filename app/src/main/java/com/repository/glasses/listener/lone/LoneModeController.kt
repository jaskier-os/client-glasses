package com.repository.glasses.listener.lone

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONArray
import org.json.JSONObject

/**
 * Source of truth for Lone mode. Lives in the glasses :backend service so it keeps working
 * standalone when the phone link drops.
 *
 * Responsibilities:
 *  - Merge devices seen by the glasses' own [LoneBtScanner] AND devices the phone reports,
 *    deduplicated by MAC, with per-source last-seen timestamps.
 *  - Maintain the trusted set as (trustedFromPhone UNION trustedPeers); the connected phone's
 *    MAC is a trustedPeer so our own pair never counts as foreign even as BLE MACs rotate.
 *  - Count foreign (untrusted, currently-live) devices, drive the HUD indicator, and fire
 *    the alert callback once per newly-seen foreign device. A device absent from BOTH sources for
 *    >120s is evicted and re-arms (re-alerts) if it returns.
 *  - Push the merged device list back to the phone (throttled, change-only) for the modal.
 *
 * All mutating work is serialized on a single background handler to avoid scan/tick/command races.
 */
class LoneModeController(
    context: Context,
    private val onActive: (Boolean) -> Unit,   // show/hide the HUD indicator (UI process)
    private val onCount: (Int) -> Unit,          // update the foreign-device count on the indicator
    private val onAlert: () -> Unit,
    private val pushToPhone: (json: String) -> Unit,
    private val log: (String) -> Unit
) {
    companion object {
        private const val ABSENCE_MS = 120_000L     // evict + re-arm after this much continuous absence
        private const val TICK_MS = 5_000L           // eviction / recompute cadence
        private const val PUSH_THROTTLE_MS = 2_000L  // min spacing between merged-list pushes
        private const val MAX_PUSH_DEVICES = 200
    }

    private class Record(
        var name: String?,
        var rssi: Int,
        var phoneLastSeen: Long,
        var glassesLastSeen: Long
    )

    private val deviceMap = HashMap<String, Record>()
    private val trustedFromPhone = HashSet<String>()
    private val trustedPeers = HashSet<String>()
    private val trustedNames = HashSet<String>()  // lowercased names of OUR pair (phone+glasses)
    private val alertedSet = HashSet<String>()

    @Volatile private var active = false
    private var started = false   // a lone_start has been received this session
    private var dirty = false
    private var lastPush = 0L
    private var lastCount = -1     // last value pushed to the overlay (avoids redundant updates)

    private val thread = HandlerThread("lone-controller").apply { start() }
    private val handler = Handler(thread.looper)

    private val scanner = LoneBtScanner(
        context = context,
        onDevice = { addr, name, rssi ->
            handler.post { merge(addr.uppercase(), name, rssi, fromPhone = false) }
        },
        log = log
    )

    private val tick = object : Runnable {
        override fun run() {
            evictStale()
            recompute()
            if (active) handler.postDelayed(this, TICK_MS)
        }
    }

    private fun effectiveTrusted(addr: String): Boolean =
        addr in trustedFromPhone || addr in trustedPeers

    // --- public API (each marshals onto the controller thread) ---

    fun start(trusted: Collection<String>, glassesMac: String?, pairNames: Collection<String>) {
        handler.post {
            started = true
            active = true
            trustedFromPhone.clear()
            trusted.forEach { trustedFromPhone.add(it.uppercase()) }
            glassesMac?.takeIf { it.isNotBlank() }?.let { trustedFromPhone.add(it.uppercase()) }
            trustedNames.clear()
            pairNames.forEach { it.trim().lowercase().takeIf { n -> n.isNotEmpty() }?.let { n -> trustedNames.add(n) } }
            onActive(true)
            lastCount = currentForeignCount()
            onCount(lastCount)
            scanner.start()
            handler.removeCallbacks(tick)
            handler.postDelayed(tick, TICK_MS)
            log("LoneMode: started, trustedFromPhone=${trustedFromPhone.size} peers=${trustedPeers.size}")
            dirty = true
            maybePush(force = true)
        }
    }

    fun stop() {
        handler.post {
            active = false
            started = false
            scanner.stop()
            handler.removeCallbacks(tick)
            onActive(false)
            deviceMap.clear()
            alertedSet.clear()
            trustedFromPhone.clear()
            trustedNames.clear()
            lastCount = -1
            log("LoneMode: stopped")
        }
    }

    /** Trust the connected companion phone's MAC. Survives phone re-pushes (separate set). */
    fun setTrustedPeer(mac: String?) {
        val m = mac?.takeIf { it.isNotBlank() }?.uppercase() ?: return
        handler.post {
            if (trustedPeers.add(m)) {
                alertedSet.remove(m)
                log("LoneMode: trusted peer $m")
                recompute()
            }
        }
    }

    fun onPhoneDevices(devicesJson: String) {
        handler.post {
            if (!started) return@post  // ignore until lone_start establishes the trusted baseline
            try {
                val arr = JSONObject(devicesJson).optJSONArray("devices") ?: return@post
                val now = System.currentTimeMillis()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val addr = o.optString("address").uppercase()
                    if (addr.isBlank()) continue
                    merge(addr, o.optString("name").takeIf { it.isNotBlank() }, o.optInt("rssi", 0), fromPhone = true, now = now)
                }
            } catch (e: Exception) {
                log("LoneMode: onPhoneDevices parse failed: ${e.message}")
            }
        }
    }

    fun onTrustUpdate(address: String, trusted: Boolean) {
        val addr = address.uppercase()
        handler.post {
            if (trusted) {
                trustedFromPhone.add(addr)
                alertedSet.remove(addr)  // so it re-arms if it ever becomes foreign again
            } else {
                trustedFromPhone.remove(addr)
            }
            log("LoneMode: trust update $addr -> $trusted")
            dirty = true
            recompute()
        }
    }

    fun release() {
        stop()  // posts the teardown (scanner.stop + overlay.hide) onto the handler
        // Quit only AFTER the posted teardown runs (same handler, FIFO order).
        handler.post { thread.quitSafely() }
    }

    // --- internals (controller thread only) ---

    private fun merge(addr: String, name: String?, rssi: Int, fromPhone: Boolean, now: Long = System.currentTimeMillis()) {
        if (!active) return
        val rec = deviceMap.getOrPut(addr) { Record(name, rssi, 0L, 0L) }
        if (!name.isNullOrBlank()) rec.name = name
        rec.rssi = rssi
        if (fromPhone) rec.phoneLastSeen = now else rec.glassesLastSeen = now

        // Our own pair (phone+glasses) advertise rotating BLE MACs that differ from their bond
        // MAC. Auto-trust any device whose advertised name matches our pair, keeping the pair
        // exempt as its address rotates (user requirement).
        val recName = rec.name?.trim()?.lowercase()
        if (recName != null && recName in trustedNames) {
            if (trustedPeers.add(addr)) alertedSet.remove(addr)
        }

        if (!effectiveTrusted(addr) && alertedSet.add(addr)) {
            // First sighting of an untrusted device this cycle -> alert once.
            log("LoneMode: ALERT new foreign device $addr (${rec.name ?: "?"})")
            onAlert()
        }
        dirty = true
        recompute()
    }

    private fun evictStale() {
        val now = System.currentTimeMillis()
        val it = deviceMap.entries.iterator()
        while (it.hasNext()) {
            val (addr, rec) = it.next()
            val lastSeen = maxOf(rec.phoneLastSeen, rec.glassesLastSeen)
            if (now - lastSeen > ABSENCE_MS) {
                it.remove()
                alertedSet.remove(addr)  // re-arm: a return after eviction re-alerts
                dirty = true
            }
        }
    }

    private fun currentForeignCount(): Int = deviceMap.keys.count { !effectiveTrusted(it) }

    private fun recompute() {
        val count = currentForeignCount()
        if (count != lastCount) {
            lastCount = count
            onCount(count)
        }
        maybePush(force = false)
    }

    private fun maybePush(force: Boolean) {
        if (!dirty && !force) return
        val now = System.currentTimeMillis()
        val wait = PUSH_THROTTLE_MS - (now - lastPush)
        if (!force && wait > 0) {
            handler.removeCallbacks(pushRunnable)
            handler.postDelayed(pushRunnable, wait)
            return
        }
        doPush(now)
    }

    private val pushRunnable = Runnable { doPush(System.currentTimeMillis()) }

    private fun doPush(now: Long) {
        if (!started) return
        handler.removeCallbacks(pushRunnable)  // cancel any pending delayed push; this one supersedes it
        lastPush = now
        dirty = false
        val arr = JSONArray()
        var n = 0
        for ((addr, rec) in deviceMap) {
            if (n >= MAX_PUSH_DEVICES) break
            val phoneRecent = rec.phoneLastSeen > 0 && now - rec.phoneLastSeen <= ABSENCE_MS
            val glassesRecent = rec.glassesLastSeen > 0 && now - rec.glassesLastSeen <= ABSENCE_MS
            val source = when {
                phoneRecent && glassesRecent -> "both"
                phoneRecent -> "phone"
                else -> "glasses"
            }
            arr.put(JSONObject().apply {
                put("address", addr)
                put("name", rec.name ?: "")
                put("rssi", rec.rssi)
                put("source", source)
                put("trusted", effectiveTrusted(addr))
                put("lastSeen", maxOf(rec.phoneLastSeen, rec.glassesLastSeen))
            })
            n++
        }
        val payload = JSONObject().apply {
            put("devices", arr)
            put("foreign", currentForeignCount())
        }.toString()
        pushToPhone(payload)
    }
}
