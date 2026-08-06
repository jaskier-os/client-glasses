package com.repository.glasses.listener.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Renders an RC session thread on the real device, and drives voice and prompts through the real
 * key handlers.
 *
 * Frames are injected the way the backend delivers them -- in-process broadcasts with
 * setPackage(self) -- so the production receivers, RcThreadModel, RcVoiceGate and RcThreadAdapter
 * all run exactly as they do in the field. Navigation is by keycode, never by coordinate tap: the
 * waveguide has no touchscreen, so a tap would drive an input path this device does not have.
 *
 * The load-bearing assertions are the refusals: hold-to-speak must be blocked while the session is
 * turning, while the orchestrator link is down, and while a prompt is waiting -- and each block
 * must be VISIBLE, never silent.
 *
 * Every rendered state is held 2-3 s so an external screen recording is a usable deliverable.
 */
@RunWith(AndroidJUnit4::class)
class RcThreadInstrumentedTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val device: UiDevice = UiDevice.getInstance(instr)
    private val tag = "RcThread"
    private val pkg = "com.repository.glasses.listener"

    // Mirrors of the ListenerService constants, kept local as the existing suites do.
    private val ACTION_RC_STATE = "$pkg.RC_STATE"
    private val ACTION_RC_MESSAGES = "$pkg.RC_MESSAGES"
    private val ACTION_RC_SEND_RESULT = "$pkg.RC_SEND_RESULT"
    private val ACTION_PARTIAL_TEXT = "$pkg.PARTIAL_TEXT"
    private val ACTION_USER_TEXT = "$pkg.USER_TEXT"
    private val ACTION_CHAT_LIST = "$pkg.CHAT_LIST"
    private val EXTRA_RC_STATE_JSON = "rc_state_json"
    private val EXTRA_RC_SESSION_ID = "rc_session_id"
    private val EXTRA_RC_MESSAGES_JSON = "rc_messages_json"
    private val EXTRA_RC_CLIENT_MSG_ID = "rc_client_msg_id"
    private val EXTRA_RC_STATUS = "rc_status"
    private val EXTRA_PARTIAL_TEXT = "partial_text"
    private val EXTRA_USER_TEXT = "user_text"
    private val EXTRA_USER_TEXT_REQUEST_ID = "user_text_request_id"
    private val EXTRA_CHAT_LIST = "chat_list"

    private val HOLD_MS = 2600L
    private val SESSION = "s-live"

    private fun artifactDir(): File =
        File(ctx.getExternalFilesDir(null), "rc-thread").apply { if (!exists()) mkdirs() }

    private fun shoot(step: String) {
        val bmp: Bitmap? = instr.uiAutomation.takeScreenshot()
        assertNotNull("takeScreenshot null at $step", bmp)
        FileOutputStream(File(artifactDir(), "$step.png")).use {
            bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        Log.i(tag, "saved $step")
    }

    // -- frame injection --

    private fun session(
        id: String = SESSION,
        name: String = "fix rfcomm idle teardown",
        folder: String = "~/AI/clients/glasses",
        turning: Boolean = false,
        ended: Boolean = false,
    ) = """{"id":"$id","n":"$name","w":"$folder","st":"${if (ended) "ended" else "open"}",""" +
        """"t":$turning,"u":false,"q":7}"""

    private fun pushState(ws: Boolean, vararg sessions: String) {
        ctx.sendBroadcast(Intent(ACTION_RC_STATE).apply {
            setPackage(pkg)
            putExtra(EXTRA_RC_STATE_JSON, """{"ws":$ws,"s":[${sessions.joinToString(",")}]}""")
        })
        device.waitForIdle()
        SystemClock.sleep(300)
    }

    private fun pushRows(json: String, sessionId: String = SESSION) {
        ctx.sendBroadcast(Intent(ACTION_RC_MESSAGES).apply {
            setPackage(pkg)
            putExtra(EXTRA_RC_SESSION_ID, sessionId)
            putExtra(EXTRA_RC_MESSAGES_JSON, json)
        })
        device.waitForIdle()
        SystemClock.sleep(300)
    }

    private fun pushSendResult(status: String, clientMsgId: String = "") {
        ctx.sendBroadcast(Intent(ACTION_RC_SEND_RESULT).apply {
            setPackage(pkg)
            putExtra(EXTRA_RC_SESSION_ID, SESSION)
            putExtra(EXTRA_RC_CLIENT_MSG_ID, clientMsgId)
            putExtra(EXTRA_RC_STATUS, status)
        })
        device.waitForIdle()
    }

    private fun pushPartial(text: String) {
        ctx.sendBroadcast(Intent(ACTION_PARTIAL_TEXT).apply {
            setPackage(pkg)
            putExtra(EXTRA_PARTIAL_TEXT, text)
        })
        device.waitForIdle()
        SystemClock.sleep(200)
    }

    /** The phone's final transcript. "tg_voice" is the shared dictation request id. */
    private fun pushFinalTranscript(text: String) {
        ctx.sendBroadcast(Intent(ACTION_USER_TEXT).apply {
            setPackage(pkg)
            putExtra(EXTRA_USER_TEXT_REQUEST_ID, "tg_voice")
            putExtra(EXTRA_USER_TEXT, text)
        })
        device.waitForIdle()
        SystemClock.sleep(300)
    }

    private fun rowsFrame(rows: String, more: Boolean = false, lastSeq: Long = -1) =
        """{"rows":[$rows],"more":$more,"lastSeq":$lastSeq}"""

    // -- view access --

    private lateinit var listRecycler: RecyclerView
    private lateinit var listAdapter: ChatListAdapter
    private lateinit var threadRecycler: RecyclerView
    private lateinit var threadAdapter: RcThreadAdapter

    private fun <T> onUi(block: () -> T): T {
        var result: T? = null
        var error: Throwable? = null
        instr.runOnMainSync {
            try { result = block() } catch (t: Throwable) { error = t }
        }
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun root(): View = ActivityLifecycleMonitorRegistry.getInstance()
        .getActivitiesInStage(Stage.RESUMED).first().window.decorView

    private fun view(id: Int): View = onUi { root().findViewById<View>(id) }

    private fun items(): List<RcThreadItem> = onUi { threadAdapter.currentItems }

    private fun threadRows(): List<RcThreadRow> =
        items().filterIsInstance<RcThreadItem.Row>().map { it.row }

    private fun findByTag(root: View, wanted: String): View? {
        if (root.tag == wanted) return root
        if (root !is ViewGroup) return null
        for (i in 0 until root.childCount) findByTag(root.getChildAt(i), wanted)?.let { return it }
        return null
    }

    private fun allByTag(root: View, wanted: String, out: MutableList<View> = mutableListOf()):
        List<View> {
        if (root.tag == wanted) out.add(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) {
            allByTag(root.getChildAt(i), wanted, out)
        }
        return out
    }

    private fun promptOptionTexts(): List<String> = onUi {
        allByTag(threadRecycler, RcThreadAdapter.TAG_PROMPT_OPTION)
            .map { (it as TextView).text.toString() }
    }

    private fun key(code: Int) {
        device.pressKeyCode(code)
        SystemClock.sleep(350)
        device.waitForIdle()
    }

    // -- the test --

    @Test
    fun rcThreadFlow() {
        instr.uiAutomation.executeShellCommand("am start -n $pkg/.MainActivity")
        SystemClock.sleep(2800)
        device.waitForIdle()
        locate()
        navigateToChatListTab()

        pushChatList("c1")
        pushState(true, session())
        openTheRcSession()

        // 1. Rows render from a CH_RC_MESSAGES_RESP frame, tools collapsed to a glyph + count.
        pushRows(
            rowsFrame(
                """{"q":1,"r":"user","x":"why does the link drop after 36s idle"},""" +
                    """{"q":2,"r":"tools","x":"Read, Grep","c":4},""" +
                    """{"q":3,"r":"assistant","x":"WiFi-Direct tears the group down after ~36s."}""",
                lastSeq = 3
            )
        )
        SystemClock.sleep(HOLD_MS)
        shoot("1_thread_rendered")
        assertEquals(listOf(1L, 2L, 3L), threadRows().map { it.seq })
        val toolLine = onUi { findByTag(threadRecycler, RcThreadAdapter.TAG_TOOL_COLLAPSE) }
        assertNotNull("a tool run must collapse to one line", toolLine)
        assertTrue("the collapsed tool line carries its count",
            (toolLine as TextView).text.toString().contains("4"))

        // 2. A second, OVERLAPPING frame appends without duplicating.
        pushRows(
            rowsFrame(
                """{"q":3,"r":"assistant","x":"WiFi-Direct tears the group down after ~36s."},""" +
                    """{"q":4,"r":"assistant","x":"Added a 20s ping frame on CH_PING."}""",
                lastSeq = 4
            )
        )
        SystemClock.sleep(HOLD_MS)
        shoot("2_appended_no_duplicate")
        assertEquals("the overlapping row must not render twice",
            listOf(1L, 2L, 3L, 4L), threadRows().map { it.seq })

        // 3. more:true prepends the earlier-messages marker.
        pushRows(rowsFrame("""{"q":5,"r":"assistant","x":"Rebuilt clean."}""", more = true,
            lastSeq = 5))
        SystemClock.sleep(HOLD_MS)
        shoot("3_more_above")
        assertEquals(RcThreadItem.EarlierOnPhone, items().first())
        assertNotNull("the marker must actually render",
            onUi { findByTag(threadRecycler, RcThreadAdapter.TAG_EARLIER) })

        // 4. A frame for ANOTHER session is dropped rather than rendered under this session's name.
        val before = threadRows().size
        pushRows(rowsFrame("""{"q":99,"r":"assistant","x":"belongs to another session"}"""),
            sessionId = "s-other")
        SystemClock.sleep(1200)
        shoot("4_wrong_session_dropped")
        assertEquals("a wrong-session frame must change nothing", before, threadRows().size)

        // 5. Hold-to-speak is REFUSED while the session is turning, and the block is visible:
        //    the spinner runs and the mic affordance is gone.
        pushState(true, session(turning = true))
        SystemClock.sleep(HOLD_MS)
        shoot("5_turning_refuses_voice")
        assertEquals("a turning session shows the spinner",
            View.VISIBLE, view(R_rcThreadSpinnerSlot()).visibility)
        assertEquals("the mic affordance is absent while turning",
            View.GONE, view(R_rcThreadMicGlyph()).visibility)
        assertHint("working")
        key(KeyEvent.KEYCODE_NUMPAD_3)
        assertEquals("a refused hold must not start a capture",
            View.GONE, view(R_rcThreadVoiceBar()).visibility)

        // 6. Hold-to-speak is REFUSED while the orchestrator link is down.
        pushState(false, session())
        SystemClock.sleep(HOLD_MS)
        shoot("6_ws_down_refuses_voice")
        assertEquals(View.GONE, view(R_rcThreadMicGlyph()).visibility)
        assertHint("agent offline")
        key(KeyEvent.KEYCODE_NUMPAD_3)
        assertEquals(View.GONE, view(R_rcThreadVoiceBar()).visibility)

        // 7. Idle and live: the mic affordance is offered.
        pushState(true, session())
        SystemClock.sleep(HOLD_MS)
        shoot("7_mic_offered")
        assertEquals(View.VISIBLE, view(R_rcThreadMicGlyph()).visibility)

        // 8. A prompt renders its options; voice is disabled while they are present.
        pushRows(rowsFrame(
            """{"q":6,"r":"prompt","x":"Deploy to glasses now?","o":["Yes","No"],"i":"req-1"}""",
            lastSeq = 6))
        SystemClock.sleep(HOLD_MS)
        shoot("8_prompt_options")
        assertNotNull("a blocking prompt renders in a bordered box",
            onUi { findByTag(threadRecycler, RcThreadAdapter.TAG_PROMPT_BOX) })
        assertEquals(listOf("> Yes", "  No"), promptOptionTexts())
        assertEquals("a prompt disables the microphone",
            View.GONE, view(R_rcThreadMicGlyph()).visibility)
        assertHint("answer the prompt")
        key(KeyEvent.KEYCODE_NUMPAD_3)
        assertEquals("voice must never answer a prompt",
            View.GONE, view(R_rcThreadVoiceBar()).visibility)

        // 9. DPAD moves the option cursor; the caret is a brightness step, not a colour.
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        SystemClock.sleep(HOLD_MS)
        shoot("9_prompt_cursor_moved")
        assertEquals(listOf("  Yes", "> No"), promptOptionTexts())
        key(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(listOf("> Yes", "  No"), promptOptionTexts())

        // 10. DPAD_CENTER confirms the highlighted option. The prompt stops blocking once the
        //     agent moves on, which is what a later row proves.
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(900)
        shoot("10_prompt_confirmed")
        pushRows(rowsFrame("""{"q":7,"r":"assistant","x":"Deploying."}""", lastSeq = 7))
        SystemClock.sleep(HOLD_MS)
        assertTrue("a resolved prompt drops its options", promptOptionTexts().isEmpty())
        assertEquals("the microphone returns once the prompt is answered",
            View.VISIBLE, view(R_rcThreadMicGlyph()).visibility)

        // 11. Hold-to-speak: capture, live partial, then the 3 s send window.
        key(KeyEvent.KEYCODE_NUMPAD_3)
        pushPartial("yes deploy it and watch")
        SystemClock.sleep(HOLD_MS)
        shoot("11_capturing")
        assertEquals(View.VISIBLE, view(R_rcThreadVoiceBar()).visibility)
        assertEquals("yes deploy it and watch",
            onUi { (root().findViewById<TextView>(R_rcThreadVoiceText())).text.toString() })

        // 12. A BLANK final transcript never opens the window.
        pushFinalTranscript("   ")
        SystemClock.sleep(1200)
        shoot("12_blank_transcript_discarded")
        assertEquals("whitespace is not a message",
            View.GONE, view(R_rcThreadVoiceBar()).visibility)

        // 13. A real transcript opens the 3 s window with the draining bar and the cancel hint.
        key(KeyEvent.KEYCODE_NUMPAD_3)
        pushFinalTranscript("yes deploy it and watch the logcat")
        SystemClock.sleep(1000)
        shoot("13_send_window")
        assertEquals(View.VISIBLE, view(R_rcThreadCountdownRow()).visibility)
        assertEquals(View.VISIBLE, view(R_rcThreadCountdownFill()).visibility)

        // 14. A DOUBLE tap inside the window cancels it. A single tap must not.
        key(KeyEvent.KEYCODE_NUMPAD_2)
        assertEquals("a single tap must not discard a dictation",
            View.VISIBLE, view(R_rcThreadVoiceBar()).visibility)
        device.pressKeyCode(KeyEvent.KEYCODE_NUMPAD_2)
        SystemClock.sleep(200)
        device.waitForIdle()
        SystemClock.sleep(HOLD_MS)
        shoot("14_send_cancelled")
        assertEquals(View.GONE, view(R_rcThreadVoiceBar()).visibility)
        assertFalse("a cancelled send must never append a row",
            threadRows().any { it.text.contains("logcat") })

        // 15. The window commits on expiry, and NO optimistic local user row appears -- the user
        //     row arrives only from the phone.
        key(KeyEvent.KEYCODE_NUMPAD_3)
        pushFinalTranscript("deploy it")
        SystemClock.sleep(3600)
        shoot("15_sent")
        assertEquals(View.GONE, view(R_rcThreadVoiceBar()).visibility)
        assertFalse("no optimistic local user row may render",
            threadRows().any { it.text == "deploy it" })

        // 16. While the send is in flight a further hold is refused, and the phone's reply clears it.
        key(KeyEvent.KEYCODE_NUMPAD_3)
        assertEquals("a second hold must not start while a send is in flight",
            View.GONE, view(R_rcThreadVoiceBar()).visibility)
        pushSendResult("sent")
        SystemClock.sleep(HOLD_MS)
        shoot("16_send_acked")
        assertEquals("the mic returns once the phone has answered",
            View.VISIBLE, view(R_rcThreadMicGlyph()).visibility)

        // 17. The user's row arrives from the phone, exactly once.
        pushRows(rowsFrame("""{"q":8,"r":"user","x":"deploy it"}""", lastSeq = 8))
        SystemClock.sleep(HOLD_MS)
        shoot("17_user_row_from_phone")
        assertEquals("the user row exists exactly once",
            1, threadRows().count { it.text == "deploy it" })

        // 18. If the open session vanishes from a snapshot, the thread pops back to the list.
        pushState(true)
        SystemClock.sleep(HOLD_MS)
        shoot("18_session_vanished")
        assertEquals(View.GONE, view(R_rcThreadContainer()).visibility)
        assertEquals(View.VISIBLE, onUi { listRecycler.visibility })

        // 19. BACK from a thread returns to the list.
        pushState(true, session())
        openTheRcSession()
        pushRows(rowsFrame("""{"q":1,"r":"assistant","x":"back test"}""", lastSeq = 1))
        SystemClock.sleep(HOLD_MS)
        shoot("19_thread_before_back")
        assertEquals(View.VISIBLE, view(R_rcThreadContainer()).visibility)
        key(KeyEvent.KEYCODE_BACK)
        SystemClock.sleep(HOLD_MS)
        shoot("20_back_to_list")
        assertEquals(View.GONE, view(R_rcThreadContainer()).visibility)
        assertEquals(View.VISIBLE, onUi { listRecycler.visibility })
        assertTrue("the thread is discarded on the way out", threadRows().isEmpty())

        Log.i(tag, "rcThreadFlow: done -> ${artifactDir().absolutePath}")
    }

    private fun assertHint(expected: String) {
        val hint = onUi { root().findViewById<TextView>(R_rcThreadFooterHint()) }
        assertEquals("the refusal must be visible, never silent",
            expected, hint.text.toString())
        assertEquals(View.VISIBLE, onUi { hint.visibility })
    }

    /** Puts the caret on the RC row and confirms it, using the real key path. */
    private fun openTheRcSession() {
        onUi {
            listAdapter.setFocused(true)
            listAdapter.selectKey("rc:$SESSION")
        }
        device.waitForIdle()
        // The confirm runs after the double-tap window, exactly as a real single tap does.
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(900)
        assertEquals("the thread must open", View.VISIBLE, view(R_rcThreadContainer()).visibility)
    }

    private fun pushChatList(vararg ids: String) {
        val json = ids.joinToString(",", "[", "]") {
            """{"id":"$it","title":"chat $it","relativeTime":"MAR 19","turnCount":2}"""
        }
        ctx.sendBroadcast(Intent(ACTION_CHAT_LIST).apply {
            setPackage(pkg)
            putExtra(EXTRA_CHAT_LIST, json)
        })
        device.waitForIdle()
    }

    private fun navigateToChatListTab() {
        repeat(8) {
            if (onUi { listRecycler.isShown }) return
            device.pressKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)
            SystemClock.sleep(400)
        }
        device.waitForIdle()
        assertTrue("the chat list tab must be on screen", onUi { listRecycler.isShown })
        // The list must be FOCUSED for a confirm to open a row.
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(700)
    }

    private fun locate() = onUi {
        val decor = root()
        listRecycler = decor.findViewById(com.repository.glasses.listener.R.id.chatListRecycler)
        listAdapter = listRecycler.adapter as ChatListAdapter
        threadRecycler = decor.findViewById(com.repository.glasses.listener.R.id.rcThreadRecycler)
        threadAdapter = threadRecycler.adapter as RcThreadAdapter
    }

    // Generated ids, read through helpers so the imports stay readable.
    private fun R_rcThreadContainer() = com.repository.glasses.listener.R.id.rcThreadContainer
    private fun R_rcThreadSpinnerSlot() = com.repository.glasses.listener.R.id.rcThreadSpinnerSlot
    private fun R_rcThreadMicGlyph() = com.repository.glasses.listener.R.id.rcThreadMicGlyph
    private fun R_rcThreadFooterHint() = com.repository.glasses.listener.R.id.rcThreadFooterHint
    private fun R_rcThreadVoiceBar() = com.repository.glasses.listener.R.id.rcThreadVoiceBar
    private fun R_rcThreadVoiceText() = com.repository.glasses.listener.R.id.rcThreadVoiceText
    private fun R_rcThreadCountdownRow() = com.repository.glasses.listener.R.id.rcThreadCountdownRow
    private fun R_rcThreadCountdownFill() = com.repository.glasses.listener.R.id.rcThreadCountdownFill
}
