package app.lrcget.android

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.FileProvider
import java.io.FileOutputStream
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import java.util.Locale
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.palette.graphics.Palette
import app.lrcget.android.data.LyricsLookupResult
import app.lrcget.android.model.DownloadMode
import app.lrcget.android.model.LyricsOutputMode
import app.lrcget.android.model.LyricsStatus
import app.lrcget.android.model.ThemeMode
import app.lrcget.android.model.TrackItem
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.BitmapImage
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "app.lrcget.android.ACTION_PLAY_PAUSE") {
                viewModel.state.value.previewTrack?.let { viewModel.toggleAudio(it) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val filter = IntentFilter("app.lrcget.android.ACTION_PLAY_PAUSE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(playbackReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(playbackReceiver, filter)
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableHighRefreshRate()
        setContent {
            val state by viewModel.state.collectAsState()
            LrcgetTheme(
                themeMode = state.themeMode,
                isAmoled = state.isAmoled
            ) {
                MainScreen(viewModel)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(playbackReceiver) }
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
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.Transparent.toArgb()
            @Suppress("DEPRECATION")
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
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 4 })
    
    var isSearchVisible by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // Track if any text field is focused to disable pager swiping
    var isTextFieldFocused by remember { mutableStateOf(false) }

    val savedCount = remember(state.tracks) { state.savedCount }
    val missingCount = remember(state.tracks) { state.missingCount }

    LaunchedEffect(state.selectedTab) {
        if (pagerState.currentPage != state.selectedTab) {
            pagerState.animateScrollToPage(state.selectedTab)
        }
    }

    LaunchedEffect(pagerState.settledPage) {
        if (state.selectedTab != pagerState.settledPage) {
            viewModel.setSelectedTab(pagerState.settledPage)
        }
    }

    PredictiveBackHandler(enabled = state.selectedTab != 0 || isSearchVisible || state.selectedTrackIds.isNotEmpty()) { progress ->
        try {
            progress.collect { }
            if (state.selectedTrackIds.isNotEmpty()) {
                viewModel.clearSelection()
            } else if (isSearchVisible) {
                isSearchVisible = false
                viewModel.setSearchQuery("")
            } else if (state.selectedTab != 0) {
                viewModel.setSelectedTab(0)
            }
        } catch (_: CancellationException) {
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

    val lrcSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null && state.previewLyrics != null) {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(state.previewLyrics!!.toByteArray())
            }
        }
    }

    fun shareLyrics(trackName: String, artistName: String, lyrics: String) {
        val shareText = "$trackName - $artistName\n\n$lyrics\n\nShared via LRCGET Android"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "$trackName - $artistName Lyrics")
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        context.startActivity(Intent.createChooser(intent, "Share Lyrics"))
    }

    LaunchedEffect(state.isRestoring) {
        if (!state.isRestoring) {
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
    }

    Scaffold(
        topBar = {
            if (isSearchVisible && state.selectedTrackIds.isEmpty() && state.selectedTab == 0) {
                        TopAppBar(
                    title = {
                        var textFieldValue by remember { mutableStateOf(TextFieldValue(state.searchQuery)) }
                        val isFocused = remember { mutableStateOf(false) }
                        
                        if (!isFocused.value && state.searchQuery != textFieldValue.text) {
                            textFieldValue = textFieldValue.withSyncedText(state.searchQuery)
                        }

                        Box(modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp)) {
                            CursorScrollableTextField(
                                value = textFieldValue,
                                onValueChange = {
                                    val textChanged = it.text != textFieldValue.text
                                    textFieldValue = it
                                    if (textChanged) {
                                        viewModel.setSearchQuery(it.text)
                                    }
                                },
                                placeholder = "Search tracks...",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester)
                                    .onFocusChanged { 
                                        isFocused.value = it.isFocused 
                                        isTextFieldFocused = it.isFocused 
                                    },
                                shape = MaterialTheme.shapes.medium,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                                onKeyboardAction = { isSearchVisible = false }
                            )
                        }
                        LaunchedEffect(Unit) { focusRequester.requestFocus() }
                    },
                    navigationIcon = {
                        IconButton(onClick = { 
                            isSearchVisible = false
                            viewModel.setSearchQuery("")
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (state.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    }
                )
            } else {
                CenterAlignedTopAppBar(
                    title = {
                        if (state.selectedTrackIds.isNotEmpty()) {
                            Text("${state.selectedTrackIds.size} selected")
                        } else {
                            Text(
                                text = when (state.selectedTab) {
                                    0 -> "LRCGET"
                                    1 -> "LRCLIB"
                                    2 -> "Contribute"
                                    3 -> "Settings"
                                    else -> "LRCGET"
                                },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
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
                            IconButton(onClick = viewModel::selectAll) {
                                Icon(Icons.Default.SelectAll, contentDescription = "Select All")
                            }
                            IconButton(onClick = viewModel::deleteSelectedLyrics) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Lyrics", tint = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            if (state.selectedTab == 0) {
                                IconButton(onClick = { isSearchVisible = true }) {
                                    Icon(Icons.Default.Search, contentDescription = "Search")
                                }
                                
                                var showFilterMenu by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { showFilterMenu = true }) {
                                        Icon(
                                            Icons.Default.FilterAlt,
                                            contentDescription = "Filter",
                                            tint = if (state.filterMode != TrackFilter.All) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = showFilterMenu,
                                        onDismissRequest = { showFilterMenu = false },
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                    ) {
                                        TrackFilter.entries.forEach { mode ->
                                            DropdownMenuItem(
                                                text = { 
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        RadioButton(
                                                            selected = state.filterMode == mode,
                                                            onClick = null
                                                        )
                                                        Spacer(Modifier.width(8.dp))
                                                        Text(mode.label()) 
                                                    }
                                                },
                                                onClick = {
                                                    viewModel.setFilterMode(mode)
                                                    showFilterMenu = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            if (state.isDownloadingAll && !state.showDownloadProgress) {
                                IconButton(onClick = { viewModel.setShowDownloadProgress(true) }) {
                                    Icon(Icons.Default.CloudDownload, contentDescription = "Show Progress", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            if (state.libraryUri != null && !state.isDownloadingAll && state.selectedTab == 0) {
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
            }
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
                    icon = { Icon(Icons.Default.CloudUpload, null) },
                    label = { Text("Contribute") }
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 3,
                    onClick = { viewModel.setSelectedTab(3) },
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text("Settings") }
                )
            }
        }
    ) { padding ->
        val blurRadius by animateDpAsState(
            targetValue = if (state.previewTrack != null || state.showExportDialog || state.showDownloadProgress) 12.dp else 0.dp,
            animationSpec = tween(durationMillis = 300),
            label = "blur"
        )

        Surface(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .blur(blurRadius)
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1
                ) { page ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = if (isLandscape) 16.dp else 8.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        when (page) {
                            0 -> {
                                if (isLandscape) {
                                    Row(
                                        modifier = Modifier.fillMaxSize(),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(0.4f),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            ActionPanel(
                                                tracksSize = state.tracks.size,
                                                savedCount = savedCount,
                                                foundCount = state.foundGlobalCount,
                                                missingCount = missingCount,
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
                                        }

                                        val tracks = state.filteredTracks
                                        PullToRefreshBox(
                                            modifier = Modifier.weight(0.6f),
                                            isRefreshing = state.isBusy && state.operationTotal == 0 && state.message.contains("Scanning"),
                                            onRefresh = viewModel::scan
                                        ) {
                                            LazyColumn(
                                                modifier = Modifier.fillMaxSize(),
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
                                                        isAnySelected = state.selectedTrackIds.isNotEmpty(),
                                                        isPlaying = state.isPlaying && state.playingTrackId == track.id,
                                                        isProcessing = state.processingTrackId == track.id,
                                                        onToggleSelection = { viewModel.toggleTrackSelection(track.id) },
                                                        onPreview = viewModel::previewLyrics,
                                                        onDownload = viewModel::downloadTrack,
                                                        onTogglePlay = { viewModel.toggleAudio(track) }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ActionPanel(
                                            tracksSize = state.tracks.size,
                                            savedCount = savedCount,
                                            foundCount = state.foundGlobalCount,
                                            missingCount = missingCount,
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
                                    }

                                    val tracks = state.filteredTracks
                                    PullToRefreshBox(
                                        modifier = Modifier.weight(1f),
                                        isRefreshing = state.isBusy && state.operationTotal == 0 && state.message.contains("Scanning"),
                                        onRefresh = viewModel::scan
                                    ) {
                                        LazyColumn(
                                            modifier = Modifier.fillMaxSize(),
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
                                                    isAnySelected = state.selectedTrackIds.isNotEmpty(),
                                                    isPlaying = state.isPlaying && state.playingTrackId == track.id,
                                                    isProcessing = state.processingTrackId == track.id,
                                                    onToggleSelection = { viewModel.toggleTrackSelection(track.id) },
                                                    onPreview = viewModel::previewLyrics,
                                                    onDownload = viewModel::downloadTrack,
                                                    onTogglePlay = { viewModel.toggleAudio(track) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            1 -> {
                                Box(modifier = Modifier.weight(1f)) {
                                    ManualSearchPanel(
                                        manualSearchQuery = state.manualSearchQuery,
                                        isSearching = state.isSearching,
                                        searchResults = state.searchResults,
                                        isDownloadingAll = state.isDownloadingAll,
                                        onQueryChange = viewModel::setManualSearchQuery,
                                        onSearch = viewModel::searchLyricsManual,
                                        onSaveToLocation = { result ->
                                            viewModel.selectPreviewLyrics(result.lyrics)
                                            lrcSaver.launch("${result.artistName} - ${result.trackName}.lrc")
                                        },
                                        onShare = ::shareLyrics,
                                        onFocusChange = { isTextFieldFocused = it }
                                    )
                                }
                            }

                            2 -> {
                                Box(modifier = Modifier.weight(1f)) {
                                    ContributePanel(
                                        isPublishing = state.isPublishing,
                                        publishStatus = state.publishStatus,
                                        onPublish = viewModel::publishLyrics,
                                        onClearStatus = viewModel::clearPublishStatus,
                                        onFocusChange = { isTextFieldFocused = it }
                                    )
                                }
                            }

                            3 -> {
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
                    previewLyrics = state.previewLyrics,
                    isBusy = state.isPreviewLoading,
                    isPlaying = state.isPlaying && state.playingTrackId == state.previewTrack?.id,
                    playbackPositionMs = state.playbackPositionMs,
                    playbackDurationMs = state.playbackDurationMs,
                    onSeek = viewModel::seekTo,
                    onSearch = viewModel::searchLyricsForPreview,
                    onSearchFieldFocusChange = { isTextFieldFocused = it },
                    onSave = { lyrics ->
                        state.previewTrack?.let { track ->
                            viewModel.downloadManualLyrics(lyrics, track)
                        }
                    },
                    onTogglePlay = { state.previewTrack?.let { viewModel.toggleAudio(it) } },
                    onShare = ::shareLyrics,
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
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        title = {
            val title = if (state.isExporting) "Exporting Lyrics" else "Finding Lyrics"
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(MaterialTheme.shapes.medium)
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
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.shapes.small
                        )
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
    foundCount: Int,
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(MaterialTheme.shapes.extraLarge),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            strokeCap = StrokeCap.Round
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(MaterialTheme.shapes.extraLarge),
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
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (foundCount > 0) {
                            Text(
                                text = "$foundCount found",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(
    track: TrackItem,
    isSelected: Boolean,
    isAnySelected: Boolean,
    isPlaying: Boolean,
    isProcessing: Boolean,
    onToggleSelection: () -> Unit,
    onPreview: (TrackItem) -> Unit,
    onDownload: (TrackItem) -> Unit,
    onTogglePlay: () -> Unit
) {
    val statusLabel = if (isProcessing) "Processing..." else track.status.label(track)

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (isAnySelected) onToggleSelection() else onPreview(track) },
                onLongClick = onToggleSelection
            )
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
                AsyncImage(
                    model = track.artUri,
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop,
                    error = rememberVectorPainter(Icons.Default.MusicNote),
                    placeholder = rememberVectorPainter(Icons.Default.MusicNote)
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
                if (isSelected) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    IconButton(onClick = onTogglePlay) {
                        Icon(
                            if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContributePanel(
    isPublishing: Boolean,
    publishStatus: String?,
    onPublish: (String, String, String, Int, String, String) -> Unit,
    onClearStatus: () -> Unit,
    onFocusChange: (Boolean) -> Unit = {}
) {
    var trackName by remember { mutableStateOf(TextFieldValue("")) }
    var artistName by remember { mutableStateOf(TextFieldValue("")) }
    var albumName by remember { mutableStateOf(TextFieldValue("")) }
    var durationStr by remember { mutableStateOf(TextFieldValue("")) }
    var plainLyrics by remember { mutableStateOf(TextFieldValue("")) }
    var syncedLyrics by remember { mutableStateOf(TextFieldValue("")) }

    val trackFocused = remember { mutableStateOf(false) }
    val artistFocused = remember { mutableStateOf(false) }
    val albumFocused = remember { mutableStateOf(false) }
    val durationFocused = remember { mutableStateOf(false) }
    val plainFocused = remember { mutableStateOf(false) }
    val syncedFocused = remember { mutableStateOf(false) }

    val updateFocus = {
        onFocusChange(trackFocused.value || artistFocused.value || albumFocused.value || 
                     durationFocused.value || plainFocused.value || syncedFocused.value)
    }

    if (publishStatus != null) {
        AlertDialog(
            onDismissRequest = onClearStatus,
            title = { Text(if (publishStatus.contains("Success")) "Success" else "Publishing Status") },
            text = { Text(publishStatus) },
            confirmButton = {
                TextButton(onClick = onClearStatus) { Text("OK") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Contribute to LRCLIB",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            "Share lyrics with the community. No registration required.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            CursorScrollableTextField(
                value = trackName,
                onValueChange = { trackName = it },
                label = "Track Name *",
                placeholder = "e.g. Bohemian Rhapsody",
                modifier = Modifier.fillMaxWidth().onFocusChanged { trackFocused.value = it.isFocused; updateFocus() },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CursorScrollableTextField(
                    value = artistName,
                    onValueChange = { artistName = it },
                    label = "Artist Name *",
                    placeholder = "e.g. Queen",
                    modifier = Modifier.weight(1f).onFocusChanged { artistFocused.value = it.isFocused; updateFocus() },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
                CursorScrollableTextField(
                    value = albumName,
                    onValueChange = { albumName = it },
                    label = "Album Name *",
                    placeholder = "e.g. A Night at the Opera",
                    modifier = Modifier.weight(1f).onFocusChanged { albumFocused.value = it.isFocused; updateFocus() },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
            }
            CursorScrollableTextField(
                value = durationStr,
                onValueChange = { newValue ->
                    // Allow only numbers and symbols (like :)
                    val filtered = newValue.text.filter { it.isDigit() || (!it.isLetter() && !it.isWhitespace()) }
                    durationStr = if (filtered == newValue.text) newValue else newValue.copy(text = filtered)
                },
                label = "Duration (Minutes and Seconds) *",
                placeholder = "e.g. 5:54 or 354",
                modifier = Modifier.fillMaxWidth().onFocusChanged { durationFocused.value = it.isFocused; updateFocus() },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Lyrics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            
            Box(modifier = Modifier.height(200.dp)) {
                CursorScrollableTextField(
                    value = plainLyrics,
                    onValueChange = { plainLyrics = it },
                    label = "Plain Lyrics",
                    placeholder = "Paste plain text lyrics here...",
                    modifier = Modifier.fillMaxSize().onFocusChanged { plainFocused.value = it.isFocused; updateFocus() },
                    shape = MaterialTheme.shapes.medium,
                    lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 5)
                )
            }

            Box(modifier = Modifier.height(200.dp)) {
                CursorScrollableTextField(
                    value = syncedLyrics,
                    onValueChange = { syncedLyrics = it },
                    label = "Synced Lyrics (LRC)",
                    placeholder = "[00:12.34] Timed lyrics here...",
                    modifier = Modifier.fillMaxSize().onFocusChanged { syncedFocused.value = it.isFocused; updateFocus() },
                    shape = MaterialTheme.shapes.medium,
                    lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 5)
                )
            }
        }

        Button(
            onClick = {
                val durationText = durationStr.text
                val duration = if (durationText.contains(":")) {
                    val parts = durationText.split(":")
                    val min = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: 0
                    val sec = parts.getOrNull(parts.size - 1)?.toIntOrNull() ?: 0
                    val hr = if (parts.size >= 3) parts.getOrNull(parts.size - 3)?.toIntOrNull() ?: 0 else 0
                    hr * 3600 + min * 60 + sec
                } else {
                    durationText.toIntOrNull() ?: 0
                }
                onPublish(
                    trackName.text,
                    artistName.text,
                    albumName.text,
                    duration,
                    plainLyrics.text,
                    syncedLyrics.text
                )
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            enabled = !isPublishing && trackName.text.isNotBlank() && artistName.text.isNotBlank() && 
                      albumName.text.isNotBlank() && durationStr.text.isNotBlank(),
            shape = MaterialTheme.shapes.large
        ) {
            if (isPublishing) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(12.dp))
                Text("Publishing...")
            } else {
                Icon(Icons.Default.CloudUpload, null)
                Spacer(Modifier.width(12.dp))
                Text("Publish to LRCLIB")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun StatusBadge(text: String, forceColor: Pair<Color, Color>? = null) {
    val (containerColor, contentColor) = forceColor ?: when {
        text.contains("Searching", ignoreCase = true) || text.contains("Saving...", ignoreCase = true) ->
            MaterialTheme.colorScheme.primary to Color.White
        text.contains("Search stopped", ignoreCase = true) ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        text.contains("synced", ignoreCase = true) -> 
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        text.contains("instrumental", ignoreCase = true) -> 
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
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
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ManualSearchPanel(
    manualSearchQuery: String,
    isSearching: Boolean,
    searchResults: List<LyricsLookupResult>,
    isDownloadingAll: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: (String, String, String) -> Unit,
    onSaveToLocation: (LyricsLookupResult) -> Unit,
    onShare: (String, String, String) -> Unit,
    onFocusChange: (Boolean) -> Unit = {}
) {
    var previewResult by remember { mutableStateOf<LyricsLookupResult?>(null) }
    
    var isFocusedInternal by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isDownloadingAll) {
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
                }
            }
        }
        return
    }

    // No-op, we removed the local query state to fix the cursor issue

    if (previewResult != null) {
        val parsedLines = remember(previewResult) { parseLrc(previewResult!!.lyrics) }
        
        AlertDialog(
            onDismissRequest = { 
                previewResult = null
            },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${previewResult!!.artistName} - ${previewResult!!.trackName}",
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { onShare(previewResult!!.trackName, previewResult!!.artistName, previewResult!!.lyrics) }) {
                        Icon(Icons.Default.Share, contentDescription = "Share")
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .background(
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = MaterialTheme.shapes.medium
                            )
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (parsedLines.isEmpty()) {
                            item {
                                Text(
                                    text = previewResult!!.lyrics,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        } else {
                            itemsIndexed(parsedLines) { _, line ->
                                Text(
                                    text = line.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
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
                TextButton(onClick = { 
                    previewResult = null
                }) {
                    Text("Close")
                }
            }
        )
    }

    if (isLandscape) {
        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(
                modifier = Modifier
                    .weight(0.4f)
                    .padding(top = 16.dp)
                    .verticalScroll(rememberScrollState(), enabled = !isFocusedInternal),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val onFocusChangeStable = remember(onFocusChange) {
                    { focused: Boolean ->
                        isFocusedInternal = focused
                        onFocusChange(focused)
                    }
                }
                SearchHeader(manualSearchQuery, isSearching, onQueryChange, onSearch, onFocusChangeStable)
            }

            LazyColumn(
                modifier = Modifier.weight(0.6f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
            ) {
                searchResults(searchResults, 0, { previewResult = it }, onSaveToLocation, onShare)
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SearchHeader(manualSearchQuery, isSearching, onQueryChange, onSearch, onFocusChange)
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                searchResults(searchResults, 0, { previewResult = it }, onSaveToLocation, onShare)
            }
        }
    }
}

@Composable
private fun CursorScrollableTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.medium,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.SingleLine,
    onKeyboardAction: (() -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val textFieldState = rememberTextFieldState(
        initialText = value.text,
        initialSelection = value.selection
    )
    val colors = MaterialTheme.colorScheme
    val borderColor = if (enabled) colors.outline else colors.onSurface.copy(alpha = 0.12f)
    val textColor = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f)

    LaunchedEffect(value.text, value.selection) {
        if (textFieldState.text.toString() != value.text || textFieldState.selection != value.selection) {
            textFieldState.edit {
                replace(0, length, value.text)
                selection = value.selection.coerceToTextLength(value.text.length)
            }
        }
    }

    LaunchedEffect(textFieldState) {
        snapshotFlow {
            TextFieldValue(
                text = textFieldState.text.toString(),
                selection = textFieldState.selection
            )
        }.collect { newValue ->
            if (newValue.text != value.text || newValue.selection != value.selection) {
                onValueChange(newValue)
            }
        }
    }

    Surface(
        modifier = modifier.onFocusChanged { isFocused = it.isFocused },
        shape = shape,
        color = colors.surface,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .border(1.dp, borderColor, shape)
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .heightIn(min = 40.dp),
            verticalAlignment = if (lineLimits is TextFieldLineLimits.SingleLine) Alignment.CenterVertically else Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BasicTextField(
                state = textFieldState,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                textStyle = textStyle.copy(color = textColor),
                keyboardOptions = keyboardOptions,
                onKeyboardAction = {
                    onKeyboardAction?.invoke() ?: it()
                },
                lineLimits = lineLimits,
                cursorBrush = SolidColor(colors.primary),
                scrollState = scrollState,
                decorator = { innerTextField ->
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (label != null) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                            ) {
                                if (isFocused && textFieldState.text.isEmpty() && placeholder != null) {
                                    Text(
                                        text = placeholder,
                                        style = textStyle,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                                innerTextField()
                            }
                        }
                    }
                }
            )
            if (trailingIcon != null) {
                trailingIcon()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchHeader(
    externalQuery: String,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: (String, String, String) -> Unit,
    onFocusChange: (Boolean) -> Unit = {}
) {
    // Isolated state to prevent cursor reset during parent recomposition (e.g. music playback)
    var textFieldValue by remember { mutableStateOf(TextFieldValue(externalQuery)) }
    val isFocused = remember { mutableStateOf(false) }

    // Synchronize ONLY when not focused
    if (!isFocused.value && externalQuery != textFieldValue.text) {
        textFieldValue = textFieldValue.withSyncedText(externalQuery)
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            "Search LRCLIB:",
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

    CursorScrollableTextField(
        value = textFieldValue,
        onValueChange = { newValue ->
            textFieldValue = newValue
            // Push update to ViewModel but ignore cursor position for sync-back
            if (newValue.text != externalQuery) {
                onQueryChange(newValue.text)
            }
        },
        placeholder = "Song, artist, or album...",
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .onFocusChanged { 
                isFocused.value = it.isFocused 
                onFocusChange(it.isFocused)
            },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
        onKeyboardAction = {
            if (textFieldValue.text.isNotBlank()) onSearch(textFieldValue.text, "", "") 
        },
        shape = MaterialTheme.shapes.extraLarge,
        trailingIcon = {
            IconButton(
                onClick = { if (textFieldValue.text.isNotBlank()) onSearch(textFieldValue.text, "", "") },
                enabled = textFieldValue.text.isNotBlank() && !isSearching
            ) {
                Icon(Icons.Default.Search, contentDescription = "Search")
            }
        }
    )
    
    if (isSearching) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.9f))
    }
}

private fun LazyListScope.searchResults(
    results: List<LyricsLookupResult>,
    trackDuration: Int,
    onPreview: (LyricsLookupResult) -> Unit,
    onSave: (LyricsLookupResult) -> Unit,
    onShare: (String, String, String) -> Unit
) {
    if (results.isNotEmpty()) {
        item {
            Text(
                "Search Results:", 
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )
        }
        itemsIndexed(
            items = results,
            key = { index, item -> "${index}-${item.trackName}-${item.lyrics.hashCode()}" }
        ) { _, result ->
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                LyricsResultItem(
                    result = result,
                    trackDuration = trackDuration,
                    onPreview = onPreview,
                    onSave = onSave,
                    onShare = onShare
                )
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
        containerColor = MaterialTheme.colorScheme.surface,
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
    previewLyrics: String?,
    isBusy: Boolean,
    isPlaying: Boolean,
    playbackPositionMs: Int,
    playbackDurationMs: Int,
    onSeek: (Int) -> Unit,
    onSearch: (String, String, String) -> Unit,
    onSearchFieldFocusChange: (Boolean) -> Unit = {},
    onSave: (LyricsLookupResult) -> Unit,
    onTogglePlay: () -> Unit,
    onShare: (String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedResultForPreview by remember { mutableStateOf<LyricsLookupResult?>(null) }
    var title by remember { mutableStateOf(track?.title ?: "") }
    var artist by remember { mutableStateOf(track?.artist ?: "") }
    var album by remember { mutableStateOf(track?.album ?: "") }
    var showSearchFields by remember { mutableStateOf(false) }
    var selectedShareLines by remember { mutableStateOf(setOf<Int>()) }
    var showShareDialog by remember { mutableStateOf(false) }
    
    // Track if search fields are focused to disable parent scrolling/paging
    var areFieldsFocused by remember { mutableStateOf(false) }

    var dragPosition by remember { mutableStateOf<Float?>(null) }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val parsedLines = remember(selectedResultForPreview) {
        selectedResultForPreview?.lyrics?.let { parseLrc(it) } ?: emptyList()
    }
    
    val currentLineIndex = remember(parsedLines, playbackPositionMs) {
        if (parsedLines.isEmpty()) -1
        else parsedLines.indexOfLast { it.timeMs <= playbackPositionMs }
    }

    val scrollState = rememberLazyListState()
    
    LaunchedEffect(currentLineIndex, selectedShareLines.isEmpty()) {
        if (currentLineIndex != -1 && selectedShareLines.isEmpty()) {
            scrollState.animateScrollToItem(currentLineIndex, -100)
        }
    }

    AlertDialog(
        onDismissRequest = if (selectedResultForPreview != null) { { selectedResultForPreview = null } } else onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth(if (isLandscape) 0.95f else 0.9f)
            .padding(vertical = if (isLandscape) 0.dp else 16.dp),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        title = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = if (selectedResultForPreview != null) "Lyrics Preview" else (track?.title ?: "Search Results"),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selectedShareLines.isNotEmpty()) {
                            IconButton(onClick = { showShareDialog = true }) {
                                Icon(Icons.Default.Share, contentDescription = "Share Card", tint = MaterialTheme.colorScheme.primary)
                            }
                        } else {
                            IconButton(onClick = {
                                val lyrics = selectedResultForPreview?.lyrics ?: previewLyrics ?: ""
                                val tName = selectedResultForPreview?.trackName ?: track?.title ?: "Unknown"
                                val aName = selectedResultForPreview?.artistName ?: artist
                                onShare(tName, aName, lyrics)
                            }) {
                                Icon(Icons.Default.Share, contentDescription = "Share")
                            }
                        }
                        if (selectedResultForPreview != null) {
                            IconButton(onClick = onTogglePlay) {
                                Icon(
                                    if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        } else {
                            IconButton(onClick = onTogglePlay) {
                                Icon(
                                    if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            IconButton(onClick = { showSearchFields = !showSearchFields }) {
                                Icon(if (showSearchFields) Icons.Default.Close else Icons.Default.Search, contentDescription = "Toggle Search")
                            }
                        }
                    }
                }
                
                val subtitle = when {
                    selectedResultForPreview != null -> "${selectedResultForPreview!!.artistName} • ${selectedResultForPreview!!.trackName}"
                    track != null -> "${track.artist} • ${track.album}"
                    else -> null
                }
                
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (playbackDurationMs > 0) {
                    val sliderPosition = dragPosition ?: playbackPositionMs.toFloat()
                    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Slider(
                            value = sliderPosition.coerceIn(0f, playbackDurationMs.toFloat()),
                            onValueChange = { dragPosition = it },
                            onValueChangeFinished = {
                                dragPosition?.let { onSeek(it.toInt()) }
                                dragPosition = null
                            },
                            valueRange = 0f..playbackDurationMs.toFloat(),
                            modifier = Modifier.fillMaxWidth().height(24.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            )
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = formatTime(sliderPosition.toInt()),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = formatTime(playbackDurationMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        text = {
            if (isLandscape) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (showSearchFields && selectedResultForPreview == null) {
                        Column(
                            modifier = Modifier
                                .weight(0.4f)
                                .verticalScroll(rememberScrollState(), enabled = !areFieldsFocused),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            PreviewSearchFields(
                                initialTitle = title,
                                initialArtist = artist,
                                initialAlbum = album,
                                onSearch = onSearch,
                                onFocusChange = {
                                    areFieldsFocused = it
                                    onSearchFieldFocusChange(it)
                                }
                            )
                        }
                    }

                    Box(modifier = Modifier.weight(if (showSearchFields && selectedResultForPreview == null) 0.6f else 1f)) {
                        PreviewMainContent(
                            selectedResultForPreview = selectedResultForPreview,
                            isBusy = isBusy,
                            results = results,
                            track = track,
                            parsedLines = parsedLines,
                            currentLineIndex = currentLineIndex,
                            scrollState = scrollState,
                            onSave = onSave,
                            onShare = onShare,
                            onSeek = onSeek,
                            onResultSelect = { selectedResultForPreview = it },
                            selectedShareLines = selectedShareLines,
                            onToggleShareLine = { index ->
                                selectedShareLines = if (selectedShareLines.contains(index)) {
                                    selectedShareLines - index
                                } else {
                                    selectedShareLines + index
                                }
                            }
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (showSearchFields && selectedResultForPreview == null) {
                        val onFocusChangeStable = remember(onSearchFieldFocusChange) {
                            { focused: Boolean ->
                                areFieldsFocused = focused
                                onSearchFieldFocusChange(focused)
                            }
                        }
                        PreviewSearchFields(
                            initialTitle = title,
                            initialArtist = artist,
                            initialAlbum = album,
                            onSearch = onSearch,
                            onFocusChange = onFocusChangeStable
                        )
                    }

                    PreviewMainContent(
                        selectedResultForPreview = selectedResultForPreview,
                        isBusy = isBusy,
                        results = results,
                        track = track,
                        parsedLines = parsedLines,
                        currentLineIndex = currentLineIndex,
                        scrollState = scrollState,
                        onSave = onSave,
                        onShare = onShare,
                        onSeek = onSeek,
                        onResultSelect = { selectedResultForPreview = it },
                        selectedShareLines = selectedShareLines,
                        onToggleShareLine = { index ->
                            selectedShareLines = if (selectedShareLines.contains(index)) {
                                selectedShareLines - index
                            } else {
                                selectedShareLines + index
                            }
                        }
                    )
                }
            }

            if (showShareDialog && track != null) {
                val linesToShare = if (selectedShareLines.isEmpty()) {
                    parsedLines.take(5).map { it.text }
                } else {
                    parsedLines.filterIndexed { index, _ -> selectedShareLines.contains(index) }.map { it.text }
                }
                ShareLyricsDialog(
                    track = track,
                    lines = linesToShare,
                    onDismiss = { showShareDialog = false }
                )
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
private fun PreviewSearchFields(
    initialTitle: String,
    initialArtist: String,
    initialAlbum: String,
    onSearch: (String, String, String) -> Unit,
    onFocusChange: (Boolean) -> Unit = {}
) {
    // Maintain local state to ensure smooth typing and scrolling
    var title by remember { mutableStateOf(TextFieldValue(initialTitle)) }
    var artist by remember { mutableStateOf(TextFieldValue(initialArtist)) }
    var album by remember { mutableStateOf(TextFieldValue(initialAlbum)) }
    
    val titleFocused = remember { mutableStateOf(false) }
    val artistFocused = remember { mutableStateOf(false) }
    val albumFocused = remember { mutableStateOf(false) }

    // Sync from props only when not focused to prevent cursor handle "blocking"
    LaunchedEffect(initialTitle) { if (!titleFocused.value) title = title.withSyncedText(initialTitle) }
    LaunchedEffect(initialArtist) { if (!artistFocused.value) artist = artist.withSyncedText(initialArtist) }
    LaunchedEffect(initialAlbum) { if (!albumFocused.value) album = album.withSyncedText(initialAlbum) }

    val updateFocus = {
        onFocusChange(titleFocused.value || artistFocused.value || albumFocused.value)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CursorScrollableTextField(
            value = title,
            onValueChange = { title = it },
            label = "Title",
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { 
                    titleFocused.value = it.isFocused 
                    updateFocus()
                },
            shape = MaterialTheme.shapes.medium,
            textStyle = MaterialTheme.typography.bodySmall,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CursorScrollableTextField(
                value = album,
                onValueChange = { album = it },
                label = "Album",
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { 
                        albumFocused.value = it.isFocused 
                        updateFocus()
                    },
                shape = MaterialTheme.shapes.medium,
                textStyle = MaterialTheme.typography.bodySmall,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false)
            )
            CursorScrollableTextField(
                value = artist,
                onValueChange = { artist = it },
                label = "Artist",
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { 
                        artistFocused.value = it.isFocused 
                        updateFocus()
                    },
                shape = MaterialTheme.shapes.medium,
                textStyle = MaterialTheme.typography.bodySmall,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false)
            )
        }
        Button(
            onClick = { onSearch(title.text, artist.text, album.text) },
            enabled = title.text.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            Text("SEARCH", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PreviewMainContent(
    selectedResultForPreview: LyricsLookupResult?,
    isBusy: Boolean,
    results: List<LyricsLookupResult>,
    track: TrackItem?,
    parsedLines: List<ParsedLrcLine>,
    currentLineIndex: Int,
    scrollState: androidx.compose.foundation.lazy.LazyListState,
    onSave: (LyricsLookupResult) -> Unit,
    onShare: (String, String, String) -> Unit,
    onSeek: (Int) -> Unit,
    onResultSelect: (LyricsLookupResult) -> Unit,
    selectedShareLines: Set<Int> = emptySet(),
    onToggleShareLine: (Int) -> Unit = {}
) {
    if (selectedResultForPreview != null) {
        LazyColumn(
            state = scrollState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 450.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium
                )
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (parsedLines.isEmpty()) {
                item {
                    Text(
                        text = selectedResultForPreview.lyrics,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                itemsIndexed(parsedLines) { index, line ->
                    val isHighlighted = index == currentLineIndex
                    Text(
                        text = line.text,
                        style = if (isHighlighted) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal,
                        color = if (isHighlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (selectedShareLines.isNotEmpty()) {
                                        onToggleShareLine(index)
                                    } else {
                                        onSeek(line.timeMs)
                                    }
                                },
                                onLongClick = { onToggleShareLine(index) }
                            )
                            .background(
                                when {
                                    selectedShareLines.contains(index) -> MaterialTheme.colorScheme.secondaryContainer.copy(
                                        alpha = 0.5f
                                    )

                                    isHighlighted -> MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                    else -> Color.Transparent
                                },
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (results.isEmpty()) {
                Text(
                    "No matches found on LRCLIB.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .heightIn(max = 450.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(
                        items = results,
                        key = { index, result -> "${result.trackName}-${result.lyrics.hashCode()}-$index" },
                        contentType = { _, _ -> "lyrics_result" }
                    ) { _, result ->
                        LyricsResultItem(
                            result = result,
                            trackDuration = track?.durationSeconds ?: 0,
                            onPreview = { onResultSelect(result) },
                            onSave = { onSave(result) },
                            onShare = onShare
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsResultItem(
    result: LyricsLookupResult,
    trackDuration: Int = 0,
    onPreview: (LyricsLookupResult) -> Unit,
    onSave: (LyricsLookupResult) -> Unit,
    onShare: (String, String, String) -> Unit
) {
    val durationDiff = if (trackDuration > 0 && result.duration > 0) abs(trackDuration - result.duration) else 0
    val durationColor = when {
        durationDiff > 10 -> MaterialTheme.colorScheme.error
        durationDiff > 5 -> Color(0xFFEF6C00) // Orange
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        onClick = { onPreview(result) },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.trackName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Text(
                    text = "${result.artistName} • ${result.albumName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (result.duration > 0) {
                        val minutes = result.duration / 60
                        val seconds = result.duration % 60
                        StatusBadge(
                            text = "%d:%02d".format(Locale.getDefault(), minutes, seconds),
                            forceColor = (if (durationDiff > 5) durationColor.copy(alpha = 0.1f) else Color.Transparent) to durationColor
                        )
                    }

                    if (result.isInstrumental) {
                        StatusBadge("Instrumental")
                    } else if (result.isSynced) {
                        StatusBadge("Synced")
                    }
                    
                    StatusBadge(
                        text = "${result.lineCount} lines", 
                        forceColor = Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    onClick = { onShare(result.trackName, result.artistName, result.lyrics) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(18.dp))
                }
                IconButton(
                    onClick = { onSave(result) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Save, 
                        contentDescription = "Save", 
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
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

private fun requestBatteryOptimizationExemption(context: Context) {
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

private data class ParsedLrcLine(val timeMs: Int, val text: String)

private fun TextFieldValue.withSyncedText(text: String): TextFieldValue {
    return copy(
        text = text,
        selection = selection.coerceToTextLength(text.length),
        composition = null
    )
}

private fun TextRange.coerceToTextLength(length: Int): TextRange =
    TextRange(start.coerceIn(0, length), end.coerceIn(0, length))

private fun formatTime(ms: Int): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(Locale.getDefault(), minutes, seconds)
}

private fun parseLrc(lrcText: String): List<ParsedLrcLine> {
    val tagPattern = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    return lrcText.lineSequence().flatMap { rawLine ->
        val matches = tagPattern.findAll(rawLine).toList()
        val text = rawLine.replace(tagPattern, "").trim()
        if (matches.isEmpty()) {
            emptySequence()
        } else {
            matches.asSequence().mapNotNull { match ->
                val minutes = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val seconds = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                val fraction = match.groupValues[3].ifBlank { "0" }
                val millis = when (fraction.length) {
                    1 -> fraction.toInt() * 100
                    2 -> fraction.toInt() * 10
                    else -> fraction.take(3).padEnd(3, '0').toInt()
                }
                ParsedLrcLine(((minutes * 60 + seconds) * 1000) + millis, text)
            }
        }
    }.sortedBy { it.timeMs }.toList()
}

@Composable
private fun ShareLyricsDialog(
    track: TrackItem,
    lines: List<String>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val graphicsLayer = rememberGraphicsLayer()
    val coroutineScope = rememberCoroutineScope()

    val colorScheme = MaterialTheme.colorScheme
    val monetColors = remember(colorScheme) {
        listOf(
            colorScheme.primaryContainer,
            colorScheme.secondaryContainer,
            colorScheme.tertiaryContainer,
            colorScheme.surfaceContainerHigh
        ).distinct()
    }
    val initialColor = monetColors.first()
    var backgroundColor by remember(initialColor) { mutableStateOf(initialColor) }
    var paletteColors by remember(monetColors) { mutableStateOf(monetColors) }
    val imageLoader = SingletonImageLoader.get(context)

    LaunchedEffect(track.artUri, monetColors) {
        paletteColors = monetColors
        if (track.artUri != null) {
            val request = ImageRequest.Builder(context)
                .data(track.artUri)
                .allowHardware(false)
                .build()
            val result = imageLoader.execute(request)
            if (result is SuccessResult) {
                val bitmap = (result.image as? BitmapImage)?.bitmap
                if (bitmap != null) {
                    val palette = Palette.from(bitmap).generate()
                    val colors = listOfNotNull(
                        palette.vibrantSwatch?.let { Color(it.rgb) },
                        palette.mutedSwatch?.let { Color(it.rgb) },
                        palette.dominantSwatch?.let { Color(it.rgb) },
                        palette.lightVibrantSwatch?.let { Color(it.rgb) },
                        palette.lightMutedSwatch?.let { Color(it.rgb) },
                        palette.darkVibrantSwatch?.let { Color(it.rgb) },
                        palette.darkMutedSwatch?.let { Color(it.rgb) }
                    ).distinct()
                    paletteColors = (monetColors + colors).distinct()
                }
            }
        }
    }

    val contentColor = if (backgroundColor.luminance() > 0.5f) Color.Black else Color.White
    val secondaryContentColor = contentColor.copy(alpha = 0.6f)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        title = { Text("Share lyrics") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Box(
                    modifier = Modifier
                        .wrapContentSize()
                        .drawWithContent {
                            graphicsLayer.record {
                                this@drawWithContent.drawContent()
                            }
                            drawContent()
                        }
                ) {
                    Column(
                        modifier = Modifier
                            .width(320.dp)
                            .background(
                                color = backgroundColor,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(24.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            AsyncImage(
                                model = track.artUri,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                error = rememberVectorPainter(Icons.Default.MusicNote)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.title, 
                                    color = contentColor, 
                                    style = MaterialTheme.typography.titleMedium, 
                                    fontWeight = FontWeight.Bold, 
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = track.artist, 
                                    color = secondaryContentColor, 
                                    style = MaterialTheme.typography.bodySmall, 
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 16.dp),
                            color = contentColor.copy(alpha = 0.1f)
                        )
                        
                        Column(
                            modifier = Modifier.padding(bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            lines.forEach { line ->
                                Text(
                                    text = line,
                                    color = contentColor,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.alpha(0.7f)
                        ) {
                            Icon(
                                imageVector = ImageVector.vectorResource(id = R.drawable.ic_logo),
                                contentDescription = null,
                                tint = contentColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "LRCGET Android",
                                color = contentColor,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Monet Color Picker (Circular Dots)
                if (paletteColors.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        paletteColors.take(8).forEach { color ->
                            val isSelected = backgroundColor == color
                            Box(
                                modifier = Modifier
                                    .size(if (isSelected) 36.dp else 28.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (isSelected) 2.dp else 0.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable { backgroundColor = color }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                coroutineScope.launch {
                    runCatching {
                        val imageBitmap = graphicsLayer.toImageBitmap()
                        val bitmap = imageBitmap.asAndroidBitmap()
                        
                        val cacheFile = java.io.File(context.cacheDir, "lyrics_share.png")
                        FileOutputStream(cacheFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cacheFile)
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Lyrics via"))
                    }
                }
            }) {
                Text("Share Now")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
