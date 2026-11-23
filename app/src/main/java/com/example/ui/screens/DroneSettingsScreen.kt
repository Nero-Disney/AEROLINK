package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.core.models.DetectorType
import com.example.core.models.DroneProfile
import com.example.core.models.FlightState
import com.example.core.models.TrackingMode
import com.example.storage.prefs.AppPreferences
import com.example.storage.prefs.NamedSettingsPreset
import com.example.storage.prefs.SettingsCategory
import com.example.ui.components.ThemeEditorPanel
import com.example.ui.theme.CautionAmber
import com.example.ui.theme.EmergencyCrimson
import com.example.ui.theme.LocalAeroTokens
import com.example.ui.theme.LossOfLinkBehavior
import com.example.ui.theme.MotionPreference
import com.example.ui.theme.NominalEmerald
import com.example.ui.theme.OrientationPreference
import com.example.ui.theme.TelemetryMonoStyle
import kotlinx.coroutines.launch

private data class SettingsSearchEntry(
    val title: String,
    val keywords: String,
    val category: SettingsCategory
)

@Composable
fun DroneSettingsScreen(
    prefs: AppPreferences,
    activeProfile: DroneProfile?,
    flightState: FlightState,
    builtInPresets: List<NamedSettingsPreset>,
    customPresets: List<NamedSettingsPreset>,
    canUndo: Boolean,
    onUndoLast: () -> Unit,
    onUpdatePrefs: ((AppPreferences) -> AppPreferences) -> Unit,
    onUpdateProfile: (DroneProfile) -> Unit,
    onResetCategory: (SettingsCategory) -> Unit,
    onResetAll: () -> Unit,
    onApplyPreset: (NamedSettingsPreset) -> Unit,
    onSaveCustomPreset: (name: String, description: String) -> Unit,
    onDeleteCustomPreset: (String) -> Unit,
    onExportJson: suspend () -> String,
    onImportJson: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<SettingsCategory?>(null) }
    var confirmResetAll by remember { mutableStateOf(false) }

    val searchIndex = remember {
        listOf(
            SettingsSearchEntry("Mode OLED & Opacité verre", "oled amoled theme couleur contraste verre", SettingsCategory.APPEARANCE),
            SettingsSearchEntry("Éditeur de thème & Hex", "editeur couleur hex accent apercu", SettingsCategory.APPEARANCE),
            SettingsSearchEntry("Orientation en vol", "paysage portrait capteur rotation", SettingsCategory.DISPLAY_ORIENTATION),
            SettingsSearchEntry("Animations & Anti-brûlure", "motion animation reduite burn in", SettingsCategory.DISPLAY_ORIENTATION),
            SettingsSearchEntry("Sensibilité & Zone morte", "stick deadzone sensibilite", SettingsCategory.CONTROLS_STICKS),
            SettingsSearchEntry("Mode Gaucher & Haptique", "gaucher left handed vibration haptique", SettingsCategory.CONTROLS_STICKS),
            SettingsSearchEntry("Inversions d'axes (Pitch/Roll/Yaw)", "inverser pitch roll yaw", SettingsCategory.CONTROLS_STICKS),
            SettingsSearchEntry("Verrou test vidéo & Armement", "securite verrou video armement confirmation", SettingsCategory.SAFETY_FLIGHT),
            SettingsSearchEntry("Perte de liaison Wi-Fi", "perte liaison wifi stationnaire atterrissage", SettingsCategory.SAFETY_FLIGHT),
            SettingsSearchEntry("Détecteur & Distance cible", "ia detecteur distance focale personnes", SettingsCategory.AI_TRACKING),
            SettingsSearchEntry("Préréglages & Export JSON", "profil preset export import reset reinitialiser", SettingsCategory.PROFILES_BACKUP)
        )
    }

    val totalModified = prefs.totalModifiedCount()

    if (confirmResetAll) {
        AlertDialog(
            onDismissRequest = { confirmResetAll = false },
            title = { Text("Tout réinitialiser ?") },
            text = {
                Text(
                    "Vous avez $totalModified réglage(s) modifié(s). " +
                        if (flightState == FlightState.Flying) {
                            "Les verrous de sécurité en vol resteront protégés."
                        } else {
                            "Tous les réglages reviendront à leur valeur d'usine."
                        }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmResetAll = false
                        onResetAll()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmergencyCrimson),
                    modifier = Modifier.testTag("confirm_reset_all_button")
                ) {
                    Text("Réinitialiser ($totalModified)")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmResetAll = false }) { Text("Annuler") }
            }
        )
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isMasterDetail = maxWidth >= 680.dp

        if (!isMasterDetail && selectedCategory != null) {
            BackHandler { selectedCategory = null }
        }

        if (isMasterDetail) {
            val activeCat = selectedCategory ?: SettingsCategory.APPEARANCE
            Row(modifier = Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsCategoryListPane(
                    prefs = prefs,
                    searchQuery = searchQuery,
                    onSearchChange = { searchQuery = it },
                    searchIndex = searchIndex,
                    selectedCategory = activeCat,
                    canUndo = canUndo,
                    onUndoLast = onUndoLast,
                    onSelectCategory = { selectedCategory = it },
                    modifier = Modifier.weight(0.40f).fillMaxHeight()
                )
                SettingsDetailPane(
                    category = activeCat,
                    prefs = prefs,
                    activeProfile = activeProfile,
                    flightState = flightState,
                    builtInPresets = builtInPresets,
                    customPresets = customPresets,
                    showBackButton = false,
                    onBack = {},
                    onUpdatePrefs = onUpdatePrefs,
                    onUpdateProfile = onUpdateProfile,
                    onResetCategory = { onResetCategory(activeCat) },
                    onRequestResetAll = { confirmResetAll = true },
                    onApplyPreset = onApplyPreset,
                    onSaveCustomPreset = onSaveCustomPreset,
                    onDeleteCustomPreset = onDeleteCustomPreset,
                    onExportJson = onExportJson,
                    onImportJson = onImportJson,
                    modifier = Modifier.weight(0.60f).fillMaxHeight()
                )
            }
        } else {
            val activeCat = selectedCategory
            if (activeCat == null) {
                SettingsCategoryListPane(
                    prefs = prefs,
                    searchQuery = searchQuery,
                    onSearchChange = { searchQuery = it },
                    searchIndex = searchIndex,
                    selectedCategory = null,
                    canUndo = canUndo,
                    onUndoLast = onUndoLast,
                    onSelectCategory = { selectedCategory = it },
                    modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp)
                )
            } else {
                SettingsDetailPane(
                    category = activeCat,
                    prefs = prefs,
                    activeProfile = activeProfile,
                    flightState = flightState,
                    builtInPresets = builtInPresets,
                    customPresets = customPresets,
                    showBackButton = true,
                    onBack = { selectedCategory = null },
                    onUpdatePrefs = onUpdatePrefs,
                    onUpdateProfile = onUpdateProfile,
                    onResetCategory = { onResetCategory(activeCat) },
                    onRequestResetAll = { confirmResetAll = true },
                    onApplyPreset = onApplyPreset,
                    onSaveCustomPreset = onSaveCustomPreset,
                    onDeleteCustomPreset = onDeleteCustomPreset,
                    onExportJson = onExportJson,
                    onImportJson = onImportJson,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingsCategoryListPane(
    prefs: AppPreferences,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    searchIndex: List<SettingsSearchEntry>,
    selectedCategory: SettingsCategory?,
    canUndo: Boolean,
    onUndoLast: () -> Unit,
    onSelectCategory: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalAeroTokens.current
    val matches = remember(searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) emptyList()
        else searchIndex.filter { it.title.lowercase().contains(q) || it.keywords.contains(q) }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Paramètres", style = MaterialTheme.typography.headlineMedium)
                if (canUndo) {
                    TextButton(onClick = onUndoLast, modifier = Modifier.testTag("settings_undo_button")) {
                        Icon(Icons.Rounded.Undo, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Annuler")
                    }
                }
            }
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                placeholder = { Text("Rechercher un réglage (ex: OLED, gaucher, distance)...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("settings_search_input")
            )
        }

        if (searchQuery.isNotBlank()) {
            if (matches.isEmpty()) {
                item {
                    Text(
                        text = "Aucun réglage correspondant à « $searchQuery ».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(matches) { hit ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = tokens.levelCarte),
                        shape = RoundedCornerShape(tokens.cornerStyle.mediumDp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(tokens.cornerStyle.mediumDp))
                            .clickable { onSelectCategory(hit.category) }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(hit.title, style = MaterialTheme.typography.titleSmall)
                                Text(hit.category.labelFr, style = TelemetryMonoStyle, color = MaterialTheme.colorScheme.primary)
                            }
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
                        }
                    }
                }
            }
        } else {
            // Strictement 6 grandes familles propres sur l'écran racine
            items(SettingsCategory.entries) { cat ->
                val modified = prefs.modifiedCountForCategory(cat)
                val selected = selectedCategory == cat
                val icon: ImageVector = when (cat) {
                    SettingsCategory.APPEARANCE -> Icons.Rounded.Palette
                    SettingsCategory.DISPLAY_ORIENTATION -> Icons.Rounded.ScreenRotation
                    SettingsCategory.CONTROLS_STICKS -> Icons.Rounded.Gamepad
                    SettingsCategory.SAFETY_FLIGHT -> Icons.Rounded.Shield
                    SettingsCategory.AI_TRACKING -> Icons.Rounded.Psychology
                    SettingsCategory.PROFILES_BACKUP -> Icons.Rounded.Backup
                }
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else tokens.levelCarte
                    ),
                    shape = RoundedCornerShape(tokens.cornerStyle.mediumDp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .border(
                            if (selected) 1.5.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.primary else tokens.borderSubtle,
                            RoundedCornerShape(tokens.cornerStyle.mediumDp)
                        )
                        .clickable { onSelectCategory(cat) }
                        .testTag("settings_category_${cat.name}")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(cat.labelFr, style = MaterialTheme.typography.titleSmall)
                                    if (modified > 0) {
                                        Text(
                                            text = "$modified mod.",
                                            style = TelemetryMonoStyle,
                                            color = CautionAmber
                                        )
                                    }
                                }
                                Text(cat.subtitleFr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsDetailPane(
    category: SettingsCategory,
    prefs: AppPreferences,
    activeProfile: DroneProfile?,
    flightState: FlightState,
    builtInPresets: List<NamedSettingsPreset>,
    customPresets: List<NamedSettingsPreset>,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onUpdatePrefs: ((AppPreferences) -> AppPreferences) -> Unit,
    onUpdateProfile: (DroneProfile) -> Unit,
    onResetCategory: () -> Unit,
    onRequestResetAll: () -> Unit,
    onApplyPreset: (NamedSettingsPreset) -> Unit,
    onSaveCustomPreset: (name: String, description: String) -> Unit,
    onDeleteCustomPreset: (String) -> Unit,
    onExportJson: suspend () -> String,
    onImportJson: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalAeroTokens.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var presetName by remember { mutableStateOf("") }
    var presetDesc by remember { mutableStateOf("") }
    var jsonTransferText by remember { mutableStateOf("") }

    val modifiedCount = prefs.modifiedCountForCategory(category)
    val isFlying = flightState == FlightState.Flying

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    if (showBackButton) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.size(48.dp).testTag("settings_back_button")
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Retour aux catégories")
                        }
                    }
                    Text(category.labelFr, style = MaterialTheme.typography.titleLarge)
                }
                if (category != SettingsCategory.PROFILES_BACKUP && modifiedCount > 0) {
                    val resetBlocked = category == SettingsCategory.SAFETY_FLIGHT && isFlying
                    TextButton(
                        onClick = onResetCategory,
                        enabled = !resetBlocked,
                        modifier = Modifier.testTag("settings_reset_category_${category.name}")
                    ) {
                        Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (resetBlocked) "Bloqué en vol" else "Réinit. ($modifiedCount)")
                    }
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
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (category) {
                        SettingsCategory.APPEARANCE -> {
                            ThemeEditorPanel(prefs = prefs, onUpdatePrefs = onUpdatePrefs)
                        }

                        SettingsCategory.DISPLAY_ORIENTATION -> {
                            Text("Orientation du Cockpit", style = MaterialTheme.typography.titleMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                OrientationPreference.entries.forEach { orient ->
                                    FilterChip(
                                        selected = prefs.orientationPreference == orient,
                                        onClick = { onUpdatePrefs { it.copy(orientationPreference = orient) } },
                                        label = { Text(orient.labelFr) },
                                        modifier = Modifier.testTag("orientation_chip_${orient.name}")
                                    )
                                }
                            }
                            SettingSwitchRow(
                                title = "Appliquer à toute l'application",
                                subtitle = "Sinon, le mode Paysage par défaut s'active automatiquement dès l'ouverture du Cockpit.",
                                checked = prefs.lockOrientationGlobally,
                                tag = "setting_lock_orientation_globally",
                                onCheckedChange = { v -> onUpdatePrefs { it.copy(lockOrientationGlobally = v) } }
                            )
                            Text("Animations d'interface", style = MaterialTheme.typography.titleSmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MotionPreference.entries.forEach { mp ->
                                    FilterChip(
                                        selected = prefs.motionPreference == mp,
                                        onClick = { onUpdatePrefs { it.copy(motionPreference = mp) } },
                                        label = { Text(mp.labelFr) },
                                        modifier = Modifier.testTag("motion_chip_${mp.name}")
                                    )
                                }
                            }
                            SettingSwitchRow(
                                title = "Protection anti-brûlure OLED (Pixel shift)",
                                subtitle = "Décale discrètement la couche HUD de 1 à 2 dp toutes les 30 s en mode OLED.",
                                checked = prefs.hudAntiBurnInShift,
                                tag = "setting_oled_shift",
                                onCheckedChange = { v -> onUpdatePrefs { it.copy(hudAntiBurnInShift = v) } }
                            )
                        }

                        SettingsCategory.CONTROLS_STICKS -> {
                            Text("Sensibilité globale : ${(prefs.stickSensitivity * 100).toInt()} %", style = TelemetryMonoStyle)
                            Slider(
                                value = prefs.stickSensitivity,
                                onValueChange = { v -> onUpdatePrefs { it.copy(stickSensitivity = v.coerceIn(0.25f, 1.0f)) } },
                                valueRange = 0.25f..1.0f
                            )
                            Text("Zone morte centrale : ${(prefs.stickDeadzone * 100).toInt()} %", style = TelemetryMonoStyle)
                            Slider(
                                value = prefs.stickDeadzone,
                                onValueChange = { v -> onUpdatePrefs { it.copy(stickDeadzone = v.coerceIn(0f, 0.25f)) } },
                                valueRange = 0f..0.25f
                            )
                            SettingSwitchRow(
                                title = "Disposition Gauchers",
                                subtitle = "Place Tangage/Roulis à gauche et Gaz/Lacet à droite.",
                                checked = prefs.leftHandedSticks,
                                tag = "setting_left_handed_sticks",
                                onCheckedChange = { v -> onUpdatePrefs { it.copy(leftHandedSticks = v) } }
                            )
                            SettingSwitchRow(
                                title = "Retours haptiques (vibrations fines)",
                                subtitle = "Légère vibration au toucher des joysticks et au verrouillage cible.",
                                checked = prefs.hapticsEnabled,
                                tag = "setting_haptics_enabled",
                                onCheckedChange = { v -> onUpdatePrefs { it.copy(hapticsEnabled = v) } }
                            )
                            if (activeProfile != null) {
                                SettingSwitchRow(
                                    title = "Inverser Tangage (Pitch)",
                                    subtitle = "Inverse avant/arrière pour ${activeProfile.commercialName}.",
                                    checked = activeProfile.invertPitch,
                                    tag = "setting_invert_pitch",
                                    onCheckedChange = { onUpdateProfile(activeProfile.copy(invertPitch = it)) }
                                )
                                SettingSwitchRow(
                                    title = "Inverser Roulis (Roll)",
                                    subtitle = "Inverse gauche/droite pour ${activeProfile.commercialName}.",
                                    checked = activeProfile.invertRoll,
                                    tag = "setting_invert_roll",
                                    onCheckedChange = { onUpdateProfile(activeProfile.copy(invertRoll = it)) }
                                )
                                SettingSwitchRow(
                                    title = "Inverser Lacet (Yaw)",
                                    subtitle = "Inverse la rotation pour ${activeProfile.commercialName}.",
                                    checked = activeProfile.invertYaw,
                                    tag = "setting_invert_yaw",
                                    onCheckedChange = { onUpdateProfile(activeProfile.copy(invertYaw = it)) }
                                )
                            } else {
                                Text(
                                    text = "Inversions d'axes par drone : indisponibles tant qu'aucun profil drone n'est actif.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        SettingsCategory.SAFETY_FLIGHT -> {
                            SettingSwitchRow(
                                title = "Mode test vidéo (Verrouiller commandes)",
                                subtitle = "Désactive tout envoi moteur pendant la validation FPV.",
                                checked = prefs.videoOnlySafetyLock,
                                tag = "setting_video_lock_switch",
                                onCheckedChange = { v -> onUpdatePrefs { it.copy(videoOnlySafetyLock = v) } }
                            )
                            SettingSwitchRow(
                                title = "Confirmation volontaire d'armement",
                                subtitle = "Exige une boîte de dialogue avant le décollage.",
                                checked = prefs.requireArmingConfirmation,
                                tag = "setting_arming_confirm_switch",
                                onCheckedChange = { v -> onUpdatePrefs { it.copy(requireArmingConfirmation = v) } }
                            )
                            Text("Comportement en perte de liaison Wi-Fi", style = MaterialTheme.typography.titleSmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                LossOfLinkBehavior.entries.forEach { beh ->
                                    val supported = beh != LossOfLinkBehavior.AUTO_LAND || (activeProfile?.capabilities?.supportsTakeOffLand == true)
                                    FilterChip(
                                        selected = prefs.lossOfLinkBehavior == beh,
                                        enabled = supported,
                                        onClick = { onUpdatePrefs { it.copy(lossOfLinkBehavior = beh) } },
                                        label = { Text(if (supported) beh.labelFr else "${beh.labelFr} (Indisponible)") },
                                        modifier = Modifier.testTag("loss_behavior_${beh.name}")
                                    )
                                }
                            }
                            if (activeProfile != null) {
                                SettingSwitchRow(
                                    title = "Protocole commandes vérifié (${activeProfile.family.displayName})",
                                    subtitle = "À activer uniquement après test hélices retirées.",
                                    checked = activeProfile.isProtocolVerified,
                                    tag = "setting_protocol_verified_switch",
                                    onCheckedChange = { verified ->
                                        onUpdateProfile(
                                            activeProfile.copy(
                                                isProtocolVerified = verified,
                                                capabilities = activeProfile.capabilities.copy(supportsFlightControl = verified)
                                            )
                                        )
                                    }
                                )
                                val c = activeProfile.capabilities
                                Text(
                                    text = "Capacités du modèle (${activeProfile.gatewayIp}:${activeProfile.controlPort})",
                                    style = MaterialTheme.typography.labelLarge
                                )
                                val capabilityItems = listOf(
                                    "Vidéo FPV" to c.supportsVideo,
                                    "Décollage/Atterrissage" to c.supportsTakeOffLand,
                                    "Arrêt d'urgence" to c.supportsEmergencyStop,
                                    "Calibration Gyro" to c.supportsGyroCalibration,
                                    "Modes Vitesse" to c.supportsSpeedModes,
                                    "Double Caméra" to c.supportsDualCamera,
                                    "Mode Headless" to c.supportsHeadless,
                                    "Flip" to c.supportsFlip
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    capabilityItems.forEach { (name, supported) ->
                                        FilterChip(
                                            selected = supported,
                                            enabled = supported,
                                            onClick = {},
                                            label = { Text(if (supported) name else "$name — indisponible") }
                                        )
                                    }
                                }
                            }
                        }

                        SettingsCategory.AI_TRACKING -> {
                            Text("Détecteur actif", style = MaterialTheme.typography.titleSmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                DetectorType.entries.forEach { det ->
                                    FilterChip(
                                        selected = prefs.activeDetector == det,
                                        onClick = { onUpdatePrefs { it.copy(activeDetector = det) } },
                                        label = { Text(det.labelFr) }
                                    )
                                }
                            }
                            Text("Mode de suivi", style = MaterialTheme.typography.titleSmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                TrackingMode.entries.forEach { mode ->
                                    FilterChip(
                                        selected = prefs.activeTrackingMode == mode,
                                        onClick = { onUpdatePrefs { it.copy(activeTrackingMode = mode) } },
                                        label = { Text(mode.labelFr) }
                                    )
                                }
                            }
                            Text("Distance cible souhaitée : ${"%.1f".format(prefs.desiredDistanceM)} m", style = TelemetryMonoStyle)
                            Slider(
                                value = prefs.desiredDistanceM,
                                onValueChange = { v -> onUpdatePrefs { it.copy(desiredDistanceM = v) } },
                                valueRange = 1.0f..8.0f
                            )
                            SettingSwitchRow(
                                title = "Reconnaissance biométrique de personnes",
                                subtitle = "Désactivée par défaut. Aucun envoi cloud.",
                                checked = prefs.personRecognitionOptIn,
                                tag = "setting_person_opt_in",
                                onCheckedChange = { v -> onUpdatePrefs { it.copy(personRecognitionOptIn = v) } }
                            )
                        }

                        SettingsCategory.PROFILES_BACKUP -> {
                            Text("Préréglages prêts à l'emploi", style = MaterialTheme.typography.titleMedium)
                            (builtInPresets + customPresets).forEach { preset ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(tokens.cornerStyle.smallDp))
                                        .background(tokens.levelControle)
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(preset.name, style = MaterialTheme.typography.titleSmall)
                                        Text(preset.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Row {
                                        FilledTonalButton(
                                            onClick = { onApplyPreset(preset) },
                                            modifier = Modifier.testTag("apply_preset_${preset.id}")
                                        ) {
                                            Text("Appliquer")
                                        }
                                        if (!preset.isBuiltIn) {
                                            IconButton(onClick = { onDeleteCustomPreset(preset.id) }) {
                                                Icon(Icons.Rounded.Delete, contentDescription = "Supprimer préréglage")
                                            }
                                        }
                                    }
                                }
                            }

                            Text("Enregistrer la configuration actuelle", style = MaterialTheme.typography.titleSmall)
                            OutlinedTextField(
                                value = presetName,
                                onValueChange = { presetName = it },
                                label = { Text("Nom du profil (ex: Vol parc soir)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("preset_name_input")
                            )
                            OutlinedTextField(
                                value = presetDesc,
                                onValueChange = { presetDesc = it },
                                label = { Text("Description courte") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                onClick = {
                                    onSaveCustomPreset(presetName, presetDesc)
                                    presetName = ""
                                    presetDesc = ""
                                },
                                enabled = presetName.isNotBlank(),
                                modifier = Modifier.testTag("save_custom_preset_button")
                            ) {
                                Text("Enregistrer le profil")
                            }

                            Text("Export / Import JSON versionné (v1)", style = MaterialTheme.typography.titleSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(
                                    onClick = {
                                        scope.launch {
                                            val exported = onExportJson()
                                            jsonTransferText = exported
                                            clipboard.setText(AnnotatedString(exported))
                                        }
                                    },
                                    modifier = Modifier.testTag("export_settings_json_button")
                                ) {
                                    Text("Exporter & Copier JSON")
                                }
                                OutlinedButton(
                                    onClick = { onImportJson(jsonTransferText) },
                                    enabled = jsonTransferText.isNotBlank(),
                                    modifier = Modifier.testTag("import_settings_json_button")
                                ) {
                                    Text("Importer JSON")
                                }
                            }
                            OutlinedTextField(
                                value = jsonTransferText,
                                onValueChange = { jsonTransferText = it },
                                label = { Text("Contenu JSON exporté / à importer") },
                                maxLines = 5,
                                modifier = Modifier.fillMaxWidth().testTag("settings_json_text_field")
                            )

                            Button(
                                onClick = onRequestResetAll,
                                colors = ButtonDefaults.buttonColors(containerColor = EmergencyCrimson, contentColor = androidx.compose.ui.graphics.Color.White),
                                modifier = Modifier.fillMaxWidth().testTag("settings_reset_all_button")
                            ) {
                                Icon(Icons.Rounded.RestartAlt, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Tout réinitialiser (${prefs.totalModifiedCount()} modifié(s))")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    tag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag)
        )
    }
}
