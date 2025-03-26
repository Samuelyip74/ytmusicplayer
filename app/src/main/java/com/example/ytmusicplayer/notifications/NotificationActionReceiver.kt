package com.example.ytmusicplayer.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.ytmusicplayer.PlaylistPlayerManager

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        val player = PlaylistPlayerManager.getPlayer() ?: return

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
            PlaylistPlayerManager.mediaSessionCompat
        )
    }

    companion object {
        const val ACTION_PLAY = "com.example.ytmusicplayer.ACTION_PLAY"
        const val ACTION_PAUSE = "com.example.ytmusicplayer.ACTION_PAUSE"
        const val ACTION_NEXT = "com.example.ytmusicplayer.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.example.ytmusicplayer.ACTION_PREVIOUS"
    }
}
