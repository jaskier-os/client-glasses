# Glasses firmware flashing -- scripts and procedure

Low-level EDL/QDL flashing for the Rokid AR Lite (glass15 / Qualcomm "neo" SoC).
These are the STOCK-image flash helpers; the rooted-image builder is one level up
(`../root-firmware.sh`). Stock images are not committed -- they live in the OS cache
fetched by `../fetch-os.sh` into `../os-cache/current/`.

## Scripts in this folder

- **flash.sh** -- flash a full STOCK build over EDL. Runs from `../os-cache/current/`
  (the extracted OTA) and calls `qdl --storage emmc xbl_s_devprg_ns.melf
  rawprogram*.xml patch*.xml`, which writes every partition in the rawprogram lists.
  Use this to return a device to clean stock.
- **generate_packet.sh** -- the vendor's build-time packaging script (from the Rokid
  AOSP tree). It collects boot/dtbo/persist/recovery/metadata/super/userdata + abl/
  vbmeta out of an `out/target/product/neo/` build, sparses/splits the big images,
  and zips them into the flashable `ar1-<ver>-<variant>.zip` that the OTA server
  serves. You only need this if you are building the OS yourself; for flashing a
  prebuilt OTA, `../fetch-os.sh` already gives you the extracted result.
- **rawprogram0.xml / rawprogram2.xml** -- qdl partition program lists for physical
  partitions 0 (main eMMC LUN: GPT, boot, super_1..7, abl, vbmeta, ...) and 2 (the
  small CDT/DDR LUN). `rawprogram*` is matched by flash.sh's glob.
- **patch0.xml / patch2.xml** -- qdl GPT patch lists (last_grow / backup-GPT sector
  fixups) applied after the rawprogram writes, for partitions 0 and 2.

These six were lifted from the stock OTA so the flash list is versioned alongside the
tooling. At flash time the matching binaries are read from `../os-cache/current/`.

### Obsolete / situational xml one level up (`../`)
`root-firmware.sh` emits its own `rawprogram_super4.xml` (GPT + abl_old + super_4
only). The other `../rawprogram_dtbo.xml`, `../rawprogram_dtbo_restore.xml`,
`../rawprogram_stock_recovery.xml` are NOT used by the main rooted flow -- they are
one-off helpers for re-flashing just the dtbo or recovery partition. Keep them only
if you do partial dtbo/recovery flashes; otherwise they can be removed.

## Non-interactive flashing (enter-edl + QDL, no user interaction)

The goal is to flash without pressing the EDL test-point by hand. On a rooted device
the overlay ships an `enter-edl` helper (built by `../root-firmware.sh` into super_4
at `/system/bin/enter-edl`). It arms the Qualcomm download-mode knobs then reboots
straight into 9008 EDL:

```
# enter-edl (on-device, runs as root): arms download_mode then `reboot edl`
for knob in /sys/module/msm_poweroff/parameters/download_mode \
            /sys/kernel/dload/emmc_dload \
            /sys/devices/soc0/select_image \
            /sys/kernel/debug/qcom_rtb/reset \
            /proc/sys/kernel/reboot_mode; do
    [ -w "$knob" ] && echo 1 > "$knob"
done
sync; reboot edl
```

End-to-end non-interactive sequence from the host (device booted + adb visible):

```bash
# 1. Make sure the OS cache exists (downloads + extracts the OTA once).
bash ../fetch-os.sh

# 2. Trigger EDL from userspace (no test-point), then wait for the 9008 device.
adb shell su 0 enter-edl              # or: adb shell enter-edl  (helper is setuid-root in the overlay)
#   the device drops off adb and re-enumerates as QUSB_BULK 05c6:9008
until lsusb | grep -qi '05c6:9008'; do sleep 1; done

# 3. Flash. qdl talks Sahara+Firehose over the 9008 bulk endpoint; fully unattended.
bash flash.sh                          # full stock, OR:
sudo qdl --storage emmc \
    ../os-cache/current/xbl_s_devprg_ns.melf \
    ../rawprogram_super4.xml \
    ../os-cache/current/patch0.xml      # rooted super_4 only (after root-firmware.sh)

# 4. qdl resets the device when done; wait for boot.
until adb get-state 2>/dev/null | grep -q device; do sleep 2; done
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
```

