package com.repository.glasses.btmanager

import org.json.JSONObject

/**
 * Decides when the local Bluetooth stack has leaked an SCO control block, and
 * which recovery tier to run. Pure logic: no Android types, no timers, no
 * Bluetooth calls. [ScoSlotGuard] is the shell that supplies the clock ticks and
 * performs the actions this class returns.
 *
 * ## The fault
 *
 * Fluoride keeps 6 SCO control blocks. `btm_sco_conn_req()` only accepts an
 * incoming SCO if it finds a block LISTENING for that peer. When an AG tears SCO
 * down and the Disconnection Complete is not mapped back onto
 * `bta_hf_client_cb.scb.sco_idx`, the block stays in state 4 (in use) forever;
 * `bta_hf_client_sco_create()` then refuses to arm a new listener
 * ("Index 0x0000 already in use") and every subsequent incoming SCO is rejected
 * with HCI 0x0d Limited Resources. The HF state machine still believes it is in
 * `AudioOn`, so nothing above the stack is ever told audio stopped -- which is
 * why `mAudioState: 2` and total silence coexist, and why only a reboot fixes it.
 *
 * We cannot patch the stack. But the wedge is reversible from above: driving the
 * state machine out of `AudioOn` runs `BTA_HfClientAudioClose ->
 * bta_hf_client_sco_close -> BTM_RemoveSco`, freeing the block and resetting
 * `sco_idx`, after which the HF client re-arms a listener on its own.
 *
 * ## Why detection is a poll and not an event
 *
 * The defining property of the wedge is that the stack STOPS emitting events, so
 * there is nothing to subscribe to. Phase 0 (2026-08-09, recorded in
 * docs/plans/2026-08-09-hfp-sco-slot-leak.md) additionally measured that the
 * obvious cross-check -- an `AudioDeviceInfo` of `TYPE_BLUETOOTH_SCO` -- never
 * appears at all in the HF role on this firmware, even while SCO is verifiably
 * carrying audio. Using it would have inverted into a guaranteed false positive
 * on every healthy session. So detection rests on two signals:
 *
 *  - [FLAP_THRESHOLD] CONNECTED->DISCONNECTED transitions inside
 *    [FLAP_WINDOW_MS] -- the flicker phase, where the AG's eSCO retry ladder
 *    occasionally lands.
 *  - the claim stuck at CONNECTED for [CLAIM_WITHOUT_TRAFFIC_MS] with zero
 *    transitions, confirmed over [DESYNC_CONFIRM_TICKS] ticks -- the fully dead
 *    phase, where nothing succeeds any more.
 *
 * ## Why the limits exist
 *
 * Both recovery tiers necessarily generate the very AUDIO_STATE_CHANGED churn
 * this class watches for, so without care the guard would trigger itself in a
 * loop. [isRecoveryInFlight] freezes all accounting during a tier,
 * [RECOVERY_COOLDOWN_MS] spaces attempts, and the per-epoch caps ([MAX_TIER1],
 * [MAX_TIER2]) bound total disruption. There is deliberately no tier 3: adapter
 * cycling would tear down A2DP Sink for every device and break the multi-source
 * handoff, so a device that survives tier 2 is latched [isUnrecovered] and left
 * alone for the user to decide about.
 */
