#!/usr/bin/env python3
"""
!!! DEPRECATED / INEFFECTIVE FOR EXPOSING RawCount -- DO NOT USE FOR THAT GOAL !!!

ON-DEVICE RESULT (verified): enlarging the SetBuffer SIZE arg (0x13/0x19 -> 0xC0)
had ZERO effect on the readable window. Highest readable host offset stayed 0x18,
first NAK at 0x19. Two CERTAIN root causes (see /tmp/program_fw_deploy.md
"SIZE PATCH CORRECTION" and ../CLAUDE.md "I2C host register map"):

 1. The host buffer is NOT an EZI2C view of SRAM. The chip serializes a FIXED
    25-byte status block via I2C_BuildStatusBlock @0x3640, which populates ONLY
    host offsets 0x00..0x18. RawCount (per-sensor snsRAM +0, widget0 SRAM
    0x200001AC) is NEVER copied into that buffer. Bytes past 0x18 are stale, so
    a bigger SIZE just exposes garbage -- host offset 0x84 is NOT RawCount.
    => The SetBuffer SIZE is the WRONG LEVER. To expose RawCount you must edit
       I2C_BuildStatusBlock @0x3640 to copy a sensor's RawCount into a spare
       in-window slot (e.g. the ignored 0x11/0x12 CRC word), then read 0x11/0x12.
       No SIZE change is needed (0x11/0x12 is already inside the 25-byte window).

 2. The driver only reflashes when its compiled fw_version_code != the chip's
    reg5; on-device both were equal, so program_fw was a no-op and the chip kept
    its prior image. Any real firmware edit must be paired with the version-gate
    bump (driver 0x75B8 + image reg5, see embed.py --set-version / --fwver) to
    force a single reflash.

The SIZE-enlarge code below is retained only because the per-image row/anchor
matching is reused by the --fwver (reg5) path, which IS still needed to make the
reflash fire. The bufSize edit itself should be considered a no-op and left at
stock; build the RawCount exposure as a separate I2C_BuildStatusBlock patcher.

------------------------------------------------------------------------------
(ORIGINAL, NOW-REFUTED RATIONALE -- kept for history)
Expose CapSense RawCount over I2C by ENLARGING the EZI2C read window. The premise
was the standard Cypress CapSense Tuner mechanism (EZI2C_SetBuffer1 exposing all
of dsRam). THAT PREMISE IS FALSE for this firmware: it is not EZI2C, it is a
fixed hand-built status block (see above). Auto-reset is left intact either way.

------------------------------------------------------------------------------
HOW EZI2C IS SET UP (verified in Ghidra, 1v8 image, base 0x1400)
------------------------------------------------------------------------------
EZI2C_SetBuffer @flash 0x33b0, called once from the main loop:
    FUN_000033b0(bufSize, rwBoundary, base)
It stores into the EZI2C state struct @0x200000a4:
    state+4  (0x200000a8) = bufSize     <- arg0  (HARD read/total clamp)
    state+6  (0x200000aa) = rwBoundary  <- arg1  (count of WRITABLE bytes)
    state+0xc(0x200000b0) = base        <- arg2

EZI2C ISR boundary logic (CERTAIN):
  * READ/address clamp @0x30d2-0x30d8:
        ldr  r1,[=0x200000a8]   ; r1 = bufSize
        ldrh r1,[r1]
        cmp  r0,r1              ; r0 = host offset
        bcs  reject             ; reject if offset >= bufSize   <-- this is what we widen
  * WRITABLE-bytes calc @0x30e2-0x30f2 (and store loop @0x3178):
        ldr  r1,[=0x200000aa]   ; r1 = rwBoundary (=5)
        cmp  r0,r1
        bcs  -> writable = 0    ; offset >= rwBoundary -> ZERO bytes writable
        else   writable = rwBoundary - offset
    The inbound host-write loop writes exactly `writable` bytes. So host writes
    are physically limited to offsets [0, rwBoundary) = [0, 5). Offsets >= 5 are
    READ-ONLY to the host.

------------------------------------------------------------------------------
THE PATCH (per image; verified call sites)
------------------------------------------------------------------------------
Stock bufSize differs between images:
  1v8 call site @flash 0x3802: movs r0,#0x13 (bufSize=19),  base=0x20000108
  1v9 call site @flash 0x37b8: movs r0,#0x19 (bufSize=25),  base=0x20000110
RawCount(widget0 sensor0) = snsRAM+0:
  1v8 = 0x200001AC = base 0x20000108 + 0xA4  -> host read offset 0xA4
  1v9 = 0x20000194 = base 0x20000110 + 0x84  -> host read offset 0x84
Set bufSize = 0xC0 (192) in BOTH images. 0xC0 > 0xA4 and > 0x84, so the enlarged
read window covers RawCount in either image with margin. 0xC0 fits a single
Thumb `movs rd,#imm8` (imm8 <= 0xFF), so it is a one-byte change of the immediate:
  1v8: movs r0,#0x13 (0x2013) -> movs r0,#0xC0 (0x20C0)   [byte 0x13 -> 0xC0]
  1v9: movs r0,#0x19 (0x2019) -> movs r0,#0xC0 (0x20C0)   [byte 0x19 -> 0xC0]
rwBoundary (arg1=5) is LEFT UNCHANGED -> host-writable region stays offsets 0..4,
so a host write can NEVER stomp RawCount / Baseline / config. SAFE.

------------------------------------------------------------------------------
cyacd row mapping (row = 64 bytes; the SIZE byte and a unique in-row anchor)
------------------------------------------------------------------------------
1v8 SIZE byte @flash 0x3802 -> row 0xE0 (224), in-row offset 0x02.
    anchor (in-row 0x00): 05 21 13 20  = movs r1,#5 ; movs r0,#0x13
1v9 SIZE byte @flash 0x37b8 -> row 0xDE (222), in-row offset 0x38.
    anchor (in-row 0x34): 22 46 05 21 19 20 = mov r2,r4 ; movs r1,#5 ; movs r0,#0x19
Each anchor is unique within its row AND appears in only one image, so we match
each image's copy by its own stock bytes (exactly like patch_resolution.py).

>>> VERIFY HARNESS NOTE: after this patch, read RawCount at host offset 0xA4
    (1v8) or 0x84 (1v9) -- NOT at 0x11/0x12. Two bytes LE = u16 RawCount. <<<

Inputs:
  --in-cyacd     cyacd file (stock 1v8/1v9 pair, or any prior patch level)
  --out-cyacd    output cyacd
  --bufsize N    new EZI2C bufSize (default 0xC0; must be <=0xFF and > the
                 larger RawCount offset 0xA4)
"""
import argparse
import sys
from cyacd_codec import parse_cyacd_text, serialize_cyacd

