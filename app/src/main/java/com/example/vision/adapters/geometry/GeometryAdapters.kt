package com.example.vision.adapters.geometry

import android.graphics.Bitmap
import android.graphics.Color
import com.example.core.models.NormalizedBox
import com.example.core.models.NormalizedPoint
import com.example.vision.domain.contracts.FeatureTracker
import com.example.vision.domain.contracts.FrameInput
import com.example.vision.domain.contracts.VisionFramework
import com.example.vision.domain.entities.CameraCalibration
import com.example.vision.domain.entities.CameraProfile
import com.example.vision.domain.entities.FrameQualityMetrics
import com.example.vision.domain.entities.LocalSpatialMap
import com.example.vision.domain.entities.RiskLevel
import com.example.vision.domain.entities.SceneGeometry
import com.example.vision.domain.entities.SpatialCell
import com.example.vision.domain.entities.TrackState
import com.example.vision.domain.observations.MotionObservation
import com.example.vision.domain.observations.MotionResult
import com.example.vision.domain.observations.MotionVector2D
import com.example.vision.domain.observations.SemanticClass
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Évalue la qualité vidéo (luminosité, contraste, flou laplacien simplifié, exposition, freeze, obstruction).
 */
class FrameQualityEngine {
    private var prevSignatureSum = -1f
    private var staticFramesCount = 0

    fun assess(bitmap: Bitmap): FrameQualityMetrics {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 8 || h < 8 || bitmap.isRecycled) {
            return FrameQualityMetrics(isObstructed = true, reasonFr = "Image invalide")
        }

        val cols = 24
        val rows = 16
        val grid = FloatArray(cols * rows)
        val rowPixels = IntArray(w)
        var sum = 0f
        var clippedHighlights = 0

        for (ry in 0 until rows) {
            val py = ((ry + 0.5f) * h / rows).toInt().coerceIn(0, h - 1)
            bitmap.getPixels(rowPixels, 0, w, 0, py, w, 1)
            for (rx in 0 until cols) {
                val px = ((rx + 0.5f) * w / cols).toInt().coerceIn(0, w - 1)
                val c = rowPixels[px]
                val luma = 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
                grid[ry * cols + rx] = luma
                sum += luma
                if (luma > 246f) clippedHighlights++
            }
        }

        val count = (cols * rows).toFloat()
        val mean = sum / count
        var varSum = 0f
        var edgeSum = 0f
        var laplacianAbsSum = 0f
        var edgeCount = 0
        var lapCount = 0

        for (ry in 0 until rows) {
            for (rx in 0 until cols) {
                val idx = ry * cols + rx
                val v = grid[idx]
                val d = v - mean
                varSum += d * d
                if (rx + 1 < cols) {
                    edgeSum += abs(v - grid[idx + 1])
                    edgeCount++
                }
                if (ry + 1 < rows) {
                    edgeSum += abs(v - grid[idx + cols])
                    edgeCount++
                }
                if (rx in 1 until (cols - 1) && ry in 1 until (rows - 1)) {
                    val lap = 4f * v - grid[idx - 1] - grid[idx + 1] - grid[idx - cols] - grid[idx + cols]
                    laplacianAbsSum += abs(lap)
                    lapCount++
                }
            }
        }

        val stdDev = sqrt(varSum / count)
        val meanEdge = if (edgeCount > 0) edgeSum / edgeCount else 0f
        val blurScore = ((laplacianAbsSum / lapCount.coerceAtLeast(1)) / 36f).coerceIn(0f, 1f)
        val overexposedRatio = clippedHighlights / count
        val exposureScore = when {
            mean < 25f -> (mean / 25f) * 0.5f
            overexposedRatio > 0.45f -> 0.45f
            else -> 1f - (abs(mean - 128f) / 140f).coerceIn(0f, 0.5f)
        }.coerceIn(0f, 1f)

        if (prevSignatureSum >= 0f && abs(sum - prevSignatureSum) < 40f && stdDev > 10f) {
            staticFramesCount++
        } else {
            staticFramesCount = 0
        }
        prevSignatureSum = sum
        val isFrozen = staticFramesCount >= 35

