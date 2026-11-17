package com.example.hdr.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.hdr.model.ToneMappingConfig
import com.example.hdr.model.VideoAnalysisReport
import com.example.hdr.model.VyronVisionMode
import com.example.hdr.ui.components.ComparisonToggleRow
import com.example.hdr.ui.components.StatusPill
import com.example.hdr.ui.components.ToneCurveVisualizer
import com.example.hdr.ui.components.VisionAiModeSelector
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.HdrAmber
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Module F: Dedicated VYRON VISION AI Settings & Optical Calibration Console.
 */
@Composable
fun VisionAiLabScreen(
    config: ToneMappingConfig,
    analysis: VideoAnalysisReport?,
    onSelectMode: (VyronVisionMode) -> Unit,
    onUpdateConfig: ((ToneMappingConfig) -> ToneMappingConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    val sourcePeak = analysis?.staticMetadata?.effectivePeakNits ?: 1000f

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("VYRON VISION AI", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Real-time OpenGL ES 3.0 Shader & Colorimetry Control",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StatusPill(label = config.activeQualityTier.label, accentColor = HdrAmber)
        }

        VisionAiModeSelector(
            selectedMode = config.mode,
            onSelectMode = onSelectMode
        )

        ComparisonToggleRow(
            enabled = config.comparisonEnabled,
            splitFraction = config.comparisonSplitFraction,
            onToggle = { checked -> onUpdateConfig { it.copy(comparisonEnabled = checked) } },
            onSplitChanged = { frac -> onUpdateConfig { it.copy(comparisonSplitFraction = frac) } }
        )

        ToneCurveVisualizer(
            config = config,
            sourcePeakNits = sourcePeak
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, HairlineBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "OPTICAL TONE-MAPPING CALIBRATION",
                        style = MaterialTheme.typography.labelMedium,
                        color = ElectricCyan
                    )
                    OutlinedButton(
                        onClick = {
                            onUpdateConfig {
                                ToneMappingConfig(
                                    mode = it.mode,
                                    comparisonEnabled = it.comparisonEnabled,
                                    comparisonSplitFraction = it.comparisonSplitFraction,
                                    activeQualityTier = it.activeQualityTier
                                )
                            }
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("reset_calibration_button")
                    ) {
                        Icon(Icons.Filled.RestartAlt, contentDescription = "Reset calibration")
                        Text("Reset")
                    }
                }

                CalibrationSlider(
                    label = "Target LCD Peak Brightness",
                    valueText = "${config.targetSdrPeakNits.roundToInt()} nits",
                    value = config.targetSdrPeakNits,
                    range = 180f..650f,
                    onValueChange = { v -> onUpdateConfig { it.copy(targetSdrPeakNits = v) } }
                )

                CalibrationSlider(
                    label = "Highlight Roll-off Knee (BT.2390)",
                    valueText = String.format(Locale.US, "%.2f", config.highlightRolloff),
                    value = config.highlightRolloff,
                    range = 0.2f..1.0f,
                    onValueChange = { v -> onUpdateConfig { it.copy(highlightRolloff = v) } }
                )

                CalibrationSlider(
                    label = "Shadow Detail Preservation",
                    valueText = String.format(Locale.US, "+%.0f%%", config.shadowLift * 100f),
                    value = config.shadowLift,
                    range = 0.0f..0.40f,
                    onValueChange = { v -> onUpdateConfig { it.copy(shadowLift = v) } }
                )

                CalibrationSlider(
                    label = "BT.2020 -> BT.709 Saturation Guard",
                    valueText = String.format(Locale.US, "%.2f", config.saturationGuard),
                    value = config.saturationGuard,
                    range = 0.65f..1.10f,
                    onValueChange = { v -> onUpdateConfig { it.copy(saturationGuard = v) } }
                )

                CalibrationSlider(
                    label = "Smart Contrast S-Curve",
                    valueText = String.format(Locale.US, "%.0f%%", config.smartContrast * 100f),
                    value = config.smartContrast,
                    range = 0.0f..0.50f,
                    onValueChange = { v -> onUpdateConfig { it.copy(smartContrast = v) } }
                )

                CalibrationSlider(
                    label = "720p LCD Clarity Sharpening",
                    valueText = String.format(Locale.US, "%.0f%%", config.sharpnessAmount * 100f),
                    value = config.sharpnessAmount,
                    range = 0.0f..0.45f,
                    onValueChange = { v -> onUpdateConfig { it.copy(sharpnessAmount = v) } }
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun CalibrationSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = ElectricCyan)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = ElectricCyan,
                activeTrackColor = ElectricCyan
            )
        )
    }
}
