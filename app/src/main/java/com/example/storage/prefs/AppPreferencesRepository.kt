package com.example.storage.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.core.models.DetectorType
import com.example.core.models.HardwareBackend
import com.example.core.models.OpticalFlowLevel
import com.example.core.models.TargetDistanceConfig
import com.example.core.models.TrackingMode
import com.example.ui.theme.CornerStyle
import com.example.ui.theme.LossOfLinkBehavior
import com.example.ui.theme.MotionPreference
import com.example.ui.theme.OrientationPreference
import com.example.ui.theme.ThemeMode
import com.example.vision.domain.contracts.ComputeBackend
import com.example.vision.domain.contracts.VisionFramework
import com.example.vision.domain.entities.CameraCalibration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.aeroPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "aerolink_prefs_v2")

enum class SettingsCategory(val labelFr: String, val subtitleFr: String) {
    APPEARANCE("Apparence & Thème", "Mode OLED, opacité verre, couleurs, éditeur de thème"),
    DISPLAY_ORIENTATION("Affichage & Orientation", "Paysage en vol, rotation, animations, protection OLED"),
    CONTROLS_STICKS("Pilotage & Sticks", "Sensibilité, zone morte, mode gaucher, retours haptiques"),
    SAFETY_FLIGHT("Sécurité & Vol", "Verrou vidéo, armement, perte de liaison, capacités"),
    AI_TRACKING("IA & Suivi", "Détecteurs, distance cible, calibration, personne opt-in"),
    PROFILES_BACKUP("Profils & Sauvegarde", "Préréglages nommés, réinitialisation, export JSON")
}