If the device is NOT rooted (no `enter-edl` helper) you must enter EDL the hardware
way (test-point / button combo) -- there is no safe software trigger from stock.

## Hard rules (firmware -- read before flashing anything)

- NEVER flash modified `vbmeta.img` / `vbmeta_system.img`. The ABL is STRICT on the
  vbmeta header signature even in orange/unlocked state -> instant fastboot brick.
  Userspace AVB is permissive and tolerates a modified super as long as the dm-verity
  hashtree is regenerated with the stock salt (which `root-firmware.sh` does).
- NEVER flash an untested boot/super image to the ACTIVE A/B slot. Rokid ABL has no
  auto-rollback; a bad active slot bootloops with no USB enumeration and only a
  physical EDL test-point recovers it. Flash the INACTIVE slot, `set_active`, validate,
  and keep the good slot to fall back to.
- The rooted super_4 ships with the **1.17 ABL** (`../abl_old.elf`), flashed to
  abl_a/abl_b by `rawprogram_super4.xml`. Reason: 1.18.100's stock ABL adopted the
  AOSP libavb hardening that turns chained-vbmeta hashtree-descriptor mismatches into
  fatal errors even on orange, so a rooted super_4 (whose system root_digest doesn't
  match stock vbmeta_system) bootloops on the 1.18 ABL. 1.17 ABL tolerates the
  mismatch. Both ABLs are signed with the same Qualcomm Test Key 0, so XBL accepts
  either. CONSEQUENCE: after any full-stock flash (`flash.sh`, which writes the 1.18
  ABL), you MUST re-run the rooted `qdl ... rawprogram_super4.xml ...` to restore the
  1.17 ABL, or the rooted super_4 won't boot.
- NEVER touch USB state programmatically (no usbreset / unbind-bind / udevadm) if qdl
  or adb can't see the device -- report and physically replug instead.
- After EDL/QDL or any reflash, always poll `getprop sys.boot_completed`==1 before
  assuming the device is up; STOP and report if it never boots (don't improvise on an
  unbooted device).

## Runtime overlay (Tier-2) -- why a reflash isn't always needed

`root-firmware.sh` bakes a Magisk-style file overlay into super_4: files dropped into
`/data/local/diy-overlay/<abs-path>` are bind-mounted over their targets at
post-fs-data. So hook-library content (e.g. `libsinkconn_hook.so`) and the priv-app
APKs can be iterated with `adb push` + reboot, no reflash. What DOES need a reflash is
anything that changes a `*.rc` file content (e.g. the `setenv LD_PRELOAD ...` line in
init.zygote64_32.rc) -- init only parses rc at boot.

- The glasses listener priv-app (`com.repository.glasses.listener`) only gets its
  `BLUETOOTH_PRIVILEGED` etc. grants when loaded from `/system/priv-app`. super_4 ships
  a 1-byte stub there (the ~100MB debug APK doesn't fit); the real APK is bind-mounted
  from `/data/local/diy-overlay/...`. `../root-firmware.sh --post-flash` pushes it and
  removes any `/data/app` sideload shadow. A plain `adb install` does NOT grant the
  privileged perms. See the repo-root CLAUDE.md for the full deploy rules.

## Where to get OS builds

- Default/pinned OTA: `https://rokid-glass-ota.oss-cn-hangzhou.aliyuncs.com/dailybuild/glass15/<version>/RVE01-<version>.zip`
  (pinned in `../fetch-os.sh`: 1.18.100-20260426-150101).
- Community firmware index / mirror: `https://rokid.andersmadsen.dk/firmware`
  -- use this to find other OS versions if a specific build is needed.
