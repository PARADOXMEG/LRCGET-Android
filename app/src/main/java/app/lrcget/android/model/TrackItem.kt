package app.lrcget.android.model

import android.net.Uri
import androidx.compose.runtime.Immutable

enum class LyricsStatus {
    Idle,
    Scanning,
    Ready,
    Found,
    Skipped,
    Downloading,
    Saved,
    Missing,
    Failed
}

enum class LyricsOutputMode {
    LrcFile,
    PlainTextFile,
    EmbeddedSynced
}

enum class ThemeMode {
    System,
    Light,
    Dark
}

enum class DownloadMode {
    All,
    MissingSynced,
    MissingAny
}

@Immutable
data class TrackItem(
    val id: String,
    val audioUri: Uri,
    val parentUri: Uri,
    val fileName: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Int,
    val lrcFileName: String,
    val subtitle: String = "",
    val artUri: String? = null,
    val hasLyrics: Boolean = false,
    val hasSyncedLyrics: Boolean = false,
    val isInstrumental: Boolean = false,
    val status: LyricsStatus = LyricsStatus.Ready,
    val message: String = ""
)
