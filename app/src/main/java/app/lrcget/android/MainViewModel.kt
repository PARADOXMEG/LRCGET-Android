package app.lrcget.android

import android.app.Application
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
    val themeMode: ThemeMode = ThemeMode.System,
    val isAmoled: Boolean = false,
) {
    val savedCount: Int get() = tracks.count { it.status == LyricsStatus.Saved }
    val missingCount: Int get() = tracks.count { it.status == LyricsStatus.Missing }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MusicLibraryRepository(application)
    private val prefs = application.getSharedPreferences("lrcget_prefs", android.content.Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state

    init {
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
            it.uri.toString() == uri.toString() && (it.isReadPermission || it.isWritePermission)
        }

        if (hasPermission) {
            _state.update { it.copy(libraryUri = uri, message = "Library folder restored") }
            scan()
        }
    }

    fun setLibrary(uri: Uri) {
        prefs.edit { putString("library_uri", uri.toString()) }
        _state.update { it.copy(libraryUri = uri, message = "Folder selected") }
        scan()
    }

    fun scan() {
        val uri = _state.value.libraryUri ?: return
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, message = "Scanning music files...") }
            val tracks = repository.scan(uri)
            _state.update {
                it.copy(
                    tracks = tracks,
                    isBusy = false,
                    message = if (tracks.isEmpty()) "No supported music files found" else "Found ${tracks.size} tracks"
                )
            }
        }
    }

    fun downloadAll() {
        val tracks = _state.value.tracks
        if (tracks.isEmpty()) return

        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, message = "Downloading lyrics...") }
            val current = _state.value
            
            val tracksToProcess = tracks.filter { track ->
                when (current.downloadMode) {
                    DownloadMode.All -> true
                    DownloadMode.MissingSynced -> !track.hasSyncedLyrics
                    DownloadMode.MissingAny -> !track.hasLyrics
                }
            }

            tracksToProcess.forEachIndexed { index, track ->
                updateTrack(track.id) { it.copy(status = LyricsStatus.Downloading, message = "Checking LRCLIB") }
                val result = repository.download(track, current.downloadMode == DownloadMode.All, current.outputModes)
                updateTrack(track.id) { result }
                _state.update { it.copy(message = "Processed ${index + 1} of ${tracksToProcess.size}") }

                if (index < tracksToProcess.size - 1) {
                    kotlinx.coroutines.delay(current.searchDelay * 1000L)
                }
            }
            _state.update { it.copy(isBusy = false, message = "Done") }
        }
    }

    fun downloadTrack(track: TrackItem) {
        viewModelScope.launch {
            updateTrack(track.id) { it.copy(status = LyricsStatus.Downloading, message = "Checking LRCLIB") }
            val current = _state.value
            val result = repository.download(track, current.downloadMode == DownloadMode.All, current.outputModes)
            updateTrack(track.id) { result }
        }
    }

    fun previewLyrics(track: TrackItem) {
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true) }
            val results = repository.getAllLyricsForPreview(track)
            _state.update {
                it.copy(
                    isBusy = false,
                    previewTrack = track,
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
        viewModelScope.launch {
            val result = repository.saveManualLyrics(track, lyrics, _state.value.downloadMode == DownloadMode.All, _state.value.outputModes)
            updateTrack(track.id) { result }
            closePreview()
        }
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

    fun setSelectedTab(value: Int) {
        _state.update { it.copy(previousTab = it.selectedTab, selectedTab = value) }
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
}
