package com.repository.glasses.btmanager

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.repository.glasses.tracing.GT
import java.util.concurrent.ConcurrentHashMap

/**
 * Brings up HFP-HF (HEADSET_CLIENT) and A2DP Sink on every bonded device whenever:
 *   - the BluetoothAdapter turns ON,
 *   - a device finishes bonding,
 *   - the glasses are unfolded,
 *   - or a periodic safety tick fires (60s) and a profile is still down.
 *
 * Fold-gated: while folded, the sweep reconnects nothing and onFold() actively
 * tears down both A2DP and HFP, so the BT stack releases its kernel wakelock
 * (hal_bluetooth_lock) and glasses-power-daemon can freeze. Both profiles
 * reconnect automatically on unfold.
 */
@SuppressLint("MissingPermission")
class ProfileAutoConnector(
    ctx: Context,
    private val adapter: BluetoothAdapter?,
    private val hfp: HfpClientController,
    private val a2dp: A2dpSinkController,
    private val foldGate: FoldGate,
    private val log: (String) -> Unit = {},
) {
    private val appCtx = ctx.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var receiverRegistered = false

    // Consecutive sweep counter where a device had zero profiles connected.
    // Incremented each sweep when both HFP and A2DP are down; reset to 0
    // when at least one profile is up or on ACL_CONNECTED.
    // When >= STALE_THRESHOLD, skip the device in periodic sweeps.
    private val disconnectedSweeps = ConcurrentHashMap<String, Int>()

    companion object {
        private const val INIT_DELAY_MS = 4_000L
        private const val ADAPTER_ON_DELAY_MS = 2_000L
        private const val BONDED_DELAY_MS = 1_500L
        private const val ACL_DELAY_MS = 800L
        // Timer cadence. Every tick re-checks devices that already have an ACL
        // up, so a profile that drops on a present device is restored within 5s
        // instead of up to a minute.
        private const val PERIODIC_MS = 5_000L
        // Absent (no ACL) devices are only swept every SLOW_TICK_EVERY'th tick,
        // keeping their paging cadence at the original 60s.
        private const val SLOW_TICK_EVERY = 12
        // Minimum gap between two connect() attempts for the same profile on the
        // same device. connect() is async and isConnected() lags it, so without
        // this a device sitting in CONNECTING would be re-connected every tick.
        private const val CONNECT_COOLDOWN_MS = 20_000L
        private const val STALE_THRESHOLD = 3
        // Every UNSTALE_PROBE_INTERVAL slow ticks, retry one stale device
        // to prevent permanent deadlock when Android doesn't auto-initiate ACL.
        private const val UNSTALE_PROBE_INTERVAL = 10 // ~10 minutes
        // If an ACL_CONNECTED arrives within this window of our own outbound
        // a2dp.connect() to the same device, the ACL was self-initiated (our
        // sweep poking an asleep device) -- not a user switch. Suppress the swap.
        private const val SELF_ACL_WINDOW_MS = 5_000L
    }

    private var periodicTickCount = 0

    @Volatile private var aclReflectionFailureLogged = false

    // A2DP handoff that arrived while a call held the audio path. Replayed by the
    // sweep once the call ends. Main-looper confined.
    private var pendingSwapAddr: String? = null

    // Last time we issued a connect() for an address, per profile. Gates the
    // fast tick so an async connect that has not settled is not re-issued.
    private val lastHfpAttemptMs = ConcurrentHashMap<String, Long>()
    private val lastA2dpAttemptMs = ConcurrentHashMap<String, Long>()

    private fun cooledDown(map: ConcurrentHashMap<String, Long>, addr: String): Boolean {
        val at = map[addr] ?: return true
        return SystemClock.elapsedRealtime() - at >= CONNECT_COOLDOWN_MS
    }

    private val periodicTick = object : Runnable {
        override fun run() {
            periodicTickCount++
            // The timer runs at the fast cadence; every SLOW_TICK_EVERY'th tick
            // is additionally a slow one, which is the only kind that may page
            // absent devices. Fast ticks only fix profile gaps on devices that
            // already have an ACL up, so the paging cost is unchanged.
            val slowTick = periodicTickCount % SLOW_TICK_EVERY == 0
            // Periodic un-stale probe: every ~10 min, reset one stale device
            // so the sweep retries it. Prevents permanent deadlock when Android
            // doesn't auto-initiate ACL reconnection for a returning device.
            if (slowTick && periodicTickCount % (SLOW_TICK_EVERY * UNSTALE_PROBE_INTERVAL) == 0) {
                val staleEntry = disconnectedSweeps.entries.firstOrNull { it.value >= STALE_THRESHOLD }
                if (staleEntry != null) {
                    staleEntry.setValue(0)
                    log("autoconnect unstale_probe addr=${staleEntry.key} reset for retry (tick=$periodicTickCount)")
                }
            }
            try { tryConnect(if (slowTick) "periodic" else "periodic_fast", slowTick) } catch (e: Throwable) {
                log("autoconnect periodic threw: ${e.message}")
            }
            handler.postDelayed(this, PERIODIC_MS)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val s = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    if (s == BluetoothAdapter.STATE_ON) {
                        // Fresh start -- all profiles are down, don't penalize devices.
                        disconnectedSweeps.clear()
                        schedule("adapter_on", ADAPTER_ON_DELAY_MS)
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val s = intent.getIntExtra(
                        BluetoothDevice.EXTRA_BOND_STATE,
                        BluetoothDevice.BOND_NONE
                    )
                    if (s == BluetoothDevice.BOND_BONDED) {
                        // Fresh bond -- clear any stale counter for this device.
                        val dev = intent.getParcelableExtra<BluetoothDevice>(
                            BluetoothDevice.EXTRA_DEVICE
                        )
                        dev?.address?.let { disconnectedSweeps.remove(it) }
                        schedule("bonded", BONDED_DELAY_MS)
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val dev = intent.getParcelableExtra<BluetoothDevice>(
                        BluetoothDevice.EXTRA_DEVICE
                    )
                    val addr = dev?.address
                    // LE-only bonds can't carry A2DP Sink. An iPhone's LE random
                    // addresses go ACL-up often, and deviceCanSourceA2dp() returns
                    // true when SDP hasn't returned UUIDs -- so without this guard
                    // the swap below would fire connectExclusive() on an LE address
                    // and drop the working A2DP link on the classic one.
                    if (dev != null && addr != null && supportsClassicProfiles(dev)) {
                        // Distinguish a self-initiated ACL (our own sweep's
                        // a2dp.connect raised it) from a genuine user-initiated
                        // connection. Our connect attempt to an asleep device
                        // brings up its ACL ~1s later; treating that as a user
                        // switch would wrongly kick the active phone's A2DP.
                        val connectAge = a2dp.msSinceConnectAttempt(addr)
                        val selfInitiated = connectAge < SELF_ACL_WINDOW_MS
                        if (selfInitiated) {
                            log("autoconnect acl_connected addr=$addr name=${dev.name} self-initiated (own connect ${connectAge}ms ago), skip swap+unstale")
                        } else {
                            // ACL up = device is reachable. Reset stale counter.
                            val prevSweeps = disconnectedSweeps.put(addr, 0) ?: 0
                            if (prevSweeps >= STALE_THRESHOLD) {
                                log("autoconnect acl_connected addr=$addr name=${dev.name} un-staled (was $prevSweeps sweeps)")
                            }
                            val canSource = deviceCanSourceA2dp(dev)
                            log("autoconnect acl_connected addr=$addr name=${dev.name} canSourceA2dp=$canSource")
                            if (canSource) {
                                // Last-writer-wins: a genuine fresh ACL from a music
                                // source swaps whoever is in the A2DP Sink slot.
                                handler.postDelayed({
                                    try {
                                        // A device with SCO up is on a live call.
                                        // Reassigning the A2DP slot makes the stack
                                        // renegotiate the shared audio path and can
                                        // drop that call's SCO, so a call outranks a
                                        // music handoff: defer the swap until the
                                        // call ends. Without a call, the slot is
                                        // freely reassigned as before -- interrupting
                                        // music costs the user nothing.
                                        if (!hfp.noCallAudioExceptFor(addr)) {
                                            // Remember it: dropping the swap
                                            // outright would leave the sink slot
                                            // shared between both sources once the
                                            // sweep reconnects A2DP, which is the
                                            // exact state connectExclusive prevents.
                                            pendingSwapAddr = addr
                                            log("autoconnect acl_connected.swap addr=$addr DEFERRED, call audio active")
                                            return@postDelayed
                                        }
                                        pendingSwapAddr = null
                                        val currentA2dp = a2dp.connectedDevices()
                                        log("autoconnect acl_connected.swap addr=$addr current_a2dp=${currentA2dp.joinToString { "${it.address}(${it.name})" }.ifEmpty { "none" }}")
                                        a2dp.connectExclusive(addr)
                                        // HFP follows the same device. The slot is
                                        // single-occupancy because the HF client
                                        // rejects a second AG's SCO, so the newly
                                        // active source has to own it or its calls
                                        // get no audio.
                                        hfp.connectExclusive(addr)
                                    } catch (e: Throwable) {
                                        log("acl_connected swap threw: ${e.message}")
                                    }
                                }, ACL_DELAY_MS)
                            }
                        }
                    }
                    schedule("acl_connected", ACL_DELAY_MS)
                }
            }
        }
    }

    fun start() {
        try {
            val f = IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            }
            appCtx.registerReceiver(receiver, f)
            receiverRegistered = true
        } catch (e: Throwable) {
            log("ProfileAutoConnector receiver register failed: ${e.message}")
        }

        // FoldGate is started by BtManagerService; ProfileAutoConnector reads
        // foldGate.folded to gate the sweep and drives onFold() teardown via
        // the fold listener wired in BtManagerService.

        // First sweep after profile proxies have time to bind.
        schedule("init", INIT_DELAY_MS)
        // Periodic safety net: cheap, only acts when something is missing.
        handler.postDelayed(periodicTick, PERIODIC_MS)

        log("ProfileAutoConnector started (fold-gated)")
    }

    /**
     * Drive profile state from a fold transition. On fold: tear down A2DP + HFP
     * on every bonded device so the BT stack releases hal_bluetooth_lock and the
     * power daemon can freeze. On unfold: kick a sweep to reconnect both.
     * Reconnect-while-folded is separately blocked in tryConnect().
     */
    fun onFold(folded: Boolean) {
        handler.post {
            try {
                if (folded) {
                    val a = adapter ?: return@post
                    val bonded = try { a.bondedDevices } catch (e: Throwable) {
                        log("onFold: bondedDevices threw: ${e.message}"); return@post
                    } ?: emptySet()
                    var droppedHfp = 0; var droppedA2dp = 0
                    for (dev in bonded) {
                        val addr = dev.address ?: continue
                        if (a2dp.isConnected(addr)) {
                            a2dp.disconnect(addr); droppedA2dp++
                        }
                        if (hfp.isConnected(addr)) {
                            hfp.disconnect(addr); droppedHfp++
                        }
                    }
                    log("onFold(folded) dropped hfp=$droppedHfp a2dp=$droppedA2dp")
                } else {
                    log("onFold(unfolded) scheduling reconnect sweep")
                    schedule("unfold", BONDED_DELAY_MS)
                }
            } catch (e: Throwable) {
                log("onFold($folded) threw: ${e.message}")
            }
        }
    }

    fun stop() {
        try { if (receiverRegistered) appCtx.unregisterReceiver(receiver) } catch (_: Throwable) {}
        receiverRegistered = false
        handler.removeCallbacksAndMessages(null)
        pendingSwapAddr = null
    }

    /**
     * True when [dev] can carry BR/EDR profiles at all. HFP-HF and A2DP Sink are
     * BR/EDR-only, so an LE-only bond can never satisfy them.
     *
     * An iPhone bonds three times against these glasses: once on its public
     * BR/EDR address and twice on LE random addresses. Sweeping the LE bonds
     * fired hfp.connect()/a2dp.connect() every 60s forever -- each attempt
     * flapped CONNECTING -> DISCONNECTED without ever succeeding, churning the
     * profile state machines on the same piconet that carries the live HFP link.
     * DUAL and UNKNOWN both stay eligible: UNKNOWN just means SDP/type hasn't
     * resolved yet, and treating that as ineligible would strand a real classic
     * device that is merely slow to report.
     */
    private fun supportsClassicProfiles(dev: BluetoothDevice): Boolean {
        val type = try { dev.type } catch (_: Throwable) { BluetoothDevice.DEVICE_TYPE_UNKNOWN }
        return type != BluetoothDevice.DEVICE_TYPE_LE
    }

    /**
     * True when [dev] advertises an A2DP Source UUID (0x110A) -- i.e. it can
     * push music to us. Phones, PCs, and most BT speakers/laptops match.
     * If SDP hasn't returned UUIDs yet, fall back to true so we don't miss
     * the swap on a fresh pair; the actual A2dpSink.connect() will simply
     * fail on devices that turn out to not have the role.
     */
    private fun deviceCanSourceA2dp(dev: BluetoothDevice): Boolean {
        val uuids = try { dev.uuids } catch (_: Throwable) { null } ?: return true
        val audioSource = "0000110A-0000-1000-8000-00805F9B34FB"
        return uuids.any { it.uuid.toString().equals(audioSource, ignoreCase = true) }
    }

    private fun schedule(reason: String, delayMs: Long) {
        handler.postDelayed({
            try { tryConnect(reason) } catch (e: Throwable) {
                log("autoconnect $reason threw: ${e.message}")
            }
        }, delayMs)
    }

    /**
     * True when [dev] currently has an ACL link up.
     *
     * BluetoothDevice.isConnected() is @hide, hence the reflection. Failing
     * closed (false) on any error is the safe default: it only costs the device
     * the slow cadence it had before, whereas failing open would page every
     * absent bond every 5s.
     */
    private fun isAclConnected(dev: BluetoothDevice): Boolean = try {
        val m = BluetoothDevice::class.java.getMethod("isConnected")
        m.isAccessible = true
        (m.invoke(dev) as? Boolean) ?: false
    } catch (e: Throwable) {
        // Log once, not per device per 5s tick. If this fires, every device
        // looks absent and the fast path is silently dead -- which would
        // otherwise be invisible, since the degraded behaviour is just the old
        // 60s cadence.
        if (!aclReflectionFailureLogged) {
            aclReflectionFailureLogged = true
            log("event=autoconnect.acl_reflection_failed err=${e.message} (fast path disabled)")
        }
        false
    }

    /**
     * @param slowTick true on the 60s cadence, which is the only one allowed to
     * page devices that have no ACL. Fast (5s) ticks act on present devices only.
     */
    private fun tryConnect(reason: String, slowTick: Boolean = true) = GT.section("bt.autoconnect") {
        val a = adapter ?: return@section
        if (!a.isEnabled) {
            log("autoconnect($reason): adapter off, skip")
            return@section
        }
        val folded = foldGate.folded
        // Folded, the sweep connects nothing, so a fast tick can only burn
        // binder calls and keep the main looper busy during exactly the idle
        // window the fold teardown exists to create. Slow ticks still run so
        // the stale bookkeeping stays live.
        if (folded && !slowTick) return@section
        val bonded = try { a.bondedDevices } catch (e: Throwable) {
            log("autoconnect($reason): bondedDevices threw: ${e.message}")
            return@section
        } ?: emptySet()
        if (bonded.isEmpty()) {
            log("autoconnect($reason): no bonded devices")
            return@section
        }
        // bondedDevices() is an unordered Set, and HFP has exactly one slot, so
        // iteration order decides which AG gets it on a cold boot. Order by most
        // recent call audio (then address, for a stable tiebreak) instead of
        // leaving it to chance -- otherwise the winner flaps between boots.
        val ordered = bonded.sortedWith(
            compareBy({ hfp.msSinceAudioActivity(it.address ?: "") }, { it.address ?: "" })
        )
        var needHfp = 0; var needA2dp = 0; var skippedStale = 0; var skippedLe = 0
        var skippedFast = 0
        // HFP is single-occupancy (the HF client rejects a second AG's SCO), so
        // reconcile the slot before doing anything else.
        //
        // Holders whose ACL is gone do not count: the stack can keep reporting
        // CONNECTED for a device that has walked away, and treating that as a
        // full slot would leave HFP down for everyone, permanently. Drop those.
        //
        // If more than one holder is left, the AG re-connected behind our back --
        // policy stays ALLOWED for kicked devices precisely so they can, and
        // nothing else evicts them. Keep the most recently active and kick the
        // rest, or the two-AG state silently returns.
        var hfpSlotClaimed = false
        run {
            val holders = hfp.connectedDevices()
            val live = holders.filter { h ->
                val stillHere = bonded.any { it.address.equals(h.address, true) && isAclConnected(it) }
                if (!stillHere) {
                    log("autoconnect($reason) hfp slot holder ${h.address} has no ACL, releasing")
                    hfp.disconnectTransient(h.address)
                }
                stillHere
            }
            if (live.size > 1) {
                val keep = live.minByOrNull { hfp.msSinceAudioActivity(it.address) } ?: live.first()
                for (d in live) {
                    if (!d.address.equals(keep.address, true)) {
                        log("autoconnect($reason) hfp slot has ${live.size} holders, kicking ${d.address} keeping ${keep.address}")
                        hfp.disconnectTransient(d.address)
                    }
                }
            }
            hfpSlotClaimed = live.isNotEmpty()
        }
        // A connect issued but not yet CONNECTED is invisible to the proxy, so
        // without this a later tick would hand the slot to a second device while
        // the first is still CONNECTING -- the same two-AG race, displaced.
        if (!hfpSlotClaimed && lastHfpAttemptMs.keys.any { !cooledDown(lastHfpAttemptMs, it) }) {
            hfpSlotClaimed = true
        }
        // Replay a handoff that arrived during a call. Runs before the per-device
        // work so the winner is decided before any A2DP connect is issued.
        // Not while folded: connectExclusive() would bring A2DP back up and undo
        // the fold teardown that releases hal_bluetooth_lock. The pending swap is
        // kept, and unfold schedules a sweep that replays it.
        pendingSwapAddr?.takeIf { !folded }?.let { want ->
            // The device may have walked away during the call. Replaying then
            // would page an absent device and evict whichever source legitimately
            // owns the sink slot now, so drop the request instead.
            val stillHere = bonded.any {
                it.address.equals(want, true) && isAclConnected(it)
            }
            if (!stillHere) {
                pendingSwapAddr = null
                log("autoconnect($reason) dropping deferred swap addr=$want (no longer connected)")
            } else if (hfp.noCallAudioExceptFor(want)) {
                pendingSwapAddr = null
                log("autoconnect($reason) replaying deferred swap addr=$want")
                try { a2dp.connectExclusive(want); hfp.connectExclusive(want) } catch (e: Throwable) {
                    log("autoconnect($reason) deferred swap threw: ${e.message}")
                }
            }
        }
        for (dev in ordered) {
            val addr = dev.address ?: continue
            // LE-only bonds can't carry HFP-HF / A2DP Sink. Skip before the stale
            // counters so they never accumulate sweeps for a device that is not a
            // candidate in the first place.
            if (!supportsClassicProfiles(dev)) {
                skippedLe++
                // Drop any counter accrued before this guard existed, so the
                // un-stale probe never burns its one-device-per-10-min slot
                // resetting an address the sweep will never act on again.
                disconnectedSweeps.remove(addr)
                continue
            }
            // An ACL-up device is present and awake: a connect() costs no paging
            // and a missing profile is a real gap the user is feeling right now,
            // so retry it on every fast tick and never let it go stale. Devices
            // with no ACL are absent -- those keep the slow cadence and the
            // stale counter, because each connect() there is a costly page.
            // Checked BEFORE the isConnected() calls below so a skipped device
            // costs one binder call per tick, not three.
            val aclUp = isAclConnected(dev)
            if (!aclUp && !slowTick) {
                skippedFast++
                continue
            }
            val hfpUp = hfp.isConnected(addr)
            val a2dpUp = a2dp.isConnected(addr)
            // Clear the cooldown as soon as a profile is actually up: the attempt
            // it was throttling has landed. Otherwise a profile that drops right
            // after connecting would sit unrepaired for the rest of the window.
            if (hfpUp) lastHfpAttemptMs.remove(addr)
            if (a2dpUp) lastA2dpAttemptMs.remove(addr)
            if (aclUp) {
                disconnectedSweeps.remove(addr)
            } else if (!hfpUp && !a2dpUp) {
                // Stale bond suppression: count consecutive sweeps where BOTH
                // profiles are down. connect() return value is unreliable (async).
                val sweeps = (disconnectedSweeps[addr] ?: 0) + 1
                disconnectedSweeps[addr] = sweeps
                if (sweeps >= STALE_THRESHOLD) {
                    if (sweeps == STALE_THRESHOLD) {
                        log("autoconnect($reason) addr=$addr name=${dev.name} marked STALE after $sweeps sweeps with zero profiles")
                    }
                    skippedStale++
                    continue
                }
            } else {
                disconnectedSweeps[addr] = 0
            }
            // Folded = off-head, not in use. Reconnect NOTHING while folded:
            // both A2DP and HFP must stay down so the BT stack releases its
            // kernel wakelock (hal_bluetooth_lock) and glasses-power-daemon can
            // freeze. onFold() actively tears both down; this just prevents the
            // sweep from bringing them back. Unfold reconnects both.
            val needsWork = !folded && (!hfpUp || !a2dpUp)
            if (!needsWork) continue
            // connect() is async and isConnected() lags it, so a device sitting
            // in CONNECTING still reads as down. Without a cooldown the 5s tick
            // would re-issue connect() on top of an attempt already in flight.
            // The listener app deliberately drops every A2DP source for the
            // duration of a call (AudioRoutingController.disconnectForCall), so
            // a2dpUp=false during a call is the intended state, not a gap to
            // repair. Re-connecting it here would fight that and disturb the
            // call's audio path. HFP is still repaired: that is the call itself.
            val a2dpAllowed = hfp.noCallAudioExceptFor(addr)
            // HFP is single-occupancy: Fluoride's HF client cannot serve two AGs
            // and rejects the second one's SCO outright (HCI 0x0f), so bringing
            // it up everywhere is what BREAKS call audio rather than enabling it.
            // Only fill the slot when it is empty; handing it between devices is
            // the ACL handler's job.
            // hfpSlotClaimed is tracked across the whole loop, not re-read per
            // device: connect() is async, so a second device evaluated in the
            // same sweep would still see the slot empty and both would be
            // connected -- recreating the exact two-AG state that breaks SCO.
            val hfpDue = !hfpUp && !hfpSlotClaimed && cooledDown(lastHfpAttemptMs, addr)
            val a2dpDue = !a2dpUp && a2dpAllowed && cooledDown(lastA2dpAttemptMs, addr)
            if (!hfpDue && !a2dpDue) continue
            log("autoconnect($reason) addr=$addr name=${dev.name} hfp=$hfpUp a2dp=$a2dpUp folded=$folded sweeps=${disconnectedSweeps[addr] ?: 0}")
            if (hfpDue) {
                needHfp++
                hfpSlotClaimed = true
                lastHfpAttemptMs[addr] = SystemClock.elapsedRealtime()
                val ok = hfp.connect(addr)
                if (!ok) log("autoconnect($reason) hfp.connect FAILED addr=$addr")
            }
            if (a2dpDue) {
                needA2dp++
                lastA2dpAttemptMs[addr] = SystemClock.elapsedRealtime()
                val ok = a2dp.connect(addr)
                if (!ok) log("autoconnect($reason) a2dp.connect FAILED addr=$addr")
            }
        }
        // skippedFast is deliberately not a reason to log: it is non-zero on
        // almost every fast tick, and logging it would put a line in the
        // persistent log every 5s forever.
        if (needHfp > 0 || needA2dp > 0 || skippedStale > 0 || skippedLe > 0) {
            log("autoconnect($reason) done bonded=${bonded.size} need_hfp=$needHfp need_a2dp=$needA2dp skipped_stale=$skippedStale skipped_le=$skippedLe skipped_fast=$skippedFast folded=$folded")
        }
    }
}
