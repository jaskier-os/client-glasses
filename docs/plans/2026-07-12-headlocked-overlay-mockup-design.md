# Head-locked overlay mockup (glasses test app) — design

Date: 2026-07-12
Status: approved (user authorized autonomous build)

## Goal

A standalone glasses test app that renders a mock AR UI larger than the field of
view. The main content stays in front of you; panels placed at increasing angular
distance (up to +/-90 deg) are discovered by turning your head. When you turn your
whole body and settle on a new heading, the main content re-centers in front of you.

This is a *feel* prototype for a head-relative discovery UI — not production, not a
real system overlay. All panel content is mock text.

## Decisions (locked)

- **Shape: standalone full-screen app (Option B).** Not a `TYPE_APPLICATION_OVERLAY`.
  Its own activity draws its own dark canvas so it visually sits "on top of home"
  while open; exit returns to home. Rationale: isolates the IMU/interaction feel
  without overlay-permission or launcher-focus complications.
- **Orientation: lazy-follow, head IMU only (Option B1).** No phone/torso sensor.
  Cannot physically distinguish head-vs-body; behaves correctly because glances are
  transient and reorientations persist.
- **Install: `adb install` explicitly authorized by the user for THIS test app only.**
  The standing no-adb-install rule remains in force for the phone app and the main
  glasses app. No `connectedAndroidTest`, no `pm uninstall`, no `pm clear`.
- **UI: all mock text.** No real clock/battery wiring in v1.
- **k (follow rate) and D (deadzone) are live-tunable on-device** via touchpad keys,
  with an on-screen debug readout. Needed to dial the 60-90 deg dwell feel.

## Where it lives

New nested Gradle module in the glasses repo, modeled on the existing
`touchpad-test-app` precedent (proven-good toolchain in this repo):
`AI/clients/glasses/headlock-overlay-test/`.

- applicationId / namespace: `com.repository.glasses.headlockoverlay`
- Plain Android Views + a single custom `Canvas` view (NOT Compose) — lowest build
  risk, and a canvas-offset render is trivial to draw directly.
- compileSdk 34, minSdk 28, targetSdk 34, landscape, `singleTask`, Java 11.
  (Mirrors `touchpad-test-app/app/build.gradle.kts`.)
- Deploy: new script `Recon/scripts/deploy-headlock-overlay-to-glasses.sh`, modeled
  on `deploy-design-preview-to-glasses.sh` (build `:app:assembleDebug`, `adb install -r`,
  `am start`). Glasses serial resolved from `adb devices` (memory serial
  1901092534009177; design-preview script used 1901092544026001 — script will pick the
  connected device rather than hard-fail on a guessed serial).

## Orientation core (ported from GlassesBareDevSample)

Port these, adapting away coroutines (use a plain listener callback instead of Flow):

- `Quaternion.kt` — pure kotlin.math, port verbatim.
- `GyroQuaternionIntegrator.kt` — pure gyro quaternion integration, port verbatim.
- `HeadOrientationTracker` — adapted: register gyro + accel at SENSOR_DELAY_GAME,
  integrate gyro into a quaternion, expose head yaw/pitch/roll in degrees via a
  callback. **Add complementary gravity correction for pitch/roll** (blend accel
  gravity vector, ~0.02 weight) so vertical panels don't drift; yaw stays gyro-only.
