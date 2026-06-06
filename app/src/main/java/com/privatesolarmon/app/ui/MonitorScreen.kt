package com.privatesolarmon.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privatesolarmon.app.ble.ConnState
import com.privatesolarmon.app.bms.BmsSample
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun MonitorScreen(vm: AppViewModel) {
    val c = LocalSolarColors.current
    val dark by vm.darkTheme.collectAsStateWithLifecycle()
    val monitored by vm.monitored.collectAsStateWithLifecycle()
    val bank by vm.bank.collectAsStateWithLifecycle()
    val samples by vm.samples.collectAsStateWithLifecycle()
    val source by vm.dataSource.collectAsStateWithLifecycle()
    val piEndpoint by vm.piEndpoint.collectAsStateWithLifecycle()
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    val piMode = source == DataSource.PI
    // In Pi mode the bank is already aggregated server-side and packs carry no master/slave number,
    // so the Pi's pack set IS the bank. In Direct mode, group by parallelNum as before.
    val packMacs: List<String>
    val members: List<String>
    val others: List<String>
    val hasBank: Boolean
    if (piMode) {
        packMacs = samples.keys.sorted()
        members = packMacs
        others = emptyList()
        hasBank = bank != null && packMacs.size >= 2
    } else {
        packMacs = monitored
        val memberSet = BankSummary.memberMacs(samples)
        members = monitored.filter { it.uppercase() in memberSet }.sortedBy { samples[it.uppercase()]?.parallelNum ?: Int.MAX_VALUE }
        others = monitored.filterNot { it.uppercase() in memberSet }
        hasBank = members.size >= 2
    }

    @Composable
    fun packCard(mac: String) {
        val onToggle = { expanded[mac] = !(expanded[mac] ?: false) }
        if (piMode) PiPackCard(mac, samples[mac.uppercase()], expanded[mac] == true, onToggle)
        else PackCard(vm, mac, expanded[mac] == true, onToggle)
    }

    val header: @Composable () -> Unit = {
        Column {
            ScreenHeader(
                title = "Batteries",
                dark = dark,
                onToggleTheme = vm::toggleTheme,
                sub = if (piMode) "Via Solar Pi" else "On-device only",
                right = {
                    if (!piMode && monitored.isNotEmpty()) {
                        OutlinePillButton("Connect all", c.accent, c.accentLine) { vm.connectAll() }
                    }
                },
            )
            DataSourceBanner(piEndpoint, modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp))
        }
    }

    // Landscape: one column per battery. When packs form a bank, the bank heads its own column
    // with its member packs stacked beneath it (scroll down); stray batteries get their own column.
    if (isLandscape()) {
        val tiles: List<@Composable () -> Unit> = buildList {
            if (packMacs.isEmpty()) {
                add { if (piMode) PiWaitingCard() else NoBatteriesCard() }
            } else if (hasBank) {
                add {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        bank?.let { b -> BankCard(b, piMode) { vm.refreshAll(members) } }
                        members.forEach { mac -> packCard(mac) }
                    }
                }
                others.forEach { mac -> add { packCard(mac) } }
            } else {
                packMacs.forEach { mac -> add { packCard(mac) } }
            }
        }
        LandscapeColumns(header = header, columns = tiles, minColumnWidth = 300.dp)
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
        header()

        Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (packMacs.isEmpty()) {
                if (piMode) PiWaitingCard() else NoBatteriesCard()
            } else {
                if (hasBank) {
                    bank?.let { BankCard(it, piMode) { vm.refreshAll(members) } }
                    SectionLabel("Packs", modifier = Modifier.padding(start = 4.dp, top = 2.dp))
                    members.forEach { mac -> packCard(mac) }
                    if (others.isNotEmpty()) {
                        SectionLabel("Other batteries", modifier = Modifier.padding(start = 4.dp, top = 2.dp))
                        others.forEach { mac -> packCard(mac) }
                    }
                } else {
                    SectionLabel("Packs", modifier = Modifier.padding(start = 4.dp, top = 2.dp))
                    packMacs.forEach { mac -> packCard(mac) }
                }
            }
        }
    }
}

