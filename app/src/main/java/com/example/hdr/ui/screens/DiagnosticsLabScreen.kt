package com.example.hdr.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.hdr.benchmark.ActiveBenchmarkState
import com.example.hdr.model.BenchmarkPreset
import com.example.hdr.model.BenchmarkResult
import com.example.hdr.model.CodecProbeEntry
import com.example.hdr.model.DecoderCapabilityReport
import com.example.hdr.model.TelemetrySample
import com.example.hdr.model.VideoAnalysisReport
import com.example.hdr.ui.components.StatusPill
import com.example.hdr.ui.components.TelemetrySparklineChart
import com.example.ui.theme.CoralThermal
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldNominal
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.HdrAmber
import com.example.ui.theme.SlateElevated
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Modules A, B, E & Benchmark Suite Diagnostics Console.
 */
@Composable
fun DiagnosticsLabScreen(
    analysis: VideoAnalysisReport?,
    capabilities: DecoderCapabilityReport?,
    isProbingHardware: Boolean,
    telemetry: TelemetrySample,
    telemetryHistory: List<TelemetrySample>,
    activeBenchmark: ActiveBenchmarkState?,
    completedBenchmarks: List<BenchmarkResult>,
    onReprobeHardware: () -> Unit,
    onStartBenchmark: (BenchmarkPreset) -> Unit,
    onStopBenchmark: () -> Unit,
    onShareBenchmarkJson: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedPreset by remember { mutableStateOf(BenchmarkPreset.UHD_HDR10_24) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("HARDWARE & THERMAL LAB", style = MaterialTheme.typography.headlineMedium)
        Text(
            "MediaCodecList Hardware Probing, Stream Colorimetry & Thermal Telemetry",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // MODULE E: Live Telemetry & Sparkline
        TelemetrySparklineChart(history = telemetryHistory)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, HairlineBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("MODULE E: ADAPTIVE PERFORMANCE", style = MaterialTheme.typography.labelMedium, color = ElectricCyan)
                    StatusPill(label = telemetry.thermalLevel.label, accentColor = HdrAmber)
                }
                SpecRow("Governor Action", telemetry.governorNote)
                SpecRow("Frame Process Latency", String.format(Locale.US, "%.2f ms", telemetry.frameProcessTimeMs))
                SpecRow("Measured Render FPS", String.format(Locale.US, "%.1f fps", telemetry.renderedFps))
                SpecRow("Dropped Frames (Total / Rate)", "${telemetry.droppedFramesTotal} (${String.format(Locale.US, "%.1f/min", telemetry.droppedFramesPerMin)})")
                SpecRow("Battery Temp / Headroom", String.format(Locale.US, "%.1f °C • %s", telemetry.batteryTempCelsius, telemetry.thermalHeadroom?.let { String.format(Locale.US, "%.2f", it) } ?: "N/A"))
                SpecRow("CPU Load / Power Draw", "${telemetry.cpuLoadPercent.roundToInt()}% • ${telemetry.estimatedPowerMw?.let { "${it.roundToInt()} mW" } ?: "N/A"}")
                SpecRow("Available System RAM", "${telemetry.availableMemoryMb} MB / ${telemetry.totalMemoryMb} MB")
            }
        }

        // MODULE A: Video Analysis
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, HairlineBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("MODULE A: STREAM ANALYSIS", style = MaterialTheme.typography.labelMedium, color = HdrAmber)
                if (analysis == null) {
                    Text(
                        "No video stream loaded yet. Pick a local video or stream URI in the Studio tab to inspect HDR10 metadata.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    SpecRow("Source", analysis.displayName)
                    SpecRow("Resolution & FPS", "${analysis.resolutionLabel} @ ${if (analysis.frameRateFps > 0f) String.format(Locale.US, "%.2f fps", analysis.frameRateFps) else "VFR"}")
                    SpecRow("Video Codec / Profile", analysis.codecProfileName)
                    SpecRow("Bit Depth", "${analysis.bitDepth}-bit")
                    SpecRow("Transfer Characteristics", analysis.transferCharacteristic.label)
                    SpecRow("Color Primaries", analysis.colorPrimaries.label)
                    SpecRow("Color Range", analysis.colorRange.label)
                    SpecRow(
                        "HDR Static Metadata",
                        analysis.staticMetadata?.let { m ->
                            "Master Max ${m.masteringMaxLuminanceNits?.roundToInt() ?: "?"} nits | MaxCLL ${m.maxContentLightLevelNits ?: "?"} | MaxFALL ${m.maxFrameAverageLightLevelNits ?: "?"}"
                        } ?: "None in container"
                    )
                    SpecRow("HDR10 Detected", if (analysis.isHdr10) "YES (PQ / ST.2084)" else if (analysis.isHdr) "YES (${analysis.hdrFormatLabel})" else "NO (SDR)")
                }
            }
        }

        // MODULE B: Hardware Decoder & Device Profile
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, HairlineBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("MODULE B: HARDWARE DECODER PROBE", style = MaterialTheme.typography.labelMedium, color = ElectricCyan)
                    OutlinedButton(
                        onClick = onReprobeHardware,
                        enabled = !isProbingHardware,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("reprobe_codecs_button")
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Re-probe codecs")
                        Spacer(Modifier.width(4.dp))
                        Text("Probe")
                    }
                }

                if (isProbingHardware) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = ElectricCyan)
                }

                capabilities?.let { caps ->
                    val dev = caps.deviceProfile
                    SpecRow("Device / SoC", "${dev.manufacturer} ${dev.deviceModel} (${dev.socModel})")
                    SpecRow("OS / GPU", "${dev.androidVersionLabel} • ${dev.gpuRenderer}")
                    SpecRow("Panel & HDR Caps", "${dev.displayResolution} @ ${dev.displayRefreshHz.roundToInt()}Hz (${dev.displayHdrTypes.joinToString()})")
                    SpecRow("4K HDR10 Live Capable", if (caps.canDecode4kHdr10Live) "VERIFIED PASS" else "CONSTRAINED / FALLBACK")
                    Text(
                        text = caps.capabilitySummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (caps.canDecode4kHdr10Live) EmeraldNominal else HdrAmber
                    )

                    HorizontalDivider(color = HairlineBorder)
                    Text("HEVC Decoders (${caps.hevcDecoders.size})", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                    caps.hevcDecoders.take(4).forEach { entry ->
                        CodecProbeRow(entry)
                    }
                }
            }
        }

        // TESTING & BENCHMARK SUITE
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, HairlineBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("VALIDATION & THERMAL BENCHMARK", style = MaterialTheme.typography.labelMedium, color = HdrAmber)
                    if (completedBenchmarks.isNotEmpty()) {
                        OutlinedButton(
                            onClick = onShareBenchmarkJson,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = "Export JSON")
                            Spacer(Modifier.width(4.dp))
                            Text("Export JSON")
                        }
                    }
                }

                if (activeBenchmark == null) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        BenchmarkPreset.entries.forEach { preset ->
                            val isSel = preset == selectedPreset
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSel) ElectricCyan.copy(alpha = 0.12f) else SlateElevated)
                                    .border(1.dp, if (isSel) ElectricCyan else HairlineBorder, RoundedCornerShape(10.dp))
                                    .clickable { selectedPreset = preset }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(preset.title, style = MaterialTheme.typography.titleSmall)
                                    Text(preset.targetSpec, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("${preset.durationMinutes}m", style = MaterialTheme.typography.labelMedium, color = ElectricCyan)
                            }
                        }
                    }

                    Button(
                        onClick = { onStartBenchmark(selectedPreset) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("start_benchmark_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = Color.Black)
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Start Benchmark Session", fontWeight = FontWeight.Bold)
                    }
                } else {
                    val progress = (activeBenchmark.elapsedMs.toFloat() / activeBenchmark.targetDurationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
                    Text("Recording: ${activeBenchmark.preset.title}", style = MaterialTheme.typography.titleSmall, color = ElectricCyan)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = ElectricCyan)
                    SpecRow("Elapsed", "${activeBenchmark.elapsedMs / 1000L}s / ${activeBenchmark.targetDurationMs / 1000L}s")
                    SpecRow("Session Dropped Frames", "${activeBenchmark.latestDroppedFrames - activeBenchmark.startDroppedFrames}")
                    SpecRow("Avg Frame Time / FPS", String.format(Locale.US, "%.2f ms • %.1f fps", activeBenchmark.avgFrameTimeMs, activeBenchmark.avgFps))
                    SpecRow("Peak Battery Temp", String.format(Locale.US, "%.1f °C (%s)", activeBenchmark.peakTempCelsius, activeBenchmark.peakThermalLevel.label))

                    Button(
                        onClick = onStopBenchmark,
                        modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize(),
                        colors = ButtonDefaults.buttonColors(containerColor = CoralThermal, contentColor = Color.White)
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Stop & Save Benchmark Run")
                    }
                }

                completedBenchmarks.forEach { res ->
                    Surface(
                        color = SlateElevated,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, HairlineBorder)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(res.presetName, style = MaterialTheme.typography.labelMedium, color = EmeraldNominal)
                            Text(
                                "Mode: ${res.visionMode} • ${res.elapsedMs / 1000}s • Drops: ${res.totalFramesDropped}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                String.format(Locale.US, "Avg Frame: %.2fms • Peak Temp: %.1f°C (%s)", res.avgFrameTimeMs, res.peakTempCelsius, res.peakThermalLevel),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun CodecProbeRow(entry: CodecProbeEntry) {
    Surface(
        color = SlateElevated,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (entry.initProbeSucceeded) EmeraldNominal.copy(alpha = 0.4f) else HairlineBorder)
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(entry.codecName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    text = if (entry.isHardwareAccelerated) "HARDWARE" else "SOFTWARE",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (entry.isHardwareAccelerated) ElectricCyan else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "Max ${entry.maxSupportedWidth}x${entry.maxSupportedHeight} • Main10: ${entry.supportsHevcMain10} • 4K24: ${entry.supports4k24Fps} • 4K30: ${entry.supports4k30Fps}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = entry.initProbeMessage,
                style = MaterialTheme.typography.labelSmall,
                color = if (entry.initProbeSucceeded) EmeraldNominal else CoralThermal
            )
        }
    }
}

@Composable
private fun SpecRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.44f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.56f)
        )
    }
}
