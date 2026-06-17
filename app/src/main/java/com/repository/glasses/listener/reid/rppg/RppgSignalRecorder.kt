package com.repository.glasses.listener.reid.rppg

import java.io.File
import kotlin.math.abs

/**
 * Diagnostic 60-second ring of the raw per-frame spatial-mean RGB samples that are
 * actually fed into the rPPG heart-rate detector, plus an on-demand CSV dump that
 * recomputes the POS pulse and marks detected beats so the captured colour signal
 * can be visualised against the beats the pipeline found.
 *
 * This is a process-wide singleton fed from [RppgEngine.onSamples] (the same RGB
 * batch the detector consumes) keyed by the most-recently-seen track, so the dump
 * reflects exactly what drove the displayed BPM. It performs no detection itself;
 * it only RECORDS the detector input and re-derives the pulse/beats for plotting.
 *
 * Columns in the CSV (one row per recorded frame, oldest first):
 *   tMs       -- elapsedRealtime ms of the frame.
 *   relS      -- seconds relative to the first row (ease of plotting).
 *   r,g,b     -- raw spatial-mean channel values fed to the detector.
 *   pos       -- POS pulse amplitude at this frame (computed over the dumped window
 *                on the resampled grid, then mapped back to nearest frame). Empty
 *                when the window is too short to compute.
 *   beat      -- 1 when this frame is a detected systolic peak of the POS pulse,
 *                else 0. Beats are local maxima of the band-limited pulse, the same
 *                signal SpectralBpm analyses, so visible amplitude bumps should line
 *                up with beat=1 marks.
 *
 * Thread-safety: [add] runs on the binder/Listener thread; [dumpCsv] runs on the
 * UI/driver thread. Both synchronize on [lock]. The ring holds at most
 * [WINDOW_MS] of samples.
 */
object RppgSignalRecorder {

    private const val WINDOW_MS = 60_000L

    private val lock = Any()
    private val tMs = ArrayDeque<Long>()
    private val rs = ArrayDeque<Float>()
    private val gs = ArrayDeque<Float>()
    private val bs = ArrayDeque<Float>()

    /** Append one frame's spatial-mean RGB at [t] (elapsedRealtime ms). */
    fun add(t: Long, r: Float, g: Float, b: Float) {
        synchronized(lock) {
            val last = tMs.lastOrNull()
            if (last != null && t < last) return // drop out-of-order defensively
            tMs.addLast(t); rs.addLast(r); gs.addLast(g); bs.addLast(b)
            val cutoff = t - WINDOW_MS
            while (tMs.isNotEmpty() && tMs.first() < cutoff) {
                tMs.removeFirst(); rs.removeFirst(); gs.removeFirst(); bs.removeFirst()
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            tMs.clear(); rs.clear(); gs.clear(); bs.clear()
        }
    }

    /**
     * Snapshot the ring, recompute POS + beats, and write a CSV to [file].
     * Returns the number of data rows written (0 if nothing recorded).
     */
    fun dumpCsv(file: File): Int {
        val t: LongArray
        val r: FloatArray
        val g: FloatArray
        val b: FloatArray
        synchronized(lock) {
            val n = tMs.size
            if (n == 0) {
                file.writeText("tMs,relS,r,g,b,pos,beat\n")
                return 0
            }
            t = LongArray(n); r = FloatArray(n); g = FloatArray(n); b = FloatArray(n)
            var i = 0
            val tIt = tMs.iterator(); val rIt = rs.iterator()
            val gIt = gs.iterator(); val bIt = bs.iterator()
            while (tIt.hasNext()) {
                t[i] = tIt.next(); r[i] = rIt.next(); g[i] = gIt.next(); b[i] = bIt.next(); i++
            }
        }

        val n = t.size
        // Per-frame POS amplitude + beat marks, derived over the whole dumped window
        // on the same resampled grid SpectralBpm uses, then mapped back to frames.
        val posPerFrame = FloatArray(n) { Float.NaN }
        val beat = IntArray(n)
        if (n >= 4) {
            val ru = Resampler.resampleUniform(t, r)
            val gu = Resampler.resampleUniform(t, g)
            val bu = Resampler.resampleUniform(t, b)
            val m = minOf(ru.values.size, gu.values.size, bu.values.size)
            if (m >= 4 && ru.fps > 0f) {
                val pulse = RppgSignal.green(
                    ru.values.copyOf(m), gu.values.copyOf(m), bu.values.copyOf(m)
                )
                // Uniform grid times: grid[k] = t0 + k*stepMs, stepMs = 1000/fps.
                val stepMs = 1000.0 / ru.fps
                val t0 = t.first()
                // Map each grid pulse sample to nearest original frame index.
                var fi = 0
                for (k in 0 until m) {
                    val gt = t0 + (k * stepMs).toLong()
                    while (fi + 1 < n && abs(t[fi + 1] - gt) <= abs(t[fi] - gt)) fi++
                    posPerFrame[fi] = pulse[k]
                }
                // Beat marks: local maxima of the grid pulse above a small positive
                // threshold (systolic peaks), mapped back to nearest frame. A simple
                // 3-point peak with refractory spacing >= 0.33s (max ~180 bpm) to
                // avoid double-marking one beat.
                val refractoryGrid = maxOf(1, (0.33 / (stepMs / 1000.0)).toInt())
                var lastPeakK = -refractoryGrid - 1
                // amplitude scale for the threshold: 0.3 * RMS of the pulse.
                var sumSq = 0.0
                for (v in pulse) sumSq += (v * v).toDouble()
                val rms = if (m > 0) Math.sqrt(sumSq / m).toFloat() else 0f
                val thr = 0.3f * rms
                for (k in 1 until m - 1) {
                    if (pulse[k] > thr && pulse[k] >= pulse[k - 1] && pulse[k] > pulse[k + 1] &&
                        k - lastPeakK >= refractoryGrid
                    ) {
                        lastPeakK = k
                        val gt = t0 + (k * stepMs).toLong()
                        var bi = 0
                        var best = Long.MAX_VALUE
                        for (idx in 0 until n) {
                            val d = abs(t[idx] - gt)
                            if (d < best) { best = d; bi = idx }
                        }
                        beat[bi] = 1
                    }
                }
            }
        }

        val sb = StringBuilder(n * 48)
        sb.append("tMs,relS,r,g,b,pos,beat\n")
        val t0 = t.first()
        for (i in 0 until n) {
            val relS = (t[i] - t0) / 1000.0
            val pos = posPerFrame[i]
            sb.append(t[i]).append(',')
                .append("%.3f".format(relS)).append(',')
                .append("%.4f".format(r[i])).append(',')
                .append("%.4f".format(g[i])).append(',')
                .append("%.4f".format(b[i])).append(',')
                .append(if (pos.isNaN()) "" else "%.5f".format(pos)).append(',')
                .append(beat[i]).append('\n')
        }
        file.writeText(sb.toString())
        return n
    }
}
