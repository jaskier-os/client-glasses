package com.repository.glasses.listener.bt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.repository.glasses.listener.MainActivity
import com.repository.glasses.listener.R
import com.repository.glasses.listener.audio.TtsPlayer
import com.repository.glasses.listener.service.ListenerService
import com.repository.glasses.listener.ui.CallOverlay
import com.repository.glasses.tracing.GT
import org.json.JSONObject

/**
 * High-level state machine for HFP incoming/ongoing calls on the glasses.
 *
 * HFP call state constants (match AOSP BluetoothHeadsetClientCall):
 *   INCOMING=4, ACTIVE=0, WAITING=5, HELD=1, DIALING=2, ALERTING=3, TERMINATED=7.
 * Audio state: DISCONNECTED=0, CONNECTED=1, CONNECTING=2.
 *
 * Reacts to BtManagerBridge.CallListener callbacks, drives CallOverlay for
 * incoming-call UI, controls LED ring pattern via Rokid lights_ctrl, pauses
 * TTS on answer, broadcasts ACTION_CALL_UI_STATE so MainActivity can adjust
 * focus + show/hide bottom-bar indicators (call duration, HF mic-mute icon).
 * Also owns HF mic mute toggle (driven by touchpad hold-tap during any
 * active SCO session, including PC-as-AG cases without an HFP call event).
 */
class CallController : BtManagerBridge.CallListener {

    enum class CallPhase { IDLE, INCOMING, OUTGOING_DIALING, ACTIVE, ENDING }

    companion object {
        private const val TAG = "App:Call"

        // Rokid PsensorObserver leg fold/unfold broadcast (glasses_leg_state
        // "1"=spread/unfolded, "0"=folded). Mirrors FoldGate / the listener's
        // nativeLegReceiver.
        private const val ACTION_LEG_STATUS_CHANGED =
            "com.rokid.sprite.ACTION_LEG_STATUS_CHANGED"

        // HFP call states
        private const val STATE_ACTIVE = 0
        private const val STATE_HELD = 1
        private const val STATE_DIALING = 2
        private const val STATE_ALERTING = 3
        private const val STATE_INCOMING = 4
        private const val STATE_WAITING = 5
        private const val STATE_TERMINATED = 7

        // Audio states. AOSP BluetoothHeadsetClient defines STATE_AUDIO_*
        // as DISCONNECTED=0, CONNECTING=1, CONNECTED=2.
        private const val AUDIO_DISCONNECTED = 0
        private const val AUDIO_CONNECTING = 1
        private const val AUDIO_CONNECTED = 2

        // Rokid lights_ctrl PHONE_RING event
        private const val LIGHTS_EVENT_TYPE_SPECIAL = 3
        private const val LIGHTS_EVENT_PHONE_RING = 3017

        // Grace period before an SCO drop during an ACTIVE call is treated as a
        // real call end. Covers the typical iPhone mid-call SCO bounce without
        // materially delaying real call-end detection.
        private const val SCO_DROP_GRACE_MS = 2500L
    }

    private var bridge: BtManagerBridge? = null
    private var overlay: CallOverlay? = null
    private var ttsPlayer: TtsPlayer? = null
    private var ctx: Context? = null
    private var remoteLog: ((String) -> Unit)? = null
    private var ringtonePlayer: MediaPlayer? = null
    @Volatile private var contactsCache: ContactsCache? = null
    @Volatile private var activeAgMac: String = ""

    /**
     * Wire the contacts cache that should be consulted when an HFP call arrives
     * with an empty name field (AOSP HFP profile only carries the number). The
     * AG identifier scopes the lookup so multiple paired phones don't collide.
     */
    fun setActiveAg(agMac: String, cache: ContactsCache) {
        activeAgMac = agMac
        contactsCache = cache
        try { cache.loadIntoMemory(agMac) } catch (_: Exception) {}
    }

    /**
     * Fold state as tracked by this controller. Drives whether incoming-call UI
     * surfaces: unfolded = show full UX, folded = suppress (glasses are put away).
     * Default unfolded so before the first fold signal we show the full UX.
     */
    @Volatile private var folded: Boolean = false

    @Volatile var phase: CallPhase = CallPhase.IDLE
        private set
    @Volatile var currentCallId: Int = -1
        private set
    @Volatile var currentAddr: String = ""
        private set
    @Volatile var currentNumber: String = ""
        private set
    @Volatile var currentName: String = ""
        private set
    @Volatile var startedElapsedRealtime: Long = 0L
        private set
    @Volatile var scoActive: Boolean = false
        private set
    @Volatile var micMuted: Boolean = false
        private set

