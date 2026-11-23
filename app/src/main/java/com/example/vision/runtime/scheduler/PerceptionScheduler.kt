package com.example.vision.runtime.scheduler

import com.example.core.models.AiDetection
import com.example.core.models.DetectionZone
import com.example.core.models.DetectorType
import com.example.core.models.OpticalFlowEstimate
import com.example.core.models.OpticalFlowLevel
import com.example.core.models.TargetDistanceConfig
import com.example.core.models.TrackingMode
import com.example.core.models.VideoFrame
import com.example.core.models.VisionAnalysisResult
import com.example.intelligence.risk.RiskAssessmentEngine
import com.example.perception.human.HumanPerceptionEngine
import com.example.perception.tracking.AdvdsTrackerEngine
import com.example.perception.tracking.toLegacySubject
import com.example.vision.adapters.geometry.CameraCalibrationEngine
import com.example.vision.adapters.geometry.FrameQualityEngine
import com.example.vision.adapters.geometry.OpticalFlowMotionAdapter
import com.example.vision.adapters.geometry.SceneGeometryAnalyzer
import com.example.vision.adapters.mlkit.MlKitBarcodeReaderAdapter
import com.example.vision.adapters.mlkit.MlKitFaceDetectorAdapter
import com.example.vision.domain.contracts.ComputeBackend
import com.example.vision.domain.contracts.DepthEstimator
import com.example.vision.domain.contracts.DetectorRequirements
import com.example.vision.domain.contracts.FrameInput
import com.example.vision.domain.contracts.InferenceConfig
import com.example.vision.domain.contracts.ObjectDetector
import com.example.vision.domain.contracts.PoseEstimator
import com.example.vision.domain.contracts.VisionFramework
import com.example.vision.domain.entities.AdvdsTaxonomy
import com.example.vision.domain.entities.CameraCalibration
import com.example.vision.domain.entities.LocalSpatialMap
import com.example.vision.domain.entities.PerceptionState
import com.example.vision.domain.entities.PipelineRatesSnapshot
import com.example.vision.domain.entities.TrackState
import com.example.vision.domain.observations.BarcodeDetection
import com.example.vision.domain.observations.DepthObservation
import com.example.vision.domain.observations.Detection
import com.example.vision.domain.observations.HumanAttributes
import com.example.vision.domain.observations.MotionObservation
import com.example.vision.domain.observations.PoseObservation
import com.example.vision.runtime.frame.BitmapFrameBuffer
import com.example.vision.runtime.provider.ResolutionReport
import com.example.vision.runtime.provider.VisionRuntimeConfig
import com.example.vision.runtime.provider.VisionRuntimeManager
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class SchedulerExecutionConfig(
    val activeDetector: DetectorType,
    val trackingMode: TrackingMode,
    val opticalFlowLevel: OpticalFlowLevel,
    val preferredBackend: ComputeBackend,
    val preferredObjectFramework: VisionFramework,
    val allowRuntimeFallback: Boolean,
    val confidenceThreshold: Float,
    val maxDisplayedBoxes: Int,
    val abandonTimeoutMs: Long,
    val showLandmarks: Boolean,
    val enableGestureRecognition: Boolean,
    val enableSessionReId: Boolean,
    val enableSpatialMap: Boolean,
    val enableBarcodeMarkers: Boolean,
    val markerSizeMeters: Float,
    val calibration: CameraCalibration,
    val detectionZone: DetectionZone,
    val distanceConfig: TargetDistanceConfig
)

/**
 * Ordonnanceur 4 voies ADVDS adapté au Snapdragon 845 (Galaxy S9) :
 * 1. FAST PERCEPTION PATH (continu) : FrameQuality + OpticalFlow RANSAC + Kalman Prediction
 * 2. AI DETECTION PATH (adaptatif ~6-10 Hz) : ObjectDetector (ONNX/LiteRT/ML Kit) / Face / Pose
 * 3. SPATIAL PATH (basse fréquence ~1-2 Hz) : DepthEstimator + SceneGeometryAnalyzer (LocalSpatialMap)
 * 4. SPECIALIZED PATH (mode/demande ~2 Hz) : Barcode/QR PnP + ReID session
 */
