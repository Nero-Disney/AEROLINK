package com.example.storage

import com.example.core.models.DroneCapabilities
import com.example.core.models.DroneFamily
import com.example.core.models.DroneProfile
import com.example.core.models.VideoTransportType
import com.example.storage.db.AeroLinkDatabase
import com.example.storage.db.BenchmarkReportEntity
import com.example.storage.db.DroneProfileEntity
import com.example.storage.db.FlightSessionEntity
import com.example.storage.db.RecordingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

class DroneRepository(private val db: AeroLinkDatabase) {

    val profiles: Flow<List<DroneProfile>> = db.droneProfileDao().observeAll().map { list ->
        list.map { it.toDomain() }
    }

    val sessions: Flow<List<FlightSessionEntity>> = db.flightSessionDao().observeAll()
    val recordings: Flow<List<RecordingEntity>> = db.recordingDao().observeAll()
    val benchmarks: Flow<List<BenchmarkReportEntity>> = db.benchmarkReportDao().observeAll()

    suspend fun ensureDefaultProfilesSeeded() {
        if (db.droneProfileDao().count() > 0) return
        val seedProfiles = listOf(
            DroneProfileEntity(
                commercialName = "FLOW-UFO (Modèle cible BL60x)",
                ssidPattern = "FLOW-UFO",
                familyId = DroneFamily.FLOW_UFO_BL60X.id,
                gatewayIp = "192.168.169.1",
                videoPort = 8800,
                controlPort = 8800,
                videoTransportName = VideoTransportType.UDP_MJPEG_PROPRIETARY.name,
                supportsVideo = true,
                supportsFlightControl = false, // À vérifier séparément sur matériel réel
                supportsTakeOffLand = true,
                supportsEmergencyStop = true,
                supportsGyroCalibration = true,
                supportsSpeedModes = true,
                supportsDualCamera = true,
                isProtocolVerified = false
            ),
            DroneProfileEntity(
                commercialName = "KY UFO / E58 / E88 (RTSP)",
                ssidPattern = "KY-",
                familyId = DroneFamily.KY_UFO_RTSP.id,
                gatewayIp = "192.168.1.1",
                videoPort = 7070,
                controlPort = 7099,
                videoTransportName = VideoTransportType.RTSP_H264.name,
                supportsVideo = true,
                supportsFlightControl = false,
                supportsTakeOffLand = true,
                supportsEmergencyStop = true,
                supportsGyroCalibration = true,
                supportsSpeedModes = true,
                supportsHeadless = true,
                supportsFlip = true,
                isProtocolVerified = false
            ),
            DroneProfileEntity(
                commercialName = "WiFi UAV / LSRC-S1S",
                ssidPattern = "WIFI_UAV",
                familyId = DroneFamily.WIFI_UAV.id,
                gatewayIp = "192.168.169.1",
                videoPort = 8800,
                controlPort = 8800,
                videoTransportName = VideoTransportType.UDP_MJPEG_PROPRIETARY.name,
                supportsVideo = true,
                supportsFlightControl = false,
                supportsTakeOffLand = true,
                supportsEmergencyStop = true,
                supportsSpeedModes = true,
                supportsDualCamera = true,
                isProtocolVerified = false
            ),
            DroneProfileEntity(
                commercialName = "Jieli / HF (CTP)",
                ssidPattern = "JIELI",
                familyId = DroneFamily.JIELI_HF.id,
                gatewayIp = "192.168.80.1",
                videoPort = 3333,
                controlPort = 3333,
                videoTransportName = VideoTransportType.JIELI_CTP_UDP.name,
                supportsVideo = true,
                supportsFlightControl = false,
                supportsTakeOffLand = true,
                supportsEmergencyStop = true,
                isProtocolVerified = false
            ),
            DroneProfileEntity(
                commercialName = "Lewei / VISUO (TCP H.264)",
                ssidPattern = "VISUO",
                familyId = DroneFamily.LEWEI.id,
                gatewayIp = "192.168.0.1",
                videoPort = 7060,
                controlPort = 50000,
                videoTransportName = VideoTransportType.TCP_H264_LEWEI.name,
                supportsVideo = true,
                supportsFlightControl = false,
                supportsTakeOffLand = true,
                supportsEmergencyStop = true,
                isProtocolVerified = false
            )
        )
        db.droneProfileDao().insertAll(seedProfiles)
    }