    // Dedupe keys
    private var lastCallKey: Long = -1L           // (callId << 8) | state
    private var lastAudioKey: String = ""         // addr|state

    // SCO-drop debounce. The iPhone's SCO genuinely bounces mid-call (SCO
    // connect/disconnect), and each ACTIVE->IDLE flip tears down the far-party
    // call-audio tap and flips the phone's translation sub-source mid-sentence.
    // On an SCO drop while ACTIVE we post enterIdle("sco-dropped") delayed by
    // SCO_DROP_GRACE_MS; an SCO reconnect within the window cancels it so a brief
    // blip does not flap the tap. A real hangup delivers AG STATE_TERMINATED ->
    // enterIdle("terminated") which cancels the pending runnable and tears down
    // immediately, so the debounce can only ever delay a spurious blip -- never
    // keep the tap alive past a genuine hangup. Runs on the main looper so the
    // delayed transition is serialized with the other phase transitions.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingScoDrop: Runnable? = null
    // Bumped on every phase transition; captured when a drop is posted and
    // re-checked when it fires so a stale runnable that was dequeued just before a
    // new call went ACTIVE cannot kill the live tap. Volatile: bumped on the binder
    // callback thread (phase transitions) and read on the main looper (the delayed
    // runnable), so it needs a cross-thread happens-before and atomic 64-bit access.
    @Volatile private var callGeneration: Long = 0L

    // Anti-thrash latch for the far-party call-audio tap. The iPhone AG re-emits
    // DIALING/ALERTING (and sometimes INCOMING/WAITING) for a call that is already
    // connected, which would bounce phase ACTIVE -> OUTGOING_DIALING and flap the tap
    // mid-utterance. A connected call never legitimately returns to a pre-active state,
    // so once a callId has reached ACTIVE we record it here and ignore backward
    // transitions for that same callId until the call really ends. Set when phase
    // becomes ACTIVE (both enterActive() and the resync path) for a known callId;
    // cleared in reset(), which every path to IDLE runs -- so the latch can never
    // outlive the call. Value -1 means "no active call latched". Read on the binder
    // callback thread; @Volatile for visibility with the main-looper reads elsewhere.
    @Volatile private var activeLatchedCallId: Int = -1

    /** Latch the current call as connected so backward transitions for its id are
     *  ignored. Only latches a known id; a -1 (AG omitted the id) is left unlatched. */
    private fun latchActiveCall() {
        if (currentCallId >= 0) activeLatchedCallId = currentCallId
    }

    private fun cancelPendingScoDrop() {
        pendingScoDrop?.let {
            mainHandler.removeCallbacks(it)
            pendingScoDrop = null
            Log.i(TAG, "event=sco_drop_cancelled gen=$callGeneration")
        }
    }

    fun start(
        bridge: BtManagerBridge,
        overlay: CallOverlay,
        ttsPlayer: TtsPlayer,
        ctx: Context,
        remoteLog: ((String) -> Unit)? = null
    ) {
        this.bridge = bridge
        this.overlay = overlay
        this.ttsPlayer = ttsPlayer
        this.ctx = ctx.applicationContext
        this.remoteLog = remoteLog
        Log.i(TAG, "event=controller_start")
        log("CallController started")

        // Seed fold state from the property so the first decision is correct
        // even before any broadcast arrives.
        folded = readFoldProperty() ?: false
        Log.i(TAG, "event=fold_initial folded=$folded")

        try {
            ctx.applicationContext.registerReceiver(
                foldReceiver,
                IntentFilter(ACTION_LEG_STATUS_CHANGED),
                Context.RECEIVER_EXPORTED,
            )
            log("fold receiver registered ($ACTION_LEG_STATUS_CHANGED)")
        } catch (t: Throwable) {
            log("fold receiver register failed: ${t.message}")
        }
    }

    fun stop() {
        hideLed()
        stopRingtone()
        overlay?.hide()
        reset()
        try { ctx?.unregisterReceiver(foldReceiver) } catch (_: Exception) {}
        bridge = null
        overlay = null
        ttsPlayer = null
        ctx = null
        Log.i(TAG, "event=controller_stop")
        log("CallController stopped")
    }

    /**
     * Called by ListenerService via the BtManagerBridge addOnBoundListener hook.
     * Queries the current call snapshot and aligns the glasses UI with it.
     */
    fun onBtManagerBound() {
        Log.i(TAG, "event=bt_bound_resync phase=$phase folded=$folded")
        resyncFromSnapshot("bt_bound")
        // Always re-emit UI state on bind. When MainActivity restarts (config
        // change, process recreated) it registers callUiStateReceiver fresh and
        // would otherwise miss the indicator state until the next snapshot
        // change. resyncFromSnapshot only broadcasts on transitions; force one
        // here so a re-bound UI sees current micMuted/scoActive immediately.
        broadcastUiState()
    }