@Serializable
data class AppPreferences(
    // Apparence & Thème
    val themeMode: ThemeMode = ThemeMode.DARK_COCKPIT,
    val customAccentHex: String = "",
    val glassOpacity: Float = 0.84f,
    val cornerStyle: CornerStyle = CornerStyle.BALANCED,
    val textScale: Float = 1.0f,
    // Affichage & Orientation
    val orientationPreference: OrientationPreference = OrientationPreference.LANDSCAPE,
    val lockOrientationGlobally: Boolean = false,
    val motionPreference: MotionPreference = MotionPreference.NORMAL,
    val hudAntiBurnInShift: Boolean = true,
    // Pilotage & Sticks
    val stickSensitivity: Float = 0.75f,
    val stickDeadzone: Float = 0.08f,
    val leftHandedSticks: Boolean = false,
    val hapticsEnabled: Boolean = true,
    // Sécurité & Vol
    val videoOnlySafetyLock: Boolean = true,
    val requireArmingConfirmation: Boolean = true,
    val lossOfLinkBehavior: LossOfLinkBehavior = LossOfLinkBehavior.NEUTRALIZE_HOVER,
    // IA & Suivi
    val activeDetector: DetectorType = DetectorType.FACE_LANDMARKS,
    val activeTrackingMode: TrackingMode = TrackingMode.MULTI_OBJECT,
    val opticalFlowLevel: OpticalFlowLevel = OpticalFlowLevel.LEVEL_1_ANALYSIS,
    val hardwareBackend: HardwareBackend = HardwareBackend.CPU,
    val showTrajectories: Boolean = true,
    val showLandmarks: Boolean = true,
    val showCenterReticle: Boolean = true,
    val detectionZoneEnabled: Boolean = false,
    val confidenceThreshold: Float = 0.48f,
    val maxDisplayedBoxes: Int = 6,
    val abandonTimeoutMs: Long = 900L,
    val desiredDistanceM: Float = 2.5f,
    val referenceTargetHeightM: Float = 1.70f,
    val calibratedFocalPx: Float = 0f,
    val showDistanceInMeters: Boolean = true,
    val personRecognitionOptIn: Boolean = false,
    // ADVDS V1 — Perception Multi-Runtime & Géométrie
    val preferredObjectFramework: VisionFramework = VisionFramework.ONNX_RUNTIME,
    val allowRuntimeFallback: Boolean = true,
    val enableSpatialMap: Boolean = true,
    val enableGestureRecognition: Boolean = true,
    val enableSessionReId: Boolean = false,
    val enableBarcodeMarkers: Boolean = true,
    val markerSizeMeters: Float = 0.20f,
    val showHorizonOverlay: Boolean = true,
    val showRiskOverlay: Boolean = true,
    val cameraCalibration: CameraCalibration = CameraCalibration()
) {
    fun toDistanceConfig(): TargetDistanceConfig = TargetDistanceConfig(
        desiredDistanceM = desiredDistanceM,
        referenceTargetHeightM = referenceTargetHeightM,
        calibratedFocalPx = calibratedFocalPx,
        showDistanceInMeters = showDistanceInMeters
    )

    fun toComputeBackend(): ComputeBackend = when (hardwareBackend) {
        HardwareBackend.CPU -> ComputeBackend.CPU
        HardwareBackend.GPU -> ComputeBackend.GPU
        HardwareBackend.NNAPI -> ComputeBackend.NPU
    }

    fun modifiedCountForCategory(category: SettingsCategory, defaults: AppPreferences = AppPreferences()): Int {
        return when (category) {
            SettingsCategory.APPEARANCE -> listOf(
                themeMode != defaults.themeMode,
                customAccentHex != defaults.customAccentHex,
                glassOpacity != defaults.glassOpacity,
                cornerStyle != defaults.cornerStyle,
                textScale != defaults.textScale
            ).count { it }

            SettingsCategory.DISPLAY_ORIENTATION -> listOf(
                orientationPreference != defaults.orientationPreference,
                lockOrientationGlobally != defaults.lockOrientationGlobally,
                motionPreference != defaults.motionPreference,
                hudAntiBurnInShift != defaults.hudAntiBurnInShift
            ).count { it }

            SettingsCategory.CONTROLS_STICKS -> listOf(
                stickSensitivity != defaults.stickSensitivity,
                stickDeadzone != defaults.stickDeadzone,
                leftHandedSticks != defaults.leftHandedSticks,
                hapticsEnabled != defaults.hapticsEnabled
            ).count { it }

            SettingsCategory.SAFETY_FLIGHT -> listOf(
                videoOnlySafetyLock != defaults.videoOnlySafetyLock,
                requireArmingConfirmation != defaults.requireArmingConfirmation,
                lossOfLinkBehavior != defaults.lossOfLinkBehavior
            ).count { it }

            SettingsCategory.AI_TRACKING -> listOf(
                activeDetector != defaults.activeDetector,
                activeTrackingMode != defaults.activeTrackingMode,
                opticalFlowLevel != defaults.opticalFlowLevel,
                hardwareBackend != defaults.hardwareBackend,
                showTrajectories != defaults.showTrajectories,
                showLandmarks != defaults.showLandmarks,
                showCenterReticle != defaults.showCenterReticle,
                detectionZoneEnabled != defaults.detectionZoneEnabled,
                confidenceThreshold != defaults.confidenceThreshold,
                maxDisplayedBoxes != defaults.maxDisplayedBoxes,
                abandonTimeoutMs != defaults.abandonTimeoutMs,
                desiredDistanceM != defaults.desiredDistanceM,
                referenceTargetHeightM != defaults.referenceTargetHeightM,
                calibratedFocalPx != defaults.calibratedFocalPx,
                showDistanceInMeters != defaults.showDistanceInMeters,
                personRecognitionOptIn != defaults.personRecognitionOptIn,
                preferredObjectFramework != defaults.preferredObjectFramework,
                allowRuntimeFallback != defaults.allowRuntimeFallback,
                enableSpatialMap != defaults.enableSpatialMap,
                enableGestureRecognition != defaults.enableGestureRecognition,
                enableSessionReId != defaults.enableSessionReId,
                enableBarcodeMarkers != defaults.enableBarcodeMarkers,
                markerSizeMeters != defaults.markerSizeMeters,
                showHorizonOverlay != defaults.showHorizonOverlay,
                showRiskOverlay != defaults.showRiskOverlay,
                cameraCalibration != defaults.cameraCalibration
            ).count { it }

            SettingsCategory.PROFILES_BACKUP -> 0
        }
    }

    fun totalModifiedCount(defaults: AppPreferences = AppPreferences()): Int =
        SettingsCategory.entries.sumOf { modifiedCountForCategory(it, defaults) }
}

@Serializable
data class NamedSettingsPreset(
    val id: String,
    val name: String,
    val description: String,
    val preferences: AppPreferences,
    val isBuiltIn: Boolean = false
)

