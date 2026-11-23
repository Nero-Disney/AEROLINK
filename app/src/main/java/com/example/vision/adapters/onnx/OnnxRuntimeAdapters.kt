package com.example.vision.adapters.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.example.vision.domain.contracts.ComputeBackend
import com.example.vision.domain.contracts.DepthEstimator
import com.example.vision.domain.contracts.DepthEstimatorProvider
import com.example.vision.domain.contracts.DetectorRequirements
import com.example.vision.domain.contracts.DeviceProfile
import com.example.vision.domain.contracts.FrameInput
import com.example.vision.domain.contracts.InferenceConfig
import com.example.vision.domain.contracts.ModelDescriptor
import com.example.vision.domain.contracts.ObjectDetector
import com.example.vision.domain.contracts.ObjectDetectorProvider
import com.example.vision.domain.contracts.ProviderAssessment
import com.example.vision.domain.contracts.VisionFramework
import com.example.vision.domain.contracts.VisionTask
import com.example.vision.domain.observations.DepthObservation
import com.example.vision.domain.observations.DepthResult
import com.example.vision.domain.observations.DetectionBatch
import com.example.vision.domain.observations.EstimateStatus
import java.io.File

class OnnxYoloDetector(
    private val model: ModelDescriptor,
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val config: InferenceConfig
) : ObjectDetector {

    override val providerId: String = "OnnxYoloDetectorProvider"
    override val framework: VisionFramework = VisionFramework.ONNX_RUNTIME

    private val preprocessor = YoloPreprocessor()
    private val decoder = YoloOutputDecoder()
    private val inputName: String = session.inputNames.firstOrNull() ?: "images"
    private val resolvedInputSpec: Triple<Boolean, Int, Int> = run {
        val shape = (session.inputInfo[inputName]?.info as? TensorInfo)?.shape
        if (shape != null && shape.size == 4) {
            val isNchw = shape[1] == 3L || (shape[3] != 3L && model.input.tensorLayout.equals("NCHW", true))
            val h = (if (isNchw) shape[2] else shape[1]).toInt().takeIf { it in 64..2048 } ?: model.input.height
            val w = (if (isNchw) shape[3] else shape[2]).toInt().takeIf { it in 64..2048 } ?: model.input.width
            Triple(isNchw, w, h)
        } else {
            Triple(model.input.tensorLayout.equals("NCHW", ignoreCase = true), model.input.width, model.input.height)
        }
    }

    override suspend fun detect(frame: FrameInput): DetectionBatch {
        val t0 = System.nanoTime()
        val (nchw, inW, inH) = resolvedInputSpec
        val prepared = frame.frame.acquire().use { lease ->
            preprocessor.prepare(
                bitmap = lease.asBitmap(),
                modelWidth = inW,
                modelHeight = inH,
                nchw = nchw,
                rotationDegrees = frame.rotationDegrees
            )
        }

        val shape = if (nchw) {
            longArrayOf(1L, 3L, prepared.height.toLong(), prepared.width.toLong())
        } else {
            longArrayOf(1L, prepared.height.toLong(), prepared.width.toLong(), 3L)
        }

        val detections = OnnxTensor.createTensor(env, prepared.buffer, shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val rawVal = result.get(0).value
                val matrix: Array<FloatArray>? = when (rawVal) {
                    is Array<*> -> (rawVal.firstOrNull() as? Array<*>)?.mapNotNull { it as? FloatArray }?.toTypedArray()
                    else -> null
                }
                if (matrix != null) {
                    decoder.decodeMatrix(
                        matrix = matrix,
                        transform = prepared.transform,
                        labels = model.labels,
                        providerId = providerId,
                        framework = framework,
                        confThreshold = config.confidenceThreshold
                    )
                } else {
                    emptyList()
                }
            }
        }

        val dtMs = ((System.nanoTime() - t0) / 1_000_000f).coerceAtLeast(0.1f)
        return DetectionBatch(
            frameSequence = frame.sequence,
            timestampNs = frame.receivedAtNs,
            inferenceDurationMs = dtMs,
            frameQualityScore = 1f,
            detections = detections,
            providerId = providerId,
            framework = framework
        )
    }

    override fun close() {
        runCatching { session.close() }
    }
}

class OnnxYoloDetectorProvider : ObjectDetectorProvider {
    override val framework: VisionFramework = VisionFramework.ONNX_RUNTIME
    override val providerId: String = "OnnxYoloDetectorProvider"

    override fun assess(
        model: ModelDescriptor,
        device: DeviceProfile,
        requirements: DetectorRequirements
    ): ProviderAssessment {
        if (model.preferredFramework != VisionFramework.ONNX_RUNTIME &&
            !model.artifact.format.equals("ONNX", ignoreCase = true)
        ) {
            return ProviderAssessment(false, ComputeBackend.UNKNOWN, null, reason = "Format non ONNX")
        }
        val file = File(model.artifact.path)
        if (!model.isArtifactAvailable || !file.exists()) {
            return ProviderAssessment(
                supported = false,
                backend = ComputeBackend.UNKNOWN,
                estimatedLatencyMs = null,
                reason = "Artefact .onnx absent (${model.artifact.path}) — import requis"
            )
        }
        val backend = if (device.nnapiSupported && ComputeBackend.NPU in model.allowedBackends) {
            ComputeBackend.NPU
        } else {
            ComputeBackend.CPU
        }
        val measured = device.measuredLatenciesMs[model.id] ?: 78f
        return ProviderAssessment(
            supported = true,
            backend = backend,
            estimatedLatencyMs = measured
        )
    }

