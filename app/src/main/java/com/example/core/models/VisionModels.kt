package com.example.core.models

enum class DetectorType(val labelFr: String, val modelInfo: String) {
    NONE("Désactivé", "Aucun détecteur actif"),
    FACE_LANDMARKS("Visages & Repères", "ML Kit Face Detector (points faciaux)"),
    PERSON_POSE("Personnes & Posture", "ML Kit Pose Landmarker (33 articulations)"),
    OBJECTS_GENERAL("Objets & Véhicules", "ML Kit Object Detector (catégories locales)")
}

enum class TrackingMode(val labelFr: String, val descriptionFr: String) {
    FACE_TRACKING("Suivi Visage", "Suit un visage détecté avec identifiant de session"),
    PERSON_TRACKING("Suivi Personne", "Suit la silhouette et la posture humaine"),
    MULTI_OBJECT("Multi-Objets", "Suivi simultané avec IDs persistants (SORT/ByteTrack)"),
    OBJECT_LOCK("Verrouillage Cible", "Verrouille une cible choisie manuellement dans le HUD"),
    TRAJECTORY("Trajectoires", "Affiche l'historique du déplacement sur l'image"),
    CENTER_TRACKING("Centrage HUD", "Mesure l'écart entre la cible et le viseur central"),
    DETECTION_ZONES("Zone de détection", "Filtre les détections dans un cadre ROI actif")
}

enum class OpticalFlowLevel(val labelFr: String, val detailFr: String) {
    OFF("Désactivé", "Aucune analyse du flux optique"),
    LEVEL_1_ANALYSIS("Niveau 1 — Analyse", "Estime le mouvement caméra sans assistance"),
    LEVEL_2_ASSISTANCE("Niveau 2 — Assistance", "Alerte visuelle de dérive latérale / rotation"),
    LEVEL_3_EXPERIMENTAL("Niveau 3 — Expérimental", "Boucle de guidage réservée aux protocoles vérifiés")
}

enum class HardwareBackend(val labelFr: String) {
    CPU("CPU (XNNPack)"),
    GPU("GPU (OpenGL / Adreno)"),
    NNAPI("NPU / NNAPI")
}

enum class SelectionStage(val labelFr: String) {
    NONE("Non sélectionné"),
    DESIGNATED("Désigné"),
    LOCKED("Verrouillé")
}

enum class DistanceConfidence(val labelFr: String) {
    CALIBRATED_HIGH("Calibrée"),
    RELATIVE("Relative"),
    UNAVAILABLE("Indisponible")
}

data class TargetDistanceConfig(
    val desiredDistanceM: Float = 2.5f,
    val referenceTargetHeightM: Float = 1.70f,
    val calibratedFocalPx: Float = 0f,
    val showDistanceInMeters: Boolean = true,
    val toleranceM: Float = 0.35f
)

data class NormalizedBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val area: Float get() = width * height
}

data class NormalizedPoint(
    val x: Float,
    val y: Float,
    val label: String = ""
)

data class AiDetection(
    val label: String,
    val category: String,
    val confidence: Float,
    val box: NormalizedBox,
    val landmarks: List<NormalizedPoint> = emptyList(),
    val skeletonEdges: List<Pair<Int, Int>> = emptyList()
)

enum class TrackStatus(val labelFr: String) {
    ACTIVE("Suivi actif"),
    COASTING("Masqué — récupération"),
    LOST("Cible perdue")
}

data class TrackedSubject(
    val trackId: String,
    val label: String,
    val category: String,
    val confidence: Float,
    val box: NormalizedBox,
    val status: TrackStatus,
    val trajectory: List<NormalizedPoint>,
    val velocityX: Float,
    val velocityY: Float,
    val isLocked: Boolean,
    val isDesignated: Boolean = false,
    val isManualBox: Boolean = false,
    val centerDeltaX: Float,
    val centerDeltaY: Float,
    val estimatedDistanceM: Float? = null,
    val distanceConfidence: DistanceConfidence = DistanceConfidence.UNAVAILABLE,
    val distanceHintFr: String = "—",
    val landmarks: List<NormalizedPoint> = emptyList(),
    val skeletonEdges: List<Pair<Int, Int>> = emptyList(),
    val lastSeenMs: Long,
    val lifecycleLabelFr: String = "SUIVI",
    val riskLevelName: String = "UNKNOWN",
    val motionStateLabelFr: String = "Stationnaire",
    val fusionStatusLabelFr: String = "Valide",
    val uncertaintyM: Float? = null,
    val ttcSec: Float? = null,
    val distanceMethodLabelFr: String = ""
) {
    val selectionStage: SelectionStage
        get() = when {
            isLocked -> SelectionStage.LOCKED
            isDesignated -> SelectionStage.DESIGNATED
            else -> SelectionStage.NONE
        }
}

data class OpticalFlowEstimate(
    val driftX: Float = 0f,
    val driftY: Float = 0f,
    val rotationDeg: Float = 0f,
    val confidence: Float = 0f,
    val featureCount: Int = 0,
    val advisoryLabel: String = "Stable"
)

data class DetectionZone(
    val left: Float = 0.15f,
    val top: Float = 0.15f,
    val right: Float = 0.85f,
    val bottom: Float = 0.85f,
    val enabled: Boolean = false
) {
    fun containsCenter(box: NormalizedBox): Boolean {
        if (!enabled) return true
        return box.centerX in left..right && box.centerY in top..bottom
    }
}

data class VisionAnalysisResult(
    val detections: List<AiDetection> = emptyList(),
    val trackedSubjects: List<TrackedSubject> = emptyList(),
    val opticalFlow: OpticalFlowEstimate = OpticalFlowEstimate(),
    val inferenceTimeMs: Long = 0L,
    val activeDetector: DetectorType = DetectorType.NONE,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val designatedTrackId: String? = null,
    val lockedTrackId: String? = null,
    val ambiguousRecoveryCandidates: List<TrackedSubject> = emptyList(),
    val isSceneObstructed: Boolean = false,
    val obstructionReasonFr: String? = null,
    val timestampMs: Long = System.currentTimeMillis(),
    val perceptionState: com.example.vision.domain.entities.PerceptionState? = null
)
