package app.lrcget.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lrcget.android.data.LyricsLookupResult
import app.lrcget.android.model.DownloadMode
import app.lrcget.android.model.LyricsOutputMode
import app.lrcget.android.model.LyricsStatus
import app.lrcget.android.model.ThemeMode
import app.lrcget.android.model.TrackItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableHighRefreshRate()
        setContent {
            val viewModel: MainViewModel = viewModel()
            val state by viewModel.state.collectAsState()
            LrcgetTheme(
                themeMode = state.themeMode,
                isAmoled = state.isAmoled
            ) {
                MainScreen(viewModel)
            }
        }
    }

    private fun enableHighRefreshRate() {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        } ?: return

        val modes = display.supportedModes
        val maxRefreshRate = modes.maxOfOrNull { it.refreshRate } ?: 0f
        if (maxRefreshRate > 60f) {
            val params = window.attributes
            params.preferredRefreshRate = maxRefreshRate
            window.attributes = params
        }
    }
}

@Composable
private fun LrcgetTheme(
    themeMode: ThemeMode,
    isAmoled: Boolean,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val darkTheme = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    val colors = remember(darkTheme, isAmoled) {
        val baseColors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            if (darkTheme) darkColorScheme() else lightColorScheme()
        }

        if (darkTheme && isAmoled) {
            baseColors.copy(
                surface = Color.Black,
                background = Color.Black,
                surfaceVariant = Color(0xFF1A1A1A),
                surfaceContainer = Color(0xFF0D0D0D),
                surfaceContainerLow = Color(0xFF080808),
                surfaceContainerLowest = Color.Black,
                surfaceContainerHigh = Color(0xFF1A1A1A),
                surfaceContainerHighest = Color(0xFF222222),
                outline = Color(0xFF333333),
                outlineVariant = Color(0xFF222222),
                onSurface = Color.White,
                onBackground = Color.White
            )
        } else {
            baseColors
        }
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        LaunchedEffect(darkTheme) {
            val window = (context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
    val isDragged by pagerState.interactionSource.collectIsDraggedAsState()

    LaunchedEffect(state.selectedTab) {
        if (pagerState.currentPage != state.selectedTab) {
            pagerState.animateScrollToPage(state.selectedTab)
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        if (isDragged && state.selectedTab != pagerState.currentPage) {
            viewModel.setSelectedTab(pagerState.currentPage)
        }
    }

    PredictiveBackHandler(enabled = state.selectedTab != 0) { progress ->
        try {
            progress.collect { }
            if (state.selectedTab == 2) {
                viewModel.setSelectedTab(if (state.previousTab == 2) 0 else state.previousTab)
            } else {
                viewModel.setSelectedTab(0)
            }
        } catch (e: CancellationException) {
            // Back gesture cancelled
        }
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        requestBatteryOptimizationExemption(context)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.setLibrary(uri)
        }
    }

    val lrcSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null && state.previewLyrics != null) {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(state.previewLyrics!!.toByteArray())
            }
        }
    }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionsLauncher.launch(permissions.toTypedArray())
        
        if (state.libraryUri == null) {
            folderPicker.launch(null)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Text(
                        text = if (state.selectedTab == 2) "Settings" else "LRCGET", 
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    ) 
                },
                navigationIcon = {
                    if (state.selectedTrackIds.isNotEmpty()) {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Default.Close, contentDescription = "Clear Selection")
                        }
                    }
                },
                actions = {
                    if (state.selectedTrackIds.isNotEmpty()) {
                        IconButton(onClick = viewModel::deleteSelectedLyrics) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Lyrics", tint = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        if (state.isDownloadingAll && !state.showDownloadProgress) {
                            IconButton(onClick = { viewModel.setShowDownloadProgress(true) }) {
                                Icon(Icons.Default.CloudDownload, contentDescription = "Show Progress", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        if (state.libraryUri != null && !state.isDownloadingAll) {
                            IconButton(onClick = { viewModel.setShowExportDialog(true) }) {
                                Icon(Icons.Default.Output, contentDescription = "Export Lyrics")
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 3.dp
            ) {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    onClick = { viewModel.setSelectedTab(0) },
                    icon = { Icon(Icons.Default.Lyrics, null) },
                    label = { Text("Tracks") }
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    onClick = { viewModel.setSelectedTab(1) },
                    icon = { Icon(Icons.Default.TravelExplore, null) },
                    label = { Text("LRCLIB") }
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 2,
                    onClick = { viewModel.setSelectedTab(2) },
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text("Settings") }
                )
            }
        }
    ) { padding ->
        Surface(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1
            ) { page ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (page) {
                        0 -> {
                            ActionPanel(
                                tracksSize = state.tracks.size,
                                savedCount = state.savedCount,
                                missingCount = state.missingCount,
                                isBusy = state.isBusy,
                                isDownloadingAll = state.isDownloadingAll,
                                hasLibrary = state.libraryUri != null,
                                message = state.message,
                                operationProgress = state.operationProgress,
                                operationTotal = state.operationTotal,
                                onChooseFolder = { folderPicker.launch(null) },
                                onScan = viewModel::scan,
                                onDownload = {
                                    if (state.libraryUri != null) {
                                        viewModel.downloadAll()
                                    }
                                }
                            )

                            val tracks = state.tracks
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 16.dp)
                            ) {
                                items(
                                    items = tracks,
                                    key = { it.id },
                                    contentType = { "track" }
                                ) { track ->
                                    TrackRow(
                                        track = track,
                                        isSelected = state.selectedTrackIds.contains(track.id),
                                        onToggleSelection = { viewModel.toggleTrackSelection(track.id) },
                                        onPreview = viewModel::previewLyrics,
                                        onDownload = viewModel::downloadTrack
                                    )
                                }
                            }
                        }

                        1 -> {
                            Box(modifier = Modifier.weight(1f)) {
                                ManualSearchPanel(
                                    state = state,
                                    onQueryChange = viewModel::setManualSearchQuery,
                                    onSearch = viewModel::searchLyricsManual,
                                    onDownload = { lyrics, track ->
                                        viewModel.downloadManualLyrics(lyrics, track)
                                    },
                                    onSaveToLocation = { result ->
                                        viewModel.selectPreviewLyrics(result.lyrics)
                                        lrcSaver.launch("${result.artistName} - ${result.trackName}.lrc")
                                    }
                                )
                            }
                        }

                        2 -> {
                            Box(modifier = Modifier.weight(1f)) {
                                SettingsPanel(
                                    state = state,
                                    onToggleOutputMode = viewModel::toggleOutputMode,
                                    onSearchDelayChanged = viewModel::setSearchDelay,
                                    onDownloadModeChanged = viewModel::setDownloadMode,
                                    onThemeModeChanged = viewModel::setThemeMode,
                                    onAmoledChanged = viewModel::setAmoled,
                                    onAddLibrary = { folderPicker.launch(null) },
                                    onRemoveLibrary = viewModel::removeLibrary
                                )
                            }
                        }
                    }
                }
            }

            if (state.showExportDialog) {
                ExportDialog(
                    outputModes = state.outputModes,
                    onToggleMode = viewModel::toggleOutputMode,
                    onExport = {
                        viewModel.setShowExportDialog(false)
                        viewModel.exportAll()
                    },
                    onDismiss = { viewModel.setShowExportDialog(false) }
                )
            }

            if (state.showDownloadProgress) {
                DownloadProgressDialog(
                    state = state,
                    onStop = viewModel::stopDownload,
                    onMinimize = { viewModel.setShowDownloadProgress(false) },
                    onDismiss = { viewModel.setShowDownloadProgress(false) }
                )
            }

            if (state.previewTrack != null) {
                LyricsPreviewDialog(
                    track = state.previewTrack,
                    results = state.previewResults,
                    isBusy = state.isBusy,
                    onSearch = viewModel::searchLyricsForPreview,
                    onSave = { lyrics ->
                        state.previewTrack?.let { track ->
                            viewModel.downloadManualLyrics(lyrics, track)
                        }
                    },
                    onDismiss = viewModel::closePreview
                )
            }
        }
    }
}

