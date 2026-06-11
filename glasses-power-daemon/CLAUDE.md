# glasses-power-daemon

Native arm64 C binary running as root via init service (`class core`, auto-respawn). Manages screen timeout, fold-triggered suspend, PSoC extcon latch policy, and tombstone crash log collection.

## Responsibilities

1. **Screen-off** after `screen_timeout_s` of input idleness (no key events on watched `/dev/input/eventN`).
2. **Suspend-to-RAM** after `power_timeout_s` of continuous folded state (hall sensor). Unfold wakes instantly (~1-2s).
3. **PSoC latch policy** at boot -- writes `1` to `enforce_psensor` (latch wear high so stock Rokid PsensorObserver never fires its wear earcon / `PowerManager.wakeUp("psensor")` screen-toggle; `is_take_on` is deprecated and unused) and `0` to `enforce_hall` (keep the fold/hall extcon emitting real uevents so `is_spread` + `ACTION_LEG_STATUS_CHANGED` / FoldGate / suspend still work).
4. **Tombstone crash logs** -- copies new `/data/tombstones/` files to `/data/local/tmp/crash-logs/` at boot (max 10, pruned).
5. **Time sync** -- reads epoch from config dir on cold boot if NTP hasn't run.

## Suspend Architecture

The fold timeout triggers `suspend_to_ram()` which puts the device into s2idle (freeze). The sequence:

1. **Stop `system_suspend` HAL** -- Android's suspend service holds a `wakeup_count` loop that races direct `/sys/power/state` writes. Stopped via `stop system_suspend`, restarted after resume.
2. **Force DWC3 USB runtime suspend** -- the Qualcomm `msm-dwc3` driver blocks kernel suspend with EBUSY if USB is "outside LPM". Setting `autosuspend_delay_ms=0` triggers immediate runtime suspend when no USB cable is connected (VBUS low, extcon reports USB=0).
3. **Release kernel wakelocks** -- `bluetooth_timer`, `hal_bluetooth_lock` etc.
4. **Write `freeze` to `/sys/power/state`** -- blocks until a wakeup source fires (PSoC unfold, RTC alarm).
5. **Resume** -- restart `system_suspend` HAL, reset activity timer.

### Why freeze (s2idle), not deep (suspend-to-RAM)

Both are available (`/sys/power/mem_sleep` shows `[s2idle] deep`). Deep suspend crashes when the DWC3 USB controller is unbound. s2idle is safe, keeps peripherals powered, and wakes in ~1-2s. Battery drain is higher than deep but acceptable for multi-hour fold windows.

### USB cable constraint

Suspend ONLY works when no USB cable is connected. The MP2724 charger IC (I2C 1-003f) reports VBUS presence via extcon4. When VBUS is high (cable connected), DWC3 refuses to enter runtime suspend/LPM, blocking kernel suspend. This is fine for production use -- glasses are never USB-connected when folded.

## Config

Key-value file at `/data/local/diy-overlay/glasses-power.conf`. Reloads via SIGHUP or inotify.

| Key | Default | Description |
|-----|---------|-------------|
| `screen_timeout_s` | 300 | Idle seconds before screen lock |
| `power_timeout_s` | 3600 | Fold seconds before suspend (0 = disabled) |
| `power_timeout_min` | - | Legacy alias, multiplied by 60 internally |
| `fold_hall_node` | sysfs path | Hall sensor sysfs or evdev path |
| `input_devices` | `event0,event1` | Comma-separated evdev paths to watch |

Config changes trigger a 30-second safety window during which suspend is blocked.

## Build & Deploy

```bash
# Build (requires Android NDK)
bash build.sh

# Deploy to overlay (reboot needed for init to pick up)
adb -s <GLASSES_SERIAL> push build/glasses-power-daemon \
    /data/local/diy-overlay/system/bin/glasses-power-daemon
adb -s <GLASSES_SERIAL> reboot

# Quick dev iteration (kills init instance, runs from /data/local/tmp)
bash deploy.sh run

# Tail log (only works with deploy.sh run; init-launched daemon logs to stderr -> /dev/null)
bash deploy.sh logs
```

## Logging

When launched by init, stderr goes to `/dev/null` (no persistent log). Use `deploy.sh run` for development to get logs at `/data/local/tmp/glasses-power-daemon.log`.

Tracing: all major code paths emit Perfetto slices via `PWR_TRACE_BEGIN`/`PWR_TRACE_END` with `pwr.*` prefix (written directly to `/sys/kernel/tracing/trace_marker`).

## Crash Logs

On boot, `save_tombstones()` copies new files from `/data/tombstones/` to `/data/local/tmp/crash-logs/`. A marker file (`.last_saved_ts`) tracks the last-copied timestamp. Max 10 files retained, oldest pruned.

These are native crash tombstones only (SIGABRT, SIGSEGV etc.). Java crashes go to `/data/anr/` and logcat.
