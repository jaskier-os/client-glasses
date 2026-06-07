#!/usr/bin/env python3
"""
v3 patch: bump slider widget config xResolution from 100 to 1000 to
get 10x finer position output.

Found by reverse-engineering the CapSense_FLASH_WD_STRUCT at flash
offset 0x3cb4 in image_1v9. The widget config layout:

  +0x00..+0x13  five pointers (4 SRAM data ptrs + 1 flash sub-config)
  +0x14         numSensors (=6)
  +0x15         padding (=0)
  +0x16         widgetType (=2 = LINEAR_SLIDER)
  +0x17         numCols (=6)
  +0x18..0x1b   xResolution u32 LE (=100 in stock)
  +0x1c..0x1f   xCentroidMultiplier u32 LE (=5120 in stock)
                = (xResolution * 256) / (numCols - 1) = 100*256/5 = 5120
  +0x20         pointer to filter history
  +0x24         u32 (=64 = position IIR coefficient?)
  +0x28         flash pointer to per-sensor sub-config

To bump xResolution to 1000, also need to update xCentroidMultiplier:
  new mult = 1000 * 256 / 5 = 51200 = 0xC800

Patch site: flash 0x3CCC..0x3CD3 (8 bytes).
Row containing it: row_num = 0x3CCC / 64 = 0xF3, offset = 0x0c.

Inputs:
  --in-cyacd     cyacd file (stock or any prior patch level)
  --out-cyacd    output cyacd
  --resolution N new xResolution value (default 1000)
  --num-cols N   slider numCols (default 6, must match firmware)
"""
import argparse
import struct
import sys
from cyacd_codec import parse_cyacd_text, serialize_cyacd

ROW_BYTES = 64
WD_FLASH_ADDR = 0x3cb4
RES_OFF_IN_WD = 0x18         # u32
MULT_OFF_IN_WD = 0x1c        # u32
WD_TYPE_OFF = 0x16           # u8 sanity check, must be 2
NUM_COLS_OFF = 0x17          # u8 sanity check

# Convert flash offset -> (row_num, byte_off_in_row)
def flash_to_row(flash_off):
    return flash_off // ROW_BYTES, flash_off % ROW_BYTES


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--in-cyacd",   required=True)
    ap.add_argument("--out-cyacd",  required=True)
    ap.add_argument("--resolution", type=int, default=1000,
                    help="new xResolution (default 1000)")
    ap.add_argument("--num-cols",   type=int, default=6)
    args = ap.parse_args()

    new_res = args.resolution
    n_cols = args.num_cols
    new_mult = (new_res * 256) // (n_cols - 1)

    src = open(args.in_cyacd, "rb").read()
    header, rows = parse_cyacd_text(src)

    # In stock image_1v9: xResolution=100, xCentroidMultiplier=5120.
    # We identify the image_1v9 copy of the row by these EXACT stock values
    # (vs image_1v8 which has different layout at the same flash address).
    EXPECTED_RES_STOCK  = 100
    EXPECTED_MULT_STOCK = 5120

    res_bytes_old   = struct.pack("<I", EXPECTED_RES_STOCK)
    mult_bytes_old  = struct.pack("<I", EXPECTED_MULT_STOCK)
    res_bytes_new   = struct.pack("<I", new_res)
    mult_bytes_new  = struct.pack("<I", new_mult)
    print(f"slider widget at flash 0x{WD_FLASH_ADDR:x}:")
    print(f"  xResolution stock = {EXPECTED_RES_STOCK}, new = {new_res}")
    print(f"  xCentroidMult stock = {EXPECTED_MULT_STOCK}, new = {new_mult}")

    # Both fields live at adjacent offsets in row 0xF3 (= flash 0x3cc0..0x3cff)
    res_row_num,  res_byte_off  = flash_to_row(WD_FLASH_ADDR + RES_OFF_IN_WD)
    mult_row_num, mult_byte_off = flash_to_row(WD_FLASH_ADDR + MULT_OFF_IN_WD)
    assert res_row_num == mult_row_num, "expected both in same row"
    print(f"  patching row 0x{res_row_num:x}, bytes 0x{res_byte_off:x}..0x{mult_byte_off+4:x}")

    patched_count = 0
    for r in rows:
        if r.row_num != res_row_num:
            continue
        cur_res_bytes  = bytes(r.data[res_byte_off  : res_byte_off  + 4])
        cur_mult_bytes = bytes(r.data[mult_byte_off : mult_byte_off + 4])
        if cur_res_bytes == res_bytes_old and cur_mult_bytes == mult_bytes_old:
            buf = bytearray(r.data)
            buf[res_byte_off  : res_byte_off  + 4] = res_bytes_new
            buf[mult_byte_off : mult_byte_off + 4] = mult_bytes_new
            r.data = bytes(buf)
            r.checksum = r.computed_checksum
            patched_count += 1
            print(f"  PATCHED image_1v9 copy of row 0x{r.row_num:x}: "
                  f"xRes {EXPECTED_RES_STOCK}->{new_res}, "
                  f"xMult {EXPECTED_MULT_STOCK}->{new_mult}, "
                  f"new checksum 0x{r.checksum:02x}")
        else:
            print(f"  SKIP non-matching copy of row 0x{r.row_num:x}: "
                  f"xRes={struct.unpack('<I',cur_res_bytes)[0]}, "
                  f"xMult={struct.unpack('<I',cur_mult_bytes)[0]} "
                  f"(image_1v8 variant)")
    if patched_count != 1:
        print(f"ERROR: expected to patch exactly 1 row copy, patched {patched_count}",
              file=sys.stderr)
        return 1

    open(args.out_cyacd, "wb").write(serialize_cyacd(header, rows))
    print(f"\nwrote {args.out_cyacd}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
