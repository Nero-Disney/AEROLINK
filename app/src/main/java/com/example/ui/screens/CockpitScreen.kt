package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.FlightLand
import androidx.compose.material.icons.rounded.FlightTakeoff
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.models.DetectionZone
import com.example.core.models.DetectorType
import com.example.core.models.DroneProfile
import com.example.core.models.FlightState
import com.example.core.models.NormalizedBox
import com.example.core.models.OpticalFlowLevel
import com.example.core.models.StickInput
import com.example.core.models.TelemetrySnapshot
import com.example.core.models.VideoFrame
import com.example.core.models.VisionAnalysisResult
import com.example.storage.prefs.AppPreferences
import com.example.ui.components.FlightStatePill
import com.example.ui.components.FpvVideoStage
import com.example.ui.components.TargetSelectionStrip
import com.example.ui.components.TelemetryBadge
import com.example.ui.components.VirtualThumbstick
import com.example.ui.theme.CautionAmber
import com.example.ui.theme.EmergencyCrimson
import com.example.ui.theme.LocalAeroTokens
import com.example.ui.theme.NominalEmerald
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TelemetryMonoStyle
import kotlinx.coroutines.delay

@Composable
fun CockpitScreen(
    activeProfile: DroneProfile?,
    telemetry: TelemetrySnapshot,
    flightState: FlightState,
    safetyMessage: String?,
    stickInput: StickInput,
    displayFrame: VideoFrame?,
    visionResult: VisionAnalysisResult,
    detectionZone: DetectionZone,
    prefs: AppPreferences,
    isRecordingVideo: Boolean,
    canArm: Boolean,
    onCaptureSnapshot: () -> Unit,
    onToggleRecording: () -> Unit,
    onSwitchCamera: () -> Unit,
    onCycleAiDetector: () -> Unit,
    onDesignateTrack: (String) -> Unit,
    onLockTrack: (String) -> Unit,
    onClearTargetSelection: () -> Unit,
    onManualBoxDrawn: (NormalizedBox) -> Unit,
    onSelectNearestTarget: () -> Unit,
    onSelectCenterTarget: () -> Unit,
    onResolveAmbiguousTarget: (String) -> Unit,
    onChangeDesiredDistance: (Float) -> Unit,
    onArmAndTakeOff: () -> Unit,
    onCancelArming: () -> Unit,
    onNormalLand: () -> Unit,
    onEmergencyStop: () -> Unit,
    onResetEmergency: () -> Unit,
    onCalibrateGyro: () -> Unit,
    onCycleSpeed: () -> Unit,
    onToggleHeadless: () -> Unit,
    onTriggerFlip: () -> Unit,
    onLeftStickMove: (yaw: Float, throttle: Float) -> Unit,
    onRightStickMove: (roll: Float, pitch: Float) -> Unit,
    onStopStream: () -> Unit,
    onReconnectDrone: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalAeroTokens.current
    var isFullscreen by remember { mutableStateOf(false) }
    var showArmConfirmDialog by remember { mutableStateOf(false) }
    var manualDrawMode by remember { mutableStateOf(false) }
    var hudShiftDp by remember { mutableStateOf(Offset.Zero) }

    // Décalage très lent anti-brûlure Super AMOLED (1 à 2 dp toutes les 30s)
    LaunchedEffect(tokens.isOledMode, prefs.hudAntiBurnInShift) {
        if (tokens.isOledMode && prefs.hudAntiBurnInShift) {
            val offsets = listOf(Offset(0f, 0f), Offset(2f, 1f), Offset(1f, -2f), Offset(-2f, 1f))
            var idx = 0
            while (true) {
                hudShiftDp = offsets[idx % offsets.size]
                idx++
                delay(30_000L)
            }
        } else {
            hudShiftDp = Offset.Zero
        }
    }

    val caps = activeProfile?.capabilities
    val sticksEnabled = flightState == FlightState.Flying || flightState == FlightState.Arming

    if (showArmConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showArmConfirmDialog = false },
            title = { Text("Confirmation d'armement") },
            text = {
                Text(
                    "Vous allez armer et déclencher le décollage sur ${activeProfile?.commercialName ?: "le drone"}. " +
                        "Assurez-vous que la zone est dégagée et que le protocole de commande est validé."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showArmConfirmDialog = false
                        onArmAndTakeOff()
                    },
                    modifier = Modifier.testTag("confirm_arm_button")
                ) {
                    Text("Confirmer Décollage")
                }
            },
            dismissButton = {
                TextButton(onClick = { showArmConfirmDialog = false }) {
                    Text("Annuler")
                }
            }
        )
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isLandscape = maxWidth > maxHeight

        if (isLandscape) {
            // COCKPIT PAYSAGE PLEIN CADRE : vidéo plein écran + barre d'état/urgence EN HAUT + sticks aux coins inférieurs
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (tokens.isOledMode) Color.Black else tokens.levelFond)
            ) {
                FpvVideoStage(
                    frame = displayFrame,
                    visionResult = visionResult,
                    detectionZone = detectionZone,
                    showTrajectories = prefs.showTrajectories,
                    showLandmarks = prefs.showLandmarks,
                    showCenterReticle = prefs.showCenterReticle,
                    opticalFlowLevel = prefs.opticalFlowLevel,
                    onDesignateTrack = onDesignateTrack,
                    onLockTrack = onLockTrack,
                    onClearSelection = onClearTargetSelection,
                    manualDrawMode = manualDrawMode,
                    onManualBoxDrawn = { box ->
                        manualDrawMode = false
                        onManualBoxDrawn(box)
                    },
                    hudShiftDp = hudShiftDp,
                    showHorizonOverlay = prefs.showHorizonOverlay,
                    showRiskOverlay = prefs.showRiskOverlay,
                    modifier = Modifier.fillMaxSize()
                )

                // Barre supérieure : État + Télémétrie + ARRÊT D'URGENCE isolé en haut à droite
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CockpitTopBar(
                        flightState = flightState,
                        telemetry = telemetry,
                        isFullscreen = isFullscreen,
                        onToggleFullscreen = { isFullscreen = !isFullscreen },
                        onEmergencyStop = onEmergencyStop,
                        onOpenSettings = onOpenSettings
                    )
                    FlightAlertsBanner(
                        flightState = flightState,
                        safetyMessage = safetyMessage,
                        singleClientConflict = telemetry.singleClientConflictWarning,
                        onCancelArming = onCancelArming,
                        onResetEmergency = onResetEmergency,
                        onReconnectDrone = onReconnectDrone,
                        onOpenDiagnostics = onOpenDiagnostics
                    )
                }

                // Gauche/Droite : Sticks virtuels dans les coins inférieurs (respecte Gauchers)
                if (!isFullscreen) {
                    CockpitDualSticksOverlay(
                        leftHanded = prefs.leftHandedSticks,
                        sticksEnabled = sticksEnabled,
                        hapticsEnabled = prefs.hapticsEnabled,
                        onLeftStickMove = onLeftStickMove,
                        onRightStickMove = onRightStickMove,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )

                    // Bande centrale inférieure : cibles + actions rapides (hors zone d'urgence)
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(0.46f)
                            .padding(bottom = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (prefs.activeDetector != DetectorType.NONE || visionResult.trackedSubjects.isNotEmpty()) {
                            TargetSelectionStrip(
                                subjects = visionResult.trackedSubjects,
                                ambiguousCandidates = visionResult.ambiguousRecoveryCandidates,
                                manualDrawMode = manualDrawMode,
                                prefs = prefs,
                                onDesignateTrack = onDesignateTrack,
                                onLockTrack = onLockTrack,
                                onClearSelection = onClearTargetSelection,
                                onSelectNearest = onSelectNearestTarget,
                                onSelectCenter = onSelectCenterTarget,
                                onToggleManualDraw = { manualDrawMode = !manualDrawMode },
                                onResolveAmbiguous = onResolveAmbiguousTarget,
                                onChangeDesiredDistance = onChangeDesiredDistance,
                                compact = true
                            )
                        }
                        CockpitFlightAndMediaRow(
                            canArm = canArm,
                            flightState = flightState,
                            isConnected = telemetry.isConnected,
                            isRecordingVideo = isRecordingVideo,
                            activeDetector = prefs.activeDetector,
                            caps = caps,
                            telemetry = telemetry,
                            onCaptureSnapshot = onCaptureSnapshot,
                            onToggleRecording = onToggleRecording,
                            onCycleAiDetector = onCycleAiDetector,
                            onSwitchCamera = onSwitchCamera,
                            onCycleSpeed = onCycleSpeed,
                            onCalibrateGyro = onCalibrateGyro,
                            onToggleHeadless = onToggleHeadless,
                            onTriggerFlip = onTriggerFlip,
                            onStopStream = onStopStream,
                            onTakeOffClick = {
                                if (prefs.requireArmingConfirmation) showArmConfirmDialog = true else onArmAndTakeOff()
                            },
                            onNormalLand = onNormalLand
                        )
                    }
                }
            }
        } else {
            // COCKPIT PORTRAIT : barre état + urgence en haut, vidéo pleine largeur, contrôles puis sticks côte à côte
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (tokens.isOledMode) Color.Black else tokens.levelFond)
                    .verticalScroll(rememberScrollState())
                    .padding(if (isFullscreen) 8.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CockpitTopBar(
                    flightState = flightState,
                    telemetry = telemetry,
                    isFullscreen = isFullscreen,
                    onToggleFullscreen = { isFullscreen = !isFullscreen },
                    onEmergencyStop = onEmergencyStop,
                    onOpenSettings = onOpenSettings
                )

                FlightAlertsBanner(
                    flightState = flightState,
                    safetyMessage = safetyMessage,
                    singleClientConflict = telemetry.singleClientConflictWarning,
                    onCancelArming = onCancelArming,
                    onResetEmergency = onResetEmergency,
                    onReconnectDrone = onReconnectDrone,
                    onOpenDiagnostics = onOpenDiagnostics
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(if (isFullscreen) 16f / 9f else 16f / 10f)
                ) {
                    FpvVideoStage(
                        frame = displayFrame,
                        visionResult = visionResult,
                        detectionZone = detectionZone,
                        showTrajectories = prefs.showTrajectories,
                        showLandmarks = prefs.showLandmarks,
                        showCenterReticle = prefs.showCenterReticle,
                        opticalFlowLevel = prefs.opticalFlowLevel,
                        onDesignateTrack = onDesignateTrack,
                        onLockTrack = onLockTrack,
                        onClearSelection = onClearTargetSelection,
                        manualDrawMode = manualDrawMode,
                        onManualBoxDrawn = { box ->
                            manualDrawMode = false
                            onManualBoxDrawn(box)
                        },
                        hudShiftDp = hudShiftDp,
                        showHorizonOverlay = prefs.showHorizonOverlay,
                        showRiskOverlay = prefs.showRiskOverlay,
                        modifier = Modifier.fillMaxSize()
                    )

                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(10.dp)
                            .background(tokens.levelFond.copy(alpha = tokens.glassAlpha), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(telemetry.sourceLabel, style = TelemetryMonoStyle, color = TelemetryCyan)
                        if (prefs.activeDetector != DetectorType.NONE) {
                            Text(
                                text = "• ${prefs.activeDetector.labelFr} (${visionResult.trackedSubjects.size})",
                                style = TelemetryMonoStyle,
                                color = NominalEmerald
                            )
                        }
                        if (prefs.opticalFlowLevel != OpticalFlowLevel.OFF && visionResult.opticalFlow.featureCount > 0) {
                            Text("• ${visionResult.opticalFlow.advisoryLabel}", style = TelemetryMonoStyle, color = CautionAmber)
                        }
                    }
                }

                TargetSelectionStrip(
                    subjects = visionResult.trackedSubjects,
                    ambiguousCandidates = visionResult.ambiguousRecoveryCandidates,
                    manualDrawMode = manualDrawMode,
                    prefs = prefs,
                    onDesignateTrack = onDesignateTrack,
                    onLockTrack = onLockTrack,
                    onClearSelection = onClearTargetSelection,
                    onSelectNearest = onSelectNearestTarget,
                    onSelectCenter = onSelectCenterTarget,
                    onToggleManualDraw = { manualDrawMode = !manualDrawMode },
                    onResolveAmbiguous = onResolveAmbiguousTarget,
                    onChangeDesiredDistance = onChangeDesiredDistance,
                    compact = false
                )

                CockpitFlightAndMediaRow(
                    canArm = canArm,
                    flightState = flightState,
                    isConnected = telemetry.isConnected,
                    isRecordingVideo = isRecordingVideo,
                    activeDetector = prefs.activeDetector,
                    caps = caps,
                    telemetry = telemetry,
                    onCaptureSnapshot = onCaptureSnapshot,
                    onToggleRecording = onToggleRecording,
                    onCycleAiDetector = onCycleAiDetector,
                    onSwitchCamera = onSwitchCamera,
                    onCycleSpeed = onCycleSpeed,
                    onCalibrateGyro = onCalibrateGyro,
                    onToggleHeadless = onToggleHeadless,
                    onTriggerFlip = onTriggerFlip,
                    onStopStream = onStopStream,
                    onTakeOffClick = {
                        if (prefs.requireArmingConfirmation) showArmConfirmDialog = true else onArmAndTakeOff()
                    },
                    onNormalLand = onNormalLand
                )

                // Joysticks côte à côte en bas (respecte l'option Gauchers)
                CockpitDualSticksOverlay(
                    leftHanded = prefs.leftHandedSticks,
                    sticksEnabled = sticksEnabled,
                    hapticsEnabled = prefs.hapticsEnabled,
                    onLeftStickMove = onLeftStickMove,
                    onRightStickMove = onRightStickMove,
                    centerTelemetryInput = stickInput
                )
            }
        }
    }
}

