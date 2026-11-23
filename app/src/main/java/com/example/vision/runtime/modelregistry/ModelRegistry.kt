package com.example.vision.runtime.modelregistry

import android.content.Context
import android.net.Uri
import com.example.vision.domain.contracts.ComputeBackend
import com.example.vision.domain.contracts.CoordinateSpace
import com.example.vision.domain.contracts.ModelArtifact
import com.example.vision.domain.contracts.ModelDescriptor
import com.example.vision.domain.contracts.ModelInputSpec
import com.example.vision.domain.contracts.ModelLabel
import com.example.vision.domain.contracts.ModelOutputSpec
import com.example.vision.domain.contracts.VisionFramework
import com.example.vision.domain.contracts.VisionTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

@Serializable
private data class RegistryJsonRoot(
    val registryVersion: String = "1.0.0",
    val models: List<RegistryModelEntry> = emptyList()
)

@Serializable
private data class RegistryModelEntry(
    val id: String,
    val name: String,
    val version: String,
    val task: String,
    val family: String,
    val artifact: RegistryArtifactEntry,
    val input: RegistryInputEntry,
    val output: RegistryOutputEntry,
    val labels: List<RegistryLabelEntry> = emptyList(),
    val preferredFramework: String? = null,
    val allowedBackends: List<String> = listOf("CPU"),
    val checksumSha256: String = "",
    val modelLicense: String = "",
    val descriptionFr: String = "",
    val supportsCustomClasses: Boolean = false
)

@Serializable
private data class RegistryArtifactEntry(
    val path: String,
    val format: String,
    val sizeBytes: Long = 0L
)

@Serializable
private data class RegistryInputEntry(
    val width: Int,
    val height: Int,
    val colorOrder: String,
    val tensorLayout: String,
    val normalization: String,
    val resizeMode: String
)

@Serializable
private data class RegistryOutputEntry(
    val decoderId: String,
    val coordinateSpace: String,
    val outputNames: List<String> = emptyList()
)

@Serializable
private data class RegistryLabelEntry(
    val id: String,
    val name: String
)

