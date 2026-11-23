package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.FlightLand
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.models.DetectionZone
import com.example.core.models.FlightState
import com.example.core.models.NormalizedBox
import com.example.core.models.NormalizedPoint
import com.example.core.models.OpticalFlowLevel
import com.example.core.models.TrackStatus
import com.example.core.models.TrackedSubject
import com.example.core.models.VideoFrame
import com.example.core.models.VisionAnalysisResult
import com.example.ui.theme.CautionAmber
import com.example.ui.theme.EmergencyCrimson
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalAeroTokens
import com.example.ui.theme.MotionPreference
import com.example.ui.theme.NominalEmerald
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TelemetryMonoStyle
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

@Composable
fun TelemetryBadge(
    label: String,
    value: String,
    icon: ImageVector? = null,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier
) {
    val tokens = LocalAeroTokens.current
    val shape = RoundedCornerShape(tokens.cornerStyle.smallDp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(tokens.levelCarte.copy(alpha = tokens.glassAlpha))
            .border(1.dp, tokens.borderSubtle, shape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = accentColor,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = "$label ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = TelemetryMonoStyle,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun FlightStatePill(
    state: FlightState,
    modifier: Modifier = Modifier
) {
    val (bg, fg, icon) = when (state) {
        FlightState.Flying -> Triple(NominalEmerald.copy(alpha = 0.22f), NominalEmerald, Icons.Rounded.Flight)
        FlightState.Ready -> Triple(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), MaterialTheme.colorScheme.primary, Icons.Rounded.CheckCircle)
        FlightState.Arming -> Triple(CautionAmber.copy(alpha = 0.22f), CautionAmber, Icons.Rounded.HourglassTop)
        FlightState.Landing -> Triple(CautionAmber.copy(alpha = 0.22f), CautionAmber, Icons.Rounded.FlightLand)
        FlightState.Emergency -> Triple(EmergencyCrimson.copy(alpha = 0.28f), EmergencyCrimson, Icons.Rounded.Warning)
        FlightState.Fault -> Triple(EmergencyCrimson.copy(alpha = 0.25f), EmergencyCrimson, Icons.Rounded.ErrorOutline)
        FlightState.Connected -> Triple(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), MaterialTheme.colorScheme.primary, Icons.Rounded.Link)
        FlightState.Disconnected -> Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, Icons.Rounded.Block)
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, fg.copy(alpha = 0.72f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = state.labelFr,
            tint = fg,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = state.labelFr,
            style = MaterialTheme.typography.labelMedium,
            color = fg
        )
    }
}

