package com.repository.glasses.listener.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Plan task 3.5 -- what happens between "the VAD says speech ended" and "the
 * phone has a transcript".
 *
 * Binder has NO client-side timeout, so a wedged capture process would hang the
 * utterance forever and the wearer would sit in LISTENING with no way out. The
 * dispatcher therefore bounds every call and falls back.
 *
 * The three outcomes map onto the wire status the phone acts on:
 *   ok + text -- deliver it,
 *   ok + ""   -- an explicit empty final. This is the CANCEL signal; it must
 *                survive as an empty string and never be upgraded to a failure,
 *                or a notification reply hangs in SENDING forever,
 *   fail      -- the phone batch-transcribes the PCM it buffered. Note it still
 *                HAS that audio: the PCM never left the listener until now, so a
 *                capture death costs only the in-flight call.
 */
class SttDispatcherTest {

    private class FakeBridge(
        val delayMs: Long = 0,
        val result: String? = "привет",
        val throws: Boolean = false,
    ) : SttDispatcher.Bridge {
        var calls = 0
        val entered = CountDownLatch(1)
        override fun transcribeUtterance(pcm: ByteArray, lang: String, utteranceId: Long): String? {
            calls++
            entered.countDown()
            if (delayMs > 0) Thread.sleep(delayMs)
            if (throws) throw IllegalStateException("capture died")
            return result
        }
    }

    private fun pcm(seconds: Double): ShortArray = ShortArray((16_000 * seconds).toInt())

    @Test
    fun aTranscriptIsReportedAsOk() {
        val d = SttDispatcher(FakeBridge(result = "привет"), "ru")
        val r = d.transcribe(pcm(3.0), 1L)
        assertEquals(SttDispatcher.Status.OK, r.status)
        assertEquals("привет", r.text)
    }

    @Test
    fun anEmptyFinalStaysAnEmptyStringAndIsStillOk() {
        // THE cancel contract. Downgrading this to FAIL would send the phone off
        // to batch-transcribe silence; upgrading it to a missing arg would strand
        // a notification reply in SENDING.
        val d = SttDispatcher(FakeBridge(result = ""), "ru")
        val r = d.transcribe(pcm(3.0), 1L)
        assertEquals(
            "an empty final is a deliberate CANCEL, not a failure",
            SttDispatcher.Status.OK, r.status
        )
        assertEquals("", r.text)
    }

    @Test
    fun aNullResultMeansUnavailableAndFallsBackToRemote() {
        val d = SttDispatcher(FakeBridge(result = null), "ru")
        val r = d.transcribe(pcm(3.0), 1L)
        assertEquals(SttDispatcher.Status.FAIL, r.status)
    }

    @Test
    fun aWedgedCaptureTimesOutRatherThanHangingTheUtterance() {
        val bridge = FakeBridge(delayMs = 10_000)
        val d = SttDispatcher(bridge, "ru", shortTimeoutMs = 300, longTimeoutMs = 300)
        val t0 = System.nanoTime()
        val r = d.transcribe(pcm(3.0), 7L)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertEquals(SttDispatcher.Status.FAIL, r.status)
        assertTrue("returned after ${elapsedMs}ms; the timeout must bound it",
            elapsedMs < 3_000)
        assertTrue(bridge.entered.await(1, TimeUnit.SECONDS))
        d.shutdown()
    }

    @Test
    fun aLateResultFromATimedOutCallIsDiscardedRatherThanDeliveredToTheNextSession() {
        // The utterance it belonged to is over. Delivering it would put the
        // previous sentence into the current session's bubble.
        val bridge = FakeBridge(delayMs = 600, result = "stale")
        val d = SttDispatcher(bridge, "ru", shortTimeoutMs = 100, longTimeoutMs = 100)
        val first = d.transcribe(pcm(3.0), 1L)
        assertEquals(SttDispatcher.Status.FAIL, first.status)
        assertEquals("the timed-out transcript must not be returned", "", first.text)
        Thread.sleep(800)  // the late result lands here
        // Asserted POSITIVELY: an empty list here would also be produced by the
        // result never arriving at all, which would prove nothing about
        // suppression. We require evidence that it arrived AND was dropped.
        assertEquals(
            "the late result must be observed and suppressed, keyed by its utterance id",
            listOf(1L), d.drainSuppressedLateResults()
        )
        d.shutdown()
    }

