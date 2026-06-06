package com.privatesolarmon.app.ui

import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privatesolarmon.app.ble.BleScanner
import com.privatesolarmon.app.bms.FaultBrand
import kotlin.math.roundToInt

internal fun looksLikeBattery(name: String?): Boolean {
    val n = name?.uppercase() ?: return false
    return BATTERY_NAME_HINTS.any { n.contains(it) }
}

private val BATTERY_NAME_HINTS =
    listOf("ECO", "LFP", "LIFEPO", "JBD", "BMS", "BATT", "BAT", "JK", "DALY", "OVERKILL", "XIAOXIANG")

private sealed interface DiscoverDevice {
    val rowKey: String
    data class Ble(val name: String, val mac: String, val rssi: Int, val connected: Boolean) : DiscoverDevice {
        override val rowKey get() = mac
    }
    data class Wifi(val name: String, val ip: String, val serial: String, val connected: Boolean) : DiscoverDevice {
        override val rowKey get() = "wifi:$ip"
    }
}

@Composable
fun DiscoveryScreen(vm: AppViewModel) {
    val c = LocalSolarColors.current
    val dark by vm.darkTheme.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val results by vm.scanResults.collectAsStateWithLifecycle()
    val monitored by vm.monitored.collectAsStateWithLifecycle()
    val reading by vm.inverter.collectAsStateWithLifecycle()
    val rawClient by vm.rawClient.collectAsStateWithLifecycle()
    val forceDirect by vm.forceDirect.collectAsStateWithLifecycle()
    val piEndpoint by vm.piEndpoint.collectAsStateWithLifecycle()
    val piError by vm.piManualError.collectAsStateWithLifecycle()

    rawClient?.let {
        SnifferPanel(vm, it)
        return
    }

    var method by rememberSaveable { mutableStateOf("bluetooth") }
    var ip by rememberSaveable { mutableStateOf(vm.inverterIp()) }
    var serial by rememberSaveable { mutableStateOf(vm.inverterSerial()) }
    val brandId by vm.inverterBrandId.collectAsStateWithLifecycle()
    val brands = remember { vm.inverterBrands() }

    val monSet = monitored.map { it.uppercase() }.toSet()
    // The Devices list mirrors the selected transport: Bluetooth shows BLE packs, Wi-Fi shows
    // the inverter. Cross-transport devices stay hidden so the list matches the chosen tab.
    val devices = remember(results, monSet, reading, method) {
        buildList {
            if (method == "bluetooth") {
                results.sortedWith(
                    compareByDescending<BleScanner.Found> { looksLikeBattery(it.name) }.thenByDescending { it.rssi },
                ).forEach {
                    add(DiscoverDevice.Ble(it.name ?: "(unnamed)", it.address, it.rssi, it.address.uppercase() in monSet))
                }
            } else if (vm.inverterConfigured()) {
                add(DiscoverDevice.Wifi("Inverter", vm.inverterIp(), vm.inverterSerial(), reading != null))
            }
        }
    }
    val live = devices.count { it is DiscoverDevice.Ble && it.connected || it is DiscoverDevice.Wifi && it.connected }

    val header: @Composable () -> Unit = {
        ScreenHeader(
            title = "Discover",
            dark = dark,
            onToggleTheme = vm::toggleTheme,
            right = {
                if (method == "bluetooth") {
                    val border = if (scanning) c.lineStrong else c.accentLine
                    val tint = if (scanning) c.txt2 else c.accent
                    Box(
                        Modifier.clip(CircleShape).border(1.dp, border, CircleShape)
                            .clickable { if (scanning) vm.stopScan() else vm.startScan() }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(if (scanning) Icons.Outlined.Stop else Icons.Outlined.Refresh, null, tint = tint, modifier = Modifier.size(14.dp))
                            Text(if (scanning) "Stop" else "Scan", style = SolarType.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp), color = tint)
                        }
                    }
                }
            },
        )
    }

    val controlsSection: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, null, tint = c.txt3, modifier = Modifier.size(13.dp))
                Text("  On-device only · no cloud", style = SolarType.body.copy(fontSize = 12.sp), color = c.txt3)
            }

            if (!vm.bluetoothEnabled()) {
                Text("Bluetooth is off — enable it to scan.", style = SolarType.body, color = c.fault)
            }

            SectionLabel("Add a device")
            MethodSwitch(method) { method = it }

            if (method == "bluetooth") {
                BtRadarCard(scanning)
            } else {
                WifiForm(ip, serial, onIp = { ip = it }, onSerial = { serial = it }) {
                    vm.addWifiDevice(ip.trim(), serial.trim())
                }
                InverterBrandPicker(brands, brandId) { vm.setInverterBrand(it) }
            }
        }
    }

    val devicesSection: @Composable () -> Unit = {
        SolarCard(pad = 16.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Devices")
                Text("$live/${devices.size} live", style = SolarType.monoSmall, color = c.txt3)
            }
            if (devices.isEmpty()) {
                Text(
                    if (scanning) "Looking for devices…" else "No devices yet.",
                    style = SolarType.body, color = c.txt3, modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            devices.forEach { dev -> DeviceRow(vm, dev) }
        }
    }

    val solarPiSection: @Composable () -> Unit = {
        SolarPiCard(vm, forceDirect, piEndpoint, piError)
    }

    AdaptiveScreen(
        landscape = isLandscape(),
        header = header,
        sections = listOf(controlsSection, devicesSection, solarPiSection),
    )
}