class PerceptionScheduler(
    private val runtimeManager: VisionRuntimeManager,
    val trackerEngine: AdvdsTrackerEngine = AdvdsTrackerEngine()
) {
    private val frameQualityEngine = FrameQualityEngine()
    private val motionAdapter = OpticalFlowMotionAdapter()
    private val faceAdapter = MlKitFaceDetectorAdapter()
    private val barcodeAdapter = MlKitBarcodeReaderAdapter()
    private val humanPerceptionEngine = HumanPerceptionEngine()

    private var cachedObjectDetector: ObjectDetector? = null
    private var cachedObjectReport: ResolutionReport? = null
    private var cachedPoseEstimator: PoseEstimator? = null
    private var cachedDepthEstimator: DepthEstimator? = null
    private var cachedDepthReport: ResolutionReport? = null
    private var lastRuntimeSignature: String = ""

    private var prevFrameInput: FrameInput? = null
    private var lastAiMs = 0L
    private var lastSpatialMs = 0L
    private var lastSpecializedMs = 0L

    private var cachedDetections: List<Detection> = emptyList()
    private var cachedPoses: List<PoseObservation> = emptyList()
    private var cachedBarcodes: List<BarcodeDetection> = emptyList()
    private var cachedDepthMap: DepthObservation? = null
    private var cachedSpatialMap: LocalSpatialMap = LocalSpatialMap()
    private var cachedHumanAttrs: HumanAttributes? = null

    private var fastHz = 0f
    private var aiHz = 0f
    private var spatialHz = 0f
    private var specializedHz = 0f
    private var lastFastMs = 0L
    private var lastAiDurationMs = 0f
    private var lastSpatialDurationMs = 0f
    private var lastSpecializedDurationMs = 0f

    @Volatile
    var lastObjectResolutionReport: ResolutionReport? = null
        private set

    @Volatile
    var lastDepthResolutionReport: ResolutionReport? = null
        private set

    suspend fun refreshRuntimeProviders(
        runtimeConfig: VisionRuntimeConfig,
        gpuSupported: Boolean,
        confidenceThreshold: Float,
        measuredLatencies: Map<String, Float> = emptyMap()
    ) {
        val sig = "${runtimeConfig.preferredObjectFramework}:${runtimeConfig.preferredBackend}:${runtimeConfig.allowFallback}"
        if (sig == lastRuntimeSignature && cachedObjectDetector != null) return
        lastRuntimeSignature = sig

        val device = runtimeManager.currentDeviceProfile(gpuSupported, measuredLatencies)
        val infCfg = InferenceConfig(
            preferredBackend = runtimeConfig.preferredBackend,
            confidenceThreshold = confidenceThreshold
        )

        cachedObjectDetector?.close()
        val (objDet, objRep) = runtimeManager.resolveObjectDetector(
            device = device,
            requirements = DetectorRequirements(allowFallback = runtimeConfig.allowFallback),
            runtimeConfig = runtimeConfig,
            inferenceConfig = infCfg
        )
        cachedObjectDetector = objDet
        cachedObjectReport = objRep
        lastObjectResolutionReport = objRep

        if (cachedPoseEstimator == null) {
            cachedPoseEstimator = runtimeManager.resolvePoseEstimator(device, runtimeConfig, infCfg)
        }

        cachedDepthEstimator?.close()
        val (depthEst, depthRep) = runtimeManager.resolveDepthEstimator(device, runtimeConfig, infCfg)
        cachedDepthEstimator = depthEst
        cachedDepthReport = depthRep
        lastDepthResolutionReport = depthRep
    }

    suspend fun processFrame(
        frame: VideoFrame,
        cfg: SchedulerExecutionConfig,
        gpuSupported: Boolean
    ): VisionAnalysisResult {
        val nowMs = frame.receivedAtMs
        val nowNs = nowMs * 1_000_000L

        val runtimeCfg = VisionRuntimeConfig(
            preferredObjectFramework = cfg.preferredObjectFramework,
            preferredBackend = cfg.preferredBackend,
            allowFallback = cfg.allowRuntimeFallback
        )
        refreshRuntimeProviders(runtimeCfg, gpuSupported, cfg.confidenceThreshold)

        barcodeAdapter.calibration = cfg.calibration
        barcodeAdapter.markerSizeMeters = cfg.markerSizeMeters
        val camProfile = CameraCalibrationEngine.buildProfile(cfg.calibration, frame.width, frame.height, frame.rotationDegrees)

        // Voie 1 : FAST PERCEPTION PATH (Qualité trame)
        val tFast0 = System.nanoTime()
        val quality = frameQualityEngine.assess(frame.bitmap)
        if (lastFastMs > 0L) {
            val dt = (nowMs - lastFastMs).coerceAtLeast(1L)
            fastHz = fastHz * 0.7f + (1000f / dt) * 0.3f
        }
        lastFastMs = nowMs

        if (quality.isObstructed) {
            trackerEngine.clearOnObstruction()
            motionAdapter.reset()
            humanPerceptionEngine.reset()
            cachedDetections = emptyList()
            cachedPoses = emptyList()
            cachedBarcodes = emptyList()

            val manualTracks = trackerEngine.updateWithObservations(
                detections = emptyList(),
                barcodes = emptyList(),
                motion = MotionObservation(),
                depthMap = null,
                humanAttrs = null,
                sourceBitmap = null,
                enableReId = false,
                trackingMode = cfg.trackingMode,
                detectionZone = cfg.detectionZone,
                frameSequence = frame.sequenceNumber,
                nowNs = nowNs,
                frameQualityScore = quality.qualityScore01,
                confidenceThreshold = cfg.confidenceThreshold,
                maxDisplayedBoxes = cfg.maxDisplayedBoxes,
                abandonTimeoutMs = cfg.abandonTimeoutMs,
                frameHeightPx = frame.height,
                calibration = cfg.calibration,
                distanceConfig = cfg.distanceConfig
            )
            val globalRisk = RiskAssessmentEngine.assessGlobalRisk(manualTracks, quality.qualityScore01, true)
            val pState = PerceptionState(
                frameSequence = frame.sequenceNumber,
                timestampNs = nowNs,
                frameQuality = quality,
                cameraProfile = camProfile,
                activeObjectFramework = cachedObjectReport?.selectedFramework ?: VisionFramework.ML_KIT,
                activeObjectProviderId = cachedObjectReport?.selectedProviderId ?: "None",
                capabilityWarningFr = cachedObjectReport?.warningFr,
                tracks = manualTracks,
                globalRisk = globalRisk,
                pipelineRates = snapshotRates(1f),
                isSceneObstructed = true,
                obstructionReasonFr = quality.reasonFr
            )
            return buildLegacyResult(frame, cfg, pState, manualTracks, 1L)
        }

        val frameInput = BitmapFrameBuffer.wrap(
            bitmap = frame.bitmap,
            sequence = frame.sequenceNumber,
            receivedAtMs = nowMs,
            capturedAtMs = frame.estimatedCaptureMs,
            rotationDegrees = frame.rotationDegrees,
            cameraProfileId = cfg.calibration.cameraProfileId
        )

        // Exécution Fast Flow + Voie AI adaptative en parallèle
        val runAiNow = cfg.activeDetector != DetectorType.NONE && (nowMs - lastAiMs >= 75L)
        val runSpecializedNow = cfg.enableBarcodeMarkers && (nowMs - lastSpecializedMs >= 420L)
        val runSpatialNow = cfg.enableSpatialMap && (nowMs - lastSpatialMs >= 650L)

        var aiRan = false
        val motionObs: MotionObservation = coroutineScope {
            val motionDeferred = async {
                if (cfg.opticalFlowLevel != OpticalFlowLevel.OFF) {
                    motionAdapter.track(prevFrameInput, frameInput).observation
                } else {
                    MotionObservation()
                }
            }
            val aiDeferred = async {
                if (runAiNow) {
                    val tai0 = System.nanoTime()
                    val res = runAiPath(frameInput, cfg)
                    lastAiDurationMs = ((System.nanoTime() - tai0) / 1_000_000f).coerceAtLeast(0.5f)
                    if (lastAiMs > 0L) {
                        aiHz = aiHz * 0.65f + (1000f / (nowMs - lastAiMs).coerceAtLeast(1L)) * 0.35f
                    }
                    lastAiMs = nowMs
                    aiRan = true
                    res
                } else null
            }
            val barcodeDeferred = async {
                if (runSpecializedNow) {
                    val ts0 = System.nanoTime()
                    val bcs = runCatching { barcodeAdapter.scan(frameInput).barcodes }.getOrDefault(emptyList())
                    lastSpecializedDurationMs = ((System.nanoTime() - ts0) / 1_000_000f).coerceAtLeast(0.2f)
                    if (lastSpecializedMs > 0L) {
                        specializedHz = specializedHz * 0.65f + (1000f / (nowMs - lastSpecializedMs).coerceAtLeast(1L)) * 0.35f
                    }
                    lastSpecializedMs = nowMs
                    bcs
                } else null
            }

            val m = motionDeferred.await()
            aiDeferred.await()?.let { (dets, poses, attrs) ->
                cachedDetections = dets
                cachedPoses = poses
                cachedHumanAttrs = attrs
            }
            barcodeDeferred.await()?.let { cachedBarcodes = it }
            m
        }

        prevFrameInput = frameInput

        // Si pas de nouveau batch IA sur cette trame, avancer la prédiction Kalman avec compensation caméra
        val tracks: List<TrackState> = if (aiRan || runSpecializedNow) {
            trackerEngine.updateWithObservations(
                detections = if (cfg.activeDetector == DetectorType.NONE) emptyList() else cachedDetections,
                barcodes = cachedBarcodes,
                motion = motionObs,
                depthMap = cachedDepthMap,
                humanAttrs = cachedHumanAttrs,
                sourceBitmap = if (cfg.enableSessionReId) frame.bitmap else null,
                enableReId = cfg.enableSessionReId,
                trackingMode = cfg.trackingMode,
                detectionZone = cfg.detectionZone,
                frameSequence = frame.sequenceNumber,
                nowNs = nowNs,
                frameQualityScore = quality.qualityScore01,
                confidenceThreshold = cfg.confidenceThreshold,
                maxDisplayedBoxes = cfg.maxDisplayedBoxes,
                abandonTimeoutMs = cfg.abandonTimeoutMs,
                frameHeightPx = frame.height,
                calibration = cfg.calibration,
                distanceConfig = cfg.distanceConfig
            )
        } else {
            trackerEngine.stepFastPrediction(
                motion = motionObs,
                depthMap = cachedDepthMap,
                humanAttrs = cachedHumanAttrs,
                frameSequence = frame.sequenceNumber,
                nowNs = nowNs,
                frameQualityScore = quality.qualityScore01,
                maxDisplayedBoxes = cfg.maxDisplayedBoxes,
                frameHeightPx = frame.height,
                calibration = cfg.calibration,
                distanceConfig = cfg.distanceConfig,
                barcodes = cachedBarcodes
            )
        }

        // Voie 3 : SPATIAL PATH (basse fréquence ~1.5 Hz)
        if (runSpatialNow) {
            val tsp0 = System.nanoTime()
            val depthEst = cachedDepthEstimator
            if (depthEst != null) {
                runCatching { cachedDepthMap = depthEst.estimate(frameInput).observation }
            }
            cachedSpatialMap = SceneGeometryAnalyzer.analyze(frame.bitmap, tracks, motionObs, nowNs)
            lastSpatialDurationMs = ((System.nanoTime() - tsp0) / 1_000_000f).coerceAtLeast(0.2f)
            if (lastSpatialMs > 0L) {
                spatialHz = spatialHz * 0.65f + (1000f / (nowMs - lastSpatialMs).coerceAtLeast(1L)) * 0.35f
            }
            lastSpatialMs = nowMs
        }

        val fastMs = ((System.nanoTime() - tFast0) / 1_000_000f).coerceAtLeast(0.5f)
        val globalRisk = RiskAssessmentEngine.assessGlobalRisk(tracks, quality.qualityScore01, false)
        val activeGestureFr = if (cfg.enableGestureRecognition) {
            cachedHumanAttrs?.takeIf { it.gestureConfirmed }?.let {
                "${it.gesture.labelFr} (${it.gesture.proposedActionFr})"
            }
        } else null

        val pState = PerceptionState(
            frameSequence = frame.sequenceNumber,
            timestampNs = nowNs,
            frameQuality = quality,
            cameraProfile = camProfile,
            activeObjectFramework = cachedObjectReport?.selectedFramework ?: VisionFramework.ML_KIT,
            activeObjectProviderId = cachedObjectReport?.selectedProviderId ?: "MlKitObjectDetectorProvider",
            capabilityWarningFr = cachedObjectReport?.warningFr,
            tracks = tracks,
            poses = cachedPoses,
            barcodes = cachedBarcodes,
            motion = motionObs,
            spatialMap = cachedSpatialMap,
            depthObservation = cachedDepthMap,
            globalRisk = globalRisk,
            pipelineRates = snapshotRates(fastMs),
            activeGestureLabelFr = activeGestureFr,
            isSceneObstructed = false,
            obstructionReasonFr = null
        )

        return buildLegacyResult(frame, cfg, pState, tracks, lastAiDurationMs.toLong().coerceAtLeast(1L))
    }

    private suspend fun runAiPath(
        frameInput: FrameInput,
        cfg: SchedulerExecutionConfig
    ): Triple<List<Detection>, List<PoseObservation>, HumanAttributes?> {
        return when (cfg.activeDetector) {
            DetectorType.NONE -> Triple(emptyList(), emptyList(), null)
            DetectorType.FACE_LANDMARKS -> {
                val batch = faceAdapter.detectFaces(frameInput, cfg.showLandmarks)
                val dets = batch.faces.map { f ->
                    val tax = AdvdsTaxonomy.classify("face", f.confidence)
                    Detection(
                        classId = "FACE",
                        label = tax.displayLabelFr,
                        family = tax.family,
                        importance = tax.importance,
                        box = f.box,
                        rawConfidence = f.confidence,
                        calibratedConfidence = f.confidence,
                        providerId = batch.providerId,
                        framework = batch.framework,
                        trackerHintId = f.trackingId,
                        landmarks = f.landmarks
                    )
                }
                Triple(dets, emptyList(), null)
            }
            DetectorType.PERSON_POSE -> {
                val poseEst = cachedPoseEstimator
                val rawBatch = poseEst?.estimate(frameInput)
                val enriched = if (rawBatch != null) {
                    humanPerceptionEngine.enrichPoses(rawBatch.poses)
                } else emptyList()
                val dets = enriched.map { p ->
                    val tax = AdvdsTaxonomy.classify("person", p.confidence)
                    Detection(
                        classId = "PERSON",
                        label = "Personne (${p.attributes.posture.labelFr})",
                        family = tax.family,
                        importance = tax.importance,
                        box = p.personBox,
                        rawConfidence = p.confidence,
                        calibratedConfidence = p.confidence,
                        providerId = rawBatch?.providerId ?: "PoseProvider",
                        framework = rawBatch?.framework ?: VisionFramework.ML_KIT,
                        landmarks = if (cfg.showLandmarks) {
                            p.landmarks.map { com.example.core.models.NormalizedPoint(it.x, it.y) }
                        } else emptyList(),
                        skeletonEdges = if (cfg.showLandmarks) p.skeletonEdges else emptyList()
                    )
                }
                Triple(dets, enriched, enriched.firstOrNull()?.attributes)
            }
            DetectorType.OBJECTS_GENERAL -> {
                val objDet = cachedObjectDetector
                val batch = objDet?.detect(frameInput)
                Triple(batch?.detections.orEmpty(), emptyList(), null)
            }
        }
    }

    private fun buildLegacyResult(
        frame: VideoFrame,
        cfg: SchedulerExecutionConfig,
        pState: PerceptionState,
        tracks: List<TrackState>,
        inferMs: Long
    ): VisionAnalysisResult {
        val nowMs = frame.receivedAtMs
        val legacyDetections = tracks.map { t ->
            AiDetection(
                label = t.label,
                category = t.classId,
                confidence = t.confidence,
                box = t.box,
                landmarks = t.landmarks,
                skeletonEdges = t.skeletonEdges
            )
        }
        val legacySubjects = tracks.map { it.toLegacySubject(nowMs) }
        val legacyFlow = OpticalFlowEstimate(
            driftX = pState.motion.cameraGlobalDriftX,
            driftY = pState.motion.cameraGlobalDriftY,
            rotationDeg = pState.motion.cameraRotationDeg,
            confidence = pState.motion.confidence,
            featureCount = pState.motion.featureCount,
            advisoryLabel = pState.motion.advisoryLabel
        )
        return VisionAnalysisResult(
            detections = legacyDetections,
            trackedSubjects = legacySubjects,
            opticalFlow = legacyFlow,
            inferenceTimeMs = inferMs,
            activeDetector = cfg.activeDetector,
            frameWidth = frame.width,
            frameHeight = frame.height,
            designatedTrackId = trackerEngine.designatedTrackId,
            lockedTrackId = trackerEngine.lockedTrackId,
            ambiguousRecoveryCandidates = trackerEngine.getAmbiguousLegacyCandidates(
                frame.sequenceNumber, frame.height, nowMs * 1_000_000L, cfg.calibration, cfg.distanceConfig
            ),
            isSceneObstructed = pState.isSceneObstructed,
            obstructionReasonFr = pState.obstructionReasonFr,
            timestampMs = nowMs,
            perceptionState = pState
        )
    }

    private fun snapshotRates(fastMs: Float): PipelineRatesSnapshot = PipelineRatesSnapshot(
        fastPathHz = fastHz,
        aiPathHz = aiHz,
        spatialPathHz = spatialHz,
        specializedPathHz = specializedHz,
        fastPathMs = fastMs,
        aiPathMs = lastAiDurationMs,
        spatialPathMs = lastSpatialDurationMs,
        specializedPathMs = lastSpecializedDurationMs
    )

    fun resetState() {
        trackerEngine.reset()
        motionAdapter.reset()
        frameQualityEngine.reset()
        humanPerceptionEngine.reset()
        cachedDetections = emptyList()
        cachedPoses = emptyList()
        cachedBarcodes = emptyList()
        cachedDepthMap = null
        cachedHumanAttrs = null
        lastAiMs = 0L
        lastSpatialMs = 0L
        lastSpecializedMs = 0L
    }

    fun close() {
        cachedObjectDetector?.close()
        cachedPoseEstimator?.close()
        cachedDepthEstimator?.close()
        faceAdapter.close()
        barcodeAdapter.close()
        motionAdapter.close()
    }
}
