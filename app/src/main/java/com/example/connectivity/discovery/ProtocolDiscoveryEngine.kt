package com.example.connectivity.discovery

import com.example.connectivity.diagnostics.DiagnosticLogger
import com.example.connectivity.diagnostics.LogSeverity
import com.example.connectivity.diagnostics.LogSubsystem
import com.example.connectivity.wifi.DroneWifiManager
import com.example.core.models.ConfidenceLevel
import com.example.core.models.DetectionResult
import com.example.core.models.DroneFamily
import com.example.core.models.NetworkContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Procédure d'identification non destructive (Section 3 du Plan Directeur).
 * Aucun paquet de commande moteur ou de vol n'est envoyé durant la découverte.
 */
class ProtocolDiscoveryEngine(
    private val wifiManager: DroneWifiManager,
    private val logger: DiagnosticLogger
) {
    suspend fun discover(baseContext: NetworkContext): DetectionResult = withContext(Dispatchers.IO) {
        val gateway = baseContext.gatewayIp
        val openTcp = mutableListOf<Int>()
        val responsiveUdp = mutableListOf<Int>()

        logger.log(
            LogSubsystem.NETWORK,
            LogSeverity.INFO,
            "Début de la sonde protocolaire non destructive sur $gateway (SSID=${baseContext.ssid})"
        )

        // 1. Sondes TCP sûres (RTSP 7070/554, Lewei 7060, HTTP diagnostic 80)
        val tcpCandidates = listOf(7070, 554, 7060, 80, 8080)
        for (port in tcpCandidates) {
            if (probeTcpPort(gateway, port, timeoutMs = 450)) {
                openTcp.add(port)
            }
        }

        // 2. Vérification RTSP OPTIONS si port 7070 ou 554 ouvert
        val rtspOk = when {
            7070 in openTcp -> checkRtspOptions(gateway, 7070)
            554 in openTcp -> checkRtspOptions(gateway, 554)
            else -> false
        }

        // 3. Sondes UDP passives / vidéo non-vol (ports FLOW-UFO 8800/8801, Jieli 3333, KY heartbeat 7099)
        if (probeFlowUfoVideoPort(gateway, 8800)) responsiveUdp.add(8800)
        if (probeFlowUfoVideoPort(gateway, 8801)) responsiveUdp.add(8801)
        if (probeUdpEcho(gateway, 3333, byteArrayOf(0x43, 0x54, 0x50, 0x3A))) responsiveUdp.add(3333)
        if (probeUdpEcho(gateway, 7099, byteArrayOf(0x01, 0x01))) responsiveUdp.add(7099)

        val enriched = baseContext.copy(
            openTcpPorts = openTcp,
            responsiveUdpPorts = responsiveUdp,
            rtspAvailable = rtspOk,
            httpStatsAvailable = 80 in openTcp
        )

        val result = classifyFamily(enriched)
        logger.log(
            LogSubsystem.NETWORK,
            LogSeverity.INFO,
            "Résultat détection: ${result.family.displayName} (confiance=${result.confidence.labelFr})",
            mapOf(
                "ssid" to result.ssid,
                "gateway" to result.gatewayIp,
                "tcp" to openTcp.joinToString(),
                "udp" to responsiveUdp.joinToString(),
                "rtsp" to rtspOk
            )
        )
        result
    }

    private fun probeTcpPort(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                wifiManager.bindSocket(socket)
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun checkRtspOptions(host: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                wifiManager.bindSocket(socket)
                socket.soTimeout = 700
                socket.connect(InetSocketAddress(host, port), 600)
                val out = OutputStreamWriter(socket.getOutputStream())
                out.write("OPTIONS rtsp://$host:$port/webcam RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: AeroLink\r\n\r\n")
                out.flush()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val statusLine = reader.readLine() ?: ""
                statusLine.contains("RTSP/1.0", ignoreCase = true)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun probeFlowUfoVideoPort(host: String, port: Int): Boolean {
        return try {
            DatagramSocket().use { socket ->
                wifiManager.bindSocket(socket)
                socket.soTimeout = 450
                val addr = InetAddress.getByName(host)
                // Trame d'initialisation vidéo FLOW-UFO/BL60x (sans commande moteur)
                val probe = byteArrayOf(0xEF.toByte(), 0x00, 0x04, 0x00)
                socket.send(DatagramPacket(probe, probe.size, addr, port))
                val buf = ByteArray(1500)
                val rx = DatagramPacket(buf, buf.size)
                socket.receive(rx)
                rx.length > 0
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun probeUdpEcho(host: String, port: Int, payload: ByteArray): Boolean {
        return try {
            DatagramSocket().use { socket ->
                wifiManager.bindSocket(socket)
                socket.soTimeout = 350
                val addr = InetAddress.getByName(host)
                socket.send(DatagramPacket(payload, payload.size, addr, port))
                val buf = ByteArray(1024)
                val rx = DatagramPacket(buf, buf.size)
                socket.receive(rx)
                rx.length > 0
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun classifyFamily(ctx: NetworkContext): DetectionResult {
        val ssidMatch = DroneWifiManager.matchFamilyBySsid(ctx.ssid)
        val gw = ctx.gatewayIp

        // Priorité aux preuves réseau réelles avant le simple nom commercial SSID
        val (family, confidence, summary) = when {
            (8800 in ctx.responsiveUdpPorts || 8801 in ctx.responsiveUdpPorts) && gw == "192.168.169.1" -> {
                Triple(
                    if (ssidMatch == DroneFamily.WIFI_UAV) DroneFamily.WIFI_UAV else DroneFamily.FLOW_UFO_BL60X,
                    ConfidenceLevel.HIGH,
                    "Flux UDP MJPEG détecté sur port(s) ${ctx.responsiveUdpPorts.joinToString()} @ $gw"
                )
            }
            ctx.rtspAvailable || 7070 in ctx.openTcpPorts -> {
                val f = if (554 in ctx.openTcpPorts && ssidMatch == DroneFamily.X69_LG) {
                    DroneFamily.X69_LG
                } else {
                    DroneFamily.KY_UFO_RTSP
                }
                Triple(
                    f,
                    ConfidenceLevel.HIGH,
                    "Serveur RTSP confirmé sur TCP ${ctx.openTcpPorts.joinToString()} @ $gw"
                )
            }
            7060 in ctx.openTcpPorts || gw == "192.168.0.1" -> {
                Triple(
                    DroneFamily.LEWEI,
                    if (7060 in ctx.openTcpPorts) ConfidenceLevel.HIGH else ConfidenceLevel.MEDIUM,
                    "Passerelle Lewei ($gw) — TCP open: ${ctx.openTcpPorts.joinToString()}"
                )
            }
            3333 in ctx.responsiveUdpPorts || gw == "192.168.80.1" -> {
                Triple(
                    DroneFamily.JIELI_HF,
                    if (3333 in ctx.responsiveUdpPorts) ConfidenceLevel.HIGH else ConfidenceLevel.MEDIUM,
                    "Protocole Jieli CTP ($gw)"
                )
            }
            ssidMatch != null && gw == ssidMatch.defaultGatewayIp -> {
                Triple(
                    ssidMatch,
                    ConfidenceLevel.MEDIUM,
                    "Correspondance SSID + passerelle $gw (flux vidéo à valider en direct)"
                )
            }
            ssidMatch != null -> {
                Triple(
                    ssidMatch,
                    ConfidenceLevel.UNCERTAIN,
                    "SSID évocateur mais réponse port non confirmée — vérification manuelle recommandée"
                )
            }
            else -> {
                Triple(
                    DroneFamily.UNKNOWN,
                    ConfidenceLevel.UNCERTAIN,
                    "Aucune signature protocolaire détectée sur $gw — sélectionnez une famille manuellement"
                )
            }
        }

        val candidates = mutableListOf<DroneFamily>()
        if (ssidMatch != null && ssidMatch != DroneFamily.UNKNOWN) candidates.add(ssidMatch)
        if (gw == "192.168.169.1") {
            if (DroneFamily.FLOW_UFO_BL60X !in candidates) candidates.add(DroneFamily.FLOW_UFO_BL60X)
            if (DroneFamily.WIFI_UAV !in candidates) candidates.add(DroneFamily.WIFI_UAV)
        } else if (gw == "192.168.1.1") {
            if (DroneFamily.KY_UFO_RTSP !in candidates) candidates.add(DroneFamily.KY_UFO_RTSP)
            if (DroneFamily.S2X !in candidates) candidates.add(DroneFamily.S2X)
            if (DroneFamily.X69_LG !in candidates) candidates.add(DroneFamily.X69_LG)
        }

        return DetectionResult(
            ssid = ctx.ssid,
            phoneIp = ctx.phoneIp,
            gatewayIp = gw,
            family = family,
            videoTransport = family.videoTransport,
            commandsVerified = false, // Toujours à vérifier séparément (Section 3 & 9.3)
            confidence = confidence,
            probeSummary = summary,
            candidateFamilies = if (confidence == ConfidenceLevel.HIGH) emptyList() else candidates.distinct()
        )
    }
}
