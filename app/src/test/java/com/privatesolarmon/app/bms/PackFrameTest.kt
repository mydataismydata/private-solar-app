package com.privatesolarmon.app.bms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pack-broadcast decode, verified against two REAL frames from device …56:72 and the
 * official app's on-screen ground truth (temps, capacity↔SOC). See tools/verify_broadcast.py.
 */
class PackFrameTest {

    // device …56:72, charging (earlier)
    private val frameE =
        "02510000ffff0036152d009400500064" +
            "0be30d400d390be70be20003032903e8" +
            "000100020000000000000307030b0301" +
            "0302000000005aa600000000000000a1"

    // device …56:72, charging (sun came out)
    private val frameC =
        "02510000ffff003614fe009c00530064" +
            "0be30d230d1b0be40bde0003033e03e8" +
            "000100020000000000000302030f0303" +
            "0302000000005aa6000000000000fb62"

    // device …57:4C — the SLAVE (#2). Reports whole-bank totals: 200 Ah full, 167.4 Ah remaining.
    private val frameN =
        "01510000ffff003614e1ff710053 0064".replace(" ", "") +
            "0be50d120d090be80be20003068a07d0" +
            "000200030000000000000210020c0103" +
            "0202000000005aa60000000000006ca4"

    // device …25:3D — 56-byte (2026 firmware) variant, master (#1), reports 4-pack bank totals.
    private val frameS =
        "01510000ffff002e14ae00a600230064" +
            "0b9d0cf40cdb0b950b920003059a0fa0" +
            "0004008b000100000000020602020201" +
            "0401000000008a37"

    private fun bytes(hex: String) = requireNotNull(hex.hexToBytesOrNull()) { "bad hex: $hex" }

    @Test
    fun decodesShortFrame() {
        val p = PackFrame.parse(bytes(frameS))
        assertEquals(52.94, p.voltage, 1e-6)
        assertEquals(16.6, p.current, 1e-6)
        assertEquals(35, p.soc)
        assertEquals(100, p.soh)
        assertEquals(1, p.parallelNum) // byte[0]=0x01 -> pos 1
        assertEquals(400.0, p.fullAh, 1e-6) // whole-bank total -> aggregating master
    }

    // device …25:44 — second new pack, 56-byte. byte[0]=0x02 -> position #2, own 100 Ah (not master).
    private val frameS2 =
        "02510000ffff002e14cc004a00210064" +
            "0bab0d030cf20b9f0b9b0003014e03e8" +
            "00010002000000000000030603020301" +
            "030200000000f685"

    @Test
    fun crcValidatesShortFrame() {
        assertTrue(PackFrame.crcValid(bytes(frameS)))
    }

    @Test
    fun distinctPositionsFromByteZeroBitmask() {
        // The bug: every non-master pack reported "#1". Position must come from byte[0]'s bit.
        assertTrue(PackFrame.crcValid(bytes(frameS2)))
        assertEquals(1, PackFrame.parse(bytes(frameS)).parallelNum)  // 0x01 -> #1 (master, 400 Ah)
        assertEquals(2, PackFrame.parse(bytes(frameS2)).parallelNum) // 0x02 -> #2
        assertEquals(100.0, PackFrame.parse(bytes(frameS2)).fullAh, 1e-6) // own capacity, not bank
        assertEquals(33, PackFrame.parse(bytes(frameS2)).soc)
    }

    @Test
    fun assemblerExtractsShortFrameAmongChunks() {
        val asm = PackBroadcastAssembler()
        asm.append(bytes("dd0400")) // leading junk
        val frame = bytes(frameS)
        var out: ByteArray? = null
        var i = 0
        while (i < frame.size) {
            val end = minOf(i + 20, frame.size)
            asm.append(frame.copyOfRange(i, end))
            asm.next()?.let { out = it }
            i = end
        }
        assertNotNull(out)
        assertEquals(56, out!!.size)
        assertEquals(1, PackFrame.parse(out!!).parallelNum)
    }

