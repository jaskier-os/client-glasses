# Night Vision / Low-Light Enhancement Research

Research compiled for Rokid AR glasses camera: max 100ms exposure, max ISO 1600, YUV_420_888 at 640x480, monochrome green output on waveguide display.

Current baseline: EMA accumulation alpha=0.12 (~8 frame window), 6x post-gain.

---

## 1. Accumulation Window: Go Much Deeper

### What Google Night Sight Does
- **Handheld**: 15 frames at 48-333ms per frame (total 1-6 seconds of light collection)
- **Tripod detected**: Up to 1 second per frame, 15 frames = 15 seconds total
- **Astrophotography mode** (Pixel 4+): Up to 16 seconds per frame, ~15 frames = 4 minutes total
- HDR+ base mode: 9-15 frames at short exposures

### SNR Improvement Math
- SNR improves proportional to sqrt(N) where N = number of frames averaged
- 8 frames (current): sqrt(8) = 2.83x noise reduction
- 16 frames: sqrt(16) = 4x noise reduction
- 32 frames: sqrt(32) = 5.66x noise reduction
- 64 frames: sqrt(64) = 8x noise reduction
- 128 frames: sqrt(128) = 11.3x noise reduction

### Recommended EMA Alpha Values
Current alpha=0.12 gives ~8-frame effective window (2/alpha - 1).

| Alpha | Effective Window | SNR Gain | Latency at 10fps |
|-------|-----------------|----------|------------------|
| 0.12  | ~16 frames      | 4.0x     | 1.6s             |
| 0.06  | ~32 frames      | 5.7x     | 3.2s             |
| 0.04  | ~49 frames      | 7.0x     | 4.9s             |
| 0.03  | ~65 frames      | 8.1x     | 6.5s             |
| 0.02  | ~99 frames      | 9.9x     | 9.9s             |

NOTE: EMA effective window = 2/alpha - 1 (where 86.5% of weight is concentrated).

**Recommendation**: Start with alpha=0.04 (~50 frame window, 7x SNR gain). This gives a 5-second ramp-up time at 10fps which is acceptable for a continuously-running night vision viewfinder. The user sees progressive improvement as the accumulator "warms up."

**Practical limit**: Beyond ~100 frames (alpha=0.02), returns diminish rapidly and motion ghosting becomes severe. 30-64 frames is the sweet spot for a head-mounted moving camera.

---

## 2. Additive Accumulation Into Wide Buffers (THE BIG WIN)

### The Problem With EMA in 8-bit
EMA averaging keeps values in [0, 255]. If a pixel receives 2 photons per frame (value ~2), the EMA converges to ~2. You then multiply by gain 6x to get 12. Still barely visible.

### The Solution: Accumulate in 32-bit, Then Tone-Map

```
Instead of:  accumulated = alpha * newFrame + (1-alpha) * accumulated  (8-bit)
Do this:     accumulator[i] += newFrame[i]    (32-bit integer array)
             frameCount++
```

After N frames, accumulator[i] holds the SUM of all pixel values. For a pixel that averages 2 per frame over 64 frames, the sum is 128. Over 128 frames, the sum is 256.

**This is fundamentally different from averaging** -- you are collecting more photons (signal) while noise grows only as sqrt(N).

### Tone Mapping the Wide Buffer
After accumulation, you need to map the wide-range values back to displayable [0, 255]:

```kotlin
// Option A: Linear scaling with auto-range
val maxVal = accumulator.max()
val scale = 255.0 / maxVal
displayPixel = (accumulator[i] * scale).coerceIn(0, 255)

// Option B: Gamma curve (better for night vision -- lifts shadows)
val normalized = accumulator[i].toDouble() / maxVal
val gamma = 0.4  // < 1.0 lifts dark values, 0.3-0.5 recommended
displayPixel = (255.0 * normalized.pow(gamma)).toInt().coerceIn(0, 255)

// Option C: Logarithmic mapping (best for extreme dynamic range)
val displayPixel = (255.0 * ln(1.0 + accumulator[i].toDouble()) / ln(1.0 + maxVal)).toInt()
```

### Sliding Window Accumulation (for continuous viewfinder)
Instead of infinite accumulation, use a ring buffer of N frames:

