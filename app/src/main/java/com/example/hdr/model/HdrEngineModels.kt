package com.example.hdr.model

import kotlinx.serialization.Serializable

/**
 * Module F: VYRON VISION AI Operating Modes.
 */
enum class VyronVisionMode(
    val title: String,
    val subtitle: String,
    val badge: String
) {
    ORIGINAL_PLAYBACK(
        title = "Original Playback",
        subtitle = "Direct hardware decoder output without shader tone mapping.",
        badge = "PASSTHROUGH"
    ),
    HDR_TO_SDR(
        title = "HDR to SDR",
        subtitle = "PQ ST.2084 EOTF, BT.2390 luminance roll-off & BT.2020 -> BT.709 gamut.",
        badge = "REFERENCE"
    ),
    HDR_TO_SDR_SMART(
        title = "HDR to SDR + Smart Enhancement",
        subtitle = "Adds perceptual micro-contrast, shadow detail lift & 720p LCD clarity.",
        badge = "VISION AI"
    ),
    AUTO_ADAPTIVE(
        title = "Auto Adaptive Mode",
        subtitle = "Dynamically tunes shader complexity and scale to thermal & FPS telemetry.",
        badge = "ADAPTIVE"
    )
}

enum class QualityTier(val label: String, val targetMaxHeight: Int, val enableSharpening: Boolean) {
    ULTRA_10BIT("Ultra Precision", 2160, true),
    BALANCED_1080P("Balanced 1080p", 1080, true),
    THERMAL_SAFE_720P("Thermal Safe 720p", 720, false)
}

/**
 * Tunable parameters for the OpenGL ES 3.0 HDR-to-SDR Shader Pipeline.
 */
data class ToneMappingConfig(
    val mode: VyronVisionMode = VyronVisionMode.AUTO_ADAPTIVE,
    val targetSdrPeakNits: Float = 380f,     // Calibrated for 720p mobile LCD peak brightness
    val highlightRolloff: Float = 0.85f,     // 0.0..1.0 BT.2390 knee softness
    val shadowLift: Float = 0.14f,           // 0.0..0.4 shadow toe preservation
    val smartContrast: Float = 0.22f,        // 0.0..0.5 S-curve contrast in Smart Enhancement
    val saturationGuard: Float = 0.92f,      // Prevents neon over-saturation from BT.2020
    val sharpnessAmount: Float = 0.18f,      // 0.0..0.5 LCD subpixel clarity
    val comparisonEnabled: Boolean = false,
    val comparisonSplitFraction: Float = 0.5f,
    val activeQualityTier: QualityTier = QualityTier.ULTRA_10BIT
)

/**
 * Module A: Parsed CTA-861.3 / SMPTE ST.2086 HDR Static Metadata.
 */
@Serializable
data class HdrStaticMetadata(
    val masteringMaxLuminanceNits: Float? = null,
    val masteringMinLuminanceNits: Float? = null,
    val maxContentLightLevelNits: Int? = null,      // MaxCLL
    val maxFrameAverageLightLevelNits: Int? = null, // MaxFALL
    val rawByteLength: Int = 0
) {
    val effectivePeakNits: Float
        get() = when {
            maxContentLightLevelNits != null && maxContentLightLevelNits > 100 -> maxContentLightLevelNits.toFloat()
            masteringMaxLuminanceNits != null && masteringMaxLuminanceNits > 100f -> masteringMaxLuminanceNits
            else -> 1000f
        }
}

enum class TransferCharacteristic(val label: String, val shaderModeInt: Int) {
    PQ_ST2084("PQ / SMPTE ST.2084", 1),
    HLG("HLG / ARIB STD-B67", 2),
    SDR_GAMMA("SDR (BT.709 / sRGB)", 0),
    LINEAR("Linear", 3),
    UNKNOWN("Unspecified (Assumed SDR)", 0)
}

enum class ColorPrimaries(val label: String, val isWideGamut: Boolean) {
    BT_2020("ITU-R BT.2020", true),
    BT_709("ITU-R BT.709", false),
    BT_601("ITU-R BT.601", false),
    DISPLAY_P3("DCI-P3 / Display P3", true),
    UNKNOWN("Unspecified", false)
}

