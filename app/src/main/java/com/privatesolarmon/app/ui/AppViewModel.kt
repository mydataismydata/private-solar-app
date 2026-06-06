package com.privatesolarmon.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privatesolarmon.app.ble.BatteryClient
import com.privatesolarmon.app.ble.BleScanner
import com.privatesolarmon.app.ble.ConnState
import com.privatesolarmon.app.bms.BmsSample
import com.privatesolarmon.app.bms.FaultBrand
import com.privatesolarmon.app.bms.FaultCatalog
import com.privatesolarmon.app.bms.SrneInverter
import com.privatesolarmon.app.data.BatteryRepository
import com.privatesolarmon.app.net.InverterClient
import com.privatesolarmon.app.net.InverterReading
import com.privatesolarmon.app.net.PiEndpoint
import com.privatesolarmon.app.net.SolarPiClient
import com.privatesolarmon.app.net.SolarPiDiscovery
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where the live battery + inverter data is coming from. */
enum class DataSource { DIRECT, PI }

/**
 * UI-facing state holder. Bridges Compose to the repository and per-battery [BatteryClient]s.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = BatteryRepository(app)

    private val _monitored = MutableStateFlow(repo.monitoredMacs())
    val monitored: StateFlow<List<String>> = _monitored.asStateFlow()

    private val _scanResults = MutableStateFlow<List<BleScanner.Found>>(emptyList())
    val scanResults: StateFlow<List<BleScanner.Found>> = _scanResults.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _rawClient = MutableStateFlow<BatteryClient?>(null)
    val rawClient: StateFlow<BatteryClient?> = _rawClient.asStateFlow()

    private val _bank = MutableStateFlow<BankSummary?>(null)
    val bank: StateFlow<BankSummary?> = _bank.asStateFlow()

    /** Latest sample per monitored MAC (keyed by upper-case MAC), for bank grouping/ordering. */
    private val _samples = MutableStateFlow<Map<String, BmsSample>>(emptyMap())
    val samples: StateFlow<Map<String, BmsSample>> = _samples.asStateFlow()

    private val latestSamples = ConcurrentHashMap<String, BmsSample>()
    private val collecting = mutableSetOf<String>()
    private var scanJob: Job? = null

    private val _inverter = MutableStateFlow<InverterReading?>(null)
    val inverter: StateFlow<InverterReading?> = _inverter.asStateFlow()
    private val _inverterError = MutableStateFlow<String?>(null)
    val inverterError: StateFlow<String?> = _inverterError.asStateFlow()
    private val _inverterBusy = MutableStateFlow(false)
    val inverterBusy: StateFlow<Boolean> = _inverterBusy.asStateFlow()
    private var inverterJob: Job? = null

    // ---- data source (direct BLE/dongle vs. a Solar Pi on the LAN) --------
    private val discovery = SolarPiDiscovery(app)
    private val _dataSource = MutableStateFlow(DataSource.DIRECT)
    val dataSource: StateFlow<DataSource> = _dataSource.asStateFlow()
    private val _piEndpoint = MutableStateFlow<PiEndpoint?>(null)
    val piEndpoint: StateFlow<PiEndpoint?> = _piEndpoint.asStateFlow()
    private val _forceDirect = MutableStateFlow(repo.forceDirect())
    val forceDirect: StateFlow<Boolean> = _forceDirect.asStateFlow()
    private val _piManualError = MutableStateFlow<String?>(null)
    val piManualError: StateFlow<String?> = _piManualError.asStateFlow()
    private val _piManualBusy = MutableStateFlow(false)
    val piManualBusy: StateFlow<Boolean> = _piManualBusy.asStateFlow()
    private var piJob: Job? = null
    private val inPiMode: Boolean get() = _dataSource.value == DataSource.PI

    init {
        discovery.start()
        // Restore a saved manual host (verified async so a dead Pi doesn't pin us into Pi mode).
        if (repo.piManualHost().isNotBlank()) {
            discovery.setManual(repo.piManualHost(), repo.piManualPort())
        }
        // Single arbitration point: a Pi (unless the user forced Direct) takes over the feeds.
        viewModelScope.launch {
            combine(discovery.endpoint, _forceDirect) { ep, force -> if (force) null else ep }
                .distinctUntilChanged()
                .collect { ep -> if (ep != null) enterPiMode(ep) else enterDirectMode() }
        }
    }

    // ---- theme (persisted dark/light) -------------------------------------
    private val _darkTheme = MutableStateFlow(repo.darkTheme())
    val darkTheme: StateFlow<Boolean> = _darkTheme.asStateFlow()

    fun toggleTheme() {
        val next = !_darkTheme.value
        _darkTheme.value = next
        repo.setDarkTheme(next)
    }

    fun bluetoothEnabled(): Boolean = repo.bluetoothEnabled()
    fun isValidMac(mac: String): Boolean = repo.isValidMac(mac)
    fun inverterConfigured(): Boolean = repo.inverterIp().isNotBlank() && repo.inverterSerial().isNotBlank()

    /** Add (and start polling) the Wi-Fi inverter from the Discover screen. */
    fun addWifiDevice(ip: String, serial: String) = connectInverter(ip, serial)

    /** The cached client for [mac], creating it if needed, and tracking its sample for the bank rollup. */
    fun client(mac: String): BatteryClient {
        val client = repo.clientFor(mac, viewModelScope)
        val key = mac.uppercase()
        if (collecting.add(key)) {
            viewModelScope.launch {
                client.sample.collect { s ->
                    if (s != null) latestSamples[key] = s else latestSamples.remove(key)
                    recomputeBank()
                }
            }
        }
        return client
    }

    private fun recomputeBank() {
        if (inPiMode) return // Pi mode writes _samples/_bank directly; BLE collectors must not clobber it
        val monitoredSamples = _monitored.value
            .mapNotNull { mac -> latestSamples[mac.uppercase()]?.let { mac.uppercase() to it } }
            .toMap()
        _samples.value = monitoredSamples
        val members = BankSummary.memberMacs(monitoredSamples)
        _bank.value = BankSummary.from(members.mapNotNull { monitoredSamples[it] })
    }

    // ---- data-source switching --------------------------------------------

    /** Hand the feeds over to the Pi: stop our own BLE/dongle access so we don't fight it for the
     *  battery's single BLE slot, then poll the Pi over HTTP. Idempotent / safe to re-enter. */
    private fun enterPiMode(ep: PiEndpoint) {
        _dataSource.value = DataSource.PI
        _piEndpoint.value = ep
        inverterJob?.cancel(); inverterJob = null
        // Release the BLE link so the Pi keeps it. Don't clear _samples/_bank — keep the last-good
        // values on screen until the first Pi poll lands (avoids a flash to "—").
        _monitored.value.forEach { repo.existingClient(it)?.disconnect() }
        startPiJob(ep)
    }

    /** Resume today's behavior: poll batteries over BLE and the inverter over the dongle directly. */
    private fun enterDirectMode() {
        val wasPi = inPiMode
        _dataSource.value = DataSource.DIRECT
        _piEndpoint.value = null
        piJob?.cancel(); piJob = null
        if (wasPi) {
            connectAll()
            if (inverterConfigured()) connectInverter(repo.inverterIp(), repo.inverterSerial())
        }
    }

    private fun startPiJob(ep: PiEndpoint) {
        piJob?.cancel()
        val pi = SolarPiClient(ep.host, ep.port)
        _inverterBusy.value = _inverter.value == null
        _inverterError.value = null
        piJob = viewModelScope.launch {
            var failures = 0
            while (isActive) {
                pi.battery().onSuccess { snap ->
                    if (snap.available) { // keep last-good battery data if the Pi's BMS is momentarily down
                        _samples.value = snap.samples
                        _bank.value = snap.bank
                    }
                }
                pi.current()
                    .onSuccess { reading ->
                        _inverter.value = reading
                        _inverterError.value = null
                        _inverterBusy.value = false
                        failures = 0
                    }
                    .onFailure {
                        // The Pi resolved but isn't answering (server down / wrong host) — after a few
                        // misses, drop it so we fall back to Direct rather than showing stale data forever.
                        if (++failures >= PI_MAX_FAILURES) discovery.notePiUnreachable()
                    }
                delay(PI_INTERVAL_MS)
            }
        }
    }

    /** User opt-out: when true, ignore any Pi and always read directly over BLE/dongle. */
    fun setForceDirect(value: Boolean) {
        repo.setForceDirect(value)
        _forceDirect.value = value // the init combine re-evaluates and switches modes
    }

    fun piManualHost(): String = repo.piManualHost()
    fun piManualPort(): Int = repo.piManualPort()

    /** Save + adopt a user-entered Pi host once it answers `/api/health`. */
    fun saveManualPi(host: String, port: Int) {
        if (_piManualBusy.value) return
        _piManualError.value = null
        _piManualBusy.value = true
        viewModelScope.launch {
            try {
                if (discovery.probeManual(host.trim(), port)) {
                    repo.setPiManual(host, port)
                    discovery.setManual(host.trim(), port)
                } else {
                    _piManualError.value = "No Solar Pi answered at $host:$port"
                }
            } finally {
                _piManualBusy.value = false
            }
        }
    }

    fun clearManualPi() {
        repo.clearPiManual()
        discovery.clearManual()
        _piManualError.value = null
    }

    // ---- monitoring --------------------------------------------------------

    fun addBattery(mac: String) {
        repo.addMac(mac)
        _monitored.value = repo.monitoredMacs()
        recomputeBank()
        connect(mac)
    }

    fun removeBattery(mac: String) {
        repo.existingClient(mac)?.disconnect()
        repo.removeMac(mac)
        latestSamples.remove(mac.uppercase())
        _monitored.value = repo.monitoredMacs()
        recomputeBank()
    }

    fun connect(mac: String) {
        if (inPiMode) return // the Pi owns the BLE link; don't fight it for the slot
        viewModelScope.launch {
            val client = client(mac)
            val st = client.state.value
            if (st !is ConnState.Connected && st !is ConnState.Live) {
                warmUp(mac) // brief scan so connectGatt latches quickly instead of doing a slow OS search
            }
            if (client.connect()) client.startStreaming()
        }
    }

    /** Scan until [mac] is seen (or 10s), then stop — leaves the device "warm" for a fast connect. */
    private suspend fun warmUp(mac: String) {
        runCatching {
            withTimeoutOrNull(10_000) {
                repo.scanner.scan().first { it.address.equals(mac, ignoreCase = true) }
            }
        }
    }

    fun disconnect(mac: String) {
        repo.existingClient(mac)?.disconnect()
    }

    fun connectAll() {
        _monitored.value.forEach { connect(it) }
    }

    /**
     * Force-refresh a battery whose telemetry has gone stale. The pack broadcast is unsolicited,
     * so a silently-dropped BLE link keeps showing the last sample; dropping and reopening the
     * connection resumes fresh broadcasts + cell reads.
     */
    fun refresh(mac: String) {
        if (inPiMode) return // Pi mode re-polls over HTTP on its own cadence
        viewModelScope.launch {
            repo.existingClient(mac)?.disconnect()
            delay(250) // let the GATT stack close before reopening
            connect(mac)
        }
    }

    fun refreshAll(macs: List<String>) {
        macs.forEach { refresh(it) }
    }

    // ---- scanning ----------------------------------------------------------

    fun startScan() {
        if (_scanning.value) return
        _scanResults.value = emptyList()
        _scanning.value = true
        scanJob = viewModelScope.launch {
            repo.scanner.scan()
                .catch { _scanning.value = false }
                .collect { found ->
                    val others = _scanResults.value.filterNot { it.address == found.address }
                    _scanResults.value = (others + found).sortedByDescending { it.rssi }
                }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _scanning.value = false
    }

    // ---- discovery / raw mode ---------------------------------------------

    fun openRaw(mac: String) {
        if (inPiMode) return // the sniffer needs the GATT slot the Pi is holding
        viewModelScope.launch {
            val client = client(mac)
            _rawClient.value = client
            client.connect()
        }
    }

    fun closeRaw() {
        _rawClient.value?.let { c ->
            // only disconnect if it is not also being monitored/streamed
            if (c.address !in _monitored.value) c.disconnect()
        }
        _rawClient.value = null
    }

    fun sendRaw(mac: String, bytes: ByteArray) {
        viewModelScope.launch { client(mac).sendRaw(bytes) }
    }

    // ---- inverter (WiFi/Solarman) ------------------------------------------

    fun inverterIp(): String = repo.inverterIp()
    fun inverterSerial(): String = repo.inverterSerial()

    // ---- inverter fault-code tables (assets/faults/*.json) ----------------
    private val faultBrands: List<FaultBrand> = FaultCatalog.load(app)
    fun inverterBrands(): List<FaultBrand> = faultBrands

    private val _inverterBrandId = MutableStateFlow(repo.inverterBrand())
    val inverterBrandId: StateFlow<String> = _inverterBrandId.asStateFlow()

    fun setInverterBrand(id: String) {
        repo.setInverterBrand(id)
        _inverterBrandId.value = id
    }

    /** Human text for an inverter fault code under the currently-selected brand. */
    fun faultLabel(code: Int): String {
        val brand = faultBrands.firstOrNull { it.id == _inverterBrandId.value }
        return brand?.text(code) ?: "Fault $code"
    }

    /** Save the dongle IP/serial and start polling the inverter every few seconds. */
    fun connectInverter(ip: String, serial: String) {
        repo.setInverter(ip, serial) // persist even in Pi mode so Direct works when the Pi goes away
        if (inPiMode) return // the Pi already serves inverter data; don't poll the dongle too
        val sn = serial.trim().toLongOrNull()
        if (ip.isBlank() || sn == null) {
            _inverterError.value = "Enter the dongle IP and its numeric serial number"
            return
        }
        inverterJob?.cancel()
        // Fresh attempt: clear any stale data/warning and show the connecting spinner.
        _inverter.value = null
        _inverterError.value = null
        _inverterBusy.value = true
        val client = InverterClient(ip.trim(), sn)
        inverterJob = viewModelScope.launch {
            var failures = 0
            // A read that fails outright, or comes back without plausible core data, must not blank
            // the screen — keep the last-good values and only surface an error if we never had any.
            fun onMiss(message: String?) {
                failures++
                when {
                    _inverter.value != null -> _inverterBusy.value = false
                    failures >= 2 -> {
                        _inverterError.value = message ?: "read failed"
                        _inverterBusy.value = false
                    }
                    else -> Unit
                }
            }
            while (isActive) {
                client.read()
                    .onSuccess { reading ->
                        if (SrneInverter.hasCore(reading.raw)) {
                            // Merge over the last-good registers so a dropped block (e.g. PV2 or L2
                            // from a flaky dongle read) carries forward instead of zeroing out.
                            val merged = SrneInverter.merge(_inverter.value?.raw, reading.raw)
                            _inverter.value = InverterReading(SrneInverter.decode(merged), merged)
                            _inverterError.value = null
                            failures = 0
                            _inverterBusy.value = false
                        } else {
                            onMiss("inverter returned incomplete data")
                        }
                    }
                    .onFailure { onMiss(it.message) }
                delay(INVERTER_INTERVAL_MS)
            }
        }
    }

    fun stopInverter() {
        inverterJob?.cancel()
        inverterJob = null
        _inverterBusy.value = false
    }

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
        inverterJob?.cancel()
        piJob?.cancel()
        discovery.stop()
    }

    private companion object {
        const val INVERTER_INTERVAL_MS = 8_000L
        const val PI_INTERVAL_MS = 3_000L
        const val PI_MAX_FAILURES = 3
    }
}