```kotlin
// Ring buffer approach
val WINDOW_SIZE = 64
val frameBuffer = Array(WINDOW_SIZE) { IntArray(width * height) }
val accumulator = IntArray(width * height)  // running sum
var bufferIndex = 0

fun addFrame(newY: ByteArray) {
    val oldFrame = frameBuffer[bufferIndex]
    for (i in accumulator.indices) {
        val newVal = newY[i].toInt() and 0xFF
        accumulator[i] += newVal - oldFrame[i]  // add new, subtract oldest
        oldFrame[i] = newVal
    }
    bufferIndex = (bufferIndex + 1) % WINDOW_SIZE
}
```

Memory cost for 640x480 at 64 frames: 640 * 480 * 64 = ~19.7 MB (very manageable).

**Recommendation**: This is the single highest-impact change. Switch from EMA to sliding-window additive accumulation with 32-64 frame window and gamma tone mapping.

---

## 3. Gain Strategy: Post-Accumulation Amplification

### Current: 6x gain on averaged signal
This amplifies both signal AND remaining noise equally.

### Better: Gain AFTER accumulation + noise reduction

The key insight: if you accumulate N frames additively, your effective signal is already N times stronger. You only need gain to compensate for the remaining gap to full brightness.

### Practical Gain Limits

| Gain | Use Case | Notes |
|------|----------|-------|
| 1-6x | After 8-frame average | Current setup, noisy |
| 6-15x | After 32-frame accumulation | Clean enough, noise reduced by 5.7x |
| 15-30x | After 64-frame accumulation | Noise reduced by 8x, 30x gain viable |
| 30-50x | After 128-frame accumulation | Push limit, will need CLAHE to control |

**Recommendation**: With 64-frame sliding window accumulation:
1. Sum all 64 frames (max possible sum = 64 * 255 = 16320)
2. Apply gamma tone mapping (gamma=0.4) to stretch dark values
3. Apply 2-4x additional gain if needed
4. Clamp to [0, 255]

This replaces 6x-on-weak-signal with accumulation-that-makes-signal-strong + mild-gain. Net effect: dramatically brighter AND cleaner.

---

## 4. CLAHE (Contrast Limited Adaptive Histogram Equalization)

### Why It Matters
Even after accumulation and gain, night scenes have compressed histograms where most pixels cluster near zero. CLAHE redistributes contrast locally, making dark detail visible without blowing highlights.

### Implementation Without External Libraries

```kotlin
// CLAHE implementation for single-channel (Y plane) image
fun applyCLAHE(
    image: ByteArray,        // input Y plane
    width: Int,
    height: Int,
    tileWidth: Int = 80,     // 640/8 = 80 pixels per tile
    tileHeight: Int = 60,    // 480/8 = 60 pixels per tile
    clipLimit: Double = 3.0  // amplification limit (2.0-4.0 range)
): ByteArray {
    val tilesX = width / tileWidth    // 8 tiles
    val tilesY = height / tileHeight  // 8 tiles
    val tilePixels = tileWidth * tileHeight
    val clipCount = (clipLimit * tilePixels / 256).toInt()

    // Step 1: Compute clipped histogram + CDF for each tile
    val cdfs = Array(tilesY) { ty ->
        Array(tilesX) { tx ->
            // Build histogram for this tile
            val hist = IntArray(256)
            for (y in 0 until tileHeight) {
                for (x in 0 until tileWidth) {
                    val px = image[(ty * tileHeight + y) * width + (tx * tileWidth + x)].toInt() and 0xFF
                    hist[px]++
                }
            }

            // Clip histogram and redistribute excess
            var excess = 0
            for (i in 0 until 256) {
                if (hist[i] > clipCount) {
                    excess += hist[i] - clipCount
                    hist[i] = clipCount
                }
            }
            val redistribute = excess / 256
            for (i in 0 until 256) hist[i] += redistribute

            // Build CDF (cumulative distribution function)
            val cdf = IntArray(256)
            cdf[0] = hist[0]
            for (i in 1 until 256) cdf[i] = cdf[i - 1] + hist[i]

            // Normalize CDF to [0, 255]
            val cdfMin = cdf.first { it > 0 }
            IntArray(256) { i ->
                ((cdf[i] - cdfMin).toDouble() / (tilePixels - cdfMin) * 255).toInt().coerceIn(0, 255)
            }
        }
    }

    // Step 2: Apply with bilinear interpolation between tile CDFs
    val output = ByteArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val px = image[y * width + x].toInt() and 0xFF

            // Find surrounding tile centers
            val tx = ((x.toDouble() / tileWidth) - 0.5).coerceIn(0.0, (tilesX - 1).toDouble())
            val ty = ((y.toDouble() / tileHeight) - 0.5).coerceIn(0.0, (tilesY - 1).toDouble())
            val tx0 = tx.toInt().coerceIn(0, tilesX - 2)
            val ty0 = ty.toInt().coerceIn(0, tilesY - 2)
            val fx = tx - tx0
            val fy = ty - ty0

            // Bilinear interpolation of 4 surrounding tile mappings
            val v00 = cdfs[ty0][tx0][px]
            val v10 = cdfs[ty0][tx0 + 1][px]
            val v01 = cdfs[ty0 + 1][tx0][px]
            val v11 = cdfs[ty0 + 1][tx0 + 1][px]

            val result = ((1 - fy) * ((1 - fx) * v00 + fx * v10) +
                          fy * ((1 - fx) * v01 + fx * v11)).toInt()
            output[y * width + x] = result.coerceIn(0, 255).toByte()
        }
    }
    return output
}
```

