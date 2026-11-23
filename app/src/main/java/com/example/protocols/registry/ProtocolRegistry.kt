package com.example.protocols.registry

import com.example.connectivity.diagnostics.DiagnosticLogger
import com.example.connectivity.wifi.DroneWifiManager
import com.example.core.models.DroneCapabilities
import com.example.core.models.DroneFamily
import com.example.protocols.adapters.FlowUfoBl60xAdapter
import com.example.protocols.adapters.GenericFamilyAdapter
import com.example.protocols.api.DroneProtocolAdapter

class ProtocolRegistry(
    private val wifiManager: DroneWifiManager,
    private val logger: DiagnosticLogger,
    private val onConflictDetected: (Boolean) -> Unit = {},
    private val onPacketStats: (acked: Long, dropped: Long, bytesSize: Int) -> Unit = { _, _, _ -> }
) {
    fun createAdapter(family: DroneFamily): DroneProtocolAdapter {
        return when (family) {
            DroneFamily.FLOW_UFO_BL60X -> FlowUfoBl60xAdapter(
                wifiManager = wifiManager,
                logger = logger,
                onConflictDetected = onConflictDetected,
                onPacketStats = onPacketStats
            )
            DroneFamily.WIFI_UAV -> GenericFamilyAdapter(
                family = DroneFamily.WIFI_UAV,
                capabilities = DroneCapabilities(
                    supportsVideo = true,
                    supportsFlightControl = true,
                    supportsTakeOffLand = true,
                    supportsEmergencyStop = true,
                    supportsGyroCalibration = true,
                    supportsSpeedModes = true,
                    supportsDualCamera = true
                ),
                wifiManager = wifiManager,
                logger = logger,
                onPacketStats = onPacketStats
            )
            DroneFamily.KY_UFO_RTSP -> GenericFamilyAdapter(
                family = DroneFamily.KY_UFO_RTSP,
                capabilities = DroneCapabilities(
                    supportsVideo = true,
                    supportsFlightControl = true,
                    supportsTakeOffLand = true,
                    supportsEmergencyStop = true,
                    supportsGyroCalibration = true,
                    supportsSpeedModes = true,
                    supportsHeadless = true,
                    supportsFlip = true
                ),
                wifiManager = wifiManager,
                logger = logger,
                onPacketStats = onPacketStats
            )
            DroneFamily.JIELI_HF -> GenericFamilyAdapter(
                family = DroneFamily.JIELI_HF,
                capabilities = DroneCapabilities(
                    supportsVideo = true,
                    supportsFlightControl = true,
                    supportsTakeOffLand = true,
                    supportsEmergencyStop = true,
                    supportsDualCamera = true
                ),
                wifiManager = wifiManager,
                logger = logger,
                onPacketStats = onPacketStats
            )
            DroneFamily.LEWEI -> GenericFamilyAdapter(
                family = DroneFamily.LEWEI,
                capabilities = DroneCapabilities(
                    supportsVideo = true,
                    supportsFlightControl = true,
                    supportsTakeOffLand = true,
                    supportsEmergencyStop = true,
                    supportsSpeedModes = true
                ),
                wifiManager = wifiManager,
                logger = logger,
                onPacketStats = onPacketStats
            )
            else -> GenericFamilyAdapter(
                family = family,
                capabilities = DroneCapabilities(
                    supportsVideo = true,
                    supportsFlightControl = family != DroneFamily.UNKNOWN,
                    supportsTakeOffLand = family != DroneFamily.UNKNOWN,
                    supportsEmergencyStop = family != DroneFamily.UNKNOWN
                ),
                wifiManager = wifiManager,
                logger = logger,
                onPacketStats = onPacketStats
            )
        }
    }

    fun allSupportedFamilies(): List<DroneFamily> =
        DroneFamily.entries.filter { it != DroneFamily.UNKNOWN }
}