enum class ColorRangeType(val label: String, val isLimited: Boolean) {
    LIMITED("Limited (TV 16-235 / 64-940)", true),
    FULL("Full (PC 0-255 / 0-1023)", false),
    UNKNOWN("Unspecified (Assumed Limited)", true)
}

/**
 * Module A: Complete Stream Analysis Output.
 */
@Serializable
data class VideoAnalysisReport(
    val sourceUri: String,
    val displayName: String,
    val width: Int = 0,
    val height: Int = 0,
    val frameRateFps: Float = 0f,
    val codecMime: String = "Unknown",
    val codecProfileName: String = "Unknown",
    val bitDepth: Int = 8,
    val transferCharacteristic: TransferCharacteristic = TransferCharacteristic.UNKNOWN,
    val colorPrimaries: ColorPrimaries = ColorPrimaries.UNKNOWN,
    val colorRange: ColorRangeType = ColorRangeType.UNKNOWN,
    val staticMetadata: HdrStaticMetadata? = null,
    val hasDynamicHdr10Plus: Boolean = false,
    val bitrateBps: Long = 0L,
    val durationMs: Long = 0L,
    val audioSummary: String = "None",
    val analysisError: String? = null
) {
    val isHdr10: Boolean
        get() = transferCharacteristic == TransferCharacteristic.PQ_ST2084 &&
            (colorPrimaries == ColorPrimaries.BT_2020 || bitDepth >= 10 || staticMetadata != null)

    val isHdr: Boolean
        get() = transferCharacteristic == TransferCharacteristic.PQ_ST2084 ||
            transferCharacteristic == TransferCharacteristic.HLG ||
            hasDynamicHdr10Plus

    val resolutionLabel: String
        get() = if (width > 0 && height > 0) {
            val tag = when {
                width >= 3800 || height >= 2100 -> "4K UHD"
                width >= 2500 || height >= 1400 -> "1440p QHD"
                width >= 1900 || height >= 1070 -> "1080p FHD"
                width >= 1200 || height >= 700 -> "720p HD"
                else -> "SD"
            }
            "${width}x${height} ($tag)"
        } else "Unknown"

    val hdrFormatLabel: String
        get() = when {
            hasDynamicHdr10Plus -> "HDR10+ (PQ / Dynamic)"
            isHdr10 -> "HDR10 (PQ / ST.2084)"
            transferCharacteristic == TransferCharacteristic.HLG -> "HLG HDR (BT.2100)"
            transferCharacteristic == TransferCharacteristic.PQ_ST2084 -> "HDR PQ (ST.2084)"
            else -> "SDR (${colorPrimaries.label})"
        }
}

/**
 * Module B: Individual MediaCodec Decoder Entry.
 */
@Serializable
data class CodecProbeEntry(
    val codecName: String,
    val mimeType: String,
    val isHardwareAccelerated: Boolean,
    val supportsHevcMain10: Boolean,
    val supportsHevcMain10Hdr10: Boolean,
    val maxSupportedWidth: Int,
    val maxSupportedHeight: Int,
    val supports4k24Fps: Boolean,
    val supports4k30Fps: Boolean,
    val supports4k60Fps: Boolean,
    val supports1080p60Fps: Boolean,
    val initProbeSucceeded: Boolean,
    val initProbeLatencyMs: Long,
    val initProbeMessage: String
)

@Serializable
data class DeviceHardwareProfile(
    val deviceModel: String,
    val manufacturer: String,
    val socModel: String,
    val hardwareBoard: String,
    val androidVersionLabel: String,
    val apiLevel: Int,
    val displayResolution: String,
    val displayWidthPx: Int,
    val displayHeightPx: Int,
    val displayRefreshHz: Float,
    val displayHdrTypes: List<String>,
    val totalRamMb: Long,
    val gpuRenderer: String,
    val gpuVendor: String,
    val glesVersion: String
) {
    val isTargetVivoY29Class: Boolean
        get() = socModel.contains("6300", ignoreCase = true) ||
            gpuRenderer.contains("G57", ignoreCase = true) ||
            (displayHeightPx in 700..760 && displayWidthPx in 1500..1700) ||
            (displayWidthPx in 700..760 && displayHeightPx in 1500..1700)
}

