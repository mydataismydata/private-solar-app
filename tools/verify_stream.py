#!/usr/bin/env python3
"""Validate the resynchronizing frame parser against REAL bytes captured from the
ECO-LFP48100-3U (JBD UP16S015) over BLE — the Sniff-screen log, 2026-06-01.

This is a 1:1 port of the Kotlin `JbdFrameAssembler` sliding-window logic. It confirms
the parser pulls the genuine 0x04 (16-cell) reply out of a stream that also carries the
battery's unsolicited telemetry frames (the `02 51 .. a1` blocks), without tripping on them.
"""
from __future__ import annotations

TAIL, SOF, VALID = 0x77, 0xDD, {0x03, 0x04, 0x05}


def checksum(payload: bytes) -> int:
    return (0x10000 - sum(payload)) & 0xFFFF


class Assembler:
    def __init__(self) -> None:
        self.buf = bytearray()

    def append(self, chunk: bytes) -> None:
        self.buf += chunk

    def next(self):
        while True:
            while self.buf and self.buf[0] != SOF:
                self.buf.pop(0)
            if len(self.buf) < 4:
                return None
            cmd, status, n = self.buf[1], self.buf[2], self.buf[3]
            if cmd not in VALID or status != 0x00:
                self.buf.pop(0)
                continue
            total = 4 + n + 3
            if len(self.buf) < total:
                return None
            if self.buf[total - 1] != TAIL:
                print(f"  resync: bad tail (cmd 0x{cmd:02x})")
                self.buf.pop(0)
                continue
            frame = bytes(self.buf[:total])
            del self.buf[:total]
            crc_start = 4 + n
            got = (frame[crc_start] << 8) | frame[crc_start + 1]
            want = checksum(frame[2:crc_start])
            if got != want:
                print(f"  reject: CRC 0x{got:04x} != 0x{want:04x}")
                continue
            print(f"  frame ok: cmd 0x{cmd:02x}, {n} data bytes")
            return cmd, frame[4:crc_start]


def cells(data: bytes):
    return [round(((data[2 * i] << 8) | data[2 * i + 1]) / 1000, 3) for i in range(len(data) // 2)]


# The notification chunks exactly as logged on the device (after a 0x04 read).
CAPTURE = [
    "dd 04 00 20 0d 43 0d 45 0d 41 0d 3f 0d 3c 0d 3b 0d 40 0d 41",
    "0d 42 0d 43 0d 40 0d 3d 0d 3c 0d 3d 0d 40 0d 42 fb 13 77",
    "02 51 00 00 ff ff 00 36 15 2d 00 94 00 50 00 64 0b e3 0d 40",  # unsolicited stream
    "0d 39 0b e7 0b e2 00 03 03 29 03 e8 00 01 00 02 00 00 00 00",
    "00 00 03 07 03 0b 03 01 03 02 00 00 00 00 5a a6 00 00 00 00",
    "00 00 00 a1",
]


def main() -> None:
    asm = Assembler()
    frames = []
    for line in CAPTURE:
        asm.append(bytes.fromhex(line.replace(" ", "")))
        while True:
            f = asm.next()
            if f is None:
                break
            frames.append(f)

    assert len(frames) == 1, f"expected exactly one valid frame, got {len(frames)}"
    cmd, data = frames[0]
    assert cmd == 0x04
    c = cells(data)
    assert len(c) == 16, f"expected 16 cells, got {len(c)}"
    print("\ncells (V):", c)
    print(f"count={len(c)}  min={min(c)}  max={max(c)}  delta={round(max(c) - min(c), 3)}")
    print("leftover buffer bytes:", len(asm.buf), "(streamed junk correctly skipped)")
    print("\nRESYNC PARSER OK - extracted the real 16-cell frame, ignored the telemetry stream")


if __name__ == "__main__":
    main()
