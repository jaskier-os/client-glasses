package com.repository.glasses.listener.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where the "Double-tap again to stop" hint sits on the waveguide.
 *
 * It used to be the first row of `mainContentLayout` -- the very TOP of the screen, above the
 * status line and everything else -- while the thing it instructs the wearer about (the waveform
 * pill) is drawn at the BOTTOM. The instruction was as far from its subject as the display allows.
 *
 * The hint is SHARED chrome: `rcShowListeningChrome` in the RC thread and the LISTENING service
 * state in the regular AI chat both drive the same view, deliberately, so the two dictation
 * surfaces cannot drift. There is therefore exactly one placement to assert and it covers both.
 *
 * Layout order is asserted from the XML because the alternative -- inflating the activity -- needs
 * a device, and a placement rule that can only be checked by wearing the glasses is the rule that
 * silently regresses.
 */
class DoubleTapHintPlacementTest {

    private val layout: String by lazy {
        val f = File("src/main/res/layout/activity_main.xml")
        assertTrue("missing layout: ${f.absolutePath}", f.isFile)
        f.readText()
    }

    private fun indexOf(marker: String): Int {
        val i = layout.indexOf(marker)
        assertTrue(
            "could not find '$marker' in activity_main.xml; it has been renamed and every " +
                "ordering assertion below would pass vacuously",
            i >= 0
        )
        return i
    }

    @Test
    fun theHintIsBelowTheContentAreaThatHoldsTheWaveformPill() {
        // The pill is added programmatically into contentFrame with gravity=BOTTOM
        // (MainActivity.showAudioVisualizer). A hint declared AFTER contentFrame in this vertical
        // LinearLayout therefore renders underneath it.
        assertTrue(
            "the hint must come after contentFrame so it reads under the pill, not above it",
            indexOf("@+id/doubleTapHint") > indexOf("@+id/contentFrame")
        )
    }

    @Test
    fun theHintIsASIBLINGOfTheContentFrameAndNotAChildOfIt() {
        // Ordering alone is not enough. Moved INSIDE contentFrame as its last child, the hint
        // would still come "after" contentFrame's opening tag by raw string offset -- and would
        // then be a FrameLayout child stacked ON the bottom-gravity pill, overlapping the very
        // thing it is supposed to sit under. So the boundary asserted is the CLOSE of contentFrame.
        //
        // contentFrame's own close is the FrameLayout terminator that returns to the indent it was
        // opened at; in this file that is a `</FrameLayout>` at 12 spaces, the last one before the
        // tab bar row.
        val closeOfContentFrame = layout.lastIndexOf("            </FrameLayout>", indexOf("@+id/tabBar"))
        assertTrue(
            "could not locate the close of contentFrame; the indentation this test keys on has " +
                "changed and it would otherwise pass vacuously",
            closeOfContentFrame > indexOf("@+id/contentFrame")
        )
        assertTrue(
            "the hint is nested inside contentFrame; as a FrameLayout child it would overlap " +
                "the waveform pill instead of sitting beneath it",
            indexOf("@+id/doubleTapHint") > closeOfContentFrame
        )
    }

    @Test
    fun theHintIsNotAboveTheStatusLineAnyMore() {
        // The exact defect: it was the first child, so it drew above "Listening..." at the very top
        // of the display.
        assertTrue(
            "the hint must no longer precede the status area",
            indexOf("@+id/doubleTapHint") > indexOf("@+id/statusArea")
        )
    }

    @Test
    fun theHintStaysAboveTheTabBarSoItDoesNotDisplaceTheTabs() {
        assertTrue(
            "the tab bar owns the very bottom row; the hint sits between it and the content",
            indexOf("@+id/doubleTapHint") < indexOf("@+id/tabBar")
        )
    }

    @Test
    fun thereIsExactlyOneHintViewSoBothSurfacesShareIt() {
        // Two copies is how the RC thread and the AI chat would come to show it in two different
        // places, which is the drift the shared chrome exists to prevent.
        assertTrue(
            "the hint must not be duplicated per surface",
            Regex("@\\+id/doubleTapHint").findAll(layout).count() == 1
        )
        assertTrue(
            "one string, one view",
            Regex("Double-tap again to stop").findAll(layout).count() == 1
        )
    }

    @Test
    fun theHintIsStillItsOwnFullWidthCentredRowRatherThanOverlappingThePill() {
        val decl = layout.substringAfter("@+id/doubleTapHint").take(600)
        assertTrue("must stay full-width", decl.contains("""android:layout_width="match_parent""""))
        assertTrue("must stay centred", decl.contains("""android:gravity="center""""))
        assertTrue("must start hidden", decl.contains("""android:visibility="gone""""))
    }
}
