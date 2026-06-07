#!/usr/bin/env python3
"""
Extract the embedded Cypress cyacd firmware blob from a stock
psoc_ts_drv_right.ko and dump artifacts that the rest of the toolchain
will consume:

  stock.cyacd            -- proper cyacd file with synthesized header
                            (PSoC4000R, silicon-id placeholder) so any
                            standard cyacd tool / reverser can read it.
  stock_offsets.json     -- list of {offset, row_num, total_chars} so
                            the embedder can put modified rows back into
                            the same file offsets in a copy of the .ko.
                            Required because rows are NOT all contiguous;
                            the linker has interleaved unrelated strings
                            between some of them.
  stock_rows.bin         -- flat firmware image keyed by row number
                            (rowNum * 64 bytes). Sparse rows are zero.

Usage:
  ./extract.py /path/to/psoc_ts_drv_right.ko OUT_DIR
"""
import json
import os
import sys

from cyacd_codec import find_all_rows


# Cypress PSoC4000 row size = 64 bytes (verified empirically: every row
# in this firmware has dataLen=64).
ROW_BYTES = 64

# Synthesized cyacd header. We don't know the exact silicon ID Rokid
# uses — the on-device bootloader doesn't need it (the driver reads it
# from the chip at runtime). 11 hex chars: 8=silicon-id, 2=revision,
# 1=checksum-type. Use 0 for everything; cyacd consumers that validate
# silicon-id will need this updated, but our flasher (the kernel
# module) doesn't read the header.
CYACD_HEADER_PLACEHOLDER = b"00000000" + b"00" + b"0"  # 11 chars


def main(ko_path: str, out_dir: str) -> int:
    os.makedirs(out_dir, exist_ok=True)
    data = open(ko_path, "rb").read()

    rows_with_off = find_all_rows(data)
    if not rows_with_off:
        print("ERROR: no cyacd rows found in", ko_path, file=sys.stderr)
        return 1

    bad = [(off, r) for (off, _, r) in rows_with_off if not r.checksum_ok]
    if bad:
        print(f"ERROR: {len(bad)} rows have bad checksums", file=sys.stderr)
        for off, r in bad[:5]:
            print(f"  @0x{off:x} stored=0x{r.checksum:02x} computed=0x{r.computed_checksum:02x}")
        return 1

    print(f"found {len(rows_with_off)} cyacd rows, all checksums valid")
    print(f"first @ 0x{rows_with_off[0][0]:x}, last @ 0x{rows_with_off[-1][0]:x}")

    # Detect whether two firmware images are concatenated. Heuristic:
    # within each image rows should appear in *some* consistent order;
    # if we see a row_num repeated, that marks the start of a 2nd image.
    seen = {}
    img_split = None
    for idx, (off, _, r) in enumerate(rows_with_off):
        key = (r.array_id, r.row_num)
        if key in seen:
            img_split = idx
            break
        seen[key] = idx

    img1 = rows_with_off if img_split is None else rows_with_off[:img_split]
    img2 = [] if img_split is None else rows_with_off[img_split:]
    print(f"image1: {len(img1)} rows  image2: {len(img2)} rows")

    # 1. stock.cyacd — emit BOTH images concatenated. The Rokid driver
    #    flashes whichever it picks at runtime; we just need the bytes
    #    for reversing.
    cyacd = CYACD_HEADER_PLACEHOLDER + b"\r\n"
    for _, _, r in rows_with_off:
        cyacd += r.serialize() + b"\r\n"
    open(os.path.join(out_dir, "stock.cyacd"), "wb").write(cyacd)

    # 2. stock_offsets.json — preserve the exact .ko placement of every
    #    row, so the embedder can patch in place.
    offsets = []
    for off, total, r in rows_with_off:
        offsets.append({
            "offset": off,
            "total_chars": total,
            "array_id": r.array_id,
            "row_num": r.row_num,
            "data_len": len(r.data),
            "stored_checksum": r.checksum,
        })
    open(os.path.join(out_dir, "stock_offsets.json"), "w").write(
        json.dumps(offsets, indent=2)
    )

    # 3. stock_rows.bin — flat image keyed by row_num. Lay out at row_num*64.
    #    For PSoC4000 the application can live up to row 0xff (= 256 rows
    #    * 64 = 16 KB flash). Allocate that much, plus a separate file
    #    for image2 if present.
    def flatten(rows, max_row=0xFF):
        flat = bytearray((max_row + 1) * ROW_BYTES)
        for _, _, r in rows:
            base = r.row_num * ROW_BYTES
            flat[base : base + ROW_BYTES] = r.data
        return bytes(flat)

    flat1 = flatten(img1)
    open(os.path.join(out_dir, "image1_flat.bin"), "wb").write(flat1)
    if img2:
        flat2 = flatten(img2)
        open(os.path.join(out_dir, "image2_flat.bin"), "wb").write(flat2)

    print(f"wrote {out_dir}/{{stock.cyacd, stock_offsets.json, image1_flat.bin"
          + (', image2_flat.bin' if img2 else '') + "}}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
