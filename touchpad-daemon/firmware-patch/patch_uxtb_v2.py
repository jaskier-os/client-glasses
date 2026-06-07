#!/usr/bin/env python3
"""
v2 patch: expose BOTH bytes of the 16-bit pos1 to the I2C report buffer.

Original sequence (image_1v9, flash 0x3ab2..0x3ab9):
    3ab2: 10 8e   ldrh r0, [r2, #0x30]   ; r0 = pos1_u16
    3ab4: 52 8e   ldrh r2, [r2, #0x32]   ; r2 = pos2_u16
    3ab6: c0 b2   uxtb r0, r0            ; r0 = pos1 lower byte
    3ab8: d2 b2   uxtb r2, r2            ; r2 = pos2 lower byte
    ...
    3ad6: 08 72   strb r0, [r1, #8]      ; buf[8] = pos1 lower byte
    3ad8: 4a 72   strb r2, [r1, #9]      ; buf[9] = pos2 lower byte

v2 patched sequence at 0x3ab6..0x3ab9:
    3ab6: 02 0a   lsr  r2, r0, #8        ; r2 = pos1 UPPER byte (uses r0 = pos1_u16)
    3ab8: c0 b2   uxtb r0, r0            ; r0 = pos1 LOWER byte (now)
    ...
    3ad6: 08 72   strb r0, [r1, #8]      ; buf[8] = pos1 lower byte (UNCHANGED)
    3ad8: 4a 72   strb r2, [r1, #9]      ; buf[9] = pos1 UPPER byte (NEW!)

Sacrifices pos2 (two-finger) reporting; for slider use that's fine.
The intermediate code path (cmp r2, #0xff; multi-finger branches) does
not modify r2 between our patch and the strb at 3ad8, so r2 reaches
buf[9] as pos1 upper byte.

Combining buf[8] (lower byte) + buf[9] (upper byte) gives 16-bit pos1
resolution per IRQ — the goal of 1-5 unit steps.
"""
import argparse
import sys
from cyacd_codec import parse_cyacd_text, serialize_cyacd

TARGET_ROW_NUM = 0xEA
PATCH_OFFSET_IN_ROW = 0x36   # flash 0x3ab6 = row 0xEA *64 + 0x36

# We accept either the stock bytes OR our v1 patch as starting point — so this
# tool can apply v2 on top of either a fresh stock cyacd or a v1-patched one.
EXPECT_STOCK = bytes.fromhex("c0b2d2b2")    # uxtb r0,r0; uxtb r2,r2
EXPECT_V1    = bytes.fromhex("000a120a")    # lsr r0,r0,#8; lsr r2,r2,#8 (v1 patch)
PATCH_V2     = bytes.fromhex("020ac0b2")    # lsr r2,r0,#8; uxtb r0,r0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--in-cyacd",  required=True)
    ap.add_argument("--out-cyacd", required=True)
    args = ap.parse_args()

    src = open(args.in_cyacd, "rb").read()
    header, rows = parse_cyacd_text(src)

    patched = 0
    for r in rows:
        if r.row_num != TARGET_ROW_NUM:
            continue
        existing = bytes(r.data[PATCH_OFFSET_IN_ROW:PATCH_OFFSET_IN_ROW + 4])
        if existing == EXPECT_STOCK:
            kind = "stock"
        elif existing == EXPECT_V1:
            kind = "v1-patched"
        else:
            print(f"SKIP row 0x{r.row_num:x} @file: bytes {existing.hex()} "
                  f"don't match stock or v1 — leaving alone (image_1v8 variant)")
            continue
        buf = bytearray(r.data)
        buf[PATCH_OFFSET_IN_ROW : PATCH_OFFSET_IN_ROW + 4] = PATCH_V2
        r.data = bytes(buf)
        r.checksum = r.computed_checksum
        patched += 1
        print(f"PATCHED row 0x{r.row_num:x} (was {kind}): "
              f"{existing.hex()} -> {PATCH_V2.hex()}, "
              f"new checksum 0x{r.checksum:02x}")

    if patched != 1:
        print(f"ERROR: expected exactly 1 patch, applied {patched}",
              file=sys.stderr)
        return 1

    open(args.out_cyacd, "wb").write(serialize_cyacd(header, rows))
    print(f"wrote {args.out_cyacd}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
