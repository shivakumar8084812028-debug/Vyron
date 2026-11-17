package com.example.hdr.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.Settings
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.R
import com.example.hdr.model.PipelineExecutionState
import com.example.hdr.model.PlaybackPipelineStatus
import com.example.hdr.model.TelemetrySample
import com.example.hdr.model.ToneMappingConfig
import com.example.hdr.model.VideoAnalysisReport
import com.example.hdr.model.VyronVisionMode
import com.example.hdr.ui.components.ComparisonToggleRow
import com.example.hdr.ui.components.PipelineReadoutGrid
import com.example.hdr.ui.components.StatusPill
import com.example.hdr.ui.components.TelemetryMiniBar
import com.example.hdr.ui.components.VisionAiModeSelector
import com.example.ui.theme.CoralThermal
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.HdrAmber
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.SlateElevated
import java.util.Locale
import kotlin.math.roundToInt

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@OptIn(UnstableApi::class)
@Composable
fun PlayerStudioScreen(
    player: Player,
    selectedUri: Uri?,
    analysis: VideoAnalysisReport?,
    isAnalyzing: Boolean,
    pipelineStatus: PlaybackPipelineStatus,
    toneConfig: ToneMappingConfig,
    telemetry: TelemetrySample,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onPickLocalVideo: () -> Unit,
    onLoadStreamUrl: (Uri) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSelectVisionMode: (VyronVisionMode) -> Unit,
    onToggleComparison: (Boolean) -> Unit,
    onUpdateSplitFraction: (Float) -> Unit,
    onFallbackCompatibleOriginal: () -> Unit,
    onStartOfflineTranscode: (Int) -> Unit,
    onCancelOfflineTranscode: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val initialSystemBrightness = remember(context) {
        val windowVal = activity?.window?.attributes?.screenBrightness ?: -1f
        if (windowVal >= 0f) {
            windowVal.coerceIn(0.05f, 1f)
        } else {
            val sys = runCatching {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 180)
            }.getOrDefault(180)
            (sys / 255f).coerceIn(0.05f, 1f)
        }
    }

    var showUrlDialog by remember { mutableStateOf(false) }
    var showVisionSheet by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    var brightnessLevel by remember { mutableStateOf(initialSystemBrightness) }
    var isCustomBrightness by remember {
        mutableStateOf((activity?.window?.attributes?.screenBrightness ?: -1f) >= 0f)
    }

    val applyWindowBrightness: (Float) -> Unit = { newLevel ->
        val clamped = newLevel.coerceIn(0.05f, 1f)
        brightnessLevel = clamped
        isCustomBrightness = true
        activity?.window?.let { win ->
            val lp = win.attributes
            lp.screenBrightness = clamped
            win.attributes = lp
        }
    }

    val resetSystemBrightness: () -> Unit = {
        isCustomBrightness = false
        brightnessLevel = initialSystemBrightness
        activity?.window?.let { win ->
            val lp = win.attributes
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            win.attributes = lp
        }
    }

    if (isFullscreen) {
        BackHandler { isFullscreen = false }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            VyronVideoViewport(
                player = player,
                hasMedia = selectedUri != null,
                toneConfig = toneConfig,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                isFullscreen = true,
                brightnessLevel = brightnessLevel,
                isCustomBrightness = isCustomBrightness,
                onBrightnessChange = applyWindowBrightness,
                onResetBrightness = resetSystemBrightness,
                onTogglePlayPause = onTogglePlayPause,
                onSeekTo = onSeekTo,
                onToggleFullscreen = { isFullscreen = false },
                onUpdateSplitFraction = onUpdateSplitFraction,
                onOpenVisionAi = { showVisionSheet = true },
                modifier = Modifier.fillMaxSize()
            )
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Top Studio Action Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "VYRON STUDIO",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = analysis?.displayName ?: "HDR10 -> SDR Real-Time Reference Engine",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                StatusPill(
                    label = toneConfig.mode.badge,
                    accentColor = ElectricCyan
                )
            }

            // Video Viewport / Empty Cinema Hero
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(ObsidianVoid)
                    .border(1.dp, HairlineBorder, RoundedCornerShape(16.dp))
            ) {
                if (selectedUri == null) {
                    EmptyCinemaHero(
                        onPickLocalVideo = onPickLocalVideo,
                        onOpenUrlDialog = { showUrlDialog = true }
                    )
                } else {
                    VyronVideoViewport(
                        player = player,
                        hasMedia = true,
                        toneConfig = toneConfig,
                        isPlaying = isPlaying,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        isFullscreen = false,
                        brightnessLevel = brightnessLevel,
                        isCustomBrightness = isCustomBrightness,
                        onBrightnessChange = applyWindowBrightness,
                        onResetBrightness = resetSystemBrightness,
                        onTogglePlayPause = onTogglePlayPause,
                        onSeekTo = onSeekTo,
                        onToggleFullscreen = { isFullscreen = true },
                        onUpdateSplitFraction = onUpdateSplitFraction,
                        onOpenVisionAi = { showVisionSheet = true },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Simple Screen Brightness Slider for HDR-to-SDR viewing
            ScreenBrightnessBar(
                brightnessLevel = brightnessLevel,
                isCustomBrightness = isCustomBrightness,
                onBrightnessChange = applyWindowBrightness,
                onResetAuto = resetSystemBrightness
            )

            // Media Source Action Strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onPickLocalVideo,
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("pick_local_video_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricCyan,
                        contentColor = Color.Black
                    )
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Load Video", fontWeight = FontWeight.SemiBold)
                }

                FilledTonalButton(
                    onClick = { showUrlDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("open_stream_url_button")
                ) {
                    Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Stream URI")
                }

                FilledTonalButton(
                    onClick = { showVisionSheet = true },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("open_vision_ai_sheet_button")
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = "VYRON VISION AI", tint = HdrAmber)
                }
            }

            AnimatedVisibility(visible = isAnalyzing) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Analyzing elementary stream & HDR10 metadata...", style = MaterialTheme.typography.labelMedium, color = ElectricCyan)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = ElectricCyan)
                }
            }

            // Module D: Fallback / Offline Transcode / Codec Error Card
            val showFallbackCard = pipelineStatus.executionState in listOf(
                PipelineExecutionState.FALLBACK_COMPATIBLE_ORIGINAL,
                PipelineExecutionState.OFFLINE_TRANSCODING,
                PipelineExecutionState.OFFLINE_TRANSCODE_READY,
                PipelineExecutionState.UNSUPPORTED_CODEC_ERROR
            ) || (analysis?.isHdr == true && selectedUri != null)

            if (showFallbackCard) {
                FallbackAndTranscodeCard(
                    pipelineStatus = pipelineStatus,
                    isHdrInput = analysis?.isHdr == true,
                    onFallbackCompatibleOriginal = onFallbackCompatibleOriginal,
                    onStartOfflineTranscode = onStartOfflineTranscode,
                    onCancelOfflineTranscode = onCancelOfflineTranscode
                )
            }

            // Real-Time Telemetry Mini Strip
            TelemetryMiniBar(telemetry = telemetry)

            // Quick A/B Split Comparison Toggle
            ComparisonToggleRow(
                enabled = toneConfig.comparisonEnabled,
                splitFraction = toneConfig.comparisonSplitFraction,
                onToggle = onToggleComparison,
                onSplitChanged = onUpdateSplitFraction
            )

            // 6-Field Signal Pipeline Readout Grid (Module F)
            PipelineReadoutGrid(
                analysis = analysis,
                pipelineStatus = pipelineStatus
            )

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showUrlDialog) {
        StreamUrlDialog(
            onDismiss = { showUrlDialog = false },
            onLoadUrl = { urlText ->
                showUrlDialog = false
                runCatching { Uri.parse(urlText.trim()) }.getOrNull()?.let(onLoadStreamUrl)
            }
        )
    }

    if (showVisionSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showVisionSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "VYRON VISION AI",
                        style = MaterialTheme.typography.headlineSmall,
                        color = ElectricCyan
                    )
                    StatusPill(label = toneConfig.activeQualityTier.label, accentColor = HdrAmber)
                }
                VisionAiModeSelector(
                    selectedMode = toneConfig.mode,
                    onSelectMode = onSelectVisionMode
                )
                ComparisonToggleRow(
                    enabled = toneConfig.comparisonEnabled,
                    splitFraction = toneConfig.comparisonSplitFraction,
                    onToggle = onToggleComparison,
                    onSplitChanged = onUpdateSplitFraction
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VyronVideoViewport(
    player: Player,
    hasMedia: Boolean,
    toneConfig: ToneMappingConfig,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    isFullscreen: Boolean,
    brightnessLevel: Float,
    isCustomBrightness: Boolean,
    onBrightnessChange: (Float) -> Unit,
    onResetBrightness: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleFullscreen: () -> Unit,
    onUpdateSplitFraction: (Float) -> Unit,
    onOpenVisionAi: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showOverlayBrightness by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { view ->
                if (view.player !== player) {
                    view.player = player
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Interactive Split-Screen Drag Overlay when Original vs Enhanced Comparison is active
        if (hasMedia && toneConfig.comparisonEnabled && toneConfig.mode != VyronVisionMode.ORIGINAL_PLAYBACK) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { change, _ ->
                            change.consume()
                            val frac = (change.position.x / size.width.toFloat()).coerceIn(0.08f, 0.92f)
                            onUpdateSplitFraction(frac)
                        }
                    }
            ) {
                val splitPx = constraints.maxWidth * toneConfig.comparisonSplitFraction
                Box(
                    modifier = Modifier
                        .offset { IntOffset((splitPx - 18).roundToInt(), 0) }
                        .fillMaxHeight()
                        .width(36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        color = ElectricCyan,
                        shape = CircleShape,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.Compare,
                                contentDescription = "Drag comparison wipe",
                                tint = Color.Black,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // Labels
                Surface(
                    color = Color.Black.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                ) {
                    Text(
                        "ORIGINAL",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
                Surface(
                    color = ElectricCyan.copy(alpha = 0.22f),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.7f)),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                ) {
                    Text(
                        "VYRON ENHANCED",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }
        }

        // Bottom Transport Overlay
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                    )
                )
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            AnimatedVisibility(visible = showOverlayBrightness) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Brightness6,
                        contentDescription = "Screen brightness",
                        tint = HdrAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Slider(
                        value = brightnessLevel,
                        onValueChange = onBrightnessChange,
                        valueRange = 0.05f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = HdrAmber,
                            activeTrackColor = HdrAmber,
                            inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(28.dp)
                    )
                    Text(
                        text = "${(brightnessLevel * 100f).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                    if (isCustomBrightness) {
                        TextButton(
                            onClick = onResetBrightness,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Text("Auto", style = MaterialTheme.typography.labelSmall, color = ElectricCyan)
                        }
                    }
                }
            }

            if (durationMs > 0L) {
                Slider(
                    value = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f),
                    onValueChange = { frac -> onSeekTo((frac * durationMs).toLong()) },
                    colors = SliderDefaults.colors(
                        thumbColor = ElectricCyan,
                        activeTrackColor = ElectricCyan,
                        inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("play_pause_button")
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White
                        )
                    }
                    Text(
                        text = "${formatTimeMs(positionMs)} / ${formatTimeMs(durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { showOverlayBrightness = !showOverlayBrightness },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("viewport_brightness_toggle")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Brightness6,
                            contentDescription = "Toggle screen brightness slider",
                            tint = if (showOverlayBrightness || isCustomBrightness) HdrAmber else Color.White
                        )
                    }
                    IconButton(
                        onClick = onOpenVisionAi,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = "VYRON VISION AI Settings",
                            tint = HdrAmber
                        )
                    }
                    IconButton(
                        onClick = onToggleFullscreen,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("fullscreen_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                            contentDescription = "Toggle fullscreen",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenBrightnessBar(
    brightnessLevel: Float,
    isCustomBrightness: Boolean,
    onBrightnessChange: (Float) -> Unit,
    onResetAuto: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (isCustomBrightness) HdrAmber.copy(alpha = 0.45f) else HairlineBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Brightness6,
                contentDescription = "Screen brightness",
                tint = HdrAmber,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "Brightness",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Slider(
                value = brightnessLevel,
                onValueChange = onBrightnessChange,
                valueRange = 0.05f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = HdrAmber,
                    activeTrackColor = HdrAmber,
                    inactiveTrackColor = HairlineBorder
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("screen_brightness_slider")
            )
            Text(
                text = if (isCustomBrightness) "${(brightnessLevel * 100f).roundToInt()}%" else "Auto",
                style = MaterialTheme.typography.labelSmall,
                color = if (isCustomBrightness) HdrAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(36.dp)
            )
            if (isCustomBrightness) {
                TextButton(
                    onClick = onResetAuto,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("brightness_auto_button")
                ) {
                    Text("Reset", style = MaterialTheme.typography.labelSmall, color = ElectricCyan)
                }
            }
        }
    }
}

