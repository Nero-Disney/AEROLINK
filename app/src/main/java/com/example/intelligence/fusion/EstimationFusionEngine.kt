package com.example.intelligence.fusion

import com.example.vision.domain.entities.TrackLifecycleState
import com.example.vision.domain.observations.DepthObservation
import com.example.vision.domain.observations.DistanceEstimate
import com.example.vision.domain.observations.DistanceMethod
import com.example.vision.domain.observations.EstimateStatus
import kotlin.math.abs

/**
 * Moteur de fusion et validation croisée ADVDS :
 * - Détecte les contradictions entre estimation géométrique et profondeur dense
 * - Applique le vieillissement temporel (observationAgeMs)
 * - Refuse de masquer l'incertitude : VERIFIED / VALID / VALID_WITH_UNCERTAINTY / UNCERTAIN / INVALID
 */
object EstimationFusionEngine {

    fun fuseTrackReliability(
        lifecycle: TrackLifecycleState,
        detectionConfidence: Float,
        frameQualityScore: Float,
        observationAgeMs: Long,
        distanceEstimate: DistanceEstimate,
        depthMap: DepthObservation?,
        boxRelativeDepth: Float?
    ): EstimateStatus {
        if (frameQualityScore < 0.2f || lifecycle == TrackLifecycleState.LOST || lifecycle == TrackLifecycleState.REJECTED) {
            return EstimateStatus.INVALID
        }
        if (observationAgeMs > 450L || lifecycle == TrackLifecycleState.OCCLUDED || lifecycle == TrackLifecycleState.UNCERTAIN) {
            return EstimateStatus.UNCERTAIN
        }

        // Validation croisée si une carte de profondeur dense et une distance métrique sont simultanément disponibles
        if (depthMap != null && depthMap.isMetric && distanceEstimate.rangeMeters != null && boxRelativeDepth != null) {
            val depthModelMeters = boxRelativeDepth
            val metricMeters = distanceEstimate.rangeMeters
            val discrepancy = abs(depthModelMeters - metricMeters) / maxOf(depthModelMeters, metricMeters, 0.5f)
            if (discrepancy > 0.45f) {
                return EstimateStatus.UNCERTAIN
            }
            if (discrepancy < 0.20f && lifecycle == TrackLifecycleState.TRACKING && frameQualityScore > 0.65f) {
                return EstimateStatus.VERIFIED
            }
        }

        return when {
            distanceEstimate.method == DistanceMethod.ARUCO_PNP &&
                distanceEstimate.status == EstimateStatus.VERIFIED &&
                detectionConfidence >= 0.75f -> EstimateStatus.VERIFIED

            lifecycle in setOf(TrackLifecycleState.CONFIRMED, TrackLifecycleState.TRACKING) &&
                detectionConfidence >= 0.68f &&
                frameQualityScore >= 0.55f -> EstimateStatus.VALID

            else -> EstimateStatus.VALID_WITH_UNCERTAINTY
        }
    }
}
