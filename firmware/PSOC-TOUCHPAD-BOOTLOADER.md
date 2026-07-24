# PSoC4000R Touchpad MCU Bootloader Notes

The Rokid Glasses right-temple touchpad is a **Cypress / Infineon
PSoC4000R** capacitive slider MCU on I²C bus 1 @ 0x08. Its firmware is
flashed in-system via the kernel module `psoc_ts_drv_right.ko`
(installed at `/system/lib/modules/`, bind-mounted over
`/vendor_dlkm/lib/modules/` at `on init`).

Below is what we have learned reverse-engineering the bootloader and
field-testing flash writes. None of this is documented by Rokid — every
fact below was extracted from the running chip plus the .ko binary.

## Chip + bootloader fundamentals

- **Silicon ID**: `0x0A48119A`. Stored in the cyacd header inside the
  `.ko` (the first .rodata addend referenced by `stringImage_1v8` and
  `stringImage_1v9`).
- **Bootloader**: factory Cypress CyBtldr v1.x, located in the chip's
  protected ROM region (rows below 0x50). We have **no docs and no
  source for this bootloader** — its rules below are inferred
  empirically.
- **Bootloader command-set used by the kernel**: `EnterBootloader`,
  `GetFlashSize`, `EraseRow`, `ProgramRow`, `VerifyRow`,
  `VerifyChecksum`, `SetActiveApp`, `ExitBootloader`. The kernel uses
  CyBtldr_* host library functions baked into `psoc_ts_drv_right.ko`
  (`CyBtldr_StartBootloadOperation @ 0x46e8`,
  `CyBtldr_ProgramRow @ 0x4d64`, `BootloadStringImage @ 0x6790`).

## App memory layout (rows 0x50..0xFF, 16 KB total)

- **Rows 0x00..0x4F**: bootloader (in chip ROM; not in our cyacd; never
  written from host).
- **Row 0x50** (flash 0x1400): start of application — **vector table**
  (initial SP `0x20000800`, reset `0x00003500`).
- **Rows 0x50..0x???**: code (`.text`).
- **Rows 0xCx..0xFx**: data / config region. Includes the CapSense
  widget config struct at flash 0x3CB4 (= row 0xF2 byte 0x34 spilling
  into row 0xF3).
- **Row 0xFF** (last app row): metadata — first 16 bytes
  `be bd 14 00 00 4f 00 00 00 c0 2b 00 ...`. The leading `0xBEBD`
  appears to be a magic/signature; format unknown.

## cyacd format actually accepted by this chip

Per-row layout (ASCII):
```
:<aa><rrrr><llll><DD…DD><cc>
   array_id (1B), row_num (2B BE), data_len (2B BE),
   data (data_len bytes), checksum (1B)
```
Header line preceding the rows: `<silicon_id 4B><rev 1B><checksum_type 1B>`
= 12 hex chars. Rokid ships **checksum_type = 0x00 (sum mode)** —
verified by reading `.rodata + 0x9795` in the kernel module
(`0A48119A0000`).

Row checksum = `(- (array_id + rh + rl + lh + ll + sum(data))) & 0xff`.
Implemented in `cyacd_codec.py`. **Recomputing this checksum after
any data change is required** — incorrect checksums cause
`CyBtldr_ProgramRow` to retry and ultimately fail with
`Psoc bootloader I2C read failed!!!`.

## Two embedded firmware images

The kernel module embeds **two complete firmware variants**, selected at
flash time based on a chip silicon-revision flag at
`ts_data + 0x1A8`:

- `stringImage_1v8` — 175-row array at `.data + 0xC08`, version constant
  144 in `get_firmware_version()` w8==0 path → 31. **Flag value 1.**
- `stringImage_1v9` — 177-row array at `.data + 0x680`, version constant
  144 in `get_firmware_version()` w8==0 path. **Flag value 0. This is
  the variant our chip runs.**

Each array entry is an `R_AARCH64_ABS64` relocation pointing at a single
cyacd row in `.rodata`. Many rows are shared between the two images
(linker dedup); 100 rownums have two distinct payloads, one per image.
**When patching a row that has two payloads, modify only the image_1v9
copy** — match on stock byte content to disambiguate.

## Row-write protection (empirically observed)

| Row range | Write attempts | Result |
|---|---|---|
| 0xEA (code, contains report-builder + uxtb truncation site) | V1 patch (NOP gate), V2 patch (uxtb→lsr) | **Both flashed successfully** after 1-3 retries each. |
| 0xF3 (data, contains CapSense widget config: `xResolution`, `xCentroidMultiplier`) | V3 patch (xResolution 100→1000, multiplier 5120→51200) | **Rejected on every retry**. dmesg shows `Psoc Bootload Err :` repeated 4× then `End psoc fw program` with no `Bootloader load App success!`. |
| Other rows | Not attempted | Unknown. |