    // ---- Public control ----

    fun accept(): Boolean {
        Log.i(TAG, "event=action_accept_entry phase=$phase id=$currentCallId")
        val b = bridge ?: run {
            Log.w(TAG, "event=action_accept_fail reason=no_bridge")
            return false
        }
        val addr = resolveAddr()
        if (addr.isEmpty()) {
            Log.w(TAG, "event=action_accept_fail reason=no_hfp_device")
            log("accept: no HFP device")
            return false
        }
        Log.i(TAG, "event=action_accept_invoke addr=$addr id=$currentCallId")
        log("accept addr=$addr")
        val ok = b.acceptCall(addr, 0)
        Log.i(TAG, "event=action_accept_result ok=$ok addr=$addr")
        return ok
    }

    fun decline(): Boolean {
        Log.i(TAG, "event=action_decline_entry phase=$phase id=$currentCallId")
        val b = bridge ?: run {
            Log.w(TAG, "event=action_decline_fail reason=no_bridge")
            return false
        }
        val addr = resolveAddr()
        if (addr.isEmpty()) {
            Log.w(TAG, "event=action_decline_fail reason=no_hfp_device")
            log("decline: no HFP device")
            return false
        }
        Log.i(TAG, "event=action_decline_invoke addr=$addr id=$currentCallId")
        log("decline addr=$addr")
        val ok = b.rejectCall(addr)
        Log.i(TAG, "event=action_decline_result ok=$ok addr=$addr")
        return ok
    }

    /**
     * Toggle the HF microphone mute on the AG side. Only valid in CallPhase.ACTIVE;
     * silently no-ops otherwise so a stray hold-tap during INCOMING / IDLE doesn't
     * accidentally arm a mute that nobody asked for. UI state lives here -- the
     * indicator subscribes via the ACTION_CALL_UI_STATE broadcast.
     */
    fun toggleHfMicMute(): Boolean {
        // Live-refresh from bt-manager so we don't miss SCO sessions that came
        // up before our broadcast receiver registered (e.g. PC AG already
        // streaming when the listener bound, no AG_CALL_CHANGED ever fires).
        try { resyncFromSnapshot("mute_toggle_entry") } catch (e: Exception) {
            log("mute toggle resync failed: ${e.message}")
        }
        Log.i(TAG, "event=action_mute_toggle_entry phase=$phase sco=$scoActive muted=$micMuted")
        // Gate on actual HF audio activity, not call phase. When the AG is a PC
        // (or anything that brings up SCO without sending AG_CALL_CHANGED), phase
        // stays IDLE forever -- but the wearer is clearly speaking and wants to
        // mute. scoActive is the real "mic is hot" signal here.
        if (!scoActive && phase != CallPhase.ACTIVE) {
            Log.i(TAG, "event=action_mute_toggle_skip reason=no_hf_audio phase=$phase sco=$scoActive")
            return false
        }
        val b = bridge ?: run {
            Log.w(TAG, "event=action_mute_toggle_fail reason=no_bridge")
            return false
        }
        val addr = resolveAddr()
        if (addr.isEmpty()) {
            Log.w(TAG, "event=action_mute_toggle_fail reason=no_hfp_device")
            return false
        }
        val next = !micMuted
        Log.i(TAG, "event=action_mute_toggle_invoke addr=$addr next=$next")
        log("toggleHfMicMute addr=$addr next=$next")
        val ok = b.setHfMicMute(addr, next)
        Log.i(TAG, "event=action_mute_toggle_result ok=$ok addr=$addr next=$next")
        if (ok) {
            micMuted = next
            broadcastUiState()
        }
        return ok
    }

    fun terminateActive(): Boolean {
        Log.i(TAG, "event=action_terminate_entry phase=$phase id=$currentCallId")
        val b = bridge ?: run {
            Log.w(TAG, "event=action_terminate_fail reason=no_bridge")
            return false
        }
        val addr = resolveAddr()
        if (addr.isEmpty() || currentCallId < 0) {
            Log.w(TAG, "event=action_terminate_fail reason=no_active addr='$addr' id=$currentCallId")
            log("terminateActive: no active call (addr='$addr' id=$currentCallId)")
            return false
        }
        Log.i(TAG, "event=action_terminate_invoke addr=$addr id=$currentCallId")
        log("terminateActive addr=$addr id=$currentCallId")
        val ok = b.terminateCall(addr, currentCallId)
        Log.i(TAG, "event=action_terminate_result ok=$ok addr=$addr id=$currentCallId")
        return ok
    }

