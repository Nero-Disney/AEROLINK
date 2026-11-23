package com.example.vision.domain.observations

import com.example.core.models.NormalizedBox
import com.example.core.models.NormalizedPoint
import com.example.vision.domain.contracts.CoordinateSpace
import com.example.vision.domain.contracts.VisionFramework
import com.example.vision.domain.contracts.VisionTask
import com.example.vision.domain.entities.ObjectFamily
import com.example.vision.domain.entities.ObstacleImportance

data class Detection(
    val classId: String,
    val label: String,
    val family: ObjectFamily,
    val importance: ObstacleImportance,
    val box: NormalizedBox,
    val rawConfidence: Float,
    val calibratedConfidence: Float?,
    val providerId: String,
    val framework: VisionFramework,
    val coordinateSpace: CoordinateSpace = CoordinateSpace.SOURCE_IMAGE,
    val isOpenSetUnknown: Boolean = false,
    val trackerHintId: Int? = null,
    val landmarks: List<NormalizedPoint> = emptyList(),
    val skeletonEdges: List<Pair<Int, Int>> = emptyList()
)

data class DetectionBatch(
    val frameSequence: Long,
    val timestampNs: Long,
    val inferenceDurationMs: Float,
    val frameQualityScore: Float,
    val detections: List<Detection>,
    val providerId: String,
    val framework: VisionFramework,
    val capabilityNoteFr: String? = null
)

enum class HumanPosture(val labelFr: String) {
    STANDING("Debout"),
    SITTING("Assis"),
    CROUCHING("Accroupi"),
    UNKNOWN("Inconnue")
}

enum class HumanFacing(val labelFr: String) {
    FRONT("Face"),
    BACK("Dos"),
    SIDE("Profil"),
    UNKNOWN("Inconnue")
}

enum class HumanGesture(val labelFr: String, val proposedActionFr: String) {
    NONE("Aucun geste", ""),
    OPEN_PALM_STOP("Main / Bras d'arrêt", "Demande arrêt suivi"),
    RAISED_ARM_LOCK("Bras levé", "Demande verrouillage cible"),
    POINTING_DIRECTION("Bras pointé", "Sélection directionnelle"),
    BOTH_ARMS_CUSTOM("Deux bras levés", "Marqueur visuel / Commande")
}

data class HumanAttributes(
    val posture: HumanPosture = HumanPosture.UNKNOWN,
    val facing: HumanFacing = HumanFacing.UNKNOWN,
    val gesture: HumanGesture = HumanGesture.NONE,
    val gestureConfirmedFrames: Int = 0,
    val gestureConfirmed: Boolean = false,
    val visibleJointRatio: Float = 0f
)

data class PoseLandmarkPoint(
    val index: Int,
    val x: Float,
    val y: Float,
    val relativeZ: Float,
    val inFrameLikelihood: Float
)

data class PoseObservation(
    val personBox: NormalizedBox,
    val confidence: Float,
    val landmarks: List<PoseLandmarkPoint>,
    val skeletonEdges: List<Pair<Int, Int>>,
    val attributes: HumanAttributes = HumanAttributes()
)

data class PoseBatch(
    val frameSequence: Long,
    val timestampNs: Long,
    val inferenceDurationMs: Float,
    val poses: List<PoseObservation>,
    val providerId: String,
    val framework: VisionFramework
)

data class FaceObservation(
    val box: NormalizedBox,
    val trackingId: Int?,
    val confidence: Float,
    val landmarks: List<NormalizedPoint>,
    val headEulerY: Float,
    val headEulerZ: Float
)

data class FaceBatch(
    val frameSequence: Long,
    val timestampNs: Long,
    val inferenceDurationMs: Float,
    val faces: List<FaceObservation>,
    val providerId: String,
    val framework: VisionFramework
)

enum class DistanceMethod(val labelFr: String) {
    MONOCULAR_DEPTH("Profondeur monoculaire"),
    KNOWN_OBJECT_SIZE("Taille connue (Sténopé)"),
    ARUCO_PNP("Marqueur PnP"),
    APRILTAG_PNP("AprilTag PnP"),
    GROUND_PLANE("Plan du sol"),
    OPTICAL_FLOW_TTC("Expansion / TTC"),
    VISUAL_ODOMETRY("Odométrie visuelle"),
    UNKNOWN("Indisponible")
}

enum class EstimateStatus(val labelFr: String) {
    VERIFIED("Vérifié"),
    VALID("Valide"),
    VALID_WITH_UNCERTAINTY("Valide (avec incertitude)"),
    UNCERTAIN("Incertain"),
    INVALID("Invalide")
}

data class DistanceEstimate(
    val rangeMeters: Float?,
    val relativeDepth: Float?,
    val method: DistanceMethod,
    val uncertaintyMeters: Float?,
    val confidence: Float,
    val sourceFrameSequence: Long,
    val calibrationId: String?,
    val timestampNs: Long,
    val status: EstimateStatus,
    val noteFr: String = ""
)

