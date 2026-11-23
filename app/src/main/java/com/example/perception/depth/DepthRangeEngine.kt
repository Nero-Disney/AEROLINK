package com.example.perception.depth

import com.example.core.models.NormalizedBox
import com.example.core.models.TargetDistanceConfig
import com.example.vision.domain.entities.CameraCalibration
import com.example.vision.domain.entities.TrackedRange
import com.example.vision.domain.observations.DepthObservation
import com.example.vision.domain.observations.DistanceEstimate
import com.example.vision.domain.observations.DistanceMethod
import com.example.vision.domain.observations.EstimateStatus
import kotlin.math.abs

/**
 * Moteur d'estimation de profondeur et de distance (4 niveaux ADVDS) :
 * 1. Profondeur relative (jamais confondue avec des mètres)
 * 2. Profondeur métrique (carte dense ou marqueur PnP calibré)
 * 3. Distance objet (taille apparente sténopé Z = f * H / h)
 * 4. Time-to-Contact par expansion visuelle (tau = s / s_dot)
 */
class DepthRangeEngine {

    private data class RangeFilterState(
        var filteredMeters: Float,
        var velocityMps: Float = 0f,
        var lastNs: Long = 0L
    )

    private val rangeFilters = HashMap<String, RangeFilterState>()

    fun estimateForTarget(
        trackId: String,
        classId: String,
        box: NormalizedBox,
        expansionRatePerSec: Float,
        frameSequence: Long,
        frameHeightPx: Int,
        timestampNs: Long,
        calibration: CameraCalibration,
        distanceConfig: TargetDistanceConfig,
        depthMap: DepthObservation?,
        markerRangeM: Float? = null,
        markerUncertaintyM: Float? = null
    ): Pair<DistanceEstimate, TrackedRange> {
        val relativeProxy = (1f - (box.height.coerceIn(0.04f, 0.9f) / 0.9f)).coerceIn(0.05f, 0.95f)

        // Priorité 1 : Marqueur QR / ArUco PnP avec taille physique connue
        if (markerRangeM != null) {
            val filtered = smoothRange(trackId, markerRangeM, timestampNs)
            val est = DistanceEstimate(
                rangeMeters = markerRangeM,
                relativeDepth = relativeProxy,
                method = DistanceMethod.ARUCO_PNP,
                uncertaintyMeters = markerUncertaintyM ?: (markerRangeM * 0.12f),
                confidence = if (calibration.isFactoryEstimated) 0.78f else 0.92f,
                sourceFrameSequence = frameSequence,
                calibrationId = calibration.cameraProfileId,
                timestampNs = timestampNs,
                status = if (calibration.isFactoryEstimated) EstimateStatus.VALID_WITH_UNCERTAINTY else EstimateStatus.VERIFIED,
                noteFr = "PnP marqueur connu"
            )
            return est to TrackedRange(
                trackId = trackId,
                currentRangeMeters = markerRangeM,
                filteredRangeMeters = filtered.first,
                relativeDepth = relativeProxy,
                uncertaintyMeters = est.uncertaintyMeters,
                rangeVelocityMps = filtered.second,
                lastValidMeasurementNs = timestampNs,
                method = DistanceMethod.ARUCO_PNP,
                status = est.status
            )
        }

        // Priorité 2 : Carte de profondeur dense (médiane robuste + MAD)
        val depthPair = depthMap?.sampleMedianInBox(box)
        val depthRangeRel = if (depthPair != null && depthMap.maxDepth > depthMap.minDepth) {
            ((depthPair.first - depthMap.minDepth) / (depthMap.maxDepth - depthMap.minDepth)).coerceIn(0f, 1f)
        } else {
            relativeProxy
        }

        // Priorité 3 : Taille apparente sténopé si calibration disponible & cible humaine/connue
        val effectiveFocal = when {
            distanceConfig.calibratedFocalPx > 50f -> distanceConfig.calibratedFocalPx
            !calibration.isFactoryEstimated -> calibration.fy
            else -> 0f
        }
        val refHeight = when (classId) {
            "PERSON" -> distanceConfig.referenceTargetHeightM
            "VEHICLE" -> 1.55f
            "FACE" -> 0.22f
            else -> 0f
        }

        if (effectiveFocal > 50f && refHeight > 0f && box.height > 0.04f && distanceConfig.showDistanceInMeters) {
            val boxPx = (box.height * frameHeightPx.coerceAtLeast(240)).coerceAtLeast(8f)
            val pinholeRange = ((effectiveFocal * refHeight) / boxPx).coerceIn(0.25f, 45f)
            val uncertainty = (pinholeRange * 0.18f).coerceIn(0.15f, 4.5f)
            val filtered = smoothRange(trackId, pinholeRange, timestampNs)
            val est = DistanceEstimate(
                rangeMeters = pinholeRange,
                relativeDepth = depthRangeRel,
                method = DistanceMethod.KNOWN_OBJECT_SIZE,
                uncertaintyMeters = uncertainty,
                confidence = if (classId == "FACE" || classId == "PERSON") 0.74f else 0.58f,
                sourceFrameSequence = frameSequence,
                calibrationId = calibration.cameraProfileId,
                timestampNs = timestampNs,
                status = EstimateStatus.VALID_WITH_UNCERTAINTY,
                noteFr = "Sténopé sur taille de référence"
            )
            return est to TrackedRange(
                trackId = trackId,
                currentRangeMeters = pinholeRange,
                filteredRangeMeters = filtered.first,
                relativeDepth = depthRangeRel,
                uncertaintyMeters = uncertainty,
                rangeVelocityMps = filtered.second,
                lastValidMeasurementNs = timestampNs,
                method = DistanceMethod.KNOWN_OBJECT_SIZE,
                status = est.status
            )
        }

        // Niveau 1 uniquement : Profondeur relative + éventuel Time-to-Contact
        val ttcMethod = if (expansionRatePerSec > 0.12f) DistanceMethod.OPTICAL_FLOW_TTC else DistanceMethod.MONOCULAR_DEPTH
        val relEst = DistanceEstimate(
            rangeMeters = null,
            relativeDepth = depthRangeRel,
            method = if (depthMap != null) DistanceMethod.MONOCULAR_DEPTH else ttcMethod,
            uncertaintyMeters = null,
            confidence = if (depthMap != null) 0.62f else 0.45f,
            sourceFrameSequence = frameSequence,
            calibrationId = calibration.cameraProfileId,
            timestampNs = timestampNs,
            status = EstimateStatus.VALID_WITH_UNCERTAINTY,
            noteFr = "Profondeur relative (calibration requise pour mètres)"
        )
        return relEst to TrackedRange(
            trackId = trackId,
            currentRangeMeters = null,
            filteredRangeMeters = null,
            relativeDepth = depthRangeRel,
            uncertaintyMeters = null,
            rangeVelocityMps = null,
            lastValidMeasurementNs = timestampNs,
            method = relEst.method,
            status = relEst.status
        )
    }

