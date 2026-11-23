package com.example.perception.human

import android.graphics.Bitmap
import android.graphics.Color
import com.example.core.models.NormalizedBox
import com.example.vision.domain.entities.ReIdSignature
import com.example.vision.domain.observations.HumanAttributes
import com.example.vision.domain.observations.HumanFacing
import com.example.vision.domain.observations.HumanGesture
import com.example.vision.domain.observations.HumanPosture
import com.example.vision.domain.observations.PoseLandmarkPoint
import com.example.vision.domain.observations.PoseObservation
import kotlin.math.abs

/**
 * Moteur de perception humaine spécialisé ADVDS :
 * - Posture (debout / assis / accroupi) et orientation approximative (face / profil / dos)
 * - Reconnaissance de gestes par séquence temporelle (confirmation sur >= 4 images consécutives)
 * - Signature visuelle locale temporaire (ReID session uniquement, aucune biométrie externe)
 */
class HumanPerceptionEngine {

    private var candidateGesture: HumanGesture = HumanGesture.NONE
    private var consecutiveFrames: Int = 0
    private val confirmationThresholdFrames = 4

    fun enrichPoses(poses: List<PoseObservation>): List<PoseObservation> {
        if (poses.isEmpty()) {
            decayGesture()
            return emptyList()
        }
        val primary = poses.first()
        val attrs = analyzeAttributes(primary)
        val updatedPrimary = primary.copy(attributes = attrs)
        return listOf(updatedPrimary) + poses.drop(1).map { it.copy(attributes = analyzeAttributes(it, updateTemporal = false)) }
    }

    private fun decayGesture() {
        consecutiveFrames = (consecutiveFrames - 1).coerceAtLeast(0)
        if (consecutiveFrames == 0) candidateGesture = HumanGesture.NONE
    }

    private fun analyzeAttributes(pose: PoseObservation, updateTemporal: Boolean = true): HumanAttributes {
        val byIdx = pose.landmarks.associateBy { it.index }
        val nose = byIdx[0]
        val lShoulder = byIdx[11]
        val rShoulder = byIdx[12]
        val lElbow = byIdx[13]
        val rElbow = byIdx[14]
        val lWrist = byIdx[15]
        val rWrist = byIdx[16]
        val lHip = byIdx[23]
        val rHip = byIdx[24]
        val lKnee = byIdx[25]
        val rKnee = byIdx[26]

        val visibleRatio = (pose.landmarks.count { it.inFrameLikelihood >= 0.55f } / 13f).coerceIn(0f, 1f)

        val shoulderY = listOfNotNull(lShoulder?.y, rShoulder?.y).average().takeIf { !it.isNaN() }?.toFloat()
        val hipY = listOfNotNull(lHip?.y, rHip?.y).average().takeIf { !it.isNaN() }?.toFloat()
        val kneeY = listOfNotNull(lKnee?.y, rKnee?.y).average().takeIf { !it.isNaN() }?.toFloat()

        val posture = when {
            shoulderY != null && hipY != null && kneeY != null -> {
                val torso = (hipY - shoulderY).coerceAtLeast(0.02f)
                val thigh = (kneeY - hipY).coerceAtLeast(0.01f)
                when {
                    thigh < torso * 0.48f -> HumanPosture.SITTING
                    pose.personBox.height < 0.35f && thigh < torso * 0.65f -> HumanPosture.CROUCHING
                    else -> HumanPosture.STANDING
                }
            }
            else -> HumanPosture.UNKNOWN
        }

        val facing = when {
            lShoulder != null && rShoulder != null -> {
                val span = abs(rShoulder.x - lShoulder.x)
                val noseVisible = (nose?.inFrameLikelihood ?: 0f) > 0.65f
                when {
                    span < pose.personBox.width * 0.28f -> HumanFacing.SIDE
                    noseVisible && lShoulder.x > rShoulder.x -> HumanFacing.FRONT
                    noseVisible -> HumanFacing.FRONT
                    else -> HumanFacing.BACK
                }
            }
            else -> HumanFacing.UNKNOWN
        }

        val rawGesture = detectInstantGesture(lShoulder, rShoulder, lElbow, rElbow, lWrist, rWrist, nose)
        if (!updateTemporal) {
            return HumanAttributes(posture, facing, rawGesture, 1, false, visibleRatio)
        }

        if (rawGesture != HumanGesture.NONE) {
            if (rawGesture == candidateGesture) {
                consecutiveFrames = (consecutiveFrames + 1).coerceAtMost(20)
            } else {
                candidateGesture = rawGesture
                consecutiveFrames = 1
            }
        } else {
            decayGesture()
        }

        val confirmed = candidateGesture != HumanGesture.NONE && consecutiveFrames >= confirmationThresholdFrames
        return HumanAttributes(
            posture = posture,
            facing = facing,
            gesture = if (confirmed) candidateGesture else rawGesture,
            gestureConfirmedFrames = consecutiveFrames,
            gestureConfirmed = confirmed,
            visibleJointRatio = visibleRatio
        )
    }

