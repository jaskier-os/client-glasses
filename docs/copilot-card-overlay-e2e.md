# CopilotCardOverlay e2e -- SAFE run procedure

`app/src/androidTest/.../ui/CopilotCardOverlayInstrumentedTest.kt` exercises the
Copilot fact-check card overlay (`ui/CopilotCardOverlay.kt`) with mocked cards
and PROGRAMMATICALLY asserts each card actually rendered on the waveguide HUD --
not merely that the showCard/dismissCard/hideAll code paths ran.

## What it verifies

Verification method is **framebuffer pixel counting**, NOT UiAutomator
accessibility. `CopilotCardOverlay` renders into a `TYPE_APPLICATION_OVERLAY`
window with `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`; UiAutomator's
accessibility tree CANNOT see overlay windows, so `By.textContains` assertions
are useless here. The test instead captures the real screen via
`UiAutomation.takeScreenshot()` and counts green-on-black pixels (cards are pure
green stroke+text on black VOID). Asserted deltas, all vs an evidence-based
baseline captured BEFORE any card is shown:

- show c1   -> green count increases over baseline
- stack c2  -> increases again over the single-card count
- dismiss c1-> decreases vs two-card count but stays above baseline (c2 remains)
- show c3   -> increases again (c2 + c3 present)
- hideAll   -> returns to ~baseline

Every count is logged via `Log.i("AsstOverlayTest", ...)` with its step label.
`SystemClock.sleep` holds (~2.5-3s) between steps keep each rendered state on
screen long enough for an external screen recording to capture it.

**Do NOT run any step below without explicit user confirmation.** Running
requires installing the `.test` APK on the glasses.

## Run on-device (requires explicit user confirmation)

1. The app under test (`com.repository.glasses.listener`) must already be
   installed via the **priv-app overlay slot**. Deploy it ONLY via:

   ```bash
   bash /media/user/Lobotomite/Repository/Recon/scripts/deploy-to-glasses.sh
   ```

   NEVER `adb install` / `adb install -r` / `pm install` the app APK -- that
   shadows the priv-app slot and strips its privileged grants. The overlay
   permission (`SYSTEM_ALERT_WINDOW`) is granted defensively by the test's
   `@Before` via `appops set ... allow`.

2. Install ONLY the `.test` APK (the instrumentation package, NOT the app):

   ```bash
   adb -s <GLASSES_SERIAL> install -r \
     AI/clients/glasses/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
   ```

3. Run the instrumentation:

   ```bash
   adb -s <GLASSES_SERIAL> shell am instrument -w \
     -e class com.repository.glasses.listener.ui.CopilotCardOverlayInstrumentedTest \
     com.repository.glasses.listener.test/androidx.test.runner.AndroidJUnitRunner
   ```

## Recording the run

Capture with `adb shell screenrecord` (180s cap per file). Keep the screen awake
for the duration and do NOT SIGINT the screenrecord process -- let it exit on its
own so the MP4 is finalized. Bounce the resulting file to the user's Telegram
Saved Messages and quote the shortId. The test also writes per-step PNG artifacts:

```bash
adb -s <GLASSES_SERIAL> shell ls \
  /sdcard/Android/data/com.repository.glasses.listener/files/asst-overlay-test/
```
