package com.example.intelligence.risk

import com.example.vision.domain.entities.ObstacleImportance
import com.example.vision.domain.entities.RiskAssessment
import com.example.vision.domain.entities.RiskLevel
import com.example.vision.domain.entities.TrackLifecycleState
import com.example.vision.domain.entities.TrackState
import com.example.vision.domain.observations.DistanceEstimate
import com.example.vision.domain.observations.EstimateStatus
import com.example.vision.domain.observations.MotionStateLabel

/**
 * Moteur d'évaluation des risques ADVDS :
 * R = w_d*D + w_t*T + w_m*M + w_q*Q + w_u*U
 * Règle stricte : UNKNOWN n'est jamais traité comme LOW.
 * Ce moteur émet uniquement des alertes visuelles consultatives, jamais de commandes de vol.
 */
object RiskAssessmentEngine {

    private const val WD = 0.34f
    private const val WT = 0.26f
    private const val WM = 0.18f
    private const val WQ = 0.10f
    private const val WU = 0.12f

    fun assessTrackRisk(
        importance: ObstacleImportance,
        lifecycle: TrackLifecycleState,
        distance: DistanceEstimate,
        motionState: MotionStateLabel,
        timeToContactSec: Float?,
        frameQualityScore: Float,
        observationAgeMs: Long,
        apparentArea: Float
    ): RiskAssessment {
        // Information insuffisante -> UNKNOWN explicite (jamais LOW)
        if (frameQualityScore < 0.22f || observationAgeMs > 600L || lifecycle == TrackLifecycleState.OCCLUDED) {
            return RiskAssessment(
                level = RiskLevel.UNKNOWN,
                score = 0.65f,
                qualityQ = 1f - frameQualityScore,
                uncertaintyU = 1f,
                reasonFr = when {
                    frameQualityScore < 0.22f -> "Qualité vidéo insuffisante"
                    observationAgeMs > 600L -> "Observation périmée (${observationAgeMs} ms)"
                    else -> "Cible masquée / incertaine"
                }
            )
        }

        // Proximité D [0..1]
        val proximityD = when {
            distance.rangeMeters != null -> (1f - (distance.rangeMeters / 6.0f)).coerceIn(0f, 1f)
            distance.relativeDepth != null -> (1f - distance.relativeDepth).coerceIn(0f, 1f)
            else -> (apparentArea / 0.25f).coerceIn(0f, 1f)
        }

        // Danger Time-to-Contact T [0..1]
        val ttcT = if (timeToContactSec != null) {
            (1f - (timeToContactSec / 5.0f)).coerceIn(0f, 1f)
        } else 0f

        // Cohérence mouvement M [0..1]
        val motionM = when (motionState) {
            MotionStateLabel.APPROACHING -> 0.90f
            MotionStateLabel.LATERAL -> 0.45f
            MotionStateLabel.STATIONARY -> 0.20f
            MotionStateLabel.RECEDING -> 0.05f
            MotionStateLabel.UNKNOWN -> 0.50f
        }

        val qualityQ = (1f - frameQualityScore).coerceIn(0f, 1f)
        val uncertaintyU = when (distance.status) {
            EstimateStatus.VERIFIED -> 0.10f
            EstimateStatus.VALID -> 0.25f
            EstimateStatus.VALID_WITH_UNCERTAINTY -> 0.45f
            EstimateStatus.UNCERTAIN -> 0.78f
            EstimateStatus.INVALID -> 1.0f
        }

        val rawScore = (WD * proximityD + WT * ttcT + WM * motionM + WQ * qualityQ + WU * uncertaintyU) *
            (0.75f + 0.25f * importance.riskWeight)
        val score = rawScore.coerceIn(0f, 1f)

        val level = when {
            score >= 0.62f || (timeToContactSec != null && timeToContactSec < 1.8f) -> RiskLevel.HIGH
            score >= 0.36f -> RiskLevel.MODERATE
            else -> RiskLevel.LOW
        }

        val reason = when (level) {
            RiskLevel.HIGH -> if (timeToContactSec != null) {
                "Rapprochement rapide (TTC ≈ ${"%.1f".format(timeToContactSec)}s)"
            } else {
                "Obstacle proche (${importance.labelFr})"
            }
            RiskLevel.MODERATE -> "Surveillance renforcée (${motionState.labelFr})"
            RiskLevel.LOW -> "Situation nominale"
            RiskLevel.UNKNOWN -> "Incertain"
        }

        return RiskAssessment(
            level = level,
            score = score,
            proximityD = proximityD,
            ttcT = ttcT,
            motionM = motionM,
            qualityQ = qualityQ,
            uncertaintyU = uncertaintyU,
            reasonFr = reason
        )
    }

    fun assessGlobalRisk(
        tracks: List<TrackState>,
        frameQualityScore: Float,
        isObstructed: Boolean
    ): RiskAssessment {
        if (isObstructed || frameQualityScore < 0.20f) {
            return RiskAssessment(
                level = RiskLevel.UNKNOWN,
                score = 0.8f,
                qualityQ = 1f - frameQualityScore,
                uncertaintyU = 1f,
                reasonFr = "Perte de perception visuelle"
            )
        }
        val highest = tracks.maxByOrNull { it.risk.score }
        if (highest != null) return highest.risk
        return RiskAssessment(
            level = RiskLevel.LOW,
            score = 0.1f,
            qualityQ = 1f - frameQualityScore,
            uncertaintyU = 0.25f,
            reasonFr = "Champ frontal dégagé (angle avant uniquement)"
        )
    }
}