ROW_BYTES = 64

# Default enlarged EZI2C bufSize. Must cover the larger RawCount offset (1v8 = 0xA4)
# and fit a Thumb movs imm8 (<= 0xFF).
DEFAULT_BUFSIZE = 0xC0
MIN_BUFSIZE = 0xA5            # 0xA4 (1v8 RawCount offset) must be < bufSize
MAX_BUFSIZE = 0xFF           # movs imm8 ceiling

# Per-image EZI2C_SetBuffer call sites. Each entry:
#   label, row_num, anchor bytes, anchor in-row offset, SIZE-byte in-row offset,
#   stock SIZE value, host-read offset where RawCount appears after the patch.
SITES = [
    dict(label="1v8", row_num=0xE0,
         anchor=bytes.fromhex("05211320"), anchor_off=0x00,
         size_off=0x02, stock_size=0x13, rawcount_offset=0xA4),
    dict(label="1v9", row_num=0xDE,
         anchor=bytes.fromhex("224605211920"), anchor_off=0x34,
         size_off=0x38, stock_size=0x19, rawcount_offset=0x84),
]

# FIRMWARE VERSION (reg5) site -- THE REFLASH-LOOP GUARD.
#
# The driver's reflash gate (psoc_ts_probe) reflashes the chip iff the byte the
# chip reports at I2C register 5 ("reg5", read via i2c_smbus_read cmd=5) != the
# driver's compiled-in fw_version_code (get_firmware_version, a MOVZ immediate --
# see embed.py FW_VERSION_FILE_OFFSET=0x75B8). reg5 is NOT a bootloader value and
# NOT the driver immediate: it is a CONSTANT the FIRMWARE writes into its status
# block at init -- `movs r0,#imm ; strb r0,[base,#5]` in I2C_BuildStatusBlock.
# After a reflash the chip runs the new image and reports THAT image's reg5.
#
# To reflash EXACTLY ONCE and stay stable (no boot loop), the flashed image's
# reg5 MUST equal the driver code for that hwid. Each hwid is an INDEPENDENT
# (driver_code, flashed_image_reg5) pair -- get this wrong per-hwid and that
# hardware boot-loops:
#   hwid==0 (OUR glasses): driver 0x75B8 (stock 0x90=144) ; program_fw flashes the
#       image whose stock reg5 == 0x90 (the row-0xDE copy). Verified by ground
#       truth: device is stable reporting v145 == 0x90-family+1, so device==hwid 0.
#   hwid==1 (other HW variant): driver 0x75B0 (stock 0x1f=31) ; flashes the image
#       whose reg5 == 0x1f (the row-0xDF copy).
#
# We only ever bump the hwid==0 path (driver 0x75B8 via embed.py --set-version).
# So --fwver bumps ONLY the hwid==0 image's reg5 (row 0xDE, stock 0x90), keeping
# it paired with the bumped driver. The hwid==1 image's reg5 (0x1f) and its driver
# immediate (0x75B0=0x1f) are BOTH left stock -> that pair stays matched (no loop
# on hwid==1 hardware). DO NOT bump the hwid==1 reg5 unless you also bump 0x75B0.
#
# Version-site anchor `62 b6 XX 20` = `cpsie i ; movs r0,#XX` -- unique per image.
FWVER_SITE = dict(label="hwid0(reg5=0x90)", row_num=0xDE,
                  anchor=bytes.fromhex("62b69020"), anchor_off=0x24,
                  ver_off=0x26, stock_ver=0x90)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--in-cyacd",  required=True)
    ap.add_argument("--out-cyacd", required=True)
    ap.add_argument("--bufsize", type=lambda x: int(x, 0), default=DEFAULT_BUFSIZE,
                    help="new EZI2C bufSize (default 0xC0)")
    ap.add_argument("--fwver", type=lambda x: int(x, 0), default=None,
                    help="set the firmware version (reg5) in BOTH images to this "
                         "value (<=0xFF). REQUIRED for a one-shot reflash: must "
                         "equal embed.py --set-version, else the driver reflashes "
                         "every boot (code != reg5 loop). Omit to leave reg5 stock "
                         "(only valid if you also leave the driver version stock).")
    args = ap.parse_args()

    new_size = args.bufsize
    if not (MIN_BUFSIZE <= new_size <= MAX_BUFSIZE):
        print(f"ERROR: bufsize 0x{new_size:x} out of range "
              f"[0x{MIN_BUFSIZE:x}..0x{MAX_BUFSIZE:x}] "
              f"(must reach RawCount offset 0xA4 and fit movs imm8)",
              file=sys.stderr)
        return 1

    src = open(args.in_cyacd, "rb").read()
    header, rows = parse_cyacd_text(src)

    print("EZI2C read-window enlarge (expose RawCount, auto-reset untouched):")
    print(f"  new bufSize = 0x{new_size:x} ; rwBoundary (writable) UNCHANGED = 5")
    for s in SITES:
        print(f"  {s['label']}: row 0x{s['row_num']:x} in-row off 0x{s['size_off']:x} "
              f"bufSize 0x{s['stock_size']:x} -> 0x{new_size:x}; "
              f"read RawCount at host offset 0x{s['rawcount_offset']:x}")

    patched_labels = []
    for s in SITES:
        a_lo = s["anchor_off"]
        a_hi = a_lo + len(s["anchor"])
        size_off = s["size_off"]
        assert a_hi <= ROW_BYTES, f"{s['label']} anchor crosses row boundary"
        assert size_off < ROW_BYTES, f"{s['label']} size byte crosses row boundary"

        found_this_site = False
        for r in rows:
            if r.row_num != s["row_num"]:
                continue
            if bytes(r.data[a_lo:a_hi]) != s["anchor"]:
                # Could be the other image's row of the same number, or already patched.
                cur_size = r.data[size_off]
                if cur_size == new_size:
                    print(f"  SKIP {s['label']} row 0x{r.row_num:x}: "
                          f"already bufSize 0x{new_size:x}")
                else:
                    print(f"  SKIP row 0x{r.row_num:x}: anchor for {s['label']} "
                          f"not present (other image / unexpected)")
                continue
            # Anchor matched -> this is the right image's copy. Sanity-check the
            # SIZE byte is the expected stock value before writing.
            if r.data[size_off] != s["stock_size"]:
                if r.data[size_off] == new_size:
                    print(f"  SKIP {s['label']} row 0x{r.row_num:x}: already patched")
                    found_this_site = True
                    continue
                print(f"  ERROR {s['label']} row 0x{r.row_num:x}: SIZE byte is "
                      f"0x{r.data[size_off]:02x}, expected stock 0x{s['stock_size']:02x}",
                      file=sys.stderr)
                return 1
            buf = bytearray(r.data)
            buf[size_off] = new_size
            r.data = bytes(buf)
            r.checksum = r.computed_checksum
            found_this_site = True
            patched_labels.append(s["label"])
            print(f"  PATCHED {s['label']} row 0x{r.row_num:x}: "
                  f"bufSize 0x{s['stock_size']:02x} -> 0x{new_size:02x}, "
                  f"new checksum 0x{r.checksum:02x}")

        if not found_this_site:
            print(f"  WARNING: {s['label']} call site row 0x{s['row_num']:x} "
                  f"not found in cyacd")

    # A complete production cyacd carries BOTH firmware images, so we must patch
    # EXACTLY 2 row-copies (the 1v8 SIZE site in row 0xE0 and the 1v9 SIZE site in
    # row 0xDE). Anything else means the cyacd is not the expected stock pair (or is
    # already patched) -- refuse rather than flash a half-patched image.
    EXPECTED_PATCHES = len(SITES)  # == 2
    if len(patched_labels) != EXPECTED_PATCHES:
        print(f"ERROR: patched {len(patched_labels)} row-copies {patched_labels}, "
              f"expected exactly {EXPECTED_PATCHES} (1v8 row 0xE0 + 1v9 row 0xDE). "
              f"Wrong cyacd, or already patched -- refusing to write.",
              file=sys.stderr)
        return 1

    # Firmware version (reg5) bump -- the reflash-loop guard. Bumps ONLY the
    # hwid==0 image's reg5 (the one our glasses flash), pairing it with the bumped
    # driver code. See FWVER_SITE. The hwid==1 image stays stock (matched there).
    if args.fwver is not None:
        new_ver = args.fwver
        if not (0 <= new_ver <= 0xFF):
            print(f"ERROR: fwver 0x{new_ver:x} out of range [0,0xFF] (reg5 is u8)",
                  file=sys.stderr)
            return 1
        s = FWVER_SITE
        a_lo = s["anchor_off"]; a_hi = a_lo + len(s["anchor"]); ver_off = s["ver_off"]
        assert a_hi <= ROW_BYTES and ver_off < ROW_BYTES
        print(f"\nFirmware version (reg5) set -> 0x{new_ver:x} in the hwid==0 image "
              f"only (row 0x{s['row_num']:x}, stock reg5 0x{s['stock_ver']:02x}); MUST "
              f"match embed.py --set-version {new_ver}. hwid==1 image (reg5 0x1f) left "
              f"stock so its 0x1f==0x1f pair stays loop-free.")
        bumped = 0
        for r in rows:
            if r.row_num != s["row_num"]:
                continue
            if bytes(r.data[a_lo:a_hi]) != s["anchor"]:
                continue  # the hwid==1 copy of this row number, or already patched
            if r.data[ver_off] != s["stock_ver"]:
                if r.data[ver_off] == new_ver:
                    print(f"  SKIP row 0x{r.row_num:x}: reg5 already 0x{new_ver:02x}")
                    bumped += 1
                    continue
                print(f"  ERROR row 0x{r.row_num:x}: reg5 byte is 0x{r.data[ver_off]:02x}, "
                      f"expected stock 0x{s['stock_ver']:02x}", file=sys.stderr)
                return 1
            buf = bytearray(r.data); buf[ver_off] = new_ver
            r.data = bytes(buf); r.checksum = r.computed_checksum
            bumped += 1
            print(f"  PATCHED reg5 row 0x{r.row_num:x}: 0x{s['stock_ver']:02x} -> "
                  f"0x{new_ver:02x}, new checksum 0x{r.checksum:02x}")
        if bumped != 1:
            print(f"ERROR: bumped reg5 in {bumped} row-copies, expected exactly 1 "
                  f"(hwid==0 image, row 0x{s['row_num']:x}). Wrong cyacd or already "
                  f"patched -- refusing to write.", file=sys.stderr)
            return 1

    open(args.out_cyacd, "wb").write(serialize_cyacd(header, rows))
    print(f"\npatched exactly {len(patched_labels)} row-copies {patched_labels}; "
          f"wrote {args.out_cyacd}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
