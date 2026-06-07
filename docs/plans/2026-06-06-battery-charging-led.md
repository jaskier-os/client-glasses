# Battery-color Charging LED Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Light the glasses LED green/red-by-battery-percent while charging and sitting still (off-head), driven by the root power daemon, gated by app-side stillness via a flag file.

**Architecture:** The app detects "charging AND still >= 60s" (only it can read the IMU) and writes a one-char flag file. The root `glasses-power-daemon` watches that file, and while armed+charging it writes the LED sysfs channels directly (the only path that lights red+green together), re-asserting every 5s. Movement -> app writes 0 -> daemon clears the LED immediately.

**Tech Stack:** C (glasses-power-daemon, NDK clang arm64), Kotlin (listener app), Android SensorManager, sysfs `/sys/class/leds/*` and `/sys/class/power_supply/*`.

**Verification reality:** No unit-test framework exists for the native daemon or the physical LED. Each task's "test" is an on-device check (sysfs read-back and/or watching the LED on glasses serial `<GLASSES_SERIAL>`). Build daemon with `NDK_VER=25.1.8937393 bash build.sh`. Deploy daemon via overlay push + reboot. Deploy app via `bash Recon/scripts/deploy-to-glasses.sh`.

**Reference design:** `docs/plans/2026-06-06-battery-charging-led-design.md`. Full LED event table: `Recon/rokid-docs/LED-EVENT-TABLE.md`.

---

## Task 1: Daemon -- LED sysfs write helpers

**Files:**
- Modify: `clients/glasses/glasses-power-daemon/src/main.c` (add helpers near `read_sysfs_int`, ~line 235)

**Step 1: Add LED channel constants + writer**

Add after the `read_sysfs_int` helper:

```c
// Battery-indicator LED. Direct sysfs is the ONLY path that can light multiple
// channels at once (stock lights_ctrl sendEvent is single-channel). Root-only.
#define LED_RED_NODE   "/sys/class/leds/red/brightness"
#define LED_GREEN_NODE "/sys/class/leds/green/brightness"

// Write a single 0..255 brightness to one LED node. Best-effort; logs on error.
static void led_write_node(const char *node, int val) {
    int fd = open(node, O_WRONLY);
    if (fd < 0) { log_line("led: open %s failed: %s", node, strerror(errno)); return; }
    char buf[8];
    int n = snprintf(buf, sizeof(buf), "%d", val);
    if (write(fd, buf, (size_t)n) < 0)
        log_line("led: write %s=%d failed: %s", node, val, strerror(errno));
    close(fd);
}

// Set the red+green pair atomically (blue/white left untouched -- owned by
// capture privacy light + BT events, never by us).
static void led_set_rg(int red, int green) {
    led_write_node(LED_RED_NODE, red);
    led_write_node(LED_GREEN_NODE, green);
}
```

**Step 2: Build**

Run: `cd clients/glasses/glasses-power-daemon && NDK_VER=25.1.8937393 bash build.sh`
Expected: `Built: .../build/glasses-power-daemon`, no warnings.

**Step 3: Commit**

```bash
git add clients/glasses/glasses-power-daemon/src/main.c
git commit -m "glasses-power-daemon: add direct-sysfs LED red/green write helpers"
```

---

## Task 2: Daemon -- battery read + color mapping

**Files:**
- Modify: `clients/glasses/glasses-power-daemon/src/main.c`

**Step 1: Add battery + charging readers and the color decision**

Add near the LED helpers:

```c
#define BATT_CAPACITY_NODE "/sys/class/power_supply/battery/capacity"
#define BATT_CHARGER_STATUS "/sys/class/power_supply/mp2724-charger/status"

// Read integer battery percent 0..100, or -1 on failure.
static int read_battery_pct(void) {
    int fd = open(BATT_CAPACITY_NODE, O_RDONLY);
    if (fd < 0) return -1;
    char buf[16] = {0};
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return -1;
    int v = atoi(buf);
    if (v < 0) v = 0; if (v > 100) v = 100;
    return v;
}

// 1 = charger connected (Charging or Full), 0 = not, -1 = unknown.
static int read_is_charging(void) {
    int fd = open(BATT_CHARGER_STATUS, O_RDONLY);
    if (fd < 0) return -1;
    char buf[32] = {0};
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return -1;
    // status strings: "Charging", "Full", "Not charging", "Discharging"
    if (strncmp(buf, "Charging", 8) == 0) return 1;
    if (strncmp(buf, "Full", 4) == 0) return 1;
    return 0;
}

// Apply the battery color for the given percent. >=45 green, 15-45 both, <15 red.
static void led_apply_battery(int pct) {
    if (pct >= 45)      led_set_rg(0, 255);    // green
    else if (pct >= 15) led_set_rg(255, 255);  // green+red (both dots)
    else                led_set_rg(255, 0);    // red
}
```

