# Screen-stuck-on / Fold-flap / Audio-jump Investigation

Date: 2026-06-07. Device: Rokid AR (Android 12, serial <GLASSES_SERIAL>), USB connected.
READ-ONLY investigation. Nothing on-device modified.

## TL;DR

Two **independent** bug families, plus one **third** mechanism that was the user's
hypothesis but is **NOT** what the evidence shows:

1. **Audio "jump" #1 (the real recurring one): self-perpetuating fold-flap loop in
   `glasses-power-daemon`** while USB is connected. NOT caused by a sysconfig restart.
   The daemon's own `suspend_loop()` resume path re-arms a spurious fold and tears down
   A2DP every ~3 min.
2. **Audio "jump" #2: a hard audio-stack crash at 03:05:23** -- a UBSan `mul-overflow`
   abort inside the custom baked-in SVA HAL `sound_trigger.primary.neo.so`, reached via
   the wakeword/SVA stream start. This kills audio HAL -> audioserver -> AudioPolicy ->
   AudioFlinger -> bt_stack -> listener:backend. Separate from the fold-flap.
3. **Screen-stuck-on: daemon `screen_on` flag desync.** A fold-triggered
   `screen_lock_broadcast` sets `screen_on=0`; if the panel is later re-lit by a wake
   path the daemon does not observe as an input event, `screen_on` stays 0 and the idle
   lock timer never re-fires.

The user's hypothesis (sysconfig restarts -> PsensorObserver seeds `is_spread="0"`
transiently) is **DISPROVEN** by the source: the seed value is always **"1"**, never "0".

---

## Task 1 -- is_spread writer behavior on restart

Writer: `com.rokid.sysconfig.PsensorObserver` inside RokidSysConfig (priv-app, pid 1670).
Source: `Recon/rokid-docs/yodaos/DECOMPILED-APPS/system/priv-app/RokidSysConfig/jadx/sources/com/rokid/sysconfig/PsensorObserver.java`
and `.../ConfigService.java`.

How the property gets written:

- `updateGlassesLegProp(boolean isSpread, boolean isBoot)` is the ONLY writer of
  `vendor.rkd.glasses.is_spread`:
  - `isSpread==true  -> SystemProperties.set("vendor.rkd.glasses.is_spread","1")`
  - `isSpread==false -> ...set(...,"0")`
  - `isSpread` is derived from the extcon state string containing **`DOCK=1`**
    (`str2.contains("DOCK=1")`).
- Init path on service create (`ConfigService.onCreate`, lines ~96-104):
  - If `persist.rkd.enablePsensor` is true (default): constructs `PsensorObserver`, whose
    ctor calls `loadExtconState()`. That reads the real extcon `state` sysfs node and calls
    `updateGlassesLegProp(state.contains("DOCK=1"), /*isBoot=*/true)`.
  - If Psensor disabled: directly `set("vendor.rkd.glasses.is_spread","1")`.

### Does the writer publish "0" on restart? -- NO (not a blind seed)

- There is **no unconditional `set("...is_spread","0")` anywhere**. The only "0" write is
  the genuine `DOCK!=1` branch.
- On a sysconfig restart, `loadExtconState()` reads the **current** extcon `state`
  immediately and synchronously in the ctor (before `start()`/observing). So it publishes
  the **true** current dock/leg state, not a transient "0".
- The default/seed value (Psensor disabled, or our own daemon boot seed in main.c) is
  always **"1"**.
- `mIsSpread` field defaults to `true`.

So **a sysconfig crash+restart does NOT momentarily publish is_spread="0"** unless the
extcon hardware genuinely reads `DOCK!=1` at that instant. If the extcon `state` node ever
transiently reads without `DOCK=1` during an I2C/extcon re-probe, THEN a "0" could be
published for real -- but that is a hardware/extcon-read transient, not a software seed.

### Did sysconfig actually crash/restart? -- NO

