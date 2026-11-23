package com.example.perception.tracking

import android.graphics.Bitmap
import com.example.core.models.DetectionZone
import com.example.core.models.DistanceConfidence
import com.example.core.models.NormalizedBox
import com.example.core.models.NormalizedPoint
import com.example.core.models.TargetDistanceConfig
import com.example.core.models.TrackStatus
import com.example.core.models.TrackedSubject
import com.example.core.models.TrackingMode
import com.example.intelligence.fusion.EstimationFusionEngine
import com.example.intelligence.risk.RiskAssessmentEngine
import com.example.perception.depth.DepthRangeEngine
import com.example.perception.human.HumanPerceptionEngine
import com.example.vision.domain.contracts.VisionFramework
import com.example.vision.domain.entities.CameraCalibration
import com.example.vision.domain.entities.ObjectFamily
import com.example.vision.domain.entities.ObstacleImportance
import com.example.vision.domain.entities.ReIdSignature
import com.example.vision.domain.entities.TrackLifecycleState
import com.example.vision.domain.entities.TrackState
import com.example.vision.domain.observations.BarcodeDetection
import com.example.vision.domain.observations.DepthObservation
import com.example.vision.domain.observations.Detection
import com.example.vision.domain.observations.DistanceMethod
import com.example.vision.domain.observations.EstimateStatus
import com.example.vision.domain.observations.HumanAttributes
import com.example.vision.domain.observations.MotionObservation
import com.example.vision.domain.observations.MotionStateLabel
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Moteur de suivi temporel ADVDS V1 :
 * - Cycle de vie complet : NEW -> TENTATIVE -> CONFIRMED -> TRACKING -> OCCLUDED -> UNCERTAIN -> REIDENTIFIED -> LOST/REJECTED
 * - ByteTrack 2 passes (haute confiance puis récupération basse confiance)
 * - Prédiction Kalman simplifiée avec covariance croissante en masquage + compensation mouvement caméra
 * - Ré-identification session uniquement (signature visuelle locale sans biométrie persistante)
 */
