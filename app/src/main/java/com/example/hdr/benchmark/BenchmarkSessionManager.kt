package com.example.hdr.benchmark

import com.example.hdr.model.BenchmarkPreset
import com.example.hdr.model.BenchmarkResult
import com.example.hdr.model.TelemetrySample
import com.example.hdr.model.ThermalLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class ActiveBenchmarkState(
    val preset: BenchmarkPreset,
    val startedAtMs: Long,
    val elapsedMs: Long,
    val targetDurationMs: Long,
    val samplesRecorded: Int,
    val startDroppedFrames: Int,
    val latestDroppedFrames: Int,
    val peakTempCelsius: Float,
    val peakThermalLevel: ThermalLevel,
    val avgFrameTimeMs: Float,
    val avgFps: Float,
    val avgPowerMw: Float?
)

/**
 * Records genuine device playback stability & thermal soak sessions for the test matrix
 * (4K HDR10 24/30fps, 4K SDR, 1080p SDR, 30-min playback, 60-min thermal test).
 */
class BenchmarkSessionManager {

    private val _activeSession = MutableStateFlow<ActiveBenchmarkState?>(null)
    val activeSession: StateFlow<ActiveBenchmarkState?> = _activeSession.asStateFlow()

    private val _completedResults = MutableStateFlow<List<BenchmarkResult>>(emptyList())
    val completedResults: StateFlow<List<BenchmarkResult>> = _completedResults.asStateFlow()

    private val recordedSamples = mutableListOf<TelemetrySample>()
    private var activeSourceLabel: String = "No stream"
    private var activeVisionMode: String = "AUTO_ADAPTIVE"

    private val json = Json { prettyPrint = true }

    fun startSession(
        preset: BenchmarkPreset,
        sourceLabel: String,
        visionModeLabel: String,
        baselineDroppedFrames: Int
    ) {
        recordedSamples.clear()
        activeSourceLabel = sourceLabel
        activeVisionMode = visionModeLabel
        _activeSession.value = ActiveBenchmarkState(
            preset = preset,
            startedAtMs = System.currentTimeMillis(),
            elapsedMs = 0L,
            targetDurationMs = preset.durationMinutes * 60_000L,
            samplesRecorded = 0,
            startDroppedFrames = baselineDroppedFrames,
            latestDroppedFrames = baselineDroppedFrames,
            peakTempCelsius = 0f,
            peakThermalLevel = ThermalLevel.NONE,
            avgFrameTimeMs = 0f,
            avgFps = 0f,
            avgPowerMw = null
        )
    }

    fun onTelemetrySample(sample: TelemetrySample) {
        val current = _activeSession.value ?: return
        recordedSamples += sample

        val count = recordedSamples.size
        val avgFrameMs = recordedSamples.map { it.frameProcessTimeMs }.average().toFloat()
        val avgFps = recordedSamples.map { it.renderedFps }.average().toFloat()
        val powerValues = recordedSamples.mapNotNull { it.estimatedPowerMw }
        val avgPower = if (powerValues.isNotEmpty()) powerValues.average().toFloat() else null
        val peakTemp = maxOf(current.peakTempCelsius, sample.batteryTempCelsius)
        val peakThermal = if (sample.thermalLevel.severityIndex > current.peakThermalLevel.severityIndex) {
            sample.thermalLevel
        } else {
            current.peakThermalLevel
        }
        val elapsed = (System.currentTimeMillis() - current.startedAtMs).coerceAtLeast(0L)

        _activeSession.value = current.copy(
            elapsedMs = elapsed,
            samplesRecorded = count,
            latestDroppedFrames = sample.droppedFramesTotal,
            peakTempCelsius = peakTemp,
            peakThermalLevel = peakThermal,
            avgFrameTimeMs = avgFrameMs,
            avgFps = avgFps,
            avgPowerMw = avgPower
        )

        if (elapsed >= current.targetDurationMs) {
            finishSession()
        }
    }

    fun finishSession(): BenchmarkResult? {
        val current = _activeSession.value ?: return null
        val result = BenchmarkResult(
            presetName = current.preset.title,
            sourceLabel = activeSourceLabel,
            visionMode = activeVisionMode,
            startedAtMs = current.startedAtMs,
            elapsedMs = current.elapsedMs,
            totalFramesDropped = (current.latestDroppedFrames - current.startDroppedFrames).coerceAtLeast(0),
            avgFrameTimeMs = current.avgFrameTimeMs,
            avgFps = current.avgFps,
            peakTempCelsius = current.peakTempCelsius,
            avgPowerMw = current.avgPowerMw,
            peakThermalLevel = current.peakThermalLevel.label,
            sampleCount = current.samplesRecorded
        )
        _completedResults.value = listOf(result) + _completedResults.value
        _activeSession.value = null
        recordedSamples.clear()
        return result
    }

    fun exportResultsAsJson(): String {
        return json.encodeToString(_completedResults.value)
    }
}
