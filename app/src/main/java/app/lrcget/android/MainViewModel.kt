package app.lrcget.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Application
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.graphics.drawable.Icon
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lrcget.android.data.LyricsLookupResult
import app.lrcget.android.data.LrclibClient
import app.lrcget.android.data.MusicLibraryRepository
import app.lrcget.android.model.DownloadMode
import app.lrcget.android.model.LyricsOutputMode
import app.lrcget.android.model.LyricsStatus
import app.lrcget.android.model.ThemeMode
import app.lrcget.android.model.TrackItem
import app.lrcget.android.service.LyricsDownloadService
import app.lrcget.android.service.DownloadProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class TrackFilter {
    All,
    Saved,
    Found,
    Missing,
    NotChecked,
    EmbeddedSynced,
    EmbeddedPlain,
    LrcFile;

    fun label(): String = when (this) {
        All -> "All"
        Saved -> "Saved"
        Found -> "Found"
        Missing -> "Missing"
        NotChecked -> "Not Checked"
        EmbeddedSynced -> "Embedded Synced"
        EmbeddedPlain -> "Embedded Plain"
        LrcFile -> "LRC File"
    }
}

@Stable
data class MainUiState(
    val libraryUri: Uri? = null,
    val tracks: List<TrackItem> = emptyList(),
    val isBusy: Boolean = false,
    val downloadMode: DownloadMode = DownloadMode.MissingAny,
    val outputModes: Set<LyricsOutputMode> = setOf(LyricsOutputMode.LrcFile),
    val searchDelay: Int = 2,
    val selectedTab: Int = 0,
    val previousTab: Int = 0,
    val message: String = "Choose a music folder to begin",
    val previewLyrics: String? = null,
    val previewTrack: TrackItem? = null,
    val previewResults: List<LyricsLookupResult> = emptyList(),
    val searchResults: List<LyricsLookupResult> = emptyList(),
    val isSearching: Boolean = false,
    val manualSearchQuery: String = "",
    val themeMode: ThemeMode = ThemeMode.System,
    val isAmoled: Boolean = false,
    val operationProgress: Int = 0,
    val operationTotal: Int = 0,
    val isDownloadingAll: Boolean = false,
    val showDownloadProgress: Boolean = false,
    val showExportDialog: Boolean = false,
    val downloadLog: List<String> = emptyList(),
    val foundCount: Int = 0,
    val notFoundCount: Int = 0,
    val fetchedLyrics: Map<String, LyricsLookupResult> = emptyMap(),
    val selectedTrackIds: Set<String> = emptySet(),
    val searchQuery: String = "",
    val filterMode: TrackFilter = TrackFilter.All,
    val isPlaying: Boolean = false,
    val playingTrackId: String? = null,
    val playbackPositionMs: Int = 0,
    val playbackDurationMs: Int = 0,
    val processingTrackId: String? = null,
    val isRestoring: Boolean = true,
    val isPublishing: Boolean = false,
    val publishStatus: String? = null,
    val isExporting: Boolean = false,
    val isPreviewLoading: Boolean = false
) {
    val filteredTracks: List<TrackItem>
        get() = tracks.filter { track ->
            val matchesQuery = searchQuery.isBlank() || 
                track.title.contains(searchQuery, ignoreCase = true) || 
                track.artist.contains(searchQuery, ignoreCase = true) ||
                track.album.contains(searchQuery, ignoreCase = true)
            
            val matchesFilter = when (filterMode) {
                TrackFilter.All -> true
                TrackFilter.Saved -> track.status == LyricsStatus.Saved
                TrackFilter.Found -> track.status == LyricsStatus.Found
                TrackFilter.Missing -> track.status == LyricsStatus.Missing
                TrackFilter.NotChecked -> track.status == LyricsStatus.Ready
                TrackFilter.EmbeddedSynced -> track.message.contains("Embedded synced", ignoreCase = true)
                TrackFilter.EmbeddedPlain -> track.message.contains("Embedded plain", ignoreCase = true)
                TrackFilter.LrcFile -> track.message.contains("LRC", ignoreCase = true)
            }
            matchesQuery && matchesFilter
        }

    val savedCount: Int get() = tracks.count { it.status == LyricsStatus.Saved }
    val missingCount: Int get() = tracks.count { it.status == LyricsStatus.Missing }
    val foundGlobalCount: Int get() = tracks.count { it.status == LyricsStatus.Found }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MusicLibraryRepository(application)
    private val lrclibClient = LrclibClient()
    private val prefs = application.getSharedPreferences("lrcget_prefs", android.content.Context.MODE_PRIVATE)
    private val notificationManager = application.getSystemService(NotificationManager::class.java)
    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state
    private var downloadJob: kotlinx.coroutines.Job? = null
    private var scanJob: kotlinx.coroutines.Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var playbackJob: kotlinx.coroutines.Job? = null
    private var mediaSession: android.media.session.MediaSession? = null
    private var currentPlayingTrack: TrackItem? = null
    
    private val audioManager = application.getSystemService(Application.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS || 
            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || 
            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            pauseAudio()
        }
    }

    init {
        createNotificationChannel()
        restoreSettings()
        setupMediaSession()
        viewModelScope.launch {
            restoreLibrary()
        }
        observeDownloadService()
    }

    private fun observeDownloadService() {
        viewModelScope.launch {
            LyricsDownloadService.progressFlow.collect { progress ->
                when (progress) {
                    is DownloadProgress.Scanning -> {
                        _state.update { it.copy(
                            isBusy = true,
                            isDownloadingAll = true,
                            operationProgress = progress.current,
                            operationTotal = progress.total,
                            message = if (progress.total > 0) "Scanning metadata..." else "Scanning folders..."
                        ) }
                    }
                    is DownloadProgress.Processing -> {
                        val logEntry = "${progress.trackTitle}: ${progress.message}"
                        updateTrack(progress.trackId) { it.copy(status = progress.status, message = progress.message) }
                        _state.update { s ->
                            s.copy(
                                isBusy = true,
                                isDownloadingAll = true,
                                isExporting = progress.isExport,
                                message = "Processing ${progress.current} of ${progress.total}",
                                operationProgress = progress.current,
                                operationTotal = progress.total,
                                downloadLog = listOf(logEntry) + s.downloadLog.take(99),
                                foundCount = if (progress.status == LyricsStatus.Found || progress.status == LyricsStatus.Saved) s.foundCount + 1 else s.foundCount,
                                notFoundCount = if (progress.status == LyricsStatus.Missing) s.notFoundCount + 1 else s.notFoundCount,
                                fetchedLyrics = if (progress.lyrics != null) s.fetchedLyrics + (progress.trackId to progress.lyrics) else s.fetchedLyrics
                            )
                        }
                    }
                    is DownloadProgress.Done -> {
                        _state.update { it.copy(
                            isBusy = false,
                            isDownloadingAll = false,
                            isExporting = false,
                            showDownloadProgress = false,
                            message = if (progress.failed > 0) "Complete with ${progress.failed} errors" else "Operation complete",
                            fetchedLyrics = if (progress.isExport) emptyMap() else it.fetchedLyrics
                        ) }
                        if (progress.isExport) {
                            scan() // Auto-refresh ONLY after export
                        }
                    }
                    is DownloadProgress.Error -> {
                        _state.update { it.copy(
                            isBusy = false,
                            isDownloadingAll = false,
                            isExporting = false,
                            message = "Error: ${progress.message}"
                        ) }
                    }
                }
            }
        }
    }

    private fun setupMediaSession() {
        mediaSession = android.media.session.MediaSession(getApplication(), "LRCGET").apply {
            setCallback(object : android.media.session.MediaSession.Callback() {
                override fun onPlay() {
                    currentPlayingTrack?.let { playAudio(it) }
                }
                override fun onPause() {
                    pauseAudio()
                }
                override fun onStop() {
                    stopAudio()
                }
                override fun onSeekTo(pos: Long) {
                    seekTo(pos.toInt())
                }
            })
        }
    }

    private fun restoreSettings() {
        val themeModeName = prefs.getString("theme_mode", ThemeMode.System.name)
        val themeMode = runCatching { ThemeMode.valueOf(themeModeName!!) }.getOrDefault(ThemeMode.System)
        val isAmoled = prefs.getBoolean("is_amoled", false)
        val downloadModeName = prefs.getString("download_mode", DownloadMode.MissingAny.name)
        val downloadMode = runCatching { DownloadMode.valueOf(downloadModeName!!) }.getOrDefault(DownloadMode.MissingAny)
        val searchDelay = prefs.getInt("search_delay", 2)
        val outputModesNames = prefs.getStringSet("output_modes", setOf(LyricsOutputMode.LrcFile.name))
        val outputModes = outputModesNames?.mapNotNull { name ->
            runCatching { LyricsOutputMode.valueOf(name) }.getOrNull()
        }?.toSet() ?: setOf(LyricsOutputMode.LrcFile)

        _state.update { it.copy(
            themeMode = themeMode,
            isAmoled = isAmoled,
            downloadMode = downloadMode,
            searchDelay = searchDelay,
            outputModes = outputModes
        ) }
    }

    private suspend fun restoreLibrary() {
        val uriString = prefs.getString("library_uri", null)
        if (uriString == null) {
            _state.update { it.copy(isRestoring = false) }
            return
        }
        val uri = uriString.toUri()
        
        // Check if we still have permission
        val hasPermission = getApplication<Application>().contentResolver.persistedUriPermissions.any { 
            it.uri == uri && (it.isReadPermission || it.isWritePermission)
        }

        if (hasPermission) {
            val cachedTracks = withContext(kotlinx.coroutines.Dispatchers.IO) { repository.loadTracks(uri) }
            _state.update { it.copy(
                libraryUri = uri, 
                tracks = cachedTracks,
                isRestoring = false,
                message = if (cachedTracks.isNotEmpty()) "Library restored (${cachedTracks.size} tracks)" else "Library folder restored"
            ) }
        } else {
            _state.update { it.copy(isRestoring = false) }
        }
    }

    private fun saveTracks(tracks: List<TrackItem>) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            repository.saveTracks(tracks)
        }
    }


    fun setLibrary(uri: Uri) {
        prefs.edit { putString("library_uri", uri.toString()) }
        _state.update { it.copy(libraryUri = uri, message = "Folder selected") }
        scan()
    }

    fun removeLibrary() {
        prefs.edit { remove("library_uri") }
        File(getApplication<Application>().filesDir, "tracks_cache.json").delete()
        _state.update { it.copy(libraryUri = null, tracks = emptyList()) }
    }

    private var lastProgressUpdateTime = 0L
    fun scan() {
        val uri = _state.value.libraryUri ?: return
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _state.update { it.copy(isBusy = true, message = "Scanning folder...", operationProgress = 0, operationTotal = 0) }
            notifyProgress("Scanning music folder", 0, 0, true)
            val tracks = repository.scan(uri) { current, total ->
                val now = System.currentTimeMillis()
                if (now - lastProgressUpdateTime > 150 || current == total) {
                    lastProgressUpdateTime = now
                    _state.update { it.copy(operationProgress = current, operationTotal = total) }
                    val text = if (total > 0) {
                        "Reading metadata $current of $total"
                    } else if (total < 0) {
                        "Scanning folders: $current folders, ${-total} audio files found"
                    } else {
                        "Found $current audio files"
                    }
                    notifyProgress(text, if (total > 0) current else 0, if (total > 0) total else 0, total <= 0)
                }
            }
            _state.update {
                it.copy(
                    tracks = tracks,
                    isBusy = false,
                    operationProgress = 0,
                    operationTotal = 0,
                    message = if (tracks.isEmpty()) "No supported music files found" else "Found ${tracks.size} tracks"
                )
            }
            saveTracks(tracks)
            notifyProgress(
                text = if (tracks.isEmpty()) "No supported music files found" else "Scan complete: ${tracks.size} tracks",
                progress = tracks.size,
                max = tracks.size,
                indeterminate = false,
                ongoing = false
            )
        }
    }

    fun downloadAll() {
        val tracks = _state.value.tracks
        if (tracks.isEmpty()) return
        val current = _state.value
        val rootUri = current.libraryUri ?: return

        _state.update { it.copy(
            isDownloadingAll = true,
            isExporting = false,
            showDownloadProgress = true,
            isBusy = true, 
            message = "Starting background search...",
            operationProgress = 0,
            downloadLog = emptyList(),
            foundCount = 0,
            notFoundCount = 0,
            fetchedLyrics = emptyMap() // Clear old search results
        ) }

        LyricsDownloadService.start(
            getApplication(),
            rootUri,
            current.downloadMode,
            current.outputModes,
            current.searchDelay,
            isExport = false
        )
    }

    fun exportAll() {
        val tracks = _state.value.tracks
        val current = _state.value
        val rootUri = current.libraryUri ?: return

        if (current.fetchedLyrics.isEmpty()) {
            _state.update { it.copy(message = "No lyrics found to export. Please run 'Find Lyrics Online' first.") }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            // Save fetchedLyrics to a file for the Service to pick up
            try {
                val file = File(getApplication<Application>().filesDir, "export_lyrics.json")
                val root = JSONObject()
                current.fetchedLyrics.forEach { (id, result) ->
                    val obj = JSONObject().apply {
                        put("lyrics", result.lyrics)
                        put("isSynced", result.isSynced)
                        put("isInstrumental", result.isInstrumental)
                        put("trackName", result.trackName)
                        put("artistName", result.artistName)
                        put("albumName", result.albumName)
                        put("duration", result.duration)
                    }
                    root.put(id, obj)
                }
                file.writeText(root.toString())

                withContext(Dispatchers.Main) {
                    _state.update { it.copy(
                        isDownloadingAll = true,
                        showDownloadProgress = true, 
                        message = "Starting background export...",
                        operationProgress = 0,
                        downloadLog = emptyList(),
                        foundCount = 0,
                        notFoundCount = 0
                    ) }

                    LyricsDownloadService.start(
                        getApplication(),
                        rootUri,
                        current.downloadMode,
                        current.outputModes,
                        current.searchDelay,
                        isExport = true
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(message = "Failed to prepare export: ${e.localizedMessage}") }
                }
            }
        }
    }

    fun stopDownload() {
        getApplication<Application>().stopService(Intent(getApplication(), LyricsDownloadService::class.java))
        downloadJob?.cancel()
        scanJob?.cancel()
        _state.update { it.copy(isBusy = false, isDownloadingAll = false, showDownloadProgress = false, message = "Download stopped") }
        notifyProgress("Stopped", 0, 0, indeterminate = false, ongoing = false)
    }

    fun setShowDownloadProgress(show: Boolean) {
        _state.update { it.copy(showDownloadProgress = show) }
    }

    fun setShowExportDialog(show: Boolean) {
        _state.update { it.copy(showExportDialog = show) }
    }

    fun downloadTrack(track: TrackItem) {
        viewModelScope.launch {
            _state.update { it.copy(processingTrackId = track.id) }
            val lyricsResult: LyricsLookupResult? = repository.getLyricsResultForTrack(track)
            
            if (lyricsResult != null) {
                _state.update { it.copy(fetchedLyrics = it.fetchedLyrics + (track.id to lyricsResult)) }
                val msg = if (lyricsResult.isInstrumental) "Instrumental found" else if (lyricsResult.isSynced) "Synced found" else "Plain found"
                updateTrack(track.id) { it.copy(status = LyricsStatus.Found, message = msg) }
            } else {
                updateTrack(track.id) { it.copy(status = LyricsStatus.Missing, message = "No lyrics found") }
            }
            _state.update { it.copy(processingTrackId = null) }
            saveTracks(_state.value.tracks)
        }
    }

    fun previewLyrics(track: TrackItem) {
        viewModelScope.launch {
            _state.update { it.copy(isPreviewLoading = true, previewTrack = track, previewResults = emptyList()) }
            val results = repository.getAllLyricsForPreview(track)
            _state.update {
                it.copy(
                    isPreviewLoading = false,
                    previewResults = results,
                    previewLyrics = results.firstOrNull()?.lyrics
                )
            }
        }
    }

    fun selectPreviewLyrics(lyrics: String) {
        _state.update { it.copy(previewLyrics = lyrics) }
    }

    fun closePreview() {
        _state.update { it.copy(previewLyrics = null, previewTrack = null, previewResults = emptyList()) }
    }

    fun searchLyricsManual(trackName: String, artistName: String = "", albumName: String = "") {
        setManualSearchQuery(trackName)
        viewModelScope.launch {
            _state.update { it.copy(isSearching = true, searchResults = emptyList()) }
            val results = repository.searchLyricsManual(trackName, artistName, albumName)
            _state.update { it.copy(isSearching = false, searchResults = results) }
        }
    }

    fun searchLyricsForPreview(trackName: String, artistName: String = "", albumName: String = "") {
        viewModelScope.launch {
            _state.update { it.copy(isPreviewLoading = true) }
            val results = repository.searchLyricsManual(trackName, artistName, albumName)
            _state.update { it.copy(isPreviewLoading = false, previewResults = results) }
        }
    }

    fun downloadManualLyrics(lyrics: LyricsLookupResult, track: TrackItem) {
        _state.update { it.copy(fetchedLyrics = it.fetchedLyrics + (track.id to lyrics)) }
        val msg = when {
            lyrics.isInstrumental -> "Instrumental selected for export"
            lyrics.isSynced -> "Synced selected for export"
            else -> "Plain selected for export"
        }
        updateTrack(track.id) { it.copy(status = LyricsStatus.Found, message = msg) }
        closePreview()
    }

    fun setDownloadMode(value: DownloadMode) {
        prefs.edit { putString("download_mode", value.name) }
        _state.update { it.copy(downloadMode = value) }
    }

    fun toggleOutputMode(mode: LyricsOutputMode) {
        _state.update { state ->
            val newModes = if (state.outputModes.contains(mode)) {
                if (state.outputModes.size > 1) state.outputModes - mode else state.outputModes
            } else {
                state.outputModes + mode
            }
            prefs.edit { putStringSet("output_modes", newModes.map { it.name }.toSet()) }
            state.copy(outputModes = newModes)
        }
    }

    fun setSearchDelay(value: Int) {
        prefs.edit { putInt("search_delay", value) }
        _state.update { it.copy(searchDelay = value) }
    }

    fun setThemeMode(value: ThemeMode) {
        prefs.edit { putString("theme_mode", value.name) }
        _state.update { it.copy(themeMode = value) }
    }

    fun setAmoled(value: Boolean) {
        prefs.edit { putBoolean("is_amoled", value) }
        _state.update { it.copy(isAmoled = value) }
    }

    fun setManualSearchQuery(value: String) {
        _state.update { 
            it.copy(
                manualSearchQuery = value,
                searchResults = if (value.isBlank()) emptyList() else it.searchResults
            )
        }
    }

    fun setSelectedTab(value: Int) {
        _state.update { it.copy(previousTab = it.selectedTab, selectedTab = value) }
    }

    fun toggleTrackSelection(id: String) {
        _state.update { state ->
            val newSelection = if (state.selectedTrackIds.contains(id)) {
                state.selectedTrackIds - id
            } else {
                state.selectedTrackIds + id
            }
            state.copy(selectedTrackIds = newSelection)
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedTrackIds = emptySet()) }
    }

    fun selectAll() {
        _state.update { it.copy(selectedTrackIds = it.filteredTracks.map { track -> track.id }.toSet()) }
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun setFilterMode(mode: TrackFilter) {
        _state.update { it.copy(filterMode = mode) }
    }

    fun toggleAudio(track: TrackItem) {
        val current = _state.value
        if (current.playingTrackId == track.id && current.isPlaying) {
            pauseAudio()
        } else {
            playAudio(track)
        }
    }

    private fun playAudio(track: TrackItem) {
        viewModelScope.launch {
            runCatching {
                val focusResult = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        .setOnAudioFocusChangeListener(audioFocusChangeListener)
                        .build()
                    audioFocusRequest = request
                    audioManager.requestAudioFocus(request)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.requestAudioFocus(
                        audioFocusChangeListener,
                        AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN
                    )
                }

                if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return@launch

                if (currentPlayingTrack?.id == track.id && mediaPlayer != null) {
                    mediaPlayer?.start()
                } else {
                    currentPlayingTrack = track
                    mediaPlayer?.release()
                    playbackJob?.cancel()
                    mediaPlayer = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .build()
                        )
                        setDataSource(getApplication(), track.audioUri)
                        prepare()
                        val duration = duration
                        _state.update { it.copy(playbackDurationMs = duration) }
                        start()
                        setOnCompletionListener {
                            stopPlaybackTracking()
                            _state.update { it.copy(isPlaying = false, playingTrackId = null, playbackPositionMs = 0) }
                            currentPlayingTrack = null
                            abandonAudioFocus()
                            updateMediaSession(null)
                        }
                    }
                }
                _state.update { it.copy(isPlaying = true, playingTrackId = track.id) }
                startPlaybackTracking()
                updateMediaSession(track)
            }.onFailure { e ->
                _state.update { it.copy(message = "Playback error: ${e.message}") }
            }
        }
    }

    private fun updateMediaSession(track: TrackItem?) {
        val session = mediaSession ?: return
        if (track == null) {
            session.isActive = false
            notificationManager.cancel(PLAYBACK_NOTIFICATION_ID)
            return
        }

        val metadataBuilder = android.media.MediaMetadata.Builder()
            .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, track.title)
            .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, track.artist)
            .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, track.album)
            .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, track.durationSeconds * 1000L)

        // Load artwork for media session if available
        track.artUri?.let { path ->
            runCatching {
                val bitmap = android.graphics.BitmapFactory.decodeFile(path)
                if (bitmap != null) {
                    metadataBuilder.putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap)
                }
            }
        }

        session.setMetadata(metadataBuilder.build())

        val isPlaying = _state.value.isPlaying
        val state = if (isPlaying) {
            android.media.session.PlaybackState.STATE_PLAYING
        } else {
            android.media.session.PlaybackState.STATE_PAUSED
        }

        val currentPos = mediaPlayer?.currentPosition?.toLong() ?: _state.value.playbackPositionMs.toLong()

        session.setPlaybackState(android.media.session.PlaybackState.Builder()
            .setActions(
                android.media.session.PlaybackState.ACTION_PLAY or
                android.media.session.PlaybackState.ACTION_PAUSE or
                android.media.session.PlaybackState.ACTION_STOP or
                android.media.session.PlaybackState.ACTION_SEEK_TO
            )
            .setState(state, currentPos, if (isPlaying) 1.0f else 0.0f)
            .build())

        session.isActive = true
        showPlaybackNotification(track)
    }

    private fun showPlaybackNotification(track: TrackItem) {
        val app = getApplication<Application>()
        val contentIntent = PendingIntent.getActivity(
            app,
            0,
            Intent(app, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseIntent = PendingIntent.getBroadcast(
            app,
            1,
            Intent("app.lrcget.android.ACTION_PLAY_PAUSE"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val isPlaying = _state.value.isPlaying
        val actionIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val actionText = if (isPlaying) "Pause" else "Play"

        // Load Large Icon (Cover Art)
        val largeIcon = track.artUri?.let { path ->
            runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull()
        }

        val notification = Notification.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(largeIcon)
            .setContentTitle(track.title)
            .setContentText(track.artist)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(app, actionIcon),
                    actionText,
                    playPauseIntent
                ).build()
            )
            .setStyle(Notification.MediaStyle()
                .setMediaSession(mediaSession?.sessionToken)
                .setShowActionsInCompactView(0)
            )
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()

        notificationManager.notify(PLAYBACK_NOTIFICATION_ID, notification)
    }

    private fun abandonAudioFocus() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    private fun startPlaybackTracking() {
        playbackJob?.cancel()
        playbackJob = viewModelScope.launch {
            while (true) {
                val pos = mediaPlayer?.currentPosition ?: 0
                if (_state.value.playbackPositionMs / 500 != pos / 500) {
                    _state.update { it.copy(playbackPositionMs = pos) }
                }
                kotlinx.coroutines.delay(200)
            }
        }
    }

    private fun stopPlaybackTracking() {
        playbackJob?.cancel()
        playbackJob = null
    }

    private fun pauseAudio() {
        mediaPlayer?.pause()
        stopPlaybackTracking()
        abandonAudioFocus()
        _state.update { it.copy(isPlaying = false) }
        currentPlayingTrack?.let { updateMediaSession(it) }
    }

    fun stopAudio() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        currentPlayingTrack = null
        stopPlaybackTracking()
        abandonAudioFocus()
        _state.update { it.copy(isPlaying = false, playingTrackId = null, playbackPositionMs = 0) }
        updateMediaSession(null)
    }

    fun seekTo(posMs: Int) {
        mediaPlayer?.seekTo(posMs)
        _state.update { it.copy(playbackPositionMs = posMs) }
        currentPlayingTrack?.let { updateMediaSession(it) }
    }

    override fun onCleared() {
        super.onCleared()
        mediaPlayer?.release()
        mediaPlayer = null
        mediaSession?.release()
        mediaSession = null
        stopPlaybackTracking()
        abandonAudioFocus()
    }

    fun deleteSelectedLyrics() {
        val selectedIds = _state.value.selectedTrackIds
        if (selectedIds.isEmpty()) return

        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, message = "Deleting lyrics...") }
            val tracks = _state.value.tracks
            selectedIds.forEach { id ->
                val track = tracks.find { it.id == id } ?: return@forEach
                val result = repository.deleteLyrics(track)
                updateTrack(id) { result }
            }
            _state.update { it.copy(isBusy = false, message = "Deleted ${selectedIds.size} lyrics", selectedTrackIds = emptySet()) }
            saveTracks(_state.value.tracks)
        }
    }

    fun publishLyrics(
        trackName: String,
        artistName: String,
        albumName: String,
        duration: Int,
        plainLyrics: String,
        syncedLyrics: String
    ) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _state.update { it.copy(isPublishing = true, publishStatus = "Solving cryptographic challenge...") }
            val result = lrclibClient.publishLyrics(
                trackName, artistName, albumName, duration, plainLyrics, syncedLyrics
            )
            
            _state.update { 
                it.copy(
                    isPublishing = false, 
                    publishStatus = if (result.isSuccess) "Success! Lyrics published." else "Failed: ${result.exceptionOrNull()?.message ?: "Unknown error"}"
                )
            }
        }
    }

    fun clearPublishStatus() {
        _state.update { it.copy(publishStatus = null) }
    }

    private fun updateTrack(id: String, transform: (TrackItem) -> TrackItem) {
        _state.update { state ->
            val index = state.tracks.indexOfFirst { it.id == id }
            if (index == -1) return@update state
            val newList = state.tracks.toMutableList()
            newList[index] = transform(state.tracks[index])
            state.copy(tracks = newList)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LRCGET progress",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows scanning, lyrics search, and export progress"
        }
        notificationManager.createNotificationChannel(channel)
    }

    private var lastNotificationTime = 0L

    private fun notifyProgress(
        text: String,
        progress: Int,
        max: Int,
        indeterminate: Boolean,
        ongoing: Boolean = true
    ) {
        val now = System.currentTimeMillis()
        if (ongoing && !indeterminate && max > 0 && progress < max && now - lastNotificationTime < 500) {
            return
        }
        lastNotificationTime = now

        val app = getApplication<Application>()
        val contentIntent = PendingIntent.getActivity(
            app,
            0,
            Intent(app, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = Notification.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("LRCGET")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setProgress(max, progress, indeterminate)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "lrcget_progress"
        private const val NOTIFICATION_ID = 1001
        private const val PLAYBACK_NOTIFICATION_ID = 1002
    }
}