### Recommended Parameters
- **Tile grid**: 8x8 tiles (80x60 pixels each at 640x480) -- standard choice, good balance
- **Clip limit**: 3.0-4.0 for night vision (higher = more contrast amplification, more noise)
  - 2.0: conservative, good when accumulation already provides brightness
  - 3.0: recommended starting point
  - 4.0: aggressive, useful in extremely dark scenes
  - 6.0+: too noisy, avoid
- **Apply AFTER accumulation + tone mapping**, not before

### Performance
At 640x480 with 8x8 tiles: ~307K pixels, 64 histogram builds of ~4800 pixels each. Very fast on ARM -- under 5ms easily. The bilinear interpolation pass is the bottleneck but still sub-10ms at this resolution.

---

## 5. Temporal Noise Reduction: Beyond Simple EMA

### 5a. Weighted Temporal Averaging With Motion Detection

Simple EMA treats all pixels equally. Better approach: detect motion per-pixel and adjust blending.

```kotlin
// Per-pixel adaptive temporal filter
val MOTION_THRESHOLD = 15  // pixel difference threshold
val ALPHA_STATIC = 0.03f   // slow blend for static pixels (more averaging)
val ALPHA_MOVING = 0.5f    // fast blend for moving pixels (less ghosting)

fun adaptiveTemporalFilter(
    newFrame: ByteArray,
    accumulated: FloatArray,  // persistent state
    output: ByteArray,
    width: Int, height: Int
) {
    for (i in newFrame.indices) {
        val newVal = newFrame[i].toInt() and 0xFF
        val oldVal = accumulated[i]
        val diff = abs(newVal - oldVal)

        val alpha = if (diff > MOTION_THRESHOLD) ALPHA_MOVING else ALPHA_STATIC
        accumulated[i] = alpha * newVal + (1 - alpha) * oldVal
        output[i] = accumulated[i].toInt().coerceIn(0, 255).toByte()
    }
}
```

This gives ~33-frame averaging for static background (strong noise reduction) while tracking motion with only ~2-frame delay.

### 5b. Temporal Median Filter (3-5 frames)

Instead of averaging, take the median of the last 3-5 frames per pixel. This eliminates hot pixels and impulse noise completely without any blurring.

```kotlin
// 5-frame temporal median
val MEDIAN_WINDOW = 5
val recentFrames = Array(MEDIAN_WINDOW) { ByteArray(width * height) }
var frameIdx = 0

fun temporalMedian(newFrame: ByteArray, output: ByteArray) {
    System.arraycopy(newFrame, 0, recentFrames[frameIdx], 0, newFrame.size)
    frameIdx = (frameIdx + 1) % MEDIAN_WINDOW

    for (i in output.indices) {
        val values = IntArray(MEDIAN_WINDOW) { recentFrames[it][i].toInt() and 0xFF }
        values.sort()
        output[i] = values[MEDIAN_WINDOW / 2].toByte()
    }
}
```

