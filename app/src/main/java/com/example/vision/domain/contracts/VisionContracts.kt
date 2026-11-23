package com.example.vision.domain.contracts

import com.example.core.models.NormalizedBox
import com.example.vision.domain.observations.BarcodeBatch
import com.example.vision.domain.observations.ClassificationResult
import com.example.vision.domain.observations.DepthResult
import com.example.vision.domain.observations.DetectionBatch
import com.example.vision.domain.observations.FaceBatch
import com.example.vision.domain.observations.MotionResult
import com.example.vision.domain.observations.PoseBatch
import com.example.vision.domain.observations.SegmentationResult

enum class VisionFramework(val displayName: String) {
    ONNX_RUNTIME("ONNX Runtime"),
    LITERT("LiteRT"),
    ML_KIT("ML Kit"),
    MEDIAPIPE("MediaPipe"),
    OPENCV("OpenCV / Géométrie"),
    CUSTOM("Personnalisé")
}

enum class ComputeBackend(val labelFr: String) {
    CPU("CPU (XNNPACK)"),
    GPU("GPU (Adreno / OpenGL)"),
    NPU("NPU / NNAPI"),
    DSP("DSP (Hexagon)"),
    UNKNOWN("Inconnu")
}

enum class VisionTask(val labelFr: String) {
    OBJECT_DETECTION("Détection d'objets"),
    CLASSIFICATION("Classification"),
    INSTANCE_SEGMENTATION("Segmentation d'instances"),
    SEMANTIC_SEGMENTATION("Segmentation sémantique"),
    DEPTH_ESTIMATION("Estimation de profondeur"),
    POSE_ESTIMATION("Estimation de posture"),
    FACE_DETECTION("Détection de visages"),
    HAND_LANDMARKS("Repères des mains"),
    BARCODE("Marqueurs QR / Codes"),
    OPTICAL_FLOW("Flux optique"),
    CAMERA_GEOMETRY("Géométrie caméra"),
    MARKER_DETECTION("Marqueurs ArUco / AprilTag")
}

enum class PixelFormat {
    RGBA_8888,
    YUV_420_888,
    RGB_888,
    GRAY_8
}

enum class CoordinateSpace {
    SOURCE_IMAGE,
    MODEL_INPUT,
    CROP,
    DISPLAY
}

/**
 * Traçabilité géométrique stricte entre l'image source du drone et le tenseur d'entrée du modèle.
 * Permet de ramener exactement les boîtes d'un letterbox 416x416 dans l'espace normalisé [0..1] source.
 */
data class CoordinateTransform(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val modelWidth: Int,
    val modelHeight: Int,
    val scaleX: Float,
    val scaleY: Float,
    val padLeftPx: Float,
    val padTopPx: Float,
    val rotationDegrees: Int = 0
) {
    fun mapModelBoxToSource(modelBox: NormalizedBox): NormalizedBox {
        val mw = modelWidth.coerceAtLeast(1).toFloat()
        val mh = modelHeight.coerceAtLeast(1).toFloat()
        val sw = sourceWidth.coerceAtLeast(1).toFloat()
        val sh = sourceHeight.coerceAtLeast(1).toFloat()

        val mx1 = modelBox.left * mw
        val my1 = modelBox.top * mh
        val mx2 = modelBox.right * mw
        val my2 = modelBox.bottom * mh

        val sx1 = ((mx1 - padLeftPx) / scaleX.coerceAtLeast(1e-4f) / sw).coerceIn(0f, 1f)
        val sy1 = ((my1 - padTopPx) / scaleY.coerceAtLeast(1e-4f) / sh).coerceIn(0f, 1f)
        val sx2 = ((mx2 - padLeftPx) / scaleX.coerceAtLeast(1e-4f) / sw).coerceIn(sx1, 1f)
        val sy2 = ((my2 - padTopPx) / scaleY.coerceAtLeast(1e-4f) / sh).coerceIn(sy1, 1f)

        return NormalizedBox(sx1, sy1, sx2, sy2)
    }

    companion object {
        fun identity(width: Int, height: Int): CoordinateTransform = CoordinateTransform(
            sourceWidth = width,
            sourceHeight = height,
            modelWidth = width,
            modelHeight = height,
            scaleX = 1f,
            scaleY = 1f,
            padLeftPx = 0f,
            padTopPx = 0f
        )
    }
}

interface FrameLease : AutoCloseable {
    fun asBitmap(): android.graphics.Bitmap
    fun asByteBuffer(): java.nio.ByteBuffer
}

interface FrameBuffer {
    val format: PixelFormat
    fun width(): Int
    fun height(): Int
    fun acquire(): FrameLease
}

data class FrameInput(
    val sequence: Long,
    val capturedAtNs: Long?,
    val receivedAtNs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val cameraProfileId: String,
    val frame: FrameBuffer
)

