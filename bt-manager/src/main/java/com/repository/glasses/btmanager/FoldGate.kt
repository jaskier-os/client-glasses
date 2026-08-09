package com.repository.glasses.btmanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread

/**
 * Reads the fold-detection signal from three redundant sources, seeding initial
 * state from the vendor.rkd.glasses.is_spread system property:
 *
 *  1. Rokid's com.rokid.sprite.ACTION_LEG_STATUS_CHANGED broadcast.
 *  2. glasses-power-daemon's ACTION_FOLD_CHANGED broadcast (boolean extra).
 *  3. A slow poll of vendor.rkd.glasses.is_spread as the authoritative backstop.
 *
 * All three exist because relying on (1) alone was a real bug. The Rokid
 * broadcast originates from PsensorObserver, which stock firmware ships latched
 * off via enforce_psensor, so it can go silent entirely. When that happened
 * bt-manager never learned about a fold, never dropped A2DP, and the still-open
 * SLIMbus TX port kept the kernel out of s2idle -- observed as "Abort: Some
 * devices failed to suspend" alongside btfm_slim_enable_ch /
 * "btfm_num_ports_open: 1" in dmesg, with hal_bluetooth_lock held. The listener
 * app hit the same class of problem and solved it with its own FoldPoll; this
 * mirrors that. is_spread is the kernel PSoC value and is authoritative, so the
 * poll is the source of truth and the two broadcasts are low-latency hints.
 *
 * Fold is the canonical "off-head, not in use" signal for power management.
 * When folded, bt-manager drops A2DP + HFP so the BT stack releases its kernel
 * wakelock (hal_bluetooth_lock) and glasses-power-daemon can enter freeze.
 *
 * Default state is "unfolded" so behavior before the first signal is permissive
 * (profiles allowed to connect). Self-contained so bt-manager owns its own fold
 * gate without binding into the listener process.
 *
 * Polarity: is_spread / glasses_leg_state "1" = spread = UNFOLDED, "0" = FOLDED.
 */
class FoldGate(
    ctx: Context,
    private val log: (String) -> Unit = {},
) {
    interface Listener { fun onFoldChanged(folded: Boolean) }

    companion object {
        const val ACTION_LEG_STATUS_CHANGED = "com.rokid.sprite.ACTION_LEG_STATUS_CHANGED"

        // Broadcast by glasses-power-daemon on every debounced fold transition.
        // The daemon sends one `am broadcast -p <pkg>` PER package (see
        // FOLD_BROADCAST_PKGS in glasses-power-daemon/src/main.c) because -p sets
        // Intent.setPackage(), which confines delivery to a single package. This
        // action keeps the listener's name for wire compatibility, but the daemon
        // explicitly targets bt-manager as well -- without that second invocation
        // this receiver would never fire.
        const val ACTION_FOLD_CHANGED =
            "com.repository.glasses.listener.ACTION_FOLD_CHANGED"
        private const val EXTRA_FOLDED = "folded"

        private const val EXTRA_LEG_STATE = "glasses_leg_state"
        private const val PROP_NAME = "vendor.rkd.glasses.is_spread"

        // The daemon debounces fold for 3s and only arms suspend 3 minutes
        // later, so a 5s poll detects every fold with a wide margin while
        // costing a property read per tick.
        private const val POLL_INTERVAL_MS = 5_000L
    }

    private val appCtx = ctx.applicationContext
    private var listener: Listener? = null
    private var registered = false

    private var pollThread: HandlerThread? = null
    private var pollHandler: Handler? = null

    @Volatile var folded: Boolean = readProperty() ?: false
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_LEG_STATUS_CHANGED -> {
                    val raw = intent.getStringExtra(EXTRA_LEG_STATE) ?: return
                    val f = when (raw) {
                        "1" -> false
                        "0" -> true
                        else -> {
                            log("FoldGate: malformed glasses_leg_state=$raw")
                            return
                        }
                    }
                    apply(f, "leg_status")
                }
                ACTION_FOLD_CHANGED -> {
                    if (!intent.hasExtra(EXTRA_FOLDED)) {
                        log("FoldGate: ACTION_FOLD_CHANGED without '$EXTRA_FOLDED' extra")
                        return
                    }
                    apply(intent.getBooleanExtra(EXTRA_FOLDED, false), "daemon")
                }
            }
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            readProperty()?.let { apply(it, "poll") }
            pollHandler?.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    /**
     * Single funnel for all sources. Dedups on the current value so whichever
     * source observes the transition first wins and the others are no-ops --
     * the listener is never notified twice for one physical fold. Every source
     * reports ABSOLUTE state (never a toggle), so an arbitrary interleaving of
     * poll and broadcast can converge but never invert.
     *
     * The listener is invoked while holding this monitor. That is only safe
     * because ProfileAutoConnector.onFoldChanged immediately hands off to a
     * Handler and does no blocking work inline -- keep it that way.
     */
    @Synchronized
    private fun apply(f: Boolean, source: String) {
        if (f == folded) return
        folded = f
        log("FoldGate: changed folded=$f (via $source)")
        notifyListener(f)
    }

    private fun notifyListener(f: Boolean) {
        try { listener?.onFoldChanged(f) } catch (e: Throwable) {
            log("FoldGate listener threw: ${e.message}")
        }
    }

    fun start(l: Listener) {
        listener = l
        if (registered) return
        try {
            val filter = IntentFilter(ACTION_LEG_STATUS_CHANGED).apply {
                addAction(ACTION_FOLD_CHANGED)
            }
            appCtx.registerReceiver(receiver, filter)
            registered = true
            log("FoldGate registered initial folded=$folded")
        } catch (e: Throwable) {
            log("FoldGate register failed: ${e.message}")
        }
        // Dispatch the seeded state when we come up ALREADY folded. apply()
        // dedups against `folded`, so without this an initial folded=true is
        // never delivered: on a service restart while folded (START_STICKY,
        // crash, MY_PACKAGE_REPLACED) an already-connected A2DP/HFP link would
        // never be torn down, which is precisely the SLIMbus wakeup source that
        // blocks s2idle. Unfolded needs no dispatch -- that is the default and
        // permissive state.
        if (folded) {
            log("FoldGate: dispatching seeded folded=true")
            notifyListener(true)
        }
        startPoll()
    }

    fun stop() {
        stopPoll()
        try { if (registered) appCtx.unregisterReceiver(receiver) } catch (_: Throwable) {}
        registered = false
        listener = null
    }

    // Polls off the main thread: this runs inside bt-manager's service process
    // and must not add property reads to its main looper.
    private fun startPoll() {
        if (pollThread != null) return
        try {
            val t = HandlerThread("FoldGatePoll").apply { start() }
            pollThread = t
            pollHandler = Handler(t.looper).apply {
                postDelayed(pollRunnable, POLL_INTERVAL_MS)
            }
        } catch (e: Throwable) {
            log("FoldGate poll start failed: ${e.message}")
        }
    }

    private fun stopPoll() {
        pollHandler?.removeCallbacks(pollRunnable)
        pollHandler = null
        try { pollThread?.quitSafely() } catch (_: Throwable) {}
        pollThread = null
    }

    private fun readProperty(): Boolean? = try {
        val cls = Class.forName("android.os.SystemProperties")
        val m = cls.getMethod("get", String::class.java, String::class.java)
        when (m.invoke(null, PROP_NAME, "") as String) {
            "1" -> false
            "0" -> true
            else -> null
        }
    } catch (_: Throwable) { null }
}
