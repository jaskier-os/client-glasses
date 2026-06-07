#!/usr/bin/env python3
"""
Disassemble image2_flat.bin (the PSoC4000R application image) as
Cortex-M0+ Thumb code and emit one big text dump for grepping. Writes
to /tmp/rokid-touchpad/extracted/image2_disasm.txt.

Usage: ./disasm.py [image2_flat.bin] [out.txt]
"""
import sys
import struct
import capstone


def main():
    in_path = sys.argv[1] if len(sys.argv) > 1 else \
        "/tmp/rokid-touchpad/extracted/image2_flat.bin"
    out_path = sys.argv[2] if len(sys.argv) > 2 else \
        "/tmp/rokid-touchpad/extracted/image2_disasm.txt"

    img = open(in_path, "rb").read()
    md = capstone.Cs(capstone.CS_ARCH_ARM, capstone.CS_MODE_THUMB | capstone.CS_MODE_MCLASS)
    md.detail = False
    md.skipdata = True  # emit `.byte 0xXX` for un-decodable bytes, keep going

    # Disassemble everything from the start of populated flash.
    # The cyacd rows start at 0x1400 (row 0x50). Anything before that
    # is bootloader space we don't have.
    APP_BASE = 0x1400
    out = []
    out.append(f"; image2_flat.bin disassembled as Cortex-M0+ Thumb")
    out.append(f"; populated range: 0x{APP_BASE:x} .. 0x{len(img):x}")
    out.append(f"")
    # Annotate vector table
    out.append(f"; ---- vector table @ 0x{APP_BASE:x} ----")
    for i in range(16):
        v = struct.unpack_from("<I", img, APP_BASE + i*4)[0]
        names = ["SP_INIT","RESET","NMI","HARDFAULT","-","-","-","-","-","-","-","SVCALL","-","-","PENDSV","SYSTICK"]
        nm = names[i] if i < len(names) else "?"
        out.append(f"  +0x{i*4:02x}  0x{v:08x}  ; {nm}")
    out.append(f"")

    # Disassemble from end of vector table to end of flash.
    # The first ~64 bytes are vector + a few more entries; we'll just
    # disassemble everything starting at 0x1440 and let the user spot
    # where real code begins.
    code_start = APP_BASE + 0x40
    out.append(f"; ---- code/data from 0x{code_start:x} ----")
    insns_count = 0
    for insn in md.disasm(img[code_start:], code_start):
        out.append(f"{insn.address:08x}  {insn.bytes.hex():<10s}  {insn.mnemonic:<8s} {insn.op_str}")
        insns_count += 1

    out.append(f"")
    out.append(f"; total instructions decoded: {insns_count}")

    open(out_path, "w").write("\n".join(out))
    print(f"wrote {out_path} ({len(out)} lines, {insns_count} instructions)")


if __name__ == "__main__":
    main()