### 5c. Recommended Combined Pipeline

```
Raw Frame -> Temporal Median (5 frames, removes hot pixels/impulse noise)
          -> Additive Accumulation (64-frame sliding window)
          -> Gamma Tone Map (gamma=0.4)
          -> CLAHE (8x8 tiles, clip=3.0)
          -> Optional mild gain (2-4x if still too dark)
          -> Clamp to [0, 255]
          -> Green channel output
```

---

## 6. Camera2 API Tricks for Extreme Low Light

### 6a. Disable On-Sensor Noise Reduction

```kotlin
captureRequestBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE,
    CameraMetadata.NOISE_REDUCTION_MODE_OFF)
```

Why: The sensor's built-in NR is tuned for normal photography and may clip faint signals. You want the raw noisy data -- YOUR accumulation pipeline does the denoising better for this use case.

### 6b. Disable Hot Pixel Correction (carefully)

```kotlin
captureRequestBuilder.set(CaptureRequest.HOT_PIXEL_MODE,
    CameraMetadata.HOT_PIXEL_MODE_OFF)
```

Why: Hot pixel correction can suppress faint real signals in very dark images. Your temporal median filter handles hot pixels better. BUT: test this -- some sensors have terrible hot pixels that are better handled in hardware.

### 6c. Maximize Frame Duration

```kotlin
// Set frame duration to maximum -- allows sensor to expose longer
val maxFrameDuration = characteristics.get(
    CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION
)
captureRequestBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, maxFrameDuration)
```

This tells the sensor it can take as long as it needs per frame. Combined with max exposure (100ms), this eliminates any artificial frame rate limiting.

### 6d. Full Manual Control

```kotlin
// Disable all auto-everything
captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
captureRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)

// Max exposure
captureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 100_000_000L) // 100ms in nanoseconds

// Max ISO
captureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, 1600)

// Focus at infinity (for AR glasses use case)
captureRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)
```

### 6e. Low Light Boost (API 35+, Android 15+)

```kotlin
// Check if available
val aeAvailableModes = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
if (aeAvailableModes?.contains(CameraMetadata.CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY) == true) {
    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
        CameraMetadata.CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY)
}
```

This is a new API that applies additional brightness boost in low light. May not be available on Rokid hardware but worth checking.

### 6f. Black Level Subtraction

```kotlin
// Read black level pattern from characteristics
val blackLevel = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
// Also check per-frame dynamic black level from capture results:
// CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL

// Subtract black level from Y values before accumulation
// This removes the sensor's dark current offset, giving you more usable range
fun subtractBlackLevel(frame: ByteArray, blackLevel: Int): ByteArray {
    return ByteArray(frame.size) { i ->
        maxOf(0, (frame[i].toInt() and 0xFF) - blackLevel).toByte()
    }
}
```

Note: For YUV_420_888, the Y plane black level is typically 16 (video range) not 0. Subtracting this floor gives ~15% more usable dynamic range in dark scenes.

### 6g. SENSOR_PIXEL_MODE for Binning

```kotlin
// Check if sensor supports pixel binning modes
val pixelModes = characteristics.get(CameraCharacteristics.SENSOR_INFO_BINNING_FACTOR)
// If available, DEFAULT mode typically uses binned (lower-res, more light) readout
// MAXIMUM_RESOLUTION mode uses full unbinned readout
captureRequestBuilder.set(CaptureRequest.SENSOR_PIXEL_MODE,
    CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT)
```

At 640x480, you are likely already getting a binned readout from a higher-resolution sensor. The sensor physically combines 2x2 or 4x4 pixel groups, which inherently provides 4x or 16x more photons per output pixel.

---

## 7. Pixel Binning: Software-Side

Even if the sensor doesn't support hardware binning modes, you can do it in software:

