# Head-locked Overlay Mockup Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans / subagent-driven-development to implement this plan task-by-task.

**Goal:** A standalone glasses test app that draws a mock AR UI larger than the FOV; main content stays in front, panels at up to +/-90 deg are discovered by turning the head, and content re-centers when the body settles on a new heading.

**Architecture:** New nested Gradle module `AI/clients/glasses/headlock-overlay-test/`, modeled on the proven `touchpad-test-app`. Plain Android Views + one custom Canvas `OverlayView`. Ported pure-Kotlin quaternion/gyro math from GlassesBareDevSample drives a `HeadOrientationTracker` (gyro yaw + accel-corrected pitch/roll). A `LazyFollowReference` converts head pose into a viewport offset with a deadzone + slow follow. Panels are placed at fixed angular coords and rendered via a single deg->px factor from a tunable FOV constant.

**Tech Stack:** Kotlin, Android SDK 34, plain Views + Canvas (no Compose), SensorManager, JUnit for pure-math unit tests, UiAutomator2 for the instrumented render test.

**Key references:**
- Model module: `AI/clients/glasses/touchpad-test-app/` (settings/gradle/manifest conventions).
- Sensor source to port: `/tmp/glasses_sample/GlassesBareDevSample/app/src/main/java/com/rokid/glassesbaredevsample/sensor/{Quaternion,GyroQuaternionIntegrator,HeadPose,SixAxisReading,GyroAxis}.kt`.
- Deploy model: `Recon/scripts/deploy-design-preview-to-glasses.sh`.
- Design doc: `docs/plans/2026-07-12-headlocked-overlay-mockup-design.md`.

Module root below is `AI/clients/glasses/headlock-overlay-test/`. Package `com.repository.glasses.headlockoverlay`. Source dir `app/src/main/java/com/repository/glasses/headlockoverlay/`.

---

