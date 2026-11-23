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
import java.net.SocketTimeoutException

/**
 * Adaptateur P0 : FLOW-UFO / BL60x (192.168.169.1, UDP 8800/8801, MJPEG fragmenté + ACK).
 * Implémentation propre basée sur les spécifications publiques OpenIPC / TurboDrone.
 */
class FlowUfoBl60xAdapter(
    private val wifiManager: DroneWifiManager,
    private val logger: DiagnosticLogger,
    private val onConflictDetected: (Boolean) -> Unit = {},
    private val onPacketStats: (acked: Long, dropped: Long, bytesSize: Int) -> Unit = { _, _, _ -> }
) : DroneProtocolAdapter {

    override val descriptor = ProtocolDescriptor(
        family = DroneFamily.FLOW_UFO_BL60X,
        version = "BL60x-UDP-v1",
        videoEndpointHint = "flowufo://192.168.169.1:8800",
        flightPacketsVerified = false
    )

    override val capabilities = DroneCapabilities(
        supportsVideo = true,
        supportsFlightControl = true,
        supportsTakeOffLand = true,
        supportsEmergencyStop = true,
        supportsGyroCalibration = true,
        supportsSpeedModes = true,
        supportsHeadless = true,
        supportsFlip = false,
        supportsDualCamera = true,
        supportsCameraTilt = false,
        supportsAltitudeTelemetry = false,
        supportsBatteryTelemetry = false,
        supportsGamepad = true
    )

    private var gatewayIp: String = DroneFamily.FLOW_UFO_BL60X.defaultGatewayIp
    private var videoPort: Int = DroneFamily.FLOW_UFO_BL60X.defaultVideoPort
    private var controlPort: Int = DroneFamily.FLOW_UFO_BL60X.defaultControlPort

    private val decoder = MjpegFrameDecoder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val frameFlow = MutableSharedFlow<VideoFrame>(extraBufferCapacity = 2)

    private var rxJob: Job? = null
    private var keepAliveJob: Job? = null
    private var videoSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var ackedPackets = 0L
    private var droppedFrames = 0L
    private var frameSeq = 0L

    @Volatile
    private var currentSpeedLevel = 1

    @Volatile
    private var headlessFlag = false

    override val videoSource: DroneVideoSource = object : DroneVideoSource {
        override suspend fun connect() {
            startUdpVideoStream()
        }

        override suspend fun disconnect() {
            stopUdpVideoStream()
        }

        override fun frames(): Flow<VideoFrame> = frameFlow.asSharedFlow()

        override suspend fun switchCamera(cameraIndex: Int): Boolean {
            // Commande switch caméra sur flux BL60x
            val cmd = byteArrayOf(0xEF.toByte(), 0x01, 0x05, cameraIndex.toByte())
            return sendUdpPacket(videoSocket, gatewayIp, videoPort, cmd)
        }
    }

    override val flightController: FlightController = object : FlightController {
        override suspend fun takeOff() {
            sendControlFrame(flagMask = 0x01)
            logger.log(LogSubsystem.FLIGHT, LogSeverity.INFO, "FLOW-UFO: commande Décollage émise")
        }

        override suspend fun land() {
            sendControlFrame(flagMask = 0x02)
            logger.log(LogSubsystem.FLIGHT, LogSeverity.INFO, "FLOW-UFO: commande Atterrissage normal émise")
        }

        override suspend fun emergencyStop() {
            sendControlFrame(flagMask = 0x04)
            logger.log(LogSubsystem.FLIGHT, LogSeverity.WARN, "FLOW-UFO: ARRÊT D'URGENCE moteurs émis")
        }

        override suspend fun calibrateGyro() {
            sendControlFrame(flagMask = 0x08)
            logger.log(LogSubsystem.FLIGHT, LogSeverity.INFO, "FLOW-UFO: Calibration gyroscope émise")
        }

        override suspend fun setSpeedMode(level: Int) {
            currentSpeedLevel = level.coerceIn(1, 3)
        }

        override suspend fun toggleHeadless(enabled: Boolean) {
            headlessFlag = enabled
        }

        override suspend fun triggerFlip() {
            // Non activé tant que non vérifié sur cette variante
        }

        override suspend fun sendStickInput(input: StickInput) {
            sendControlFrame(
                roll = input.roll,
                pitch = input.pitch,
                throttle = input.throttle,
                yaw = input.yaw,
                flagMask = 0x00
            )
        }
    }

    override suspend fun identify(context: NetworkContext): DetectionResult {
        val confidence = if (context.gatewayIp == "192.168.169.1" &&
            (8800 in context.responsiveUdpPorts || 8801 in context.responsiveUdpPorts)
        ) ConfidenceLevel.HIGH else ConfidenceLevel.MEDIUM

        return DetectionResult(
            ssid = context.ssid,
            phoneIp = context.phoneIp,
            gatewayIp = context.gatewayIp,
            family = DroneFamily.FLOW_UFO_BL60X,
            videoTransport = descriptor.family.videoTransport,
            commandsVerified = false,
            confidence = confidence,
            probeSummary = "Adaptateur FLOW-UFO/BL60x prêt sur ${context.gatewayIp}:8800"
        )
    }

    override suspend fun connect(gatewayIp: String, videoPort: Int, controlPort: Int) {
        this.gatewayIp = gatewayIp
        this.videoPort = videoPort
        this.controlPort = controlPort
        controlSocket = DatagramSocket().also { wifiManager.bindSocket(it) }
        videoSource.connect()
    }

    override suspend fun disconnect() {
        videoSource.disconnect()
        runCatching { controlSocket?.close() }
        controlSocket = null
    }

    private fun startUdpVideoStream() {
        stopUdpVideoStream()
        ackedPackets = 0L
        droppedFrames = 0L
        frameSeq = 0L
        onConflictDetected(false)

        val socket = DatagramSocket().apply {
            soTimeout = 800
            receiveBufferSize = 256 * 1024
        }
        wifiManager.bindSocket(socket)
        videoSocket = socket

        // Boucle d'initialisation & keep-alive vidéo UDP (non-vol)
        keepAliveJob = scope.launch {
            val initPacket = byteArrayOf(0xEF.toByte(), 0x00, 0x04, 0x00)
            val ackPacket = byteArrayOf(0xEF.toByte(), 0x02, 0x04, 0x00)
            sendUdpPacket(socket, gatewayIp, videoPort, initPacket)
            while (isActive) {
                delay(750L)
                sendUdpPacket(socket, gatewayIp, videoPort, ackPacket)
            }
        }

        // Réception et réassemblage des fragments MJPEG par marqueurs SOI (FF D8) / EOI (FF D9)
        rxJob = scope.launch {
            val packetBuf = ByteArray(2048)
            val jpegAccumulator = ByteArrayOutputStream(64 * 1024)
            var inFrame = false
            var consecutiveTimeouts = 0

            logger.log(
                LogSubsystem.VIDEO,
                LogSeverity.INFO,
                "Récepteur UDP FLOW-UFO démarré ($gatewayIp:$videoPort)"
            )

            while (isActive) {
                try {
                    val dp = DatagramPacket(packetBuf, packetBuf.size)
                    socket.receive(dp)
                    consecutiveTimeouts = 0
                    onConflictDetected(false)
                    ackedPackets++

                    val len = dp.length
                    if (len <= 2) continue

                    // Extraction tolérante : saute un éventuel en-tête propriétaire (2 à 8 octets) jusqu'à SOI FF D8
                    val soiOffset = findJpegMarker(packetBuf, len, 0xD8.toByte())
                    val eoiOffset = findJpegMarker(packetBuf, len, 0xD9.toByte())

                    if (soiOffset >= 0) {
                        if (inFrame && jpegAccumulator.size() > 0) {
                            droppedFrames++
                        }
                        jpegAccumulator.reset()
                        inFrame = true
                        jpegAccumulator.write(packetBuf, soiOffset, len - soiOffset)
                    } else if (inFrame) {
                        // Ignore un en-tête court de fragmentation si présent (typiquement 4 octets sur BL60x)
                        val payloadStart = if (len > 4 && packetBuf[0] == 0xEF.toByte()) 4 else 0
                        jpegAccumulator.write(packetBuf, payloadStart, len - payloadStart)
                    }

                    if (inFrame && eoiOffset >= 0) {
                        val rawJpeg = jpegAccumulator.toByteArray()
                        val endTrimmed = trimAfterEoi(rawJpeg)
                        val bitmap = decoder.decode(rawJpeg, endTrimmed)
                        if (bitmap != null) {
                            frameSeq++
                            val now = System.currentTimeMillis()
                            onPacketStats(ackedPackets, droppedFrames, endTrimmed)
                            frameFlow.tryEmit(
                                VideoFrame(
                                    bitmap = bitmap,
                                    receivedAtMs = now,
                                    estimatedCaptureMs = now - 28L,
                                    width = bitmap.width,
                                    height = bitmap.height,
                                    protocolId = DroneFamily.FLOW_UFO_BL60X.id,
                                    sequenceNumber = frameSeq
                                )
                            )
                        } else {
                            droppedFrames++
                            onPacketStats(ackedPackets, droppedFrames, 0)
                        }
                        jpegAccumulator.reset()
                        inFrame = false
                    }

                    // Protection anti-saturation mémoire si EOI manqué
                    if (jpegAccumulator.size() > 260_000) {
                        jpegAccumulator.reset()
                        inFrame = false
                        droppedFrames++
                    }
                } catch (_: SocketTimeoutException) {
                    consecutiveTimeouts++
                    if (consecutiveTimeouts == 5) {
                        // Souvent causé par l'app propriétaire encore ouverte (1 seul client vidéo accepté)
                        onConflictDetected(true)
                        logger.log(
                            LogSubsystem.VIDEO,
                            LogSeverity.WARN,
                            "Aucune trame vidéo reçue sur $gatewayIp:$videoPort — vérifiez que l'application propriétaire est fermée"
                        )
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        logger.log(LogSubsystem.VIDEO, LogSeverity.ERROR, "Erreur socket FLOW-UFO: ${e.message}")
                        delay(400L)
                    }
                }
            }
        }
    }

    private fun stopUdpVideoStream() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        rxJob?.cancel()
        rxJob = null
        runCatching { videoSocket?.close() }
        videoSocket = null
    }

    private fun sendControlFrame(
        roll: Float = 0f,
        pitch: Float = 0f,
        throttle: Float = 0f,
        yaw: Float = 0f,
        flagMask: Int = 0
    ) {
        val sock = controlSocket ?: return
        // Encodage standard 8 bits centré sur 0x80 (128), borné [0x00..0xFF]
        fun axisByte(v: Float): Byte = ((v.coerceIn(-1f, 1f) * 127f) + 128f).toInt().coerceIn(1, 254).toByte()
        val r = axisByte(roll)
        val p = axisByte(pitch)
        val t = axisByte(throttle)
        val y = axisByte(yaw)
        val flags = (flagMask or (if (headlessFlag) 0x10 else 0) or ((currentSpeedLevel - 1) shl 5)).toByte()
        val checksum = ((r.toInt() xor p.toInt() xor t.toInt() xor y.toInt() xor flags.toInt()) and 0xFF).toByte()
        val payload = byteArrayOf(0x66, r, p, t, y, flags, checksum, 0x99.toByte())
        sendUdpPacket(sock, gatewayIp, controlPort, payload)
    }

    private fun sendUdpPacket(socket: DatagramSocket?, host: String, port: Int, data: ByteArray): Boolean {
        val sock = socket ?: return false
        return try {
            val addr = InetAddress.getByName(host)
            sock.send(DatagramPacket(data, data.size, addr, port))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun findJpegMarker(buf: ByteArray, length: Int, secondByte: Byte): Int {
        for (i in 0 until length - 1) {
            if (buf[i] == 0xFF.toByte() && buf[i + 1] == secondByte) {
                return i
            }
        }
        return -1
    }

    private fun trimAfterEoi(data: ByteArray): Int {
        for (i in data.size - 2 downTo 0) {
            if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD9.toByte()) {
                return i + 2
            }
        }
        return data.size
    }
}
