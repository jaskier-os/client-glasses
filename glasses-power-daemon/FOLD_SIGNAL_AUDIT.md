# Fold Signal Discrepancy Audit (2026-06-06)

Device: Rokid glasses serial <GLASSES_SERIAL>. Glasses physically UNFOLDED throughout incident.

## Verdict

The **daemon is wrong**. It is NOT a polarity inversion and NOT a wrong sysfs node.
The daemon's `hall` read and the app's `is_spread` read are consistent. The spurious
`folded=true` broadcasts come from the daemon's **suspend/freeze-failure path**, which
re-asserts the *stale* `fold_folded=1` state after a freeze abort -- it never re-reads
hall before broadcasting.

## Polarity ground truth (live, glasses UNFOLDED)

| signal | value | meaning |
|---|---|---|
| `hall` | `1` | daemon interprets `==1` as FOLDED (main.c:768, :1047) -- **but see below** |
| `enforce_hall` | `0` | unlatched (daemon wrote 0 at boot, main.c:920) |
| `enforce_psensor` | `1` | **still latched** -- wear/psensor bits forced high |
| extcon3 `state` | `DOCK=1 JIG=1` | DOCK=spread(unfolded)=1, JIG=worn=1 -> worn+unfolded |
| `vendor.rkd.glasses.is_spread` | `1` | `1`=spread=UNFOLDED |
| `vendor.rkd.glasses.is_take_on` | `1` | worn |

Note the daemon's polarity assumption (`hall==1` => folded) DISAGREES with the live
reading (hall=1 while unfolded). On THIS hardware the hall node reads 1 when the magnet
is near in the *worn/docked* posture, so the daemon's compile-time polarity is arguably
inverted -- BUT that is masked at runtime: at boot the daemon reads hall once
(`fold initial=`) and thereafter the steady-state fold transitions are debounced
correctly. The actual fault is in the suspend resume path, not the polarity branch.

## Configuration (confirmed)

- `fold_hall_node = /sys/devices/platform/soc/a90000.i2c/i2c-1/1-0008/hall` (sysfs, NOT evdev)
  - set_defaults() main.c:149; on-device `/data/local/diy-overlay/glasses-power.conf` confirms same.
- There is **no SW_LID evdev device**: event0=`qpnp_pon`, event1=`ROKID,PSOC-TP-R` (touchpad),
  event2=`rokid-touchpad-virt`. So `fold_is_evdev=0`; the evdev SW_LID branch never runs.
- `FOLD_DEBOUNCE_MS = 3000` (main.c:86).
- Daemon IS running: PID 897, state `do_sys_poll`.

## Root cause (daemon log, /data/local/tmp/glasses-power-daemon.log)

The daemon entered its **suspend loop** while it BELIEVED the glasses were folded
(`fold_folded=1` -- a stale value from an earlier hall read, never corrected because
USB is connected and freeze always fails). Repeated entries:

```
20:21:36 897 suspend: woke, folded=1 remain=3237s     <- still_folded=(atoi(hall)==1), hall reads 1
... (166 such lines) ...
```

At each spurious broadcast (20:11:16, 20:15:34, 20:38:26, 20:41:28) the sequence is:

```
20:38:25 897 suspend: woke, folded=1 remain=2228s
20:38:25 897 resumed from suspend, fold_folded reset    <- fold_folded forcibly reset
20:38:26 897 fold=1                                      <- re-evaluated as folded
20:38:26 897 screen lock broadcast (fold)
20:38:26 897 fold broadcast folded=1                     <- SPURIOUS folded=true -> A2DP drop
20:38:26 897 fold armed: suspend in 180000ms, shutdown in 3600s
```

And at 20:11:15:
```
20:11:15 897 suspend: 3 consecutive freeze failures, aborting suspend loop
20:11:15 897 suspend: DWC3 autosuspend restored
20:11:15 897 resumed from suspend, fold_folded reset
20:11:16 897 fold=1
20:11:16 897 fold broadcast folded=1                     <- SPURIOUS
```

### Why debounce did NOT catch it

The 3s debounce only guards the normal `while(!g_stop)` poll path (main.c:~1240). The
suspend path is a SEPARATE loop (`do_freeze`/suspend resume, main.c:~700-790). On
"resumed from suspend, fold_folded reset" the daemon clears `fold_folded` and then the
main loop re-detects `hall==1` as a *fresh* 0->1 transition, firing `fold_broadcast(1)`
immediately with NO debounce window (it treats it as a brand-new fold event, not a blip).
The hall line is not noisy; the value is a legitimate (but semantically wrong) `1` that
the daemon's polarity treats as folded.

### Why the daemon got stuck "folded" in the first place

`fold_folded` became 1 because the daemon's hall polarity reads `hall==1` as folded,
yet on this worn+docked hardware hall=1 is the NORMAL unfolded-but-worn state. The
daemon then tries to suspend (folded => power save), freeze fails because USB is
connected (`suspend: freeze write failed: Device or resource busy`), aborts after 3
failures, resumes, re-broadcasts folded=1, and loops. Every resume = one spurious A2DP
drop. `enforce_psensor=1` keeps the wear/psensor latched, compounding the wrong posture
read.

## App side (correct)

- `FoldPoll` (ListenerService.kt:2975-3009) polls `vendor.rkd.glasses.is_spread` every 5s:
  `"1"->folded=false, "0"->folded=true`. This is the kernel PSoC driver's authoritative
  value. 166 ticks all read `is_spread='1' -> folded=false` (correct).
- `handleFoldChange(folded, source)` (ListenerService.kt:1246): dedups on `lastFoldedState`
  and rate-limits to 2s. It does NOT prefer one source over another -- whichever broadcast
  arrives first and represents a *transition* wins. So a daemon `folded=true` that lands
  while `lastFoldedState=false` is ACCEPTED (passes dedup), flips state, calls
  `audioRouting.setFolded(true)` -> disconnectAllSources(). The next poll tick (`folded=false`)
  then flips it back -> reconnect with reset volume. This is the user-visible drop.

## Recommended unification

Make `vendor.rkd.glasses.is_spread` (kernel PSoC prop) the SINGLE authoritative fold
source and demote the daemon's broadcast:

1. **Preferred:** Stop the daemon from broadcasting `ACTION_FOLD_CHANGED` for audio
   purposes entirely. Let the app's `FoldPoll` (already polling the authoritative prop)
   be the sole driver of `setFolded`. The daemon keeps its internal fold tracking only
   for its own power-off/suspend timer.
2. If the daemon must keep broadcasting: (a) fix its hall polarity to match this hardware
   (or read `is_spread` instead of raw `hall`), and (b) NEVER re-broadcast `folded=1` on
   the "resumed from suspend, fold_folded reset" path without first re-reading the hall
   node AND clearing the stale state -- i.e. re-run the debounce, don't fast-path it.
3. In `handleFoldChange`, add source precedence: treat `source=poll` (kernel prop) as
   authoritative and IGNORE `source=daemon folded=true` unless the poll also agrees within
   the rate-limit window. This makes the app robust even if the daemon stays buggy.
4. Separately: the daemon should not even ARM suspend while USB is connected (it already
   skips the freeze, but it keeps looping and re-broadcasting). Guard the whole
   fold->suspend arm on `!usb_cable_connected()`.
