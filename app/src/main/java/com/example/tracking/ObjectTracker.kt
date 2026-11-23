package com.example.tracking

import com.example.core.models.AiDetection
import com.example.core.models.DetectionZone
import com.example.core.models.DistanceConfidence
import com.example.core.models.NormalizedBox
import com.example.core.models.NormalizedPoint
import com.example.core.models.TargetDistanceConfig
import com.example.core.models.TrackStatus
import com.example.core.models.TrackedSubject
import com.example.core.models.TrackingMode
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Tracker multi-objets inspiré de SORT / ByteTrack.
 * Gère la désignation vs verrouillage pilote, les boîtes manuelles,
 * la récupération après masquage (coasting + désambiguïsation), et l'estimation de distance consultative.
 */
class ObjectTracker {

    private data class InternalTrack(
        val id: String,
        var label: String,
        var category: String,
        var confidence: Float,
        var box: NormalizedBox,
        var velocityX: Float = 0f,
        var velocityY: Float = 0f,
        val trajectory: ArrayDeque<NormalizedPoint> = ArrayDeque(30),
        var landmarks: List<NormalizedPoint> = emptyList(),
        var skeletonEdges: List<Pair<Int, Int>> = emptyList(),
        var missedFrames: Int = 0,
        var lastSeenMs: Long = System.currentTimeMillis(),
        val isManualBox: Boolean = false
    )

    private val tracks = mutableListOf<InternalTrack>()
    private var nextFaceNum = 1
    private var nextPersonNum = 1
    private var nextObjectNum = 1
    private var nextManualNum = 1

    @Volatile
    var designatedTrackId: String? = null
        private set

    @Volatile
    var lockedTrackId: String? = null
        private set

    private var lostLockedReferenceBox: NormalizedBox? = null
    private var lostLockedCategory: String? = null
    private var ambiguousCandidatesSnapshot: List<TrackedSubject> = emptyList()

