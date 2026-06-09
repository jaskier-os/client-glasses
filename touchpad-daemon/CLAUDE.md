# CLAUDE.md -- Touchpad Daemon + PSoC Firmware Patching

Covers the `rokid-touchpad-daemon` native binary AND the `firmware-patch/`
pipeline for modifying the Cypress **PSoC 4000R** touchpad MCU firmware itself.
The daemon's runtime behavior (scroll scaling, `KEY_PROG1` deferral, app-side
remap, overlay-vs-reflash deploy) is documented in the parent
`AI/clients/glasses/CLAUDE.md` -> "Touchpad Daemon + App-Side Scroll". This file
is about the **chip firmware** and the **PSoC register/RE facts**.

IMPORTANT: NEVER USE EMOJIS ANYWHERE IN LOGGING, CODE, OR TEXT.

## What the touchpad hardware is

- Cypress **PSoC 4000R** MCU (ARM Cortex-M0+, Thumb, LE) on I2C-1 addr `0x08`,
  driver `Rokid,PSOC-TP-R`, input device `/dev/input/event1`.
- The chip runs **CapSense** firmware: it scans the capacitive electrodes,
  classifies gestures internally, and emits discrete keycodes
  (`KEY_UP/DOWN/LEFT/RIGHT/ENTER/BACK/PROG1-3/F13/F14/DASHBOARD`). Raw
  coordinates do NOT leave the chip by default.
- The driver `.ko` (`/system/lib/modules/psoc_ts_drv_right.ko`, bind-mounted
  from the DIY overlay over stock `vendor_dlkm`) **embeds two complete cyacd
  firmware images** (`stringImage_1v8` ~174 rows, `stringImage_1v9` ~176 rows,
  selected by hardware ID at runtime) and **auto-reflashes the chip on
  insmod/boot when the embedded `fw_version_code` (0x90 = 144) differs from the
  chip's reported version.** This is the patch delivery channel -- NOT the
  brick-prone `program_fw` sysfs write the docs warn about.

## Firmware patch pipeline (`firmware-patch/`)

This is a PROVEN, already-shipped channel. Three patches have gone through it:
`patch_uxtb.py` / `patch_uxtb_v2.py` (v1/v2, pos1 upper byte) and
`patch_resolution.py` (v3, xResolution 100->1000 for 10x finer position).

### Files

| File | Role |
|---|---|
| `cyacd_codec.py` | cyacd row parse/serialize + checksum. `find_all_rows()`, `parse_cyacd_text()`, `CyacdRow` (`.computed_checksum`, `.checksum_ok`, `.serialize()`). |
| `extract.py` | `./extract.py stock.ko OUT_DIR` -> `stock.cyacd`, `stock_offsets.json` (per-row .ko file offsets), `image1_flat.bin` / `image2_flat.bin` (flat images keyed by rowNum*64). |
| `patch_<x>.py` | Each takes `--in-cyacd` -> `--out-cyacd`, edits specific flash bytes, re-checksums the touched row. Use `patch_resolution.py` as the template for a new patch. |
| `embed.py` | Writes patched rows back into a COPY of the stock `.ko` at their original file offsets, re-checksums, and (with `--bump-version N`) bumps `fw_version_code` at .ko file offset `0x132A0` (stock `0x90`). |
| `disasm.py` | Thumb disasm helper for locating patch sites. |

### End-to-end recipe

```bash
cd AI/clients/glasses/touchpad-daemon/firmware-patch
# 1. extract once (stock .ko -> cyacd + offsets)
./extract.py /path/to/stock/psoc_ts_drv_right.ko /tmp/psoc-out
# 2. apply a patch (cyacd -> cyacd)
python patch_resolution.py --in-cyacd /tmp/psoc-out/stock.cyacd \
    --out-cyacd /tmp/psoc-out/patched.cyacd --resolution 1000
# 3. embed back into a .ko copy + bump version so the driver reflashes
python embed.py --in-ko /path/to/stock/psoc_ts_drv_right.ko \
    --in-cyacd /tmp/psoc-out/patched.cyacd \
    --offsets /tmp/psoc-out/stock_offsets.json \
    --out-ko ../build/psoc_ts_drv_right.ko --bump-version 1
# 4. deploy: push .ko, rmmod/insmod; driver reflashes the chip on insmod
cd .. && ./deploy-driver.sh patch
# RECOVERY: ./deploy-driver.sh revert   (reloads stock vendor_dlkm .ko)
```

