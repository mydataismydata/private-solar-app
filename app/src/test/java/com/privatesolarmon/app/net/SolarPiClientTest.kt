package com.privatesolarmon.app.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the Pi JSON → app-model mapping (the part that runs without a socket). */
class SolarPiClientTest {

    @Test
    fun mapsBatteryPacksAndBank() {
        val json = """
            {
              "available": true,
              "ts": 1717591234,
              "bank": {
                "packs": 4, "voltage": 48.25, "current": 2.5, "power": 120.6,
                "soc": 75.3, "nominal_ah": 100.0, "residual_ah": 75.3,
                "capacity_kwh": 4.8, "cell_min": 3.201, "cell_max": 3.218,
                "cell_delta": 0.017, "temp_min": 22.5, "temp_max": 28.3, "fault_packs": []
              },
              "packs": [
                {
                  "name": "065672", "address": "aa:c2:37:06:56:72",
                  "voltage": 48.23, "current": 0.62, "power": 29.9, "soc": 75,
                  "residual_ah": 18.75, "nominal_ah": 25.0, "cycles": 145,
                  "cells": [3.215, 3.208, 3.214], "cell_min": 3.208, "cell_max": 3.215,
                  "cell_delta": 0.007, "temps": [25.2, 26.1], "temp_min": 25.2,
                  "temp_max": 26.1, "has_fault": false
                }
              ]
            }
        """.trimIndent()

        val snap = mapBattery(JSONObject(json))
        assertTrue(snap.available)
        assertEquals(1717591234L, snap.ts)

        // MAC is upper-cased to match the BLE keying.
        val pack = snap.samples["AA:C2:37:06:56:72"]!!
        assertEquals(48.23, pack.voltage!!, 1e-6)
        assertEquals(0.62, pack.current!!, 1e-6)
        assertEquals(75, pack.soc)
        assertNull(pack.soh) // Pi has no SOH
        assertEquals(18.75, pack.remainingAh!!, 1e-6)
        assertEquals(25.0, pack.fullAh!!, 1e-6)
        assertEquals(145, pack.cycles)
        assertEquals(listOf(3.215, 3.208, 3.214), pack.cellVoltages)
        assertEquals(listOf(25.2, 26.1), pack.tempsC)
        assertTrue(pack.protectionNormal)

        val bank = snap.bank!!
        assertEquals(4, bank.packs)
        assertEquals(75, bank.soc) // 75.3 rounded
        assertEquals(48.25, bank.voltage!!, 1e-6)
        assertEquals(2.5, bank.current!!, 1e-6)
        assertEquals(75.3, bank.remainingAh!!, 1e-6)
        assertEquals(100.0, bank.fullAh!!, 1e-6)
    }

    @Test
    fun unavailableBatteryYieldsEmpty() {
        val snap = mapBattery(JSONObject("""{ "available": false }"""))
        assertFalse(snap.available)
        assertTrue(snap.samples.isEmpty())
        assertNull(snap.bank)
    }

    @Test
    fun mapsInverterAndFaultCodes() {
        val json = """
            {
              "available": true, "ts": 1717591234,
              "battery_soc": 75, "battery_voltage": 51.2, "battery_current": 2.5,
              "battery_temp": 25.3, "pv1_voltage": 380.5, "pv1_current": 3.2,
              "pv2_voltage": 0, "pv2_current": 0,
              "load_power": 850, "load_apparent": 900, "load_current": 3.74,
              "load_l2_power": null, "load_l2_current": null,
              "grid_voltage": 230.1, "grid_frequency": 50.02,
              "output_voltage": 229.8, "output_frequency": 50.01,
              "dc_temp": 28.5, "ac_temp": 32.1, "machine_state": 0,
              "faults": [ { "code": 101, "text": "Over-discharge" }, { "code": 7, "text": "Foo" } ]
            }
        """.trimIndent()

        val reading = mapInverter(JSONObject(json))
        assertTrue(reading.raw.isEmpty())
        val s = reading.status
        assertEquals(75, s.batterySoc)
        assertEquals(51.2, s.batteryVoltage!!, 1e-6)
        assertEquals(2.5, s.batteryCurrent!!, 1e-6) // same sign convention, mapped straight through
        assertEquals(850, s.loadPower)
        assertNull(s.loadL2Power) // explicit JSON null → absent
        assertEquals(listOf(101, 7), s.faultCodes)
        // derived values still compute app-side
        assertEquals(380.5 * 3.2, s.pvPower!!, 1e-3)
    }
}
