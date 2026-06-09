package com.repository.glasses.listener.ui

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the redesigned tab UI so an external screen recording can capture the
 * new moving circular highlight that flattens with speed -- on BOTH the bottom
 * main tab bar and the TODO sub-tab row.
 *
 * This is a motion demo, not a pixel assertion: it injects the same DPAD
 * keycodes the touchpad daemon emits (LEFT/RIGHT = switch, CENTER = enter) into
 * the focused MainActivity window and lets the live UI animate. We alternate
 * slow single steps (blob relaxes to a circle) with fast multi-step bursts (blob
 * stretches into a horizontal streak) so the recording shows both extremes.
 */
@RunWith(AndroidJUnit4::class)
class TabHighlightRedesignInstrumentedTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instr)
    private val tag = "TabHighlightDemo"

    private fun key(code: Int) {
        device.pressKeyCode(code)
    }

    private fun right() = key(KeyEvent.KEYCODE_DPAD_RIGHT)
    private fun left() = key(KeyEvent.KEYCODE_DPAD_LEFT)
    private fun center() = key(KeyEvent.KEYCODE_DPAD_CENTER)

    @Test
    fun driveTabHighlightMotion() {
        instr.uiAutomation.executeShellCommand(
            "am start -n com.repository.glasses.listener/.MainActivity"
        )
        SystemClock.sleep(2500)
        device.waitForIdle()

        Log.i(tag, "=== bottom tab bar: slow single steps (relaxed circle) ===")
        // Walk to the leftmost tab first so we have a known start.
        repeat(6) { left(); SystemClock.sleep(650) }
        SystemClock.sleep(1000)

        // Slow rightward steps: each settles back to a round blob.
        repeat(5) { right(); SystemClock.sleep(750) }
        SystemClock.sleep(1200)

        Log.i(tag, "=== bottom tab bar: fast bursts (flattened streak) ===")
        // Fast leftward burst -- high speed flattens the blob hard.
        repeat(5) { left(); SystemClock.sleep(110) }
        SystemClock.sleep(1400)
        // Fast rightward burst back.
        repeat(5) { right(); SystemClock.sleep(110) }
        SystemClock.sleep(1400)

        Log.i(tag, "=== enter TODO tab + drive its sub-tab row ===")
        // TODO is the leftmost default tab; go all the way left, then enter it.
        repeat(6) { left(); SystemClock.sleep(180) }
        SystemClock.sleep(700)
        center() // TAB_NAV -> TODO_FOCUSED (sub-tab switcher, level 0)
        SystemClock.sleep(1500)

        // Slow sub-tab steps: relaxed circle on the sub-tab row.
        repeat(3) { right(); SystemClock.sleep(800) }
        SystemClock.sleep(1000)
        repeat(3) { left(); SystemClock.sleep(800) }
        SystemClock.sleep(1000)

        // Fast sub-tab burst: flattened streak on the sub-tab row.
        repeat(3) { right(); SystemClock.sleep(120) }
        SystemClock.sleep(1200)
        repeat(3) { left(); SystemClock.sleep(120) }
        SystemClock.sleep(1500)

        // Double-tap center to drop back to the main tab bar, settle.
        center(); SystemClock.sleep(120); center()
        SystemClock.sleep(1500)

        Log.i(tag, "driveTabHighlightMotion: done")
    }
}