### cyacd format + checksum (from `cyacd_codec.py`)

```
: aa rrrr llll DD..DD cc
  aa   arrayId (1B)
  rrrr rowNum  (2B, BIG-endian)
  llll dataLen (2B, BE) = 0x0040 = 64 (PSoC4000 row size)
  DD.. data (64B)
  cc   checksum (1B)
checksum = (-(arrayId + rowNumHi + rowNumLo + lenHi + lenLo + sum(data))) & 0xFF
```

- App flash rows are **80..255** (app base `0x1400` = 80*64). Bootloader
  (`0x0-0x13FF`) is NOT in the cyacd -- these are application images.
- **Flash addr -> row:** `row_num = flash_off // 64`, `byte_off = flash_off % 64`.
- A patch must edit the correct image: `1v8` and `1v9` carry the SAME flash
  address with DIFFERENT bytes (HW-rev calibration). Match by the stock value
  at the site (see `patch_resolution.py`: it patches only the row copy whose
  bytes equal the expected `1v9` stock values; skips the `1v8` variant).

### Safety

- **Always reflash the UNMODIFIED extracted image first** to prove the
  round-trip before flashing a real patch.
- `./deploy-driver.sh revert` reloads the stock module = recovery, as long as
  the bootloader region is intact (we never touch it).
- A failed/interrupted reflash that corrupts the bootloader = SWD-only
  recovery (soldering). The version-gated reflash-on-insmod is the routine
  path; risk is MODERATE, not the catastrophic framing some notes imply.

## Firmware RE facts (Ghidra, established 2026-05-31 -- do NOT re-derive)

