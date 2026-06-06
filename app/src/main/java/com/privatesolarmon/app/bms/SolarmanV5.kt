package com.privatesolarmon.app.bms

/**
 * Solarman V5 framing — wraps a Modbus-RTU frame for IGEN/Solarman data-logger sticks (the
 * Eco-Worthy/SRNE WiFi dongle), spoken over local TCP port 8899.
 *
 * Frame: A5 | len(2,LE) | control(2,LE) | seq(2,LE) | loggerSerial(4,LE) | dataField | checksum | 15
 * `len` counts only the data field; checksum = sum(all bytes except start/checksum/end) & 0xFF.
 * Request control 0x4510, response 0x1510. The inner Modbus frame is big-endian.
 *
 * Ported from jmccrohan/pysolarmanv5; pinned to that library's output in SolarmanV5Test.
 */
object SolarmanV5 {
    private const val START = 0xA5
    private const val END = 0x15

    /** Wrap [modbusFrame] in a V5 request for data logger [serial] with sequence [sequence] (0–255). */
    fun encode(serial: Long, sequence: Int, modbusFrame: ByteArray): ByteArray {
        val payloadLen = 15 + modbusFrame.size // frameType(1)+sensorType(2)+3×time(4) + modbus
        val out = ArrayList<Byte>(13 + payloadLen)
        out.add(START.toByte())
        out.add((payloadLen and 0xFF).toByte()); out.add(((payloadLen ushr 8) and 0xFF).toByte())
        out.add(0x10); out.add(0x45) // control code 0x4510 (little-endian)
        out.add((sequence and 0xFF).toByte()); out.add(((sequence ushr 8) and 0xFF).toByte())
        out.add((serial and 0xFF).toByte())
        out.add(((serial ushr 8) and 0xFF).toByte())
        out.add(((serial ushr 16) and 0xFF).toByte())
        out.add(((serial ushr 24) and 0xFF).toByte())
        out.add(0x02) // frame type (solar inverter)
        out.add(0x00); out.add(0x00) // sensor type
        repeat(12) { out.add(0x00) } // total working + power-on + offset times
        modbusFrame.forEach { out.add(it) }
        var sum = 0
        for (i in 1 until out.size) sum += out[i].toInt() and 0xFF
        out.add((sum and 0xFF).toByte()) // checksum
        out.add(END.toByte())
        return out.toByteArray()
    }

    /** Validate a V5 response and return its inner Modbus-RTU frame, or null if invalid. */
    fun decode(frame: ByteArray): ByteArray? {
        if (frame.size < 13) return null
        if ((frame[0].toInt() and 0xFF) != START) return null
        val payloadLen = (frame[1].toInt() and 0xFF) or ((frame[2].toInt() and 0xFF) shl 8)
        val total = 13 + payloadLen
        if (frame.size < total) return null
        if ((frame[total - 1].toInt() and 0xFF) != END) return null
        var sum = 0
        for (i in 1 until total - 2) sum += frame[i].toInt() and 0xFF
        if ((sum and 0xFF) != (frame[total - 2].toInt() and 0xFF)) return null
        if ((frame[3].toInt() and 0xFF) != 0x10 || (frame[4].toInt() and 0xFF) != 0x15) return null // 0x1510
        val mbStart = 25
        val mbEnd = total - 2
        if (mbEnd <= mbStart) return null
        return frame.copyOfRange(mbStart, mbEnd)
    }
}
