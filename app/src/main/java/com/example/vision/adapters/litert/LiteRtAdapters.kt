package com.example.vision.adapters.litert

import com.example.vision.adapters.onnx.YoloOutputDecoder
import com.example.vision.adapters.onnx.YoloPreprocessor
import com.example.vision.domain.contracts.ComputeBackend
import com.example.vision.domain.contracts.DetectorRequirements
import com.example.vision.domain.contracts.DeviceProfile
import com.example.vision.domain.contracts.FrameInput
import com.example.vision.domain.contracts.InferenceConfig
import com.example.vision.domain.contracts.ModelDescriptor
import com.example.vision.domain.contracts.ObjectDetector
import com.example.vision.domain.contracts.ObjectDetectorProvider
import com.example.vision.domain.contracts.ProviderAssessment
import com.example.vision.domain.contracts.VisionFramework
import com.example.vision.domain.observations.DetectionBatch
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File

class LiteRtYoloDetector(
    private val model: ModelDescriptor,
    private val interpreter: Interpreter,
    private val gpuDelegate: GpuDelegate?,
    private val config: InferenceConfig
) : ObjectDetector {

    override val providerId: String = "LiteRtDetectorProvider"
    override val framework: VisionFramework = VisionFramework.LITERT

    private val preprocessor = YoloPreprocessor()
    private val decoder = YoloOutputDecoder()

    private val resolvedInputSpec: Triple<Boolean, Int, Int> = run {
        val shape = runCatching { interpreter.getInputTensor(0).shape() }.getOrNull()
        if (shape != null && shape.size == 4) {
            val isNchw = shape[1] == 3 && shape[3] != 3
            val h = (if (isNchw) shape[2] else shape[1]).takeIf { it in 64..2048 } ?: model.input.height
            val w = (if (isNchw) shape[3] else shape[2]).takeIf { it in 64..2048 } ?: model.input.width
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

        val outTensor = interpreter.getOutputTensor(0)
        val outShape = outTensor.shape()
        val detections = if (outShape.size == 3 && outShape[0] == 1) {
            val rows = outShape[1]
            val cols = outShape[2]
            val outBuffer = Array(1) { Array(rows) { FloatArray(cols) } }
            interpreter.run(prepared.buffer, outBuffer)
            decoder.decodeMatrix(
                matrix = outBuffer[0],
                transform = prepared.transform,
                labels = model.labels,
                providerId = providerId,
                framework = framework,
                confThreshold = config.confidenceThreshold
            )
        } else {
            emptyList()
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
        runCatching { interpreter.close() }
        runCatching { gpuDelegate?.close() }
    }
}

class LiteRtYoloDetectorProvider : ObjectDetectorProvider {
    override val framework: VisionFramework = VisionFramework.LITERT
    override val providerId: String = "LiteRtDetectorProvider"

    override fun assess(
        model: ModelDescriptor,
        device: DeviceProfile,
        requirements: DetectorRequirements
    ): ProviderAssessment {
        if (!model.artifact.format.equals("TFLITE", ignoreCase = true)) {
            return ProviderAssessment(false, ComputeBackend.UNKNOWN, null, reason = "Format non TFLite")
        }
        val file = File(model.artifact.path)
        if (!model.isArtifactAvailable || !file.exists()) {
            return ProviderAssessment(
                supported = false,
                backend = ComputeBackend.UNKNOWN,
                estimatedLatencyMs = null,
                reason = "Artefact .tflite absent (${model.artifact.path}) — import requis"
            )
        }
        val backend = if (device.gpuDelegateSupported && ComputeBackend.GPU in model.allowedBackends) {
            ComputeBackend.GPU
        } else {
            ComputeBackend.CPU
        }
        return ProviderAssessment(
            supported = true,
            backend = backend,
            estimatedLatencyMs = device.measuredLatenciesMs[model.id] ?: 62f
        )
    }

    override suspend fun create(model: ModelDescriptor, config: InferenceConfig): ObjectDetector {
        val options = Interpreter.Options().apply {
            setNumThreads(config.numThreads.coerceIn(1, 4))
        }
        var gpu: GpuDelegate? = null
        if (config.preferredBackend == ComputeBackend.GPU) {
            runCatching {
                gpu = GpuDelegate()
                options.addDelegate(gpu)
            }.onFailure {
                gpu?.close()
                gpu = null
            }
        }
        val interpreter = Interpreter(File(model.artifact.path), options)
        return LiteRtYoloDetector(model, interpreter, gpu, config)
    }
}
