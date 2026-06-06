package com.privatesolarmon.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.privatesolarmon.app.bms.BmsSample
import com.privatesolarmon.app.bms.JbdFrameAssembler
import com.privatesolarmon.app.bms.JbdParser
import com.privatesolarmon.app.bms.JbdProtocol
import com.privatesolarmon.app.bms.PackBroadcastAssembler
import com.privatesolarmon.app.bms.PackFrame
import com.privatesolarmon.app.bms.PackTelemetry
import com.privatesolarmon.app.bms.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Connection lifecycle for a single battery. */
sealed interface ConnState {
    data object Idle : ConnState
    data object Connecting : ConnState
    /** Connected and notifications enabled, but no telemetry yet. */
    data class Connected(val name: String?) : ConnState
    /** Connected and publishing telemetry. */
    data class Live(val name: String?) : ConnState
    data class Error(val message: String) : ConnState
    data object Disconnected : ConnState
}

/** One line in the raw traffic log shown on the discovery/sniffer screen. */
data class LogLine(val dir: Dir, val text: String) {
    enum class Dir { TX, RX, INFO }
}

/**
 * Drives one ECO-LFP48100 (JBD UP16S015) battery over BLE.
 *
 * This unit does NOT answer the JBD 0x03 "basic info" command. Instead it emits an
 * unsolicited 64-byte pack-telemetry broadcast (parsed by [PackFrame]) carrying voltage,
 * current, SOC, temps, capacity and cell min/max. Per-cell voltages still come from the
 * standard JBD 0x04 read, which we poll on an interval. The two sources are merged into a
 * single [BmsSample] for the UI.
 *
 * NOTE: exercised against real hardware (no Android SDK on the build host); the pure parsers
 * are unit-tested. Iterate on this layer in-device.
 */
