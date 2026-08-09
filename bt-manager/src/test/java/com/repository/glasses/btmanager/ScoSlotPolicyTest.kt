package com.repository.glasses.btmanager

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the SCO slot leak decision logic.
 *
 * ScoSlotPolicy holds every rule that decides *whether* to act; ScoSlotGuard is
 * the thin Android shell that owns timers, reflection and the actual profile
 * calls. Keeping the rules here is what makes them testable at all -- the guard
 * itself needs a live BluetoothHeadsetClient proxy and cannot run on the JVM.
 *
 * Clock is injected so the 30s flap window, the 120s staleness bound and the
 * 60s cooldown can be exercised without sleeping.
 */
class ScoSlotPolicyTest {

    private val PC = "F4:4E:FC:C1:E4:76"
    private val PHONE = "48:7E:25:A1:4F:BF"

    private var now = 1_000_000L
    private fun policy() = ScoSlotPolicy(clock = { now })

    private fun ScoSlotPolicy.connect(addr: String) =
        onAudioState(addr, HfpClientController.AUDIO_STATE_CONNECTED)

    private fun ScoSlotPolicy.disconnect(addr: String) =
        onAudioState(addr, HfpClientController.AUDIO_STATE_DISCONNECTED)

    /** Drive one full CONNECTED -> DISCONNECTED flap taking [ms]. */
    private fun ScoSlotPolicy.flap(addr: String, ms: Long = 1_000L) {
        connect(addr)
        now += ms
        disconnect(addr)
    }

    // ---- arming ----

    @Test
    fun `starts disarmed and evaluates to no action`() {
        val p = policy()
        assertFalse(p.isArmed())
        assertNull(p.evaluate(claim = { -1 }, preconditions = ok()))
    }

    @Test
    fun `arms on audio connected and disarms when all devices disconnect`() {
        val p = policy()
        p.connect(PC)
        assertTrue(p.isArmed())
        p.disconnect(PC)
        assertFalse(p.isArmed())
    }

    @Test
    fun `stays armed while a second device still has audio`() {
        val p = policy()
        p.connect(PC)
        p.connect(PHONE)
        p.disconnect(PC)
        assertTrue("still armed for the phone", p.isArmed())
        p.disconnect(PHONE)
        assertFalse(p.isArmed())
    }

    // ---- flap verdict ----

    @Test
    fun `four flaps inside the window trip a tier1 verdict`() {
        val p = policy()
        repeat(ScoSlotPolicy.FLAP_THRESHOLD) { p.flap(PC) }
        p.connect(PC)
        val a = p.evaluate(claim = { connected() }, preconditions = ok())
        assertEquals(ScoSlotPolicy.Action.TIER1, a?.action)
        assertEquals(PC, a?.address)
    }

    @Test
    fun `three flaps are under the threshold and do not trip`() {
        val p = policy()
        repeat(ScoSlotPolicy.FLAP_THRESHOLD - 1) { p.flap(PC) }
        p.connect(PC)
        assertNull(p.evaluate(claim = { connected() }, preconditions = ok()))
    }

    @Test
    fun `flaps aging out of the window stop counting`() {
        val p = policy()
        repeat(ScoSlotPolicy.FLAP_THRESHOLD - 1) { p.flap(PC) }
        // Push the earlier flaps out of FLAP_WINDOW_MS.
        now += ScoSlotPolicy.FLAP_WINDOW_MS + 1
        p.flap(PC)
        p.connect(PC)
        assertNull("old flaps must not accumulate forever", p.evaluate(claim = { connected() }, preconditions = ok()))
    }

    @Test
    fun `flaps are counted per address, not globally`() {
        val p = policy()
        repeat(ScoSlotPolicy.FLAP_THRESHOLD - 1) { p.flap(PC) }
        repeat(ScoSlotPolicy.FLAP_THRESHOLD - 1) { p.flap(PHONE) }
        p.connect(PC)
        assertNull("two devices must not pool their flaps", p.evaluate(claim = { connected() }, preconditions = ok()))
    }

    // ---- staleness verdict ----

