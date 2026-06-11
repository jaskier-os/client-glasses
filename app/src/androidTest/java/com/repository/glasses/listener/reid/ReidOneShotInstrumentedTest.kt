package com.repository.glasses.listener.reid

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.repository.glasses.listener.capture.CaptureBridge
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * ONE-SHOT ReID diagnostic instrument.
 *
 * Grabs ONE real camera frame from the capture process (via CaptureBridge ->
 * ICapture.captureReidFrame over AIDL), then runs it through the EXACT same
 * Google ML Kit face-detector config that ReidFrameConsumer uses (PERFORMANCE_MODE_ACCURATE,
 * minFaceSize 0.1, tracking; JPEG decoded via BitmapFactory, pre-rotated by rotationDeg via
 * Matrix.postRotate BEFORE InputImage.fromBitmap(bitmap, 0)).
 *
 * It writes the raw JPEG, the unrotated decoded bitmap, the oriented (rotated) bitmap ML Kit
 * actually sees, an oriented+boxes overlay, and a JSON report into
 * <externalFilesDir>/reid_oneshot/ so the human can eyeball whether the frame orientation is the
 * reason ReID logs face=false.
 *
 * Diagnostic, not a pass/fail unit test: it PASSES as long as a frame arrived. The key signal is
 * in the report -- whether ML Kit finds a face on the ORIENTED bitmap vs the UNROTATED control.
 */