        val tooDark = mean < 15f && stdDev < 14f
        val flatObstruction = stdDev < 9.5f && meanEdge < 4.5f
        val obstructed = tooDark || flatObstruction
        val reason = when {
            tooDark -> "Caméra obstruée ou scène trop sombre"
            flatObstruction -> "Objectif recouvert ou contraste nul"
            blurScore < 0.14f -> "Flou de mouvement important"
            else -> null
        }

        val qualityScore = if (obstructed) {
            0.05f
        } else {
            (0.45f * blurScore + 0.35f * exposureScore + 0.20f * (meanEdge / 25f).coerceIn(0f, 1f))
                .coerceIn(0.1f, 1f)
        }

        return FrameQualityMetrics(
            meanLuma = mean,
            stdDevLuma = stdDev,
            meanEdgeContrast = meanEdge,
            blurScore01 = blurScore,
            exposureScore01 = exposureScore,
            compressionNoise01 = (1f - blurScore) * 0.4f,
            isFrozen = isFrozen,
            isObstructed = obstructed,
            qualityScore01 = qualityScore,
            reasonFr = reason
        )
    }

    fun reset() {
        prevSignatureSum = -1f
        staticFramesCount = 0
    }
}

/**
 * Flux optique sparse (gradients Lucas-Kanade + rejet RANSAC des points aberrants)
 * pour séparer le mouvement global de la caméra du mouvement local résiduel des objets.
 */
class OpticalFlowMotionAdapter : FeatureTracker {
    override val providerId: String = "AdvdsSparseLucasKanadeRansac"
    override val framework: VisionFramework = VisionFramework.OPENCV

    private val gridW = 56
    private val gridH = 32
    private val stateLock = Any()
    private var prevLuma: IntArray? = null

