package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import com.example.storage.prefs.AppPreferences
import com.example.ui.theme.CautionAmber
import com.example.ui.theme.CornerStyle
import com.example.ui.theme.LocalAeroTokens
import com.example.ui.theme.NominalEmerald
import com.example.ui.theme.TelemetryMonoStyle
import com.example.ui.theme.ThemeMode
import com.example.ui.theme.computeContrastRatio
import com.example.ui.theme.parseHexColorOrNull

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeEditorPanel(
    prefs: AppPreferences,
    onUpdatePrefs: ((AppPreferences) -> AppPreferences) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalAeroTokens.current
    var hexDraft by remember(prefs.customAccentHex) {
        mutableStateOf(prefs.customAccentHex.ifBlank { "#00E5FF" })
    }
    val parsedCustom = parseHexColorOrNull(prefs.customAccentHex)
    val effectiveAccent = parsedCustom ?: MaterialTheme.colorScheme.primary
    val contrastOnBg = computeContrastRatio(effectiveAccent, MaterialTheme.colorScheme.surface)
    val contrastOk = contrastOnBg >= 4.5f

    val accentSwatches = remember {
        listOf(
            "#00E5FF" to "Cyan FPV",
            "#00E676" to "Émeraude",
            "#38BDF8" to "Azur",
            "#FFB300" to "Ambre",
            "#A78BFA" to "Indigo",
            "#00687A" to "Nuit Jour"
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Aperçu en direct : miniature Cockpit + Carte
        Text("Aperçu en direct (Cockpit & Carte)", style = MaterialTheme.typography.titleSmall)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(prefs.cornerStyle.largeDp))
                .background(tokens.levelFond)
                .border(1.dp, tokens.borderStrong, RoundedCornerShape(prefs.cornerStyle.largeDp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Miniature barre HUD verre
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(prefs.cornerStyle.mediumDp))
                    .background(tokens.levelCarte.copy(alpha = prefs.glassOpacity))
                    .border(1.dp, tokens.borderSubtle, RoundedCornerShape(prefs.cornerStyle.mediumDp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("FPV • 29.8 FPS", style = TelemetryMonoStyle, color = effectiveAccent)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(effectiveAccent.copy(alpha = 0.2f))
                        .border(1.dp, effectiveAccent, RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(prefs.themeMode.labelFr, style = MaterialTheme.typography.labelSmall, color = effectiveAccent)
                }
            }
            // Miniature carte
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(prefs.cornerStyle.mediumDp))
                    .background(tokens.levelCarte)
                    .border(1.dp, tokens.borderSubtle, RoundedCornerShape(prefs.cornerStyle.mediumDp))
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("FLOW-UFO / BL60x", style = MaterialTheme.typography.titleSmall)
                    Text("Opacité ${(prefs.glassOpacity * 100).toInt()} % • Contraste ${"%.1f".format(contrastOnBg)}:1", style = TelemetryMonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(prefs.cornerStyle.smallDp))
                        .background(effectiveAccent)
                )
            }
        }

        // Sélecteur de mode / préréglage de base
        Text("Mode de thème", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = prefs.themeMode == mode,
                    onClick = { onUpdatePrefs { it.copy(themeMode = mode) } },
                    label = { Text(mode.labelFr) },
                    modifier = Modifier.testTag("theme_chip_${mode.name}")
                )
            }
        }

        // Opacité des surfaces "verre" (repli semi-opaque compatible Galaxy S9 Android 10)
        Text(
            text = "Opacité des panneaux HUD : ${(prefs.glassOpacity * 100).toInt()} %",
            style = TelemetryMonoStyle
        )
        Slider(
            value = prefs.glassOpacity,
            onValueChange = { v -> onUpdatePrefs { it.copy(glassOpacity = v.coerceIn(0.4f, 0.96f)) } },
            valueRange = 0.40f..0.96f,
            modifier = Modifier.testTag("theme_glass_opacity_slider")
        )

        // Rayons de coins (3 niveaux maximum)
        Text("Arrondis d'interface", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CornerStyle.entries.forEach { cs ->
                FilterChip(
                    selected = prefs.cornerStyle == cs,
                    onClick = { onUpdatePrefs { it.copy(cornerStyle = cs) } },
                    label = { Text(cs.labelFr) },
                    modifier = Modifier.testTag("corner_chip_${cs.name}")
                )
            }
        }

        // Taille du texte
        Text(
            text = "Échelle typographique : ${(prefs.textScale * 100).toInt()} %",
            style = TelemetryMonoStyle
        )
        Slider(
            value = prefs.textScale,
            onValueChange = { v -> onUpdatePrefs { it.copy(textScale = v.coerceIn(0.90f, 1.25f)) } },
            valueRange = 0.90f..1.25f,
            modifier = Modifier.testTag("theme_text_scale_slider")
        )

        // Couleur d'accentuation + Vérification contraste WCAG 4.5:1
        Text("Couleur d'accentuation & Contraste (4.5:1)", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            accentSwatches.forEach { (hex, name) ->
                val swatchColor = parseHexColorOrNull(hex) ?: Color.Cyan
                val selected = prefs.customAccentHex.equals(hex, ignoreCase = true)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(swatchColor)
                            .border(if (selected) 2.5.dp else 1.dp, Color.White, CircleShape)
                            .clickable {
                                hexDraft = hex
                                onUpdatePrefs { it.copy(customAccentHex = hex) }
                            }
                            .testTag("accent_swatch_$hex"),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) {
                            Icon(Icons.Rounded.Check, contentDescription = name, tint = Color.Black, modifier = Modifier.size(18.dp))
                        }
                    }
                    Text(name, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = hexDraft,
                onValueChange = { raw ->
                    hexDraft = raw
                    if (parseHexColorOrNull(raw) != null) {
                        onUpdatePrefs { it.copy(customAccentHex = raw.trim().uppercase()) }
                    }
                },
                label = { Text("Code Hex (#RRGGBB)") },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("theme_hex_input")
            )
            IconButton(
                onClick = {
                    hexDraft = ""
                    onUpdatePrefs { it.copy(customAccentHex = "") }
                },
                modifier = Modifier.size(48.dp).testTag("theme_reset_accent")
            ) {
                Icon(Icons.Rounded.RestartAlt, contentDescription = "Réinitialiser couleur d'accent")
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(prefs.cornerStyle.smallDp))
                .background((if (contrastOk) NominalEmerald else CautionAmber).copy(alpha = 0.14f))
                .border(1.dp, if (contrastOk) NominalEmerald else CautionAmber, RoundedCornerShape(prefs.cornerStyle.smallDp))
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (contrastOk) Icons.Rounded.Contrast else Icons.Rounded.Warning,
                contentDescription = null,
                tint = if (contrastOk) NominalEmerald else CautionAmber,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (contrastOk) {
                    "Contraste mesuré : ${"%.2f".format(contrastOnBg)}:1 (conforme WCAG ≥ 4.5:1)"
                } else {
                    "Contraste faible (${"%.2f".format(contrastOnBg)}:1 < 4.5:1) : lisibilité réduite sur cet arrière-plan."
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
