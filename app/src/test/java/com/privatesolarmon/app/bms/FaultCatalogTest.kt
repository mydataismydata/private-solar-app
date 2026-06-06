package com.privatesolarmon.app.bms

import org.junit.Assert.assertEquals
import org.junit.Test

class FaultCatalogTest {

    @Test
    fun parsesBrandTable() {
        val json = """
            {
              "id": "acme",
              "name": "ACME Inverters",
              "match": ["ACME", "AC-"],
              "codes": { "1": "Battery low", "8": "Bus overvoltage" }
            }
        """.trimIndent()
        val b = FaultCatalog.parse(json)
        assertEquals("acme", b.id)
        assertEquals("ACME Inverters", b.name)
        assertEquals(listOf("ACME", "AC-"), b.match)
        assertEquals("Battery low", b.text(1))
        assertEquals("Bus overvoltage", b.text(8))
        assertEquals("Unknown fault", b.text(2)) // code absent from this table
    }

    @Test
    fun nameDefaultsToIdAndMatchIsOptional() {
        val b = FaultCatalog.parse("""{ "id": "x", "codes": { "1": "a" } }""")
        assertEquals("x", b.name)
        assertEquals(emptyList<String>(), b.match)
        assertEquals("a", b.text(1))
    }
}
