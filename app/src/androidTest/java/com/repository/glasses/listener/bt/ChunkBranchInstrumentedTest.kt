package com.repository.glasses.listener.bt

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.repository.glasses.listener.service.ListenerService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The runtime proof that real chunked traffic from the PAIRED PHONE reassembles cleanly.
 *
 * This test is what made task 37 safe to run. Before the legacy branch was deleted it asserted
 * that every stream took branch=STREAM_ID -- evidence, not inference, that the transitional path
 * was dead in the field. It ran green on hardware across three channels
 * (listener_todo_list_resp, listener_chat_list_resp, listener_alarm_list_resp) with zero legacy
 * hits, and only then was the branch removed.
 *
 * It is kept, now guarding the stricter post-deletion contract: the assembler recognises exactly
 * one layout and refuses anything else, so a framing regression on the phone would show up here as
 * chunk_unframed rather than as a silently torn payload on a user's screen.
 *
 * It asserts:
 *   - at least one stream was actually opened, so a silent no-op cannot pass as a clean run,
 *   - no stream was refused (chunk_unframed) or dropped (gap, orphan, stale, overflow).
 *
 * This needs a paired, awake phone running a build from this branch. Without one no stream opens
 * and the first assertion fails loudly rather than reporting a green run on no data.
 */
@RunWith(AndroidJUnit4::class)
class ChunkBranchInstrumentedTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val device: UiDevice = UiDevice.getInstance(instr)
    private val tag = "ChunkBranch"
    private val pkg = "com.repository.glasses.listener"

    /** Matches the line GlassesBtClient writes on the first frame of every stream. */
    private val OPENED = Regex("""Chunk stream opened on (\S+)""")
    private val DROPPED = Regex("""Chunk stream dropped on (\S+): (\S+)""")

    /**
     * Reads the service's own log. btLog mirrors every line to logcat under GlassesListenerSvc,
     * which is readable here without root and without the WiFi-Direct log pull.
     */
    private fun serviceLogSince(marker: String): List<String> {
        val out = instr.uiAutomation
            .executeShellCommand("logcat -d -s GlassesListenerSvc:I")
            .let { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() }
            }
        val idx = out.lastIndexOf(marker)
        return (if (idx >= 0) out.substring(idx) else out).lines()
    }

    private fun mark(what: String): String {
        val m = "CHUNKBRANCH-MARK-${System.nanoTime()}-$what"
        // Any btLog-carried line works as a fence; a request the service always logs is simplest.
        Log.i("GlassesListenerSvc", m)
        SystemClock.sleep(300)
        return m
    }

    private fun requestTgChatList() {
        ctx.sendBroadcast(Intent(ListenerService.ACTION_REQUEST_TG_CHAT_LIST).apply {
            setPackage(pkg)
            putExtra(ListenerService.EXTRA_TG_LIMIT, 30)
        })
    }

    private fun request(action: String) {
        ctx.sendBroadcast(Intent(action).apply { setPackage(pkg) })
    }

    /**
     * Every channel the phone genuinely chunks over (PhoneBtHost.sendChunkedJson call sites) that
     * the glasses can ask for unprompted. Driving all of them makes the proof cover the whole
     * chunked surface, not just whichever one happened to answer first.
     */
    private fun requestEveryChunkedChannel() {
        requestTgChatList()
        request(ListenerService.ACTION_REQUEST_CHAT_LIST)
        request(ListenerService.ACTION_REQUEST_TODO_LIST)
        request(ListenerService.ACTION_REQUEST_ALARM_LIST)
        request(ListenerService.ACTION_REQUEST_JOB_LIST)
    }

    @Test
    fun everyChunkedStreamFromThePhoneReassemblesCleanly() {
        instr.uiAutomation.executeShellCommand("am start -n $pkg/.MainActivity")
        SystemClock.sleep(3000)
        device.waitForIdle()

        val fence = mark("start")

        // Two overlapping requests on the SAME channel: this is both the concurrency case the
        // rewrite exists to fix and the case where a legacy fallback would be most visible.
        requestEveryChunkedChannel()
        SystemClock.sleep(250)
        requestEveryChunkedChannel()

        // Chunked history over RFCOMM is slow by design (the phone sleeps between chunks), so the
        // wait is generous and does NOT stop at the first stream: stopping early would prove only
        // that ONE channel is on the new path, which is not the claim being made.
        var lines: List<String> = emptyList()
        repeat(30) {
            SystemClock.sleep(1500)
            lines = serviceLogSince(fence)
        }

        val opened = lines.mapNotNull { OPENED.find(it) }.map { it.groupValues[1] }
        val dropped = lines.mapNotNull { DROPPED.find(it) }
            .map { it.groupValues[1] to it.groupValues[2] }

        opened.forEach { Log.i(tag, "PROOF opened channel=$it") }
        dropped.forEach { Log.i(tag, "PROOF dropped channel=${it.first} reason=${it.second}") }

        assertTrue(
            "no chunk stream opened at all -- the phone is not answering, so this run proves " +
                "NOTHING about the framing. Do not read a pass into it.",
            opened.isNotEmpty()
        )
        assertEquals(
            "the phone sent a frame this build refuses to reassemble, so a real payload was lost " +
                "rather than silently torn: $dropped",
            emptyList<Pair<String, String>>(),
            dropped.filter { it.second == "chunk_unframed" }
        )
        assertEquals(
            "a stream was dropped during a normal run: $dropped",
            emptyList<Pair<String, String>>(),
            dropped
        )
    }
}
