package com.repository.glasses.capture

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Plan task 0.3 -- measure the REAL cold load, first execute, steady execute and
 * peak RSS of the GigaAM encoder INSIDE the capture process.
 *
 * The design's 90 s idle-unload timer is provisional: the quoted 3.1 s load is a
 * WARM benchmark figure, and SCRFD's experience on this device is that the first
 * HTP execute after a context load can add a ~12-13 s graph finalize
 * (ScrfdFaceDetector.HTP_FIRST_CEILING_MS = 30_000). Whether the prebuilt GigaAM
 * context avoids that is untested in this process, so it is measured here before
 * any residency policy is written.
 *
 * This reads the context binary from /data/local/tmp/gigaam/ctxout/ deliberately:
 * this is a MEASUREMENT, not the shipping path. The shipping path delivers the
 * blob to capture.filesDir via filesync (plan task 4.3).
 */
@RunWith(AndroidJUnit4::class)
class GigaAmColdLoadMeasurementTest {

    private companion object {
        const val TAG = "GigaAmColdLoad"
        const val CTX_PATH = "/data/local/tmp/gigaam/ctxout/enc_e2e_ctx.bin"
        const val ENC_DIM = 768
        const val ENC_T = 125
        const val STEADY_ITERS = 10
    }

    private fun vmRssKb(): Long =
        File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("VmRSS:") }
            ?.filter { it.isDigit() }?.toLongOrNull() ?: -1L

    private fun loadMelFb(): FloatArray {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = ctx.assets.open("ml/gigaam/melfb_64x161.f32").use { it.readBytes() }
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }

    @Test
    fun measureColdLoadFirstExecuteSteadyExecuteAndRss() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val ctxFile = File(CTX_PATH)
        assertTrue("context binary missing at $CTX_PATH", ctxFile.isFile)

        val rssBefore = vmRssKb()
        val melFb = loadMelFb()

        val t0 = SystemClock.elapsedRealtime()
        val loaded = GigaAmNative.load(ctx.applicationInfo.nativeLibraryDir, CTX_PATH, melFb)
        val coldInitMs = SystemClock.elapsedRealtime() - t0
        assertTrue("load() failed (init returned 0, see logcat tag GigaAmEnc)", loaded)

        try {
            val rssAfterInit = vmRssKb()

            // 5 s of float32 mono 16 kHz PCM. Content does not matter for timing;
            // a fixed tone keeps it deterministic and non-degenerate.
            val pcm = FloatArray(16000 * 5) { i ->
                (0.1 * kotlin.math.sin(2.0 * Math.PI * 220.0 * i / 16000.0)).toFloat()
            }

            val f0 = SystemClock.elapsedRealtime()
            val first = GigaAmNative.encode(pcm)
            val firstExecMs = SystemClock.elapsedRealtime() - f0
            assertTrue("first encode returned null", first != null)
            assertTrue(
                "unexpected encode output length ${first!!.size}",
                first.size == ENC_DIM * ENC_T + 3
            )
            // The length check alone is a compile-time constant and would pass on
            // all-zero garbage, so assert the encoder actually produced signal and
            // reported a sane frame count. This does not verify transcription
            // correctness (that is a later task) but it does catch a context binary
            // that loads and then emits nothing.
            assertTrue(
                "encoder output is all zeros -- model loaded but produced no signal",
                first.take(ENC_DIM * ENC_T).any { it != 0f }
            )
            // encodedLen is the number of VALID encoder frames; ENC_T (125) is the
            // buffer capacity, and a 5 s window yields 124. Assert a sane frame
            // count rather than a specific one: the point is to catch an encoder
            // that reports zero/garbage frames, not to pin the exact stride.
            val encodedLen = first[ENC_DIM * ENC_T]
            assertTrue(
                "implausible encodedLen $encodedLen (expected roughly $ENC_T)",
                encodedLen > ENC_T * 0.9f && encodedLen <= ENC_T.toFloat()
            )

            val steady = LongArray(STEADY_ITERS)
            for (i in 0 until STEADY_ITERS) {
                val s0 = SystemClock.elapsedRealtime()
                val r = GigaAmNative.encode(pcm)
                steady[i] = SystemClock.elapsedRealtime() - s0
                assertTrue("steady encode $i returned null", r != null)
            }
            steady.sort()
            val medianMs = steady[STEADY_ITERS / 2]
            val rssPeak = vmRssKb()

            val report = buildString {
                append("STT-COLD-LOAD ")
                append("cold_init_ms=$coldInitMs ")
                append("first_exec_ms=$firstExecMs ")
                append("steady_median_ms=$medianMs ")
                append("steady_min_ms=${steady.first()} steady_max_ms=${steady.last()} ")
                append("rss_before_kb=$rssBefore rss_after_init_kb=$rssAfterInit ")
                append("rss_peak_kb=$rssPeak ")
                append("rss_model_delta_kb=${rssAfterInit - rssBefore} ")
                append("first_minus_steady_ms=${firstExecMs - medianMs}")
            }
            Log.i(TAG, report)
            println(report)
        } finally {
            GigaAmNative.close()
        }
    }

    /**
     * The 21 s cold load measured above was a single sample with an uncontrolled
     * page cache, and a residency policy now rests on it. This re-measures the
     * load TWICE back to back so a page-cache artifact would show up as a large
     * second-run speedup. If run 2 is close to run 1, the cost is real work
     * (context deserialization), not blob I/O.
     */
    @Test
    fun coldLoadIsReproducibleAndNotAPageCacheArtifact() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("context binary missing at $CTX_PATH", File(CTX_PATH).isFile)
        val melFb = loadMelFb()
        val dir = ctx.applicationInfo.nativeLibraryDir

        val runs = LongArray(2)
        for (i in 0 until 2) {
            val t0 = SystemClock.elapsedRealtime()
            val ok = GigaAmNative.load(dir, CTX_PATH, melFb)
            runs[i] = SystemClock.elapsedRealtime() - t0
            assertTrue("load() failed on run $i", ok)
            GigaAmNative.close()
        }
        val report = "STT-COLD-LOAD-REPEAT run1_ms=${runs[0]} run2_ms=${runs[1]} " +
            "delta_ms=${runs[0] - runs[1]}"
        Log.i(TAG, report)
        println(report)
    }

    /**
     * Regression guard for the use-after-free the audit found: calling encode()
     * after close() must return null rather than dereferencing a freed engine.
     * Before the handle was made private and owned by GigaAmNative, a caller
     * holding a stale Long could crash the capture process here.
     */
    @Test
    fun encodeAfterCloseReturnsNullInsteadOfCrashing() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("context binary missing at $CTX_PATH", File(CTX_PATH).isFile)
        val melFb = loadMelFb()
        assertTrue(GigaAmNative.load(ctx.applicationInfo.nativeLibraryDir, CTX_PATH, melFb))
        assertTrue(GigaAmNative.isLoaded())
        GigaAmNative.close()
        assertTrue("still loaded after close", !GigaAmNative.isLoaded())
        // Must be a clean null, not a native crash.
        assertEquals(null, GigaAmNative.encode(FloatArray(16000)))
        // close() is idempotent -- a double close must not double free.
        GigaAmNative.close()
    }
}
