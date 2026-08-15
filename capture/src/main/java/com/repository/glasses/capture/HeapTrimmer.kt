package com.repository.glasses.capture

import android.util.Log

/**
 * Keeps this process's Java heap from looking bigger to lmkd than it really is.
 *
 * ## Why
 * The device ships `dalvik.vm.heaptargetutilization=0.5`, so ART sizes the heap
 * at ~2x the live set and keeps the slack. Measured here after a photo:
 * **49MB live but 147MB of capacity** -- ~98MB of retained-but-empty heap.
 *
 * lmkd picks victims by `oom_score_adj * size`, and this capture priv-app runs
 * at adj 100 -- the highest-scoring real target on the device. So it was being
 * killed for memory it was not using, which loses the user's photo mid-capture.
 *
 * Raising target utilization to [HEAP_UTILIZATION] makes ART track ~1.33x live
 * instead of 2x: measured capacity drops 147MB -> 75MB for the same live set.
 *
 * ## What this deliberately does NOT do
 * `VMRuntime.trimHeap()` would hand the slack back directly, but it is
 * `max-target-o` and every reflective route to it is refused on this SDK-32
 * build -- direct lookup, boot-classpath double reflection, and
 * `setHiddenApiExemptions` all fail with the hidden-API policy. That code was
 * removed rather than left in: it never worked here, and
 * `setHiddenApiExemptions` in particular REPLACES the exemption list for the
 * whole process, which also hosts onnxruntime/QNN and camera reflection.
 *
 * `System.gc()` plus a tighter utilization target achieves the same end without
 * touching hidden API at all.
 */
internal object HeapTrimmer {
    private const val TAG = "Cap:HeapTrim"

    /**
     * 0.75 = heap sized at ~1.33x live; device default is 0.5 (2x).
     *
     * Measured on this hardware after a photo (49MB live):
     *   0.50 (default) -> 147MB capacity
     *   0.75           ->  99MB capacity
     *
     * Kept at 0.75 rather than higher: a tighter heap means more frequent GC,
     * and those pauses can land on the ImageReader callback thread during a
     * 24MB frame read. 0.75 is the documented ART maximum in several releases
     * anyway, so asking for more buys nothing.
     */
    private const val HEAP_UTILIZATION = 0.75f


    @Volatile private var utilizationSet = false

    /**
     * Set ART's target heap utilization for this process. Idempotent and cheap;
     * call once at service start, before the first capture allocates.
     *
     * `setTargetHeapUtilization` is greylisted (not blocked), so unlike
     * `trimHeap` it is reachable on this build.
     */
    fun init() {
        if (utilizationSet) return
        utilizationSet = true
        val vmClass = try {
            Class.forName("dalvik.system.VMRuntime")
        } catch (e: Throwable) {
            Log.w(TAG, "VMRuntime unavailable: ${e.message}")
            return
        }
        val runtime = try {
            vmClass.getDeclaredMethod("getRuntime").apply { isAccessible = true }.invoke(null)
        } catch (e: Throwable) {
            Log.w(TAG, "getRuntime failed: ${e.message}")
            return
        }

        try {
            vmClass.getDeclaredMethod("setTargetHeapUtilization", Float::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(runtime, HEAP_UTILIZATION)
            Log.i(TAG, "heap target utilization set to $HEAP_UTILIZATION (device default ~0.5)")
        } catch (e: Throwable) {
            Log.w(TAG, "setTargetHeapUtilization failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        // TRIED AND REVERTED: setMinHeapFree(4MB)/setMaxHeapFree(8MB).
        // The theory was sound (the device default heapmaxfree=32m lets ART
        // hoard, and a peak snapshot showed 97MB free inside a 149MB heap), but
        // measured on device it made things WORSE: peak rose 202MB -> 363MB and
        // the double-press pass rate fell from 8/10 to 4/10. Forcing tiny free
        // windows makes ART grow-then-collect repeatedly under this pipeline's
        // large short-lived arrays. Do not re-add without new measurements.
    }

    /**
     * Collect the pixel arrays a finished capture left behind.
     *
     * Call only when no capture is in flight and the queue is empty: the
     * collection walks the heap and would otherwise compete with work the user
     * is waiting on. Safe to call from a background thread; never from the
     * camera or ImageReader callback threads.
     */
    fun collect() {
        init()
        val rt = Runtime.getRuntime()
        val beforeKb = (rt.totalMemory() - rt.freeMemory()) / 1024
        try {
            // Two passes: the first runs finalizers, the second collects them.
            System.gc()
            System.runFinalization()
            System.gc()
        } catch (_: Throwable) {
            return
        }
        val afterKb = (rt.totalMemory() - rt.freeMemory()) / 1024
        Log.i(
            TAG,
            "heap after gc: live ${beforeKb}kB -> ${afterKb}kB, capacity ${rt.totalMemory() / 1024}kB",
        )
    }
}
