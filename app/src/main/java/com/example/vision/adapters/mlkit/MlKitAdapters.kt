package com.example.vision.adapters.mlkit

import com.example.core.models.NormalizedBox
import com.example.core.models.NormalizedPoint
import com.example.vision.adapters.geometry.MarkerRangeEstimator
import com.example.vision.domain.capabilities.FrameworkCapabilities
import com.example.vision.domain.contracts.BarcodeReader
import com.example.vision.domain.contracts.BarcodeReaderProvider
import com.example.vision.domain.contracts.ComputeBackend
import com.example.vision.domain.contracts.CoordinateSpace
import com.example.vision.domain.contracts.DetectorRequirements
import com.example.vision.domain.contracts.DeviceProfile
import com.example.vision.domain.contracts.FaceDetectorPort
import com.example.vision.domain.contracts.FrameInput
import com.example.vision.domain.contracts.InferenceConfig
import com.example.vision.domain.contracts.ModelDescriptor
import com.example.vision.domain.contracts.ObjectDetector
import com.example.vision.domain.contracts.ObjectDetectorProvider
import com.example.vision.domain.contracts.PoseEstimator
import com.example.vision.domain.contracts.PoseEstimatorProvider
import com.example.vision.domain.contracts.ProviderAssessment
import com.example.vision.domain.contracts.VisionFramework
import com.example.vision.domain.contracts.VisionTask
import com.example.vision.domain.entities.AdvdsTaxonomy
import com.example.vision.domain.entities.CameraCalibration
import com.example.vision.domain.entities.ObjectFamily
import com.example.vision.domain.entities.ObstacleImportance
import com.example.vision.domain.observations.BarcodeBatch
import com.example.vision.domain.observations.BarcodeDetection
import com.example.vision.domain.observations.Detection
import com.example.vision.domain.observations.DetectionBatch
import com.example.vision.domain.observations.FaceBatch
import com.example.vision.domain.observations.FaceObservation
import com.example.vision.domain.observations.PoseBatch
import com.example.vision.domain.observations.PoseLandmarkPoint
import com.example.vision.domain.observations.PoseObservation
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object MlKitCapabilityCatalog {
    val capabilities = FrameworkCapabilities(
        framework = VisionFramework.ML_KIT,
        supportedTasks = setOf(
            VisionTask.OBJECT_DETECTION,
            VisionTask.POSE_ESTIMATION,
            VisionTask.FACE_DETECTION,
            VisionTask.BARCODE
        ),
        maxObjectsPerFrame = 5,
        supportsCustomTaxonomy = false,
        supportsSegmentationMasks = false,
        honestLimitNoteFr = "ML Kit standard : max 5 objets, catégories génériques (ne remplace pas YOLO multi-classes)."
    )
}

internal fun scaleBitmapForMlKit(bitmap: android.graphics.Bitmap, maxDim: Int = 480): Pair<android.graphics.Bitmap, Boolean> {
    if (bitmap.width <= maxDim && bitmap.height <= maxDim) return bitmap to false
    val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
    val scaled = android.graphics.Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * scale).toInt().coerceAtLeast(16),
        (bitmap.height * scale).toInt().coerceAtLeast(16),
        false
    )
    return scaled to true
}

