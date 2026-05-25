package app.lrcget.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
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
import app.lrcget.android.service.LyricsDownloadService

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
                surfaceContainer = Color.Black,
                surfaceContainerLow = Color.Black,
                surfaceContainerLowest = Color.Black,
                surfaceContainerHigh = Color(0xFF1A1A1A),
                surfaceContainerHighest = Color(0xFF222222),
                outline = Color(0xFF333333),
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
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    BackHandler(enabled = state.selectedTab != 0) {
        if (state.selectedTab == 2) {
            viewModel.setSelectedTab(if (state.previousTab == 2) 0 else state.previousTab)
        } else {
            viewModel.setSelectedTab(0)
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

    LaunchedEffect(Unit) {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionsLauncher.launch(permissions.toTypedArray())
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
                    if (state.selectedTab == 2) {
                        IconButton(onClick = { viewModel.setSelectedTab(if (state.previousTab == 2) 0 else state.previousTab) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (state.selectedTab != 2) {
                        IconButton(onClick = { viewModel.setSelectedTab(2) }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { padding ->
        Surface(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AnimatedContent(
                    targetState = state.selectedTab,
                    transitionSpec = {
                        if (targetState == 2 || initialState == 2) {
                            if (targetState == 2) {
                                (slideInHorizontally { it } + fadeIn()).togetherWith(slideOutHorizontally { -it } + fadeOut())
                            } else {
                                (slideInHorizontally { -it } + fadeIn()).togetherWith(slideOutHorizontally { it } + fadeOut())
                            }
                        } else {
                            fadeIn().togetherWith(fadeOut())
                        }
                    },
                    label = "tabTransition",
                    modifier = Modifier.weight(1f)
                ) { targetTab ->
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (targetTab != 2) {
                            ActionPanel(
                                tracksSize = state.tracks.size,
                                savedCount = state.savedCount,
                                missingCount = state.missingCount,
                                isBusy = state.isBusy,
                                hasLibrary = state.libraryUri != null,
                                message = state.message,
                                onChooseFolder = { folderPicker.launch(null) },
                                onScan = viewModel::scan,
                                onDownload = {
                                    state.libraryUri?.let { uri ->
                                        viewModel.downloadAll()
                                        LyricsDownloadService.start(
                                            context = context,
                                            rootUri = uri,
                                            downloadMode = state.downloadMode,
                                            outputModes = state.outputModes,
                                            searchDelay = state.searchDelay
                                        )
                                    }
                                }
                            )

                            if (state.isBusy) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }

                            TabRow(selectedTabIndex = targetTab) {
                                Tab(
                                    selected = targetTab == 0,
                                    onClick = { viewModel.setSelectedTab(0) },
                                    text = { Text("Tracks") }
                                )
                                Tab(
                                    selected = targetTab == 1,
                                    onClick = { viewModel.setSelectedTab(1) },
                                    text = { Text("Search") }
                                )
                            }
                        }

                        when (targetTab) {
                            0 -> {
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
                                        onSearch = viewModel::searchLyricsManual,
                                        onDownload = viewModel::downloadManualLyrics
                                    )
                                }
                            }

                            else -> {
                                Box(modifier = Modifier.weight(1f)) {
                                    SettingsPanel(
                                        state = state,
                                        onToggleOutputMode = viewModel::toggleOutputMode,
                                        onSearchDelayChanged = viewModel::setSearchDelay,
                                        onDownloadModeChanged = viewModel::setDownloadMode,
                                        onThemeModeChanged = viewModel::setThemeMode,
                                        onAmoledChanged = viewModel::setAmoled
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (state.previewTrack != null) {
                LyricsPreviewDialog(
                    track = state.previewTrack,
                    results = state.previewResults,
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
private fun ActionPanel(
    tracksSize: Int,
    savedCount: Int,
    missingCount: Int,
    isBusy: Boolean,
    hasLibrary: Boolean,
    message: String,
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
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = onChooseFolder, 
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    Icon(Icons.Default.Folder, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Folder")
                }
                FilledTonalButton(
                    onClick = onScan, 
                    enabled = hasLibrary && !isBusy,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Scan")
                }
            }
            
            Button(
                onClick = onDownload,
                enabled = tracksSize > 0 && !isBusy,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large
            ) {
                Icon(Icons.Default.CloudDownload, null)
                Spacer(Modifier.width(8.dp))
                Text("Download All Lyrics")
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(
                    progress = { if (tracksSize > 0) savedCount.toFloat() / tracksSize else 0f },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(MaterialTheme.shapes.extraLarge),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = StrokeCap.Round
                )
                
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

            if (message.isNotBlank()) {
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
    onPreview: (TrackItem) -> Unit,
    onDownload: (TrackItem) -> Unit
) {
    val statusLabel = track.status.label()
    val statusColor = track.status.color()

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
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
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    modifier = Modifier.padding(end = 4.dp)
                )
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
private fun ManualSearchPanel(
    state: MainUiState,
    onSearch: (String, String, String) -> Unit,
    onDownload: (LyricsLookupResult, TrackItem) -> Unit
) {
    var previewResult by remember { mutableStateOf<LyricsLookupResult?>(null) }
    var selectedTrack by remember { mutableStateOf<TrackItem?>(null) }

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
                if (selectedTrack != null) {
                    Button(onClick = {
                        onDownload(previewResult!!, selectedTrack!!)
                        previewResult = null
                    }) {
                        Text("Save to Selected")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { previewResult = null }) {
                    Text("Close")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ManualSearchForm(
                isSearching = state.isSearching,
                onSearch = onSearch
            )
        }

        if (state.isSearching) {
            item {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        if (state.searchResults.isNotEmpty()) {
            item {
                Text("Search Results:", style = MaterialTheme.typography.titleSmall)
            }
            items(
                items = state.searchResults,
                key = { it.lyrics.hashCode() },
                contentType = { "lyrics_result" }
            ) { result ->
                LyricsResultCard(
                    result = result,
                    onPreview = { previewResult = it },
                    onSave = { 
                        if (selectedTrack != null) {
                            onDownload(it, selectedTrack!!)
                        }
                    }
                )
            }
        }

        if (state.tracks.isNotEmpty()) {
            item {
                Text("Select destination track:", style = MaterialTheme.typography.titleSmall)
            }
            items(
                items = state.tracks,
                key = { it.id },
                contentType = { "track_chip" }
            ) { track ->
                FilterChip(
                    selected = selectedTrack?.id == track.id,
                    onClick = { selectedTrack = track },
                    label = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ManualSearchForm(
    isSearching: Boolean,
    onSearch: (String, String, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var album by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Search Lyrics", style = MaterialTheme.typography.titleMedium)

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
            enabled = title.isNotBlank() && !isSearching,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE91E63))
        ) {
            Text("SEARCH")
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
    onAmoledChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
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
                        text = "LRCGET Android v0.1.0",
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
private fun LyricsPreviewDialog(
    track: TrackItem?,
    results: List<LyricsLookupResult>,
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
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE91E63))
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
                                items(
                                    items = results,
                                    key = { it.lyrics.hashCode() },
                                    contentType = { "lyrics_result" }
                                ) { result ->
                                    LyricsResultCard(
                                        result = result,
                                        onPreview = { selectedResultForPreview = it },
                                        onSave = { onSave(it) }
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
                    
                    if (result.isSynced) {
                        Badge(containerColor = Color(0xFFE91E63), contentColor = Color.White) {
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


@Composable
private fun LyricsStatus.color() = when (this) {
    LyricsStatus.Found, LyricsStatus.Saved -> MaterialTheme.colorScheme.primary
    LyricsStatus.Missing, LyricsStatus.Failed -> MaterialTheme.colorScheme.error
    LyricsStatus.Skipped -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun LyricsStatus.label() = when (this) {
    LyricsStatus.Ready -> "Not checked"
    LyricsStatus.Found -> "Lyrics found"
    LyricsStatus.Downloading -> "Searching"
    LyricsStatus.Saved -> "Lyrics found"
    LyricsStatus.Missing -> "No lyrics"
    else -> name
}

private fun requestBatteryOptimizationExemption(context: android.content.Context) {
    val powerManager = context.getSystemService(PowerManager::class.java)
    if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) return

    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                "package:${context.packageName}".toUri()
            )
        )
    }
}
