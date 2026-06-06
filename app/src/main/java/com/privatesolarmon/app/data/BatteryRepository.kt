package com.privatesolarmon.app.data

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import com.privatesolarmon.app.ble.BatteryClient
import com.privatesolarmon.app.ble.BleScanner
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the BLE adapter, the scanner, and a cache of [BatteryClient]s keyed by MAC.
 * Persists the set of monitored MAC addresses across launches.
 */
class BatteryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val prefs = appContext.getSharedPreferences("privatesolarmon", Context.MODE_PRIVATE)
    private val clients = ConcurrentHashMap<String, BatteryClient>()

    val scanner = BleScanner(appContext)

    fun bluetoothAvailable(): Boolean = adapter != null
    fun bluetoothEnabled(): Boolean = adapter?.isEnabled == true

    fun isValidMac(mac: String): Boolean = BluetoothAdapter.checkBluetoothAddress(mac.uppercase())

    /** Get (or create) the client for [mac]. [scope] should outlive the connection. */
    fun clientFor(mac: String, scope: CoroutineScope): BatteryClient {
        val normalized = mac.uppercase()
        return clients.getOrPut(normalized) {
            val device = requireNotNull(adapter).getRemoteDevice(normalized)
            BatteryClient(appContext, device, scope)
        }
    }

    fun existingClient(mac: String): BatteryClient? = clients[mac.uppercase()]

    fun monitoredMacs(): List<String> =
        prefs.getStringSet(KEY_MACS, emptySet())!!.sorted()

    fun addMac(mac: String) {
        prefs.edit().putStringSet(KEY_MACS, (monitoredMacs() + mac.uppercase()).toSet()).apply()
    }

    fun removeMac(mac: String) {
        prefs.edit().putStringSet(KEY_MACS, (monitoredMacs() - mac.uppercase()).toSet()).apply()
    }

    // ---- inverter (Solarman WiFi dongle) ----
    fun inverterIp(): String = prefs.getString(KEY_INV_IP, "") ?: ""
    fun inverterSerial(): String = prefs.getString(KEY_INV_SN, "") ?: ""
    fun setInverter(ip: String, serial: String) {
        prefs.edit()
            .putString(KEY_INV_IP, ip.trim())
            .putString(KEY_INV_SN, serial.trim())
            .apply()
    }

    fun inverterBrand(): String = prefs.getString(KEY_INV_BRAND, null) ?: com.privatesolarmon.app.bms.FaultCatalog.DEFAULT_BRAND
    fun setInverterBrand(id: String) { prefs.edit().putString(KEY_INV_BRAND, id).apply() }

    // ---- Solar Pi (LAN dashboard the app reads from instead of fighting for the BLE slot) ----
    fun piManualHost(): String = prefs.getString(KEY_PI_HOST, "") ?: ""
    fun piManualPort(): Int = prefs.getInt(KEY_PI_PORT, DEFAULT_PI_PORT)
    fun setPiManual(host: String, port: Int) {
        prefs.edit().putString(KEY_PI_HOST, host.trim()).putInt(KEY_PI_PORT, port).apply()
    }
    fun clearPiManual() { prefs.edit().remove(KEY_PI_HOST).remove(KEY_PI_PORT).apply() }

    /** When true the user has opted out of Pi mode entirely — always read directly over BLE. */
    fun forceDirect(): Boolean = prefs.getBoolean(KEY_FORCE_DIRECT, false)
    fun setForceDirect(value: Boolean) { prefs.edit().putBoolean(KEY_FORCE_DIRECT, value).apply() }

    // ---- theme ----
    fun darkTheme(): Boolean = prefs.getBoolean(KEY_DARK, true)
    fun setDarkTheme(dark: Boolean) { prefs.edit().putBoolean(KEY_DARK, dark).apply() }

    companion object {
        private const val KEY_MACS = "monitored_macs"
        private const val KEY_INV_IP = "inverter_ip"
        private const val KEY_INV_SN = "inverter_serial"
        private const val KEY_INV_BRAND = "inverter_brand"
        private const val KEY_DARK = "dark_theme"
        private const val KEY_PI_HOST = "pi_host"
        private const val KEY_PI_PORT = "pi_port"
        private const val KEY_FORCE_DIRECT = "force_direct"
        const val DEFAULT_PI_PORT = 8000
    }
}