class AdvdsTrackerEngine(
    private val depthRangeEngine: DepthRangeEngine = DepthRangeEngine(),
    private val humanPerceptionEngine: HumanPerceptionEngine = HumanPerceptionEngine()
) {

    private data class InternalAdvdsTrack(
        val id: String,
        var classId: String,
        var label: String,
        var family: ObjectFamily,
        var importance: ObstacleImportance,
        var confidence: Float,
        var box: NormalizedBox,
        var vx: Float = 0f,
        var vy: Float = 0f,
        var expansionRatePerSec: Float = 0f,
        var covariance: Float = 0.25f,
        var hitStreak: Int = 1,
        var missedFrames: Int = 0,
        var lifecycle: TrackLifecycleState = TrackLifecycleState.NEW,
        var lastSeenNs: Long,
        var sourceFramework: VisionFramework,
        var sourceProviderId: String,
        val trajectory: ArrayDeque<NormalizedPoint> = ArrayDeque(32),
        var landmarks: List<NormalizedPoint> = emptyList(),
        var skeletonEdges: List<Pair<Int, Int>> = emptyList(),
        var humanAttributes: HumanAttributes? = null,
        var reIdSignature: ReIdSignature? = null,
        val isManualBox: Boolean = false
    )

    private val tracks = mutableListOf<InternalAdvdsTrack>()
    private var nextIdCounter = 100
    private var ambiguousCandidates: List<TrackState> = emptyList()
    private var lostLockedRefBox: NormalizedBox? = null

    @Volatile
    var designatedTrackId: String? = null
        private set

    @Volatile
    var lockedTrackId: String? = null
        private set

    @Synchronized
    fun designateTarget(trackId: String?) {
        designatedTrackId = trackId
        if (trackId == null) lockedTrackId = null
        ambiguousCandidates = emptyList()
    }

    @Synchronized
    fun lockTarget(trackId: String?) {
        if (trackId == null) {
            lockedTrackId = null
            return
        }
        if (lockedTrackId == trackId) {
            lockedTrackId = null
        } else {
            designatedTrackId = trackId
            lockedTrackId = trackId
            lostLockedRefBox = null
            ambiguousCandidates = emptyList()
        }
    }

    @Synchronized
    fun clearSelection() {
        designatedTrackId = null
        lockedTrackId = null
        lostLockedRefBox = null
        ambiguousCandidates = emptyList()
    }

    @Synchronized
    fun addManualTargetBox(box: NormalizedBox, nowNs: Long = System.nanoTime()): String {
        val id = "M-%03d".format(nextIdCounter++)
        val clamped = NormalizedBox(
            left = box.left.coerceIn(0f, 0.96f),
            top = box.top.coerceIn(0f, 0.96f),
            right = box.right.coerceIn((box.left + 0.04f).coerceAtMost(1f), 1f),
            bottom = box.bottom.coerceIn((box.top + 0.04f).coerceAtMost(1f), 1f)
        )
        val t = InternalAdvdsTrack(
            id = id,
            classId = "MANUAL_TARGET",
            label = "Zone manuelle",
            family = ObjectFamily.SPECIAL,
            importance = ObstacleImportance.DYNAMIC_OBJECT,
            confidence = 1f,
            box = clamped,
            lifecycle = TrackLifecycleState.CONFIRMED,
            lastSeenNs = nowNs,
            sourceFramework = VisionFramework.CUSTOM,
            sourceProviderId = "PilotManualDraw",
            isManualBox = true
        )
        appendTraj(t, clamped)
        tracks.add(t)
        designatedTrackId = id
        lockedTrackId = id
        return id
    }

    @Synchronized
    fun selectNearestOrLargest(): String? {
        val cand = tracks.filter { it.missedFrames <= 3 && it.lifecycle != TrackLifecycleState.REJECTED }
            .maxByOrNull { it.box.area } ?: return null
        designatedTrackId = cand.id
        return cand.id
    }

    @Synchronized
    fun selectCenterTarget(): String? {
        val cand = tracks.filter { it.missedFrames <= 3 && it.lifecycle != TrackLifecycleState.REJECTED }
            .minByOrNull { hypot(it.box.centerX - 0.5f, it.box.centerY - 0.5f) } ?: return null
        designatedTrackId = cand.id
        return cand.id
    }

    @Synchronized
    fun resolveAmbiguousCandidate(trackId: String) {
        designatedTrackId = trackId
        lockedTrackId = trackId
        ambiguousCandidates = emptyList()
        lostLockedRefBox = null
    }

    @Synchronized
    fun clearOnObstruction() {
        val iter = tracks.iterator()
        while (iter.hasNext()) {
            val t = iter.next()
            if (!t.isManualBox) {
                if (lockedTrackId == t.id) lockedTrackId = null
                if (designatedTrackId == t.id) designatedTrackId = null
                iter.remove()
            }
        }
        ambiguousCandidates = emptyList()
    }

    @Synchronized
    fun reset() {
        tracks.clear()
        clearSelection()
        depthRangeEngine.reset()
        nextIdCounter = 100
    }

    @Synchronized
    fun getAmbiguousLegacyCandidates(
        frameSequence: Long,
        frameHeightPx: Int,
        nowNs: Long,
        calibration: CameraCalibration,
        distanceConfig: TargetDistanceConfig
    ): List<TrackedSubject> = ambiguousCandidates.map { it.toLegacySubject(nowNs / 1_000_000L) }

    /**
     * Prédiction Kalman légère entre deux inférences lourdes (Fast Path CONTINU).
     * Retourne la liste complète des états prédits pour éviter tout clignotement HUD.
     */
    @Synchronized
    fun stepFastPrediction(
        motion: MotionObservation,
        depthMap: DepthObservation?,
        humanAttrs: HumanAttributes?,
        frameSequence: Long,
        nowNs: Long,
        frameQualityScore: Float,
        maxDisplayedBoxes: Int,
        frameHeightPx: Int,
        calibration: CameraCalibration,
        distanceConfig: TargetDistanceConfig,
        barcodes: List<BarcodeDetection> = emptyList()
    ): List<TrackState> {
        for (t in tracks) {
            if (t.isManualBox) continue
            // Compensation du mouvement global caméra pour éviter d'attribuer la dérive du drone à la cible
            val dx = (t.vx * 0.55f) - (motion.cameraGlobalDriftX * 0.35f)
            val dy = (t.vy * 0.55f) - (motion.cameraGlobalDriftY * 0.35f)
            if (t.missedFrames <= 2) {
                t.box = NormalizedBox(
                    left = (t.box.left + dx).coerceIn(0f, 1f),
                    top = (t.box.top + dy).coerceIn(0f, 1f),
                    right = (t.box.right + dx).coerceIn(0f, 1f),
                    bottom = (t.box.bottom + dy).coerceIn(0f, 1f)
                )
            }
            t.covariance = (t.covariance + 0.03f).coerceAtMost(1.0f)
        }
        return buildTrackStates(
            motion = motion,
            depthMap = depthMap,
            humanAttrs = humanAttrs,
            barcodes = barcodes,
            frameSequence = frameSequence,
            nowNs = nowNs,
            frameQualityScore = frameQualityScore,
            maxDisplayedBoxes = maxDisplayedBoxes,
            frameHeightPx = frameHeightPx,
            calibration = calibration,
            distanceConfig = distanceConfig
        )
    }

    @Synchronized
    fun updateWithObservations(
        detections: List<Detection>,
        barcodes: List<BarcodeDetection>,
        motion: MotionObservation,
        depthMap: DepthObservation?,
        humanAttrs: HumanAttributes?,
        sourceBitmap: Bitmap?,
        enableReId: Boolean,
        trackingMode: TrackingMode,
        detectionZone: DetectionZone,
        frameSequence: Long,
        nowNs: Long,
        frameQualityScore: Float,
        confidenceThreshold: Float,
        maxDisplayedBoxes: Int,
        abandonTimeoutMs: Long,
        frameHeightPx: Int,
        calibration: CameraCalibration,
        distanceConfig: TargetDistanceConfig
    ): List<TrackState> {
        // Ajoute les marqueurs QR détectés comme observations SPECIAL si présents
        val markerDetections = barcodes.map { bc ->
            Detection(
                classId = "MARKER",
                label = "Marqueur ${bc.formatLabel} (${bc.rawValue.take(8)})",
                family = ObjectFamily.SPECIAL,
                importance = ObstacleImportance.MARKER,
                box = bc.box,
                rawConfidence = 0.96f,
                calibratedConfidence = 0.96f,
                providerId = "MlKitBarcodeReaderProvider",
                framework = VisionFramework.ML_KIT,
                landmarks = bc.corners
            )
        }

        val allDetections = (detections + markerDetections).filter { det ->
            val modeOk = when (trackingMode) {
                TrackingMode.FACE_TRACKING -> det.classId == "FACE" || det.label.contains("Visage", true)
                TrackingMode.PERSON_TRACKING -> det.classId == "PERSON" || det.family == ObjectFamily.HUMAN
                else -> true
            }
            val zoneOk = if (trackingMode == TrackingMode.DETECTION_ZONES || detectionZone.enabled) {
                detectionZone.containsCenter(det.box)
            } else true
            modeOk && zoneOk
        }

        val highDetections = allDetections.filter { it.rawConfidence >= confidenceThreshold }
        val lowDetections = allDetections.filter { it.rawConfidence >= 0.28f && it.rawConfidence < confidenceThreshold }

        // 1. Prédiction amortie compensée par mouvement caméra
        for (t in tracks) {
            if (t.isManualBox) continue
            if (t.missedFrames < 2) {
                val stepX = t.vx * 0.60f - motion.cameraGlobalDriftX * 0.4f
                val stepY = t.vy * 0.60f - motion.cameraGlobalDriftY * 0.4f
                t.box = NormalizedBox(
                    left = (t.box.left + stepX).coerceIn(0f, 1f),
                    top = (t.box.top + stepY).coerceIn(0f, 1f),
                    right = (t.box.right + stepX).coerceIn(0f, 1f),
                    bottom = (t.box.bottom + stepY).coerceIn(0f, 1f)
                )
            }
            t.vx *= 0.65f
            t.vy *= 0.65f
        }

        val matchedTrackIdx = mutableSetOf<Int>()
        val matchedHighIdx = mutableSetOf<Int>()

        // Passe 1 ByteTrack : détections haute confiance
        associateGreedy(tracks, highDetections, matchedTrackIdx, matchedHighIdx, nowNs, sourceBitmap, enableReId)

        // Passe 2 ByteTrack : récupération sur détections basse confiance pour pistes déjà confirmées
        val remainingTrackIndices = tracks.indices.filter { it !in matchedTrackIdx && tracks[it].hitStreak >= 2 }
        if (remainingTrackIndices.isNotEmpty() && lowDetections.isNotEmpty()) {
            val subTracks = remainingTrackIndices.map { tracks[it] }
            val subMatchedT = mutableSetOf<Int>()
            val subMatchedD = mutableSetOf<Int>()
            associateGreedy(subTracks, lowDetections, subMatchedT, subMatchedD, nowNs, sourceBitmap, enableReId)
            subMatchedT.forEach { stIdx -> matchedTrackIdx.add(remainingTrackIndices[stIdx]) }
        }

        // Pistes non appariées : transition OCCLUDED / UNCERTAIN / LOST
        val iter = tracks.iterator()
        var idx = 0
        val timeoutNs = abandonTimeoutMs.coerceIn(500L, 6000L) * 1_000_000L
        while (iter.hasNext()) {
            val t = iter.next()
            if (idx !in matchedTrackIdx && !t.isManualBox) {
                t.missedFrames++
                t.covariance = (t.covariance + 0.16f).coerceAtMost(1.0f)
                t.lifecycle = when {
                    t.hitStreak < 2 && t.missedFrames >= 2 -> TrackLifecycleState.REJECTED
                    t.missedFrames <= 2 -> TrackLifecycleState.OCCLUDED
                    t.missedFrames <= 4 -> TrackLifecycleState.UNCERTAIN
                    else -> TrackLifecycleState.LOST
                }
                val ageNs = nowNs - t.lastSeenNs
                if (t.lifecycle == TrackLifecycleState.REJECTED || t.lifecycle == TrackLifecycleState.LOST || ageNs > timeoutNs) {
                    if (lockedTrackId == t.id) {
                        lostLockedRefBox = t.box
                        lockedTrackId = null
                    }
                    if (designatedTrackId == t.id) designatedTrackId = null
                    iter.remove()
                }
            }
            idx++
        }

        // Nouvelles pistes à partir des détections haute confiance non appariées
        for (di in highDetections.indices) {
            if (di in matchedHighIdx) continue
            val det = highDetections[di]
            val id = "T-%03d".format(nextIdCounter++)
            val sig = if (enableReId && det.family == ObjectFamily.HUMAN) {
                humanPerceptionEngine.extractSessionReIdSignature(sourceBitmap, det.box)
            } else null

            val nt = InternalAdvdsTrack(
                id = id,
                classId = det.classId,
                label = det.label,
                family = det.family,
                importance = det.importance,
                confidence = det.rawConfidence,
                box = det.box,
                covariance = 0.35f,
                hitStreak = 1,
                lifecycle = TrackLifecycleState.TENTATIVE,
                lastSeenNs = nowNs,
                sourceFramework = det.framework,
                sourceProviderId = det.providerId,
                landmarks = det.landmarks,
                skeletonEdges = det.skeletonEdges,
                reIdSignature = sig
            )
            appendTraj(nt, det.box)
            tracks.add(nt)
        }

        val activeIds = tracks.mapTo(HashSet(tracks.size)) { it.id }
        depthRangeEngine.pruneMissing(activeIds)

        return buildTrackStates(
            motion = motion,
            depthMap = depthMap,
            humanAttrs = humanAttrs,
            barcodes = barcodes,
            frameSequence = frameSequence,
            nowNs = nowNs,
            frameQualityScore = frameQualityScore,
            maxDisplayedBoxes = maxDisplayedBoxes,
            frameHeightPx = frameHeightPx,
            calibration = calibration,
            distanceConfig = distanceConfig
        )
    }

    private fun buildTrackStates(
        motion: MotionObservation,
        depthMap: DepthObservation?,
        humanAttrs: HumanAttributes?,
        barcodes: List<BarcodeDetection>,
        frameSequence: Long,
        nowNs: Long,
        frameQualityScore: Float,
        maxDisplayedBoxes: Int,
        frameHeightPx: Int,
        calibration: CameraCalibration,
        distanceConfig: TargetDistanceConfig
    ): List<TrackState> {
        // Conversion complète vers TrackState ADVDS (Depth + Fusion + Risk)
        val states = tracks.sortedWith(
            compareByDescending<InternalAdvdsTrack> { it.id == lockedTrackId }
                .thenByDescending { it.id == designatedTrackId }
                .thenByDescending { it.confidence }
        ).take(maxDisplayedBoxes.coerceIn(1, 12)).map { t ->
            val matchingBarcode = if (t.classId == "MARKER") {
                barcodes.minByOrNull { hypot(it.box.centerX - t.box.centerX, it.box.centerY - t.box.centerY) }
            } else null

            val (distEst, trackedRange) = depthRangeEngine.estimateForTarget(
                trackId = t.id,
                classId = t.classId,
                box = t.box,
                expansionRatePerSec = t.expansionRatePerSec,
                frameSequence = frameSequence,
                frameHeightPx = frameHeightPx,
                timestampNs = nowNs,
                calibration = calibration,
                distanceConfig = distanceConfig,
                depthMap = depthMap,
                markerRangeM = matchingBarcode?.estimatedDistanceM,
                markerUncertaintyM = matchingBarcode?.uncertaintyM
            )

            val ttc = depthRangeEngine.computeTimeToContactSec(t.box.area, t.expansionRatePerSec)
            val obsAgeMs = ((nowNs - t.lastSeenNs) / 1_000_000L).coerceAtLeast(0L)
            val motionLabel = when {
                t.expansionRatePerSec > 0.08f -> MotionStateLabel.APPROACHING
                t.expansionRatePerSec < -0.08f -> MotionStateLabel.RECEDING
                hypot((t.vx - motion.cameraGlobalDriftX).toDouble(), (t.vy - motion.cameraGlobalDriftY).toDouble()) > 0.015 ->
                    MotionStateLabel.LATERAL
                else -> MotionStateLabel.STATIONARY
            }

            val fusionStatus = EstimationFusionEngine.fuseTrackReliability(
                lifecycle = t.lifecycle,
                detectionConfidence = t.confidence,
                frameQualityScore = frameQualityScore,
                observationAgeMs = obsAgeMs,
                distanceEstimate = distEst,
                depthMap = depthMap,
                boxRelativeDepth = distEst.relativeDepth
            )

            val risk = RiskAssessmentEngine.assessTrackRisk(
                importance = t.importance,
                lifecycle = t.lifecycle,
                distance = distEst,
                motionState = motionLabel,
                timeToContactSec = ttc,
                frameQualityScore = frameQualityScore,
                observationAgeMs = obsAgeMs,
                apparentArea = t.box.area
            )

            val attrs = if (t.family == ObjectFamily.HUMAN) humanAttrs ?: t.humanAttributes else null

            TrackState(
                trackId = t.id,
                label = t.label,
                classId = t.classId,
                family = t.family,
                importance = t.importance,
                lifecycle = t.lifecycle,
                confidence = t.confidence,
                box = t.box,
                residualVelocityX = t.vx - motion.cameraGlobalDriftX,
                residualVelocityY = t.vy - motion.cameraGlobalDriftY,
                expansionRatePerSec = t.expansionRatePerSec,
                covariance = t.covariance,
                timeToContactSec = ttc,
                motionState = motionLabel,
                distanceEstimate = distEst,
                trackedRange = trackedRange,
                risk = risk,
                humanAttributes = attrs,
                reIdSignature = t.reIdSignature,
                trajectory = t.trajectory.toList(),
                landmarks = t.landmarks,
                skeletonEdges = t.skeletonEdges,
                sourceFramework = t.sourceFramework,
                sourceProviderId = t.sourceProviderId,
                isDesignated = t.id == designatedTrackId || t.id == lockedTrackId,
                isLocked = t.id == lockedTrackId,
                isManualBox = t.isManualBox,
                observationAgeMs = obsAgeMs,
                overallFusionStatus = fusionStatus
            )
        }

        // Désambiguïsation après perte de cible verrouillée
        val lostRef = lostLockedRefBox
        ambiguousCandidates = if (lockedTrackId == null && lostRef != null && states.size >= 2) {
            states.filter { hypot(it.box.centerX - lostRef.centerX, it.box.centerY - lostRef.centerY) < 0.32f }
                .take(3)
        } else {
            emptyList()
        }

        return states
    }

    private fun associateGreedy(
        trackPool: List<InternalAdvdsTrack>,
        detPool: List<Detection>,
        matchedTrackIdx: MutableSet<Int>,
        matchedDetIdx: MutableSet<Int>,
        nowNs: Long,
        sourceBitmap: Bitmap?,
        enableReId: Boolean
    ) {
        val pairs = mutableListOf<Triple<Int, Int, Float>>()
        for (ti in trackPool.indices) {
            for (di in detPool.indices) {
                val t = trackPool[ti]
                val d = detPool[di]
                val iou = computeIoU(t.box, d.box)
                val dist = hypot((t.box.centerX - d.box.centerX).toDouble(), (t.box.centerY - d.box.centerY).toDouble()).toFloat()
                var score = iou - dist * 0.35f
                if (enableReId && t.reIdSignature != null && d.family == ObjectFamily.HUMAN && sourceBitmap != null) {
                    val candSig = humanPerceptionEngine.extractSessionReIdSignature(sourceBitmap, d.box)
                    if (candSig != null) {
                        val colorDiff = abs(candSig.avgR - t.reIdSignature!!.avgR) +
                            abs(candSig.avgG - t.reIdSignature!!.avgG) +
                            abs(candSig.avgB - t.reIdSignature!!.avgB)
                        score += (0.25f - colorDiff * 0.3f)
                    }
                }
                if (iou >= 0.14f || dist <= 0.18f) {
                    pairs.add(Triple(ti, di, score))
                }
            }
        }
        pairs.sortByDescending { it.third }

        for ((ti, di, _) in pairs) {
            if (ti in matchedTrackIdx || di in matchedDetIdx) continue
            matchedTrackIdx.add(ti)
            matchedDetIdx.add(di)

            val t = trackPool[ti]
            val d = detPool[di]
            val dtSec = ((nowNs - t.lastSeenNs) / 1_000_000_000f).coerceIn(0.02f, 0.8f)
            val prevArea = t.box.area
            val rawDx = d.box.centerX - t.box.centerX
            val rawDy = d.box.centerY - t.box.centerY
            val moveMag = hypot(rawDx.toDouble(), rawDy.toDouble()).toFloat()
            val alpha = when {
                moveMag < 0.012f -> 0.38f
                moveMag < 0.045f -> 0.58f
                else -> 0.82f
            }

            val smoothed = NormalizedBox(
                left = t.box.left + (d.box.left - t.box.left) * alpha,
                top = t.box.top + (d.box.top - t.box.top) * alpha,
                right = t.box.right + (d.box.right - t.box.right) * alpha,
                bottom = t.box.bottom + (d.box.bottom - t.box.bottom) * alpha
            )

            val dArea = (d.box.area - prevArea) / dtSec
            t.expansionRatePerSec = t.expansionRatePerSec * 0.5f + dArea * 0.5f
            t.vx = t.vx * 0.45f + rawDx * 0.55f
            t.vy = t.vy * 0.45f + rawDy * 0.55f
            t.box = smoothed
            if (!t.isManualBox) {
                t.label = d.label
                t.classId = d.classId
                t.family = d.family
                t.importance = d.importance
                t.sourceFramework = d.framework
                t.sourceProviderId = d.providerId
            }
            t.confidence = t.confidence * 0.4f + d.rawConfidence * 0.6f
            t.landmarks = smoothLandmarks(t.landmarks, d.landmarks, alpha)
            if (d.skeletonEdges.isNotEmpty()) t.skeletonEdges = d.skeletonEdges

            val wasOccluded = t.missedFrames > 0
            t.missedFrames = 0
            t.hitStreak++
            t.covariance = (t.covariance * 0.6f).coerceAtLeast(0.08f)
            t.lastSeenNs = nowNs
            t.lifecycle = when {
                wasOccluded && t.hitStreak >= 3 -> TrackLifecycleState.REIDENTIFIED
                t.hitStreak >= 3 -> TrackLifecycleState.TRACKING
                t.hitStreak == 2 -> TrackLifecycleState.CONFIRMED
                else -> TrackLifecycleState.TENTATIVE
            }

            if (enableReId && d.family == ObjectFamily.HUMAN && sourceBitmap != null && t.reIdSignature == null) {
                t.reIdSignature = humanPerceptionEngine.extractSessionReIdSignature(sourceBitmap, smoothed)
            }
            appendTraj(t, smoothed)
        }
    }

    private fun smoothLandmarks(
        prev: List<NormalizedPoint>,
        curr: List<NormalizedPoint>,
        alpha: Float
    ): List<NormalizedPoint> {
        if (prev.size != curr.size || prev.isEmpty()) return curr
        return curr.mapIndexed { i, pt ->
            val p = prev[i]
            NormalizedPoint(
                x = p.x + (pt.x - p.x) * alpha,
                y = p.y + (pt.y - p.y) * alpha,
                label = pt.label
            )
        }
    }

    private fun appendTraj(t: InternalAdvdsTrack, box: NormalizedBox) {
        if (t.trajectory.size >= 28) t.trajectory.removeFirst()
        t.trajectory.addLast(NormalizedPoint(box.centerX, box.centerY))
    }

    private fun computeIoU(a: NormalizedBox, b: NormalizedBox): Float {
        val ix1 = max(a.left, b.left)
        val iy1 = max(a.top, b.top)
        val ix2 = min(a.right, b.right)
        val iy2 = min(a.bottom, b.bottom)
        val inter = (ix2 - ix1).coerceAtLeast(0f) * (iy2 - iy1).coerceAtLeast(0f)
        val union = a.area + b.area - inter
        return if (union > 1e-6f) inter / union else 0f
    }
}

