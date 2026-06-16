package com.repository.glasses.listener.reid.rppg

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Heart-rate estimate produced by [SpectralBpm].
 *
 * @property bpm beats per minute, clamped to the plausible [42, 240] range; 0 when no estimate.
 * @property snr signal quality in [0, 1]: power in a narrow band around the chosen peak
 *               divided by total in-band power. Callers gate on this to reject noise.
 */
data class BpmEstimate(val bpm: Float, val snr: Float)

/**
 * Spectral heart-rate estimator for a uniformly-sampled 1-D pulse signal.
 *
 * Pipeline (all dependency-free, kotlin.math only):
 *   1. Linear detrend -- subtract the least-squares fitted line (slope + intercept),
 *      not just the mean, to remove slow illumination/motion drift.
 *   2. Hann window -- reduce spectral leakage from the finite window.
 *   3. Zero-pad to nfft (>= 2048, next power of two) and run an in-file radix-2
 *      Cooley-Tukey FFT; compute the one-sided magnitude spectrum.
 *   4. Band-limited peak search in [0.7, 4] Hz (42-240 bpm). A true time-domain
 *      bandpass filter is unnecessary: restricting the spectral peak search to the
 *      heart-rate band is equivalent for picking the dominant frequency and is
 *      simpler. This is the documented design choice.
 *   5. Parabolic (quadratic) interpolation across the peak bin and its two
 *      neighbours for sub-bin frequency accuracy.
 *   6. Harmonic disambiguation -- if a strong peak sits at ~half the detected
 *      frequency and is still in-band, prefer that lower fundamental (a strong 2nd
 *      harmonic must not be mistaken for the heart rate).
 *   7. snr -- power within +/-0.1 Hz of the chosen peak over total [0.7, 4] Hz power.
 *
 * This object does spectral estimation only: no resampling (Resampler), no POS/CHROM
 * (RppgSignal), no temporal smoothing.
 */
object SpectralBpm {

    private const val MIN_HZ = 0.7f
    private const val MAX_HZ = 4.0f
    private const val MIN_FUNDAMENTAL_HZ = 0.58f // ~35 bpm: lowest half-frequency we accept
    private const val SNR_HALF_BAND_HZ = 0.1f
    private const val HARMONIC_POWER_FRACTION = 0.5f
    private const val MIN_BPM = 42f
    private const val MAX_BPM = 240f
    private const val MIN_NFFT = 2048

    /**
     * Estimate heart rate from a uniformly-sampled pulse [signal] taken at [fps] Hz.
     *
     * @return [BpmEstimate]; (0, 0) for empty/too-short input or non-positive fps.
     */
    fun estimate(signal: FloatArray, fps: Float): BpmEstimate {
        if (fps <= 0f || signal.size < 4) return BpmEstimate(0f, 0f)

        val detrended = linearDetrend(signal)
        applyHann(detrended)

        val nfft = nextPow2(maxOf(MIN_NFFT, detrended.size))
        val mag = magnitudeSpectrum(detrended, nfft)

        val binHz = fps / nfft
        val loBin = maxOf(1, Math.ceil((MIN_HZ / binHz).toDouble()).toInt())
        val hiBin = minOf(mag.size - 2, (MAX_HZ / binHz).toInt())
        if (loBin > hiBin) return BpmEstimate(0f, 0f)

        // Power spectrum within the band.
        val power = FloatArray(mag.size) { mag[it] * mag[it] }

        // Dominant peak in band.
        var peakBin = loBin
        for (k in loBin..hiBin) {
            if (power[k] > power[peakBin]) peakBin = k
        }

        var refinedHz = parabolicPeakHz(mag, peakBin, binHz)
        var chosenBin = peakBin

        // Harmonic disambiguation: look for a strong peak near half the detected freq.
        val halfHz = refinedHz / 2f
        if (halfHz >= MIN_FUNDAMENTAL_HZ) {
            val halfBin = Math.round(halfHz / binHz)
            val subBin = localPeakBin(power, halfBin, loBin, hiBin, binHz)
            if (subBin != null && power[subBin] > HARMONIC_POWER_FRACTION * power[peakBin]) {
                chosenBin = subBin
                refinedHz = parabolicPeakHz(mag, subBin, binHz)
            }
        }

        val bpm = (refinedHz * 60f).coerceIn(MIN_BPM, MAX_BPM)
        val snr = bandSnr(power, chosenBin, binHz, loBin, hiBin)
        return BpmEstimate(bpm, snr)
    }