- `dumpsys activity processes`: `com.rokid.sysconfig` is pid **1670**, `+1h24m53s` uptime
  -- it has run continuously since boot (~01:50). It did **not** restart during the
  02:23-02:39 fold-flap window.
- Therefore the fold-flap is **not** explained by sysconfig at all.

---

## Task 1b -- What actually caused the fold-flap (root cause of audio jump #1)

`glasses-power-daemon.log` (pid 893) shows a textbook self-perpetuating loop, repeating
every ~189 s from 02:23 through 02:38, while `is_spread` was steady "1" (unfolded):

```
02:23:21 fold=1 -> screen_lock(fold), fold_broadcast(folded=1)  [A2DP teardown]
         fold armed: suspend in 180000ms
... 3 min later ...
02:26:22 suspend: entering freeze
02:26:22 suspend: freeze write failed: Device or resource busy   [USB connected -> EBUSY]
         (x3) -> "3 consecutive freeze failures, aborting suspend loop"
02:26:29 resumed from suspend, fold_folded reset
02:26:30 fold=1   <-- re-detected fold immediately, loop repeats
```

The loop **broke by itself at 02:39:01** (`suspend: woke, folded=0`) and never recurred
(after that only LED ticks). So the flap is intermittent and self-limiting, but each
`fold=1` fires one A2DP teardown via the listener (`ACTION_FOLD_CHANGED folded=true`).

### Why does fold re-trigger right after every aborted suspend?

In `main.c`:

- `suspend_loop()` (lines ~880-928): after a failed freeze it does
  `usleep(500000)` then samples `read_fold_from_spread()` up to 3x. On abort
  (`consecutive_failures>=3`) it `suspend_teardown(); return 0;` **without** resetting
  fold bookkeeping.
- Back in the main loop after `suspend_loop()` returns (lines ~1438-1453): on the
  `!did_shutdown` branch it sets `fold_folded = 0; last_activity_ms = now_ms();`
  ("resumed from suspend, fold_folded reset").
- BUT `fold_raw_last` is **not** reset here. On the next iteration the fold-drain
  (lines ~1369-1394) reads `is_spread`. The PSoC/extcon momentarily returns a fold-looking
  raw value (or the debounce bookkeeping `fold_raw_last`/`fold_change_ms` is stale), the
  raw differs from the just-reset `fold_folded=0` for >= `FOLD_DEBOUNCE_MS` (3000ms,
  line 87), and `fold=1` fires again (lines 1383-1394). Loop closes.

The daemon comments acknowledge the historical hall-polarity inversion bug; the residual
issue is that the **suspend abort/resume path leaves the debounce state machine able to
re-latch a fold** even though `is_spread` is "1". The aborted-suspend branch resets
`fold_folded` but trusts the immediate next `is_spread` sample, which on this
worn+docked+USB hardware can read transiently inconsistent right after a half-resume.

Net: the **A2DP teardown of jump #1 is the fold-flap's `fold_broadcast(1)`**, driven by
the daemon's own suspend-abort/resume loop -- NOT by sysconfig and NOT by the 03:05 crash.

---

## Task 3 -- The 03:05 audio-stack crash (audio jump #2)

This is a DIFFERENT event, ~26 min after the flap stopped. Root cause from
`/tmp/jump2.txt` tombstone (lines 34431-34545):

```
03:05:23.901  F libc: Fatal signal 6 (SIGABRT) in tid 8385 (HwBinder:8299_4), pid 8299 (audio.service)
   Abort message: 'ubsan: mul-overflow'
   #01 sound_trigger.primary.neo.so (abort_with_message)
   #02 sound_trigger.primary.neo.so (__ubsan_handle_mul_overflow_minimal_abort)
   #03 libar-pal.so (SessionAlsaPcm::start)
   #04 libar-pal.so (ACDEngine::ProcessStartEngine)
   #05 libar-pal.so (ACDEngine::StartEngine)
   #06 libar-pal.so (StreamACD::ACDLoaded::ProcessEvent)
   #07 libar-pal.so (StreamACD::start)
   #08 libar-pal.so (pal_stream_start)
   ... vendor.qti.hardware.pal HIDL onTransact
```

