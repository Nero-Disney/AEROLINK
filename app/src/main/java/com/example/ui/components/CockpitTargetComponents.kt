package com.example.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.models.DistanceConfidence
import com.example.core.models.TrackedSubject
import com.example.storage.prefs.AppPreferences
import com.example.ui.theme.CautionAmber
import com.example.ui.theme.LocalAeroTokens
import com.example.ui.theme.NominalEmerald
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TelemetryMonoStyle
import kotlin.math.abs

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TargetSelectionStrip(
    subjects: List<TrackedSubject>,
    ambiguousCandidates: List<TrackedSubject>,
    manualDrawMode: Boolean,
    prefs: AppPreferences,
    onDesignateTrack: (String) -> Unit,
    onLockTrack: (String) -> Unit,
    onClearSelection: () -> Unit,
    onSelectNearest: () -> Unit,
    onSelectCenter: () -> Unit,
    onToggleManualDraw: () -> Unit,
    onResolveAmbiguous: (String) -> Unit,
    onChangeDesiredDistance: (Float) -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val tokens = LocalAeroTokens.current
    val haptic = LocalHapticFeedback.current
    val activeTarget = subjects.firstOrNull { it.isLocked } ?: subjects.firstOrNull { it.isDesignated }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(tokens.cornerStyle.mediumDp))
            .background(tokens.levelCarte.copy(alpha = tokens.glassAlpha))
            .border(1.dp, tokens.borderSubtle, RoundedCornerShape(tokens.cornerStyle.mediumDp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Raccourcis de sélection rapides (jamais de verrouillage automatique sans action pilote)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = false,
                onClick = onSelectNearest,
                enabled = subjects.isNotEmpty(),
                label = { Text("Plus proche") },
                leadingIcon = { Icon(Icons.Rounded.NearMe, contentDescription = null, modifier = Modifier.size(16.dp)) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("target_shortcut_nearest")
            )
            FilterChip(
                selected = false,
                onClick = onSelectCenter,
                enabled = subjects.isNotEmpty(),
                label = { Text("Au centre") },
                leadingIcon = { Icon(Icons.Rounded.CenterFocusStrong, contentDescription = null, modifier = Modifier.size(16.dp)) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("target_shortcut_center")
            )
            FilterChip(
                selected = manualDrawMode,
                onClick = onToggleManualDraw,
                label = { Text(if (manualDrawMode) "Tracer : ACTIF" else "Boîte manuelle") },
                leadingIcon = { Icon(Icons.Rounded.CropFree, contentDescription = null, modifier = Modifier.size(16.dp)) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("target_manual_draw_toggle")
            )
            if (activeTarget != null) {
                FilterChip(
                    selected = false,
                    onClick = onClearSelection,
                    label = { Text("Libérer") },
                    leadingIcon = { Icon(Icons.Rounded.Clear, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("target_clear_selection")
                )
            }
        }

        // Barre horizontale de vignettes cibles : appui = désigner, appui long = verrouiller
        if (subjects.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                subjects.forEach { subj ->
                    val borderColor = when {
                        subj.isLocked -> CautionAmber
                        subj.isDesignated -> MaterialTheme.colorScheme.primary
                        else -> tokens.borderSubtle
                    }
                    Row(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(tokens.cornerStyle.smallDp))
                            .background(tokens.levelControle.copy(alpha = tokens.glassAlpha))
                            .border(if (subj.isLocked || subj.isDesignated) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(tokens.cornerStyle.smallDp))
                            .combinedClickable(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onDesignateTrack(subj.trackId)
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onLockTrack(subj.trackId)
                                }
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("target_chip_${subj.trackId}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (subj.isLocked) Icons.Rounded.Lock else Icons.Rounded.Radar,
                            contentDescription = subj.selectionStage.labelFr,
                            tint = borderColor,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Column {
                            Text(
                                text = "${subj.trackId} • ${subj.label}",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                text = "${subj.selectionStage.labelFr} • ${subj.distanceHintFr}",
                                style = TelemetryMonoStyle,
                                color = if (subj.isLocked) CautionAmber else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            if (!compact) {
                Text(
                    text = "Astuce : Appui simple = désigner • Appui long = verrouiller • Vide = annuler",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Désambiguïsation si plusieurs candidats apparaissent après masquage
        if (ambiguousCandidates.size >= 2) {
            Card(
                colors = CardDefaults.cardColors(containerColor = CautionAmber.copy(alpha = 0.14f)),
                shape = RoundedCornerShape(tokens.cornerStyle.smallDp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, CautionAmber, RoundedCornerShape(tokens.cornerStyle.smallDp))
            ) {
                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Cible perdue puis plusieurs pistes possibles : choisissez celle à verrouiller",
                        style = MaterialTheme.typography.labelMedium,
                        color = CautionAmber
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ambiguousCandidates.forEach { cand ->
                            OutlinedButton(
                                onClick = { onResolveAmbiguous(cand.trackId) },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = CautionAmber),
                                modifier = Modifier.testTag("disambiguate_${cand.trackId}")
                            ) {
                                Text("Choisir ${cand.trackId}")
                            }
                        }
                    }
                }
            }
        }

        // Jauge consultative de distance cible (ne pilote jamais le drone)
        if (!compact && activeTarget != null) {
            DistanceAdvisoryGauge(
                subject = activeTarget,
                desiredDistanceM = prefs.desiredDistanceM,
                onChangeDesiredDistance = onChangeDesiredDistance
            )
        }
    }
}

@Composable
fun DistanceAdvisoryGauge(
    subject: TrackedSubject,
    desiredDistanceM: Float,
    onChangeDesiredDistance: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val est = subject.estimatedDistanceM
    val advice: Pair<String, Color> = when {
        est == null && subject.distanceConfidence == DistanceConfidence.UNAVAILABLE ->
            "Distance : — (cible perdue)" to MaterialTheme.colorScheme.onSurfaceVariant
        est == null ->
            "Taille apparente : ${subject.distanceHintFr} (calibrer focale dans Centre IA)" to TelemetryCyan
        est > desiredDistanceM + 0.35f ->
            "Trop loin (${subject.distanceHintFr} vs ${"%.1f".format(desiredDistanceM)} m) → Approcher" to CautionAmber
        est < (desiredDistanceM - 0.35f).coerceAtLeast(0.4f) ->
            "Trop proche (${subject.distanceHintFr} vs ${"%.1f".format(desiredDistanceM)} m) → Reculer" to CautionAmber
        else ->
            "Distance nominale (${subject.distanceHintFr})" to NominalEmerald
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = advice.first,
                style = MaterialTheme.typography.labelMedium,
                color = advice.second
            )
            Text(
                text = "Cible ${"%.1f".format(desiredDistanceM)} m",
                style = TelemetryMonoStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (est != null) {
            val deltaRatio = ((est - desiredDistanceM) / desiredDistanceM.coerceAtLeast(0.5f)).coerceIn(-1f, 1f)
            val progress = ((deltaRatio + 1f) / 2f).coerceIn(0f, 1f)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Reculer", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(
                    progress = { progress },
                    color = if (abs(est - desiredDistanceM) <= 0.35f) NominalEmerald else CautionAmber,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Rounded.ArrowForward, contentDescription = "Approcher", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Slider(
            value = desiredDistanceM,
            onValueChange = onChangeDesiredDistance,
            valueRange = 1.0f..8.0f,
            modifier = Modifier.testTag("desired_distance_slider")
        )
    }
}
