package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Camera
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.core.models.DetectionZone
import com.example.core.models.DetectorType
import com.example.core.models.HardwareBackend
import com.example.core.models.NormalizedBox
import com.example.core.models.OpticalFlowLevel
import com.example.core.models.TrackingMode
import com.example.core.models.VideoFrame
import com.example.core.models.VisionAnalysisResult
import com.example.storage.prefs.AppPreferences
import com.example.ui.components.FpvVideoStage
import com.example.ui.components.TargetSelectionStrip
import com.example.ui.theme.CautionAmber
import com.example.ui.theme.EmergencyCrimson
import com.example.ui.theme.LocalAeroTokens
import com.example.ui.theme.NominalEmerald
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TelemetryMonoStyle
import com.example.vision.domain.contracts.VisionFramework
import com.example.vision.domain.entities.RiskLevel
import com.example.vision.domain.observations.SemanticClass
import com.example.vision.runtime.provider.ResolutionReport
import java.util.concurrent.Executors

private enum class VisionSubTab(val labelFr: String) {
    PERCEPTION("Perception"),
    TRACKING("Suivi & Humain"),
    DEPTH_CALIB("Profondeur & Calib"),
    SPATIAL_RISK("Carte & Risque"),
    SCHEDULER("Ordonnanceur"),
    DISPLAY("Affichage")
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun VisionCenterScreen(
    displayFrame: VideoFrame?,
    visionResult: VisionAnalysisResult,
    detectionZone: DetectionZone,
    prefs: AppPreferences,
    isLocalCameraBenchActive: Boolean,
    objectResolutionReport: ResolutionReport? = null,
    onSetDetector: (DetectorType) -> Unit,
    onSetPreferredFramework: (VisionFramework) -> Unit = {},
    onSetTrackingMode: (TrackingMode) -> Unit,
    onSetOpticalFlowLevel: (OpticalFlowLevel) -> Unit,
    onSetBackend: (HardwareBackend) -> Unit,
    onSetOverlayToggles: (trajectories: Boolean?, landmarks: Boolean?, center: Boolean?, zone: Boolean?) -> Unit,
    onUpdatePrefs: ((AppPreferences) -> AppPreferences) -> Unit,
    onDesignateTrack: (String) -> Unit,
    onLockTrack: (String) -> Unit,
    onClearSelection: () -> Unit,
    onManualBoxDrawn: (NormalizedBox) -> Unit,
    onSelectNearestTarget: () -> Unit,
    onSelectCenterTarget: () -> Unit,
    onResolveAmbiguousTarget: (String) -> Unit,
    onCalibrateFocal: (knownDistanceM: Float, realHeightM: Float, apparentHeightNorm: Float) -> Unit,
    onResetCalibration: () -> Unit = {},
    onToggleLocalCameraBench: (Boolean) -> Unit,
    onSubmitBenchBitmap: (Bitmap, Int) -> Unit,
    onAnalyzePickedImage: (android.net.Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalAeroTokens.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var cameraEnabled by remember { mutableStateOf(false) }
    var activeTab by remember { mutableStateOf(VisionSubTab.PERCEPTION) }
    var manualDrawMode by remember { mutableStateOf(false) }
    var calibDistanceM by remember { mutableFloatStateOf(2.0f) }

    val pState = visionResult.perceptionState

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            cameraEnabled = true
            onToggleLocalCameraBench(true)
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            cameraEnabled = false
            onAnalyzePickedImage(uri)
        }
    }