    /**
     * A long quiet CONNECTED session is what a healthy PC mic session looks like
     * (measured in Phase 0: 20s+ of continuous non-silent audio with zero
     * transitions). It must NEVER be diagnosed as wedged. The fault always
     * flickers before it dies, so staleness only counts once this epoch has
     * actually seen SCO drop at least once.
     */
    @Test
    fun `a long quiet healthy session is never diagnosed as stale`() {
        val p = policy()
        p.connect(PC)
        now += ScoSlotPolicy.CLAIM_WITHOUT_TRAFFIC_MS * 10
        repeat(50) {
            assertNull(
                "healthy long session must not be cut",
                p.evaluate(claim = { connected() }, preconditions = ok()),
            )
            now += ScoSlotPolicy.POLL_MS
        }
    }

    @Test
    fun `claim held silently past the staleness bound trips after confirm ticks`() {
        val p = policy()
        // The link flickered first -- this epoch has seen the fault.
        p.flap(PC)
        p.connect(PC)
        now += ScoSlotPolicy.CLAIM_WITHOUT_TRAFFIC_MS + 1
        // Needs DESYNC_CONFIRM_TICKS consecutive confirmations.
        repeat(ScoSlotPolicy.DESYNC_CONFIRM_TICKS - 1) {
            assertNull(p.evaluate(claim = { connected() }, preconditions = ok()))
            now += ScoSlotPolicy.POLL_MS
        }
        val a = p.evaluate(claim = { connected() }, preconditions = ok())
        assertEquals(ScoSlotPolicy.Action.TIER1, a?.action)
    }

    @Test
    fun `claim held for less than the staleness bound never trips`() {
        val p = policy()
        p.flap(PC)
        p.connect(PC)
        // Tick right up to, but never past, the bound.
        while (now - 1_000_000L < ScoSlotPolicy.CLAIM_WITHOUT_TRAFFIC_MS - ScoSlotPolicy.POLL_MS) {
            assertNull(p.evaluate(claim = { connected() }, preconditions = ok()))
            now += ScoSlotPolicy.POLL_MS
        }
    }

    @Test
    fun `traffic resets the staleness timer`() {
        val p = policy()
        p.flap(PC)
        p.connect(PC)
        now += ScoSlotPolicy.CLAIM_WITHOUT_TRAFFIC_MS - 1
        // A real transition arrives -- the link is alive, not wedged.
        p.disconnect(PC)
        p.connect(PC)
        now += 10
        repeat(ScoSlotPolicy.DESYNC_CONFIRM_TICKS + 1) {
            assertNull(p.evaluate(claim = { connected() }, preconditions = ok()))
            now += ScoSlotPolicy.POLL_MS
        }
    }

    @Test
    fun `confirm ticks must be consecutive`() {
        val p = policy()
        p.flap(PC)
        p.connect(PC)
        now += ScoSlotPolicy.CLAIM_WITHOUT_TRAFFIC_MS + 1
        p.evaluate(claim = { connected() }, preconditions = ok())
        now += ScoSlotPolicy.POLL_MS
        // Claim briefly drops -> the stack is NOT stuck in AudioOn, streak resets.
        p.evaluate(claim = { HfpClientController.AUDIO_STATE_DISCONNECTED }, preconditions = ok())
        now += ScoSlotPolicy.POLL_MS
        assertNull(p.evaluate(claim = { connected() }, preconditions = ok()))
    }

    // ---- preconditions ----

    @Test
    fun `never acts during an active call`() {
        val p = policy()
        repeat(ScoSlotPolicy.FLAP_THRESHOLD) { p.flap(PC) }
        p.connect(PC)
        val pre = ok().copy(hasActiveCall = true)
        assertNull("a real call must never be interrupted", p.evaluate(claim = { connected() }, preconditions = pre))
    }

    @Test
    fun `never acts while folded`() {
        val p = policy()
        repeat(ScoSlotPolicy.FLAP_THRESHOLD) { p.flap(PC) }
        p.connect(PC)
        val pre = ok().copy(folded = true)
        assertNull(p.evaluate(claim = { connected() }, preconditions = pre))
    }