    @Test
    fun decodesFrameE() {
        val p = PackFrame.parse(bytes(frameE))
        assertEquals(54.21, p.voltage, 1e-6)
        assertEquals(14.8, p.current, 1e-6)   // charging
        assertEquals(80, p.soc)
        assertEquals(100, p.soh)
        assertEquals(3.392, p.cellMax, 1e-6)
        assertEquals(3.385, p.cellMin, 1e-6)
        assertEquals(80.9, p.remainingAh, 1e-6)
        assertEquals(100.0, p.fullAh, 1e-6)
        assertEquals(3, p.temps.size)
        assertEquals(31.2, p.temps[0], 1e-9)
        assertEquals(31.6, p.temps[1], 1e-9)
        assertEquals(31.1, p.temps[2], 1e-9)
        assertEquals(2, p.parallelNum) // byte[0]=0x02 -> pos 2
    }

    @Test
    fun decodesMasterFrameAsBankAggregate() {
        val p = PackFrame.parse(bytes(frameN))
        assertEquals(1, p.parallelNum)        // master (byte[0]=0x01 -> pos 1), aggregates the bank
        assertEquals(200.0, p.fullAh, 1e-6)   // whole-bank capacity (2 x 100 Ah)
        assertEquals(167.4, p.remainingAh, 1e-6)
        assertEquals(-14.3, p.current, 1e-6)  // discharging
        assertTrue(PackFrame.crcValid(bytes(frameN)))
    }

    @Test
    fun decodesFrameC() {
        val p = PackFrame.parse(bytes(frameC))
        assertEquals(53.74, p.voltage, 1e-6)
        assertEquals(15.6, p.current, 1e-6)
        assertEquals(83, p.soc)
        assertEquals(3.363, p.cellMax, 1e-6)
        assertEquals(3.355, p.cellMin, 1e-6)
        assertEquals(83.0, p.remainingAh, 1e-6)
        // matches the official app exactly: temp max 31.3, min 30.7
        assertEquals(31.3, p.temps.max(), 1e-6)
        assertEquals(30.7, p.temps.min(), 1e-6)
        // remaining/full * 100 tracks SOC
        assertEquals(p.soc.toDouble(), p.remainingAh / p.fullAh * 100, 0.6)
        assertEquals(2, p.parallelNum) // byte[0]=0x02 -> pos 2
    }

    @Test
    fun crcValidatesRealFrames() {
        assertTrue(PackFrame.crcValid(bytes(frameE)))
        assertTrue(PackFrame.crcValid(bytes(frameC)))
    }

    @Test
    fun crcRejectsCorruptedFrame() {
        val bad = bytes(frameC)
        bad[8] = (bad[8] + 1).toByte() // change the voltage byte, leave CRC
        assertFalse(PackFrame.crcValid(bad))
    }

    @Test
    fun assemblerExtractsBroadcastFromChunkedStreamWithJunk() {
        val asm = PackBroadcastAssembler()
        // leading junk that is not a broadcast header
        asm.append(bytes("dd0400"))
        val frame = bytes(frameC)
        var out: ByteArray? = null
        var i = 0
        while (i < frame.size) { // feed in 20-byte BLE-sized chunks
            val end = minOf(i + 20, frame.size)
            asm.append(frame.copyOfRange(i, end))
            asm.next()?.let { out = it }
            i = end
        }
        assertNotNull(out)
        assertEquals(53.74, PackFrame.parse(out!!).voltage, 1e-6)
    }

    @Test
    fun assemblerRejectsBadCrcFrame() {
        val bad = bytes(frameC)
        bad[10] = (bad[10] + 5).toByte() // corrupt current; CRC no longer matches
        val asm = PackBroadcastAssembler()
        asm.append(bad)
        assertNull(asm.next())
    }
}