    /** Subtract the least-squares line fitted over sample index. */
    private fun linearDetrend(x: FloatArray): FloatArray {
        val n = x.size
        val nd = n.toDouble()
        var sumX = 0.0
        var sumY = 0.0
        var sumXX = 0.0
        var sumXY = 0.0
        for (i in 0 until n) {
            val xi = i.toDouble()
            val yi = x[i].toDouble()
            sumX += xi
            sumY += yi
            sumXX += xi * xi
            sumXY += xi * yi
        }
        val denom = nd * sumXX - sumX * sumX
        val slope = if (denom == 0.0) 0.0 else (nd * sumXY - sumX * sumY) / denom
        val intercept = (sumY - slope * sumX) / nd
        return FloatArray(n) { i -> (x[i] - (slope * i + intercept)).toFloat() }
    }

    /** Apply a Hann window in place. */
    private fun applyHann(x: FloatArray) {
        val n = x.size
        if (n < 2) return
        for (i in 0 until n) {
            val w = 0.5 * (1.0 - cos(2.0 * PI * i / (n - 1)))
            x[i] = (x[i] * w).toFloat()
        }
    }

    /**
     * One-sided magnitude spectrum of real input [x] zero-padded to [nfft]
     * (a power of two). Returns nfft/2 + 1 bins.
     */
    private fun magnitudeSpectrum(x: FloatArray, nfft: Int): FloatArray {
        val re = DoubleArray(nfft)
        val im = DoubleArray(nfft)
        for (i in x.indices) re[i] = x[i].toDouble()
        fft(re, im)
        val half = nfft / 2
        return FloatArray(half + 1) { k -> sqrt(re[k] * re[k] + im[k] * im[k]).toFloat() }
    }

    /** Test-only hook exposing the internal radix-2 FFT magnitude for verification. */
    internal fun magnitudeSpectrumForTest(x: FloatArray, nfft: Int): FloatArray =
        magnitudeSpectrum(x, nfft)

    /**
     * In-place iterative radix-2 Cooley-Tukey FFT. [re]/[im] length must be a power of two.
     */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        if (n <= 1) return

        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang)
            val wIm = kotlin.math.sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = i + k + len / 2
                    val tRe = re[b] * curRe - im[b] * curIm
                    val tIm = re[b] * curIm + im[b] * curRe
                    re[b] = re[a] - tRe
                    im[b] = im[a] - tIm
                    re[a] += tRe
                    im[a] += tIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Quadratic interpolation of the peak frequency from magnitude bin [peak] +/- 1. */
    private fun parabolicPeakHz(mag: FloatArray, peak: Int, binHz: Float): Float {
        if (peak <= 0 || peak >= mag.size - 1) return peak * binHz
        val a = mag[peak - 1].toDouble()
        val b = mag[peak].toDouble()
        val c = mag[peak + 1].toDouble()
        val denom = a - 2.0 * b + c
        val delta = if (denom == 0.0) 0.0 else 0.5 * (a - c) / denom
        return ((peak + delta) * binHz).toFloat()
    }

    /** Find the local power-peak bin near [center], constrained to [lo, hi]. */
    private fun localPeakBin(
        power: FloatArray,
        center: Int,
        lo: Int,
        hi: Int,
        binHz: Float
    ): Int? {
        val search = maxOf(1, Math.round(0.1f / binHz)) // +/-0.1 Hz around the half-frequency
        val from = maxOf(lo, center - search)
        val to = minOf(hi, center + search)
        if (from > to) return null
        var best = from
        for (k in from..to) if (power[k] > power[best]) best = k
        return best
    }

    /** Power within +/-0.1 Hz of the [peak] bin over total in-band power, in [0, 1]. */
    private fun bandSnr(
        power: FloatArray,
        peak: Int,
        binHz: Float,
        lo: Int,
        hi: Int
    ): Float {
        val half = maxOf(1, Math.round(SNR_HALF_BAND_HZ / binHz))
        var peakPower = 0.0
        val pFrom = maxOf(lo, peak - half)
        val pTo = minOf(hi, peak + half)
        for (k in pFrom..pTo) peakPower += power[k]
        var total = 0.0
        for (k in lo..hi) total += power[k]
        if (total <= 0.0) return 0f
        return (peakPower / total).toFloat().coerceIn(0f, 1f)
    }

    private fun nextPow2(v: Int): Int {
        var p = 1
        while (p < v) p = p shl 1
        return p
    }
}
