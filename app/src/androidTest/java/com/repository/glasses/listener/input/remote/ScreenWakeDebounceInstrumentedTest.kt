package com.repository.glasses.listener.input.remote

import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.repository.glasses.listener.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device proof that a burst of remote events against a DARK panel loses at most one event.
 *
 * This is the defect the user reported as "you probably not debouncing the screen-on on
 * interaction properly". `panelIsOff()` reads the real display state, which keeps reporting OFF
 * for tens of milliseconds after the wake is triggered, so before the fix EVERY event arriving in
 * that window was consumed as "the waking event" and a fast bezel spin silently ate several
 * detents. Hardware evidence of the bug: two consecutive
 * "[RemoteInput] SCROLL_STEP consumed to WAKE the panel" lines 45 ms apart in the glasses log.
 *
 * A JVM unit test cannot cover this, because the thing that lags is the physical panel. So this
 * runs on the glasses, drives the REAL [MainActivity] through its [RemoteInputSink] entry point,
 * and counts what actually reached the dispatcher.
 *
 * Run with `adb shell am instrument`, NEVER `connectedAndroidTest` -- its teardown uninstalls the
 * app, and this app is deployed through the priv-app overlay slot.
 */
@RunWith(AndroidJUnit4::class)
class ScreenWakeDebounceInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val mainHandler = android.os.Handler(instrumentation.targetContext.mainLooper)

    private fun drainMainThread(timeoutMs: Long = 5_000) {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        repeat(5) {
            val remaining = deadline - android.os.SystemClock.elapsedRealtime()
            assertTrue("main thread did not drain", remaining > 0)
            val latch = CountDownLatch(1)
            mainHandler.post { latch.countDown() }
            assertTrue("main thread did not drain", latch.await(remaining, TimeUnit.MILLISECONDS))
        }
    }

    private fun panelIsOff(): Boolean =
        (instrumentation.targetContext
            .getSystemService(android.content.Context.DISPLAY_SERVICE)
                as android.hardware.display.DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_OFF

    private fun event(action: RemoteAction, seq: Int) = RemoteInputEvent(
        action = action,
        delta = if (action == RemoteAction.SCROLL_STEP) 1 else 0,
        sourceId = "instrumented",
        sid = 1L,
        seq = seq.toLong(),
        ageMs = 0,
        sinceLastMs = if (seq == 0) -1 else 5,
    )

    /**
     * The load-bearing assertion: with the panel dark, a burst of scroll detents must cost the
     * user exactly ONE detent -- the one that lights the panel -- and no more.
     *
     * Counted from the glasses' own log rather than from a mock, because the point is what the
     * REAL activity did with the REAL display state, and a mock display would test nothing.
     */
    @Test
    fun aBurstOfScrollsAgainstADarkPanelLosesAtMostOneEvent() {
        val activity = currentActivity()
        putPanelToSleep()
        assertTrue("panel did not go dark, so this test proves nothing", panelIsOff())

        val marker = "WAKEDEBOUNCE-${System.currentTimeMillis()}"
        clearLog()

        // Ten detents as fast as the main thread will take them -- the fast bezel spin.
        mainHandler.post {
            repeat(10) { i -> activity.onRemoteInput(event(RemoteAction.SCROLL_STEP, i)) }
        }
        drainMainThread()

        val settled = logLinesContaining("consumed to WAKE the panel")
        val consumedAsWake = settled.count { it.contains("consumed to WAKE the panel") }
        assertEquals(
            "$marker: exactly one event may be spent waking the panel; " +
                "more than one is the input loss the user reported",
            1,
            consumedAsWake,
        )

        // And the remainder must not have been silently swallowed either.
        val swallowed = settled.count { it.contains("panel still waking, not acting blind") }
        assertEquals(
            "$marker: scroll must never be swallowed by the waking latch -- it only moves a " +
                "selection, so it is safe to dispatch while the panel comes up",
            0,
            swallowed,
        )

        // Hold the woken state visible so an external screen recording captures the result.
        Thread.sleep(2_500)
    }

    /**
     * SELECT and BACK are the opposite trade: they COMMIT to something the user cannot yet see,
     * so during the wake window they must still be consumed rather than dispatched blind.
     *
     * Without this the debounce fix would have reintroduced the exact hazard the wake-consume
     * exists to prevent -- on this UI several selections sit one keypress from a microphone.
     */
    @Test
    fun selectDuringTheWakeWindowIsNotDispatchedBlind() {
        val activity = currentActivity()
        putPanelToSleep()
        assertTrue("panel did not go dark, so this test proves nothing", panelIsOff())

        clearLog()
        mainHandler.post {
            // First event wakes; the SELECT immediately behind it must not act.
            activity.onRemoteInput(event(RemoteAction.SCROLL_STEP, 0))
            activity.onRemoteInput(event(RemoteAction.SELECT, 1))
        }
        drainMainThread()

        val lines = logLinesContaining("SELECT consumed: panel still waking")
        assertEquals(
            "exactly one event may be spent waking the panel",
            1,
            lines.count { it.contains("consumed to WAKE the panel") },
        )
        assertTrue(
            "SELECT must be consumed while the panel is still coming up, never dispatched blind",
            lines.any { it.contains("SELECT consumed: panel still waking") },
        )
        Thread.sleep(2_500)
    }

    // ---- helpers ----

    /**
     * The live [MainActivity] instance.
     *
     * Searched across every lifecycle stage, not just RESUMED: this test deliberately puts the
     * panel to sleep, which PAUSES the activity, and the activity is still the registered
     * [RemoteInputSink] in that state -- that is precisely the state under test. Requiring RESUMED
     * would make the test unrunnable against the very condition it exists to check.
     */
    private fun currentActivity(): MainActivity {
        wakePanel()
        // Bring the activity back to the foreground. The previous test left the panel asleep,
        // which can stop or destroy it; without this the next test races that teardown.
        instrumentation.uiAutomation.executeShellCommand(
            "am start -n com.repository.glasses.listener/.MainActivity"
        ).close()
        Thread.sleep(1_500)
        var found: MainActivity? = null
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (found == null && android.os.SystemClock.elapsedRealtime() < deadline) {
            val latch = CountDownLatch(1)
            mainHandler.post {
                val monitor = androidx.test.runner.lifecycle
                    .ActivityLifecycleMonitorRegistry.getInstance()
                // Stages searched most-alive first. Putting the panel to sleep in one test
                // can destroy and recreate the activity before the next, and the lifecycle
                // monitor still holds the DESTROYED instance -- driving that corpse logs
                // nothing, so the assertions below would fail against correct behaviour.
                // Always take the liveliest instance available.
                found = listOf(
                    androidx.test.runner.lifecycle.Stage.RESUMED,
                    androidx.test.runner.lifecycle.Stage.STARTED,
                    androidx.test.runner.lifecycle.Stage.PAUSED,
                    androidx.test.runner.lifecycle.Stage.STOPPED,
                    androidx.test.runner.lifecycle.Stage.CREATED,
                ).asSequence()
                    .flatMap { monitor.getActivitiesInStage(it).asSequence() }
                    .filterIsInstance<MainActivity>()
                    .firstOrNull { !it.isDestroyed }
                latch.countDown()
            }
            assertTrue("could not reach the main thread", latch.await(5, TimeUnit.SECONDS))
            if (found == null) Thread.sleep(250)
        }
        return requireNotNull(found) {
            "MainActivity was not found in any lifecycle stage. This test drives the real " +
                "activity through its RemoteInputSink entry point, so the app must be running."
        }
    }

    /** Light the panel and wait for the display to actually report it, so a test can start clean. */
    private fun wakePanel() {
        if (!panelIsOff()) return
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (android.os.SystemClock.elapsedRealtime() < deadline && panelIsOff()) {
            Thread.sleep(100)
        }
    }

    /**
     * Put the panel out via the same route the product uses, then wait for the display state to
     * actually report OFF -- the lag is the whole subject of this test, so polling is required
     * rather than a fixed sleep.
     */
    private fun putPanelToSleep() {
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (android.os.SystemClock.elapsedRealtime() < deadline && !panelIsOff()) {
            Thread.sleep(100)
        }
    }

    private fun clearLog() {
        instrumentation.uiAutomation.executeShellCommand("logcat -c").close()
        Thread.sleep(300)
    }

    private fun logLines(): List<String> {
        val out = instrumentation.uiAutomation.executeShellCommand("logcat -d -s MainActivityUI:I")
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(out)
            .bufferedReader().readText().lines()
    }

    /**
     * Read the log back once it has settled, rather than the instant the main thread drains.
     *
     * `logcat` is written asynchronously, so a single read taken immediately after
     * [drainMainThread] can miss a line the activity has already emitted -- observed here as the
     * WAKE line being visible while the SELECT line logged 8 ms later was not yet. That is a
     * property of the log transport, not of the code under test, and asserting through it without
     * waiting produces a flaky test that fails on correct behaviour.
     *
     * Waits until [expected] is present, or until the deadline -- at which point the assertion
     * that follows reports the genuine absence.
     */
    private fun logLinesContaining(expected: String, timeoutMs: Long = 5_000): List<String> {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        var lines = logLines()
        while (android.os.SystemClock.elapsedRealtime() < deadline &&
            lines.none { it.contains(expected) }
        ) {
            Thread.sleep(200)
            lines = logLines()
        }
        return lines
    }
}
