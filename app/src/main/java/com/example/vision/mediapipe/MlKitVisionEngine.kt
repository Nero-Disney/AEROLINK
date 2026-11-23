package com.example.vision.mediapipe

import android.graphics.Bitmap
import com.example.core.models.AiDetection
import com.example.core.models.DetectorType
import com.example.core.models.NormalizedBox
import com.example.core.models.NormalizedPoint
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

class MlKitVisionEngine {

    private val faceDetectorWithLandmarks by lazy {
        val opts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.10f)
            .enableTracking()
            .build()
        FaceDetection.getClient(opts)
    }

    private val faceDetectorFast by lazy {
        val opts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.10f)
            .enableTracking()
            .build()
        FaceDetection.getClient(opts)
    }

    private val poseDetector by lazy {
        val opts = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
        PoseDetection.getClient(opts)
    }

    private val objectDetector by lazy {
        val opts = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
        ObjectDetection.getClient(opts)
    }

    suspend fun analyze(
        bitmap: Bitmap,
        detectorType: DetectorType,
        enableLandmarks: Boolean = true
    ): List<AiDetection> {
        if (detectorType == DetectorType.NONE || bitmap.isRecycled) return emptyList()

        // Réduction contrôlée à 480 px max pour accélérer nettement ML Kit tout en gardant des boîtes normalisées [0..1] exactes
        val maxDim = 480
        val inferBmp = if (bitmap.width > maxDim || bitmap.height > maxDim) {
            val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(16),
                (bitmap.height * scale).toInt().coerceAtLeast(16),
                false
            )
        } else {
            bitmap
        }

        val w = inferBmp.width.coerceAtLeast(1).toFloat()
        val h = inferBmp.height.coerceAtLeast(1).toFloat()
        val image = InputImage.fromBitmap(inferBmp, 0)

        return try {
            when (detectorType) {
                DetectorType.NONE -> emptyList()
                DetectorType.FACE_LANDMARKS -> detectFaces(image, w, h, enableLandmarks)
                DetectorType.PERSON_POSE -> detectPose(image, w, h)
                DetectorType.OBJECTS_GENERAL -> detectObjects(image, w, h)
            }
        } finally {
            if (inferBmp !== bitmap && !inferBmp.isRecycled) {
                inferBmp.recycle()
            }
        }
    }

    private suspend fun detectFaces(
        image: InputImage,
        w: Float,
        h: Float,
        enableLandmarks: Boolean
    ): List<AiDetection> = suspendCancellableCoroutine { cont ->
        val client = if (enableLandmarks) faceDetectorWithLandmarks else faceDetectorFast
        client.process(image)
            .addOnSuccessListener { faces ->
                val list = faces.mapNotNull { face ->
                    val b = face.boundingBox
                    val box = NormalizedBox(
                        left = (b.left / w).coerceIn(0f, 1f),
                        top = (b.top / h).coerceIn(0f, 1f),
                        right = (b.right / w).coerceIn(0f, 1f),
                        bottom = (b.bottom / h).coerceIn(0f, 1f)
                    )
                    if (box.width < 0.04f || box.height < 0.04f || box.area > 0.90f) {
                        return@mapNotNull null
                    }
                    val landmarks = if (enableLandmarks) {
                        listOfNotNull(
                            face.getLandmark(FaceLandmark.LEFT_EYE)?.let {
                                NormalizedPoint((it.position.x / w).coerceIn(0f, 1f), (it.position.y / h).coerceIn(0f, 1f), "Oeil G")
                            },
                            face.getLandmark(FaceLandmark.RIGHT_EYE)?.let {
                                NormalizedPoint((it.position.x / w).coerceIn(0f, 1f), (it.position.y / h).coerceIn(0f, 1f), "Oeil D")
                            },
                            face.getLandmark(FaceLandmark.NOSE_BASE)?.let {
                                NormalizedPoint((it.position.x / w).coerceIn(0f, 1f), (it.position.y / h).coerceIn(0f, 1f), "Nez")
                            },
                            face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.let {
                                NormalizedPoint((it.position.x / w).coerceIn(0f, 1f), (it.position.y / h).coerceIn(0f, 1f), "Bouche")
                            }
                        )
                    } else emptyList()

                    // Confiance reflétant la qualité structurelle du visage détecté
                    val conf = if (enableLandmarks) {
                        if (landmarks.isEmpty()) 0.55f else (0.76f + landmarks.size * 0.05f).coerceAtMost(0.96f)
                    } else {
                        0.88f
                    }

                    AiDetection(
                        label = "Visage",
                        category = "FACE",
                        confidence = conf,
                        box = box,
                        landmarks = landmarks
                    )
                }
                if (cont.isActive) cont.resume(list)
            }
            .addOnFailureListener {
                if (cont.isActive) cont.resume(emptyList())
            }
    }

    private suspend fun detectPose(image: InputImage, w: Float, h: Float): List<AiDetection> =
        suspendCancellableCoroutine { cont ->
            poseDetector.process(image)
                .addOnSuccessListener { pose ->
                    val allLandmarks = pose.allPoseLandmarks
                    if (allLandmarks.isEmpty()) {
                        if (cont.isActive) cont.resume(emptyList())
                        return@addOnSuccessListener
                    }

                    val reliable = allLandmarks.filter { it.inFrameLikelihood >= 0.45f }
                    val highConfCount = allLandmarks.count { it.inFrameLikelihood >= 0.55f }
                    if (highConfCount < 6 || reliable.size < 6) {
                        if (cont.isActive) cont.resume(emptyList())
                        return@addOnSuccessListener
                    }

                    var minX = 1f
                    var minY = 1f
                    var maxX = 0f
                    var maxY = 0f
                    var confSum = 0f
                    val points = mutableListOf<NormalizedPoint>()
                    val indexByType = mutableMapOf<Int, Int>()

                    reliable.forEach { lm ->
                        val nx = (lm.position.x / w).coerceIn(0f, 1f)
                        val ny = (lm.position.y / h).coerceIn(0f, 1f)
                        minX = minOf(minX, nx)
                        minY = minOf(minY, ny)
                        maxX = maxOf(maxX, nx)
                        maxY = maxOf(maxY, ny)
                        confSum += lm.inFrameLikelihood
                        indexByType[lm.landmarkType] = points.size
                        points.add(NormalizedPoint(nx, ny))
                    }

                    val avgConf = (confSum / reliable.size).coerceIn(0f, 1f)
                    val box = NormalizedBox(
                        left = (minX - 0.03f).coerceIn(0f, 1f),
                        top = (minY - 0.03f).coerceIn(0f, 1f),
                        right = (maxX + 0.03f).coerceIn(0f, 1f),
                        bottom = (maxY + 0.03f).coerceIn(0f, 1f)
                    )
                    if (avgConf < 0.55f || box.width < 0.05f || box.height < 0.08f || box.area > 0.92f) {
                        if (cont.isActive) cont.resume(emptyList())
                        return@addOnSuccessListener
                    }

                    val edgePairs = listOf(
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
                    ).mapNotNull { (a, b) ->
                        val ia = indexByType[a]
                        val ib = indexByType[b]
                        if (ia != null && ib != null) ia to ib else null
                    }

                    val detection = AiDetection(
                        label = "Personne",
                        category = "PERSON",
                        confidence = avgConf,
                        box = box,
                        landmarks = points,
                        skeletonEdges = edgePairs
                    )
                    if (cont.isActive) cont.resume(listOf(detection))
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(emptyList())
                }
        }

    private suspend fun detectObjects(image: InputImage, w: Float, h: Float): List<AiDetection> =
        suspendCancellableCoroutine { cont ->
            objectDetector.process(image)
                .addOnSuccessListener { objects ->
                    val list = objects.mapNotNull { obj ->
                        val b = obj.boundingBox
                        val box = NormalizedBox(
                            left = (b.left / w).coerceIn(0f, 1f),
                            top = (b.top / h).coerceIn(0f, 1f),
                            right = (b.right / w).coerceIn(0f, 1f),
                            bottom = (b.bottom / h).coerceIn(0f, 1f)
                        )
                        // Rejette les micro-bruits ou artefacts plein écran (caméra couverte / flou)
                        if (box.width < 0.04f || box.height < 0.04f || box.area > 0.85f) {
                            return@mapNotNull null
                        }
                        val topLabel = obj.labels.maxByOrNull { it.confidence }
                        // Ne jamais inventer une fausse confiance 0.65f pour un blob non classifié
                        if (topLabel == null || topLabel.confidence < 0.35f) {
                            return@mapNotNull null
                        }
                        AiDetection(
                            label = translateLabelFr(topLabel.text),
                            category = topLabel.text.uppercase(),
                            confidence = topLabel.confidence,
                            box = box
                        )
                    }
                    if (cont.isActive) cont.resume(list)
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(emptyList())
                }
        }

    private fun translateLabelFr(english: String): String = when (english.lowercase()) {
        "fashion good" -> "Accessoire / Vêtement"
        "home good" -> "Objet intérieur"
        "food" -> "Aliment"
        "place" -> "Structure / Lieu"
        "plant" -> "Plante"
        "person" -> "Personne"
        else -> english
    }

    fun close() {
        runCatching { faceDetectorWithLandmarks.close() }
        runCatching { faceDetectorFast.close() }
        runCatching { poseDetector.close() }
        runCatching { objectDetector.close() }
    }
}