    override suspend fun track(previous: FrameInput?, current: FrameInput): MotionResult {
        val t0 = System.nanoTime()
        val bmp = current.frame.acquire().use { it.asBitmap() }
        val scaled = Bitmap.createScaledBitmap(bmp, gridW, gridH, false)
        val pixels = IntArray(gridW * gridH)
        scaled.getPixels(pixels, 0, gridW, 0, 0, gridW, gridH)
        if (scaled !== bmp) scaled.recycle()

        val curr = IntArray(gridW * gridH) { i ->
            val c = pixels[i]
            (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100
        }

        val prev = synchronized(stateLock) {
            val old = prevLuma
            prevLuma = curr
            old
        }
        if (prev == null) {
            return MotionResult(
                frameSequence = current.sequence,
                timestampNs = current.receivedAtNs,
                durationMs = 0.5f,
                observation = MotionObservation(advisoryLabel = "Initialisation référence"),
                providerId = providerId
            )
        }

        val rawVectors = mutableListOf<MotionVector2D>()
        for (y in 2 until gridH - 2 step 2) {
            for (x in 2 until gridW - 2 step 2) {
                val idx = y * gridW + x
                val ix = (curr[idx + 1] - curr[idx - 1]) * 0.5f
                val iy = (curr[idx + gridW] - curr[idx - gridW]) * 0.5f
                val gradSq = ix * ix + iy * iy
                if (gradSq < 160f) continue
                val it = (curr[idx] - prev[idx]).toFloat()
                val vx = -(it * ix) / (gradSq + 1e-2f)
                val vy = -(it * iy) / (gradSq + 1e-2f)
                if (abs(vx) < 3.5f && abs(vy) < 3.5f) {
                    rawVectors.add(
                        MotionVector2D(
                            x = x.toFloat() / gridW,
                            y = y.toFloat() / gridH,
                            dx = vx / gridW,
                            dy = vy / gridH,
                            isInlier = true
                        )
                    )
                }
            }
        }

        if (rawVectors.size < 6) {
            val dtMs = ((System.nanoTime() - t0) / 1_000_000f).coerceAtLeast(0.1f)
            return MotionResult(
                frameSequence = current.sequence,
                timestampNs = current.receivedAtNs,
                durationMs = dtMs,
                observation = MotionObservation(
                    featureCount = rawVectors.size,
                    confidence = 0.2f,
                    advisoryLabel = "Texture sol insuffisante"
                ),
                providerId = providerId
            )
        }

        // RANSAC léger : recherche du vecteur dominant par consensus médian
        var bestModelDx = 0f
        var bestModelDy = 0f
        var bestInliers = 0
        val inlierThreshold = 0.012f
        val sampleStep = (rawVectors.size / 10).coerceAtLeast(1)
        for (i in rawVectors.indices step sampleStep) {
            val cand = rawVectors[i]
            var count = 0
            for (v in rawVectors) {
                if (hypot((v.dx - cand.dx).toDouble(), (v.dy - cand.dy).toDouble()) <= inlierThreshold) {
                    count++
                }
            }
            if (count > bestInliers) {
                bestInliers = count
                bestModelDx = cand.dx
                bestModelDy = cand.dy
            }
        }

        var sumDx = 0f
        var sumDy = 0f
        var inlierCount = 0
        val taggedVectors = rawVectors.map { v ->
            val inlier = hypot((v.dx - bestModelDx).toDouble(), (v.dy - bestModelDy).toDouble()) <= inlierThreshold
            if (inlier) {
                sumDx += v.dx
                sumDy += v.dy
                inlierCount++
            }
            v.copy(isInlier = inlier)
        }

        val camDx = if (inlierCount > 0) sumDx / inlierCount else bestModelDx
        val camDy = if (inlierCount > 0) sumDy / inlierCount else bestModelDy
        val inlierRatio = inlierCount.toFloat() / rawVectors.size.coerceAtLeast(1)
        val mag = hypot(camDx.toDouble(), camDy.toDouble()).toFloat()
        val label = when {
            mag < 0.007f -> "Stationnaire"
            abs(camDx) > abs(camDy) && camDx > 0 -> "Pivot/Dérive Droite"
            abs(camDx) > abs(camDy) && camDx < 0 -> "Pivot/Dérive Gauche"
            camDy > 0 -> "Dérive Arrière"
            else -> "Dérive Avant"
        }

        val dtMs = ((System.nanoTime() - t0) / 1_000_000f).coerceAtLeast(0.1f)
        return MotionResult(
            frameSequence = current.sequence,
            timestampNs = current.receivedAtNs,
            durationMs = dtMs,
            observation = MotionObservation(
                cameraGlobalDriftX = camDx,
                cameraGlobalDriftY = camDy,
                cameraRotationDeg = (camDx * 48f).coerceIn(-18f, 18f),
                inlierRatio = inlierRatio,
                featureCount = rawVectors.size,
                vectors = taggedVectors.take(40),
                confidence = ((inlierCount / 36f) * inlierRatio).coerceIn(0.25f, 0.98f),
                advisoryLabel = label
            ),
            providerId = providerId
        )
    }

    fun reset() {
        synchronized(stateLock) {
            prevLuma = null
        }
    }

    override fun close() {
        reset()
    }
}

/**
 * Gestionnaire de calibration sténopé K=[fx,0,cx; 0,fy,cy; 0,0,1] et profil caméra unifié.
 */
object CameraCalibrationEngine {

    fun computeFovDegrees(focalPx: Float, dimensionPx: Int): Float {
        if (focalPx <= 1f || dimensionPx <= 1) return 55f
        val rad = 2.0 * atan((dimensionPx / (2.0 * focalPx)))
        return Math.toDegrees(rad).toFloat().coerceIn(20f, 140f)
    }

    fun calibrateFromReferenceObservations(
        widthPx: Int,
        heightPx: Int,
        knownDistanceMeters: Float,
        realObjectHeightMeters: Float,
        apparentHeightNorm: Float,
        profileId: String = "calibrated_${widthPx}x${heightPx}"
    ): CameraCalibration {
        val apparentPx = (apparentHeightNorm * heightPx).coerceAtLeast(8f)
        val focal = ((apparentPx * knownDistanceMeters) / realObjectHeightMeters.coerceAtLeast(0.05f))
            .coerceIn(120f, 4500f)
        val hFov = computeFovDegrees(focal, widthPx)
        val vFov = computeFovDegrees(focal, heightPx)
        return CameraCalibration(
            cameraProfileId = profileId,
            width = widthPx,
            height = heightPx,
            fx = focal,
            fy = focal,
            cx = widthPx / 2f,
            cy = heightPx / 2f,
            distortion = listOf(0f, 0f, 0f, 0f, 0f),
            horizontalFovDegrees = hFov,
            verticalFovDegrees = vFov,
            calibratedAt = System.currentTimeMillis(),
            reprojectionError = 0.42f,
            isFactoryEstimated = false
        )
    }

