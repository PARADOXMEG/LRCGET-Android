package app.lrcget.android.data

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.lrcget.android.model.LyricsOutputMode
import app.lrcget.android.model.LyricsStatus
import app.lrcget.android.model.TrackItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MusicLibraryRepository(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver
    private val lrclibClient = LrclibClient()
    private val parentFolders = mutableMapOf<String, DocumentFile>()

    suspend fun scan(rootUri: Uri): List<TrackItem> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return@withContext emptyList()
        val tracks = mutableListOf<TrackItem>()
        parentFolders.clear()
        walk(root, tracks)
        tracks.sortedBy { "${it.artist} ${it.album} ${it.title}".lowercase() }
    }

    suspend fun download(
        track: TrackItem,
        overwriteExisting: Boolean,
        outputModes: Set<LyricsOutputMode>,
    ): TrackItem = withContext(Dispatchers.IO) {
        val parent = parentFolders[track.id]
            ?: return@withContext track.copy(status = LyricsStatus.Failed, message = "Scan again before downloading")

        val lyrics = lrclibClient.findLyrics(track, syncedOnly = false)
            ?: return@withContext track.copy(status = LyricsStatus.Missing, message = "No lyrics match found")

        var lastResult = track
        outputModes.forEach { mode ->
            lastResult = when (mode) {
                LyricsOutputMode.LrcFile -> saveLyricsToFile(track, parent, lyrics.lyrics, ".lrc", overwriteExisting)
                LyricsOutputMode.PlainTextFile -> saveLyricsToFile(track, parent, stripTimestamps(lyrics.lyrics), ".lrc", overwriteExisting)
                LyricsOutputMode.EmbeddedSynced -> {
                    if (lyrics.isSynced) {
                        embedSyncedLyrics(track, lyrics.lyrics)
                    } else {
                        track.copy(status = LyricsStatus.Failed, message = "Cannot embed non-synced lyrics")
                    }
                }
            }
        }
        lastResult
    }

    suspend fun searchLyrics(track: TrackItem): TrackItem = withContext(Dispatchers.IO) {
        val result = lrclibClient.findLyrics(track, syncedOnly = false)
        if (result == null) {
            track.copy(status = LyricsStatus.Missing, message = "No lyrics found")
        } else {
            val kind = when {
                result.isInstrumental -> "Instrumental"
                result.isSynced -> "Synced lyrics found"
                else -> "Plain lyrics found"
            }
            track.copy(status = LyricsStatus.Found, message = kind)
        }
    }


    suspend fun getAllLyricsForPreview(track: TrackItem): List<LyricsLookupResult> = withContext(Dispatchers.IO) {
        lrclibClient.findAllLyrics(track)
    }

    suspend fun searchLyricsManual(
        trackName: String,
        artistName: String = "",
        albumName: String = ""
    ): List<LyricsLookupResult> = withContext(Dispatchers.IO) {
        lrclibClient.searchLyricsManual(trackName, artistName, albumName)
    }

    suspend fun saveManualLyrics(
        track: TrackItem,
        lyrics: LyricsLookupResult,
        overwriteExisting: Boolean,
        outputModes: Set<LyricsOutputMode>
    ): TrackItem = withContext(Dispatchers.IO) {
        val parent = parentFolders[track.id]
            ?: return@withContext track.copy(status = LyricsStatus.Failed, message = "Scan again before saving")

        var lastResult = track
        outputModes.forEach { mode ->
            lastResult = when (mode) {
                LyricsOutputMode.LrcFile -> saveLyricsToFile(track, parent, lyrics.lyrics, ".lrc", overwriteExisting)
                LyricsOutputMode.PlainTextFile -> saveLyricsToFile(track, parent, stripTimestamps(lyrics.lyrics), ".lrc", overwriteExisting)
                LyricsOutputMode.EmbeddedSynced -> {
                    if (lyrics.isSynced) {
                        embedSyncedLyrics(track, lyrics.lyrics)
                    } else {
                        track.copy(status = LyricsStatus.Failed, message = "Cannot embed non-synced lyrics")
                    }
                }
            }
        }
        lastResult
    }

    private fun saveLyricsToFile(
        track: TrackItem,
        parent: DocumentFile,
        lyrics: String,
        extension: String,
        overwriteExisting: Boolean
    ): TrackItem {
        val fileName = track.fileName.substringBeforeLast('.') + extension
        val existing = parent.findFile(fileName)
        if ((existing != null) && !overwriteExisting) {
            return track.copy(status = LyricsStatus.Skipped, message = "Lyrics already exist")
        }
        val target = existing ?: parent.createFile("application/octet-stream", fileName)
            ?: return track.copy(status = LyricsStatus.Failed, message = "Could not create lyrics file")

        val saved = runCatching {
            resolver.openOutputStream(target.uri, "wt")?.use { stream ->
                stream.write(lyrics.toByteArray(Charsets.UTF_8))
            } ?: error("Could not open output stream")
        }

        return if (saved.isSuccess) {
            track.copy(status = LyricsStatus.Saved, message = "Saved $fileName")
        } else {
            track.copy(status = LyricsStatus.Failed, message = saved.exceptionOrNull()?.message.orEmpty())
        }
    }

    private fun stripTimestamps(lyrics: String): String {
        return lyrics.lines().joinToString("\n") { line ->
            line.replace(Regex("\\[\\d{2}:\\d{2}\\.\\d{2,3}]"), "").trim()
        }.trim()
    }

    private fun embedSyncedLyrics(track: TrackItem, lyrics: String): TrackItem {
        if (!track.fileName.endsWith(".mp3", ignoreCase = true)) {
            return track.copy(status = LyricsStatus.Failed, message = "Embedded synced lyrics support MP3/ID3 only")
        }

        val embedded = runCatching {
            val original = resolver.openInputStream(track.audioUri)?.use { it.readBytes() }
                ?: error("Could not read audio file")
            val updated = Id3SyncedLyricsWriter.writeSyncedLyrics(original, lyrics)
            resolver.openOutputStream(track.audioUri, "wt")?.use { it.write(updated) }
                ?: error("Could not write audio file")
        }

        return if (embedded.isSuccess) {
            track.copy(status = LyricsStatus.Saved, message = "Embedded synced lyrics in ID3 SYLT")
        } else {
            track.copy(status = LyricsStatus.Failed, message = embedded.exceptionOrNull()?.message.orEmpty())
        }
    }

    private fun walk(folder: DocumentFile, tracks: MutableList<TrackItem>) {
        folder.listFiles().forEach { file ->
            when {
                file.isDirectory -> walk(file, tracks)
                file.isFile && file.name?.isAudioFile() == true -> tracks += file.toTrack(folder)
            }
        }
    }

    private fun DocumentFile.toTrack(parent: DocumentFile): TrackItem {
        val fileName = name.orEmpty()
        val baseName = fileName.substringBeforeLast('.', fileName)
        val metadata = readMetadata(uri)
        val title = metadata.title.ifBlank { baseName.cleanTitle() }
        val id = uri.toString()
        parentFolders[id] = parent

        val lrcFile = parent.findFile("$baseName.lrc")
        val hasLrc = lrcFile != null
        
        val subtitle = listOf(metadata.artist, metadata.album).filter { it.isNotBlank() }
            .joinToString(" - ")
            .ifBlank { fileName }

        return TrackItem(
            id = id,
            audioUri = uri,
            parentUri = parent.uri,
            fileName = fileName,
            title = title,
            artist = metadata.artist,
            album = metadata.album,
            durationSeconds = metadata.durationSeconds,
            lrcFileName = "$baseName.lrc",
            subtitle = subtitle,
            hasLyrics = hasLrc || metadata.hasEmbeddedLyrics,
            hasSyncedLyrics = hasLrc || metadata.hasSyncedEmbeddedLyrics
        )
    }

    private fun readMetadata(uri: Uri): AudioMetadata {
        val retriever = MediaMetadataRetriever()
        return runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                retriever.setDataSource(descriptor.fileDescriptor)
                val hasEmbedded = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != null
                // Note: MediaMetadataRetriever doesn't easily expose SYLT vs USLT
                // For now we just check if it has a title to verify access
                AudioMetadata(
                    title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty(),
                    artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                    album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(),
                    durationSeconds = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                        ?.div(1000)
                        ?.toInt()
                        ?: 0,
                    hasEmbeddedLyrics = false, // Simplified
                    hasSyncedEmbeddedLyrics = false // Simplified
                )
            } ?: AudioMetadata()
        }.getOrDefault(AudioMetadata()).also {
            retriever.release()
        }
    }

    private fun String.isAudioFile(): Boolean =
        substringAfterLast('.', "").lowercase() in setOf("mp3", "flac", "m4a", "ogg", "opus", "wav", "aac", "wma")

    private fun String.cleanTitle(): String =
        replace(Regex("^\\d+\\s*[-_. ]\\s*"), "").replace('_', ' ').trim()

    private data class AudioMetadata(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val durationSeconds: Int = 0,
        val hasEmbeddedLyrics: Boolean = false,
        val hasSyncedEmbeddedLyrics: Boolean = false
    )
}