Interpretation:

- The crashing module is the **custom baked-in SVA HAL `sound_trigger.primary.neo.so`**
  (matches prior project note: SoundTriggerService binds a Repository custom HAL baked into
  super_4). It hit a **UBSan multiply-overflow** during ACD (acoustic-context-detection /
  wakeword) PCM stream start (`ACDEngine::StartEngine` -> `SessionAlsaPcm::start`).
- This was triggered by the **wakeword/SVA stream start** that the listener backend
  requested. Just before the crash the listener log shows
  `[WWGate] reason=phone-connect ... action=start` and
  `PAL: GetSoundTriggerConcurrencyCount_l: conc enable cnt 1` at 03:03:06 -- i.e. SVA was
  being armed because the companion phone connected.
- The abort cascades: `vendor.audio-hal` (pid 8299) dies -> init kills `audioserver`
  (8298) -> `AudioPolicyService server died`, `AudioFlinger server died`,
  `bt_stack serviceDied: restarting connection with new Audio Hal`,
  `Process com.repository.glasses.listener:backend (pid 8307) has died`.
- `SoundTriggerModule: Underlying HAL driver died` is a **downstream symptom**, not the
  cause. The cause is the UBSan abort in the SVA HAL itself.

So jump #2's audio drop is the whole audio stack going down and restarting, killing any
active A2DP/HFP route -- distinct from the fold-flap A2DP teardown of jump #1.

**Both jumps exist; they are different root causes.** The user is seeing whichever fires.

---

## Task 4 -- Screen-stuck-on mechanism (exact code refs in src/main.c)

State variables: `static int screen_on = 1;` (line 105),
`static long long last_activity_ms` (line 104), `g_cfg.screen_timeout_s` (default 300,
lines 154/204).

The only two places `screen_on` is set back to **1**:

- **Input-event drain, line 1337:** `screen_on = 1;` -- set on ANY evdev input event from
  the watched `input_devices` fds (lines 1320-1339). Comment: "The kernel/input stack
  wakes the display on real key events. We just track that the screen is awake again so
  the idle-lock gate re-arms."
- **Hot-reload, line 1333-1337 region:** `last_activity_ms = now_ms(); ... screen_on = 1;`
  on a config reload (so a reload re-arms the gate).

The only places `screen_on` is set to **0**:

- **Fold trigger, line 1390:** inside `prev==0 && fold_folded==1`: `screen_lock_broadcast("fold"); screen_on = 0; last_activity_ms = now;` (lines 1389-1391).
- **Idle lock, line 1426:** `screen_lock_broadcast("idle"); screen_on = 0;` (lines 1424-1427).

The idle-lock gate (lines 1421-1428):

```c
if (!disabled && screen_on &&
    g_cfg.screen_timeout_s > 0 &&
    (now - last_activity_ms) > screen_timeout_s*1000) {
        screen_lock_broadcast("idle");
        screen_on = 0;
        last_activity_ms = now;
}
```

### The bug