**Step 2: Verify readers on device (manual, before wiring the loop)**

Run:
```bash
adb -s <GLASSES_SERIAL> shell 'su 0 cat /sys/class/power_supply/battery/capacity /sys/class/power_supply/mp2724-charger/status'
```
Expected: a number 0-100 and a status word. Confirms node paths and status string format match the code.

**Step 3: Build**

Run: `cd clients/glasses/glasses-power-daemon && NDK_VER=25.1.8937393 bash build.sh`
Expected: builds clean.

**Step 4: Commit**

```bash
git add clients/glasses/glasses-power-daemon/src/main.c
git commit -m "glasses-power-daemon: add battery/charging readers + color mapping"
```

---

## Task 3: Daemon -- arm flag file + LED state machine in main loop

**Files:**
- Modify: `clients/glasses/glasses-power-daemon/src/main.c`

**Step 1: Add flag path constant + state globals**

Near the other `#define`s (top, ~line 88):

```c
#define LED_ARM_FLAG_FILE  "glasses-led-battery-arm"  // basename in cfg_dir
#define LED_REASSERT_MS    5000LL
```

Near the other file-static state (~line 107):

```c
static long long led_last_assert_ms = 0;
static int       led_active         = 0;  // 1 = we currently own the LED
static int       led_color_pct_band = -1; // -1/0/1/2 last-applied band for dedup
```

**Step 2: Add a flag reader**

Add near the battery helpers. `cfg_dir` is available in `main()`; pass it in.

```c
// Read the app-written arm flag (single char '1'/'0') from cfg_dir.
// Returns 1=arm, 0=disarm, -1=missing/unreadable (treated as disarm by caller).
static int read_led_arm_flag(const char *cfg_dir) {
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/%s", cfg_dir, LED_ARM_FLAG_FILE);
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    char c = 0;
    ssize_t n = read(fd, &c, 1);
    close(fd);
    if (n <= 0) return -1;
    if (c == '1') return 1;
    if (c == '0') return 0;
    return -1;
}

// Compute band 0=red(<15) 1=both(15-45) 2=green(>=45) for dedup.
static int led_band_for(int pct) {
    if (pct >= 45) return 2;
    if (pct >= 15) return 1;
    return 0;
}

// Evaluate arm flag + charging, drive/clear LED. Called every loop iteration.
static void led_tick(const char *cfg_dir, long long now) {
    int arm = read_led_arm_flag(cfg_dir);
    int charging = (arm == 1) ? read_is_charging() : 0;
    int want = (arm == 1 && charging == 1);

    if (!want) {
        if (led_active) {
            led_set_rg(0, 0);
            led_active = 0;
            led_color_pct_band = -1;
            log_line("led: cleared (arm=%d charging=%d)", arm, charging);
        }
        return;
    }
    // Armed + charging. Re-assert every LED_REASSERT_MS, or immediately on band change.
    int pct = read_battery_pct();
    if (pct < 0) return;
    int band = led_band_for(pct);
    int due = (now - led_last_assert_ms) >= LED_REASSERT_MS;
    if (!led_active || band != led_color_pct_band || due) {
        led_apply_battery(pct);
        led_last_assert_ms = now;
        if (!led_active || band != led_color_pct_band)
            log_line("led: battery pct=%d band=%d", pct, band);
        led_active = 1;
        led_color_pct_band = band;
    }
}
```

**Step 3: Call `led_tick` from the main loop**

