package com.privatesolarmon.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privatesolarmon.app.bms.InverterStatus
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun InverterScreen(vm: AppViewModel) {
    val dark by vm.darkTheme.collectAsStateWithLifecycle()
    val reading by vm.inverter.collectAsStateWithLifecycle()
    val busy by vm.inverterBusy.collectAsStateWithLifecycle()
    val error by vm.inverterError.collectAsStateWithLifecycle()
    val piEndpoint by vm.piEndpoint.collectAsStateWithLifecycle()
    val piMode = piEndpoint != null

    val header: @Composable () -> Unit = {
        Column {
            ScreenHeader(
                title = "Inverter",
                dark = dark,
                onToggleTheme = vm::toggleTheme,
                sub = if (piMode) "Via Solar Pi" else "On-device only",
                right = { LiveDot(on = reading != null, label = if (reading != null) "Live" else "Off") },
            )
            DataSourceBanner(piEndpoint, modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp))
        }
    }

    val landscape = isLandscape()
    val brandId by vm.inverterBrandId.collectAsStateWithLifecycle()
    val faultText: (Int) -> String = remember(brandId) { { code -> vm.faultLabel(code) } }
    val st = reading?.status
    val sections: List<@Composable () -> Unit> = if (st == null) {
        listOf { InverterStatusCard(busy, error, vm.inverterConfigured()) }
    } else buildList {
        if (st.hasFault) add { FaultCard(st, faultText) }
        add { PowerFlowCard(st, vertical = landscape) }
        add { SolarPvCard(st) }
        add { AcOutputCard(st) }
        add { BatteryBankCard(st, compact = landscape) }
        add { AllDataCard(st) }
    }

    // Landscape: one panel per column (Power flow, Solar, AC output, Battery, All data), scrolling
    // horizontally. The Power flow panel is narrow (it's a vertical stack), the rest are full width.
    // Portrait: the classic single scrolling column.
    if (landscape) {
        // Power flow and Battery bank are slim vertical panels; the rest are full width.
        val widths: List<Dp?> = if (st == null) emptyList() else buildList {
            if (st.hasFault) add(280.dp)
            add(110.dp); add(null); add(null); add(160.dp); add(null)
        }
        LandscapeColumns(header = header, columns = sections, columnWidths = widths, fillWhenFits = false)
    } else {
        AdaptiveScreen(landscape = false, header = header, sections = sections)
    }
}

@Composable
private fun InverterStatusCard(busy: Boolean, error: String?, configured: Boolean) {
    val c = LocalSolarColors.current
    SolarCard(pad = 18.dp) {
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.accent)
                Text("Connecting to the inverter…", style = SolarType.body, color = c.txt)
            }
        } else {
            Text(
                if (configured) "Inverter not responding." else "No inverter yet.",
                style = SolarType.body,
                color = c.txt,
            )
            Text(
                "Add or connect it on the Discover tab (Wi-Fi).",
                style = SolarType.body,
                color = c.txt3,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (error != null) {
            Text("⚠ $error", style = SolarType.body, color = c.fault, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun FaultCard(st: InverterStatus, faultText: (Int) -> String) {
    val c = LocalSolarColors.current
    SolarCard(pad = 16.dp) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.WarningAmber, null, tint = c.fault, modifier = Modifier.size(22.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionLabel("Inverter fault", color = c.fault)
                st.faultCodes.forEach { code ->
                    Text(
                        String.format(Locale.US, "F%02d · %s", code, faultText(code)),
                        style = SolarType.body.copy(fontWeight = FontWeight.SemiBold),
                        color = c.txt,
                    )
                }
            }
        }
    }
}

@Composable
private fun PowerFlowCard(st: InverterStatus, vertical: Boolean = false) {
    SolarCard(pad = 16.dp) {
        SectionLabel("Power flow", modifier = Modifier.padding(bottom = 16.dp))
        FlowDiagram(
            pv = st.pvPower?.roundToInt() ?: 0,
            load = st.loadTotal ?: 0,
            battW = st.batteryPower?.roundToInt() ?: 0,
            vertical = vertical,
        )
    }
}

@Composable
private fun SolarPvCard(st: InverterStatus) {
    val c = LocalSolarColors.current
    val pvTotal = st.pvPower?.roundToInt() ?: 0
    SolarCard(pad = 18.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Solar PV")
            Pill(tone = if (pvTotal > 10) "accent" else "neutral") {
                Icon(Icons.Outlined.LightMode, null, modifier = Modifier.size(13.dp))
                Text(if (pvTotal > 10) "Powering" else "Idle")
            }
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            RadialGauge(value = pvTotal.toFloat(), max = 4000f, unit = "W", sub = "total input", c1 = c.accent, c2 = c.accent2)
        }
        HorizontalLine()
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            MetricLeg("PV1", st.pv1Power, st.pv1Voltage, st.pv1Current, c.accent, 2000f, Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(72.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(1.dp)).background(c.line))
            MetricLeg("PV2", st.pv2Power, st.pv2Voltage, st.pv2Current, c.accent2, 2000f, Modifier.weight(1f))
        }
    }
}