class ModelRegistry(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val modelsRootDir: File by lazy {
        File(context.filesDir, "advds").apply { mkdirs() }
    }

    private val _models = MutableStateFlow<List<ModelDescriptor>>(emptyList())
    val models: StateFlow<List<ModelDescriptor>> = _models.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val parsed = loadFromAssets()
        val resolved = parsed.map { desc ->
            val path = desc.artifact.path
            when {
                path.startsWith("bundled://") || path.startsWith("builtin://") -> {
                    desc.copy(isArtifactAvailable = true)
                }
                else -> {
                    val localFile = resolveLocalFile(path)
                    if (localFile != null && localFile.exists() && localFile.length() > 0L) {
                        desc.copy(
                            artifact = desc.artifact.copy(
                                path = localFile.absolutePath,
                                sizeBytes = localFile.length()
                            ),
                            checksumSha256 = computeSha256(localFile),
                            isArtifactAvailable = true
                        )
                    } else {
                        desc.copy(isArtifactAvailable = false)
                    }
                }
            }
        }
        _models.value = resolved
    }

    fun modelsForTask(task: VisionTask): List<ModelDescriptor> =
        _models.value.filter { it.task == task }

    fun getById(id: String): ModelDescriptor? =
        _models.value.firstOrNull { it.id == id }

    suspend fun importArtifactForModel(modelId: String, uri: Uri): Result<ModelDescriptor> =
        withContext(Dispatchers.IO) {
            runCatching {
                val targetDesc = getById(modelId)
                    ?: error("Modèle introuvable dans le registre: $modelId")
                val relPath = when (targetDesc.artifact.format.uppercase()) {
                    "ONNX" -> "models/${modelId}.onnx"
                    "TFLITE" -> "models/${modelId}.tflite"
                    else -> error("Ce modèle intégré ne nécessite pas d'import externe")
                }
                val destFile = File(modelsRootDir, relPath)
                destFile.parentFile?.mkdirs()

                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { out ->
                        input.copyTo(out)
                    }
                } ?: error("Impossible de lire le fichier sélectionné")

                require(destFile.length() > 1024L) {
                    "Fichier modèle vide ou invalide (${destFile.length()} octets)"
                }
                refresh()
                getById(modelId) ?: targetDesc
            }
        }

    suspend fun removeImportedArtifact(modelId: String) = withContext(Dispatchers.IO) {
        val desc = getById(modelId) ?: return@withContext
        val f = File(desc.artifact.path)
        if (f.exists() && f.absolutePath.startsWith(modelsRootDir.absolutePath)) {
            f.delete()
        }
        refresh()
    }

    private fun resolveLocalFile(relativeOrAbs: String): File? {
        val abs = File(relativeOrAbs)
        if (abs.isAbsolute && abs.exists()) return abs
        val underRoot = File(modelsRootDir, relativeOrAbs)
        if (underRoot.exists() && underRoot.length() > 1024L) return underRoot
        val fileName = relativeOrAbs.substringAfterLast('/')
        val byIdModel = File(modelsRootDir, "models/$fileName")
        if (byIdModel.exists() && byIdModel.length() > 1024L) return byIdModel

        // Vérifie si l'artefact a été empaqueté dans assets/advds/models/<fichier>
        val assetPath = "advds/models/$fileName"
        val extracted = runCatching {
            context.assets.open(assetPath).use { input ->
                byIdModel.parentFile?.mkdirs()
                byIdModel.outputStream().use { out -> input.copyTo(out) }
            }
            if (byIdModel.exists() && byIdModel.length() > 1024L) byIdModel else null
        }.getOrNull()
        if (extracted != null) return extracted

        return underRoot
    }

    private fun loadFromAssets(): List<ModelDescriptor> {
        val raw = runCatching {
            context.assets.open("advds/model_registry.json").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return emptyList()

        val root = runCatching { json.decodeFromString<RegistryJsonRoot>(raw) }.getOrNull()
            ?: return emptyList()

        return root.models.map { e ->
            val task = runCatching { VisionTask.valueOf(e.task) }.getOrDefault(VisionTask.OBJECT_DETECTION)
            val fw = e.preferredFramework?.let { runCatching { VisionFramework.valueOf(it) }.getOrNull() }
            val backends = e.allowedBackends.mapNotNull {
                runCatching { ComputeBackend.valueOf(it) }.getOrNull()
            }.toSet().ifEmpty { setOf(ComputeBackend.CPU) }
            val coordSpace = runCatching { CoordinateSpace.valueOf(e.output.coordinateSpace) }
                .getOrDefault(CoordinateSpace.MODEL_INPUT)

            val candidateIdFile = when (e.artifact.format.uppercase()) {
                "ONNX" -> File(modelsRootDir, "models/${e.id}.onnx")
                "TFLITE" -> File(modelsRootDir, "models/${e.id}.tflite")
                else -> null
            }
            val effectivePath = if (candidateIdFile != null && candidateIdFile.exists()) {
                candidateIdFile.absolutePath
            } else {
                e.artifact.path
            }

            ModelDescriptor(
                id = e.id,
                name = e.name,
                version = e.version,
                task = task,
                family = e.family,
                artifact = ModelArtifact(
                    path = effectivePath,
                    format = e.artifact.format,
                    sizeBytes = e.artifact.sizeBytes
                ),
                input = ModelInputSpec(
                    width = e.input.width,
                    height = e.input.height,
                    colorOrder = e.input.colorOrder,
                    tensorLayout = e.input.tensorLayout,
                    normalization = e.input.normalization,
                    resizeMode = e.input.resizeMode
                ),
                output = ModelOutputSpec(
                    decoderId = e.output.decoderId,
                    coordinateSpace = coordSpace,
                    outputNames = e.output.outputNames
                ),
                labels = e.labels.map { ModelLabel(it.id, it.name) },
                preferredFramework = fw,
                allowedBackends = backends,
                checksumSha256 = e.checksumSha256,
                modelLicense = e.modelLicense,
                descriptionFr = e.descriptionFr,
                supportsCustomClasses = e.supportsCustomClasses,
                isArtifactAvailable = false
            )
        }
    }

    private fun computeSha256(file: File): String {
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                var read = input.read(buf)
                while (read > 0) {
                    digest.update(buf, 0, read)
                    read = input.read(buf)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }.take(16) + "…"
        }.getOrDefault("SHA256_ERR")
    }
}