@Serializable
data class SettingsExportBundle(
    val schemaVersion: Int = 1,
    val exportedAtMs: Long = System.currentTimeMillis(),
    val preferences: AppPreferences,
    val customPresets: List<NamedSettingsPreset> = emptyList()
)

class AppPreferencesRepository(private val context: Context) {

    private object Keys {
        val PREFS_JSON = stringPreferencesKey("prefs_json_v2")
        val PRESETS_JSON = stringPreferencesKey("custom_presets_json_v1")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    val builtInPresets: List<NamedSettingsPreset> = listOf(
        NamedSettingsPreset(
            id = "preset_oled_night",
            name = "Cockpit OLED Nuit",
            description = "Noir pur AMOLED, sticks fins, décalage HUD anti-brûlure actif.",
            preferences = AppPreferences(
                themeMode = ThemeMode.OLED,
                glassOpacity = 0.72f,
                hudAntiBurnInShift = true
            ),
            isBuiltIn = true
        ),
        NamedSettingsPreset(
            id = "preset_indoor",
            name = "Vol en salle",
            description = "Sensibilité maîtrisée (60 %), distance cible courte (1,8 m).",
            preferences = AppPreferences(
                themeMode = ThemeMode.DARK_COCKPIT,
                stickSensitivity = 0.60f,
                stickDeadzone = 0.06f,
                desiredDistanceM = 1.8f,
                opticalFlowLevel = OpticalFlowLevel.LEVEL_2_ASSISTANCE
            ),
            isBuiltIn = true
        ),
        NamedSettingsPreset(
            id = "preset_outdoor_sun",
            name = "Extérieur plein soleil",
            description = "Thème clair fort contraste, verre opaque (94 %), texte agrandi.",
            preferences = AppPreferences(
                themeMode = ThemeMode.DAYLIGHT,
                glassOpacity = 0.94f,
                textScale = 1.10f,
                desiredDistanceM = 3.5f
            ),
            isBuiltIn = true
        )
    )

    val preferences: Flow<AppPreferences> = context.aeroPrefsDataStore.data.map { prefs ->
        val raw = prefs[Keys.PREFS_JSON]
        if (raw.isNullOrBlank()) {
            AppPreferences()
        } else {
            runCatching { json.decodeFromString<AppPreferences>(raw) }.getOrDefault(AppPreferences())
        }
    }