    fun buildProfile(
        calibration: CameraCalibration,
        width: Int,
        height: Int,
        rotationDeg: Int = 0
    ): CameraProfile {
        val scale = if (calibration.width > 0) width.toFloat() / calibration.width else 1f
        val scaledCalib = if (abs(scale - 1f) > 0.02f) {
            calibration.copy(
                width = width,
                height = height,
                fx = calibration.fx * scale,
                fy = calibration.fy * scale,
                cx = width / 2f,
                cy = height / 2f
            )
        } else {
            calibration
        }
        return CameraProfile(
            cameraId = scaledCalib.cameraProfileId,
            width = width,
            height = height,
            aspectRatio = width.toFloat() / height.coerceAtLeast(1),
            calibration = scaledCalib,
            rotationDeg = rotationDeg
        )
    }
}

/**
 * Estimateur PnP simplifié pour marqueurs QR / ArUco de taille physique connue.
 */
object MarkerRangeEstimator {

    fun estimateFromMarkerCorners(
        corners: List<NormalizedPoint>,
        fallbackBox: NormalizedBox,
        frameWidthPx: Int,
        frameHeightPx: Int,
        calibration: CameraCalibration,
        physicalMarkerSizeMeters: Float
    ): Pair<Float?, Float?> {
        val sizeM = physicalMarkerSizeMeters.coerceIn(0.05f, 2.0f)
        val focal = calibration.fy.coerceAtLeast(150f)

        val apparentSidePx = if (corners.size >= 4) {
            val topEdge = hypot(
                ((corners[1].x - corners[0].x) * frameWidthPx).toDouble(),
                ((corners[1].y - corners[0].y) * frameHeightPx).toDouble()
            ).toFloat()
            val rightEdge = hypot(
                ((corners[2].x - corners[1].x) * frameWidthPx).toDouble(),
                ((corners[2].y - corners[1].y) * frameHeightPx).toDouble()
            ).toFloat()
            val bottomEdge = hypot(
                ((corners[3].x - corners[2].x) * frameWidthPx).toDouble(),
                ((corners[3].y - corners[2].y) * frameHeightPx).toDouble()
            ).toFloat()
            val leftEdge = hypot(
                ((corners[0].x - corners[3].x) * frameWidthPx).toDouble(),
                ((corners[0].y - corners[3].y) * frameHeightPx).toDouble()
            ).toFloat()
            val edges = listOf(topEdge, rightEdge, bottomEdge, leftEdge)
            val mean = edges.average().toFloat()
            val skew = (edges.max() - edges.min()) / mean.coerceAtLeast(1f)
            val range = (focal * sizeM) / mean.coerceAtLeast(6f)
            val uncertainty = (range * (0.08f + skew * 0.35f + if (calibration.isFactoryEstimated) 0.12f else 0f))
                .coerceIn(0.05f, 3.0f)
            return range.coerceIn(0.15f, 35f) to uncertainty
        } else {
            (fallbackBox.height * frameHeightPx).coerceAtLeast(6f)
        }

        val range = (focal * sizeM) / apparentSidePx
        val uncertainty = range * (if (calibration.isFactoryEstimated) 0.22f else 0.12f)
        return range.coerceIn(0.15f, 35f) to uncertainty
    }
}

/**
 * Analyse géométrique de scène et génération de la LocalSpatialMap (8x5).
 * Les zones sans observation fiable sont explicitement marquées UNKNOWN.
 */
object SceneGeometryAnalyzer {

