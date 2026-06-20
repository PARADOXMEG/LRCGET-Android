package app.lrcget.android.data

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import app.lrcget.android.model.LyricsOutputMode
import app.lrcget.android.model.LyricsStatus
import app.lrcget.android.model.TrackItem
import kotlinx.coroutines.*
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

    init {
        // Enable Android-specific fixes for jaudiotagger
        org.jaudiotagger.tag.TagOptionSingleton.getInstance().isAndroid = true
    }

    suspend fun scan(rootUri: Uri, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<TrackItem> = withContext(Dispatchers.IO) {
        clearInternalCache()
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

        // Parallel processing of metadata
        // Reduced parallelism to avoid disk I/O congestion which causes lag
        val parallelism = 3
        val processedCount = java.util.concurrent.atomic.AtomicInteger(0)
        
        val tracks = coroutineScope {
            foundFiles.chunked((totalCount / parallelism).coerceAtLeast(1)).map { chunk ->
                async {
                    chunk.map { file ->
                        val track = toTrack(file)
                        val current = processedCount.incrementAndGet()
                        onProgress(current, totalCount)
                        track
                    }
                }
            }.awaitAll().flatten()
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
        val metadata = readMetadata(uri, fileName, file.hasLrcFile)
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

        // Save artwork to cache
        val artFile = File(context.cacheDir, "art_${id.hashCode()}.jpg")
        val artPath = if (artFile.exists()) {
            artFile.absolutePath
        } else if (metadata.artwork != null) {
            runCatching {
                artFile.outputStream().use { it.write(metadata.artwork) }
                artFile.absolutePath
            }.getOrNull()
        } else null

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
            artUri = artPath,
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
                LyricsOutputMode.EmbeddedSynced -> embedLyrics(lastResult, lyrics.lyrics, parent)
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
        val firstAttempt = lrclibClient.findLyrics(track, syncedOnly = false)
        if (firstAttempt != null) return@withContext firstAttempt

        // Smart Fallback: Clean title and retry
        val cleanTitle = track.title.stripExtraInfo()
        if (cleanTitle != track.title) {
            val retryTrack = track.copy(title = cleanTitle)
            return@withContext lrclibClient.findLyrics(retryTrack, syncedOnly = false)
        }
        
        null
    }

    private fun String.stripExtraInfo(): String {
        return this.replace(Regex("\\s*\\(.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[.*?]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*-\\s*(Remaster|Radio Edit|Live|Deluxe|Bonus).*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*feat\\..*$", RegexOption.IGNORE_CASE), "")
            .trim()
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
                LyricsOutputMode.EmbeddedSynced -> embedLyrics(lastResult, lyrics.lyrics, parent)
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
        val rawExtension = track.fileName.substringAfterLast('.', "").lowercase()
        if (rawExtension in setOf("mp3", "flac", "m4a", "ogg", "opus", "wav", "aac", "wma")) {
            val tempFile = File(context.cacheDir, "temp_delete_${System.currentTimeMillis()}.$rawExtension")
            val embedResult = runCatching {
                resolver.openInputStream(track.audioUri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Could not read file")

                val effectiveExtension = when {
                    rawExtension == "ogg" || rawExtension == "opus" -> identifyOggBitstream(tempFile) ?: rawExtension
                    rawExtension == "aac" -> "m4a"
                    else -> rawExtension
                }

                val fileToTag = if (effectiveExtension != rawExtension) {
                    val renamedFile = File(context.cacheDir, "temp_delete_fixed_${System.currentTimeMillis()}.$effectiveExtension")
                    if (tempFile.renameTo(renamedFile)) renamedFile else tempFile
                } else {
                    tempFile
                }

                when (effectiveExtension) {
                    "mp3" -> {
                        val bytes = fileToTag.readBytes()
                        val updatedBytes = Id3SyncedLyricsWriter.writeSyncedLyrics(bytes, "")
                        fileToTag.writeBytes(updatedBytes)
                    }
                    else -> {
                        val audioFile = AudioFileIO.read(fileToTag)
                        val tag = audioFile.tag
                        if (tag != null) {
                            tag.deleteField(FieldKey.LYRICS)
                            audioFile.commit()
                        }
                    }
                }

                resolver.openOutputStream(track.audioUri, "wt")?.use { output ->
                    fileToTag.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Could not write file")
                
                if (fileToTag.exists()) fileToTag.delete()
                if (tempFile.exists()) tempFile.delete()
                true
            }.getOrElse { e ->
                if (tempFile.exists()) tempFile.delete()
                false
            }
            clearedEmbedded = embedResult
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

    fun clearInternalCache() {
        runCatching {
            context.cacheDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("temp_") || 
                    (file.name.startsWith("art_") && System.currentTimeMillis() - file.lastModified() > 86400000)) {
                    file.delete()
                }
            }
        }
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

    private fun embedLyrics(track: TrackItem, lyrics: String, parent: ParentDirectory? = null): TrackItem {
        val rawExtension = track.fileName.substringAfterLast('.', "").lowercase()
        val tempFile = File(context.cacheDir, "temp_tagging_${System.currentTimeMillis()}.$rawExtension")
        
        return runCatching {
            // 1. Copy to temp file
            resolver.openInputStream(track.audioUri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            } ?: error("Read error")

            // 2. Identify correct reader extension for Ogg containers
            val effectiveExtension = when {
                rawExtension == "ogg" || rawExtension == "opus" -> identifyOggBitstream(tempFile) ?: rawExtension
                rawExtension == "aac" -> "m4a"
                else -> rawExtension
            }

            // Automatic Fallback: If it's an Opus audio file, save an external LRC instead of embedding
            if (effectiveExtension == "opus" && parent != null) {
                if (tempFile.exists()) tempFile.delete()
                return saveLyricsToFile(track, parent, lyrics, ".lrc", true)
            }

            // 3. Rename temp file if extension needs adjustment for jaudiotagger
            val fileToTag = if (effectiveExtension != rawExtension) {
                val renamedFile = File(context.cacheDir, "temp_tagging_fixed_${System.currentTimeMillis()}.$effectiveExtension")
                tempFile.renameTo(renamedFile)
                renamedFile
            } else {
                tempFile
            }

            val isSynced = lyrics.hasLrcTimestamps()
            when (effectiveExtension) {
                "mp3" -> {
                    val updatedBytes = Id3SyncedLyricsWriter.writeSyncedLyrics(fileToTag.readBytes(), lyrics)
                    fileToTag.writeBytes(updatedBytes)
                }
                else -> {
                    // jaudiotagger handles Ogg (Vorbis), FLAC, MP4 (M4A), WAV, WMA
                    val audioFile = AudioFileIO.read(fileToTag)
                    val tag = audioFile.tag ?: audioFile.createDefaultTag()
                    tag.setField(FieldKey.LYRICS, lyrics)
                    audioFile.tag = tag
                    audioFile.commit()
                }
            }

            // 4. Write back to original location
            resolver.openOutputStream(track.audioUri, "wt")?.use { output ->
                fileToTag.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Write error")

            if (fileToTag.exists()) fileToTag.delete()
            if (tempFile.exists()) tempFile.delete()

            track.copy(
                status = LyricsStatus.Saved,
                message = if (isSynced) "Embedded synced" else "Embedded plain",
                hasLyrics = true,
                hasSyncedLyrics = isSynced
            )
        }.getOrElse { e ->
            if (tempFile.exists()) tempFile.delete()
            val msg = e.message ?: ""
            val userMsg = when {
                msg.contains("No Reader", ignoreCase = true) -> "Format not supported for embedding"
                msg.contains("Invalid Identification", ignoreCase = true) -> "Header mismatch in ${rawExtension.uppercase()} file"
                else -> "Embed fail: $msg"
            }
            track.copy(status = LyricsStatus.Failed, message = userMsg)
        }
    }

    private fun identifyOggBitstream(file: File): String? {
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(64)
                if (input.read(header) < 64) return null
                val content = String(header, Charsets.ISO_8859_1)
                when {
                    content.contains("OpusHead") -> "opus"
                    content.contains("vorbis") -> "ogg"
                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readMetadata(uri: Uri, fileName: String? = null, skipDeepScan: Boolean = false): AudioMetadata {
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
                        ?: 0,
                    artwork = retriever.embeddedPicture
                )
            }
        }
        retriever.release()

        // Use jaudiotagger for more precise metadata and lyrics detection
        val rawExtension = fileName?.substringAfterLast('.', "")?.lowercase() 
            ?: uri.toString().substringAfterLast('.', "").lowercase()

        if (!skipDeepScan && rawExtension in setOf("mp3", "flac", "m4a", "ogg", "opus", "wav", "aac", "wma")) {
            val tempFile = File(context.cacheDir, "temp_meta_${System.currentTimeMillis()}.$rawExtension")
            runCatching {
                resolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
                
                val effectiveExtension = if (rawExtension == "ogg" || rawExtension == "opus") {
                    identifyOggBitstream(tempFile) ?: rawExtension
                } else if (rawExtension == "aac") {
                    "m4a"
                } else {
                    rawExtension
                }

                val fileToRead = if (effectiveExtension != rawExtension) {
                    val renamedFile = File(context.cacheDir, "temp_meta_fixed_${System.currentTimeMillis()}.$effectiveExtension")
                    tempFile.renameTo(renamedFile)
                    renamedFile
                } else {
                    tempFile
                }

                val audioFile = AudioFileIO.read(fileToRead)
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
                        hasSyncedEmbeddedLyrics = hasSynced,
                        artwork = metadata.artwork ?: tag.firstArtwork?.binaryData
                    )
                }
                if (fileToRead.exists()) fileToRead.delete()
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
        if (extension.equals(".txt", ignoreCase = true)) {
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
        val hasSyncedEmbeddedLyrics: Boolean = false,
        val artwork: ByteArray? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AudioMetadata) return false
            if (title != other.title) return false
            if (artist != other.artist) return false
            if (album != other.album) return false
            if (durationSeconds != other.durationSeconds) return false
            if (hasEmbeddedLyrics != other.hasEmbeddedLyrics) return false
            if (hasSyncedEmbeddedLyrics != other.hasSyncedEmbeddedLyrics) return false
            if (artwork != null) {
                if (other.artwork == null) return false
                if (!artwork.contentEquals(other.artwork)) return false
            } else if (other.artwork != null) return false
            return true
        }

        override fun hashCode(): Int {
            var result = title.hashCode()
            result = 31 * result + artist.hashCode()
            result = 31 * result + album.hashCode()
            result = 31 * result + durationSeconds
            result = 31 * result + hasEmbeddedLyrics.hashCode()
            result = 31 * result + hasSyncedEmbeddedLyrics.hashCode()
            result = 31 * result + (artwork?.contentHashCode() ?: 0)
            return result
        }
    }
}
