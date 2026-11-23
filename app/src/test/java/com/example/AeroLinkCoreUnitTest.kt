package com.example

import androidx.compose.ui.graphics.Color
import com.example.connectivity.wifi.DroneWifiManager
import com.example.core.models.AiDetection
import com.example.core.models.DetectionZone
import com.example.core.models.DistanceConfidence
import com.example.core.models.DroneFamily
import com.example.core.models.NormalizedBox
import com.example.core.models.TargetDistanceConfig
import com.example.core.models.TrackStatus
import com.example.core.models.TrackingMode
import com.example.storage.prefs.AppPreferences
import com.example.storage.prefs.SettingsCategory
import com.example.tracking.ObjectTracker
import com.example.ui.theme.ThemeMode
import com.example.ui.theme.computeContrastRatio
import com.example.ui.theme.parseHexColorOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AeroLinkCoreUnitTest {

    @Test
    fun ssidPrefixMatchesCorrectDroneFamilies() {
        assertEquals(DroneFamily.FLOW_UFO_BL60X, DroneWifiManager.matchFamilyBySsid("FLOW-UFO-4F81A2"))
        assertEquals(DroneFamily.KY_UFO_RTSP, DroneWifiManager.matchFamilyBySsid("KY-UFO-9921"))
        assertEquals(DroneFamily.WIFI_UAV, DroneWifiManager.matchFamilyBySsid("WIFI_UAV_123"))
        assertEquals(DroneFamily.LEWEI, DroneWifiManager.matchFamilyBySsid("VISUO-XS809"))
    }

    @Test
    fun objectTrackerPreservesSessionIdsAcrossMovementAndOcclusion() {
        val tracker = ObjectTracker()
        val det1 = AiDetection(
            label = "Personne",
            category = "PERSON",
            confidence = 0.9f,
            box = NormalizedBox(0.2f, 0.2f, 0.4f, 0.6f)
        )
        val t1 = tracker.update(listOf(det1), TrackingMode.MULTI_OBJECT, DetectionZone(), 1000L)
        assertEquals(1, t1.size)
        val firstId = t1.first().trackId
        assertTrue(firstId.startsWith("P"))

        val det2 = det1.copy(box = NormalizedBox(0.23f, 0.21f, 0.43f, 0.61f))
        val t2 = tracker.update(listOf(det2), TrackingMode.MULTI_OBJECT, DetectionZone(), 1100L)
        assertEquals(firstId, t2.first().trackId)
        assertEquals(2, t2.first().trajectory.size)

        val t3 = tracker.update(emptyList(), TrackingMode.MULTI_OBJECT, DetectionZone(), 1200L)
        assertEquals(1, t3.size)
        assertEquals(TrackStatus.COASTING, t3.first().status)
    }

    @Test
    fun detectionZoneFiltersOutOutsideTargetsWhenEnabled() {
        val tracker = ObjectTracker()
        val zone = DetectionZone(left = 0.3f, top = 0.3f, right = 0.7f, bottom = 0.7f, enabled = true)
        val outside = AiDetection("Visage", "FACE", 0.9f, NormalizedBox(0.05f, 0.05f, 0.15f, 0.15f))
        val inside = AiDetection("Visage", "FACE", 0.9f, NormalizedBox(0.4f, 0.4f, 0.55f, 0.55f))

        val res = tracker.update(listOf(outside, inside), TrackingMode.DETECTION_ZONES, zone, 1000L)
        assertEquals(1, res.size)
        assertFalse(res.first().box.left < 0.2f)
    }

    @Test
    fun trackerDistinguishesDesignationLockManualBoxAndDistanceEstimation() {
        val tracker = ObjectTracker()
        val near = AiDetection("Personne", "PERSON", 0.92f, NormalizedBox(0.1f, 0.1f, 0.3f, 0.7f)) // height 0.6
        val centered = AiDetection("Objet", "OBJECT", 0.88f, NormalizedBox(0.45f, 0.45f, 0.55f, 0.55f)) // center (0.5,0.5)

        val distConfig = TargetDistanceConfig(
            desiredDistanceM = 2.0f,
            referenceTargetHeightM = 1.70f,
            calibratedFocalPx = 600f
        )
        val initial = tracker.update(
            detections = listOf(near, centered),
            trackingMode = TrackingMode.MULTI_OBJECT,
            detectionZone = DetectionZone(),
            nowMs = 1000L,
            frameHeightPx = 1000,
            distanceConfig = distConfig
        )
        assertEquals(2, initial.size)
        val nearTrack = initial.first { it.category == "PERSON" }
        assertEquals(DistanceConfidence.CALIBRATED_HIGH, nearTrack.distanceConfidence)
        assertNotNull(nearTrack.estimatedDistanceM)
        // (1.70 * 600) / (0.6 * 1000) = 1.70 m
        assertEquals(1.7f, nearTrack.estimatedDistanceM!!, 0.05f)

        val nearestId = tracker.selectNearestOrLargest()
        assertEquals(nearTrack.trackId, nearestId)
        assertNull(tracker.lockedTrackId)

        tracker.lockTarget(nearestId)
        assertEquals(nearestId, tracker.lockedTrackId)

        tracker.clearSelection()
        assertNull(tracker.designatedTrackId)
        assertNull(tracker.lockedTrackId)

        val manualId = tracker.addManualTargetBox(NormalizedBox(0.3f, 0.3f, 0.5f, 0.5f), 1100L)
        assertTrue(manualId.startsWith("M"))
        assertEquals(manualId, tracker.lockedTrackId)
    }

    @Test
    fun themeContrastAndPreferencesModifiedCountWorkAccurately() {
        val highContrast = computeContrastRatio(Color(0xFF00E5FF), Color(0xFF000000))
        assertTrue(highContrast > 10f)

        val parsed = parseHexColorOrNull("#00E5FF")
        assertNotNull(parsed)
        assertNull(parseHexColorOrNull("invalid"))

        val modifiedPrefs = AppPreferences(
            themeMode = ThemeMode.OLED,
            leftHandedSticks = true,
            desiredDistanceM = 3.2f
        )
        assertEquals(1, modifiedPrefs.modifiedCountForCategory(SettingsCategory.APPEARANCE))
        assertEquals(1, modifiedPrefs.modifiedCountForCategory(SettingsCategory.CONTROLS_STICKS))
        assertEquals(1, modifiedPrefs.modifiedCountForCategory(SettingsCategory.AI_TRACKING))
        assertEquals(3, modifiedPrefs.totalModifiedCount())
    }

    @Test
    fun objectTrackerClearsAiTracksOnObstructionAndSmoothsJitter() {
        val tracker = ObjectTracker()
        val det1 = AiDetection("Visage", "FACE", 0.90f, NormalizedBox(0.20f, 0.20f, 0.40f, 0.40f))
        val initial = tracker.update(listOf(det1), TrackingMode.FACE_TRACKING, DetectionZone(), 1000L)
        assertEquals(1, initial.size)

        // Petit décalage (jitter < 0.012) : lissage EMA amorti (alpha = 0.38) au lieu d'un saut brutal
        val detJitter = det1.copy(box = NormalizedBox(0.206f, 0.204f, 0.406f, 0.404f))
        val smoothed = tracker.update(listOf(detJitter), TrackingMode.FACE_TRACKING, DetectionZone(), 1050L)
        assertEquals(1, smoothed.size)
        assertTrue(smoothed.first().box.left > 0.200f && smoothed.first().box.left < 0.205f)

        // Boîte manuelle + obstruction caméra -> les détections IA sont purgées, la boîte manuelle reste
        val manualId = tracker.addManualTargetBox(NormalizedBox(0.50f, 0.50f, 0.70f, 0.70f), 1100L)
        tracker.clearOnObstruction()
        val afterObstruction = tracker.update(emptyList(), TrackingMode.FACE_TRACKING, DetectionZone(), 1150L)
        assertEquals(1, afterObstruction.size)
        assertEquals(manualId, afterObstruction.first().trackId)

        // Vérifie la purge rapide d'une piste perdue (> 900 ms)
        val detTemp = AiDetection("Personne", "PERSON", 0.85f, NormalizedBox(0.10f, 0.10f, 0.30f, 0.50f))
        tracker.update(listOf(detTemp), TrackingMode.MULTI_OBJECT, DetectionZone(), 2000L)
        val expired = tracker.update(emptyList(), TrackingMode.MULTI_OBJECT, DetectionZone(), 3000L)
        assertTrue(expired.none { it.category == "PERSON" })
    }

    @Test
    fun advdsCoordinateTransformAndYolo26DecoderInvertLetterboxAccurately() {
        val sw = 1280
        val sh = 720
        val mw = 416
        val mh = 416
        val scale = mw.toFloat() / sw
        val padTop = (mh - sh * scale) / 2f
        val ct = com.example.vision.domain.contracts.CoordinateTransform(
            sourceWidth = sw,
            sourceHeight = sh,
            modelWidth = mw,
            modelHeight = mh,
            scaleX = scale,
            scaleY = scale,
            padLeftPx = 0f,
            padTopPx = padTop
        )

        val decoder = com.example.vision.adapters.onnx.YoloOutputDecoder()
        val labels = listOf(
            com.example.vision.domain.contracts.ModelLabel("0", "person"),
            com.example.vision.domain.contracts.ModelLabel("1", "alien_mystery")
        )
        // [x1, y1, x2, y2, score, classId] en espace modèle 416x416 (centre exact horizontalement, milieu bande utile verticalement)
        val y1 = padTop + (sh * scale) * 0.2f
        val y2 = padTop + (sh * scale) * 0.8f
        val matrix = arrayOf(
            floatArrayOf(104f, y1, 312f, y2, 0.91f, 0f),
            floatArrayOf(20f, y1, 80f, y2, 0.72f, 1f)
        )
        val dets = decoder.decodeMatrix(
            matrix = matrix,
            transform = ct,
            labels = labels,
            providerId = "OnnxYoloDetectorProvider",
            framework = com.example.vision.domain.contracts.VisionFramework.ONNX_RUNTIME,
            confThreshold = 0.45f
        )
        assertEquals(2, dets.size)
        val person = dets.first()
        assertEquals("PERSON", person.classId)
        assertEquals(0.25f, person.box.left, 0.02f)
        assertEquals(0.75f, person.box.right, 0.02f)
        assertEquals(0.20f, person.box.top, 0.02f)
        assertEquals(0.80f, person.box.bottom, 0.02f)

        val unknown = dets.last()
        assertTrue(unknown.isOpenSetUnknown)
        assertTrue(unknown.label.startsWith("UNKNOWN OBJECT"))
    }

    @Test
    fun advdsRiskAndFusionNeverMaskUnknownOrContradictions() {
        val highRisk = com.example.intelligence.risk.RiskAssessmentEngine.assessTrackRisk(
            importance = com.example.vision.domain.entities.ObstacleImportance.THIN_OBSTACLE,
            lifecycle = com.example.vision.domain.entities.TrackLifecycleState.TRACKING,
            distance = com.example.vision.domain.observations.DistanceEstimate(
                rangeMeters = 1.2f,
                relativeDepth = 0.2f,
                method = com.example.vision.domain.observations.DistanceMethod.ARUCO_PNP,
                uncertaintyMeters = 0.2f,
                confidence = 0.9f,
                sourceFrameSequence = 1L,
                calibrationId = "calib_1",
                timestampNs = 1000L,
                status = com.example.vision.domain.observations.EstimateStatus.VERIFIED
            ),
            motionState = com.example.vision.domain.observations.MotionStateLabel.APPROACHING,
            timeToContactSec = 1.1f,
            frameQualityScore = 0.9f,
            observationAgeMs = 30L,
            apparentArea = 0.22f
        )
        assertEquals(com.example.vision.domain.entities.RiskLevel.HIGH, highRisk.level)
        assertTrue(highRisk.score >= 0.62f)

        // Qualité insuffisante -> UNKNOWN explicite (jamais LOW)
        val lowQualityRisk = com.example.intelligence.risk.RiskAssessmentEngine.assessTrackRisk(
            importance = com.example.vision.domain.entities.ObstacleImportance.SURFACE,
            lifecycle = com.example.vision.domain.entities.TrackLifecycleState.TRACKING,
            distance = com.example.vision.domain.observations.DistanceEstimate(
                rangeMeters = null,
                relativeDepth = null,
                method = com.example.vision.domain.observations.DistanceMethod.UNKNOWN,
                uncertaintyMeters = null,
                confidence = 0f,
                sourceFrameSequence = 2L,
                calibrationId = null,
                timestampNs = 2000L,
                status = com.example.vision.domain.observations.EstimateStatus.INVALID
            ),
            motionState = com.example.vision.domain.observations.MotionStateLabel.STATIONARY,
            timeToContactSec = null,
            frameQualityScore = 0.12f,
            observationAgeMs = 10L,
            apparentArea = 0.05f
        )
        assertEquals(com.example.vision.domain.entities.RiskLevel.UNKNOWN, lowQualityRisk.level)

        // Contradiction > 45% entre profondeur dense (5.0m) et distance métrique (1.5m) -> UNCERTAIN
        val fused = com.example.intelligence.fusion.EstimationFusionEngine.fuseTrackReliability(
            lifecycle = com.example.vision.domain.entities.TrackLifecycleState.TRACKING,
            detectionConfidence = 0.88f,
            frameQualityScore = 0.85f,
            observationAgeMs = 40L,
            distanceEstimate = com.example.vision.domain.observations.DistanceEstimate(
                rangeMeters = 1.5f,
                relativeDepth = 0.3f,
                method = com.example.vision.domain.observations.DistanceMethod.KNOWN_OBJECT_SIZE,
                uncertaintyMeters = 0.3f,
                confidence = 0.8f,
                sourceFrameSequence = 3L,
                calibrationId = "calib_1",
                timestampNs = 3000L,
                status = com.example.vision.domain.observations.EstimateStatus.VALID
            ),
            depthMap = com.example.vision.domain.observations.DepthObservation(
                width = 4, height = 4, minDepth = 1f, maxDepth = 8f, isMetric = true, values = FloatArray(16) { 5.0f }
            ),
            boxRelativeDepth = 5.0f
        )
        assertEquals(com.example.vision.domain.observations.EstimateStatus.UNCERTAIN, fused)
    }

    @Test
    fun advdsCalibrationMarkerPnpHumanGestureAndNineStateLifecycleWork() {
        val calib = com.example.vision.adapters.geometry.CameraCalibrationEngine.calibrateFromReferenceObservations(
            widthPx = 640,
            heightPx = 480,
            knownDistanceMeters = 2.0f,
            realObjectHeightMeters = 1.60f,
            apparentHeightNorm = 0.5f
        )
        assertFalse(calib.isFactoryEstimated)
        // 0.5 * 480 = 240 px -> fy = (240 * 2.0) / 1.6 = 300 px
        assertEquals(300f, calib.fy, 1f)

        val corners = listOf(
            com.example.core.models.NormalizedPoint(0.4f, 0.4f),
            com.example.core.models.NormalizedPoint(0.5f, 0.4f),
            com.example.core.models.NormalizedPoint(0.5f, 0.5333f),
            com.example.core.models.NormalizedPoint(0.4f, 0.5333f)
        )
        val (markerDist, markerUnc) = com.example.vision.adapters.geometry.MarkerRangeEstimator.estimateFromMarkerCorners(
            corners = corners,
            fallbackBox = NormalizedBox(0.4f, 0.4f, 0.5f, 0.5333f),
            frameWidthPx = 640,
            frameHeightPx = 480,
            calibration = calib,
            physicalMarkerSizeMeters = 0.20f
        )
        assertNotNull(markerDist)
        assertNotNull(markerUnc)
        assertEquals(0.94f, markerDist!!, 0.15f)

        // Confirmation gestuelle multi-trames (>= 4 trames consécutives)
        val humanEngine = com.example.perception.human.HumanPerceptionEngine()
        val pose = com.example.vision.domain.observations.PoseObservation(
            personBox = NormalizedBox(0.3f, 0.2f, 0.6f, 0.9f),
            confidence = 0.92f,
            landmarks = listOf(
                com.example.vision.domain.observations.PoseLandmarkPoint(0, 0.45f, 0.28f, 0f, 0.9f),
                com.example.vision.domain.observations.PoseLandmarkPoint(12, 0.40f, 0.40f, 0f, 0.9f),
                com.example.vision.domain.observations.PoseLandmarkPoint(16, 0.38f, 0.18f, 0f, 0.9f)
            ),
            skeletonEdges = emptyList()
        )
        repeat(3) {
            val out = humanEngine.enrichPoses(listOf(pose)).first()
            assertFalse(out.attributes.gestureConfirmed)
        }
        val confirmedOut = humanEngine.enrichPoses(listOf(pose)).first()
        assertTrue(confirmedOut.attributes.gestureConfirmed)
        assertEquals(com.example.vision.domain.observations.HumanGesture.RAISED_ARM_LOCK, confirmedOut.attributes.gesture)

        // Cycle de vie 9 états : TENTATIVE -> CONFIRMED -> TRACKING -> OCCLUDED -> REIDENTIFIED
        val advdsTracker = com.example.perception.tracking.AdvdsTrackerEngine()
        val det = com.example.vision.domain.observations.Detection(
            classId = "PERSON",
            label = "Personne",
            family = com.example.vision.domain.entities.ObjectFamily.HUMAN,
            importance = com.example.vision.domain.entities.ObstacleImportance.DYNAMIC_OBJECT,
            box = NormalizedBox(0.3f, 0.2f, 0.5f, 0.7f),
            rawConfidence = 0.88f,
            calibratedConfidence = 0.88f,
            providerId = "Test",
            framework = com.example.vision.domain.contracts.VisionFramework.ONNX_RUNTIME
        )
        fun step(dets: List<com.example.vision.domain.observations.Detection>, tMs: Long) =
            advdsTracker.updateWithObservations(
                detections = dets,
                barcodes = emptyList(),
                motion = com.example.vision.domain.observations.MotionObservation(),
                depthMap = null,
                humanAttrs = null,
                sourceBitmap = null,
                enableReId = false,
                trackingMode = TrackingMode.MULTI_OBJECT,
                detectionZone = DetectionZone(),
                frameSequence = tMs / 33L,
                nowNs = tMs * 1_000_000L,
                frameQualityScore = 0.85f,
                confidenceThreshold = 0.45f,
                maxDisplayedBoxes = 6,
                abandonTimeoutMs = 1500L,
                frameHeightPx = 480,
                calibration = calib,
                distanceConfig = TargetDistanceConfig()
            )

        assertEquals(com.example.vision.domain.entities.TrackLifecycleState.TENTATIVE, step(listOf(det), 100L).first().lifecycle)
        assertEquals(com.example.vision.domain.entities.TrackLifecycleState.CONFIRMED, step(listOf(det), 180L).first().lifecycle)
        assertEquals(com.example.vision.domain.entities.TrackLifecycleState.TRACKING, step(listOf(det), 260L).first().lifecycle)
        assertEquals(com.example.vision.domain.entities.TrackLifecycleState.OCCLUDED, step(emptyList(), 340L).first().lifecycle)
        assertEquals(com.example.vision.domain.entities.TrackLifecycleState.REIDENTIFIED, step(listOf(det), 420L).first().lifecycle)
    }
}
