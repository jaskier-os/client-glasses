package com.test.facedetbench

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.android.gms.tasks.Tasks
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Standalone ML Kit face-detection latency benchmark for the Rokid glasses.
 *
 * Sanity-check sweep: is the ~3.2s/frame INTRINSIC, or an artifact of config?
 * Varies one factor at a time (minFaceSize, resolution, performance mode + options)
 * around the production baseline (ACCURATE, minFaceSize 0.1f, no landmarks/contours/
 * classification -- confirmed in ListenerService.kt extractFaceFromImage).
 *
 * Timing methodology is identical for every config: 5 warmups + 50 warm iters,
 * timing ONLY detector.process(inputImage) awaited via Tasks.await. fromBitmap prep
 * is measured separately. Each config verifies a face is actually detected.
 */
class BenchActivity : Activity() {

    companion object {
        const val TAG = "FaceDetBench"
        const val WARMUP = 5
        const val ITERS = 50
    }

    /** One benchmark cell: a named detector config + an input resolution. */
    data class Cfg(
        val label: String,
        val perfMode: Int,
        val minFaceSize: Float,
        val landmarks: Boolean,
        val contours: Boolean,
        val classification: Boolean,
        val w: Int,
        val h: Int
    )

    private fun buildOptions(c: Cfg): FaceDetectorOptions {
        val b = FaceDetectorOptions.Builder()
            .setPerformanceMode(c.perfMode)
            .setMinFaceSize(c.minFaceSize)
        b.setLandmarkMode(
            if (c.landmarks) FaceDetectorOptions.LANDMARK_MODE_ALL
            else FaceDetectorOptions.LANDMARK_MODE_NONE
        )
        b.setContourMode(
            if (c.contours) FaceDetectorOptions.CONTOUR_MODE_ALL
            else FaceDetectorOptions.CONTOUR_MODE_NONE
        )
        b.setClassificationMode(
            if (c.classification) FaceDetectorOptions.CLASSIFICATION_MODE_ALL
            else FaceDetectorOptions.CLASSIFICATION_MODE_NONE
        )
        return b.build()
    }

    /**
     * Build the config matrix. We change ONE variable at a time off the production
     * baseline so each cell's delta is attributable.
     *   baseline = ACCURATE, minFaceSize 0.1, no extras, 1008x756 (production frame).
     */
    private fun buildMatrix(): List<Cfg> {
        val A = FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE
        val F = FaceDetectorOptions.PERFORMANCE_MODE_FAST
        val cfgs = ArrayList<Cfg>()

        // --- (1) minFaceSize sweep @ production resolution 1008x756, ACCURATE, no extras
        for (mfs in listOf(0.1f, 0.15f, 0.25f, 0.4f)) {
            cfgs.add(Cfg("ACC_mfs${mfs}_1008x756", A, mfs, false, false, false, 1008, 756))
        }

        // --- (2) resolution sweep @ ACCURATE, production minFaceSize 0.1, no extras
        for ((w, h) in listOf(Pair(480, 360), Pair(640, 480), Pair(1024, 1024))) {
            cfgs.add(Cfg("ACC_mfs0.1_${w}x${h}", A, 0.1f, false, false, false, w, h))
        }

        // --- (3) options / mode comparison @ production resolution 1008x756, mfs 0.1
        //   PRODUCTION-EXACT: ACCURATE, no extras (== baseline above, repeated for clarity)
        cfgs.add(Cfg("PROD_EXACT_ACC_noextras_1008x756", A, 0.1f, false, false, false, 1008, 756))
        //   stripped-down lightest possible: FAST, no extras
        cfgs.add(Cfg("LIGHT_FAST_noextras_1008x756", F, 0.1f, false, false, false, 1008, 756))
        //   heavy: ACCURATE + landmarks+contours+classification (what extras would cost)
        cfgs.add(Cfg("HEAVY_ACC_allextras_1008x756", A, 0.1f, true, true, true, 1008, 756))

        // --- bonus: best-case fast small input (FAST, 480x360, larger minFaceSize)
        cfgs.add(Cfg("BEST_FAST_mfs0.25_480x360", F, 0.25f, false, false, false, 480, 360))

        return cfgs
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thread(name = "facedet-bench") {
            try {
                runAll()
            } catch (t: Throwable) {
                Log.e(TAG, "FATAL: ${t.message}", t)
            } finally {
                finish()
            }
        }
    }

