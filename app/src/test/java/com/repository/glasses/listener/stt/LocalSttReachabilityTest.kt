package com.repository.glasses.listener.stt

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * An audit found the whole on-glasses recognition stack built, unit-tested, and
 * reachable by NOBODY: no production code constructed the collector or the
 * dispatcher, subscribed to the microphone bus, or sent either Bluetooth
 * channel. Every test passed. Nothing worked.
 *
 * That failure is invisible to ordinary unit tests by construction -- they
 * instantiate the classes themselves, so they keep passing no matter what the
 * service does. These assertions look at the SERVICE instead, and fail if the
 * feature is ever disconnected again.
 *
 * They deliberately assert wiring, not behaviour: behaviour is covered by
 * LocalSttSessionTest and friends. What is checked here is only that the shipped
 * code path reaches them.
 */
class LocalSttReachabilityTest {

    private val service: String by lazy {
        File("src/main/java/com/repository/glasses/listener/service/ListenerService.kt").readText()
    }

    private val bridge: String by lazy {
        File("src/main/java/com/repository/glasses/listener/capture/CaptureBridge.kt").readText()
    }

    private val btClient: String by lazy {
        File("src/main/java/com/repository/glasses/listener/bt/GlassesBtClient.kt").readText()
    }

    @Test
    fun theServiceConstructsTheSession() {
        assertTrue(
            "nothing builds LocalSttSession -- the whole feature would be dead code",
            service.contains("LocalSttSession(")
        )
    }

    @Test
    fun everyInScopeSessionStartOpensARecognitionSession() {
        // Assistant hold, Telegram voice, notification reply and RC dictation.
        // A start path that forgets this leaves the phone unaware, so the phone
        // transcribes it remotely -- silently, with nobody noticing the local
        // model never ran.
        val opens = Regex("beginLocalSttSession\\(").findAll(service).count()
        assertTrue(
            "expected the four in-scope session starts to open a session, found $opens",
            opens >= 4
        )
    }

    @Test
    fun theSessionIsAlsoClosed() {
        // MicBus is a process singleton: a session never closed keeps the
        // collector receiving audio across a service restart.
        assertTrue(
            "no endLocalSttSession call -- the mic subscription would leak",
            service.contains("endLocalSttSession()")
        )
    }

    @Test
    fun theBridgeExposesTheCaptureCalls() {
        for (m in listOf("transcribeUtterance", "isSttAvailable", "prepareStt", "releaseStt")) {
            assertTrue("CaptureBridge must expose $m", bridge.contains("fun $m"))
        }
    }

    @Test
    fun theModelIsWarmedBeforeSpeechIsLikely() {
        // The cold load is ~21 s. With no warm-up the FIRST utterance after a
        // cold start can never be served locally, which quietly makes the
        // feature look broken to anyone who tries it once.
        // Asserted against the BRIDGE call specifically. Matching a bare
        // "prepareStt()" also matched the error-log string in the catch block,
        // so the assertion survived deleting the call itself -- caught by
        // mutating exactly that.
        assertTrue(
            "nothing calls captureBridge.prepareStt() -- the first utterance always falls back",
            service.contains("captureBridge.prepareStt()")
        )
    }

    @Test
    fun bothBluetoothChannelsAreActuallySent() {
        assertTrue("CH_STT_MODE is never published", btClient.contains("BtProtocol.CH_STT_MODE"))
        assertTrue(
            "CH_LOCAL_TRANSCRIPT is never published -- no transcript could ever reach the phone",
            btClient.contains("BtProtocol.CH_LOCAL_TRANSCRIPT")
        )
        assertTrue(service.contains("sendSttMode(") || btClient.contains("fun sendSttMode"))
    }

    @Test
    fun theRouterReadsThePersistedLanguage() {
        // The decision must come from the cached GlassesConfig value, not a
        // literal: hardcoding it would either disable the feature or force it on
        // for English speakers.
        assertTrue(
            "the router must read GlassesConfig.sttLanguage",
            service.contains("GlassesConfig.sttLanguage")
        )
    }
}
