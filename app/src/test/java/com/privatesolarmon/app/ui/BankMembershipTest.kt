package com.privatesolarmon.app.ui

import com.privatesolarmon.app.bms.BmsSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BankMembershipTest {

    private fun pack(parallel: Int?, fullAh: Double?) =
        BmsSample(voltage = 52.0, parallelNum = parallel, fullAh = fullAh)

    @Test
    fun groupsMasterWithSlaves() {
        val m = mapOf(
            "AA" to pack(1, 300.0), // master / aggregator
            "BB" to pack(2, 100.0), // slave
            "CC" to pack(3, 100.0), // slave
        )
        assertEquals(setOf("AA", "BB", "CC"), BankSummary.memberMacs(m))
    }

    @Test
    fun excludesStandaloneFromBank() {
        val m = mapOf(
            "AA" to pack(1, 300.0), // bank master (large, whole-bank capacity)
            "BB" to pack(2, 100.0), // slave
            "ZZ" to pack(1, 100.0), // separate standalone: also #1, but small capacity
        )
        val members = BankSummary.memberMacs(m)
        assertEquals(setOf("AA", "BB"), members)
        assertFalse("ZZ" in members)
    }

    @Test
    fun noBankForLoneStandalone() {
        assertTrue(BankSummary.memberMacs(mapOf("AA" to pack(1, 100.0))).isEmpty())
    }

    @Test
    fun looksLikeBatteryIsFuzzy() {
        assertTrue(looksLikeBattery("ECO-LFP48100-3U"))
        assertTrue(looksLikeBattery("jbd-bms-1234"))
        assertFalse(looksLikeBattery("Galaxy Buds"))
        assertFalse(looksLikeBattery(null))
    }
}
