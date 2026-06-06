package com.privatesolarmon.app.net

import com.privatesolarmon.app.bms.BmsSample
import com.privatesolarmon.app.bms.InverterStatus
import com.privatesolarmon.app.ui.BankSummary
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A battery snapshot pulled from the Solar Pi's `/api/battery`, mapped onto the app's models. */
data class PiBatterySnapshot(
    val available: Boolean,
    val ts: Long?,
    val samples: Map<String, BmsSample>, // keyed by UPPERCASE MAC, matches the BLE path
    val bank: BankSummary?,
)

/**
 * Reads battery + inverter telemetry from a Solar Pi (the Raspberry Pi FastAPI dashboard) over
 * local-network HTTP. Used instead of direct BLE/dongle access when a Pi is present, so the app
 * doesn't fight the Pi for the battery's single BLE connection slot.
 *
 * The JSON→domain mapping lives in pure top-level functions ([mapBattery], [mapInverter]) so it
 * unit-tests without a socket. Field names and units mirror the Pi's `solardash/api.py` payloads;
 * both sides already use the app's "+ charge / − discharge" current convention, so values map
 * straight through.
 */
class SolarPiClient(
    private val host: String,
    private val port: Int = 8000,
) {
    private val base = "http://$host:$port"

    suspend fun health(): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching { getJson("/api/health"); true }
    }

    suspend fun battery(): Result<PiBatterySnapshot> = withContext(Dispatchers.IO) {
        runCatching { mapBattery(getJson("/api/battery")) }
    }

    suspend fun current(): Result<InverterReading> = withContext(Dispatchers.IO) {
        runCatching { mapInverter(getJson("/api/current")) }
    }

    private fun getJson(path: String): JSONObject {
        val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) error("HTTP $code from $path")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TIMEOUT_MS = 4000
    }
}

// ---- pure mappers (no Android deps — unit-testable) ----------------------------------------

/** Map the Pi's `/api/battery` payload to per-pack [BmsSample]s plus a [BankSummary]. */
fun mapBattery(o: JSONObject): PiBatterySnapshot {
    val available = o.optBoolean("available", false)
    if (!available) return PiBatterySnapshot(false, null, emptyMap(), null)

    val samples = LinkedHashMap<String, BmsSample>()
    val packs = o.optJSONArray("packs")
    if (packs != null) {
        for (i in 0 until packs.length()) {
            val p = packs.optJSONObject(i) ?: continue
            val mac = p.optString("address").uppercase()
            if (mac.isBlank()) continue
            samples[mac] = BmsSample(
                voltage = p.optDoubleOrNull("voltage"),
                current = p.optDoubleOrNull("current"),
                soc = p.optDoubleOrNull("soc")?.roundToInt(),
                soh = null, // the Pi has no per-pack SOH
                remainingAh = p.optDoubleOrNull("residual_ah"),
                fullAh = p.optDoubleOrNull("nominal_ah"),
                cycles = p.optIntOrNull("cycles"),
                parallelNum = null, // bank comes straight from the JSON, no master/slave inference
                protectionNormal = !p.optBoolean("has_fault", false),
                cellVoltages = p.optJSONArray("cells").toDoubleList(),
                tempsC = p.optJSONArray("temps").toDoubleList(),
                broadcastCellMax = p.optDoubleOrNull("cell_max"),
                broadcastCellMin = p.optDoubleOrNull("cell_min"),
            )
        }
    }

    val bankObj = o.optJSONObject("bank")
    val bank = bankObj?.let {
        BankSummary(
            packs = it.optInt("packs"),
            soc = it.optDoubleOrNull("soc")?.roundToInt(),
            voltage = it.optDoubleOrNull("voltage"),
            current = it.optDoubleOrNull("current"),
            power = it.optDoubleOrNull("power"),
            remainingAh = it.optDoubleOrNull("residual_ah"),
            fullAh = it.optDoubleOrNull("nominal_ah"),
        )
    }
    return PiBatterySnapshot(true, o.optLongOrNull("ts"), samples, bank)
}

/** Map the Pi's `/api/current` payload to an [InverterReading]. `raw` is empty — the Pi already
 *  decoded the registers, and only the (Direct-mode) sniffer/liveness logic consumes `raw`. */
fun mapInverter(o: JSONObject): InverterReading {
    val faults = o.optJSONArray("faults")
    val faultCodes = buildList {
        if (faults != null) for (i in 0 until faults.length()) {
            faults.optJSONObject(i)?.optIntOrNull("code")?.let { add(it) }
        }
    }
    val status = InverterStatus(
        batterySoc = o.optIntOrNull("battery_soc"),
        batteryVoltage = o.optDoubleOrNull("battery_voltage"),
        batteryCurrent = o.optDoubleOrNull("battery_current"),
        batteryTemp = o.optDoubleOrNull("battery_temp"),
        pv1Voltage = o.optDoubleOrNull("pv1_voltage"),
        pv1Current = o.optDoubleOrNull("pv1_current"),
        pv2Voltage = o.optDoubleOrNull("pv2_voltage"),
        pv2Current = o.optDoubleOrNull("pv2_current"),
        gridVoltage = o.optDoubleOrNull("grid_voltage"),
        gridFrequency = o.optDoubleOrNull("grid_frequency"),
        outputVoltage = o.optDoubleOrNull("output_voltage"),
        outputFrequency = o.optDoubleOrNull("output_frequency"),
        loadPower = o.optIntOrNull("load_power"),
        loadApparent = o.optIntOrNull("load_apparent"),
        loadCurrent = o.optDoubleOrNull("load_current"),
        loadL2Power = o.optIntOrNull("load_l2_power"),
        loadL2Apparent = o.optIntOrNull("load_l2_apparent"),
        loadL2Current = o.optDoubleOrNull("load_l2_current"),
        gridL2Voltage = o.optDoubleOrNull("grid_l2_voltage"),
        outputL2Voltage = o.optDoubleOrNull("output_l2_voltage"),
        dcTemp = o.optDoubleOrNull("dc_temp"),
        acTemp = o.optDoubleOrNull("ac_temp"),
        faultCodes = faultCodes,
        machineState = o.optIntOrNull("machine_state"),
    )
    return InverterReading(status, emptyMap())
}

// ---- JSON helpers: treat both a missing key and an explicit JSON null as "absent" ----------

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (isNull(key)) null else if (has(key)) optDouble(key).takeUnless { it.isNaN() } else null

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (isNull(key) || !has(key)) null else optInt(key)

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (isNull(key) || !has(key)) null else optLong(key)

private fun JSONArray?.toDoubleList(): List<Double> {
    if (this == null) return emptyList()
    return (0 until length()).map { getDouble(it) }
}
