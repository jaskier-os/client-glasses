package com.repository.glasses.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Proves the :capture module has a working androidTest source set.
 *
 * This exists because the on-glasses STT kill gates (ORT CPU session smoke test,
 * model cold-load measurement) are instrumented tests that must run INSIDE the
 * capture process -- capture is the only process whose linker namespace can reach
 * the CDSP. Before this source set existed, :capture had only src/main and
 * src/test, no testInstrumentationRunner and no androidTest dependencies, so
 * none of those gates were runnable.
 */
@RunWith(AndroidJUnit4::class)
class CaptureInstrumentationSmokeTest {

    @Test
    fun runsInsideTheCaptureProcess() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val ctx = instr.targetContext
        assertEquals("com.repository.glasses.capture", ctx.packageName)
        // Proves we are instrumenting the capture APK rather than running
        // self-targeted: the test APK is a distinct package.
        assertEquals("${ctx.packageName}.test", instr.context.packageName)
    }

    /**
     * The main source set must be on the loaded classpath, i.e. the test really
     * runs against the capture APK and not an empty shell. A source set that
     * "executes" but cannot see CaptureService would let every later STT gate
     * pass vacuously.
     */
    @Test
    fun mainSourceSetIsOnTheClasspath() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        Class.forName("com.repository.glasses.capture.CaptureService", false, ctx.classLoader)
    }

    /**
     * The QNN HTP skel must be extracted as a REAL FILE in nativeLibraryDir.
     * fastRPC loads it onto the DSP via ADSP_LIBRARY_PATH and cannot mmap it out
     * of the APK. This is the assertion that actually gates the on-glasses STT
     * work: it fails if useLegacyPackaging / extractNativeLibs / the QNN 2.47
     * pickFirsts resolution ever regress.
     */
    @Test
    fun qnnHtpSkelIsExtractedAsRealFile() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val skel = File(ctx.applicationInfo.nativeLibraryDir, "libQnnHtpV73Skel.so")
        assertTrue("missing ${skel.absolutePath}", skel.isFile)
        assertTrue("empty ${skel.absolutePath}", skel.length() > 0)
    }
}
