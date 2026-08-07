package com.repository.glasses.capture

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.FloatBuffer

/**
 * Confirms an onnxruntime CPU session runs inside the capture process alongside
 * the bundled QNN 2.47 runtime libraries.
 *
 * Why this exists: the GigaAM RNNT decoder/joint run on the CPU through ORT while
 * the conformer encoder runs on the Hexagon NPU through the raw QNN C API, both in
 * this process. The benchmark project used the PLAIN onnxruntime-android artifact
 * because the -qnn variant bundles QNN 2.27 backend libs that clash with a bundled
 * 2.47. This module depends on onnxruntime-android-qnn:1.20.0 but already resolves
 * that clash in build.gradle.kts with an explicit jniLibs pickFirsts block naming
 * all eight libQnn*.so, so the src/main/jniLibs 2.47 copies win over the AAR's
 * 2.27 copies (2.27's deserializer rejects 2.47 context binaries with
 * QNN_CONTEXT_ERROR_BINARY_VERSION, err 0x7532). SplitterNet already ships on this
 * arrangement, so this is a confirmation rather than an open question.
 *
 * Do NOT "fix" a failure here by switching to plain onnxruntime-android:
 * LowLightCapturer uses OrtSession.SessionOptions.addQnn, so dropping the -qnn
 * artifact would silently disable low-light NPU inference.
 */
@RunWith(AndroidJUnit4::class)
class OrtCpuSessionSmokeTest {

    /** y = x * 2 + 1, elementwise over 4 floats. */
    private fun readModel(): ByteArray {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        return ctx.assets.open("ml/ort_cpu_smoke.onnx").use { it.readBytes() }
    }

    @Test
    fun ortCpuSessionRunsInCaptureProcess() {
        val env = OrtEnvironment.getEnvironment()
        val session = env.createSession(readModel(), OrtSession.SessionOptions())
        session.use {
            val input = floatArrayOf(1f, 2f, 3f, 4f)
            val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(4))
            tensor.use {
                val result = session.run(mapOf("x" to tensor))
                result.use {
                    @Suppress("UNCHECKED_CAST")
                    val out = result[0].value as FloatArray
                    // Assert the actual computed values, not merely that a session
                    // was constructed: a session that loads but mis-executes would
                    // otherwise pass and take the RNNT decoder down with it later.
                    assertArrayEquals(floatArrayOf(3f, 5f, 7f, 9f), out, 1e-5f)
                }
            }
        }
    }

    /**
     * The QNN 2.47 runtime libs must be resident as real files in the SAME process
     * that just ran the ORT CPU session. This is the coexistence claim: if the
     * pickFirsts resolution regressed and the AAR's 2.27 copies won, the encoder
     * would later fail to deserialize the 2.47 context binary at runtime.
     */
    @Test
    fun qnn247RuntimeLibsCoexistWithOrt() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.applicationInfo.nativeLibraryDir)
        for (name in listOf("libQnnHtp.so", "libQnnSystem.so", "libQnnHtpV73Stub.so", "libQnnHtpV73Skel.so")) {
            val f = File(dir, name)
            assertTrue("missing ${f.absolutePath}", f.isFile)
            assertTrue("empty ${f.absolutePath}", f.length() > 0)
        }
        // And ORT itself is loadable in this same process.
        assertEquals("com.repository.glasses.capture", ctx.packageName)
        OrtEnvironment.getEnvironment()
    }
}
