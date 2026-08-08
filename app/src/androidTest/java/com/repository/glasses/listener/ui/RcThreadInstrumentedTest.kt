package com.repository.glasses.listener.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
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

    // The OUTBOUND requests, i.e. what actually reaches the wire as CH_RC_MESSAGES_REQ /
    // CH_RC_SEND_REQ / CH_RC_ANSWER_REQ. Asserting the rendered UI alone cannot tell a frame that
    // was sent from one that was not, and the empty-sessionId unsubscribe on BACK has no UI at all.
    private val ACTION_RC_MESSAGES_REQ = "$pkg.RC_MESSAGES_REQ"
    private val ACTION_RC_SEND_MSG = "$pkg.RC_SEND_MSG"
    private val ACTION_RC_ANSWER = "$pkg.RC_ANSWER"
    private val EXTRA_RC_SEEN_SEQ = "rc_seen_seq"
    private val EXTRA_RC_TEXT = "rc_text"
    private val EXTRA_RC_REQUEST_ID = "rc_request_id"

    /** Test-only, matched by no production receiver. Used to prove the spy is still alive. */
    private val ACTION_SPY_PROBE = "$pkg.TEST_SPY_PROBE"

    private val HOLD_MS = 2600L

    /**
     * Unique per run, so the PAIRED PHONE cannot answer this test's own MSGS_REQ.
     *
     * A fixed id collided with a real session on the live phone: the phone replied with genuine
     * rows ("RC messages response: session=... 95 chars") that merged beside the injected ones and
     * turned [1,2,3] into [0,1,2,3]. That is test contamination, not a product defect -- the merge
     * was doing exactly what it must. An id no session can own keeps every row in the thread an
     * injected one, so the merge-by-seq and rendering assertions still mean what they claim.
     */
    private val SESSION = "s-test-${java.util.UUID.randomUUID()}"

    /** Every outbound RC request the activity broadcast, in order, as "ACTION|arg|arg". */
    private val sent = java.util.Collections.synchronizedList(mutableListOf<String>())

    private val sentSpy = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val a = i?.action ?: return
            sent.add(
                when (a) {
                    ACTION_RC_MESSAGES_REQ ->
                        "MSGS_REQ|${i.getStringExtra(EXTRA_RC_SESSION_ID)}" +
                            "|${i.getLongExtra(EXTRA_RC_SEEN_SEQ, Long.MIN_VALUE)}"
                    ACTION_RC_SEND_MSG ->
                        "SEND|${i.getStringExtra(EXTRA_RC_SESSION_ID)}" +
                            "|${i.getStringExtra(EXTRA_RC_TEXT)}"
                    ACTION_RC_ANSWER ->
                        "ANSWER|${i.getStringExtra(EXTRA_RC_SESSION_ID)}" +
                            "|${i.getStringExtra(EXTRA_RC_REQUEST_ID)}" +
                            "|${i.getStringExtra(EXTRA_RC_TEXT)}"
                    else -> a
                }
            )
        }
    }

    /** Drains the recorded frames, so each assertion speaks about its own step only. */
    private fun drainSent(): List<String> {
        synchronized(sent) {
            val out = sent.toList()
            sent.clear()
            return out
        }
    }

    /**
     * Proves the spy is ALIVE before an assertion that a frame was NOT sent.
     *
     * "no SEND frame arrived" is equally satisfied by a spy that stopped receiving anything at all,
     * which would turn every negative assertion in this test green while the code under test was
     * broken. Round-tripping one known frame first removes that reading.
     */
    private fun assertSpyIsListening() {
        drainSent()
        // A test-only action. Reusing a production one would make the real ListenerService receiver
        // put a fabricated frame on the wire to the phone, which is not this test's business.
        ctx.sendBroadcast(Intent(ACTION_SPY_PROBE).apply { setPackage(pkg) })
        SystemClock.sleep(400)
        assertTrue("the frame spy stopped receiving; every negative assertion below is meaningless",
            drainSent().contains(ACTION_SPY_PROBE))
    }

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
        landInjectedRows()
    }

    /**
     * The caret-stability gate holds any row-set change for HOLD_MS while the list is focused, and
     * keeps only the NEWEST held snapshot. On a live-paired device the phone's own RC state and
     * chat-list pushes overwrite the injected snapshot inside that window, so the synthetic row
     * never lands. Forcing the flush is test isolation, not a product behaviour change: the gate
     * still governs the live path, this only stops a real frame from racing an injected one.
     */
    private fun landInjectedRows() {
        onUi { listAdapter.flushPendingIfDue(force = true) }
        device.waitForIdle()
    }

    /**
     * The thread must be open for any of the voice steps to mean anything.
     *
     * BACK is layered: it cancels a capture or a pending send when one is live, and only otherwise
     * leaves the thread. Every BACK in the voice steps below is pressed while one of those two IS
     * live, so none of them may reach the thread -- and a BACK that did would be a defect, not a
     * precondition to quietly repair. Asserting says which of the two happened; re-opening the
     * thread instead would hide it.
     */
    private fun assertThreadOpen(why: String) {
        assertEquals(why, View.VISIBLE, view(R_rcThreadContainer()).visibility)
    }

    /**
     * Refuses to let a send-window assertion be answered by the clock.
     *
     * An expired window tears the voice bar and the countdown down on its own, so "GONE" after
     * 3 s is true whatever the key press under test did. Every assertion about a window must
     * therefore prove it was still open when it was made -- the exact reading that let the old
     * step 13b pass while it was SENDING the words it claimed to have dropped.
     */
    private fun assertRemainingWindow(armedAtMs: Long, what: String) {
        val elapsed = SystemClock.uptimeMillis() - armedAtMs
        assertTrue(
            "$what ran ${elapsed}ms after the window armed, past its ${RcSendWindow.WINDOW_MS}ms " +
                "life: the window expired on its own, so this proves nothing about the key press",
            elapsed < RcSendWindow.WINDOW_MS - 250
        )
    }

    /**
     * True when the wear sensor says the glasses are on a head.
     *
     * `vendor.rkd.glasses.is_take_on` is the property the WearSensor itself reads, so this asks
     * the same source the product asks rather than a proxy for it.
     */
    private fun micIsAvailable(): Boolean {
        val v = instr.uiAutomation
            .executeShellCommand("getprop vendor.rkd.glasses.is_take_on")
            .let { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() }
            }
            .trim()
        Log.i(tag, "wear sensor is_take_on='$v'")
        return v == "1"
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

    private fun resumedActivityOrNull() = onUi {
        ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED).firstOrNull()
    }

    /**
     * Blocks off the main thread until MainActivity is RESUMED.
     *
     * `am start` on an activity that is ALREADY foreground (a previous instrumented run left it
     * there) is a no-op, so the lifecycle monitor can still be empty when the test proceeds; the
     * first [root] then failed with a bare "List is empty" that says nothing about the cause. The
     * wait must NOT happen inside [onUi] -- sleeping on the main thread is what would prevent the
     * resume it is waiting for.
     */
    private fun awaitResumed() {
        repeat(24) {
            if (resumedActivityOrNull() != null) return
            SystemClock.sleep(250)
        }
        throw AssertionError("no RESUMED activity after 6s; MainActivity never came to the front")
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

    /**
     * Listens for the activity's outbound RC requests alongside the real ListenerService receiver.
     * A broadcast reaches every registered receiver, so spying costs the production path nothing.
     */
    @Before
    fun startSpy() {
        val f = IntentFilter().apply {
            addAction(ACTION_RC_MESSAGES_REQ)
            addAction(ACTION_RC_SEND_MSG)
            addAction(ACTION_RC_ANSWER)
            addAction(ACTION_SPY_PROBE)
        }
        ctx.registerReceiver(sentSpy, f, Context.RECEIVER_NOT_EXPORTED)
    }

    /**
     * Keeps the waveguide lit for the whole run, and restores the device setting afterwards.
     *
     * This test holds each state for HOLD_MS so a recording is usable, which makes the run longer
     * than the glasses' screen timeout. When the screen blanks, MainActivity leaves RESUMED and
     * every view lookup fails on an empty activity list -- a harness artefact that reads exactly
     * like a product defect. Left as a setting rather than a product flag: the app must NOT keep
     * the screen on in the field.
     */
    @Before
    fun keepScreenLit() {
        instr.uiAutomation.executeShellCommand("svc power stayon true")
        device.wakeUp()
        SystemClock.sleep(500)
    }

    @After
    fun releaseScreen() {
        runCatching { instr.uiAutomation.executeShellCommand("svc power stayon false") }
    }

    @After
    fun stopSpy() {
        runCatching { ctx.unregisterReceiver(sentSpy) }
    }

    @Test
    fun rcThreadFlow() {
        instr.uiAutomation.executeShellCommand("am start -n $pkg/.MainActivity")
        SystemClock.sleep(2800)
        device.waitForIdle()
        awaitResumed()
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
        // A rows push pins the thread to its LAST row, so the marker at position 0 is off-screen
        // and therefore not attached. Scroll to it before asserting it renders -- the marker is
        // meant to be found by scrolling up, so requiring it while pinned to the bottom would
        // assert the opposite of the intended behaviour.
        onUi { threadRecycler.scrollToPosition(0) }
        device.waitForIdle()
        SystemClock.sleep(400)
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
        drainSent()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(900)
        shoot("10_prompt_confirmed")
        // The answer must reach the WIRE with the prompt's own request id. A rendered cursor move
        // proves nothing about what the phone was told.
        assertEquals("confirming an option emits exactly one CH_RC_ANSWER_REQ",
            listOf("ANSWER|$SESSION|req-1|Yes"),
            drainSent().filter { it.startsWith("ANSWER|") })
        // The confirm is unacknowledged and the next row is a round trip away. A second tap in that
        // gap must write NOTHING: the phone would refuse the duplicate, but a frame that should
        // never have been written is not made correct by being discarded at the far end.
        assertTrue("a resolved prompt drops its options the instant it is answered",
            promptOptionTexts().isEmpty())
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(900)
        shoot("10b_double_tap_sends_nothing")
        assertEquals("a second confirm before the reply must not emit a second answer",
            emptyList<String>(), drainSent().filter { it.startsWith("ANSWER|") })
        pushRows(rowsFrame("""{"q":7,"r":"assistant","x":"Deploying."}""", lastSeq = 7))
        SystemClock.sleep(HOLD_MS)
        assertTrue("a resolved prompt drops its options", promptOptionTexts().isEmpty())
        assertEquals("the microphone returns once the prompt is answered",
            View.VISIBLE, view(R_rcThreadMicGlyph()).visibility)

        // 11. Hold-to-speak: capture, live partial, then the 3 s send window.
        //
        // HARDWARE PRECONDITION: the glasses must be WORN. The wear gate refuses to open the
        // microphone off-head ("Audio stream arm (BT connect) -- off-head, refusing to open mic"),
        // which is correct product behaviour, so every step from here down asserts a capture that
        // the device is right to deny. Assumed rather than asserted: a red bar on glasses sitting
        // on a desk would report a defect that does not exist, and silently skipping would hide a
        // real one. This states the reason out loud and stops.
        assumeTrue(
            "the glasses are OFF-HEAD, so the wear gate refuses the microphone by design; " +
                "steps 11-15 (voice capture, send window, cancel) need them worn",
            micIsAvailable()
        )
        // NUMPAD_3 only starts an RC capture while focus is RC_THREAD_FOCUSED; outside the thread
        // it falls through to the AI-chat long-press. Asserting the voice bar without first
        // pinning that precondition blames the microphone for a focus problem.
        assertThreadOpen("nothing above steps 11-15 may leave the thread")
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

        // 13. A real transcript opens the 3 s window with the draining bar and the cancel hint,
        //     and BACK undoes it WITHOUT leaving the thread.
        assertThreadOpen("step 12 must not have left the thread")
        drainSent()
        key(KeyEvent.KEYCODE_NUMPAD_3)
        pushFinalTranscript("yes deploy it and watch the logcat")
        SystemClock.sleep(700)
        // Asserted BEFORE the screenshot: PNG compression costs time the 3 s window is spending,
        // and the BACK below must land while the send is still pending to mean what it claims.
        assertEquals(View.VISIBLE, view(R_rcThreadSendCountdown()).visibility)
        shoot("13_send_window")
        key(KeyEvent.KEYCODE_BACK)
        SystemClock.sleep(500)
        assertThreadOpen("BACK that undoes a pending send must not also leave the thread")
        assertEquals("BACK must tear the voice bar down with the window",
            View.GONE, view(R_rcThreadVoiceBar()).visibility)

        // 13b. A transcript belonging to a capture the user ABANDONED must not be adopted by the
        //      next one. This is the path that would otherwise ship cancelled words to the agent.
        //
        //      The capture must be ABANDONED WHILE RUNNING for this to test anything: RcCapture
        //      .cancel() early-returns on an inactive capture, so a BACK pressed when no capture
        //      is live owes no discard at all and the "stale" transcript below would then be
        //      adopted entirely legitimately. Hence NUMPAD_3 -> BACK with NO transcript between.
        key(KeyEvent.KEYCODE_NUMPAD_3)
        SystemClock.sleep(400)
        assertEquals("13b needs a capture that is actually RUNNING before it can be abandoned",
            View.VISIBLE, view(R_rcThreadVoiceBar()).visibility)
        key(KeyEvent.KEYCODE_BACK)          // abandons a LIVE capture -- this is what owes a discard
        SystemClock.sleep(400)
        assertThreadOpen("BACK that cancels a live capture must not also leave the thread")
        assertEquals("the abandoned capture must tear its bar down",
            View.GONE, view(R_rcThreadVoiceBar()).visibility)

        // The next capture. The phone now delivers the ABANDONED one's transcript into it.
        key(KeyEvent.KEYCODE_NUMPAD_3)
        SystemClock.sleep(400)
        assertEquals("the fresh capture must be running for the adoption to be possible at all",
            View.VISIBLE, view(R_rcThreadVoiceBar()).visibility)
        drainSent()
        pushFinalTranscript("this belonged to the cancelled capture")
        // Deliberately WELL under RcSendWindow.WINDOW_MS (3000). An adopted transcript would have
        // a countdown VISIBLE right now; waiting longer would let the window expire and pass this
        // assertion for the opposite reason -- because the words were SENT.
        SystemClock.sleep(500)
        // Asserted BEFORE the screenshot, for the same reason step 13 is: a PNG compress costs
        // over a second, which is a third of the window this assertion must land inside.
        assertEquals("the abandoned utterance must not arm the new capture's window",
            View.GONE, view(R_rcThreadSendCountdown()).visibility)
        shoot("13b_stale_transcript_dropped")
        // A dropped transcript is not consumed: the fresh capture is still listening for its own.
        assertEquals("dropping the stale transcript must not tear down the running capture",
            View.VISIBLE, view(R_rcThreadVoiceBar()).visibility)
        // The assertion that actually protects the user: the cancelled words never left the device.
        // Waited past a full window first, so a late commit could not hide behind the clock.
        SystemClock.sleep(RcSendWindow.WINDOW_MS + 600)
        val afterStale = drainSent()
        assertSpyIsListening()
        assertTrue("words from an abandoned capture must never reach the wire: $afterStale",
            afterStale.none { it.startsWith("SEND|") })

        // 13c. The discard must not DEAFEN the running capture: its own transcript still arms the
        //      window. Same capture as above -- no new hold, the debt is already paid off.
        pushFinalTranscript("yes deploy it and watch the logcat")
        val armedAt = SystemClock.uptimeMillis()
        SystemClock.sleep(400)
        assertEquals("the capture that survived the discard must still accept its own transcript",
            View.VISIBLE, view(R_rcThreadVoiceBar()).visibility)
        assertEquals("and open the send window, so step 14 has something to cancel",
            View.VISIBLE, view(R_rcThreadSendCountdown()).visibility)

        // 14. A DOUBLE tap inside the window cancels it. A single tap must not.
        //
        // Every assertion here must land INSIDE the 3 s window: an expired window tears the voice
        // bar down by itself, which would make "GONE" true whatever the double tap did. That is
        // exactly how step 13b used to pass while shipping the words it claimed to drop, so the
        // margin is asserted rather than assumed.
        device.pressKeyCode(KeyEvent.KEYCODE_NUMPAD_2)
        // Longer than DOUBLE_TAP_MAX_MS, so this tap's chain has lapsed and the tap below cannot
        // pair with it. Without that, "a single tap did nothing" and "the pair was too slow to
        // count" are the same observation.
        SystemClock.sleep(RcSendWindow.DOUBLE_TAP_MAX_MS + 150)
        device.waitForIdle()
        assertRemainingWindow(armedAt, "the single-tap assertion")
        assertEquals("a single tap must not discard a dictation",
            View.VISIBLE, view(R_rcThreadVoiceBar()).visibility)
        // A real pair: two presses inside DOUBLE_TAP_MIN_MS..MAX_MS of each other.
        device.pressKeyCode(KeyEvent.KEYCODE_NUMPAD_2)
        SystemClock.sleep(150)
        device.pressKeyCode(KeyEvent.KEYCODE_NUMPAD_2)
        SystemClock.sleep(250)
        device.waitForIdle()
        assertRemainingWindow(armedAt, "the double-tap cancel assertion")
        assertEquals("a double tap must cancel the send while the window is still open",
            View.GONE, view(R_rcThreadVoiceBar()).visibility)
        SystemClock.sleep(HOLD_MS)
        shoot("14_send_cancelled")
        assertEquals("and it must stay cancelled", View.GONE, view(R_rcThreadVoiceBar()).visibility)
        assertFalse("a cancelled send must never append a row",
            threadRows().any { it.text.contains("logcat") })
        // The load-bearing half: a cancel must stop the frame, not merely hide the bar.
        val afterCancel = drainSent()
        assertSpyIsListening()
        assertTrue("a cancelled dictation must never reach the wire",
            afterCancel.none { it.startsWith("SEND|") })

        // 15. The window commits on expiry, and NO optimistic local user row appears -- the user
        //     row arrives only from the phone.
        key(KeyEvent.KEYCODE_NUMPAD_3)
        pushFinalTranscript("deploy it")
        SystemClock.sleep(3600)
        shoot("15_sent")
        assertEquals(View.GONE, view(R_rcThreadVoiceBar()).visibility)
        assertFalse("no optimistic local user row may render",
            threadRows().any { it.text == "deploy it" })
        assertEquals("the expired window emits exactly one CH_RC_SEND_REQ",
            listOf("SEND|$SESSION|deploy it"), drainSent().filter { it.startsWith("SEND|") })

        // 16. While the send is in flight a further hold is refused, and the phone's reply clears it.
        key(KeyEvent.KEYCODE_NUMPAD_3)
        assertEquals("a second hold must not start while a send is in flight",
            View.GONE, view(R_rcThreadVoiceBar()).visibility)
        // Drive the refused hold all the way through the path that WOULD send: a final transcript
        // and a full window. Without those, no frame could arrive whatever the guard did, and the
        // assertion below would be true by construction rather than by the guard working.
        pushFinalTranscript("this must never reach the agent")
        SystemClock.sleep(3600)
        val afterRefusedHold = drainSent()
        assertSpyIsListening()
        assertTrue("a hold refused in flight must emit no second frame, even after a full window",
            afterRefusedHold.none { it.startsWith("SEND|") })
        assertFalse("and it must not render either",
            threadRows().any { it.text.contains("never reach the agent") })
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

        // 17b. A prompt arriving DURING the send window revokes it: free text must never land in a
        //      session that is waiting on an option pick.
        key(KeyEvent.KEYCODE_NUMPAD_3)
        pushFinalTranscript("run the whole suite")
        SystemClock.sleep(800)
        assertEquals(View.VISIBLE, view(R_rcThreadSendCountdown()).visibility)
        pushRows(rowsFrame(
            """{"q":9,"r":"prompt","x":"Allow Bash?","o":["Yes","No"],"i":"req-2"}""", lastSeq = 9))
        SystemClock.sleep(HOLD_MS)
        shoot("17b_prompt_revokes_send")
        assertEquals("a prompt must revoke a send already counting down",
            View.GONE, view(R_rcThreadVoiceBar()).visibility)
        assertHint("answer the prompt")
        // Answer it so the thread is unblocked for the assertions below.
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(900)
        pushRows(rowsFrame("""{"q":10,"r":"assistant","x":"Running."}""", lastSeq = 10))
        SystemClock.sleep(800)

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
        drainSent()
        key(KeyEvent.KEYCODE_BACK)
        SystemClock.sleep(HOLD_MS)
        shoot("20_back_to_list")
        assertEquals(View.GONE, view(R_rcThreadContainer()).visibility)
        assertEquals(View.VISIBLE, onUi { listRecycler.visibility })
        assertTrue("the thread is discarded on the way out", threadRows().isEmpty())
        // D6: leaving must UNSUBSCRIBE, or the phone keeps pushing rows for a thread nobody is
        // looking at forever. This has no visible effect on the glasses, so only the frame proves it.
        assertEquals("BACK must tell the phone to stop pushing rows, via an empty session id",
            listOf("MSGS_REQ||-1"), drainSent().filter { it.startsWith("MSGS_REQ|") })

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
        // Assert the caret actually landed. A refused selectKey would leave the caret elsewhere and
        // the DPAD_CENTER below would confirm a DIFFERENT row -- possibly the one that starts the
        // microphone -- while the failure surfaced as a confusing later assertion.
        val landed = onUi {
            listAdapter.setFocused(true)
            listAdapter.selectKey("rc:$SESSION")
        }
        assertTrue("the caret must reach rc:$SESSION before it can be confirmed", landed)
        device.waitForIdle()
        drainSent()
        // The confirm runs after the double-tap window, exactly as a real single tap does.
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(900)
        // Opening asks for the rows, and THAT request is the read acknowledgement. Nothing is held
        // yet, so seenSeq is -1; a wrong value here would clear an unread bar the user never saw.
        //
        // Only the FIRST request is pinned. An open thread re-acknowledges on every rows push with
        // the new seenSeq, so on a live link the phone's own reply legitimately adds more requests
        // here; asserting the whole list would fail for a reason that is correct behaviour.
        assertEquals("opening a thread must request its rows with seenSeq -1",
            "MSGS_REQ|$SESSION|-1",
            drainSent().first { it.startsWith("MSGS_REQ|") })
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
        landInjectedRows()
    }

    private fun navigateToChatListTab() {
        // A bare `return` here would leave the WHOLE function, skipping the confirm below that
        // moves the activity from TAB_NAV into LIST_FOCUSED -- without which a later confirm only
        // enters the list instead of opening the caret's row, and no MSGS_REQ is ever sent.
        run {
            repeat(8) {
                if (onUi { listRecycler.isShown }) return@run
                device.pressKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)
                SystemClock.sleep(400)
            }
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
    /**
     * The 3 s withdraw countdown. It used to be a track + fill + row trio in the layout; it is now
     * one shared [SendCountdownBar], the same view class the AI chat draws, which makes ITSELF
     * visible in start() and hides itself in stop(). So its own visibility is the assertion these
     * steps always wanted: "is the send window on screen".
     */
    private fun R_rcThreadSendCountdown() = com.repository.glasses.listener.R.id.rcThreadSendCountdown
}