/**
 * Solar Pi settings: shows the active source, lets the user pin a manual host (when mDNS can't
 * reach it), and lets them opt out of Pi mode entirely. Auto-discovery needs no setup here.
 */
@Composable
private fun SolarPiCard(
    vm: AppViewModel,
    forceDirect: Boolean,
    endpoint: com.privatesolarmon.app.net.PiEndpoint?,
    error: String?,
) {
    val c = LocalSolarColors.current
    val busy by vm.piManualBusy.collectAsStateWithLifecycle()
    var host by rememberSaveable { mutableStateOf(vm.piManualHost()) }
    var port by rememberSaveable { mutableStateOf(vm.piManualPort().toString()) }
    var autoFilled by rememberSaveable { mutableStateOf("") }

    // Reading from this exact host right now? (Either auto-discovered or manually pinned.)
    val connected = endpoint != null && host.trim().isNotEmpty() && endpoint.host == host.trim()
    val canConnect = host.isNotBlank() && !busy && !connected

    // Surface the active Pi's IP/host in the field — without clobbering what the user is typing
    // (only fill when the field is blank or still holds a previous auto-fill).
    LaunchedEffect(endpoint?.host, endpoint?.port) {
        val ep = endpoint ?: return@LaunchedEffect
        if (host.isBlank() || host == autoFilled) {
            host = ep.host
            port = ep.port.toString()
            autoFilled = ep.host
        }
    }

    SolarCard(pad = 18.dp) {
        SectionLabel("Solar Pi")
        Text(
            "If a Solar Pi is on your network, the app reads battery + inverter data from it so it " +
                "doesn't compete with the Pi for the battery's Bluetooth link. Pis are found " +
                "automatically; enter a host below only if auto-discovery doesn't reach yours.",
            style = SolarType.body.copy(fontSize = 12.sp), color = c.txt3,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        // Live status of the current source.
        val statusText = when {
            forceDirect -> "Direct mode — ignoring any Solar Pi"
            endpoint != null -> "Connected to ${endpoint.host}:${endpoint.port} (${if (endpoint.source == com.privatesolarmon.app.net.PiEndpoint.Source.MANUAL) "manual" else "auto-discovered"})"
            else -> "No Solar Pi found — reading directly"
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (endpoint != null && !forceDirect) c.charge else c.txt3))
            Text(statusText, style = SolarType.monoSmall, color = c.txt2)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(host, { host = it }, label = { Text("Pi host or IP") }, singleLine = true, modifier = Modifier.weight(2f))
            OutlinedTextField(
                port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
            )
        }
        // One button that toggles: connect to the host, or — once connected — forget it and
        // force a disconnect (back to direct BLE/dongle, and stop auto-rediscovering it).
        Box(
            Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(12.dp))
                .background(if (canConnect) c.accent else c.surface2)
                .border(1.dp, if (canConnect) c.accent else if (connected) c.lineStrong else c.line, RoundedCornerShape(12.dp))
                .clickable(enabled = !busy && (connected || canConnect)) {
                    if (connected) {
                        host = ""; autoFilled = ""
                        vm.clearManualPi()
                        vm.setForceDirect(true)
                    } else {
                        vm.setForceDirect(false)
                        vm.saveManualPi(host.trim(), port.toIntOrNull() ?: 8000)
                    }
                }
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = c.accent)
                Text(
                    when { busy -> "Checking…"; connected -> "Forget IP"; else -> "Use this Pi" },
                    style = SolarType.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                    color = if (canConnect) c.accentInk else if (connected) c.txt else c.txt3,
                )
            }
        }
        if (error != null) {
            Text("⚠ $error", style = SolarType.body.copy(fontSize = 12.sp), color = c.fault, modifier = Modifier.padding(top = 8.dp))
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Always use direct connection", style = SolarType.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp), color = c.txt)
                Text("Ignore any Solar Pi and read over Bluetooth/Wi-Fi.", style = SolarType.body.copy(fontSize = 12.sp), color = c.txt3)
            }
            Switch(checked = forceDirect, onCheckedChange = { vm.setForceDirect(it) })
        }
    }
}