@Composable
fun FpvVideoStage(
    frame: VideoFrame?,
    visionResult: VisionAnalysisResult,
    detectionZone: DetectionZone,
    showTrajectories: Boolean,
    showLandmarks: Boolean,
    showCenterReticle: Boolean,
    opticalFlowLevel: OpticalFlowLevel,
    onDesignateTrack: (String) -> Unit,
    onLockTrack: (String) -> Unit = onDesignateTrack,
    onClearSelection: () -> Unit = {},
    manualDrawMode: Boolean = false,
    onManualBoxDrawn: (NormalizedBox) -> Unit = {},
    hudShiftDp: Offset = Offset.Zero,
    showHorizonOverlay: Boolean = true,
    showRiskOverlay: Boolean = true,
    emptyTitle: String = "En attente du flux vidéo FPV",
    emptySubtitle: String = "Connectez-vous au Wi-Fi FLOW-UFO (192.168.169.1:8800) ou activez le Banc IA local.",
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val tokens = LocalAeroTokens.current
    val shape = RoundedCornerShape(tokens.cornerStyle.mediumDp)

    Box(
        modifier = modifier
            .clip(shape)
            .background(if (tokens.isOledMode) Color.Black else Color(0xFF070A0E))
            .border(1.dp, tokens.borderStrong, shape),
        contentAlignment = Alignment.Center
    ) {
        if (frame != null) {
            val imageBitmap = remember(frame.sequenceNumber, frame.bitmap) {
                frame.bitmap.asImageBitmap()
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cw = size.width
                val ch = size.height
                val bw = imageBitmap.width.toFloat().coerceAtLeast(1f)
                val bh = imageBitmap.height.toFloat().coerceAtLeast(1f)
                val scale = minOf(cw / bw, ch / bh)
                val dw = (bw * scale).toInt()
                val dh = (bh * scale).toInt()
                val ox = ((cw - dw) / 2f).toInt()
                val oy = ((ch - dh) / 2f).toInt()
                drawImage(
                    image = imageBitmap,
                    dstOffset = IntOffset(ox, oy),
                    dstSize = IntSize(dw, dh)
                )
            }
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val step = 48.dp.toPx()
                val gridColor = if (tokens.isOledMode) Color(0xFF0E141C) else Color(0xFF14212E)
                var x = 0f
                while (x < size.width) {
                    drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                    x += step
                }
                var y = 0f
                while (y < size.height) {
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    y += step
                }
                val cx = size.width / 2f
                val cy = size.height / 2f
                drawCircle(
                    color = TelemetryCyan.copy(alpha = 0.22f),
                    radius = 44.dp.toPx(),
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.5f)
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.VideocamOff,
                    contentDescription = "Aucun flux vidéo",
                    tint = TelemetryCyan.copy(alpha = 0.7f),
                    modifier = Modifier.size(36.dp)
                )
                Text(
                    text = emptyTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFD9E0E8)
                )
                Text(
                    text = emptySubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8B98A8)
                )
            }
        }

        VisionOverlayCanvas(
            visionResult = visionResult,
            detectionZone = detectionZone,
            showTrajectories = showTrajectories,
            showLandmarks = showLandmarks,
            showCenterReticle = showCenterReticle,
            opticalFlowLevel = opticalFlowLevel,
            showHorizonOverlay = showHorizonOverlay,
            showRiskOverlay = showRiskOverlay,
            textMeasurer = textMeasurer,
            manualDrawMode = manualDrawMode,
            onDesignateTrack = onDesignateTrack,
            onLockTrack = onLockTrack,
            onClearSelection = onClearSelection,
            onManualBoxDrawn = onManualBoxDrawn,
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(hudShiftDp.x.roundToInt(), hudShiftDp.y.roundToInt()) }
        )

        val pState = visionResult.perceptionState
        if (visionResult.isSceneObstructed) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.78f))
                    .border(1.dp, CautionAmber.copy(alpha = 0.85f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = "Caméra obstruée",
                    tint = CautionAmber,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = visionResult.obstructionReasonFr ?: "Caméra obstruée — détection en pause",
                    style = MaterialTheme.typography.labelMedium,
                    color = CautionAmber
                )
            }
        } else if (pState != null) {
            if (showRiskOverlay) {
                val riskColor = when (pState.globalRisk.level.name) {
                    "HIGH" -> EmergencyCrimson
                    "MODERATE" -> CautionAmber
                    "UNKNOWN" -> Color(0xFFA78BFA)
                    else -> NominalEmerald
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.68f))
                        .border(1.dp, riskColor.copy(alpha = 0.8f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "${pState.activeObjectFramework.displayName} • RISQUE ${pState.globalRisk.level.labelFr} • Q${(pState.frameQuality.qualityScore01 * 100).toInt()}%",
                        style = TelemetryMonoStyle,
                        color = riskColor
                    )
                }
            }
            pState.activeGestureLabelFr?.let { gestureTxt ->
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.74f))
                        .border(1.dp, NominalEmerald.copy(alpha = 0.85f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "GESTE : $gestureTxt",
                        style = TelemetryMonoStyle,
                        color = NominalEmerald
                    )
                }
            }
        }
    }
}

