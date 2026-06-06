#!/usr/bin/env python3
"""Lock the offsets of the ECO-LFP48100 unsolicited 64-byte pack-telemetry broadcast
(the `XX 51 00 00 ff ff ...` frame), using two real captures from device ...56:72 plus
the official app's on-screen ground truth.

Official app @15:33 (device ...56:72): SOC 82%, 53.27 V, -7.80 A, cycle 0,
cell max 3.333 V (#5) / min 3.325 V (#1), temp max 31.30 (#3) / min 30.70 (#2).

The two captures below are the same battery at other moments (E = charging earlier,
C = charging when the sun came out), so values won't equal the app exactly, but the
*structure* must be self-consistent (e.g. remaining_Ah/full_Ah == SOC%) and the temp
max/min of C must equal the app's 31.3 / 30.7.
"""
from __future__ import annotations

# device ...56:72, charging (earlier)
E = bytes.fromhex((
    "02 51 00 00 ff ff 00 36 15 2d 00 94 00 50 00 64 0b e3 0d 40"
    "0d 39 0b e7 0b e2 00 03 03 29 03 e8 00 01 00 02 00 00 00 00"
    "00 00 03 07 03 0b 03 01 03 02 00 00 00 00 5a a6 00 00 00 00"
    "00 00 00 a1"
).replace(" ", ""))

# device ...56:72, charging (sun came out)
C = bytes.fromhex((
    "02 51 00 00 ff ff 00 36 14 fe 00 9c 00 53 00 64 0b e3 0d 23"
    "0d 1b 0b e4 0b de 00 03 03 3e 03 e8 00 01 00 02 00 00 00 00"
    "00 00 03 02 03 0f 03 03 03 02 00 00 00 00 5a a6 00 00 00 00"
    "00 00 fb 62"
).replace(" ", ""))

# device ...57:4C — the SLAVE (parallel #2). Reports WHOLE-BANK extensive totals:
# full 200 Ah (2x100), remaining 167.4 Ah. Discharging (cloudy).
N = bytes.fromhex((
    "01 51 00 00 ff ff 00 36 14 e1 ff 71 00 53 00 64 0b e5 0d 12"
    "0d 09 0b e8 0b e2 00 03 06 8a 07 d0 00 02 00 03 00 00 00 00"
    "00 00 02 10 02 0c 01 03 02 02 00 00 00 00 5a a6 00 00 00 00"
    "00 00 6c a4"
).replace(" ", ""))


def u16(d, i): return (d[i] << 8) | d[i + 1]
def s16(d, i): v = u16(d, i); return v - 0x10000 if v & 0x8000 else v


def decode(d: bytes) -> dict:
    temps = [round((u16(d, o) - 2731) / 10, 1) for o in (16, 22, 24)]  # 3 NTC sensors
    return {
        "voltage": u16(d, 8) / 100,            # V
        "current": s16(d, 10) / 10,            # A, + charge / - discharge
        "soc": u16(d, 12),                     # %
        "soh": u16(d, 14),                     # % (constant 100 so far)
        "cell_max": u16(d, 18) / 1000,         # V
        "cell_min": u16(d, 20) / 1000,         # V
        "temps": temps,
        "temp_count": u16(d, 26),
        "remaining_ah": u16(d, 28) / 10,       # Ah
        "full_ah": u16(d, 30) / 10,            # Ah
        "parallel_num": u16(d, 32),            # 1 = master, 2+ = slave
        "crc_field": f"{d[62]:02x}{d[63]:02x}",
    }


def crc16_modbus(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc


def find_crc() -> str:
    """Search for the CRC algorithm/range that reproduces ALL frames' trailing 2 bytes."""
    frames = (E, C, N)
    le = lambda c: bytes([c & 0xFF, c >> 8])
    be = lambda c: bytes([c >> 8, c & 0xFF])
    for lo in range(0, 8):
        hi = 62
        if all(le(crc16_modbus(f[lo:hi])) == f[62:64] for f in frames):
            return f"CRC-16/Modbus over [{lo}:62], little-endian"
        if all(be(crc16_modbus(f[lo:hi])) == f[62:64] for f in frames):
            return f"CRC-16/Modbus over [{lo}:62], big-endian"
    return "not identified (will gate by signature + length instead)"


def main() -> None:
    de, dc, dn = decode(E), decode(C), decode(N)
    print("E (master):", de)
    print("C (master):", dc)
    print("N (slave) :", dn)

    # internal consistency: remaining/full *100 should equal SOC% (capacity tracks SOC)
    for tag, d in (("E", de), ("C", dc), ("N", dn)):
        pct = round(d["remaining_ah"] / d["full_ah"] * 100)
        assert abs(pct - d["soc"]) <= 1, f"{tag}: remaining/full {pct}% != SOC {d['soc']}%"
        assert 50 <= d["voltage"] <= 58, f"{tag}: voltage out of range"
        assert d["temp_count"] == 3
        assert d["cell_max"] >= d["cell_min"]

    # master (#1) reports its own 100 Ah; slave (#2) reports the WHOLE BANK = 200 Ah
    assert de["parallel_num"] == 1 and dc["parallel_num"] == 1, "master should be parallel #1"
    assert de["full_ah"] == 100.0 and dc["full_ah"] == 100.0, "master full = 100 Ah (own pack)"
    assert dn["parallel_num"] == 2, "slave should be parallel #2"
    assert dn["full_ah"] == 200.0, "slave full = 200 Ah (whole-bank aggregate)"

    # C must match the app's temperature max/min (slow-changing, ~same time)
    assert max(dc["temps"]) == 31.3, dc["temps"]
    assert min(dc["temps"]) == 30.7, dc["temps"]
    # sign sanity: both master captures were charging (+), slave capture discharging (-)
    assert de["current"] > 0 and dc["current"] > 0 and dn["current"] < 0

    print("\nCRC:", find_crc())
    print("\nBROADCAST + PARALLEL-NUM LOCKED - OK")


if __name__ == "__main__":
    main()
