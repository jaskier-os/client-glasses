# QDL EDL Flashing -- Rokid Glasses (glass15 / Qualcomm "neo")

How to flash the glasses over EDL (Qualcomm 9008) with `qdl`, including running it
as an unattended background task. This is a practical index over the scripts that
already exist in the repo -- prefer the wrappers over hand-rolling a `qdl` line.

> Read the hard rules at the bottom before flashing anything. Wrong slot or a
> modified vbmeta bricks the device to a physical test-point recovery.

## What qdl needs

`qdl` uploads a signed **Firehose programmer** over Sahara, then drives the flash
from **rawprogram** (image->partition maps) + **patch** (GPT fixups) XMLs.

On this device the programmer is `xbl_s_devprg_ns.melf` (NOT a generic
`prog_firehose_ddr.elf`), and storage is **eMMC**.

## Where to get each file

### The qdl tool itself
`qdl` is NOT part of the OS download -- you install it separately, per host OS:

| Host | How to get qdl |
|---|---|
| **Linux** | `apt install qdl` (Debian/Ubuntu ship it) or build from source `github.com/linux-msm/qdl`. This box already has it at `/usr/bin/qdl`. |
| **Windows** | `qdl` is a Linux tool with no native Windows build. Two options: (a) **WSL2** + `usbipd-win` to pass the 9008 USB device into WSL, then `apt install qdl` -- this runs these exact scripts unchanged; or (b) Qualcomm **QFIL** (from the QPST package), the native-Windows Firehose flasher: point it at the same `xbl_s_devprg_ns.melf` programmer + `rawprogram*.xml` + `patch*.xml`. WSL+qdl is recommended since it matches the repo scripts 1:1. |
| **macOS** | build from source, or use the bundled Mach-O copy at `Recon/rokid-docs/yodaos-stock-full/qdl` (that committed binary is **macOS-only**, not Linux). |

> Correction to any earlier note: the repo-committed `qdl` is a **macOS** Mach-O
> binary. The Linux `qdl` here comes from the apt package, not the repo.

### The OS images, programmer, and XMLs (all from the OTA zip)
Everything else -- the Firehose programmer, every partition image, and the
rawprogram/patch XMLs -- ships **inside the stock OTA zip**. You do not download
them piece by piece.

- **OTA zip URL (pinned build):**
  `https://rokid-glass-ota.oss-cn-hangzhou.aliyuncs.com/dailybuild/glass15/1.18.100-20260426-150101/RVE01-1.18.100-20260426-150101.zip`
- **Other versions:** same pattern, swap the version in two places:
  `.../dailybuild/glass15/<version>/RVE01-<version>.zip`
- **Version index / community mirror:** `https://rokid.andersmadsen.dk/firmware`

Inside the zip (wrapped in a single `ar1-<version>-<variant>/` dir -- flatten it):
`xbl_s_devprg_ns.melf` (the programmer), `super_4.img`, `boot.img`, `gpt_*`,
`abl.elf`, `vbmeta*.img`, `rawprogram0.xml` / `rawprogram2.xml`,
`patch0.xml` / `patch2.xml`, etc.

**On Linux with this repo:** `bash fetch-os.sh` does the download + extract for you
into `os-cache/current/`. **On Windows / without the repo:** download the zip
manually, `unzip` it, and run `qdl`/QFIL from the flattened folder -- that folder
already contains the programmer + rawprogram + patch XMLs, so you point the flasher
straight at them:

```
qdl --storage emmc xbl_s_devprg_ns.melf rawprogram0.xml rawprogram2.xml patch0.xml patch2.xml
```

The `rawprogram*.xml` / `patch*.xml` committed in `scripts/` are just versioned
copies of the ones in the zip; at flash time `flash.sh` uses the zip's copies from
`os-cache/current/`.

## Paths

### qdl binary
| Path | Notes |
|---|---|
| `/usr/bin/qdl` | Linux ELF, from the apt `qdl` package -- what `flash.sh` uses on this box |
| `Recon/rokid-docs/yodaos-stock-full/qdl` | committed copy, but **macOS Mach-O only** -- not runnable on Linux/Windows |

(See "Where to get each file" above for installing qdl on Linux / Windows / macOS.)

### Flash wrappers (use these)
| Path | Purpose |
|---|---|
| `AI/clients/glasses/firmware/scripts/flash.sh` | flash a **full STOCK** build (all partitions) |
| `AI/clients/glasses/firmware/root-firmware.sh` | build **rooted super_4** + emit `rawprogram_super4.xml` |
| `AI/clients/glasses/firmware/fetch-os.sh` | download + extract the OTA into `../os-cache/current/` |