fun TrackState.toLegacySubject(nowMs: Long): TrackedSubject {
    val legacyStatus = when (lifecycle) {
        TrackLifecycleState.OCCLUDED, TrackLifecycleState.UNCERTAIN -> TrackStatus.COASTING
        TrackLifecycleState.LOST, TrackLifecycleState.REJECTED -> TrackStatus.LOST
        else -> TrackStatus.ACTIVE
    }
    val distConf = when {
        distanceEstimate.rangeMeters != null &&
            (distanceEstimate.status == EstimateStatus.VERIFIED || distanceEstimate.status == EstimateStatus.VALID || distanceEstimate.status == EstimateStatus.VALID_WITH_UNCERTAINTY) ->
            DistanceConfidence.CALIBRATED_HIGH
        distanceEstimate.relativeDepth != null -> DistanceConfidence.RELATIVE
        else -> DistanceConfidence.UNAVAILABLE
    }
    val estM = trackedRange.filteredRangeMeters ?: distanceEstimate.rangeMeters
    val hint = when {
        estM != null -> {
            val unc = distanceEstimate.uncertaintyMeters?.let { " ±${"%.1f".format(it)}" } ?: ""
            "≈ ${"%.1f".format(estM)}$unc m"
        }
        distanceEstimate.relativeDepth != null -> when {
            distanceEstimate.relativeDepth < 0.35f -> "Proche (rel.)"
            distanceEstimate.relativeDepth < 0.65f -> "Moyen (rel.)"
            else -> "Loin (rel.)"
        }
        else -> "—"
    }

    return TrackedSubject(
        trackId = trackId,
        label = label,
        category = classId,
        confidence = confidence,
        box = box,
        status = legacyStatus,
        trajectory = trajectory,
        velocityX = residualVelocityX,
        velocityY = residualVelocityY,
        isLocked = isLocked,
        isDesignated = isDesignated,
        isManualBox = isManualBox,
        centerDeltaX = box.centerX - 0.5f,
        centerDeltaY = box.centerY - 0.5f,
        estimatedDistanceM = estM,
        distanceConfidence = distConf,
        distanceHintFr = hint,
        landmarks = landmarks,
        skeletonEdges = skeletonEdges,
        lastSeenMs = nowMs,
        lifecycleLabelFr = lifecycle.labelFr,
        riskLevelName = risk.level.name,
        motionStateLabelFr = motionState.labelFr,
        fusionStatusLabelFr = overallFusionStatus.labelFr,
        uncertaintyM = distanceEstimate.uncertaintyMeters,
        ttcSec = timeToContactSec,
        distanceMethodLabelFr = distanceEstimate.method.labelFr
    )
}
