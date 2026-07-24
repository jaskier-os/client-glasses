# firmware

Rooting / flashing tooling for the Rokid Glasses (glass15 / Qualcomm "neo"). The
large OS images are NOT committed -- they are fetched on demand into a gitignored
cache.

## Quick start

```bash
bash fetch-os.sh            # download + extract the stock OTA into os-cache/current/
bash root-firmware.sh       # build the rooted super_4.img (reads stock from os-cache)
# flash (device in EDL) -- see scripts/CLAUDE.md for the non-interactive sequence:
sudo qdl --storage emmc os-cache/current/xbl_s_devprg_ns.melf \
         rawprogram_super4.xml os-cache/current/patch0.xml
bash root-firmware.sh --post-flash   # push priv-app APKs once the device is back up
```

## Layout

- `fetch-os.sh` -- downloads a stock OTA zip (Aliyun OTA server, version pinned inside)
  and extracts the raw partition images into `os-cache/<version>/`, with
  `os-cache/current` symlinked to it. Other versions: pass a version arg, or see the
  community index at https://rokid.andersmadsen.dk/firmware .
- `root-firmware.sh` -- builds the rooted `super_4.img` (root + SELinux-permissive +
  the diy-overlay engine + enter-edl helper + patched touchpad driver + the
  sinkconn/a2dpduck hooks), regenerates the AVB hashtree, and emits
  `rawprogram_super4.xml` (GPT + 1.17 abl_old + super_4). `--post-flash` deploys the
  Tier-2 priv-app APKs. Reads stock images from `os-cache/current` (override STOCK_DIR=).
- `abl_old.elf` -- 1.17 ABL, flashed to abl_a/abl_b by the rooted flow (the 1.18 ABL
  bootloops a rooted super_4; see scripts/CLAUDE.md).
- `rawprogram_dtbo.xml`, `rawprogram_dtbo_restore.xml`, `rawprogram_stock_recovery.xml`
  -- situational single-partition (dtbo / recovery) flash helpers; not part of the main
  rooted flow.
- `scripts/` -- STOCK-image flash helpers (flash.sh, generate_packet.sh, the
  rawprogram*/patch* lists) + **scripts/CLAUDE.md** (per-script descriptions, the
  non-interactive enter-edl + QDL procedure, and all the firmware hard rules).
- `sinkconn-hook/`, `a2dpduck-hook/` -- native LD_PRELOAD hooks baked into super_4 by
  root-firmware.sh (A2DP sink-connection cap lift, ducking). `*/build.sh` builds them;
  `sinkconn-hook/deploy.sh` does the no-reflash bind-mount + reboot dev iteration.
- `OVERLAY-README.md` -- full diy-overlay design reference.
- `PSOC-TOUCHPAD-BOOTLOADER.md` -- reverse-engineered notes on the PSoC4000R touchpad
  MCU bootloader (separate subsystem from the OS flashing).
- `os-cache/` -- gitignored OS image cache (populated by fetch-os.sh).

See **scripts/CLAUDE.md** for flashing procedure + safety rules before touching a device.
