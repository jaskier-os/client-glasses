#!/usr/bin/env python3
"""
Patch psoc_ts_drv_right.ko:

1. Remove the delta >= 36 threshold that guards the "pre_position = ...
   p_delta = ..." dev_info print in psoc_report_work.
   VMA 0x14b0: b.lo -> nop

2. Bypass the proximity-based touch disable in psoc_report_work.
   VMA 0x126c: tbnz w8, #0, 0x12c4  ->  b 0x12c4
   The stock driver checks byte[6] bit0 from the PSoC I2C read; when 0
   (off-head) it skips all touch event processing. Patching to an
   unconditional branch makes the driver always take the on-head path,
   so the touchpad stays alive regardless of proximity state.

.text starts at file offset 0x1000; file_offset = VMA + 0x1000.
AArch64 instructions are 32-bit little-endian.
"""
import os
import sys
import shutil

PATCHES = [
    # (name, file_offset, expected_stock_bytes, patched_bytes)
    (
        "delta-threshold (b.lo -> nop)",
        0x1000 + 0x14b0,  # 0x24b0
        bytes.fromhex("630f0054"),   # b.lo +0x4ec (skip print if |delta|<36)
        bytes.fromhex("1f2003d5"),   # nop
    ),
    (
        "proximity-touch-gate (tbnz -> b)",
        0x1000 + 0x126c,  # 0x226c
        bytes.fromhex("c8020037"),   # tbnz w8, #0, 0x12c4
        bytes.fromhex("16000014"),   # b 0x12c4 (unconditional)
    ),
    (
        "spread-bit-bailout (cbz w21 -> nop)",
        0x1000 + 0x12f8,  # 0x22f8
        bytes.fromhex("951d0034"),   # cbz w21, 0x16a8 (bail if byte[6]&2==0)
        bytes.fromhex("1f2003d5"),   # nop
    ),
    (
        "prev-state-bailout (cbz w8 -> nop)",
        0x1000 + 0x1300,  # 0x2300
        bytes.fromhex("481d0034"),   # cbz w8, 0x16a8 (bail if prev==0)
        bytes.fromhex("1f2003d5"),   # nop
    ),
    (
        "enforce-clear-report (strb wzr -> nop)",
        0x1000 + 0x1354,  # 0x2354
        bytes.fromhex("bf020039"),   # strb wzr, [x21] (clear enforce in psoc_report_work)
        bytes.fromhex("1f2003d5"),   # nop
    ),
    (
        "enforce-clear-psensor (strb wzr -> nop)",
        0x1000 + 0x207c,  # 0x307c
        bytes.fromhex("bf020039"),   # strb wzr, [x??] (clear enforce in psensor_delay_work)
        bytes.fromhex("1f2003d5"),   # nop
    ),
]

def main():
    if len(sys.argv) < 3:
        print("usage: patch-driver.py <input.ko> <output.ko>", file=sys.stderr)
        sys.exit(2)
    src, dst = sys.argv[1], sys.argv[2]

    shutil.copy(src, dst)
    with open(dst, "r+b") as f:
        for name, offset, stock, patched in PATCHES:
            f.seek(offset)
            found = f.read(4)
            if found == patched:
                print(f"  [{name}] already patched at 0x{offset:x}")
                continue
            if found != stock:
                print(f"  [{name}] ERROR: unexpected bytes at 0x{offset:x}: "
                      f"got {found.hex()}, expected {stock.hex()}", file=sys.stderr)
                sys.exit(1)
            f.seek(offset)
            f.write(patched)
            print(f"  [{name}] patched at 0x{offset:x}: {stock.hex()} -> {patched.hex()}")

if __name__ == "__main__":
    main()
