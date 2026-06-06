package com.privatesolarmon.app.ble

import java.util.UUID

/** GATT identifiers for the JBD BMS BLE service. */
object BleUuids {
    /** Vendor service that carries the BMS characteristics. */
    val SERVICE: UUID = uuid16("ff00")

    /** Notify characteristic — the BMS pushes response frames here. */
    val RX_NOTIFY: UUID = uuid16("ff01")

    /** Write characteristic — we send command frames here. */
    val TX_WRITE: UUID = uuid16("ff02")

    /** Standard Client Characteristic Configuration Descriptor (enables notifications). */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private fun uuid16(short: String): UUID =
        UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
}
