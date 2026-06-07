package com.repository.glasses.listener.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Instrumented test for the notification-solo blackout (screen-was-off glanceable
 * notification). Drives the REAL onNotification() path in-process and verifies, via
 * FRAMEBUFFER PIXEL COUNTING, that:
 *   - when a notification arrives while the screen was OFF and the glasses are WORN,
 *     MainActivity blacks out its own content so ONLY the backend notification card is
 *     lit (the bottom tab bar / clock / battery region goes dark), and
 *   - a touchpad key press restores the full UI (bottom region lights up again).
 *
 * Why an instrumented test (not `adb shell am broadcast`): the test hook
 * ACTION_NOTIFICATION_TEST is registered NOT_EXPORTED -- it is a debugging aid, never a
 * shipping feature -- so it is only reachable in-process. This test runs in the app's
 * UID, so its targetContext.sendBroadcast(setPackage(...)) reaches the receiver while an
 * external adb broadcast cannot.
 *
 * Verification method: the notification card is a separate TYPE_APPLICATION_OVERLAY
 * window; the blackout is a cover View inside MainActivity's own content frame. The live
 * framebuffer (uiAutomation.takeScreenshot) includes BOTH, so we sample the BOTTOM
 * quarter of the screen -- where the tab bar, "say wake word" hint, clock and battery
 * live, and where the notification card never reaches -- and count green-on-black pixels:
 *   - screen-off solo notification -> bottom green ~0 (blacked out)
 *   - after key press               -> bottom green back near the on-screen baseline
 *
 * SystemClock.sleep waits hold each state long enough for an external screen recording.
 */
@RunWith(AndroidJUnit4::class)
class NotifSoloBlackoutInstrumentedTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instr.targetContext
    private val device: UiDevice = UiDevice.getInstance(instr)

    private val tag = "NotifSoloTest"
    private val holdMs = 2800L
    private val settleMs = 1000L

    private fun shell(cmd: String): String {
        val pfd = instr.uiAutomation.executeShellCommand(cmd)
        return java.io.FileInputStream(pfd.fileDescriptor).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun artifactDir(): File {
        val dir = File(targetContext.getExternalFilesDir(null), "nsolo-test")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun captureBitmap(step: String): Bitmap {
        val bmp = instr.uiAutomation.takeScreenshot()
        assertNotNull("FAIL [$step]: takeScreenshot returned null", bmp)
        try {
            FileOutputStream(File(artifactDir(), "$step.png")).use {
                bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            Log.i(tag, "step=$step saved ${File(artifactDir(), "$step.png").absolutePath} (${bmp!!.width}x${bmp.height})")
        } catch (e: Exception) {
            Log.w(tag, "step=$step PNG save failed: ${e.message}")
        }
        return bmp!!
    }

    /** Count green-on-black pixels in the BOTTOM [fraction] of the frame (tab bar / clock /
     *  battery zone). The notification card never reaches here, so this region is a clean
     *  proxy for "is the MainActivity UI visible". */
    private fun bottomGreenCount(bmp: Bitmap, fraction: Float = 0.25f): Int {
        val w = bmp.width
        val h = bmp.height
        val yStart = (h * (1f - fraction)).toInt().coerceIn(0, h - 1)
        val row = IntArray(w)
        var count = 0
        for (y in yStart until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]
                if (Color.green(p) > 80 && Color.red(p) < 60 && Color.blue(p) < 60) count++
            }
        }
        return count
    }

    private fun fireTestNotification(text: String) {
        // In-process broadcast (setPackage to self) -- reaches the NOT_EXPORTED receiver.
        targetContext.sendBroadcast(
            Intent("com.repository.glasses.listener.ACTION_NOTIFICATION_TEST").apply {
                setPackage("com.repository.glasses.listener")
                putExtra("sender", "Ana K")
                putExtra("text", text)
                putExtra("repliable", false)
                putExtra("force_worn", true)
            }
        )
    }

    @Test
    fun screenOffNotificationBlacksOutUiThenRevealsOnKey() {
        try {
            shell("appops set com.repository.glasses.listener SYSTEM_ALERT_WINDOW allow")
        } catch (e: Exception) {
            Log.w(tag, "appops grant skipped: ${e.message}")
        }
        // Bring MainActivity to the foreground so there is a UI to black out.
        shell("am start -n com.repository.glasses.listener/.MainActivity")
        SystemClock.sleep(settleMs)
        device.waitForIdle()

        // BEFORE: screen on, full UI -- establish the on-screen bottom-region baseline.
        val baseBmp = captureBitmap("0_before")
        val baseBottom = bottomGreenCount(baseBmp)
        Log.i(tag, "bottomGreen step=0:before -> $baseBottom")
        assertTrue(
            "FAIL [0:before]: expected the MainActivity UI (tab bar/clock) lit before the " +
                "test; bottom green pixels $baseBottom too low -- is the app foregrounded?",
            baseBottom > 100
        )
        SystemClock.sleep(holdMs)

        // Screen OFF, then fire a worn screen-off notification. The card's FLAG_TURN_SCREEN_ON
        // wakes the panel; MainActivity must have blacked out its content by then.
        shell("input keyevent KEYCODE_SLEEP")
        SystemClock.sleep(settleMs)
        fireTestNotification("kickoff pushed to thursday 10am")
        SystemClock.sleep(settleMs + 500)

        // TRIGGER: only the card should be lit. The bottom region must be ~dark.
        val trigBmp = captureBitmap("1_trigger")
        val trigBottom = bottomGreenCount(trigBmp)
        Log.i(tag, "bottomGreen step=1:trigger -> $trigBottom (before=$baseBottom)")
        assertTrue(
            "FAIL [1:trigger]: the UI was NOT blacked out -- bottom green pixels $trigBottom " +
                "still high vs before=$baseBottom. The notification-solo cover did not occlude " +
                "the activity (the original backend-overlay backdrop bug).",
            trigBottom < baseBottom / 4
        )
        SystemClock.sleep(holdMs)

        // REVEAL: a touchpad key (DPAD_CENTER == tap) must restore the full UI.
        shell("input keyevent KEYCODE_DPAD_CENTER")
        SystemClock.sleep(settleMs)
        val revealBmp = captureBitmap("2_reveal")
        val revealBottom = bottomGreenCount(revealBmp)
        Log.i(tag, "bottomGreen step=2:reveal -> $revealBottom (before=$baseBottom, trigger=$trigBottom)")
        assertTrue(
            "FAIL [2:reveal]: key press did not restore the UI -- bottom green pixels " +
                "$revealBottom still near the blacked-out trigger count $trigBottom.",
            revealBottom > baseBottom / 2
        )
        SystemClock.sleep(holdMs)

        Log.i(tag, "screenOffNotificationBlacksOutUiThenRevealsOnKey: passed " +
            "(before=$baseBottom trigger=$trigBottom reveal=$revealBottom)")
    }
}