    override suspend fun create(model: ModelDescriptor, config: InferenceConfig): ObjectDetector {
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(config.numThreads.coerceIn(1, 4))
            if (config.preferredBackend == ComputeBackend.NPU) {
                runCatching { addNnapi() }
            }
        }
        val session = env.createSession(model.artifact.path, opts)
        return OnnxYoloDetector(model, env, session, config)
    }
}

class OnnxDepthEstimator(
    private val model: ModelDescriptor,
    private val env: OrtEnvironment,
    private val session: OrtSession
) : DepthEstimator {

    override val providerId: String = "OnnxDepthEstimatorProvider"
    override val framework: VisionFramework = VisionFramework.ONNX_RUNTIME
    private val preprocessor = YoloPreprocessor()
    private val inputName: String = session.inputNames.firstOrNull() ?: "images"
    private val resolvedInputSpec: Triple<Boolean, Int, Int> = run {
        val shape = (session.inputInfo[inputName]?.info as? TensorInfo)?.shape
        if (shape != null && shape.size == 4) {
            val isNchw = shape[1] == 3L || shape[3] != 3L
            val h = (if (isNchw) shape[2] else shape[1]).toInt().takeIf { it in 64..2048 } ?: model.input.height
            val w = (if (isNchw) shape[3] else shape[2]).toInt().takeIf { it in 64..2048 } ?: model.input.width
            Triple(isNchw, w, h)
        } else {
            Triple(true, model.input.width, model.input.height)
        }
    }

    override suspend fun estimate(frame: FrameInput): DepthResult {
        val t0 = System.nanoTime()
        val (nchw, inW, inH) = resolvedInputSpec
        val prepared = frame.frame.acquire().use { lease ->
            preprocessor.prepare(
                bitmap = lease.asBitmap(),
                modelWidth = inW,
                modelHeight = inH,
                nchw = nchw,
                rotationDegrees = frame.rotationDegrees
            )
        }
        val shape = longArrayOf(1L, 3L, prepared.height.toLong(), prepared.width.toLong())

        var obs: DepthObservation? = null
        OnnxTensor.createTensor(env, prepared.buffer, shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val rawVal = result.get(0).value
                val matrix = when (rawVal) {
                    is Array<*> -> {
                        val first = rawVal.firstOrNull()
                        when (first) {
                            is Array<*> -> (first.firstOrNull() as? Array<*>)?.mapNotNull { it as? FloatArray }?.toTypedArray()
                                ?: first.mapNotNull { it as? FloatArray }.toTypedArray()
                            else -> null
                        }
                    }
                    else -> null
                }
                if (matrix != null && matrix.isNotEmpty()) {
                    val dh = matrix.size
                    val dw = matrix[0].size
                    val flat = FloatArray(dw * dh)
                    var minD = Float.MAX_VALUE
                    var maxD = -Float.MAX_VALUE
                    for (y in 0 until dh) {
                        for (x in 0 until dw) {
                            val v = matrix[y][x]
                            flat[y * dw + x] = v
                            if (v < minD) minD = v
                            if (v > maxD) maxD = v
                        }
                    }
                    obs = DepthObservation(
                        width = dw,
                        height = dh,
                        minDepth = minD,
                        maxDepth = maxD,
                        isMetric = false,
                        values = flat
                    )
                }
            }
        }

        val dtMs = ((System.nanoTime() - t0) / 1_000_000f).coerceAtLeast(0.1f)
        return DepthResult(
            frameSequence = frame.sequence,
            timestampNs = frame.receivedAtNs,
            inferenceDurationMs = dtMs,
            observation = obs,
            providerId = providerId,
            framework = framework,
            status = if (obs != null) EstimateStatus.VALID_WITH_UNCERTAINTY else EstimateStatus.INVALID
        )
    }

    override fun close() {
        runCatching { session.close() }
    }
}

class OnnxDepthEstimatorProvider : DepthEstimatorProvider {
    override val framework: VisionFramework = VisionFramework.ONNX_RUNTIME
    override val providerId: String = "OnnxDepthEstimatorProvider"

    override fun assess(model: ModelDescriptor, device: DeviceProfile): ProviderAssessment {
        if (model.task != VisionTask.DEPTH_ESTIMATION || !model.artifact.format.equals("ONNX", true)) {
            return ProviderAssessment(false, ComputeBackend.UNKNOWN, null, reason = "Pas un modèle profondeur ONNX")
        }
        val file = File(model.artifact.path)
        if (!model.isArtifactAvailable || !file.exists()) {
            return ProviderAssessment(false, ComputeBackend.UNKNOWN, null, reason = "Artefact yolo26n-depth.onnx absent")
        }
        return ProviderAssessment(
            supported = true,
            backend = ComputeBackend.CPU,
            estimatedLatencyMs = device.measuredLatenciesMs[model.id] ?: 272f,
            capabilityGapWarningFr = "Profondeur monoculaire exécutée à basse fréquence (1-2 Hz)"
        )
    }

    override suspend fun create(model: ModelDescriptor, config: InferenceConfig): DepthEstimator {
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(config.numThreads.coerceIn(1, 4))
        }
        val session = env.createSession(model.artifact.path, opts)
        return OnnxDepthEstimator(model, env, session)
    }
}
