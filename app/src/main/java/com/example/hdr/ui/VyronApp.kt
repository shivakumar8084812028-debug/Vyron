package com.example.hdr.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.hdr.ui.screens.DiagnosticsLabScreen
import com.example.hdr.ui.screens.PlayerStudioScreen
import com.example.hdr.ui.screens.VisionAiLabScreen
import com.example.ui.theme.CarbonPanel
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ObsidianVoid
import kotlinx.serialization.Serializable

@Serializable object PlayerRoute
@Serializable object VisionAiRoute
@Serializable object DiagnosticsRoute

private data class NavTab(
    val route: Any,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val VyronTabs = listOf(
    NavTab(PlayerRoute, "Studio", Icons.Filled.PlayCircle, Icons.Outlined.PlayCircle, "nav_studio"),
    NavTab(VisionAiRoute, "Vision AI", Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome, "nav_vision_ai"),
    NavTab(DiagnosticsRoute, "Lab", Icons.Filled.Analytics, Icons.Outlined.Analytics, "nav_lab")
)

@Composable
fun VyronApp(
    widthSizeClass: WindowWidthSizeClass,
    viewModel: VyronEngineViewModel = viewModel()
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination

    val selectedUri by viewModel.selectedUri.collectAsStateWithLifecycle()
    val analysis by viewModel.videoAnalysis.collectAsStateWithLifecycle()
    val isAnalyzing by viewModel.isAnalyzingVideo.collectAsStateWithLifecycle()
    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
    val isProbingHardware by viewModel.isProbingHardware.collectAsStateWithLifecycle()
    val pipelineStatus by viewModel.pipelineStatus.collectAsStateWithLifecycle()
    val toneConfig by viewModel.toneConfig.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val telemetryHistory by viewModel.telemetryHistory.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val positionMs by viewModel.playbackPositionMs.collectAsStateWithLifecycle()
    val durationMs by viewModel.playbackDurationMs.collectAsStateWithLifecycle()
    val activeBenchmark by viewModel.activeBenchmark.collectAsStateWithLifecycle()
    val completedBenchmarks by viewModel.completedBenchmarks.collectAsStateWithLifecycle()

    // Zero-permission Storage Access Framework video picker (complies with Google Play Policy)
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.loadMediaUri(uri)
        }
    }

    val useNavRail = widthSizeClass != WindowWidthSizeClass.Compact

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = ObsidianVoid,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (!useNavRail) {
                NavigationBar(containerColor = CarbonPanel) {
                    VyronTabs.forEach { tab ->
                        val selected = currentDest?.hasRoute(tab.route::class) == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                    contentDescription = tab.label
                                )
                            },
                            label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = ElectricCyan,
                                selectedTextColor = ElectricCyan,
                                indicatorColor = ElectricCyan.copy(alpha = 0.16f),
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.testTag(tab.testTag)
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (useNavRail) {
                NavigationRail(containerColor = CarbonPanel) {
                    VyronTabs.forEach { tab ->
                        val selected = currentDest?.hasRoute(tab.route::class) == true
                        NavigationRailItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                    contentDescription = tab.label
                                )
                            },
                            label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = ElectricCyan,
                                selectedTextColor = ElectricCyan,
                                indicatorColor = ElectricCyan.copy(alpha = 0.16f),
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.testTag(tab.testTag)
                        )
                    }
                }
            }

            NavHost(
                navController = navController,
                startDestination = PlayerRoute,
                modifier = Modifier.weight(1f).fillMaxSize()
            ) {
                composable<PlayerRoute> {
                    PlayerStudioScreen(
                        player = viewModel.getPlayer(),
                        selectedUri = selectedUri,
                        analysis = analysis,
                        isAnalyzing = isAnalyzing,
                        pipelineStatus = pipelineStatus,
                        toneConfig = toneConfig,
                        telemetry = telemetry,
                        isPlaying = isPlaying,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        onPickLocalVideo = { videoPickerLauncher.launch(arrayOf("video/*")) },
                        onLoadStreamUrl = { uri -> viewModel.loadMediaUri(uri) },
                        onTogglePlayPause = { viewModel.togglePlayPause() },
                        onSeekTo = { ms -> viewModel.seekTo(ms) },
                        onSelectVisionMode = { mode -> viewModel.setVisionMode(mode) },
                        onToggleComparison = { enabled ->
                            viewModel.updateToneParameters { it.copy(comparisonEnabled = enabled) }
                        },
                        onUpdateSplitFraction = { frac ->
                            viewModel.updateToneParameters { it.copy(comparisonSplitFraction = frac) }
                        },
                        onFallbackCompatibleOriginal = { viewModel.switchToCompatibleOriginalFallback() },
                        onStartOfflineTranscode = { h -> viewModel.startOfflineTranscode(h) },
                        onCancelOfflineTranscode = { viewModel.cancelOfflineTranscode() }
                    )
                }

                composable<VisionAiRoute> {
                    VisionAiLabScreen(
                        config = toneConfig,
                        analysis = analysis,
                        onSelectMode = { mode -> viewModel.setVisionMode(mode) },
                        onUpdateConfig = { transform -> viewModel.updateToneParameters(transform) }
                    )
                }

                composable<DiagnosticsRoute> {
                    DiagnosticsLabScreen(
                        analysis = analysis,
                        capabilities = capabilities,
                        isProbingHardware = isProbingHardware,
                        telemetry = telemetry,
                        telemetryHistory = telemetryHistory,
                        activeBenchmark = activeBenchmark,
                        completedBenchmarks = completedBenchmarks,
                        onReprobeHardware = { viewModel.runHardwareProbe() },
                        onStartBenchmark = { preset -> viewModel.startBenchmark(preset) },
                        onStopBenchmark = { viewModel.stopBenchmark() },
                        onShareBenchmarkJson = {
                            val jsonPayload = viewModel.exportBenchmarkJson()
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_SUBJECT, "Vyron HDR Engine Benchmark Report")
                                putExtra(Intent.EXTRA_TEXT, jsonPayload)
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Export Vyron Benchmark JSON"))
                        }
                    )
                }
            }
        }
    }
}
