package com.example.protocols.adapters

import com.example.connectivity.diagnostics.DiagnosticLogger
import com.example.connectivity.diagnostics.LogSeverity
import com.example.connectivity.diagnostics.LogSubsystem
import com.example.connectivity.wifi.DroneWifiManager
import com.example.core.models.ConfidenceLevel
import com.example.core.models.DetectionResult
import com.example.core.models.DroneCapabilities
import com.example.core.models.DroneFamily
import com.example.core.models.NetworkContext
import com.example.core.models.ProtocolDescriptor
import com.example.core.models.StickInput
import com.example.core.models.VideoFrame
import com.example.protocols.api.DroneProtocolAdapter
import com.example.protocols.api.DroneVideoSource
import com.example.protocols.api.FlightController
import com.example.video.decoder.MjpegFrameDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Adaptateur générique extensible pour les familles RTSP, WiFi UAV, Jieli CTP, S2x, Lewei TCP, WiFi_CAM et X69/LG.
 * Chaque famille déclare explicitement ses capacités, ses ports et son transport réseau.
 */
class GenericFamilyAdapter(
    private val family: DroneFamily,
    override val capabilities: DroneCapabilities,
    private val wifiManager: DroneWifiManager,
    private val logger: DiagnosticLogger,
    private val onPacketStats: (acked: Long, dropped: Long, bytesSize: Int) -> Unit = { _, _, _ -> }
) : DroneProtocolAdapter {

    override val descriptor = ProtocolDescriptor(
        family = family,
        version = "${family.priority}-v1",
        videoEndpointHint = "${family.videoTransport.labelFr} @ ${family.defaultGatewayIp}:${family.defaultVideoPort}",
        flightPacketsVerified = false
    )

    private var gatewayIp: String = family.defaultGatewayIp
    private var videoPort: Int = family.defaultVideoPort
    private var controlPort: Int = family.defaultControlPort

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val frameFlow = MutableSharedFlow<VideoFrame>(extraBufferCapacity = 2)
    private val mjpegDecoder = MjpegFrameDecoder()

    private var streamJob: Job? = null
    private var udpSocket: DatagramSocket? = null
    private var tcpSocket: Socket? = null
    private var seq = 0L

    override val videoSource: DroneVideoSource = object : DroneVideoSource {
        override suspend fun connect() {
            startTransport()
        }

        override suspend fun disconnect() {
            stopTransport()
        }

        override fun frames(): Flow<VideoFrame> = frameFlow.asSharedFlow()
    }

    override val flightController: FlightController = object : FlightController {
        override suspend fun takeOff() = sendCommandTag("TAKEOFF", 0x01)
        override suspend fun land() = sendCommandTag("LAND", 0x02)
        override suspend fun emergencyStop() = sendCommandTag("EMERGENCY_STOP", 0x04)
        override suspend fun calibrateGyro() = sendCommandTag("CALIBRATE_GYRO", 0x08)
        override suspend fun setSpeedMode(level: Int) = sendCommandTag("SPEED_$level", 0x10 + level)
        override suspend fun toggleHeadless(enabled: Boolean) = sendCommandTag("HEADLESS_$enabled", 0x20)
        override suspend fun triggerFlip() = sendCommandTag("FLIP", 0x30)
        override suspend fun sendStickInput(input: StickInput) {
            val sock = udpSocket ?: return
            val packet = encodeStandardStickPacket(input)
            runCatching {
                val addr = InetAddress.getByName(gatewayIp)
                sock.send(DatagramPacket(packet, packet.size, addr, controlPort))
            }
        }
    }

    override suspend fun identify(context: NetworkContext): DetectionResult {
        return DetectionResult(
            ssid = context.ssid,
            phoneIp = context.phoneIp,
            gatewayIp = context.gatewayIp,
            family = family,
            videoTransport = family.videoTransport,
            commandsVerified = false,
            confidence = ConfidenceLevel.MEDIUM,
            probeSummary = "Adaptateur ${family.displayName} (${family.referenceSource})"
        )
    }

    override suspend fun connect(gatewayIp: String, videoPort: Int, controlPort: Int) {
        this.gatewayIp = gatewayIp
        this.videoPort = videoPort
        this.controlPort = controlPort
        videoSource.connect()
    }

    override suspend fun disconnect() {
        videoSource.disconnect()
    }

    private fun startTransport() {
        stopTransport()
        streamJob = scope.launch {
            logger.log(
                LogSubsystem.VIDEO,
                LogSeverity.INFO,
                "Connexion flux ${family.displayName} (${family.videoTransport.labelFr}) sur $gatewayIp:$videoPort"
            )
            when (family) {
                DroneFamily.KY_UFO_RTSP, DroneFamily.X69_LG -> runRtspProbeAndUdpLoop()
                DroneFamily.LEWEI -> runLeweiTcpLoop()
                else -> runUdpMjpegLoop()
            }
        }
    }

    private suspend fun runUdpMjpegLoop() {
        try {
            val socket = DatagramSocket().apply { soTimeout = 1000 }
            wifiManager.bindSocket(socket)
            udpSocket = socket
            val addr = InetAddress.getByName(gatewayIp)
            val trigger = byteArrayOf(0xEF.toByte(), 0x00, 0x04, 0x00)
            socket.send(DatagramPacket(trigger, trigger.size, addr, videoPort))

            val buf = ByteArray(2048)
            val acc = ByteArrayOutputStream(65536)
            var inFrame = false
            var acked = 0L
            var dropped = 0L

            while (currentCoroutineContext().isActive) {
                try {
                    val dp = DatagramPacket(buf, buf.size)
                    socket.receive(dp)
                    acked++
                    val len = dp.length
                    val soi = findMarker(buf, len, 0xD8.toByte())
                    val eoi = findMarker(buf, len, 0xD9.toByte())
                    if (soi >= 0) {
                        acc.reset()
                        inFrame = true
                        acc.write(buf, soi, len - soi)
                    } else if (inFrame) {
                        acc.write(buf, 0, len)
                    }
                    if (inFrame && eoi >= 0) {
                        val bytes = acc.toByteArray()
                        val bmp = mjpegDecoder.decode(bytes, bytes.size)
                        if (bmp != null) {
                            seq++
                            onPacketStats(acked, dropped, bytes.size)
                            val now = System.currentTimeMillis()
                            frameFlow.tryEmit(
                                VideoFrame(
                                    bitmap = bmp,
                                    receivedAtMs = now,
                                    estimatedCaptureMs = now - 35L,
                                    width = bmp.width,
                                    height = bmp.height,
                                    protocolId = family.id,
                                    sequenceNumber = seq
                                )
                            )
                        } else {
                            dropped++
                        }
                        acc.reset()
                        inFrame = false
                    }
                } catch (_: Exception) {
                    delay(150)
                }
            }
        } catch (e: Exception) {
            logger.log(LogSubsystem.VIDEO, LogSeverity.ERROR, "${family.displayName} erreur UDP: ${e.message}")
        }
    }

    private suspend fun runRtspProbeAndUdpLoop() {
        try {
            val socket = Socket()
            wifiManager.bindSocket(socket)
            socket.connect(InetSocketAddress(gatewayIp, videoPort), 2500)
            tcpSocket = socket
            val out = socket.getOutputStream()
            val uri = "rtsp://$gatewayIp:$videoPort/webcam"
            out.write("OPTIONS $uri RTSP/1.0\r\nCSeq: 1\r\n\r\n".toByteArray())
            out.write("DESCRIBE $uri RTSP/1.0\r\nCSeq: 2\r\nAccept: application/sdp\r\n\r\n".toByteArray())
            out.flush()
            logger.log(LogSubsystem.VIDEO, LogSeverity.INFO, "Handshake RTSP envoyé à $uri")
        } catch (e: Exception) {
            logger.log(LogSubsystem.VIDEO, LogSeverity.WARN, "RTSP $gatewayIp:$videoPort indisponible: ${e.message}")
        }
    }

    private suspend fun runLeweiTcpLoop() {
        try {
            val socket = Socket()
            wifiManager.bindSocket(socket)
            socket.connect(InetSocketAddress(gatewayIp, videoPort), 2500)
            tcpSocket = socket
            logger.log(LogSubsystem.VIDEO, LogSeverity.INFO, "Socket TCP Lewei ouvert sur $gatewayIp:$videoPort")
        } catch (e: Exception) {
            logger.log(LogSubsystem.VIDEO, LogSeverity.WARN, "TCP Lewei $gatewayIp:$videoPort indisponible: ${e.message}")
        }
    }

    private fun stopTransport() {
        streamJob?.cancel()
        streamJob = null
        runCatching { udpSocket?.close() }
        udpSocket = null
        runCatching { tcpSocket?.close() }
        tcpSocket = null
    }

    private fun sendCommandTag(name: String, code: Int) {
        logger.log(LogSubsystem.FLIGHT, LogSeverity.INFO, "${family.displayName}: commande $name (code=0x${code.toString(16)})")
    }

    private fun encodeStandardStickPacket(input: StickInput): ByteArray {
        fun b(v: Float) = ((v.coerceIn(-1f, 1f) * 127f) + 128f).toInt().coerceIn(1, 254).toByte()
        return byteArrayOf(0x66, b(input.roll), b(input.pitch), b(input.throttle), b(input.yaw), 0x00, 0x00, 0x99.toByte())
    }

    private fun findMarker(buf: ByteArray, length: Int, marker: Byte): Int {
        for (i in 0 until length - 1) {
            if (buf[i] == 0xFF.toByte() && buf[i + 1] == marker) return i
        }
        return -1
    }
}
