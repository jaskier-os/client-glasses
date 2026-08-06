package com.repository.glasses.listener.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Renders remote-control session rows in the real chat list on the real device.
 *
 * Frames are injected the way the backend delivers them -- an in-process broadcast of
 * ACTION_RC_STATE with setPackage(self) -- so the production BroadcastReceiver, RcStateParser,
 * ChatRowBuilder, ListMutationGate and ChatListAdapter all run exactly as they do in the field.
 * Nothing is stubbed and no coordinate taps are used.
 *
 * The load-bearing assertion is [caretStaysOnItsRowWhenRcSessionsAppearAbove]: an RC session
 * arriving asynchronously must never re-aim the caret, because one of the rows it could land on
 * starts the microphone.
 *
 * Every rendered state is held 2-3 s so an external screen recording is a usable deliverable.
 */
@RunWith(AndroidJUnit4::class)
class RcMirrorListInstrumentedTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val device: UiDevice = UiDevice.getInstance(instr)
    private val tag = "RcMirrorList"
    private val pkg = "com.repository.glasses.listener"

    // Mirrors of the ListenerService constants (kept local, exactly as ChatWeatherFlow does).
    private val ACTION_RC_STATE = "$pkg.RC_STATE"
    private val EXTRA_RC_STATE_JSON = "rc_state_json"
    private val ACTION_CHAT_LIST = "$pkg.CHAT_LIST"
    private val EXTRA_CHAT_LIST = "chat_list"

    private val HOLD_MS = 2600L

    private fun artifactDir(): File =
        File(ctx.getExternalFilesDir(null), "rc-mirror-list").apply { if (!exists()) mkdirs() }

    private fun shoot(step: String) {
        val bmp: Bitmap? = instr.uiAutomation.takeScreenshot()
        assertNotNull("takeScreenshot null at $step", bmp)
        FileOutputStream(File(artifactDir(), "$step.png")).use {
            bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        Log.i(tag, "saved $step")
    }

    // -- frame injection --

    private fun pushRcState(json: String) {
        Log.i(tag, "rc state -> $json")
        ctx.sendBroadcast(Intent(ACTION_RC_STATE).apply {
            setPackage(pkg)
            putExtra(EXTRA_RC_STATE_JSON, json)
        })
        device.waitForIdle()
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

    private fun session(
        id: String,
        name: String,
        folder: String = "~/AI/clients/glasses",
        turning: Boolean = false,
        unread: Boolean = false,
        ended: Boolean = false,
        lastSeq: Long = 7,
    ) = """{"id":"$id","n":"$name","w":"$folder","st":"${if (ended) "ended" else "open"}",""" +
        """"t":$turning,"u":$unread,"q":$lastSeq}"""

    private fun snapshot(ws: Boolean, vararg sessions: String) =
        """{"ws":$ws,"s":[${sessions.joinToString(",")}]}"""

    // -- adapter / view access (read-only; the test never drives the UI by coordinates) --

    private lateinit var activity: MainActivityHandle

    private class MainActivityHandle(val recycler: RecyclerView, val adapter: ChatListAdapter)

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

    private fun rows(): List<ChatRow> = onUi { activity.adapter.currentRows }

    private fun rcRows(): List<ChatRow.RcSession> = rows().filterIsInstance<ChatRow.RcSession>()

    private fun keys(): List<String> = rows().map { it.key }

    /** The view currently rendering [key], or null when it is not laid out. */
    private fun viewFor(key: String): View? = onUi {
        val idx = rows().indexOfFirst { it.key == key }
        if (idx < 0) null else activity.recycler.findViewHolderForAdapterPosition(idx)?.itemView
    }

    /**
     * Depth-first search for the view carrying [tag]. Tags, not geometry: these are programmatic
     * views with no ids, and a shape heuristic would quietly start matching a different view the
     * first time the row layout changed.
     */
    private fun findByTag(root: View, tag: String): View? {
        if (root.tag == tag) return root
        if (root !is ViewGroup) return null
        for (i in 0 until root.childCount) {
            findByTag(root.getChildAt(i), tag)?.let { return it }
        }
        return null
    }

    private fun unreadBarOf(key: String): View? =
        viewFor(key)?.let { findByTag(it, ChatListAdapter.TAG_UNREAD_BAR) }

    private fun spinnerOf(key: String): SpinnerView? =
        viewFor(key)?.let { findByTag(it, ChatListAdapter.TAG_SPINNER) as? SpinnerView }

    private fun dp(px: Int): Float =
        px / ctx.resources.displayMetrics.density

    // -- the test --

    @Test
    fun rcMirrorListFlow() {
        instr.uiAutomation.executeShellCommand("am start -n $pkg/.MainActivity")
        SystemClock.sleep(2800)
        device.waitForIdle()
        activity = locateChatList()
        navigateToChatListTab()

        // 1. Two ordinary conversations, no RC. Baseline for "existing rows are untouched".
        pushChatList("c1", "c2")
        SystemClock.sleep(HOLD_MS)
        shoot("1_conversations_only")
        assertTrue("no RC rows before any push", rcRows().isEmpty())
        val baselineKeys = keys()

        // 2. RC sessions arrive: pinned ABOVE the conversations, behind a desktop-glyph group mark.
        pushRcState(
            snapshot(
                true,
                session("s-live", "fix rfcomm idle teardown", turning = true),
                session("s-new", "resume KB embeddings on runpod", folder = "~/Repository", unread = true),
                session("s-idle", "mirror runbook - branch protection", folder = "~/deploy"),
            )
        )
        SystemClock.sleep(HOLD_MS)
        shoot("2_rc_pinned")

        val afterKeys = keys()
        val firstRc = afterKeys.indexOfFirst { it.startsWith("rc:") }
        val firstConv = afterKeys.indexOfFirst { it.startsWith("conv:") }
        assertTrue("RC rows must exist", firstRc > 0)
        assertTrue("RC rows must be pinned above conversations", firstRc < firstConv)
        assertTrue("the RC group marker precedes the RC rows", afterKeys.indexOf("hdr:rc") < firstRc)
        assertEquals("the two header rows are untouched",
            baselineKeys.take(2), afterKeys.take(2))
        assertEquals("turning sorts first", "rc:s-live", afterKeys[firstRc])

        // 3. The unread indicator is a SOLID bar at least 18dp tall -- measured, not assumed.
        val bar = unreadBarOf("rc:s-new")
        assertNotNull("the unread row must render an unread bar", bar)
        assertTrue("unread bar is ${dp(bar!!.height)}dp tall, spec floor is 18dp",
            dp(bar.height) >= 18f)
        assertTrue("the unread indicator must be a bar, not a dot", bar.height > bar.width)
        assertEquals("a solid filled bar is fully opaque", 1f, bar.alpha, 0.001f)
        assertEquals(View.VISIBLE, bar.visibility)
        Log.i(tag, "unread bar measured ${dp(bar.width)}x${dp(bar.height)}dp")

        // 4. The spinner runs on the turning row, and the two indicators never co-render.
        val spinner = spinnerOf("rc:s-live")
        assertNotNull("a turning session must show the spinner", spinner)
        assertEquals(View.VISIBLE, spinner!!.visibility)
        assertTrue("the spinner must actually be animating", onUi { spinner.isRunning })
        assertEquals("a turning row shows no unread bar",
            View.GONE, unreadBarOf("rc:s-live")?.visibility ?: View.GONE)
        assertEquals("an unread row shows no spinner",
            View.GONE, spinnerOf("rc:s-new")?.visibility ?: View.GONE)
        SystemClock.sleep(HOLD_MS)
        shoot("3_indicators")

        // 5. The caret does not move when RC sessions appear above it. THE load-bearing property.
        caretStaysOnItsRowWhenRcSessionsAppearAbove()

        // 6. The unread bar clears when the phone reports the session read.
        pushRcState(
            snapshot(
                true,
                session("s-live", "fix rfcomm idle teardown", turning = true),
                session("s-new", "resume KB embeddings on runpod", folder = "~/Repository"),
                session("s-idle", "mirror runbook - branch protection", folder = "~/deploy"),
            )
        )
        SystemClock.sleep(HOLD_MS)
        shoot("4_unread_cleared")
        assertFalse("the bar must clear once the session is read",
            rcRows().single { it.id == "s-new" }.unread)
        assertEquals(View.GONE, unreadBarOf("rc:s-new")?.visibility ?: View.GONE)

        // 7. ws:false dims EVERY RC row and refuses dictation on all of them.
        pushRcState(
            snapshot(
                false,
                session("s-live", "fix rfcomm idle teardown", turning = true),
                session("s-new", "resume KB embeddings on runpod", folder = "~/Repository"),
                session("s-idle", "mirror runbook - branch protection", folder = "~/deploy"),
            )
        )
        SystemClock.sleep(HOLD_MS)
        shoot("5_ws_down")
        val dimmed = rcRows()
        assertEquals(3, dimmed.size)
        assertTrue("every RC row dims when the orchestrator link is down", dimmed.all { it.dim })
        assertTrue("hold-to-speak is refused on every row", dimmed.none { it.voiceAllowed })

        // 8. An ended session renders dim, non-enterable, with no spinner and no bar.
        pushRcState(
            snapshot(
                true,
                session("s-live", "fix rfcomm idle teardown", turning = true, unread = true, ended = true),
                session("s-idle", "mirror runbook - branch protection", folder = "~/deploy"),
            )
        )
        SystemClock.sleep(HOLD_MS)
        shoot("6_ended")
        val ended = rcRows().single { it.id == "s-live" }
        assertTrue(ended.ended)
        assertTrue("an ended session renders dim", ended.dim)
        assertFalse("an ended session is not enterable", ended.enterable)
        assertFalse("an ended session may not spin", ended.turning)
        assertFalse("an ended session may not show an unread bar", ended.unread)
        assertEquals(View.GONE, spinnerOf("rc:s-live")?.visibility ?: View.GONE)
        assertEquals(View.GONE, unreadBarOf("rc:s-live")?.visibility ?: View.GONE)
        assertNull("confirming an ended session must open nothing",
            onUi { activity.adapter.selectKey("rc:s-live"); activity.adapter.getSelectedRcSession() })

        // 9. Absence from the next snapshot IS the removal instruction.
        pushRcState(snapshot(true, session("s-idle", "mirror runbook - branch protection")))
        SystemClock.sleep(HOLD_MS)
        shoot("7_removed_by_absence")
        assertEquals("only the session still in the snapshot survives",
            listOf("s-idle"), rcRows().map { it.id })

        // 10. An empty snapshot removes the whole pinned block, group marker included.
        pushRcState(snapshot(true))
        SystemClock.sleep(HOLD_MS)
        shoot("8_rc_gone")
        assertTrue(rcRows().isEmpty())
        assertFalse("the group marker goes with the last RC row", keys().contains("hdr:rc"))
        assertEquals("the conversations are exactly as they were", baselineKeys, keys())

        Log.i(tag, "rcMirrorListFlow: done -> ${artifactDir().absolutePath}")
    }

    /**
     * The hazard this whole stage exists to close. The caret sits on the LAST conversation; RC
     * sessions then arrive asynchronously and land above it. The caret must still be on the same
     * row afterwards -- at a different index, which is what proves the insert really happened.
     */
    private fun caretStaysOnItsRowWhenRcSessionsAppearAbove() {
        pushRcState(snapshot(true))
        SystemClock.sleep(600)

        onUi {
            activity.adapter.setFocused(true)
            activity.adapter.selectKey("conv:c2")
        }
        device.waitForIdle()
        SystemClock.sleep(HOLD_MS)
        shoot("9a_caret_before_insert")

        val keyBefore = onUi { activity.adapter.selectedKey }
        val indexBefore = onUi { activity.adapter.selectedPosition }
        assertEquals("conv:c2", keyBefore)

        // Four sessions arrive at once, above the caret.
        pushRcState(
            snapshot(
                true,
                session("i1", "async insert one", turning = true),
                session("i2", "async insert two", unread = true),
                session("i3", "async insert three"),
                session("i4", "async insert four"),
            )
        )
        // While the list is FOCUSED the set change is deferred, so leaving focus is what lands it.
        onUi { activity.adapter.setFocused(false); activity.adapter.setFocused(true) }
        device.waitForIdle()
        SystemClock.sleep(HOLD_MS)
        shoot("9b_caret_after_insert")

        val keyAfter = onUi { activity.adapter.selectedKey }
        val indexAfter = onUi { activity.adapter.selectedPosition }
        assertEquals("the caret must still be on the SAME row", keyBefore, keyAfter)
        assertTrue("the rows really did shift down (otherwise this proves nothing)",
            indexAfter > indexBefore)
        assertFalse("the caret must never be re-aimed onto the row that starts the microphone",
            onUi { activity.adapter.isAssistantSelected() })
        assertEquals("conv:c2", onUi { activity.adapter.getSelectedItem()?.let { "conv:${it.id}" } })
        Log.i(tag, "caret held key=$keyAfter across insert ($indexBefore -> $indexAfter)")

        onUi { activity.adapter.setFocused(false) }
        pushRcState(snapshot(true))
        SystemClock.sleep(800)
    }

    /**
     * Walks the tab bar to CHAT_LIST with the same DPAD keycodes the temple touchpad emits.
     * Key events, never coordinate taps: the waveguide has no touchscreen, so a tap would be
     * driving an input path the device does not have.
     */
    private fun navigateToChatListTab() {
        repeat(8) {
            if (onUi { activity.recycler.isShown }) return
            device.pressKeyCode(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            SystemClock.sleep(400)
        }
        device.waitForIdle()
        assertTrue("the chat list tab must be on screen for rows to lay out",
            onUi { activity.recycler.isShown })
        SystemClock.sleep(600)
    }

    /**
     * Finds the chat-list RecyclerView and its adapter without touching MainActivity internals.
     * Uses the generated R.id rather than getIdentifier: the debug variant is R8-minified with
     * resource shrinking, so a name lookup is not guaranteed to resolve.
     */
    private fun locateChatList(): MainActivityHandle = onUi {
        val recycler = currentActivityRoot()
            ?.findViewById<RecyclerView>(com.repository.glasses.listener.R.id.chatListRecycler)
            ?: throw AssertionError("chatListRecycler not found; is MainActivity in the foreground?")
        val adapter = recycler.adapter as? ChatListAdapter
            ?: throw AssertionError("chatListRecycler has no ChatListAdapter")
        MainActivityHandle(recycler, adapter)
    }

    /** The resumed Activity's decor view, via the supported AndroidX lifecycle monitor. */
    private fun currentActivityRoot(): View? =
        ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)
            .firstOrNull()
            ?.window?.decorView
}