@Composable
private fun EmptyCinemaHero(
    onPickLocalVideo: () -> Unit,
    onOpenUrlDialog: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.img_hdr_cinema_hero_1794903279405),
            contentDescription = "Vyron HDR Calibration Hero",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.25f),
                            Color.Black.copy(alpha = 0.85f)
                        )
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.MovieCreation, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(18.dp))
                Text(
                    "4K HDR10 -> SDR STUDIO MONITOR",
                    style = MaterialTheme.typography.labelMedium,
                    color = ElectricCyan
                )
            }
            Text(
                "Load an HEVC Main10 HDR10 file or stream URI to inspect & tone-map live on GPU.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.88f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onPickLocalVideo) {
                    Text("Pick Local Video", color = ElectricCyan, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onOpenUrlDialog) {
                    Text("Enter Stream URL", color = HdrAmber, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun FallbackAndTranscodeCard(
    pipelineStatus: PlaybackPipelineStatus,
    isHdrInput: Boolean,
    onFallbackCompatibleOriginal: () -> Unit,
    onStartOfflineTranscode: (Int) -> Unit,
    onCancelOfflineTranscode: () -> Unit
) {
    val isError = pipelineStatus.executionState == PipelineExecutionState.UNSUPPORTED_CODEC_ERROR
    val borderColor = if (isError) CoralThermal else HdrAmber.copy(alpha = 0.55f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isError) Icons.Filled.ErrorOutline else Icons.Filled.Transform,
                    contentDescription = null,
                    tint = if (isError) CoralThermal else HdrAmber
                )
                Text(
                    text = if (isError) "CODEC COMPATIBILITY / FALLBACK" else "OFFLINE MEDIA3 TRANSFORMER FALLBACK",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isError) CoralThermal else HdrAmber
                )
            }

            if (pipelineStatus.fallbackReason != null) {
                Text(
                    text = pipelineStatus.fallbackReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (isHdrInput) {
                Text(
                    text = "If live 4K HDR10 GPU playback drops frames or your panel driver prefers pre-baked SDR, export a compatible 720p/1080p SDR BT.709 MP4 via Media3 Transformer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (pipelineStatus.executionState == PipelineExecutionState.OFFLINE_TRANSCODING) {
                LinearProgressIndicator(
                    progress = { pipelineStatus.offlineTranscodeProgressPercent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = HdrAmber
                )
                OutlinedButton(
                    onClick = onCancelOfflineTranscode,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text("Cancel Transcode")
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onFallbackCompatibleOriginal,
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                    ) {
                        Text("Original Playback", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Button(
                        onClick = { onStartOfflineTranscode(720) },
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("offline_transcode_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = HdrAmber, contentColor = Color.Black)
                    ) {
                        Text("Transcode 720p SDR", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamUrlDialog(
    onDismiss: () -> Unit,
    onLoadUrl: (String) -> Unit
) {
    var urlInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SlateElevated,
        title = { Text("Load Video Stream URI", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Enter an HTTP/HTTPS MP4, MKV, HLS (.m3u8), or DASH (.mpd) test stream URL.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("Stream URL") },
                    placeholder = { Text("https://...") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("stream_url_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onLoadUrl(urlInput) },
                enabled = urlInput.isNotBlank(),
                modifier = Modifier.testTag("confirm_stream_url_button")
            ) {
                Text("Analyze & Play")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun formatTimeMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