    @Test
    fun `never acts while the adapter is off`() {
        val p = policy()
        repeat(ScoSlotPolicy.FLAP_THRESHOLD) { p.flap(PC) }
        p.connect(PC)
        val pre = ok().copy(adapterOn = false)
        assertNull(p.evaluate(claim = { connected() }, preconditions = pre))
    }

    @Test
    fun `never acts right after an autoconnect sweep touched the address`() {
        val p = policy()
        repeat(ScoSlotPolicy.FLAP_THRESHOLD) { p.flap(PC) }
        p.connect(PC)
        val pre = ok().copy(msSinceSweepTouched = { 1_000L })
        assertNull(p.evaluate(claim = { connected() }, preconditions = pre))
    }

    @Test
    fun `acts once the sweep guard window has elapsed`() {
        val p = policy()
        repeat(ScoSlotPolicy.FLAP_THRESHOLD) { p.flap(PC) }
        p.connect(PC)
        val pre = ok().copy(msSinceSweepTouched = { ScoSlotPolicy.SWEEP_GUARD_MS + 1 })
        assertEquals(ScoSlotPolicy.Action.TIER1, p.evaluate(claim = { connected() }, preconditions = pre)?.action)
    }

    @Test
    fun `folding clears all per-address state`() {
        val p = policy()
        repeat(ScoSlotPolicy.FLAP_THRESHOLD) { p.flap(PC) }
        p.connect(PC)
        p.onFold(true)
        assertFalse(p.isArmed())
        p.onFold(false)
        p.connect(PC)
        assertNull("flaps from before the fold must not survive it",
            p.evaluate(claim = { connected() }, preconditions = ok()))
    }

    // ---- escalation, caps, cooldown ----

    @Test
    fun `escalates to tier2 only after tier1 has failed its cap`() {
        val p = policy()
        val pre = ok()
        repeat(ScoSlotPolicy.FLAP_THRESHOLD) { p.flap(PC) }
        p.connect(PC)

        assertEquals(ScoSlotPolicy.Action.TIER1, p.evaluate(claim = { connected() }, preconditions = pre)?.action)
        p.onRecoveryFinished(PC, succeeded = false)
        now += ScoSlotPolicy.RECOVERY_COOLDOWN_MS + 1
        reinduce(p)

        assertEquals(ScoSlotPolicy.Action.TIER1, p.evaluate(claim = { connected() }, preconditions = pre)?.action)
        p.onRecoveryFinished(PC, succeeded = false)
        now += ScoSlotPolicy.RECOVERY_COOLDOWN_MS + 1
        reinduce(p)

        assertEquals("tier1 cap reached -> escalate",
            ScoSlotPolicy.Action.TIER2, p.evaluate(claim = { connected() }, preconditions = pre)?.action)
    }

    /**
     * Re-create the symptom after a failed recovery. A tier clears the flap
     * counter (its own churn must not count), so a device that is still wedged
     * has to flap again before the next verdict -- which is exactly what a real
     * wedged AG does as it keeps retrying its eSCO ladder.
     */
    private fun reinduce(p: ScoSlotPolicy) {
        repeat(ScoSlotPolicy.FLAP_THRESHOLD) { p.flap(PC) }
        p.connect(PC)
    }

    @Test
    fun `gives up after tier2 and never acts again for that epoch`() {
        val p = policy()
        val pre = ok()
        repeat(ScoSlotPolicy.FLAP_THRESHOLD) { p.flap(PC) }
        p.connect(PC)
        repeat(ScoSlotPolicy.MAX_TIER1 + ScoSlotPolicy.MAX_TIER2) {
            p.evaluate(claim = { connected() }, preconditions = pre)
            p.onRecoveryFinished(PC, succeeded = false)
            now += ScoSlotPolicy.RECOVERY_COOLDOWN_MS + 1
            reinduce(p)
        }
        assertNull("no tier 3, ever", p.evaluate(claim = { connected() }, preconditions = pre))
        assertTrue(p.isUnrecovered(PC))
        // And it must stay silent no matter how long it keeps failing.
        repeat(20) {
            now += ScoSlotPolicy.RECOVERY_COOLDOWN_MS + 1
            reinduce(p)
            assertNull(p.evaluate(claim = { connected() }, preconditions = pre))
        }
    }