> CRITICAL (established 2026-06-02 -- THE most important fact): **this device is
> `ro.boot.HWID = 5`, so the driver flashes the *1v8* firmware image, NOT 1v9.**
> The driver picks the image from a global `ts_data+0x424` set at probe to
> `(hwid < 5) ? 1 : 0`; `program_fw@0x6690` then flashes `stringImage_1v9` (177
> rows) when that global is 1 (hwid<5) and `stringImage_1v8` (175 rows) when 0
> (hwid>=5). HWID 5 -> global 0 -> **1v8 live**. The two images are interleaved
> in the embedded cyacd: for row 0xD8 the FIRST copy (idx181) is 1v9, the SECOND
> (idx243) is 1v8. **ALL patches must target the 1v8 copy.** An entire session
> was burned patching the 1v9 copy (dead code on this device): every flash
> printed "Bootloader load App success!" yet on-chip behavior never changed
> because the patched image is not the one that runs. The 1v8 builder is NOT at
> flash 0x3600 (that address in 1v8 is an unrelated math helper); 1v8's builder,
> getter, widget table, and snsList are all at DIFFERENT flash/SRAM addresses
> than 1v9's -- re-derive them for 1v8, do not reuse 1v9 addresses.
>
> Version gate (driver reflash-on-probe @0x498): `reflash IF chip==0xFF OR
> (code!=2 AND chip!=code)`. `code` = `get_firmware_version@0x658c` = hwid-derived
> (g=0->144, g=1->31, else 255) -- NOT the immediate at file 0x75B8 (that only
> feeds the hwid>=5 branch and editing it does not change reported code). `chip`
> = I2C cmd=5 read. `program_fw_store@0x24f4` flashes UNCONDITIONALLY when the
> sysfs byte=='1' (no gate) -- so `echo 1 > program_fw` is the reliable trigger,
> but it flashes the LIVE-hwid image, so the patch must be in that image's copy.
>
> *** TRUE ROOT CAUSE (2026-06-02, DEFINITIVE): `program_fw` CANNOT reprogram this
> chip -- the I2C flash physically FAILS. dmesg during `echo 1 > program_fw` shows:
> `i2c_geni a90000.i2c: IO lines in bad state, Power the slave` flooding the bus,
> `IIC error -6` (ENXIO), then **`Psoc Bootload Err : 0x04`** and `Psoc retry
> program fw, retry_cnt = 1`. Only **2** `psoc_ts_write_internal` calls happen
> during a "flash" (a real 177-row program would be 177+). The bootloader-entry/
> row-program phase ERRORS OUT at the I2C level; the chip is NEVER reprogrammed.
> The trailing `Psoc Bootloader load App success!` is just the app RE-LAUNCH of
> the EXISTING (unchanged) firmware, NOT a successful write. So EVERY patch this
> entire effort -- 1v8 or 1v9, builder or marker, embed.py or direct-bytes -- was
> correct but NEVER REACHED THE CHIP, because the flash transport is broken.
> The bus contention is likely the `mp2724-charger 1-003f` on the SAME i2c-1 bus
> (it also throws `read reg failed ret=-6` during the flash) and/or the bootloader
> entry putting SCL/SDA "in bad state". FIX DIRECTIONS to try: (1) quiesce other
> i2c-1 devices during flash (the charger driver) so the bus is clean for the PSoC
> bootloader; (2) check pullups/`psoc,rst-gpio` reset timing; (3) flash via SWD
> instead of I2C bootloader (the `swd_vdd` sysfs hints SWD exists -- though user
> said no SWD port access); (4) investigate the i2c_geni "IO lines in bad state"
> recovery. Bootload Err 0x04 = CYRET command/device error from the Cypress
> bootloader. Until the I2C flash transport works, NO firmware patch can land,
> and all the builder/image/checksum analysis below is moot for delivery (though
> correct for authoring once the transport is fixed). ***
>
> IMAGE SELECTION CORRECTION (verified via pyelftools, supersedes earlier agent):
> `.text` is at file offset 0x1000 (vaddr 0). `program_fw@0x65e0`: reads
> `g=*(ts_data+0x424)`; g==0 -> flashes **stringImage_1v9** (x24), g==1 ->
> stringImage_1v8 (x25). probe sets g=(hwid<5)?1:0. **HWID=5 -> g=0 -> the device
> flashes/runs stringImage_1v9** (NOT 1v8 as an earlier agent claimed). The live
> host I2C block layout (0x13..0x18 populated) confirms 1v9. The firmware is
> stored as `stringImage_1v9/1v8` POINTER TABLES in .data (filled by
> `.rela.data.__cfi_jt_init_module` relocs, 177/175 entries) pointing to ASCII
> cyacd row strings in .rodata. 1v9 builder row data @.ko 0xf966 (flash 0x3600);
> 1v8 builder @.ko 0xaf69 (flash 0x3640). cyacd row fmt: `:AAAA RR LLLL <data>
> <cksum>`; flash addr = rowNum*64 (rows 80..255), base 0x1400, confirmed.
>
> STILL-OPEN (now subordinate to the I2C-flash-transport blocker above): **patched
> PSoC firmware does NOT execute on-chip, no matter which builder copy is patched
> -- because the flash never lands (see TRUE ROOT CAUSE).**
> Findings, all verified by DIRECT .ko byte-patching (NOT embed.py -- see its bug
> below) + the 0xBEEF/0xEF marker-write oracle (patch builder tail to write a
> constant to a host slot, flash, read it back over I2C):
> - The LIVE host I2C block (read raw via I2C_RDWR, 25 bytes) = `0b:ac 0c:6a
>   0d:ae 0e:6a 0f:64 10:00 11:00 12:00 13:5a 14:6a 15:59 16:6a 17:64 18:00`.
>   0x0B/0x0C and 0x0D/0x0E = X/Y centroid (~0x6aXX); 0x0F=0x64; 0x13/0x15 diag;
>   0x17=0x64. This layout (populated 0x13..0x18) matches the **1v9** builder's
>   write pattern, NOT the 1v8 builder (which writes only 0x0B..0x12). So the
>   running firmware looks like 1v9 -- contradicting the HWID=5->1v8 mapping.
> - Marker test on the 1v9 builder (row 0xD8 @.ko-ascii 0xf966, flash 0x3600,
>   tail `movs r0,#3;bl 0x3460` -> `r0=0xBEEF`): host 0x17 stayed **0x64**, NOT
>   0xEF. 1v9 builder does NOT execute.
> - Marker test on the real 1v8 builder (row 0xD9 @.ko-ascii **0xaf69** -- a
>   DIFFERENT copy than the 0x10bad fragment the agent disassembled; this one is
>   a true builder: push{lr};ldr r3=0x20000070(centroid);ldr r2=0x20000108(host
>   base); strb centroid->0x0B..; getter@0x3460 baseline->0x0F/0x10; tail
>   ldrh[r3,#0xe]->0x11/0x12): patched tail to write 0xEF to host 0x11. Host 0x11
>   stayed **0x00**, NOT 0xEF. 1v8 builder does NOT execute either.
> - EVERY flash: `Start psoc fw program / Psoc Bootloader load App success! / End
>   psoc fw program`, all `psoc_ts_write_internal ret = 0`. The live on-device
>   .ko provably contains the patched bytes (pulled it, disasm'd the patch). Yet
>   NO patch ever changes chip behavior.
> CONCLUSION (high confidence): **`program_fw` "success" is misleading -- it is
> NOT writing the .ko's embedded cyacd rows to the chip.** Either it flashes a
> cached/different source, the row-write phase is skipped/gated, or the chip's
> bootloader rejects the rows. The two embedded images + which-image-is-live is a
> RED HERRING relative to this: the flash itself isn't applying our bytes.
> NEXT: (1) RE `program_fw`/`BootloadStringImage` with CORRECT ELF section
> mapping (use pyelftools to map vaddr<->file-offset; file offset != vaddr for
> this aarch64 .ko -- the 0x75B8 "version" and 0x65e0 "program_fw" raw-offset
> disasms are UNRELIABLE without it) to find WHERE it reads firmware bytes and
> whether it verifies/gates per row. (2) Consider the insmod-reflash path
> (rmmod+insmod or the version-gate) as an alternative trigger -- but note the
> gate is hwid-derived and resisted our version edits. (3) The marker-oracle is
> the ONLY trusted "did it run" signal -- keep using it.
>
> EMBED.PY BUG (found 2026-06-02): the embedded cyacd has DUPLICATE (arrayId,
> rowNum) keys (two arr=0 row=0xD9 at .ko offsets 0xaf5e and 0x10ba2, two row
> 0xD8 at 0xf95b/0x11d91 -- the 1v8/1v9 interleave). embed.py maps patched rows
> back to .ko slots by (arrayId,rowNum) only, so it cannot distinguish the two
> copies and may write a patch to the WRONG physical slot (or both). It also
> errored `serialized len 169 != slot total 141` on a multi-row patch. PREFER
> direct .ko byte-patching: find the row's ASCII-hex data by searching the .ko
> for the known data-hex string, edit in place, recompute the cyacd row checksum
> `(-(arrId+rowHi+rowLo+lenHi+lenLo+sum(data)))&0xFF`, write the 2 ASCII checksum
> chars after the 128 data chars. Builder copies: 1v9 row0xD8 data @0xf966
> (flash 0x3600); 1v8 row0xD9 data @0xaf69 (flash 0x3640, the REAL builder, not
> the 0x10bad fragment).

Image: PSoC 4000R, Silicon ID `0A48119A`, Cortex-M0+ Thumb LE. Load the flat
`.bin` in Ghidra as `ARM:LE:32:Cortex`, **base 0x1400** (NOT 0x0 -- bootloader
absent), reset handler `0x3500`, vector table at `0x1400` (SP `0x20000800`).

### I2C host register map (CERTAIN -- `I2C_BuildStatusBlock`: 1v9 @0x3600, 1v8 @0x3640)

The chip serializes a FIXED 25-byte status block (host offsets `0x00..0x18`)
into SRAM `0x20000108`. It is **NOT EZI2C** -- reading host offset > 0x18 NAKs
(verified on-device). So there is no SRAM-window passthrough; the only way to
read a new value is to make the builder WRITE it into one of the 25 bytes (this
is exactly how the RawCount patch below works).

| Host reg | Meaning |
|---|---|
| 0x07 bit0 | touch flag (derived from diff; **dies ~1.5s into a still hold**) |
| 0x08 | position/status byte |
| 0x0B/0x0C | touchpad **X centroid** (post-baseline; collapses under absorption) |
| 0x0D/0x0E | touchpad **Y centroid** (same) |
| 0x0F/0x10 | a sensor **Baseline** (from slider widget0 snsRAM `+2`) |
| 0x17/0x18 | **RawCount** of slider widget0 (snsRAM `+0`) -- **PATCHED IN, survives absorption.** Stock = an unused diag field. See "SOLVED" below. |

The pre-baseline **RawCount** otherwise lives only in chip SRAM (per-widget
snsRAM; for the device-flashed **1v9** image the touchpad slider is widget0 @
`0x20000194`, with `+0`=RawCount, `+2`=Baseline, `+6`=Diff). Stock firmware
never serialized it; the patch below copies widget0 RawCount into host 0x17/0x18.
(1v8 twin slider snsRAM = `0x200001AC`; the earlier note's `0x200001AC` was the
1v8 address.)

### "Sensor Auto-Reset" -- the hold-to-talk blocker

A motionless finger is absorbed into the CapSense baseline after **~1.5s**;
the chip then reports no-touch while still pressed, and emits NO event at the
real lift. This is the documented Cypress **Sensor Auto-Reset** feature
(continuous baseline update). It is **code-gated, not a data/config bit** --
no sysfs knob (`thres_pct` tested null) and no I2C register disables it.

- The ~1.5s comes from the firmware's `KEY_PROG1` grace path + baseline IIR;
  a still hold produces `KEY_DASHBOARD`(down) -> `KEY_PROG1`(down ~500ms) ->
  `KEY_PROG1`(up ~1.5s, NOT the real lift) -> silence.
- Candidate disable patch (UNVERIFIED, MODERATE-HIGH collateral -- kills drift
  comp, risks stuck-on sensors): baseline-gate at flash **`0x1ea2`**:
  `d2 05` (`bcs`) -> `27 e0` (`b`), forcing the gate to always skip the
  baseline write. The baseline-update chain (renamed in the Ghidra db):
  `CapSense_UpdateSensorBaseline_gated @0x1e4c`, widget loop `@0x1ad6`,
  baseline IIR `@0x1dc8`, `LowBaselineReset @0x1dfc`. 1v9 twin ~ +0x74.
  DO NOT flash this without on-device baseline-vs-raw validation first.

### Consequence for "press and HOLD to talk"

Continuous-hold detection from the STOCK readable signals is impossible -- every
stock host-readable field (flag, position, centroid, baseline) collapses at the
1.5s absorption; the RawCount that survives is not serialized. **SOLVED 2026-05-31
by Option A below: a firmware patch now exposes RawCount over I2C, verified
on-device.** (Option B, the absorption-immune-events UX redesign, remains a
zero-risk fallback: long-press `KEY_KP3` at ~800ms + tap `KEY_ENTER` + swipe
`KP0/KP1`.)

### PARTIAL: a CapSense field is exposable at host I2C 0x17/0x18 -- but it is BASELINE, not raw (on-device 2026-05-31)

The MECHANISM is proven and reusable: patch the **1v9** status-block builder so a
diagnostic host slot (0x17/0x18) carries any chip SRAM value we choose. What is
NOT yet solved: finding a SRAM field that is BOTH whole-pad AND instantaneous.
Every field tried so far is either single-electrode or behaves like a baseline.

The patch mechanism (verified to land the byte on-chip and read back over I2C):
- Builder: `I2C_BuildStatusBlock` 1v9 copy @ flash **`0x3600`** (1v8 twin @ `0x3640`;
  the device flashes 1v9). The builder holds `r2` = host-block base `0x20000110`
  throughout and calls getter `@0x3460` (`movs r0,#idx; bl 0x3460` returns
  `widget_table[idx]` inline-struct `[+4]` snsRAM ptr, then `[ptr+2]`).
- The builder tail writes diag host slots 0x13/0x14, 0x15/0x16, 0x17/0x18 from
  fields the aarch64 driver never reads (driver `psoc_report_work @0x11bc`
  decodes keycodes only from host `0x05..0x09`). So 0x0A..0x18 are free to
  repurpose. We rewrite the `movs r0,#1; bl; mov r3; movs r0,#3; bl` tail
  (flash 0x3626..0x363f, cyacd **row 0xD8** off 0x26, 26 bytes) with an inline
  SRAM load (or a sum loop). No version/reg5 change -> `program_fw` won't loop.
- Read it: host 0x17 (lo)/0x18 (hi) 16-bit LE via a polite `I2C_RDWR` block read
  of 0x00..0x18 (`/tmp/rawhold.c` on host, pushed to /data/local/tmp/rawhold).

WIDGET / SRAM MAP (1v9, anchored by forcing getter(0)->slider sensor0):
- Widget table INLINE entries @ flash `0x3cb0`, stride `0x2c`. Each entry:
  `+4` = RAM_WD getter-mirror ptr, `+8` = RAM_SNS (snsList) array base, `+0xc`
  = a stride-6 mirror.
  - idx0 LINEAR_SLIDER (the touchpad), numSensors=6: mirror `0x20000194`,
    snsList **`0x200001dc`** (stride 10: +0,+2,+4 per sensor), mirror2 `0x20000240`.
  - idx1 PROXIMITY: mirror `0x200001a8`, snsList `0x20000218`.
  - idx2 BUTTON: mirror `0x200001ba`, snsList `0x20000222`.
  - idx3 PROXIMITY: mirror `0x200001ca`, snsList `0x2000022c`.
  - The 6 slider sensors' first-field addrs: 0x1dc, e6, f0, fa, 0x204, 0x20e
    (offsets from 0x20000110 = 0xcc,d6,e0,ea,f4,fe -- all fit `adds r0,#imm8`).

ON-DEVICE BEHAVIOR (what each field actually does -- THE KEY LESSON):
- The touch FLAG (host 0x07 bit0) fires instantly on finger-down ANYWHERE, but
  DIES ~1.5-2s into a still hold (the documented Sensor Auto-Reset absorption).
  This is the only stock instant whole-pad signal, but it is useless past ~2s.
- A SINGLE snsList `+0` field (e.g. slider sensor0 0x1dc, or prox 0x218/0x22c)
  is SINGLE-ELECTRODE: it only rises when the finger dwells over that one
  electrode's physical zone; reads the idle floor (~100) elsewhere or when
  sliding. Proven by a full-length position sweep. So no single read is whole-pad.
- SUM of all 6 slider snsList `+0` fields IS whole-pad (a middle-of-pad hold that
  read 100 single-electrode reads ~698 summed). BUT the value is a delayed STEP,
  not instant: it stays pinned at the idle floor (~100) WHILE the touch flag is
  active (0..~3.5s), then JUMPS to ~698 only AFTER absorption (~6.5s) and persists
  for the rest of the hold; on lift it drops back to ~100 within ~250ms.
- That delayed-step behavior means snsList `+0` is the **Baseline**, not the
  instantaneous RawCount: while the flag is active the baseline-update is
  suppressed (pinned low); at absorption auto-reset lets the baseline climb to
  meet the still finger (-> ~698). The TRUE instantaneous RawCount (written from
  the CSD ADC result register every scan, high from the moment of contact) lives
  in a DIFFERENT SRAM array -- being located now via RE of the scan routine.

NET (current understanding): combining the two stock-ish signals already covers
the use case -- `present = (host 0x07 bit0) OR (summed-snsList > threshold)`.
The flag covers instant down (0..~2s); the summed field covers the sustained
hold (~6.5s+) and the lift edge. The only soft window is a hold released during
the ~2-6.5s gap (flag dead, baseline not yet risen). Closing that gap requires
the true instantaneous RawCount array (in progress) summed across the 6 sensors.

> METHOD CAVEAT: a `snsList[N]` field that "rises under a finger" is NOT proof
> it is RawCount. Distinguish raw (instant jump on contact) from baseline
> (delayed climb that only starts after absorption) by the on-device TIME COURSE,
> not just the magnitude. Earlier notes mislabeled `+0` as RawCount and `+2` as
> Baseline; the observed dynamics show the exposed `+0` field is baseline-like.

> NOTE on flat images: extract.py's `image1_flat.bin`/`image2_flat.bin` are
> UNRELIABLE -- the linker interleaves the 1v8 and 1v9 copies per row_num (100
> rows have two copies), so a naive flat split is a Frankenstein mix. Disassemble
> from the cyacd rows directly, discriminating 1v9 by exact stock bytes (as
> `patch_resolution.py` and `patch_expose_rawcount.py` do), NOT from the flats.

## Reproducing the firmware analysis

Flat images + tools from the last session: extract with `extract.py`, load the
flat `.bin` in Ghidra (`ARM:LE:32:Cortex`, base `0x1400`), disassemble at
`0x3500`, auto-analyze. The cyacd checksum + flat-image build also live in a
standalone `/tmp/cyacd_tools.py` (parse/validate/build_image/recompute_checksum)
but `firmware-patch/cyacd_codec.py` is the canonical, in-repo codec -- prefer it.
