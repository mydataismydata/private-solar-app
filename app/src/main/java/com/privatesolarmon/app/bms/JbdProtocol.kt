package com.privatesolarmon.app.bms

/**
 * JBD / Xiaoxiang ("Overkill Solar") BMS wire protocol: command framing + checksum.
 *
 * This object is pure (no Android dependencies) so it is fully unit-testable on the JVM.
 * The byte math here is validated against real hardware frames in
 * `tools/verify_parser.py` and in [JbdParserTest].
 *
 * Frame shapes:
 *   Command : DD A5 <cmd> <len> [data...] <crcHi> <crcLo> 77   (len/data usually 0 for reads)
 *   Response: DD <cmd> <status> <len> [data...] <crcHi> <crcLo> 77
 *   CRC = (0x10000 - sum(bytes from index 2 up to, but excluding, the CRC)) & 0xFFFF
 */
object JbdProtocol {
    const val SOF_RESPONSE = 0xDD
    const val SOF_COMMAND_1 = 0xDD
    const val SOF_COMMAND_2 = 0xA5
    const val TAIL = 0x77

    /** Register IDs that can be read with [readCommand]. */
    const val REG_BASIC_INFO = 0x03
    const val REG_CELL_VOLTAGES = 0x04
    const val REG_DEVICE_NAME = 0x05

    const val DEFAULT_PASSWORD = "000000"

    /** JBD checksum over the bytes between the 2-byte header and the CRC field. */
    fun checksum(payload: ByteArray): Int {
        var sum = 0
        for (b in payload) sum += b.toInt() and 0xFF
        return (0x10000 - sum) and 0xFFFF
    }

    /**
     * Assemble a register-read command, e.g. `readCommand(0x03)` -> `DD A5 03 00 FF FD 77`.
     */
    fun readCommand(register: Int): ByteArray {
        val body = byteArrayOf(register.toByte(), 0x00) // cmd + length(0)
        val crc = checksum(body)
        return byteArrayOf(
            SOF_COMMAND_1.toByte(),
            SOF_COMMAND_2.toByte(),
            body[0],
            body[1],
            ((crc ushr 8) and 0xFF).toByte(),
            (crc and 0xFF).toByte(),
            TAIL.toByte(),
        )
    }

    /**
     * Build the optional authentication frame used by newer firmware.
     * Most units stream reads without it; supply the user's password if the BMS rejects reads.
     * Layout: FF AA 15 <len> <password-ascii...> <sum8>, where sum8 = (0x15 + len + sum(data)) & 0xFF.
     */
    fun authCommand(password: String = DEFAULT_PASSWORD): ByteArray {
        val data = password.toByteArray(Charsets.US_ASCII)
        var sum = 0x15 + data.size
        for (b in data) sum += b.toInt() and 0xFF
        return byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x15, data.size.toByte()) +
            data + byteArrayOf((sum and 0xFF).toByte())
    }

    /** The positive ACK a BMS returns to a correct [authCommand]: FF AA 15 01 00 16. */
    val AUTH_ACK = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x15, 0x01, 0x00, 0x16)
}

/** Render bytes as lowercase hex, space-separated — used by the discovery/sniffer log. */
fun ByteArray.toHex(separator: String = " "): String =
    joinToString(separator) { "%02x".format(it.toInt() and 0xFF) }

/** Parse a hex string ("dda5..." or "dd a5 ..") into bytes; null if malformed. */
fun String.hexToBytesOrNull(): ByteArray? {
    val clean = filter { !it.isWhitespace() }
    if (clean.isEmpty() || clean.length % 2 != 0) return null
    return runCatching {
        ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }.getOrNull()
}
