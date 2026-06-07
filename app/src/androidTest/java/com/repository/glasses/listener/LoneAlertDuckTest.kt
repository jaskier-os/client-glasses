package com.repository.glasses.listener

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that the Lone-mode alert SFX, which renders locally on the glasses,
 * also performs the glasses-side A2DP duck for the duration of the clip.
 *
 * The duck lives in the :backend process (ListenerService.updateDuckState), which
 * lowers the incoming A2DP music via the vendor HAL parameter "btsink_volume".
 * Two independent, machine-observable signals are asserted from logcat:
 *
 *   1. Backend btLog:  "A2DP duck ON: btsink_volume=..."  (then "A2DP duck OFF")
 *      -- tag GlassesListenerSvc, proves updateDuckState decided to duck because
 *         loneAlertPlaying became true.
 *   2. Audio HAL:      "AHAL: BTSink: btsink_set_volume ROKID_AUDIO volume=N"
 *      -- proves the vendor HAL physically applied a lowered volume.
 *
 * The SFX is fired deterministically via the existing debug broadcast
 * ACTION_DEBUG_LONE_ALERT (RECEIVER_NOT_EXPORTED, same precedent as the
 * ACTION_DEBUG_CALL_* hooks). sendBroadcast runs as the app UID so it reaches the
 * not-exported receiver; UiAutomation.executeShellCommand runs as shell so it can
 * read logcat.
 *
 * Preconditions (bench): glasses worn+unfolded (is_take_on=1) so the wornOk gate
 * in updateDuckState is satisfied, and an A2DP source connected + streaming.
 */
@RunWith(AndroidJUnit4::class)
class LoneAlertDuckTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val ui = instr.uiAutomation

    private fun shell(cmd: String): String {
        val pfd = ui.executeShellCommand(cmd)
        return java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    @Test
    fun loneAlertSfxDucksA2dp() {
        // The duck is gated on wornOk (updateDuckState skips ducking when the
        // glasses are OFF_HEAD). On a USB bench they are usually off-head, so
        // inject ON_HEAD via the existing DEBUG_WEAR hook to satisfy the gate.
        // This drives the same WearState the real psensor would; it does not
        // bypass the gate, it sets it truthfully for the test.
        ctx.sendBroadcast(
            Intent("com.repository.glasses.listener.DEBUG_WEAR")
                .setPackage("com.repository.glasses.listener")
                .putExtra("wearing", true)
        )
        Thread.sleep(800)

        // Clear logcat so we only observe lines produced by THIS test run.
        shell("logcat -c")
        Thread.sleep(300)

        // Fire the SFX. The receiver lives in :backend and calls playLoneAlert(),
        // which flips loneAlertPlaying=true and calls updateDuckState() (duck ON),
        // then on MediaPlayer completion flips it back (duck OFF).
        ctx.sendBroadcast(
            Intent("com.repository.glasses.listener.DEBUG_LONE_ALERT")
                .setPackage("com.repository.glasses.listener")
        )

        // Poll logcat up to ~8s for the duck-ON line. The lone_alert clip is short,
        // so duck OFF follows within a couple of seconds.
        val deadline = System.currentTimeMillis() + 8_000
        var sawSfx = false
        var sawDuckOn = false
        var sawDuckOff = false
        var sawHalLower = false
        var dump = ""
        while (System.currentTimeMillis() < deadline && !(sawDuckOn && sawDuckOff)) {
            Thread.sleep(500)
            dump = shell(
                "logcat -d -s GlassesListenerSvc:I AHAL:D"
            )
            sawSfx = sawSfx || dump.contains("DebugLoneAlert: firing lone alert SFX") ||
                dump.contains("LoneMode: alert", ignoreCase = true)
            sawDuckOn = sawDuckOn || dump.contains("A2DP duck ON: btsink_volume=")
            sawDuckOff = sawDuckOff || dump.contains("A2DP duck OFF: btsink_volume=")
            sawHalLower = sawHalLower || dump.contains("btsink_set_volume")
        }

        val sb = StringBuilder()
        sb.append("sfxFired=$sawSfx duckOn=$sawDuckOn duckOff=$sawDuckOff halApplied=$sawHalLower\n")
        sb.append("--- relevant logcat ---\n")
        for (line in dump.split("\n")) {
            if (line.contains("A2DP duck") || line.contains("LoneAlert") ||
                line.contains("LoneMode") || line.contains("btsink_set_volume")
            ) {
                sb.append(line).append('\n')
            }
        }
        val summary = sb.toString()

        // Restore off-head so the bench device doesn't keep a stale ON_HEAD state
        // (best-effort; the real psensor reasserts on next put-on/take-off anyway).
        ctx.sendBroadcast(
            Intent("com.repository.glasses.listener.DEBUG_WEAR")
                .setPackage("com.repository.glasses.listener")
                .putExtra("wearing", false)
        )

        if (!sawSfx) {
            fail("Lone alert SFX never fired -- debug broadcast did not reach :backend.\n$summary")
        }
        assertTrue(
            "Expected glasses-side A2DP duck ON when lone SFX plays, but it never ducked.\n$summary",
            sawDuckOn
        )
        assertTrue(
            "Duck never released after the SFX finished (duck OFF missing).\n$summary",
            sawDuckOff
        )
    }
}
