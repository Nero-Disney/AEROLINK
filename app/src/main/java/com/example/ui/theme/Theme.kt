package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

enum class ThemeMode(val labelFr: String) {
    DARK_COCKPIT("Cockpit Sombre"),
    OLED("Mode OLED (AMOLED)"),
    DAYLIGHT("Jour / Extérieur"),
    SYSTEM("Système")
}

enum class CornerStyle(
    val labelFr: String,
    val smallDp: Dp,
    val mediumDp: Dp,
    val largeDp: Dp
) {
    COMPACT("Net (6 dp)", 6.dp, 10.dp, 14.dp),
    BALANCED("Équilibré (10 dp)", 8.dp, 14.dp, 20.dp),
    ROUNDED("Arrondi (14 dp)", 12.dp, 18.dp, 24.dp)
}

enum class MotionPreference(val labelFr: String) {
    NORMAL("Normales"),
    REDUCED("Réduites"),
    OFF("Désactivées")
}

enum class OrientationPreference(val labelFr: String) {
    LANDSCAPE("Paysage (Vol recommandé)"),
    PORTRAIT("Portrait"),
    AUTO_SENSOR("Capteur auto"),
    SYSTEM("Système")
}

enum class LossOfLinkBehavior(val labelFr: String) {
    NEUTRALIZE_HOVER("Neutraliser sticks (Stationnaire)"),
    AUTO_LAND("Atterrissage auto si supporté"),
    EMERGENCY_STOP("Coupure d'urgence immédiate")
}

@Immutable
data class AeroVisualTokens(
    val levelFond: Color = CockpitObsidian,
    val levelCarte: Color = CockpitSlate,
    val levelControle: Color = CockpitSurface,
    val levelFlottant: Color = CockpitSurfaceElevated,
    val levelDialogue: Color = CockpitDialogue,
    val borderSubtle: Color = CockpitBorder.copy(alpha = 0.65f),
    val borderStrong: Color = CockpitBorder,
    val glassAlpha: Float = 0.84f,
    val isOledMode: Boolean = false,
    val isDaylight: Boolean = false,
    val motionPreference: MotionPreference = MotionPreference.NORMAL,
    val textScale: Float = 1.0f,
    val cornerStyle: CornerStyle = CornerStyle.BALANCED
)

val LocalAeroTokens = staticCompositionLocalOf { AeroVisualTokens() }

fun computeContrastRatio(foreground: Color, background: Color): Float {
    val l1 = foreground.luminance()
    val l2 = background.luminance()
    val brighter = max(l1, l2)
    val darker = min(l1, l2)
    return (brighter + 0.05f) / (darker + 0.05f)
}

fun parseHexColorOrNull(raw: String?): Color? {
    if (raw.isNullOrBlank()) return null
    val cleaned = raw.trim().removePrefix("#")
    if (cleaned.length != 6 && cleaned.length != 8) return null
    return runCatching {
        val value = cleaned.toLong(16)
        val argb = if (cleaned.length == 6) (0xFF000000L or value) else value
        Color(argb.toInt())
    }.getOrNull()
}

fun colorToHex(color: Color): String {
    val rgb = color.toArgb() and 0x00FFFFFF
    return "#%06X".format(rgb)
}

private val AeroDarkColorScheme = darkColorScheme(
    primary = TelemetryCyan,
    onPrimary = CockpitObsidian,
    primaryContainer = TelemetryCyanContainer,
    onPrimaryContainer = TelemetryCyan,
    secondary = NominalEmerald,
    onSecondary = CockpitObsidian,
    secondaryContainer = NominalEmeraldContainer,
    onSecondaryContainer = NominalEmerald,
    tertiary = CautionAmber,
    onTertiary = CockpitObsidian,
    tertiaryContainer = CautionAmberContainer,
    onTertiaryContainer = CautionAmber,
    error = EmergencyCrimson,
    onError = Color.White,
    errorContainer = EmergencyCrimsonContainer,
    onErrorContainer = EmergencyCrimson,
    background = CockpitObsidian,
    onBackground = TextPrimaryDark,
    surface = CockpitSlate,
    onSurface = TextPrimaryDark,
    surfaceVariant = CockpitSurface,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerLowest = CockpitObsidian,
    surfaceContainerLow = CockpitSlate,
    surfaceContainer = CockpitSurface,
    surfaceContainerHigh = CockpitSurfaceElevated,
    outline = CockpitBorder,
    outlineVariant = Color(0xFF1F2A38)
)

