package com.example.vision.domain.entities

import com.example.core.models.NormalizedBox
import kotlinx.serialization.Serializable

@Serializable
data class CameraCalibration(
    val cameraProfileId: String = "default_640x480",
    val width: Int = 640,
    val height: Int = 480,
    val fx: Float = 560f,
    val fy: Float = 560f,
    val cx: Float = 320f,
    val cy: Float = 240f,
    val distortion: List<Float> = listOf(0f, 0f, 0f, 0f, 0f),
    val horizontalFovDegrees: Float = 59.5f,
    val verticalFovDegrees: Float = 46.4f,
    val calibratedAt: Long = 0L,
    val reprojectionError: Float = 0f,
    val isFactoryEstimated: Boolean = true
)

data class FrameQualityMetrics(
    val meanLuma: Float = 0f,
    val stdDevLuma: Float = 0f,
    val meanEdgeContrast: Float = 0f,
    val blurScore01: Float = 0f,
    val exposureScore01: Float = 0f,
    val compressionNoise01: Float = 0f,
    val isFrozen: Boolean = false,
    val isObstructed: Boolean = false,
    val qualityScore01: Float = 0f,
    val reasonFr: String? = null
)

data class CameraProfile(
    val cameraId: String = "cam_main",
    val width: Int = 640,
    val height: Int = 480,
    val aspectRatio: Float = 4f / 3f,
    val calibration: CameraCalibration = CameraCalibration(),
    val rotationDeg: Int = 0,
    val mirrored: Boolean = false,
    val cropBox: NormalizedBox = NormalizedBox(0f, 0f, 1f, 1f),
    val distortionAlreadyCorrected: Boolean = false,
    val cameraToDronePitchDeg: Float = -10f
)