@Composable
fun VisionOverlayCanvas(
    visionResult: VisionAnalysisResult,
    detectionZone: DetectionZone,
    showTrajectories: Boolean,
    showLandmarks: Boolean,
    showCenterReticle: Boolean,
    opticalFlowLevel: OpticalFlowLevel,
    showHorizonOverlay: Boolean = true,
    showRiskOverlay: Boolean = true,
    textMeasurer: TextMeasurer,
    manualDrawMode: Boolean = false,
    onDesignateTrack: (String) -> Unit,
    onLockTrack: (String) -> Unit,
    onClearSelection: () -> Unit,
    onManualBoxDrawn: (NormalizedBox) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val motionPref = LocalAeroTokens.current.motionPreference
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }
    val animatedBoxes = remember { mutableStateMapOf<String, NormalizedBox>() }
    val animatedLandmarks = remember { mutableStateMapOf<String, List<NormalizedPoint>>() }

    LaunchedEffect(visionResult.trackedSubjects, motionPref) {
        val subjects = visionResult.trackedSubjects
        val activeIds = subjects.mapTo(HashSet(subjects.size)) { it.trackId }
        animatedBoxes.keys.retainAll(activeIds)
        animatedLandmarks.keys.retainAll(activeIds)

        if (subjects.isEmpty()) return@LaunchedEffect

        if (motionPref == MotionPreference.OFF) {
            for (s in subjects) {
                animatedBoxes[s.trackId] = s.box
                animatedLandmarks[s.trackId] = s.landmarks
            }
            return@LaunchedEffect
        }

        val speedHz = if (motionPref == MotionPreference.REDUCED) 13f else 22f
        var prevNanos = 0L
        while (isActive) {
            var maxDelta = 0f
            withFrameNanos { frameNanos ->
                val dt = if (prevNanos == 0L) 0.016f else ((frameNanos - prevNanos) / 1_000_000_000f).coerceIn(0.002f, 0.05f)
                prevNanos = frameNanos
                val alpha = (1f - exp(-dt * speedHz)).coerceIn(0.12f, 0.92f)

                for (s in subjects) {
                    val target = s.box
                    val cur = animatedBoxes[s.trackId]
                    val nextBox = if (cur == null) {
                        target
                    } else {
                        val d = maxOf(
                            abs(target.left - cur.left),
                            abs(target.top - cur.top),
                            abs(target.right - cur.right),
                            abs(target.bottom - cur.bottom)
                        )
                        if (d > maxDelta) maxDelta = d
                        if (d < 0.0008f) {
                            target
                        } else {
                            NormalizedBox(
                                left = cur.left + (target.left - cur.left) * alpha,
                                top = cur.top + (target.top - cur.top) * alpha,
                                right = cur.right + (target.right - cur.right) * alpha,
                                bottom = cur.bottom + (target.bottom - cur.bottom) * alpha
                            )
                        }
                    }
                    animatedBoxes[s.trackId] = nextBox

                    val targetLm = s.landmarks
                    val curLm = animatedLandmarks[s.trackId]
                    animatedLandmarks[s.trackId] = if (curLm == null || curLm.size != targetLm.size) {
                        targetLm
                    } else {
                        targetLm.mapIndexed { idx, pt ->
                            val cp = curLm[idx]
                            val dl = maxOf(abs(pt.x - cp.x), abs(pt.y - cp.y))
                            if (dl > maxDelta) maxDelta = dl
                            NormalizedPoint(
                                x = cp.x + (pt.x - cp.x) * alpha,
                                y = cp.y + (pt.y - cp.y) * alpha
                            )
                        }
                    }
                }
            }
            if (maxDelta < 0.0008f) break
        }
    }

    val gestureModifier = if (manualDrawMode) {
        modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { offset ->
                    dragStart = offset
                    dragCurrent = offset
                },
                onDragEnd = {
                    val s = dragStart
                    val e = dragCurrent
                    if (s != null && e != null && size.width > 0 && size.height > 0) {
                        val l = minOf(s.x, e.x) / size.width
                        val r = maxOf(s.x, e.x) / size.width
                        val t = minOf(s.y, e.y) / size.height
                        val b = maxOf(s.y, e.y) / size.height
                        if ((r - l) > 0.04f && (b - t) > 0.04f) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onManualBoxDrawn(NormalizedBox(l, t, r, b))
                        }
                    }
                    dragStart = null
                    dragCurrent = null
                },
                onDragCancel = {
                    dragStart = null
                    dragCurrent = null
                }
            ) { change, dragAmount ->
                change.consume()
                dragCurrent = (dragCurrent ?: Offset.Zero) + dragAmount
            }
        }
    } else {
        modifier.pointerInput(visionResult.trackedSubjects) {
            detectTapGestures(
                onTap = { tapOffset ->
                    val nx = tapOffset.x / size.width.coerceAtLeast(1)
                    val ny = tapOffset.y / size.height.coerceAtLeast(1)
                    val hit = visionResult.trackedSubjects.firstOrNull { subj ->
                        nx in (subj.box.left - 0.02f)..(subj.box.right + 0.02f) &&
                            ny in (subj.box.top - 0.02f)..(subj.box.bottom + 0.02f)
                    }
                    if (hit != null) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onDesignateTrack(hit.trackId)
                    } else {
                        onClearSelection()
                    }
                },
                onLongPress = { tapOffset ->
                    val nx = tapOffset.x / size.width.coerceAtLeast(1)
                    val ny = tapOffset.y / size.height.coerceAtLeast(1)
                    val hit = visionResult.trackedSubjects.firstOrNull { subj ->
                        nx in (subj.box.left - 0.02f)..(subj.box.right + 0.02f) &&
                            ny in (subj.box.top - 0.02f)..(subj.box.bottom + 0.02f)
                    }
                    if (hit != null) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLockTrack(hit.trackId)
                    }
                }
            )
        }
    }

    Canvas(modifier = gestureModifier) {
        val w = size.width
        val h = size.height

        if (detectionZone.enabled) {
            val zl = detectionZone.left * w
            val zt = detectionZone.top * h
            val zr = detectionZone.right * w
            val zb = detectionZone.bottom * h
            drawRect(
                color = TelemetryCyan.copy(alpha = 0.65f),
                topLeft = Offset(zl, zt),
                size = Size(zr - zl, zb - zt),
                style = Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                )
            )
        }

        if (showCenterReticle) {
            val cx = w / 2f
            val cy = h / 2f
            val arm = 12.dp.toPx()
            val reticleColor = Color.White.copy(alpha = 0.48f)
            drawLine(reticleColor, Offset(cx - arm, cy), Offset(cx + arm, cy), 1.5f)
            drawLine(reticleColor, Offset(cx, cy - arm), Offset(cx, cy + arm), 1.5f)
        }

        if (opticalFlowLevel != OpticalFlowLevel.OFF && visionResult.opticalFlow.featureCount > 0) {
            val flow = visionResult.opticalFlow
            val start = Offset(w * 0.5f, h * 0.86f)
            val end = Offset(
                (start.x + flow.driftX * w * 3.5f).coerceIn(24f, w - 24f),
                (start.y + flow.driftY * h * 3.5f).coerceIn(24f, h - 24f)
            )
            val flowColor = if (opticalFlowLevel == OpticalFlowLevel.LEVEL_2_ASSISTANCE &&
                flow.advisoryLabel != "Stationnaire"
            ) CautionAmber else TelemetryCyan

            drawCircle(flowColor.copy(alpha = 0.25f), radius = 16.dp.toPx(), center = start)
            drawLine(flowColor, start, end, strokeWidth = 3.dp.toPx())
            drawCircle(flowColor, radius = 4.dp.toPx(), center = end)
        }

        // Prévisualisation d'une boîte tracée manuellement au doigt
        val ds = dragStart
        val dc = dragCurrent
        if (manualDrawMode && ds != null && dc != null) {
            val left = minOf(ds.x, dc.x)
            val top = minOf(ds.y, dc.y)
            val boxW = abs(dc.x - ds.x)
            val boxH = abs(dc.y - ds.y)
            drawRect(
                color = CautionAmber,
                topLeft = Offset(left, top),
                size = Size(boxW, boxH),
                style = Stroke(width = 2.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            )
        }

        val pState = visionResult.perceptionState
        if (showHorizonOverlay && pState != null && !visionResult.isSceneObstructed) {
            val geom = pState.spatialMap.geometry
            val hy = (geom.horizonYNorm * h).coerceIn(12f, h - 12f)
            val vx = (geom.vanishingPointX * w).coerceIn(16f, w - 16f)
            val horizonColor = TelemetryCyan.copy(alpha = 0.36f)
            drawLine(
                color = horizonColor,
                start = Offset(w * 0.08f, hy),
                end = Offset(w * 0.92f, hy),
                strokeWidth = 1.2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
            )
            drawCircle(
                color = TelemetryCyan.copy(alpha = 0.55f),
                radius = 5.dp.toPx(),
                center = Offset(vx, hy),
                style = Stroke(width = 1.5.dp.toPx())
            )
        }

        if (pState != null && pState.barcodes.isNotEmpty()) {
            for (bc in pState.barcodes) {
                if (bc.corners.size >= 4) {
                    val path = Path()
                    bc.corners.forEachIndexed { i, pt ->
                        if (i == 0) path.moveTo(pt.x * w, pt.y * h) else path.lineTo(pt.x * w, pt.y * h)
                    }
                    path.close()
                    drawPath(path, color = NominalEmerald, style = Stroke(width = 2.5.dp.toPx()))
                }
            }
        }

        for (subj: TrackedSubject in visionResult.trackedSubjects) {
            val boxColor = when {
                subj.isLocked -> CautionAmber
                subj.isDesignated -> Color.White
                subj.status == TrackStatus.LOST -> EmergencyCrimson
                showRiskOverlay && subj.riskLevelName == "HIGH" -> EmergencyCrimson
                showRiskOverlay && subj.riskLevelName == "MODERATE" -> CautionAmber
                showRiskOverlay && subj.riskLevelName == "UNKNOWN" -> Color(0xFFA78BFA)
                subj.status == TrackStatus.COASTING -> Color(0xFF94A3B8)
                subj.category == "FACE" -> TelemetryCyan
                subj.category == "PERSON" -> NominalEmerald
                else -> Color(0xFF38BDF8)
            }

            val box = animatedBoxes[subj.trackId] ?: subj.box
            val landmarks = animatedLandmarks[subj.trackId] ?: subj.landmarks

            val left = box.left * w
            val top = box.top * h
            val bw = box.width * w
            val bh = box.height * h

            val isDashed = subj.status != TrackStatus.ACTIVE || (subj.isDesignated && !subj.isLocked)
            drawRect(
                color = boxColor,
                topLeft = Offset(left, top),
                size = Size(bw, bh),
                style = Stroke(
                    width = when {
                        subj.isLocked -> 3.dp.toPx()
                        subj.isDesignated -> 2.5.dp.toPx()
                        else -> 1.8.dp.toPx()
                    },
                    pathEffect = if (isDashed) PathEffect.dashPathEffect(floatArrayOf(12f, 8f)) else null
                )
            )

            if ((subj.isLocked || subj.isDesignated) && showCenterReticle) {
                drawLine(
                    color = boxColor.copy(alpha = 0.8f),
                    start = Offset(w / 2f, h / 2f),
                    end = Offset(box.centerX * w, box.centerY * h),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                )
            }

            if (showTrajectories && subj.trajectory.size >= 2) {
                val path = Path()
                subj.trajectory.forEachIndexed { idx, pt ->
                    val px = pt.x * w
                    val py = pt.y * h
                    if (idx == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(
                    path = path,
                    color = boxColor.copy(alpha = 0.8f),
                    style = Stroke(width = 2.dp.toPx())
                )
            }

            if (showLandmarks) {
                for ((ia, ib) in subj.skeletonEdges) {
                    val pa = landmarks.getOrNull(ia) ?: continue
                    val pb = landmarks.getOrNull(ib) ?: continue
                    drawLine(
                        color = boxColor.copy(alpha = 0.85f),
                        start = Offset(pa.x * w, pa.y * h),
                        end = Offset(pb.x * w, pb.y * h),
                        strokeWidth = 2.dp.toPx()
                    )
                }
                for (lm in landmarks) {
                    drawCircle(
                        color = Color.White,
                        radius = 3.dp.toPx(),
                        center = Offset(lm.x * w, lm.y * h)
                    )
                }
            }

            val tagText = buildString {
                when {
                    subj.isLocked -> append("VERROUILLÉ ")
                    subj.isDesignated -> append("DÉSIGNÉ ")
                }
                append("${subj.trackId} [${subj.lifecycleLabelFr}] • ${subj.label} ${(subj.confidence * 100).toInt()}%")
                if (subj.distanceHintFr.isNotBlank() && subj.distanceHintFr != "—") {
                    append(" • ${subj.distanceHintFr}")
                }
                if (showRiskOverlay && (subj.riskLevelName == "HIGH" || subj.riskLevelName == "MODERATE")) {
                    append(" • !${subj.riskLevelName}")
                }
            }
            val layoutResult = textMeasurer.measure(
                text = tagText,
                style = TextStyle(
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 10.sp,
                    color = Color.Black
                )
            )
            val padH = 6.dp.toPx()
            val padV = 2.dp.toPx()
            val badgeW = layoutResult.size.width + padH * 2
            val badgeH = layoutResult.size.height + padV * 2
            val badgeTop = (top - badgeH).coerceAtLeast(2f)

            drawRect(
                color = boxColor,
                topLeft = Offset(left, badgeTop),
                size = Size(badgeW, badgeH)
            )
            drawText(
                textLayoutResult = layoutResult,
                topLeft = Offset(left + padH, badgeTop + padV)
            )
        }
    }
}
