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

    /**
     * The load-bearing test for the SECOND defect: the panel dying MID-INTERACTION.
     *
     * Remote events arrive as `onRemoteInput` callbacks, not as real `InputEvent`s through
     * `InputDispatcher`, so `PowerManagerService` never counts them as user activity and never
     * restarts the display idle timer. `PowerManager.userActivity()` would, but it needs
     * `DEVICE_POWER`, which this app does not hold. The result was a screen that went dark on the
     * fixed timeout while the user was still actively scrolling the watch bezel, and whose next
     * event was then spent re-waking it -- what the user described as screen-on "not being
     * debounced". It was not bouncing; it was expiring on a timer remote input could not reset.
     *
     * ## Why this test can actually fail
     *
     * The obvious test -- send one event, wait, watch the screen go off -- is WORTHLESS here: with
     * the fix, the hold equals the system timeout, so fixed and broken produce an identical single
     * sleep at the same instant. The only observation that separates them is SUSTAINED interaction
     * across the timeout boundary. Events are therefore injected every [POKE_INTERVAL_MS], which is
     * far shorter than the timeout, for longer than the timeout, and the panel is asserted still lit
     * at a moment strictly BEYOND when an unreset timer would have killed it.
     *
     * Against the broken code this fails at [assertPanelLit] shortly after the timeout elapses.
     * Against a naive "pin the screen on forever" non-fix it fails at the release assertion below,
     * which is why that half is not optional: a hold that never expires is a battery defect on a
     * head-worn device, not a fix.
     */
    @Test
    fun sustainedRemoteInputKeepsThePanelLitPastTheScreenTimeout() {
        val activity = currentActivity()
        val timeoutMs = systemScreenOffTimeoutMs()
        // The whole method is meaningless if events are not being sent well inside the timeout.
        assertTrue(
            "poke interval ($POKE_INTERVAL_MS ms) must be far shorter than the screen timeout " +
                "($timeoutMs ms), or this test cannot distinguish a working hold from a broken one",
            POKE_INTERVAL_MS * 2 < timeoutMs,
        )

        wakePanel()
        assertTrue("panel did not light, so this test proves nothing", !panelIsOff())

        // Interact continuously for well past the timeout. An unreset idle timer kills the panel
        // `timeoutMs` after the FIRST event regardless of everything that follows.
        val started = android.os.SystemClock.elapsedRealtime()
        val runForMs = timeoutMs * 2
        var seq = 0
        while (android.os.SystemClock.elapsedRealtime() - started < runForMs) {
            val n = seq++
            mainHandler.post { activity.onRemoteInput(event(RemoteAction.SCROLL_STEP, n)) }
            drainMainThread()
            // Check DURING the run, not only at the end: a panel that died at the timeout and was
            // re-woken by a later poke would be lit again by the time a final-only check ran, and
            // the test would pass against the exact bug it exists to catch.
            if (android.os.SystemClock.elapsedRealtime() - started > timeoutMs + SETTLE_MS) {
                assertPanelLit(
                    "panel went dark while remote input was still arriving every " +
                        "$POKE_INTERVAL_MS ms -- the idle timer was not reset by remote events"
                )
            }
            Thread.sleep(POKE_INTERVAL_MS)
        }
        assertTrue("at least three pokes must have been sent", seq >= 3)

        // The other half: the hold must EXPIRE. Stop poking and require the panel to go dark on the
        // ordinary timeout. Without this, pinning the screen on permanently would pass the test
        // above while draining a head-worn battery.
        val quietStart = android.os.SystemClock.elapsedRealtime()
        val offDeadline = quietStart + timeoutMs + RELEASE_SLACK_MS
        while (android.os.SystemClock.elapsedRealtime() < offDeadline && !panelIsOff()) {
            Thread.sleep(250)
        }
        val offAfterMs = android.os.SystemClock.elapsedRealtime() - quietStart
        assertTrue(
            "panel never went dark after remote input stopped (waited ${offAfterMs} ms for a " +
                "${timeoutMs} ms timeout): the hold is not expiring and will pin the waveguide on",
            panelIsOff(),
        )
        // And it must not have gone dark EARLY either -- that would be the original mid-interaction
        // death simply moved a few seconds later.
        assertTrue(
            "panel went dark after only ${offAfterMs} ms, sooner than the ${timeoutMs} ms system " +
                "timeout it should have inherited",
            offAfterMs >= timeoutMs - SETTLE_MS,
        )

        wakePanel()
        Thread.sleep(2_500)
    }

    // ---- helpers ----

    /** How often a poke is injected. Must be well inside the system timeout. */
    private val POKE_INTERVAL_MS = 2_000L

    /** Tolerance for display-state reporting lag and handler scheduling. */
    private val SETTLE_MS = 2_000L

    /** Extra time allowed for the panel to go dark once interaction stops. */
    private val RELEASE_SLACK_MS = 10_000L

    private fun assertPanelLit(message: String) = assertTrue(message, !panelIsOff())

    /**
     * The system timeout the hold is supposed to mirror.
     *
     * Read rather than assumed, and never written: `glasses-power-daemon` owns this setting and
     * rewrites it from its config, so a test that hardcoded 15000 would silently stop testing
     * anything the day that config changed.
     */
    private fun systemScreenOffTimeoutMs(): Long =
        android.provider.Settings.System.getInt(
            instrumentation.targetContext.contentResolver,
            android.provider.Settings.System.SCREEN_OFF_TIMEOUT,
        ).toLong()

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
