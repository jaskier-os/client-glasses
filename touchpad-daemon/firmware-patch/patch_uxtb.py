#!/usr/bin/env python3
"""
Build a patched cyacd that changes the position-byte truncation in
image_1v9 (the active firmware variant on this chip):

  flash 0x3ab6:  c0 b2  (uxtb r0, r0)  ->  00 0a  (lsr  r0, r0, #8)
  flash 0x3ab8:  d2 b2  (uxtb r2, r2)  ->  12 0a  (lsr  r2, r2, #8)

After this patch the chip should report the UPPER byte of the 16-bit
slider position via report-buffer byte 8 (and pos2 via byte 9). If the
upper byte carries finer resolution we'll see it move smoothly with
finger position. If always zero, the patch was a no-op (revert).

Image_1v8 row 0xEA has DIFFERENT code at the same flash address; we
explicitly avoid touching it by checking the existing bytes match the
expected pattern.

Inputs:
  --stock-cyacd     stock.cyacd from extract.py
  --offsets-json    stock_offsets.json
  --out-cyacd       patched.cyacd

Behavior:
  - Loads every row from the cyacd
  - For each row at row_num=0xEA: if bytes [0x36..0x39] == c0 b2 d2 b2,
    apply the patch and recompute checksum. If they don't match, leave
    the row alone (it's image_1v8's variant of row 0xEA).
  - Writes a fully-formed cyacd output.
"""
import argparse
import sys
from cyacd_codec import parse_cyacd_text, serialize_cyacd, CyacdRow

PATCH_FLASH_ADDR = 0x3ab6
TARGET_ROW_NUM = 0xEA          # 0x3ab6 // 64
ROW_BYTES = 64
PATCH_OFFSET_IN_ROW = 0x36     # 0x3ab6 - 0xEA*64 = 0x36
EXPECT_BYTES = bytes.fromhex("c0b2d2b2")
PATCH_BYTES  = bytes.fromhex("000a120a")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--stock-cyacd",  required=True)
    ap.add_argument("--out-cyacd",    required=True)
    args = ap.parse_args()

    stock = open(args.stock_cyacd, "rb").read()
    header, rows = parse_cyacd_text(stock)

    patched_count = 0
    skipped_count = 0
    for r in rows:
        if r.row_num != TARGET_ROW_NUM:
            continue
        existing = bytes(r.data[PATCH_OFFSET_IN_ROW:PATCH_OFFSET_IN_ROW + 4])
        if existing == EXPECT_BYTES:
            buf = bytearray(r.data)
            buf[PATCH_OFFSET_IN_ROW : PATCH_OFFSET_IN_ROW + 4] = PATCH_BYTES
            r.data = bytes(buf)
            r.checksum = r.computed_checksum  # recompute for the modified row
            patched_count += 1
            print(f"PATCHED row 0x{r.row_num:x}: bytes "
                  f"{existing.hex()} -> {PATCH_BYTES.hex()}, "
                  f"new checksum 0x{r.checksum:02x}")
        else:
            skipped_count += 1
            print(f"SKIPPED row 0x{r.row_num:x} (different image): "
                  f"bytes at +0x{PATCH_OFFSET_IN_ROW:x} = {existing.hex()}, "
                  f"expected {EXPECT_BYTES.hex()}")

    if patched_count != 1:
        print(f"ERROR: expected exactly 1 patch, applied {patched_count}",
              file=sys.stderr)
        return 1
    if skipped_count != 1:
        print(f"WARN: expected to SKIP exactly 1 alt-image row, "
              f"skipped {skipped_count}", file=sys.stderr)

    # Re-emit cyacd
    open(args.out_cyacd, "wb").write(serialize_cyacd(header, rows))
    print(f"\nwrote {args.out_cyacd}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