class MlKitObjectDetectorAdapter(
    private val confThreshold: Float
) : ObjectDetector {

    override val providerId: String = "MlKitObjectDetectorProvider"
    override val framework: VisionFramework = VisionFramework.ML_KIT

    private val client by lazy {
        val opts = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
        ObjectDetection.getClient(opts)
    }

    override suspend fun detect(frame: FrameInput): DetectionBatch {
        val t0 = System.nanoTime()
        val (inferBmp, didAlloc) = frame.frame.acquire().use { lease ->
            scaleBitmapForMlKit(lease.asBitmap(), 480)
        }
        val list = try {
            suspendCancellableCoroutine { cont ->
                val img = InputImage.fromBitmap(inferBmp, 0)
                val w = inferBmp.width.toFloat().coerceAtLeast(1f)
                val h = inferBmp.height.toFloat().coerceAtLeast(1f)
                client.process(img)
                    .addOnSuccessListener { objects ->
                        val mapped = objects.mapNotNull { obj ->
                            val r = obj.boundingBox
                            val box = NormalizedBox(
                                left = (r.left / w).coerceIn(0f, 1f),
                                top = (r.top / h).coerceIn(0f, 1f),
                                right = (r.right / w).coerceIn(0f, 1f),
                                bottom = (r.bottom / h).coerceIn(0f, 1f)
                            )
                            if (box.width < 0.04f || box.height < 0.04f || box.area > 0.85f) return@mapNotNull null

                            val topLabel = obj.labels.maxByOrNull { it.confidence }
                            val rawConf = topLabel?.confidence ?: 0.30f
                            if (rawConf < confThreshold.coerceAtLeast(0.30f) && topLabel == null) {
                                // Open-Set : objet détecté sans label fiable
                                return@mapNotNull Detection(
                                    classId = "UNKNOWN_OBJECT",
                                    label = "UNKNOWN OBJECT",
                                    family = ObjectFamily.UNKNOWN,
                                    importance = ObstacleImportance.UNKNOWN,
                                    box = box,
                                    rawConfidence = 0.36f,
                                    calibratedConfidence = null,
                                    providerId = providerId,
                                    framework = framework,
                                    coordinateSpace = CoordinateSpace.SOURCE_IMAGE,
                                    isOpenSetUnknown = true,
                                    trackerHintId = obj.trackingId
                                )
                            }
                            if (rawConf < 0.35f) return@mapNotNull null
                            val tax = AdvdsTaxonomy.classify(topLabel?.text ?: "unknown", rawConf)
                            Detection(
                                classId = tax.classId,
                                label = tax.displayLabelFr,
                                family = tax.family,
                                importance = tax.importance,
                                box = box,
                                rawConfidence = rawConf,
                                calibratedConfidence = rawConf,
                                providerId = providerId,
                                framework = framework,
                                coordinateSpace = CoordinateSpace.SOURCE_IMAGE,
                                isOpenSetUnknown = tax.isOpenSetUnknown,
                                trackerHintId = obj.trackingId
                            )
                        }
                        if (cont.isActive) cont.resume(mapped)
                    }
                    .addOnFailureListener {
                        if (cont.isActive) cont.resume(emptyList())
                    }
            }
        } finally {
            if (didAlloc && !inferBmp.isRecycled) inferBmp.recycle()
        }

        val dtMs = ((System.nanoTime() - t0) / 1_000_000f).coerceAtLeast(0.1f)
        return DetectionBatch(
            frameSequence = frame.sequence,
            timestampNs = frame.receivedAtNs,
            inferenceDurationMs = dtMs,
            frameQualityScore = 1f,
            detections = list,
            providerId = providerId,
            framework = framework,
            capabilityNoteFr = MlKitCapabilityCatalog.capabilities.honestLimitNoteFr
        )
    }

    override fun close() {
        runCatching { client.close() }
    }
}

class MlKitObjectDetectorProvider : ObjectDetectorProvider {
    override val framework: VisionFramework = VisionFramework.ML_KIT
    override val providerId: String = "MlKitObjectDetectorProvider"

    override fun assess(
        model: ModelDescriptor,
        device: DeviceProfile,
        requirements: DetectorRequirements
    ): ProviderAssessment {
        if (model.preferredFramework != VisionFramework.ML_KIT &&
            !model.artifact.format.equals("MLKIT_BUNDLED", ignoreCase = true)
        ) {
            return ProviderAssessment(false, ComputeBackend.UNKNOWN, null, reason = "Pas un descripteur ML Kit")
        }

        val customDroneClasses = setOf("tree", "pole", "wire", "cable", "building", "aruco", "apriltag")
        val demandsCustom = requirements.requiredClasses.any { it.lowercase() in customDroneClasses }
        if (demandsCustom && !requirements.allowFallback) {
            return ProviderAssessment(
                supported = false,
                backend = ComputeBackend.CPU,
                estimatedLatencyMs = null,
                reason = "ML Kit standard ne prend pas en charge la taxonomie drone spécialisée (poteaux/câbles/ArUco)"
            )
        }

        return ProviderAssessment(
            supported = true,
            backend = if (device.gpuDelegateSupported) ComputeBackend.GPU else ComputeBackend.CPU,
            estimatedLatencyMs = device.measuredLatenciesMs[model.id] ?: 42f,
            capabilityGapWarningFr = if (demandsCustom) {
                "Mode dégradé ML Kit : limité à 5 objets génériques (classes spécialisées non couvertes)"
            } else {
                MlKitCapabilityCatalog.capabilities.honestLimitNoteFr
            }
        )
    }

    override suspend fun create(model: ModelDescriptor, config: InferenceConfig): ObjectDetector =
        MlKitObjectDetectorAdapter(config.confidenceThreshold)
}

