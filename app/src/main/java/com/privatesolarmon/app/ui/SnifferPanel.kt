package com.privatesolarmon.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privatesolarmon.app.ble.BatteryClient
import com.privatesolarmon.app.ble.LogLine
import com.privatesolarmon.app.bms.JbdProtocol
import com.privatesolarmon.app.bms.hexToBytesOrNull

/** Protocol inspector / sniffer for a BLE device — reverse-engineering tool. */
@Composable
fun SnifferPanel(vm: AppViewModel, client: BatteryClient, modifier: Modifier = Modifier) {
    val state by client.state.collectAsStateWithLifecycle()
    val log by client.rawLog.collectAsStateWithLifecycle()
    var hex by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1)
    }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Inspect ${client.name ?: client.address}", style = MaterialTheme.typography.titleMedium)
                Text(state.label(), style = MaterialTheme.typography.labelMedium, color = state.color())
            }
            TextButton(onClick = { vm.closeRaw() }) { Text("Close") }
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(onClick = { vm.sendRaw(client.address, JbdProtocol.readCommand(JbdProtocol.REG_BASIC_INFO)) }) { Text("0x03") }
            OutlinedButton(onClick = { vm.sendRaw(client.address, JbdProtocol.readCommand(JbdProtocol.REG_CELL_VOLTAGES)) }) { Text("0x04") }
            OutlinedButton(onClick = { vm.sendRaw(client.address, JbdProtocol.readCommand(JbdProtocol.REG_DEVICE_NAME)) }) { Text("name") }
            OutlinedButton(onClick = {
                val text = log.joinToString("\n") { "${prefix(it.dir)} ${it.text}" }
                clipboard.setText(AnnotatedString(text))
            }) { Text("copy") }
            OutlinedButton(onClick = { client.clearLog() }) { Text("clear") }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = hex,
                onValueChange = { hex = it },
                singleLine = true,
                label = { Text("custom hex, e.g. dd a5 03 00 ff fd 77") },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            val bytes = hex.hexToBytesOrNull()
            Button(enabled = bytes != null, onClick = { bytes?.let { vm.sendRaw(client.address, it) } }) {
                Text("Send")
            }
        }

        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            itemsIndexed(log) { _, line ->
                Text(
                    "${prefix(line.dir)} ${line.text}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (line.dir) {
                        LogLine.Dir.TX -> MaterialTheme.colorScheme.primary
                        LogLine.Dir.RX -> MaterialTheme.colorScheme.tertiary
                        LogLine.Dir.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

private fun prefix(dir: LogLine.Dir): String = when (dir) {
    LogLine.Dir.TX -> "TX →"
    LogLine.Dir.RX -> "RX ←"
    LogLine.Dir.INFO -> "  ·"
}
