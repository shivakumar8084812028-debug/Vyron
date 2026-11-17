package com.example.hdr.playback

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.example.hdr.gl.HdrShaderRuntimeState
import com.example.hdr.gl.VyronHdrToneMapEffect
import com.example.hdr.model.ColorPrimaries
import com.example.hdr.model.ColorRangeType
import com.example.hdr.model.DecoderCapabilityReport
import com.example.hdr.model.PipelineExecutionState
import com.example.hdr.model.PlaybackPipelineStatus
import com.example.hdr.model.QualityTier
import com.example.hdr.model.ToneMappingConfig
import com.example.hdr.model.TransferCharacteristic
import com.example.hdr.model.VideoAnalysisReport
import com.example.hdr.model.VyronVisionMode
import com.example.hdr.telemetry.AdaptivePerformanceMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Module D: Live Video Playback & Offline Transcoding Fallback Engine.
 * Integrates Media3 ExoPlayer + Media3 Effect (VyronHdrToneMapEffect) for live playback,
 * tracks AnalyticsListener decoder/drop-frame counters, and falls back explicitly to
 * Compatible Original Playback or Offline Media3 Transformer HDR->SDR export when needed.
 */
@OptIn(UnstableApi::class)
class VyronPlaybackEngine(
    private val context: Context,
    private val telemetryMonitor: AdaptivePerformanceMonitor
) {
    private val _pipelineStatus = MutableStateFlow(PlaybackPipelineStatus())
    val pipelineStatus: StateFlow<PlaybackPipelineStatus> = _pipelineStatus.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs: StateFlow<Long> = _playbackPositionMs.asStateFlow()

    private val _playbackDurationMs = MutableStateFlow(0L)
    val playbackDurationMs: StateFlow<Long> = _playbackDurationMs.asStateFlow()

    private val _liveVideoSize = MutableStateFlow(0 to 0)
    val liveVideoSize: StateFlow<Pair<Int, Int>> = _liveVideoSize.asStateFlow()

    @Volatile
    private var shaderRuntimeState = HdrShaderRuntimeState(
        config = ToneMappingConfig(),
        sourceTransfer = TransferCharacteristic.SDR_GAMMA,
        isWideGamutBt2020 = false,
        isLimitedRange = true,
        sourcePeakNits = 1000f
    )

    private var exoPlayer: ExoPlayer? = null
    private var activeTransformer: Transformer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentUri: Uri? = null
    private var currentAnalysis: VideoAnalysisReport? = null
    private var currentCapabilities: DecoderCapabilityReport? = null
    private var glEffectsAttached = false

    private val toneMapEffect = VyronHdrToneMapEffect(
        runtimeStateProvider = { shaderRuntimeState },
        onFrameTimedNs = { durationNs -> telemetryMonitor.recordShaderFrameTimeNs(durationNs) }
    )

    fun getOrCreatePlayer(): ExoPlayer {
        exoPlayer?.let { return it }

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        val player = ExoPlayer.Builder(context, renderersFactory)
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                _liveVideoSize.value = videoSize.width to videoSize.height
                updateOutputLabels()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    _playbackDurationMs.value = player.duration.coerceAtLeast(0L)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                handlePlayerError(error)
            }
        })

        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                val isHw = !decoderName.startsWith("OMX.google.", ignoreCase = true) &&
                    !decoderName.startsWith("c2.android.", ignoreCase = true)
                _pipelineStatus.value = _pipelineStatus.value.copy(
                    activeDecoderName = decoderName,
                    isHardwareDecoder = isHw
                )
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long
            ) {
                telemetryMonitor.recordDroppedFrames(droppedFrames)
            }

            override fun onVideoDisabled(
                eventTime: AnalyticsListener.EventTime,
                decoderCounters: DecoderCounters
            ) {
                // Counters reset handled per session
            }
        })

        exoPlayer = player
        return player
    }

    fun updateToneMappingConfig(config: ToneMappingConfig) {
        val analysis = currentAnalysis
        shaderRuntimeState = shaderRuntimeState.copy(
            config = config,
            sourceTransfer = analysis?.transferCharacteristic ?: shaderRuntimeState.sourceTransfer,
            isWideGamutBt2020 = analysis?.colorPrimaries?.isWideGamut ?: shaderRuntimeState.isWideGamutBt2020,
            isLimitedRange = analysis?.colorRange?.isLimited ?: shaderRuntimeState.isLimitedRange,
            sourcePeakNits = analysis?.staticMetadata?.effectivePeakNits ?: 1000f
        )
        updateOutputLabels()
    }

    fun loadAndPlay(
        uri: Uri,
        analysis: VideoAnalysisReport,
        capabilities: DecoderCapabilityReport,
        config: ToneMappingConfig,
        forceCompatibleOriginal: Boolean = false
    ) {
        currentUri = uri
        currentAnalysis = analysis
        currentCapabilities = capabilities
        telemetryMonitor.resetSessionCounters()

        shaderRuntimeState = HdrShaderRuntimeState(
            config = config,
            sourceTransfer = analysis.transferCharacteristic,
            isWideGamutBt2020 = analysis.colorPrimaries.isWideGamut,
            isLimitedRange = analysis.colorRange.isLimited,
            sourcePeakNits = analysis.staticMetadata?.effectivePeakNits ?: 1000f
        )

        val player = getOrCreatePlayer()
        player.stop()
        player.clearMediaItems()

        val is4k = analysis.width >= 3800 || analysis.height >= 2100
        val hevcUnsupported = analysis.codecMime == "video/hevc" &&
            capabilities.primaryHevcHwDecoder == null &&
            capabilities.hevcDecoders.none { it.initProbeSucceeded }

        if (hevcUnsupported) {
            _pipelineStatus.value = PlaybackPipelineStatus(
                executionState = PipelineExecutionState.UNSUPPORTED_CODEC_ERROR,
                activeDecoderName = "None (Incompatible)",
                isHardwareDecoder = false,
                outputResolutionLabel = "N/A",
                outputColorFormatLabel = "N/A",
                enhancementStatusLabel = "Codec Error",
                fallbackReason = "No functional HEVC decoder passed MediaCodec initialization for ${analysis.resolutionLabel}."
            )
            return
        }

        val shouldUseGlEffect = !forceCompatibleOriginal &&
            capabilities.canRunGlHdrToSdrEffect &&
            config.mode != VyronVisionMode.ORIGINAL_PLAYBACK

        val effects: List<Effect> = if (shouldUseGlEffect) {
            buildList {
                // For 720p target LCD (e.g. Vivo Y29 5G) under Thermal Safe tier, scale down gracefully
                if (config.mode == VyronVisionMode.AUTO_ADAPTIVE &&
                    config.activeQualityTier == QualityTier.THERMAL_SAFE_720P &&
                    is4k
                ) {
                    add(Presentation.createForHeight(720))
                }
                add(toneMapEffect)
            }
        } else {
            emptyList()
        }

        try {
            player.setVideoEffects(effects)
            glEffectsAttached = effects.isNotEmpty()
        } catch (t: Throwable) {
            // Never claim live conversion succeeded if setVideoEffects fails
            glEffectsAttached = false
            player.setVideoEffects(emptyList())
            _pipelineStatus.value = _pipelineStatus.value.copy(
                executionState = PipelineExecutionState.FALLBACK_COMPATIBLE_ORIGINAL,
                enhancementStatusLabel = "GL Effect Unsupported -> Original Fallback",
                fallbackReason = "Real-time Media3 Effect chain rejected by device GPU/driver: ${t.message}"
            )
        }

        val mediaItem = MediaItem.fromUri(uri)
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true

        _pipelineStatus.value = _pipelineStatus.value.copy(
            executionState = when {
                forceCompatibleOriginal -> PipelineExecutionState.FALLBACK_COMPATIBLE_ORIGINAL
                glEffectsAttached -> PipelineExecutionState.LIVE_HDR_TO_SDR_ACTIVE
                else -> PipelineExecutionState.LIVE_PASSTHROUGH_ACTIVE
            },
            fallbackReason = if (forceCompatibleOriginal) {
                "Playing compatible original stream without real-time shader conversion."
            } else null
        )
        updateOutputLabels()
    }

    /**
     * Module D Fallback: Offline HDR-to-SDR Transcoding using Media3 Transformer.
     */
    fun startOfflineHdrToSdrTranscode(targetHeight: Int = 720) {
        val uri = currentUri ?: return
        cancelOfflineTranscode()

        // Release active playback decoder resources before Transformer claims HW codecs
        exoPlayer?.let { player ->
            player.stop()
            player.clearMediaItems()
        }

        val outputFile = File(context.cacheDir, "vyron_sdr_${System.currentTimeMillis()}.mp4")
        val mediaItem = MediaItem.fromUri(uri)

        val videoEffects = buildList<Effect> {
            add(Presentation.createForHeight(targetHeight))
            add(toneMapEffect)
        }

        val editedItem = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        val composition = Composition.Builder(
            androidx.media3.transformer.EditedMediaItemSequence.Builder(editedItem).build()
        )
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()

        _pipelineStatus.value = _pipelineStatus.value.copy(
            executionState = PipelineExecutionState.OFFLINE_TRANSCODING,
            offlineTranscodeProgressPercent = 0,
            enhancementStatusLabel = "Offline Media3 Transcode (0%)",
            fallbackReason = "Converting HDR10 to 8-bit SDR BT.709 MP4 (${targetHeight}p) via Media3 Transformer..."
        )

        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    _pipelineStatus.value = _pipelineStatus.value.copy(
                        executionState = PipelineExecutionState.OFFLINE_TRANSCODE_READY,
                        offlineTranscodeProgressPercent = 100,
                        offlineTranscodeOutputPath = outputFile.absolutePath,
                        enhancementStatusLabel = "Offline SDR Ready (${exportResult.width}x${exportResult.height})",
                        fallbackReason = "Offline HDR-to-SDR transcode complete. Playing converted SDR output."
                    )
                    // Automatically load converted SDR output in ExoPlayer
                    val player = getOrCreatePlayer()
                    player.setVideoEffects(emptyList())
                    glEffectsAttached = false
                    player.setMediaItem(MediaItem.fromUri(Uri.fromFile(outputFile)))
                    player.prepare()
                    player.playWhenReady = true
                }

                override fun onError(
                    composition: Composition,
                    result: ExportResult,
                    exception: ExportException
                ) {
                    _pipelineStatus.value = _pipelineStatus.value.copy(
                        executionState = PipelineExecutionState.UNSUPPORTED_CODEC_ERROR,
                        enhancementStatusLabel = "Transcode Failed",
                        fallbackReason = "Offline Transformer failed (${exception.errorCodeName}): ${exception.localizedMessage}"
                    )
                }
            })
            .build()

        activeTransformer = transformer
        transformer.start(composition, outputFile.absolutePath)

        val progressHolder = ProgressHolder()
        mainHandler.post(object : Runnable {
            override fun run() {
                val t = activeTransformer ?: return
                val state = t.getProgress(progressHolder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val pct = progressHolder.progress.coerceIn(0, 100)
                    _pipelineStatus.value = _pipelineStatus.value.copy(
                        offlineTranscodeProgressPercent = pct,
                        enhancementStatusLabel = "Offline Media3 Transcode ($pct%)"
                    )
                }
                if (_pipelineStatus.value.executionState == PipelineExecutionState.OFFLINE_TRANSCODING) {
                    mainHandler.postDelayed(this, 400L)
                }
            }
        })
    }

    fun cancelOfflineTranscode() {
        activeTransformer?.cancel()
        activeTransformer = null
    }

    fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs.coerceAtLeast(0L))
        _playbackPositionMs.value = positionMs.coerceAtLeast(0L)
    }

    fun syncPositionTick() {
        exoPlayer?.let { p ->
            _playbackPositionMs.value = p.currentPosition.coerceAtLeast(0L)
            if (p.duration != C.TIME_UNSET && p.duration > 0) {
                _playbackDurationMs.value = p.duration
            }
        }
    }

    private fun handlePlayerError(error: PlaybackException) {
        val wasGl = glEffectsAttached
        val uri = currentUri
        val analysis = currentAnalysis
        val caps = currentCapabilities

        if (wasGl && uri != null && analysis != null && caps != null) {
            // Live Effect pipeline failed -> switch once to compatible original fallback
            glEffectsAttached = false
            _pipelineStatus.value = _pipelineStatus.value.copy(
                executionState = PipelineExecutionState.FALLBACK_COMPATIBLE_ORIGINAL,
                enhancementStatusLabel = "Live Effect Error -> Compatible Fallback",
                fallbackReason = "Live GL HDR conversion failed (${error.errorCodeName}). Switched to compatible original playback or try Offline Transcode."
            )
            loadAndPlay(uri, analysis, caps, shaderRuntimeState.config, forceCompatibleOriginal = true)
        } else {
            _pipelineStatus.value = _pipelineStatus.value.copy(
                executionState = PipelineExecutionState.UNSUPPORTED_CODEC_ERROR,
                enhancementStatusLabel = "Playback Error (${error.errorCodeName})",
                fallbackReason = error.localizedMessage ?: "Hardware decoder cannot play this stream or was reclaimed by MediaResourceManager."
            )
        }
    }

    private fun updateOutputLabels() {
        val cfg = shaderRuntimeState.config
        val disp = currentCapabilities?.deviceProfile
        val vidSize = _liveVideoSize.value
        val outRes = when {
            cfg.mode == VyronVisionMode.AUTO_ADAPTIVE && cfg.activeQualityTier == QualityTier.THERMAL_SAFE_720P ->
                "1280x720 (Thermal-Safe)"
            disp != null && disp.displayHeightPx in 700..760 ->
                "${disp.displayResolution} (Panel Fit)"
            vidSize.first > 0 -> "${vidSize.first}x${vidSize.second}"
            else -> disp?.displayResolution ?: "Panel Native"
        }

        val outColor = if (cfg.mode == VyronVisionMode.ORIGINAL_PLAYBACK) {
            currentAnalysis?.hdrFormatLabel ?: "Native Passthrough"
        } else {
            "SDR BT.709 / sRGB (8-bit)"
        }

        val enhLabel = when (cfg.mode) {
            VyronVisionMode.ORIGINAL_PLAYBACK -> "Original Passthrough"
            VyronVisionMode.HDR_TO_SDR -> "HDR->SDR Reference Shader"
            VyronVisionMode.HDR_TO_SDR_SMART -> "HDR->SDR + Smart Vision AI"
            VyronVisionMode.AUTO_ADAPTIVE -> "Auto Adaptive (${cfg.activeQualityTier.label})"
        }

        _pipelineStatus.value = _pipelineStatus.value.copy(
            outputResolutionLabel = outRes,
            outputColorFormatLabel = outColor,
            enhancementStatusLabel = enhLabel
        )
    }

    fun release() {
        cancelOfflineTranscode()
        exoPlayer?.release()
        exoPlayer = null
    }
}