@Composable
private fun MethodSwitch(method: String, onSelect: (String) -> Unit) {
    val c = LocalSolarColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("bluetooth" to Icons.Outlined.Bluetooth, "wifi" to Icons.Outlined.Wifi).forEach { (key, icon) ->
            val on = method == key
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .background(if (on) c.accent else c.surface)
                    .border(1.dp, if (on) c.accent else c.line, RoundedCornerShape(12.dp))
                    .clickable { onSelect(key) }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(icon, null, tint = if (on) c.accentInk else c.txt2, modifier = Modifier.size(16.dp))
                    Text(if (key == "bluetooth") "Bluetooth" else "Wi-Fi", style = SolarType.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp), color = if (on) c.accentInk else c.txt2)
                }
            }
        }
    }
}

@Composable
private fun BtRadarCard(scanning: Boolean) {
    val c = LocalSolarColors.current
    SolarCard(pad = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (scanning) {
                    val t = rememberInfiniteTransition(label = "radar")
                    val s by t.animateFloat(0.4f, 1f, infiniteRepeatable(tween(2200), RepeatMode.Restart), label = "s")
                    val a by t.animateFloat(0.55f, 0f, infiniteRepeatable(tween(2200), RepeatMode.Restart), label = "a")
                    Box(Modifier.size(48.dp).graphicsLayer { scaleX = s; scaleY = s; alpha = a }.border(2.dp, c.accent, CircleShape))
                }
                Box(Modifier.size(12.dp).clip(CircleShape).background(if (scanning) c.accent else c.txt3))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(if (scanning) "Looking for devices…" else "Scanning paused", style = SolarType.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp), color = c.txt)
                Text(
                    if (scanning) "Bluetooth packs appear automatically — keep them powered and nearby."
                    else "The list below may be out of date. Resume to refresh signal & order.",
                    style = SolarType.body.copy(fontSize = 12.sp), color = c.txt3,
                )
            }
        }
    }
}

