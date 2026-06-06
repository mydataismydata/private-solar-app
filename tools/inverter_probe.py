#!/usr/bin/env python3
"""Probe the Eco-Worthy ECO10000W(SR) inverter through its Solarman WiFi dongle.

The dongle accepts local Modbus reads on TCP port 8899 (Solarman V5 protocol wraps Modbus-RTU)
— no cloud needed. This script confirms that works and dumps live inverter telemetry, so we can
build the in-app reader against real values (same de-risking approach we used for the batteries).

Run on a PC/phone on the SAME Wi-Fi as the dongle:

    pip install pysolarmanv5
    python tools/inverter_probe.py                      # try to auto-discover the dongle
    python tools/inverter_probe.py 192.168.1.50 1234567890   # or pass <IP> <SERIAL> directly
    python tools/inverter_probe.py 192.168.1.50 1234567890 0x0100 0x0140  # custom sweep range

Find the dongle's IP in your router's client list; the SERIAL (≈10 digits) is on the dongle's
label (or auto-discovered below). Register map: SRNE HESP (danzelziggy/srne-solarman V2.07).

After printing the mapped registers it also does a one-register-at-a-time SWEEP of an address
range (default 0x0100..0x0140) — this finds channels the firmware hides from block reads, such
as the 2nd MPPT. Non-zero registers are printed with a x0.1 volts/amps hint.
"""
from __future__ import annotations

import socket
import sys

# (address, name, scale, signed, unit)
REGS: list[tuple[int, str, float, bool, str]] = [
    (0x0100, "Battery SOC", 1, False, "%"),
    (0x0101, "Battery Voltage", 0.1, False, "V"),
    (0x0102, "Battery Current", 0.1, True, "A"),
    (0x0103, "Battery Temp", 0.1, True, "C"),
    (0x0107, "PV1 Voltage", 0.1, False, "V"),
    (0x0108, "PV1 Current", 0.1, False, "A"),
    (0x0109, "PV1 Power", 1, False, "W"),
    (0x010F, "PV2 Voltage", 0.1, False, "V"),
    (0x0110, "PV2 Current", 0.1, False, "A"),
    (0x0111, "PV2 Power", 1, False, "W"),
    (0x010E, "PV+AC Power", 1, False, "W"),
    (0x0213, "Grid L1 Voltage", 0.1, False, "V"),
    (0x0214, "Grid L1 Current", 0.1, False, "A"),
    (0x0215, "Grid Frequency", 0.01, False, "Hz"),
    (0x0216, "Output L1 Voltage", 0.1, False, "V"),
    (0x0217, "Output L1 Current", 0.1, True, "A"),
    (0x0218, "Output Frequency", 0.01, False, "Hz"),
    (0x0219, "Load L1 Current", 0.1, False, "A"),
    (0x021B, "Load L1 Power", 1, False, "W"),
    (0x021C, "Load L1 Apparent", 1, False, "VA"),
    (0x0220, "DC Temp", 0.1, False, "C"),
    (0x0221, "AC Temp", 0.1, False, "C"),
    (0x022A, "Grid L2 Voltage", 0.1, False, "V"),
    (0x022C, "Output L2 Voltage", 0.1, False, "V"),
]


def discover(timeout: float = 3.0) -> tuple[str | None, str | None]:
    """UDP-broadcast discovery of Solarman/IGEN loggers (port 48899)."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    s.settimeout(timeout)
    try:
        s.sendto(b"WIFIKIT-214028-READ", ("255.255.255.255", 48899))
        data, _ = s.recvfrom(1024)
        parts = data.decode(errors="ignore").split(",")
        return parts[0], parts[2]  # ip, serial  (reply is "IP,MAC,SERIAL")
    except Exception:
        return None, None
    finally:
        s.close()


def signed16(v: int) -> int:
    return v - 0x10000 if v & 0x8000 else v


def sweep(mb, start: int, end: int) -> None:
    """Read each register in [start, end] individually and print every one that responds.

    Single-register reads are robust against firmware that NAKs multi-register block
    reads, so this surfaces channels the block reader can't see (e.g. the 2nd MPPT).
    A register that answers (even with 0) EXISTS; one that raises is unsupported and is
    skipped. Zeros are printed too, so an idle 2nd MPPT can still be located by address.
    """
    print(f"\n--- raw sweep 0x{start:04x}..0x{end:04x} (one register at a time) ---")
    responded = 0
    for addr in range(start, end + 1):
        try:
            raw = mb.read_holding_registers(addr, 1)[0]
        except Exception:  # noqa: BLE001 — unsupported register / NAK, just skip it
            continue
        responded += 1
        hint = f"~{raw * 0.1:>7.1f}  (if x0.1 V/A)" if raw else "(zero)"
        print(f"  0x{addr:04x} = {raw:>6}  (0x{raw:04x})   {hint}")
    print(f"  -> {responded} registers responded in this range")


def main() -> None:
    if len(sys.argv) >= 3:
        ip, serial = sys.argv[1], sys.argv[2]
    else:
        print("discovering logger via UDP 48899 …")
        ip, serial = discover()
        if not ip:
            print("no logger auto-found. Run: python tools/inverter_probe.py <IP> <SERIAL>")
            return
    print(f"logger ip={ip} serial={serial}")

    try:
        from pysolarmanv5 import PySolarmanV5
    except ImportError:
        print("missing dependency — run:  pip install pysolarmanv5")
        return

    mb = None
    last_err: Exception | None = None
    for slave in (1, 0xFF):  # SRNE default is 1; docs also mention universal 0xFF
        try:
            client = PySolarmanV5(ip, int(serial), port=8899, mb_slave_id=slave, socket_timeout=4)
            client.read_holding_registers(0x0100, 1)  # liveness check
            mb = client
            print(f"connected (modbus slave id {slave})\n")
            break
        except Exception as e:  # noqa: BLE001
            last_err = e
    if mb is None:
        print("could not read from logger:", last_err)
        print("(if it connects but every read fails, port 8899 may be locked by firmware)")
        return

    # Read registers one at a time — robust against firmware that NAKs multi-register ranges.
    for addr, name, scale, is_signed, unit in REGS:
        try:
            raw = mb.read_holding_registers(addr, 1)[0]
            val = (signed16(raw) if is_signed else raw) * scale
            shown = round(val, 2) if scale != 1 else int(val)
            print(f"  0x{addr:04x}  {name:18s} {shown:>9} {unit:3s}  (raw {raw} / 0x{raw:04x})")
        except Exception as e:  # noqa: BLE001
            print(f"  0x{addr:04x}  {name:18s}   <no data: {e}>")

    # Sweep for unmapped channels (e.g. the 2nd MPPT). Override with trailing hex args:
    #   python tools/inverter_probe.py <IP> <SERIAL> <start_hex> <end_hex>
    scan_start = int(sys.argv[3], 16) if len(sys.argv) >= 4 else 0x0100
    scan_end = int(sys.argv[4], 16) if len(sys.argv) >= 5 else 0x0160
    sweep(mb, scan_start, scan_end)

    try:
        mb.disconnect()
    except Exception:
        pass
    print("\nDONE")


if __name__ == "__main__":
    main()
