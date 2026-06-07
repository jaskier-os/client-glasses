package com.repository.glasses.listener.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Instrumented test for the Assistant fact-check card overlay
 * ([CopilotCardOverlay]).
 *
 * This drives a realistic working-assistant sequence with mocked fact-check
 * cards and PROGRAMMATICALLY asserts that each card actually rendered on the
 * HUD -- not merely that the showCard/dismissCard/hideAll code paths ran.
 *
 * Verification method: FRAMEBUFFER PIXEL COUNTING.
 *
 * CopilotCardOverlay renders each card into a TYPE_APPLICATION_OVERLAY window
 * with FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE. UiAutomator's accessibility tree
 * CANNOT see such overlay windows, so By.textContains assertions are useless
 * here -- they fail even when the card is clearly rendered on the waveguide. The
 * framebuffer, however, DOES include overlay windows. We therefore capture the
 * actual screen pixels via UiAutomation.takeScreenshot() and count green-on-black
 * pixels: the waveguide cards are pure green (stroke GLOW + text MID) on black
 * (VOID), so an active card produces a distinct cluster of green pixels while an
 * empty HUD produces ~baseline.
 *
 * Assertion model (all deltas measured against an evidence-based baseline that is
 * captured BEFORE any card is shown, so other MainActivity HUD chrome is
 * tolerated):
 *   - show c1   -> greenCount INCREASES meaningfully over baseline
 *   - stack c2  -> greenCount INCREASES again over the single-card count
 *   - dismiss c1-> greenCount DECREASES vs two-card count but stays ABOVE baseline (c2 remains)
 *   - show c3   -> greenCount INCREASES again (c2 + c3 present)
 *   - hideAll   -> greenCount returns to ~baseline
 * Every count is logged via Log.i with its step label so the run output is
 * self-documenting and thresholds are evidence-based.
 *
 * Recording requirement: SystemClock.sleep waits (~2.5-3s) are inserted between
 * steps so every rendered state persists on screen long enough for an external
 * screen recording to capture it.
 *
 * SAFE on-device run procedure: see ASSISTANT_OVERLAY_TEST.md next to this file.
 */
