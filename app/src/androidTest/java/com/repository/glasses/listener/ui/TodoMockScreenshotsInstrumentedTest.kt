package com.repository.glasses.listener.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Captures a screenshot of each of the four restyled TODO sub-tabs
 * (tasks / saved / jobs / alarms) populated with MOCK content.
 *
 * The mock data is injected purely in-memory: it is broadcast to the same
 * dynamically-registered receivers MainActivity uses for real data
 * (ACTION_TODO_LIST_LOADED etc.), so it flows through the real parse + adapter
 * path and renders exactly as production would -- but NOTHING is written to any
 * database or to the ListenerService backend. Each broadcast is sent in-process
 * (setPackage to self) so it reaches the receivers regardless of export flags.
 *
 * Navigation uses injected DPAD keycodes (the same the touchpad daemon emits):
 * enter the TODO tab, then step the sub-tab row right. After each switch we
 * re-inject the mock (the switch fires requestTodoData() which the backend would
 * answer with empty data, so we override it after a settle delay), drop into
 * content focus so the selection border shows, hold, and screenshot.
 */
@RunWith(AndroidJUnit4::class)
class TodoMockScreenshotsInstrumentedTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val device: UiDevice = UiDevice.getInstance(instr)
    private val tag = "TodoMockShots"
    private val pkg = "com.repository.glasses.listener"

    // Mirror of ListenerService action/extra constants (kept local to avoid a
    // visibility dependency; values must match ListenerService).
    private val ACTION_TODO = "$pkg.TODO_LIST_LOADED";          private val EXTRA_TODO = "todo_json"
    private val ACTION_TG   = "$pkg.TELEGRAM_SAVED_LOADED";     private val EXTRA_TG = "telegram_json"
    private val ACTION_JOB  = "$pkg.JOB_LIST_LOADED";           private val EXTRA_JOB = "job_json"
    private val ACTION_ALARM = "$pkg.ALARM_LIST_LOADED";        private val EXTRA_ALARM = "alarm_json"

    private fun artifactDir(): File =
        File(ctx.getExternalFilesDir(null), "todo-mocks").apply { if (!exists()) mkdirs() }

    private fun shoot(step: String) {
        val bmp: Bitmap? = instr.uiAutomation.takeScreenshot()
        assertNotNull("takeScreenshot null at $step", bmp)
        FileOutputStream(File(artifactDir(), "$step.png")).use {
            bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        Log.i(tag, "saved $step (${bmp!!.width}x${bmp.height})")
    }

    private fun inject(action: String, extra: String, json: String) {
        ctx.sendBroadcast(Intent(action).apply { setPackage(pkg); putExtra(extra, json) })
    }

    private fun key(c: Int) { device.pressKeyCode(c); SystemClock.sleep(120) }

    // ---- mock payloads (match the design mock content) --------------------
    private val tasksJson = """
      [
        {"id":"1","text":"finish glasses HUD review","completed":false,"createdAt":1},
        {"id":"2","text":"reply to ana re: contract","completed":false,"createdAt":2},
        {"id":"3","text":"reorder beans @ blue bottle","completed":false,"createdAt":3},
        {"id":"4","text":"pay parking ticket","completed":true,"createdAt":4},
        {"id":"5","text":"plan napa trip","completed":false,"createdAt":5},
        {"id":"6","text":"gym 2x this week","completed":false,"createdAt":6},
        {"id":"7","text":"read CRDT paper","completed":false,"createdAt":7}
      ]
    """.trimIndent()

    private val savedJson = """
      [
        {"id":1,"sender":"self","text":"don't forget to bring the picture frames when you visit","date":"mar 19"},
        {"id":2,"sender":"self","text":"the talk that changed how i think about distributed systems - youtu.be/...","date":"mar 18"},
        {"id":3,"sender":"self","text":"idea: HUD as a passive read-only surface, all edits flow back to phone","date":"mar 17"},
        {"id":4,"sender":"self","text":"the pricing page deck for tomorrow - final v3.pdf","date":"mar 17"},
        {"id":5,"sender":"self","text":"reorder beans @ blue bottle","date":"mar 16"}
      ]
    """.trimIndent()

    private val jobsJson = """
      [
        {"id":"1","name":"call dad","prompt":"","scheduledAt":1,"status":"pending"},
        {"id":"2","name":"leave for AMS flight","prompt":"","scheduledAt":2,"status":"pending"},
        {"id":"3","name":"standup prep","prompt":"","scheduledAt":3,"status":"pending"},
        {"id":"4","name":"dentist - marina","prompt":"","scheduledAt":4,"status":"pending"},
        {"id":"5","name":"review weekly plan","prompt":"","scheduledAt":5,"status":"running"},
        {"id":"6","name":"sync notes to notion","prompt":"","scheduledAt":6,"status":"completed"}
      ]
    """.trimIndent()

    private val alarmsJson = """
      [
        {"id":1,"hour":6,"minute":30,"title":"weekday wake","enabled":true,"triggerTimeMillis":1},
        {"id":2,"hour":7,"minute":15,"title":"standup nudge","enabled":true,"triggerTimeMillis":2},
        {"id":3,"hour":19,"minute":0,"title":"call dad","enabled":true,"triggerTimeMillis":3},
        {"id":4,"hour":21,"minute":30,"title":"wind down","enabled":false,"triggerTimeMillis":4}
      ]
    """.trimIndent()

    /**
     * Mark-done animation demo: enter the tasks list, select item 1, press center
     * to mark it done, then HOLD ~9s so the recording captures: strike-through
     * appears -> stays 3s -> fades out over 5s. A second item is marked partway to
     * show two overlapping fades.
     */
    @Test
    fun driveTaskDoneFade() {
        instr.uiAutomation.executeShellCommand("am start -n $pkg/.MainActivity")
        SystemClock.sleep(2500)
        device.waitForIdle()

        repeat(8) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
        SystemClock.sleep(500)
        key(KeyEvent.KEYCODE_DPAD_CENTER) // -> TODO_FOCUSED, TASKS sub-tab nav
        SystemClock.sleep(700)
        inject(ACTION_TODO, EXTRA_TODO, tasksJson)
        SystemClock.sleep(800)
        // Enter content (selects row 0).
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(500)
        inject(ACTION_TODO, EXTRA_TODO, tasksJson)
        SystemClock.sleep(800)

        // Stay on item 0 ("finish glasses HUD review") and mark it done to exercise
        // the move-from-position-0 case. Do NOT re-inject after marking -- the
        // optimistic flow owns the list and the removedRecently guard absorbs any
        // lagging backend echo, so re-injecting would just fight the animation.
        Log.i(tag, "marking item 0 done at t=0")
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_CENTER) // toggle done -> strike -> move-to-end -> hold -> fade -> remove
        // strike(0.5) + move-settle(0.32) + end-hold(3) + fade(5) ~= 9s; add margin.
        SystemClock.sleep(10000)
        Log.i(tag, "driveTaskDoneFade: done")
    }

    @Test
    fun captureAllTodoSubtabs() {
        instr.uiAutomation.executeShellCommand("am start -n $pkg/.MainActivity")
        SystemClock.sleep(2500)
        device.waitForIdle()

        // Reach the bottom tab bar, walk fully left to the TODO tab, enter it.
        repeat(8) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
        SystemClock.sleep(600)
        key(KeyEvent.KEYCODE_DPAD_CENTER) // TAB_NAV -> TODO_FOCUSED (sub-tab nav, TASKS)
        SystemClock.sleep(800)

        // Sub-tab order: TASKS(0) SAVED(1) JOBS(2) ALARMS(3).
        // For each: settle, inject mock (overrides the backend's empty answer),
        // enter content focus so the selection border renders, hold, shoot.
        captureSubtab("01_tasks", 0) { inject(ACTION_TODO, EXTRA_TODO, tasksJson) }
        captureSubtab("02_saved", 1) { inject(ACTION_TG, EXTRA_TG, savedJson) }
        captureSubtab("03_jobs", 2) { inject(ACTION_JOB, EXTRA_JOB, jobsJson) }
        captureSubtab("04_alarms", 3) { inject(ACTION_ALARM, EXTRA_ALARM, alarmsJson) }

        Log.i(tag, "captureAllTodoSubtabs: done -> ${artifactDir().absolutePath}")
    }

    /**
     * Motion demo for the vertical jumping cursor on the tasks list: enter the
     * TODO tab, inject mock tasks, drop into content focus, then step the
     * selection down through every item and back up so the bright cursor circle
     * jumps row to row (and the list scrolls under it). Held slow enough for an
     * external screen recording.
     */
    @Test
    fun driveTasksCursor() {
        instr.uiAutomation.executeShellCommand("am start -n $pkg/.MainActivity")
        SystemClock.sleep(2500)
        device.waitForIdle()

        repeat(8) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
        SystemClock.sleep(500)
        key(KeyEvent.KEYCODE_DPAD_CENTER) // -> TODO_FOCUSED, TASKS sub-tab nav
        SystemClock.sleep(700)
        inject(ACTION_TODO, EXTRA_TODO, tasksJson)
        SystemClock.sleep(800)
        // Enter content (selects row 0, cursor appears on first dot).
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(400)
        inject(ACTION_TODO, EXTRA_TODO, tasksJson)
        SystemClock.sleep(1200)

        // Step DOWN through all 7 items: cursor jumps + shrinks per row.
        repeat(6) {
            device.pressKeyCode(KeyEvent.KEYCODE_DPAD_DOWN)
            SystemClock.sleep(750)
        }
        SystemClock.sleep(800)
        // Step back UP to the top.
        repeat(6) {
            device.pressKeyCode(KeyEvent.KEYCODE_DPAD_UP)
            SystemClock.sleep(750)
        }
        SystemClock.sleep(1000)
        Log.i(tag, "driveTasksCursor: done")
    }

    private fun captureSubtab(label: String, targetIdx: Int, injectMock: () -> Unit) {
        // We start each capture from sub-tab nav (level 0). targetIdx==0 means
        // we are already there; otherwise the caller advanced via prior steps.
        // Wait for the auto requestTodoData() empty answer to land first...
        SystemClock.sleep(700)
        // ...then override it with the mock and let it render.
        injectMock()
        SystemClock.sleep(700)
        // Drop into content focus so the selected-row border appears (matches
        // the design's focusLevel=1). CENTER enters content + selects pos 0.
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(400)
        // Re-inject in case entering focus retriggered a data request.
        injectMock()
        SystemClock.sleep(900)
        shoot(label)
        // Back out to sub-tab nav, then advance to the next sub-tab.
        device.pressKeyCode(KeyEvent.KEYCODE_BACK)
        SystemClock.sleep(400)
        if (targetIdx < 3) {
            device.pressKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)
            SystemClock.sleep(500)
        }
    }
}
