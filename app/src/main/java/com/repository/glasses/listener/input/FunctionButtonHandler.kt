package com.repository.glasses.listener.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.repository.glasses.listener.capture.CaptureBridge
import com.repository.glasses.tracing.GT

/**
 * KEYCODE_CAMERA state machine:
 *
 *  Unfolded:
 *  - short press  (released within longPressMs)
 *      - not recording -> takePhoto()
 *      - recording     -> togglePauseVideo()
 *  - long press   (held >= longPressMs)
 *      - not recording -> startVideo()
 *      - recording     -> stopVideo()
 *
 *  Folded (leg in):
 *  - capture is suppressed entirely (no photo / no video while folded).
 *  - three rapid presses within [tripleWindowMs] -> onPairingRequested()
 *    (used to enter BT pairing mode without needing a screen UI).
 *
 * Call `onKeyDown` / `onKeyUp` from the Activity. Both return true when handled
 * (caller should consume the event).
 */
class FunctionButtonHandler(
    private val capture: CaptureBridge,
    private val longPressMs: Long = 500L,
    private val tripleWindowMs: Long = 700L,
    private val onPairingRequested: (() -> Unit)? = null,
    /**
     * Fires synchronously on FN UP when a short-press resolves to a photo
     * capture (i.e. not currently recording video). The listener service
     * uses this to bring up the shutter-confirmation overlay's placeholder
     * BEFORE the actual JPEG lands -- the camera path is ~2 s end-to-end,
     * so without this hook the overlay would feel laggy.
     */
    private val onPhotoTriggered: (() -> Unit)? = null,
    /**
     * Fires just before the AIDL takePhoto, so the listener can free its own
     * heap first. Capture is the priv-app lmkd targets when memory is tight, so
     * shrinking the sibling process improves the odds the user's photo survives.
     * Mirrors the existing pre-video flush.
     */
    private val beforePhoto: (() -> Unit)? = null,
    /**
     * Optional override for the long-press video toggle. Listener supplies
     * this so it can interrupt a phone-driven recording (record_video /
     * record_ar_screen via ArVideoRecorder) before the capture process
     * tries to open the camera -- otherwise the second open races and
     * either fails outright or steals the camera mid-finalize. When the
     * lambda is supplied the handler calls it instead of the
     * capture.startVideo()/stopVideo() toggle. Returning is fire-and-forget.
     * Falls back to the original toggle when null.
     */
    private val onLongPressVideo: (() -> Unit)? = null,
    private val log: (String) -> Unit = {},
) {
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var downAtMs: Long = 0
    @Volatile private var longPressFired = false
    @Volatile private var folded: Boolean = false

    /**
     * True when the press being tracked came from a REMOTE device, so the capture it
     * produces must not light the privacy LED.
     *
     * Latched on DOWN and read on the resolution paths (the long-press runnable and UP),
     * because by then the originating intent is long gone. One flag suffices: there is a
     * single finger and a single state machine, so a remote and a physical press cannot
     * be in flight at once.
     */
    @Volatile private var silentPress: Boolean = false

    // Triple-press tracking (folded mode only).
    private val pressTimestamps: ArrayDeque<Long> = ArrayDeque()

    private val longPressRunnable = Runnable {
        longPressFired = true
        val handler = onLongPressVideo
        if (handler != null) {
            log("FnButton: long-press -> onLongPressVideo")
            try { handler.invoke() } catch (t: Throwable) {
                log("FnButton: onLongPressVideo threw: ${t.message}")
            }
        } else {
            // Authoritative session state, not the VideoRecorder boolean (see
            // CaptureBridge.isRecordingActive) so a wedged false can't double-start.
            val rec = capture.isRecordingActive()
            log("FnButton: long-press, active=$rec silent=$silentPress -> ${if (rec) "stopVideo" else "startVideo"}")
            // Silence applies to START only. A stop has no LED to suppress -- it turns
            // the LED off -- and passing the flag there would be meaningless.
            if (rec) capture.stopVideo() else capture.startVideo(silent = silentPress)
        }
    }

    fun setFolded(f: Boolean) {
        if (folded == f) return
        folded = f
        if (!f) pressTimestamps.clear()
        android.util.Log.i("FnButton", "fold state -> folded=$f")
        log("FnButton: fold state -> folded=$f")
    }

    /**
     * @param silent this press came from a REMOTE device; the capture it resolves to must
     *        not light the privacy LED. See [silentPress].
     */
    @JvmOverloads
    fun onKeyDown(repeatCount: Int, silent: Boolean = false): Boolean = GT.section("input.fn.down") {
        if (repeatCount > 0) return@section true  // swallow repeats while held

        if (folded) {
            // Folded mode: no capture. Track triple-press for pairing.
            val now = SystemClock.elapsedRealtime()
            pressTimestamps.addLast(now)
            while (pressTimestamps.isNotEmpty() && now - pressTimestamps.first() > tripleWindowMs) {
                pressTimestamps.removeFirst()
            }
            if (pressTimestamps.size >= 3) {
                android.util.Log.i("FnButton", "folded triple-press -> onPairingRequested")
                log("FnButton: folded triple-press -> onPairingRequested")
                pressTimestamps.clear()
                try {
                    onPairingRequested?.invoke()
                } catch (t: Throwable) {
                    log("FnButton: onPairingRequested threw: ${t.message}")
                }
            }
            return@section true
        }

        if (downAtMs != 0L) return@section true  // already tracking a press
        downAtMs = SystemClock.elapsedRealtime()
        longPressFired = false
        silentPress = silent
        handler.postDelayed(longPressRunnable, longPressMs)
        true
    }

    fun onKeyUp(): Boolean = GT.section("input.fn.up") {
        if (folded) return@section true  // folded mode: no up action

        val start = downAtMs
        if (start == 0L) return@section true  // up without a tracked down -- ignore
        downAtMs = 0
        handler.removeCallbacks(longPressRunnable)
        if (longPressFired) return@section true  // long-press path already acted
        val elapsed = SystemClock.elapsedRealtime() - start
        if (elapsed >= longPressMs) return@section true  // race: treat as long (unlikely)
        // Use the AUTHORITATIVE session state (recorder surface present) rather
        // than the VideoRecorder boolean, so a binder/HAL wedge that desyncs
        // isRecording() to false can't make a short-press mis-route (pause vs
        // photo). Matches the long-press toggle.
        //
        // TRI-STATE on purpose. isRecordingActive() collapses "binder
        // unreachable" to TRUE, which is correct for the long-press toggle
        // (prefer an idempotent stop) but catastrophic here: after the capture
        // process dies or is restarting, every short press resolved to
        // togglePauseVideo, which is a silent no-op when nothing is recording.
        // The user pressed the button repeatedly and got NO photo and NO error.
        // Measured on device: capture died, then 0 photos from any press until
        // the app was restarted. So an UNKNOWN state must take the photo path --
        // taking a photo when a recording is somehow live is recoverable, while
        // silently discarding every press is not.
        val recOrNull = capture.isRecordingActiveOrNull()
        val rec = recOrNull == true
        if (recOrNull == null) {
            log("FnButton: short-press, capture state UNKNOWN (binder down?) -> takePhoto anyway")
        }
        log("FnButton: short-press, active=$rec silent=$silentPress -> ${if (rec) "togglePauseVideo" else "takePhoto"}")
        if (rec) {
            capture.togglePauseVideo()
        } else {
            // Fire the placeholder hook BEFORE the AIDL takePhoto so the
            // overlay bounces in immediately on button release. The actual
            // JPEG arrives later via onPhotoTaken and swaps into the
            // already-onscreen overlay.
            try { onPhotoTriggered?.invoke() } catch (t: Throwable) {
                log("FnButton: onPhotoTriggered threw: ${t.message}")
            }
            // Drop the LISTENER's heap before capture allocates its ~120-170MB.
            // lmkd scores by oom_score_adj * size and picks the capture priv-app
            // (adj 100) even at ~124MB when the system total is tight -- measured
            // "Memory Load Sum: 1241 MB" on this 1.8GB device. Shrinking the
            // sibling process is the one lever we have from this side. Already
            // done before video starts; photos need it just as much.
            try { beforePhoto?.invoke() } catch (t: Throwable) {
                log("FnButton: beforePhoto threw: ${t.message}")
            }
            capture.takePhoto(silent = silentPress)
        }
        true
    }

    /**
     * Drop any pending long-press / in-flight DOWN. Call from Activity.onPause/onStop so a DOWN
     * without a matching UP (screen turned off mid-press, focus change) doesn't freeze the state
     * machine and make the next DOWN a no-op.
     */
    fun reset() {
        handler.removeCallbacks(longPressRunnable)
        downAtMs = 0
        longPressFired = false
        pressTimestamps.clear()
    }
}
