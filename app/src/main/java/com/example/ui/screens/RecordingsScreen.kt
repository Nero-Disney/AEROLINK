package com.example.ui.screens

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.rememberAsyncImagePainter
import com.example.storage.db.RecordingEntity
import com.example.ui.theme.EmergencyCrimson
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TelemetryMonoStyle
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecordingsScreen(
    recordings: List<RecordingEntity>,
    onDeleteRecording: (RecordingEntity) -> Unit,
    onPurgeAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showPurgeConfirm by remember { mutableStateOf(false) }

    if (showPurgeConfirm) {
        AlertDialog(
            onDismissRequest = { showPurgeConfirm = false },
            title = { Text("Supprimer toutes les données locales ?") },
            text = {
                Text("Cette action efface définitivement toutes les captures photo, vidéos MP4, sessions et journaux locaux (confidentialité hors ligne).")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPurgeConfirm = false
                        onPurgeAll()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmergencyCrimson),
                    modifier = Modifier.testTag("confirm_purge_all")
                ) {
                    Text("Tout supprimer")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPurgeConfirm = false }) { Text("Annuler") }
            }
        )
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().widthIn(max = 920.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enregistrements & Captures", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            text = "Stockage 100 % local sur l'appareil, export manuel uniquement.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = { showPurgeConfirm = true },
                        modifier = Modifier.testTag("recordings_purge_all_button")
                    ) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = null, tint = EmergencyCrimson)
                        Spacer(Modifier.width(4.dp))
                        Text("Tout effacer", color = EmergencyCrimson)
                    }
                }
            }

            if (recordings.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PhotoCamera,
                                contentDescription = null,
                                tint = TelemetryCyan,
                                modifier = Modifier.size(36.dp)
                            )
                            Text("Aucune capture enregistrée", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "Utilisez les boutons Photo ou Enreg. MP4 depuis le Cockpit FPV.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(recordings, key = { it.id }) { rec ->
                    val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE).format(Date(rec.createdAtMs))
                    val file = File(rec.filePath)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (rec.mediaType == "PHOTO" && file.exists()) {
                                Image(
                                    painter = rememberAsyncImagePainter(file),
                                    contentDescription = rec.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (rec.mediaType == "VIDEO") Icons.Rounded.Movie else Icons.Rounded.PhotoCamera,
                                        contentDescription = rec.mediaType,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(rec.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "${rec.mediaType} • ${rec.width}x${rec.height} • ${rec.sizeBytes / 1024} Ko",
                                    style = TelemetryMonoStyle,
                                    color = TelemetryCyan
                                )
                                Text(
                                    text = "$dateStr • ${rec.ssid}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = {
                                    if (file.exists()) {
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            file
                                        )
                                        val mime = if (rec.mediaType == "VIDEO") "video/mp4" else "image/jpeg"
                                        val share = Intent(Intent.ACTION_SEND).apply {
                                            type = mime
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(share, "Exporter ${rec.title}"))
                                    }
                                },
                                modifier = Modifier.testTag("share_recording_${rec.id}")
                            ) {
                                Icon(Icons.Rounded.Share, contentDescription = "Exporter")
                            }
                            IconButton(
                                onClick = { onDeleteRecording(rec) },
                                modifier = Modifier.testTag("delete_recording_${rec.id}")
                            ) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Supprimer", tint = EmergencyCrimson)
                            }
                        }
                    }
                }
            }
        }
    }
}
