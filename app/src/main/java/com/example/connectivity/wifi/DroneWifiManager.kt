package com.example.connectivity.wifi

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.connectivity.diagnostics.DiagnosticLogger
import com.example.connectivity.diagnostics.LogSeverity
import com.example.connectivity.diagnostics.LogSubsystem
import com.example.core.models.DroneFamily
import com.example.core.models.NetworkContext
import com.example.core.models.ScannedWifiNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket

enum class WifiConnectionStatus(val labelFr: String) {
    IDLE("Inactif"),
    SCANNING("Recherche des drones..."),
    CONNECTING("Connexion au point d'accès..."),
    CONNECTED("Connecté (sans Internet)"),
    LOST("Connexion perdue — reconnexion..."),
    UNAVAILABLE("Connexion refusée ou indisponible")
}

class DroneWifiManager(
    private val context: Context,
    private val logger: DiagnosticLogger
) {
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _scannedNetworks = MutableStateFlow<List<ScannedWifiNetwork>>(emptyList())
    val scannedNetworks: StateFlow<List<ScannedWifiNetwork>> = _scannedNetworks.asStateFlow()

    private val _connectionStatus = MutableStateFlow(WifiConnectionStatus.IDLE)
    val connectionStatus: StateFlow<WifiConnectionStatus> = _connectionStatus.asStateFlow()

    private val _activeNetworkContext = MutableStateFlow<NetworkContext?>(null)
    val activeNetworkContext: StateFlow<NetworkContext?> = _activeNetworkContext.asStateFlow()

    private val _reconnectAttempt = MutableStateFlow(0)
    val reconnectAttempt: StateFlow<Int> = _reconnectAttempt.asStateFlow()

    @Volatile
    var boundNetwork: Network? = null
        private set

    private var activeCallback: ConnectivityManager.NetworkCallback? = null
    private var lastRequestedSsid: String? = null
    private var lastRequestedPassphrase: String? = null
    private var reconnectAttemptCount = 0

    fun hasWifiScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    fun startWifiScan() {
        _connectionStatus.value = WifiConnectionStatus.SCANNING
        if (!hasWifiScanPermission()) {
            logger.log(
                LogSubsystem.NETWORK,
                LogSeverity.WARN,
                "Permissions Wi-Fi/Localisation manquantes pour scanner les SSID"
            )
            _connectionStatus.value = WifiConnectionStatus.IDLE
            return
        }

        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    runCatching { context.unregisterReceiver(this) }
                    readScanResults()
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    receiver,
                    IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                context.registerReceiver(
                    receiver,
                    IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                )
            }
            @Suppress("DEPRECATION")
            val started = wifiManager.startScan()
            if (!started) {
                readScanResults()
            }
            logger.log(LogSubsystem.NETWORK, LogSeverity.INFO, "Scan Wi-Fi lancé (started=$started)")
        } catch (e: SecurityException) {
            logger.log(LogSubsystem.NETWORK, LogSeverity.ERROR, "SecurityException scan Wi-Fi: ${e.message}")
        }
        readScanResults()
    }

    fun readScanResults() {
        if (!hasWifiScanPermission()) return
        try {
            @Suppress("MissingPermission")
            val results = wifiManager.scanResults.orEmpty()
            val mapped = results
                .filter { !it.SSID.isNullOrBlank() }
                .distinctBy { it.SSID }
                .map { sr ->
                    val family = matchFamilyBySsid(sr.SSID)
                    val isOpen = !sr.capabilities.contains("WPA", ignoreCase = true) &&
                        !sr.capabilities.contains("WEP", ignoreCase = true)
                    ScannedWifiNetwork(
                        ssid = sr.SSID,
                        bssid = sr.BSSID ?: "",
                        rssiDbm = sr.level,
                        frequencyMhz = sr.frequency,
                        matchedFamily = family,
                        isOpenNetwork = isOpen
                    )
                }
                .sortedWith(
                    compareByDescending<ScannedWifiNetwork> { it.matchedFamily != null }
                        .thenByDescending { it.rssiDbm }
                )
            _scannedNetworks.value = mapped
            if (_connectionStatus.value == WifiConnectionStatus.SCANNING) {
                _connectionStatus.value = if (boundNetwork != null) {
                    WifiConnectionStatus.CONNECTED
                } else {
                    WifiConnectionStatus.IDLE
                }
            }
        } catch (e: Exception) {
            logger.log(LogSubsystem.NETWORK, LogSeverity.ERROR, "Erreur lecture scanResults: ${e.message}")
        }
    }

    /**
     * Lit la connexion Wi-Fi système déjà établie manuellement par l'utilisateur (ex: Galaxy S9 déjà connecté à FLOW-UFO).
     */
    fun inspectCurrentSystemWifi() {
        val activeWifiNetwork = connectivityManager.allNetworks.firstOrNull { network ->
            val caps = connectivityManager.getNetworkCapabilities(network)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        if (activeWifiNetwork != null) {
            boundNetwork = activeWifiNetwork
            val ctx = buildNetworkContext(activeWifiNetwork, null)
            _activeNetworkContext.value = ctx
            _connectionStatus.value = WifiConnectionStatus.CONNECTED
            logger.log(
                LogSubsystem.NETWORK,
                LogSeverity.INFO,
                "Réseau Wi-Fi actif inspecté: ${ctx.ssid} (${ctx.phoneIp} -> ${ctx.gatewayIp})"
            )
        } else {
            logger.log(
                LogSubsystem.NETWORK,
                LogSeverity.WARN,
                "Aucun réseau Wi-Fi actif détecté par ConnectivityManager"
            )
        }
    }

    fun connectToDroneSsid(ssid: String, passphrase: String? = null) {
        lastRequestedSsid = ssid
        lastRequestedPassphrase = passphrase
        reconnectAttemptCount = 0
        requestWifiNetwork(ssid, passphrase)
    }

    private fun requestWifiNetwork(ssid: String, passphrase: String?) {
        disconnect()
        _connectionStatus.value = WifiConnectionStatus.CONNECTING

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            logger.log(
                LogSubsystem.NETWORK,
                LogSeverity.WARN,
                "Android < 10 : veuillez sélectionner $ssid dans les paramètres Wi-Fi système puis inspecter la connexion."
            )
            inspectCurrentSystemWifi()
            return
        }

        val specifierBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
        if (!passphrase.isNullOrBlank()) {
            specifierBuilder.setWpa2Passphrase(passphrase)
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifierBuilder.build())
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                boundNetwork = network
                reconnectAttemptCount = 0
                runCatching { connectivityManager.bindProcessToNetwork(network) }
                val ctx = buildNetworkContext(network, ssid)
                _activeNetworkContext.value = ctx
                _connectionStatus.value = WifiConnectionStatus.CONNECTED
                logger.log(
                    LogSubsystem.NETWORK,
                    LogSeverity.INFO,
                    "Connecté via WifiNetworkSpecifier à $ssid (IP=${ctx.phoneIp}, Passerelle=${ctx.gatewayIp})"
                )
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                super.onLinkPropertiesChanged(network, linkProperties)
                if (boundNetwork == network) {
                    _activeNetworkContext.value = buildNetworkContext(network, ssid, linkProperties)
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                if (boundNetwork == network) {
                    boundNetwork = null
                    runCatching { connectivityManager.bindProcessToNetwork(null) }
                    _connectionStatus.value = WifiConnectionStatus.LOST
                    logger.log(
                        LogSubsystem.NETWORK,
                        LogSeverity.WARN,
                        "Perte de connexion Wi-Fi avec $ssid"
                    )
                    scheduleControlledReconnect()
                }
            }

            override fun onUnavailable() {
                super.onUnavailable()
                boundNetwork = null
                _connectionStatus.value = WifiConnectionStatus.UNAVAILABLE
                logger.log(
                    LogSubsystem.NETWORK,
                    LogSeverity.ERROR,
                    "Réseau $ssid indisponible ou demande refusée par l'utilisateur"
                )
            }
        }

        activeCallback = callback
        try {
            connectivityManager.requestNetwork(request, callback, 20_000)
            logger.log(LogSubsystem.NETWORK, LogSeverity.INFO, "Demande WifiNetworkSpecifier envoyée pour $ssid")
        } catch (e: Exception) {
            _connectionStatus.value = WifiConnectionStatus.UNAVAILABLE
            logger.log(LogSubsystem.NETWORK, LogSeverity.ERROR, "Échec requestNetwork: ${e.message}")
        }
    }

    private fun scheduleControlledReconnect() {
        val targetSsid = lastRequestedSsid ?: return
        if (reconnectAttemptCount >= 3) {
            logger.log(
                LogSubsystem.NETWORK,
                LogSeverity.ERROR,
                "Arrêt des reconnexions automatiques après 3 tentatives pour $targetSsid"
            )
            _connectionStatus.value = WifiConnectionStatus.UNAVAILABLE
            return
        }
        reconnectAttemptCount++
        _reconnectAttempt.value = reconnectAttemptCount
        scope.launch {
            delay(2500L)
            if (_connectionStatus.value == WifiConnectionStatus.LOST) {
                logger.log(
                    LogSubsystem.NETWORK,
                    LogSeverity.INFO,
                    "Tentative de reconnexion #$reconnectAttemptCount vers $targetSsid"
                )
                requestWifiNetwork(targetSsid, lastRequestedPassphrase)
            }
        }
    }

    fun cancelReconnect() {
        lastRequestedSsid = null
        reconnectAttemptCount = 0
        _reconnectAttempt.value = 0
        if (_connectionStatus.value == WifiConnectionStatus.LOST || _connectionStatus.value == WifiConnectionStatus.CONNECTING) {
            _connectionStatus.value = WifiConnectionStatus.IDLE
        }
        logger.log(LogSubsystem.NETWORK, LogSeverity.INFO, "Reconnexion automatique annulée par le pilote")
    }

    fun disconnect() {
        lastRequestedSsid = null
        reconnectAttemptCount = 0
        _reconnectAttempt.value = 0
        activeCallback?.let { cb ->
            runCatching { connectivityManager.unregisterNetworkCallback(cb) }
        }
        activeCallback = null
        boundNetwork = null
        runCatching { connectivityManager.bindProcessToNetwork(null) }
        _activeNetworkContext.value = null
        _connectionStatus.value = WifiConnectionStatus.IDLE
    }

    fun bindSocket(socket: DatagramSocket) {
        val net = boundNetwork ?: return
        runCatching { net.bindSocket(socket) }.onFailure {
            Log.w("DroneWifiManager", "bindSocket UDP failed: ${it.message}")
        }
    }

    fun bindSocket(socket: Socket) {
        val net = boundNetwork ?: return
        runCatching { net.bindSocket(socket) }.onFailure {
            Log.w("DroneWifiManager", "bindSocket TCP failed: ${it.message}")
        }
    }

    private fun buildNetworkContext(
        network: Network,
        knownSsid: String?,
        providedLp: LinkProperties? = null
    ): NetworkContext {
        val lp = providedLp ?: connectivityManager.getLinkProperties(network)
        val ipv4 = lp?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress ?: "0.0.0.0"

        val gateway = lp?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway?.hostAddress
            ?: inferGatewayFromIp(ipv4)

        @Suppress("DEPRECATION")
        val wifiInfo: WifiInfo? = runCatching { wifiManager.connectionInfo }.getOrNull()
        val rawSsid = wifiInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        val resolvedSsid = knownSsid ?: rawSsid ?: "Réseau Wi-Fi Drone"

        return NetworkContext(
            ssid = resolvedSsid,
            bssid = wifiInfo?.bssid ?: "",
            phoneIp = ipv4,
            gatewayIp = gateway,
            rssiDbm = wifiInfo?.rssi ?: -60,
            frequencyMhz = wifiInfo?.frequency ?: 2412
        )
    }

    private fun inferGatewayFromIp(ip: String): String {
        val parts = ip.split(".")
        return if (parts.size == 4 && parts[0] != "0") {
            "${parts[0]}.${parts[1]}.${parts[2]}.1"
        } else {
            "192.168.169.1"
        }
    }

    companion object {
        fun matchFamilyBySsid(ssid: String): DroneFamily? {
            val upper = ssid.uppercase()
            return DroneFamily.entries
                .filter { it != DroneFamily.UNKNOWN }
                .firstOrNull { family ->
                    family.ssidPrefixes.any { prefix -> upper.startsWith(prefix.uppercase()) }
                }
        }
    }
}
