package com.privatesolarmon.app.bms

/**
 * Unified, live view of one battery for the UI.
 *
 * The ECO-LFP48100 (JBD UP16S015) does NOT answer the JBD 0x03 "basic info" command;
 * instead it emits an unsolicited 64-byte pack-telemetry broadcast (see [PackFrame]).
 * So pack-level fields here come from that broadcast, while per-cell voltages come from
 * the JBD 0x04 read. Fields are nullable because the two sources arrive independently —
 * the UI shows "—" until each is populated.
 */
data class BmsSample(
    val voltage: Double? = null,        // pack voltage, V (broadcast)
    val current: Double? = null,        // A: + charging / - discharging (broadcast)
    val soc: Int? = null,               // state of charge, % (broadcast)
    val soh: Int? = null,               // state of health, % (broadcast)
    val remainingAh: Double? = null,    // residual capacity, Ah (broadcast)
    val fullAh: Double? = null,         // full/learned capacity, Ah (broadcast)
    val cycles: Int? = null,            // "Cycle Index" — offset not yet located in this BMS
    val parallelNum: Int? = null,       // 1 = master, 2+ = slave (in a parallel bank)
    val protectionNormal: Boolean = true,
    val cellVoltages: List<Double> = emptyList(), // per cell, V (JBD 0x04)
    val tempsC: List<Double> = emptyList(),       // per NTC sensor, °C (broadcast)
    val broadcastCellMax: Double? = null,         // cell max from broadcast (fallback)
    val broadcastCellMin: Double? = null,         // cell min from broadcast (fallback)
) {
    val power: Double? get() = if (voltage != null && current != null) voltage * current else null

    /** Prefer the precise per-cell list; fall back to the broadcast's max/min. */
    val cellMax: Double? get() = cellVoltages.maxOrNull() ?: broadcastCellMax
    val cellMin: Double? get() = cellVoltages.minOrNull() ?: broadcastCellMin
    val cellDelta: Double? get() {
        val mx = cellMax ?: return null
        val mn = cellMin ?: return null
        return mx - mn
    }

    val tempMax: Double? get() = tempsC.maxOrNull()
    val tempMin: Double? get() = tempsC.minOrNull()

    /** True once we have anything worth showing. */
    val hasData: Boolean get() = voltage != null || cellVoltages.isNotEmpty()
}
