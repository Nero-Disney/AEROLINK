package com.example.core.models

import android.graphics.Bitmap

enum class VideoTransportType(val labelFr: String) {
    UDP_MJPEG_PROPRIETARY("UDP / MJPEG propriétaire"),
    RTSP_H264("RTSP / H.264"),
    RTSP_H265("RTSP / H.265 (HEVC)"),
    JIELI_CTP_UDP("CTP + UDP (Jieli)"),
    TCP_H264_LEWEI("H.264 sur TCP (Lewei)"),
    UDP_PROPRIETARY("UDP propriétaire")
}

enum class DroneFamily(
    val id: String,
    val priority: String,
    val displayName: String,
    val defaultGatewayIp: String,
    val defaultVideoPort: Int,
    val defaultControlPort: Int,
    val videoTransport: VideoTransportType,
    val ssidPrefixes: List<String>,
    val referenceSource: String
) {
    FLOW_UFO_BL60X(
        id = "flow_ufo_bl60x",
        priority = "P0",
        displayName = "FLOW-UFO / BL60x",
        defaultGatewayIp = "192.168.169.1",
        defaultVideoPort = 8800,
        defaultControlPort = 8800,
        videoTransport = VideoTransportType.UDP_MJPEG_PROPRIETARY,
        ssidPrefixes = listOf("FLOW-UFO", "FLOW_UFO", "FLOW-WIFI", "BL60"),
        referenceSource = "OpenIPC + TurboDrone"
    ),
    WIFI_UAV(
        id = "wifi_uav",
        priority = "P1",
        displayName = "WiFi UAV",
        defaultGatewayIp = "192.168.169.1",
        defaultVideoPort = 8800,
        defaultControlPort = 8800,
        videoTransport = VideoTransportType.UDP_MJPEG_PROPRIETARY,
        ssidPrefixes = listOf("WIFI_UAV", "WIFI-UAV", "UAV-", "LSRC"),
        referenceSource = "TurboDrone (wifi_uav)"
    ),
    KY_UFO_RTSP(
        id = "ky_ufo_rtsp",
        priority = "P2",
        displayName = "KY UFO / CooingDV RTSP",
        defaultGatewayIp = "192.168.1.1",
        defaultVideoPort = 7070,
        defaultControlPort = 7099,
        videoTransport = VideoTransportType.RTSP_H264,
        ssidPrefixes = listOf("KY-", "KY_UFO", "COOING", "WIFI_8K", "E58", "E88"),
        referenceSource = "OpenIPC + TurboDrone (cooingdv)"
    ),
    JIELI_HF(
        id = "jieli_hf",
        priority = "P3",
        displayName = "Jieli / HF (CTP)",
        defaultGatewayIp = "192.168.80.1",
        defaultVideoPort = 3333,
        defaultControlPort = 3333,
        videoTransport = VideoTransportType.JIELI_CTP_UDP,
        ssidPrefixes = listOf("JIELI", "HF-", "FPV-WIFI", "FH-"),
        referenceSource = "OpenIPC + TurboDrone (cooingdv_jieli)"
    ),
    S2X(
        id = "s2x",
        priority = "P4",
        displayName = "Famille S2x",
        defaultGatewayIp = "192.168.1.1",
        defaultVideoPort = 8080,
        defaultControlPort = 8081,
        videoTransport = VideoTransportType.UDP_PROPRIETARY,
        ssidPrefixes = listOf("S20", "S2X", "S2-"),
        referenceSource = "TurboDrone (s2x)"
    ),
    LEWEI(
        id = "lewei",
        priority = "P5",
        displayName = "Lewei / VISUO",
        defaultGatewayIp = "192.168.0.1",
        defaultVideoPort = 7060,
        defaultControlPort = 50000,
        videoTransport = VideoTransportType.TCP_H264_LEWEI,
        ssidPrefixes = listOf("VISUO", "LEWEI", "LW-", "XS8"),
        referenceSource = "OpenIPC (Lewei)"
    ),
    WIFI_CAM(
        id = "wifi_cam",
        priority = "P6",
        displayName = "WiFi_CAM",
        defaultGatewayIp = "192.168.4.1",
        defaultVideoPort = 8080,
        defaultControlPort = 8080,
        videoTransport = VideoTransportType.UDP_PROPRIETARY,
        ssidPrefixes = listOf("WIFI_CAM", "WIFICAM", "CAM-"),
        referenceSource = "TurboDrone (wifi_cam)"
    ),
    X69_LG(
        id = "x69_lg",
        priority = "P7",
        displayName = "X69 / LG (H.265)",
        defaultGatewayIp = "192.168.1.1",
        defaultVideoPort = 554,
        defaultControlPort = 7099,
        videoTransport = VideoTransportType.RTSP_H265,
        ssidPrefixes = listOf("X69", "LG-", "K417"),
        referenceSource = "TurboDrone (x69_lg)"
    ),
    UNKNOWN(
        id = "unknown",
        priority = "--",
        displayName = "Protocole non identifié",
        defaultGatewayIp = "192.168.169.1",
        defaultVideoPort = 8800,
        defaultControlPort = 8800,
        videoTransport = VideoTransportType.UDP_MJPEG_PROPRIETARY,
        ssidPrefixes = emptyList(),
        referenceSource = "Sonde réseau"
    )
}