    private fun resolveAddr(): String {
        if (currentAddr.isNotEmpty()) return currentAddr
        val b = bridge ?: return ""
        return b.getPrimaryHfpDeviceAddress()
    }

    // ---- BtManagerBridge.CallListener ----

    override fun onCallStateChanged(
        deviceAddress: String,
        callId: Int,
        uuid: String,
        state: Int,
        number: String,
        name: String,
        multiParty: Boolean,
        outgoing: Boolean
    ) = GT.section("audio.hfp.call_state") {
        GT.counter("audio.hfp.call_active", if (state == STATE_ACTIVE) 1L else 0L)
        val key = ((callId.toLong() and 0xFFFFFF) shl 8) or (state.toLong() and 0xFF)
        if (key == lastCallKey) {
            Log.d(TAG, "event=call_state_dedup addr=$deviceAddress id=$callId state=$state")
            return@section
        }
        lastCallKey = key

        val stateName = when (state) {
            STATE_ACTIVE -> "ACTIVE"
            STATE_HELD -> "HELD"
            STATE_DIALING -> "DIALING"
            STATE_ALERTING -> "ALERTING"
            STATE_INCOMING -> "INCOMING"
            STATE_WAITING -> "WAITING"
            STATE_TERMINATED -> "TERMINATED"
            else -> "UNKNOWN($state)"
        }
        Log.i(TAG, "event=call_state addr=$deviceAddress id=$callId state=$stateName outgoing=$outgoing multiParty=$multiParty hasName=${name.isNotEmpty()} hasNum=${number.isNotEmpty()}")
        log("onCallStateChanged addr=$deviceAddress id=$callId state=$state num='$number' name='$name' out=$outgoing")

        // Drop spurious events from secondary AGs. Symptom: when two phones are
        // paired, both send AG_CALL_CHANGED for the same call. The wrong AG
        // arrives with a bogus number (hidden-API hashed toString() value like
        // "-1466788360" or empty). If we honored those, currentAddr gets clobbered
        // and the next accept/decline goes to the wrong device, returning false.
        val numberLooksReal = number.isNotEmpty() &&
            (number.startsWith("+") || number.firstOrNull()?.isDigit() == true)
        if (!numberLooksReal && state != STATE_TERMINATED) {
            Log.w(TAG, "event=call_state_drop addr=$deviceAddress state=$state reason=bogus_number num='$number'")
            return@section
        }
        // If we already latched a different AG, ignore late events from another
        // one until we go IDLE.
        if (currentAddr.isNotEmpty() && deviceAddress != currentAddr && phase != CallPhase.IDLE) {
            Log.w(TAG, "event=call_state_drop addr=$deviceAddress reason=other_ag_active current=$currentAddr")
            return@section
        }
        currentAddr = deviceAddress
        currentCallId = callId
        currentNumber = number
        // HFP itself does not transmit caller name. If the AG-supplied name is
        // empty, fall back to a contacts-cache lookup synced from the phone.
        currentName = name.ifEmpty {
            val looked = try { contactsCache?.lookup(number).orEmpty() } catch (_: Exception) { "" }
            Log.i(TAG, "event=name_lookup number=$number cache=${contactsCache != null} ag=$activeAgMac result='$looked'")
            looked
        }

        // Anti-thrash: the iPhone AG re-emits DIALING/ALERTING (and sometimes
        // INCOMING/WAITING) for an already-connected call, which would bounce phase
        // ACTIVE -> OUTGOING_DIALING and flap the far-party call-audio tap mid-utterance.
        // A connected call never legitimately returns to a pre-active state, so once
        // this callId has reached ACTIVE we ignore backward transitions for it. Real
        // call-end (STATE_TERMINATED) and the SCO-drop grace still end the call; HELD is
        // not suppressed (hold/unhold keeps the audio route). A pre-active event for a
        // DIFFERENT callId is a genuinely new call (or call-waiting) and passes through,
        // because the latch is keyed on callId.
        if (phase == CallPhase.ACTIVE &&
            activeLatchedCallId >= 0 &&
            callId == activeLatchedCallId &&
            (state == STATE_INCOMING || state == STATE_WAITING ||
                state == STATE_DIALING || state == STATE_ALERTING)) {
            Log.i(TAG, "event=call_state_backward_ignored id=$callId state=$stateName (already active)")
            log("ignoring spurious $stateName for active call id=$callId")
            return@section
        }

        when (state) {
            STATE_INCOMING, STATE_WAITING -> enterIncoming()
            STATE_DIALING, STATE_ALERTING -> enterDialing()
            STATE_ACTIVE -> enterActive()
            STATE_TERMINATED -> enterIdle("terminated")
            STATE_HELD -> { /* no UI change for hold */ }
        }
    }

