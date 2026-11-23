package com.example.ui.screens

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.models.DetectorType
import com.example.core.models.HardwareBackend
import com.example.storage.db.BenchmarkReportEntity
import com.example.testlab.ValidationLabReport
import com.example.ui.theme.CautionAmber
import com.example.ui.theme.EmergencyCrimson
import com.example.ui.theme.NominalEmerald
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TelemetryMonoStyle
import com.example.vision.domain.contracts.ModelDescriptor
import com.example.vision.domain.contracts.VisionFramework
import com.example.vision.litert.InstalledModelSpec
import com.example.vision.runtime.provider.ResolutionReport

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelsBenchmarkScreen(
    installedModels: List<InstalledModelSpec>,
    registryModels: List<ModelDescriptor> = emptyList(),
    objectResolutionReport: ResolutionReport? = null,
    depthResolutionReport: ResolutionReport? = null,
    validationReport: ValidationLabReport? = null,
    isValidatingContracts: Boolean = false,
    gpuDelegateSupported: Boolean,
    activeDetector: DetectorType,
    preferredFramework: VisionFramework = VisionFramework.ONNX_RUNTIME,
    selectedBackend: HardwareBackend,
    isBenchmarkRunning: Boolean,
    reports: List<BenchmarkReportEntity>,
    onSelectBackend: (HardwareBackend) -> Unit,
    onSelectDetector: (DetectorType) -> Unit,
    onRunBenchmark: (DetectorType, HardwareBackend) -> Unit,
    onImportModelArtifact: (modelId: String, uri: Uri) -> Unit = { _, _ -> },
    onRemoveModelArtifact: (modelId: String) -> Unit = {},
    onRunValidationSuite: (VisionFramework) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var pendingImportModelId by remember { mutableStateOf<String?>(null) }

    val modelFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val modelId = pendingImportModelId
        pendingImportModelId = null
        if (uri != null && modelId != null) {
            onImportModelArtifact(modelId, uri)
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().widthIn(max = 920.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text("Registre Multi-Runtime & Banc IA", style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = "Appareil : ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT}) • GPU LiteRT : ${if (gpuDelegateSupported) "Compatible" else "CPU/NNAPI"}",
                    style = TelemetryMonoStyle,
                    color = TelemetryCyan
                )
            }

            // Résolution active du Runtime Manager
            if (objectResolutionReport != null || depthResolutionReport != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, TelemetryCyan.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Memory, contentDescription = null, tint = TelemetryCyan)
                                Spacer(Modifier.width(8.dp))
                                Text("Résolution dynamique des Runtimes ADVDS", style = MaterialTheme.typography.titleMedium)
                            }
                            objectResolutionReport?.let { rep ->
                                Text(
                                    text = "Détection Objets : ${rep.selectedFramework?.displayName ?: "Aucun"} (${rep.selectedProviderId ?: "—"}) • Backend ${rep.selectedBackend.labelFr}",
                                    style = TelemetryMonoStyle,
                                    color = if (rep.usedFallback) CautionAmber else NominalEmerald
                                )
                                rep.warningFr?.let { warn ->
                                    Text(warn, style = MaterialTheme.typography.bodySmall, color = CautionAmber)
                                }
                            }
                            depthResolutionReport?.let { rep ->
                                Text(
                                    text = "Profondeur Dense : ${rep.selectedFramework?.displayName ?: "Repli Géométrique/PnP"}",
                                    style = TelemetryMonoStyle,
                                    color = if (rep.selectedFramework != null) NominalEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                rep.warningFr?.let { warn ->
                                    Text(warn, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            // Manifestes JSON du ModelRegistry (ONNX / LiteRT / ML Kit / Géométrie)
            if (registryModels.isNotEmpty()) {
                item {
                    Text("Manifestes ADVDS (assets/advds/model_registry.json)", style = MaterialTheme.typography.titleMedium)
                }
                items(registryModels, key = { it.id }) { model ->
                    val isExternalArtifact = model.artifact.format.equals("ONNX", true) ||
                        model.artifact.format.equals("TFLITE", true)
                    val statusColor = if (model.isArtifactAvailable) NominalEmerald else CautionAmber

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(model.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                Text(
                                    text = if (model.isArtifactAvailable) "PRÊT v${model.version}" else "ARTEFACT REQUIS",
                                    style = TelemetryMonoStyle,
                                    color = statusColor
                                )
                            }
                            if (model.descriptionFr.isNotBlank()) {
                                Text(model.descriptionFr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                text = "${model.task.labelFr} • ${model.preferredFramework?.displayName ?: model.artifact.format} • ${model.input.width}×${model.input.height} ${model.input.tensorLayout}",
                                style = TelemetryMonoStyle,
                                color = TelemetryCyan
                            )
                            if (model.isArtifactAvailable && model.checksumSha256.isNotBlank() && isExternalArtifact) {
                                Text(
                                    text = "SHA256: ${model.checksumSha256} • ${model.artifact.sizeBytes / 1024} Ko",
                                    style = TelemetryMonoStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (isExternalArtifact) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FilledTonalButton(
                                        onClick = {
                                            pendingImportModelId = model.id
                                            modelFilePicker.launch(arrayOf("*/*"))
                                        },
                                        modifier = Modifier.heightIn(min = 48.dp).testTag("import_model_${model.id}")
                                    ) {
                                        Icon(Icons.Rounded.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            if (model.isArtifactAvailable) {
                                                "Remplacer .${model.artifact.format.lowercase()}"
                                            } else {
                                                "Importer .${model.artifact.format.lowercase()}"
                                            }
                                        )
                                    }
                                    if (model.isArtifactAvailable) {
                                        IconButton(
                                            onClick = { onRemoveModelArtifact(model.id) },
                                            modifier = Modifier.size(48.dp).testTag("remove_model_${model.id}")
                                        ) {
                                            Icon(
                                                Icons.Rounded.DeleteOutline,
                                                contentDescription = "Supprimer artefact ${model.name}",
                                                tint = EmergencyCrimson
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Laboratoire de Validation Contractuelle ADVDS
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, NominalEmerald.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Science, contentDescription = null, tint = NominalEmerald)
                            Spacer(Modifier.width(8.dp))
                            Text("Laboratoire de validation contractuelle ADVDS", style = MaterialTheme.typography.titleMedium)
                        }
                        Text(
                            text = "Vérifie l'inversion letterbox [0..1], la résistance aux trames noires, la préservation d'horodatage et le budget P50/P95 sur cet appareil.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = { onRunValidationSuite(preferredFramework) },
                            enabled = !isValidatingContracts,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("run_validation_lab_button")
                        ) {
                            if (isValidatingContracts) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Tests contractuels en cours...")
                            } else {
                                Icon(Icons.Rounded.Science, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Exécuter Suite Contrats (${preferredFramework.displayName})")
                            }
                        }
                        validationReport?.let { vRep ->
                            Text(
                                text = "Provider évalué : ${vRep.providerId} (${vRep.frameworkName}) • P50=${vRep.medianLatencyMs} ms • P95=${vRep.p95LatencyMs} ms",
                                style = TelemetryMonoStyle,
                                color = if (vRep.allPassed) NominalEmerald else CautionAmber
                            )
                            vRep.checks.forEach { chk ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (chk.passed) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                                        contentDescription = null,
                                        tint = if (chk.passed) NominalEmerald else EmergencyCrimson,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Column {
                                        Text(chk.name, style = MaterialTheme.typography.labelMedium)
                                        Text(chk.detailFr, style = TelemetryMonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Runtimes intégrés & Banc de mesure performances
            item {
                Text("Moteurs intégrés prêts hors ligne", style = MaterialTheme.typography.titleMedium)
            }

            items(installedModels, key = { it.id }) { model ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(model.name, style = MaterialTheme.typography.titleSmall)
                            Text("v${model.version}", style = TelemetryMonoStyle, color = NominalEmerald)
                        }
                        Text(model.targetTask, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = "${model.runtime} • GPU: ${if (model.gpuCompatible) "Prêt" else "CPU"}",
                            style = TelemetryMonoStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, TelemetryCyan.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Speed, contentDescription = null, tint = TelemetryCyan)
                            Spacer(Modifier.width(8.dp))
                            Text("Banc de mesure performances (P50 / P95)", style = MaterialTheme.typography.titleMedium)
                        }

                        Text("Accélération matérielle ciblée :", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HardwareBackend.entries.forEach { b ->
                                FilterChip(
                                    selected = selectedBackend == b,
                                    onClick = { onSelectBackend(b) },
                                    label = { Text(b.labelFr) },
                                    modifier = Modifier.testTag("backend_chip_${b.name}")
                                )
                            }
                        }

                        Text("Détecteur à mesurer :", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DetectorType.entries.filter { it != DetectorType.NONE }.forEach { det ->
                                FilterChip(
                                    selected = activeDetector == det,
                                    onClick = { onSelectDetector(det) },
                                    label = { Text(det.labelFr) },
                                    modifier = Modifier.testTag("bench_detector_${det.name}")
                                )
                            }
                        }

                        val targetDetector = if (activeDetector == DetectorType.NONE) DetectorType.FACE_LANDMARKS else activeDetector
                        Button(
                            onClick = { onRunBenchmark(targetDetector, selectedBackend) },
                            enabled = !isBenchmarkRunning,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("run_benchmark_button")
                        ) {
                            if (isBenchmarkRunning) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Mesure en cours (18 passes)...")
                            } else {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Lancer Benchmark (${targetDetector.labelFr})")
                            }
                        }
                    }
                }
            }

            if (reports.isNotEmpty()) {
                item {
                    Text("Rapports mesurés sur cet appareil", style = MaterialTheme.typography.titleMedium)
                }
                items(reports, key = { it.id }) { rep ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("${rep.modelName} [${rep.backend}]", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "${"%.1f".format(rep.inferenceFps)} FPS",
                                    style = TelemetryMonoStyle,
                                    color = NominalEmerald
                                )
                            }
                            Text(
                                text = "Médiane: ${rep.medianInferenceMs} ms • P95: ${rep.p95InferenceMs} ms • Moy: ${"%.1f".format(rep.avgInferenceMs)} ms",
                                style = TelemetryMonoStyle,
                                color = TelemetryCyan
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${rep.deviceModel} • RAM ${rep.memoryMb} Mo",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Therm: ${rep.thermalStatus}",
                                    style = TelemetryMonoStyle,
                                    color = CautionAmber
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