    val customPresets: Flow<List<NamedSettingsPreset>> = context.aeroPrefsDataStore.data.map { prefs ->
        val raw = prefs[Keys.PRESETS_JSON]
        if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString<List<NamedSettingsPreset>>(raw) }.getOrDefault(emptyList())
        }
    }

    suspend fun update(transform: (AppPreferences) -> AppPreferences) {
        context.aeroPrefsDataStore.edit { store ->
            val current = store[Keys.PREFS_JSON]?.let {
                runCatching { json.decodeFromString<AppPreferences>(it) }.getOrNull()
            } ?: AppPreferences()
            val updated = transform(current)
            store[Keys.PREFS_JSON] = json.encodeToString(updated)
        }
    }

    suspend fun resetCategory(category: SettingsCategory) {
        val def = AppPreferences()
        update { cur ->
            when (category) {
                SettingsCategory.APPEARANCE -> cur.copy(
                    themeMode = def.themeMode,
                    customAccentHex = def.customAccentHex,
                    glassOpacity = def.glassOpacity,
                    cornerStyle = def.cornerStyle,
                    textScale = def.textScale
                )
                SettingsCategory.DISPLAY_ORIENTATION -> cur.copy(
                    orientationPreference = def.orientationPreference,
                    lockOrientationGlobally = def.lockOrientationGlobally,
                    motionPreference = def.motionPreference,
                    hudAntiBurnInShift = def.hudAntiBurnInShift
                )
                SettingsCategory.CONTROLS_STICKS -> cur.copy(
                    stickSensitivity = def.stickSensitivity,
                    stickDeadzone = def.stickDeadzone,
                    leftHandedSticks = def.leftHandedSticks,
                    hapticsEnabled = def.hapticsEnabled
                )
                SettingsCategory.SAFETY_FLIGHT -> cur.copy(
                    videoOnlySafetyLock = def.videoOnlySafetyLock,
                    requireArmingConfirmation = def.requireArmingConfirmation,
                    lossOfLinkBehavior = def.lossOfLinkBehavior
                )
                SettingsCategory.AI_TRACKING -> cur.copy(
                    activeDetector = def.activeDetector,
                    activeTrackingMode = def.activeTrackingMode,
                    opticalFlowLevel = def.opticalFlowLevel,
                    hardwareBackend = def.hardwareBackend,
                    showTrajectories = def.showTrajectories,
                    showLandmarks = def.showLandmarks,
                    showCenterReticle = def.showCenterReticle,
                    detectionZoneEnabled = def.detectionZoneEnabled,
                    confidenceThreshold = def.confidenceThreshold,
                    maxDisplayedBoxes = def.maxDisplayedBoxes,
                    abandonTimeoutMs = def.abandonTimeoutMs,
                    desiredDistanceM = def.desiredDistanceM,
                    referenceTargetHeightM = def.referenceTargetHeightM,
                    calibratedFocalPx = def.calibratedFocalPx,
                    showDistanceInMeters = def.showDistanceInMeters,
                    personRecognitionOptIn = def.personRecognitionOptIn,
                    preferredObjectFramework = def.preferredObjectFramework,
                    allowRuntimeFallback = def.allowRuntimeFallback,
                    enableSpatialMap = def.enableSpatialMap,
                    enableGestureRecognition = def.enableGestureRecognition,
                    enableSessionReId = def.enableSessionReId,
                    enableBarcodeMarkers = def.enableBarcodeMarkers,
                    markerSizeMeters = def.markerSizeMeters,
                    showHorizonOverlay = def.showHorizonOverlay,
                    showRiskOverlay = def.showRiskOverlay,
                    cameraCalibration = def.cameraCalibration
                )
                SettingsCategory.PROFILES_BACKUP -> cur
            }
        }
    }

    suspend fun resetAll(preserveSafetyLockIfFlying: Boolean) {
        val def = AppPreferences()
        update { cur ->
            if (preserveSafetyLockIfFlying) {
                def.copy(
                    videoOnlySafetyLock = cur.videoOnlySafetyLock,
                    requireArmingConfirmation = cur.requireArmingConfirmation,
                    lossOfLinkBehavior = cur.lossOfLinkBehavior
                )
            } else {
                def
            }
        }
    }

    suspend fun saveCustomPreset(name: String, description: String) {
        val current = preferences.first()
        context.aeroPrefsDataStore.edit { store ->
            val list = store[Keys.PRESETS_JSON]?.let {
                runCatching { json.decodeFromString<List<NamedSettingsPreset>>(it) }.getOrNull()
            }?.toMutableList() ?: mutableListOf()
            list.add(
                0,
                NamedSettingsPreset(
                    id = "custom_${System.currentTimeMillis()}",
                    name = name.trim().ifBlank { "Profil personnalisé" },
                    description = description.trim().ifBlank { "Configuration enregistrée localement" },
                    preferences = current,
                    isBuiltIn = false
                )
            )
            store[Keys.PRESETS_JSON] = json.encodeToString(list)
        }
    }

    suspend fun deleteCustomPreset(id: String) {
        context.aeroPrefsDataStore.edit { store ->
            val list = store[Keys.PRESETS_JSON]?.let {
                runCatching { json.decodeFromString<List<NamedSettingsPreset>>(it) }.getOrNull()
            }?.filterNot { it.id == id } ?: emptyList()
            store[Keys.PRESETS_JSON] = json.encodeToString(list)
        }
    }

    suspend fun applyPreset(preset: NamedSettingsPreset) {
        update { preset.preferences }
    }

    suspend fun exportBundleJson(): String {
        val current = preferences.first()
        val custom = customPresets.first()
        return json.encodeToString(
            SettingsExportBundle(
                schemaVersion = 1,
                preferences = current,
                customPresets = custom
            )
        )
    }

    suspend fun importBundleJson(rawJson: String): Result<SettingsExportBundle> = runCatching {
        val bundle = json.decodeFromString<SettingsExportBundle>(rawJson)
        require(bundle.schemaVersion >= 1) { "Version de schéma invalide" }
        context.aeroPrefsDataStore.edit { store ->
            store[Keys.PREFS_JSON] = json.encodeToString(bundle.preferences)
            if (bundle.customPresets.isNotEmpty()) {
                store[Keys.PRESETS_JSON] = json.encodeToString(bundle.customPresets)
            }
        }
        bundle
    }
}