    suspend fun saveProfile(profile: DroneProfile): Long {
        return db.droneProfileDao().upsert(profile.toEntity())
    }

    suspend fun deleteProfile(id: Long) {
        db.droneProfileDao().deleteById(id)
    }

    suspend fun recordSession(session: FlightSessionEntity): Long {
        return db.flightSessionDao().insert(session)
    }

    suspend fun saveRecording(recording: RecordingEntity): Long {
        return db.recordingDao().insert(recording)
    }

    suspend fun deleteRecording(recording: RecordingEntity) {
        runCatching { File(recording.filePath).delete() }
        db.recordingDao().deleteById(recording.id)
    }

    suspend fun purgeAllUserData(recordingsDir: File, capturesDir: File) {
        db.recordingDao().clearAll()
        db.flightSessionDao().clearAll()
        db.diagnosticLogDao().clearAll()
        recordingsDir.listFiles()?.forEach { it.delete() }
        capturesDir.listFiles()?.forEach { it.delete() }
    }

    suspend fun saveBenchmark(report: BenchmarkReportEntity): Long {
        return db.benchmarkReportDao().insert(report)
    }

    suspend fun clearBenchmarks() {
        db.benchmarkReportDao().clearAll()
    }
}

private fun DroneProfileEntity.toDomain(): DroneProfile {
    val family = DroneFamily.entries.find { it.id == familyId } ?: DroneFamily.UNKNOWN
    val transport = runCatching { VideoTransportType.valueOf(videoTransportName) }
        .getOrDefault(family.videoTransport)
    return DroneProfile(
        id = id,
        commercialName = commercialName,
        ssidPattern = ssidPattern,
        family = family,
        gatewayIp = gatewayIp,
        videoPort = videoPort,
        controlPort = controlPort,
        videoTransport = transport,
        sendRateHz = sendRateHz,
        invertPitch = invertPitch,
        invertRoll = invertRoll,
        invertYaw = invertYaw,
        capabilities = DroneCapabilities(
            supportsVideo = supportsVideo,
            supportsFlightControl = supportsFlightControl,
            supportsTakeOffLand = supportsTakeOffLand,
            supportsEmergencyStop = supportsEmergencyStop,
            supportsGyroCalibration = supportsGyroCalibration,
            supportsSpeedModes = supportsSpeedModes,
            supportsHeadless = supportsHeadless,
            supportsFlip = supportsFlip,
            supportsDualCamera = supportsDualCamera,
            supportsCameraTilt = supportsCameraTilt
        ),
        isProtocolVerified = isProtocolVerified,
        lastConnectedMs = lastConnectedMs
    )
}

private fun DroneProfile.toEntity(): DroneProfileEntity {
    return DroneProfileEntity(
        id = id,
        commercialName = commercialName,
        ssidPattern = ssidPattern,
        familyId = family.id,
        gatewayIp = gatewayIp,
        videoPort = videoPort,
        controlPort = controlPort,
        videoTransportName = videoTransport.name,
        sendRateHz = sendRateHz,
        invertPitch = invertPitch,
        invertRoll = invertRoll,
        invertYaw = invertYaw,
        supportsVideo = capabilities.supportsVideo,
        supportsFlightControl = capabilities.supportsFlightControl,
        supportsTakeOffLand = capabilities.supportsTakeOffLand,
        supportsEmergencyStop = capabilities.supportsEmergencyStop,
        supportsGyroCalibration = capabilities.supportsGyroCalibration,
        supportsSpeedModes = capabilities.supportsSpeedModes,
        supportsHeadless = capabilities.supportsHeadless,
        supportsFlip = capabilities.supportsFlip,
        supportsDualCamera = capabilities.supportsDualCamera,
        supportsCameraTilt = capabilities.supportsCameraTilt,
        isProtocolVerified = isProtocolVerified,
        lastConnectedMs = lastConnectedMs
    )
}