In the `while (!g_stop)` loop, after the fold-drain/debounce block and near the
other per-iteration action checks (~line 1246, after `int within_safety = ...`),
add:

```c
        // Battery-indicator LED (charging + app says still). cfg_dir is the
        // inotify-watched config dir; the arm flag lives alongside the .conf.
        led_tick(cfg_dir, now);
```

(`cfg_dir` and `now` are both in scope there.)

**Step 4: React instantly to the flag via inotify**

In the inotify drain block (~line 1123), where it checks `ev->name` against
`cfg_base`/`TIME_SYNC_FILE`/`WIFI_REQ_FILE`, add a branch so a flag write
triggers an immediate `led_tick` (don't wait up to 1s):

```c
                    if (ev->len > 0 && strcmp(ev->name, LED_ARM_FLAG_FILE) == 0) {
                        led_tick(cfg_dir, now_ms());
                    }
```

**Step 5: Clear LED on daemon exit**

In the shutdown path after the `while` loop (near `log_line("exit")`, ~line 1279),
add before closing fds:

```c
    led_set_rg(0, 0); // never leave the battery LED stuck on across a daemon restart
```

**Step 6: Build**

Run: `cd clients/glasses/glasses-power-daemon && NDK_VER=25.1.8937393 bash build.sh`
Expected: builds clean, no warnings.

**Step 7: Commit**

```bash
git add clients/glasses/glasses-power-daemon/src/main.c
git commit -m "glasses-power-daemon: battery LED state machine driven by arm flag + charging"
```

---

## Task 4: Daemon -- on-device verification (manual flag toggle)

**Files:** none (verification only).

**Step 1: Deploy daemon**

```bash
adb -s <GLASSES_SERIAL> push clients/glasses/glasses-power-daemon/build/glasses-power-daemon \
    /data/local/diy-overlay/system/bin/glasses-power-daemon
adb -s <GLASSES_SERIAL> reboot
```
Then poll `getprop sys.boot_completed` == 1 (with a fastboot guard; STOP if it
lands in fastboot). Confirm daemon: `adb -s <GLASSES_SERIAL> shell pidof glasses-power-daemon`.

**Step 2: Simulate "armed" by writing the flag as the app would**

```bash
adb -s <GLASSES_SERIAL> shell 'echo 1 > /data/local/diy-overlay/glasses-led-battery-arm'
```
Expected (glasses must be on a charger): within ~1s the LED lights the
battery color. Read back:
```bash
adb -s <GLASSES_SERIAL> shell 'su 0 cat /sys/class/leds/red/brightness /sys/class/leds/green/brightness'
adb -s <GLASSES_SERIAL> shell 'cat /data/local/tmp/glasses-power-daemon.log | grep led: | tail'
```
Expected: red/green values match the band for the current pct; log shows
`led: battery pct=.. band=..`.

**Step 3: Disarm**

```bash
adb -s <GLASSES_SERIAL> shell 'echo 0 > /data/local/diy-overlay/glasses-led-battery-arm'
```
Expected: LED goes dark immediately; sysfs red=0 green=0; log `led: cleared`.

**Step 4: Unplug test (if feasible)**

With flag still `1`, unplug the charger. Expected: LED clears within ~1s
(daemon's own charging re-check fails). Log shows `led: cleared ... charging=0`.

**Step 5: No commit (verification task).** If any check fails, return to Task 3.

---

## Task 5: App -- flag writer helper

**Files:**
- Create: `clients/glasses/app/src/main/java/com/repository/glasses/listener/power/BatteryLedControl.kt`

**Step 1: Write the helper**

```kotlin
package com.repository.glasses.listener.power

import android.util.Log
import java.io.File

/**
 * Writes the battery-LED arm flag the glasses-power-daemon watches. The daemon
 * (root) owns the actual LED; the app only signals "charging + still, light it"
 * vs "off". Both the app UID and the daemon (root) can read/write this dir.
 */
object BatteryLedControl {
    private const val TAG = "BatteryLedControl"
    private const val FLAG_PATH = "/data/local/diy-overlay/glasses-led-battery-arm"

    @Volatile private var last: Boolean? = null

    /** Idempotent: only writes on change. arm=true -> "1", false -> "0". */
    fun setArmed(arm: Boolean) {
        if (arm == last) return
        try {
            File(FLAG_PATH).parentFile?.mkdirs()
            File(FLAG_PATH).writeText(if (arm) "1" else "0")
            last = arm
            Log.i(TAG, "arm flag -> $arm")
        } catch (e: Exception) {
            Log.w(TAG, "failed to write $FLAG_PATH: ${e.message}")
        }
    }
}
```

**Step 2: Build the app**

Run: `clients/glasses/gradlew -p clients/glasses :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

**Step 3: Commit**

```bash
git add clients/glasses/app/src/main/java/com/repository/glasses/listener/power/BatteryLedControl.kt
git commit -m "listener: add BatteryLedControl arm-flag writer"
```

---

## Task 6: App -- stillness + charging arming logic

**Files:**
- Create: `clients/glasses/app/src/main/java/com/repository/glasses/listener/power/BatteryLedArmer.kt`
- Modify: `clients/glasses/app/src/main/java/com/repository/glasses/listener/service/ListenerService.kt` (wire start/stop + charging signal)

**Pre-step: read these first**
- `clients/glasses/app/src/main/java/com/repository/glasses/listener/audio/routing/StillnessSensor.kt` (its Listener interface + start/stop API)
- The battery broadcast handling already in ListenerService (`ACTION_BATTERY_CHANGED`, `EXTRA_STATUS`) -- reuse, do not add a second receiver.

**Step 1: Write the armer**

```kotlin
package com.repository.glasses.listener.power

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.repository.glasses.listener.audio.routing.StillnessSensor

/**
 * Arms the battery LED (via BatteryLedControl) when the glasses are charging AND
 * have been physically still for >= STILL_ARM_MS continuously. Movement or unplug
 * disarms immediately. Worn glasses always micro-move, so they never reach the
 * stillness threshold -> the LED never shows while worn.
 *
 * Only the app can read the IMU (sensor HAL), so the stillness decision lives
 * here; the daemon owns the actual LED write.
 */
class BatteryLedArmer(
    ctx: Context,
    private val log: (String) -> Unit = {},
) {
    companion object {
        private const val TAG = "BatteryLedArmer"
        private const val STILL_ARM_MS = 60_000L
    }

    private val stillness = StillnessSensor(ctx, log)
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var charging = false
    @Volatile private var still = false
    private var running = false

    private val armRunnable = Runnable {
        // Fired only if we stayed charging+still for the full window.
        if (charging && still) {
            BatteryLedControl.setArmed(true)
            log("armed: charging+still ${STILL_ARM_MS}ms")
        }
    }

    fun start() {
        if (running) return
        running = true
        stillness.listener = object : StillnessSensor.Listener {
            override fun onStillnessChanged(s: Boolean) {
                still = s
                reevaluate("stillness=$s")
            }
        }
        stillness.start()
        log("BatteryLedArmer started")
    }

    fun stop() {
        running = false
        handler.removeCallbacks(armRunnable)
        stillness.stop()
        stillness.listener = null
        BatteryLedControl.setArmed(false)
        log("BatteryLedArmer stopped")
    }

    /** Call from ListenerService whenever the battery broadcast reports charging. */
    fun setCharging(isCharging: Boolean) {
        if (isCharging == charging) return
        charging = isCharging
        reevaluate("charging=$isCharging")
    }

    private fun reevaluate(reason: String) {
        if (!running) return
        if (charging && still) {
            // Start (or keep) the 60s timer; only arms when it elapses.
            handler.removeCallbacks(armRunnable)
            handler.postDelayed(armRunnable, STILL_ARM_MS)
            log("pending arm in ${STILL_ARM_MS}ms ($reason)")
        } else {
            // Not eligible -> cancel timer and disarm immediately (movement/unplug).
            handler.removeCallbacks(armRunnable)
            BatteryLedControl.setArmed(false)
            log("disarmed ($reason)")
        }
    }
}
```

**Step 2: Wire into ListenerService**

- Add a field: `private lateinit var batteryLedArmer: BatteryLedArmer`
- In the service `onCreate`/start path (where other controllers like
  AudioRoutingController are started): `batteryLedArmer = BatteryLedArmer(this) { btLog(it) }; batteryLedArmer.start()`
- In the existing battery broadcast handler (the one reading `EXTRA_STATUS`),
  after computing charging:
  `batteryLedArmer.setCharging(status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)`
- In the service teardown/`onDestroy`: `try { batteryLedArmer.stop() } catch (_: Exception) {}`

(Exact line numbers found during execution by reading the file; match the
existing controller start/stop and battery-receiver locations.)

**Step 3: Build the app**

Run: `clients/glasses/gradlew -p clients/glasses :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

**Step 4: Commit**

```bash
git add clients/glasses/app/src/main/java/com/repository/glasses/listener/power/BatteryLedArmer.kt \
        clients/glasses/app/src/main/java/com/repository/glasses/listener/service/ListenerService.kt
git commit -m "listener: arm battery LED on charging + 60s stillness"
```

---

## Task 7: Full deploy + end-to-end on-device verification

**Files:** none (verification only).

**Step 1: Deploy app (phone first per convention is N/A here; glasses only)**

Run: `bash Recon/scripts/deploy-to-glasses.sh` (installs app; daemon already
deployed in Task 4). If daemon changed since Task 4, re-push + reboot it too.

**Step 2: On charger, on a table (still)**

Place glasses on charger, stationary. Within ~60s the LED should light the
battery color. Verify:
```bash
adb -s <GLASSES_SERIAL> shell 'su 0 cat /sys/class/leds/red/brightness /sys/class/leds/green/brightness'
adb -s <GLASSES_SERIAL> shell 'cat /data/local/tmp/glasses-power-daemon.log | grep led: | tail'
adb -s <GLASSES_SERIAL> shell 'cat /sdcard/Download/glasses-client.log 2>/dev/null | grep -E "BatteryLedArmer|BatteryLedControl" | tail'
```
Expected: app log shows `pending arm` then `armed`; daemon log shows
`led: battery pct=..`; sysfs matches the band.

**Step 3: Move the glasses**

Pick them up / shake. Expected: LED goes dark within a second or two
(StillnessSensor reports moving -> flag 0 -> daemon clears). App log: `disarmed (stillness=false)`.

**Step 4: Wear-while-charging test**

Put glasses on while charging. Expected: LED never lights (continuous
micro-movement prevents the 60s-still arm). Confirm no `armed` log appears.

**Step 5: Record a short clip of the table->move->dark transition and bounce it
to Telegram** per user-global instructions; quote the shortId.

**Step 6: No commit (verification).** If any step fails, return to the relevant task.

---

## Task 8: Documentation -- glasses CLAUDE.md LED section

**Files:**
- Modify: `clients/glasses/CLAUDE.md` (extend the existing "LED Control" subsection under "ADB via USB Cable")

**Step 1: Add the multi-channel sysfs + battery-indicator documentation**

Append to the existing LED Control section: the 4 sysfs channels
(`/sys/class/leds/{red,green,blue,white}/brightness`, 0-255, root-only), the key
fact that stock `sendEvent(type,id)` lights ONE channel while direct sysfs writes
light any combination (red+green together verified), a short reference of
known-working steady `sendEvent` colors (green=(1,1013)/(3,3018), red=(3,3016),
blue=(4,4011), white=(2,2014)/(3,3021); full table in
`Recon/rokid-docs/LED-EVENT-TABLE.md`), and the battery indicator: daemon-owned,
gated on charging + app-detected 60s stillness via
`/data/local/diy-overlay/glasses-led-battery-arm`, colors green>=45 /
green+red 15-45 / red<15, re-asserted every 5s.

**Step 2: Commit**

```bash
git add clients/glasses/CLAUDE.md
git commit -m "docs: document glasses LED sysfs channels + battery indicator"
```

---

## Done criteria

- Daemon lights the correct battery color only when charging + arm flag set;
  clears instantly on disarm/unplug; never leaves the LED stuck (cleared on exit).
- App arms only after 60s continuous stillness while charging; disarms instantly
  on movement or unplug; never arms while worn.
- LED never touches blue/white (no collision with capture privacy light / BT).
- `clients/glasses/CLAUDE.md` documents the LED control mechanism.
