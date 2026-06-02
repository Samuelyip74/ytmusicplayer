package com.example.ytmusicplayer.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.example.ytmusicplayer.DownloaderImpl
import com.example.ytmusicplayer.MainActivity
import com.example.ytmusicplayer.R
import com.example.ytmusicplayer.StreamExtractorHelper
import com.example.ytmusicplayer.database.PlaylistDatabase
import com.example.ytmusicplayer.ui.search.DownloadUiState
import com.example.ytmusicplayer.ui.search.SearchSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class YouTubeDownloadService : Service() {

    companion object {
        private const val CHANNEL_ID = "yt_music_download_channel"
        private const val NOTIFICATION_ID = 2

        private const val EXTRA_ITEM_ID = "item_id"
        private const val EXTRA_VIDEO_URL = "video_url"
        private const val EXTRA_VIDEO_TITLE = "video_title"
        const val EXTRA_VIDEO_ID = "video_id"

        const val ACTION_DOWNLOAD_PROGRESS = "com.example.ytmusicplayer.DOWNLOAD_PROGRESS"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_INDETERMINATE = "indeterminate"
        const val EXTRA_STATUS = "status"

        const val STATUS_STARTED = "started"
        const val STATUS_DOWNLOADING = "downloading"
        const val STATUS_CONVERTING = "converting"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"

        fun createIntent(
            context: Context,
            itemId: Int,
            videoUrl: String,
            videoTitle: String,
            videoId: String
        ): Intent = Intent(context, YouTubeDownloadService::class.java).apply {
            putExtra(EXTRA_ITEM_ID, itemId)
            putExtra(EXTRA_VIDEO_URL, videoUrl)
            putExtra(EXTRA_VIDEO_TITLE, videoTitle)
            putExtra(EXTRA_VIDEO_ID, videoId)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        NewPipe.init(DownloaderImpl())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val itemId = intent?.getIntExtra(EXTRA_ITEM_ID, -1) ?: -1
        val videoUrl = intent?.getStringExtra(EXTRA_VIDEO_URL).orEmpty()
        val videoTitle = intent?.getStringExtra(EXTRA_VIDEO_TITLE).orEmpty().ifBlank { "YouTube audio" }
        val videoId = intent?.getStringExtra(EXTRA_VIDEO_ID).orEmpty()

        if (itemId <= 0 || videoUrl.isBlank() || videoId.isBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildProgressNotification(videoTitle, "Preparing download...", 0, true))
        SearchSessionStore.downloadStates[videoId] = DownloadUiState(visible = true, progress = 0, indeterminate = true)
        broadcastProgress(videoId, STATUS_STARTED, 0, true)

        serviceScope.launch {
            runCatching {
                downloadAndSaveMp3(
                    itemId = itemId,
                    url = videoUrl,
                    title = videoTitle,
                    videoId = videoId
                )
            }.onSuccess {
                showCompletionNotification(videoTitle, "Download complete")
                SearchSessionStore.downloadStates.remove(videoId)
                SearchSessionStore.activeDownloads.remove(videoId)
                broadcastProgress(videoId, STATUS_COMPLETED, 100, false)
            }.onFailure { error ->
                showCompletionNotification(videoTitle, "Download failed: ${error.message ?: "Unknown error"}")
                SearchSessionStore.downloadStates.remove(videoId)
                SearchSessionStore.activeDownloads.remove(videoId)
                broadcastProgress(videoId, STATUS_FAILED, 0, false)
            }

            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    private suspend fun downloadAndSaveMp3(itemId: Int, url: String, title: String, videoId: String): String {
        var tempFile: File? = null

        try {
            updateProgressNotification(title, "Fetching stream info...", 0, true)
            SearchSessionStore.downloadStates[videoId] = DownloadUiState(visible = true, progress = 0, indeterminate = true)
            broadcastProgress(videoId, STATUS_STARTED, 0, true)
            val streamInfo = extractStreamInfoWithRetry(url)

            val audioSource = StreamExtractorHelper.selectAudioSource(streamInfo)
            val safeTitle = sanitizeFileName(streamInfo.name.orEmpty().ifBlank { title })

            tempFile = File(cacheDir, "$safeTitle.${audioSource.suffix}")
            val outputFile = File(filesDir, "$safeTitle.mp3")

            val request = Request.Builder()
                .url(audioSource.url)
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://www.youtube.com/")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Download failed: ${response.code}")
                }

                val total = response.body?.contentLength() ?: -1L
                var downloaded = 0L

                response.body?.byteStream()?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read

                            val percent = if (total > 0) {
                                (downloaded * 100 / total).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }

                            updateProgressNotification(title, "Downloading audio...", percent, total <= 0)
                            SearchSessionStore.downloadStates[videoId] = DownloadUiState(
                                visible = true,
                                progress = percent,
                                indeterminate = total <= 0
                            )
                            broadcastProgress(videoId, STATUS_DOWNLOADING, percent, total <= 0)
                        }
                    }
                } ?: throw IOException("Empty response body")
            }

            updateProgressNotification(title, "Converting to MP3...", 0, true)
            SearchSessionStore.downloadStates[videoId] = DownloadUiState(
                visible = true,
                progress = 100,
                indeterminate = true
            )
            broadcastProgress(videoId, STATUS_CONVERTING, 100, true)

            val ffmpegCommand =
                "-y -i \"${tempFile.absolutePath}\" -vn -ar 44100 -ac 2 -b:a 192k \"${outputFile.absolutePath}\""

            val session = FFmpegKit.execute(ffmpegCommand)
            if (!ReturnCode.isSuccess(session.returnCode)) {
                throw Exception(session.failStackTrace ?: "FFmpeg conversion failed")
            }

            val dao = PlaylistDatabase.getDatabase(applicationContext).playlistDao()
            val existing = dao.getPlaylistItemById(itemId) ?: throw Exception("Playlist item not found")
            dao.updatePlaylistItem(existing.copy(downloadedFilePath = outputFile.absolutePath))

            return outputFile.absolutePath
        } finally {
            tempFile?.delete()
        }
    }

    private suspend fun extractStreamInfoWithRetry(url: String) =
        withContext(Dispatchers.IO) {
            var lastError: Exception? = null

            repeat(2) { attempt ->
                try {
                    return@withContext StreamExtractorHelper.extractStreams(ServiceList.YouTube, url)
                } catch (e: Exception) {
                    lastError = e
                    if (attempt == 0) {
                        Thread.sleep(1200)
                    }
                }
            }

            throw lastError ?: Exception("Failed to extract stream info")
        }

    private fun sanitizeFileName(input: String): String {
        return input
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), "_")
            .take(120)
            .ifBlank { "yt_audio" }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "YT Music Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background audio downloads"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildProgressNotification(
        title: String,
        text: String,
        progress: Int,
        indeterminate: Boolean
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, progress, indeterminate)
            .build()
    }

    private fun updateProgressNotification(
        title: String,
        text: String,
        progress: Int,
        indeterminate: Boolean
    ) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(
            NOTIFICATION_ID,
            buildProgressNotification(title, text, progress, indeterminate)
        )
    }

    private fun broadcastProgress(
        videoId: String,
        status: String,
        progress: Int,
        indeterminate: Boolean
    ) {
        sendBroadcast(
            Intent(ACTION_DOWNLOAD_PROGRESS).apply {
                setPackage(packageName)
                putExtra(EXTRA_VIDEO_ID, videoId)
                putExtra(EXTRA_STATUS, status)
                putExtra(EXTRA_PROGRESS, progress)
                putExtra(EXTRA_INDETERMINATE, indeterminate)
            }
        )
    }

    private fun showCompletionNotification(title: String, text: String) {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