@Composable
private fun AcOutputCard(st: InverterStatus) {
    val c = LocalSolarColors.current
    val load = st.loadTotal ?: 0
    SolarCard(pad = 18.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("AC Output · Load")
            Pill(tone = "neutral") { Text("${fmt(st.outputFrequency, 2)} Hz") }
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            RadialGauge(value = load.toFloat(), max = 4000f, unit = "W", sub = "real power · L1+L2", c1 = c.load, c2 = c.load)
        }
        HorizontalLine()
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            MetricLeg("L1", st.loadPower?.toDouble(), st.outputVoltage, st.loadCurrent, c.load, 2000f, Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(72.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(1.dp)).background(c.line))
            MetricLeg("L2", st.loadL2Power?.toDouble(), st.outputL2Voltage, st.loadL2Current, c.load, 2000f, Modifier.weight(1f))
        }
    }
}

@Composable
private fun BatteryBankCard(st: InverterStatus, compact: Boolean = false) {
    if (compact) {
        CompactBatteryBankCard(st)
        return
    }
    val c = LocalSolarColors.current
    val battW = st.batteryPower?.roundToInt() ?: 0
    val charging = (st.batteryCurrent ?: 0.0) >= 0.0
    SolarCard(pad = 18.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Battery bank")
            Pill(tone = if (charging) "green" else "amber") {
                Icon(if (charging) Icons.Outlined.ArrowDownward else Icons.Outlined.ArrowUpward, null, modifier = Modifier.size(12.dp))
                Text(if (charging) "Charging" else "Discharging")
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(st.batterySoc?.toString() ?: "—", style = SolarType.metric, color = c.txt)
                Text("%", style = SolarType.metric.copy(fontSize = 20.sp), color = c.txt2, modifier = Modifier.padding(bottom = 4.dp))
            }
            Text(
                (if (battW > 0) "+$battW" else "$battW") + " W",
                style = SolarType.metric.copy(fontSize = 22.sp),
                color = if (charging) c.charge else c.discharge,
            )
        }
        SocBar(st.batterySoc ?: 0, color = if (charging) c.charge else c.discharge, height = 14.dp)
        FooterStats(
            listOf(
                Triple("Voltage", fmt(st.batteryVoltage, 2), "V"),
                Triple("Current", signed(st.batteryCurrent, 1), "A"),
                Triple("Temp", fmt(st.batteryTemp, 1), "°C"),
            ),
            Modifier.padding(top = 16.dp),
        )
    }
}

/** Narrow vertical battery panel for landscape: SOC + power beside a short vertical bar, with the
 *  Voltage/Current/Temp stats stacked beneath a divider. */
@Composable
private fun CompactBatteryBankCard(st: InverterStatus) {
    val c = LocalSolarColors.current
    val battW = st.batteryPower?.roundToInt() ?: 0
    val charging = (st.batteryCurrent ?: 0.0) >= 0.0
    val tone = if (charging) c.charge else c.discharge
    SolarCard(pad = 14.dp) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("Battery bank")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(st.batterySoc?.toString() ?: "—", style = SolarType.metric.copy(fontSize = 36.sp), color = c.txt)
                        Text("%", style = SolarType.metric.copy(fontSize = 18.sp), color = c.txt2, modifier = Modifier.padding(bottom = 4.dp))
                    }
                    Text(
                        (if (battW > 0) "+$battW" else "$battW") + " W",
                        style = SolarType.monoValue.copy(fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
                        color = tone,
                    )
                    Pill(tone = if (charging) "green" else "amber") {
                        Icon(if (charging) Icons.Outlined.ArrowDownward else Icons.Outlined.ArrowUpward, null, modifier = Modifier.size(12.dp))
                        Text(if (charging) "Charging" else "Discharging")
                    }
                }
                VerticalSocBar(st.batterySoc ?: 0, color = tone, width = 20.dp, modifier = Modifier.height(96.dp))
            }
            HorizontalLine()
            CompactStat("Voltage", fmt(st.batteryVoltage, 2), "V")
            CompactStat("Current", signed(st.batteryCurrent, 1), "A")
            CompactStat("Temp", fmt(st.batteryTemp, 1), "°C")
        }
    }
}

@Composable
private fun CompactStat(label: String, value: String, unit: String) {
    val c = LocalSolarColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Text(label.uppercase(), style = SolarType.labelTiny, color = c.txt3)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = SolarType.monoValue.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp), color = c.txt)
            Text(" $unit", style = SolarType.monoSmall, color = c.txt3, modifier = Modifier.padding(bottom = 1.dp))
        }
    }
}