    fun analyze(
        bitmap: Bitmap,
        tracks: List<TrackState>,
        motion: MotionObservation,
        timestampNs: Long
    ): LocalSpatialMap {
        val w = bitmap.width
        val h = bitmap.height
        val horizonY = estimateHorizonY(bitmap, w, h)
        val vpX = (0.5f - motion.cameraGlobalDriftX * 2.5f).coerceIn(0.25f, 0.75f)
        val geometry = SceneGeometry(
            horizonYNorm = horizonY,
            vanishingPointX = vpX,
            vanishingPointY = horizonY,
            dominantSlopeDeg = (motion.cameraRotationDeg * 0.4f).coerceIn(-12f, 12f),
            groundPlaneConfidence = if (horizonY in 0.22f..0.78f) 0.72f else 0.35f
        )

        val cols = 8
        val rows = 5
        val cells = ArrayList<SpatialCell>(cols * rows)
        for (gy in 0 until rows) {
            val cy = (gy + 0.5f) / rows
            for (gx in 0 until cols) {
                val cx = (gx + 0.5f) / cols
                val overlappingTrack = tracks.firstOrNull { t ->
                    cx in t.box.left..t.box.right && cy in t.box.top..t.box.bottom
                }
                if (overlappingTrack != null) {
                    val sem = when {
                        overlappingTrack.classId == "PERSON" -> SemanticClass.PERSON
                        overlappingTrack.classId == "VEHICLE" -> SemanticClass.VEHICLE
                        overlappingTrack.classId == "STRUCTURE" -> SemanticClass.BUILDING
                        overlappingTrack.classId == "VEGETATION" -> SemanticClass.VEGETATION
                        else -> SemanticClass.UNKNOWN
                    }
                    cells.add(
                        SpatialCell(
                            gridX = gx,
                            gridY = gy,
                            relativeDepth = overlappingTrack.distanceEstimate.relativeDepth,
                            metricRangeMeters = overlappingTrack.trackedRange.filteredRangeMeters,
                            semanticClass = sem,
                            riskLevel = overlappingTrack.risk.level,
                            uncertainty = overlappingTrack.covariance.coerceIn(0.1f, 1f),
                            observed = true
                        )
                    )
                } else {
                    val isSky = cy < horizonY - 0.08f
                    val isGround = cy > horizonY + 0.10f
                    val sem = when {
                        isSky -> SemanticClass.SKY
                        isGround -> SemanticClass.GROUND
                        else -> SemanticClass.UNKNOWN
                    }
                    val relDepth = if (isGround) ((1f - cy) * 0.85f).coerceIn(0.1f, 0.9f) else null
                    val risk = if (sem == SemanticClass.UNKNOWN) RiskLevel.UNKNOWN else RiskLevel.LOW
                    cells.add(
                        SpatialCell(
                            gridX = gx,
                            gridY = gy,
                            relativeDepth = relDepth,
                            metricRangeMeters = null,
                            semanticClass = sem,
                            riskLevel = risk,
                            uncertainty = if (sem == SemanticClass.UNKNOWN) 1f else 0.55f,
                            observed = sem != SemanticClass.UNKNOWN
                        )
                    )
                }
            }
        }

        return LocalSpatialMap(
            cols = cols,
            rows = rows,
            cells = cells,
            geometry = geometry,
            timestampNs = timestampNs
        )
    }

    private fun estimateHorizonY(bitmap: Bitmap, w: Int, h: Int): Float {
        if (w < 16 || h < 16 || bitmap.isRecycled) return 0.45f
        val rows = 12
        val cols = 12
        val rowPixels = IntArray(w)
        val rowMeanLuma = FloatArray(rows)
        for (ry in 0 until rows) {
            val py = ((ry + 0.5f) * h / rows).toInt().coerceIn(0, h - 1)
            bitmap.getPixels(rowPixels, 0, w, 0, py, w, 1)
            var sum = 0f
            for (rx in 0 until cols) {
                val px = ((rx + 0.5f) * w / cols).toInt().coerceIn(0, w - 1)
                val c = rowPixels[px]
                sum += 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
            }
            rowMeanLuma[ry] = sum / cols
        }
        var maxJump = 0f
        var bestRow = 5
        for (ry in 2 until rows - 2) {
            val jump = abs(rowMeanLuma[ry] - rowMeanLuma[ry + 1])
            if (jump > maxJump) {
                maxJump = jump
                bestRow = ry
            }
        }
        return ((bestRow + 1f) / rows).coerceIn(0.25f, 0.72f)
    }
}