    override fun onCallAudioStateChanged(deviceAddress: String, audioState: Int) = GT.section("audio.hfp.sco_state") {
        GT.counter("audio.hfp.sco_active", if (audioState == AUDIO_CONNECTED) 1L else 0L)
        val key = "$deviceAddress|$audioState"
        if (key == lastAudioKey) {
            Log.d(TAG, "event=sco_state_dedup addr=$deviceAddress state=$audioState")
            return@section
        }
        lastAudioKey = key

        val stateName = when (audioState) {
            AUDIO_CONNECTED -> "CONNECTED"
            AUDIO_DISCONNECTED -> "DISCONNECTED"
            AUDIO_CONNECTING -> "CONNECTING"
            else -> "UNKNOWN($audioState)"
        }
        Log.i(TAG, "event=sco_state addr=$deviceAddress state=$stateName phase=$phase")
        log("onCallAudioStateChanged addr=$deviceAddress state=$audioState")

        when (audioState) {
            AUDIO_CONNECTED -> {
                scoActive = true
                // SCO came back within the grace window: the drop was a blip, keep
                // the call ACTIVE and the tap alive. Cancel here directly rather than
                // via enterActive(), which early-returns when phase is still ACTIVE.
                cancelPendingScoDrop()
                Log.i(TAG, "event=sco_connected addr=$deviceAddress phase=$phase")
                if (phase != CallPhase.ACTIVE && (phase == CallPhase.INCOMING || phase == CallPhase.OUTGOING_DIALING)) {
                    Log.i(TAG, "event=sco_upgrade_to_active addr=$deviceAddress from_phase=$phase")
                    // SCO came up before AG call-state ACTIVE -- upgrade now
                    enterActive()
                } else {
                    broadcastUiState()
                }
            }
            AUDIO_DISCONNECTED -> {
                scoActive = false
                // Mute is meaningless without an active SCO uplink; clear it so
                // the next session starts unmuted and the indicator hides.
                if (micMuted) {
                    Log.i(TAG, "event=mute_clear reason=sco_disconnected addr=$deviceAddress")
                    micMuted = false
                }
                Log.i(TAG, "event=sco_disconnected addr=$deviceAddress phase=$phase")
                if (phase == CallPhase.ACTIVE) {
                    Log.w(TAG, "event=sco_dropped_during_active addr=$deviceAddress")
                    // Debounce: do NOT tear down immediately. A brief SCO bounce
                    // (common on iPhone AGs) would otherwise flap the call-audio tap
                    // and cut the phone's translation stream mid-sentence. Post a
                    // delayed enterIdle; an SCO reconnect or a real terminate cancels
                    // it. Guard the fire on the generation captured now so a stale
                    // runnable can never tear down a newer call.
                    cancelPendingScoDrop()
                    val gen = callGeneration
                    val dropAddr = deviceAddress
                    val r = Runnable {
                        pendingScoDrop = null
                        if (callGeneration == gen && phase == CallPhase.ACTIVE && !scoActive) {
                            Log.w(TAG, "event=sco_drop_grace_expired addr=$dropAddr gen=$gen")
                            enterIdle("sco-dropped")
                        } else {
                            Log.i(TAG, "event=sco_drop_grace_stale addr=$dropAddr gen=$gen cur=$callGeneration phase=$phase sco=$scoActive")
                        }
                    }
                    pendingScoDrop = r
                    mainHandler.postDelayed(r, SCO_DROP_GRACE_MS)
                    log("sco dropped during active -- ${SCO_DROP_GRACE_MS}ms grace before idle")
                } else {
                    // Even when phase is IDLE (e.g. PC HFP mic without a real call),
                    // notify UI so the mute indicator hides.
                    broadcastUiState()
                }
            }
            AUDIO_CONNECTING -> {
                Log.i(TAG, "event=sco_connecting addr=$deviceAddress phase=$phase")
                broadcastUiState()
            }
        }
    }

    override fun onHfpConnectionChanged(deviceAddress: String, state: Int) {
        // BluetoothProfile states: DISCONNECTED=0, CONNECTING=1, CONNECTED=2, DISCONNECTING=3
        val stateName = when (state) {
            0 -> "DISCONNECTED"
            1 -> "CONNECTING"
            2 -> "CONNECTED"
            3 -> "DISCONNECTING"
            else -> "UNKNOWN($state)"
        }
        Log.i(TAG, "event=hfp_conn_state addr=$deviceAddress state=$stateName")
        log("onHfpConnectionChanged addr=$deviceAddress state=$state")
    }

