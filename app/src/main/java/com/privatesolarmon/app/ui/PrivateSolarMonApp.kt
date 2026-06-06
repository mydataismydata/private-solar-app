package com.privatesolarmon.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun PrivateSolarMonApp(vm: AppViewModel) {
    val c = LocalSolarColors.current
    var tab by rememberSaveable { mutableIntStateOf(1) } // default: Inverter
    val landscape = isLandscape()
    Scaffold(
        containerColor = c.bg,
        // Landscape moves the menu to a left-hand rail; portrait keeps the bottom bar.
        bottomBar = { if (!landscape) SolarBottomBar(tab) { tab = it } },
    ) { padding ->
        Row(Modifier.padding(padding).fillMaxSize().background(c.bg)) {
            if (landscape) SolarNavRail(tab) { tab = it }
            Box(Modifier.weight(1f).fillMaxSize()) {
                when (tab) {
                    0 -> MonitorScreen(vm)
                    1 -> InverterScreen(vm)
                    else -> DiscoveryScreen(vm)
                }
            }
        }
    }
}

private val NAV_ITEMS = listOf(
    NavItem(0, "Batteries", Icons.Outlined.BatteryFull),
    NavItem(1, "Inverter", Icons.Outlined.Power),
    NavItem(2, "Discover", Icons.Outlined.Sensors),
)

private data class NavItem(val index: Int, val label: String, val icon: ImageVector)

/** Vertical navigation rail shown on the left in landscape. */
@Composable
private fun SolarNavRail(selected: Int, onSelect: (Int) -> Unit) {
    val c = LocalSolarColors.current
    Row(Modifier.fillMaxHeight()) {
        Column(
            Modifier.width(84.dp).fillMaxHeight().background(c.surface).padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NAV_ITEMS.forEach { item ->
                val on = selected == item.index
                Column(
                    Modifier.clip(RoundedCornerShape(16.dp)).clickable { onSelect(item.index) }.padding(horizontal = 6.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Box(
                        Modifier.size(width = 56.dp, height = 30.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(if (on) c.accentSoft else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(item.icon, item.label, tint = if (on) c.accent else c.txt3, modifier = Modifier.size(22.dp))
                    }
                    Text(item.label, style = SolarType.navLabel, color = if (on) c.accent else c.txt3)
                }
            }
        }
        VerticalDivider(color = c.line)
    }
}

@Composable
private fun SolarBottomBar(selected: Int, onSelect: (Int) -> Unit) {
    val c = LocalSolarColors.current
    Column(Modifier.background(c.surface)) {
        HorizontalDivider(color = c.line)
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            NAV_ITEMS.forEach { item ->
                val on = selected == item.index
                Column(
                    Modifier.clip(RoundedCornerShape(16.dp)).clickable { onSelect(item.index) }.padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Box(
                        Modifier.size(width = 64.dp, height = 30.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(if (on) c.accentSoft else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(item.icon, item.label, tint = if (on) c.accent else c.txt3, modifier = Modifier.size(22.dp))
                    }
                    Text(item.label, style = SolarType.navLabel, color = if (on) c.accent else c.txt3)
                }
            }
        }
    }
}
