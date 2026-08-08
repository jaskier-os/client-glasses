package com.repository.glasses.listener.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards that the RC mirror UI has no text size the wearer's font setting cannot reach.
 *
 * This is checked against the SOURCE rather than against a rendered view because the alternative
 * -- asserting on an inflated Activity -- needs a device, and the failure being guarded here is
 * one of OMISSION: a text view added later that nobody remembered to route through
 * [ChatFontScale]. A behavioural test can only assert on views it already knows about, so it can
 * never fail for the view that was forgotten. Scanning the source can.
 *
 * Two surfaces are covered:
 *  - [RcThreadAdapter] and the RC rows in [ChatListAdapter]: programmatic `setTextSize` calls.
 *  - The `rcThreadContainer` block of `activity_main.xml`: the thread's chrome (title, workDir,
 *    footer hint, `[mic]` glyph, voice transcript, countdown). XML `android:textSize` is a
 *    compile-time constant no setting can move, so those views must instead be sized in code at
 *    bind time.
 */
class RcFontPropagationTest {

    private val srcRoot = File("src/main/java/com/repository/glasses/listener")
    private val layout = File("src/main/res/layout/activity_main.xml")

    private fun read(path: String): String {
        val f = File(srcRoot, path)
        assertTrue("missing source file: ${f.absolutePath}", f.isFile)
        return f.readText()
    }

    /**
     * Every `setTextSize` in the RC thread must take its value from [ChatFontScale], never a bare
     * literal. `ChatFontScale.sp(14f)` is fine; `setTextSize(..., 14f)` is not.
     */
    @Test
    fun `RcThreadAdapter sizes every text view through ChatFontScale`() {
        val offenders = hardcodedSetTextSizeCalls(read("ui/RcThreadAdapter.kt"))
        assertEquals(
            "RcThreadAdapter has hardcoded text sizes the font setting cannot reach: $offenders",
            emptyList<String>(), offenders
        )
    }

    /**
     * The RC rows live in the shared chat-list adapter. The whole adapter is checked, not just the
     * RC holders: the RC rows sit inline with the conversation rows, and a 13sp conversation title
     * next to a scaled RC title is the same half-scaled screen this exists to prevent.
     */
    @Test
    fun `ChatListAdapter sizes every text view through ChatFontScale`() {
        val offenders = hardcodedSetTextSizeCalls(read("ui/ChatListAdapter.kt"))
        assertEquals(
            "ChatListAdapter has hardcoded text sizes the font setting cannot reach: $offenders",
            emptyList<String>(), offenders
        )
    }