    @Test
    fun `cooldown blocks a second attempt inside the window`() {
        val p = policy()
        val pre = ok()
        repeat(ScoSlotPolicy.FLAP_THRESHOLD) { p.flap(PC) }
        p.connect(PC)
        assertEquals(ScoSlotPolicy.Action.TIER1, p.evaluate(claim = { connected() }, preconditions = pre)?.action)
        p.onRecoveryFinished(PC, succeeded = false)
        now += ScoSlotPolicy.RECOVERY_COOLDOWN_MS - 1
        assertNull("still cooling down", p.evaluate(claim = { connected() }, preconditions = pre))
    }

    @Test
    fun `recovery in flight suppresses verdicts and self-inflicted flaps`() {
        val p = policy()
        val pre = ok()
        repeat(ScoSlotPolicy.FLAP_THRESHOLD) { p.flap(PC) }
        p.connect(PC)
        assertEquals(ScoSlotPolicy.Action.TIER1, p.evaluate(claim = { connected() }, preconditions = pre)?.action)
        assertTrue(p.isRecoveryInFlight())
        // The tier's own disconnectAudio churn must not feed the counters.
        repeat(10) { p.flap(PC) }
        assertNull(p.evaluate(claim = { connected() }, preconditions = pre))
        p.onRecoveryFinished(PC, succeeded = false)
        now += ScoSlotPolicy.RECOVERY_COOLDOWN_MS + 1
        // The self-inflicted flaps must not have counted toward a new verdict.
        assertEquals(0, p.flapCount(PC))
    }

    @Test
    fun `a verified healthy session resets the epoch and restores the attempt budget`() {
        val p = policy()
        val pre = ok()
        repeat(ScoSlotPolicy.FLAP_THRESHOLD) { p.flap(PC) }
        p.connect(PC)
        p.evaluate(claim = { connected() }, preconditions = pre)
        p.onRecoveryFinished(PC, succeeded = true)

        // Healthy: connected and quiet for EPOCH_HEALTHY_MS.
        p.disconnect(PC)
        p.connect(PC)
        now += ScoSlotPolicy.EPOCH_HEALTHY_MS + 1
        p.evaluate(claim = { connected() }, preconditions = pre)
        assertEquals(0, p.tier1Attempts(PC))
        assertFalse(p.isUnrecovered(PC))
    }

    @Test
    fun `a wedged device cannot farm attempts by flapping`() {
        val p = policy()
        val pre = ok()
        repeat(ScoSlotPolicy.MAX_TIER1 + ScoSlotPolicy.MAX_TIER2 + 20) {
            reinduce(p)
            p.evaluate(claim = { connected() }, preconditions = pre)
            p.onRecoveryFinished(PC, succeeded = false)
            // A wedged device keeps flapping and keeps outliving cooldowns; the
            // caps, not the passage of time, must be what stops the guard.
            now += ScoSlotPolicy.RECOVERY_COOLDOWN_MS + 1
        }
        assertEquals(ScoSlotPolicy.MAX_TIER1, p.tier1Attempts(PC))
        assertEquals(ScoSlotPolicy.MAX_TIER2, p.tier2Attempts(PC))
    }

    @Test
    fun `tier1 success is judged by the claim leaving AudioOn`() {
        val p = policy()
        assertTrue(p.tier1Succeeded(HfpClientController.AUDIO_STATE_DISCONNECTED))
        assertFalse(p.tier1Succeeded(HfpClientController.AUDIO_STATE_CONNECTED))
    }

    /**
     * Tier 1 works by driving the state machine out of AudioOn -- so the success
     * signal IS an AUDIO_STATE_CHANGED. If that transition bumped the generation,
     * the settle callback would read it as "superseded" and score every SUCCESS
     * as a failure, burning the attempt budget on a device it just fixed.
     */
    @Test
    fun `the transition tier1 itself causes does not bump the generation`() {
        val p = policy()
        repeat(ScoSlotPolicy.FLAP_THRESHOLD) { p.flap(PC) }
        p.connect(PC)
        p.evaluate(claim = { connected() }, preconditions = ok())
        val gen = p.generation
        p.disconnect(PC)
        assertEquals("in-flight churn must not supersede its own tier", gen, p.generation)
    }

