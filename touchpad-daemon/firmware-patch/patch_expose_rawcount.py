#!/usr/bin/env python3
"""
patch_expose_rawcount: expose the PSoC 4000R widget0 pre-baseline RawCount
over I2C by redirecting ONE diagnostic host-block slot to it.

Problem recap (see touchpad-daemon/CLAUDE.md): a motionless finger is absorbed
into the CapSense baseline after ~1.5s. Every host-readable field (touch flag,
position, X/Y centroid, baseline) collapses at absorption. The pre-baseline
RawCount (snsRAM widget0 +0) does NOT collapse, but the stock firmware never
serializes it to any host I2C register, and the 25-byte block is a FIXED
serialized copy (not an EZI2C SRAM window -- proven on-device that enlarging
the EZI2C SIZE byte does not widen the readable window). The ONLY way to read
RawCount over I2C is to make the firmware WRITE it into one of the 25 bytes.

What this patch does (1v9 image only)
-------------------------------------
The 1v9 status-block builder lives at flash 0x3600 (the 1v8 twin is at 0x3640).
Its tail writes these host offsets:

  ldrh r0,[r1,#0x10] -> host 0x0B/0x0C  X centroid     (r1 = 0x20000070)
  ldrh r0,[r1,#0x12] -> host 0x0D/0x0E  Y centroid
  ldrh r0,[r1,#0x14] -> host 0x13/0x14  diag           (driver ignores)
  ldrh r0,[r1,#0x16] -> host 0x15/0x16  diag           (driver ignores)
  movs r0,#1; bl 0x3460 -> r3 -> host 0x0F/0x10  Baseline (slider/widget idx 1)
  movs r0,#3; bl 0x3460 -> r0 -> host 0x17/0x18  diag (widget idx 3 baseline)

The getter at 0x3460 computes widget_table[idx].snsRAMptr then returns
snsRAMptr[+2] (the Baseline). RawCount is the same pointer at [+0].

The driver (aarch64 psoc_ts_drv_right.ko, psoc_report_work @0x11bc) reads the
25-byte block into stack buffer sp+8 (host 0x00 == sp+8) and decodes keycodes
ONLY from host offsets 0x05..0x09 (stack sp+0x0d..0x11). It never reads host
0x0A..0x18. Therefore host 0x17/0x18 is a safe, driver-ignored 16-bit slot.

r2 already holds the host-block base 0x20000110 throughout the builder, and the
1v9 widget0 snsRAM base is 0x20000194 = 0x20000110 + 0x84 (derived from the 1v9
widget config table at flash 0x3cb0: idx0.field+4 = [0x3cb4] = 0x20000194).
So we replace the second getter call (which fed host 0x17/0x18 with an unused
diagnostic baseline) with an inline RawCount load that needs NO new literal:

  flash 0x362e:  movs r0,#3        (03 20)  \
  flash 0x3630:  bl   0x3460       (ff f7 16 ff)  >  6 bytes
                                            /
  ->
  flash 0x362e:  mov  r0,r2        (10 46)  ; r0 = 0x20000110
  flash 0x3630:  adds r0,#0x84     (84 30)  ; r0 = 0x20000194 (RawCount ptr)
  flash 0x3632:  ldrh r0,[r0]      (00 88)  ; r0 = RawCount (snsRAM widget0 +0)

The existing strb r0,[r2,#0x17] / lsrs / strb r0,[r2,#0x18] then writes RawCount
to host 0x17/0x18. host 0x0F/0x10 Baseline (from r3, the first call) is
untouched, r2 is untouched, and the dropped bl 0x3460(idx=3) had no side effects
(pure getter). Auto-reset / baseline logic is entirely unchanged -- this only
ADDS a read path.

Result: host offset 0x17 (lo) / 0x18 (hi) returns the 16-bit widget0 RawCount,
which stays high during a still hold (it does not collapse when the baseline
rises to meet it), so a motionless press remains detectable by the daemon.

Targeting (1v9 only, 1v8 twin untouched)
----------------------------------------
The builder is in cyacd row 0xD8 (flash 0x3600). Exactly one copy of row 0xD8
in the embedded cyacd carries the 1v9 builder; its byte window 0x2e..0x33 equals
the OLD bytes 03 20 ff f7 16 ff. The 1v8 twin copy of row 0xD8 holds entirely
different code at that window and is skipped. We additionally pin the match with
the full 64-byte stock content of the 1v9 builder row for safety.

Inputs:
  --in-cyacd    cyacd file (stock or any prior patch level)
  --out-cyacd   output cyacd
"""
import argparse
import sys
from cyacd_codec import parse_cyacd_text, serialize_cyacd

