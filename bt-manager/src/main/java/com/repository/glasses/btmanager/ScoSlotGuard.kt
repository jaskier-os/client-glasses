package com.repository.glasses.btmanager

import android.bluetooth.BluetoothAdapter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Watchdog that clears a leaked Fluoride SCO control block without a reboot.
 *
 * All the "should we act" rules live in [ScoSlotPolicy] (pure, unit tested).
 * This class owns only the parts that need Android: the tick timer, the
 * BluetoothHeadsetClient calls, and the settle-and-check sequencing.
 *
 * Cost when idle is zero: the timer only runs while the HF proxy claims audio is
 * up, which is exactly when SCO already holds the stack awake, so this adds no
 * wakeups to the idle power floor.
 *
 * See ScoSlotPolicy's docs and docs/plans/2026-08-09-hfp-sco-slot-leak.md for the
 * fault itself and the Phase 0 measurements that shaped the detection signals.
 */
class ScoSlotGuard(
    private val adapter: BluetoothAdapter?,
    private val hfp: HfpClientController,
    private val autoConnector: ProfileAutoConnector,
    private val foldGate: FoldGate,
    private val log: (String) -> Unit = {},
) {

    companion object {
        /** How long to let the stack settle after a tier before judging it. */
        private const val TIER_SETTLE_MS = 3_000L

        /** Gap between disconnecting HFP and asking for it back in tier 2. */
        private const val HFP_RECONNECT_DELAY_MS = 1_500L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val policy = ScoSlotPolicy(clock = { SystemClock.elapsedRealtime() })

    @Volatile private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try { evaluate() } catch (e: Throwable) {
                log("ScoSlotGuard tick threw: ${e.message}")
            }
            if (running) handler.postDelayed(this, ScoSlotPolicy.POLL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.postDelayed(tick, ScoSlotPolicy.POLL_MS)
        log("event=sco_guard.start poll_ms=${ScoSlotPolicy.POLL_MS}")
    }

    fun stop() {
        running = false
        // This drops any pending settle callback, which is the only thing that
        // would have cleared recoveryInFlight -- so clear it here, or a
        // stop/start cycle leaves the guard permanently disabled.
        handler.removeCallbacksAndMessages(null)
        policy.reset()
        log("event=sco_guard.stop")
    }

    /** Feed from HfpClientController.handleAudioStateChanged. */
    fun onAudioState(addr: String, newState: Int) {
        policy.onAudioState(addr, newState)
    }

    fun onFold(folded: Boolean) {
        policy.onFold(folded)
        if (folded) log("event=sco_guard.fold_cleared")
    }

    fun healthJson(): String = policy.healthJson()

    private fun evaluate() {
        if (!policy.isArmed()) return

        val pre = ScoSlotPolicy.Preconditions(
            folded = foldGate.folded,
            hasActiveCall = hfp.hasActiveCall(),
            adapterOn = adapter?.isEnabled == true,
            msSinceSweepTouched = { autoConnector.msSinceSweepTouched(it) },
        )

        val decision = policy.evaluate(
            claim = { hfp.liveAudioState(it) },
            preconditions = pre,
        ) ?: return

        val gen = policy.generation
        log(
            "event=sco_guard.verdict addr=${decision.address} action=${decision.action} " +
                "flaps=${policy.flapCount(decision.address)} " +
                "tier1=${policy.tier1Attempts(decision.address)} " +
                "tier2=${policy.tier2Attempts(decision.address)}"
        )

        when (decision.action) {
            ScoSlotPolicy.Action.TIER1 -> runTier1(decision.address, gen)
            ScoSlotPolicy.Action.TIER2 -> runTier2(decision.address, gen)
        }
    }

    /**
     * Tier 1: drive the state machine out of AudioOn. That runs
     * BTA_HfClientAudioClose -> bta_hf_client_sco_close -> BTM_RemoveSco, which
     * frees the leaked block and resets sco_idx; the HF client then re-arms a
     * LISTENING block itself and the AG's next retry (~1.4s on the PC) lands.
     */
    private fun runTier1(addr: String, gen: Long) {
        val ok = hfp.disconnectAudio(addr)
        log("event=sco_guard.tier1 addr=$addr disconnectAudio=$ok")
        handler.postDelayed({
            if (!running || policy.generation != gen) {
                // Superseded by a real transition or another attempt; the newer
                // path owns the outcome. Still clear in-flight so we don't wedge
                // the guard itself.
                policy.onRecoveryFinished(addr, succeeded = false)
                return@postDelayed
            }
            val after = hfp.liveAudioState(addr)
            val succeeded = policy.tier1Succeeded(after)
            log("event=sco_guard.tier1_result addr=$addr claim_after=$after succeeded=$succeeded")
            policy.onRecoveryFinished(addr, succeeded)
        }, TIER_SETTLE_MS)
    }

    /**
     * Tier 2: transiently drop the HFP profile for this device only. Service
     * close runs bta_hf_client_sco_shutdown, which releases every index the scb
     * owns regardless of whether the disconnect-complete was ever mapped back.
     *
     * Uses disconnectTransient (NOT disconnect) so the connection policy stays
     * ALLOWED -- forbidding it would block both our reconnect and the AG's.
     * A2DP Sink + AVRCP keep the ACL up throughout, so this does not disturb the
     * multi-source A2DP handoff.
     */
    private fun runTier2(addr: String, gen: Long) {
        val ok = hfp.disconnectTransient(addr)
        log("event=sco_guard.tier2 addr=$addr disconnectTransient=$ok")
        handler.postDelayed({
            if (!running || policy.generation != gen) {
                policy.onRecoveryFinished(addr, succeeded = false)
                return@postDelayed
            }
            // Funnel the reconnect through ProfileAutoConnector so all HFP
            // connect attempts stay in one place and its bookkeeping stays
            // coherent (no double connect racing the 60s sweep).
            autoConnector.requestHfpReconnect(addr)
            handler.postDelayed({
                if (!running || policy.generation != gen) {
                    policy.onRecoveryFinished(addr, succeeded = false)
                    return@postDelayed
                }
                // Tier 2 worked iff HFP came back AND the stack is no longer
                // claiming a phantom AudioOn. Accepting "either" made this
                // true in essentially every outcome, so the give-up latch
                // never announced itself.
                val after = hfp.liveAudioState(addr)
                val reconnected = hfp.isConnected(addr)
                val succeeded = reconnected &&
                    after != HfpClientController.AUDIO_STATE_CONNECTED
                log(
                    "event=sco_guard.tier2_result addr=$addr claim_after=$after " +
                        "hfp_connected=$reconnected succeeded=$succeeded"
                )
                policy.onRecoveryFinished(addr, succeeded)
                if (policy.isUnrecovered(addr)) {
                    log("event=hfp.sco_slot_leak_unrecovered addr=$addr")
                }
            }, TIER_SETTLE_MS)
        }, HFP_RECONNECT_DELAY_MS)
    }
}
