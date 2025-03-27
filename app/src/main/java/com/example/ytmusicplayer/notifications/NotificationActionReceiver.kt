package com.example.ytmusicplayer.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.ytmusicplayer.PlaylistPlayerManager
import com.example.ytmusicplayer.services.MediaPlaybackService

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val playlistId = intent?.getIntExtra("playlistId", -1) ?: -1

        val action = intent?.action ?: return

        val player = PlaylistPlayerManager.getPlayer() ?: return

        val serviceIntent = Intent(context, MediaPlaybackService::class.java)
        context.startService(serviceIntent)

        when (action) {
            ACTION_PLAY -> player.play()
            ACTION_PAUSE -> player.pause()
            ACTION_NEXT -> {
                player.seekToNext()
                player.play()
            }
            ACTION_PREVIOUS -> {
                player.seekToPrevious()
                player.play()
            }
        }

        // ✅ Fetch actual current media item
        val currentItem = player?.currentMediaItem
        val title = currentItem?.mediaMetadata?.title?.toString() ?: "Unknown Title"
        // Update notification with current metadata (replace with real data)
        showMediaNotification(
            context,
            player.isPlaying,
            title, // TODO: Replace with current title
            title,     // TODO: Replace with current artist
            PlaylistPlayerManager.mediaSessionCompat,
            playlistId
        )
    }

    companion object {
        const val ACTION_PLAY = "com.example.ytmusicplayer.ACTION_PLAY"
        const val ACTION_PAUSE = "com.example.ytmusicplayer.ACTION_PAUSE"
        const val ACTION_NEXT = "com.example.ytmusicplayer.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.example.ytmusicplayer.ACTION_PREVIOUS"
    }
}
