package com.repository.glasses.listener.stt

import com.repository.glasses.listener.capture.MicBus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ordering and lifecycle the rest of the system relies on.
 *
 * Two things here are easy to get subtly wrong and impossible to notice at
 * runtime:
 *
 *  - the mode announcement must reach the phone BEFORE any audio. If it arrives
 *    late the phone has already opened its transcriber, and the utterance gets
 *    recognised twice and delivered twice.
 *  - MicBus is a process SINGLETON. A collector that is not unsubscribed keeps
 *    receiving audio across a service restart, transcribing into a session that
 *    no longer exists.
 */
class LocalSttSessionTest {

    private class FakeTransport : LocalSttSession.Transport {
        val events = ArrayList<String>()
        override fun sendSttMode(mode: String, sessionTag: String) {
            events += "mode:$mode:$sessionTag"
        }
        override fun sendLocalTranscript(tag: String, status: String, text: String) {
            events += "final:$tag:$status:$text"
        }
    }

    private class FakeState(
        override var sttLanguage: String = "ru",
        override var sttAvailable: Boolean = true,
        override var videoActive: Boolean = false,
        override var denoiseActive: Boolean = false,
    ) : SttRouter.State

    /** Emits the accumulated audio on every frame. */
    private class ImmediateSegmenter : SttPcmCollector.Segmenter {
        override fun accept(pcm: ShortArray, offset: Int, length: Int): ShortArray? =
            pcm.copyOfRange(offset, offset + length)
        override fun reset() {}
    }

    private fun dispatcher(result: String?, fail: Boolean = false) = SttDispatcher(
        object : SttDispatcher.Bridge {
            override fun transcribeUtterance(pcm: ByteArray, lang: String, utteranceId: Long) =
                if (fail) null else result
        },
        "ru"
    )

    private var session: LocalSttSession? = null

    @After
    fun tearDown() {
        session?.end()
        assertEquals(
            "MicBus is a process singleton; a leaked subscriber outlives the session",
            0, MicBus.subscriberCount()
        )
    }

    @Test
    fun aRussianSessionWithTheModelPresentGoesLocalAndSaysSoFirst() {
        val t = FakeTransport()
        val s = LocalSttSession(
            SttRouter(FakeState()), ImmediateSegmenter(), dispatcher("привет"), t
        ).also { session = it }

        assertTrue(s.begin(SttRouter.TAG_ASSISTANT))
        assertEquals(
            "the mode must be announced before anything else happens",
            "mode:local:assistant", t.events.first()
        )
    }

    @Test
    fun anEnglishSessionStaysRemoteAndSubscribesToNothing() {
        val t = FakeTransport()
        val s = LocalSttSession(
            SttRouter(FakeState(sttLanguage = "en")), ImmediateSegmenter(), dispatcher("x"), t
        ).also { session = it }

        assertFalse(s.begin(SttRouter.TAG_ASSISTANT))
        assertEquals(listOf("mode:remote:assistant"), t.events)
        assertEquals(
            "a remote session must not attach to the mic at all",
            0, MicBus.subscriberCount()
        )
    }

    @Test
    fun aRemoteSessionIsStillAnnouncedSoThePhoneIsNotLeftGuessing() {
        val t = FakeTransport()
        val s = LocalSttSession(
            SttRouter(FakeState(sttAvailable = false)), ImmediateSegmenter(), dispatcher("x"), t
        ).also { session = it }
        s.begin(SttRouter.TAG_TG_VOICE)
        assertEquals(listOf("mode:remote:tg_voice"), t.events)
    }

    @Test
    fun aLocalSessionSubscribesToTheMicAndUnsubscribesOnEnd() {
        val t = FakeTransport()
        val s = LocalSttSession(
            SttRouter(FakeState()), ImmediateSegmenter(), dispatcher("привет"), t
        )
        s.begin(SttRouter.TAG_ASSISTANT)
        assertEquals(1, MicBus.subscriberCount())
        s.end()
        assertEquals(
            "a leaked subscriber keeps transcribing into a dead session",
            0, MicBus.subscriberCount()
        )
    }

    @Test
    fun aTranscriptIsShippedAsAnOkFinal() {
        val t = FakeTransport()
        val s = LocalSttSession(
            SttRouter(FakeState()), ImmediateSegmenter(), dispatcher("привет"), t
        ).also { session = it }
        s.begin(SttRouter.TAG_ASSISTANT)
        MicBus.emit(ShortArray(1600), 0, 1600, 0L)
        waitForEvent(t, "final:")
        assertTrue(t.events.contains("final:assistant:ok:привет"))
    }