@Composable
private fun DownloadProgressDialog(
    state: MainUiState,
    onStop: () -> Unit,
    onMinimize: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            val title = if (state.message.contains("Exporting")) "Exporting Lyrics" else "Finding Lyrics"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = onMinimize) {
                    Icon(Icons.Default.Close, contentDescription = "Minimize")
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                LinearProgressIndicator(
                    progress = { if (state.operationTotal > 0) state.operationProgress.toFloat() / state.operationTotal else 0f },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(MaterialTheme.shapes.medium)
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("${state.foundCount} FOUND", style = MaterialTheme.typography.labelSmall)
                    Text("${state.notFoundCount} NOT FOUND", style = MaterialTheme.typography.labelSmall)
                    Text("${state.operationTotal} TOTAL", style = MaterialTheme.typography.labelSmall)
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                        .padding(8.dp)
                ) {
                    LazyColumn {
                        items(state.downloadLog) { log ->
                            Text(
                                log, 
                                style = MaterialTheme.typography.bodySmall,
                                color = if (log.contains("No lyrics") || log.contains("Failed") || log.contains("NotFound")) 
                                    MaterialTheme.colorScheme.error 
                                else 
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Button(
                    onClick = onStop,
                    enabled = state.isDownloadingAll,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFB71C1C),
                        contentColor = Color.White
                    ),
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Text("STOP")
                }
            }
        },
        dismissButton = {
            if (!state.isDownloadingAll) {
                TextButton(onClick = onDismiss) {
                    Text("CLOSE")
                }
            }
        }
    )
}

