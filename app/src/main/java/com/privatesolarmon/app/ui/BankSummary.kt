package com.privatesolarmon.app.ui

import com.privatesolarmon.app.bms.BmsSample
import kotlin.math.roundToInt

/**
 * Whole-bank rollup across the monitored batteries.
 *
 * Intensive values (SOC, voltage) are averaged. Extensive values (current, power, capacity)
 * are taken from the *aggregating* pack — the one that already reports whole-bank totals,
 * identified as the pack reporting the largest full capacity. Summing instead would double-count,
 * because the slave's broadcast already includes the master's contribution.
 */
data class BankSummary(
    val packs: Int,
    val soc: Int?,
    val voltage: Double?,
    val current: Double?,
    val power: Double?,
    val remainingAh: Double?,
    val fullAh: Double?,
) {
    companion object {
        /**
         * MAC keys (as keyed in [samples]) that form the parallel bank: every slave
         * (parallelNum >= 2) plus the single master (parallelNum == 1) reporting the largest
         * capacity — that master is the pack whose broadcast carries the whole-bank totals.
         * Returns empty when no slaves are present, i.e. there is no parallel bank to group
         * (a lone standalone pack added on its own should not be treated as a bank).
         */
        fun memberMacs(samples: Map<String, BmsSample>): Set<String> {
            val withData = samples.filterValues { it.hasData }
            val slaves = withData.filterValues { (it.parallelNum ?: 0) >= 2 }.keys
            if (slaves.isEmpty()) return emptySet()
            val master = withData.filterValues { it.parallelNum == 1 }
                .maxByOrNull { it.value.fullAh ?: 0.0 }?.key
            return slaves + setOfNotNull(master)
        }

        fun from(samples: List<BmsSample>): BankSummary? {
            if (samples.isEmpty()) return null
            val aggregator = samples.maxByOrNull { it.fullAh ?: 0.0 }
            val socs = samples.mapNotNull { it.soc }
            val volts = samples.mapNotNull { it.voltage }
            return BankSummary(
                packs = samples.size,
                soc = socs.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                voltage = volts.takeIf { it.isNotEmpty() }?.average(),
                current = aggregator?.current,
                power = aggregator?.power,
                remainingAh = aggregator?.remainingAh,
                fullAh = aggregator?.fullAh,
            )
        }
    }
}
