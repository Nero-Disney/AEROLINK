package com.example.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.core.models.TelemetrySnapshot
import com.example.storage.db.DiagnosticLogEntity
import com.example.ui.theme.CautionAmber
import com.example.ui.theme.EmergencyCrimson
import com.example.ui.theme.NominalEmerald
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TelemetryMonoStyle
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(
    telemetry: TelemetrySnapshot,
    logs: List<DiagnosticLogEntity>,
    onExportReport: suspend () -> File,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var filterSubsystem by remember { mutableStateOf("ALL") }

    val filteredLogs = remember(logs, filterSubsystem) {
        if (filterSubsystem == "ALL") logs else logs.filter { it.subsystem == filterSubsystem }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().widthIn(max = 920.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Diagnostic & Journaux système", style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = "Inspection temps réel réseau, transport vidéo, pipeline IA et contrôleur de vol (format stats.json / log).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Résumé stats temps réel
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0A0F15))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("TÉLÉMÉTRIE INSTANTANÉE (/stats.json)", style = MaterialTheme.typography.labelMedium, color = TelemetryCyan)
                        Text("SSID=${telemetry.ssid} | RSSI=${telemetry.rssiDbm} dBm | Débit=${telemetry.rxKbps} kbps", style = TelemetryMonoStyle, color = Color(0xFFE2E8F0))
                        Text("FPS=${"%.1f".format(telemetry.videoFps)} | Lat Méd=${telemetry.medianLatencyMs}ms | P95=${telemetry.p95LatencyMs}ms", style = TelemetryMonoStyle, color = NominalEmerald)
                        Text("Trames=${telemetry.totalFrames} | Pertes=${telemetry.droppedFrames} | ACKs=${telemetry.ackedPackets}", style = TelemetryMonoStyle, color = Color(0xFF94A3B8))
                        Text("IA=${"%.1f".format(telemetry.inferenceFps)} inf/s (${telemetry.inferenceLatencyMs}ms) | RAM=${telemetry.memoryMb} Mo | Therm=${telemetry.thermalStatusLabel}", style = TelemetryMonoStyle, color = CautionAmber)
                    }
                }
            }

            // Actions Export & Filtres
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                val file = onExportReport()
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(share, "Exporter rapport diagnostic JSON"))
                            }
                        },
                        modifier = Modifier.weight(1f).testTag("export_diagnostics_button")
                    ) {
                        Icon(Icons.Rounded.IosShare, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Exporter JSON")
                    }

                    OutlinedButton(
                        onClick = onClearLogs,
                        modifier = Modifier.testTag("clear_diagnostics_button")
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Vider")
                    }
                }
            }

            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ALL" to "Tous", "NETWORK" to "Réseau", "VIDEO" to "Vidéo", "AI" to "IA", "FLIGHT" to "Vol").forEach { (key, label) ->
                        FilterChip(
                            selected = filterSubsystem == key,
                            onClick = { filterSubsystem = key },
                            label = { Text(label) },
                            modifier = Modifier.testTag("diag_filter_$key")
                        )
                    }
                }
            }

            items(filteredLogs, key = { it.id }) { entry ->
                val sevColor = when (entry.severity) {
                    "ERROR" -> EmergencyCrimson
                    "WARN" -> CautionAmber
                    else -> TelemetryCyan
                }
                val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(entry.timestampMs))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("[${entry.subsystem}] ${entry.severity}", style = TelemetryMonoStyle, color = sevColor)
                            Text(ts, style = TelemetryMonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(entry.message, style = MaterialTheme.typography.bodySmall)
                        if (entry.metadataJson != "{}") {
                            Text(entry.metadataJson, style = TelemetryMonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
