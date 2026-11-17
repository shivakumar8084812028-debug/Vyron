package com.example.hdr.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.hdr.gl.ToneMappingMath
import com.example.hdr.model.PlaybackPipelineStatus
import com.example.hdr.model.TelemetrySample
import com.example.hdr.model.ThermalLevel
import com.example.hdr.model.ToneMappingConfig
import com.example.hdr.model.VideoAnalysisReport
import com.example.hdr.model.VyronVisionMode
import com.example.ui.theme.CoralThermal
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldNominal
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.HdrAmber
import com.example.ui.theme.SlateElevated
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Module F: Live Input -> Output Pipeline Specification Strip.
 * Displays all 6 mandatory technical fields cleanly.
 */
@Composable
fun PipelineReadoutGrid(
    analysis: VideoAnalysisReport?,
    pipelineStatus: PlaybackPipelineStatus,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SIGNAL PIPELINE TELEMETRY",
                    style = MaterialTheme.typography.labelMedium,
                    color = ElectricCyan
                )
                StatusPill(
                    label = pipelineStatus.executionState.name.replace('_', ' '),
                    accentColor = when (pipelineStatus.executionState) {
                        com.example.hdr.model.PipelineExecutionState.LIVE_HDR_TO_SDR_ACTIVE -> EmeraldNominal
                        com.example.hdr.model.PipelineExecutionState.OFFLINE_TRANSCODING -> HdrAmber
                        com.example.hdr.model.PipelineExecutionState.UNSUPPORTED_CODEC_ERROR -> CoralThermal
                        else -> ElectricCyan
                    }
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReadoutTile(
                    label = "Input Resolution",
                    value = analysis?.let {
                        val fpsStr = if (it.frameRateFps > 0f) " @ ${it.frameRateFps.roundToInt()}fps" else ""
                        "${it.resolutionLabel}$fpsStr"
                    } ?: "No Stream",
                    modifier = Modifier.weight(1f)
                )
                ReadoutTile(
                    label = "Input HDR Format",
                    value = analysis?.hdrFormatLabel ?: "Standby",
                    accent = if (analysis?.isHdr == true) HdrAmber else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReadoutTile(
                    label = "Output Resolution",
                    value = pipelineStatus.outputResolutionLabel,
                    modifier = Modifier.weight(1f)
                )
                ReadoutTile(
                    label = "Output Color Format",
                    value = pipelineStatus.outputColorFormatLabel,
                    accent = ElectricCyan,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReadoutTile(
                    label = "Decoder Type",
                    value = buildString {
                        append(pipelineStatus.activeDecoderName)
                        if (pipelineStatus.activeDecoderName != "Pending") {
                            append(if (pipelineStatus.isHardwareDecoder) " (HW)" else " (SW)")
                        }
                    },
                    accent = if (pipelineStatus.isHardwareDecoder) EmeraldNominal else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                ReadoutTile(
                    label = "Enhancement Status",
                    value = pipelineStatus.enhancementStatusLabel,
                    accent = ElectricCyan,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ReadoutTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(SlateElevated)
            .border(1.dp, HairlineBorder.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = accent,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun StatusPill(
    label: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = accentColor.copy(alpha = 0.14f),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(accentColor)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = accentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Module F: VYRON VISION AI Mode Selector Card.
 */
@Composable
fun VisionAiModeSelector(
    selectedMode: VyronVisionMode,
    onSelectMode: (VyronVisionMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        VyronVisionMode.entries.forEach { mode ->
            val isSelected = mode == selectedMode
            val borderColor by animateColorAsState(
                targetValue = if (isSelected) ElectricCyan else HairlineBorder,
                label = "modeBorder"
            )
            val bgColor by animateColorAsState(
                targetValue = if (isSelected) ElectricCyan.copy(alpha = 0.10f) else SlateElevated,
                label = "modeBg"
            )
            val icon = when (mode) {
                VyronVisionMode.ORIGINAL_PLAYBACK -> Icons.Filled.GraphicEq
                VyronVisionMode.HDR_TO_SDR -> Icons.Filled.Tune
                VyronVisionMode.HDR_TO_SDR_SMART -> Icons.Filled.AutoAwesome
                VyronVisionMode.AUTO_ADAPTIVE -> Icons.Filled.Speed
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSelectMode(mode) }
                    .testTag("vision_mode_${mode.name.lowercase(Locale.US)}"),
                color = bgColor,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, borderColor)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) ElectricCyan.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = mode.title,
                            tint = if (isSelected) ElectricCyan else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = mode.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Surface(
                                color = if (isSelected) ElectricCyan.copy(alpha = 0.18f) else HdrAmber.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = mode.badge,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSelected) ElectricCyan else HdrAmber,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = mode.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = "Active mode",
                            tint = ElectricCyan
                        )
                    }
                }
            }
        }
    }
}

/**
 * Interactive PQ ST.2084 -> BT.2390 SDR Tone-Mapping Transfer Curve Visualizer.
 */
@Composable
fun ToneCurveVisualizer(
    config: ToneMappingConfig,
    sourcePeakNits: Float,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, HairlineBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PQ EOTF -> BT.2390 TONE CURVE",
                    style = MaterialTheme.typography.labelMedium,
                    color = HdrAmber
                )
                Text(
                    text = "Peak ${sourcePeakNits.roundToInt()} -> ${config.targetSdrPeakNits.roundToInt()} nits",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF06080D))
                    .padding(12.dp)
            ) {
                val w = size.width
                val h = size.height

                // Reference grid lines
                val gridColor = Color(0xFF1B2336)
                for (i in 1..3) {
                    val y = h * (i / 4f)
                    val x = w * (i / 4f)
                    drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                    drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                }

                // Dashed un-mapped SDR diagonal reference
                drawLine(
                    color = Color(0xFF475569),
                    start = Offset(0f, h),
                    end = Offset(w, 0f),
                    strokeWidth = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                )

                // Plot real PQ -> BT.2390 -> sRGB output curve
                val tonePath = Path()
                val steps = 64
                for (i in 0..steps) {
                    val xNorm = i.toFloat() / steps.toFloat()
                    val linearNits = ToneMappingMath.pqEotfToNits(xNorm.toDouble())
                    val mappedLinear = ToneMappingMath.mapLuminanceNitsToSdrLinear(
                        linearNits = linearNits,
                        sourcePeakNits = sourcePeakNits.toDouble(),
                        targetSdrPeakNits = config.targetSdrPeakNits.toDouble(),
                        highlightRolloff = config.highlightRolloff.toDouble(),
                        shadowLift = config.shadowLift.toDouble()
                    )
                    val sdrY = ToneMappingMath.linearToSrgbOetf(mappedLinear).toFloat()
                    val px = xNorm * w
                    val py = h - (sdrY.coerceIn(0f, 1f) * h)
                    if (i == 0) tonePath.moveTo(px, py) else tonePath.lineTo(px, py)
                }

                drawPath(
                    path = tonePath,
                    brush = Brush.horizontalGradient(listOf(ElectricCyan, HdrAmber)),
                    style = Stroke(width = 4.5f, cap = StrokeCap.Round)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("0.0 PQ (Blacks)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("0.5 PQ (~100 nits)", style = MaterialTheme.typography.labelSmall, color = ElectricCyan)
                Text("1.0 PQ (Highlights)", style = MaterialTheme.typography.labelSmall, color = HdrAmber)
            }
        }
    }
}