data class DroneCapabilities(
    val supportsVideo: Boolean = true,
    val supportsFlightControl: Boolean = false,
    val supportsTakeOffLand: Boolean = false,
    val supportsEmergencyStop: Boolean = false,
    val supportsGyroCalibration: Boolean = false,
    val supportsSpeedModes: Boolean = false,
    val supportsHeadless: Boolean = false,
    val supportsFlip: Boolean = false,
    val supportsDualCamera: Boolean = false,
    val supportsCameraTilt: Boolean = false,
    val supportsAltitudeTelemetry: Boolean = false,
    val supportsBatteryTelemetry: Boolean = false,
    val supportsGamepad: Boolean = true
)

data class ProtocolDescriptor(
    val family: DroneFamily,
    val version: String,
    val videoEndpointHint: String,
    val flightPacketsVerified: Boolean
)

data class ScannedWifiNetwork(
    val ssid: String,
    val bssid: String,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val matchedFamily: DroneFamily?,
    val isOpenNetwork: Boolean
)

data class NetworkContext(
    val ssid: String,
    val bssid: String = "",
    val phoneIp: String = "0.0.0.0",
    val gatewayIp: String = "192.168.169.1",
    val subnetMask: String = "255.255.255.0",
    val rssiDbm: Int = -100,
    val frequencyMhz: Int = 2412,
    val openTcpPorts: List<Int> = emptyList(),
    val responsiveUdpPorts: List<Int> = emptyList(),
    val rtspAvailable: Boolean = false,
    val httpStatsAvailable: Boolean = false
)

enum class ConfidenceLevel(val labelFr: String) {
    HIGH("Élevée"),
    MEDIUM("Moyenne"),
    UNCERTAIN("Incertaine — choix manuel requis")
}

data class DetectionResult(
    val ssid: String,
    val phoneIp: String,
    val gatewayIp: String,
    val family: DroneFamily,
    val videoTransport: VideoTransportType,
    val commandsVerified: Boolean,
    val confidence: ConfidenceLevel,
    val probeSummary: String,
    val candidateFamilies: List<DroneFamily> = emptyList(),
    val cameraBusy: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis()
)

data class DroneProfile(
    val id: Long = 0,
    val commercialName: String,
    val ssidPattern: String,
    val family: DroneFamily,
    val gatewayIp: String,
    val videoPort: Int,
    val controlPort: Int,
    val videoTransport: VideoTransportType,
    val sendRateHz: Int = 25,
    val invertPitch: Boolean = false,
    val invertRoll: Boolean = false,
    val invertYaw: Boolean = false,
    val capabilities: DroneCapabilities = DroneCapabilities(),
    val isProtocolVerified: Boolean = false,
    val lastConnectedMs: Long = 0L
)

enum class FlightState(val labelFr: String, val isAirborneOrActive: Boolean) {
    Disconnected("Déconnecté", false),
    Connected("Connecté (Vidéo)", false),
    Ready("Prêt au vol", false),
    Arming("Armement...", false),
    Flying("En vol", true),
    Landing("Atterrissage", true),
    Emergency("ARRÊT D'URGENCE", false),
    Fault("Anomalie sécurité", false)
}

data class StickInput(
    val throttle: Float = 0f, // -1..1 (vertical left)
    val yaw: Float = 0f,      // -1..1 (horizontal left)
    val pitch: Float = 0f,    // -1..1 (vertical right)
    val roll: Float = 0f,     // -1..1 (horizontal right)
    val timestampMs: Long = System.currentTimeMillis()
)

data class VideoFrame(
    val bitmap: Bitmap,
    val receivedAtMs: Long,
    val estimatedCaptureMs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int = 0,
    val protocolId: String,
    val sequenceNumber: Long
)

data class TelemetrySnapshot(
    val isConnected: Boolean = false,
    val isVideoStreaming: Boolean = false,
    val sourceLabel: String = "Aucun flux",
    val ssid: String = "--",
    val rssiDbm: Int = 0,
    val videoFps: Float = 0f,
    val latencyMs: Long = 0L,
    val medianLatencyMs: Long = 0L,
    val p95LatencyMs: Long = 0L,
    val droppedFrames: Long = 0L,
    val totalFrames: Long = 0L,
    val ackedPackets: Long = 0L,
    val rxKbps: Int = 0,
    val inferenceFps: Float = 0f,
    val inferenceLatencyMs: Long = 0L,
    val memoryMb: Int = 0,
    val thermalStatusLabel: String = "Nominal",
    val packetTxHz: Int = 0,
    val speedMode: Int = 1,
    val headlessEnabled: Boolean = false,
    val activeCameraIndex: Int = 0,
    val gamepadConnected: Boolean = false,
    val singleClientConflictWarning: Boolean = false
)