    fun computeTimeToContactSec(apparentArea: Float, expansionRatePerSec: Float): Float? {
        if (apparentArea < 0.005f || expansionRatePerSec <= 0.08f) return null
        val tau = (apparentArea / expansionRatePerSec).coerceIn(0.2f, 25f)
        return tau
    }

    private fun smoothRange(trackId: String, measuredM: Float, timestampNs: Long): Pair<Float, Float> {
        val prev = rangeFilters[trackId]
        if (prev == null) {
            rangeFilters[trackId] = RangeFilterState(measuredM, 0f, timestampNs)
            return measuredM to 0f
        }
        val dtSec = ((timestampNs - prev.lastNs) / 1_000_000_000f).coerceIn(0.02f, 1.0f)
        val rawVel = ((measuredM - prev.filteredMeters) / dtSec).coerceIn(-8f, 8f)
        val alpha = if (abs(measuredM - prev.filteredMeters) > 1.2f) 0.48f else 0.30f
        val nextFiltered = prev.filteredMeters + (measuredM - prev.filteredMeters) * alpha
        val nextVel = prev.velocityMps * 0.6f + rawVel * 0.4f
        prev.filteredMeters = nextFiltered
        prev.velocityMps = nextVel
        prev.lastNs = timestampNs
        return nextFiltered to nextVel
    }

    fun pruneMissing(activeIds: Set<String>) {
        rangeFilters.keys.retainAll(activeIds)
    }

    fun reset() {
        rangeFilters.clear()
    }
}
