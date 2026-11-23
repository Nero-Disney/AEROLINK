package com.example.protocols.api

import com.example.core.models.DetectionResult
import com.example.core.models.DroneCapabilities
import com.example.core.models.NetworkContext
import com.example.core.models.ProtocolDescriptor
import com.example.core.models.StickInput
import com.example.core.models.VideoFrame
import kotlinx.coroutines.flow.Flow

interface DroneVideoSource {
    suspend fun connect()
    suspend fun disconnect()
    fun frames(): Flow<VideoFrame>
    suspend fun switchCamera(cameraIndex: Int): Boolean = false
}

interface FlightController {
    suspend fun takeOff()
    suspend fun land()
    suspend fun emergencyStop()
    suspend fun calibrateGyro()
    suspend fun setSpeedMode(level: Int)
    suspend fun toggleHeadless(enabled: Boolean)
    suspend fun triggerFlip()
    suspend fun sendStickInput(input: StickInput)
}

interface DroneProtocolAdapter {
    val descriptor: ProtocolDescriptor
    val capabilities: DroneCapabilities
    val videoSource: DroneVideoSource
    val flightController: FlightController

    suspend fun identify(context: NetworkContext): DetectionResult
    suspend fun connect(gatewayIp: String, videoPort: Int, controlPort: Int)
    suspend fun disconnect()
}
