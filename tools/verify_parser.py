#!/usr/bin/env python3
"""Standalone validator + living spec for the JBD (Jiabaida) BMS BLE protocol.

The Eco-Worthy ECO-LFP48100 48V rack battery uses a JBD UP16S015 BMS, which
speaks the well-documented JBD / Xiaoxiang / "Overkill Solar" protocol over BLE.

This script implements the *exact* byte math that the Kotlin app uses, and asserts
it against real frames captured from JBD hardware (sourced from the Apache-2.0
project patman15/aiobmsble). Run it with `python tools/verify_parser.py` to prove
the decoder is correct without any Android tooling.

Protocol summary
----------------
GATT:  service 0xFF00 | notify(RX) 0xFF01 | write(TX) 0xFF02
Command:  DD A5 <cmd> <len> [data...] <crc_hi> <crc_lo> 77    (len/data usually 0)
Response: DD <cmd> <status> <len> [data...] <crc_hi> <crc_lo> 77
CRC = (0x10000 - sum(bytes from index 2 up to but not including the CRC)) & 0xFFFF
      i.e. the 2-byte two's-complement of the running sum, big-endian.
Reads:  0x03 = basic info, 0x04 = cell voltages, 0x05 = device name.
Note: a single logical frame can arrive split across several 20-byte BLE packets,
      so the app reassembles until length == len+7 and the trailing byte is 0x77.
"""
from __future__ import annotations

TAIL = 0x77


def crc(payload: bytes) -> int:
    """JBD checksum over the bytes between the header and the CRC field."""
    return (0x10000 - sum(payload)) & 0xFFFF


def build_read_cmd(cmd: int) -> bytes:
    """Assemble a register-read command frame, e.g. 0x03 -> dd a5 03 00 ff fd 77."""
    body = bytes([cmd, 0x00])          # cmd + length(0)
    c = crc(body)
    return bytes([0xDD, 0xA5]) + body + bytes([c >> 8, c & 0xFF, TAIL])


def u16(b: bytes, i: int) -> int:
    return (b[i] << 8) | b[i + 1]


def s16(b: bytes, i: int) -> int:
    v = u16(b, i)
    return v - 0x10000 if v & 0x8000 else v


def check_frame(frame: bytes, expected_cmd: int) -> bytes:
    """Validate header/tail/CRC and return the data section."""
    assert frame[0] == 0xDD, "bad start of frame"
    assert frame[1] == expected_cmd, f"unexpected cmd 0x{frame[1]:02x}"
    assert frame[2] == 0x00, f"BMS reported error status 0x{frame[2]:02x}"
    n = frame[3]
    end = 4 + n                        # index of first CRC byte
    assert len(frame) >= end + 3, "frame too short"
    assert frame[end + 2] == TAIL, "bad tail"
    got = (frame[end] << 8) | frame[end + 1]
    want = crc(frame[2:end])           # status + len + data
    assert got == want, f"CRC mismatch: got 0x{got:04x} want 0x{want:04x}"
    return frame[4:end]


def parse_basic(frame: bytes) -> dict:
    """Decode the 0x03 'basic info' register into engineering units."""
    check_frame(frame, 0x03)
    temp_sensors = frame[26]
    temps = [round((u16(frame, 27 + 2 * i) - 2731) / 10, 3) for i in range(temp_sensors)]
    fet = frame[24]
    return {
        "voltage": u16(frame, 4) / 100,            # 10 mV units -> V
        "current": s16(frame, 6) / 100,            # signed; + charge / - discharge
        "remaining_ah": u16(frame, 8) / 100,       # residual capacity
        "design_ah": u16(frame, 10) // 100,
        "cycles": u16(frame, 12),                  # <-- "Cycle Index"
        "protection_code": u16(frame, 20),
        "soc": frame[23],                          # %
        "charge_mosfet": bool(fet & 0x1),
        "discharge_mosfet": bool(fet & 0x2),
        "cell_count": frame[25],
        "temp_sensors": temp_sensors,
        "temps_c": temps,
        "temp_max_c": max(temps),
        "temp_min_c": min(temps),
    }


def parse_cells(frame: bytes) -> dict:
    """Decode the 0x04 'cell voltages' register (big-endian mV per cell)."""
    data = check_frame(frame, 0x04)
    cells = [round(u16(frame, 4 + 2 * i) / 1000, 3) for i in range(len(data) // 2)]
    return {
        "cells_v": cells,
        "cell_max_v": max(cells),
        "cell_min_v": min(cells),
        "delta_v": round(max(cells) - min(cells), 3),
    }


# --- real captured frames (source: patman15/aiobmsble, Apache-2.0) ---
BASIC = bytes.fromhex(
    "dd0300 1d 0618 fee1 01f2 01f4 002a 2c7c 0000 0000 0000 80 64 03 04 03"
    "0b8b 0b8a 0b84 f884 77".replace(" ", "")
)
CELLS = bytes.fromhex("dd040008 0d66 0d61 0d68 0d59 fe3c 77".replace(" ", ""))


def main() -> None:
    # command builders
    assert build_read_cmd(0x03).hex() == "dda50300fffd77"
    assert build_read_cmd(0x04).hex() == "dda50400fffc77"
    assert build_read_cmd(0x05).hex() == "dda50500fffb77"

    basic = parse_basic(BASIC)
    cells = parse_cells(CELLS)

    # assert against the known-correct decoded values from the source's test suite
    assert basic["voltage"] == 15.6, basic
    assert basic["current"] == -2.87, basic
    assert basic["soc"] == 100, basic
    assert basic["cycles"] == 42, basic
    assert basic["design_ah"] == 5, basic
    assert basic["remaining_ah"] == 4.98, basic
    assert basic["cell_count"] == 4, basic
    assert basic["temp_sensors"] == 3, basic
    assert basic["temps_c"] == [22.4, 22.3, 21.7], basic
    assert basic["charge_mosfet"] and basic["discharge_mosfet"], basic
    assert basic["protection_code"] == 0, basic
    assert cells["cells_v"] == [3.43, 3.425, 3.432, 3.417], cells
    assert cells["delta_v"] == 0.015, cells

    print("commands OK:", build_read_cmd(0x03).hex(), build_read_cmd(0x04).hex())
    print("basic :", basic)
    print("cells :", cells)
    print("\nALL ASSERTIONS PASSED - OK")


if __name__ == "__main__":
    main()