@SuppressLint("MissingPermission")
class BatteryClient(
    private val context: Context,
    val device: BluetoothDevice,
    private val scope: CoroutineScope,
) {
    val address: String get() = device.address
    val name: String? get() = runCatching { device.name }.getOrNull()

    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _sample = MutableStateFlow<BmsSample?>(null)
    val sample: StateFlow<BmsSample?> = _sample.asStateFlow()

    private val _rawLog = MutableStateFlow<List<LogLine>>(emptyList())
    val rawLog: StateFlow<List<LogLine>> = _rawLog.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var rxCharUuid: java.util.UUID? = null
    private var writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

    private val jbdAssembler = JbdFrameAssembler { msg -> log(LogLine.Dir.INFO, msg) }
    private val packAssembler = PackBroadcastAssembler { msg -> log(LogLine.Dir.INFO, msg) }
    private var lastPack: PackTelemetry? = null
    private var lastCells: List<Double> = emptyList()

    private val opMutex = Mutex()
    private var opResult: CompletableDeferred<Boolean>? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private val rawWaiters = mutableListOf<(ByteArray) -> Unit>()
    private var streamJob: Job? = null

    // ---- public API --------------------------------------------------------

    /** Connect, discover services, and enable notifications. Returns true when ready. */
    suspend fun connect(password: String? = null): Boolean {
        when (_state.value) {
            is ConnState.Connected, is ConnState.Live -> return true
            else -> {}
        }
        _state.value = ConnState.Connecting
        log(LogLine.Dir.INFO, "opening GATT to $address")
        val readyDeferred = CompletableDeferred<Boolean>()
        ready = readyDeferred
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)

        val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { readyDeferred.await() } ?: false
        if (!ok) {
            log(LogLine.Dir.INFO, "connect/setup failed or timed out")
            disconnect()
            return false
        }
        gatt?.requestMtu(247) // best effort; reassembly works at any MTU
        gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) // faster interval -> data sooner
        if (password != null && !authenticate(password)) {
            _state.value = ConnState.Error("authentication rejected")
            return false
        }
        return true
    }

    /** Poll the 0x04 cell read on an interval; pack data arrives via broadcast meanwhile. */
    fun startStreaming(intervalMs: Long = 5_000L) {
        if (streamJob?.isActive == true) return
        streamJob = scope.launch {
            while (isActive) {
                sendRaw(JbdProtocol.readCommand(JbdProtocol.REG_CELL_VOLTAGES))
                delay(intervalMs)
            }
        }
    }

    fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
    }

    /** Send an arbitrary command frame (used by the discovery screen for sniffing). */
    suspend fun sendRaw(bytes: ByteArray): Boolean = writeRaw(bytes)

    fun disconnect() {
        stopStreaming()
        val g = gatt
        runCatching { g?.disconnect() }
        runCatching { g?.close() }
        cleanup()
        _state.value = ConnState.Disconnected
    }

    fun clearLog() {
        _rawLog.value = emptyList()
    }

    // ---- internals ---------------------------------------------------------

    private fun publish() {
        val p = lastPack
        val sample = BmsSample(
            voltage = p?.voltage,
            current = p?.current,
            soc = p?.soc,
            soh = p?.soh,
            remainingAh = p?.remainingAh,
            fullAh = p?.fullAh,
            cycles = null, // offset not yet located in this BMS's frames
            parallelNum = p?.parallelNum,
            protectionNormal = p?.protectionNormal ?: true,
            cellVoltages = lastCells,
            tempsC = p?.temps ?: emptyList(),
            broadcastCellMax = p?.cellMax,
            broadcastCellMin = p?.cellMin,
        )
        _sample.value = sample
        if (sample.hasData) _state.value = ConnState.Live(name)
    }

    private suspend fun authenticate(password: String): Boolean {
        val ackDeferred = CompletableDeferred<ByteArray>()
        synchronized(rawWaiters) { rawWaiters.add { ackDeferred.complete(it) } }
        if (!writeRaw(JbdProtocol.authCommand(password))) return false
        val ack = withTimeoutOrNull(RESPONSE_TIMEOUT_MS) {
            var reply = ackDeferred.await()
            while (reply.size < 5 || reply[0].toInt() and 0xFF != 0xFF || reply[2].toInt() and 0xFF != 0x15) {
                val next = CompletableDeferred<ByteArray>()
                synchronized(rawWaiters) { rawWaiters.add { next.complete(it) } }
                reply = next.await()
            }
            reply
        }
        val accepted = ack != null && (ack[4].toInt() and 0xFF) == 0x00
        log(LogLine.Dir.INFO, if (accepted) "authenticated" else "auth failed / no ACK")
        return accepted
    }

    private suspend fun writeRaw(bytes: ByteArray): Boolean = opMutex.withLock {
        val g = gatt
        val tx = txChar
        if (g == null || tx == null) {
            log(LogLine.Dir.INFO, "write ignored: not connected")
            return@withLock false
        }
        val done = CompletableDeferred<Boolean>()
        opResult = done
        log(LogLine.Dir.TX, bytes.toHex())
        if (!writeCharCompat(g, tx, bytes)) {
            opResult = null
            return@withLock false
        }
        val ok = withTimeoutOrNull(OP_TIMEOUT_MS) { done.await() } ?: false
        opResult = null
        ok
    }

    @Suppress("DEPRECATION")
    private fun writeCharCompat(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, value, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            ch.writeType = writeType
            ch.value = value
            g.writeCharacteristic(ch)
        }

    @Suppress("DEPRECATION")
    private fun writeCccdEnable(g: BluetoothGatt, cccd: BluetoothGattDescriptor, indicate: Boolean) {
        val enable = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, enable)
        } else {
            cccd.value = enable
            g.writeDescriptor(cccd)
        }
    }

    private fun handleNotify(value: ByteArray) {
        log(LogLine.Dir.RX, value.toHex())
        // raw waiters (e.g. auth ACK, which is not a 0xDD frame)
        val waiters = synchronized(rawWaiters) { rawWaiters.toList().also { rawWaiters.clear() } }
        waiters.forEach { it(value) }

        // JBD command replies — we use the 0x04 cell-voltage read
        jbdAssembler.append(value)
        while (true) {
            val frame = jbdAssembler.next() ?: break
            if (frame.command == JbdProtocol.REG_CELL_VOLTAGES) {
                lastCells = JbdParser.decodeCellVoltages(frame.data)
                publish()
            }
        }

        // unsolicited pack-telemetry broadcast
        packAssembler.append(value)
        while (true) {
            val frame = packAssembler.next() ?: break
            runCatching { PackFrame.parse(frame) }.getOrNull()?.let {
                lastPack = it
                publish()
            }
        }
    }

    private fun cleanup() {
        gatt = null
        txChar = null
        rxCharUuid = null
        jbdAssembler.reset()
        packAssembler.reset()
        opResult?.let { if (!it.isCompleted) it.complete(false) }
        synchronized(rawWaiters) { rawWaiters.clear() }
    }

    private fun log(dir: LogLine.Dir, text: String) {
        _rawLog.update { (it + LogLine(dir, text)).takeLast(MAX_LOG) }
    }

    private val callback = object : android.bluetooth.BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    val started = g.discoverServices()
                    log(LogLine.Dir.INFO, "connected (status $status); discovering services (started=$started)")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log(LogLine.Dir.INFO, "disconnected (status $status)")
                    ready?.let { if (!it.isCompleted) it.complete(false) }
                    runCatching { g.close() }
                    cleanup()
                    _state.value = ConnState.Disconnected
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            // Dump the full GATT table — invaluable for probing unknown devices (e.g. the inverter).
            log(LogLine.Dir.INFO, "services discovered (status $status):")
            for (svc in g.services) {
                log(LogLine.Dir.INFO, "  svc ${shortUuid(svc.uuid)}")
                for (ch in svc.characteristics) {
                    log(LogLine.Dir.INFO, "    chr ${shortUuid(ch.uuid)} [${propsString(ch.properties)}]")
                }
            }
            val (rx, tx) = pickRxTx(g)
            if (rx == null || tx == null) {
                _state.value = ConnState.Error("no usable notify + write characteristic found")
                ready?.complete(false)
                return
            }
            rxCharUuid = rx.uuid
            txChar = tx
            writeType = if (tx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            log(LogLine.Dir.INFO, "using rx=${shortUuid(rx.uuid)} tx=${shortUuid(tx.uuid)}")

            val notifyOk = g.setCharacteristicNotification(rx, true)
            val cccd = rx.getDescriptor(BleUuids.CCCD)
            if (cccd == null) {
                // Some transparent modules notify without an explicit CCCD — proceed anyway.
                log(LogLine.Dir.INFO, "no CCCD on ${shortUuid(rx.uuid)}; proceeding (setNotify=$notifyOk)")
                _state.value = ConnState.Connected(name)
                ready?.complete(true)
                return
            }
            val indicate = rx.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 &&
                rx.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
            log(LogLine.Dir.INFO, "enabling ${if (indicate) "indications" else "notifications"} on ${shortUuid(rx.uuid)} (setNotify=$notifyOk)…")
            writeCccdEnable(g, cccd, indicate)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != BleUuids.CCCD) return
            val ok = status == BluetoothGatt.GATT_SUCCESS
            log(LogLine.Dir.INFO, if (ok) "notifications enabled" else "enable notifications failed ($status)")
            if (ok) _state.value = ConnState.Connected(name)
            ready?.complete(ok)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            opResult?.let { if (!it.isCompleted) it.complete(status == BluetoothGatt.GATT_SUCCESS) }
        }

        // API 33+
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (ch.uuid == rxCharUuid) handleNotify(value)
        }

        // API <= 32
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid == rxCharUuid) handleNotify(ch.value ?: ByteArray(0))
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log(LogLine.Dir.INFO, "MTU = $mtu (status $status)")
        }
    }

    /**
     * Auto-detect the notify (RX) and write (TX) characteristics of a transparent-UART style
     * device. Prefers a vendor service that exposes both; ignores the standard GATT services.
     * Works for the batteries (ff00/ff01/ff02) and the inverter module (ff00/ff05/ff06).
     */
    private fun pickRxTx(
        g: BluetoothGatt,
    ): Pair<BluetoothGattCharacteristic?, BluetoothGattCharacteristic?> {
        val standard = setOf("0x1800", "0x1801", "0x180a", "0x180f")
        fun canNotify(c: BluetoothGattCharacteristic) =
            c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        fun canWrite(c: BluetoothGattCharacteristic) =
            c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

        for (svc in g.services) {
            if (shortUuid(svc.uuid) in standard) continue
            val n = svc.characteristics.firstOrNull { canNotify(it) }
            val w = svc.characteristics.firstOrNull { canWrite(it) }
            if (n != null && w != null) return n to w
        }
        val chars = g.services.filterNot { shortUuid(it.uuid) in standard }.flatMap { it.characteristics }
        return chars.firstOrNull { canNotify(it) } to chars.firstOrNull { canWrite(it) }
    }

    private fun shortUuid(uuid: java.util.UUID): String {
        val s = uuid.toString()
        return if (s.endsWith("-0000-1000-8000-00805f9b34fb")) "0x" + s.substring(4, 8) else s
    }

    private fun propsString(p: Int): String = buildString {
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) append('R')
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) append('W')
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) append('w')
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) append('N')
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) append('I')
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val OP_TIMEOUT_MS = 5_000L
        private const val RESPONSE_TIMEOUT_MS = 4_000L
        private const val MAX_LOG = 400
    }
}
