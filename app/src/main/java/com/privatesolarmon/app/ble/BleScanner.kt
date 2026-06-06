package com.privatesolarmon.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Thin coroutine wrapper around [android.bluetooth.le.BluetoothLeScanner].
 *
 * Caller must already hold BLUETOOTH_SCAN (API 31+) / location (API <= 30) — see MainActivity.
 */
class BleScanner(private val context: Context) {

    /** A discovered advertiser. [name] may be null until a scan response arrives. */
    data class Found(val name: String?, val address: String, val rssi: Int)

    /**
     * Cold flow of scan results. Collecting starts the scan; cancelling the collector stops it.
     * We scan with no service filter so the user can see their named device (`ECO-LFP48100-…`)
     * even if the advertisement omits the FF00 service UUID.
     */
    @SuppressLint("MissingPermission")
    fun scan(): Flow<Found> = callbackFlow {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth is off or unavailable"))
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(Found(result.device.name, result.device.address, result.rssi))
            }
            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE scan failed (code $errorCode)"))
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }
}
