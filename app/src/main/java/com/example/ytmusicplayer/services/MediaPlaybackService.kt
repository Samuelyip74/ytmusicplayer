package com.example.ytmusicplayer.services

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.example.ytmusicplayer.PlaylistPlayerManager
import com.example.ytmusicplayer.notifications.showMediaNotification

class MediaPlaybackService : Service() {

    override fun onCreate() {
        super.onCreate()

        // ✅ This is crucial to set up MediaSession and ExoPlayer
        PlaylistPlayerManager.initialize(this)

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        PlaylistPlayerManager.initialize(this)

        // Show persistent notification
        val player = PlaylistPlayerManager.getPlayer()
        val currentItem = player?.currentMediaItem
        val title = currentItem?.mediaMetadata?.title?.toString() ?: "Unknown Title"
        val artist = currentItem?.mediaMetadata?.artist?.toString() ?: "Unknown Artist"

        val notification = showMediaNotification(
            this, // 👈 Pass service context
            player?.isPlaying == true,
            title,
            artist,
            PlaylistPlayerManager.mediaSessionCompat
        )

        // ✅ Required to keep service alive in background
        startForeground(1, notification)

        return START_STICKY
    }


    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        //Log.d("MediaPlaybackService", "Task removed - service still alive?")
    }
}
