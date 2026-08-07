package com.repository.glasses.listener.stt

import android.util.Log
import com.repository.glasses.listener.bt.LocalTranscriptWire
import com.repository.glasses.listener.bt.SttModeWire
import com.repository.glasses.listener.capture.MicBus
import java.util.concurrent.atomic.AtomicLong

/**
 * Ties the pieces of on-glasses recognition into one session.
 *
 * Owns the ordering that the rest of the system depends on:
 *
 *  1. decide ONCE, at session start, whether this session is local (SttRouter),
 *  2. tell the phone BEFORE any audio flows, so it can decline to open its own
 *     transcriber rather than discovering halfway through that it should not
 *     have,
 *  3. subscribe to the mic, endpoint on the glasses, transcribe, and ship the
 *     final,
 *  4. unsubscribe on end, because MicBus is a process singleton and a leaked
 *     subscriber keeps receiving audio across a service restart.
 *
 * The session is REMOTE unless everything lines up. Every failure -- wrong
 * language, model absent, NPU busy, capture dead, Binder timeout -- ends as a
 * `fail` on the wire, and the phone batch-transcribes the PCM it buffered
 * throughout. Nothing here can strand a session, because the phone is always
 * holding the audio as well.
 */
class LocalSttSession(
    private val router: SttRouter,
    private val segmenter: SttPcmCollector.Segmenter,
    private val dispatcher: SttDispatcher,
    private val transport: Transport,
) {

    /** The glasses -> phone Bluetooth channels, injected so the ordering is testable. */
    interface Transport {
        /** CH_STT_MODE. Must reach the phone BEFORE any audio of this session. */
        fun sendSttMode(mode: String, sessionTag: String)

        /** CH_LOCAL_TRANSCRIPT. [text] may legitimately be "" -- that is a cancel. */
        fun sendLocalTranscript(tag: String, status: String, text: String)
    }

    private companion object { const val TAG = "LocalSttSession" }

    private val utteranceIds = AtomicLong(0L)

    @Volatile private var collector: SttPcmCollector? = null
    @Volatile private var tag: String? = null

    /**
     * Open a session. Announces the mode either way -- the phone needs to hear
     * "remote" just as much as "local", because silence would leave it guessing.
     *
     * @return true when this session is being recognised on the glasses.
     */
    fun begin(sessionTag: String): Boolean {
        val mode = router.beginSession(sessionTag)
        val local = mode == SttRouter.Mode.LOCAL
        // Announced BEFORE subscribing to the mic, so the phone has already
        // decided what to do by the time the first frame exists.
        transport.sendSttMode(
            if (local) SttModeWire.MODE_LOCAL else SttModeWire.MODE_REMOTE, sessionTag
        )
        if (!local) return false

        tag = sessionTag
        val c = SttPcmCollector(segmenter, object : SttPcmCollector.Sink {
            override fun onUtterance(pcm: ShortArray) = onUtteranceReady(pcm)
        })
        collector = c
        MicBus.subscribe(c)
        return true
    }

    /**
     * Close the session and unsubscribe.
     *
     * MicBus is a process singleton, so failing to unsubscribe leaves this
     * collector receiving audio across a ListenerService restart -- pinning the
     * old session's objects and transcribing into a session that no longer
     * exists.
     */
    fun end() {
        collector?.let {
            MicBus.unsubscribe(it)
            it.stop()
        }
        collector = null
        tag = null
        router.endSession()
    }

    /** Runs on the collector's worker thread, never the mic thread. */
    private fun onUtteranceReady(pcm: ShortArray) {
        val sessionTag = tag ?: return
        val id = utteranceIds.incrementAndGet()
        val result = dispatcher.transcribe(pcm, id)
        when (result.status) {
            SttDispatcher.Status.OK ->
                // Includes text == "": an explicit empty final, which the phone
                // reads as the wearer cancelling. It must go out as "ok" with an
                // empty string, NOT as a failure.
                transport.sendLocalTranscript(
                    sessionTag, LocalTranscriptWire.STATUS_OK, result.text
                )

            SttDispatcher.Status.FAIL -> {
                // Sent BEFORE anything else can throw. Every path out of this
                // method must produce a final: if none is sent the phone waits
                // for a transcript that will never come, and the wearer sits in
                // LISTENING. (Caught in test -- logging first meant a throwing
                // logger swallowed the failure final entirely.) The phone still
                // holds this utterance's PCM, so this costs latency, not words.
                transport.sendLocalTranscript(
                    sessionTag, LocalTranscriptWire.STATUS_FAIL, ""
                )
                try {
                    Log.i(TAG, "utt=$id local recognition failed; phone will transcribe")
                } catch (_: Throwable) {
                }
            }
        }
    }
}
