package com.example.ytmusicplayer.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.ytmusicplayer.PlaylistPlayerManager
import com.example.ytmusicplayer.database.PlaylistDatabase
import com.example.ytmusicplayer.services.MediaPlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val player = PlaylistPlayerManager.getPlayer() ?: return

        when (action) {
            ACTION_PLAY -> player.play()
            ACTION_PAUSE -> player.pause()
            ACTION_NEXT -> {
                if (player.currentMediaItemIndex < player.mediaItemCount - 1) {
                    player.seekToNext()
                    player.play()
                } else {
                    // 🚀 Go to next playlist
                    navigateToNextPlaylist(context)
                }
            }

            ACTION_PREVIOUS -> {
                if (player.hasPreviousMediaItem()) {
                    player.seekToPrevious()
                    player.play()
                } else {
                    // ⬅️ Go to previous playlist
                    navigateToPreviousPlaylist(context)
                }
            }

            ACTION_FAST_FORWARD -> {
                val newPos = player.currentPosition + 10_000L
                player.seekTo(newPos.coerceAtMost(player.duration))
            }
            ACTION_REWIND -> {
                val newPos = player.currentPosition - 10_000L
                player.seekTo(newPos.coerceAtLeast(0))
            }
        }

        val currentItem = player.currentMediaItem
        val title = currentItem?.mediaMetadata?.title?.toString() ?: "Unknown Title"
        showMediaNotification(
            context,
            player.isPlaying,
            title,
            title, // Replace with artist if available
            PlaylistPlayerManager.mediaSessionCompat,
            PlaylistPlayerManager.currentPlaylistId ?: -1
        )
    }

    companion object {
        const val ACTION_PLAY = "com.example.ytmusicplayer.ACTION_PLAY"
        const val ACTION_PAUSE = "com.example.ytmusicplayer.ACTION_PAUSE"
        const val ACTION_NEXT = "com.example.ytmusicplayer.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.example.ytmusicplayer.ACTION_PREVIOUS"
        const val ACTION_FAST_FORWARD = "com.example.ytmusicplayer.ACTION_FAST_FORWARD"
        const val ACTION_REWIND = "com.example.ytmusicplayer.ACTION_REWIND"
    }

    private fun navigateToNextPlaylist(context: Context) {
        val dao = PlaylistDatabase.getDatabase(context).playlistDao()
        CoroutineScope(Dispatchers.IO).launch {
            val playlists = dao.getAllPlaylists().sortedBy { it.id }
            val currentId = PlaylistPlayerManager.currentPlaylistId
            val currentIndex = playlists.indexOfFirst { it.id == currentId }
            val next = playlists.getOrNull(currentIndex + 1)

            if (next != null) {
                val items = dao.getItemsForPlaylist(next.id).sortedBy { it.position }
                val files = items.mapNotNull { it.downloadedFilePath }
                    .map { File(it) }
                    .filter { it.exists() }

                if (files.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        PlaylistPlayerManager.playPlaylist(context, files, next.id)
                    }
                }
            }
        }
    }

    private fun navigateToPreviousPlaylist(context: Context) {
        val dao = PlaylistDatabase.getDatabase(context).playlistDao()
        CoroutineScope(Dispatchers.IO).launch {
            val playlists = dao.getAllPlaylists().sortedBy { it.id }
            val currentId = PlaylistPlayerManager.currentPlaylistId
            val currentIndex = playlists.indexOfFirst { it.id == currentId }
            val previous = playlists.getOrNull(currentIndex - 1)

            if (previous != null) {
                val items = dao.getItemsForPlaylist(previous.id).sortedBy { it.position }
                val files = items.mapNotNull { it.downloadedFilePath }
                    .map { File(it) }
                    .filter { it.exists() }

                if (files.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        PlaylistPlayerManager.playPlaylist(context, files,  previous.id)
                    }
                }
            }
        }
    }

}
