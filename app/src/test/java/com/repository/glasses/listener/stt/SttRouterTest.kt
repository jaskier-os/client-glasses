package com.repository.glasses.listener.stt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plan task 1.2 -- the routing decision table, isolated from all I/O.
 *
 * Routing rule (design section 8), evaluated ONCE per session at beginSession
 * and then LATCHED: local iff the session is in scope AND sttLanguage == "ru"
 * AND the model is available AND neither video nor denoise owns the NPU.
 *
 * The latch matters: flipping mid-utterance would leave the phone half
 * configured (it was told "local", so it opened no transcriber stream and armed
 * no watchdog).
 */
class SttRouterTest {

    private class FakeState(
        override var sttLanguage: String = "ru",
        override var sttAvailable: Boolean = true,
        override var videoActive: Boolean = false,
        override var denoiseActive: Boolean = false,
    ) : SttRouter.State

    private fun router(
        lang: String = "ru",
        available: Boolean = true,
        video: Boolean = false,
        denoise: Boolean = false,
    ): Pair<SttRouter, FakeState> {
        val s = FakeState(lang, available, video, denoise)
        return SttRouter(s) to s
    }

    @Test
    fun russianAvailableAndIdleRoutesLocal() {
        val (r, _) = router()
        assertEquals(SttRouter.Mode.LOCAL, r.beginSession(SttRouter.TAG_ASSISTANT))
    }

    @Test
    fun telegramVoiceIsAlsoInScope() {
        val (r, _) = router()
        assertEquals(SttRouter.Mode.LOCAL, r.beginSession(SttRouter.TAG_TG_VOICE))
    }

    @Test
    fun englishRoutesRemote() {
        val (r, _) = router(lang = "en")
        assertEquals(SttRouter.Mode.REMOTE, r.beginSession(SttRouter.TAG_ASSISTANT))
    }

    @Test
    fun modelUnavailableRoutesRemote() {
        val (r, _) = router(available = false)
        assertEquals(SttRouter.Mode.REMOTE, r.beginSession(SttRouter.TAG_ASSISTANT))
    }

    @Test
    fun videoRecordingRoutesRemote() {
        val (r, _) = router(video = true)
        assertEquals(SttRouter.Mode.REMOTE, r.beginSession(SttRouter.TAG_ASSISTANT))
    }

    @Test
    fun denoiseInProgressRoutesRemote() {
        val (r, _) = router(denoise = true)
        assertEquals(SttRouter.Mode.REMOTE, r.beginSession(SttRouter.TAG_ASSISTANT))
    }

    @Test
    fun outOfScopeTagRoutesRemoteRegardlessOfLanguage() {
        // Teleprompter needs ordered streaming partials -- a hard break with a
        // finals-only local path. It must stay remote even in Russian.
        val (r, _) = router(lang = "ru")
        assertEquals(SttRouter.Mode.REMOTE, r.beginSession("teleprompter"))
    }

    @Test
    fun decisionIsLatchedForTheWholeSession() {
        val (r, s) = router(lang = "ru")
        assertEquals(SttRouter.Mode.LOCAL, r.beginSession(SttRouter.TAG_ASSISTANT))
        // Everything that fed the decision now flips mid-session.
        s.sttLanguage = "en"
        s.sttAvailable = false
        s.videoActive = true
        assertEquals(
            "the decision must not re-evaluate mid-session",
            SttRouter.Mode.LOCAL, r.currentMode()
        )
    }

    @Test
    fun theNextSessionReEvaluatesAfterEndSession() {
        val (r, s) = router(lang = "ru")
        assertEquals(SttRouter.Mode.LOCAL, r.beginSession(SttRouter.TAG_ASSISTANT))
        r.endSession()
        s.sttLanguage = "en"
        assertEquals(SttRouter.Mode.REMOTE, r.beginSession(SttRouter.TAG_ASSISTANT))
    }

    @Test
    fun modeIsRemoteWhenNoSessionIsOpen() {
        val (r, _) = router()
        assertEquals(SttRouter.Mode.REMOTE, r.currentMode())
        r.beginSession(SttRouter.TAG_ASSISTANT)
        r.endSession()
        assertEquals(SttRouter.Mode.REMOTE, r.currentMode())
    }

    @Test
    fun languageMatchIsCaseInsensitiveAndToleratesRegionTags() {
        // The phone pushes whatever the config dropdown holds; a "RU" or "ru-RU"
        // must not silently disable the whole feature.
        assertEquals(SttRouter.Mode.LOCAL, router(lang = "RU").first.beginSession(SttRouter.TAG_ASSISTANT))
        assertEquals(SttRouter.Mode.LOCAL, router(lang = "ru-RU").first.beginSession(SttRouter.TAG_ASSISTANT))
    }

    @Test
    fun beginSessionWhileAlreadyOpenKeepsTheFirstDecision() {
        val (r, s) = router(lang = "ru")
        assertEquals(SttRouter.Mode.LOCAL, r.beginSession(SttRouter.TAG_ASSISTANT))
        s.sttLanguage = "en"
        // A duplicate begin (racing triggers: wake word then hold gesture) must
        // not re-decide underneath a live session.
        assertEquals(SttRouter.Mode.LOCAL, r.beginSession(SttRouter.TAG_ASSISTANT))
    }
}
