package com.privatesolarmon.app.bms

/** Decoded snapshot of the SRNE/Eco-Worthy inverter. Nullable fields = register not (yet) read. */
data class InverterStatus(
    val batterySoc: Int? = null,
    val batteryVoltage: Double? = null,   // V
    val batteryCurrent: Double? = null,   // A (+ charge / - discharge), already sign-normalised at decode
    val batteryTemp: Double? = null,      // °C
    val pv1Voltage: Double? = null,       // V
    val pv1Current: Double? = null,       // A
    val pv2Voltage: Double? = null,       // V
    val pv2Current: Double? = null,       // A
    val gridVoltage: Double? = null,      // V (L1)
    val gridFrequency: Double? = null,    // Hz
    val outputVoltage: Double? = null,    // V (L1)
    val outputFrequency: Double? = null,  // Hz
    val loadPower: Int? = null,           // W (L1)
    val loadApparent: Int? = null,        // VA (L1)
    val loadCurrent: Double? = null,      // A (L1)
    val loadL2Power: Int? = null,         // W (L2, split phase)
    val loadL2Apparent: Int? = null,      // VA (L2, split phase)
    val loadL2Current: Double? = null,    // A (L2, split phase)
    val gridL2Voltage: Double? = null,    // V (split phase)
    val outputL2Voltage: Double? = null,  // V (split phase)
    val dcTemp: Double? = null,           // °C
    val acTemp: Double? = null,           // °C
    val faultCodes: List<Int> = emptyList(), // active fault code(s) from 0x0204..0x0207 (0 = none)
    val machineState: Int? = null,        // working state (0x0210)
) {
    val hasFault: Boolean get() = faultCodes.isNotEmpty()
    val batteryPower: Double? get() =
        if (batteryVoltage != null && batteryCurrent != null) batteryVoltage * batteryCurrent else null

    /** Total real load power (W) across both split-phase legs. Falls back to whichever leg
     *  is present; this is a 240 V split-phase inverter, so the meaningful load is L1 + L2. */
    val loadTotal: Int? get() = when {
        loadPower != null && loadL2Power != null -> loadPower + loadL2Power
        else -> loadPower ?: loadL2Power
    }

    /** Total apparent load power (VA) across both legs. */
    val loadApparentTotal: Int? get() = when {
        loadApparent != null && loadL2Apparent != null -> loadApparent + loadL2Apparent
        else -> loadApparent ?: loadL2Apparent
    }

    /** Per-string DC power (W), derived from each string's V x I. */
    val pv1Power: Double? get() =
        if (pv1Voltage != null && pv1Current != null) pv1Voltage * pv1Current else null
    val pv2Power: Double? get() =
        if (pv2Voltage != null && pv2Current != null) pv2Voltage * pv2Current else null

    /** Total PV power (W) across both MPPT strings. Derived from V x I, which the inverter
     *  reports reliably, whereas the dedicated total-power register reads 0 on this unit. */
    val pvPower: Double? get() {
        val a = pv1Power
        val b = pv2Power
        return if (a != null && b != null) a + b else a ?: b
    }

    val hasData: Boolean get() =
        batterySoc != null || batteryVoltage != null || pvPower != null || loadTotal != null
}

/**
 * SRNE hybrid-inverter holding-register map (read function 0x03).
 * Source: danzelziggy/srne-solarman (srne_hesp.yaml) / SRNE Modbus V2.07.
 */
object SrneInverter {
    /** Contiguous register blocks to read as (startRegister, count). Read independently;
     *  a block the firmware rejects is simply skipped. PV2 is its own tight block because
     *  the firmware NAKs any read that runs past 0x0111. */
    val BLOCKS: List<Pair<Int, Int>> = listOf(
        0x0100 to 0x0F,   // battery + PV1
        0x010F to 0x03,   // PV2 voltage / current / power
        0x0200 to 0x11,   // fault bits (0x0200-03) + fault codes (0x0204-07) + machine state (0x0210)
        0x0212 to 0x1B,   // grid / output / load L1 / temps
        0x0230 to 0x05,   // load L2 current / power / apparent (split phase)
    )

    private fun u(m: Map<Int, Int>, addr: Int, scale: Double): Double? = m[addr]?.let { it * scale }
    private fun s(m: Map<Int, Int>, addr: Int, scale: Double): Double? =
        m[addr]?.let { (if (it and 0x8000 != 0) it - 0x10000 else it) * scale }

    fun decode(m: Map<Int, Int>): InverterStatus = InverterStatus(
        batterySoc = m[0x0100],
        batteryVoltage = u(m, 0x0101, 0.1),
        // This unit reports battery current as +discharge / -charge (opposite of the app's
        // "+ charge / - discharge" convention), so negate to normalise. Verified against live
        // telemetry: with load > PV the battery is discharging yet 0x0102 reads positive.
        batteryCurrent = s(m, 0x0102, -0.1),
        batteryTemp = s(m, 0x0103, 0.1),
        pv1Voltage = u(m, 0x0107, 0.1),
        pv1Current = u(m, 0x0108, 0.1),
        pv2Voltage = u(m, 0x010F, 0.1),
        pv2Current = u(m, 0x0110, 0.1),
        gridVoltage = u(m, 0x0213, 0.1),
        gridFrequency = u(m, 0x0215, 0.01),
        outputVoltage = u(m, 0x0216, 0.1),
        outputFrequency = u(m, 0x0218, 0.01),
        loadPower = m[0x021B],
        loadApparent = m[0x021C],
        loadCurrent = u(m, 0x0219, 0.1),
        loadL2Current = u(m, 0x0230, 0.1),
        loadL2Power = m[0x0232],
        loadL2Apparent = m[0x0234],
        gridL2Voltage = u(m, 0x022A, 0.1),
        outputL2Voltage = u(m, 0x022C, 0.1),
        dcTemp = u(m, 0x0220, 0.1),
        acTemp = u(m, 0x0221, 0.1),
        faultCodes = listOf(0x0204, 0x0205, 0x0206, 0x0207).mapNotNull { a -> m[a]?.takeIf { it != 0 } },
        machineState = m[0x0210],
    )

    /**
     * Liveness gate for a poll. The battery block is always present on this off-grid unit, so a
     * read whose pack voltage (0x0101) is absent or zero is a partial/bogus reply (a dropped
     * block from the Wi-Fi dongle) and must NOT be allowed to overwrite good data.
     */
    fun hasCore(raw: Map<Int, Int>): Boolean = (raw[0x0101] ?: 0) > 0

    /**
     * Overlay [fresh] registers onto [previous], carrying forward any register a partial read
     * missed. Registers actually returned this cycle (including legitimate zeros, e.g. PV at
     * night) win; only blocks that didn't respond fall back to the last-good value.
     */
    fun merge(previous: Map<Int, Int>?, fresh: Map<Int, Int>): Map<Int, Int> =
        LinkedHashMap<Int, Int>().apply {
            previous?.let { putAll(it) }
            putAll(fresh)
        }
}
