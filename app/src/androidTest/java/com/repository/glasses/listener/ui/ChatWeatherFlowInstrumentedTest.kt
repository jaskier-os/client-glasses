package com.repository.glasses.listener.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Drives a realistic AI conversation on the glasses chat tab with MOCKED
 * orchestrator traffic and records it.
 *
 * Conversation: user asks "Tell me what's the current weather?", the assistant
 * runs a weather lookup tool, streams an answer in, then settles.
 *
 * Nothing talks to the real ListenerService backend or the orchestrator. The
 * test broadcasts the exact intents the backend emits
 * (ACTION_STATE_UPDATE, ACTION_CHAT_MESSAGE, ACTION_TOOL_STATUS,
 * ACTION_STREAMING_TEXT), all in-process (setPackage to self), so they flow
 * through MainActivity's real receivers + ChatAdapter and render exactly as
 * production would. The LISTENING state auto-focuses the CHAT tab, so no manual
 * tab navigation is needed.
 *
 * SystemClock.sleep holds between steps keep each rendered state on screen long
 * enough for an external screen recording to capture the whole flow. A PNG is
 * also saved per step.
 */
@RunWith(AndroidJUnit4::class)
class ChatWeatherFlowInstrumentedTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val device: UiDevice = UiDevice.getInstance(instr)
    private val tag = "ChatWeatherFlow"
    private val pkg = "com.repository.glasses.listener"

    // Mirror of ListenerService action/extra constants (kept local to avoid a
    // visibility dependency; values must match ListenerService).
    private val ACTION_STATE = "$pkg.STATE_UPDATE";        private val EXTRA_STATE = "state"
    private val ACTION_CHAT = "$pkg.CHAT_MESSAGE";         private val EXTRA_CHAT = "chat_message"
    private val ACTION_TOOL = "$pkg.TOOL_STATUS";          private val EXTRA_TOOL = "tool_status"
    private val ACTION_STREAM = "$pkg.STREAMING_TEXT";     private val EXTRA_STREAM = "streaming_text"

    private val reqId = "weather-001"
    private val question = "Tell me what's the current weather?"
    private val answer = "It's currently 18C and partly cloudy in San Francisco, " +
        "with light winds from the west. Expect a high of 21C this afternoon and " +
        "clear skies by evening."

    private fun artifactDir(): File =
        File(ctx.getExternalFilesDir(null), "chat-weather").apply { if (!exists()) mkdirs() }

    private fun shoot(step: String) {
        val bmp: Bitmap? = instr.uiAutomation.takeScreenshot()
        assertNotNull("takeScreenshot null at $step", bmp)
        FileOutputStream(File(artifactDir(), "$step.png")).use {
            bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        Log.i(tag, "saved $step (${bmp!!.width}x${bmp.height})")
    }

    private fun state(s: String) {
        Log.i(tag, "state -> $s")
        ctx.sendBroadcast(Intent(ACTION_STATE).apply { setPackage(pkg); putExtra(EXTRA_STATE, s) })
    }

    private fun chat(role: String, text: String) {
        val json = """{"requestId":"$reqId","role":"$role","text":${jsonStr(text)}}"""
        ctx.sendBroadcast(Intent(ACTION_CHAT).apply { setPackage(pkg); putExtra(EXTRA_CHAT, json) })
    }

    private fun tool(name: String, query: String, status: String) {
        val json = """{"requestId":"$reqId","toolName":"$name","status":"$status",""" +
            """"toolCallId":"tc-1","toolArgs":{"query":${jsonStr(query)}}}"""
        ctx.sendBroadcast(Intent(ACTION_TOOL).apply { setPackage(pkg); putExtra(EXTRA_TOOL, json) })
    }

    private fun stream(partial: String) {
        val json = """{"requestId":"$reqId","partialText":${jsonStr(partial)}}"""
        ctx.sendBroadcast(Intent(ACTION_STREAM).apply { setPackage(pkg); putExtra(EXTRA_STREAM, json) })
    }

    private fun jsonStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    @Test
    fun weatherConversationFlow() {
        instr.uiAutomation.executeShellCommand("am start -n $pkg/.MainActivity")
        SystemClock.sleep(2800)
        device.waitForIdle()

        // 1. Session starts: backend goes LISTENING. This auto-focuses the CHAT
        //    tab and shows the mic status + audio visualizer.
        state("LISTENING")
        SystemClock.sleep(1800)
        shoot("1_listening")

        // 2. The recognized user utterance lands as a USER chat message.
        chat("USER", question)
        SystemClock.sleep(2200)
        shoot("2_user_question")

        // 3. Backend starts producing a response.
        state("RESPONDING")
        SystemClock.sleep(900)

        // 4. Assistant invokes a weather lookup tool (in-progress chip).
        tool("get_weather", "current weather San Francisco", "running")
        SystemClock.sleep(2600)
        shoot("3_tool_running")
        tool("get_weather", "current weather San Francisco", "complete")
        SystemClock.sleep(600)

        // 5. Assistant streams its answer in a few chunks.
        val words = answer.split(" ")
        val sb = StringBuilder()
        var shotMid = false
        for ((i, w) in words.withIndex()) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(w)
            // Push a partial roughly every 3 words for a visible typing effect.
            if (i % 3 == 0 || i == words.lastIndex) {
                stream(sb.toString())
                SystemClock.sleep(260)
                if (!shotMid && sb.length > answer.length / 2) {
                    shoot("4_streaming")
                    shotMid = true
                }
            }
        }
        SystemClock.sleep(700)

        // 6. Final assistant message replaces the stream and the answer settles.
        chat("ASSISTANT", answer)
        SystemClock.sleep(2600)
        shoot("5_answer")

        // Hold the finished conversation so the recording lingers on it.
        SystemClock.sleep(2500)

        // 7. Session ends.
        state("IDLE")
        SystemClock.sleep(1500)
        shoot("6_idle")

        Log.i(tag, "weatherConversationFlow: done -> ${artifactDir().absolutePath}")
    }
}