    // ---- Phase transitions ----

    private fun enterIncoming() {
        if (phase == CallPhase.INCOMING) return
        val prev = phase
        phase = CallPhase.INCOMING
        Log.i(TAG, "event=phase_transition from=$prev to=INCOMING addr=$currentAddr id=$currentCallId folded=$folded")
        log("phase -> INCOMING folded=$folded")
        if (!folded) {
            showIncomingUi()
        } else {
            Log.i(TAG, "event=incoming_suppressed_folded addr=$currentAddr id=$currentCallId")
            log("incoming suppressed (folded); will restore on unfold")
        }
    }

    /**
     * Actually surface the incoming call on the glasses: overlay + ringtone + LED
     * + foreground MainActivity + UI-state broadcast. Used by enterIncoming() when
     * worn, and by the wear-on/bt-bound resync path when we discover a call that
     * was already incoming.
     */
    private fun showIncomingUi() {
        overlay?.showIncoming(currentName, currentNumber)
        showLed()
        ctx?.let { startRingtone(it) }
        launchMainActivity()
        broadcastUiState()
    }

    private fun enterDialing() {
        if (phase == CallPhase.OUTGOING_DIALING) return
        val prev = phase
        phase = CallPhase.OUTGOING_DIALING
        Log.i(TAG, "event=phase_transition from=$prev to=OUTGOING_DIALING addr=$currentAddr id=$currentCallId")
        log("phase -> OUTGOING_DIALING")
        broadcastUiState()
    }

    private fun enterActive() {
        if (phase == CallPhase.ACTIVE) return
        val prev = phase
        // New ACTIVE epoch: invalidate any pending sco-drop from a prior state and
        // bump the generation so a runnable already dequeued cannot fire against us.
        cancelPendingScoDrop()
        callGeneration++
        phase = CallPhase.ACTIVE
        // Latch the connected call so later spurious backward transitions for the same
        // callId are ignored (see onCallStateChanged).
        latchActiveCall()
        startedElapsedRealtime = SystemClock.elapsedRealtime()
        Log.i(TAG, "event=phase_transition from=$prev to=ACTIVE addr=$currentAddr id=$currentCallId scoActive=$scoActive")
        log("phase -> ACTIVE")
        overlay?.hide()
        hideLed()
        stopRingtone()
        try { ttsPlayer?.interrupt() } catch (e: Exception) {
            log("ttsPlayer.interrupt failed: ${e.message}")
        }
        broadcastUiState()
    }

    private fun enterIdle(reason: String) {
        // A real terminate (or any non-debounced idle) must win instantly over a
        // pending sco-drop grace timer so the tap never survives past a hangup.
        cancelPendingScoDrop()
        callGeneration++
        if (phase == CallPhase.IDLE) return
        val prev = phase
        val durMs = if (startedElapsedRealtime > 0L) SystemClock.elapsedRealtime() - startedElapsedRealtime else 0L
        Log.i(TAG, "event=phase_transition from=$prev to=IDLE reason=$reason durMs=$durMs addr=$currentAddr id=$currentCallId")
        log("phase -> IDLE ($reason)")
        phase = CallPhase.IDLE
        overlay?.hide()
        hideLed()
        stopRingtone()
        broadcastUiState()
        reset()
    }

    private fun reset() {
        cancelPendingScoDrop()
        phase = CallPhase.IDLE
        currentCallId = -1
        currentAddr = ""
        currentNumber = ""
        currentName = ""
        startedElapsedRealtime = 0L
        scoActive = false
        micMuted = false
        lastCallKey = -1L
        lastAudioKey = ""
        activeLatchedCallId = -1
    }

    // ---- Side effects ----

    private fun broadcastUiState() {
        val c = ctx ?: return
        val intent = Intent(ListenerService.ACTION_CALL_UI_STATE).apply {
            setPackage(c.packageName)
            putExtra("phase", phase.name)
            putExtra("number", currentNumber)
            putExtra("name", currentName)
            putExtra("callId", currentCallId)
            putExtra("startedElapsedRealtime", startedElapsedRealtime)
            putExtra("scoActive", scoActive)
            putExtra("micMuted", micMuted)
        }
        c.sendBroadcast(intent)
    }

