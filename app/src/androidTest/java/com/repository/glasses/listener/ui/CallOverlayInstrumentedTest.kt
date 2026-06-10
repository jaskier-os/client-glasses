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
 * Instrumented test that mocks the incoming-call modal ([CallOverlay]) and
 * captures it on the waveguide HUD.
 *
 * In production [CallOverlay] is driven by [com.repository.glasses.listener.bt.CallController]
 * reacting to live HFP BluetoothHeadsetClient events. There is no real phone
 * call here -- the overlay is constructed directly and fed a mocked contact
 * name + number, exactly as CallController.showIncomingUi() would.
 *
 * Verification: FRAMEBUFFER PIXEL COUNTING (same rationale as
 * CopilotCardOverlayInstrumentedTest). The modal renders into a
 * TYPE_APPLICATION_OVERLAY window with FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE,
 * which UiAutomator's accessibility tree CANNOT see, so we capture the real
 * screen via UiAutomation.takeScreenshot() and count green-on-black pixels (the
 * modal is a green-stroked black box with green mono text). A held ~3s window
 * lets an external screenrecord capture the modal.
 */
@RunWith(AndroidJUnit4::class)
class CallOverlayInstrumentedTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instr.targetContext
    private val device: UiDevice = UiDevice.getInstance(instr)

    private lateinit var overlay: CallOverlay

    private val mockName = "Anna Petrova"
    private val mockNumber = "+7 916 555 0142"

    private val tag = "CallOverlayTest"
    private val holdMs = 4000L
    private val settleMs = 800L

    private fun shell(cmd: String): String {
        val pfd = instr.uiAutomation.executeShellCommand(cmd)
        return java.io.FileInputStream(pfd.fileDescriptor).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun screenshotDir(): File {
        val dir = File(targetContext.getExternalFilesDir(null), "call-overlay-test")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun captureBitmap(label: String): Bitmap {
        val bmp = instr.uiAutomation.takeScreenshot()
        assertNotNull("FAIL [$label]: takeScreenshot returned null", bmp)
        val file = File(screenshotDir(), "$label.png")
        try {
            FileOutputStream(file).use { bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Log.i(tag, "step=$label saved ${file.absolutePath} (${bmp!!.width}x${bmp.height})")
        } catch (e: Exception) {
            Log.w(tag, "step=$label PNG save failed: ${e.message}")
        }
        return bmp!!
    }

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
        try {
            shell("appops set com.repository.glasses.listener SYSTEM_ALERT_WINDOW allow")
        } catch (e: Exception) {
            Log.w(tag, "appops grant skipped/failed (may already be granted): ${e.message}")
        }
        device.waitForIdle()

        instr.runOnMainSync {
            overlay = CallOverlay(targetContext)
            overlay.remoteLog = { Log.i(tag, it) }
        }
    }

    @Test
    fun incomingCallModalRenders() {
        SystemClock.sleep(settleMs)
        val baseBmp = captureBitmap("0_baseline")
        val baseGreen = greenPixelCount(baseBmp)
        Log.i(tag, "greenCount step=0:baseline -> $baseGreen")

        val minModalDelta = 300

        // Surface the incoming-call modal exactly as CallController would.
        overlay.showIncoming(mockName, mockNumber)
        SystemClock.sleep(settleMs)
        val g1 = greenPixelCount(captureBitmap("1_incoming"))
        Log.i(tag, "greenCount step=1:incoming -> $g1 (baseline=$baseGreen, delta=${g1 - baseGreen})")
        assertTrue(
            "FAIL [1:incoming]: call modal did not render -- green pixels $g1 not " +
                "meaningfully above baseline $baseGreen (need +$minModalDelta).",
            g1 - baseGreen >= minModalDelta
        )
        // Hold so an external screenrecord captures the modal.
        SystemClock.sleep(holdMs)

        // Hide. Green must return to ~baseline.
        overlay.hide()
        SystemClock.sleep(settleMs)
        val g2 = greenPixelCount(captureBitmap("2_hidden"))
        Log.i(tag, "greenCount step=2:hidden -> $g2 (baseline=$baseGreen, delta=${g2 - baseGreen})")
        assertTrue(
            "FAIL [2:hidden]: modal green did not clear -- green pixels $g2 still well " +
                "above baseline $baseGreen (tolerance $minModalDelta).",
            g2 - baseGreen <= minModalDelta
        )
        SystemClock.sleep(1200)

        Log.i(tag, "incomingCallModalRenders: all pixel assertions passed " +
            "(baseline=$baseGreen incoming=$g1 hidden=$g2)")
    }

    @After
    fun tearDown() {
        if (::overlay.isInitialized) {
            instr.runOnMainSync { overlay.hide() }
            SystemClock.sleep(600)
        }
    }
}
