package com.privatesolarmon.app.net

import com.privatesolarmon.app.bms.InverterStatus
import com.privatesolarmon.app.bms.ModbusRtu
import com.privatesolarmon.app.bms.SolarmanV5
import com.privatesolarmon.app.bms.SrneInverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** Decoded inverter status plus the raw registers (handy for verifying the decode). */
data class InverterReading(val status: InverterStatus, val raw: Map<Int, Int>)

/**
 * Reads the SRNE inverter through its Solarman WiFi dongle over local TCP (port 8899).
 * One [read] opens a socket, reads the register blocks, and returns decoded + raw values.
 */
class InverterClient(
    private val ip: String,
    private val serial: Long,
    private val port: Int = 8899,
) {
    private var seq = 0
    private fun nextSeq(): Int { seq = (seq + 1) and 0xFF; return seq }

    suspend fun read(): Result<InverterReading> = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
                sock.soTimeout = READ_TIMEOUT_MS
                val out = sock.getOutputStream()
                val inp = sock.getInputStream()
                // SRNE Modbus address is usually 1; some firmware uses the universal 0xFF.
                for (slave in intArrayOf(1, 0xFF)) {
                    val raw = LinkedHashMap<Int, Int>()
                    for ((start, count) in SrneInverter.BLOCKS) {
                        readBlock(out, inp, start, count, slave)?.let { regs ->
                            for (i in regs.indices) raw[start + i] = regs[i]
                        }
                    }
                    if (raw.isNotEmpty()) return@use InverterReading(SrneInverter.decode(raw), raw)
                }
                error("connected to the dongle, but got no Modbus reply (port 8899 may be locked, or the serial is wrong)")
            }
        }
    }

    private fun readBlock(out: OutputStream, inp: InputStream, start: Int, count: Int, slave: Int): IntArray? {
        val request = SolarmanV5.encode(serial, nextSeq(), ModbusRtu.readHoldingRegisters(slave, start, count))
        out.write(request)
        out.flush()
        val response = readV5Frame(inp) ?: return null
        val modbus = SolarmanV5.decode(response) ?: return null
        return ModbusRtu.parseHoldingResponse(modbus, slave)
    }

    /** Read exactly one V5 frame: header tells us the length (total = 13 + payloadLen). */
    private fun readV5Frame(inp: InputStream): ByteArray? {
        val head = readFully(inp, 3) ?: return null // start + 2-byte length
        if ((head[0].toInt() and 0xFF) != 0xA5) return null
        val payloadLen = (head[1].toInt() and 0xFF) or ((head[2].toInt() and 0xFF) shl 8)
        val rest = readFully(inp, 13 + payloadLen - 3) ?: return null
        return head + rest
    }

    private fun readFully(inp: InputStream, n: Int): ByteArray? {
        if (n <= 0) return null
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = try { inp.read(buf, off, n - off) } catch (_: Exception) { return null }
            if (r < 0) return null
            off += r
        }
        return buf
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 5000
    }
}