@Serializable
data class DecoderCapabilityReport(
    val deviceProfile: DeviceHardwareProfile,
    val hevcDecoders: List<CodecProbeEntry>,
    val avcDecoders: List<CodecProbeEntry>,
    val av1Decoders: List<CodecProbeEntry>,
    val primaryHevcHwDecoder: CodecProbeEntry?,
    val canDecode4kHdr10Live: Boolean,
    val canRunGlHdrToSdrEffect: Boolean,
    val capabilitySummary: String
)

/**
 * Module D: Pipeline Execution Status & Fallbacks.
 */
enum class PipelineExecutionState {
    IDLE,
    PROBING,
    LIVE_HDR_TO_SDR_ACTIVE,
    LIVE_PASSTHROUGH_ACTIVE,
    FALLBACK_COMPATIBLE_ORIGINAL,
    OFFLINE_TRANSCODING,
    OFFLINE_TRANSCODE_READY,
    UNSUPPORTED_CODEC_ERROR
}

data class PlaybackPipelineStatus(
    val executionState: PipelineExecutionState = PipelineExecutionState.IDLE,
    val activeDecoderName: String = "Pending",
    val isHardwareDecoder: Boolean = false,
    val outputResolutionLabel: String = "Display Native",
    val outputColorFormatLabel: String = "SDR BT.709 (sRGB)",
    val enhancementStatusLabel: String = "Standby",
    val fallbackReason: String? = null,
    val offlineTranscodeProgressPercent: Int = 0,
    val offlineTranscodeOutputPath: String? = null
)

/**
 * Module E: Real-Time Performance & Thermal Telemetry.
 */
enum class ThermalLevel(val label: String, val severityIndex: Int) {
    NONE("Nominal", 0),
    LIGHT("Light Warmth", 1),
    MODERATE("Moderate Load", 2),
    SEVERE("Severe Thermal", 3),
    CRITICAL("Critical Throttle", 4),
    EMERGENCY("Emergency", 5),
    UNAVAILABLE("Sensor Unavailable", -1)
}

@Serializable
data class TelemetrySample(
    val timestampMs: Long,
    val frameProcessTimeMs: Float,
    val renderedFps: Float,
    val droppedFramesTotal: Int,
    val droppedFramesPerMin: Float,
    val batteryTempCelsius: Float,
    val thermalLevel: ThermalLevel,
    val thermalHeadroom: Float?, // 0.0..1.0+ from PowerManager
    val availableMemoryMb: Long,
    val totalMemoryMb: Long,
    val cpuLoadPercent: Float,
    val batteryCurrentMa: Float?,
    val estimatedPowerMw: Float?,
    val governorNote: String
)

/**
 * Testing & Benchmark Scenario Presets (User Testing Requirements).
 */
enum class BenchmarkPreset(
    val title: String,
    val targetSpec: String,
    val durationMinutes: Int
) {
    UHD_HDR10_24("4K HDR10 HEVC Main10 @ 24 FPS", "3840x2160 • 10-bit PQ • 24fps", 5),
    UHD_HDR10_30("4K HDR10 @ 30 FPS", "3840x2160 • 10-bit PQ • 30fps", 5),
    UHD_SDR_30("4K SDR @ 30 FPS", "3840x2160 • 8-bit BT.709 • 30fps", 5),
    FHD_SDR("1080p SDR", "1920x1080 • 8-bit BT.709 • 30/60fps", 5),
    SOAK_30_MIN("30-Minute Playback Stability", "Continuous drop-frame & memory soak", 30),
    THERMAL_60_MIN("60-Minute Thermal Stress Test", "Long-duration Dimensity thermal curve", 60)
}

@Serializable
data class BenchmarkResult(
    val presetName: String,
    val sourceLabel: String,
    val visionMode: String,
    val startedAtMs: Long,
    val elapsedMs: Long,
    val totalFramesDropped: Int,
    val avgFrameTimeMs: Float,
    val avgFps: Float,
    val peakTempCelsius: Float,
    val avgPowerMw: Float?,
    val peakThermalLevel: String,
    val sampleCount: Int
)