data class InferenceConfig(
    val preferredBackend: ComputeBackend = ComputeBackend.CPU,
    val numThreads: Int = 4,
    val confidenceThreshold: Float = 0.45f,
    val enableLandmarks: Boolean = true,
    val maxDetections: Int = 10
)

data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val gpuDelegateSupported: Boolean,
    val nnapiSupported: Boolean,
    val measuredLatenciesMs: Map<String, Float> = emptyMap()
)

data class DetectorRequirements(
    val requiredClasses: Set<String> = emptySet(),
    val multipleObjects: Boolean = true,
    val minimumInputWidth: Int = 320,
    val maximumLatencyMs: Float? = null,
    val offlineRequired: Boolean = true,
    val requiredOutputs: Set<String> = emptySet(),
    val allowFallback: Boolean = true
)

data class ProviderAssessment(
    val supported: Boolean,
    val backend: ComputeBackend,
    val estimatedLatencyMs: Float?,
    val capabilityGapWarningFr: String? = null,
    val reason: String? = null
)

data class ModelArtifact(
    val path: String,
    val format: String,
    val sizeBytes: Long
)

data class ModelInputSpec(
    val width: Int,
    val height: Int,
    val colorOrder: String,
    val tensorLayout: String,
    val normalization: String,
    val resizeMode: String
)

data class ModelOutputSpec(
    val decoderId: String,
    val coordinateSpace: CoordinateSpace,
    val outputNames: List<String>
)

data class ModelLabel(
    val id: String,
    val name: String
)

data class ModelDescriptor(
    val id: String,
    val name: String,
    val version: String,
    val task: VisionTask,
    val family: String,
    val artifact: ModelArtifact,
    val input: ModelInputSpec,
    val output: ModelOutputSpec,
    val labels: List<ModelLabel>,
    val preferredFramework: VisionFramework?,
    val allowedBackends: Set<ComputeBackend>,
    val checksumSha256: String,
    val modelLicense: String,
    val descriptionFr: String = "",
    val supportsCustomClasses: Boolean = false,
    val isArtifactAvailable: Boolean = false
)

// Contrats universels par tâche (Ports Hexagonaux)

interface ObjectDetector : AutoCloseable {
    val providerId: String
    val framework: VisionFramework
    suspend fun detect(frame: FrameInput): DetectionBatch
}

interface PoseEstimator : AutoCloseable {
    val providerId: String
    val framework: VisionFramework
    suspend fun estimate(frame: FrameInput): PoseBatch
}

interface FaceDetectorPort : AutoCloseable {
    val providerId: String
    val framework: VisionFramework
    suspend fun detectFaces(frame: FrameInput, withLandmarks: Boolean): FaceBatch
}

interface DepthEstimator : AutoCloseable {
    val providerId: String
    val framework: VisionFramework
    suspend fun estimate(frame: FrameInput): DepthResult
}

interface ImageSegmenter : AutoCloseable {
    val providerId: String
    val framework: VisionFramework
    suspend fun segment(frame: FrameInput): SegmentationResult
}

data class ImageCrop(
    val frameSequence: Long,
    val box: NormalizedBox,
    val bitmap: android.graphics.Bitmap
)

interface ImageClassifier : AutoCloseable {
    val providerId: String
    suspend fun classify(crop: ImageCrop): ClassificationResult
}

interface BarcodeReader : AutoCloseable {
    val providerId: String
    val framework: VisionFramework
    suspend fun scan(frame: FrameInput): BarcodeBatch
}

interface FeatureTracker : AutoCloseable {
    val providerId: String
    val framework: VisionFramework
    suspend fun track(previous: FrameInput?, current: FrameInput): MotionResult
}

// Contrats Providers

interface ObjectDetectorProvider {
    val framework: VisionFramework
    val providerId: String
    fun assess(
        model: ModelDescriptor,
        device: DeviceProfile,
        requirements: DetectorRequirements
    ): ProviderAssessment

    suspend fun create(model: ModelDescriptor, config: InferenceConfig): ObjectDetector
}

interface PoseEstimatorProvider {
    val framework: VisionFramework
    val providerId: String
    fun assess(model: ModelDescriptor, device: DeviceProfile): ProviderAssessment
    suspend fun create(model: ModelDescriptor, config: InferenceConfig): PoseEstimator
}

interface DepthEstimatorProvider {
    val framework: VisionFramework
    val providerId: String
    fun assess(model: ModelDescriptor, device: DeviceProfile): ProviderAssessment
    suspend fun create(model: ModelDescriptor, config: InferenceConfig): DepthEstimator
}

interface BarcodeReaderProvider {
    val framework: VisionFramework
    val providerId: String
    fun assess(model: ModelDescriptor, device: DeviceProfile): ProviderAssessment
    suspend fun create(model: ModelDescriptor, config: InferenceConfig): BarcodeReader
}

data class RuntimeCandidate(
    val provider: ObjectDetectorProvider,
    val model: ModelDescriptor,
    val assessment: ProviderAssessment,
    val score: Float
)
