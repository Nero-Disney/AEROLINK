package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.InputDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.connectivity.diagnostics.DiagnosticLogger
import com.example.connectivity.diagnostics.LogSeverity
import com.example.connectivity.diagnostics.LogSubsystem
import com.example.connectivity.discovery.ProtocolDiscoveryEngine
import com.example.connectivity.wifi.DroneWifiManager
import com.example.connectivity.wifi.WifiConnectionStatus
import com.example.core.models.ConfidenceLevel
import com.example.core.models.DetectionResult
import com.example.core.models.DetectionZone
import com.example.core.models.DetectorType
import com.example.core.models.DroneFamily
import com.example.core.models.DroneProfile
import com.example.core.models.FlightState
import com.example.core.models.HardwareBackend
import com.example.core.models.NetworkContext
import com.example.core.models.NormalizedBox
import com.example.core.models.OpticalFlowEstimate
import com.example.core.models.OpticalFlowLevel
import com.example.core.models.ScannedWifiNetwork
import com.example.core.models.StickInput
import com.example.core.models.TelemetrySnapshot
import com.example.core.models.TrackingMode
import com.example.core.models.VideoFrame
import com.example.core.models.VisionAnalysisResult
import com.example.core.utilities.PerformanceMonitor
import com.example.flight.FlightControllerManager
import com.example.protocols.api.DroneProtocolAdapter
import com.example.protocols.registry.ProtocolRegistry
import com.example.storage.DroneRepository
import com.example.storage.db.AeroLinkDatabase
import com.example.storage.db.BenchmarkReportEntity
import com.example.storage.db.DiagnosticLogEntity
import com.example.storage.db.FlightSessionEntity
import com.example.storage.db.RecordingEntity
import com.example.storage.prefs.AppPreferences
import com.example.storage.prefs.AppPreferencesRepository
import com.example.storage.prefs.NamedSettingsPreset
import com.example.storage.prefs.SettingsCategory
import com.example.testlab.AdvdsValidationLab
import com.example.testlab.ValidationLabReport
import com.example.video.frame_pipeline.SharedFramePipeline
import com.example.video.recorder.LocalMediaRecorder
import com.example.vision.adapters.geometry.CameraCalibrationEngine
import com.example.vision.adapters.litert.LiteRtYoloDetectorProvider
import com.example.vision.adapters.mlkit.MlKitBarcodeReaderProvider
import com.example.vision.adapters.mlkit.MlKitObjectDetectorProvider
import com.example.vision.adapters.mlkit.MlKitPoseEstimatorProvider
import com.example.vision.adapters.onnx.OnnxDepthEstimatorProvider
import com.example.vision.adapters.onnx.OnnxYoloDetectorProvider
import com.example.vision.domain.contracts.InferenceConfig
import com.example.vision.domain.contracts.ModelDescriptor
import com.example.vision.domain.contracts.VisionFramework
import com.example.vision.domain.entities.CameraCalibration
import com.example.vision.litert.InstalledModelSpec
import com.example.vision.litert.LiteRtBenchmarkRunner
import com.example.vision.mediapipe.MlKitVisionEngine
import com.example.vision.runtime.modelregistry.ModelRegistry
import com.example.vision.runtime.provider.ResolutionReport
import com.example.vision.runtime.provider.VisionRuntimeConfig
import com.example.vision.runtime.provider.VisionRuntimeManager
import com.example.vision.runtime.scheduler.PerceptionScheduler
import com.example.vision.runtime.scheduler.SchedulerExecutionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AeroLinkViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AeroLinkDatabase.getInstance(application)
    private val repository = DroneRepository(db)
    val prefsRepo = AppPreferencesRepository(application)
    val logger = DiagnosticLogger(application, db.diagnosticLogDao())
    val wifiManager = DroneWifiManager(application, logger)
    private val discoveryEngine = ProtocolDiscoveryEngine(wifiManager, logger)
    private val perfMonitor = PerformanceMonitor(application)
    val framePipeline = SharedFramePipeline()
    private val visionEngine = MlKitVisionEngine()
    val modelRegistry = ModelRegistry(application)
    private val visionRuntimeManager = VisionRuntimeManager(
        modelRegistry = modelRegistry,
        objectProviders = listOf(
            OnnxYoloDetectorProvider(),
            LiteRtYoloDetectorProvider(),
            MlKitObjectDetectorProvider()
        ),
        poseProviders = listOf(MlKitPoseEstimatorProvider()),
        depthProviders = listOf(OnnxDepthEstimatorProvider()),
        barcodeProviders = listOf(MlKitBarcodeReaderProvider())
    )
    private val perceptionScheduler = PerceptionScheduler(visionRuntimeManager)
    private val validationLab = AdvdsValidationLab(visionRuntimeManager)
    val mediaRecorder = LocalMediaRecorder(application, logger)
    val flightManager = FlightControllerManager(logger)
    private val benchmarkRunner = LiteRtBenchmarkRunner(application, visionEngine)

    private var ackedPacketsCount = 0L
    private var droppedFramesCount = 0L
    private var totalFramesCount = 0L
    private var cameraConflictFlag = false
    private var sessionStartMs = 0L
    private var sessionCapturesCount = 0
    private var previousPreferencesSnapshot: AppPreferences? = null

    private val protocolRegistry = ProtocolRegistry(
        wifiManager = wifiManager,
        logger = logger,
        onConflictDetected = { conflict -> cameraConflictFlag = conflict },
        onPacketStats = { acked, dropped, _ ->
            ackedPacketsCount = acked
            droppedFramesCount = dropped
        }
    )

    val preferences: StateFlow<AppPreferences> = prefsRepo.preferences
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppPreferences())

    val customPresets: StateFlow<List<NamedSettingsPreset>> = prefsRepo.customPresets
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val builtInPresets: List<NamedSettingsPreset> = prefsRepo.builtInPresets

    val droneProfiles: StateFlow<List<DroneProfile>> = repository.profiles
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val flightSessions: StateFlow<List<FlightSessionEntity>> = repository.sessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recordings: StateFlow<List<RecordingEntity>> = repository.recordings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val diagnosticLogs: StateFlow<List<DiagnosticLogEntity>> = logger.recentLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val benchmarkReports: StateFlow<List<BenchmarkReportEntity>> = repository.benchmarks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val scannedNetworks: StateFlow<List<ScannedWifiNetwork>> = wifiManager.scannedNetworks
    val wifiStatus: StateFlow<WifiConnectionStatus> = wifiManager.connectionStatus
    val reconnectAttempt: StateFlow<Int> = wifiManager.reconnectAttempt
    val activeNetworkContext: StateFlow<NetworkContext?> = wifiManager.activeNetworkContext
    val displayFrame: StateFlow<VideoFrame?> = framePipeline.displayFrame
    val flightState: StateFlow<FlightState> = flightManager.flightState
    val stickInput: StateFlow<StickInput> = flightManager.stickInput
    val speedMode: StateFlow<Int> = flightManager.speedMode
    val headlessEnabled: StateFlow<Boolean> = flightManager.headlessEnabled
    val safetyStatusMessage: StateFlow<String?> = flightManager.safetyStatusMessage

    private val _activeProfile = MutableStateFlow<DroneProfile?>(null)
    val activeProfile: StateFlow<DroneProfile?> = _activeProfile.asStateFlow()

    private val _detectionResult = MutableStateFlow<DetectionResult?>(null)
    val detectionResult: StateFlow<DetectionResult?> = _detectionResult.asStateFlow()

    private val _isProbing = MutableStateFlow(false)
    val isProbing: StateFlow<Boolean> = _isProbing.asStateFlow()

    private val _telemetry = MutableStateFlow(TelemetrySnapshot())
    val telemetry: StateFlow<TelemetrySnapshot> = _telemetry.asStateFlow()

    private val _visionResult = MutableStateFlow(VisionAnalysisResult())
    val visionResult: StateFlow<VisionAnalysisResult> = _visionResult.asStateFlow()

    private val _detectionZone = MutableStateFlow(DetectionZone())
    val detectionZone: StateFlow<DetectionZone> = _detectionZone.asStateFlow()

    private val _lockedTrackId = MutableStateFlow<String?>(null)
    val lockedTrackId: StateFlow<String?> = _lockedTrackId.asStateFlow()

    private val _isRecordingVideo = MutableStateFlow(false)
    val isRecordingVideo: StateFlow<Boolean> = _isRecordingVideo.asStateFlow()

    private val _isLocalCameraBenchActive = MutableStateFlow(false)
    val isLocalCameraBenchActive: StateFlow<Boolean> = _isLocalCameraBenchActive.asStateFlow()

    private val _isBenchmarkRunning = MutableStateFlow(false)
    val isBenchmarkRunning: StateFlow<Boolean> = _isBenchmarkRunning.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _canUndoLastSetting = MutableStateFlow(false)
    val canUndoLastSetting: StateFlow<Boolean> = _canUndoLastSetting.asStateFlow()

    private val _installedModels = MutableStateFlow(benchmarkRunner.installedModels(false))
    val installedModels: StateFlow<List<InstalledModelSpec>> = _installedModels.asStateFlow()

    private val _gpuDelegateSupported = MutableStateFlow(false)
    val gpuDelegateSupported: StateFlow<Boolean> = _gpuDelegateSupported.asStateFlow()

    val registryModels: StateFlow<List<ModelDescriptor>> = modelRegistry.models

    private val _objectResolutionReport = MutableStateFlow<ResolutionReport?>(null)
    val objectResolutionReport: StateFlow<ResolutionReport?> = _objectResolutionReport.asStateFlow()

    private val _depthResolutionReport = MutableStateFlow<ResolutionReport?>(null)
    val depthResolutionReport: StateFlow<ResolutionReport?> = _depthResolutionReport.asStateFlow()

    private val _validationReport = MutableStateFlow<ValidationLabReport?>(null)
    val validationReport: StateFlow<ValidationLabReport?> = _validationReport.asStateFlow()

    private val _isValidatingContracts = MutableStateFlow(false)
    val isValidatingContracts: StateFlow<Boolean> = _isValidatingContracts.asStateFlow()

    private var activeAdapter: DroneProtocolAdapter? = null
    private var videoCollectorJob: Job? = null
    private var benchFrameSeq = 0L

    init {
        viewModelScope.launch {
            repository.ensureDefaultProfilesSeeded()
            logger.log(LogSubsystem.NETWORK, LogSeverity.INFO, "AeroLink initialisé — prêt pour découverte Wi-Fi")
            val gpuSupported = benchmarkRunner.probeGpuDelegateAvailable()
            _gpuDelegateSupported.value = gpuSupported
            _installedModels.value = benchmarkRunner.installedModels(gpuSupported)
            val prefs = preferences.value
            perceptionScheduler.refreshRuntimeProviders(
                runtimeConfig = VisionRuntimeConfig(
                    preferredObjectFramework = prefs.preferredObjectFramework,
                    preferredBackend = prefs.toComputeBackend(),
                    allowFallback = prefs.allowRuntimeFallback
                ),
                gpuSupported = gpuSupported,
                confidenceThreshold = prefs.confidenceThreshold
            )
            _objectResolutionReport.value = perceptionScheduler.lastObjectResolutionReport
            _depthResolutionReport.value = perceptionScheduler.lastDepthResolutionReport
        }

        // Synchronise les préférences vers le contrôleur de vol
        viewModelScope.launch {
            preferences.collectLatest { prefs ->
                _detectionZone.value = _detectionZone.value.copy(enabled = prefs.detectionZoneEnabled)
                val profile = _activeProfile.value
                flightManager.updateSafetyConfig(
                    videoOnlyLock = prefs.videoOnlySafetyLock,
                    protocolVerified = profile?.isProtocolVerified ?: false,
                    sendRateHz = profile?.sendRateHz ?: 25,
                    sensitivity = prefs.stickSensitivity,
                    deadzone = prefs.stickDeadzone,
                    invertPitch = profile?.invertPitch ?: false,
                    invertRoll = profile?.invertRoll ?: false,
                    invertYaw = profile?.invertYaw ?: false
                )
            }
        }

        // Surveille la perte Wi-Fi pour neutraliser immédiatement les commandes et stopper l'assistance IA
        viewModelScope.launch {
            wifiStatus.collectLatest { status ->
                if (status == WifiConnectionStatus.LOST || status == WifiConnectionStatus.UNAVAILABLE) {
                    if (activeAdapter != null) {
                        flightManager.onConnectionLost()
                        if (!_isLocalCameraBenchActive.value) {
                            _visionResult.value = VisionAnalysisResult()
                        }
                    }
                }
            }
        }

        // Consommateur IA découplé ADVDS (4 voies sur STRATEGY_KEEP_ONLY_LATEST)
        viewModelScope.launch(Dispatchers.Default) {
            framePipeline.aiFrames.collect { frame ->
                val prefs = preferences.value
                val detector = prefs.activeDetector
                val flowLevel = prefs.opticalFlowLevel
                if (detector == DetectorType.NONE &&
                    flowLevel == OpticalFlowLevel.OFF &&
                    !prefs.enableSpatialMap &&
                    !prefs.enableBarcodeMarkers
                ) {
                    _visionResult.value = VisionAnalysisResult(
                        frameWidth = frame.width,
                        frameHeight = frame.height,
                        timestampMs = frame.receivedAtMs
                    )
                    return@collect
                }

                val effectiveCalibration = if (prefs.calibratedFocalPx > 50f && prefs.cameraCalibration.isFactoryEstimated) {
                    prefs.cameraCalibration.copy(
                        fx = prefs.calibratedFocalPx,
                        fy = prefs.calibratedFocalPx,
                        isFactoryEstimated = false
                    )
                } else {
                    prefs.cameraCalibration
                }

                val execCfg = SchedulerExecutionConfig(
                    activeDetector = detector,
                    trackingMode = prefs.activeTrackingMode,
                    opticalFlowLevel = flowLevel,
                    preferredBackend = prefs.toComputeBackend(),
                    preferredObjectFramework = prefs.preferredObjectFramework,
                    allowRuntimeFallback = prefs.allowRuntimeFallback,
                    confidenceThreshold = prefs.confidenceThreshold,
                    maxDisplayedBoxes = prefs.maxDisplayedBoxes,
                    abandonTimeoutMs = prefs.abandonTimeoutMs,
                    showLandmarks = prefs.showLandmarks,
                    enableGestureRecognition = prefs.enableGestureRecognition,
                    enableSessionReId = prefs.enableSessionReId || prefs.personRecognitionOptIn,
                    enableSpatialMap = prefs.enableSpatialMap,
                    enableBarcodeMarkers = prefs.enableBarcodeMarkers,
                    markerSizeMeters = prefs.markerSizeMeters,
                    calibration = effectiveCalibration,
                    detectionZone = _detectionZone.value,
                    distanceConfig = prefs.toDistanceConfig()
                )

                val result = perceptionScheduler.processFrame(
                    frame = frame,
                    cfg = execCfg,
                    gpuSupported = _gpuDelegateSupported.value
                )
                perfMonitor.recordInference()
                _objectResolutionReport.value = perceptionScheduler.lastObjectResolutionReport
                _depthResolutionReport.value = perceptionScheduler.lastDepthResolutionReport
                _lockedTrackId.value = result.lockedTrackId
                _visionResult.value = result
            }
        }

        // Boucle de rafraîchissement télémétrie 2 Hz
        viewModelScope.launch {
            while (isActive) {
                val netCtx = activeNetworkContext.value
                val isDroneStreaming = activeAdapter != null && (System.currentTimeMillis() - (displayFrame.value?.receivedAtMs ?: 0L)) < 2500L
                val isBenchStreaming = _isLocalCameraBenchActive.value
                val gamepadFound = InputDevice.getDeviceIds().any { id ->
                    val dev = InputDevice.getDevice(id)
                    dev != null && ((dev.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                        (dev.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
                }

                _telemetry.value = TelemetrySnapshot(
                    isConnected = netCtx != null || activeAdapter != null,
                    isVideoStreaming = isDroneStreaming || isBenchStreaming,
                    sourceLabel = when {
                        isBenchStreaming -> "BANC IA LOCAL (Caméra)"
                        activeAdapter != null -> activeAdapter!!.descriptor.family.displayName
                        else -> "En attente de flux"
                    },
                    ssid = netCtx?.ssid ?: _activeProfile.value?.ssidPattern ?: "--",
                    rssiDbm = netCtx?.rssiDbm ?: 0,
                    videoFps = perfMonitor.videoFps(),
                    latencyMs = displayFrame.value?.let { (it.receivedAtMs - it.estimatedCaptureMs).coerceAtLeast(0L) } ?: 0L,
                    medianLatencyMs = perfMonitor.medianLatencyMs(),
                    p95LatencyMs = perfMonitor.p95LatencyMs(),
                    droppedFrames = droppedFramesCount,
                    totalFrames = totalFramesCount,
                    ackedPackets = ackedPacketsCount,
                    rxKbps = perfMonitor.rxKbps(),
                    inferenceFps = perfMonitor.inferenceFps(),
                    inferenceLatencyMs = _visionResult.value.inferenceTimeMs,
                    memoryMb = perfMonitor.usedMemoryMb(),
                    thermalStatusLabel = perfMonitor.thermalStatusLabel(),
                    packetTxHz = if (flightState.value == FlightState.Flying) (_activeProfile.value?.sendRateHz ?: 25) else 0,
                    speedMode = speedMode.value,
                    headlessEnabled = headlessEnabled.value,
                    gamepadConnected = gamepadFound,
                    singleClientConflictWarning = cameraConflictFlag
                )
                delay(500L)
            }
        }
    }

    fun clearBanner() {
        _statusBanner.value = null
    }

    fun postBanner(message: String) {
        _statusBanner.value = message
    }

    fun scanWifiNetworks() {
        wifiManager.startWifiScan()
    }

    fun connectToWifi(ssid: String, passphrase: String? = null) {
        wifiManager.connectToDroneSsid(ssid, passphrase)
    }

    fun cancelWifiReconnect() {
        wifiManager.cancelReconnect()
    }

    fun inspectActiveSystemWifi() {
        wifiManager.inspectCurrentSystemWifi()
        activeNetworkContext.value?.let { runProtocolDiscovery(it) }
    }

    fun runProtocolDiscovery(customContext: NetworkContext? = null) {
        val ctx = customContext ?: activeNetworkContext.value ?: NetworkContext(
            ssid = "FLOW-UFO-manuel",
            phoneIp = "192.168.169.100",
            gatewayIp = "192.168.169.1"
        )
        viewModelScope.launch {
            _isProbing.value = true
            val result = discoveryEngine.discover(ctx)
            _detectionResult.value = result.copy(cameraBusy = cameraConflictFlag)
            _isProbing.value = false
        }
    }

    fun overrideDetectedFamily(family: DroneFamily) {
        val current = _detectionResult.value ?: DetectionResult(
            ssid = activeNetworkContext.value?.ssid ?: family.ssidPrefixes.firstOrNull() ?: "Manuel",
            phoneIp = activeNetworkContext.value?.phoneIp ?: "0.0.0.0",
            gatewayIp = family.defaultGatewayIp,
            family = family,
            videoTransport = family.videoTransport,
            commandsVerified = false,
            confidence = ConfidenceLevel.MEDIUM,
            probeSummary = "Sélection manuelle de la famille ${family.displayName}"
        )
        _detectionResult.value = current.copy(
            family = family,
            gatewayIp = activeNetworkContext.value?.gatewayIp ?: family.defaultGatewayIp,
            videoTransport = family.videoTransport,
            confidence = ConfidenceLevel.MEDIUM,
            probeSummary = "Famille sélectionnée manuellement (${family.referenceSource})"
        )
    }

    fun selectAndConnectProfile(profile: DroneProfile) {
        _activeProfile.value = profile
        startDroneVideoStream(profile)
    }

    fun connectFromDetectionResult() {
        val res = _detectionResult.value ?: return
        val family = if (res.family == DroneFamily.UNKNOWN) DroneFamily.FLOW_UFO_BL60X else res.family
        val profile = DroneProfile(
            commercialName = "${family.displayName} (${res.ssid})",
            ssidPattern = res.ssid,
            family = family,
            gatewayIp = res.gatewayIp,
            videoPort = family.defaultVideoPort,
            controlPort = family.defaultControlPort,
            videoTransport = res.videoTransport,
            isProtocolVerified = res.commandsVerified,
            lastConnectedMs = System.currentTimeMillis()
        )
        viewModelScope.launch {
            val savedId = repository.saveProfile(profile)
            val persisted = profile.copy(id = savedId)
            _activeProfile.value = persisted
            startDroneVideoStream(persisted)
        }
    }

    fun startDroneVideoStream(profile: DroneProfile) {
        stopActiveStream()
        _isLocalCameraBenchActive.value = false
        perfMonitor.reset()
        perceptionScheduler.resetState()
        sessionStartMs = System.currentTimeMillis()
        sessionCapturesCount = 0
        totalFramesCount = 0L
        droppedFramesCount = 0L
        ackedPacketsCount = 0L
        cameraConflictFlag = false

        val adapter = protocolRegistry.createAdapter(profile.family)
        activeAdapter = adapter

        flightManager.attachProtocol(
            controller = adapter.flightController,
            capabilities = adapter.capabilities,
            isConnected = true,
            isProtocolVerified = profile.isProtocolVerified
        )

        videoCollectorJob = viewModelScope.launch {
            launch {
                adapter.videoSource.frames().collect { frame ->
                    totalFramesCount++
                    val latency = (frame.receivedAtMs - frame.estimatedCaptureMs).coerceAtLeast(0L)
                    perfMonitor.recordVideoFrame(frame.bitmap.byteCount, latency)
                    framePipeline.publishFrame(frame)
                    if (mediaRecorder.isRecordingVideo) {
                        mediaRecorder.appendVideoFrame(frame)
                    }
                }
            }
            adapter.connect(profile.gatewayIp, profile.videoPort, profile.controlPort)
        }
        _statusBanner.value = "Récepteur vidéo ${profile.family.displayName} actif (${profile.gatewayIp}:${profile.videoPort})"
    }

    fun stopActiveStream() {
        val wasStreaming = activeAdapter != null && sessionStartMs > 0L
        if (_isRecordingVideo.value) {
            toggleVideoRecording()
        }
        videoCollectorJob?.cancel()
        videoCollectorJob = null
        val oldAdapter = activeAdapter
        activeAdapter = null
        viewModelScope.launch {
            oldAdapter?.disconnect()
            if (wasStreaming) {
                val duration = ((System.currentTimeMillis() - sessionStartMs) / 1000L).coerceAtLeast(1L)
                repository.recordSession(
                    FlightSessionEntity(
                        ssid = _activeProfile.value?.ssidPattern ?: "--",
                        familyId = _activeProfile.value?.family?.id ?: DroneFamily.UNKNOWN.id,
                        startedAtMs = sessionStartMs,
                        durationSec = duration,
                        avgVideoFps = perfMonitor.videoFps(),
                        medianLatencyMs = perfMonitor.medianLatencyMs(),
                        p95LatencyMs = perfMonitor.p95LatencyMs(),
                        droppedFrames = droppedFramesCount,
                        capturesCount = sessionCapturesCount
                    )
                )
            }
        }
        flightManager.attachProtocol(null, com.example.core.models.DroneCapabilities(), false, false)
        framePipeline.clear()
        _visionResult.value = VisionAnalysisResult()
    }

    fun setLocalCameraBenchActive(active: Boolean) {
        if (active) {
            stopActiveStream()
            perfMonitor.reset()
            perceptionScheduler.resetState()
            _isLocalCameraBenchActive.value = true
            logger.log(LogSubsystem.AI, LogSeverity.INFO, "Banc d'essai Caméra locale activé pour test IA ADVDS")
        } else {
            _isLocalCameraBenchActive.value = false
            framePipeline.clear()
            _visionResult.value = VisionAnalysisResult()
        }
    }

    fun submitLocalBenchBitmap(bitmap: Bitmap, rotationDeg: Int = 0) {
        if (!_isLocalCameraBenchActive.value) return
        benchFrameSeq++
        val now = System.currentTimeMillis()
        perfMonitor.recordVideoFrame(bitmap.byteCount, 8L)
        framePipeline.publishFrame(
            VideoFrame(
                bitmap = bitmap,
                receivedAtMs = now,
                estimatedCaptureMs = now - 8L,
                width = bitmap.width,
                height = bitmap.height,
                rotationDegrees = rotationDeg,
                protocolId = "local_camera_bench",
                sequenceNumber = benchFrameSeq
            )
        )
    }

    fun analyzeStaticGalleryImage(uri: Uri) {
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    val ctx = getApplication<Application>()
                    val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, _, _ ->
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Images.Media.getBitmap(ctx.contentResolver, uri)
                    }
                    val maxDim = 960
                    if (raw.width > maxDim || raw.height > maxDim) {
                        val scale = maxDim.toFloat() / maxOf(raw.width, raw.height)
                        Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt(), (raw.height * scale).toInt(), true)
                    } else raw
                }.getOrNull()
            }
            if (bmp != null) {
                setLocalCameraBenchActive(true)
                submitLocalBenchBitmap(bmp)
                _statusBanner.value = "Image chargée dans le pipeline IA embarqué"
            }
        }
    }

    fun captureSnapshot() {
        val frame = displayFrame.value ?: run {
            _statusBanner.value = "Aucune image vidéo disponible à capturer"
            return
        }
        viewModelScope.launch {
            val entity = mediaRecorder.captureSnapshot(
                frame = frame,
                ssid = _activeProfile.value?.ssidPattern ?: "LocalBench",
                familyId = _activeProfile.value?.family?.id ?: "local"
            )
            if (entity != null) {
                sessionCapturesCount++
                repository.saveRecording(entity)
                _statusBanner.value = "Capture enregistrée (${entity.width}x${entity.height})"
            }
        }
    }

    fun toggleVideoRecording() {
        if (_isRecordingVideo.value) {
            val saved = mediaRecorder.stopVideoRecording()
            _isRecordingVideo.value = false
            if (saved != null) {
                viewModelScope.launch {
                    repository.saveRecording(saved)
                    _statusBanner.value = "Vidéo MP4 enregistrée (${saved.sizeBytes / 1024} Ko)"
                }
            }
        } else {
            val frame = displayFrame.value
            val w = frame?.width ?: 640
            val h = frame?.height ?: 480
            val started = mediaRecorder.startVideoRecording(
                width = w,
                height = h,
                ssid = _activeProfile.value?.ssidPattern ?: "LocalBench",
                familyId = _activeProfile.value?.family?.id ?: "local"
            )
            _isRecordingVideo.value = started
            _statusBanner.value = if (started) "Enregistrement vidéo démarré" else "Échec démarrage enregistrement"
        }
    }

    fun switchDroneCamera() {
        val adapter = activeAdapter ?: return
        val nextCam = if (_telemetry.value.activeCameraIndex == 0) 1 else 0
        viewModelScope.launch {
            val ok = adapter.videoSource.switchCamera(nextCam)
            if (ok) {
                _telemetry.value = _telemetry.value.copy(activeCameraIndex = nextCam)
                logger.log(LogSubsystem.VIDEO, LogSeverity.INFO, "Bascule caméra -> index $nextCam")
            }
        }
    }

    // Sélection & verrouillage cible ADVDS (Session uniquement)
    fun designateTarget(trackId: String) {
        perceptionScheduler.trackerEngine.designateTarget(trackId)
        syncTrackerSelectionIntoVisionResult()
    }

    fun toggleTargetLock(trackId: String) {
        perceptionScheduler.trackerEngine.lockTarget(trackId)
        syncTrackerSelectionIntoVisionResult()
    }

    fun clearTargetSelection() {
        perceptionScheduler.trackerEngine.clearSelection()
        syncTrackerSelectionIntoVisionResult()
    }

    fun addManualTargetBox(box: NormalizedBox) {
        perceptionScheduler.trackerEngine.addManualTargetBox(box)
        syncTrackerSelectionIntoVisionResult()
        _statusBanner.value = "Cible manuelle tracée et verrouillée"
    }

    fun selectNearestTarget() {
        val id = perceptionScheduler.trackerEngine.selectNearestOrLargest()
        if (id != null) {
            syncTrackerSelectionIntoVisionResult()
            _statusBanner.value = "Cible la plus proche désignée ($id)"
        }
    }

    fun selectCenterTarget() {
        val id = perceptionScheduler.trackerEngine.selectCenterTarget()
        if (id != null) {
            syncTrackerSelectionIntoVisionResult()
            _statusBanner.value = "Cible centrale désignée ($id)"
        }
    }

    fun resolveAmbiguousTarget(trackId: String) {
        perceptionScheduler.trackerEngine.resolveAmbiguousCandidate(trackId)
        syncTrackerSelectionIntoVisionResult()
    }

    private fun syncTrackerSelectionIntoVisionResult() {
        val tracker = perceptionScheduler.trackerEngine
        val locked = tracker.lockedTrackId
        val designated = tracker.designatedTrackId
        _lockedTrackId.value = locked
        val cur = _visionResult.value
        val prefs = preferences.value
        val ambig = tracker.getAmbiguousLegacyCandidates(
            frameSequence = cur.perceptionState?.frameSequence ?: 0L,
            frameHeightPx = cur.frameHeight.takeIf { it > 0 } ?: 720,
            nowNs = System.nanoTime(),
            calibration = prefs.cameraCalibration,
            distanceConfig = prefs.toDistanceConfig()
        )
        _visionResult.value = cur.copy(
            designatedTrackId = designated,
            lockedTrackId = locked,
            ambiguousRecoveryCandidates = ambig,
            trackedSubjects = cur.trackedSubjects.map { s ->
                s.copy(
                    isLocked = s.trackId == locked,
                    isDesignated = s.trackId == designated || s.trackId == locked
                )
            },
            perceptionState = cur.perceptionState?.let { ps ->
                ps.copy(
                    tracks = ps.tracks.map { t ->
                        t.copy(
                            isLocked = t.trackId == locked,
                            isDesignated = t.trackId == designated || t.trackId == locked
                        )
                    }
                )
            }
        )
    }

    fun calibrateFocalFromReferenceBox(knownDistanceM: Float, realHeightM: Float, apparentHeightNorm: Float) {
        val frameW = _visionResult.value.frameWidth.takeIf { it > 0 } ?: 640
        val frameH = _visionResult.value.frameHeight.takeIf { it > 0 } ?: 480
        val calib = CameraCalibrationEngine.calibrateFromReferenceObservations(
            widthPx = frameW,
            heightPx = frameH,
            knownDistanceMeters = knownDistanceM,
            realObjectHeightMeters = realHeightM,
            apparentHeightNorm = apparentHeightNorm
        )
        updatePreferences {
            it.copy(
                referenceTargetHeightM = realHeightM.coerceIn(0.1f, 4.0f),
                calibratedFocalPx = calib.fy,
                cameraCalibration = calib,
                showDistanceInMeters = true
            )
        }
        _statusBanner.value = "Calibration sténopé effectuée : fy=${calib.fy.toInt()} px • FOV H=${calib.horizontalFovDegrees.toInt()}°"
    }

    fun resetCameraCalibration() {
        updatePreferences {
            it.copy(
                calibratedFocalPx = 0f,
                cameraCalibration = CameraCalibration()
            )
        }
        _statusBanner.value = "Calibration caméra réinitialisée (estimée)"
    }

    fun updatePreferences(transform: (AppPreferences) -> AppPreferences) {
        viewModelScope.launch {
            previousPreferencesSnapshot = preferences.value
            _canUndoLastSetting.value = true
            prefsRepo.update(transform)
        }
    }

    fun undoLastPreferenceChange() {
        val snap = previousPreferencesSnapshot ?: return
        viewModelScope.launch {
            prefsRepo.update { snap }
            previousPreferencesSnapshot = null
            _canUndoLastSetting.value = false
            _statusBanner.value = "Dernier réglage annulé"
        }
    }

    fun resetSettingsCategory(category: SettingsCategory) {
        if (category == SettingsCategory.SAFETY_FLIGHT && flightState.value == FlightState.Flying) {
            _statusBanner.value = "Réinitialisation sécurité bloquée pendant le vol"
            return
        }
        viewModelScope.launch {
            previousPreferencesSnapshot = preferences.value
            _canUndoLastSetting.value = true
            prefsRepo.resetCategory(category)
            _statusBanner.value = "${category.labelFr} réinitialisé"
        }
    }

    fun resetAllSettings() {
        val isFlying = flightState.value == FlightState.Flying
        viewModelScope.launch {
            previousPreferencesSnapshot = preferences.value
            _canUndoLastSetting.value = true
            prefsRepo.resetAll(preserveSafetyLockIfFlying = isFlying)
            _statusBanner.value = if (isFlying) {
                "Réglages réinitialisés (verrous de sécurité conservés en vol)"
            } else {
                "Tous les réglages ont été réinitialisés"
            }
        }
    }

    fun applyPreset(preset: NamedSettingsPreset) {
        viewModelScope.launch {
            previousPreferencesSnapshot = preferences.value
            _canUndoLastSetting.value = true
            prefsRepo.applyPreset(preset)
            _statusBanner.value = "Préréglage « ${preset.name} » appliqué"
        }
    }

    fun saveCustomPreset(name: String, description: String) {
        viewModelScope.launch {
            prefsRepo.saveCustomPreset(name, description)
            _statusBanner.value = "Profil « $name » enregistré"
        }
    }

    fun deleteCustomPreset(id: String) {
        viewModelScope.launch { prefsRepo.deleteCustomPreset(id) }
    }

    suspend fun exportSettingsJson(): String = prefsRepo.exportBundleJson()

    fun importSettingsJson(rawJson: String) {
        viewModelScope.launch {
            prefsRepo.importBundleJson(rawJson)
                .onSuccess { _statusBanner.value = "Configuration importée (schéma v${it.schemaVersion})" }
                .onFailure { _statusBanner.value = "Import JSON invalide : ${it.message}" }
        }
    }

    fun setActiveDetector(d: DetectorType) = updatePreferences {
        perceptionScheduler.resetState()
        it.copy(activeDetector = d)
    }

    fun setPreferredObjectFramework(framework: VisionFramework) = updatePreferences {
        perceptionScheduler.resetState()
        it.copy(preferredObjectFramework = framework)
    }

    fun setTrackingMode(m: TrackingMode) = updatePreferences { it.copy(activeTrackingMode = m) }
    fun setOpticalFlowLevel(l: OpticalFlowLevel) = updatePreferences {
        perceptionScheduler.resetState()
        it.copy(opticalFlowLevel = l)
    }
    fun setHardwareBackend(b: HardwareBackend) = updatePreferences { it.copy(hardwareBackend = b) }
    fun setOverlayToggles(
        trajectories: Boolean? = null,
        landmarks: Boolean? = null,
        centerReticle: Boolean? = null,
        detectionZone: Boolean? = null
    ) = updatePreferences { cur ->
        cur.copy(
            showTrajectories = trajectories ?: cur.showTrajectories,
            showLandmarks = landmarks ?: cur.showLandmarks,
            showCenterReticle = centerReticle ?: cur.showCenterReticle,
            detectionZoneEnabled = detectionZone ?: cur.detectionZoneEnabled
        )
    }

    fun importModelArtifact(modelId: String, uri: Uri) {
        viewModelScope.launch {
            modelRegistry.importArtifactForModel(modelId, uri)
                .onSuccess { desc ->
                    val prefs = preferences.value
                    perceptionScheduler.refreshRuntimeProviders(
                        runtimeConfig = VisionRuntimeConfig(
                            preferredObjectFramework = prefs.preferredObjectFramework,
                            preferredBackend = prefs.toComputeBackend(),
                            allowFallback = prefs.allowRuntimeFallback
                        ),
                        gpuSupported = _gpuDelegateSupported.value,
                        confidenceThreshold = prefs.confidenceThreshold
                    )
                    _objectResolutionReport.value = perceptionScheduler.lastObjectResolutionReport
                    _depthResolutionReport.value = perceptionScheduler.lastDepthResolutionReport
                    logger.log(LogSubsystem.AI, LogSeverity.INFO, "Modèle importé : ${desc.name} (${desc.checksumSha256})")
                    _statusBanner.value = "Artefact importé pour ${desc.name} (${desc.artifact.sizeBytes / 1024} Ko)"
                }
                .onFailure { err ->
                    _statusBanner.value = "Import modèle refusé : ${err.message}"
                }
        }
    }

    fun removeModelArtifact(modelId: String) {
        viewModelScope.launch {
            modelRegistry.removeImportedArtifact(modelId)
            val prefs = preferences.value
            perceptionScheduler.refreshRuntimeProviders(
                runtimeConfig = VisionRuntimeConfig(
                    preferredObjectFramework = prefs.preferredObjectFramework,
                    preferredBackend = prefs.toComputeBackend(),
                    allowFallback = prefs.allowRuntimeFallback
                ),
                gpuSupported = _gpuDelegateSupported.value,
                confidenceThreshold = prefs.confidenceThreshold
            )
            _objectResolutionReport.value = perceptionScheduler.lastObjectResolutionReport
            _depthResolutionReport.value = perceptionScheduler.lastDepthResolutionReport
            _statusBanner.value = "Artefact supprimé du stockage local"
        }
    }

    fun runValidationLabSuite(framework: VisionFramework) {
        if (_isValidatingContracts.value) return
        viewModelScope.launch {
            _isValidatingContracts.value = true
            val rep = validationLab.runProviderContractSuite(framework, _gpuDelegateSupported.value)
            _validationReport.value = rep
            logger.log(
                LogSubsystem.AI,
                if (rep.allPassed) LogSeverity.INFO else LogSeverity.WARN,
                "Lab Validation ${rep.providerId}: ${rep.checks.count { it.passed }}/${rep.checks.size} tests OK (P50=${rep.medianLatencyMs}ms, P95=${rep.p95LatencyMs}ms)"
            )
            _isValidatingContracts.value = false
            _statusBanner.value = "Tests contrat ADVDS : ${rep.checks.count { it.passed }}/${rep.checks.size} réussis"
        }
    }

    fun updateActiveProfile(updated: DroneProfile) {
        _activeProfile.value = updated
        viewModelScope.launch {
            repository.saveProfile(updated)
            _statusBanner.value = "Profil ${updated.commercialName} mis à jour"
        }
    }

    fun runAiBenchmark(detectorType: DetectorType, backend: HardwareBackend) {
        if (_isBenchmarkRunning.value) return
        viewModelScope.launch {
            _isBenchmarkRunning.value = true
            val report = benchmarkRunner.runBenchmark(detectorType, backend)
            repository.saveBenchmark(report)
            logger.log(
                LogSubsystem.AI,
                LogSeverity.INFO,
                "Benchmark ${report.modelName} [${report.backend}]: médiane=${report.medianInferenceMs}ms, P95=${report.p95InferenceMs}ms, ${"%.1f".format(report.inferenceFps)} FPS"
            )
            _isBenchmarkRunning.value = false
            _statusBanner.value = "Benchmark terminé : ${report.medianInferenceMs} ms médiane (${"%.1f".format(report.inferenceFps)} FPS)"
        }
    }

    fun deleteRecording(rec: RecordingEntity) = viewModelScope.launch { repository.deleteRecording(rec) }

    fun purgeAllLocalData() {
        viewModelScope.launch {
            repository.purgeAllUserData(mediaRecorder.recordingsDir, mediaRecorder.capturesDir)
            _statusBanner.value = "Toutes les sessions, captures et journaux locaux ont été supprimés"
        }
    }

    suspend fun exportDiagnosticsFile(): File {
        return logger.buildStatsJsonAndReportFile(_telemetry.value)
    }

    fun clearDiagnosticLogs() = viewModelScope.launch { logger.clear() }

    override fun onCleared() {
        super.onCleared()
        stopActiveStream()
        wifiManager.disconnect()
        perceptionScheduler.close()
        visionEngine.close()
    }
}