```kotlin
// 2x2 software binning: 640x480 -> 320x240
// Each output pixel = average of 4 input pixels = 2x SNR improvement
fun softwareBin2x2(input: ByteArray, inWidth: Int, inHeight: Int): ByteArray {
    val outWidth = inWidth / 2
    val outHeight = inHeight / 2
    val output = ByteArray(outWidth * outHeight)

    for (y in 0 until outHeight) {
        for (x in 0 until outWidth) {
            val sum = (input[(y*2) * inWidth + (x*2)].toInt() and 0xFF) +
                      (input[(y*2) * inWidth + (x*2+1)].toInt() and 0xFF) +
                      (input[(y*2+1) * inWidth + (x*2)].toInt() and 0xFF) +
                      (input[(y*2+1) * inWidth + (x*2+1)].toInt() and 0xFF)
            output[y * outWidth + x] = (sum / 4).toByte()
        }
    }
    return output
}
```

2x2 binning: 320x240 output, 2x SNR improvement (sqrt(4) = 2).
4x4 binning: 160x120 output, 4x SNR improvement (sqrt(16) = 4).

**Recommendation**: For a waveguide display at 640x480, consider working internally at 320x240 (2x2 binned) and upscaling for display. The waveguide display resolution is likely low enough that this is invisible, and you get a free 2x SNR boost plus 4x less data to process in the accumulation pipeline.

Alternatively, you can request a lower resolution from the sensor directly:
```kotlin
// Request 320x240 instead of 640x480 from Camera2
// The sensor may use larger binning internally, giving more photons per pixel
val imageReader = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 4)
```

This can be more effective than software binning because the sensor may use true analog binning which combines charge before readout, avoiding the read noise penalty.

---

## 8. What Real Night Vision Apps Do

Based on research of available Android night vision / low-light camera apps:

### Common Techniques (implementable)
1. **Long exposure simulation via frame stacking** -- exactly what we're discussing
2. **Aggressive gamma correction** (gamma 0.3-0.5) -- lifts dark values dramatically
3. **Green monochrome colorization** -- you already do this (matches real NVG aesthetic)
4. **Histogram stretching** -- find the actual min/max in the image, stretch to full range
5. **Edge enhancement after denoising** -- mild unsharp mask to recover sharpness lost in averaging
6. **Auto-gain with safety clamp** -- measure average brightness, compute gain to reach target brightness (~100-120 for NVG look), clamp at max gain

### Auto-Gain Algorithm

```kotlin
// Automatic gain targeting specific mean brightness
val TARGET_BRIGHTNESS = 110  // typical NVG look (0-255)
val MAX_GAIN = 50.0
val MIN_GAIN = 1.0

fun computeAutoGain(frame: ByteArray): Double {
    var sum = 0L
    for (b in frame) sum += (b.toInt() and 0xFF)
    val mean = sum.toDouble() / frame.size
    if (mean < 1.0) return MAX_GAIN  // extremely dark, use max
    val gain = (TARGET_BRIGHTNESS / mean).coerceIn(MIN_GAIN, MAX_GAIN)
    return gain
}
```

### Unsharp Mask (Simple Edge Enhancement)

```kotlin
// After accumulation + CLAHE, sharpen with a 3x3 unsharp mask
// This recovers edges softened by temporal averaging
fun unsharpMask(input: ByteArray, width: Int, height: Int, amount: Float = 0.5f): ByteArray {
    val output = ByteArray(input.size)
    for (y in 1 until height - 1) {
        for (x in 1 until width - 1) {
            val center = input[y * width + x].toInt() and 0xFF
            // Simple box blur of neighbors
            var blur = 0
            for (dy in -1..1) for (dx in -1..1) {
                blur += input[(y + dy) * width + (x + dx)].toInt() and 0xFF
            }
            blur /= 9
            // Unsharp: original + amount * (original - blur)
            val sharpened = center + (amount * (center - blur)).toInt()
            output[y * width + x] = sharpened.coerceIn(0, 255).toByte()
        }
    }
    return output
}
```

---

## 9. References and Further Reading

### Key Papers
1. **"Burst photography for high dynamic range and low-light imaging on mobile cameras"** (Hasinoff et al., 2016) -- The HDR+ paper. Foundational work on burst capture, alignment, and Wiener merging on mobile devices. Available via Google Research.

2. **"Learning to See in the Dark"** (Chen et al., CVPR 2018, arXiv:1805.01934) -- End-to-end CNN for extreme low-light, operates on raw sensor data. Demonstrates that >100x amplification is possible with learned denoising. Not directly usable without a model, but the amplification factors are informative.