class ScoSlotPolicy(
    private val clock: () -> Long,
) {

    companion object {
        /** Evaluator period. Only runs while armed, i.e. while SCO is claimed up. */
        const val POLL_MS = 5_000L

        /** Rolling window for counting SCO flaps. */
        const val FLAP_WINDOW_MS = 30_000L

        /**
         * Flaps within [FLAP_WINDOW_MS] that constitute a verdict. The observed
         * symptom is ~2.6s audio / ~1.4s silence, so a genuinely flickering link
         * reaches 4 in well under the window, while an AG legitimately bouncing
         * SCO once or twice (e.g. an iPhone mid-call renegotiation) does not.
         */
        const val FLAP_THRESHOLD = 4

        /**
         * How long the claim may sit at CONNECTED with no transitions at all
         * before it is considered stale. Generous on purpose: a healthy but idle
         * SCO session also produces no transitions, and the cost of being wrong
         * is cutting real audio.
         */
        const val CLAIM_WITHOUT_TRAFFIC_MS = 120_000L

        /** Consecutive stale ticks required before the staleness verdict fires. */
        const val DESYNC_CONFIRM_TICKS = 3

        /** Quiet period after any tier attempt before a new verdict is allowed. */
        const val RECOVERY_COOLDOWN_MS = 60_000L

        /** Attempt caps per address per SCO epoch. */
        const val MAX_TIER1 = 2
        const val MAX_TIER2 = 1

        /**
         * Uninterrupted CONNECTED time that proves the link genuinely recovered
         * and so resets the epoch (and the attempt budget). Without this a wedged
         * device could farm attempts forever by flapping.
         *
         * MUST stay comfortably longer than [RECOVERY_COOLDOWN_MS]. When the two
         * were equal, simply waiting out a cooldown also satisfied the healthy
         * check -- the attempt budget refilled every cycle and the caps stopped
         * bounding anything. Caught by ScoSlotPolicyTest.
         */
        const val EPOCH_HEALTHY_MS = 180_000L

        /**
         * Never act within this long of a ProfileAutoConnector sweep touching the
         * address -- the sweep's own connect churn looks like a flap.
         */
        const val SWEEP_GUARD_MS = 5_000L

        /**
         * Hard bound on how long a recovery may be considered in flight. The
         * settle callback normally clears it, but if that callback is ever
         * dropped (guard stopped mid-tier, handler flushed) the flag would stay
         * true and silently disable the guard forever. This is the backstop.
         */
        const val RECOVERY_EXPIRY_MS = 30_000L
    }

    enum class Action { TIER1, TIER2 }

    data class Decision(val address: String, val action: Action)

    /**
     * External conditions that must all hold before any recovery may run.
     * [msSinceSweepTouched] is a lambda because it is queried per address and
     * only when a verdict is otherwise reached.
     */
    data class Preconditions(
        val folded: Boolean,
        val hasActiveCall: Boolean,
        val adapterOn: Boolean,
        val msSinceSweepTouched: (String) -> Long,
    )

    private class DeviceState {
        /** Timestamps of CONNECTED->DISCONNECTED transitions, pruned to the window. */
        val flaps = ArrayDeque<Long>()
        var audioState: Int = HfpClientController.AUDIO_STATE_DISCONNECTED
        /** When the current uninterrupted CONNECTED claim began; 0 when not connected. */
        var connectedSinceMs: Long = 0
        /** Last time any transition was observed for this address. */
        var lastTransitionMs: Long = 0
        var staleTicks: Int = 0
        var tier1Attempts: Int = 0
        var tier2Attempts: Int = 0
        var lastAttemptMs: Long = 0
        var lastVerdictMs: Long = 0
        var unrecovered: Boolean = false
        /**
         * True once this epoch has actually seen SCO drop. The staleness verdict
         * is gated on it: the fault always flickers before it goes fully silent,
         * whereas a healthy session (a PC mic call) is legitimately quiet for
         * many minutes and must never be cut.
         */
        var sawFlapThisEpoch: Boolean = false
    }

    /**
     * Guarded by [lock]. Ticks arrive on the main looper but healthJson() is
     * called from a binder thread, so every access is synchronized.
     */
    private val devices = LinkedHashMap<String, DeviceState>()

    private val lock = Any()

    private var recoveryInFlight: Boolean = false

    /** When the in-flight recovery started, for [RECOVERY_EXPIRY_MS]. */
    private var recoveryStartedMs: Long = 0

    /**
     * Bumped on every arm/disarm, real transition and tier attempt. Delayed work
     * captures it and no-ops if it changed, mirroring CallController.callGeneration.
     *
     * Deliberately NOT bumped by transitions that arrive while a recovery is in
     * flight: a tier's success signal IS an AUDIO_STATE_CHANGED (leaving AudioOn
     * is what frees the block), so counting it would make the settle callback
     * read every success as "superseded" and score it a failure.
     */
    var generation: Long = 0
        get() = synchronized(lock) { field }
        private set

    private fun state(addr: String) = devices.getOrPut(addr) { DeviceState() }

    fun isArmed(): Boolean = synchronized(lock) {
        devices.values.any { it.audioState == HfpClientController.AUDIO_STATE_CONNECTED }
    }

    fun isRecoveryInFlight(): Boolean = synchronized(lock) {
        expireStuckRecovery(clock())
        recoveryInFlight
    }

    fun isUnrecovered(addr: String): Boolean =
        synchronized(lock) { devices[addr]?.unrecovered == true }

    fun tier1Attempts(addr: String): Int =
        synchronized(lock) { devices[addr]?.tier1Attempts ?: 0 }

    fun tier2Attempts(addr: String): Int =
        synchronized(lock) { devices[addr]?.tier2Attempts ?: 0 }

    fun flapCount(addr: String): Int = synchronized(lock) {
        val s = devices[addr] ?: return 0
        prune(s, clock())
        s.flaps.size
    }

    /**
     * Drop all state and any in-flight recovery. Called when the guard stops, so
     * a stop/start cycle can never leave recoveryInFlight latched true (which
     * would disable the guard permanently).
     */
    fun reset() = synchronized(lock) {
        devices.clear()
        recoveryInFlight = false
        recoveryStartedMs = 0
        generation++
    }

    private fun expireStuckRecovery(now: Long) {
        if (!recoveryInFlight) return
        if (recoveryStartedMs != 0L && (now - recoveryStartedMs) < RECOVERY_EXPIRY_MS) return
        recoveryInFlight = false
        recoveryStartedMs = 0
        generation++
    }

    /**
     * Feed an AUDIO_STATE_CHANGED transition. While a recovery is in flight the
     * caches are updated but the flap counter is NOT, so a tier's own churn can
     * never manufacture the next verdict.
     */
    fun onAudioState(addr: String, newState: Int) = synchronized(lock) {
        if (addr.isEmpty()) return
        val now = clock()
        expireStuckRecovery(now)
        val s = state(addr)
        val prev = s.audioState
        s.audioState = newState

        if (prev == newState) return
        s.lastTransitionMs = now

        // Churn we caused ourselves must not supersede the tier that caused it,
        // nor feed the counters that would justify the next one.
        if (!recoveryInFlight) generation++

        if (!recoveryInFlight &&
            prev == HfpClientController.AUDIO_STATE_CONNECTED &&
            newState == HfpClientController.AUDIO_STATE_DISCONNECTED
        ) {
            s.flaps.addLast(now)
            s.sawFlapThisEpoch = true
            prune(s, now)
        }

        if (newState == HfpClientController.AUDIO_STATE_CONNECTED) {
            if (prev != HfpClientController.AUDIO_STATE_CONNECTED) s.connectedSinceMs = now
        } else {
            s.connectedSinceMs = 0
        }
        // Any transition means the stack is still talking to us, so the staleness
        // streak restarts from zero.
        s.staleTicks = 0
    }

    /** Fold clears everything: a folded device has HFP intentionally down. */
    fun onFold(folded: Boolean) = synchronized(lock) {
        generation++
        if (folded) devices.clear()
    }

    /**
     * Evaluate one tick. Returns the recovery to run, or null for no action.
     * A non-null return marks the recovery as in flight; the caller must report
     * back via [onRecoveryFinished].
     */
    fun evaluate(claim: (String) -> Int, preconditions: Preconditions): Decision? = synchronized(lock) {
        val now = clock()
        expireStuckRecovery(now)
        if (recoveryInFlight) return null
        if (preconditions.folded || preconditions.hasActiveCall || !preconditions.adapterOn) return null

        // Snapshot: claim() calls out into Bluetooth reflection, and a verdict
        // mutates the map, so never iterate the live map here.
        for ((addr, s) in devices.entries.toList()) {
            if (s.unrecovered) continue

            val live = claim(addr)
            // Only a device the stack still claims is in AudioOn can be wedged in
            // the way this guard fixes.
            if (live != HfpClientController.AUDIO_STATE_CONNECTED) {
                s.staleTicks = 0
                continue
            }

            // A long, quiet, flap-free CONNECTED stretch is the definition of a
            // healthy session -- reclaim the attempt budget.
            maybeResetEpoch(s, now)

            prune(s, now)
            val flapVerdict = s.flaps.size >= FLAP_THRESHOLD

            // Staleness only counts once this epoch has seen SCO actually drop.
            // The fault flickers before it dies, so a wedged link always has a
            // flap behind it; a healthy PC mic session is simply quiet for
            // minutes on end and must not be cut. (Phase 0 measured 20s+ of
            // continuous healthy audio with zero transitions.)
            val quietSince = maxOf(s.lastTransitionMs, s.connectedSinceMs)
            val stale = s.sawFlapThisEpoch &&
                quietSince > 0 &&
                (now - quietSince) >= CLAIM_WITHOUT_TRAFFIC_MS
            if (stale) s.staleTicks++ else s.staleTicks = 0
            val staleVerdict = s.staleTicks >= DESYNC_CONFIRM_TICKS

            if (!flapVerdict && !staleVerdict) continue

            if (s.lastAttemptMs != 0L && (now - s.lastAttemptMs) < RECOVERY_COOLDOWN_MS) continue
            if (preconditions.msSinceSweepTouched(addr) < SWEEP_GUARD_MS) continue

            val action = when {
                s.tier1Attempts < MAX_TIER1 -> Action.TIER1
                s.tier2Attempts < MAX_TIER2 -> Action.TIER2
                else -> {
                    // Out of budget: latch and stop. No tier 3 by design.
                    s.unrecovered = true
                    continue
                }
            }

            when (action) {
                Action.TIER1 -> s.tier1Attempts++
                Action.TIER2 -> s.tier2Attempts++
            }
            s.lastAttemptMs = now
            s.lastVerdictMs = now
            s.staleTicks = 0
            s.flaps.clear()
            recoveryInFlight = true
            recoveryStartedMs = now
            generation++
            return Decision(addr, action)
        }
        return null
    }

    /**
     * Tier 1 worked iff the state machine actually left AudioOn -- that is the
     * transition that runs BTM_RemoveSco and frees the leaked block.
     */
    fun tier1Succeeded(claimAfter: Int): Boolean =
        claimAfter == HfpClientController.AUDIO_STATE_DISCONNECTED

    /** Close out a tier. Clears in-flight state and the self-inflicted churn. */
    fun onRecoveryFinished(addr: String, succeeded: Boolean) = synchronized(lock) {
        recoveryInFlight = false
        recoveryStartedMs = 0
        generation++
        val s = devices[addr] ?: return
        s.flaps.clear()
        s.staleTicks = 0
        // Note: a "succeeded" tier only means the leaked block was freed, not
        // that the link is healthy, so the attempt budget is NOT restored here.
        // Only EPOCH_HEALTHY_MS of demonstrably clean audio does that.
        if (!succeeded && s.tier1Attempts >= MAX_TIER1 && s.tier2Attempts >= MAX_TIER2) {
            s.unrecovered = true
        }
    }

    private fun maybeResetEpoch(s: DeviceState, now: Long) {
        if (s.connectedSinceMs == 0L) return
        if ((now - s.connectedSinceMs) < EPOCH_HEALTHY_MS) return
        prune(s, now)
        if (s.flaps.isNotEmpty()) return
        s.tier1Attempts = 0
        s.tier2Attempts = 0
        s.unrecovered = false
        s.lastAttemptMs = 0
        s.sawFlapThisEpoch = false
    }

    private fun prune(s: DeviceState, now: Long) {
        while (s.flaps.isNotEmpty() && (now - s.flaps.first()) > FLAP_WINDOW_MS) {
            s.flaps.removeFirst()
        }
    }

    fun healthJson(): String = synchronized(lock) {
        val now = clock()
        val devs = JSONObject()
        for ((addr, s) in devices.entries.toList()) {
            prune(s, now)
            devs.put(
                addr,
                JSONObject()
                    .put("claim", s.audioState)
                    .put("flaps", s.flaps.size)
                    .put("staleTicks", s.staleTicks)
                    .put("tier1Attempts", s.tier1Attempts)
                    .put("tier2Attempts", s.tier2Attempts)
                    .put("lastVerdictMs", s.lastVerdictMs)
                    .put("unrecovered", s.unrecovered),
            )
        }
        return JSONObject()
            .put("armed", devices.values.any {
                it.audioState == HfpClientController.AUDIO_STATE_CONNECTED
            })
            .put("recoveryInFlight", recoveryInFlight)
            .put("generation", generation)
            .put("devices", devs)
            .toString()
    }
}