    private fun detectInstantGesture(
        lShoulder: PoseLandmarkPoint?,
        rShoulder: PoseLandmarkPoint?,
        lElbow: PoseLandmarkPoint?,
        rElbow: PoseLandmarkPoint?,
        lWrist: PoseLandmarkPoint?,
        rWrist: PoseLandmarkPoint?,
        nose: PoseLandmarkPoint?
    ): HumanGesture {
        val leftRaised = lWrist != null && lShoulder != null && lWrist.y < (nose?.y ?: lShoulder.y) - 0.03f
        val rightRaised = rWrist != null && rShoulder != null && rWrist.y < (nose?.y ?: rShoulder.y) - 0.03f
        if (leftRaised && rightRaised) return HumanGesture.BOTH_ARMS_CUSTOM
        if (rightRaised) return HumanGesture.RAISED_ARM_LOCK
        if (leftRaised) return HumanGesture.OPEN_PALM_STOP

        val leftPointing = lWrist != null && lShoulder != null && lElbow != null &&
            abs(lWrist.y - lShoulder.y) < 0.08f && abs(lWrist.x - lShoulder.x) > 0.14f
        val rightPointing = rWrist != null && rShoulder != null && rElbow != null &&
            abs(rWrist.y - rShoulder.y) < 0.08f && abs(rWrist.x - rShoulder.x) > 0.14f
        if (leftPointing || rightPointing) return HumanGesture.POINTING_DIRECTION

        return HumanGesture.NONE
    }

    fun extractSessionReIdSignature(bitmap: Bitmap?, box: NormalizedBox): ReIdSignature? {
        if (bitmap == null || bitmap.isRecycled || bitmap.width < 16 || bitmap.height < 16) return null
        val x0 = ((box.left + box.width * 0.25f) * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val x1 = ((box.right - box.width * 0.25f) * bitmap.width).toInt().coerceIn(x0, bitmap.width - 1)
        val y0 = ((box.top + box.height * 0.22f) * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val y1 = ((box.top + box.height * 0.58f) * bitmap.height).toInt().coerceIn(y0, bitmap.height - 1)
        var rSum = 0f
        var gSum = 0f
        var bSum = 0f
        var count = 0
        val stepX = ((x1 - x0) / 6).coerceAtLeast(1)
        val stepY = ((y1 - y0) / 6).coerceAtLeast(1)
        for (py in y0..y1 step stepY) {
            for (px in x0..x1 step stepX) {
                val c = bitmap.getPixel(px, py)
                rSum += Color.red(c) / 255f
                gSum += Color.green(c) / 255f
                bSum += Color.blue(c) / 255f
                count++
            }
        }
        if (count == 0) return null
        return ReIdSignature(
            avgR = rSum / count,
            avgG = gSum / count,
            avgB = bSum / count,
            aspectRatio = box.width / box.height.coerceAtLeast(0.05f),
            areaNorm = box.area
        )
    }

    fun reset() {
        candidateGesture = HumanGesture.NONE
        consecutiveFrames = 0
    }
}
