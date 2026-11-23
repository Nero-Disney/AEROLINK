package com.example.ui.screens

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FlightTakeoff
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiFind
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.models.DroneProfile
import com.example.core.models.FlightState
import com.example.core.models.TelemetrySnapshot
import com.example.storage.db.FlightSessionEntity
import com.example.ui.components.FlightStatePill
import com.example.ui.components.TelemetryBadge
import com.example.ui.theme.CautionAmber
import com.example.ui.theme.NominalEmerald
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TelemetryMonoStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    telemetry: TelemetrySnapshot,
    flightState: FlightState,
    profiles: List<DroneProfile>,
    sessions: List<FlightSessionEntity>,
    onOpenConnectionWizard: () -> Unit,
    onOpenCockpit: () -> Unit,
    onOpenVisionCenter: () -> Unit,
    onSelectProfile: (DroneProfile) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 920.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Hero Cockpit Card
            item {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0E141C)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(22.dp))
                ) {
                    Box(modifier = Modifier.fillMaxWidth().height(236.dp)) {
                        Image(
                            painter = painterResource(id = R.drawable.img_cockpit_hero_1795275143102),
                            contentDescription = "Illustration drone AeroLink",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(
                                            Color(0xFF0B0F14).copy(alpha = 0.96f),
                                            Color(0xFF0B0F14).copy(alpha = 0.80f),
                                            Color(0xFF0B0F14).copy(alpha = 0.25f)
                                        )
                                    )
                                )
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(18.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "AEROLINK FPV & VISION",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = TelemetryCyan
                                    )
                                    Text(
                                        text = "Poste de pilotage universel",
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = Color.White
                                    )
                                }
                                FlightStatePill(state = flightState)
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                TelemetryBadge(
                                    label = "WI-FI",
                                    value = telemetry.ssid,
                                    icon = Icons.Rounded.Wifi
                                )
                                TelemetryBadge(
                                    label = "APPAREIL",
                                    value = Build.MODEL,
                                    icon = Icons.Rounded.Memory
                                )
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Button(
                                    onClick = onOpenConnectionWizard,
                                    modifier = Modifier.testTag("home_connect_button"),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = TelemetryCyan,
                                        contentColor = Color(0xFF081018)
                                    )
                                ) {
                                    Icon(Icons.Rounded.WifiFind, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Connecter un drone")
                                }
                                FilledTonalButton(
                                    onClick = onOpenCockpit,
                                    modifier = Modifier.testTag("home_cockpit_button")
                                ) {
                                    Icon(Icons.Rounded.FlightTakeoff, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Cockpit")
                                }
                                OutlinedButton(
                                    onClick = onOpenVisionCenter,
                                    modifier = Modifier.testTag("home_vision_button")
                                ) {
                                    Icon(Icons.Rounded.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("IA")
                                }
                            }
                        }
                    }
                }
            }

            // Bandeau architecture modulaire (3 sous-systèmes)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SubsystemCard(
                        title = "Moteur Vidéo",
                        subtitle = "UDP MJPEG • RTSP • TCP",
                        icon = Icons.Rounded.Radar,
                        accent = TelemetryCyan,
                        modifier = Modifier.weight(1f)
                    )
                    SubsystemCard(
                        title = "Perception ADVDS",
                        subtitle = "ONNX • LiteRT • ML Kit",
                        icon = Icons.Rounded.Visibility,
                        accent = NominalEmerald,
                        modifier = Modifier.weight(1f)
                    )
                    SubsystemCard(
                        title = "Fusion & Sécurité",
                        subtitle = "4 voies • Risque • Verrou",
                        icon = Icons.Rounded.VerifiedUser,
                        accent = CautionAmber,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Profils de drones enregistrés
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Profils & familles supportées",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "${profiles.size} profils",
                        style = TelemetryMonoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(profiles, key = { it.id }) { profile ->
                DroneProfileCard(
                    profile = profile,
                    onClick = {
                        onSelectProfile(profile)
                        onOpenCockpit()
                    }
                )
            }

            // Historique des sessions récentes
            item {
                Text(
                    text = "Sessions récentes",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (sessions.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Hub,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "Aucune session enregistrée. Connectez votre FLOW-UFO ou testez le pipeline dans le Centre IA.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(sessions.take(5), key = { it.id }) { s ->
                    val dateStr = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(Date(s.startedAtMs))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(text = "${s.ssid} (${s.familyId})", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "$dateStr • ${s.durationSec}s • ${"%.1f".format(s.avgVideoFps)} FPS",
                                    style = TelemetryMonoStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "Med ${s.medianLatencyMs}ms / P95 ${s.p95LatencyMs}ms",
                                style = TelemetryMonoStyle,
                                color = TelemetryCyan
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubsystemCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Icon(icon, contentDescription = title, tint = accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(6.dp))
            Text(text = title, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun DroneProfileCard(
    profile: DroneProfile,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("profile_card_${profile.id}")
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = profile.family.priority,
                            style = TelemetryMonoStyle,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = profile.commercialName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${profile.gatewayIp}:${profile.videoPort} • ${profile.videoTransport.labelFr}",
                    style = TelemetryMonoStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                val statusText = if (profile.isProtocolVerified) {
                    "Protocole commandes vérifié"
                } else {
                    "Vidéo prête • Commandes à vérifier"
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (profile.isProtocolVerified) NominalEmerald else CautionAmber
                )
            }
            FilledTonalButton(
                onClick = onClick,
                modifier = Modifier.testTag("launch_profile_${profile.id}")
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Ouvrir ${profile.commercialName}")
                Spacer(Modifier.width(4.dp))
                Text("FPV")
            }
        }
    }
}