The gate is **guarded by `screen_on`**. Once a **fold** event sets `screen_on=0`
(line 1390) and broadcasts the lock, the daemon believes the panel is OFF. If the panel is
then turned back **ON by a wake path the daemon does NOT see as an evdev input event**, the
`screen_on=1` reset (line 1337) never runs, so `screen_on` stays 0 and the idle-lock branch
(line 1421) is permanently skipped. Result: the panel is physically lit but the daemon's
idle timer never re-fires -> **screen stays on until the user manually double-taps** (the
listener app's own screen-off path, independent of the daemon).

Wake paths that bypass the daemon's evdev fds:

- `PowerManager.wakeUp(...,"psensor")` from `PsensorObserver.mTakeonTask` (wear/take-on) --
  a programmatic wake, generates no key event on the watched input devices.
- A listener-app-initiated screen-on, or any `am`/PowerManager wake.
- Spurious fold at line 1390 that sets `screen_on=0` while the panel is actually still on
  (exactly the fold-flap above): the daemon now thinks screen is off; the next real wake,
  if not an evdev event, leaves it stuck.

This dovetails with bug #1: the **spurious fold itself** sets `screen_on=0` (line 1390)
even when the screen was on, immediately desyncing the flag. The listener app may re-light
the panel on the BT/phone activity without producing a watched evdev event, so the gate
stays disarmed.

---

## Recommended root-cause fix directions (NOT implemented)

1. **Fold-flap / audio jump #1 (daemon):**
   - When `suspend_loop()` aborts due to USB EBUSY, do not let the fold state machine
     re-latch on the immediate post-resume `is_spread` sample. After the
     "resumed from suspend, fold_folded reset" branch, also reset the debounce
     bookkeeping (`fold_raw_last = 0; fold_change_ms = 0;`) and/or require a fresh
     >=3s stable `is_spread=="0"` read **distinct from a just-resumed transient** before
     re-arming. Equivalently: do not arm fold-suspend at all while USB is connected
     (detect EBUSY-prone state up front and skip the suspend/teardown/re-arm cycle), since
     freeze can never succeed over USB anyway -- this removes the entire failing loop.
   - Gate `fold_broadcast(1)` (A2DP teardown) behind a confirmed real fold, not a
     post-suspend-abort re-read.

2. **Audio crash / audio jump #2 (SVA HAL):**
   - The crash is a UBSan mul-overflow inside the custom `sound_trigger.primary.neo.so`
     during ACD stream start. This is a defect in the baked-in HAL. Options: (a) replace
     with the stock `sound_trigger.primary.neo.so` from yodaos-stock vendor.img (consistent
     with the existing SVA-HAL-blocker project note); (b) stop the listener from arming the
     SVA/ACD wakeword stream on this HAL (disable on-device wakeword, route wakeword to
     phone) so `ACDEngine::StartEngine` is never reached; (c) if the HAL is ours/rebuildable,
     fix the integer overflow in the ACD PCM-size computation and drop the `-fsanitize`
     trap or build it non-fatal.

3. **Screen-stuck-on (daemon):**
   - Decouple the idle-lock gate from a possibly-stale `screen_on` flag. Track real panel
     state authoritatively (e.g. read the display power state / `PowerManager.isInteractive`
     equivalent, or a sysfs backlight/`/sys/class/.../brightness` node) instead of inferring
     it from observed input events. Re-arm `last_activity_ms` and `screen_on` whenever the
     panel is observed ON regardless of whether the wake came through the watched evdev fds.
   - At minimum, do NOT set `screen_on=0` on a fold unless the fold is confirmed real
     (ties to fix #1); and add a periodic reconcile: if the panel is actually on but
     `screen_on==0`, set `screen_on=1` and reset `last_activity_ms` so the idle timer
     re-arms.

## Evidence file references

- Daemon source: `AI/clients/glasses/glasses-power-daemon/src/main.c`
  (lines 87, 104-105, 154, 387-394, 880-928, 1180-1215, 1320-1453).
- is_spread writer: `Recon/rokid-docs/yodaos/DECOMPILED-APPS/system/priv-app/RokidSysConfig/jadx/sources/com/rokid/sysconfig/PsensorObserver.java`
  (`updateGlassesLegProp`, `loadExtconState`) and `.../ConfigService.java` (lines 96-104).
- Daemon runtime log: `/data/local/tmp/glasses-power-daemon.log` (02:23-02:39 flap loop).
- Crash log: `/tmp/jump2.txt` lines 34431-34545 (UBSan mul-overflow tombstone),
  28730-29082 (03:03 BT/SVA arm preceding the crash).
- Live: `dumpsys activity processes` -> sysconfig pid 1670, +1h24m uptime (no restart).
  `getprop vendor.rkd.glasses.is_spread` -> `1`.
