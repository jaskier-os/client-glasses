package com.repository.glasses.listener.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan task 1.6 -- the glasses->phone wire for a locally-produced transcript.
 *
 * The single most likely silent breakage in this whole feature lives here: an
 * EMPTY transcript is meaningful. It is the wearer cancelling, and the phone acts
 * on it by clearing a pending notification reply. Any encoding that collapses ""
 * into a missing argument turns a cancel into nothing at all, and the reply hangs
 * in SENDING forever with no error anywhere.
 *
 * The decoder must also never throw: it runs on a Binder thread, where an
 * uncaught exception kills the service outright.
 */
class LocalTranscriptWireTest {

    @Test
    fun aNormalTranscriptRoundTrips() {
        val args = LocalTranscriptWire.encode("assistant", "ok", "привет")
        val m = LocalTranscriptWire.decode(args)!!
        assertEquals("assistant", m.tag)
        assertEquals("ok", m.status)
        assertEquals("привет", m.text)
        assertTrue(m.isOk)
    }

    @Test
    fun anEmptyTranscriptSurvivesAsAnEmptyStringAndNotAsAMissingArgument() {
        // THE contract. "" here is the wearer cancelling; the phone reads it as
        // such and clears the pending notification reply.
        val args = LocalTranscriptWire.encode("tg_voice", "ok", "")
        assertEquals("the empty text must occupy its argument slot", 3, args.size)
        assertEquals("", args[2])
        val m = LocalTranscriptWire.decode(args)!!
        assertEquals("tg_voice", m.tag)
        assertTrue(m.isOk)
        assertEquals("an empty final must decode as \"\", never as null", "", m.text)
    }

    @Test
    fun aFailureStatusRoundTrips() {
        // fail means "local STT could not do it": the phone batch-transcribes the
        // PCM it buffered. It must be distinguishable from an empty final.
        val m = LocalTranscriptWire.decode(
            LocalTranscriptWire.encode("tg_voice", "fail", "")
        )!!
        assertEquals("fail", m.status)
        assertTrue(!m.isOk)
    }

    @Test
    fun anEmptyOkAndAnEmptyFailAreNotConfusable() {
        // Both carry no text, and they mean opposite things: cancel the session,
        // versus go and transcribe it remotely.
        val ok = LocalTranscriptWire.decode(LocalTranscriptWire.encode("tg_voice", "ok", ""))!!
        val fail = LocalTranscriptWire.decode(LocalTranscriptWire.encode("tg_voice", "fail", ""))!!
        assertTrue(ok.isOk)
        assertTrue(!fail.isOk)
    }

    @Test
    fun aTruncatedMessageIsRefusedRatherThanThrowing() {
        // onMessage runs on a Binder thread; an uncaught throw kills the service.
        assertNull(LocalTranscriptWire.decode(emptyList()))
        assertNull(LocalTranscriptWire.decode(listOf("tg_voice")))
        assertNull(LocalTranscriptWire.decode(listOf("tg_voice", "ok")))
    }

    @Test
    fun extraTrailingArgumentsAreIgnoredForForwardCompatibility() {
        val m = LocalTranscriptWire.decode(
            listOf("tg_voice", "ok", "привет", "something-new")
        )!!
        assertEquals("привет", m.text)
    }

    @Test
    fun anUnknownStatusIsTreatedAsAFailureRatherThanAsSuccess() {
        // Failing open here would deliver whatever text a garbled frame carried
        // as though it were a confirmed transcript.
        val m = LocalTranscriptWire.decode(listOf("tg_voice", "weird", "привет"))!!
        assertTrue("an unrecognised status must not count as ok", !m.isOk)
    }

    @Test
    fun textContainingTheArgumentSeparatorSurvives() {
        // Russian speech with punctuation regularly contains commas and pipes are
        // used as separators by some framers; the text must arrive intact.
        val text = "привет, как дела? | всё хорошо"
        assertEquals(text, LocalTranscriptWire.decode(
            LocalTranscriptWire.encode("assistant", "ok", text)
        )!!.text)
    }

    @Test
    fun theChannelNamesAreStable() {
        // These strings ARE the protocol. Renaming one on a single side silently
        // stops the channel being delivered at all.
        assertEquals("listener_local_transcript", BtProtocol.CH_LOCAL_TRANSCRIPT)
        assertEquals("listener_stt_mode", BtProtocol.CH_STT_MODE)
        assertEquals("listener_stt_capability", BtProtocol.CH_STT_CAPABILITY)
    }

    // ---- CH_STT_MODE ----

    @Test
    fun theModeAnnouncementRoundTrips() {
        val m = SttModeWire.decode(SttModeWire.encode("local", "assistant"))!!
        assertEquals("local", m.mode)
        assertEquals("assistant", m.sessionTag)
        assertTrue(m.isLocal)
    }

    @Test
    fun anUnknownModeIsTreatedAsRemote() {
        // The phone must keep doing what it does today unless it is explicitly
        // and correctly told the glasses have taken over. Failing open would
        // leave NOBODY transcribing.
        assertTrue(!SttModeWire.decode(listOf("gibberish", "assistant"))!!.isLocal)
        assertNull(SttModeWire.decode(listOf("local")))
    }
}
