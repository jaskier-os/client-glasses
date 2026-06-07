# Battery-color charging LED -- Design (2026-06-06)

## Goal

Show a battery-percentage-colored LED while the glasses are charging and sitting
idle (on a table), and never while worn. The LED reflects charge level:

| Battery % | Color | LED channels |
|---|---|---|
| >= 45% | GREEN | green=255, red=0 |
| 15-45% | GREEN+RED (both dots) | red=255, green=255 |
| < 15% | RED | red=255, green=0 |

When armed, re-assert the chosen color every 5s so stock charger triggers don't
reclaim the LED. On any movement, disable the LED immediately.

## Hardware facts (verified live on device <GLASSES_SERIAL>)

- The LED is 4 independent channels on the mp2724 charger PMIC:
  `/sys/class/leds/{red,green,blue,white}/brightness`, each 0-255, root-writable.
- Stock `lights_ctrl` `sendEvent(type,id)` lights exactly ONE channel at a time
  (its `mLightId` is a single-bit mask). It CANNOT show red+green together.
- Direct sysfs writes CAN drive multiple channels simultaneously -- verified
  `red=255 green=255` lit both dots at once. This is the only path to the
  "both dots" mid-band signal, so the battery LED uses direct sysfs, not events.
- Battery %: `/sys/class/power_supply/battery/capacity`.
- Charging status: `/sys/class/power_supply/mp2724-charger/status`
  (`Charging` / `Full` = plugged in; otherwise not charging).
- The accelerometer (ICM-4x6xx) is behind the SLPI sensor HAL. There is NO IIO
  or input node for it -- native C (the daemon) CANNOT read motion. Only the
  Android app, via SensorManager, can detect stillness.

## Responsibility split

Because only the app can read the IMU and only root can write the LED sysfs,
the work splits:

- **App** (`com.repository.glasses.listener`):
  - Reuses the existing `StillnessSensor` (already in
    `audio/routing/StillnessSensor.kt`, instantiated by AudioRoutingController).
  - Also reads charging state (already available via `ACTION_BATTERY_CHANGED`
    `EXTRA_STATUS`).
  - Computes the single boolean "arm the battery LED": charging AND still for
    >= 60s continuously. On movement, immediately set it false.
  - Writes the boolean to a flag file the daemon watches.
  - Owns NO LED or battery-color logic.

- **Daemon** (`glasses-power-daemon`, root):
  - Watches the flag file via its existing inotify loop on the diy-overlay dir.
  - When armed: reads battery capacity, picks the color, writes the LED sysfs
    channels, and re-asserts every 5s.
  - Independently re-checks charging status itself (defense-in-depth: never
    light the LED if not actually charging, even if the app flag is stale).
  - When disarmed (flag=0, or charging stops): writes all 4 channels to 0
    immediately (inotify is event-driven, so movement -> flag=0 -> instant off).

## IPC: flag file

- Path: `/data/local/diy-overlay/glasses-led-battery-arm` (sibling to the
  existing `glasses-power.conf`, same dir the daemon already inotify-watches and
  both UIDs can read/write).
- Content: single char `1` (arm) or `0` (disarm).
- App writes it via a small helper (mirror of `PowerDaemonControl.writeConfig`
  pattern: `File(path).writeText("1"|"0")`, then best-effort SIGHUP to nudge the
  daemon in case inotify was not armed yet).
- Daemon: on inotify CLOSE_WRITE for that filename (added alongside the existing
  cfg/time-sync/wifi filename checks), re-read the flag and update LED state
  immediately. Also re-read it each main-loop iteration so the 5s re-assert and
  charging re-check stay live without depending solely on inotify.

## Daemon LED state machine

State: `led_armed` (from flag) + `led_color_last` (debounce sysfs writes).

Every main-loop iteration (the loop already wakes ~1s via poll timeout):
1. Read flag file -> want_arm.
2. If want_arm: read charging status; if not charging -> treat as disarmed.
3. If armed+charging:
   - If >= 5s since last assert (or color changed): read capacity, map to color,
     write the 1-2 lit channels + zero the rest. Log on color change only.
4. If disarmed: if LED currently ours, write all channels 0 once; clear state.

Re-assert cadence: 5s. The daemon does NOT need a new timer; it reuses the
existing 1s poll loop and tracks `last_led_assert_ms`.

## App stillness/arming logic

- A new lightweight `BatteryLedArmer` (or fold it into the existing battery/
  routing wiring -- decided in the plan) that:
  - Tracks charging from the battery broadcast already received.
  - Subscribes to `StillnessSensor` still/moving callbacks.
  - Arms (writes flag `1`) only after charging==true AND still==true for >= 60s
    continuously. A 60s timer that movement or unplug cancels.
  - On movement OR unplug: writes flag `0` immediately and cancels the timer.
  - Re-arms on the next fresh 60s-still window while still charging.
- Worn glasses always have micro-movement, so they never reach 60s-still ->
  LED never shows while worn (the user's hard requirement), without needing the
  wear sensor at all.

## Edge cases / safety

- Daemon writes LED only when its OWN charging re-check passes -> unplug always
  kills the LED even if the app flag is stale or the app died.
- On daemon shutdown/exit: zero all 4 channels in the exit path.
- The battery LED never touches WHITE (capture's camera privacy light) or BLUE,
  so it cannot collide with the capture service's LED or BT-connect events.
- If the flag file is missing/empty: treat as disarmed (LED off).
- Stock charger LED: user reports "LED usually disabled", so no persistent stock
  driver is expected. The 5s re-assert covers any transient stock reclaim.

## Documentation

Add an "LED Control" section to `clients/glasses/CLAUDE.md`:
- The 4 sysfs channels, 0-255, root-only.
- Stock `sendEvent(type,id)` is single-channel; direct sysfs is multi-channel.
- Reference table of known-working steady `sendEvent` colors (green=(1,1013)/
  (3,3018), red=(3,3016), blue=(4,4011), white=(2,2014)/(3,3021)); full table in
  `Recon/rokid-docs/LED-EVENT-TABLE.md`.
- This battery indicator: color map, arming rules (charging + still 60s), the
  flag-file IPC, and that the daemon owns the sysfs write.

## Out of scope (YAGNI)

- No brightness ramps/animation -- steady colors only.
- No wear-sensor gating -- stillness is the proxy for "not worn".
- No new config keys -- the arm flag is a separate one-char file.
