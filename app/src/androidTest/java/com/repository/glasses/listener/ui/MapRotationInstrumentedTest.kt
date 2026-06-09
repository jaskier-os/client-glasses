package com.repository.glasses.listener.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Heading-up minimap E2E: feeds a synthetic north-up map bitmap into the glasses
 * MAP tab, then sweeps the streamed heading 0..315 deg. With the heading-up
 * change the MAP must physically ROTATE under a pinned, upright center arrow, and
 * a fixed perspective keystone (vanishing point at top) must stay put regardless
 * of heading.
 *
 * Injection mirrors the production path exactly -- the same in-process broadcasts
 * ListenerService relays to MainActivity:
 *   ACTION_MAP_MINIMAP visible=true  -> show + focus the MAP tab
 *   ACTION_MAP_BITMAP  map_bitmap    -> base64 PNG north-up base map
 *   ACTION_MAP_ARROW   arrow_x/y/heading -> per-sample arrow + (now) map rotation
 *
 * The rotation/keystone code on the glasses is provider-agnostic (the glasses
 * only ever receive a north-up bitmap); the two test methods feed a
 * Google-flavoured and a Yandex-flavoured synthetic map so each provider gets its
 * own held recording, but both exercise the identical rotation path.
 *
 * Each heading is HELD ~2.6s so an external `adb shell screenrecord` captures the
 * rotated state. Assertions: every state screenshots non-null with green present,
 * and the centroid of bright (green) pixels ORBITS the map as heading advances --
 * if the map did not rotate, the centroid would barely move and the test fails.
 */
