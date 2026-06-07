# AssistantCardOverlay instrumented test -- SAFE run procedure

`AssistantCardOverlayInstrumentedTest.kt` exercises the Assistant fact-check
card overlay (`ui/AssistantCardOverlay.kt`) with mocked cards and
PROGRAMMATICALLY asserts each card actually rendered (UiAutomator
`By.textContains` presence/absence checks), with per-step screenshots as a
secondary artifact.

**Do NOT run any of the steps below without explicit user confirmation.**
Running requires installing the `.test` APK on the glasses, which the project's
guards otherwise forbid for the app APK itself.

## What it verifies

| Step | Action | Assertion |
|---|---|---|
| 1 | `showCard("c1", ...)` Eiffel Tower | card text visible on HUD |
| 2 | `showCard("c2", ...)` Mount Everest | both c1 and c2 visible (stack) |
| 3 | `dismissCard("c1")` | c1 gone, c2 still visible (glided up) |
| 4 | `showCard("c3", ...)` Sanity check | c3 visible, c2 still visible |
| 5 | `hideAll()` | c2 and c3 both gone |

Each step holds ~2.8s so an external screen recording captures the state.

## Build the test APK only (does NOT install)

```bash
cd <repo-root>
./gradlew :app:assembleDebugAndroidTest
```

Output: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.

## Run on-device (requires explicit user confirmation)

1. The app under test (`com.repository.glasses.listener`) must already be
   installed via the priv-app overlay slot. Deploy it ONLY via:

   ```bash
   bash <workspace>/Recon/scripts/deploy-to-glasses.sh
   ```

   NEVER `adb install` / `adb install -r` / `pm install` the app APK -- that
   shadows the priv-app slot and strips its privileged grants. The overlay
   permission (`SYSTEM_ALERT_WINDOW`) the test needs is granted defensively by
   the test's `@Before` via `appops set ... allow`.

2. Install ONLY the `.test` APK (the instrumentation package, not the app):

   ```bash
   adb -s <GLASSES_SERIAL> install -r \
     <repo-root>/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
   ```

3. Run the instrumentation. The runner is the one declared in
   `app/build.gradle.kts`:
   `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`.

   ```bash
   adb -s <GLASSES_SERIAL> shell am instrument -w \
     -e class com.repository.glasses.listener.ui.AssistantCardOverlayInstrumentedTest \
     com.repository.glasses.listener.test/androidx.test.runner.AndroidJUnitRunner
   ```

## Recording the run

If you want a video, start `adb shell screenrecord` in parallel. Note the
**180 s per-file cap** on `screenrecord`; this test runs ~16s so one file is
plenty. After capture, bounce the file to the user's Telegram Saved Messages
(see global instructions) and quote the `shortId`.

## Pulling the per-step screenshots

The test writes PNG artifacts to the app's external files dir:

```bash
adb -s <GLASSES_SERIAL> shell ls \
  /sdcard/Android/data/com.repository.glasses.listener/files/asst-overlay-test/
```
