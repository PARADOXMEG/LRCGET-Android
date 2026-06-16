package app.lrcget.android.data

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import app.lrcget.android.model.LyricsOutputMode
import app.lrcget.android.model.LyricsStatus
import app.lrcget.android.model.TrackItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.io.FileOutputStream
import java.text.Collator
import java.util.ArrayDeque
import java.util.Locale

class MusicLibraryRepository(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver
    private val lrclibClient = LrclibClient()
    private val parentFolders = mutableMapOf<String, ParentDirectory>()

    suspend fun scan(rootUri: Uri, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<TrackItem> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<TrackItem>()
        parentFolders.clear()

        val rootId = DocumentsContract.getTreeDocumentId(rootUri)
        
        onProgress(0, 0)
        
        val foundFiles = mutableListOf<ScannedAudioFile>()
        val folderQueue = ArrayDeque<String>()
        folderQueue.add(rootId)
        var scannedFolders = 0
        
        while (folderQueue.isNotEmpty()) {
            val dirId = folderQueue.removeFirst()
            scannedFolders += 1
            val parent = ParentDirectory(
                rootUri = rootUri,
                documentId = dirId,
                uri = DocumentsContract.buildDocumentUriUsingTree(rootUri, dirId)
            )
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, dirId)
            
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )

            val audioInDirectory = mutableListOf<Pair<Uri, String>>()
            val lrcBaseNames = mutableSetOf<String>()

            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idIdx)
                    val mime = cursor.getString(mimeIdx)
                    val name = cursor.getString(nameIdx) ?: continue

                    if (DocumentsContract.Document.MIME_TYPE_DIR == mime) {
                        folderQueue.add(docId)
                    } else if (name.isAudioFile()) {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, docId)
                        audioInDirectory.add(docUri to name)
                        parentFolders[docUri.toString()] = parent
                    } else if (name.endsWith(".lrc", ignoreCase = true)) {
                        lrcBaseNames.add(name.substringBeforeLast('.').lowercase())
                    }
                }
            }

            audioInDirectory.forEach { (uri, name) ->
                val baseName = name.substringBeforeLast('.', name).lowercase()
                foundFiles.add(
                    ScannedAudioFile(
                        uri = uri,
                        fileName = name,
                        parent = parent,
                        hasLrcFile = lrcBaseNames.contains(baseName)
                    )
                )
            }
            onProgress(scannedFolders, -foundFiles.size)
        }
        
        val totalCount = foundFiles.size
        if (totalCount == 0) return@withContext emptyList()

        foundFiles.forEachIndexed { index, file ->
            tracks.add(toTrack(file))
            onProgress(index + 1, totalCount)
        }

        val collator = Collator.getInstance(Locale.getDefault()).apply {
            strength = Collator.PRIMARY
        }
        tracks.sortedWith { first, second ->
            collator.compare(first.sortName(), second.sortName())
        }
    }

    fun restoreParentFolders(rootUri: Uri, tracks: List<TrackItem>) {
        tracks.forEach { track ->
            runCatching {
                val dirId = DocumentsContract.getDocumentId(track.parentUri)
                parentFolders[track.id] = ParentDirectory(rootUri, dirId, track.parentUri)
            }
        }
    }

    private fun toTrack(file: ScannedAudioFile): TrackItem {
        val uri = file.uri
        val fileName = file.fileName
        val baseName = fileName.substringBeforeLast('.', fileName)
        val metadata = readMetadata(uri, fileName)
        val title = metadata.title.readableOrBlank().ifBlank { baseName.cleanTitle() }
        val id = uri.toString()

        val hasLrc = file.hasLrcFile
        
        val artist = metadata.artist.readableOrBlank()
        val album = metadata.album.readableOrBlank()

        val subtitle = listOf(artist, album).filter { it.isNotBlank() }
            .joinToString(" - ")
            .ifBlank { fileName }

        val hasSynced = hasLrc || metadata.hasSyncedEmbeddedLyrics
        val hasAnyLyrics = hasLrc || metadata.hasEmbeddedLyrics
        
        val initialStatus = if (hasAnyLyrics) LyricsStatus.Saved else LyricsStatus.Ready
        val initialMessage = when {
            hasLrc -> "LRC file present"
            metadata.hasSyncedEmbeddedLyrics -> "Embedded synced"
            metadata.hasEmbeddedLyrics -> "Embedded plain"
            else -> ""
        }

        return TrackItem(
            id = id,
            audioUri = uri,
            parentUri = file.parent.uri,
            fileName = fileName,
            title = title,
            artist = artist,
            album = album,
            durationSeconds = metadata.durationSeconds,
            lrcFileName = "$baseName.lrc",
            subtitle = subtitle,
            hasLyrics = hasAnyLyrics,
            hasSyncedLyrics = hasSynced,
            isInstrumental = false,
            status = initialStatus,
            message = initialMessage
        )
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
        var savedModes = 0
        val failures = mutableListOf<String>()
        outputModes.forEach { mode ->
            val result = when (mode) {
                LyricsOutputMode.LrcFile -> saveLyricsToFile(lastResult, parent, lyrics.lyrics, ".lrc", overwriteExisting)
                LyricsOutputMode.PlainTextFile -> saveLyricsToFile(lastResult, parent, stripTimestamps(lyrics.lyrics), ".txt", overwriteExisting)
                LyricsOutputMode.EmbeddedSynced -> embedLyrics(lastResult, lyrics.lyrics)
            }
            
            lastResult = if (result.status == LyricsStatus.Saved) {
                savedModes += 1
                result.copy(
                    isInstrumental = lyrics.isInstrumental,
                    message = if (outputModes.size > 1) "Saved ${result.message}" else result.message
                )
            } else {
                failures.add(result.message)
                result
            }
        }
        summarizeSaveResult(lastResult, savedModes, failures)
    }

    private fun summarizeSaveResult(track: TrackItem, savedModes: Int, failures: List<String>): TrackItem {
        return when {
            savedModes > 0 && failures.isEmpty() -> track
            savedModes > 0 -> track.copy(status = LyricsStatus.Saved, message = "Saved with ${failures.size} errors")
            else -> track.copy(status = LyricsStatus.Failed, message = failures.firstOrNull() ?: "Failed")
        }
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


    suspend fun getLyricsResultForTrack(track: TrackItem): LyricsLookupResult? = withContext(Dispatchers.IO) {
        lrclibClient.findLyrics(track, syncedOnly = false)
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
        var savedModes = 0
        val successMessages = mutableListOf<String>()
        val failures = mutableListOf<String>()
        
        outputModes.forEach { mode ->
            val result = when (mode) {
                LyricsOutputMode.LrcFile -> saveLyricsToFile(lastResult, parent, lyrics.lyrics, ".lrc", overwriteExisting)
                LyricsOutputMode.PlainTextFile -> saveLyricsToFile(lastResult, parent, stripTimestamps(lyrics.lyrics), ".txt", overwriteExisting)
                LyricsOutputMode.EmbeddedSynced -> embedLyrics(lastResult, lyrics.lyrics)
            }
            
            if (result.status == LyricsStatus.Saved) {
                savedModes += 1
                successMessages.add(result.message)
                lastResult = result.copy(isInstrumental = lyrics.isInstrumental)
            } else {
                failures.add("${mode.name}: ${result.message}")
            }
        }

        if (savedModes > 0) {
            val combinedMessage = successMessages.distinct().joinToString(", ")
            val finalMessage = if (failures.isEmpty()) combinedMessage else "$combinedMessage (${failures.size} errors)"
            lastResult.copy(message = finalMessage)
        } else {
            lastResult.copy(status = LyricsStatus.Failed, message = failures.firstOrNull() ?: "Failed")
        }
    }

    suspend fun deleteLyrics(track: TrackItem): TrackItem = withContext(Dispatchers.IO) {
        val parent = parentFolders[track.id]
            ?: return@withContext track.copy(status = LyricsStatus.Failed, message = "Scan again before deleting")

        // 1. Delete external files (.lrc, .txt)
        val lrcFile = findChildUri(parent, track.lrcFileName)
        val txtFile = findChildUri(parent, track.fileName.substringBeforeLast('.') + ".txt")
        
        var deletedLrc = false
        var deletedTxt = false
        
        lrcFile?.let {
            deletedLrc = runCatching { DocumentsContract.deleteDocument(resolver, it) }.getOrDefault(false)
        }
        txtFile?.let {
            deletedTxt = runCatching { DocumentsContract.deleteDocument(resolver, it) }.getOrDefault(false)
        }
        
        // 2. Clear embedded lyrics
        var clearedEmbedded = false
        val extension = track.fileName.substringAfterLast('.', "").lowercase()
        if (extension in setOf("mp3", "flac", "m4a", "ogg", "opus", "wav", "aac", "wma")) {
            val tempFile = File(context.cacheDir, "temp_delete_${System.currentTimeMillis()}.$extension")
            val embedResult = runCatching {
                resolver.openInputStream(track.audioUri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Could not read file")

                if (track.fileName.endsWith(".mp3", ignoreCase = true)) {
                    val bytes = tempFile.readBytes()
                    // Re-use Id3SyncedLyricsWriter to rebuild tag without SYLT/USLT
                    val updatedBytes = Id3SyncedLyricsWriter.writeSyncedLyrics(bytes, "") 
                    tempFile.writeBytes(updatedBytes)
                } else {
                    val audioFile = AudioFileIO.read(tempFile)
                    val tag = audioFile.tag
                    if (tag != null) {
                        tag.deleteField(FieldKey.LYRICS)
                        audioFile.commit()
                    }
                }

                resolver.openOutputStream(track.audioUri, "wt")?.use { output ->
                    tempFile.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Could not write file")
                true
            }.getOrDefault(false)
            clearedEmbedded = embedResult
            if (tempFile.exists()) tempFile.delete()
        }

        val messages = mutableListOf<String>()
        if (deletedLrc) messages.add("LRC")
        if (deletedTxt) messages.add("TXT")
        if (clearedEmbedded) messages.add("Embedded")

        track.copy(
            status = LyricsStatus.Ready, 
            message = if (messages.isEmpty()) "No lyrics to delete" else "Deleted: ${messages.joinToString(", ")}",
            hasLyrics = false,
            hasSyncedLyrics = false
        )
    }

    private fun saveLyricsToFile(
        track: TrackItem,
        parent: ParentDirectory,
        lyrics: String,
        extension: String,
        overwriteExisting: Boolean
    ): TrackItem {
        val fileName = track.fileName.substringBeforeLast('.') + extension
        val existing = findChildUri(parent, fileName)
        if ((existing != null) && !overwriteExisting) {
            return track.copy(status = LyricsStatus.Skipped, message = "Exists: $extension")
        }
        val target = existing ?: DocumentsContract.createDocument(resolver, parent.uri, lyricsMimeType(extension), fileName)
            ?: return track.copy(status = LyricsStatus.Failed, message = "Create error: $extension")

        val saved = runCatching {
            resolver.openOutputStream(target, "wt")?.use { stream ->
                stream.write(lyrics.toByteArray(Charsets.UTF_8))
            } ?: error("Open error")
        }

        return if (saved.isSuccess) {
            val type = if (extension.equals(".lrc", ignoreCase = true)) "LRC file" else "Text file"
            track.copy(
                status = LyricsStatus.Saved,
                message = type,
                hasLyrics = true,
                hasSyncedLyrics = track.hasSyncedLyrics || extension.equals(".lrc", ignoreCase = true)
            )
        } else {
            track.copy(status = LyricsStatus.Failed, message = saved.exceptionOrNull()?.message.orEmpty())
        }
    }

    private fun stripTimestamps(lyrics: String): String {
        val tagPattern = Regex("\\[\\d{1,2}:\\d{2}(?:[.:]\\d{1,3})?]")
        return lyrics.lines().joinToString("\n") { line ->
            line.replace(tagPattern, "").trim()
        }.trim()
    }

    private fun embedLyrics(track: TrackItem, lyrics: String): TrackItem {
        val extension = track.fileName.substringAfterLast('.', "").takeIf { it.isNotBlank() } ?: "audio"
        val tempFile = File(context.cacheDir, "temp_tagging_${System.currentTimeMillis()}.$extension")
        return runCatching {
            // Copy from Uri to temp file
            resolver.openInputStream(track.audioUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: error("Read error")

            val isSynced = lyrics.hasLrcTimestamps()
            if (track.fileName.endsWith(".mp3", ignoreCase = true)) {
                val updatedBytes = Id3SyncedLyricsWriter.writeSyncedLyrics(tempFile.readBytes(), lyrics)
                tempFile.writeBytes(updatedBytes)
            } else {
                val audioFile = AudioFileIO.read(tempFile)
                val tag = audioFile.tag ?: audioFile.createDefaultTag()
                tag.setField(FieldKey.LYRICS, lyrics)
                audioFile.tag = tag
                audioFile.commit()
            }

            // Copy back to Uri
            resolver.openOutputStream(track.audioUri, "wt")?.use { output ->
                tempFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: error("Write error")

            track.copy(
                status = LyricsStatus.Saved,
                message = if (isSynced) "Embedded synced" else "Embedded plain",
                hasLyrics = true,
                hasSyncedLyrics = isSynced
            )
        }.getOrElse {
            track.copy(status = LyricsStatus.Failed, message = "Embed fail: ${it.message}")
        }.also {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private fun readMetadata(uri: Uri, fileName: String? = null): AudioMetadata {
        val retriever = MediaMetadataRetriever()
        var metadata = AudioMetadata()
        
        runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                retriever.setDataSource(descriptor.fileDescriptor)
                
                fun getMeta(key: Int): String {
                    return retriever.extractMetadata(key).orEmpty().fixEncoding()
                }

                metadata = metadata.copy(
                    title = getMeta(MediaMetadataRetriever.METADATA_KEY_TITLE).readableOrBlank(),
                    artist = getMeta(MediaMetadataRetriever.METADATA_KEY_ARTIST).readableOrBlank(),
                    album = getMeta(MediaMetadataRetriever.METADATA_KEY_ALBUM).readableOrBlank(),
                    durationSeconds = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                        ?.div(1000)
                        ?.toInt()
                        ?: 0
                )
            }
        }
        retriever.release()

        // Use jaudiotagger for more precise metadata and lyrics detection
        val extension = fileName?.substringAfterLast('.', "")?.lowercase() 
            ?: uri.toString().substringAfterLast('.', "").lowercase()

        if (extension in setOf("mp3", "flac", "m4a", "ogg", "opus", "wav", "aac", "wma")) {
            val tempFile = File(context.cacheDir, "temp_meta_${System.currentTimeMillis()}.$extension")
            runCatching {
                resolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
                val audioFile = AudioFileIO.read(tempFile)
                val tag = audioFile.tag
                if (tag != null) {
                    val lyrics = tag.getFirst(FieldKey.LYRICS)
                    val hasSynced = lyrics.hasLrcTimestamps()
                    
                    val tagTitle = tag.getFirst(FieldKey.TITLE).readableOrBlank()
                    val tagArtist = tag.getFirst(FieldKey.ARTIST).readableOrBlank()
                    val tagAlbum = tag.getFirst(FieldKey.ALBUM).readableOrBlank()

                    metadata = metadata.copy(
                        title = tagTitle.ifBlank { metadata.title },
                        artist = tagArtist.ifBlank { metadata.artist },
                        album = tagAlbum.ifBlank { metadata.album },
                        hasEmbeddedLyrics = lyrics.isNotBlank(),
                        hasSyncedEmbeddedLyrics = hasSynced
                    )
                }
            }
            if (tempFile.exists()) tempFile.delete()
        }

        return metadata
    }

    private fun String.fixEncoding(): String {
        if (this.isBlank()) return this
        if (none { it.code > 127 }) return this
        
        return runCatching {
            val bytes = this.toByteArray(Charsets.ISO_8859_1)
            val back = String(bytes, Charsets.ISO_8859_1)
            if (back != this) return this 
            
            val decoded = String(bytes, Charsets.UTF_8)
            if (decoded != this && !decoded.contains('\uFFFD')) {
                decoded
            } else {
                this
            }
        }.getOrDefault(this)
    }

    private fun String.readableOrBlank(): String {
        val fixed = trim().fixEncoding()
        if (fixed.isBlank()) return ""
        
        // Don't discard the whole string just because of one replacement character or some questions.
        // We replace Unicode replacement chars with '?' for display, but keep the rest.
        val cleaned = fixed.replace('\uFFFD', '?')
        
        val visible = cleaned.filterNot { it.isWhitespace() }
        if (visible.isEmpty()) return ""

        // Only discard if the string is entirely question marks and reasonably long, 
        // suggesting it was complete garbage from the start.
        if (visible.length >= 6 && visible.all { it == '?' || it == '？' }) return ""

        return cleaned
    }

    private fun String.isAudioFile(): Boolean =
        substringAfterLast('.', "").lowercase() in setOf("mp3", "flac", "m4a", "ogg", "opus", "wav", "aac", "wma")

    private fun String.cleanTitle(): String =
        replace(Regex("^\\d+\\s*[-_. ]\\s*"), "").replace('_', ' ').trim()

    private fun TrackItem.sortName(): String =
        title.ifBlank { fileName.substringBeforeLast('.', fileName) }.trim()

    private fun String.hasLrcTimestamps(): Boolean =
        Regex("\\[\\d{1,2}:\\d{2}(?:[.:]\\d{1,3})?]").containsMatchIn(this)

    private fun findChildUri(parent: ParentDirectory, fileName: String): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent.rootUri, parent.documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )

        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx)
                if (name.equals(fileName, ignoreCase = true)) {
                    val docId = cursor.getString(idIdx)
                    return DocumentsContract.buildDocumentUriUsingTree(parent.rootUri, docId)
                }
            }
        }

        return null
    }

    private fun lyricsMimeType(extension: String): String =
        if (extension.equals(".txt", ignoreCase = true) || extension.equals(".lrc", ignoreCase = true)) {
            "text/plain"
        } else {
            "application/octet-stream"
        }

    private data class ParentDirectory(
        val rootUri: Uri,
        val documentId: String,
        val uri: Uri
    )

    private data class ScannedAudioFile(
        val uri: Uri,
        val fileName: String,
        val parent: ParentDirectory,
        val hasLrcFile: Boolean
    )

    private data class AudioMetadata(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val durationSeconds: Int = 0,
        val hasEmbeddedLyrics: Boolean = false,
        val hasSyncedEmbeddedLyrics: Boolean = false
    )
}