@RunWith(AndroidJUnit4::class)
class ReidOneShotInstrumentedTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instr.targetContext
    private val tag = "ReidOneShot"

    private fun log(msg: String) = Log.i(tag, msg)

    private fun outDir(): File {
        val dir = File(targetContext.getExternalFilesDir(null), "reid_oneshot")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun saveJpeg(name: String, bmp: Bitmap) {
        val f = File(outDir(), name)
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        log("saved $name (${bmp.width}x${bmp.height}) -> ${f.absolutePath}")
    }

    private fun saveBytes(name: String, data: ByteArray) {
        val f = File(outDir(), name)
        FileOutputStream(f).use { it.write(data) }
        log("saved $name (${data.size} bytes) -> ${f.absolutePath}")
    }

    private fun ensureMlKitInitialized() {
        // ML Kit normally self-initializes via its MlKitInitProvider ContentProvider. In this
        // instrumentation process that provider did not run, and -- critically -- the MlKitContext
        // class actually loaded at runtime comes from the DEPLOYED, R8-MINIFIED priv-app
        // (/system/priv-app/com.repository.glasses.listener/listener.apk), where the public
        // initializeIfNeeded(...) overloads have been stripped/renamed. So direct calls fail with
        // NoSuchMethod. We therefore reflect over WHATEVER static initializer the on-device class
        // exposes, trying the public names first and then the obfuscated single-Context initializer
        // (zza(Context) in the mapping we read from the AAR). We register the three known
        // ComponentRegistrars explicitly when an overload accepts a List, since the test manifest
        // carries no component-discovery meta-data.
        val app = targetContext.applicationContext
        val mlCtx = Class.forName("com.google.mlkit.common.sdkinternal.MlKitContext")

        // If already initialized, getInstance() succeeds and we are done.
        try {
            mlCtx.getMethod("getInstance").invoke(null)
            log("MlKitContext already initialized")
            return
        } catch (_: Throwable) { /* not yet initialized; fall through */ }

        val registrars: List<Any> = buildList {
            for (cn in listOf(
                "com.google.mlkit.common.internal.CommonComponentRegistrar",
                "com.google.mlkit.vision.common.internal.VisionCommonRegistrar",
                "com.google.mlkit.vision.face.internal.FaceRegistrar"
            )) {
                try { add(Class.forName(cn).getDeclaredConstructor().newInstance()) }
                catch (e: Throwable) { log("registrar $cn unavailable: ${e.message}") }
            }
        }

        // Candidate (methodName, args) initializers, public names first, obfuscated last.
        val attempts: List<Pair<String, Array<Any>>> = listOf(
            "initializeIfNeeded" to arrayOf(app, registrars),
            "initialize" to arrayOf(app, registrars),
            "initializeIfNeeded" to arrayOf<Any>(app),
            "zza" to arrayOf<Any>(app)
        )
        var initialized = false
        for ((name, args) in attempts) {
            try {
                val m = mlCtx.declaredMethods.firstOrNull { cand ->
                    cand.name == name && cand.parameterTypes.size == args.size &&
                        cand.parameterTypes.zip(args).all { (p, a) -> p.isInstance(a) || p.isAssignableFrom(a.javaClass) }
                } ?: continue
                m.isAccessible = true
                m.invoke(null, *args)
                log("MlKitContext initialized via ${name}(${args.size} args)")
                initialized = true
                break
            } catch (e: Throwable) {
                log("init attempt ${name}/${args.size} failed: ${e.message}")
            }
        }
        if (!initialized) log("WARN: no MlKitContext initializer succeeded; getClient may throw")
    }

    private fun newDetector(): com.google.mlkit.vision.face.FaceDetector {
        ensureMlKitInitialized()
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setMinFaceSize(0.1f)
            .enableTracking()
            .build()
        return FaceDetection.getClient(options)
    }

    /** Synchronously detect; returns faces + elapsed ms. */
    private fun detect(
        detector: com.google.mlkit.vision.face.FaceDetector,
        bmp: Bitmap
    ): Pair<List<Face>, Long> {
        val t0 = SystemClock.elapsedRealtime()
        val faces: List<Face> = try {
            // Plain 1-arg blocking await -- the (Task, long, TimeUnit) overload is stripped from the
            // deployed app's R8 build, and this is exactly what ReidFrameConsumer itself uses.
            Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0)))
        } catch (e: Exception) {
            log("detection failed: ${e.message}")
            emptyList()
        }
        return faces to (SystemClock.elapsedRealtime() - t0)
    }

    private fun facesJson(faces: List<Face>): String {
        if (faces.isEmpty()) return "[]"
        val sb = StringBuilder("[")
        faces.forEachIndexed { i, f ->
            val b = f.boundingBox
            if (i > 0) sb.append(",")
            sb.append("{\"L\":${b.left},\"T\":${b.top},\"R\":${b.right},\"B\":${b.bottom}")
                .append(",\"w\":${b.width()},\"h\":${b.height()}")
                .append(",\"trackingId\":${f.trackingId}}")
        }
        sb.append("]")
        return sb.toString()
    }

    @Test
    fun captureOneFrameAndDiagnoseFaceDetection() {
        log("event=start grabbing one frame from capture process")

        val bridge = CaptureBridge(targetContext)
        bridge.remoteLog = { Log.i(tag, "[bridge] $it") }

        val frameLatch = CountDownLatch(1)
        val gotJpeg = AtomicReference<ByteArray?>(null)
        val gotW = AtomicInteger(0)
        val gotH = AtomicInteger(0)
        val gotRot = AtomicInteger(0)
        val gotFrameId = AtomicLong(0)
        val captured = AtomicBoolean(false)

        val listener = object : CaptureBridge.Listener {
            override fun onFrame(
                jpeg: ByteArray, width: Int, height: Int, rotationDeg: Int, frameId: Long
            ) {
                if (captured.getAndSet(true)) return
                gotJpeg.set(jpeg.copyOf())
                gotW.set(width); gotH.set(height); gotRot.set(rotationDeg); gotFrameId.set(frameId)
                log("event=first_frame w=$width h=$height rot=$rotationDeg id=$frameId bytes=${jpeg.size}")
                frameLatch.countDown()
            }
        }
        bridge.addListener(listener)

        // bind() posts to the main looper, so kick it from the main thread.
        instr.runOnMainSync { bridge.bind() }

        // Wait for the AIDL binder to connect (capture service already running on-device).
        val bindDeadline = SystemClock.elapsedRealtime() + 8000
        while (!bridge.isBound && SystemClock.elapsedRealtime() < bindDeadline) {
            SystemClock.sleep(100)
        }
        log("event=bind_state bound=${bridge.isBound}")
        assertTrue(
            "FAIL: capture binder did not connect within 8s (isBound=false). Capture service may not be running.",
            bridge.isBound
        )

        // Trigger ONE exposed ReID still. CaptureBridge.captureReidFrame() runs the
        // RAW burst -> demosaic recipe and delivers the upright JPEG via onFrame
        // (rotationDeg=0, rotation baked into the pixels).
        instr.runOnMainSync { bridge.captureReidFrame() }
        log("event=reid_capture_requested")

        // The ReID still includes an AE warmup + RAW frame + demosaic, so allow longer.
        val arrived = frameLatch.await(20, TimeUnit.SECONDS)

        bridge.removeListener(listener)
        instr.runOnMainSync { bridge.unbind() }

        val jpeg = gotJpeg.get()
        assertTrue(
            "FAIL: no frame arrived within 20s after captureReidFrame (binder was connected). " +
                "Capture pipeline did not deliver a JPEG.",
            arrived && jpeg != null
        )
        jpeg!!

        val frameW = gotW.get()
        val frameH = gotH.get()
        val rot = gotRot.get()
        val frameId = gotFrameId.get()

        // 1. raw received JPEG bytes
        saveBytes("raw_frame.jpg", jpeg)

        // 2. decoded (UNROTATED) bitmap -- what ML Kit would see with no rotation
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        assertTrue("FAIL: BitmapFactory could not decode the received JPEG.", decoded != null)
        decoded!!
        saveJpeg("decoded.jpg", decoded)

        // 3. ORIENTED bitmap: pre-rotate by rotationDeg exactly like ReidFrameConsumer does
        val oriented: Bitmap = if (rot != 0) {
            val m = Matrix().apply { postRotate(rot.toFloat()) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        } else {
            decoded
        }
        saveJpeg("oriented.jpg", oriented)

        // 4. Run ML Kit on ORIENTED (the real ReID path) and on UNROTATED (control).
        val detector = newDetector()
        var facesOriented: List<Face> = emptyList()
        var msOriented = -1L
        var facesUnrotated: List<Face> = emptyList()
        var msUnrotated = -1L
        try {
            val (fo, mo) = detect(detector, oriented)
            facesOriented = fo; msOriented = mo
            log("event=detect_oriented faces=${fo.size} ms=$mo")
            val (fu, mu) = detect(detector, decoded)
            facesUnrotated = fu; msUnrotated = mu
            log("event=detect_unrotated faces=${fu.size} ms=$mu")
        } finally {
            detector.close()
        }

        // 5. Draw boxes on a copy of the oriented bitmap if any faces found there.
        if (facesOriented.isNotEmpty()) {
            val boxed = oriented.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(boxed)
            val paint = Paint().apply {
                color = Color.GREEN; style = Paint.Style.STROKE
                strokeWidth = (boxed.width / 160f).coerceAtLeast(3f)
                isAntiAlias = true
            }
            facesOriented.forEach { canvas.drawRect(it.boundingBox, paint) }
            saveJpeg("oriented_boxes.jpg", boxed)
            if (!boxed.isRecycled) boxed.recycle()
        }

        // 6. Verdict + JSON report.
        val verdict = when {
            facesOriented.isNotEmpty() ->
                "FACE FOUND on ORIENTED bitmap (rotationDeg=$rot is correct for ML Kit)."
            facesUnrotated.isNotEmpty() ->
                "FACE FOUND ONLY on UNROTATED bitmap -- rotationDeg handling is WRONG; ML Kit sees a sideways face after postRotate($rot)."
            else ->
                "NO FACE on either orientation -- not an orientation bug (crop/exposure/no-face-in-frame/detector)."
        }
        log("event=verdict $verdict")

        val report = buildString {
            append("{\n")
            append("  \"frameWidth\": $frameW,\n")
            append("  \"frameHeight\": $frameH,\n")
            append("  \"rotationDeg\": $rot,\n")
            append("  \"frameId\": $frameId,\n")
            append("  \"jpegBytes\": ${jpeg.size},\n")
            append("  \"decodedWidth\": ${decoded.width},\n")
            append("  \"decodedHeight\": ${decoded.height},\n")
            append("  \"orientedWidth\": ${oriented.width},\n")
            append("  \"orientedHeight\": ${oriented.height},\n")
            append("  \"facesOnOriented\": { \"count\": ${facesOriented.size}, \"detectMs\": $msOriented, \"boxes\": ${facesJson(facesOriented)} },\n")
            append("  \"facesOnUnrotated\": { \"count\": ${facesUnrotated.size}, \"detectMs\": $msUnrotated, \"boxes\": ${facesJson(facesUnrotated)} },\n")
            append("  \"verdict\": \"${verdict.replace("\"", "'")}\"\n")
            append("}\n")
        }
        File(outDir(), "report.json").writeText(report)
        log("event=report_written\n$report")

        if (oriented !== decoded && !oriented.isRecycled) oriented.recycle()
        if (!decoded.isRecycled) decoded.recycle()

        log("event=done artifacts in ${outDir().absolutePath}")
        // Diagnostic: pass as long as a frame was captured (asserted above).
    }
}