ROW_BYTES = 64

# Builder row + patch window (flash 0x362e..0x3633 -> row 0xD8, byte 0x2e..0x33).
BUILDER_FLASH = 0x3600
PATCH_FLASH = 0x362E
PATCH_ROW = PATCH_FLASH // ROW_BYTES          # 0xD8
PATCH_OFF = PATCH_FLASH % ROW_BYTES           # 0x2E
assert PATCH_ROW == 0xD8 and PATCH_OFF == 0x2E

# movs r0,#3 ; bl 0x3460   (the 1v9 second-getter call writing host 0x17/0x18)
OLD_BYTES = bytes.fromhex("0320fff716ff")
# mov r0,r2 ; adds r0,#0x84 ; ldrh r0,[r0]   (RawCount = [0x20000110 + 0x84])
NEW_BYTES = bytes.fromhex("104684300088")
assert len(OLD_BYTES) == len(NEW_BYTES) == 6

# Full 64-byte stock content of the 1v9 builder row (row 0xD8 copy @ .ko 0xf95b).
# Used as a precise identity check so we patch the 1v9 copy and nothing else.
EXPECTED_1V9_ROW = bytes.fromhex(
    "00b51049104a088ad072000a1073488a5073000a9073888a"
    "d074000a1075c88a5075000a90750120fff71aff0346"
    "0320fff716ffd373190a1174d075000a1076"
)
assert len(EXPECTED_1V9_ROW) == ROW_BYTES

# Derived constants for the log (1v9):
HOST_BLOCK_BASE_1V9 = 0x20000110
SNSRAM_W0_1V9 = 0x20000194         # RawCount @ +0, Baseline @ +2
RAWCOUNT_OFFSET = SNSRAM_W0_1V9 - HOST_BLOCK_BASE_1V9   # 0x84


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--in-cyacd", required=True)
    ap.add_argument("--out-cyacd", required=True)
    args = ap.parse_args()

    src = open(args.in_cyacd, "rb").read()
    header, rows = parse_cyacd_text(src)

    print(f"1v9 status-block builder @ flash 0x{BUILDER_FLASH:x}")
    print(f"  RawCount source: snsRAM widget0 = 0x{SNSRAM_W0_1V9:08x} "
          f"(= host base 0x{HOST_BLOCK_BASE_1V9:08x} + 0x{RAWCOUNT_OFFSET:x})")
    print(f"  patch site: flash 0x{PATCH_FLASH:x} -> row 0x{PATCH_ROW:x}, "
          f"bytes 0x{PATCH_OFF:x}..0x{PATCH_OFF + 5:x}")
    print(f"  old bytes {OLD_BYTES.hex()} (movs r0,#3; bl 0x3460)")
    print(f"  new bytes {NEW_BYTES.hex()} (mov r0,r2; adds r0,#0x84; ldrh r0,[r0])")
    print(f"  -> host 0x17/0x18 now carries widget0 RawCount (driver-ignored slot)")

    patched_count = 0
    seen_row_copies = 0
    for r in rows:
        if r.row_num != PATCH_ROW:
            continue
        seen_row_copies += 1
        window = bytes(r.data[PATCH_OFF:PATCH_OFF + len(OLD_BYTES)])
        is_1v9 = bytes(r.data) == EXPECTED_1V9_ROW
        if is_1v9 and window == OLD_BYTES:
            buf = bytearray(r.data)
            buf[PATCH_OFF:PATCH_OFF + len(NEW_BYTES)] = NEW_BYTES
            r.data = bytes(buf)
            r.checksum = r.computed_checksum
            patched_count += 1
            print(f"  PATCHED 1v9 copy of row 0x{r.row_num:x}: "
                  f"{OLD_BYTES.hex()} -> {NEW_BYTES.hex()}, "
                  f"new checksum 0x{r.checksum:02x}")
        elif window == OLD_BYTES and not is_1v9:
            # Same window bytes but full row mismatch -- refuse rather than guess.
            print(f"  SKIP row 0x{r.row_num:x} copy: window matches but full row "
                  f"differs from expected 1v9 (not patching to stay safe)")
        else:
            print(f"  SKIP row 0x{r.row_num:x} copy: 1v8 twin "
                  f"(window {window.hex()})")

    if patched_count != 1:
        print(f"ERROR: expected to patch exactly 1 row copy, patched "
              f"{patched_count} (saw {seen_row_copies} copies of row "
              f"0x{PATCH_ROW:x})", file=sys.stderr)
        return 1

    open(args.out_cyacd, "wb").write(serialize_cyacd(header, rows))
    print(f"\nwrote {args.out_cyacd}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
