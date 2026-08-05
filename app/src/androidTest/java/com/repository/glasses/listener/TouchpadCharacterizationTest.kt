package com.repository.glasses.listener

import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Characterization harness for the touchpad key handler (plan task A4a).
 *
 * ## What this is for
 *
 * The user drives the physical touchpad every day. `handleTouchpadKey` decides what a swipe, tap or
 * hold does in each of 24 focus states, and a silent change there is the worst regression this app
 * can ship -- it would not crash, it would just quietly stop doing what the user's fingers expect.
 *
 * The extraction that created this seam was proven byte-identical by diff, so this harness is not
 * evidence for THAT change. It exists so the next change has something to fail against: it pins the
 * CURRENT decision of every (keycode x focus state x fold) combination into a golden file, and any
 * future edit that alters one of them has to say so explicitly by updating the golden.
 *
 * ## Why on-device and reflective
 *
 * `MainActivity` is a large Activity with `lateinit` views and native dependencies; it cannot be
 * constructed headlessly, which is what made a pre-extraction harness impossible. Reflection drives
 * the real private method on a real instance, so the harness tests the shipped code path rather than
 * a testable copy of it -- a copy would be free to drift from the original, which defeats the point.
 *
 * ## What is recorded
 *
 * The returned `Boolean?` (true = consumed, null = fell through to the focus dispatch) plus every
 * piece of state the handler is known to mutate. That tuple is the handler's entire observable
 * contract from `onKeyDown`'s perspective.
 */
@RunWith(AndroidJUnit4::class)
class TouchpadCharacterizationTest {

    /** The four keycodes the touchpad daemon emits, plus one that must FALL THROUGH untouched. */
    private val keyCodes = listOf(
        KeyEvent.KEYCODE_NUMPAD_0 to "NUMPAD_0",
        KeyEvent.KEYCODE_NUMPAD_1 to "NUMPAD_1",
        KeyEvent.KEYCODE_NUMPAD_2 to "NUMPAD_2",
        KeyEvent.KEYCODE_NUMPAD_3 to "NUMPAD_3",
        // Not a touchpad code. It must reach the fall-through that resets the double-tap chain,
        // which is the exact behaviour a naive "gate on NUMPAD_*" extraction would have broken.
        KeyEvent.KEYCODE_DPAD_CENTER to "DPAD_CENTER",
    )

    /**
     * Reply-machine preconditions, so the branches guarded by them are actually entered.
     *
     * With everything false/null -- the obvious baseline -- the NUMPAD_3 arm branch, the
     * in-flight-reply guard and the send-window cancel are all unreachable, and the golden pins
     * only the paths around them. Deleting any of those three branches would then leave the trace
     * byte-identical. Each row below turns one of them on.
     */
    private data class ReplyState(
        val label: String,
        val repliable: Boolean = false,
        val pendingNotif: String? = null,
        val arming: Boolean = false,
        val activeReply: String? = null,
        val sendPending: Boolean = false,
    )

    private val replyStates = listOf(
        ReplyState("idle"),
        // Arms a voice reply that records the mic and sends to a real contact -- the branch with
        // the most dangerous consequence in the whole handler.
        ReplyState("repliable", repliable = true, pendingNotif = "notif-1"),
        // A hold arriving while a reply is already arming must not re-arm or reach AI chat.
        ReplyState("arming", repliable = true, pendingNotif = "notif-1", arming = true),
        // The post-transcript window where a double tap cancels the pending send.
        ReplyState("sendPending", activeReply = "notif-1", sendPending = true),
    )

    private val focusStates = MainActivity.FocusState.values()