3. **"Handheld Multi-Frame Super-Resolution"** (Wronski et al., SIGGRAPH 2019) -- Google's Super Res Zoom, also used in Night Sight on Pixel 3+. Shows how multi-frame alignment can improve both resolution and SNR.

4. **"Rendering Nighttime Image Via Cascaded Color and Brightness Compensation"** (Li et al., NTIRE/CVPR 2022, arXiv:2204.08970) -- Two-stage ISP for nighttime: first color correction, then brightness compensation. Ranked #2 in NTIRE Night Photography Challenge.

### Key Blog Posts
5. **Google AI Blog: "Night Sight: Seeing in the Dark on Pixel Phones"** (Nov 2018) -- Most detailed public description of Night Sight's pipeline: motion metering, adaptive exposure (48-333ms), 15-frame capture, alignment/merging, learning-based AWB, tone mapping.

6. **Cambridge in Colour: "Noise Reduction by Image Averaging"** -- Excellent visual explanation of sqrt(N) noise reduction, practical comparison with commercial denoising, bit depth improvements from averaging.

### Camera2 API Keys (Most Relevant)
7. `SENSOR_EXPOSURE_TIME` -- Nanoseconds, set to max (100ms = 100,000,000)
8. `SENSOR_SENSITIVITY` -- ISO, set to max (1600)
9. `SENSOR_FRAME_DURATION` -- Nanoseconds, set to max to allow full exposure
10. `NOISE_REDUCTION_MODE` -- Set to OFF for custom pipeline
11. `HOT_PIXEL_MODE` -- Consider OFF with temporal median fallback
12. `SENSOR_BLACK_LEVEL_PATTERN` / `SENSOR_DYNAMIC_BLACK_LEVEL` -- Subtract for more range
13. `SENSOR_INFO_BINNING_FACTOR` -- Check sensor's native binning
14. `CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY` -- API 35+ boost mode

---

## 10. Recommended Implementation Priority

Ordered by expected impact (highest first):

### Phase 1: Immediate (Biggest Gains)
1. **Switch from EMA to sliding-window additive accumulation** (Section 2)
   - 64-frame ring buffer, 32-bit integer accumulator
   - Gamma tone mapping (gamma=0.4) instead of linear gain
   - Expected: 5-10x brightness improvement over current EMA+6x gain

2. **Subtract black level before accumulation** (Section 6f)
   - Y plane video range floor is 16; subtract it
   - Expected: ~15% more usable signal

3. **Auto-gain with target brightness** (Section 8)
   - Replace fixed 6x gain with adaptive gain targeting mean=110
   - Expected: consistent brightness across varying light levels

### Phase 2: Quality Enhancement
4. **CLAHE post-processing** (Section 4)
   - 8x8 tiles, clip limit 3.0
   - Apply after tone mapping, before gain
   - Expected: dramatically better contrast and detail visibility

5. **Adaptive temporal filter with motion detection** (Section 5a)
   - Different alpha for static vs moving pixels
   - Expected: sharp moving objects + strong denoising on background

6. **Camera2 manual mode** (Section 6d)
   - Disable auto-exposure, auto-NR, auto-AWB
   - Fix exposure=100ms, ISO=1600, NR=OFF
   - Expected: consistent raw data, no auto-exposure fighting your pipeline

### Phase 3: Polish
7. **Software 2x2 binning** (Section 7)
   - Process at 320x240, upscale for display
   - Expected: 2x SNR improvement, 4x faster processing

8. **Unsharp mask** (Section 8)
   - Light sharpening after all denoising/averaging
   - Expected: recovered edge sharpness

9. **Temporal median pre-filter** (Section 5b)
   - 5-frame median before accumulation
   - Expected: eliminates hot pixels and impulse noise

### Expected Total Improvement
Combining Phase 1 + Phase 2:
- 64-frame accumulation: ~8x SNR improvement over single frame
- Gamma tone mapping: ~3-5x perceived brightness lift in dark regions
- CLAHE: ~2-3x local contrast improvement
- Auto-gain: adapts to hit target brightness
- Combined with max exposure (100ms) and max ISO (1600)

**Conservative estimate**: 20-40x improvement in perceived brightness and usability over current EMA+6x setup. In extreme dark, the scene should go from "black with noise" to "clearly visible green NVG-style imagery."
