package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.connectivity.wifi.WifiConnectionStatus
import com.example.core.models.ConfidenceLevel
import com.example.core.models.DetectionResult
import com.example.core.models.DroneFamily
import com.example.core.models.NetworkContext
import com.example.core.models.ScannedWifiNetwork
import com.example.ui.theme.CautionAmber
import com.example.ui.theme.EmergencyCrimson
import com.example.ui.theme.LocalAeroTokens
import com.example.ui.theme.NominalEmerald
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TelemetryMonoStyle

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectionWizardScreen(
    wifiStatus: WifiConnectionStatus,
    reconnectAttempt: Int,
    hasWifiPermission: Boolean,
    scannedNetworks: List<ScannedWifiNetwork>,
    activeNetworkContext: NetworkContext?,
    detectionResult: DetectionResult?,
    isProbing: Boolean,
    singleClientConflictWarning: Boolean,
    videoOnlySafetyLock: Boolean,
    onScanWifi: () -> Unit,
    onConnectWifi: (ssid: String, passphrase: String?) -> Unit,
    onCancelReconnect: () -> Unit,
    onInspectActiveWifi: () -> Unit,
    onRunDiscovery: () -> Unit,
    onOverrideFamily: (DroneFamily) -> Unit,
    onLaunchStreamAndOpenCockpit: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalAeroTokens.current
    var manualSsid by remember { mutableStateOf("FLOW-UFO") }
    var passphrase by remember { mutableStateOf("") }
    var noInternetAcknowledged by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.any { it }) {
            onScanWifi()
        }
    }

    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 920.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    text = "Assistant de connexion & Identification",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = "Détecte le réseau du drone, maintient la liaison sans Internet et identifie le protocole en 3 étapes sans envoyer d'ordre de vol.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Bandeau Perte de connexion / Reconnexion limitée + accès direct journaux
            if (wifiStatus == WifiConnectionStatus.LOST || wifiStatus == WifiConnectionStatus.UNAVAILABLE) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = EmergencyCrimson.copy(alpha = 0.14f)),
                        shape = RoundedCornerShape(tokens.cornerStyle.mediumDp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, EmergencyCrimson, RoundedCornerShape(tokens.cornerStyle.mediumDp))
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.WifiOff, contentDescription = null, tint = EmergencyCrimson)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if (wifiStatus == WifiConnectionStatus.LOST) {
                                        "Connexion perdue — reconnexion (${reconnectAttempt.coerceAtLeast(1)}/3)"
                                    } else {
                                        "Connexion perdue ou refusée"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = EmergencyCrimson
                                )
                            }
                            Text(
                                text = "Vérifiez que le drone est allumé et à portée. Les tentatives automatiques sont limitées pour préserver la batterie.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = onCancelReconnect,
                                    modifier = Modifier.testTag("wizard_cancel_reconnect")
                                ) {
                                    Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Annuler")
                                }
                                FilledTonalButton(
                                    onClick = onOpenDiagnostics,
                                    modifier = Modifier.testTag("wizard_open_logs")
                                ) {
                                    Icon(Icons.Rounded.BugReport, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Journaux réseau")
                                }
                            }
                        }
                    }
                }
            }

            // Alerte Caméra occupée
            if (singleClientConflictWarning || detectionResult?.cameraBusy == true) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CautionAmber.copy(alpha = 0.14f)),
                        shape = RoundedCornerShape(tokens.cornerStyle.mediumDp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, CautionAmber, RoundedCornerShape(tokens.cornerStyle.mediumDp))
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.VideocamOff, contentDescription = null, tint = CautionAmber)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "La caméra est occupée. Fermez l'application du drone.",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = CautionAmber
                                )
                            }
                            Button(
                                onClick = onRunDiscovery,
                                modifier = Modifier.testTag("wizard_camera_busy_retry")
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Réessayer")
                            }
                        }
                    }
                }
            }

            // Étape 1 : Liaison Wi-Fi Drone & message "sans Internet"
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = tokens.levelCarte),
                    shape = RoundedCornerShape(tokens.cornerStyle.mediumDp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, tokens.borderStrong, RoundedCornerShape(tokens.cornerStyle.mediumDp))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("1. Liaison Wi-Fi Drone", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = wifiStatus.labelFr,
                                style = TelemetryMonoStyle,
                                color = if (wifiStatus == WifiConnectionStatus.CONNECTED) NominalEmerald else MaterialTheme.colorScheme.primary
                            )
                        }

                        // Rappel rassurant Android "sans Internet"
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                            ),
                            shape = RoundedCornerShape(tokens.cornerStyle.smallDp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        Icons.Rounded.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "Ce réseau n'a pas d'accès Internet, c'est normal pour un drone.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        noInternetAcknowledged = true
                                        onInspectActiveWifi()
                                    },
                                    modifier = Modifier.testTag("wizard_stay_connected_button")
                                ) {
                                    Text(if (noInternetAcknowledged) "Maintenu ✓" else "Rester connecté")
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    if (hasWifiPermission) onScanWifi() else permissionLauncher.launch(requiredPermissions)
                                },
                                modifier = Modifier.testTag("wifi_scan_button")
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (hasWifiPermission) "Scanner Wi-Fi" else "Autoriser & Scanner")
                            }
                            FilledTonalButton(
                                onClick = onInspectActiveWifi,
                                modifier = Modifier.testTag("wifi_inspect_active_button")
                            ) {
                                Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Lire Wi-Fi actif")
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = manualSsid,
                                onValueChange = { manualSsid = it },
                                label = { Text("SSID exact (ex: FLOW-UFO-xxxxxx)") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("manual_ssid_input")
                            )
                            OutlinedButton(
                                onClick = { onConnectWifi(manualSsid.trim(), passphrase.ifBlank { null }) },
                                enabled = manualSsid.isNotBlank(),
                                modifier = Modifier.testTag("manual_connect_button")
                            ) {
                                Text("Connecter")
                            }
                        }
                    }
                }
            }

            // Liste des réseaux détectés
            if (scannedNetworks.isNotEmpty()) {
                item {
                    Text(
                        text = "Réseaux Wi-Fi à proximité (${scannedNetworks.size})",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                items(scannedNetworks.take(8), key = { it.ssid + it.bssid }) { net ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = tokens.levelCarte),
                        shape = RoundedCornerShape(tokens.cornerStyle.mediumDp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("scanned_wifi_${net.ssid}")
                            .border(
                                1.dp,
                                if (net.matchedFamily != null) TelemetryCyan.copy(alpha = 0.7f) else tokens.borderSubtle,
                                RoundedCornerShape(tokens.cornerStyle.mediumDp)
                            )
                            .clickable { onConnectWifi(net.ssid, null) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Rounded.Wifi,
                                    contentDescription = null,
                                    tint = if (net.matchedFamily != null) TelemetryCyan else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(net.ssid, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = net.matchedFamily?.let { "Famille probable : ${it.displayName}" } ?: "Réseau générique",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (net.matchedFamily != null) TelemetryCyan else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(
                                text = "${net.rssiDbm} dBm",
                                style = TelemetryMonoStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Étape 2 : Sonde protocolaire non destructive
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = tokens.levelCarte),
                    shape = RoundedCornerShape(tokens.cornerStyle.mediumDp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, tokens.borderStrong, RoundedCornerShape(tokens.cornerStyle.mediumDp))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("2. Sonde protocolaire non destructive", style = MaterialTheme.typography.titleMedium)

                        if (activeNetworkContext != null) {
                            Text(
                                text = "SSID: ${activeNetworkContext.ssid} • Tél: ${activeNetworkContext.phoneIp} • Passerelle: ${activeNetworkContext.gatewayIp}",
                                style = TelemetryMonoStyle,
                                color = NominalEmerald
                            )
                        } else {
                            Text(
                                text = "Connectez-vous d'abord au Wi-Fi du drone (ou sondez 192.168.169.1 si déjà connecté via Android).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Button(
                            onClick = onRunDiscovery,
                            enabled = !isProbing,
                            modifier = Modifier.testTag("run_discovery_button")
                        ) {
                            if (isProbing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(if (isProbing) "Analyse des ports en cours..." else "Lancer l'identification réseau & vidéo")
                        }
                    }
                }
            }

            // Étape 3 : Fiche DetectionResult + Badges textuels de confiance + Ambiguïté + Choix manuel
            if (detectionResult != null) {
                item {
                    val isAmbiguous = detectionResult.confidence != ConfidenceLevel.HIGH ||
                        detectionResult.candidateFamilies.size > 1
                    val confColor = when (detectionResult.confidence) {
                        ConfidenceLevel.HIGH -> NominalEmerald
                        ConfidenceLevel.MEDIUM -> TelemetryCyan
                        ConfidenceLevel.UNCERTAIN -> CautionAmber
                    }
                    val confBadgeLabel = when (detectionResult.confidence) {
                        ConfidenceLevel.HIGH -> "Confiance élevée"
                        ConfidenceLevel.MEDIUM -> "Confiance moyenne"
                        ConfidenceLevel.UNCERTAIN -> "Confiance incertaine"
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = tokens.levelCarte),
                        shape = RoundedCornerShape(tokens.cornerStyle.largeDp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, confColor.copy(alpha = 0.72f), RoundedCornerShape(tokens.cornerStyle.largeDp))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (isAmbiguous) Icons.Rounded.Warning else Icons.Rounded.CheckCircle,
                                        contentDescription = confBadgeLabel,
                                        tint = confColor
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = if (isAmbiguous && detectionResult.candidateFamilies.size > 1) {
                                            "Plusieurs familles possibles"
                                        } else {
                                            "Drone détecté"
                                        },
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                                // Badge explicite avec mot + couleur (jamais couleur seule)
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(confColor.copy(alpha = 0.16f))
                                        .border(1.dp, confColor, RoundedCornerShape(50))
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = confBadgeLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = confColor
                                    )
                                }
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(tokens.cornerStyle.smallDp))
                                    .background(tokens.levelFond)
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                DetectionRow("Wi-Fi", detectionResult.ssid)
                                DetectionRow("Téléphone", detectionResult.phoneIp)
                                DetectionRow("Passerelle", detectionResult.gatewayIp)
                                DetectionRow("Famille", detectionResult.family.displayName)
                                DetectionRow("Vidéo", detectionResult.videoTransport.labelFr)
                                DetectionRow(
                                    "Commandes",
                                    if (detectionResult.commandsVerified) "Vérifiées" else "À vérifier séparément",
                                    if (detectionResult.commandsVerified) NominalEmerald else CautionAmber
                                )
                                DetectionRow("Confiance", confBadgeLabel, confColor)
                            }

                            // Bandeau sécurité explicite selon l'état de validation
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(tokens.cornerStyle.smallDp))
                                    .background(CautionAmber.copy(alpha = 0.12f))
                                    .border(1.dp, CautionAmber.copy(alpha = 0.45f), RoundedCornerShape(tokens.cornerStyle.smallDp))
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Lock, contentDescription = null, tint = CautionAmber, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = when {
                                        !detectionResult.commandsVerified && videoOnlySafetyLock ->
                                            "Drone non validé : pilotage verrouillé • Vidéo OK, commandes à vérifier"
                                        !detectionResult.commandsVerified ->
                                            "Vidéo OK, commandes à vérifier dans Paramètres > Sécurité."
                                        else ->
                                            "Protocole vérifié. Restez vigilant au premier décollage."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Text(
                                text = detectionResult.probeSummary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Text(
                                text = if (isAmbiguous) {
                                    "Choisissez manuellement la famille correspondante :"
                                } else {
                                    "Sélection / correction manuelle de la famille :"
                                },
                                style = MaterialTheme.typography.labelLarge
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                DroneFamily.entries.filter { it != DroneFamily.UNKNOWN }.forEach { fam ->
                                    FilterChip(
                                        selected = detectionResult.family == fam,
                                        onClick = { onOverrideFamily(fam) },
                                        label = { Text("${fam.priority} ${fam.displayName}") },
                                        modifier = Modifier.testTag("family_chip_${fam.id}")
                                    )
                                }
                            }

                            Spacer(Modifier.height(4.dp))
                            Button(
                                onClick = onLaunchStreamAndOpenCockpit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("wizard_open_cockpit_button"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Afficher la vidéo (${detectionResult.family.displayName})")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetectionRow(label: String, value: String, valueColor: Color = Color(0xFFD9E0E8)) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = label.padEnd(11),
            style = TelemetryMonoStyle,
            color = Color(0xFF8B98A8)
        )
        Text(
            text = value,
            style = TelemetryMonoStyle,
            color = valueColor
        )
    }
}