class MlKitPoseEstimatorAdapter : PoseEstimator {
    override val providerId: String = "MlKitPoseEstimatorProvider"
    override val framework: VisionFramework = VisionFramework.ML_KIT

    private val client by lazy {
        val opts = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
        PoseDetection.getClient(opts)
    }

    override suspend fun estimate(frame: FrameInput): PoseBatch {
        val t0 = System.nanoTime()
        val (inferBmp, didAlloc) = frame.frame.acquire().use { lease ->
            scaleBitmapForMlKit(lease.asBitmap(), 480)
        }
        val poses = try {
            suspendCancellableCoroutine { cont ->
                val img = InputImage.fromBitmap(inferBmp, 0)
                val w = inferBmp.width.toFloat().coerceAtLeast(1f)
                val h = inferBmp.height.toFloat().coerceAtLeast(1f)
                client.process(img)
                    .addOnSuccessListener { pose ->
                        val all = pose.allPoseLandmarks
                        val highConf = all.filter { it.inFrameLikelihood >= 0.55f }
                        val usable = all.filter { it.inFrameLikelihood >= 0.45f }
                        if (highConf.size < 6) {
                            if (cont.isActive) cont.resume(emptyList())
                            return@addOnSuccessListener
                        }
                        val avgConf = (usable.sumOf { it.inFrameLikelihood.toDouble() } / usable.size).toFloat()
                        if (avgConf < 0.55f) {
                            if (cont.isActive) cont.resume(emptyList())
                            return@addOnSuccessListener
                        }
                        val minX = usable.minOf { (it.position.x / w).coerceIn(0f, 1f) }
                        val maxX = usable.maxOf { (it.position.x / w).coerceIn(0f, 1f) }
                        val minY = usable.minOf { (it.position.y / h).coerceIn(0f, 1f) }
                        val maxY = usable.maxOf { (it.position.y / h).coerceIn(0f, 1f) }
                        val box = NormalizedBox(
                            (minX - 0.03f).coerceIn(0f, 1f),
                            (minY - 0.04f).coerceIn(0f, 1f),
                            (maxX + 0.03f).coerceIn(0f, 1f),
                            (maxY + 0.04f).coerceIn(0f, 1f)
                        )
                        if (box.width < 0.05f || box.height < 0.08f) {
                            if (cont.isActive) cont.resume(emptyList())
                            return@addOnSuccessListener
                        }

                        val lmMap = usable.associateBy { it.landmarkType }
                        val orderedTypes = listOf(
                            PoseLandmark.NOSE,
                            PoseLandmark.LEFT_SHOULDER,
                            PoseLandmark.RIGHT_SHOULDER,
                            PoseLandmark.LEFT_ELBOW,
                            PoseLandmark.RIGHT_ELBOW,
                            PoseLandmark.LEFT_WRIST,
                            PoseLandmark.RIGHT_WRIST,
                            PoseLandmark.LEFT_HIP,
                            PoseLandmark.RIGHT_HIP,
                            PoseLandmark.LEFT_KNEE,
                            PoseLandmark.RIGHT_KNEE,
                            PoseLandmark.LEFT_ANKLE,
                            PoseLandmark.RIGHT_ANKLE
                        )
                        val points = orderedTypes.mapIndexedNotNull { idx, t ->
                            lmMap[t]?.let { lm ->
                                PoseLandmarkPoint(
                                    index = t,
                                    x = (lm.position.x / w).coerceIn(0f, 1f),
                                    y = (lm.position.y / h).coerceIn(0f, 1f),
                                    relativeZ = lm.position3D.z / w,
                                    inFrameLikelihood = lm.inFrameLikelihood
                                )
                            }
                        }
                        val byTypeIdx = points.withIndex().associate { it.value.index to it.index }
                        val edgeTypes = listOf(
                            PoseLandmark.LEFT_SHOULDER to PoseLandmark.RIGHT_SHOULDER,
                            PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_ELBOW,
                            PoseLandmark.LEFT_ELBOW to PoseLandmark.LEFT_WRIST,
                            PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_ELBOW,
                            PoseLandmark.RIGHT_ELBOW to PoseLandmark.RIGHT_WRIST,
                            PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_HIP,
                            PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_HIP,
                            PoseLandmark.LEFT_HIP to PoseLandmark.RIGHT_HIP,
                            PoseLandmark.LEFT_HIP to PoseLandmark.LEFT_KNEE,
                            PoseLandmark.LEFT_KNEE to PoseLandmark.LEFT_ANKLE,
                            PoseLandmark.RIGHT_HIP to PoseLandmark.RIGHT_KNEE,
                            PoseLandmark.RIGHT_KNEE to PoseLandmark.RIGHT_ANKLE
                        )
                        val edges = edgeTypes.mapNotNull { (a, b) ->
                            val ia = byTypeIdx[a]
                            val ib = byTypeIdx[b]
                            if (ia != null && ib != null) ia to ib else null
                        }
                        val obs = PoseObservation(
                            personBox = box,
                            confidence = avgConf.coerceIn(0.45f, 0.98f),
                            landmarks = points,
                            skeletonEdges = edges
                        )
                        if (cont.isActive) cont.resume(listOf(obs))
                    }
                    .addOnFailureListener {
                        if (cont.isActive) cont.resume(emptyList())
                    }
            }
        } finally {
            if (didAlloc && !inferBmp.isRecycled) inferBmp.recycle()
        }

        val dtMs = ((System.nanoTime() - t0) / 1_000_000f).coerceAtLeast(0.1f)
        return PoseBatch(
            frameSequence = frame.sequence,
            timestampNs = frame.receivedAtNs,
            inferenceDurationMs = dtMs,
            poses = poses,
            providerId = providerId,
            framework = framework
        )
    }

