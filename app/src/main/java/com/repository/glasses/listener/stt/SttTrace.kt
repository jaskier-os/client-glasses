package com.repository.glasses.listener.stt

import android.util.Log

/**
 * One logcat tag for the whole on-glasses recognition path.
 *
 * Every line the local STT path emits -- in the listener process AND in the
 * capture process, which has its own copy of this object -- carries the tag
 * [TAG], so `adb logcat -s STT:V` shows the complete flow and nothing else. The
 * live incident this exists for produced exactly two lines across two devices
 * and left no way to tell whether the session began, the mic was subscribed, the
 * VAD ever fired, or the Binder call was made.
 *
 * RULES, all learned the hard way:
 *
 *  - INFO, not DEBUG. These lines must survive a release build; the path is only
 *    ever debugged on a real device with a real wearer, and a verbose-only trace
 *    is a trace that is not there when it is needed.
 *  - Every line carries an id (`s<session>` or `s<session>/u<utterance>`) so two
 *    sessions in quick succession, or a late result from a retired utterance,
 *    can be told apart rather than read as one confused timeline.
 *  - Every call is wrapped. android.util.Log is not mocked on the JVM test
 *    classpath and THROWS there, and these calls sit inside lifecycle paths
 *    between subscribing to the mic and closing that subscription -- a throw
 *    would leak the subscription this class exists to make visible.
 */
object SttTrace {

    const val TAG = "STT"

    /**
     * Mirrors every trace line into the persistent glasses log
     * (/sdcard/Download/glasses-client.log) so the path is still readable when
     * the glasses are on Bluetooth only and logcat is unreachable. Wired by
     * ListenerService; null in the capture process and in tests.
     */
    @Volatile
    var mirror: ((String) -> Unit)? = null

    fun i(msg: String) {
        try { Log.i(TAG, msg) } catch (_: Throwable) {}
        try { mirror?.invoke("[STT] $msg") } catch (_: Throwable) {}
    }

    fun w(msg: String) {
        try { Log.w(TAG, msg) } catch (_: Throwable) {}
        try { mirror?.invoke("[STT] WARN $msg") } catch (_: Throwable) {}
    }

    /** Milliseconds since [startMs], for the elapsed field every hop carries. */
    fun since(startMs: Long): Long = System.currentTimeMillis() - startMs
}
