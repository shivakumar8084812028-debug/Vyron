package com.example.hdr.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.example.hdr.analyzer.VideoStreamAnalyzer
import com.example.hdr.benchmark.ActiveBenchmarkState
import com.example.hdr.benchmark.BenchmarkSessionManager
import com.example.hdr.data.VyronSettingsRepository
import com.example.hdr.decoder.HardwareDecoderProber
import com.example.hdr.model.BenchmarkPreset
import com.example.hdr.model.BenchmarkResult
import com.example.hdr.model.DecoderCapabilityReport
import com.example.hdr.model.PlaybackPipelineStatus
import com.example.hdr.model.QualityTier
import com.example.hdr.model.TelemetrySample
import com.example.hdr.model.ToneMappingConfig
import com.example.hdr.model.VideoAnalysisReport
import com.example.hdr.model.VyronVisionMode
import com.example.hdr.playback.VyronPlaybackEngine
import com.example.hdr.telemetry.AdaptivePerformanceMonitor
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Coordinates Modules A–F: Stream Analysis, Hardware Decoder Probe, OpenGL ES HDR->SDR Pipeline,
 * Media3 ExoPlayer & Offline Transformer Fallback, Thermal/FPS Telemetry, and Benchmark Recording.
 */
class VyronEngineViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo = VyronSettingsRepository(application)
    private val streamAnalyzer = VideoStreamAnalyzer(application)
    private val decoderProber = HardwareDecoderProber(application)
    private val telemetryMonitor = AdaptivePerformanceMonitor(application, viewModelScope)
    private val playbackEngine = VyronPlaybackEngine(application, telemetryMonitor)
    private val benchmarkManager = BenchmarkSessionManager()

    private val _toneConfig = MutableStateFlow(ToneMappingConfig())
    val toneConfig: StateFlow<ToneMappingConfig> = _toneConfig.asStateFlow()

    private val _capabilities = MutableStateFlow<DecoderCapabilityReport?>(null)
    val capabilities: StateFlow<DecoderCapabilityReport?> = _capabilities.asStateFlow()

    private val _isProbingHardware = MutableStateFlow(true)
    val isProbingHardware: StateFlow<Boolean> = _isProbingHardware.asStateFlow()

    private val _videoAnalysis = MutableStateFlow<VideoAnalysisReport?>(null)
    val videoAnalysis: StateFlow<VideoAnalysisReport?> = _videoAnalysis.asStateFlow()

    private val _isAnalyzingVideo = MutableStateFlow(false)
    val isAnalyzingVideo: StateFlow<Boolean> = _isAnalyzingVideo.asStateFlow()

    private val _selectedUri = MutableStateFlow<Uri?>(null)
    val selectedUri: StateFlow<Uri?> = _selectedUri.asStateFlow()

    val pipelineStatus: StateFlow<PlaybackPipelineStatus> = playbackEngine.pipelineStatus
    val isPlaying: StateFlow<Boolean> = playbackEngine.isPlaying
    val playbackPositionMs: StateFlow<Long> = playbackEngine.playbackPositionMs
    val playbackDurationMs: StateFlow<Long> = playbackEngine.playbackDurationMs
    val liveVideoSize: StateFlow<Pair<Int, Int>> = playbackEngine.liveVideoSize

    val telemetry: StateFlow<TelemetrySample> = telemetryMonitor.currentTelemetry
    val telemetryHistory: StateFlow<List<TelemetrySample>> = telemetryMonitor.telemetryHistory
    val activeQualityTier: StateFlow<QualityTier> = telemetryMonitor.recommendedTier

    val activeBenchmark: StateFlow<ActiveBenchmarkState?> = benchmarkManager.activeSession
    val completedBenchmarks: StateFlow<List<BenchmarkResult>> = benchmarkManager.completedResults

    init {
        viewModelScope.launch {
            settingsRepo.configFlow.collectLatest { saved ->
                val synced = saved.copy(activeQualityTier = telemetryMonitor.recommendedTier.value)
                _toneConfig.value = synced
                playbackEngine.updateToneMappingConfig(synced)
            }
        }

        viewModelScope.launch {
            telemetryMonitor.recommendedTier.collect { tier ->
                val updated = _toneConfig.value.copy(activeQualityTier = tier)
                _toneConfig.value = updated
                playbackEngine.updateToneMappingConfig(updated)
            }
        }

        viewModelScope.launch {
            telemetryMonitor.currentTelemetry.collect { sample ->
                benchmarkManager.onTelemetrySample(sample)
            }
        }

        viewModelScope.launch {
            while (isActive) {
                playbackEngine.syncPositionTick()
                delay(250L)
            }
        }

        telemetryMonitor.startMonitoring { _toneConfig.value.mode }
        runHardwareProbe()
    }

    private var activeProbeDeferred: Deferred<DecoderCapabilityReport>? = null

    fun getPlayer(): Player = playbackEngine.getOrCreatePlayer()

    fun runHardwareProbe() {
        val currentDeferred = activeProbeDeferred
        if (currentDeferred != null && currentDeferred.isActive) return

        val shouldRunLiveInit = !playbackEngine.isPlaying.value
        val deferred = viewModelScope.async {
            _isProbingHardware.value = true
            try {
                decoderProber.probeDeviceCapabilities(runLiveCodecInitProbe = shouldRunLiveInit)
            } finally {
                _isProbingHardware.value = false
            }
        }
        activeProbeDeferred = deferred
        viewModelScope.launch {
            _capabilities.value = deferred.await()
        }
    }

    fun loadMediaUri(uri: Uri) {
        viewModelScope.launch {
            _selectedUri.value = uri
            _isAnalyzingVideo.value = true
            val analysis = streamAnalyzer.analyze(uri)
            _videoAnalysis.value = analysis
            _isAnalyzingVideo.value = false

            val caps = _capabilities.value
                ?: activeProbeDeferred?.await()
                ?: decoderProber.probeDeviceCapabilities(runLiveCodecInitProbe = false)
                    .also { _capabilities.value = it }

            playbackEngine.loadAndPlay(
                uri = uri,
                analysis = analysis,
                capabilities = caps,
                config = _toneConfig.value,
                forceCompatibleOriginal = false
            )
        }
    }

    fun setVisionMode(mode: VyronVisionMode) {
        val prevMode = _toneConfig.value.mode
        val updated = _toneConfig.value.copy(mode = mode)
        updateConfig(updated)

        // Switching into or out of ORIGINAL_PLAYBACK requires re-attaching or clearing Media3 VideoEffects
        val needsEffectRebind = (prevMode == VyronVisionMode.ORIGINAL_PLAYBACK) !=
            (mode == VyronVisionMode.ORIGINAL_PLAYBACK)
        if (needsEffectRebind) {
            val uri = _selectedUri.value
            val analysis = _videoAnalysis.value
            val caps = _capabilities.value
            if (uri != null && analysis != null && caps != null) {
                playbackEngine.loadAndPlay(
                    uri = uri,
                    analysis = analysis,
                    capabilities = caps,
                    config = updated,
                    forceCompatibleOriginal = mode == VyronVisionMode.ORIGINAL_PLAYBACK
                )
            }
        }
    }

    fun updateToneParameters(transform: (ToneMappingConfig) -> ToneMappingConfig) {
        val updated = transform(_toneConfig.value)
        updateConfig(updated)
    }

    private fun updateConfig(config: ToneMappingConfig) {
        _toneConfig.value = config
        playbackEngine.updateToneMappingConfig(config)
        viewModelScope.launch {
            settingsRepo.saveConfig(config)
        }
    }

    fun togglePlayPause() = playbackEngine.togglePlayPause()

    fun seekTo(ms: Long) = playbackEngine.seekTo(ms)

    fun switchToCompatibleOriginalFallback() {
        val uri = _selectedUri.value ?: return
        val analysis = _videoAnalysis.value ?: return
        val caps = _capabilities.value ?: return
        playbackEngine.loadAndPlay(
            uri = uri,
            analysis = analysis,
            capabilities = caps,
            config = _toneConfig.value,
            forceCompatibleOriginal = true
        )
    }

    fun startOfflineTranscode(targetHeight: Int = 720) {
        playbackEngine.startOfflineHdrToSdrTranscode(targetHeight)
    }

    fun cancelOfflineTranscode() {
        playbackEngine.cancelOfflineTranscode()
    }

    fun startBenchmark(preset: BenchmarkPreset) {
        val source = _videoAnalysis.value?.displayName ?: "No Video Loaded (Thermal/Idle Baseline)"
        val mode = _toneConfig.value.mode.title
        benchmarkManager.startSession(
            preset = preset,
            sourceLabel = source,
            visionModeLabel = mode,
            baselineDroppedFrames = telemetry.value.droppedFramesTotal
        )
    }

    fun stopBenchmark() {
        benchmarkManager.finishSession()
    }

    fun exportBenchmarkJson(): String = benchmarkManager.exportResultsAsJson()

    override fun onCleared() {
        super.onCleared()
        telemetryMonitor.stopMonitoring()
        playbackEngine.release()
    }
}
