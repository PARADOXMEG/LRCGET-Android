package app.lrcget.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lrcget.android.data.LyricsLookupResult
import app.lrcget.android.data.MusicLibraryRepository
import app.lrcget.android.model.DownloadMode
import app.lrcget.android.model.LyricsOutputMode
import app.lrcget.android.model.LyricsStatus
import app.lrcget.android.model.ThemeMode
import app.lrcget.android.model.TrackItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
) {
    val savedCount: Int get() = tracks.count { it.status == LyricsStatus.Saved }
    val missingCount: Int get() = tracks.count { it.status == LyricsStatus.Missing }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MusicLibraryRepository(application)
    private val prefs = application.getSharedPreferences("lrcget_prefs", android.content.Context.MODE_PRIVATE)
    private val notificationManager = application.getSystemService(NotificationManager::class.java)
    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state
    private var downloadJob: kotlinx.coroutines.Job? = null
    private var scanJob: kotlinx.coroutines.Job? = null

    init {
        createNotificationChannel()
        restoreSettings()
        restoreLibrary()
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

    private fun restoreLibrary() {
        val uriString = prefs.getString("library_uri", null) ?: return
        val uri = uriString.toUri()
        
        // Check if we still have permission
        val hasPermission = getApplication<Application>().contentResolver.persistedUriPermissions.any { 
            it.uri == uri && (it.isReadPermission || it.isWritePermission)
        }

        if (hasPermission) {
            val cachedTracks = loadTracks()
            if (cachedTracks.isNotEmpty()) {
                repository.restoreParentFolders(uri, cachedTracks)
            }
            _state.update { it.copy(
                libraryUri = uri, 
                tracks = cachedTracks,
                message = if (cachedTracks.isNotEmpty()) "Library restored (${cachedTracks.size} tracks)" else "Library folder restored"
            ) }
        }
    }

    private fun saveTracks(tracks: List<TrackItem>) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val array = JSONArray()
                tracks.forEach { track ->
                    val obj = JSONObject().apply {
                        put("id", track.id)
                        put("audioUri", track.audioUri.toString())
                        put("parentUri", track.parentUri.toString())
                        put("fileName", track.fileName)
                        put("title", track.title)
                        put("artist", track.artist)
                        put("album", track.album)
                        put("durationSeconds", track.durationSeconds)
                        put("lrcFileName", track.lrcFileName)
                        put("subtitle", track.subtitle)
                        put("hasLyrics", track.hasLyrics)
                        put("hasSyncedLyrics", track.hasSyncedLyrics)
                        put("isInstrumental", track.isInstrumental)
                        put("status", track.status.name)
                        put("message", track.message)
                    }
                    array.put(obj)
                }
                getApplication<Application>().openFileOutput("tracks_cache.json", android.content.Context.MODE_PRIVATE).use {
                    it.write(array.toString().toByteArray())
                }
            }
        }
    }

    private fun loadTracks(): List<TrackItem> {
        return try {
            val file = File(getApplication<Application>().filesDir, "tracks_cache.json")
            if (!file.exists()) return emptyList()
            val json = file.readText()
            val array = JSONArray(json)
            val list = mutableListOf<TrackItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(TrackItem(
                    id = obj.getString("id"),
                    audioUri = obj.getString("audioUri").toUri(),
                    parentUri = obj.getString("parentUri").toUri(),
                    fileName = obj.getString("fileName"),
                    title = obj.getString("title"),
                    artist = obj.getString("artist"),
                    album = obj.getString("album"),
                    durationSeconds = obj.getInt("durationSeconds"),
                    lrcFileName = obj.getString("lrcFileName"),
                    subtitle = obj.optString("subtitle", ""),
                    hasLyrics = obj.optBoolean("hasLyrics", false),
                    hasSyncedLyrics = obj.optBoolean("hasSyncedLyrics", false),
                    isInstrumental = obj.optBoolean("isInstrumental", false),
                    status = runCatching { LyricsStatus.valueOf(obj.getString("status")) }.getOrDefault(LyricsStatus.Ready),
                    message = obj.optString("message", "")
                ))
            }
            list
        } catch (e: Exception) {
            emptyList()
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

    fun scan() {
        val uri = _state.value.libraryUri ?: return
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _state.update { it.copy(isBusy = true, message = "Scanning folder...", operationProgress = 0, operationTotal = 0) }
            notifyProgress("Scanning music folder", 0, 0, true)
            val tracks = repository.scan(uri) { current, total ->
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

        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            val current = _state.value
            
            val tracksToProcess = tracks.filter { track ->
                when (current.downloadMode) {
                    DownloadMode.All -> true
                    DownloadMode.MissingSynced -> !track.hasSyncedLyrics
                    DownloadMode.MissingAny -> !track.hasLyrics
                }
            }

            if (tracksToProcess.isEmpty()) {
                _state.update { it.copy(message = "All lyrics are already up to date") }
                return@launch
            }

            _state.update { it.copy(
                isDownloadingAll = true,
                showDownloadProgress = true,
                isBusy = true, 
                message = "Searching lyrics...",
                operationProgress = 0,
                operationTotal = tracksToProcess.size,
                downloadLog = emptyList(),
                foundCount = 0,
                notFoundCount = 0
            ) }
            notifyProgress("Searching lyrics", 0, tracksToProcess.size, false)

            tracksToProcess.forEachIndexed { index, track ->
                updateTrack(track.id) { it.copy(status = LyricsStatus.Downloading, message = "Checking LRCLIB") }
                notifyProgress("Searching ${track.title}", index, tracksToProcess.size, false)
                
                // Fetch the result but DON'T save it yet
                val lyricsResult: LyricsLookupResult? = repository.getLyricsResultForTrack(track)
                
                val status = if (lyricsResult != null) {
                    _state.update { it.copy(fetchedLyrics = it.fetchedLyrics + (track.id to lyricsResult)) }
                    LyricsStatus.Found
                } else {
                    LyricsStatus.Missing
                }
                
                val msg = if (lyricsResult != null) {
                    if (lyricsResult.isInstrumental) "Instrumental found" else if (lyricsResult.isSynced) "Synced found" else "Plain found"
                } else {
                    "No lyrics found"
                }

                updateTrack(track.id) { it.copy(status = status, message = msg) }
                
                val logEntry = "${track.title}: $msg"
                _state.update { s ->
                    s.copy(
                        message = "Processed ${index + 1} of ${tracksToProcess.size}",
                        operationProgress = index + 1,
                        downloadLog = listOf(logEntry) + s.downloadLog.take(99),
                        foundCount = if (status == LyricsStatus.Found) s.foundCount + 1 else s.foundCount,
                        notFoundCount = if (status == LyricsStatus.Missing) s.notFoundCount + 1 else s.notFoundCount
                    )
                }
                notifyProgress("$msg: ${track.title}", index + 1, tracksToProcess.size, false)

                if (index < tracksToProcess.size - 1) {
                    kotlinx.coroutines.delay(current.searchDelay * 1000L)
                }
            }
            _state.update { it.copy(isBusy = false, isDownloadingAll = false, showDownloadProgress = false, message = "Done") }
            saveTracks(_state.value.tracks)
            notifyProgress(
                "Search complete: ${_state.value.foundCount} found, ${_state.value.notFoundCount} missing",
                tracksToProcess.size,
                tracksToProcess.size,
                indeterminate = false,
                ongoing = false
            )
        }
    }

    fun exportAll() {
        val tracks = _state.value.tracks
        val fetched = _state.value.fetchedLyrics
        if (fetched.isEmpty()) {
            _state.update { it.copy(message = "Nothing to export. Search first.") }
            return
        }

        viewModelScope.launch {
            val current = _state.value
            _state.update { it.copy(
                isDownloadingAll = true,
                showDownloadProgress = true, 
                message = "Exporting lyrics...",
                operationProgress = 0,
                operationTotal = fetched.size,
                downloadLog = emptyList(),
                foundCount = 0,
                notFoundCount = 0
            ) }
            notifyProgress("Exporting lyrics", 0, fetched.size, false)

            var count = 0
            var saved = 0
            var failed = 0
            fetched.forEach { (trackId, lyrics) ->
                val track = tracks.find { it.id == trackId } ?: return@forEach
                updateTrack(track.id) { it.copy(status = LyricsStatus.Downloading, message = "Saving...") }
                notifyProgress("Exporting ${track.title}", count, fetched.size, false)
                
                val result = repository.saveManualLyrics(track, lyrics, true, current.outputModes)
                updateTrack(track.id) { result }
                count++
                if (result.status == LyricsStatus.Saved) saved++ else failed++
                
                val logEntry = "${track.title}: ${result.message}"
                _state.update { s ->
                    s.copy(
                        message = "Exporting $count of ${fetched.size}",
                        operationProgress = count,
                        downloadLog = listOf(logEntry) + s.downloadLog.take(99),
                        foundCount = if (result.status == LyricsStatus.Saved) s.foundCount + 1 else s.foundCount,
                        notFoundCount = if (result.status == LyricsStatus.Saved) s.notFoundCount else s.notFoundCount + 1
                    )
                }
                notifyProgress("${result.message}: ${track.title}", count, fetched.size, false)
            }
            _state.update { it.copy(isBusy = false, isDownloadingAll = false, showDownloadProgress = false, message = "Export complete: $saved saved, $failed failed", fetchedLyrics = emptyMap()) }
            saveTracks(_state.value.tracks)
            notifyProgress(
                "Export complete: $saved saved, $failed failed",
                fetched.size,
                fetched.size,
                indeterminate = false,
                ongoing = false
            )
        }
    }

    fun stopDownload() {
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
            updateTrack(track.id) { it.copy(status = LyricsStatus.Downloading, message = "Searching LRCLIB") }
            val lyricsResult: LyricsLookupResult? = repository.getLyricsResultForTrack(track)
            
            if (lyricsResult != null) {
                _state.update { it.copy(fetchedLyrics = it.fetchedLyrics + (track.id to lyricsResult)) }
                val msg = if (lyricsResult.isInstrumental) "Instrumental found" else if (lyricsResult.isSynced) "Synced found" else "Plain found"
                updateTrack(track.id) { it.copy(status = LyricsStatus.Found, message = msg) }
            } else {
                updateTrack(track.id) { it.copy(status = LyricsStatus.Missing, message = "No lyrics found") }
            }
            saveTracks(_state.value.tracks)
        }
    }

    fun previewLyrics(track: TrackItem) {
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, previewTrack = track, previewResults = emptyList()) }
            val results = repository.getAllLyricsForPreview(track)
            _state.update {
                it.copy(
                    isBusy = false,
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
            _state.update { it.copy(isBusy = true) }
            val results = repository.searchLyricsManual(trackName, artistName, albumName)
            _state.update { it.copy(isBusy = false, previewResults = results) }
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

    private fun notifyProgress(
        text: String,
        progress: Int,
        max: Int,
        indeterminate: Boolean,
        ongoing: Boolean = true
    ) {
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
    }
}