    private fun field(name: String) =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Test
    fun touchpadDecisionsMatchTheGolden() {
        val handler = MainActivity::class.java.getDeclaredMethod(
            "handleTouchpadKey",
            Int::class.java,
            KeyEvent::class.java,
            com.repository.glasses.listener.input.remote.InputOrigin::class.java,
        ).apply { isAccessible = true }

        val fFocus = field("focusState")
        val fFolded = field("foldedState")
        val fLastNumpad2 = field("lastNumpad2Ms")
        val fReplyArming = field("replyArming")
        val fRepliable = field("notificationRepliable")
        val fPendingNotif = field("pendingNotifId")
        val fActiveReply = field("activeReplyNotifId")
        val fSendPending = field("replySendPending")

        val trace = StringBuilder()

        // Deliberately NOT `.use { }`. MainActivity is a singleTask HOME activity that the system
        // keeps resident, so ActivityScenario.close() waits for a DESTROYED it never reaches and
        // throws -- discarding the trace collected just above it.
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        run {
            for (folded in listOf(false, true)) {
                for (focus in focusStates) {
                    for (reply in replyStates) {
                        for ((code, codeName) in keyCodes) {
                            scenario.onActivity { activity ->
                                // Reset to a known baseline before EVERY case, so each line
                                // describes one decision rather than the accumulation of the
                                // ones before it.
                                fFocus.set(activity, focus)
                                fFolded.set(activity, folded)
                                // Pre-ARM the double-tap chain, do not zero it.
                                //
                                // Starting from 0 makes "did this key CLEAR the chain?"
                                // unobservable: the field reads false before and after, so a
                                // handler that stopped clearing it produces an identical trace.
                                // That is not hypothetical -- it was verified by mutating the
                                // handler to gate on NUMPAD_* only, which is the exact bad
                                // extraction this harness exists to catch, and the zeroed version
                                // passed. Arming first makes the clear visible.
                                // Arm with a stamp far enough in the past that it can never collide
                                // with the `uptimeMillis()` the handler writes. Using "now" made
                                // `kept` vs `rearmed` depend on whether the millisecond ticked
                                // between setup and the write -- a coin flip that produced a flaky
                                // golden, which is worse than a weak one.
                                //
                                // It must also sit OUTSIDE the double-tap window, so a NUMPAD_2 is
                                // never read as the second tap of a pair; that path calls
                                // turnScreenOff() and would blank the display mid-run.
                                val armedAt = android.os.SystemClock.uptimeMillis() - 10_000L
                                fLastNumpad2.set(activity, armedAt)
                                fReplyArming.set(activity, reply.arming)
                                fRepliable.set(activity, reply.repliable)
                                fPendingNotif.set(activity, reply.pendingNotif)
                                fActiveReply.set(activity, reply.activeReply)
                                fSendPending.set(activity, reply.sendPending)

                                val result = handler.invoke(
                                    activity,
                                    code,
                                    KeyEvent(KeyEvent.ACTION_DOWN, code),
                                    com.repository.glasses.listener.input.remote.InputOrigin.TOUCHPAD,
                                ) as Boolean?

                                // Three-valued, not a boolean. "Armed" alone cannot tell a chain
                                // that was left alone from one that was cleared and re-armed with
                                // a fresh stamp, so a handler that stopped re-arming would look
                                // identical.
                                val after = fLastNumpad2.get(activity) as Long
                                val chain = when {
                                    after == 0L -> "cleared"
                                    after == armedAt -> "kept"
                                    else -> "rearmed"
                                }
                                trace.append(
                                    "folded=$folded focus=$focus reply=${reply.label} " +
                                        "key=$codeName -> ret=$result " +
                                        "focusAfter=${fFocus.get(activity)} chain=$chain " +
                                        "arming=${fReplyArming.get(activity)}\n"
                                )
                            }
                        }
                    }
                }
            }
        }

        val actual = trace.toString().trim()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // Written every run so a mismatch can be diffed off-device instead of squinting at a stack
        // trace with 240 lines in it.
        File(ctx.getExternalFilesDir(null), "touchpad-characterization-actual.txt")
            .writeText(actual)

        val golden = javaClass.classLoader
            ?.getResourceAsStream("touchpad-characterization-golden.txt")
            ?.bufferedReader()?.readText()?.trim()

        if (golden == null) {
            throw AssertionError(
                "No golden checked in yet. Pull the generated file from " +
                    "${ctx.getExternalFilesDir(null)}/touchpad-characterization-actual.txt, " +
                    "review EVERY line as intended behaviour, then check it in as " +
                    "app/src/androidTest/resources/touchpad-characterization-golden.txt"
            )
        }

        val goldenLines = golden.lines()
        val actualLines = actual.lines()
        val diffs = goldenLines.zip(actualLines).withIndex()
            .filter { (_, pair) -> pair.first != pair.second }
            .map { (i, pair) -> "line ${i + 1}:\n  golden: ${pair.first}\n  actual: ${pair.second}" }

        assertEquals(
            "touchpad behaviour changed:\n${diffs.take(15).joinToString("\n")}",
            0,
            diffs.size,
        )
        assertEquals("case count changed", goldenLines.size, actualLines.size)
    }
}