- Axis mapping: reuse the sample's convention (gx->pitch, gy->roll, gz->yaw); no
  interactive axis-verification wizard in v1 (hard-code the mapping; if an axis is
  wrong on-device, it's a one-line sign/axis flip we tune live).

## Lazy-follow reference (the B1 behavior)

Per frame (driven by sensor callback):

```
headYaw, headPitch  = tracker output (deg)
errYaw   = wrap180(headYaw   - refYaw)
errPitch = wrap180(headPitch - refPitch)

# reveal: panels are world-fixed; viewport offset = -(head - ref)
# within deadzone, treat error as 0 so center content sits locked in front
drawErrYaw   = if (|errYaw|   < D) 0 else errYaw
drawErrPitch = if (|errPitch| < D) 0 else errPitch

# follow: reference slowly chases head so a settled heading re-centers content
refYaw   += k * errYaw   * dt
refPitch += k * errPitch * dt
```

- Quick head-turn outruns the follower -> panels reveal.
- Sustained heading (body turn + settle) -> follower catches up -> content returns front.
- `k` small (e.g. 0.6/s start) so a deliberate hard 90 deg turn still reveals & dwells
  before re-centering. `D` (e.g. 6 deg) gives a stable head-locked center zone.
- Both adjustable live (see controls).

## Angular-to-pixel mapping (audit-driven)

Single source of truth: a `HORIZONTAL_FOV_DEG` constant for the display. Pixels per
degree = viewportWidthPx / HORIZONTAL_FOV_DEG. All panel placement and the viewport
offset use this same factor, so "turn 60 deg to find it" is physically honest.
FOV constant is tunable live alongside k/D (we don't know the exact combiner FOV; dial
it until a known-angle panel appears at the right head-turn).

## UI layout (all mock text, audit-driven)

Each panel is self-contained (title + own body) because at these eccentricities a panel
is only read after you turn to face it — no peripheral context. Panels are solid-ish
dark cards with a bright 1-2px border and a light title bar so they read against the
transparent combiner (pure black is invisible on an AR combiner).

Angular coordinates (yaw, pitch), degrees, +yaw = right, +pitch = up:

- (0, 0)      Center: main card — large mock clock line + 2-3 lines body. Stays in front.
- (0, +20)    Top: notifications strip — 3 mock notification rows.
- (0, -20)    Bottom: status bar — battery / wifi / quick-settings row (mock).
- (-30, 0)    Left primary: vertical menu list, several mock items.
- (+30, 0)    Right primary: "now playing" card — title + paragraph (mock).
- (-60, +60?) Corner secondaries at ~60 deg diagonals: small secondary cards.
  (place 2-4 corner cards: e.g. (-60,+25), (+60,+25), (-60,-25), (+60,-25))
- (-90, 0)    Far left: edge menu — hard head-turn to reach.
- (+90, 0)    Far right: edge info card — hard head-turn to reach.

Type scale by ROLE not uniform: center card largest/highest-contrast (often glanced
peripherally); far panels normal weight (read head-on). Faint center reticle (crosshair)
always drawn at screen center for reference.

Debug readout (small, corner, toggleable): headYaw/headPitch, errYaw, refYaw, k, D, FOV.

## On-device controls (touchpad keycodes)

Reuse the glasses touchpad mapping (from memory reference): tap = NUMPAD_2,
hold = NUMPAD_3, scroll = NUMPAD_0 / NUMPAD_1. In `onKeyDown`:

- scroll up/down (NUMPAD_0 / NUMPAD_1): adjust the currently-selected tunable +/-.
- tap (NUMPAD_2): cycle selected tunable (k -> D -> FOV -> recenter-now).
- hold (NUMPAD_3): recenter immediately (snap ref = head) / toggle debug readout.

Exact keycodes verified on-device during bring-up; adjust if the glasses report
different codes. Never drive UI by coordinate taps — this is real hardware input.

## Testing

- Build must pass: `./gradlew :app:assembleDebug` in the new module.
- Instrumented `androidTest` (UiAutomator2) that launches the activity and feeds a
  scripted yaw/pitch sweep through a test seam on the tracker (the tracker accepts
  injected samples so the render can be driven without physical motion), holding each
  revealed panel visible ~2-3s so a screen recording captures observable behavior
  (per the "recordings must show observable behavior" rule).
- Live on-device: user moves head to discover panels; tunes k/D/FOV via touchpad.
- Recording of the instrumented run bounced to the user's Telegram Saved Messages.

## Out of scope (v1)

- True head-vs-body via phone torso sensor (Option B2).
- Real system overlay over the live home app (Option A).
- Real data (clock/battery/wifi), axis-verification wizard, persistence of tuned values.
```