    DisposableEffect(cameraEnabled) {
        val executor = Executors.newSingleThreadExecutor()
        var cameraProvider: ProcessCameraProvider? = null
        if (cameraEnabled && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                runCatching {
                    val provider = future.get()
                    cameraProvider = provider
                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setOutputImageRotationEnabled(true)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor) { imageProxy ->
                        try {
                            val bmp = imageProxy.toBitmap()
                            val rot = imageProxy.imageInfo.rotationDegrees
                            val oriented = if (rot != 0) {
                                val m = Matrix().apply { postRotate(rot.toFloat()) }
                                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, false)
                                if (rotated !== bmp) bmp.recycle()
                                rotated
                            } else {
                                bmp
                            }
                            onSubmitBenchBitmap(oriented, 0)
                        } catch (_: Throwable) {
                        } finally {
                            imageProxy.close()
                        }
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                }
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose {
            runCatching { cameraProvider?.unbindAll() }
            executor.shutdown()
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().widthIn(max = 920.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Centre de Perception ADVDS", style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = "Architecture hexagonale multi-runtime (ONNX • LiteRT • ML Kit • Géométrie). Aucune commande de vol n'est émise par l'IA.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Aperçu vidéo IA + sélection de cible
            item {
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
                    onClearSelection = onClearSelection,
                    manualDrawMode = manualDrawMode,
                    onManualBoxDrawn = { box ->
                        manualDrawMode = false
                        onManualBoxDrawn(box)
                    },
                    showHorizonOverlay = prefs.showHorizonOverlay,
                    showRiskOverlay = prefs.showRiskOverlay,
                    emptyTitle = "Aperçu perception ADVDS",
                    emptySubtitle = "Affiche le flux drone actif, ou testez avec le Banc Caméra / Photo locale.",
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                )
            }

            // Boutons Banc Caméra / Photo
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            if (cameraEnabled) {
                                cameraEnabled = false
                                onToggleLocalCameraBench(false)
                            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                cameraEnabled = true
                                onToggleLocalCameraBench(true)
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("vision_camera_bench_button")
                    ) {
                        Icon(
                            imageVector = if (cameraEnabled) Icons.Rounded.VideocamOff else Icons.Rounded.Camera,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (cameraEnabled) "Couper Banc Caméra" else "Banc Caméra locale")
                    }

                    FilledTonalButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("vision_photo_picker_button")
                    ) {
                        Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Tester sur Photo")
                    }
                }
            }

            item {
                TargetSelectionStrip(
                    subjects = visionResult.trackedSubjects,
                    ambiguousCandidates = visionResult.ambiguousRecoveryCandidates,
                    manualDrawMode = manualDrawMode,
                    prefs = prefs,
                    onDesignateTrack = onDesignateTrack,
                    onLockTrack = onLockTrack,
                    onClearSelection = onClearSelection,
                    onSelectNearest = onSelectNearestTarget,
                    onSelectCenter = onSelectCenterTarget,
                    onToggleManualDraw = { manualDrawMode = !manualDrawMode },
                    onResolveAmbiguous = onResolveAmbiguousTarget,
                    onChangeDesiredDistance = { dist -> onUpdatePrefs { it.copy(desiredDistanceM = dist) } },
                    compact = false
                )
            }

            // Navigation par sous-onglets propres
            item {
                ScrollableTabRow(
                    selectedTabIndex = VisionSubTab.entries.indexOf(activeTab),
                    edgePadding = 4.dp
                ) {
                    VisionSubTab.entries.forEach { tab ->
                        Tab(
                            selected = activeTab == tab,
                            onClick = { activeTab = tab },
                            text = { Text(tab.labelFr) },
                            modifier = Modifier.testTag("vision_tab_${tab.name}")
                        )
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = tokens.levelCarte),
                    shape = RoundedCornerShape(tokens.cornerStyle.mediumDp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, tokens.borderSubtle, RoundedCornerShape(tokens.cornerStyle.mediumDp))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        when (activeTab) {
                            VisionSubTab.PERCEPTION -> {
                                Text("Détecteur actif & Runtime Objets", style = MaterialTheme.typography.titleMedium)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    DetectorType.entries.forEach { det ->
                                        FilterChip(
                                            selected = prefs.activeDetector == det,
                                            onClick = { onSetDetector(det) },
                                            label = { Text(det.labelFr) },
                                            modifier = Modifier.testTag("detector_chip_${det.name}")
                                        )
                                    }
                                }

                                Text("Runtime préféré (Détection d'objets) :", style = MaterialTheme.typography.labelLarge)
                                val objectFrameworks = listOf(
                                    VisionFramework.ONNX_RUNTIME,
                                    VisionFramework.LITERT,
                                    VisionFramework.ML_KIT
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    objectFrameworks.forEach { fw ->
                                        FilterChip(
                                            selected = prefs.preferredObjectFramework == fw,
                                            onClick = { onSetPreferredFramework(fw) },
                                            label = { Text(fw.displayName) },
                                            modifier = Modifier.testTag("framework_chip_${fw.name}")
                                        )
                                    }
                                }

                                VisionToggleRow(
                                    label = "Autoriser le repli automatique si artefact absent",
                                    checked = prefs.allowRuntimeFallback
                                ) { allow ->
                                    onUpdatePrefs { it.copy(allowRuntimeFallback = allow) }
                                }

                                val rep = objectResolutionReport
                                if (rep != null) {
                                    val isFallback = rep.usedFallback
                                    val bannerColor = if (isFallback || rep.selectedFramework == null) CautionAmber else NominalEmerald
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(bannerColor.copy(alpha = 0.12f))
                                            .border(1.dp, bannerColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isFallback) Icons.Rounded.Warning else Icons.Rounded.Info,
                                            contentDescription = null,
                                            tint = bannerColor,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Column {
                                            Text(
                                                text = "Actif : ${rep.selectedFramework?.displayName ?: "Aucun"} (${rep.selectedProviderId ?: "—"}) [${rep.selectedBackend.labelFr}]",
                                                style = TelemetryMonoStyle,
                                                color = bannerColor
                                            )
                                            rep.warningFr?.let { warn ->
                                                Text(
                                                    text = warn,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }

                                Text(
                                    text = "Seuil de confiance minimal : ${(prefs.confidenceThreshold * 100).toInt()} %",
                                    style = TelemetryMonoStyle
                                )
                                Slider(
                                    value = prefs.confidenceThreshold,
                                    onValueChange = { v -> onUpdatePrefs { it.copy(confidenceThreshold = v) } },
                                    valueRange = 0.2f..0.85f
                                )
                                VisionToggleRow(
                                    label = "Scanner les marqueurs QR / ArUco (Voie spécialisée)",
                                    checked = prefs.enableBarcodeMarkers
                                ) { enabled ->
                                    onUpdatePrefs { it.copy(enableBarcodeMarkers = enabled) }
                                }
                            }

                            VisionSubTab.TRACKING -> {
                                Text("Suivi ByteTrack 9 états & Flux optique RANSAC", style = MaterialTheme.typography.titleMedium)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TrackingMode.entries.forEach { mode ->
                                        FilterChip(
                                            selected = prefs.activeTrackingMode == mode,
                                            onClick = { onSetTrackingMode(mode) },
                                            label = { Text(mode.labelFr) },
                                            modifier = Modifier.testTag("tracking_chip_${mode.name}")
                                        )
                                    }
                                }
                                Text("Délai d'abandon après masquage : ${prefs.abandonTimeoutMs} ms", style = TelemetryMonoStyle)
                                Slider(
                                    value = prefs.abandonTimeoutMs.toFloat(),
                                    onValueChange = { v -> onUpdatePrefs { it.copy(abandonTimeoutMs = v.toLong()) } },
                                    valueRange = 800f..6000f
                                )

                                Text("Flux optique sparse Lucas-Kanade + RANSAC :", style = MaterialTheme.typography.titleSmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OpticalFlowLevel.entries.forEach { lvl ->
                                        FilterChip(
                                            selected = prefs.opticalFlowLevel == lvl,
                                            onClick = { onSetOpticalFlowLevel(lvl) },
                                            label = { Text(lvl.labelFr) },
                                            modifier = Modifier.testTag("flow_chip_${lvl.name}")
                                        )
                                    }
                                }
                                pState?.motion?.let { m ->
                                    Text(
                                        text = "${m.advisoryLabel} • Inliers RANSAC ${(m.inlierRatio * 100).toInt()}% (${m.featureCount} pts)",
                                        style = TelemetryMonoStyle,
                                        color = TelemetryCyan
                                    )
                                }

                                HorizontalDivider()
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Security, contentDescription = null, tint = CautionAmber)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Perception Humaine & Confidentialité", style = MaterialTheme.typography.titleSmall)
                                }
                                VisionToggleRow(
                                    label = "Reconnaissance gestuelle multi-trames (consultatif)",
                                    checked = prefs.enableGestureRecognition
                                ) { enabled ->
                                    onUpdatePrefs { it.copy(enableGestureRecognition = enabled) }
                                }
                                VisionToggleRow(
                                    label = "Ré-identification visuelle de session (ReID locale)",
                                    checked = prefs.enableSessionReId
                                ) { enabled ->
                                    onUpdatePrefs { it.copy(enableSessionReId = enabled) }
                                }
                                Text(
                                    text = "Aucune donnée biométrique externe : signatures locales temporaires détruites dès fin de session.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedButton(
                                    onClick = {
                                        onClearSelection()
                                        onUpdatePrefs { it.copy(personRecognitionOptIn = false, enableSessionReId = false) }
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = EmergencyCrimson),
                                    modifier = Modifier.testTag("wipe_person_biometrics_button")
                                ) {
                                    Icon(Icons.Rounded.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Purger signatures & désactiver ReID")
                                }
                            }

                            VisionSubTab.DEPTH_CALIB -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Straighten, contentDescription = null, tint = TelemetryCyan)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Profondeur 4 niveaux & Calibration Sténopé", style = MaterialTheme.typography.titleMedium)
                                }
                                val cal = prefs.cameraCalibration
                                Text(
                                    text = if (!cal.isFactoryEstimated || prefs.calibratedFocalPx > 50f) {
                                        "Calibrée : fx=${cal.fx.toInt()} px • fy=${cal.fy.toInt()} px • FOV H=${cal.horizontalFovDegrees.toInt()}° (Métrique ≈ m)"
                                    } else {
                                        "Estimée usine (${cal.fx.toInt()} px) — effectuez une calibration pour réduire l'incertitude"
                                    },
                                    style = TelemetryMonoStyle,
                                    color = if (!cal.isFactoryEstimated || prefs.calibratedFocalPx > 50f) NominalEmerald else CautionAmber
                                )
                                Text(
                                    text = "Matrice K : cx=${cal.cx.toInt()}, cy=${cal.cy.toInt()}, err=${"%.2f".format(cal.reprojectionError)} px",
                                    style = TelemetryMonoStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Text("Distance souhaitée : ${"%.1f".format(prefs.desiredDistanceM)} m", style = TelemetryMonoStyle)
                                Slider(
                                    value = prefs.desiredDistanceM,
                                    onValueChange = { d -> onUpdatePrefs { it.copy(desiredDistanceM = d) } },
                                    valueRange = 1.0f..8.0f
                                )
                                Text("Taille réelle de référence : ${"%.2f".format(prefs.referenceTargetHeightM)} m", style = TelemetryMonoStyle)
                                Slider(
                                    value = prefs.referenceTargetHeightM,
                                    onValueChange = { h -> onUpdatePrefs { it.copy(referenceTargetHeightM = h) } },
                                    valueRange = 0.2f..2.2f
                                )
                                Text("Taille physique marqueur QR / ArUco : ${"%.2f".format(prefs.markerSizeMeters)} m", style = TelemetryMonoStyle)
                                Slider(
                                    value = prefs.markerSizeMeters,
                                    onValueChange = { s -> onUpdatePrefs { it.copy(markerSizeMeters = s) } },
                                    valueRange = 0.08f..0.60f
                                )
                                Text("Calibration rapide : placez la cible à ${"%.1f".format(calibDistanceM)} m", style = TelemetryMonoStyle)
                                Slider(
                                    value = calibDistanceM,
                                    onValueChange = { calibDistanceM = it },
                                    valueRange = 0.8f..6.0f
                                )
                                val sampleBox = visionResult.trackedSubjects.firstOrNull { it.isLocked }
                                    ?: visionResult.trackedSubjects.firstOrNull()
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            if (sampleBox != null) {
                                                onCalibrateFocal(calibDistanceM, prefs.referenceTargetHeightM, sampleBox.box.height)
                                            }
                                        },
                                        enabled = sampleBox != null,
                                        modifier = Modifier.testTag("calibrate_focal_button")
                                    ) {
                                        Text(if (sampleBox != null) "Calibrer sur ${sampleBox.trackId}" else "Aucune cible visible")
                                    }
                                    if (!cal.isFactoryEstimated || prefs.calibratedFocalPx > 0f) {
                                        OutlinedButton(onClick = onResetCalibration) {
                                            Text("Réinitialiser")
                                        }
                                    }
                                }
                            }

                            VisionSubTab.SPATIAL_RISK -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.GridOn, contentDescription = null, tint = TelemetryCyan)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Carte Spatiale 8×5 & Risque Consultatif", style = MaterialTheme.typography.titleMedium)
                                }

                                val sMap = pState?.spatialMap
                                if (sMap != null && sMap.cells.isNotEmpty()) {
                                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        for (ry in 0 until sMap.rows) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                                            ) {
                                                for (rx in 0 until sMap.cols) {
                                                    val cell = sMap.cells.firstOrNull { it.gridX == rx && it.gridY == ry }
                                                    val bg = when (cell?.riskLevel) {
                                                        RiskLevel.HIGH -> EmergencyCrimson.copy(alpha = 0.55f)
                                                        RiskLevel.MODERATE -> CautionAmber.copy(alpha = 0.48f)
                                                        RiskLevel.LOW -> NominalEmerald.copy(alpha = 0.25f)
                                                        else -> Color(0xFF475569).copy(alpha = 0.35f)
                                                    }
                                                    val code = when (cell?.semanticClass) {
                                                        SemanticClass.PERSON -> "P"
                                                        SemanticClass.VEHICLE -> "V"
                                                        SemanticClass.BUILDING -> "B"
                                                        SemanticClass.VEGETATION -> "A"
                                                        SemanticClass.SKY -> "C"
                                                        SemanticClass.GROUND -> "S"
                                                        else -> "?"
                                                    }
                                                    val rangeLbl = cell?.metricRangeMeters?.let { "${"%.1f".format(it)}m" } ?: ""
                                                    Box(
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .height(28.dp)
                                                            .clip(RoundedCornerShape(4.dp))
                                                            .background(bg)
                                                            .border(0.5.dp, tokens.borderSubtle, RoundedCornerShape(4.dp)),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(
                                                            text = if (rangeLbl.isNotEmpty()) "$code $rangeLbl" else code,
                                                            fontSize = 9.sp,
                                                            textAlign = TextAlign.Center,
                                                            style = TelemetryMonoStyle
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    Text(
                                        text = "Géométrie : Horizon ${(sMap.geometry.horizonYNorm * 100).toInt()}% • Fuite (${(sMap.geometry.vanishingPointX * 100).toInt()}%, ${(sMap.geometry.horizonYNorm * 100).toInt()}%) • Sol ${(sMap.geometry.groundPlaneConfidence * 100).toInt()}%",
                                        style = TelemetryMonoStyle,
                                        color = TelemetryCyan
                                    )
                                } else {
                                    Text(
                                        text = "Activez le Banc Caméra ou un flux vidéo pour remplir la grille spatiale locale 8×5.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                HorizontalDivider()
                                val gr = pState?.globalRisk
                                Text("Score Risque Global : ${gr?.level?.labelFr ?: "INCONNU"} (${((gr?.score ?: 0f) * 100).toInt()}%)", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = gr?.reasonFr ?: "R = 0.34·D + 0.26·T + 0.18·M + 0.10·Q + 0.12·U (UNKNOWN ≠ LOW)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (gr != null) {
                                    RiskComponentBar("D (Proximité)", gr.proximityD, EmergencyCrimson)
                                    RiskComponentBar("T (Danger TTC)", gr.ttcT, CautionAmber)
                                    RiskComponentBar("M (Mouvement)", gr.motionM, TelemetryCyan)
                                    RiskComponentBar("Q (Dégradation vidéo)", gr.qualityQ, Color(0xFF94A3B8))
                                    RiskComponentBar("U (Incertitude)", gr.uncertaintyU, Color(0xFFA78BFA))
                                }
                            }

                            VisionSubTab.SCHEDULER -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Speed, contentDescription = null, tint = TelemetryCyan)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Ordonnanceur 4 Voies (Snapdragon 845)", style = MaterialTheme.typography.titleMedium)
                                }
                                val rates = pState?.pipelineRates
                                SchedulerTierRow("Voie 1 • Fast Path (Qualité + Flow + Kalman)", rates?.fastPathHz ?: 0f, rates?.fastPathMs ?: 0f, NominalEmerald)
                                SchedulerTierRow("Voie 2 • AI Path (Détection / Pose / Visages)", rates?.aiPathHz ?: 0f, rates?.aiPathMs ?: 0f, TelemetryCyan)
                                SchedulerTierRow("Voie 3 • Spatial Path (Carte 8×5 / Profondeur)", rates?.spatialPathHz ?: 0f, rates?.spatialPathMs ?: 0f, CautionAmber)
                                SchedulerTierRow("Voie 4 • Specialized Path (QR PnP / ReID)", rates?.specializedPathHz ?: 0f, rates?.specializedPathMs ?: 0f, Color(0xFFA78BFA))

                                pState?.frameQuality?.let { fq ->
                                    Text(
                                        text = "Qualité vidéo Q=${(fq.qualityScore01 * 100).toInt()}% • Flou=${(fq.blurScore01 * 100).toInt()}% • Expo=${(fq.exposureScore01 * 100).toInt()}% • Luma=${fq.meanLuma.toInt()}",
                                        style = TelemetryMonoStyle,
                                        color = if (fq.isObstructed) EmergencyCrimson else TelemetryCyan
                                    )
                                }

                                HorizontalDivider()
                                Text("Accélération matérielle ciblée :", style = MaterialTheme.typography.titleSmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    HardwareBackend.entries.forEach { b ->
                                        FilterChip(
                                            selected = prefs.hardwareBackend == b,
                                            onClick = { onSetBackend(b) },
                                            label = { Text(b.labelFr) },
                                            modifier = Modifier.testTag("vision_backend_${b.name}")
                                        )
                                    }
                                }
                            }

                            VisionSubTab.DISPLAY -> {
                                Text("Surimpressions HUD & Perception", style = MaterialTheme.typography.titleMedium)
                                VisionToggleRow("Afficher trajectoires", prefs.showTrajectories) {
                                    onSetOverlayToggles(it, null, null, null)
                                }
                                VisionToggleRow("Repères faciaux & squelette", prefs.showLandmarks) {
                                    onSetOverlayToggles(null, it, null, null)
                                }
                                VisionToggleRow("Viseur de centrage HUD", prefs.showCenterReticle) {
                                    onSetOverlayToggles(null, null, it, null)
                                }
                                VisionToggleRow("Zone de détection ROI", prefs.detectionZoneEnabled) {
                                    onSetOverlayToggles(null, null, null, it)
                                }
                                VisionToggleRow("Ligne d'horizon & point de fuite", prefs.showHorizonOverlay) { v ->
                                    onUpdatePrefs { it.copy(showHorizonOverlay = v) }
                                }
                                VisionToggleRow("Couleurs & badge de risque HUD", prefs.showRiskOverlay) { v ->
                                    onUpdatePrefs { it.copy(showRiskOverlay = v) }
                                }
                                VisionToggleRow("Carte spatiale locale 8×5", prefs.enableSpatialMap) { v ->
                                    onUpdatePrefs { it.copy(enableSpatialMap = v) }
                                }
                                VisionToggleRow("Afficher distances en mètres (≈ m)", prefs.showDistanceInMeters) { v ->
                                    onUpdatePrefs { it.copy(showDistanceInMeters = v) }
                                }
                                Text("Nombre maximal de boîtes affichées : ${prefs.maxDisplayedBoxes}", style = TelemetryMonoStyle)
                                Slider(
                                    value = prefs.maxDisplayedBoxes.toFloat(),
                                    onValueChange = { v -> onUpdatePrefs { it.copy(maxDisplayedBoxes = v.toInt()) } },
                                    valueRange = 1f..10f
                                )
                            }
                        }
                    }
                }
            }

            if (visionResult.trackedSubjects.isNotEmpty()) {
                item {
                    Text(
                        text = "Pistes ADVDS actives (${visionResult.trackedSubjects.size})",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                items(visionResult.trackedSubjects, key = { it.trackId }) { subj ->
                    val riskColor = when (subj.riskLevelName) {
                        "HIGH" -> EmergencyCrimson
                        "MODERATE" -> CautionAmber
                        "UNKNOWN" -> Color(0xFFA78BFA)
                        else -> NominalEmerald
                    }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = tokens.levelCarte),
                        shape = RoundedCornerShape(tokens.cornerStyle.mediumDp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                if (subj.isLocked) CautionAmber else if (subj.isDesignated) TelemetryCyan else riskColor.copy(alpha = 0.65f),
                                RoundedCornerShape(tokens.cornerStyle.mediumDp)
                            )
                            .combinedClickable(
                                onClick = { onDesignateTrack(subj.trackId) },
                                onLongClick = { onLockTrack(subj.trackId) }
                            )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "${subj.trackId} [${subj.lifecycleLabelFr}] • ${subj.label} (${(subj.confidence * 100).toInt()}%)",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = "${subj.distanceHintFr} • ${subj.motionStateLabelFr} • Risque ${subj.riskLevelName}${subj.ttcSec?.let { " • TTC ${"%.1f".format(it)}s" } ?: ""}",
                                    style = TelemetryMonoStyle,
                                    color = riskColor
                                )
                                Text(
                                    text = "Fusion: ${subj.fusionStatusLabelFr} • ${subj.distanceMethodLabelFr} • Δ(${"%.2f".format(subj.centerDeltaX)}, ${"%.2f".format(subj.centerDeltaY)})",
                                    style = TelemetryMonoStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = if (subj.isLocked) Icons.Rounded.Lock else Icons.Rounded.Radar,
                                contentDescription = "Verrouiller cible",
                                tint = if (subj.isLocked) CautionAmber else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SchedulerTierRow(label: String, hz: Float, ms: Float, accent: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(
            text = "${"%.1f".format(hz)} Hz • ${"%.1f".format(ms)} ms",
            style = TelemetryMonoStyle,
            color = accent
        )
    }
}

@Composable
private fun RiskComponentBar(label: String, value: Float, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text("${(value.coerceIn(0f, 1f) * 100).toInt()} %", style = TelemetryMonoStyle, color = color)
        }
        LinearProgressIndicator(
            progress = { value.coerceIn(0f, 1f) },
            color = color,
            modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp))
        )
    }
}

@Composable
private fun VisionToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