    override fun close() {
        runCatching { client.close() }
    }
}

class MlKitPoseEstimatorProvider : PoseEstimatorProvider {
    override val framework: VisionFramework = VisionFramework.ML_KIT
    override val providerId: String = "MlKitPoseEstimatorProvider"

    override fun assess(model: ModelDescriptor, device: DeviceProfile): ProviderAssessment {
        if (model.task != VisionTask.POSE_ESTIMATION) {
            return ProviderAssessment(false, ComputeBackend.UNKNOWN, null, reason = "Tâche non pose")
        }
        return ProviderAssessment(
            supported = true,
            backend = if (device.gpuDelegateSupported) ComputeBackend.GPU else ComputeBackend.CPU,
            estimatedLatencyMs = device.measuredLatenciesMs[model.id] ?: 55f
        )
    }

    override suspend fun create(model: ModelDescriptor, config: InferenceConfig): PoseEstimator =
        MlKitPoseEstimatorAdapter()
}

class MlKitFaceDetectorAdapter : FaceDetectorPort {
    override val providerId: String = "MlKitFaceDetector"
    override val framework: VisionFramework = VisionFramework.ML_KIT

    private val clientWithLandmarks by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.10f)
                .enableTracking()
                .build()
        )
    }

    private val clientFast by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setMinFaceSize(0.10f)
                .enableTracking()
                .build()
        )
    }

    override suspend fun detectFaces(frame: FrameInput, withLandmarks: Boolean): FaceBatch {
        val t0 = System.nanoTime()
        val (inferBmp, didAlloc) = frame.frame.acquire().use { lease ->
            scaleBitmapForMlKit(lease.asBitmap(), 480)
        }
        val faces = try {
            val client = if (withLandmarks) clientWithLandmarks else clientFast
            suspendCancellableCoroutine { cont ->
                val img = InputImage.fromBitmap(inferBmp, 0)
                val w = inferBmp.width.toFloat().coerceAtLeast(1f)
                val h = inferBmp.height.toFloat().coerceAtLeast(1f)
                client.process(img)
                    .addOnSuccessListener { list ->
                        val mapped = list.mapNotNull { f ->
                            val r = f.boundingBox
                            val box = NormalizedBox(
                                left = (r.left / w).coerceIn(0f, 1f),
                                top = (r.top / h).coerceIn(0f, 1f),
                                right = (r.right / w).coerceIn(0f, 1f),
                                bottom = (r.bottom / h).coerceIn(0f, 1f)
                            )
                            if (box.width < 0.04f || box.height < 0.04f || box.area > 0.90f) return@mapNotNull null
                            val lms = if (withLandmarks) {
                                listOfNotNull(
                                    f.getLandmark(FaceLandmark.LEFT_EYE)?.position?.let { NormalizedPoint(it.x / w, it.y / h, "L_EYE") },
                                    f.getLandmark(FaceLandmark.RIGHT_EYE)?.position?.let { NormalizedPoint(it.x / w, it.y / h, "R_EYE") },
                                    f.getLandmark(FaceLandmark.NOSE_BASE)?.position?.let { NormalizedPoint(it.x / w, it.y / h, "NOSE") },
                                    f.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position?.let { NormalizedPoint(it.x / w, it.y / h, "MOUTH") }
                                )
                            } else emptyList()
                            FaceObservation(
                                box = box,
                                trackingId = f.trackingId,
                                confidence = 0.91f,
                                landmarks = lms,
                                headEulerY = f.headEulerAngleY,
                                headEulerZ = f.headEulerAngleZ
                            )
                        }
                        if (cont.isActive) cont.resume(mapped)
                    }
                    .addOnFailureListener {
                        if (cont.isActive) cont.resume(emptyList())
                    }
            }
        } finally {
            if (didAlloc && !inferBmp.isRecycled) inferBmp.recycle()
        }
        val dtMs = ((System.nanoTime() - t0) / 1_000_000f).coerceAtLeast(0.1f)
        return FaceBatch(frame.sequence, frame.receivedAtNs, dtMs, faces, providerId, framework)
    }

    override fun close() {
        runCatching { clientWithLandmarks.close() }
        runCatching { clientFast.close() }
    }
}