### Task 1: Module scaffold that builds

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradlew`, `gradlew.bat`, `gradle/wrapper/*` (copy from touchpad-test-app), `local.properties` (copy, points at same SDK).
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/{themes.xml,strings.xml}`, `app/src/main/java/com/repository/glasses/headlockoverlay/MainActivity.kt`.

**Step 1:** Copy the touchpad-test-app gradle scaffolding (wrapper, settings, gradle.properties, local.properties, gradlew). Set `rootProject.name = "HeadlockOverlay"`.

**Step 2:** Write `app/build.gradle.kts`: same structure as touchpad-test-app (compileSdk 34, minSdk 28, targetSdk 34, arm64-v8a, Java 11), namespace/applicationId `com.repository.glasses.headlockoverlay`. Dependencies: `androidx.core:core-ktx:1.12.0`, `androidx.appcompat:appcompat:1.6.1`.

**Step 3:** Manifest: single landscape `singleTask` `.MainActivity`, exported, LAUNCHER intent. No permissions (IMU needs none). Theme `Theme.HeadlockOverlay` (copy touchpad theme, fullscreen/no action bar). Add `android:label="HeadlockOverlay"`.

**Step 4:** `MainActivity.kt`: minimal `AppCompatActivity` that setsContentView to an empty `FrameLayout` for now (placeholder). Immersive fullscreen (hide system bars).

**Step 5:** Build. Run: `cd AI/clients/glasses/headlock-overlay-test && ./gradlew :app:assembleDebug`. Expected: BUILD SUCCESSFUL, apk at `app/build/outputs/apk/debug/app-debug.apk`.

**Step 6:** Commit `feat: scaffold headlock-overlay-test module`.

---

### Task 2: Port + unit-test the quaternion/gyro math

**Files:**
- Create: `app/src/main/java/.../sensor/Quaternion.kt` (port verbatim from sample).
- Create: `app/src/main/java/.../sensor/GyroQuaternionIntegrator.kt` (port verbatim).
- Create: `app/src/main/java/.../math/AngleMath.kt` — `wrap180(deg: Float): Float`.
- Test: `app/src/test/java/.../QuaternionTest.kt`, `AngleMathTest.kt`.

**Step 1 (failing test):** `AngleMathTest`: `wrap180(190f) == -170f`, `wrap180(-190f) == 170f`, `wrap180(10f) == 10f` (within 1e-3). `QuaternionTest`: integrating a constant +1.0 rad/s about Z for 1s (100 steps of 10ms) yields yaw ~= +57.29 deg (within 1 deg) via `toEulerRadians`.

**Step 2:** Run `./gradlew :app:testDebugUnitTest`. Expected: FAIL (classes not defined).

**Step 3:** Port `Quaternion.kt` and `GyroQuaternionIntegrator.kt` from the sample verbatim (adjust package). Implement `wrap180`.

**Step 4:** Run `./gradlew :app:testDebugUnitTest`. Expected: PASS.

**Step 5:** Commit `feat: port quaternion/gyro integration math with tests`.

---

### Task 3: LazyFollowReference (pure, unit-tested) — the core behavior

**Files:**
- Create: `app/src/main/java/.../orientation/LazyFollowReference.kt`.
- Test: `app/src/test/java/.../LazyFollowReferenceTest.kt`.

**API:**
```kotlin
class LazyFollowReference(var followRate: Float = 0.6f, var deadzoneDeg: Float = 6f) {
    var refYaw = 0f; private set
    var refPitch = 0f; private set
    data class Offset(val yawDeg: Float, val pitchDeg: Float) // viewport offset = -(head-ref), deadzoned
    fun update(headYaw: Float, headPitch: Float, dt: Float): Offset
    fun recenter(headYaw: Float, headPitch: Float) // snap ref = head
}
```
Logic per design: `err = wrap180(head-ref)`; drawErr = 0 if |err|<deadzone else err; offset = -drawErr; `ref += followRate*err*dt` (use raw err, not deadzoned, so it still follows slowly inside deadzone — actually follow only outside deadzone to keep center rock-stable: `if(|err|>=deadzone) ref += followRate*err*dt`). Use the outside-deadzone-follow variant.

**Step 1 (failing tests):**
- Within deadzone: `update(3f,0f,0.016f).yawDeg == 0f` and refYaw stays 0.
- Reveal: `update(30f,0f,0.016f).yawDeg == -30f` (approx, first frame before follow moves much).
- Re-center on settle: feed `update(90f,0f,0.1f)` repeatedly for many frames (simulate holding 90 deg); assert offset magnitude decays toward 0 and refYaw approaches 90.
- `recenter(45f,0f)` makes refYaw==45 and next `update(45f,0f,dt).yawDeg==0`.

**Step 2:** Run unit tests. Expected: FAIL.

**Step 3:** Implement.

**Step 4:** Run. Expected: PASS.

**Step 5:** Commit `feat: lazy-follow reference with deadzone + slow follow`.

---

### Task 4: HeadOrientationTracker (device + injectable seam)

**Files:**
- Create: `app/src/main/java/.../sensor/HeadOrientationTracker.kt`.
- Test: `app/src/test/java/.../HeadOrientationTrackerTest.kt`.

**Design:** Not coroutine-based. Registers gyro+accel listeners; on gyro sample integrates via `GyroQuaternionIntegrator`; maintains yaw from gyro, and blends accel-derived pitch/roll with complementary weight `alpha=0.02`. Exposes `var onPose: ((yawDeg,pitchDeg,rollDeg)->Unit)?`. **Test seam:** public `fun injectGyro(gx,gy,gz,tNs)` and `fun injectAccel(ax,ay,az)` that the real SensorEventListener also calls, so tests and the instrumented render can drive it without hardware.

**Step 1 (failing test):** feed injectGyro constant +1 rad/s about Z (gz) for 1s; assert reported yawDeg ~= 57 deg. Feed injectAccel gravity straight down (0,0,9.8) and assert pitch/roll converge near 0 over many samples.

**Step 2:** Run. Expected FAIL.

**Step 3:** Implement (Android `SensorManager` wiring guarded so unit test only uses inject seams; construct with a nullable SensorManager for tests).

**Step 4:** Run. Expected PASS.

**Step 5:** Commit `feat: head orientation tracker with injectable sensor seam`.

---

### Task 5: Panel model + angular->pixel projection

**Files:**
- Create: `app/src/main/java/.../ui/Panel.kt` — data class `Panel(yawDeg, pitchDeg, title, lines: List<String>, role: PanelRole)`; `PanelRole { CENTER, PRIMARY, SECONDARY, FAR }`.
- Create: `app/src/main/java/.../ui/MockPanels.kt` — the fixed layout list from the design (center, +/-20 top/bottom, +/-30 primaries, ~60 corners, +/-90 far).
- Create: `app/src/main/java/.../ui/Projection.kt` — `class Projection(var horizontalFovDeg: Float=28f){ fun pxPerDeg(viewWidthPx): Float; fun screenX(panelYaw, offsetYaw, viewW): Float; fun screenY(panelPitch, offsetPitch, viewH): Float }`.
- Test: `app/src/test/java/.../ProjectionTest.kt`.

**Step 1 (failing test):** with fov 28, width 640: a panel at yaw 0 with offset 0 lands at center (320). A panel at yaw = +fov/2 with offset 0 lands at right edge (~640). Offset shifts panels opposite to head turn.

**Step 2:** Run. Expected FAIL.

**Step 3:** Implement projection + panel data.

**Step 4:** Run. Expected PASS.

**Step 5:** Commit `feat: panel model, mock layout, angular projection`.

---

### Task 6: OverlayView rendering

**Files:**
- Create: `app/src/main/java/.../ui/OverlayView.kt` — custom `View`. Holds current offset + tunables; `onDraw` clears to near-black, draws center reticle, draws each panel as a dark rounded card with bright border + title bar + body lines, using `Projection`. Culls panels fully off-screen. Draws debug readout (yaw/pitch/err/refYaw/k/D/fov) in a corner when enabled. Type size by `PanelRole`.
- Create/Modify: `MainActivity.kt` — instantiate tracker + LazyFollowReference + OverlayView; on each pose, compute offset, push to view, `invalidate()`.

**Step 1:** Implement OverlayView.onDraw (no unit test — visual; validated by instrumented test in Task 8). Keep all layout math in Projection (already tested).

**Step 2:** Wire MainActivity: start tracker in onResume, stop in onPause, feed poses through LazyFollowReference into the view.

**Step 3:** Build `./gradlew :app:assembleDebug`. Expected SUCCESS.

**Step 4:** Commit `feat: overlay view rendering + activity wiring`.

---

### Task 7: On-device touchpad controls

**Files:**
- Modify: `MainActivity.kt` — `onKeyDown` handling NUMPAD_0/1 (adjust selected tunable +/-), NUMPAD_2 (cycle selected: k -> D -> FOV -> RECENTER), NUMPAD_3 (recenter now + toggle debug). Show selected tunable in debug readout.

**Step 1:** Implement onKeyDown; clamp k in [0.05,3], D in [0,20], fov in [12,60].

**Step 2:** Build. Expected SUCCESS.

**Step 3:** Commit `feat: live touchpad tuning of k/deadzone/fov + recenter`.

---

### Task 8: Instrumented render test (observable, recordable)

**Files:**
- Create: `app/src/androidTest/java/.../OverlaySweepTest.kt` — UiAutomator2/ActivityScenario. Launches MainActivity, obtains the tracker via a test hook (activity exposes `@VisibleForTesting fun testInjectPose(yaw,pitch)` that pushes straight through LazyFollowReference to the view on the UI thread). Scripts a sweep: hold center 2s, pan to +20 top, +/-30 primaries, +/-60 corners, +/-90 far — each held ~2.5s so a screen recording shows each panel entering/leaving. Assert the activity/view is displayed at each step.
- Modify: `app/build.gradle.kts` — add androidx.test + uiautomator + espresso-core androidTest deps and `testInstrumentationRunner`.

**Step 1:** Add deps + runner.

**Step 2:** Write the test.

**Step 3 (device run):** The runner will `adb install` (authorized for THIS app). Run: `./gradlew :app:connectedDebugAndroidTest` OR `adb install -r` then `adb shell am instrument`. NOTE: prefer manual `adb install -r` + `am instrument` to avoid AGP auto-uninstall on teardown. Capture screen with `adb shell screenrecord` during the run.

**Step 4:** Bounce the recording to Telegram (curl tg-upload) and quote the shortId.

**Step 5:** Commit `test: instrumented head-sweep render test`.

---

### Task 9: Deploy script

**Files:**
- Create: `Recon/scripts/deploy-headlock-overlay-to-glasses.sh` — modeled on deploy-design-preview. Build `:app:assembleDebug`, pick connected glasses via `adb devices` (don't hard-fail on a guessed serial), `adb install -r`, `am start -n com.repository.glasses.headlockoverlay/.MainActivity`. No emojis/color.

**Step 1:** Write script, `chmod +x`.

**Step 2:** Commit `feat: deploy script for headlock-overlay-test`.

---

### Final verification
- `./gradlew :app:testDebugUnitTest` all green.
- `./gradlew :app:assembleDebug` builds.
- Instrumented sweep recorded + bounced to Telegram.
- Report shortId + how to launch on-device + which tunables to try.