### Program / patch XMLs
| Path | Purpose |
|---|---|
| `AI/clients/glasses/firmware/scripts/rawprogram0.xml` | main eMMC LUN (GPT, boot, super_1..7, abl, vbmeta, ...) |
| `AI/clients/glasses/firmware/scripts/rawprogram2.xml` | small CDT/DDR LUN |
| `AI/clients/glasses/firmware/scripts/patch0.xml` / `patch2.xml` | GPT last_grow / backup-GPT fixups |
| `AI/clients/glasses/firmware/rawprogram_super4.xml` | rooted flow: GPT + abl_old (1.17) + super_4 only |
| `AI/clients/glasses/firmware/abl_old.elf` | 1.17 ABL (rooted super_4 needs it; 1.18 ABL bootloops rooted) |
| `AI/clients/glasses/firmware/rawprogram_dtbo*.xml`, `rawprogram_stock_recovery.xml` | one-off partial re-flashes |

Stock binaries themselves are NOT committed -- they live in
`AI/clients/glasses/firmware/os-cache/current/` after `fetch-os.sh`.

## Enter EDL

On a **rooted** device (has the baked `enter-edl` helper), no test-point needed:

```bash
adb shell su 0 enter-edl                 # arms download cookie, reboots to 9008
until lsusb | grep -qi '05c6:9008'; do sleep 1; done
```

`05c6:9008` = QDLoader 9008 = EDL. On a **stock/non-rooted** device there is no safe
software trigger -- use the hardware test-point / button combo.

## Flash

### Full stock
```bash
cd AI/clients/glasses/firmware/scripts
bash flash.sh
# == sudo qdl --storage emmc xbl_s_devprg_ns.melf rawprogram*.xml patch*.xml
#    (run from ../os-cache/current/)
```

### Rooted super_4 (after root-firmware.sh)
```bash
cd AI/clients/glasses/firmware
sudo qdl --storage emmc \
    os-cache/current/xbl_s_devprg_ns.melf \
    rawprogram_super4.xml \
    os-cache/current/patch0.xml
```

> After any full-stock `flash.sh` (writes the 1.18 ABL) you MUST re-run the rooted
> `qdl ... rawprogram_super4.xml ...` to restore the 1.17 ABL, or the rooted
> super_4 won't boot.

## Run qdl as a background task

`qdl` is fully non-interactive over the 9008 bulk endpoint, so it backgrounds
cleanly. Log to a file and poll the log / exit code rather than watching it live.

```bash
cd AI/clients/glasses/firmware/scripts
LOG=/tmp/qdl-flash-$(date +%s).log

# detach from the shell; survives terminal close
sudo setsid bash flash.sh >"$LOG" 2>&1 &
QDL_PID=$!
echo "qdl running as pid $QDL_PID, log: $LOG"

# follow progress without blocking (Ctrl-C leaves qdl running)
tail -f "$LOG"

# check completion later
wait "$QDL_PID"; echo "qdl exit code: $?"     # 0 = success
```

Notes for background runs:
- `sudo` may prompt for a password -- run one `sudo true` first so the credential
  is cached before you background the job, or configure a sudoers rule for `qdl`.
- Do NOT touch USB state programmatically if qdl loses the device -- physically
  replug and report (see rules).
- qdl resets the device when done. Confirm it actually booted afterwards:

```bash
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
echo "device booted"
```

If `sys.boot_completed` never reaches `1`, STOP and report -- do not improvise on
an unbooted device.

## Hard rules (read before flashing)

- **NEVER flash a modified `vbmeta.img` / `vbmeta_system.img`** -- ABL is strict on
  the vbmeta signature even when unlocked -> instant fastboot brick.
- **NEVER flash an untested image to the ACTIVE A/B slot.** No auto-rollback here; a
  bad active slot bootloops with no USB enumeration, recoverable only by physical
  EDL test-point. Flash the INACTIVE slot, `set_active`, validate, keep the good slot.
- Rooted super_4 must ship the **1.17 ABL** (`abl_old.elf`); the 1.18 ABL's libavb
  hardening bootloops a rooted super.
- **NEVER touch USB state programmatically** (no `usbreset` / unbind-bind / udevadm)
  if qdl/adb can't see the device -- report and physically replug.
- After any reflash, always poll `getprop sys.boot_completed == 1` before assuming
  the device is up.

## References
- `AI/clients/glasses/firmware/scripts/CLAUDE.md` -- stock flash + non-interactive EDL sequence
- `AI/clients/glasses/firmware/OVERLAY-README.md` -- rooted overlay / super_4 build
- `Recon/rokid-docs/yodaos-root-full/` -- rooted firmware source
