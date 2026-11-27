"""Genkan: writes pce-cd-test.chd, disc_sector_test's made-up disc: a tiny uncompressed CHD v5 CD image (an
audio track, then a MODE1 data track whose second sector is a PC Engine CD boot block with a made-up
program name). chdman isn't needed. Usage: python3 make_chd.py pce-cd-test.chd"""
import struct
import sys

FRAME = 2352
SUB = 96
UNIT = FRAME + SUB          # a CD frame and its subcode, as a CHD stores it
HUNK_FRAMES = 8
HUNK = UNIT * HUNK_FRAMES


def boot_block() -> bytes:
    b = bytearray(2048)
    b[0x20:0x20 + 24] = b"PC Engine CD-ROM SYSTEM\0"
    b[0x38:0x38 + 20] = b"Copyright TEST SOFT\0"
    b[0x6A:0x6A + 12] = b"TEST PROGRAM"
    return bytes(b)


def mode1(user: bytes, lba: int) -> bytes:
    """A raw MODE1 sector: sync, header (MSF of lba + 150, mode 1), 2048 bytes, EDC/ECC left zero."""
    m, rest = divmod(lba + 150, 75 * 60)
    s, f = divmod(rest, 75)
    bcd = lambda v: (v // 10) << 4 | v % 10
    head = b"\x00" + b"\xff" * 10 + b"\x00" + bytes([bcd(m), bcd(s), bcd(f), 1])
    return head + user + bytes(FRAME - 16 - len(user))


def main(out: str) -> None:
    audio_frames, data_frames = 2, 3
    pad = lambda n: (4 - n % 4) % 4                     # each track padded to 4 frames in a CHD
    frames = []
    for i in range(audio_frames):
        frames.append(bytes([(i * 7 + j) & 0xFF for j in range(FRAME)]))
    frames += [bytes(FRAME)] * pad(audio_frames)
    for i in range(data_frames):
        user = boot_block() if i == 1 else bytes([0x11 * (i + 1)] * 2048)
        frames.append(mode1(user, audio_frames + i))
    frames += [bytes(FRAME)] * pad(data_frames)
    units = b"".join(f + bytes(SUB) for f in frames)
    hunks = (len(units) + HUNK - 1) // HUNK
    units += bytes(hunks * HUNK - len(units))

    meta = []
    for n, (kind, count) in enumerate((("AUDIO", audio_frames), ("MODE1_RAW", data_frames)), start=1):
        text = f"TRACK:{n} TYPE:{kind} SUBTYPE:NONE FRAMES:{count} PREGAP:0 PGTYPE:MODE1 PGSUB:RW POSTGAP:0\0".encode()
        meta.append(text)

    header_len = 124
    map_offset = header_len
    map_bytes = 4 * hunks
    meta_offset = map_offset + map_bytes
    entries = b""
    at = meta_offset
    for i, text in enumerate(meta):
        nxt = at + 16 + len(text) if i + 1 < len(meta) else 0
        entries += b"CHT2" + struct.pack(">I", 0x01000000 | len(text)) + struct.pack(">Q", nxt) + text
        at += 16 + len(text)
    data_offset = meta_offset + len(entries)
    data_offset += (-data_offset) % HUNK                # hunks at multiples of the hunk size
    hunk_map = b"".join(struct.pack(">I", data_offset // HUNK + i) for i in range(hunks))

    header = (b"MComprHD" + struct.pack(">II", header_len, 5) + bytes(16)       # no compressors: raw hunks
              + struct.pack(">QQQII", hunks * HUNK, map_offset, meta_offset, HUNK, UNIT) + bytes(60))
    assert len(header) == header_len
    body = header + hunk_map + entries
    body += bytes(data_offset - len(body)) + units
    with open(out, "wb") as f:
        f.write(body)


if __name__ == "__main__":
    main(sys.argv[1])