**Rule of thumb (provisional)**: code rows are programmable; certain
data/config rows in the high range (≥ 0xF0) are not. The exact
gating mechanism inside the chip's bootloader is opaque. Possible
causes:
- Per-row write-protection bitmap baked into the bootloader at factory
  programming time. Cypress PSoC4 bootloaders support a `g_validRows`
  array indicating writable rows; the chip's copy is queried at
  `EnterBootloader` time.
- A second-tier integrity check on config rows (e.g. an XOR signature
  over the config struct, validated post-write).
- An app-image-level checksum stored in row 0xFF metadata that doesn't
  re-derive when individual config rows are altered.

We have not yet identified which.

## Host-side bitmap (`g_validRows`, .data + 0x4a8)

The `psoc_ts_drv_right.ko` symbol `g_validRows` is a 32-byte (256-bit)
bitmap, one bit per flash row. **The static initialization in the .ko
is placeholder garbage** (`0000…0000a401000000000000`); the bitmap is
populated at runtime by `CyBtldr_StartBootloadOperation` from the chip's
response to a "valid rows" query. Therefore:

- **You cannot tell from the .ko alone which rows the chip allows.**
- **Patching the .ko's `g_validRows` will not bypass chip-side
  protection** because the chip enforces protection independently.
- A kprobe at `CyBtldr_StartBootloadOperation` after `BootloadStringImage`
  reads the bitmap can dump the actual allowed-row list at runtime
  (untested).

## Flashing the chip from userspace

Two paths:

1. **Auto on probe**: kernel reads chip's running version (=
   `i2c_smbus_read_i2c_block_data(0x08, cmd=0x05, len=1)` → byte at
   chip reg 0x05) and compares to embedded `fw_version_code`
   (returned by `get_firmware_version()`). On mismatch, it auto-flashes
   the embedded image.

2. **Manual**: `echo 1 > /sys/devices/platform/soc/a90000.i2c/i2c-1/1-0008/program_fw`.
   Triggers `program_fw_store` → `program_fw()` → `BootloadStringImage`
   unconditionally. Used for testing patches without bumping the version
   constant.

The first attempt typically fails with transient
`Psoc bootloader I2C read failed!!!` errors. Retry once; the second
attempt usually succeeds for writable rows.

## Recovery model

- **Bootloader is in protected ROM** and cannot be erased by the
  CyBtldr_* command set we have (only app rows ≥ 0x50 are
  programmable). Even a maximally-broken application flash leaves the
  bootloader alive on the I²C bus. Re-flashing stock firmware via
  `echo 1 > program_fw` always recovers.
- **No-software-only recovery path** if the bootloader itself is
  corrupted (it shouldn't be; nothing in our flash flow targets
  rows < 0x50). For SWD-based recovery we would need physical access
  to the chip's debug pads — not available on glasses without
  disassembly.

## What we successfully changed in the running chip

- **V2 patch (deployed, currently running)**: row 0xEA bytes
  `c0 b2 d2 b2` → `02 0a c0 b2`. Replaces `uxtb r0, r0; uxtb r2, r2`
  (truncation of the 16-bit slider position to byte) with
  `lsr r2, r0, #8; uxtb r0, r0`. Net effect: pos1 byte at chip reg
  0x08 now exposes the firmware's centroid output without the
  truncation losing precision.
- **Resolution achieved**: 11-step (10 distinct levels across 0..100).
  2× improvement over stock's 21-step.

## What we tried and could not flash

- **V3 patch**: same row 0xEA edit + flash 0x3CCC..0x3CD3 in row 0xF3:
  `xResolution` 100 → 1000, `xCentroidMultiplier` 5120 → 51200. Goal:
  ~100 distinct levels. **Bootloader rejects every attempt.** Reason
  unknown; suspected per-row protection on row 0xF3 or app-image
  checksum mismatch.

## Open work

- Identify the row-protection mechanism. Options:
  1. Live-dump `g_validRows` after `EnterBootloader` via kprobe.
  2. Locate the app-checksum field in row 0xFF metadata; recompute it
     for the patched image; include it in the flash payload.
  3. Patch the kernel module's `BootloadStringImage` to skip the
     pre-check on protected rows, so the host attempts the write
     anyway and we observe the chip-side error code.
- Find a code-row patch that achieves the same behavior without
  modifying row 0xF3. Specifically: locate the slider centroid
  algorithm's read of `xResolution` / `xCentroidMultiplier` (load
  instructions with widget struct base + 0x18 / +0x1C), and replace
  with a sequence that computes a finer position. **This is the
  current line of attack** (see touchpad-daemon/firmware-patch in
  the AI/ tree).
