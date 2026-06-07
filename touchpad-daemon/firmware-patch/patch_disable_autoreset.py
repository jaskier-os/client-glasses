#!/usr/bin/env python3
"""
Disable CapSense Sensor Auto-Reset by flipping the baseline-update gate.

A motionless finger is absorbed into the CapSense baseline after ~1.5 s, so
every host-readable I2C field (touch flag, X/Y centroid, baseline) collapses
while the finger is still pressed. The absorption happens in the baseline
updater CapSense_UpdateSensorBaseline_gated (flash 0x1e4c). The in-band
NoiseThreshold gate at flash 0x1ea2:

    0x1ea0: cmp  r2,r0          ; r0 = |raw - baseline| overshoot, r2 = NoiseTh
    0x1ea2: bcs  0x1eb0         ; STOCK: NoiseTh >= diff -> take UPDATE path
                                ;        (writes baseline IIR -> absorbs still finger)

Replacing the conditional `bcs 0x1eb0` (in-file bytes `05 d2`, halfword 0xD205)
with an unconditional `b 0x1ef4` (in-file bytes `27 e0`, halfword 0xE027) forces
the gate to ALWAYS skip the baseline write when reached, so a still finger is no
longer absorbed:

    BEFORE @0x1ea2: 05 d2   bcs 0x1eb0   (update baseline when in NoiseTh band)
    AFTER  @0x1ea2: 27 e0   b   0x1ef4   (always skip baseline write at this gate)

Site -> cyacd row:
    flash 0x1ea2 -> row_num = 0x1ea2 // 64 = 0x7A (122), byte_off = 0x1ea2 % 64 = 0x22 (34)

Both firmware images (1v8 and 1v9) are BYTE-IDENTICAL across flash row 0x7A, so
both cyacd copies of that row carry the same stock `05 d2` at offset 0x22 and
both receive the identical patch. We locate each copy by the stock context
window around the gate and patch EVERY match (expected: exactly 2 -- one per
image). See /tmp/patch_authoring.md (Task 4) for the full analysis.

COLLATERAL RISK (MODERATE): force-skipping this gate also disables in-band drift
compensation for any sensor that reaches it -- slow thermal/humidity drift is no
longer tracked into the baseline. Recoverable via `deploy-driver.sh revert`.
Validate baseline-vs-raw on-device before relying on it.

Inputs:
  --in-cyacd     cyacd file (stock or any prior patch level)
  --out-cyacd    output cyacd
"""
import argparse
import sys
from cyacd_codec import parse_cyacd_text, serialize_cyacd

ROW_BYTES = 64

# Baseline-gate site.
GATE_FLASH_ADDR = 0x1ea2
GATE_STOCK = bytes.fromhex("05d2")   # bcs 0x1eb0   (halfword 0xD205, in-file LE)
GATE_PATCH = bytes.fromhex("27e0")   # b   0x1ef4   (halfword 0xE027, in-file LE)

# Context window used to positively identify the gate inside the row, so we never
# patch a coincidental `05 d2` elsewhere. These are the bytes immediately
# surrounding the gate (flash 0x1e9e..0x1ea7), identical in 1v8 and 1v9:
#   0x1e9e: 3a 79   ldrb r2,[r7,#4]
#   0x1ea0: 82 42   cmp  r2,r0
#   0x1ea2: 05 d2   bcs  0x1eb0      <-- patch target
#   0x1ea4: 7a 79   ldrb r2,[r7,#5]
#   0x1ea6: 82 42   cmp  r2,r0
CTX_FLASH_ADDR = 0x1e9e
CTX_STOCK = bytes.fromhex("3a79824205d27a798242")
# Offset of the 2-byte gate within the context window.
GATE_OFF_IN_CTX = GATE_FLASH_ADDR - CTX_FLASH_ADDR        # = 4


# Convert flash offset -> (row_num, byte_off_in_row)
def flash_to_row(flash_off):
    return flash_off // ROW_BYTES, flash_off % ROW_BYTES


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--in-cyacd",  required=True)
    ap.add_argument("--out-cyacd", required=True)
    args = ap.parse_args()

    src = open(args.in_cyacd, "rb").read()
    header, rows = parse_cyacd_text(src)

    gate_row_num, gate_byte_off = flash_to_row(GATE_FLASH_ADDR)
    ctx_row_num,  ctx_byte_off  = flash_to_row(CTX_FLASH_ADDR)
    assert gate_row_num == ctx_row_num, "gate and its context must share one row"
    ctx_end = ctx_byte_off + len(CTX_STOCK)
    assert ctx_end <= ROW_BYTES, "context window must not cross a row boundary"

    print(f"baseline-gate disable at flash 0x{GATE_FLASH_ADDR:x}:")
    print(f"  stock bytes {GATE_STOCK.hex()} (bcs 0x1eb0) -> "
          f"patch bytes {GATE_PATCH.hex()} (b 0x1ef4)")
    print(f"  patching row 0x{gate_row_num:x}, byte offset 0x{gate_byte_off:x}")
    print(f"  identifying via context @flash 0x{CTX_FLASH_ADDR:x}: {CTX_STOCK.hex()}")

    patched_count = 0
    for r in rows:
        if r.row_num != gate_row_num:
            continue
        cur_ctx = bytes(r.data[ctx_byte_off:ctx_end])
        if cur_ctx == CTX_STOCK:
            buf = bytearray(r.data)
            # sanity: the gate bytes inside the matched context must be stock
            assert bytes(buf[gate_byte_off:gate_byte_off + 2]) == GATE_STOCK, \
                "context matched but gate bytes are not stock -- aborting"
            buf[gate_byte_off:gate_byte_off + 2] = GATE_PATCH
            r.data = bytes(buf)
            r.checksum = r.computed_checksum
            patched_count += 1
            print(f"  PATCHED copy of row 0x{r.row_num:x}: "
                  f"{GATE_STOCK.hex()} -> {GATE_PATCH.hex()}, "
                  f"new checksum 0x{r.checksum:02x}")
        else:
            already = bytes(r.data[gate_byte_off:gate_byte_off + 2]) == GATE_PATCH
            note = "already patched" if already else "non-matching context"
            print(f"  SKIP copy of row 0x{r.row_num:x}: {note} "
                  f"(ctx={cur_ctx.hex()})")

    # Both images carry row 0x7A -> we expect exactly 2 stock copies on a fresh
    # stock cyacd. Accept 1 or 2 (a prior patch level may have already flipped one),
    # but never 0.
    if patched_count == 0:
        print("ERROR: patched 0 rows -- gate context not found "
              "(already patched, or wrong cyacd?)", file=sys.stderr)
        return 1
    if patched_count not in (1, 2):
        print(f"ERROR: patched {patched_count} rows, expected 1 or 2",
              file=sys.stderr)
        return 1

    open(args.out_cyacd, "wb").write(serialize_cyacd(header, rows))
    print(f"\npatched {patched_count} image copy/copies; wrote {args.out_cyacd}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
