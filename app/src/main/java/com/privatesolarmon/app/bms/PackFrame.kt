package com.privatesolarmon.app.bms

/** Pack-level telemetry decoded from one ECO-LFP48100 broadcast frame. */
data class PackTelemetry(
    val voltage: Double,        // V
    val current: Double,        // A: + charging / - discharging
    val soc: Int,               // %
    val soh: Int,               // %
    val cellMax: Double,        // V
    val cellMin: Double,        // V
    val temps: List<Double>,    // °C per NTC sensor
    val remainingAh: Double,    // Ah (own pack on master; whole-bank total on the aggregating pack)
    val fullAh: Double,         // Ah (own pack on master; whole-bank total on the aggregating pack)
    val parallelNum: Int,       // position in the parallel group: 1 = master, 2+ = slave
    val protectionNormal: Boolean = true,
)

/**
 * The ECO-LFP48100 (JBD UP16S015) emits an unsolicited 64-byte pack-telemetry broadcast,
 * reverse-engineered against the official app on 2026-06-01 (see tools/verify_broadcast.py).
 *
 * Layout (big-endian fields). Two variants exist: the legacy 64-byte frame and a newer 56-byte
 * frame (2026 firmware). Core offsets [0..31] are shared; only the tail/length differs.
 * ```
 * [0]     pack position bitmask: 0x01=#1, 0x02=#2, 0x04=#3, 0x08=#4 … (#1 is the master)
 * [1]     0x51 type marker     [2:3] 00 00     [4:5] FF FF     [6:7] const
 * [8:9]   voltage   /100 V        [10:11] current  signed /10 A
 * [12:13] SOC %                   [14:15] SOH %
 * [16:17] temp[0]  (raw-2731)/10  [18:19] cell max /1000 V   [20:21] cell min /1000 V
 * [22:23] temp[1]                 [24:25] temp[2]            [26:27] temp count
 * [28:29] remaining /10 Ah        [30:31] full /10 Ah
 * [32:33] pack COUNT as seen by the aggregating master (NOT this pack's position)
 * CRC-16/Modbus over [0..len-3], little-endian, in the last two bytes.
 * ```
 */
object PackFrame {
    const val LENGTH = 64        // legacy full broadcast
    const val SHORT_LENGTH = 56  // newer (2026) firmware variant — same header/core, shorter tail
    /** Candidate frame lengths, shortest first (so a 56-byte frame isn't mistaken for a 64-byte one). */
    val LENGTHS = intArrayOf(SHORT_LENGTH, LENGTH)
    private const val MIN_PARSE = 32 // bytes needed up to and incl. the full-capacity field
    private const val TYPE = 0x51

    private fun u16(d: ByteArray, i: Int): Int = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)
    private fun s16(d: ByteArray, i: Int): Int = u16(d, i).let { if (it and 0x8000 != 0) it - 0x10000 else it }

    /** True if a frame header signature begins at index [i] of [d]. */
    fun headerAt(d: ByteArray, i: Int): Boolean =
        i + 6 <= d.size &&
            (d[i + 1].toInt() and 0xFF) == TYPE &&
            d[i + 2].toInt() == 0 && d[i + 3].toInt() == 0 &&
            (d[i + 4].toInt() and 0xFF) == 0xFF && (d[i + 5].toInt() and 0xFF) == 0xFF

    /** CRC-16/Modbus over [0,end), low byte first. */
    fun crc16Modbus(d: ByteArray, end: Int): Int {
        var crc = 0xFFFF
        for (i in 0 until end) {
            crc = crc xor (d[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc
    }

    /** CRC over [0, len-2), little-endian tail at [len-2 .. len-1]. */
    fun crcValidAt(frame: ByteArray, len: Int): Boolean {
        if (len < 4 || frame.size < len) return false
        val got = (frame[len - 2].toInt() and 0xFF) or ((frame[len - 1].toInt() and 0xFF) shl 8)
        return got == crc16Modbus(frame, len - 2)
    }

    /** Validate using the frame's own length. */
    fun crcValid(frame: ByteArray): Boolean = crcValidAt(frame, frame.size)

    fun parse(d: ByteArray): PackTelemetry {
        require(d.size >= MIN_PARSE) { "pack frame too short: ${d.size}" }
        val temps = listOf(16, 22, 24).map { (u16(d, it) - 2731) / 10.0 }
        // Pack position is a single-bit mask in byte [0]: 0x01=#1, 0x02=#2, 0x04=#3, 0x08=#4 …
        // (same for both frame variants). [32:33] is the pack count the master reports, not this
        // pack's position — using it labelled every non-master pack "#1".
        val posBit = d[0].toInt() and 0xFF
        val parallel = if (posBit == 0) 0 else Integer.numberOfTrailingZeros(posBit) + 1
        return PackTelemetry(
            voltage = u16(d, 8) / 100.0,
            current = s16(d, 10) / 10.0,
            soc = u16(d, 12),
            soh = u16(d, 14),
            cellMax = u16(d, 18) / 1000.0,
            cellMin = u16(d, 20) / 1000.0,
            temps = temps,
            remainingAh = u16(d, 28) / 10.0,
            fullAh = u16(d, 30) / 10.0,
            parallelNum = parallel,
        )
    }
}

/**
 * Extracts pack broadcasts (64-byte legacy or 56-byte newer firmware) from the BLE notification
 * stream. The broadcast has no length/tail markers, so we scan for the header signature, then
 * accept the first candidate length in [PackFrame.LENGTHS] whose trailing CRC validates (false
 * signatures and wrong lengths self-reject).
 */
class PackBroadcastAssembler(private val log: (String) -> Unit = {}) {
    private val buf = ArrayDeque<Byte>()
    private val maxBuffer = 256

    fun append(chunk: ByteArray) {
        for (b in chunk) buf.addLast(b)
        while (buf.size > maxBuffer) buf.removeFirst()
    }

    fun next(): ByteArray? {
        while (true) {
            val snapshot = ByteArray(buf.size) { buf[it] }
            var idx = -1
            var i = 0
            while (i + 6 <= snapshot.size) {
                if (PackFrame.headerAt(snapshot, i)) { idx = i; break }
                i++
            }
            if (idx < 0) return null // no header yet; keep buffering
            repeat(idx) { buf.removeFirst() } // drop bytes before the header

            // Try each known frame length (shortest first); accept the one whose CRC validates.
            for (len in PackFrame.LENGTHS) {
                if (buf.size < len) continue
                val frame = ByteArray(len) { buf[it] }
                if (PackFrame.crcValidAt(frame, len)) {
                    repeat(len) { buf.removeFirst() }
                    log("pack broadcast ok (crc valid, ${len}B)")
                    return frame
                }
            }
            // Wait until we have enough bytes to have tested the largest candidate before giving up.
            if (buf.size < PackFrame.LENGTH) return null
            buf.removeFirst() // false header — step past and rescan
            log("pack broadcast crc fail, resync")
        }
    }

    fun reset() = buf.clear()
}
