package com.privatesolarmon.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.privatesolarmon.app.ble.ConnState

/** Short human label for a connection state. */
internal fun ConnState.label(): String = when (this) {
    ConnState.Idle -> "idle"
    ConnState.Connecting -> "connecting…"
    is ConnState.Connected -> "connected"
    is ConnState.Live -> "live"
    ConnState.Disconnected -> "disconnected"
    is ConnState.Error -> "error"
}

/** Theme color matching a connection state. */
@Composable
internal fun ConnState.color(): Color = when (this) {
    is ConnState.Live, is ConnState.Connected -> MaterialTheme.colorScheme.primary
    ConnState.Connecting -> MaterialTheme.colorScheme.tertiary
    is ConnState.Error -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
