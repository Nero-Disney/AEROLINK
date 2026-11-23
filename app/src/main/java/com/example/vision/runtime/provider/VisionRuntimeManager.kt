package com.example.vision.runtime.provider

import android.os.Build
import com.example.vision.domain.contracts.BarcodeReader
import com.example.vision.domain.contracts.BarcodeReaderProvider
import com.example.vision.domain.contracts.ComputeBackend
import com.example.vision.domain.contracts.DepthEstimator
import com.example.vision.domain.contracts.DepthEstimatorProvider
import com.example.vision.domain.contracts.DetectorRequirements
import com.example.vision.domain.contracts.DeviceProfile
import com.example.vision.domain.contracts.InferenceConfig
import com.example.vision.domain.contracts.ModelDescriptor
import com.example.vision.domain.contracts.ObjectDetector
import com.example.vision.domain.contracts.ObjectDetectorProvider
import com.example.vision.domain.contracts.PoseEstimator
import com.example.vision.domain.contracts.PoseEstimatorProvider
import com.example.vision.domain.contracts.ProviderAssessment
import com.example.vision.domain.contracts.RuntimeCandidate
import com.example.vision.domain.contracts.VisionFramework
import com.example.vision.domain.contracts.VisionTask
import com.example.vision.runtime.modelregistry.ModelRegistry

data class VisionRuntimeConfig(
    val preferredObjectFramework: VisionFramework = VisionFramework.ONNX_RUNTIME,
    val preferredPoseFramework: VisionFramework = VisionFramework.ML_KIT,
    val preferredDepthFramework: VisionFramework = VisionFramework.ONNX_RUNTIME,
    val preferredBackend: ComputeBackend = ComputeBackend.CPU,
    val allowFallback: Boolean = true
)

data class ResolutionReport(
    val task: VisionTask,
    val requestedFramework: VisionFramework,
    val selectedFramework: VisionFramework?,
    val selectedProviderId: String?,
    val selectedModelId: String?,
    val selectedBackend: ComputeBackend,
    val usedFallback: Boolean,
    val warningFr: String? = null,
    val candidateSummaries: List<String> = emptyList()
)

class ObjectDetectorResolver(
    private val providers: List<ObjectDetectorProvider>
) {
    suspend fun resolve(
        models: List<ModelDescriptor>,
        device: DeviceProfile,
        requirements: DetectorRequirements,
        runtimeConfig: VisionRuntimeConfig,
        inferenceConfig: InferenceConfig
    ): Pair<ObjectDetector?, ResolutionReport> {
        val candidates = mutableListOf<RuntimeCandidate>()
        val summaries = mutableListOf<String>()

        for (model in models) {
            for (provider in providers) {
                val assessment = provider.assess(model, device, requirements)
                summaries.add("${provider.providerId} (${model.id}) -> ${if (assessment.supported) "OK" else assessment.reason}")
                if (!assessment.supported) continue

                val preferredBonus = if (provider.framework == runtimeConfig.preferredObjectFramework) -500f else 0f
                val customBonus = if (model.supportsCustomClasses) -150f else 0f
                val latency = assessment.estimatedLatencyMs ?: 120f
                val score = latency + preferredBonus + customBonus
                candidates.add(RuntimeCandidate(provider, model, assessment, score))
            }
        }

        val sorted = candidates.sortedBy { it.score }.filter { cand ->
            runtimeConfig.allowFallback || cand.provider.framework == runtimeConfig.preferredObjectFramework
        }

        for (cand in sorted) {
            val resolved = runCatching {
                cand.provider.create(cand.model, inferenceConfig.copy(preferredBackend = cand.assessment.backend))
            }.getOrNull()

            if (resolved != null) {
                val usedFallback = cand.provider.framework != runtimeConfig.preferredObjectFramework
                val warning = buildString {
                    if (usedFallback) {
                        append("Repli vers ${cand.provider.framework.displayName} (")
                        append("${runtimeConfig.preferredObjectFramework.displayName} indisponible ou non chargé)")
                    }
                    cand.assessment.capabilityGapWarningFr?.let { gap ->
                        if (isNotEmpty()) append(" • ")
                        append(gap)
                    }
                }.ifBlank { null }

                return resolved to ResolutionReport(
                    task = VisionTask.OBJECT_DETECTION,
                    requestedFramework = runtimeConfig.preferredObjectFramework,
                    selectedFramework = cand.provider.framework,
                    selectedProviderId = cand.provider.providerId,
                    selectedModelId = cand.model.id,
                    selectedBackend = cand.assessment.backend,
                    usedFallback = usedFallback,
                    warningFr = warning,
                    candidateSummaries = summaries
                )
            }
        }

        return null to ResolutionReport(
            task = VisionTask.OBJECT_DETECTION,
            requestedFramework = runtimeConfig.preferredObjectFramework,
            selectedFramework = null,
            selectedProviderId = null,
            selectedModelId = null,
            selectedBackend = ComputeBackend.UNKNOWN,
            usedFallback = false,
            warningFr = "Aucun provider compatible pour les exigences demandées",
            candidateSummaries = summaries
        )
    }
}