@RunWith(AndroidJUnit4::class)
class CopilotCardOverlayInstrumentedTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instr.targetContext
    private val device: UiDevice = UiDevice.getInstance(instr)

    private lateinit var overlay: CopilotCardOverlay

    private val c1Text = "Note: the Eiffel Tower is about 330 meters tall, not 300."
    private val c2Text = "Note: Mount Everest is the tallest mountain above sea level at 8,849 m."
    private val c3Text = "Sanity check: 1995 was 30 years ago, not 20."

    private val tag = "AsstOverlayTest"
    private val holdMs = 2800L
    // Settle time after a showCard/dismiss/hideAll call so the overlay window is
    // added/removed and the ~150ms appear/disappear ramp has fully applied before
    // we sample the framebuffer.
    private val settleMs = 800L

    private fun shell(cmd: String): String {
        val pfd = instr.uiAutomation.executeShellCommand(cmd)
        return java.io.FileInputStream(pfd.fileDescriptor).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun screenshotDir(): File {
        val dir = File(targetContext.getExternalFilesDir(null), "asst-overlay-test")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Capture the live framebuffer (which includes overlay windows) as a Bitmap,
     * assert it is non-null, and save a PNG artifact named per step.
     */
    private fun captureBitmap(tag: String): Bitmap {
        val bmp = instr.uiAutomation.takeScreenshot()
        assertNotNull("FAIL [$tag]: takeScreenshot returned null", bmp)
        val file = File(screenshotDir(), "$tag.png")
        try {
            FileOutputStream(file).use { bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Log.i(this.tag, "step=$tag saved ${file.absolutePath} (${bmp!!.width}x${bmp.height})")
        } catch (e: Exception) {
            Log.w(this.tag, "step=$tag PNG save failed: ${e.message}")
        }
        return bmp!!
    }

    /**
     * Count pixels that are clearly green-on-black: high green channel, low red
     * and blue. The waveguide cards (green stroke + green text on black) produce
     * a dense cluster of such pixels; an empty black HUD produces ~0.
     */
    private fun greenPixelCount(bmp: Bitmap): Int {
        val w = bmp.width
        val h = bmp.height
        val row = IntArray(w)
        var count = 0
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]
                if (Color.green(p) > 80 && Color.red(p) < 60 && Color.blue(p) < 60) {
                    count++
                }
            }
        }
        return count
    }

    @Before
    fun setUp() {
        // Grant the overlay permission defensively. appops set is idempotent.
        // We do NOT install or uninstall anything here.
        try {
            shell("appops set com.repository.glasses.listener SYSTEM_ALERT_WINDOW allow")
        } catch (e: Exception) {
            Log.w(tag, "appops grant skipped/failed (may already be granted): ${e.message}")
        }
        device.waitForIdle()

        // WindowManager.addView must run on a Looper thread. Constructing the
        // overlay on the main thread is the safest choice; showCard/dismiss/hideAll
        // post their own work to the overlay's main Handler internally.
        instr.runOnMainSync {
            overlay = CopilotCardOverlay(targetContext)
            overlay.remoteLog = { Log.i(tag, it) }
        }
    }

    @Test
    fun factCheckCardSequenceRendersAndDismisses() {
        // Baseline: capture the HUD BEFORE showing any card. MainActivity chrome
        // may contribute some green, so we record this and assert RELATIVE deltas.
        SystemClock.sleep(settleMs)
        val baseBmp = captureBitmap("0_baseline")
        val baseGreen = greenPixelCount(baseBmp)
        Log.i(tag, "greenCount step=0:baseline -> $baseGreen")

        // Threshold: a card must add at least this many green pixels to count as
        // rendered. Tuned conservatively; actual deltas are far larger (a card is
        // a multi-line green-stroked box of green text). Logged counts above make
        // the real numbers visible for future tuning.
        val minCardDelta = 300

        // 1. First fact-check card appears.
        overlay.showCard("c1", "reply", "the eiffel tower", c1Text)
        SystemClock.sleep(settleMs)
        val g1 = greenPixelCount(captureBitmap("1_show_c1"))
        Log.i(tag, "greenCount step=1:show-c1 -> $g1 (baseline=$baseGreen, delta=${g1 - baseGreen})")
        assertTrue(
            "FAIL [1:show-c1]: card c1 (Eiffel Tower) did not render -- green pixels " +
                "$g1 not meaningfully above baseline $baseGreen (need +$minCardDelta).",
            g1 - baseGreen >= minCardDelta
        )
        SystemClock.sleep(holdMs)

        // 2. Second card stacks below the first; total green must increase again.
        overlay.showCard("c2", "note", "mount everest", c2Text)
        SystemClock.sleep(settleMs)
        val g2 = greenPixelCount(captureBitmap("2_stack_c1_c2"))
        Log.i(tag, "greenCount step=2:stack-c1-c2 -> $g2 (single-card=$g1, delta=${g2 - g1})")
        assertTrue(
            "FAIL [2:stack-c1-c2]: second card c2 (Mount Everest) did not render -- green " +
                "pixels $g2 not above single-card count $g1 (need +$minCardDelta).",
            g2 - g1 >= minCardDelta
        )
        SystemClock.sleep(holdMs)

        // 3. Dismiss c1. Green must DECREASE vs two-card count (one card removed)
        //    but remain ABOVE baseline (c2 survives and glides up).
        overlay.dismissCard("c1")
        SystemClock.sleep(settleMs)
        val g3 = greenPixelCount(captureBitmap("3_dismiss_c1"))
        Log.i(tag, "greenCount step=3:dismiss-c1 -> $g3 (two-card=$g2, baseline=$baseGreen)")
        assertTrue(
            "FAIL [3:dismiss-c1]: c1 did not go away -- green pixels $g3 not below two-card " +
                "count $g2.",
            g3 < g2
        )
        assertTrue(
            "FAIL [3:dismiss-c1]: c2 (Mount Everest) vanished too -- green pixels $g3 " +
                "fell to/below baseline $baseGreen but c2 should still be visible.",
            g3 - baseGreen >= minCardDelta
        )
        SystemClock.sleep(holdMs)

        // 4. Third sanity-check card appears below the surviving c2.
        overlay.showCard("c3", "note", "sanity check", c3Text)
        SystemClock.sleep(settleMs)
        val g4 = greenPixelCount(captureBitmap("4_show_c3"))
        Log.i(tag, "greenCount step=4:show-c3 -> $g4 (after-dismiss=$g3, delta=${g4 - g3})")
        assertTrue(
            "FAIL [4:show-c3]: third card c3 (Sanity check) did not render -- green pixels " +
                "$g4 not above post-dismiss count $g3 (need +$minCardDelta).",
            g4 - g3 >= minCardDelta
        )
        SystemClock.sleep(holdMs)

        // 5. Tear everything down. Green must return to ~baseline (all cards gone).
        overlay.hideAll()
        SystemClock.sleep(settleMs)
        val g5 = greenPixelCount(captureBitmap("5_hide_all"))
        Log.i(tag, "greenCount step=5:hideAll -> $g5 (baseline=$baseGreen, delta=${g5 - baseGreen})")
        // Allow a small tolerance for AA fringing / unrelated HUD changes.
        val hideTolerance = minCardDelta
        assertTrue(
            "FAIL [5:hideAll]: overlay green did not clear -- green pixels $g5 still well " +
                "above baseline $baseGreen (tolerance $hideTolerance).",
            g5 - baseGreen <= hideTolerance
        )
        SystemClock.sleep(1200)

        Log.i(tag, "factCheckCardSequenceRendersAndDismisses: all pixel assertions passed " +
            "(baseline=$baseGreen c1=$g1 c1c2=$g2 c2only=$g3 c2c3=$g4 cleared=$g5)")
    }

    @After
    fun tearDown() {
        // Ensure no overlay windows leak between runs.
        if (::overlay.isInitialized) {
            instr.runOnMainSync { overlay.hideAll() }
            SystemClock.sleep(600)
        }
    }
}
