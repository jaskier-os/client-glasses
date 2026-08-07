package com.repository.glasses.listener.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the second half of the wearer's font decision: RC text WRAPS, it does not truncate.
 *
 * [ChatFontScale] caps growth at [ChatFontScale.CAP_SP]; capping alone would still leave a long
 * session name cut off with an ellipsis, only at 18sp instead of 24sp. The wearer asked for the
 * opposite trade: let the text take a second and third line rather than lose its tail. On a
 * 480x640 waveguide the tail is the only thing that distinguishes `~/AI/clients/glasses` from
 * `~/AI/clients/phone`, so an ellipsis costs more than a line does.
 *
 * Two failure modes are covered, because removing `maxLines` fixes only one of them:
 *  - the view still declares `maxLines` / `ellipsize`, so it truncates outright;
 *  - the view has no `maxLines` but sits in a FIXED-height box, so it lays out three lines and
 *    the box clips two of them -- which looks like truncation but greps clean.
 *
 * Checked against the source rather than a rendered view for the same reason
 * [RcFontPropagationTest] is: the failure guarded here is one of OMISSION, and a behavioural test
 * can only assert on the views it already knows about.
 */
class RcTextWrapTest {

    private val srcRoot = File("src/main/java/com/repository/glasses/listener")
    private val layout = File("src/main/res/layout/activity_main.xml")

    /**
     * The RC thread chrome the wearer reads prose in. `rcThreadMicGlyph` is deliberately absent:
     * it renders the fixed literal `[mic]`, which cannot overflow at any setting, so a wrap rule
     * on it would guard nothing.
     */
    private val wrappingChromeIds = listOf(
        "rcThreadTitle",
        "rcThreadFolder",
        "rcThreadFooterHint",
        "rcThreadVoiceText",
    )

    private fun read(path: String): String {
        val f = File(srcRoot, path)
        assertTrue("missing source file: ${f.absolutePath}", f.isFile)
        return f.readText()
    }

    /**
     * No RC thread chrome view may cap its line count. A `maxLines` of 1 or 3 is the truncation
     * this feature removes; `ellipsize` without `maxLines` is just as bad, since a single-line
     * TextView with an ellipsize mode still cuts.
     */
    @Test
    fun `no RC thread chrome view caps its lines or ellipsizes`() {
        val xml = layoutText()
        wrappingChromeIds.forEach { id ->
            val element = element(xml, id)
            assertTrue(
                "$id still declares maxLines, so a long value truncates instead of wrapping: " +
                    element,
                !element.contains("android:maxLines")
            )
            assertTrue(
                "$id still declares ellipsize, so a long value is cut rather than wrapped: " +
                    element,
                !element.contains("android:ellipsize")
            )
            assertTrue(
                "$id must not be singleLine either -- that is maxLines=1 under another name: " +
                    element,
                !element.contains("android:singleLine")
            )
        }
    }