    @Synchronized
    fun designateTarget(trackId: String?) {
        designatedTrackId = trackId
        if (trackId == null) {
            lockedTrackId = null
        }
        ambiguousCandidatesSnapshot = emptyList()
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
            lostLockedReferenceBox = null
            lostLockedCategory = null
            ambiguousCandidatesSnapshot = emptyList()
        }
    }

    @Synchronized
    fun clearSelection() {
        designatedTrackId = null
        lockedTrackId = null
        lostLockedReferenceBox = null
        lostLockedCategory = null
        ambiguousCandidatesSnapshot = emptyList()
    }

    @Synchronized
    fun addManualTargetBox(box: NormalizedBox, nowMs: Long = System.currentTimeMillis()): String {
        val id = "M%02d".format(nextManualNum++)
        val clamped = NormalizedBox(
            left = box.left.coerceIn(0f, 0.96f),
            top = box.top.coerceIn(0f, 0.96f),
            right = box.right.coerceIn((box.left + 0.04f).coerceAtMost(1f), 1f),
            bottom = box.bottom.coerceIn((box.top + 0.04f).coerceAtMost(1f), 1f)
        )
        val nt = InternalTrack(
            id = id,
            label = "Zone manuelle",
            category = "MANUAL",
            confidence = 1.0f,
            box = clamped,
            lastSeenMs = nowMs,
            isManualBox = true
        )
        appendTrajectory(nt, clamped)
        tracks.add(nt)
        designatedTrackId = id
        lockedTrackId = id
        ambiguousCandidatesSnapshot = emptyList()
        return id
    }

    @Synchronized
    fun selectNearestOrLargest(): String? {
        val candidate = tracks.filter { it.missedFrames <= 4 }
            .maxByOrNull { it.box.area } ?: return null
        designatedTrackId = candidate.id
        return candidate.id
    }

    @Synchronized
    fun selectCenterTarget(): String? {
        val candidate = tracks.filter { it.missedFrames <= 4 }
            .minByOrNull { hypot(it.box.centerX - 0.5f, it.box.centerY - 0.5f) } ?: return null
        designatedTrackId = candidate.id
        return candidate.id
    }

    @Synchronized
    fun resolveAmbiguousCandidate(trackId: String) {
        designatedTrackId = trackId
        lockedTrackId = trackId
        lostLockedReferenceBox = null
        lostLockedCategory = null
        ambiguousCandidatesSnapshot = emptyList()
    }

    @Synchronized
    fun clearOnObstruction() {
        val iterator = tracks.iterator()
        while (iterator.hasNext()) {
            val t = iterator.next()
            if (!t.isManualBox) {
                if (lockedTrackId == t.id) lockedTrackId = null
                if (designatedTrackId == t.id) designatedTrackId = null
                iterator.remove()
            }
        }
        ambiguousCandidatesSnapshot = emptyList()
    }

    @Synchronized
    fun reset() {
        tracks.clear()
        clearSelection()
        nextFaceNum = 1
        nextPersonNum = 1
        nextObjectNum = 1
        nextManualNum = 1
    }

    @Synchronized
    fun getAmbiguousCandidates(): List<TrackedSubject> = ambiguousCandidatesSnapshot

    @Synchronized
    fun update(
        detections: List<AiDetection>,
        trackingMode: TrackingMode,
        detectionZone: DetectionZone,
        nowMs: Long = System.currentTimeMillis(),
        confidenceThreshold: Float = 0.45f,
        maxDisplayedBoxes: Int = 6,
        abandonTimeoutMs: Long = 900L,
        frameHeightPx: Int = 720,
        distanceConfig: TargetDistanceConfig = TargetDistanceConfig()
    ): List<TrackedSubject> {
        val filtered = detections.filter { det ->
            val confOk = det.confidence >= confidenceThreshold
            val modeOk = when (trackingMode) {
                TrackingMode.FACE_TRACKING -> det.category == "FACE"
                TrackingMode.PERSON_TRACKING -> det.category == "PERSON"
                else -> true
            }
            val zoneOk = if (trackingMode == TrackingMode.DETECTION_ZONES || detectionZone.enabled) {
                detectionZone.containsCenter(det.box)
            } else {
                true
            }
            confOk && modeOk && zoneOk
        }

        // Prédiction amortie avant association (évite la dérive infinie si la cible disparaît)
        for (track in tracks) {
            if (track.isManualBox) continue
            if (track.missedFrames < 2) {
                track.box = NormalizedBox(
                    left = (track.box.left + track.velocityX * 0.65f).coerceIn(0f, 1f),
                    top = (track.box.top + track.velocityY * 0.65f).coerceIn(0f, 1f),
                    right = (track.box.right + track.velocityX * 0.65f).coerceIn(0f, 1f),
                    bottom = (track.box.bottom + track.velocityY * 0.65f).coerceIn(0f, 1f)
                )
            }
            track.velocityX *= 0.6f
            track.velocityY *= 0.6f
        }

        val matchedDetIndices = mutableSetOf<Int>()
        val matchedTrackIndices = mutableSetOf<Int>()

        val pairs = mutableListOf<Triple<Int, Int, Float>>()
        for (ti in tracks.indices) {
            for (di in filtered.indices) {
                val track = tracks[ti]
                val det = filtered[di]
                val iou = computeIoU(track.box, det.box)
                val dist = hypot(
                    (track.box.centerX - det.box.centerX).toDouble(),
                    (track.box.centerY - det.box.centerY).toDouble()
                ).toFloat()
                val score = iou - dist * 0.35f
                if (iou >= 0.16f || dist <= 0.16f) {
                    pairs.add(Triple(ti, di, score))
                }
            }
        }
        pairs.sortByDescending { it.third }

        for ((ti, di, _) in pairs) {
            if (ti in matchedTrackIndices || di in matchedDetIndices) continue
            matchedTrackIndices.add(ti)
            matchedDetIndices.add(di)

            val track = tracks[ti]
            val det = filtered[di]
            val rawDx = det.box.centerX - track.box.centerX
            val rawDy = det.box.centerY - track.box.centerY
            val moveMag = hypot(rawDx.toDouble(), rawDy.toDouble()).toFloat()

            val alpha = when {
                moveMag < 0.012f -> 0.38f
                moveMag < 0.045f -> 0.58f
                else -> 0.82f
            }

            val smoothedBox = NormalizedBox(
                left = track.box.left + (det.box.left - track.box.left) * alpha,
                top = track.box.top + (det.box.top - track.box.top) * alpha,
                right = track.box.right + (det.box.right - track.box.right) * alpha,
                bottom = track.box.bottom + (det.box.bottom - track.box.bottom) * alpha
            )

            track.velocityX = track.velocityX * 0.45f + rawDx * 0.55f
            track.velocityY = track.velocityY * 0.45f + rawDy * 0.55f
            track.box = smoothedBox
            if (!track.isManualBox) {
                track.label = det.label
                track.category = det.category
            }
            track.confidence = track.confidence * 0.35f + det.confidence * 0.65f
            track.landmarks = smoothLandmarks(track.landmarks, det.landmarks, alpha)
            track.skeletonEdges = det.skeletonEdges
            track.missedFrames = 0
            track.lastSeenMs = nowMs
            appendTrajectory(track, smoothedBox)
        }

        // Pistes non appariées : coasting bref (max 5 cycles) puis expiration selon abandonTimeoutMs
        val toRemove = mutableListOf<InternalTrack>()
        tracks.forEachIndexed { idx, t ->
            if (idx !in matchedTrackIndices && !t.isManualBox) {
                t.missedFrames++
                val elapsedMissing = (nowMs - t.lastSeenMs).coerceAtLeast(0L)
                if (t.missedFrames > 5 || elapsedMissing > abandonTimeoutMs) {
                    if (lockedTrackId == t.id) {
                        lostLockedReferenceBox = t.box
                        lostLockedCategory = t.category
                        lockedTrackId = null
                    }
                    if (designatedTrackId == t.id) {
                        designatedTrackId = null
                    }
                    toRemove.add(t)
                }
            }
        }
        tracks.removeAll(toRemove)

        // Nouvelles détections -> nouveaux identifiants de session
        for (di in filtered.indices) {
            if (di in matchedDetIndices) continue
            val det = filtered[di]
            val newId = allocateSessionId(det.category)
            val nt = InternalTrack(
                id = newId,
                label = det.label,
                category = det.category,
                confidence = det.confidence,
                box = det.box,
                landmarks = det.landmarks,
                skeletonEdges = det.skeletonEdges,
                lastSeenMs = nowMs
            )
            appendTrajectory(nt, det.box)
            tracks.add(nt)
        }

        val activeLock = lockedTrackId
        val activeDesignation = designatedTrackId
        val subjects = tracks.map { t ->
            val status = when {
                t.isManualBox -> TrackStatus.ACTIVE
                t.missedFrames == 0 -> TrackStatus.ACTIVE
                t.missedFrames <= 3 -> TrackStatus.COASTING
                else -> TrackStatus.LOST
            }
            val (distM, distConf, distHint) = estimateDistance(
                track = t,
                status = status,
                frameHeightPx = frameHeightPx,
                config = distanceConfig
            )
            TrackedSubject(
                trackId = t.id,
                label = t.label,
                category = t.category,
                confidence = t.confidence,
                box = t.box,
                status = status,
                trajectory = t.trajectory.toList(),
                velocityX = t.velocityX,
                velocityY = t.velocityY,
                isLocked = activeLock == t.id,
                isDesignated = activeDesignation == t.id || activeLock == t.id,
                isManualBox = t.isManualBox,
                centerDeltaX = t.box.centerX - 0.5f,
                centerDeltaY = t.box.centerY - 0.5f,
                estimatedDistanceM = distM,
                distanceConfidence = distConf,
                distanceHintFr = distHint,
                landmarks = t.landmarks,
                skeletonEdges = t.skeletonEdges,
                lastSeenMs = t.lastSeenMs
            )
        }
            .sortedWith(
                compareByDescending<TrackedSubject> { it.isLocked }
                    .thenByDescending { it.isDesignated }
                    .thenByDescending { it.confidence }
            )
            .take(maxDisplayedBoxes.coerceIn(1, 12))

        // Désambiguïsation si la cible verrouillée a disparu et >= 2 candidats similaires réapparaissent
        val refBox = lostLockedReferenceBox
        ambiguousCandidatesSnapshot = if (activeLock == null && refBox != null) {
            val matching = subjects.filter {
                (lostLockedCategory == null || it.category == lostLockedCategory) &&
                    hypot(it.box.centerX - refBox.centerX, it.box.centerY - refBox.centerY) <= 0.35f
            }
            if (matching.size >= 2) matching.take(3) else emptyList()
        } else {
            emptyList()
        }

        return subjects
    }

    private fun smoothLandmarks(
        prev: List<NormalizedPoint>,
        curr: List<NormalizedPoint>,
        alpha: Float
    ): List<NormalizedPoint> {
        if (prev.size != curr.size || prev.isEmpty()) return curr
        return curr.mapIndexed { idx, p ->
            val old = prev[idx]
            NormalizedPoint(
                x = old.x + (p.x - old.x) * alpha,
                y = old.y + (p.y - old.y) * alpha,
                label = p.label
            )
        }
    }

    private fun estimateDistance(
        track: InternalTrack,
        status: TrackStatus,
        frameHeightPx: Int,
        config: TargetDistanceConfig
    ): Triple<Float?, DistanceConfidence, String> {
        if (status == TrackStatus.LOST || track.box.height < 0.03f) {
            return Triple(null, DistanceConfidence.UNAVAILABLE, "—")
        }
        val boxHeightPx = track.box.height * frameHeightPx.coerceAtLeast(360)
        val focal = config.calibratedFocalPx
        if (focal > 50f && config.referenceTargetHeightM > 0.05f) {
            val distM = ((config.referenceTargetHeightM * focal) / boxHeightPx).coerceIn(0.25f, 60f)
            val hint = if (config.showDistanceInMeters) {
                "≈ ${"%.1f".format(distM)} m"
            } else {
                bucketLabel(track.box.height)
            }
            return Triple(distM, DistanceConfidence.CALIBRATED_HIGH, hint)
        }
        return Triple(null, DistanceConfidence.RELATIVE, bucketLabel(track.box.height))
    }

    private fun bucketLabel(normHeight: Float): String = when {
        normHeight >= 0.45f -> "Proche"
        normHeight >= 0.20f -> "Moyen"
        else -> "Loin"
    }

    private fun allocateSessionId(category: String): String = when (category) {
        "FACE" -> "F%02d".format(nextFaceNum++)
        "PERSON" -> "P%02d".format(nextPersonNum++)
        "MANUAL" -> "M%02d".format(nextManualNum++)
        else -> "O%02d".format(nextObjectNum++)
    }

    private fun appendTrajectory(track: InternalTrack, box: NormalizedBox) {
        if (track.trajectory.size >= 26) track.trajectory.removeFirst()
        track.trajectory.addLast(NormalizedPoint(box.centerX, box.centerY))
    }

    private fun computeIoU(a: NormalizedBox, b: NormalizedBox): Float {
        val ix1 = maxOf(a.left, b.left)
        val iy1 = maxOf(a.top, b.top)
        val ix2 = minOf(a.right, b.right)
        val iy2 = minOf(a.bottom, b.bottom)
        val iw = (ix2 - ix1).coerceAtLeast(0f)
        val ih = (iy2 - iy1).coerceAtLeast(0f)
        val inter = iw * ih
        val union = a.area + b.area - inter
        return if (union > 1e-5f) inter / union else 0f
    }
}
