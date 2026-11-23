package com.example

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WifiFind
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.FlightTakeoff
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WifiFind
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.core.models.DetectorType
import com.example.ui.AeroLinkViewModel
import com.example.ui.navigation.CockpitRoute
import com.example.ui.navigation.ConnectionRoute
import com.example.ui.navigation.DiagnosticsRoute
import com.example.ui.navigation.HomeRoute
import com.example.ui.navigation.ModelsRoute
import com.example.ui.navigation.RecordingsRoute
import com.example.ui.navigation.SettingsRoute
import com.example.ui.navigation.VisionCenterRoute
import com.example.ui.screens.CockpitScreen
import com.example.ui.screens.ConnectionWizardScreen
import com.example.ui.screens.DiagnosticsScreen
import com.example.ui.screens.DroneSettingsScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.ModelsBenchmarkScreen
import com.example.ui.screens.RecordingsScreen
import com.example.ui.screens.VisionCenterScreen
import com.example.ui.theme.AeroLinkTheme
import com.example.ui.theme.OrientationPreference
import kotlin.reflect.KClass

private data class NavDestinationItem(
    val route: Any,
    val routeClass: KClass<*>,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

class MainActivity : ComponentActivity() {
    private val viewModel: AeroLinkViewModel by viewModels()

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val prefs by viewModel.preferences.collectAsStateWithLifecycle()

            AeroLinkTheme(
                themeMode = prefs.themeMode,
                customAccentHex = prefs.customAccentHex,
                glassOpacity = prefs.glassOpacity,
                cornerStyle = prefs.cornerStyle,
                textScale = prefs.textScale,
                motionPreference = prefs.motionPreference
            ) {
                AeroLinkAppShell(
                    viewModel = viewModel,
                    useNavigationRail = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact
                )
            }
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (viewModel.flightManager.handleGamepadMotion(event)) return true
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (viewModel.flightManager.handleGamepadKey(keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AeroLinkAppShell(
    viewModel: AeroLinkViewModel,
    useNavigationRail: Boolean
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination
    val isCockpitRoute = currentDest?.hasRoute(CockpitRoute::class) == true
    val snackbarHostState = remember { SnackbarHostState() }
    val activity = LocalActivity.current

    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val profiles by viewModel.droneProfiles.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val sessions by viewModel.flightSessions.collectAsStateWithLifecycle()
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val logs by viewModel.diagnosticLogs.collectAsStateWithLifecycle()
    val benchmarks by viewModel.benchmarkReports.collectAsStateWithLifecycle()
    val scannedNetworks by viewModel.scannedNetworks.collectAsStateWithLifecycle()
    val wifiStatus by viewModel.wifiStatus.collectAsStateWithLifecycle()
    val reconnectAttempt by viewModel.reconnectAttempt.collectAsStateWithLifecycle()
    val activeNetContext by viewModel.activeNetworkContext.collectAsStateWithLifecycle()
    val detectionResult by viewModel.detectionResult.collectAsStateWithLifecycle()
    val isProbing by viewModel.isProbing.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val flightState by viewModel.flightState.collectAsStateWithLifecycle()
    val stickInput by viewModel.stickInput.collectAsStateWithLifecycle()
    val safetyMessage by viewModel.safetyStatusMessage.collectAsStateWithLifecycle()
    val displayFrame by viewModel.displayFrame.collectAsStateWithLifecycle()
    val visionResult by viewModel.visionResult.collectAsStateWithLifecycle()
    val detectionZone by viewModel.detectionZone.collectAsStateWithLifecycle()
    val isRecordingVideo by viewModel.isRecordingVideo.collectAsStateWithLifecycle()
    val isLocalCameraBench by viewModel.isLocalCameraBenchActive.collectAsStateWithLifecycle()
    val isBenchmarkRunning by viewModel.isBenchmarkRunning.collectAsStateWithLifecycle()
    val installedModels by viewModel.installedModels.collectAsStateWithLifecycle()
    val registryModels by viewModel.registryModels.collectAsStateWithLifecycle()
    val objectResolutionReport by viewModel.objectResolutionReport.collectAsStateWithLifecycle()
    val depthResolutionReport by viewModel.depthResolutionReport.collectAsStateWithLifecycle()
    val validationReport by viewModel.validationReport.collectAsStateWithLifecycle()
    val isValidatingContracts by viewModel.isValidatingContracts.collectAsStateWithLifecycle()
    val gpuDelegateSupported by viewModel.gpuDelegateSupported.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()
    val customPresets by viewModel.customPresets.collectAsStateWithLifecycle()
    val canUndoSetting by viewModel.canUndoLastSetting.collectAsStateWithLifecycle()

    // Orientation utilisateur : Cockpit en Paysage par défaut, ou orientation forcée globalement
    LaunchedEffect(prefs.orientationPreference, prefs.lockOrientationGlobally, isCockpitRoute) {
        val targetOrientation = if (isCockpitRoute || prefs.lockOrientationGlobally) {
            when (prefs.orientationPreference) {
                OrientationPreference.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                OrientationPreference.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                OrientationPreference.AUTO_SENSOR -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                OrientationPreference.SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (activity != null && activity.requestedOrientation != targetOrientation) {
            activity.requestedOrientation = targetOrientation
        }
    }

    LaunchedEffect(statusBanner) {
        statusBanner?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBanner()
        }
    }

    val primaryNavItems = remember {
        listOf(
            NavDestinationItem(HomeRoute, HomeRoute::class, "Accueil", Icons.Rounded.Home, Icons.Outlined.Home, "nav_home"),
            NavDestinationItem(ConnectionRoute, ConnectionRoute::class, "Connexion", Icons.Rounded.WifiFind, Icons.Outlined.WifiFind, "nav_connect"),
            NavDestinationItem(CockpitRoute, CockpitRoute::class, "Cockpit", Icons.Rounded.FlightTakeoff, Icons.Outlined.FlightTakeoff, "nav_cockpit"),
            NavDestinationItem(VisionCenterRoute, VisionCenterRoute::class, "Centre IA", Icons.Rounded.Psychology, Icons.Outlined.Psychology, "nav_vision"),
            NavDestinationItem(RecordingsRoute, RecordingsRoute::class, "Médias", Icons.Rounded.PhotoLibrary, Icons.Outlined.PhotoLibrary, "nav_recordings")
        )
    }

    val secondaryNavItems = remember {
        listOf(
            NavDestinationItem(ModelsRoute, ModelsRoute::class, "Modèles", Icons.Rounded.Memory, Icons.Outlined.Memory, "nav_models"),
            NavDestinationItem(SettingsRoute, SettingsRoute::class, "Paramètres", Icons.Rounded.Settings, Icons.Outlined.Settings, "nav_settings"),
            NavDestinationItem(DiagnosticsRoute, DiagnosticsRoute::class, "Diagnostic", Icons.Rounded.BugReport, Icons.Outlined.BugReport, "nav_diagnostics")
        )
    }

    fun navigateTo(route: Any) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            if (!isCockpitRoute) {
                TopAppBar(
                    title = {
                        Text(
                            text = "AeroLink",
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    actions = {
                        secondaryNavItems.forEach { item ->
                            val selected = currentDest?.hasRoute(item.routeClass) == true
                            IconButton(
                                onClick = { navigateTo(item.route) },
                                modifier = Modifier.testTag(item.testTag)
                            ) {
                                Icon(
                                    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = item.label,
                                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (!useNavigationRail) {
                NavigationBar {
                    primaryNavItems.forEach { item ->
                        val selected = currentDest?.hasRoute(item.routeClass) == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navigateTo(item.route) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = item.label
                                )
                            },
                            label = { Text(item.label, maxLines = 1) },
                            modifier = Modifier.testTag(item.testTag)
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (useNavigationRail) {
                NavigationRail {
                    (primaryNavItems + secondaryNavItems).forEach { item ->
                        val selected = currentDest?.hasRoute(item.routeClass) == true
                        NavigationRailItem(
                            selected = selected,
                            onClick = { navigateTo(item.route) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = item.label
                                )
                            },
                            label = { Text(item.label) },
                            modifier = Modifier.testTag("rail_${item.testTag}")
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                NavHost(
                    navController = navController,
                    startDestination = HomeRoute
                ) {
                    composable<HomeRoute> {
                        HomeScreen(
                            telemetry = telemetry,
                            flightState = flightState,
                            profiles = profiles,
                            sessions = sessions,
                            onOpenConnectionWizard = { navigateTo(ConnectionRoute) },
                            onOpenCockpit = { navigateTo(CockpitRoute) },
                            onOpenVisionCenter = { navigateTo(VisionCenterRoute) },
                            onSelectProfile = { profile -> viewModel.selectAndConnectProfile(profile) }
                        )
                    }
                    composable<ConnectionRoute> {
                        ConnectionWizardScreen(
                            wifiStatus = wifiStatus,
                            reconnectAttempt = reconnectAttempt,
                            hasWifiPermission = viewModel.wifiManager.hasWifiScanPermission(),
                            scannedNetworks = scannedNetworks,
                            activeNetworkContext = activeNetContext,
                            detectionResult = detectionResult,
                            isProbing = isProbing,
                            singleClientConflictWarning = telemetry.singleClientConflictWarning,
                            videoOnlySafetyLock = prefs.videoOnlySafetyLock,
                            onScanWifi = { viewModel.scanWifiNetworks() },
                            onConnectWifi = { ssid, pass -> viewModel.connectToWifi(ssid, pass) },
                            onCancelReconnect = { viewModel.cancelWifiReconnect() },
                            onInspectActiveWifi = { viewModel.inspectActiveSystemWifi() },
                            onRunDiscovery = { viewModel.runProtocolDiscovery() },
                            onOverrideFamily = { fam -> viewModel.overrideDetectedFamily(fam) },
                            onLaunchStreamAndOpenCockpit = {
                                viewModel.connectFromDetectionResult()
                                navigateTo(CockpitRoute)
                            },
                            onOpenDiagnostics = { navigateTo(DiagnosticsRoute) }
                        )
                    }
                    composable<CockpitRoute> {
                        CockpitScreen(
                            activeProfile = activeProfile,
                            telemetry = telemetry,
                            flightState = flightState,
                            safetyMessage = safetyMessage,
                            stickInput = stickInput,
                            displayFrame = displayFrame,
                            visionResult = visionResult,
                            detectionZone = detectionZone,
                            prefs = prefs,
                            isRecordingVideo = isRecordingVideo,
                            canArm = viewModel.flightManager.canArm(),
                            onCaptureSnapshot = { viewModel.captureSnapshot() },
                            onToggleRecording = { viewModel.toggleVideoRecording() },
                            onSwitchCamera = { viewModel.switchDroneCamera() },
                            onCycleAiDetector = {
                                val nextIdx = (DetectorType.entries.indexOf(prefs.activeDetector) + 1) % DetectorType.entries.size
                                viewModel.setActiveDetector(DetectorType.entries[nextIdx])
                            },
                            onDesignateTrack = { id -> viewModel.designateTarget(id) },
                            onLockTrack = { id -> viewModel.toggleTargetLock(id) },
                            onClearTargetSelection = { viewModel.clearTargetSelection() },
                            onManualBoxDrawn = { box -> viewModel.addManualTargetBox(box) },
                            onSelectNearestTarget = { viewModel.selectNearestTarget() },
                            onSelectCenterTarget = { viewModel.selectCenterTarget() },
                            onResolveAmbiguousTarget = { id -> viewModel.resolveAmbiguousTarget(id) },
                            onChangeDesiredDistance = { dist -> viewModel.updatePreferences { it.copy(desiredDistanceM = dist) } },
                            onArmAndTakeOff = { viewModel.flightManager.armAndTakeOff(true) },
                            onCancelArming = { viewModel.flightManager.cancelArming() },
                            onNormalLand = { viewModel.flightManager.normalLand() },
                            onEmergencyStop = { viewModel.flightManager.emergencyStop() },
                            onResetEmergency = { viewModel.flightManager.resetEmergency() },
                            onCalibrateGyro = { viewModel.flightManager.calibrateGyro() },
                            onCycleSpeed = { viewModel.flightManager.cycleSpeedMode() },
                            onToggleHeadless = { viewModel.flightManager.toggleHeadless() },
                            onTriggerFlip = { viewModel.flightManager.triggerFlip() },
                            onLeftStickMove = { yaw, thr -> viewModel.flightManager.updateLeftStick(yaw, thr) },
                            onRightStickMove = { roll, pitch -> viewModel.flightManager.updateRightStick(roll, pitch) },
                            onStopStream = { viewModel.stopActiveStream() },
                            onReconnectDrone = {
                                val prof = activeProfile
                                if (prof != null) viewModel.startDroneVideoStream(prof) else navigateTo(ConnectionRoute)
                            },
                            onOpenSettings = { navigateTo(SettingsRoute) },
                            onOpenDiagnostics = { navigateTo(DiagnosticsRoute) }
                        )
                    }
                    composable<VisionCenterRoute> {
                        VisionCenterScreen(
                            displayFrame = displayFrame,
                            visionResult = visionResult,
                            detectionZone = detectionZone,
                            prefs = prefs,
                            isLocalCameraBenchActive = isLocalCameraBench,
                            objectResolutionReport = objectResolutionReport,
                            onSetDetector = { viewModel.setActiveDetector(it) },
                            onSetPreferredFramework = { viewModel.setPreferredObjectFramework(it) },
                            onSetTrackingMode = { viewModel.setTrackingMode(it) },
                            onSetOpticalFlowLevel = { viewModel.setOpticalFlowLevel(it) },
                            onSetBackend = { viewModel.setHardwareBackend(it) },
                            onSetOverlayToggles = { t, l, c, z -> viewModel.setOverlayToggles(t, l, c, z) },
                            onUpdatePrefs = { transform -> viewModel.updatePreferences(transform) },
                            onDesignateTrack = { viewModel.designateTarget(it) },
                            onLockTrack = { viewModel.toggleTargetLock(it) },
                            onClearSelection = { viewModel.clearTargetSelection() },
                            onManualBoxDrawn = { box -> viewModel.addManualTargetBox(box) },
                            onSelectNearestTarget = { viewModel.selectNearestTarget() },
                            onSelectCenterTarget = { viewModel.selectCenterTarget() },
                            onResolveAmbiguousTarget = { viewModel.resolveAmbiguousTarget(it) },
                            onCalibrateFocal = { dist, height, norm -> viewModel.calibrateFocalFromReferenceBox(dist, height, norm) },
                            onResetCalibration = { viewModel.resetCameraCalibration() },
                            onToggleLocalCameraBench = { viewModel.setLocalCameraBenchActive(it) },
                            onSubmitBenchBitmap = { bmp, rot -> viewModel.submitLocalBenchBitmap(bmp, rot) },
                            onAnalyzePickedImage = { uri -> viewModel.analyzeStaticGalleryImage(uri) }
                        )
                    }
                    composable<RecordingsRoute> {
                        RecordingsScreen(
                            recordings = recordings,
                            onDeleteRecording = { viewModel.deleteRecording(it) },
                            onPurgeAll = { viewModel.purgeAllLocalData() }
                        )
                    }
                    composable<ModelsRoute> {
                        ModelsBenchmarkScreen(
                            installedModels = installedModels,
                            registryModels = registryModels,
                            objectResolutionReport = objectResolutionReport,
                            depthResolutionReport = depthResolutionReport,
                            validationReport = validationReport,
                            isValidatingContracts = isValidatingContracts,
                            gpuDelegateSupported = gpuDelegateSupported,
                            activeDetector = prefs.activeDetector,
                            preferredFramework = prefs.preferredObjectFramework,
                            selectedBackend = prefs.hardwareBackend,
                            isBenchmarkRunning = isBenchmarkRunning,
                            reports = benchmarks,
                            onSelectBackend = { viewModel.setHardwareBackend(it) },
                            onSelectDetector = { viewModel.setActiveDetector(it) },
                            onRunBenchmark = { det, backend -> viewModel.runAiBenchmark(det, backend) },
                            onImportModelArtifact = { modelId, uri -> viewModel.importModelArtifact(modelId, uri) },
                            onRemoveModelArtifact = { modelId -> viewModel.removeModelArtifact(modelId) },
                            onRunValidationSuite = { fw -> viewModel.runValidationLabSuite(fw) }
                        )
                    }
                    composable<SettingsRoute> {
                        DroneSettingsScreen(
                            prefs = prefs,
                            activeProfile = activeProfile,
                            flightState = flightState,
                            builtInPresets = viewModel.builtInPresets,
                            customPresets = customPresets,
                            canUndo = canUndoSetting,
                            onUndoLast = { viewModel.undoLastPreferenceChange() },
                            onUpdatePrefs = { transform -> viewModel.updatePreferences(transform) },
                            onUpdateProfile = { viewModel.updateActiveProfile(it) },
                            onResetCategory = { cat -> viewModel.resetSettingsCategory(cat) },
                            onResetAll = { viewModel.resetAllSettings() },
                            onApplyPreset = { preset -> viewModel.applyPreset(preset) },
                            onSaveCustomPreset = { name, desc -> viewModel.saveCustomPreset(name, desc) },
                            onDeleteCustomPreset = { id -> viewModel.deleteCustomPreset(id) },
                            onExportJson = { viewModel.exportSettingsJson() },
                            onImportJson = { raw -> viewModel.importSettingsJson(raw) }
                        )
                    }
                    composable<DiagnosticsRoute> {
                        DiagnosticsScreen(
                            telemetry = telemetry,
                            logs = logs,
                            onExportReport = { viewModel.exportDiagnosticsFile() },
                            onClearLogs = { viewModel.clearDiagnosticLogs() }
                        )
                    }
                }
            }
        }
    }
}
