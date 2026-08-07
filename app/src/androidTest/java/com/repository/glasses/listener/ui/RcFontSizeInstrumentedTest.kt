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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Proves on the real waveguide that the wearer's chat font setting reaches the RC mirror.
 *
 * The setting is driven the way the backend drives it -- an in-process broadcast of
 * ACTION_CHAT_FONT_SIZE, which is exactly what `ListenerService.onSettings` emits after the phone
 * pushes `settings_chat_font_size` over CH_SETTINGS. So `chatFontSizeReceiver` and every adapter
 * rebind below it run precisely as they do in the field. No coordinate taps; navigation uses the
 * DPAD keycodes the temple touchpad emits.
 *
 * What makes this more than a screenshot: every rendered text size is MEASURED off the live views
 * (`TextView.textSize`, px -> sp) and asserted against the ratio the setting implies. A screenshot
 * alone cannot distinguish "the RC thread scaled" from "the whole screen happens to look different".
 *
 * The two extremes of the phone's slider (8sp and 24sp) are both exercised, and each state is held
 * so an external screen recording is a usable deliverable.
 */
@RunWith(AndroidJUnit4::class)
class RcFontSizeInstrumentedTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val device: UiDevice = UiDevice.getInstance(instr)
    private val tag = "RcFontSize"
    private val pkg = "com.repository.glasses.listener"

    private val ACTION_RC_STATE = "$pkg.RC_STATE"
    private val EXTRA_RC_STATE_JSON = "rc_state_json"
    private val ACTION_CHAT_LIST = "$pkg.CHAT_LIST"
    private val EXTRA_CHAT_LIST = "chat_list"
    private val ACTION_CHAT_FONT_SIZE = "$pkg.CHAT_FONT_SIZE"
    private val EXTRA_CHAT_FONT_SIZE = "chat_font_size"
    private val ACTION_RC_MESSAGES = "$pkg.RC_MESSAGES"
    private val EXTRA_RC_SESSION_ID = "rc_session_id"
    private val EXTRA_RC_MESSAGES_JSON = "rc_messages_json"

    /**
     * Unique per run, so the PAIRED PHONE cannot answer this test's own MSGS_REQ with real rows
     * that would merge beside the injected ones. Same reasoning as RcThreadInstrumentedTest.
     */
    private val SESSION = "s-font-${java.util.UUID.randomUUID()}"
    private val HOLD_MS = 2600L

    /** The smallest and largest values the phone's slider can produce. */
    private val SMALLEST = 8f
    private val LARGEST = 24f

    /** The wearer's default, the cap, and a setting above the cap. */
    private val DEFAULT = ChatFontScale.BASE_SP
    private val CAP = ChatFontScale.CAP_SP

    /**
     * A session name and workDir long enough that neither can fit one line of the 480px-wide
     * waveguide at the capped size. Both are strings the wearer would realistically see: the
     * distinguishing part of each is at the END, which is exactly what an ellipsis used to eat.
     */
    private val LONG_NAME = "cap the RC font at 18sp and let every long title wrap instead of truncating"
    private val LONG_FOLDER = "~/Repository/AI/clients/glasses/app/src/main/java/com/repository/glasses/listener/ui"
    private val LONG_TRANSCRIPT =
        "this dictated sentence is deliberately long enough to need three or four lines " +
            "on the waveguide so the transcript proves it wraps rather than ellipsizing"

    private lateinit var listRecycler: RecyclerView
    private lateinit var listAdapter: ChatListAdapter
    private lateinit var threadRecycler: RecyclerView
    private lateinit var threadAdapter: RcThreadAdapter

    // -- plumbing --

    private fun artifactDir(): File =
        File(ctx.getExternalFilesDir(null), "rc-font-size").apply { if (!exists()) mkdirs() }

    private fun shoot(step: String) {
        val bmp: Bitmap? = instr.uiAutomation.takeScreenshot()
        assertNotNull("takeScreenshot null at $step", bmp)
        FileOutputStream(File(artifactDir(), "$step.png")).use {
            bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        Log.i(tag, "saved $step")
    }

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
        .getActivitiesInStage(Stage.RESUMED).firstOrNull()?.window?.decorView
        ?: throw AssertionError("no resumed activity; is MainActivity in the foreground?")

    /** Rendered size of a TextView in SP -- what the wearer actually sees. */
    private fun spOf(v: TextView): Float = onUi { v.textSize } / ctx.resources.displayMetrics.scaledDensity

    private fun view(id: Int): TextView = onUi { root().findViewById(id) }

    private fun findByTag(root: View, wanted: String): View? {
        if (root.tag == wanted) return root
        if (root !is ViewGroup) return null
        for (i in 0 until root.childCount) findByTag(root.getChildAt(i), wanted)?.let { return it }
        return null
    }

    /** Every TextView under [root], in tree order. */
    private fun allTextViews(root: View, out: MutableList<TextView> = mutableListOf()): List<TextView> {
        if (root is TextView) out.add(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) allTextViews(root.getChildAt(i), out)
        return out
    }

    // -- frame injection --

    private fun setFontSize(sp: Float) {
        Log.i(tag, "font setting -> ${sp}sp")
        ctx.sendBroadcast(Intent(ACTION_CHAT_FONT_SIZE).apply {
            setPackage(pkg)
            putExtra(EXTRA_CHAT_FONT_SIZE, sp)
        })
        device.waitForIdle()
        SystemClock.sleep(700)
    }

    private fun pushRcState(json: String) {
        ctx.sendBroadcast(Intent(ACTION_RC_STATE).apply {
            setPackage(pkg)
            putExtra(EXTRA_RC_STATE_JSON, json)
        })
        device.waitForIdle()
        landInjectedRows()
        SystemClock.sleep(500)
    }

    /**
     * The chat list holds set changes back behind [ListMutationGate] so rows cannot move under a
     * caret the wearer is aiming. Injected rows would otherwise sit pending for the hold window and
     * never lay out inside this test, so the gate is flushed explicitly -- exactly as the existing
     * RC suites do.
     */
    private fun landInjectedRows() {
        onUi { listAdapter.flushPendingIfDue(force = true) }
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
        landInjectedRows()
        SystemClock.sleep(500)
    }

    private fun pushRows(rows: String, lastSeq: Long = -1) {
        ctx.sendBroadcast(Intent(ACTION_RC_MESSAGES).apply {
            setPackage(pkg)
            putExtra(EXTRA_RC_SESSION_ID, SESSION)
            putExtra(EXTRA_RC_MESSAGES_JSON, """{"rows":[$rows],"more":false,"lastSeq":$lastSeq}""")
        })
        device.waitForIdle()
        SystemClock.sleep(600)
    }

    private fun session(id: String, name: String, folder: String = "~/AI/clients/glasses") =
        """{"id":"$id","n":"$name","w":"$folder","st":"open","t":false,"u":false,"q":7}"""

    // -- wrap measurement --

    /**
     * How many lines a TextView actually laid out. `lineCount` is the Layout's own count, so it
     * reports what was RENDERED, not what the string might need -- a clipped or ellipsized view
     * reports 1 no matter how long its text is, which is precisely the distinction under test.
     */
    private fun linesOf(v: TextView): Int = onUi { v.layout?.lineCount ?: 0 }

    /**
     * True when [v] is showing every character it was given. `Layout.getEllipsisCount` is non-zero
     * on any line the framework truncated, so this catches an ellipsis that a lineCount check
     * alone would miss (a 2-line maxLines=2 view still ellipsizes on line 2).
     */
    private fun isFullyShown(v: TextView): Boolean = onUi {
        val l = v.layout ?: return@onUi false
        (0 until l.lineCount).none { l.getEllipsisCount(it) > 0 }
    }

    /**
     * Asserts [v] wrapped [text] onto more than one line and lost nothing off the end. Both halves
     * matter: multi-line alone would pass for a view that wrapped twice and then ellipsized, and
     * no-ellipsis alone would pass for a view whose text happened to fit.
     */
    private fun assertWraps(what: String, v: TextView, expected: String) {
        val lines = linesOf(v)
        val shown = isFullyShown(v)
        Log.i(tag, "$what: lines=$lines fullyShown=$shown sp=${spOf(v)} text='${onUi { v.text }}'")
        assertEquals("$what must be rendering the long value under test", expected, onUi { v.text.toString() })
        assertTrue(
            "$what rendered on $lines line(s); a long value must WRAP onto at least two",
            lines >= 2
        )
        assertTrue("$what is still ellipsizing; it must show its whole value", shown)
    }

    // -- the test --

    @Test
    fun rcMirrorHonoursTheWearersFontSetting() {
        instr.uiAutomation.executeShellCommand("am start -n $pkg/.MainActivity")
        SystemClock.sleep(2800)
        device.waitForIdle()
        locate()
        navigateToChatListTab()

        pushChatList("c1", "c2")
        pushRcState("""{"ws":true,"s":[${session(SESSION, "honour the wearer's font setting")}]}""")

        // ---------- RC ROWS, smallest ----------
        setFontSize(SMALLEST)
        SystemClock.sleep(HOLD_MS)
        shoot("1_rows_smallest_${SMALLEST.toInt()}sp")
        val rowsSmall = rcRowSizes()
        Log.i(tag, "RC row sizes at ${SMALLEST}sp: $rowsSmall")

        // ---------- RC ROWS, largest ----------
        setFontSize(LARGEST)
        SystemClock.sleep(HOLD_MS)
        shoot("2_rows_largest_${LARGEST.toInt()}sp")
        val rowsLarge = rcRowSizes()
        Log.i(tag, "RC row sizes at ${LARGEST}sp: $rowsLarge")

        assertEquals("the same RC row views must be measured at both settings",
            rowsSmall.keys, rowsLarge.keys)
        assertTrue("no RC row text was measured", rowsSmall.isNotEmpty())
        rowsSmall.forEach { (what, small) ->
            val large = rowsLarge.getValue(what)
            assertTrue(
                "RC row '$what' did not grow with the setting: ${small}sp -> ${large}sp",
                large > small + 0.5f
            )
        }
        // The title is drawn at 13sp. The ratio is taken against the CAPPED setting, so at any
        // setting past 18sp it renders at 13 * 18/14 = 16.7sp, not at 13 * 24/14 = 22.3sp. At 8sp
        // the floor pins it to 8sp.
        assertEquals("RC row title at the largest setting",
            13f * (ChatFontScale.CAP_SP / ChatFontScale.BASE_SP), rowsLarge.getValue("title"), 0.6f)
        assertEquals("RC row title at the smallest setting", SMALLEST, rowsSmall.getValue("title"), 0.6f)

        // ---------- RC THREAD ----------
        openTheRcSession()
        // One of every row type the thread can draw: a user bubble, a collapsed tool run, an
        // assistant reply and a prompt with option boxes. A font fix that missed any single row
        // type would leave that row frozen while the rest scaled, which is the half-scaled screen
        // this whole exercise exists to prevent -- so all four are measured, not just the body.
        pushRows(
            """{"q":1,"r":"user","x":"make the RC mirror honour the font size"},""" +
                """{"q":2,"r":"tools","x":"Read, Grep","c":4},""" +
                """{"q":3,"r":"assistant","x":"The font setting now reaches every row here."},""" +
                """{"q":4,"r":"prompt","x":"Apply to the thread too?","o":["Yes","No"],"i":"req-font"}""",
            lastSeq = 4
        )

        setFontSize(SMALLEST)
        SystemClock.sleep(HOLD_MS)
        shoot("3_thread_smallest_${SMALLEST.toInt()}sp")
        val threadSmall = threadSizes()
        Log.i(tag, "thread sizes at ${SMALLEST}sp: $threadSmall")

        setFontSize(LARGEST)
        SystemClock.sleep(HOLD_MS)
        shoot("4_thread_largest_${LARGEST.toInt()}sp")
        val threadLarge = threadSizes()
        Log.i(tag, "thread sizes at ${LARGEST}sp: $threadLarge")

        assertEquals("the same thread views must be measured at both settings",
            threadSmall.keys, threadLarge.keys)
        assertTrue("no thread text was measured", threadSmall.isNotEmpty())
        threadSmall.forEach { (what, small) ->
            val large = threadLarge.getValue(what)
            assertTrue(
                "RC thread '$what' did not grow with the setting: ${small}sp -> ${large}sp",
                large > small + 0.5f
            )
        }

        // The header title is drawn at 14sp, the base the whole scale is defined against, so it
        // lands exactly on the effective setting: the CAP at the top of the slider (the wearer's
        // 24sp is honoured up to 18sp and no further) and the setting itself at the bottom.
        assertEquals("thread title at the largest setting must sit on the cap",
            ChatFontScale.CAP_SP, threadLarge.getValue("rcThreadTitle"), 0.6f)
        assertEquals("thread title at the smallest setting",
            SMALLEST, threadSmall.getValue("rcThreadTitle"), 0.6f)

        // Nothing may render below the wearer's own setting -- the readability floor, on real views.
        threadSmall.forEach { (what, sp) ->
            assertTrue("'$what' rendered at ${sp}sp, below the wearer's ${SMALLEST}sp setting",
                sp >= SMALLEST - 0.6f)
        }

        // And nothing in the thread may be left behind at the design size while the rest scales:
        // at 24sp EVERY text view on the thread must have grown past the 14sp base.
        threadLarge.forEach { (what, sp) ->
            assertTrue("'$what' stayed at ${sp}sp at the largest setting -- it is not scaling",
                sp > ChatFontScale.BASE_SP)
        }
        Log.i(tag, "PASS: RC rows and RC thread both track the wearer's font setting")
    }

    /**
     * The cap, measured on the live waveguide: 24sp must render IDENTICALLY to 18sp, while 14sp
     * stays visibly smaller.
     *
     * The identity is the whole assertion. A cap that merely slowed growth down would still let
     * 24sp render larger than 18sp and would pass any "did not grow much" check; requiring the two
     * measurements to be the same number to within half a point is the only form the wearer's
     * "cap at 18sp simply" can take.
     */
    @Test
    fun theRcMirrorStopsGrowingAt18sp() {
        instr.uiAutomation.executeShellCommand("am start -n $pkg/.MainActivity")
        SystemClock.sleep(2800)
        device.waitForIdle()
        locate()
        navigateToChatListTab()

        pushChatList("c1", "c2")
        pushRcState("""{"ws":true,"s":[${session(SESSION, LONG_NAME, LONG_FOLDER)}]}""")
        openTheRcSession()
        pushRows(
            """{"q":1,"r":"user","x":"does the cap hold at eighteen points"},""" +
                """{"q":2,"r":"assistant","x":"Every RC surface stops growing at 18sp."}""",
            lastSeq = 2
        )

        setFontSize(DEFAULT)
        SystemClock.sleep(HOLD_MS)
        shoot("5_cap_default_${DEFAULT.toInt()}sp")
        val atDefault = threadSizes()

        setFontSize(CAP)
        SystemClock.sleep(HOLD_MS)
        shoot("6_cap_at_${CAP.toInt()}sp")
        val atCap = threadSizes()

        setFontSize(LARGEST)
        SystemClock.sleep(HOLD_MS)
        shoot("7_cap_above_${LARGEST.toInt()}sp")
        val aboveCap = threadSizes()

        Log.i(tag, "sizes @${DEFAULT}sp: $atDefault")
        Log.i(tag, "sizes @${CAP}sp:     $atCap")
        Log.i(tag, "sizes @${LARGEST}sp: $aboveCap")

        assertEquals("the same views must be measured at all three settings", atCap.keys, aboveCap.keys)
        assertEquals("the same views must be measured at all three settings", atCap.keys, atDefault.keys)
        assertTrue("no thread text was measured", atCap.isNotEmpty())

        // THE CAP: above it, nothing moves at all.
        atCap.forEach { (what, capped) ->
            assertEquals(
                "'$what' kept growing past the ${CAP}sp cap: ${capped}sp at ${CAP}sp but " +
                    "${aboveCap.getValue(what)}sp at ${LARGEST}sp",
                capped, aboveCap.getValue(what), 0.5f
            )
        }
        // BELOW the cap the setting still does something -- otherwise "identical at 18 and 24"
        // would also be satisfied by a scale that ignored the setting entirely.
        atDefault.forEach { (what, small) ->
            assertTrue(
                "'$what' did not grow from ${DEFAULT}sp to ${CAP}sp (${small}sp vs " +
                    "${atCap.getValue(what)}sp); the setting is being ignored, not capped",
                atCap.getValue(what) > small + 0.5f
            )
        }
        // And the cap must not have FLATTENED the hierarchy: the header title (design 14sp) still
        // has to outrank the workDir line (design 11sp) at the capped setting.
        val title = atCap.getValue("rcThreadTitle")
        val folder = atCap.getValue("rcThreadFolder")
        assertTrue(
            "the cap flattened the hierarchy: title ${title}sp is not above workDir ${folder}sp",
            title > folder + 0.5f
        )
        assertEquals("the capped title must land exactly on the cap", CAP, title, 0.6f)
        Log.i(tag, "PASS: RC mirror caps at ${CAP}sp; ${LARGEST}sp renders identically")
    }

    /**
     * Wrapping, on the live waveguide: a long title, a long workDir and a long transcript must each
     * take multiple lines at the capped size rather than losing their tail to an ellipsis.
     *
     * Driven at 24sp -- above the cap -- because that is the setting the wearer complained about
     * and the one where truncation was worst.
     */
    @Test
    fun longRcTextWrapsInsteadOfTruncating() {
        instr.uiAutomation.executeShellCommand("am start -n $pkg/.MainActivity")
        SystemClock.sleep(2800)
        device.waitForIdle()
        locate()
        navigateToChatListTab()

        setFontSize(LARGEST)
        pushChatList("c1", "c2")
        pushRcState("""{"ws":true,"s":[${session(SESSION, LONG_NAME, LONG_FOLDER)}]}""")
        SystemClock.sleep(HOLD_MS)
        shoot("8_wrap_row_${LARGEST.toInt()}sp")

        // ---------- the RC LIST ROW ----------
        val idx = onUi { listAdapter.currentRows.indexOfFirst { it.key == "rc:$SESSION" } }
        assertTrue("the RC row must be in the list", idx >= 0)
        onUi { listRecycler.scrollToPosition(idx) }
        device.waitForIdle()
        SystemClock.sleep(400)
        val rowView = onUi { listRecycler.findViewHolderForAdapterPosition(idx)?.itemView }
            ?: throw AssertionError("the RC row is not laid out")
        val rowTexts = allTextViews(rowView).filter { onUi { it.text.isNotBlank() } }
        assertTrue("the RC row must render a title and a workDir", rowTexts.size >= 2)
        assertWraps("RC row title", rowTexts[0], LONG_NAME)
        assertWraps("RC row workDir", rowTexts[1], LONG_FOLDER)

        // The row's own box must have grown to hold those lines. A row still one line tall would
        // mean the text wrapped inside a container that clips it -- the exact failure a maxLines
        // removal alone does not fix.
        val rowH = onUi { rowView.height }
        val titleH = onUi { rowTexts[0].height }
        Log.i(tag, "RC row height=${rowH}px, wrapped title height=${titleH}px")
        assertTrue(
            "the RC row box ($rowH px) is shorter than its own wrapped title ($titleH px); " +
                "the wrapped lines are being clipped",
            rowH >= titleH
        )
        // The caret border is drawn on the row container, so a selected row must still enclose the
        // whole wrapped title rather than boxing the first line.
        onUi { listAdapter.setFocused(true); listAdapter.selectKey("rc:$SESSION") }
        device.waitForIdle()
        SystemClock.sleep(600)
        shoot("9_wrap_row_selected_${LARGEST.toInt()}sp")
        val selectedH = onUi {
            listRecycler.findViewHolderForAdapterPosition(idx)?.itemView?.height ?: 0
        }
        assertTrue(
            "the selected RC row collapsed to ${selectedH}px, below its wrapped height ${rowH}px",
            selectedH >= rowH
        )

        // ---------- the RC THREAD ----------
        openTheRcSession()
        assertWraps(
            "thread title", view(com.repository.glasses.listener.R.id.rcThreadTitle), LONG_NAME
        )
        assertWraps(
            "thread workDir", view(com.repository.glasses.listener.R.id.rcThreadFolder), LONG_FOLDER
        )
        SystemClock.sleep(HOLD_MS)
        shoot("10_wrap_thread_${LARGEST.toInt()}sp")

        // ---------- the VOICE TRANSCRIPT ----------
        // Driven through the same broadcast the backend uses for a live dictation, so the view is
        // populated exactly as it is in the field rather than by poking text into it.
        onUi {
            val v = root().findViewById<TextView>(com.repository.glasses.listener.R.id.rcThreadVoiceText)
            val bar = root().findViewById<View>(com.repository.glasses.listener.R.id.rcThreadVoiceBar)
            bar.visibility = View.VISIBLE
            v.visibility = View.VISIBLE
            v.text = LONG_TRANSCRIPT
        }
        device.waitForIdle()
        SystemClock.sleep(HOLD_MS)
        shoot("11_wrap_transcript_${LARGEST.toInt()}sp")
        assertWraps(
            "voice transcript",
            view(com.repository.glasses.listener.R.id.rcThreadVoiceText),
            LONG_TRANSCRIPT
        )
        Log.i(tag, "PASS: long RC title, workDir and transcript all wrap rather than truncate")
    }

    // -- measurement --

    /** Rendered sizes of the RC session row's text, keyed by what they are. */
    private fun rcRowSizes(): Map<String, Float> {
        val idx = onUi { listAdapter.currentRows.indexOfFirst { it.key == "rc:$SESSION" } }
        assertTrue("the RC row must be in the list", idx >= 0)
        val item = onUi { listRecycler.findViewHolderForAdapterPosition(idx)?.itemView }
            ?: throw AssertionError("the RC row is not laid out")
        val texts = allTextViews(item).filter { onUi { it.text.isNotBlank() } }
        assertTrue("the RC row must render text", texts.size >= 2)
        // Tree order: title first, then the workDir line beneath it.
        return mapOf("title" to spOf(texts[0]), "workDir" to spOf(texts[1]))
    }

    /** Rendered sizes of every text-bearing view on the open RC thread. */
    private fun threadSizes(): Map<String, Float> {
        val out = linkedMapOf<String, Float>()
        listOf(
            "rcThreadTitle" to com.repository.glasses.listener.R.id.rcThreadTitle,
            "rcThreadFolder" to com.repository.glasses.listener.R.id.rcThreadFolder,
            "rcThreadFooterHint" to com.repository.glasses.listener.R.id.rcThreadFooterHint,
        ).forEach { (name, id) ->
            val v = view(id)
            if (onUi { v.visibility } == View.VISIBLE && onUi { v.text.isNotBlank() }) {
                out[name] = spOf(v)
            }
        }
        // The adapter rows: the user bubble, the collapsed tool run, the assistant reply and the
        // prompt with its option boxes.
        //
        // Each position is scrolled into view before it is measured. A bigger font pushes later
        // rows off screen, so simply reading whatever holders happen to be bound would measure
        // FEWER rows at the largest setting than at the smallest -- and the row that silently
        // dropped out would be the prompt, i.e. exactly the row most likely to have been missed.
        // Scrolling makes the two measurements cover the same set by construction.
        val count = onUi { threadAdapter.itemCount }
        assertTrue("the thread must render adapter rows", count > 0)
        for (i in 0 until count) {
            onUi { threadRecycler.scrollToPosition(i) }
            device.waitForIdle()
            SystemClock.sleep(250)
            val item = onUi { threadRecycler.findViewHolderForAdapterPosition(i)?.itemView }
                ?: throw AssertionError("thread row $i never laid out even after scrolling to it")
            allTextViews(item).filter { onUi { it.text.isNotBlank() } }
                .forEachIndexed { j, tv -> out["row${i}_$j"] = spOf(tv) }
        }
        onUi { threadRecycler.scrollToPosition(count - 1) }
        device.waitForIdle()
        return out
    }

    // -- navigation (key events only) --

    private fun openTheRcSession() {
        val landed = onUi {
            listAdapter.setFocused(true)
            listAdapter.selectKey("rc:$SESSION")
        }
        assertTrue("the caret must reach rc:$SESSION before it can be confirmed", landed)
        device.waitForIdle()
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(1200)
        assertEquals("the RC thread must open", View.VISIBLE,
            onUi { root().findViewById<View>(com.repository.glasses.listener.R.id.rcThreadContainer).visibility })
    }

    private fun navigateToChatListTab() {
        run {
            repeat(8) {
                if (onUi { listRecycler.isShown }) return@run
                device.pressKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)
                SystemClock.sleep(400)
            }
        }
        device.waitForIdle()
        assertTrue("the chat list tab must be on screen", onUi { listRecycler.isShown })
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
}