    /**
     * No `android:textSize` may survive inside `rcThreadContainer`. An XML size is frozen at
     * inflate time, so a view carrying one silently ignores every later setting change.
     */
    @Test
    fun `RC thread layout declares no fixed textSize`() {
        assertTrue("missing layout: ${layout.absolutePath}", layout.isFile)
        val block = rcThreadContainerBlock(layout.readText())
        val offenders = Regex("""android:textSize="[^"]*"""").findAll(block)
            .map { it.value }.toList()
        assertEquals(
            "rcThreadContainer has fixed XML text sizes: $offenders", emptyList<String>(), offenders
        )
    }

    /**
     * Sizing at bind time is only half of it: the views must actually be RE-sized when the setting
     * changes while the thread is open, matching what the existing chat UI does (its
     * `chatFontSizeReceiver` calls `notifyDataSetChanged`). Without a re-apply the open thread
     * keeps the old size until it is closed and reopened.
     */
    @Test
    fun `a live font change re-applies to the open RC thread`() {
        val main = read("MainActivity.kt")
        // Both anchors are looked up with an explicit missing-delimiter value of "". Kotlin's
        // substringAfter/Before otherwise return the WHOLE string when the anchor is absent, which
        // would silently widen the window to all of MainActivity -- every assertion below would
        // then pass on unrelated code and this test would go vacuous the moment the receiver was
        // renamed. Failing loudly on a rename is the point.
        val receiver = main
            .substringAfter("private val chatFontSizeReceiver", "")
            .substringBefore("private fun applyBottomPadding", "")
        assertTrue(
            "could not locate the chatFontSizeReceiver body; the anchors this test scans between " +
                "have been renamed and it would otherwise pass vacuously",
            receiver.isNotBlank() && receiver.length < 4000
        )
        assertTrue(
            "chatFontSizeReceiver must re-render the RC thread chrome, else an open thread keeps " +
                "the old size: $receiver",
            receiver.contains("applyRcThreadFontSizes")
        )
        assertTrue(
            "chatFontSizeReceiver must invalidate the RC thread adapter: $receiver",
            receiver.contains("rcThreadAdapter.notifyDataSetChanged()")
        )
        assertTrue(
            "chatFontSizeReceiver must invalidate the chat-list adapter so the RC rows resize: " +
                receiver,
            receiver.contains("chatListAdapter.rebindAllContent()")
        )
    }

    /**
     * The chat list must be re-bound through its own content payload, never a blanket invalidate.
     *
     * `ChatListAdapter`'s contract says set changes are diffed and NOT `notifyDataSetChanged`,
     * because a blanket invalidate cancels the caret's border ValueAnimator and restarts every
     * spinner on screen. Resizing text is a content change like any other and must respect that;
     * reaching for `notifyDataSetChanged` here would make the caret flicker and every RC spinner
     * jump every time the wearer moved the font slider.
     */
    @Test
    fun `the chat list resizes via its content payload, not a blanket invalidate`() {
        val main = read("MainActivity.kt")
        assertTrue(
            "MainActivity must not blanket-invalidate the chat list; use rebindAllContent()",
            !main.contains("chatListAdapter.notifyDataSetChanged()")
        )
        val adapter = read("ui/ChatListAdapter.kt")
        val fn = adapter.substringAfter("fun rebindAllContent()", "")
            .substringBefore("\n    fun ").substringBefore("\n    private fun ")
        assertTrue("ChatListAdapter has no rebindAllContent()", fn.isNotBlank())
        assertTrue(
            "rebindAllContent must use the PAYLOAD_CONTENT range notify, not a blanket one: $fn",
            fn.contains("notifyItemRangeChanged") && fn.contains("PAYLOAD_CONTENT")
        )
    }

    /** Every RC thread chrome view must be sized in code, since none may be sized in XML. */
    @Test
    fun `every RC thread chrome view is sized in code`() {
        val main = read("MainActivity.kt")
        // Explicit missing-delimiter values, for the same reason as the live-change test: without
        // them a renamed function widens the window to all of MainActivity, which mentions every
        // rcThread* id somewhere else, so all eight assertions below would pass vacuously.
        val fn = main
            .substringAfter("private fun applyRcThreadFontSizes()", "")
            .substringBefore("\n    private fun ", "")
        assertTrue(
            "could not locate applyRcThreadFontSizes(); it has been renamed and this test would " +
                "otherwise pass vacuously against unrelated code",
            fn.isNotBlank() && fn.length < 2000
        )
        listOf(
            "rcThreadTitle",
            "rcThreadFolder",
            "rcThreadMicGlyph",
            "rcThreadFooterHint",
            "rcThreadVoiceText",
            "rcThreadVoiceMicGlyph",
            // The countdown's own hint and seconds label moved into SendCountdownBar, which is
            // shared with the AI chat. MainActivity must still poke it, and BOTH instances of it.
            "rcSendCountdown",
            "chatSendCountdown",
        ).forEach { view ->
            assertTrue("applyRcThreadFontSizes does not size $view", fn.contains(view))
        }
        assertTrue(
            "the shared countdown bar must be re-scaled through applyFontScale(), not by " +
                "MainActivity reaching into its children",
            fn.contains("applyFontScale()")
        )
    }

    /**
     * The shared countdown bar sizes its OWN text, off the same [ChatFontScale] everything else
     * uses. A hard-coded sp there would strand it at one size while the rest of the chrome scaled.
     */
    @Test
    fun `the shared countdown bar scales its own text off ChatFontScale`() {
        val bar = read("ui/SendCountdownBar.kt")
        val fn = bar
            .substringAfter("fun applyFontScale()", "")
            .substringBefore("\n    /**", "")
        assertTrue("SendCountdownBar has no applyFontScale()", fn.isNotBlank() && fn.length < 800)
        assertTrue(
            "applyFontScale must route both labels through ChatFontScale.sp: $fn",
            Regex("ChatFontScale\\.sp").findAll(fn).count() >= 2
        )
        assertTrue(
            "SendCountdownBar must not hard-code a text size in sp units outside applyFontScale",
            !bar.substringBefore("fun applyFontScale()")
                .contains("COMPLEX_UNIT_SP")
        )
    }

    /**
     * Sizes must be applied when a row BINDS, not when its holder is created.
     *
     * `notifyDataSetChanged` re-binds; it does not re-create view holders. A size applied in
     * `onCreateViewHolder` therefore sticks for the lifetime of that holder, so after a live
     * setting change every already-created row would keep the old size while newly-scrolled rows
     * got the new one -- the same half-scaled screen, only intermittent and much harder to spot.
     */
    @Test
    fun `adapters apply text sizes at bind time, not at holder creation`() {
        listOf("ui/RcThreadAdapter.kt", "ui/ChatListAdapter.kt").forEach { path ->
            val src = read(path)
            val createRegion = src.substring(
                src.indexOf("override fun onCreateViewHolder").also {
                    assertTrue("$path has no onCreateViewHolder", it >= 0)
                },
                src.indexOf("override fun onBindViewHolder").also {
                    assertTrue("$path has no onBindViewHolder", it >= 0)
                }
            )
            val offenders = Regex("""setTextSize\([^)]*\)""").findAll(createRegion)
                .map { it.value }.toList()
            assertEquals(
                "$path sizes text in the create-holder region; a recycled holder would keep the " +
                    "old size after a live setting change: $offenders",
                emptyList<String>(), offenders
            )
        }
    }

    // -- helpers --

    /** `setTextSize(...)` calls whose size argument is not a ChatFontScale expression. */
    private fun hardcodedSetTextSizeCalls(src: String): List<String> =
        Regex("""setTextSize\([^)]*\)""").findAll(src)
            .map { it.value }
            .filterNot { it.contains("ChatFontScale") }
            .toList()

    /** The `rcThreadContainer` element, bounded by tag depth so siblings are not swept in. */
    private fun rcThreadContainerBlock(xml: String): String {
        val start = xml.indexOf("""android:id="@+id/rcThreadContainer"""")
        assertTrue("rcThreadContainer not found in layout", start > 0)
        // Walk back to the opening '<' of the element that carries the id.
        val open = xml.lastIndexOf('<', start)
        var depth = 0
        var i = open
        while (i < xml.length) {
            when {
                xml.startsWith("<!--", i) -> {
                    i = xml.indexOf("-->", i).let { if (it < 0) xml.length else it + 3 }
                    continue
                }
                xml.startsWith("</", i) -> {
                    depth--
                    if (depth == 0) return xml.substring(open, xml.indexOf('>', i) + 1)
                    i = xml.indexOf('>', i) + 1
                    continue
                }
                xml[i] == '<' -> {
                    val end = xml.indexOf('>', i)
                    val selfClosing = xml[end - 1] == '/'
                    if (!selfClosing) depth++
                    i = end + 1
                    if (depth == 0) return xml.substring(open, end + 1)
                    continue
                }
                else -> i++
            }
        }
        return xml.substring(open)
    }
}
