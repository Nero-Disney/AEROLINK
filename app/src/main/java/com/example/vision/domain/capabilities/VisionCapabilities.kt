package com.example.vision.domain.capabilities

import com.example.vision.domain.contracts.VisionFramework
import com.example.vision.domain.contracts.VisionTask

data class FrameworkCapabilities(
    val framework: VisionFramework,
    val supportedTasks: Set<VisionTask>,
    val maxObjectsPerFrame: Int?,
    val supportsCustomTaxonomy: Boolean,
    val supportsSegmentationMasks: Boolean,
    val honestLimitNoteFr: String
)

sealed class VisionDomainError(val messageFr: String) {
    data class UnsupportedCapabilityError(val detailFr: String) : VisionDomainError(detailFr)
    data class ModelArtifactMissingError(val modelId: String, val path: String) :
        VisionDomainError("Artefact modèle manquant pour $modelId ($path)")
    data class CorruptedFrameError(val detailFr: String) : VisionDomainError(detailFr)
    data class CalibrationRequiredError(val detailFr: String) : VisionDomainError(detailFr)
}