    @Test
    fun `a real transition outside a recovery does bump the generation`() {
        val p = policy()
        p.connect(PC)
        val gen = p.generation
        p.disconnect(PC)
        assertTrue(p.generation != gen)
    }

    @Test
    fun `reset clears a stuck in-flight recovery`() {
        val p = policy()
        repeat(ScoSlotPolicy.FLAP_THRESHOLD) { p.flap(PC) }
        p.connect(PC)
        p.evaluate(claim = { connected() }, preconditions = ok())
        assertTrue(p.isRecoveryInFlight())
        // Guard stopped mid-tier: the settle callback is dropped and would never
        // clear the flag, silently disabling the guard forever.
        p.reset()
        assertFalse(p.isRecoveryInFlight())
        assertFalse(p.isArmed())
    }

    @Test
    fun `an abandoned recovery expires instead of wedging the guard`() {
        val p = policy()
        val pre = ok()
        repeat(ScoSlotPolicy.FLAP_THRESHOLD) { p.flap(PC) }
        p.connect(PC)
        p.evaluate(claim = { connected() }, preconditions = pre)
        assertTrue(p.isRecoveryInFlight())
        // onRecoveryFinished never arrives (dropped callback, crash, etc).
        now += ScoSlotPolicy.RECOVERY_EXPIRY_MS + 1
        assertFalse("in-flight must self-expire", p.isRecoveryInFlight())
        // Wait out the cooldown BEFORE re-inducing, so the fresh flaps are still
        // inside FLAP_WINDOW_MS when the verdict is evaluated.
        now += ScoSlotPolicy.RECOVERY_COOLDOWN_MS + 1
        reinduce(p)
        assertEquals(ScoSlotPolicy.Action.TIER1,
            p.evaluate(claim = { connected() }, preconditions = pre)?.action)
    }

    @Test
    fun `healthJson is safe to call concurrently with ticks`() {
        val p = policy()
        // getScoHealthJson() arrives on a binder thread while the guard ticks on
        // the main looper; iterating devices while pruning must not blow up.
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val err = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val reader = Thread {
            try { while (!stop.get()) p.healthJson() } catch (t: Throwable) { err.set(t) }
        }
        reader.start()
        try {
            repeat(2_000) {
                now += 100
                p.flap(PC)
                p.connect(PHONE)
                p.evaluate(claim = { connected() }, preconditions = ok())
            }
        } finally {
            stop.set(true); reader.join(5_000)
        }
        assertNull("healthJson raced with the tick: ${err.get()}", err.get())
    }

    // ---- reporting ----

    @Test
    fun `healthJson reports per address counters`() {
        val p = policy()
        repeat(ScoSlotPolicy.FLAP_THRESHOLD) { p.flap(PC) }
        p.connect(PC)
        p.evaluate(claim = { connected() }, preconditions = ok())
        p.onRecoveryFinished(PC, succeeded = false)

        val root = JSONObject(p.healthJson())
        assertTrue(root.getBoolean("armed"))
        val dev = root.getJSONObject("devices").getJSONObject(PC)
        assertEquals(1, dev.getInt("tier1Attempts"))
        assertEquals(0, dev.getInt("tier2Attempts"))
        assertFalse(dev.getBoolean("unrecovered"))
    }

    @Test
    fun `healthJson is valid when nothing has happened`() {
        val root = JSONObject(policy().healthJson())
        assertFalse(root.getBoolean("armed"))
        assertEquals(0, root.getJSONObject("devices").length())
    }

    // ---- helpers ----

    private fun connected() = HfpClientController.AUDIO_STATE_CONNECTED

    private fun ok() = ScoSlotPolicy.Preconditions(
        folded = false,
        hasActiveCall = false,
        adapterOn = true,
        msSinceSweepTouched = { Long.MAX_VALUE },
    )
}
