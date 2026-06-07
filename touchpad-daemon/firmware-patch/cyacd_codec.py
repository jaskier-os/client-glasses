"""
Cypress cyacd row codec.

A cyacd file is an ASCII text file produced by PSoC Creator. The first
line is an 11-character header (silicon-id 4B + revision 1B + checksum-type 1B,
hex-encoded). Each subsequent line is one flash-row record:

    : aa rrrr llll DD..DD cc

    aa   = arrayId (1 byte, 2 hex chars)
    rrrr = row number (2 bytes, 4 hex chars, big-endian)
    llll = data length (2 bytes, 4 hex chars, big-endian)
    DD.. = data (`llll` bytes, 2*llll hex chars)
    cc   = checksum (1 byte, 2 hex chars)

The checksum is the 2's complement of the sum of (arrayId + rowNum_hi +
rowNum_lo + len_hi + len_lo + sum(data)), low byte only.

In the Rokid driver the cyacd lines are concatenated in `.rodata` of the
.ko separated by 0x0a (newline) bytes. Two firmware images may be
concatenated end-to-end (stringImage_1v8 / stringImage_1v9).
"""
from dataclasses import dataclass
from typing import List


@dataclass
class CyacdRow:
    array_id: int
    row_num: int
    data: bytes
    checksum: int  # the byte stored in the file (for round-trip validation)

    @property
    def computed_checksum(self) -> int:
        s = self.array_id
        s += (self.row_num >> 8) & 0xFF
        s += self.row_num & 0xFF
        s += (len(self.data) >> 8) & 0xFF
        s += len(self.data) & 0xFF
        s += sum(self.data)
        return ((-s) & 0xFF)

    @property
    def checksum_ok(self) -> bool:
        return self.checksum == self.computed_checksum

    def serialize(self) -> bytes:
        """Re-emit the row as ASCII bytes (no trailing newline)."""
        out = b":"
        out += f"{self.array_id:02X}".encode()
        out += f"{self.row_num:04X}".encode()
        out += f"{len(self.data):04X}".encode()
        out += self.data.hex().upper().encode()
        out += f"{self.checksum:02X}".encode()
        return out


def parse_row(buf: bytes, offset: int) -> CyacdRow | None:
    """Try to parse a cyacd row starting at `offset`. Returns None if no
    valid row is present at that position. Does NOT validate checksum
    (caller decides)."""
    HEX = b"0123456789abcdefABCDEF"
    if offset >= len(buf) or buf[offset] != ord(":"):
        return None
    if offset + 11 > len(buf):
        return None
    head = buf[offset + 1 : offset + 11]
    if not all(b in HEX for b in head):
        return None
    array_id = int(head[0:2], 16)
    row_num = int(head[2:6], 16)
    data_len = int(head[6:10], 16)
    total_chars = 1 + 10 + 2 * data_len + 2
    if offset + total_chars > len(buf):
        return None
    body = buf[offset + 11 : offset + 11 + 2 * data_len + 2]
    if not all(b in HEX for b in body):
        return None
    data_bytes = bytes.fromhex(body[: 2 * data_len].decode("ascii"))
    chk = int(body[2 * data_len :].decode("ascii"), 16)
    return CyacdRow(array_id, row_num, data_bytes, chk)


def find_all_rows(buf: bytes) -> List[tuple[int, int, CyacdRow]]:
    """Scan buf for every well-formed cyacd row. Returns list of
    (offset, total_char_length, row).
    Skips past each successful parse to avoid overlapping matches."""
    rows = []
    i = 0
    while i < len(buf):
        r = parse_row(buf, i)
        if r is None:
            i += 1
            continue
        total = 1 + 10 + 2 * len(r.data) + 2
        rows.append((i, total, r))
        i += total
    return rows


def parse_cyacd_text(text: bytes) -> tuple[bytes, List[CyacdRow]]:
    """Parse a standalone cyacd file: first line is the header, rest are
    rows. Returns (header_bytes, rows)."""
    lines = text.replace(b"\r", b"").split(b"\n")
    lines = [ln for ln in lines if ln.strip()]
    if not lines:
        return b"", []
    header = lines[0]
    rows = []
    for ln in lines[1:]:
        r = parse_row(ln, 0)
        if r is None:
            raise ValueError(f"could not parse cyacd row: {ln[:40]!r}...")
        rows.append(r)
    return header, rows


def serialize_cyacd(header: bytes, rows: List[CyacdRow]) -> bytes:
    """Serialize a header + list of rows back into cyacd text."""
    out = header + b"\r\n"
    for r in rows:
        out += r.serialize() + b"\r\n"
    return out
