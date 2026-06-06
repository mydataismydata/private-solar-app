#!/usr/bin/env python3
"""Mirror of the Kotlin Modbus + Solarman V5 codec, checked against reference frames generated
by the real pysolarmanv5 library (logger serial 1234567890, sequence 0x5A). Confirms the Kotlin
port (ModbusRtu.kt / SolarmanV5.kt) is byte-correct before building the app."""
from __future__ import annotations


def mbcrc(d: bytes) -> int:
    c = 0xFFFF
    for b in d:
        c ^= b
        for _ in range(8):
            c = (c >> 1) ^ 0xA001 if c & 1 else c >> 1
    return c


def modbus_read(slave: int, start: int, count: int) -> bytes:  # ModbusRtu.readHoldingRegisters
    body = [slave, 3, (start >> 8) & 0xFF, start & 0xFF, (count >> 8) & 0xFF, count & 0xFF]
    c = mbcrc(bytes(body))
    return bytes(body + [c & 0xFF, (c >> 8) & 0xFF])


def v5_encode(serial: int, seq: int, modbus: bytes) -> bytes:  # SolarmanV5.encode
    payload_len = 15 + len(modbus)
    out = [0xA5, payload_len & 0xFF, (payload_len >> 8) & 0xFF, 0x10, 0x45, seq & 0xFF, (seq >> 8) & 0xFF]
    out += [serial & 0xFF, (serial >> 8) & 0xFF, (serial >> 16) & 0xFF, (serial >> 24) & 0xFF]
    out += [0x02, 0x00, 0x00] + [0x00] * 12 + list(modbus)
    out.append(sum(out[1:]) & 0xFF)
    out.append(0x15)
    return bytes(out)


def v5_decode(frame: bytes):  # SolarmanV5.decode
    if len(frame) < 13 or frame[0] != 0xA5:
        return None
    total = 13 + (frame[1] | (frame[2] << 8))
    if len(frame) < total or frame[total - 1] != 0x15:
        return None
    if (sum(frame[1:total - 2]) & 0xFF) != frame[total - 2]:
        return None
    if frame[3] != 0x10 or frame[4] != 0x15:
        return None
    mb = frame[25:total - 2]
    return mb or None


def parse_holding(frame: bytes, slave: int):  # ModbusRtu.parseHoldingResponse
    if len(frame) < 5 or frame[0] != slave or frame[1] != 0x03:
        return None
    n = frame[2]
    crc_start = 3 + n
    if len(frame) < crc_start + 2:
        return None
    got = frame[crc_start] | (frame[crc_start + 1] << 8)
    if got != mbcrc(frame[:crc_start]):
        return None
    return [(frame[3 + 2 * i] << 8) | frame[3 + 2 * i + 1] for i in range(n // 2)]


def main() -> None:
    req = modbus_read(1, 0x0100, 1)
    assert req.hex() == "01030100000185f6", req.hex()

    frame = v5_encode(1234567890, 0x5A, req)
    assert frame.hex() == "a5170010455a00d202964902000000000000000000000000000001030100000185f6fc15", frame.hex()

    resp = bytes.fromhex("a5150010155a00d202964902010000000000000000000000000103020054b9bb1815")
    mb = v5_decode(resp)
    assert mb is not None and mb.hex() == "0103020054b9bb", mb
    assert parse_holding(mb, 1) == [0x0054]

    print("request frame :", frame.hex())
    print("decoded modbus:", mb.hex(), "-> regs", parse_holding(mb, 1))
    print("\nKOTLIN CODEC REPLICA MATCHES REFERENCE - OK")


if __name__ == "__main__":
    main()
