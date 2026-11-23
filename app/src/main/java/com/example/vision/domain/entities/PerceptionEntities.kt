package com.example.vision.domain.entities

import com.example.core.models.NormalizedBox
import com.example.core.models.NormalizedPoint
import com.example.vision.domain.contracts.VisionFramework
import com.example.vision.domain.observations.BarcodeDetection
import com.example.vision.domain.observations.DepthObservation
import com.example.vision.domain.observations.DistanceEstimate
import com.example.vision.domain.observations.DistanceMethod
import com.example.vision.domain.observations.EstimateStatus
import com.example.vision.domain.observations.HumanAttributes
import com.example.vision.domain.observations.MotionObservation
import com.example.vision.domain.observations.MotionStateLabel
import com.example.vision.domain.observations.PoseObservation
import com.example.vision.domain.observations.SemanticClass

enum class TrackLifecycleState(val labelFr: String) {
    NEW("NOUVEAU"),
    TENTATIVE("TENTATIVE"),
    CONFIRMED("CONFIRMÉ"),
    TRACKING("SUIVI"),
    OCCLUDED("MASQUÉ"),
    UNCERTAIN("INCERTAIN"),
    REIDENTIFIED("RÉIDENTIFIÉ"),
    LOST("PERDU"),
    REJECTED("REJETÉ")
}

data class TrackedRange(
    val trackId: String,
    val currentRangeMeters: Float?,
    val filteredRangeMeters: Float?,
    val relativeDepth: Float?,
    val uncertaintyMeters: Float?,
    val rangeVelocityMps: Float?,
    val lastValidMeasurementNs: Long,
    val method: DistanceMethod,
    val status: EstimateStatus
)

/**
 * Signature visuelle locale temporaire (session uniquement, sans biométrie externe).
 */
data class ReIdSignature(
    val avgR: Float,
    val avgG: Float,
    val avgB: Float,
    val aspectRatio: Float,
    val areaNorm: Float
)

enum class RiskLevel(val labelFr: String) {
    LOW("FAIBLE"),
    MODERATE("MODÉRÉ"),
    HIGH("ÉLEVÉ"),
    UNKNOWN("INCONNU")
}

data class RiskAssessment(
    val level: RiskLevel = RiskLevel.UNKNOWN,
    val score: Float = 0f,
    val proximityD: Float = 0f,
    val ttcT: Float = 0f,
    val motionM: Float = 0f,
    val qualityQ: Float = 0f,
    val uncertaintyU: Float = 1f,
    val reasonFr: String = "Information insuffisante"
)

data class TrackState(
    val trackId: String,
    val label: String,
    val classId: String,
    val family: ObjectFamily,
    val importance: ObstacleImportance,
    val lifecycle: TrackLifecycleState,
    val confidence: Float,
    val box: NormalizedBox,
    val residualVelocityX: Float,
    val residualVelocityY: Float,
    val expansionRatePerSec: Float,
    val covariance: Float,
    val timeToContactSec: Float?,
    val motionState: MotionStateLabel,
    val distanceEstimate: DistanceEstimate,
    val trackedRange: TrackedRange,
    val risk: RiskAssessment,
    val humanAttributes: HumanAttributes? = null,
    val reIdSignature: ReIdSignature? = null,
    val trajectory: List<NormalizedPoint> = emptyList(),
    val landmarks: List<NormalizedPoint> = emptyList(),
    val skeletonEdges: List<Pair<Int, Int>> = emptyList(),
    val sourceFramework: VisionFramework,
    val sourceProviderId: String,
    val isDesignated: Boolean = false,
    val isLocked: Boolean = false,
    val isManualBox: Boolean = false,
    val observationAgeMs: Long = 0L,
    val overallFusionStatus: EstimateStatus = EstimateStatus.VALID_WITH_UNCERTAINTY
)

data class SpatialCell(
    val gridX: Int,
    val gridY: Int,
    val relativeDepth: Float?,
    val metricRangeMeters: Float?,
    val semanticClass: SemanticClass,
    val riskLevel: RiskLevel,
    val uncertainty: Float,
    val observed: Boolean
)

data class SceneGeometry(
    val horizonYNorm: Float = 0.45f,
    val vanishingPointX: Float = 0.5f,
    val vanishingPointY: Float = 0.45f,
    val dominantSlopeDeg: Float = 0f,
    val groundPlaneConfidence: Float = 0f
)

data class LocalSpatialMap(
    val cols: Int = 8,
    val rows: Int = 5,
    val cells: List<SpatialCell> = emptyList(),
    val geometry: SceneGeometry = SceneGeometry(),
    val timestampNs: Long = 0L
)

data class PipelineRatesSnapshot(
    val fastPathHz: Float = 0f,
    val aiPathHz: Float = 0f,
    val spatialPathHz: Float = 0f,
    val specializedPathHz: Float = 0f,
    val fastPathMs: Float = 0f,
    val aiPathMs: Float = 0f,
    val spatialPathMs: Float = 0f,
    val specializedPathMs: Float = 0f
)

data class PerceptionState(
    val frameSequence: Long = 0L,
    val timestampNs: Long = 0L,
    val frameQuality: FrameQualityMetrics = FrameQualityMetrics(),
    val cameraProfile: CameraProfile = CameraProfile(),
    val activeObjectFramework: VisionFramework = VisionFramework.ML_KIT,
    val activeObjectProviderId: String = "",
    val capabilityWarningFr: String? = null,
    val tracks: List<TrackState> = emptyList(),
    val poses: List<PoseObservation> = emptyList(),
    val barcodes: List<BarcodeDetection> = emptyList(),
    val motion: MotionObservation = MotionObservation(),
    val spatialMap: LocalSpatialMap = LocalSpatialMap(),
    val depthObservation: DepthObservation? = null,
    val globalRisk: RiskAssessment = RiskAssessment(),
    val pipelineRates: PipelineRatesSnapshot = PipelineRatesSnapshot(),
    val activeGestureLabelFr: String? = null,
    val isSceneObstructed: Boolean = false,
    val obstructionReasonFr: String? = null
)