@Composable
private fun WifiForm(ip: String, serial: String, onIp: (String) -> Unit, onSerial: (String) -> Unit, onAdd: () -> Unit) {
    val c = LocalSolarColors.current
    val ready = ip.isNotBlank() && serial.isNotBlank()
    SolarCard(pad = 18.dp) {
        Text(
            "Wi-Fi inverters aren't discoverable over the air. Enter the unit's static IP and serial number to connect directly.",
            style = SolarType.body.copy(fontSize = 12.sp), color = c.txt3, modifier = Modifier.padding(bottom = 14.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(ip, onIp, label = { Text("IP address") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(
                serial, { onSerial(it.filter(Char::isDigit)) }, label = { Text("Serial no.") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
            )
        }
        Box(
            Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(12.dp))
                .background(if (ready) c.accent else c.surface2)
                .border(1.dp, if (ready) c.accent else c.line, RoundedCornerShape(12.dp))
                .clickable(enabled = ready) { onAdd() }
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(Icons.Outlined.Add, null, tint = if (ready) c.accentInk else c.txt3, modifier = Modifier.size(16.dp))
                Text("Add Wi-Fi device", style = SolarType.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp), color = if (ready) c.accentInk else c.txt3)
            }
        }
    }
}

@Composable
private fun InverterBrandPicker(brands: List<FaultBrand>, selectedId: String, onSelect: (String) -> Unit) {
    val c = LocalSolarColors.current
    SolarCard(pad = 18.dp) {
        SectionLabel("Inverter brand")
        Text(
            "Picks which fault-code table to use. Add more brands in assets/faults/.",
            style = SolarType.body.copy(fontSize = 12.sp), color = c.txt3,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        if (brands.isEmpty()) {
            Text("No fault-code tables found.", style = SolarType.body, color = c.txt3)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                brands.forEach { b ->
                    val on = b.id == selectedId
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (on) c.accentSoft else c.surface)
                            .border(1.dp, if (on) c.accent else c.line, RoundedCornerShape(12.dp))
                            .clickable { onSelect(b.id) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            b.name,
                            style = SolarType.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                            color = if (on) c.accent else c.txt2,
                        )
                        if (on) Icon(Icons.Outlined.Check, null, tint = c.accent, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(vm: AppViewModel, dev: DiscoverDevice) {
    val c = LocalSolarColors.current
    val wifi = dev is DiscoverDevice.Wifi
    val col = if (wifi) c.accent2 else c.accent
    val connected = when (dev) {
        is DiscoverDevice.Ble -> dev.connected
        is DiscoverDevice.Wifi -> dev.connected
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(c.surface2).border(1.dp, c.line, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (wifi) Icons.Outlined.Wifi else Icons.Outlined.Bluetooth, null, tint = col, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                when (dev) { is DiscoverDevice.Ble -> dev.name; is DiscoverDevice.Wifi -> dev.name },
                style = SolarType.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp), color = c.txt, maxLines = 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 3.dp)) {
                when (dev) {
                    is DiscoverDevice.Ble -> {
                        Text(dev.mac, style = SolarType.monoSmall, color = c.txt3)
                        SignalBars(dev.rssi)
                    }
                    is DiscoverDevice.Wifi -> Text("${dev.ip} · ${dev.serial}", style = SolarType.monoSmall, color = c.txt3)
                }
            }
        }
        if (dev is DiscoverDevice.Ble) {
            Box(
                Modifier.clip(CircleShape).border(1.dp, c.line, CircleShape).clickable { vm.openRaw(dev.mac) }.padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(Icons.Outlined.Search, null, tint = c.txt2, modifier = Modifier.size(14.dp))
                    Text("Inspect", style = SolarType.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp), color = c.txt2)
                }
            }
        }
        if (connected) {
            Pill(tone = "green") {
                Icon(Icons.Outlined.Check, null, modifier = Modifier.size(13.dp))
                Text("Live")
            }
        } else {
            Box(
                Modifier.clip(CircleShape).border(1.dp, c.accentLine, CircleShape)
                    .clickable {
                        when (dev) {
                            is DiscoverDevice.Ble -> vm.addBattery(dev.mac)
                            is DiscoverDevice.Wifi -> vm.addWifiDevice(dev.ip, dev.serial)
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text("Connect", style = SolarType.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp), color = c.accent)
            }
        }
    }
}

@Composable
private fun SignalBars(rssi: Int) {
    val c = LocalSolarColors.current
    val bars = ((rssi + 100) / 12f).roundToInt().coerceIn(0, 4)
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(5, 8, 11, 14).forEachIndexed { i, h ->
            Box(Modifier.size(width = 2.5.dp, height = h.dp).clip(RoundedCornerShape(1.dp)).background(if (i < bars) c.charge else c.line))
        }
    }
}