@Composable
fun ComparisonToggleRow(
    enabled: Boolean,
    splitFraction: Float,
    onToggle: (Boolean) -> Unit,
    onSplitChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, if (enabled) ElectricCyan.copy(alpha = 0.5f) else HairlineBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Compare,
                        contentDescription = "Original versus Enhanced comparison",
                        tint = ElectricCyan
                    )
                    Column {
                        Text(
                            text = "Original vs Enhanced Split Comparison",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Live GPU shader A/B wipe on active frame",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.testTag("comparison_switch"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = ElectricCyan
                    )
                )
            }
            if (enabled) {
                Slider(
                    value = splitFraction,
                    onValueChange = onSplitChanged,
                    valueRange = 0.08f..0.92f,
                    modifier = Modifier.testTag("comparison_split_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = ElectricCyan,
                        activeTrackColor = ElectricCyan
                    )
                )
            }
        }
    }
}

@Composable
fun TelemetryMiniBar(
    telemetry: TelemetrySample,
    modifier: Modifier = Modifier
) {
    val thermalColor = when (telemetry.thermalLevel) {
        ThermalLevel.NONE, ThermalLevel.LIGHT -> EmeraldNominal
        ThermalLevel.MODERATE -> HdrAmber
        ThermalLevel.SEVERE, ThermalLevel.CRITICAL, ThermalLevel.EMERGENCY -> CoralThermal
        ThermalLevel.UNAVAILABLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, HairlineBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MiniMetric(
            icon = Icons.Filled.Speed,
            label = "FRAME",
            value = String.format(Locale.US, "%.1fms", telemetry.frameProcessTimeMs),
            color = ElectricCyan
        )
        MiniMetric(
            icon = Icons.Filled.GraphicEq,
            label = "DROPPED",
            value = "${telemetry.droppedFramesTotal}",
            color = if (telemetry.droppedFramesTotal > 0) HdrAmber else EmeraldNominal
        )
        MiniMetric(
            icon = Icons.Filled.Thermostat,
            label = "TEMP",
            value = String.format(Locale.US, "%.1f°C", telemetry.batteryTempCelsius),
            color = thermalColor
        )
        MiniMetric(
            icon = Icons.Filled.Memory,
            label = "RAM FREE",
            value = "${telemetry.availableMemoryMb}MB",
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun MiniMetric(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    color: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(16.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Module E: Real-time Frame Processing Time & Thermal Sparkline Chart.
 */
@Composable
fun TelemetrySparklineChart(
    history: List<TelemetrySample>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, HairlineBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("REAL-TIME FRAME LATENCY & TEMP", style = MaterialTheme.typography.labelMedium, color = ElectricCyan)
                Text("${history.size}s window", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (history.size < 2) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF06080D)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Collecting live GPU shader & thermal telemetry...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF06080D))
                        .padding(10.dp)
                ) {
                    val w = size.width
                    val h = size.height
                    val maxMs = (history.maxOfOrNull { it.frameProcessTimeMs } ?: 16f).coerceAtLeast(16.6f)

                    // 16.6ms 60fps threshold line
                    val y60 = h - ((16.6f / maxMs).coerceIn(0f, 1f) * h)
                    drawLine(
                        color = EmeraldNominal.copy(alpha = 0.35f),
                        start = Offset(0f, y60),
                        end = Offset(w, y60),
                        strokeWidth = 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                    )

                    val framePath = Path()
                    val tempPath = Path()
                    val count = history.size
                    history.forEachIndexed { idx, s ->
                        val x = (idx.toFloat() / (count - 1).coerceAtLeast(1)) * w
                        val fy = h - ((s.frameProcessTimeMs / maxMs).coerceIn(0f, 1f) * h)
                        val ty = h - (((s.batteryTempCelsius - 20f) / 35f).coerceIn(0f, 1f) * h)
                        if (idx == 0) {
                            framePath.moveTo(x, fy)
                            tempPath.moveTo(x, ty)
                        } else {
                            framePath.lineTo(x, fy)
                            tempPath.lineTo(x, ty)
                        }
                    }

                    drawPath(tempPath, color = HdrAmber, style = Stroke(width = 3f, cap = StrokeCap.Round))
                    drawPath(framePath, color = ElectricCyan, style = Stroke(width = 3.5f, cap = StrokeCap.Round))
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(ElectricCyan))
                    Text("Shader Frame Time (ms)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(HdrAmber))
                    Text("Battery Thermal (°C)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