@Composable
private fun ActionPanel(
    tracksSize: Int,
    savedCount: Int,
    missingCount: Int,
    isBusy: Boolean,
    isDownloadingAll: Boolean,
    hasLibrary: Boolean,
    message: String,
    operationProgress: Int,
    operationTotal: Int,
    onChooseFolder: () -> Unit,
    onScan: () -> Unit,
    onDownload: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.padding(bottom = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onChooseFolder, 
                    enabled = !isBusy,
                    colors = IconButtonDefaults.filledTonalIconButtonColors()
                ) {
                    Icon(Icons.Default.CreateNewFolder, "Add Folder", modifier = Modifier.size(20.dp))
                }
                
                IconButton(
                    onClick = onScan, 
                    enabled = hasLibrary && !isBusy,
                    colors = IconButtonDefaults.filledTonalIconButtonColors()
                ) {
                    Icon(Icons.Default.Refresh, "Scan", modifier = Modifier.size(20.dp))
                }
                
                Button(
                    onClick = onDownload,
                    enabled = tracksSize > 0 && !isBusy,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.large,
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Find Lyrics Online", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            if (isBusy || isDownloadingAll) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (operationTotal > 0) {
                        LinearProgressIndicator(
                            progress = { operationProgress.toFloat() / operationTotal },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(MaterialTheme.shapes.extraLarge),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            strokeCap = StrokeCap.Round
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(MaterialTheme.shapes.extraLarge),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            strokeCap = StrokeCap.Round
                        )
                    }
                    
                    val progressText = if (isDownloadingAll) {
                        "Searching lyrics ($operationProgress / $operationTotal)"
                    } else if (operationTotal > 0) {
                        "$message ($operationProgress / $operationTotal)"
                    } else if (operationTotal < 0) {
                        "Scanning folders: $operationProgress folders, ${-operationTotal} audio files found"
                    } else {
                        message
                    }
                    
                    Text(
                        text = progressText,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "$savedCount / $tracksSize saved",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (missingCount > 0) {
                        Text(
                            text = "$missingCount missing",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            if (message.isNotBlank() && !message.contains("Scanning") && !isBusy && !isDownloadingAll) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: TrackItem,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onPreview: (TrackItem) -> Unit,
    onDownload: (TrackItem) -> Unit
) {
    val statusLabel = track.status.label(track)

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleSelection() }
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection() }
                )
                Column {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    if (statusLabel.isNotBlank() || track.isInstrumental) {
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            modifier = Modifier.padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (track.isInstrumental && !statusLabel.contains("Instrumental", ignoreCase = true)) {
                                StatusBadge("Instrumental")
                            }
                            
                            statusLabel.split(",").filter { it.isNotBlank() }.forEach { label ->
                                StatusBadge(label.trim())
                            }
                        }
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onPreview(track) }) {
                    Icon(
                        Icons.Default.Visibility,
                        contentDescription = "View",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(onClick = { onDownload(track) }) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "Download",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String) {
    val (containerColor, contentColor) = when {
        text.contains("synced", ignoreCase = true) -> 
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        text.contains("LRC", ignoreCase = true) -> 
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        text.contains("plain", ignoreCase = true) || text.contains("Text", ignoreCase = true) -> 
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        text.contains("Deleted", ignoreCase = true) || text.contains("Failed", ignoreCase = true) || text.contains("Missing", ignoreCase = true) -> 
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else -> 
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.extraSmall
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ManualSearchPanel(
    state: MainUiState,
    onQueryChange: (String) -> Unit,
    onSearch: (String, String, String) -> Unit,
    onDownload: (LyricsLookupResult, TrackItem) -> Unit,
    onSaveToLocation: (LyricsLookupResult) -> Unit
) {
    var query by remember { mutableStateOf(state.manualSearchQuery) }
    var previewResult by remember { mutableStateOf<LyricsLookupResult?>(null) }

    if (state.isDownloadingAll) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.large,
                tonalElevation = 2.dp
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "Manual search is unavailable while 'Find Lyrics Online' is running.",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "To search for a specific track now, use the preview icon in the Tracks tab.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        return
    }

    LaunchedEffect(state.manualSearchQuery) {
        if (state.manualSearchQuery != query) {
            query = state.manualSearchQuery
        }
    }

    if (previewResult != null) {
        AlertDialog(
            onDismissRequest = { previewResult = null },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            title = { Text("${previewResult!!.artistName} - ${previewResult!!.trackName}") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = MaterialTheme.shapes.medium
                        )
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = previewResult!!.lyrics,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    onSaveToLocation(previewResult!!)
                    previewResult = null
                }) {
                    Text("Save .lrc File")
                }
            },
            dismissButton = {
                TextButton(onClick = { previewResult = null }) {
                    Text("Close")
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "Search with LRCLIB instance:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        "https://lrclib.net",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { 
                    query = it
                    onQueryChange(it)
                },
                placeholder = { 
                    Text(
                        "Song title, artist, or album...",
                        style = MaterialTheme.typography.bodyMedium
                    ) 
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                shape = MaterialTheme.shapes.extraLarge,
                singleLine = true,
                maxLines = 1,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        if (query.isNotBlank()) onSearch(query, "", "")
                    }
                ),
                trailingIcon = {
                    IconButton(
                        onClick = { if (query.isNotBlank()) onSearch(query, "", "") },
                        enabled = query.isNotBlank() && !state.isSearching
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )
            )
            
            if (state.isSearching) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.9f))
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            if (state.searchResults.isNotEmpty()) {
                item {
                    Text(
                        "Search Results:", 
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    )
                }
                itemsIndexed(
                    items = state.searchResults,
                    key = { index, item -> "${index}-${item.trackName}-${item.lyrics.hashCode()}" }
                ) { _, result ->
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                        LyricsResultCard(
                            result = result,
                            onPreview = { previewResult = it },
                            onSave = { onSaveToLocation(it) }
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun SettingsPanel(
    state: MainUiState,
    onToggleOutputMode: (LyricsOutputMode) -> Unit,
    onSearchDelayChanged: (Int) -> Unit,
    onDownloadModeChanged: (DownloadMode) -> Unit,
    onThemeModeChanged: (ThemeMode) -> Unit,
    onAmoledChanged: (Boolean) -> Unit,
    onAddLibrary: () -> Unit,
    onRemoveLibrary: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Library Management", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Music Folder", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = state.libraryUri?.path?.substringAfterLast('/') ?: "No folder selected",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row {
                            IconButton(onClick = onAddLibrary) {
                                Icon(Icons.Default.Edit, contentDescription = "Change Folder")
                            }
                            if (state.libraryUri != null) {
                                IconButton(onClick = onRemoveLibrary) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove Folder", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Appearance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            
            Text("Theme Mode", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.themeMode == mode,
                        onClick = { onThemeModeChanged(mode) },
                        label = { Text(mode.name) }
                    )
                }
            }

            if (state.themeMode != ThemeMode.Light) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = state.isAmoled,
                        onCheckedChange = onAmoledChanged
                    )
                    Column {
                        Text("AMOLED Black", style = MaterialTheme.typography.bodyMedium)
                        Text("Use pure black background in dark mode", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("General", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            
            Text("Download lyrics for:", style = MaterialTheme.typography.bodyMedium)
            
            DownloadModeOption(
                selected = state.downloadMode == DownloadMode.All,
                onClick = { onDownloadModeChanged(DownloadMode.All) },
                label = "All tracks (overwrite existing lyrics)"
            )
            DownloadModeOption(
                selected = state.downloadMode == DownloadMode.MissingSynced,
                onClick = { onDownloadModeChanged(DownloadMode.MissingSynced) },
                label = "Only tracks without synced lyrics"
            )
            DownloadModeOption(
                selected = state.downloadMode == DownloadMode.MissingAny,
                onClick = { onDownloadModeChanged(DownloadMode.MissingAny) },
                label = "Only tracks without any lyrics"
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Lyrics save mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = state.outputModes.contains(LyricsOutputMode.LrcFile),
                    onClick = { onToggleOutputMode(LyricsOutputMode.LrcFile) },
                    label = { Text("Separate LRC") }
                )
                FilterChip(
                    selected = state.outputModes.contains(LyricsOutputMode.PlainTextFile),
                    onClick = { onToggleOutputMode(LyricsOutputMode.PlainTextFile) },
                    label = { Text("Plain Text") }
                )
                FilterChip(
                    selected = state.outputModes.contains(LyricsOutputMode.EmbeddedSynced),
                    onClick = { onToggleOutputMode(LyricsOutputMode.EmbeddedSynced) },
                    label = { Text("Embed synced") }
                )
            }
            Text(
                "Select one or more modes. Separate LRC and Plain Text modes save files next to audio. Embedded mode writes ID3 SYLT synced lyrics for MP3 files.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Rate limiting", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Delay between each lyrics search:", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(1, 3, 5, 10).forEach { seconds ->
                    FilterChip(
                        selected = state.searchDelay == seconds,
                        onClick = { onSearchDelayChanged(seconds) },
                        label = { Text("${seconds}s") }
                    )
                }
            }
            Text(
                "Higher delay helps avoid being rate-limited by LRCLIB during large scans.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("About & Credits", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "LRCGET Android v1.0.0",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Native Android adaptation based on the original LRCget for Windows.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    
                    Text(
                        text = "Original Developer",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    TextButton(
                        onClick = { 
                            val intent = Intent(Intent.ACTION_VIEW, "https://github.com/tranxuanthang/lrcget".toUri())
                            context.startActivity(intent)
                        },
                        modifier = Modifier.offset(x = (-12).dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = "tranxuanthang/lrcget",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Text(
                        text = "Open Source Licenses",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        text = "• Jetpack Compose & Material 3 (Apache 2.0)\n• AndroidX Libraries (Apache 2.0)\n• Kotlin Coroutines (Apache 2.0)\n• LRCLIB API (Public Domain/Community)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun DownloadModeOption(
    selected: Boolean,
    onClick: () -> Unit,
    label: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ExportDialog(
    outputModes: Set<LyricsOutputMode>,
    onToggleMode: (LyricsOutputMode) -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        title = {
            Text(
                "EXPORT ALL LYRICS TO TRACKS' DIRECTORY:",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportOption(
                    label = "Plain lyrics (.txt)",
                    checked = outputModes.contains(LyricsOutputMode.PlainTextFile),
                    onCheckedChange = { onToggleMode(LyricsOutputMode.PlainTextFile) }
                )
                ExportOption(
                    label = "Synced lyrics (.lrc)",
                    checked = outputModes.contains(LyricsOutputMode.LrcFile),
                    onCheckedChange = { onToggleMode(LyricsOutputMode.LrcFile) }
                )
                ExportOption(
                    label = "Embed into track",
                    checked = outputModes.contains(LyricsOutputMode.EmbeddedSynced),
                    onCheckedChange = { onToggleMode(LyricsOutputMode.EmbeddedSynced) }
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Button(
                    onClick = onExport,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    enabled = outputModes.isNotEmpty()
                ) {
                    Text("EXPORT", fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

@Composable
private fun ExportOption(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun LyricsPreviewDialog(
    track: TrackItem?,
    results: List<LyricsLookupResult>,
    isBusy: Boolean,
    onSearch: (String, String, String) -> Unit,
    onSave: (LyricsLookupResult) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedResultForPreview by remember { mutableStateOf<LyricsLookupResult?>(null) }
    var title by remember { mutableStateOf(track?.title ?: "") }
    var artist by remember { mutableStateOf(track?.artist ?: "") }
    var album by remember { mutableStateOf(track?.album ?: "") }
    var showSearchFields by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = if (selectedResultForPreview != null) { { selectedResultForPreview = null } } else onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (selectedResultForPreview != null) "Lyrics Preview" else (track?.title ?: "Search Results"),
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Visible
                    )
                    if (selectedResultForPreview != null) {
                        Text(
                            text = "${selectedResultForPreview!!.artistName} - ${selectedResultForPreview!!.trackName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (track != null) {
                        Text(
                            text = "${track.artist} - ${track.album}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (selectedResultForPreview == null) {
                    IconButton(onClick = { showSearchFields = !showSearchFields }) {
                        Icon(if (showSearchFields) Icons.Default.Close else Icons.Default.Search, contentDescription = "Toggle Search")
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (showSearchFields && selectedResultForPreview == null) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("Title") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = album,
                                onValueChange = { album = it },
                                label = { Text("Album") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = artist,
                                onValueChange = { artist = it },
                                label = { Text("Artist") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                        Button(
                            onClick = { onSearch(title, artist, album) },
                            enabled = title.isNotBlank(),
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text("SEARCH")
                        }
                    }
                }

                if (selectedResultForPreview != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 450.dp)
                            .background(
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = MaterialTheme.shapes.medium
                            )
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = selectedResultForPreview!!.lyrics,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                } else if (isBusy) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (results.isEmpty()) {
                            Text("No matches found on LRCLIB.")
                        } else {
                            Text(
                                "Found ${results.size} matches:",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            LazyColumn(
                                modifier = Modifier
                                    .heightIn(max = 400.dp)
                                    .fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                itemsIndexed(
                                    items = results,
                                    key = { index, result -> "${result.trackName}-${result.lyrics.hashCode()}-$index" },
                                    contentType = { _, _ -> "lyrics_result" }
                                ) { _, result ->
                                    LyricsResultCard(
                                        result = result,
                                        onPreview = { selectedResultForPreview = result },
                                        onSave = { onSave(result) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (selectedResultForPreview != null) {
                Button(onClick = { 
                    onSave(selectedResultForPreview!!)
                    selectedResultForPreview = null 
                }) {
                    Text("Save This Version")
                }
            }
        },
        dismissButton = {
            if (selectedResultForPreview != null) {
                TextButton(onClick = { selectedResultForPreview = null }) {
                    Text("Back")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    )
}

@Composable
private fun LyricsResultCard(
    result: LyricsLookupResult,
    onPreview: (LyricsLookupResult) -> Unit,
    onSave: (LyricsLookupResult) -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.trackName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                
                Row(
                    verticalAlignment = Alignment.CenterVertically, 
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    val lineCount = result.lineCount
                    Badge(containerColor = Color.DarkGray, contentColor = Color.White) {
                        Text("$lineCount Lines", modifier = Modifier.padding(horizontal = 4.dp))
                    }
                    
                    if (result.isInstrumental) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ) {
                            Text("Instrumental", modifier = Modifier.padding(horizontal = 4.dp))
                        }
                    } else if (result.isSynced) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ) {
                            Text("Synced", modifier = Modifier.padding(horizontal = 4.dp))
                        }
                    }
                }

                Text(
                    text = "${result.albumName} | ${result.artistName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { onPreview(result) }) {
                    Icon(Icons.Default.Visibility, contentDescription = "View", modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = { onSave(result) }) {
                    Icon(Icons.Default.Save, contentDescription = "Save", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}


private fun LyricsStatus.color() = when (this) {
    LyricsStatus.Found, LyricsStatus.Saved -> Color(0xFF2E7D32) // Green
    LyricsStatus.Missing, LyricsStatus.Failed -> Color(0xFFC62828) // Red
    LyricsStatus.Skipped -> Color(0xFF757575) // Gray
    else -> Color(0xFF757575)
}

private fun LyricsStatus.label(track: TrackItem? = null): String = when (this) {
    LyricsStatus.Ready -> track?.message?.takeIf { it.isNotBlank() } ?: "Not checked"
    LyricsStatus.Found -> track?.message?.takeIf { it.isNotBlank() } ?: "Lyrics found"
    LyricsStatus.Downloading -> "Searching"
    LyricsStatus.Saved -> track?.message?.takeIf { it.isNotBlank() } ?: "Lyrics found"
    LyricsStatus.Missing -> "No lyrics"
    LyricsStatus.Skipped -> track?.message?.takeIf { it.isNotBlank() } ?: "Skipped"
    LyricsStatus.Failed -> track?.message?.takeIf { it.isNotBlank() } ?: "Failed"
    else -> name
}

private fun requestBatteryOptimizationExemption(context: android.content.Context) {
    val powerManager = context.getSystemService(PowerManager::class.java)
    if (powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true) return

    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                "package:${context.packageName}".toUri()
            )
        )
    }
}