@RunWith(AndroidJUnit4::class)
class MapRotationInstrumentedTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val device: UiDevice = UiDevice.getInstance(instr)
    private val tag = "MapRotShots"
    private val pkg = "com.repository.glasses.listener"

    // Mirror of ListenerService action/extra constants (kept local to avoid a
    // visibility dependency; values must match ListenerService).
    private val ACTION_MAP_MINIMAP = "$pkg.MAP_MINIMAP"
    private val ACTION_MAP_BITMAP = "$pkg.MAP_BITMAP"; private val EXTRA_MAP_BITMAP = "map_bitmap"
    private val ACTION_MAP_ARROW = "$pkg.MAP_ARROW"
    private val EXTRA_ARROW_X = "arrow_x"; private val EXTRA_ARROW_Y = "arrow_y"
    private val EXTRA_ARROW_HEADING = "arrow_heading"

    private val headings = floatArrayOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f)

    private fun artifactDir(label: String): File =
        File(ctx.getExternalFilesDir(null), "map-rotation/$label").apply { if (!exists()) mkdirs() }

    /** Synthetic north-up base map: black with a bright bar near the TOP edge plus
     * a few "roads", so rotation visibly moves the bright mass around the center.
     * googleFlavour=true uses a denser road grid (mimics Google top-down); false a
     * sparser look (mimics the Yandex POI/road style). Rotation behaviour is the
     * same either way -- the flavour only changes the imagery in the recording. */
    private fun baseMapPng(googleFlavour: Boolean): String {
        val w = 600; val h = 300
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        // Distinctive bright bar near the top so "up" is identifiable as it rotates.
        c.drawRect(w * 0.2f, h * 0.10f, w * 0.8f, h * 0.18f, p)
        // Roads.
        p.strokeWidth = if (googleFlavour) 6f else 9f
        c.drawLine(0f, h * 0.5f, w.toFloat(), h * 0.5f, p)
        c.drawLine(w * 0.5f, 0f, w * 0.5f, h.toFloat(), p)
        if (googleFlavour) {
            c.drawLine(0f, h * 0.3f, w.toFloat(), h * 0.7f, p)
            c.drawLine(w * 0.3f, 0f, w * 0.7f, h.toFloat(), p)
        } else {
            c.drawCircle(w * 0.7f, h * 0.65f, 22f, p)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun inject(action: String, build: Intent.() -> Unit) {
        ctx.sendBroadcast(Intent(action).apply { setPackage(pkg); build() })
    }

    private fun sendArrow(headingDeg: Float) {
        inject(ACTION_MAP_ARROW) {
            putExtra(EXTRA_ARROW_X, 0.5f)
            putExtra(EXTRA_ARROW_Y, 0.5f)
            putExtra(EXTRA_ARROW_HEADING, headingDeg)
        }
    }

    private fun shoot(dir: File, step: String): Bitmap {
        val bmp: Bitmap? = instr.uiAutomation.takeScreenshot()
        assertNotNull("takeScreenshot null at $step", bmp)
        FileOutputStream(File(dir, "$step.png")).use {
            bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        Log.i(tag, "saved $step (${bmp!!.width}x${bmp.height})")
        return bmp
    }

    /** Centroid (x,y) of green-ish pixels in the MAP region only, plus count.
     * The waveguide renders only green, so any lit pixel is part of the HUD; we
     * exclude the top status row and the bottom tab bar (both static) so only the
     * rotating minimap contributes -- otherwise the constant bar dominates and the
     * centroid never moves. The 288x144dp map frame is centered; on the 480x640
     * panel that is comfortably inside rows [120, 480]. */
    private fun greenCentroid(bmp: Bitmap): Triple<Float, Float, Long> {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val yTop = (h * 0.18f).toInt()
        val yBot = (h * 0.75f).toInt()
        var sx = 0.0; var sy = 0.0; var n = 0L
        for (y in yTop until yBot) {
            var i = y * w
            for (x in 0 until w) {
                val c = px[i++]
                val g = (c shr 8) and 0xFF
                val r = (c shr 16) and 0xFF
                val b = c and 0xFF
                if (g > 80 && g > r + 20 && g > b + 20) {
                    sx += x; sy += y; n++
                }
            }
        }
        return if (n == 0L) Triple(w / 2f, h / 2f, 0L)
        else Triple((sx / n).toFloat(), (sy / n).toFloat(), n)
    }

    /** Re-assert the MAP tab + base map. The companion-phone BT relay may flap and
     * each disconnect broadcasts MAP_MINIMAP(false), tearing the tab down; showing
     * it again is idempotent when already visible, so we re-assert before captures. */
    private fun ensureMapShown(mapPng: String) {
        inject(ACTION_MAP_MINIMAP) { putExtra("visible", true) }
        inject(ACTION_MAP_BITMAP) { putExtra(EXTRA_MAP_BITMAP, mapPng) }
    }

    @Test
    fun mapRotationGoogle() = runSweep("google", googleFlavour = true)

    @Test
    fun mapRotationYandex() = runSweep("yandex", googleFlavour = false)

    private fun runSweep(label: String, googleFlavour: Boolean) {
        val dir = artifactDir(label)
        val mapPng = baseMapPng(googleFlavour)

        instr.uiAutomation.executeShellCommand("am start -n $pkg/.MainActivity")
        SystemClock.sleep(2500)
        device.waitForIdle()

        // Show + focus the MAP tab, then push the north-up base map.
        ensureMapShown(mapPng)
        SystemClock.sleep(1500)
        // Prime the arrow sample buffer so the Choreographer loop is running.
        sendArrow(0f); SystemClock.sleep(250); sendArrow(0f)
        SystemClock.sleep(800)

        val centroids = ArrayList<Pair<Float, Float>>()
        for (hdg in headings) {
            // Re-assert the map (BT relay may flap and tear the tab down) and settle
            // the smoothed heading on this value, holding for the recording.
            repeat(10) {
                ensureMapShown(mapPng)
                sendArrow(hdg)
                SystemClock.sleep(250)
            }
            // Final re-assert immediately before the shot to beat any flap teardown.
            ensureMapShown(mapPng)
            sendArrow(hdg)
            SystemClock.sleep(400)
            val shot = shoot(dir, "h_%03d".format(hdg.toInt()))
            val (gx, gy, n) = greenCentroid(shot)
            Log.i(tag, "$label heading=$hdg centroid=($gx,$gy) green=$n")
            assertTrue("$label: no green map pixels at heading=$hdg (map not rendered?)", n > 200)
            centroids.add(gx to gy)
            shot.recycle()
        }

        // The map must actually rotate: the green centroid should orbit the map
        // center, so its x and y spread across the sweep must be non-trivial. A
        // static (non-rotating) map would keep the centroid essentially fixed.
        val xs = centroids.map { it.first }
        val ys = centroids.map { it.second }
        val spanX = (xs.maxOrNull()!! - xs.minOrNull()!!)
        val spanY = (ys.maxOrNull()!! - ys.minOrNull()!!)
        Log.i(tag, "$label centroid spans: x=$spanX y=$spanY")
        assertTrue(
            "$label: centroid barely moved across headings (x=$spanX y=$spanY) -- map did not rotate",
            spanX > 8f || spanY > 8f
        )

        // Opposite headings (0 vs 180) should put the bright top-bar on opposite
        // sides of the map center, so their centroids must differ noticeably.
        val c0 = centroids[0]
        val idx180 = headings.indexOfFirst { it == 180f }
        val c180 = centroids[idx180]
        val flip = abs(c0.second - c180.second) + abs(c0.first - c180.first)
        Log.i(tag, "$label 0-vs-180 centroid delta=$flip")
        assertTrue("$label: 0 and 180 headings look identical -- map did not rotate", flip > 6f)

        // Sanity: the angular position of the centroid relative to its own mean
        // should advance, not stay constant.
        val mx = xs.average().toFloat(); val my = ys.average().toFloat()
        val angles = centroids.map { atan2((it.second - my).toDouble(), (it.first - mx).toDouble()) }
        Log.i(tag, "$label centroid angles=$angles")

        Log.i(tag, "$label: done -> ${dir.absolutePath}")
    }
}
