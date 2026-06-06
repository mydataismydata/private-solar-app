package com.privatesolarmon.app.bms

/**
 * Reassembles JBD response frames from a BLE notification byte stream.
 *
 * Real JBD packs (especially 48V rack units) interleave unsolicited telemetry frames with
 * command replies, and a single logical reply can span several 20-byte notifications. This
 * parser therefore works as a resynchronizing stream scanner: it hunts for the `0xDD`
 * start-of-frame, validates command/status/tail/CRC, consumes exactly one frame at a time,
 * and silently steps over anything that isn't a valid `DD..77` frame.
 *
 * Pure JVM code — see [JbdParserTest] for verification against real captured frames.
 *
 * @param log optional sink for human-readable accept/reject messages (wired to the UI log).
 */
class JbdFrameAssembler(private val log: (String) -> Unit = {}) {
    /** A complete, CRC-validated frame: [command] is the echoed register, [data] is the payload. */
    data class Frame(val command: Int, val data: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Frame && command == other.command && data.contentEquals(other.data)
        override fun hashCode(): Int = 31 * command + data.contentHashCode()
    }

    private val buf = ArrayDeque<Byte>()
    private val maxBuffer = 1024 // runaway guard against a desynced stream

    /** Append one BLE notification chunk to the working buffer. */
    fun append(chunk: ByteArray) {
        for (b in chunk) buf.addLast(b)
        while (buf.size > maxBuffer) buf.removeFirst()
    }

    /**
     * Extract the next complete, CRC-valid [Frame], or null if none is ready yet.
     * Resynchronizes past junk/streamed bytes. Call repeatedly to drain multiple frames.
     */
    fun next(): Frame? {
        while (true) {
            // 1) align: drop bytes until the buffer starts with a response start-of-frame
            while (buf.isNotEmpty() && (buf[0].toInt() and 0xFF) != JbdProtocol.SOF_RESPONSE) {
                buf.removeFirst()
            }
            if (buf.size < 4) return null // need DD + cmd + status + len to size the frame

            val cmd = buf[1].toInt() and 0xFF
            val status = buf[2].toInt() and 0xFF
            val dataLen = buf[3].toInt() and 0xFF
            if (cmd !in VALID_CMDS || status != 0x00) {
                buf.removeFirst() // false header — skip this 0xDD and keep scanning
                continue
            }

            val total = 4 + dataLen + 3 // header(4) + data + crc(2) + tail(1)
            if (buf.size < total) return null // wait for the rest of the frame

            if ((buf[total - 1].toInt() and 0xFF) != JbdProtocol.TAIL) {
                log("frame resync: bad tail (cmd 0x%02x)".format(cmd))
                buf.removeFirst()
                continue
            }

            val frame = ByteArray(total) { buf[it] }
            repeat(total) { buf.removeFirst() } // consume this frame

            val crcStart = 4 + dataLen
            val got = ((frame[crcStart].toInt() and 0xFF) shl 8) or (frame[crcStart + 1].toInt() and 0xFF)
            val want = JbdProtocol.checksum(frame.copyOfRange(2, crcStart)) // status + len + data
            if (got != want) {
                log("frame rejected: CRC 0x%04x != 0x%04x (cmd 0x%02x)".format(got, want, cmd))
                continue
            }

            log("frame ok: cmd 0x%02x, %d data bytes".format(cmd, dataLen))
            return Frame(cmd, frame.copyOfRange(4, crcStart))
        }
    }

    /** Convenience: append then return the first ready frame (used by tests and simple callers). */
    fun offer(chunk: ByteArray): Frame? {
        append(chunk)
        return next()
    }

    /** Discard any buffered bytes (call on disconnect). */
    fun reset() = buf.clear()

    private companion object {
        val VALID_CMDS = setOf(0x03, 0x04, 0x05)
    }
}

/** Decoders for JBD register payloads. All offsets are relative to the start of the data section. */
object JbdParser {

    private fun u16(d: ByteArray, i: Int): Int = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)

    /**
     * Decode a 0x04 "cell voltages" payload (big-endian millivolts) into volts.
     *
     * (This battery does not answer the 0x03 "basic info" command — pack-level data comes
     * from the unsolicited broadcast instead, see [PackFrame].)
     */
    fun decodeCellVoltages(data: ByteArray): List<Double> =
        (0 until data.size / 2).map { i -> u16(data, 2 * i) / 1000.0 }
}
