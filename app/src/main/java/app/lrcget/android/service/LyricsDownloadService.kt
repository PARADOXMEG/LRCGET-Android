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
import app.lrcget.android.MainActivity
import app.lrcget.android.R
import app.lrcget.android.data.MusicLibraryRepository
import app.lrcget.android.model.DownloadMode
import app.lrcget.android.model.LyricsOutputMode
import app.lrcget.android.model.LyricsStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LyricsDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var notificationManager: NotificationManager
    private var activeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rootUri = intent?.getStringExtra(EXTRA_ROOT_URI)?.let(Uri::parse)
        if (rootUri == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val downloadMode = DownloadMode.valueOf(
            intent.getStringExtra(EXTRA_DOWNLOAD_MODE) ?: DownloadMode.MissingAny.name
        )
        val searchDelay = intent.getIntExtra(EXTRA_SEARCH_DELAY, 2)
        val outputModesStrings = intent.getStringArrayExtra(EXTRA_OUTPUT_MODES) ?: arrayOf(LyricsOutputMode.LrcFile.name)
        val outputModes = outputModesStrings.map { LyricsOutputMode.valueOf(it) }.toSet()

        startForegroundCompat(buildNotification("Preparing lyrics download", 0, 0, true))
        activeJob?.cancel()
        activeJob = scope.launch {
            runDownload(rootUri, downloadMode, outputModes, searchDelay)
            stopSelf(startId)
        }

        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        activeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runDownload(
        rootUri: Uri,
        downloadMode: DownloadMode,
        outputModes: Set<LyricsOutputMode>,
        searchDelay: Int
    ) {
        val repository = MusicLibraryRepository(this)
        notificationManager.notify(notificationId(), buildNotification("Scanning music folder", 0, 0, true))
        val tracks = repository.scan(rootUri)

        if (tracks.isEmpty()) {
            notificationManager.notify(notificationId(), buildNotification("No supported music files found", 0, 0, false))
            return
        }
        
        val tracksToProcess = tracks.filter { track ->
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

        tracksToProcess.forEachIndexed { index, track ->
            notificationManager.notify(
                notificationId(),
                buildNotification("Downloading ${track.title}", index, tracksToProcess.size, false)
            )

            when (repository.download(track, downloadMode == DownloadMode.All, outputModes).status) {
                LyricsStatus.Saved -> saved += 1
                LyricsStatus.Missing -> missing += 1
                LyricsStatus.Skipped -> skipped += 1
                LyricsStatus.Failed -> failed += 1
                else -> Unit
            }

            if (index < (tracksToProcess.size - 1)) {
                kotlinx.coroutines.delay(searchDelay * 1000L)
            }
        }

        val summary = "Saved $saved, missing $missing, skipped $skipped, failed $failed"
        notificationManager.notify(notificationId(), buildNotification(summary, tracksToProcess.size, tracksToProcess.size, false))
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
            .setOngoing(progress < max || indeterminate)
            .setOnlyAlertOnce(true)
            .setProgress(max, progress, indeterminate)
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
        private const val CHANNEL_ID = "lyrics_downloads"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_ROOT_URI = "root_uri"
        private const val EXTRA_DOWNLOAD_MODE = "download_mode"
        private const val EXTRA_OUTPUT_MODES = "output_modes"
        private const val EXTRA_SEARCH_DELAY = "search_delay"

        private fun notificationId() = NOTIFICATION_ID

        fun start(
            context: Context,
            rootUri: Uri,
            downloadMode: DownloadMode,
            outputModes: Set<LyricsOutputMode>,
            searchDelay: Int,
        ) {
            val intent = Intent(context, LyricsDownloadService::class.java)
                .putExtra(EXTRA_ROOT_URI, rootUri.toString())
                .putExtra(EXTRA_DOWNLOAD_MODE, downloadMode.name)
                .putExtra(EXTRA_OUTPUT_MODES, outputModes.map { it.name }.toTypedArray())
                .putExtra(EXTRA_SEARCH_DELAY, searchDelay)

            context.startForegroundService(intent)
        }
    }
}