class VisionRuntimeManager(
    private val modelRegistry: ModelRegistry,
    private val objectProviders: List<ObjectDetectorProvider>,
    private val poseProviders: List<PoseEstimatorProvider>,
    private val depthProviders: List<DepthEstimatorProvider>,
    private val barcodeProviders: List<BarcodeReaderProvider>
) {
    private val objectResolver = ObjectDetectorResolver(objectProviders)

    fun currentDeviceProfile(
        gpuDelegateSupported: Boolean,
        measuredLatenciesMs: Map<String, Float> = emptyMap()
    ): DeviceProfile = DeviceProfile(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        sdkInt = Build.VERSION.SDK_INT,
        gpuDelegateSupported = gpuDelegateSupported,
        nnapiSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P,
        measuredLatenciesMs = measuredLatenciesMs
    )

    suspend fun resolveObjectDetector(
        device: DeviceProfile,
        requirements: DetectorRequirements,
        runtimeConfig: VisionRuntimeConfig,
        inferenceConfig: InferenceConfig
    ): Pair<ObjectDetector?, ResolutionReport> {
        val objectModels = modelRegistry.modelsForTask(VisionTask.OBJECT_DETECTION)
        return objectResolver.resolve(objectModels, device, requirements, runtimeConfig, inferenceConfig)
    }

    suspend fun resolvePoseEstimator(
        device: DeviceProfile,
        runtimeConfig: VisionRuntimeConfig,
        inferenceConfig: InferenceConfig
    ): PoseEstimator? {
        val models = modelRegistry.modelsForTask(VisionTask.POSE_ESTIMATION)
        for (model in models) {
            for (provider in poseProviders) {
                if (!runtimeConfig.allowFallback && provider.framework != runtimeConfig.preferredPoseFramework) continue
                val assessment = provider.assess(model, device)
                if (assessment.supported) {
                    val created = runCatching { provider.create(model, inferenceConfig) }.getOrNull()
                    if (created != null) return created
                }
            }
        }
        return null
    }

    suspend fun resolveDepthEstimator(
        device: DeviceProfile,
        runtimeConfig: VisionRuntimeConfig,
        inferenceConfig: InferenceConfig
    ): Pair<DepthEstimator?, ResolutionReport> {
        val models = modelRegistry.modelsForTask(VisionTask.DEPTH_ESTIMATION)
        val summaries = mutableListOf<String>()
        for (model in models) {
            for (provider in depthProviders) {
                val assessment = provider.assess(model, device)
                summaries.add("${provider.providerId} (${model.id}) -> ${if (assessment.supported) "OK" else assessment.reason}")
                if (!assessment.supported) continue
                if (!runtimeConfig.allowFallback && provider.framework != runtimeConfig.preferredDepthFramework) continue
                val created = runCatching { provider.create(model, inferenceConfig) }.getOrNull()
                if (created != null) {
                    return created to ResolutionReport(
                        task = VisionTask.DEPTH_ESTIMATION,
                        requestedFramework = runtimeConfig.preferredDepthFramework,
                        selectedFramework = provider.framework,
                        selectedProviderId = provider.providerId,
                        selectedModelId = model.id,
                        selectedBackend = assessment.backend,
                        usedFallback = provider.framework != runtimeConfig.preferredDepthFramework,
                        warningFr = assessment.capabilityGapWarningFr,
                        candidateSummaries = summaries
                    )
                }
            }
        }
        return null to ResolutionReport(
            task = VisionTask.DEPTH_ESTIMATION,
            requestedFramework = runtimeConfig.preferredDepthFramework,
            selectedFramework = null,
            selectedProviderId = null,
            selectedModelId = null,
            selectedBackend = ComputeBackend.UNKNOWN,
            usedFallback = true,
            warningFr = "Modèle de profondeur dense non importé — repli géométrique / PnP actif",
            candidateSummaries = summaries
        )
    }

    suspend fun resolveBarcodeReader(
        device: DeviceProfile,
        inferenceConfig: InferenceConfig
    ): BarcodeReader? {
        val models = modelRegistry.modelsForTask(VisionTask.BARCODE)
        for (model in models) {
            for (provider in barcodeProviders) {
                val assessment = provider.assess(model, device)
                if (assessment.supported) {
                    val created = runCatching { provider.create(model, inferenceConfig) }.getOrNull()
                    if (created != null) return created
                }
            }
        }
        return null
    }
}