    private fun runAll() {
        val assets = assetManager().list("")?.filter { it.startsWith("face") && it.endsWith(".jpg") }?.sorted()
            ?: emptyList()
        Log.i(TAG, "===== Face Detection Benchmark start =====")
        Log.i(TAG, "device=${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT} " +
                "abi=${android.os.Build.SUPPORTED_ABIS.joinToString(",")} faces=$assets")

        val out = JSONObject()
        out.put("device", android.os.Build.MODEL)
        out.put("sdk", android.os.Build.VERSION.SDK_INT)
        out.put("mlkit_face_detection", "16.1.7")
        out.put("warmup", WARMUP)
        out.put("iters", ITERS)
        out.put("note", "production baseline = ACCURATE, minFaceSize 0.1, no landmarks/contours/classification")
        val results = JSONArray()

        // Decode the source faces once at native size; each cell scales as needed.
        val srcFaces = assets.map { decodeAsset(it) }

        for (c in buildMatrix()) {
            val options = buildOptions(c)
            val detector = FaceDetection.getClient(options)

            // Pre-scale all faces to this cell's resolution (timed loop excludes scaling).
            val bitmaps = srcFaces.map { scaleTo(it, c.w, c.h) }

            // Cold-start: first detect on a fresh detector (lazy model load for this config).
            var coldMs = -1.0
            try {
                val t0 = SystemClock.elapsedRealtimeNanos()
                Tasks.await(detector.process(InputImage.fromBitmap(bitmaps[0], 0)))
                coldMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
            } catch (e: Exception) {
                Log.e(TAG, "[${c.label}] cold detect failed: ${e.message}", e)
            }

            // Warmup (not timed).
            repeat(WARMUP) {
                Tasks.await(detector.process(InputImage.fromBitmap(bitmaps[it % bitmaps.size], 0)))
            }

            var faceCount = -1
            var box = ""
            val times = DoubleArray(ITERS)
            var prepMsAccum = 0.0
            for (i in 0 until ITERS) {
                val bm = bitmaps[i % bitmaps.size]
                val tPrep0 = SystemClock.elapsedRealtimeNanos()
                val img = InputImage.fromBitmap(bm, 0)
                val tProc0 = SystemClock.elapsedRealtimeNanos()
                val faces: List<Face> = Tasks.await(detector.process(img))
                val tEnd = SystemClock.elapsedRealtimeNanos()
                times[i] = (tEnd - tProc0) / 1e6
                prepMsAccum += (tProc0 - tPrep0) / 1e6
                if (i == 0) {
                    faceCount = faces.size
                    val fc = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                    box = fc?.boundingBox?.toShortString() ?: "none"
                }
            }
            times.sort()
            val stats = stats(times)
            val prepMean = prepMsAccum / ITERS
            Log.i(TAG, "[${c.label}] faces=$faceCount box=$box cold=${f(coldMs)} | " +
                    "process(ms) min=${f(stats[0])} median=${f(stats[1])} mean=${f(stats[2])} " +
                    "p90=${f(stats[3])} max=${f(stats[4])} | prep_mean=${f(prepMean)}ms")

            val r = JSONObject()
            r.put("label", c.label)
            r.put("perf_mode", if (c.perfMode == FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE) "ACCURATE" else "FAST")
            r.put("min_face_size", c.minFaceSize.toDouble())
            r.put("landmarks", c.landmarks)
            r.put("contours", c.contours)
            r.put("classification", c.classification)
            r.put("width", c.w); r.put("height", c.h)
            r.put("cold_start_ms", coldMs)
            r.put("faces_detected", faceCount)
            r.put("bounding_box", box)
            r.put("process_min_ms", stats[0])
            r.put("process_median_ms", stats[1])
            r.put("process_mean_ms", stats[2])
            r.put("process_p90_ms", stats[3])
            r.put("process_max_ms", stats[4])
            r.put("bitmap_prep_mean_ms", prepMean)
            results.put(r)

            bitmaps.forEach { bm -> if (bm !in srcFaces && !bm.isRecycled) bm.recycle() }
            detector.close()
        }

        srcFaces.forEach { if (!it.isRecycled) it.recycle() }

        out.put("results", results)
        val outFile = File(getExternalFilesDir(null), "facedet_bench_results.json")
        outFile.writeText(out.toString(2))
        Log.i(TAG, "Results written to ${outFile.absolutePath}")
        Log.i(TAG, "===== Face Detection Benchmark done =====")
    }

    private fun stats(sorted: DoubleArray): DoubleArray {
        val n = sorted.size
        val min = sorted[0]
        val max = sorted[n - 1]
        val mean = sorted.sum() / n
        val median = if (n % 2 == 0) (sorted[n / 2 - 1] + sorted[n / 2]) / 2 else sorted[n / 2]
        val p90 = sorted[Math.min(n - 1, Math.ceil(0.90 * n).toInt() - 1).coerceAtLeast(0)]
        return doubleArrayOf(min, median, mean, p90, max)
    }

    private fun f(v: Double) = "%.2f".format(v)

    private fun assetManager() = resources.assets

    private fun decodeAsset(name: String): Bitmap {
        assetManager().open(name).use { ins ->
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            return BitmapFactory.decodeStream(ins, null, opts)!!
        }
    }

    /** Scale src to (w,h). Returns src unchanged if already that size; never recycles src. */
    private fun scaleTo(src: Bitmap, w: Int, h: Int): Bitmap {
        if (src.width == w && src.height == h) return src
        return Bitmap.createScaledBitmap(src, w, h, true)
    }
}