@Composable
private fun NoBatteriesCard() {
    val c = LocalSolarColors.current
    SolarCard(pad = 18.dp) {
        Text("No batteries yet.", style = SolarType.body, color = c.txt)
        Text("Add them on the Discover tab.", style = SolarType.body, color = c.txt3, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun BankCard(bank: BankSummary, piMode: Boolean = false, onRefresh: () -> Unit) {
    val c = LocalSolarColors.current
    val charging = (bank.current ?: 0.0) >= 0.0
    SolarCard(pad = 18.dp, accent = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Bank total", color = c.txt2)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(tone = "green") {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(c.charge))
                    Text("${bank.packs} packs")
                }
                if (!piMode) RefreshIconButton(onRefresh) // the Pi re-polls on its own cadence
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Text("STATE OF CHARGE", style = SolarType.labelTiny, color = c.txt3)
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 6.dp)) {
                    Text("${bank.soc ?: "—"}", style = SolarType.metric.copy(fontSize = 42.sp), color = c.txt)
                    Text("%", style = SolarType.metric.copy(fontSize = 20.sp), color = c.txt2, modifier = Modifier.padding(bottom = 5.dp))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    (bank.power?.let { (if (it >= 0) "+" else "") + it.toInt() } ?: "—") + " W",
                    style = SolarType.monoValue.copy(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
                    color = if (charging) c.charge else c.discharge,
                )
                Text(signed(bank.current, 1) + " A", style = SolarType.monoSmall, color = c.txt3, modifier = Modifier.padding(top = 5.dp))
            }
        }
        SocBar(bank.soc ?: 0, color = if (charging) c.charge else c.discharge, height = 14.dp)
        Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            BankStat("Capacity", if (bank.remainingAh != null && bank.fullAh != null) "${fmt(bank.remainingAh, 0)} / ${fmt(bank.fullAh, 0)}" else "—", "Ah", Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(34.dp).background(c.line))
            BankStat("Voltage", fmt(bank.voltage, 2), "V", Modifier.weight(1f).padding(start = 16.dp))
        }
    }
}

@Composable
private fun BankStat(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    val c = LocalSolarColors.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label.uppercase(), style = SolarType.labelTiny, color = c.txt3)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = SolarType.monoValue.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp), color = c.txt)
            Text(" $unit", style = SolarType.monoSmall, color = c.txt3, modifier = Modifier.padding(bottom = 1.dp))
        }
    }
}

/** Direct (BLE) pack card: owns a live [BatteryClient], connecting on first appearance. */
@Composable
private fun PackCard(vm: AppViewModel, mac: String, expanded: Boolean, onToggle: () -> Unit) {
    val c = LocalSolarColors.current
    val client = remember(mac) { vm.client(mac) }
    val state by client.state.collectAsStateWithLifecycle()
    val sample by client.sample.collectAsStateWithLifecycle()
    LaunchedEffect(mac) { vm.connect(mac) }

    val connected = state is ConnState.Live || state is ConnState.Connected
    PackCardContent(
        title = client.name ?: "Battery",
        mac = mac,
        sample = sample,
        connected = connected,
        connectingLabel = "Connecting…",
        expanded = expanded,
        onToggle = onToggle,
        statusRight = {
            RefreshIconButton { vm.refresh(mac) }
            LiveDot(on = connected, label = if (connected) "On" else "Off")
            Icon(Icons.Outlined.ChevronRight, null, tint = c.txt3, modifier = Modifier.size(18.dp).rotate(if (expanded) 90f else 0f))
        },
        expandedControls = {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    OutlineFullButton(if (connected) "Disconnect" else "Connect", c.lineStrong, c.txt) {
                        if (connected) vm.disconnect(mac) else vm.connect(mac)
                    }
                }
                Text(
                    "Remove",
                    style = SolarType.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp),
                    color = c.fault,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { vm.removeBattery(mac) }.padding(horizontal = 18.dp, vertical = 13.dp),
                )
            }
        },
    )
}

/** Pi pack card: read-only telemetry fed from the Solar Pi's `/api/battery`. No GATT, no controls. */
@Composable
private fun PiPackCard(mac: String, sample: BmsSample?, expanded: Boolean, onToggle: () -> Unit) {
    val c = LocalSolarColors.current
    val live = sample?.hasData == true
    PackCardContent(
        title = "Battery " + shortName(mac),
        mac = mac,
        sample = sample,
        connected = live,
        connectingLabel = "Waiting for Solar Pi…",
        expanded = expanded,
        onToggle = onToggle,
        statusRight = {
            LiveDot(on = live, label = if (live) "Pi" else "—")
            Icon(Icons.Outlined.ChevronRight, null, tint = c.txt3, modifier = Modifier.size(18.dp).rotate(if (expanded) 90f else 0f))
        },
        expandedControls = {
            Text(
                "Read-only · served by the Solar Pi",
                style = SolarType.body.copy(fontSize = 12.sp),
                color = c.txt3,
                modifier = Modifier.padding(top = 14.dp),
            )
        },
    )
}