@Composable
private fun CockpitTopBar(
    flightState: FlightState,
    telemetry: TelemetrySnapshot,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onEmergencyStop: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val tokens = LocalAeroTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(tokens.cornerStyle.mediumDp))
            .background(tokens.levelCarte.copy(alpha = tokens.glassAlpha))
            .border(1.dp, tokens.borderSubtle, RoundedCornerShape(tokens.cornerStyle.mediumDp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FlightStatePill(state = flightState)
            TelemetryBadge("FPS", "%.1f".format(telemetry.videoFps))
            TelemetryBadge("LAT", "${telemetry.latencyMs}ms")
            TelemetryBadge("PERTES", "${telemetry.droppedFrames}")
            TelemetryBadge("IA", "${telemetry.inferenceLatencyMs}ms", accentColor = NominalEmerald)
            if (telemetry.gamepadConnected) {
                TelemetryBadge("PAD", "BT/USB", icon = Icons.Rounded.Gamepad, accentColor = NominalEmerald)
            }
        }

        Spacer(Modifier.width(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.size(48.dp).testTag("cockpit_safety_settings")
            ) {
                Icon(Icons.Rounded.Settings, contentDescription = "Paramètres cockpit")
            }
            IconButton(
                onClick = onToggleFullscreen,
                modifier = Modifier.size(48.dp).testTag("cockpit_fullscreen_toggle")
            ) {
                Icon(
                    imageVector = if (isFullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                    contentDescription = "Basculer plein écran"
                )
            }
            // Bouton ARRÊT D'URGENCE isolé EN HAUT, loin des pouces et séparé de l'atterrissage
            Button(
                onClick = onEmergencyStop,
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmergencyCrimson,
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("cockpit_emergency_stop_button")
            ) {
                Icon(Icons.Rounded.PanTool, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("URGENCE")
            }
        }
    }
}

@Composable
private fun FlightAlertsBanner(
    flightState: FlightState,
    safetyMessage: String?,
    singleClientConflict: Boolean,
    onCancelArming: () -> Unit,
    onResetEmergency: () -> Unit,
    onReconnectDrone: () -> Unit,
    onOpenDiagnostics: () -> Unit
) {
    if (flightState == FlightState.Emergency) {
        Card(
            colors = CardDefaults.cardColors(containerColor = EmergencyCrimson.copy(alpha = 0.24f)),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.5.dp, EmergencyCrimson, RoundedCornerShape(12.dp))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Warning, contentDescription = null, tint = EmergencyCrimson)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "ARRÊT D'URGENCE ACTIF — Acquittez l'alerte pour autoriser un nouveau réarmement.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White
                    )
                }
                Button(
                    onClick = onResetEmergency,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = EmergencyCrimson),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("acknowledge_emergency_button")
                ) {
                    Text("Acquitter")
                }
            }
        }
    } else if (flightState == FlightState.Arming) {
        Card(
            colors = CardDefaults.cardColors(containerColor = CautionAmber.copy(alpha = 0.2f)),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, CautionAmber, RoundedCornerShape(12.dp))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Armement en cours...", style = MaterialTheme.typography.titleSmall, color = CautionAmber)
                Button(
                    onClick = onCancelArming,
                    colors = ButtonDefaults.buttonColors(containerColor = CautionAmber, contentColor = Color.Black),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("cancel_arming_button")
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Annuler")
                }
            }
        }
    } else if (flightState == FlightState.Fault) {
        Card(
            colors = CardDefaults.cardColors(containerColor = EmergencyCrimson.copy(alpha = 0.18f)),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, EmergencyCrimson, RoundedCornerShape(12.dp))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = safetyMessage ?: "Anomalie de liaison — sticks neutralisés",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Rounded.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Diagnostic")
                    }
                    Button(onClick = onReconnectDrone, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Reconnecter")
                    }
                }
            }
        }
    } else if (singleClientConflict) {
        Card(
            colors = CardDefaults.cardColors(containerColor = CautionAmber.copy(alpha = 0.16f)),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, CautionAmber, RoundedCornerShape(12.dp))
        ) {
            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Warning, contentDescription = null, tint = CautionAmber)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "La caméra est occupée. Fermez l'application propriétaire du drone puis relancez.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    } else if (!safetyMessage.isNullOrBlank()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            Icon(
                imageVector = if (flightState == FlightState.Flying) Icons.Rounded.FlightTakeoff else Icons.Rounded.Lock,
                contentDescription = null,
                tint = if (flightState == FlightState.Flying) NominalEmerald else CautionAmber,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(safetyMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CockpitFlightAndMediaRow(
    canArm: Boolean,
    flightState: FlightState,
    isConnected: Boolean,
    isRecordingVideo: Boolean,
    activeDetector: DetectorType,
    caps: com.example.core.models.DroneCapabilities?,
    telemetry: TelemetrySnapshot,
    onCaptureSnapshot: () -> Unit,
    onToggleRecording: () -> Unit,
    onCycleAiDetector: () -> Unit,
    onSwitchCamera: () -> Unit,
    onCycleSpeed: () -> Unit,
    onCalibrateGyro: () -> Unit,
    onToggleHeadless: () -> Unit,
    onTriggerFlip: () -> Unit,
    onStopStream: () -> Unit,
    onTakeOffClick: () -> Unit,
    onNormalLand: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onTakeOffClick,
            enabled = canArm,
            modifier = Modifier.heightIn(min = 48.dp).testTag("cockpit_takeoff_button")
        ) {
            Icon(Icons.Rounded.FlightTakeoff, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Décollage")
        }

        if (caps?.supportsTakeOffLand != false) {
            FilledTonalButton(
                onClick = onNormalLand,
                enabled = flightState == FlightState.Flying,
                modifier = Modifier.heightIn(min = 48.dp).testTag("cockpit_land_button")
            ) {
                Icon(Icons.Rounded.FlightLand, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Atterrissage")
            }
        }

        FilledTonalButton(
            onClick = onCaptureSnapshot,
            modifier = Modifier.heightIn(min = 48.dp).testTag("cockpit_snapshot_button")
        ) {
            Icon(Icons.Rounded.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Photo")
        }

        Button(
            onClick = onToggleRecording,
            modifier = Modifier.heightIn(min = 48.dp).testTag("cockpit_record_button"),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRecordingVideo) EmergencyCrimson else MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (isRecordingVideo) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
            )
        ) {
            Icon(
                imageVector = if (isRecordingVideo) Icons.Rounded.Stop else Icons.Rounded.FiberManualRecord,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(if (isRecordingVideo) "Stop MP4" else "MP4")
        }

        FilledTonalButton(
            onClick = onCycleAiDetector,
            modifier = Modifier.heightIn(min = 48.dp).testTag("cockpit_cycle_ai_button")
        ) {
            Icon(Icons.Rounded.Psychology, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("IA: ${activeDetector.labelFr}")
        }

        if (caps?.supportsDualCamera == true) {
            OutlinedButton(onClick = onSwitchCamera, modifier = Modifier.heightIn(min = 48.dp).testTag("cockpit_switch_cam")) {
                Icon(Icons.Rounded.Cameraswitch, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Cam ${telemetry.activeCameraIndex + 1}")
            }
        }

        if (caps?.supportsSpeedModes == true) {
            OutlinedButton(onClick = onCycleSpeed, modifier = Modifier.heightIn(min = 48.dp).testTag("cockpit_speed_button")) {
                Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Vit. ${telemetry.speedMode}/3")
            }
        }

        if (caps?.supportsGyroCalibration == true) {
            OutlinedButton(onClick = onCalibrateGyro, modifier = Modifier.heightIn(min = 48.dp).testTag("cockpit_gyro_button")) {
                Icon(Icons.Rounded.Explore, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Gyro")
            }
        }

        if (caps?.supportsHeadless == true) {
            OutlinedButton(onClick = onToggleHeadless, modifier = Modifier.heightIn(min = 48.dp).testTag("cockpit_headless_button")) {
                Text(if (telemetry.headlessEnabled) "Headless ON" else "Headless")
            }
        }

        if (caps?.supportsFlip == true) {
            OutlinedButton(onClick = onTriggerFlip, modifier = Modifier.heightIn(min = 48.dp).testTag("cockpit_flip_button")) {
                Icon(Icons.Rounded.Loop, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Flip")
            }
        }

        if (isConnected) {
            OutlinedButton(onClick = onStopStream, modifier = Modifier.heightIn(min = 48.dp).testTag("cockpit_stop_stream")) {
                Icon(Icons.Rounded.StopCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Couper flux")
            }
        }
    }
}

@Composable
private fun CockpitDualSticksOverlay(
    leftHanded: Boolean,
    sticksEnabled: Boolean,
    hapticsEnabled: Boolean,
    onLeftStickMove: (yaw: Float, throttle: Float) -> Unit,
    onRightStickMove: (roll: Float, pitch: Float) -> Unit,
    centerTelemetryInput: StickInput? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!leftHanded) {
            VirtualThumbstick(
                title = "GAZ / LACET",
                horizontalLabel = "Yaw",
                verticalLabel = "Thr",
                enabled = sticksEnabled,
                hapticsEnabled = hapticsEnabled,
                testTagName = "stick_throttle_yaw",
                onMove = onLeftStickMove
            )
            centerTelemetryInput?.let { StickReadoutColumn(it) }
            VirtualThumbstick(
                title = "TANGAGE / ROULIS",
                horizontalLabel = "Roll",
                verticalLabel = "Pitch",
                enabled = sticksEnabled,
                hapticsEnabled = hapticsEnabled,
                testTagName = "stick_pitch_roll",
                onMove = onRightStickMove
            )
        } else {
            VirtualThumbstick(
                title = "TANGAGE / ROULIS",
                horizontalLabel = "Roll",
                verticalLabel = "Pitch",
                enabled = sticksEnabled,
                hapticsEnabled = hapticsEnabled,
                testTagName = "stick_pitch_roll_left",
                onMove = onRightStickMove
            )
            centerTelemetryInput?.let { StickReadoutColumn(it) }
            VirtualThumbstick(
                title = "GAZ / LACET",
                horizontalLabel = "Yaw",
                verticalLabel = "Thr",
                enabled = sticksEnabled,
                hapticsEnabled = hapticsEnabled,
                testTagName = "stick_throttle_yaw_right",
                onMove = onLeftStickMove
            )
        }
    }
}

@Composable
private fun StickReadoutColumn(stickInput: StickInput) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text("T=${"%.2f".format(stickInput.throttle)}", style = TelemetryMonoStyle)
        Text("Y=${"%.2f".format(stickInput.yaw)}", style = TelemetryMonoStyle)
        Text("P=${"%.2f".format(stickInput.pitch)}", style = TelemetryMonoStyle)
        Text("R=${"%.2f".format(stickInput.roll)}", style = TelemetryMonoStyle)
    }
}
