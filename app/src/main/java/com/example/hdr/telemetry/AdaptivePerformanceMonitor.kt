package com.example.hdr.telemetry

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import com.example.hdr.model.QualityTier
import com.example.hdr.model.TelemetrySample
import com.example.hdr.model.ThermalLevel
import com.example.hdr.model.VyronVisionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Module E: Adaptive Performance & Thermal Safety Monitor.
 * Polls Android Thermal API (PowerManager), BatteryManager temperature/power,
 * ActivityManager memory, process CPU load, shader frame processing time, and dropped frames.
 */
class AdaptivePerformanceMonitor(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val _currentTelemetry = MutableStateFlow(createInitialSample())
    val currentTelemetry: StateFlow<TelemetrySample> = _currentTelemetry.asStateFlow()

    private val _telemetryHistory = MutableStateFlow<List<TelemetrySample>>(emptyList())
    val telemetryHistory: StateFlow<List<TelemetrySample>> = _telemetryHistory.asStateFlow()

    private val _recommendedTier = MutableStateFlow(QualityTier.ULTRA_10BIT)
    val recommendedTier: StateFlow<QualityTier> = _recommendedTier.asStateFlow()

    private val lastShaderFrameTimeNs = AtomicLong(0L)
    private val totalDroppedFrames = AtomicInteger(0)
    private val renderedFrameCounter = AtomicInteger(0)

    private var monitorJob: Job? = null
    private var prevCpuTimeMs = Process.getElapsedCpuTime()
    private var prevWallTimeMs = SystemClock.elapsedRealtime()
    private var prevDroppedFrames = 0
    private var lastFpsCheckWallMs = SystemClock.elapsedRealtime()
    private var highLoadStreak = 0
    private var coolStreak = 0

    fun recordShaderFrameTimeNs(durationNs: Long) {
        lastShaderFrameTimeNs.set(durationNs)
        renderedFrameCounter.incrementAndGet()
    }

    fun recordDroppedFrames(deltaDropped: Int) {
        if (deltaDropped > 0) {
            totalDroppedFrames.addAndGet(deltaDropped)
        }
    }

    fun resetSessionCounters() {
        totalDroppedFrames.set(0)
        renderedFrameCounter.set(0)
        prevDroppedFrames = 0
        highLoadStreak = 0
        coolStreak = 0
        _recommendedTier.value = QualityTier.ULTRA_10BIT
        _telemetryHistory.value = emptyList()
    }

    fun startMonitoring(activeModeProvider: () -> VyronVisionMode) {
        if (monitorJob?.isActive == true) return
        lastFpsCheckWallMs = SystemClock.elapsedRealtime()
        prevWallTimeMs = SystemClock.elapsedRealtime()
        prevCpuTimeMs = Process.getElapsedCpuTime()

        monitorJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(1000L)
                val sample = collectSample(activeModeProvider())
                _currentTelemetry.value = sample
                val updated = (_telemetryHistory.value + sample).takeLast(120)
                _telemetryHistory.value = updated
            }
        }
    }

    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    private fun collectSample(activeMode: VyronVisionMode): TelemetrySample {
        val now = SystemClock.elapsedRealtime()
        val elapsedSec = ((now - lastFpsCheckWallMs).coerceAtLeast(100L)) / 1000f
        lastFpsCheckWallMs = now

        val framesInWindow = renderedFrameCounter.getAndSet(0)
        val measuredFps = framesInWindow / elapsedSec

        val totalDropped = totalDroppedFrames.get()
        val droppedDelta = (totalDropped - prevDroppedFrames).coerceAtLeast(0)
        prevDroppedFrames = totalDropped
        val droppedPerMin = (droppedDelta / elapsedSec) * 60f

        val frameTimeMs = lastShaderFrameTimeNs.get() / 1_000_000f

        // CPU Utilization Estimate (Process CPU delta / Wall delta / Cores)
        val cpuNow = Process.getElapsedCpuTime()
        val wallDelta = (now - prevWallTimeMs).coerceAtLeast(1L)
        val cpuDelta = (cpuNow - prevCpuTimeMs).coerceAtLeast(0L)
        prevCpuTimeMs = cpuNow
        prevWallTimeMs = now
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val cpuPercent = ((cpuDelta.toFloat() / (wallDelta * cores)) * 100f).coerceIn(0f, 100f)

        // Memory
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val availMb = memInfo.availMem / (1024L * 1024L)
        val totalMb = memInfo.totalMem / (1024L * 1024L)

        // Thermal API
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val thermalLevel = readThermalLevel(pm)
        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && pm != null) {
            runCatching { pm.getThermalHeadroom(10) }.getOrNull()?.takeIf { !it.isNaN() && it >= 0f }
        } else null

        // Battery Temperature & Power Estimate
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 300) ?: 300
        val tempC = tempTenths / 10f
        val voltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 3850) ?: 3850

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val currentUa = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentMa = currentUa?.takeIf { it != Int.MIN_VALUE && it != 0 }?.let { kotlin.math.abs(it) / 1000f }
        val powerMw = currentMa?.let { ma -> (ma * (voltageMv / 1000f)) }

        val governorNote = evaluateAdaptiveGovernor(
            activeMode = activeMode,
            thermalLevel = thermalLevel,
            tempC = tempC,
            droppedDelta = droppedDelta,
            frameTimeMs = frameTimeMs,
            availMemMb = availMb
        )

        return TelemetrySample(
            timestampMs = System.currentTimeMillis(),
            frameProcessTimeMs = frameTimeMs,
            renderedFps = measuredFps,
            droppedFramesTotal = totalDropped,
            droppedFramesPerMin = droppedPerMin,
            batteryTempCelsius = tempC,
            thermalLevel = thermalLevel,
            thermalHeadroom = headroom,
            availableMemoryMb = availMb,
            totalMemoryMb = totalMb,
            cpuLoadPercent = cpuPercent,
            batteryCurrentMa = currentMa,
            estimatedPowerMw = powerMw,
            governorNote = governorNote
        )
    }

    private fun evaluateAdaptiveGovernor(
        activeMode: VyronVisionMode,
        thermalLevel: ThermalLevel,
        tempC: Float,
        droppedDelta: Int,
        frameTimeMs: Float,
        availMemMb: Long
    ): String {
        if (activeMode != VyronVisionMode.AUTO_ADAPTIVE) {
            _recommendedTier.value = QualityTier.ULTRA_10BIT
            return "Manual Mode (${activeMode.badge})"
        }

        val thermalPressure = thermalLevel.severityIndex >= ThermalLevel.MODERATE.severityIndex || tempC >= 41.5f
        val severePressure = thermalLevel.severityIndex >= ThermalLevel.SEVERE.severityIndex || tempC >= 44.0f
        val framePressure = droppedDelta >= 3 || frameTimeMs > 24f || availMemMb < 450L

        if (severePressure) {
            highLoadStreak += 2
            coolStreak = 0
        } else if (thermalPressure || framePressure) {
            highLoadStreak++
            coolStreak = 0
        } else {
            coolStreak++
            highLoadStreak = (highLoadStreak - 1).coerceAtLeast(0)
        }

        val currentTier = _recommendedTier.value
        val nextTier = when {
            severePressure || highLoadStreak >= 4 -> QualityTier.THERMAL_SAFE_720P
            (thermalPressure || framePressure) && currentTier == QualityTier.ULTRA_10BIT -> QualityTier.BALANCED_1080P
            coolStreak >= 10 && currentTier == QualityTier.THERMAL_SAFE_720P -> QualityTier.BALANCED_1080P
            coolStreak >= 15 && currentTier == QualityTier.BALANCED_1080P -> QualityTier.ULTRA_10BIT
            else -> currentTier
        }
        _recommendedTier.value = nextTier

        return when {
            nextTier == QualityTier.THERMAL_SAFE_720P -> "Thermal/Frame Guard Active -> 720p Fast Curve"
            nextTier == QualityTier.BALANCED_1080P -> "Adaptive Scaling -> Balanced 1080p Shader"
            else -> "Nominal -> Full Precision HDR-to-SDR"
        }
    }

    private fun readThermalLevel(pm: PowerManager?): ThermalLevel {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || pm == null) {
            return ThermalLevel.UNAVAILABLE
        }
        return when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalLevel.NONE
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL -> ThermalLevel.CRITICAL
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalLevel.EMERGENCY
            else -> ThermalLevel.UNAVAILABLE
        }
    }

    private fun createInitialSample(): TelemetrySample = TelemetrySample(
        timestampMs = System.currentTimeMillis(),
        frameProcessTimeMs = 0f,
        renderedFps = 0f,
        droppedFramesTotal = 0,
        droppedFramesPerMin = 0f,
        batteryTempCelsius = 30.0f,
        thermalLevel = ThermalLevel.NONE,
        thermalHeadroom = null,
        availableMemoryMb = 0L,
        totalMemoryMb = 0L,
        cpuLoadPercent = 0f,
        batteryCurrentMa = null,
        estimatedPowerMw = null,
        governorNote = "Standby"
    )
}