@Composable
private fun AllDataCard(st: InverterStatus) {
    val c = LocalSolarColors.current
    var showAll by rememberSaveable { mutableStateOf(true) }
    SolarCard(pad = 0.dp) {
        Row(
            Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                .clickable { showAll = !showAll }
                .padding(horizontal = 18.dp, vertical = 15.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("All data · raw telemetry")
            val rot by animateFloatAsState(if (showAll) 180f else 0f, label = "chev")
            Icon(Icons.Outlined.ExpandMore, null, tint = c.txt3, modifier = Modifier.size(18.dp).rotate(rot))
        }
        if (showAll) {
            Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 6.dp)) {
                DataRow("Solar PV (total)", "${st.pvPower?.roundToInt() ?: "—"}", "W", strong = true)
                DataRow("PV1 voltage", fmt(st.pv1Voltage, 1), "V")
                DataRow("PV1 current", fmt(st.pv1Current, 1), "A")
                DataRow("PV1 power", "${st.pv1Power?.roundToInt() ?: "—"}", "W")
                DataRow("PV2 voltage", fmt(st.pv2Voltage, 1), "V")
                DataRow("PV2 current", fmt(st.pv2Current, 1), "A")
                DataRow("PV2 power", "${st.pv2Power?.roundToInt() ?: "—"}", "W")
                DataRow("Battery SOC", st.batterySoc?.toString() ?: "—", "%", color = c.discharge, strong = true)
                DataRow("Battery voltage", fmt(st.batteryVoltage, 2), "V")
                DataRow("Battery current", signed(st.batteryCurrent, 1), "A", color = c.discharge)
                DataRow("Battery power", (st.batteryPower?.let { (if (it >= 0) "+" else "") + it.roundToInt() }) ?: "—", "W", color = c.discharge)
                DataRow("Battery temp", fmt(st.batteryTemp, 1), "°C")
                DataRow("Grid L1 / L2", "${fmt(st.gridVoltage, 1)} / ${fmt(st.gridL2Voltage, 1)}", "V")
                DataRow("Output L2", fmt(st.outputL2Voltage, 1), "V")
                DataRow("Load L1 power", st.loadPower?.toString() ?: "—", "W")
                DataRow("Load L2 power", st.loadL2Power?.toString() ?: "—", "W")
                DataRow("Load total", st.loadTotal?.toString() ?: "—", "W", strong = true)
                DataRow(
                    "Fault code(s)",
                    if (st.faultCodes.isEmpty()) "none" else st.faultCodes.joinToString(", ") { String.format(Locale.US, "F%02d", it) },
                    color = if (st.hasFault) c.fault else null,
                    strong = st.hasFault,
                )
                DataRow("Machine state", st.machineState?.toString() ?: "—", last = true)
            }
        }
    }
}

@Composable
private fun MetricLeg(name: String, w: Double?, v: Double?, a: Double?, color: Color, max: Float, modifier: Modifier = Modifier) {
    val c = LocalSolarColors.current
    val frac by animateFloatAsState(((w ?: 0.0).toFloat() / max).coerceIn(0f, 1f), label = "leg")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(9.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp)).background(color))
            Text(name, style = SolarType.label, color = c.txt2)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${w?.roundToInt() ?: "—"}", style = SolarType.monoValue.copy(fontWeight = FontWeight.SemiBold, fontSize = 22.sp), color = c.txt)
            Text(" W", style = SolarType.monoSmall, color = c.txt3, modifier = Modifier.padding(bottom = 2.dp))
        }
        Box(Modifier.fillMaxWidth().height(5.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp)).background(c.track)) {
            Box(Modifier.fillMaxWidth(frac).height(5.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp)).background(color))
        }
        Text("${fmt(v, 1)} V · ${fmt(a, 1)} A", style = SolarType.monoSmall, color = c.txt3)
    }
}

@Composable
private fun FooterStats(stats: List<Triple<String, String, String>>, modifier: Modifier = Modifier) {
    val c = LocalSolarColors.current
    Row(modifier.fillMaxWidth()) {
        stats.forEachIndexed { i, (label, value, unit) ->
            if (i > 0) Box(Modifier.width(1.dp).height(36.dp).background(c.line))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(value, style = SolarType.monoValue.copy(fontWeight = FontWeight.SemiBold, fontSize = 18.sp), color = c.txt)
                    Text(" $unit", style = SolarType.monoSmall, color = c.txt3, modifier = Modifier.padding(bottom = 1.dp))
                }
                Text(label.uppercase(), style = SolarType.labelTiny, color = c.txt3)
            }
        }
    }
}

@Composable
private fun HorizontalLine() {
    val c = LocalSolarColors.current
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
}

private fun fmt(v: Double?, digits: Int): String = v?.let { String.format(Locale.US, "%.${digits}f", it) } ?: "—"
private fun signed(v: Double?, digits: Int): String = v?.let { (if (it >= 0) "+" else "") + String.format(Locale.US, "%.${digits}f", it) } ?: "—"
