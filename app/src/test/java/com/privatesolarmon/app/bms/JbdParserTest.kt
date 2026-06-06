package com.privatesolarmon.app.bms

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JBD 0x04 cell-voltage path, verified against a REAL 16-cell frame captured from the
 * ECO-LFP48100 (device …56:72) and the resync behaviour needed to ignore the battery's
 * unsolicited pack broadcasts.
 */
class JbdParserTest {

    // Real 0x04 reply from the device: 16 cells.
    private val cell16Hex =
        "dd0400200d430d450d410d3f0d3c0d3b0d400d410d420d430d400d3d0d3c0d3d0d400d42fb1377"
    private val expected16 = listOf(
        3.395, 3.397, 3.393, 3.391, 3.388, 3.387, 3.392, 3.393,
        3.394, 3.395, 3.392, 3.389, 3.388, 3.389, 3.392, 3.394,
    )
    // A real pack broadcast (NOT a dd..77 frame) — the JBD assembler must ignore it.
    private val broadcastHex =
        "02510000ffff003614fe009c00530064" +
            "0be30d230d1b0be40bde0003033e03e8" +
            "000100020000000000000302030f0303" +
            "0302000000005aa6000000000000fb62"

    private fun bytes(hex: String) = requireNotNull(hex.hexToBytesOrNull()) { "bad hex: $hex" }

    private fun assembleInChunks(frame: ByteArray, chunk: Int): JbdFrameAssembler.Frame? {
        val asm = JbdFrameAssembler()
        var result: JbdFrameAssembler.Frame? = null
        var i = 0
        while (i < frame.size) {
            val end = minOf(i + chunk, frame.size)
            asm.offer(frame.copyOfRange(i, end))?.let { result = it }
            i = end
        }
        return result
    }

    @Test
    fun readCommandsMatchSpec() {
        assertArrayEquals(bytes("dda50300fffd77"), JbdProtocol.readCommand(0x03))
        assertArrayEquals(bytes("dda50400fffc77"), JbdProtocol.readCommand(0x04))
        assertArrayEquals(bytes("dda50500fffb77"), JbdProtocol.readCommand(0x05))
    }

    @Test
    fun decodesRealCellFrame() {
        val frame = JbdFrameAssembler().offer(bytes(cell16Hex))
        assertNotNull(frame)
        assertEquals(0x04, frame!!.command)
        val cells = JbdParser.decodeCellVoltages(frame.data)
        assertEquals(16, cells.size)
        cells.forEachIndexed { i, v -> assertEquals(expected16[i], v, 1e-6) }
    }

    @Test
    fun reassemblesAcrossManyTinyPackets() {
        val frame = assembleInChunks(bytes(cell16Hex), 1) // 1-byte packets stress the path hardest
        assertNotNull(frame)
        assertEquals(16, JbdParser.decodeCellVoltages(frame!!.data).size)
    }

    @Test
    fun rejectsCorruptedCrc() {
        val bad = bytes(cell16Hex)
        bad[5] = (bad[5] + 1).toByte() // flip a data byte without fixing the CRC
        assertNull(assembleInChunks(bad, 20))
    }

    @Test
    fun ignoresJunkBeforeHeader() {
        val asm = JbdFrameAssembler()
        assertNull(asm.offer(bytes("00112233"))) // no 0xDD start
        assertNotNull(asm.offer(bytes(cell16Hex))) // clean frame still parses
    }

    @Test
    fun ignoresPackBroadcast() {
        // The pack broadcast has no 0xDD framing — the JBD assembler must yield nothing.
        assertNull(JbdFrameAssembler().offer(bytes(broadcastHex)))
    }
}
