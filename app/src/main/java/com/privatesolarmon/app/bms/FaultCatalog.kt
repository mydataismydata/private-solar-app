package com.privatesolarmon.app.bms

import android.content.Context
import org.json.JSONObject

/** One inverter brand's fault-code table, loaded from assets/faults/<id>.json. */
data class FaultBrand(
    val id: String,
    val name: String,
    val match: List<String> = emptyList(),
    val codes: Map<Int, String> = emptyMap(),
) {
    /** Human text for a fault code, or a sensible fallback if this table doesn't list it. */
    fun text(code: Int): String = codes[code] ?: "Unknown fault"
}

/**
 * Loads the per-brand fault-code tables shipped under `assets/faults/` (one JSON file per brand).
 * The tables are plain JSON so anyone can add their inverter brand without touching code — see
 * the README in that folder.
 */
object FaultCatalog {
    const val DEFAULT_BRAND = "ecoworthy"

    /** Parse a single brand table from its JSON text. Pure (no Android deps) so it's unit-testable. */
    fun parse(json: String): FaultBrand {
        val o = JSONObject(json)
        val codesObj = o.getJSONObject("codes")
        val codes = LinkedHashMap<Int, String>()
        for (key in codesObj.keys()) {
            key.toIntOrNull()?.let { codes[it] = codesObj.getString(key) }
        }
        val matchArr = o.optJSONArray("match")
        val match = if (matchArr == null) emptyList() else (0 until matchArr.length()).map { matchArr.getString(it) }
        val id = o.getString("id")
        return FaultBrand(id = id, name = o.optString("name", id), match = match, codes = codes)
    }

    /** Load every brand table from assets, sorted by display name. Bad/missing files are skipped. */
    fun load(context: Context): List<FaultBrand> {
        val assets = context.assets
        val files = runCatching { assets.list("faults") }.getOrNull().orEmpty()
            .filter { it.endsWith(".json", ignoreCase = true) }
        return files.mapNotNull { file ->
            runCatching {
                assets.open("faults/$file").use { it.readBytes().toString(Charsets.UTF_8) }.let(::parse)
            }.getOrNull()
        }.sortedBy { it.name.lowercase() }
    }
}