    @Test
    fun aThrowingBridgeIsAFailureNotACrash() {
        // Binder throws DeadObjectException when capture is force-stopped. That
        // must become a remote fallback, not an exception on the worker.
        val d = SttDispatcher(FakeBridge(throws = true), "ru")
        assertEquals(SttDispatcher.Status.FAIL, d.transcribe(pcm(3.0), 1L).status)
    }

    @Test
    fun aLongerUtteranceGetsTheLongerTimeout() {
        // A 12 s utterance is three encoder windows at ~1.1 s each; timing it out
        // on the 5 s budget would fail every long sentence.
        assertEquals(
            SttDispatcher.LONG_TIMEOUT_MS,
            SttDispatcher.timeoutFor(pcm(12.0).size)
        )
        assertEquals(
            SttDispatcher.SHORT_TIMEOUT_MS,
            SttDispatcher.timeoutFor(pcm(5.0).size)
        )
    }

    @Test
    fun theLanguageIsPassedThroughUnchanged() {
        // Capture refuses anything but Russian. Rewriting the tag here would
        // either disable the feature or run a Russian model over English.
        var seen: String? = null
        val bridge = object : SttDispatcher.Bridge {
            override fun transcribeUtterance(pcm: ByteArray, lang: String, utteranceId: Long): String? {
                seen = lang
                return "x"
            }
        }
        SttDispatcher(bridge, "ru-RU").transcribe(pcm(1.0), 1L)
        assertEquals("ru-RU", seen)
    }

    @Test
    fun pcmIsHandedOverAsLittleEndianInt16() {
        // The AIDL contract is little-endian int16 mono 16 kHz. Getting the byte
        // order wrong produces noise the model transcribes as confident garbage
        // rather than any kind of error.
        var bytes: ByteArray? = null
        val bridge = object : SttDispatcher.Bridge {
            override fun transcribeUtterance(pcm: ByteArray, lang: String, utteranceId: Long): String? {
                bytes = pcm
                return "x"
            }
        }
        SttDispatcher(bridge, "ru").transcribe(shortArrayOf(0x0102, -2), 1L)
        assertEquals(4, bytes!!.size)
        assertEquals(0x02.toByte(), bytes!![0])
        assertEquals(0x01.toByte(), bytes!![1])
        assertEquals(0xFE.toByte(), bytes!![2])
        assertEquals(0xFF.toByte(), bytes!![3])
    }

    @Test
    fun anUtteranceOverTheBinderLimitFailsRatherThanBeingSent() {
        // 384000 bytes is the AIDL ceiling; the 1 MB transaction buffer is shared
        // with camera JPEGs, so oversizing it would throw
        // TransactionTooLargeException on an unrelated photo.
        val bridge = FakeBridge()
        val d = SttDispatcher(bridge, "ru")
        val r = d.transcribe(pcm(13.0), 1L)
        assertEquals(SttDispatcher.Status.FAIL, r.status)
        assertEquals("an oversize payload must not reach the Binder at all", 0, bridge.calls)
    }
    @Test
    fun aColdModelIsGrantedTheLoadTimeItActuallyNeeds() {
        // Measured on device: the first call after eviction spends ~21 s mapping
        // the 231 MB context binary before it can answer. Budgeting the warm cost
        // abandoned every first utterance -- the transcript completed and arrived
        // as a LATE result that was dropped -- and since the model unloads when
        // idle, the next attempt was cold again and local STT never won once.
        val warm = SttDispatcher.timeoutFor(16_000, modelWarm = true)
        val cold = SttDispatcher.timeoutFor(16_000, modelWarm = false)
        assertTrue(
            "a cold call must outlast the measured 21s load, got ${cold}ms",
            cold >= 21_000L
        )
        assertTrue("cold must exceed warm", cold > warm)
    }

}