    @Test
    fun anEmptyFinalIsShippedAsOkWithAnEmptyStringNotAsAFailure() {
        // The cancel contract, at the last hop before Bluetooth. Downgrading it
        // to fail would send the phone off to batch-transcribe silence instead of
        // cancelling the notification reply.
        val t = FakeTransport()
        val s = LocalSttSession(
            SttRouter(FakeState()), ImmediateSegmenter(), dispatcher(""), t
        ).also { session = it }
        s.begin(SttRouter.TAG_TG_VOICE)
        MicBus.emit(ShortArray(1600), 0, 1600, 0L)
        waitForEvent(t, "final:")
        assertTrue(
            "an empty final must travel as ok with an empty string",
            t.events.contains("final:tg_voice:ok:")
        )
    }

    @Test
    fun anUnavailableRecogniserShipsAFailSoThePhoneTranscribesItsBuffer() {
        val t = FakeTransport()
        val s = LocalSttSession(
            SttRouter(FakeState()), ImmediateSegmenter(), dispatcher(null, fail = true), t
        ).also { session = it }
        s.begin(SttRouter.TAG_ASSISTANT)
        MicBus.emit(ShortArray(1600), 0, 1600, 0L)
        waitForEvent(t, "final:")
        assertTrue(t.events.contains("final:assistant:fail:"))
    }

    @Test
    fun beginningTwiceDoesNotLeakTheFirstSubscription() {
        // No start path guards against re-entry: a hold-tap while already
        // listening, or a repeated reply START, both reach begin() twice. The
        // first collector would otherwise stay subscribed to MicBus forever,
        // endpoint independently, and fire a second stale final into the live
        // session -- plus leak its worker thread.
        val t = FakeTransport()
        val s = LocalSttSession(
            SttRouter(FakeState()), ImmediateSegmenter(), dispatcher("привет"), t
        ).also { session = it }
        s.begin(SttRouter.TAG_ASSISTANT)
        s.begin(SttRouter.TAG_ASSISTANT)
        assertEquals(
            "a re-entered session must not leave the previous collector subscribed",
            1, MicBus.subscriberCount()
        )
    }

    @Test
    fun theModeIsAnnouncedBeforeTheMicrophoneIsEverTouched() {
        // Ordering, not just occurrence. The phone acts on the announcement by
        // NOT opening its own transcriber; if audio can flow first, the phone has
        // already started and the utterance is recognised and delivered twice.
        val seen = ArrayList<Int>()
        val t = object : LocalSttSession.Transport {
            override fun sendSttMode(mode: String, sessionTag: String) {
                seen += MicBus.subscriberCount()
            }
            override fun sendLocalTranscript(tag: String, status: String, text: String) {}
        }
        val s = LocalSttSession(
            SttRouter(FakeState()), ImmediateSegmenter(), dispatcher("x"), t
        ).also { session = it }
        s.begin(SttRouter.TAG_ASSISTANT)
        assertEquals(
            "the mode must be announced BEFORE subscribing to the mic",
            listOf(0), seen
        )
    }

    @Test
    fun aSessionStartedByOneFeatureIsNotClosedByAnother() {
        // transitionToIdle is reached from watchdogs, TTS finishing, screen-off
        // and dismiss. Closing unconditionally there would tear down a live
        // Telegram capture, whose utterance would then produce NO final at all --
        // not even a failure -- leaving the phone waiting forever.
        val t = FakeTransport()
        val s = LocalSttSession(
            SttRouter(FakeState()), ImmediateSegmenter(), dispatcher("x"), t
        ).also { session = it }
        s.begin(SttRouter.TAG_TG_VOICE)
        assertFalse(
            "the assistant must not close a telegram capture",
            s.endIfOwnedBy(SttRouter.TAG_ASSISTANT)
        )
        assertEquals("the live capture must still hold the mic", 1, MicBus.subscriberCount())
        assertTrue("its own owner closes it", s.endIfOwnedBy(SttRouter.TAG_TG_VOICE))
        assertEquals(0, MicBus.subscriberCount())
    }

    @Test
    fun closingASessionThatWasNeverOpenedIsHarmless() {
        val s = LocalSttSession(
            SttRouter(FakeState()), ImmediateSegmenter(), dispatcher("x"), FakeTransport()
        )
        assertFalse(s.endIfOwnedBy(SttRouter.TAG_ASSISTANT))
    }

    @Test
    fun endingASessionTwiceIsHarmless() {
        val t = FakeTransport()
        val s = LocalSttSession(
            SttRouter(FakeState()), ImmediateSegmenter(), dispatcher("x"), t
        )
        s.begin(SttRouter.TAG_ASSISTANT)
        s.end()
        s.end()
        assertEquals(0, MicBus.subscriberCount())
    }

    private fun waitForEvent(t: FakeTransport, prefix: String) {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            if (t.events.any { it.startsWith(prefix) }) return
            Thread.sleep(10)
        }
        throw AssertionError("no event starting '$prefix'; saw ${t.events}")
    }
}