data class DepthObservation(
    val width: Int,
    val height: Int,
    val minDepth: Float,
    val maxDepth: Float,
    val isMetric: Boolean,
    val values: FloatArray,
    val calibrationScale: Float = 1f,
    val calibrationShift: Float = 0f
) {
    fun sampleMedianInBox(box: NormalizedBox): Pair<Float, Float> {
        if (width <= 0 || height <= 0 || values.isEmpty()) return 0.5f to 0.5f
        val x0 = ((box.left + box.width * 0.2f) * width).toInt().coerceIn(0, width - 1)
        val x1 = ((box.right - box.width * 0.2f) * width).toInt().coerceIn(x0, width - 1)
        val y0 = ((box.top + box.height * 0.2f) * height).toInt().coerceIn(0, height - 1)
        val y1 = ((box.bottom - box.height * 0.2f) * height).toInt().coerceIn(y0, height - 1)
        val samples = ArrayList<Float>((x1 - x0 + 1) * (y1 - y0 + 1))
        for (y in y0..y1) {
            val rowOff = y * width
            for (x in x0..x1) {
                val idx = rowOff + x
                if (idx in values.indices) samples.add(values[idx])
            }
        }
        if (samples.isEmpty()) return 0.5f to 0.5f
        samples.sort()
        val median = samples[samples.size / 2]
        val deviations = samples.map { kotlin.math.abs(it - median) }.sorted()
        val mad = deviations[deviations.size / 2]
        return median to mad
    }
}

data class DepthResult(
    val frameSequence: Long,
    val timestampNs: Long,
    val inferenceDurationMs: Float,
    val observation: DepthObservation?,
    val providerId: String,
    val framework: VisionFramework,
    val status: EstimateStatus
)

enum class MotionStateLabel(val labelFr: String) {
    STATIONARY("Stationnaire"),
    APPROACHING("En approche"),
    RECEDING("Éloignement"),
    LATERAL("Mouvement latéral"),
    UNKNOWN("Inconnu")
}

data class MotionVector2D(
    val x: Float,
    val y: Float,
    val dx: Float,
    val dy: Float,
    val isInlier: Boolean
)

data class MotionObservation(
    val cameraGlobalDriftX: Float = 0f,
    val cameraGlobalDriftY: Float = 0f,
    val cameraRotationDeg: Float = 0f,
    val inlierRatio: Float = 0f,
    val featureCount: Int = 0,
    val vectors: List<MotionVector2D> = emptyList(),
    val confidence: Float = 0f,
    val advisoryLabel: String = "Stable"
)

data class MotionResult(
    val frameSequence: Long,
    val timestampNs: Long,
    val durationMs: Float,
    val observation: MotionObservation,
    val providerId: String
)

enum class SemanticClass(val labelFr: String) {
    SKY("Ciel"),
    GROUND("Sol"),
    VEGETATION("Végétation"),
    BUILDING("Bâtiment"),
    ROAD("Route"),
    WATER("Eau"),
    PERSON("Personne"),
    VEHICLE("Véhicule"),
    UNKNOWN("Inconnu")
}

data class SegmentationObservation(
    val width: Int,
    val height: Int,
    val classMap: ByteArray
)

data class SegmentationResult(
    val frameSequence: Long,
    val timestampNs: Long,
    val inferenceDurationMs: Float,
    val observation: SegmentationObservation?,
    val providerId: String,
    val framework: VisionFramework
)

data class ClassificationResult(
    val frameSequence: Long,
    val topClassId: String,
    val topLabel: String,
    val confidence: Float,
    val isUnknownOpenSet: Boolean
)

data class BarcodeDetection(
    val rawValue: String,
    val formatLabel: String,
    val box: NormalizedBox,
    val corners: List<NormalizedPoint>,
    val estimatedDistanceM: Float?,
    val uncertaintyM: Float?
)

data class BarcodeBatch(
    val frameSequence: Long,
    val timestampNs: Long,
    val durationMs: Float,
    val barcodes: List<BarcodeDetection>,
    val providerId: String
)

data class VisionObservation(
    val id: String,
    val frameSequence: Long,
    val timestampNs: Long,
    val sourceFramework: VisionFramework,
    val sourceModel: String,
    val task: VisionTask,
    val quality: Float,
    val payload: ObservationPayload
)

sealed interface ObservationPayload {
    data class DetectionPayload(val batch: DetectionBatch) : ObservationPayload
    data class PosePayload(val batch: PoseBatch) : ObservationPayload
    data class DepthPayload(val result: DepthResult) : ObservationPayload
    data class MotionPayload(val result: MotionResult) : ObservationPayload
    data class SegmentationPayload(val result: SegmentationResult) : ObservationPayload
    data class BarcodePayload(val batch: BarcodeBatch) : ObservationPayload
}