private val AeroOledColorScheme = darkColorScheme(
    primary = TelemetryCyan,
    onPrimary = OledPureBlack,
    primaryContainer = TelemetryCyanContainer,
    onPrimaryContainer = TelemetryCyan,
    secondary = NominalEmerald,
    onSecondary = OledPureBlack,
    secondaryContainer = NominalEmeraldContainer,
    onSecondaryContainer = NominalEmerald,
    tertiary = CautionAmber,
    onTertiary = OledPureBlack,
    tertiaryContainer = CautionAmberContainer,
    onTertiaryContainer = CautionAmber,
    error = EmergencyCrimson,
    onError = Color.White,
    errorContainer = EmergencyCrimsonContainer,
    onErrorContainer = EmergencyCrimson,
    background = OledPureBlack,
    onBackground = OledOffWhiteText,
    surface = OledSmearSafeSlate,
    onSurface = OledOffWhiteText,
    surfaceVariant = OledSmearSafeSurface,
    onSurfaceVariant = OledMutedText,
    surfaceContainerLowest = OledPureBlack,
    surfaceContainerLow = OledSmearSafeSlate,
    surfaceContainer = OledSmearSafeSurface,
    surfaceContainerHigh = OledSmearSafeElevated,
    outline = OledBorder,
    outlineVariant = Color(0xFF1D232C)
)

private val AeroLightColorScheme = lightColorScheme(
    primary = DaylightCyan,
    onPrimary = Color.White,
    primaryContainer = DaylightCyanContainer,
    onPrimaryContainer = DaylightCyan,
    secondary = DaylightEmerald,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = DaylightEmerald,
    tertiary = DaylightAmber,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = DaylightAmber,
    error = DaylightCrimson,
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = DaylightCrimson,
    background = DaylightBackground,
    onBackground = TextPrimaryLight,
    surface = DaylightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = DaylightSurfaceVariant,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainerLowest = DaylightSurface,
    surfaceContainerLow = DaylightBackground,
    surfaceContainer = DaylightSurfaceVariant,
    surfaceContainerHigh = DaylightElevated,
    outline = DaylightBorder,
    outlineVariant = Color(0xFFE2E8F0)
)

val AeroShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private fun scaledTypography(base: Typography, scale: Float): Typography {
    if (scale in 0.98f..1.02f) return base
    return Typography(
        displayLarge = base.displayLarge.copy(fontSize = base.displayLarge.fontSize * scale),
        headlineMedium = base.headlineMedium.copy(fontSize = base.headlineMedium.fontSize * scale),
        titleLarge = base.titleLarge.copy(fontSize = base.titleLarge.fontSize * scale),
        titleMedium = base.titleMedium.copy(fontSize = base.titleMedium.fontSize * scale),
        titleSmall = base.titleSmall.copy(fontSize = base.titleSmall.fontSize * scale),
        bodyLarge = base.bodyLarge.copy(fontSize = base.bodyLarge.fontSize * scale),
        bodyMedium = base.bodyMedium.copy(fontSize = base.bodyMedium.fontSize * scale),
        bodySmall = base.bodySmall.copy(fontSize = base.bodySmall.fontSize * scale),
        labelLarge = base.labelLarge.copy(fontSize = base.labelLarge.fontSize * scale),
        labelMedium = base.labelMedium.copy(fontSize = base.labelMedium.fontSize * scale),
        labelSmall = base.labelSmall.copy(fontSize = base.labelSmall.fontSize * scale)
    )
}

