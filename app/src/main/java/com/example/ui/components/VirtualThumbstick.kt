package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LocalAeroTokens
import com.example.ui.theme.MotionPreference
import com.example.ui.theme.TelemetryMonoStyle
import kotlin.math.hypot

@Composable
fun VirtualThumbstick(
    title: String,
    horizontalLabel: String,
    verticalLabel: String,
    enabled: Boolean,
    hapticsEnabled: Boolean = true,
    sizeDp: Dp = 144.dp,
    testTagName: String = "thumbstick",
    onMove: (x: Float, y: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalAeroTokens.current
    val haptic = LocalHapticFeedback.current
    var rawKnobOffset by remember { mutableStateOf(Offset.Zero) }
    var isDragging by remember { mutableStateOf(false) }
    var passedHalfway by remember { mutableStateOf(false) }

    val animatedOffset by animateOffsetAsState(
        targetValue = rawKnobOffset,
        animationSpec = if (isDragging || tokens.motionPreference != MotionPreference.NORMAL) {
            spring(stiffness = Spring.StiffnessHigh)
        } else {
            spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow)
        },
        label = "stick_spring"
    )
    val displayedOffset = if (isDragging || tokens.motionPreference == MotionPreference.OFF) rawKnobOffset else animatedOffset

    val primaryColor = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    }
    val ringColor = tokens.borderStrong.copy(alpha = if (enabled) 0.9f else 0.35f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        // Zone tactile élargie (+16 dp autour du stick) pour tolérer les mouvements rapides du pouce
        Box(
            modifier = Modifier
                .size(sizeDp + 16.dp)
                .testTag(testTagName)
                .semantics { contentDescription = "Joystick $title" }
                .pointerInput(enabled, hapticsEnabled) {
                    if (!enabled) {
                        rawKnobOffset = Offset.Zero
                        isDragging = false
                        return@pointerInput
                    }
                    val maxRadius = (minOf(size.width, size.height) / 2f) * 0.68f
                    detectDragGestures(
                        onDragStart = {
                            isDragging = true
                            passedHalfway = false
                            if (hapticsEnabled) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        },
                        onDragEnd = {
                            isDragging = false
                            rawKnobOffset = Offset.Zero
                            onMove(0f, 0f)
                            if (hapticsEnabled) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        },
                        onDragCancel = {
                            isDragging = false
                            rawKnobOffset = Offset.Zero
                            onMove(0f, 0f)
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        val candidate = rawKnobOffset + dragAmount
                        val dist = hypot(candidate.x, candidate.y)
                        val clamped = if (dist > maxRadius && dist > 0f) {
                            candidate * (maxRadius / dist)
                        } else candidate
                        rawKnobOffset = clamped

                        val normMag = if (maxRadius > 0f) (hypot(clamped.x, clamped.y) / maxRadius) else 0f
                        if (!passedHalfway && normMag > 0.65f) {
                            passedHalfway = true
                            if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        } else if (passedHalfway && normMag < 0.45f) {
                            passedHalfway = false
                        }

                        val nx = (clamped.x / maxRadius).coerceIn(-1f, 1f)
                        val ny = (-clamped.y / maxRadius).coerceIn(-1f, 1f)
                        onMove(nx, ny)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(sizeDp).fillMaxSize()) {
                val r = (minOf(size.width, size.height) / 2f) - 4.dp.toPx()
                val center = Offset(size.width / 2f, size.height / 2f)

                if (!tokens.isOledMode) {
                    drawCircle(
                        color = tokens.levelFond.copy(alpha = tokens.glassAlpha * 0.85f),
                        radius = r,
                        center = center
                    )
                } else {
                    // Mode OLED : contours fins uniquement pour préserver la netteté et éviter la traînée AMOLED
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.45f),
                        radius = r,
                        center = center
                    )
                }

                drawCircle(
                    color = ringColor,
                    radius = r,
                    center = center,
                    style = Stroke(width = if (tokens.isOledMode) 1.5.dp.toPx() else 2.dp.toPx())
                )
                drawCircle(
                    color = ringColor.copy(alpha = 0.42f),
                    radius = r * 0.55f,
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )
                drawLine(
                    color = ringColor.copy(alpha = 0.35f),
                    start = Offset(center.x - r, center.y),
                    end = Offset(center.x + r, center.y),
                    strokeWidth = 1f
                )
                drawLine(
                    color = ringColor.copy(alpha = 0.35f),
                    start = Offset(center.x, center.y - r),
                    end = Offset(center.x, center.y + r),
                    strokeWidth = 1f
                )

                val knobCenter = center + displayedOffset
                if (tokens.isOledMode) {
                    drawCircle(
                        color = primaryColor,
                        radius = 18.dp.toPx(),
                        center = knobCenter,
                        style = Stroke(width = 2.5.dp.toPx())
                    )
                    drawCircle(
                        color = primaryColor.copy(alpha = 0.35f),
                        radius = 7.dp.toPx(),
                        center = knobCenter
                    )
                } else {
                    drawCircle(
                        color = primaryColor.copy(alpha = 0.24f),
                        radius = 24.dp.toPx(),
                        center = knobCenter
                    )
                    drawCircle(
                        color = primaryColor,
                        radius = 15.dp.toPx(),
                        center = knobCenter
                    )
                }
            }
        }
        Text(
            text = "$title ($verticalLabel / $horizontalLabel)",
            style = TelemetryMonoStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
