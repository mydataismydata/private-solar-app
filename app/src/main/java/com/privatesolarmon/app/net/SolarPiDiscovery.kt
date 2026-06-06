package com.privatesolarmon.app.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A reachable Solar Pi, found either by mDNS or a user-entered host. */
data class PiEndpoint(val host: String, val port: Int, val source: Source) {
    enum class Source { MDNS, MANUAL }
}

/**
 * Finds a Solar Pi on the LAN two ways and exposes the best current [endpoint]:
 *  - mDNS auto-discovery of the `_solarpi._tcp` service the Pi advertises, and
 *  - a user-entered host, verified by probing `/api/health`.
 *
 * A manually-entered host wins over mDNS (explicit user intent). Discovery is best-effort —
 * multicast is unreliable on many Wi-Fi networks — so the manual path is the dependable fallback.
 */
class SolarPiDiscovery(context: Context) {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(NsdManager::class.java)
    private val wifi = appContext.getSystemService(WifiManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _endpoint = MutableStateFlow<PiEndpoint?>(null)
    val endpoint: StateFlow<PiEndpoint?> = _endpoint.asStateFlow()

    private val lock = Any()
    private var mdnsEndpoint: PiEndpoint? = null
    private var manualEndpoint: PiEndpoint? = null

    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolveInFlight = false

    private var manualHost: String? = null
    private var manualPort: Int = 8000
    private var manualWatch: Job? = null

    private fun recompute() {
        // Manual (explicit) beats mDNS (best-effort).
        _endpoint.value = manualEndpoint ?: mdnsEndpoint
    }

    // ---- mDNS --------------------------------------------------------------

    fun start() {
        synchronized(lock) {
            if (discoveryListener != null || nsd == null) return
            multicastLock = wifi?.createMulticastLock("solarpi-mdns")?.apply {
                setReferenceCounted(true)
                runCatching { acquire() }
            }
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onServiceFound(info: NsdServiceInfo) {
                    if (info.serviceType.contains(SERVICE_BASE)) resolve(info)
                }
                override fun onServiceLost(info: NsdServiceInfo) {
                    // A single dropped multicast packet shouldn't bounce us to Direct — confirm
                    // the Pi is really gone with one health probe before clearing.
                    val ep = mdnsEndpoint ?: return
                    scope.launch {
                        if (!SolarPiClient(ep.host, ep.port).health().getOrDefault(false)) {
                            synchronized(lock) { mdnsEndpoint = null; recompute() }
                        }
                    }
                }
            }
            discoveryListener = listener
            runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
        }
    }

    fun stop() {
        synchronized(lock) {
            discoveryListener?.let { runCatching { nsd?.stopServiceDiscovery(it) } }
            discoveryListener = null
            multicastLock?.let { runCatching { if (it.isHeld) it.release() } }
            multicastLock = null
        }
        manualWatch?.cancel()
        // The IO scope is intentionally left alive so the discovery object can be restarted.
    }

    private fun resolve(info: NsdServiceInfo) {
        synchronized(lock) {
            if (resolveInFlight) return // some OEM NSD stacks throw FAILURE_ALREADY_ACTIVE on concurrent resolves
            resolveInFlight = true
        }
        nsd?.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                synchronized(lock) { resolveInFlight = false }
            }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress
                synchronized(lock) {
                    resolveInFlight = false
                    if (host != null) {
                        mdnsEndpoint = PiEndpoint(host, serviceInfo.port, PiEndpoint.Source.MDNS)
                        recompute()
                    }
                }
            }
        })
    }

    // ---- manual fallback ---------------------------------------------------

    /** Probe a candidate host without committing to it (used by the settings "Connect" button). */
    suspend fun probeManual(host: String, port: Int = 8000): Boolean =
        SolarPiClient(host, port).health().getOrDefault(false)

    /** Adopt a user-entered host and keep it monitored so it recovers if the Pi reboots. */
    fun setManual(host: String, port: Int) {
        synchronized(lock) { manualHost = host; manualPort = port }
        manualWatch?.cancel()
        manualWatch = scope.launch {
            while (isActive) {
                val ok = SolarPiClient(host, port).health().getOrDefault(false)
                synchronized(lock) {
                    manualEndpoint = if (ok) PiEndpoint(host, port, PiEndpoint.Source.MANUAL) else null
                    recompute()
                }
                delay(MANUAL_PROBE_MS)
            }
        }
    }

    fun clearManual() {
        manualWatch?.cancel(); manualWatch = null
        synchronized(lock) { manualHost = null; manualEndpoint = null; recompute() }
    }

    /** Called by the poller after repeated HTTP failures — drop the active endpoint now so the
     *  app falls back to Direct immediately; mDNS re-resolve / the manual watcher will restore it. */
    fun notePiUnreachable() {
        synchronized(lock) {
            mdnsEndpoint = null
            manualEndpoint = null
            recompute()
        }
    }

    companion object {
        private const val SERVICE_BASE = "_solarpi._tcp"
        const val SERVICE_TYPE = "_solarpi._tcp." // NsdManager wants the trailing dot
        private const val MANUAL_PROBE_MS = 10_000L
    }
}