class MlKitBarcodeReaderAdapter(
    @Volatile var calibration: CameraCalibration = CameraCalibration(),
    @Volatile var markerSizeMeters: Float = 0.20f
) : BarcodeReader {

    override val providerId: String = "MlKitBarcodeReaderProvider"
    override val framework: VisionFramework = VisionFramework.ML_KIT

    private val client by lazy {
        val opts = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_DATA_MATRIX,
                Barcode.FORMAT_AZTEC,
                Barcode.FORMAT_CODE_128
            )
            .build()
        BarcodeScanning.getClient(opts)
    }

    override suspend fun scan(frame: FrameInput): BarcodeBatch {
        val t0 = System.nanoTime()
        val bmp = frame.frame.acquire().use { it.asBitmap() }
        val w = bmp.width.toFloat().coerceAtLeast(1f)
        val h = bmp.height.toFloat().coerceAtLeast(1f)

        val detections = suspendCancellableCoroutine { cont ->
            val img = InputImage.fromBitmap(bmp, 0)
            client.process(img)
                .addOnSuccessListener { barcodes ->
                    val mapped = barcodes.mapNotNull { bc ->
                        val rect = bc.boundingBox ?: return@mapNotNull null
                        val box = NormalizedBox(
                            left = (rect.left / w).coerceIn(0f, 1f),
                            top = (rect.top / h).coerceIn(0f, 1f),
                            right = (rect.right / w).coerceIn(0f, 1f),
                            bottom = (rect.bottom / h).coerceIn(0f, 1f)
                        )
                        val corners = bc.cornerPoints?.map { pt ->
                            NormalizedPoint((pt.x / w).coerceIn(0f, 1f), (pt.y / h).coerceIn(0f, 1f))
                        }.orEmpty()
                        val est = MarkerRangeEstimator.estimateFromMarkerCorners(
                            corners = corners,
                            fallbackBox = box,
                            frameWidthPx = bmp.width,
                            frameHeightPx = bmp.height,
                            calibration = calibration,
                            physicalMarkerSizeMeters = markerSizeMeters
                        )
                        val fmt = when (bc.format) {
                            Barcode.FORMAT_QR_CODE -> "QR_CODE"
                            Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX"
                            Barcode.FORMAT_AZTEC -> "AZTEC"
                            else -> "MARKER"
                        }
                        BarcodeDetection(
                            rawValue = bc.rawValue ?: "MARQUEUR",
                            formatLabel = fmt,
                            box = box,
                            corners = corners,
                            estimatedDistanceM = est.first,
                            uncertaintyM = est.second
                        )
                    }
                    if (cont.isActive) cont.resume(mapped)
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(emptyList())
                }
        }
        val dtMs = ((System.nanoTime() - t0) / 1_000_000f).coerceAtLeast(0.1f)
        return BarcodeBatch(frame.sequence, frame.receivedAtNs, dtMs, detections, providerId)
    }

    override fun close() {
        runCatching { client.close() }
    }
}

class MlKitBarcodeReaderProvider : BarcodeReaderProvider {
    override val framework: VisionFramework = VisionFramework.ML_KIT
    override val providerId: String = "MlKitBarcodeReaderProvider"

    override fun assess(model: ModelDescriptor, device: DeviceProfile): ProviderAssessment {
        if (model.task != VisionTask.BARCODE) {
            return ProviderAssessment(false, ComputeBackend.UNKNOWN, null, reason = "Pas un modèle code/marqueur")
        }
        return ProviderAssessment(true, ComputeBackend.CPU, 28f)
    }

    override suspend fun create(model: ModelDescriptor, config: InferenceConfig): BarcodeReader =
        MlKitBarcodeReaderAdapter()
}
