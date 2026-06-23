package app.lrcget.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import app.lrcget.android.MainActivity
import app.lrcget.android.R
import app.lrcget.android.data.LyricsLookupResult
import app.lrcget.android.data.MusicLibraryRepository
import app.lrcget.android.model.DownloadMode
import app.lrcget.android.model.LyricsOutputMode
import app.lrcget.android.model.LyricsStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

sealed class DownloadProgress {
    data class Scanning(val current: Int, val total: Int) : DownloadProgress()
    data class Processing(
        val trackId: String, 
        val current: Int, 
        val total: Int, 
        val trackTitle: String, 
        val status: LyricsStatus, 
        val message: String,
        val isExport: Boolean,
        val lyrics: app.lrcget.android.data.LyricsLookupResult? = null
    ) : DownloadProgress()
    data class Done(val saved: Int, val missing: Int, val skipped: Int, val failed: Int, val isExport: Boolean) : DownloadProgress()
    data class Error(val message: String) : DownloadProgress()
}

class LyricsDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var notificationManager: NotificationManager
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var activeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        powerManager = getSystemService(PowerManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rootUriString = intent?.getStringExtra(EXTRA_ROOT_URI)
        if (rootUriString == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val rootUri = Uri.parse(rootUriString)

        val downloadMode = DownloadMode.valueOf(
            intent.getStringExtra(EXTRA_DOWNLOAD_MODE) ?: DownloadMode.MissingAny.name
        )
        val isExport = intent.getBooleanExtra(EXTRA_IS_EXPORT, false)
        val searchDelay = intent.getIntExtra(EXTRA_SEARCH_DELAY, 2)
        val outputModesStrings = intent.getStringArrayExtra(EXTRA_OUTPUT_MODES) ?: arrayOf(LyricsOutputMode.LrcFile.name)
        val outputModes = outputModesStrings.map { LyricsOutputMode.valueOf(it) }.toSet()

        val notificationTitle = if (isExport) "Exporting lyrics" else "Finding lyrics"
        startForegroundCompat(buildNotification(notificationTitle, 0, 0, true))
        
        activeJob?.cancel()
        activeJob = scope.launch {
            try {
                acquireWakeLock()
                runDownload(rootUri, downloadMode, outputModes, searchDelay, isExport)
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e("LyricsDownloadService", "Download failed", e)
                    _progressFlow.emit(DownloadProgress.Error(e.localizedMessage ?: "Unknown error"))
                    notificationManager.notify(
                        notificationId(),
                        buildNotification("Download failed: ${e.localizedMessage ?: "Unknown error"}", 0, 0, false)
                    )
                }
            } finally {
                releaseWakeLock()
                stopSelf(startId)
            }
        }

        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        activeJob?.cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LRCGET:DownloadWakeLock").apply {
            // Set a timeout of 30 minutes just in case
            acquire(30 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
    }

    private suspend fun runDownload(
        rootUri: Uri,
        downloadMode: DownloadMode,
        outputModes: Set<LyricsOutputMode>,
        searchDelay: Int,
        isExport: Boolean
    ) {
        val repository = MusicLibraryRepository(this)
        
        // Load exported lyrics if they exist
        val exportedLyrics = if (isExport) {
            try {
                val file = File(filesDir, "export_lyrics.json")
                if (file.exists()) {
                    val json = JSONObject(file.readText())
                    val map = mutableMapOf<String, LyricsLookupResult>()
                    json.keys().forEach { id ->
                        val obj = json.getJSONObject(id)
                        map[id] = LyricsLookupResult(
                            lyrics = obj.getString("lyrics"),
                            isSynced = obj.getBoolean("isSynced"),
                            isInstrumental = obj.getBoolean("isInstrumental"),
                            trackName = obj.optString("trackName"),
                            artistName = obj.optString("artistName"),
                            albumName = obj.optString("albumName"),
                            duration = obj.optInt("duration")
                        )
                    }
                    file.delete() // Clean up after reading
                    map
                } else null
            } catch (e: Exception) {
                Log.e("LyricsDownloadService", "Failed to load exported lyrics", e)
                null
            }
        } else null

        // Try to use cached tracks first to avoid redundant scanning
        var tracks = repository.loadTracks(rootUri)
        
        if (tracks.isEmpty()) {
            val initialTitle = if (isExport) "Preparing export" else "Scanning music folder"
            notificationManager.notify(notificationId(), buildNotification(initialTitle, 0, 0, true))
            
            tracks = repository.scan(rootUri) { current, total ->
                scope.launch { _progressFlow.emit(DownloadProgress.Scanning(current, total)) }
                notificationManager.notify(
                    notificationId(), 
                    buildNotification("Scanning music files", current, total, false)
                )
            }
        }

        if (tracks.isEmpty()) {
            notificationManager.notify(notificationId(), buildNotification("No supported music files found", 0, 0, false))
            return
        }
        
        val tracksToProcess = tracks.filter { track ->
            if (isExport) {
                // For export, ONLY process tracks that were successfully found in this session
                return@filter exportedLyrics?.containsKey(track.id) == true
            }

            // Skip tracks that already have a result if we are just finding online
            if (track.status == LyricsStatus.Found || track.status == LyricsStatus.Saved) {
                return@filter false
            }
            
            when (downloadMode) {
                DownloadMode.All -> true
                DownloadMode.MissingSynced -> !track.hasSyncedLyrics
                DownloadMode.MissingAny -> !track.hasLyrics
            }
        }

        var saved = 0
        var missing = 0
        var skipped = 0
        var failed = 0
        var currentTrackIndex = -1

        try {
            tracksToProcess.forEachIndexed { index, track ->
                currentTrackIndex = index
                val trackAction = if (isExport) "Saving" else "Finding"
                notificationManager.notify(
                    notificationId(),
                    buildNotification("$trackAction ${track.title}", index + 1, tracksToProcess.size, false)
                )

                // Emit "Downloading" status while searching/saving
                _progressFlow.emit(
                    DownloadProgress.Processing(
                        track.id,
                        index + 1,
                        tracksToProcess.size,
                        track.title,
                        LyricsStatus.Downloading,
                        if (isExport) "Saving..." else "Searching...",
                        isExport,
                        null
                    )
                )

                val lyrics = if (isExport) {
                    exportedLyrics?.get(track.id)
                } else {
                    repository.getLyricsResultForTrack(track)
                }
                
                val status: LyricsStatus
                val message: String

                if (isExport) {
                    if (lyrics != null) {
                        val result = repository.saveManualLyrics(track, lyrics, true, outputModes)
                        status = result.status
                        message = result.message
                    } else {
                        status = LyricsStatus.Missing
                        message = "No lyrics fetched"
                    }
                } else {
                    if (lyrics != null) {
                        status = LyricsStatus.Found
                        message = when {
                            lyrics.isInstrumental -> "Instrumental found"
                            lyrics.isSynced -> "Synced found"
                            else -> "Plain found"
                        }
                    } else {
                        status = LyricsStatus.Missing
                        message = "Not found"
                    }
                }

                _progressFlow.emit(DownloadProgress.Processing(track.id, index + 1, tracksToProcess.size, track.title, status, message, isExport, lyrics))

                when (status) {
                    LyricsStatus.Saved, LyricsStatus.Found -> saved += 1
                    LyricsStatus.Missing -> missing += 1
                    LyricsStatus.Skipped -> skipped += 1
                    LyricsStatus.Failed -> failed += 1
                    else -> Unit
                }

                if (index < (tracksToProcess.size - 1)) {
                    kotlinx.coroutines.delay(searchDelay * 1000L)
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                if (currentTrackIndex != -1 && currentTrackIndex < tracksToProcess.size) {
                    val track = tracksToProcess[currentTrackIndex]
                    _progressFlow.emit(
                        DownloadProgress.Processing(
                            track.id,
                            currentTrackIndex + 1,
                            tracksToProcess.size,
                            track.title,
                            LyricsStatus.Ready,
                            "Search stopped",
                            isExport,
                            null
                        )
                    )
                }
            }
            throw e
        }

        val totalToProcess = tracksToProcess.size
        val summary = if (isExport) {
            "Saved $saved out of $totalToProcess lyrics"
        } else {
            "Found $saved out of $totalToProcess lyrics, ready to export"
        }
        
        _progressFlow.emit(DownloadProgress.Done(saved, missing, skipped, failed, isExport))
        notificationManager.notify(notificationId(), buildNotification(summary, totalToProcess, totalToProcess, false))
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String, progress: Int, max: Int, indeterminate: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("LRCGET")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(progress < max || max == 0 || indeterminate)
            .setOnlyAlertOnce(true)
            .setProgress(max, progress, indeterminate || (max == 0 && progress == 0))
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Lyrics downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows lyric download progress while LRCGET runs in the background"
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private val _progressFlow = MutableSharedFlow<DownloadProgress>(extraBufferCapacity = 10)
        val progressFlow = _progressFlow.asSharedFlow()

        private const val CHANNEL_ID = "lyrics_downloads"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_ROOT_URI = "root_uri"
        private const val EXTRA_DOWNLOAD_MODE = "download_mode"
        private const val EXTRA_IS_EXPORT = "is_export"
        private const val EXTRA_OUTPUT_MODES = "output_modes"
        private const val EXTRA_SEARCH_DELAY = "search_delay"

        private fun notificationId() = NOTIFICATION_ID

        fun start(
            context: Context,
            rootUri: Uri,
            downloadMode: DownloadMode,
            outputModes: Set<LyricsOutputMode>,
            searchDelay: Int,
            isExport: Boolean = false
        ) {
            val intent = Intent(context, LyricsDownloadService::class.java)
                .putExtra(EXTRA_ROOT_URI, rootUri.toString())
                .putExtra(EXTRA_DOWNLOAD_MODE, downloadMode.name)
                .putExtra(EXTRA_IS_EXPORT, isExport)
                .putExtra(EXTRA_OUTPUT_MODES, outputModes.map { it.name }.toTypedArray())
                .putExtra(EXTRA_SEARCH_DELAY, searchDelay)

            context.startForegroundService(intent)
        }
    }
}
