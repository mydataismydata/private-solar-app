package com.privatesolarmon.app.bms

/**
 * Minimal Modbus-RTU helper: build a "read holding registers" (function 0x03) request and parse
 * its response. CRC is CRC-16/Modbus (reused from [PackFrame.crc16Modbus]), appended low byte first.
 * Pure JVM — see ModbusRtuTest.
 */
object ModbusRtu {
    const val READ_HOLDING = 0x03

    /** Request: [slave][0x03][startHi][startLo][countHi][countLo][crcLo][crcHi] (Modbus is big-endian). */
    fun readHoldingRegisters(slave: Int, startReg: Int, count: Int): ByteArray {
        val body = byteArrayOf(
            slave.toByte(),
            READ_HOLDING.toByte(),
            ((startReg ushr 8) and 0xFF).toByte(), (startReg and 0xFF).toByte(),
            ((count ushr 8) and 0xFF).toByte(), (count and 0xFF).toByte(),
        )
        val crc = PackFrame.crc16Modbus(body, body.size)
        return body + byteArrayOf((crc and 0xFF).toByte(), ((crc ushr 8) and 0xFF).toByte())
    }

    /**
     * Parse a function-0x03 response `[slave][0x03][byteCount][data…][crcLo][crcHi]`.
     * Returns one Int per 16-bit register, or null on malformed frame / exception / wrong slave / bad CRC.
     */
    fun parseHoldingResponse(frame: ByteArray, expectedSlave: Int): IntArray? {
        if (frame.size < 5) return null
        if ((frame[0].toInt() and 0xFF) != expectedSlave) return null
        if ((frame[1].toInt() and 0xFF) != READ_HOLDING) return null // 0x83 would be an exception
        val byteCount = frame[2].toInt() and 0xFF
        val crcStart = 3 + byteCount
        if (frame.size < crcStart + 2) return null
        val got = (frame[crcStart].toInt() and 0xFF) or ((frame[crcStart + 1].toInt() and 0xFF) shl 8)
        if (got != PackFrame.crc16Modbus(frame, crcStart)) return null
        return IntArray(byteCount / 2) { i ->
            ((frame[3 + 2 * i].toInt() and 0xFF) shl 8) or (frame[3 + 2 * i + 1].toInt() and 0xFF)
        }
    }
}
