package com.repository.glasses.capture

import android.util.Log

/**
 * The capture-process half of the local-STT trace.
 *
 * A deliberate duplicate of the listener's
 * com.repository.glasses.listener.stt.SttTrace: the two live in separate APKs
 * and separate processes, so they cannot share a class, but they MUST share the
 * [TAG] -- the whole point is that one `adb logcat -s STT:V` shows the listener
 * side and the capture side of the same utterance interleaved.
 *
 * INFO, not DEBUG, so the model-load and encoder timings survive a release
 * build. Every line carries the utterance id the listener assigned, so a line
 * here can be matched to the Binder call that produced it.
 */
object SttTrace {

    const val TAG = "STT"

    fun i(msg: String) {
        try { Log.i(TAG, "cap $msg") } catch (_: Throwable) {}
    }

    fun w(msg: String) {
        try { Log.w(TAG, "cap $msg") } catch (_: Throwable) {}
    }
}