    private fun launchMainActivity() {
        val c = ctx ?: return
        try {
            val intent = Intent(c, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            c.startActivity(intent)
        } catch (e: Exception) {
            log("launchMainActivity failed: ${e.message}")
        }
    }

    private fun showLed() {
        runShell(
            "service call lights_ctrl 8 i32 $LIGHTS_EVENT_TYPE_SPECIAL i32 $LIGHTS_EVENT_PHONE_RING"
        )
    }

    private fun hideLed() {
        runShell(
            "service call lights_ctrl 9 i32 $LIGHTS_EVENT_TYPE_SPECIAL i32 $LIGHTS_EVENT_PHONE_RING"
        )
    }

    private fun runShell(cmd: String) {
        Thread {
            try {
                val process = ProcessBuilder(listOf("sh", "-c", cmd))
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val finished = process.waitFor(2, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroy()
                    log("runShell timeout cmd='$cmd'")
                    return@Thread
                }
                val exit = process.exitValue()
                if (exit != 0) {
                    log("runShell non-zero exit=$exit cmd='$cmd' out='${output.trim()}'")
                }
            } catch (e: IOException) {
                log("runShell io failed: ${e.message}")
            } catch (e: InterruptedException) {
                log("runShell interrupted: ${e.message}")
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                log("runShell failed: ${e.message}")
            }
        }.start()
    }

    private fun startRingtone(ctx: Context) {
        if (ringtonePlayer != null) return
        try {
            val mp = MediaPlayer.create(ctx, R.raw.ringtone_incoming) ?: run {
                log("startRingtone: MediaPlayer.create returned null")
                return
            }
            mp.isLooping = true
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setOnErrorListener { _: android.media.MediaPlayer, _: Int, _: Int -> true }
            mp.start()
            ringtonePlayer = mp
            log("ringtone started")
        } catch (e: Exception) {
            log("startRingtone failed: ${e.message}")
        }
    }

    private fun stopRingtone() {
        val mp = ringtonePlayer ?: return
        ringtonePlayer = null
        try {
            if (mp.isPlaying) mp.stop()
        } catch (e: Exception) {
            log("stopRingtone stop failed: ${e.message}")
        }
        try {
            mp.release()
            log("ringtone stopped")
        } catch (e: Exception) {
            log("stopRingtone release failed: ${e.message}")
        }
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        remoteLog?.invoke("CallController: $msg")
    }

    // ---- Fold handling ----

    private val foldReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_LEG_STATUS_CHANGED) return
            val raw = intent.getStringExtra("glasses_leg_state") ?: return
            val f = when (raw) {
                "1" -> false  // spread = unfolded
                "0" -> true   // not spread = folded
                else -> {
                    log("fold: malformed glasses_leg_state=$raw")
                    return
                }
            }
            handleFoldChange(f)
        }
    }

    private fun readFoldProperty(): Boolean? {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val m = cls.getMethod("get", String::class.java, String::class.java)
            val v = m.invoke(null, "vendor.rkd.glasses.is_spread", "") as String
            when (v) {
                "1" -> false
                "0" -> true
                else -> null
            }
        } catch (t: Throwable) {
            log("readFoldProperty failed: ${t.message}")
            null
        }
    }

    private fun handleFoldChange(nowFolded: Boolean) {
        if (folded == nowFolded) return
        val prev = folded
        folded = nowFolded
        Log.i(TAG, "event=fold_changed from=$prev to=$nowFolded phase=$phase")
        log("fold changed $prev -> $nowFolded phase=$phase")

        if (!nowFolded) {
            // FOLDED -> UNFOLDED. Restore UI for whatever state the call is in.
            if (phase == CallPhase.INCOMING) {
                // Late-surface suppressed incoming UI.
                Log.i(TAG, "event=unfold_restore_incoming id=$currentCallId")
                showIncomingUi()
            } else {
                // No active incoming inside us; resync from the snapshot in case the
                // call originated or transitioned while we were folded.
                resyncFromSnapshot("unfold")
            }
        } else {
            // UNFOLDED -> FOLDED. Hide user-visible artifacts but keep internal
            // state so unfolding mid-ring restores the UI.
            if (phase == CallPhase.INCOMING) {
                Log.i(TAG, "event=fold_suppress_incoming id=$currentCallId")
                overlay?.hide()
                hideLed()
                stopRingtone()
                // Reset UI (MainActivity) to IDLE focus so nothing is visible.
                broadcastUiStatePhase(CallPhase.IDLE)
            } else if (phase == CallPhase.ACTIVE) {
                // Call audio stays routed -- we just don't need to show anything.
                overlay?.hide()
                // Keep ACTIVE broadcast in case the UI comes back; no ringtone anyway.
            }
        }
    }

    private fun resyncFromSnapshot(reason: String) {
        val b = bridge ?: return
        val json = try { b.getCallSnapshotJson() } catch (_: Exception) { "{}" }
        val snap = parseSnapshot(json)
        Log.i(TAG, "event=snapshot_resync reason=$reason raw='$json' parsed=$snap folded=$folded phase=$phase")
        log("snapshot($reason): $json")
        if (snap == null) return

        // Always sync SCO state from the snapshot. The HFP-HF AUDIO_STATE_CHANGED
        // broadcast can fire before we register a receiver (PC AG already
        // streaming when the listener starts). Keeping scoActive in sync here
        // makes the touchpad mute toggle work even with no call event ever.
        val nowSco = snap.audioState == AUDIO_CONNECTED
        if (nowSco != scoActive) {
            Log.i(TAG, "event=snapshot_sco_sync from=$scoActive to=$nowSco audioState=${snap.audioState}")
            scoActive = nowSco
            if (!nowSco && micMuted) {
                Log.i(TAG, "event=mute_clear reason=snapshot_sco_off")
                micMuted = false
            }
            if (snap.address.isNotEmpty()) currentAddr = snap.address
            broadcastUiState()
        }

        if (snap.callState == null) {
            // No call event but SCO may still be active (e.g. PC HFP mic
            // streaming). scoActive sync above already broadcasted UI state.
            return
        }

        // Sync internal call metadata from snapshot so overlay/MainActivity can render it.
        if (snap.address.isNotEmpty()) currentAddr = snap.address
        snap.callId?.let { if (it >= 0) currentCallId = it }
        snap.number?.let { currentNumber = it }
        snap.name?.let { currentName = it }

        when (snap.callState) {
            STATE_INCOMING, STATE_WAITING -> {
                if (phase != CallPhase.INCOMING) {
                    phase = CallPhase.INCOMING
                    Log.i(TAG, "event=phase_transition from=resync to=INCOMING folded=$folded")
                }
                if (!folded) showIncomingUi()
            }
            STATE_ACTIVE -> {
                if (phase != CallPhase.ACTIVE) {
                    phase = CallPhase.ACTIVE
                    // Latch the same as enterActive() so the anti-thrash guard also
                    // covers a call that first became ACTIVE via resync (e.g. unfold-
                    // restore of a call that connected while folded).
                    latchActiveCall()
                    // We missed the real call start; use now() as an approximation.
                    startedElapsedRealtime = SystemClock.elapsedRealtime()
                    Log.i(TAG, "event=phase_transition from=resync to=ACTIVE approx_start=$startedElapsedRealtime")
                    stopRingtone()
                    overlay?.hide()
                    hideLed()
                    broadcastUiState()
                }
            }
            else -> {
                // DIALING / ALERTING / HELD / TERMINATED: ignore for resync.
            }
        }
    }

    private data class CallSnapshot(
        val address: String,
        val hfpState: Int,
        val audioState: Int,
        val callState: Int?,  // null if no call
        val callId: Int?,
        val number: String?,
        val name: String?,
    )

    private fun parseSnapshot(json: String): CallSnapshot? {
        return try {
            val obj = JSONObject(json)
            val addr = obj.optString("address", "")
            val hfp = obj.optInt("state", -1)
            val audio = obj.optInt("audioState", -1)
            val callObj = obj.opt("call")
            if (callObj == null || callObj == JSONObject.NULL || callObj !is JSONObject) {
                CallSnapshot(addr, hfp, audio, null, null, null, null)
            } else {
                CallSnapshot(
                    address = addr,
                    hfpState = hfp,
                    audioState = audio,
                    callState = callObj.optInt("state", -1).takeIf { it >= 0 },
                    callId = callObj.optInt("id", -1).takeIf { it >= 0 },
                    number = callObj.optString("number", ""),
                    name = callObj.optString("name", ""),
                )
            }
        } catch (e: Exception) {
            log("parseSnapshot failed: ${e.message}")
            null
        }
    }

    private fun broadcastUiStatePhase(forcedPhase: CallPhase) {
        val c = ctx ?: return
        val intent = Intent(ListenerService.ACTION_CALL_UI_STATE).apply {
            setPackage(c.packageName)
            putExtra("phase", forcedPhase.name)
            putExtra("number", currentNumber)
            putExtra("name", currentName)
            putExtra("callId", currentCallId)
            putExtra("startedElapsedRealtime", startedElapsedRealtime)
            putExtra("scoActive", scoActive)
            putExtra("micMuted", micMuted)
        }
        c.sendBroadcast(intent)
    }
}