/** Shared visual shell for a pack card; the live-vs-Pi specifics come in via the slots. */
@Composable
private fun PackCardContent(
    title: String,
    mac: String,
    sample: BmsSample?,
    connected: Boolean,
    connectingLabel: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    statusRight: @Composable () -> Unit,
    expandedControls: @Composable () -> Unit,
) {
    val c = LocalSolarColors.current
    val soc = sample?.soc
    SolarCard(pad = 0.dp) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable { onToggle() }.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = SolarType.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.5.sp), color = c.txt)
                    Text(mac, style = SolarType.monoSmall, color = c.txt3, modifier = Modifier.padding(top = 4.dp))
                    sample?.parallelNum?.let {
                        Box(Modifier.padding(top = 6.dp)) { Pill(tone = "accent") { Text("#$it") } }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    statusRight()
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("State of charge", style = SolarType.body.copy(fontSize = 13.sp), color = c.txt2)
                Text("${soc ?: "—"} %", style = SolarType.monoSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp), color = socColor(soc, c))
            }
            SocBar(soc ?: 0, color = socColor(soc, c))
        }
        if (expanded) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                val s = sample
                if (s == null || !s.hasData) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 10.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.accent)
                        Text(connectingLabel, style = SolarType.body, color = c.txt2)
                    }
                } else {
                    DataRow("Pack voltage", fmt(s.voltage, 2), "V")
                    val mx = s.cellMax; val mn = s.cellMin; val dv = s.cellDelta
                    DataRow("Cells", if (mx != null && mn != null && dv != null) "${fmt(mn, 3)}–${fmt(mx, 3)}" else "—", if (dv != null) "V · Δ${fmt(dv, 3)}" else "V")
                    val tmax = s.tempMax; val tmin = s.tempMin
                    DataRow("Temps", if (tmax != null && tmin != null) "${fmt(tmin, 1)}–${fmt(tmax, 1)}" else "—", "°C")
                    DataRow("Health", s.soh?.toString() ?: "—", "%", color = c.charge)
                    DataRow("Protection", if (s.protectionNormal) "Normal" else "Fault", color = if (s.protectionNormal) c.charge else c.discharge, strong = true, last = true)
                }
                expandedControls()
            }
        }
    }
}

@Composable
private fun PiWaitingCard() {
    val c = LocalSolarColors.current
    SolarCard(pad = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.accent)
            Text("Waiting for battery data from the Solar Pi…", style = SolarType.body, color = c.txt2)
        }
    }
}

/** Last 6 hex of a MAC, e.g. AA:C2:37:08:25:3D → 08253D — matches the Pi's pack naming. */
private fun shortName(mac: String): String = mac.replace(":", "").takeLast(6).uppercase()

/** Small circular refresh button that spins briefly when tapped. Has its own clickable so a tap
 *  here doesn't also toggle the surrounding (clickable) pack card. */
@Composable
private fun RefreshIconButton(onClick: () -> Unit) {
    val c = LocalSolarColors.current
    var spinning by remember { mutableStateOf(false) }
    LaunchedEffect(spinning) { if (spinning) { delay(1100); spinning = false } }
    val t = rememberInfiniteTransition(label = "refresh")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle")
    Box(
        Modifier.size(32.dp).clip(CircleShape).border(1.dp, c.line, CircleShape)
            .clickable { spinning = true; onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Refresh, "Refresh", tint = c.txt2,
            modifier = Modifier.size(16.dp).rotate(if (spinning) angle else 0f),
        )
    }
}

@Composable
private fun OutlinePillButton(text: String, textColor: androidx.compose.ui.graphics.Color, borderColor: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape).border(1.dp, borderColor, CircleShape).clickable { onClick() }.padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = SolarType.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp), color = textColor)
    }
}

@Composable
private fun OutlineFullButton(text: String, borderColor: androidx.compose.ui.graphics.Color, textColor: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, borderColor, RoundedCornerShape(12.dp)).clickable { onClick() }.padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = SolarType.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp), color = textColor)
    }
}

private fun socColor(soc: Int?, c: SolarColors): androidx.compose.ui.graphics.Color = when {
    soc == null -> c.txt3
    soc < 35 -> c.fault
    soc <= 70 -> c.discharge
    else -> c.charge
}

private fun fmt(v: Double?, digits: Int): String = v?.let { String.format(Locale.US, "%.${digits}f", it) } ?: "—"
private fun signed(v: Double?, digits: Int): String = v?.let { (if (it >= 0) "+" else "") + String.format(Locale.US, "%.${digits}f", it) } ?: "—"