    /**
     * Removing `maxLines` from a view that lives in a fixed-height box changes nothing the wearer
     * can see: the second line is laid out and then clipped by the parent. Every wrapping view
     * must therefore be free to grow vertically.
     */
    @Test
    fun `every wrapping RC thread view is free to grow vertically`() {
        val xml = layoutText()
        wrappingChromeIds.forEach { id ->
            val element = element(xml, id)
            assertTrue(
                "$id has no android:layout_height at all: $element",
                element.contains("android:layout_height")
            )
            assertTrue(
                "$id has a fixed layout_height, so wrapped lines are clipped rather than shown: " +
                    element,
                element.contains("""android:layout_height="wrap_content"""")
            )
        }
    }

    /**
     * The containers those views sit in must pass the growth on. A wrap_content TextView inside a
     * fixed-height row is clipped exactly as if it had been given a fixed height itself, so the
     * sub-row (workDir + spinner), the footer (mic glyph + hint) and the voice bar are checked
     * too. The thread's RecyclerView is exempt on purpose: it is the weighted `0dp` element that
     * absorbs whatever the chrome takes, which is what lets the chrome grow at all.
     */
    @Test
    fun `the rows holding the wrapping views can grow with them`() {
        val xml = layoutText()
        listOf("rcThreadSubRow", "rcThreadFooter", "rcThreadVoiceBar").forEach { id ->
            val element = element(xml, id)
            assertTrue(
                "$id has a fixed height, so its wrapping child is clipped: $element",
                element.contains("""android:layout_height="wrap_content"""")
            )
        }
    }

    /**
     * The RC session ROW in the chat list is built in code, not XML. Its title and workDir are the
     * two lines that told the wearer which session a row is, and both truncated at maxLines=1.
     *
     * Only the RC holder is scanned. The plain conversation rows beside it keep their single-line
     * titles: those are a scrolling index of past chats where a wrapped title costs a row of
     * screen for a name the wearer is skimming past, and the wearer's decision was about the RC
     * mirror. Widening this to the whole adapter would silently restyle the chat list.
     */
    @Test
    fun `the RC session row wraps its title and workDir`() {
        val fn = createRcSessionHolder()
        listOf("android.maxLines", "maxLines = 1", "maxLines = 2", "maxLines = 3").forEach {
            assertTrue(
                "createRcSessionHolder still caps lines ($it); the RC row truncates: $fn",
                !fn.contains(it)
            )
        }
        assertTrue(
            "createRcSessionHolder still sets ellipsize; the RC row is cut rather than wrapped: $fn",
            !fn.contains("ellipsize")
        )
        assertTrue(
            "createRcSessionHolder still sets isSingleLine/setSingleLine: $fn",
            !fn.contains("SingleLine")
        )
    }

    /**
     * The RC row grows by growing its holder, so nothing in its view tree may pin a height. The
     * caret border and the selection rail are drawn on those same containers; a pinned height
     * would leave the border boxing one line while the text ran on past it.
     */
    @Test
    fun `nothing in the RC session row pins a height`() {
        val fn = createRcSessionHolder()
        val offenders = Regex("""LayoutParams\(\s*[^)]*?\b\d+\.dpToPx\(\)\s*\)""")
            .findAll(fn).map { it.value }.toList()
        assertEquals(
            "createRcSessionHolder pins a pixel height on a container, which clips wrapped " +
                "lines and leaves the caret border around the first line only: $offenders",
            emptyList<String>(), offenders.filterNot { it.contains("UNREAD_BAR_H_DP") }
        )
    }

    /**
     * A variable-height list must not be told its rows are uniform. `setHasFixedSize(true)` is a
     * promise that adapter content changes cannot change the RecyclerView's own bounds; it is
     * about the RecyclerView, not the rows, but `setItemViewCacheSize`-style optimisations that
     * assume uniform rows would break scrolling outright. The stronger, checkable rule is that no
     * RC list hard-codes a per-row height.
     */
    @Test
    fun `the RC thread rows are measured, not assumed uniform`() {
        val adapter = read("ui/RcThreadAdapter.kt")
        val create = region(
            adapter, "override fun onCreateViewHolder", "override fun onBindViewHolder",
            "RcThreadAdapter.onCreateViewHolder"
        )
        assertTrue(
            "RcThreadAdapter rows must be WRAP_CONTENT so a wrapped reply is fully drawn: $create",
            create.contains("ViewGroup.LayoutParams.WRAP_CONTENT")
        )
        assertTrue(
            "RcThreadAdapter must not cap the lines of a reply: $create",
            !create.contains("maxLines") && !create.contains("ellipsize")
        )
    }

    // -- helpers --
    //
    // Every anchor lookup below passes an explicit missing-delimiter value and then asserts the
    // window is non-blank and bounded. Kotlin's substringAfter/Before otherwise return the WHOLE
    // input when the anchor is absent, and a whole-file window makes every "does not contain"
    // assertion above pass vacuously the moment something is renamed.

    private fun layoutText(): String {
        assertTrue("missing layout: ${layout.absolutePath}", layout.isFile)
        return layout.readText()
    }

    private fun region(src: String, from: String, to: String, what: String): String {
        val window = src.substringAfter(from, "").substringBefore(to, "")
        assertTrue(
            "could not locate $what between '$from' and '$to'; it has been renamed and this " +
                "test would otherwise pass vacuously against unrelated code",
            window.isNotBlank() && window.length < 6000
        )
        return window
    }

    private fun createRcSessionHolder(): String = region(
        read("ui/ChatListAdapter.kt"),
        "private fun createRcSessionHolder(",
        "\n    private fun ",
        "createRcSessionHolder"
    )

    /** The single XML element carrying [id], from its opening `<` to the matching `>`. */
    private fun element(xml: String, id: String): String {
        val at = xml.indexOf("""android:id="@+id/$id"""")
        assertTrue("no view with id $id in the layout", at > 0)
        val open = xml.lastIndexOf('<', at)
        val close = xml.indexOf('>', at)
        assertTrue("malformed element for $id", open in 0 until close)
        return xml.substring(open, close + 1)
    }
}