@Composable
fun AeroLinkTheme(
    themeMode: ThemeMode = ThemeMode.DARK_COCKPIT,
    customAccentHex: String = "",
    glassOpacity: Float = 0.84f,
    cornerStyle: CornerStyle = CornerStyle.BALANCED,
    textScale: Float = 1.0f,
    motionPreference: MotionPreference = MotionPreference.NORMAL,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val effectiveMode = when (themeMode) {
        ThemeMode.SYSTEM -> if (systemDark) ThemeMode.DARK_COCKPIT else ThemeMode.DAYLIGHT
        else -> themeMode
    }
    val useDark = effectiveMode != ThemeMode.DAYLIGHT
    val isOled = effectiveMode == ThemeMode.OLED

    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !isOled -> {
            val context = LocalContext.current
            if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isOled -> AeroOledColorScheme
        useDark -> AeroDarkColorScheme
        else -> AeroLightColorScheme
    }

    val customAccent = parseHexColorOrNull(customAccentHex)
    val colorScheme = if (customAccent != null) {
        val onCustom = if (customAccent.luminance() > 0.45f) Color(0xFF0A0F14) else Color.White
        baseScheme.copy(
            primary = customAccent,
            onPrimary = onCustom,
            primaryContainer = customAccent.copy(alpha = 0.18f),
            onPrimaryContainer = customAccent
        )
    } else {
        baseScheme
    }

    val tokens = when (effectiveMode) {
        ThemeMode.OLED -> AeroVisualTokens(
            levelFond = OledPureBlack,
            levelCarte = OledSmearSafeSlate,
            levelControle = OledSmearSafeSurface,
            levelFlottant = OledSmearSafeElevated,
            levelDialogue = OledDialogueSurface,
            borderSubtle = OledBorder.copy(alpha = 0.7f),
            borderStrong = OledBorder,
            glassAlpha = glassOpacity.coerceIn(0.4f, 0.96f),
            isOledMode = true,
            isDaylight = false,
            motionPreference = motionPreference,
            textScale = textScale,
            cornerStyle = cornerStyle
        )
        ThemeMode.DAYLIGHT -> AeroVisualTokens(
            levelFond = DaylightBackground,
            levelCarte = DaylightSurface,
            levelControle = DaylightSurfaceVariant,
            levelFlottant = DaylightElevated,
            levelDialogue = DaylightDialogue,
            borderSubtle = DaylightBorder.copy(alpha = 0.75f),
            borderStrong = DaylightBorder,
            glassAlpha = glassOpacity.coerceIn(0.55f, 0.98f),
            isOledMode = false,
            isDaylight = true,
            motionPreference = motionPreference,
            textScale = textScale,
            cornerStyle = cornerStyle
        )
        else -> AeroVisualTokens(
            levelFond = CockpitObsidian,
            levelCarte = CockpitSlate,
            levelControle = CockpitSurface,
            levelFlottant = CockpitSurfaceElevated,
            levelDialogue = CockpitDialogue,
            borderSubtle = CockpitBorder.copy(alpha = 0.65f),
            borderStrong = CockpitBorder,
            glassAlpha = glassOpacity.coerceIn(0.4f, 0.96f),
            isOledMode = false,
            isDaylight = false,
            motionPreference = motionPreference,
            textScale = textScale,
            cornerStyle = cornerStyle
        )
    }

    val shapes = Shapes(
        small = RoundedCornerShape(cornerStyle.smallDp),
        medium = RoundedCornerShape(cornerStyle.mediumDp),
        large = RoundedCornerShape(cornerStyle.largeDp),
        extraLarge = RoundedCornerShape(cornerStyle.largeDp + 6.dp)
    )

    CompositionLocalProvider(LocalAeroTokens provides tokens) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = scaledTypography(AeroTypography, textScale.coerceIn(0.88f, 1.25f)),
            shapes = shapes,
            content = content
        )
    }
}
