#!/usr/bin/env python3
"""
Embed a (possibly modified) cyacd row set back into a copy of the stock
psoc_ts_drv_right.ko, preserving each row's original file offset.

Inputs:
  --in-ko          stock psoc_ts_drv_right.ko (read-only)
  --in-cyacd       cyacd file produced by extract.py (or modified)
  --offsets        stock_offsets.json from extract.py — drives placement
  --out-ko         destination .ko (will be overwritten)
  --bump-version N optional: bump the embedded fw_version_code by N
                   (default 0). Driver re-flashes when chip != code.

The cyacd input may have rows in any order. We re-pair each row to the
.ko offset by (array_id, row_num) ordinal: the Nth occurrence of a given
(array_id, row_num) in the cyacd lands at the Nth occurrence in the
.ko offsets list. This handles cases where the same row appears in
both stringImage_1v8 and stringImage_1v9 (two firmware variants
embedded side-by-side).

Each rewritten row is re-checksummed automatically.
"""
import argparse
import json
import sys
from collections import defaultdict

from cyacd_codec import CyacdRow, parse_cyacd_text, parse_row


# Where the kernel module's compiled-in fw_version_code (=144=0x90) is
# stored. Empirically located at file offset 0x132A0 (1 byte u8). The
# bytes around it are 0x00 padding, so a single-byte bump there is safe.
# Confirm before flashing: byte at this offset must be 0x90 in stock .ko.
FW_VERSION_FILE_OFFSET = 0x132A0
FW_VERSION_EXPECTED_STOCK = 0x90


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--in-ko", required=True)
    ap.add_argument("--in-cyacd", required=True)
    ap.add_argument("--offsets", required=True)
    ap.add_argument("--out-ko", required=True)
    ap.add_argument("--bump-version", type=int, default=0,
                    help="add this many to the fw_version_code byte")
    args = ap.parse_args()

    ko = bytearray(open(args.in_ko, "rb").read())
    cyacd_text = open(args.in_cyacd, "rb").read()
    offsets = json.load(open(args.offsets))

    header, rows = parse_cyacd_text(cyacd_text)
    print(f"loaded {len(rows)} rows from {args.in_cyacd}")
    print(f"loaded {len(offsets)} offsets from {args.offsets}")
    if len(rows) != len(offsets):
        print(f"WARN: row count {len(rows)} != offsets count {len(offsets)}",
              file=sys.stderr)

    # Pair rows to offsets. Group offsets by (array_id, row_num) preserving
    # file order. Then walk rows in cyacd order; for each, take the next
    # available offset slot for that key.
    slots_by_key = defaultdict(list)
    for o in offsets:
        slots_by_key[(o["array_id"], o["row_num"])].append(o)
    cursor = defaultdict(int)

    written_offsets = set()
    for r in rows:
        key = (r.array_id, r.row_num)
        idx = cursor[key]
        if idx >= len(slots_by_key[key]):
            print(f"WARN: cyacd has more rows for (arr={r.array_id}, "
                  f"row=0x{r.row_num:x}) than .ko has slots; skipping",
                  file=sys.stderr)
            continue
        slot = slots_by_key[key][idx]
        cursor[key] += 1

        # Re-checksum the row (in case data changed) before emit.
        # CyacdRow.serialize uses self.checksum verbatim; recompute and
        # update self.checksum so the output row is internally consistent.
        r.checksum = r.computed_checksum
        wire = r.serialize()
        expected_total = slot["total_chars"]
        if len(wire) != expected_total:
            print(f"ERR: row arr={r.array_id} row=0x{r.row_num:x}: "
                  f"serialized len {len(wire)} != slot total {expected_total}",
                  file=sys.stderr)
            return 1

        off = slot["offset"]
        ko[off : off + expected_total] = wire
        written_offsets.add(off)

    print(f"wrote {len(written_offsets)} rows back into .ko")

    # Optional version bump. Sanity-check against expected stock value
    # before touching it.
    if args.bump_version:
        old = ko[FW_VERSION_FILE_OFFSET]
        if old != FW_VERSION_EXPECTED_STOCK:
            print(f"WARN: fw_version_code @0x{FW_VERSION_FILE_OFFSET:x} = "
                  f"0x{old:02x}, expected 0x{FW_VERSION_EXPECTED_STOCK:02x}; "
                  f"bumping anyway", file=sys.stderr)
        new = (old + args.bump_version) & 0xFF
        ko[FW_VERSION_FILE_OFFSET] = new
        print(f"bumped fw_version_code @0x{FW_VERSION_FILE_OFFSET:x}: "
              f"{old} -> {new}")

    open(args.out_ko, "wb").write(bytes(ko))
    print(f"wrote {args.out_ko}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
