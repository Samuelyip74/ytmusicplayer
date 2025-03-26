package com.example.ytmusicplayer

import android.content.Context
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import com.example.ytmusicplayer.database.PlaylistDatabase
import com.example.ytmusicplayer.notifications.showMediaNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object PlaylistPlayerManager {
    private var exoPlayer: ExoPlayer? = null
    private val listeners = mutableListOf<androidx.media3.common.Player.Listener>()

    private var _mediaSessionCompat: MediaSessionCompat? = null
    val mediaSessionCompat: MediaSessionCompat
        get() = _mediaSessionCompat
            ?: throw IllegalStateException("MediaSessionCompat not initialized. Call initialize(context) first.")

    var onPlaylistEnded: (() -> Unit)? = null

    fun initialize(context: Context) {
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(context.applicationContext).build()

            exoPlayer?.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == ExoPlayer.STATE_ENDED) {
                        onPlaylistEnded?.invoke()
                    }
                }
            })

            listeners.forEach { exoPlayer?.addListener(it) }

            if (_mediaSessionCompat == null) {
                _mediaSessionCompat = MediaSessionCompat(context, "YTMusicMediaSession").apply {
                    isActive = true
                }
            }
        }
    }

    fun addListener(listener: androidx.media3.common.Player.Listener) {
        listeners.add(listener)
        exoPlayer?.addListener(listener)
    }

    fun removeListener(listener: androidx.media3.common.Player.Listener) {
        listeners.remove(listener)
        exoPlayer?.removeListener(listener)
    }

    fun getPlayer(): ExoPlayer? = exoPlayer

    fun release() {
        exoPlayer?.release()
        exoPlayer = null
        _mediaSessionCompat?.release()
        _mediaSessionCompat = null
    }

    fun playPlaylist(context: Context, files: List<File>, startIndex: Int = 0) {
        if (files.isEmpty()) return

        initialize(context)

        CoroutineScope(Dispatchers.IO).launch {
            val dao = PlaylistDatabase.getDatabase(context.applicationContext).playlistDao()
            val allItems = dao.getAllPlaylistItems()

            val mediaItems = files.mapNotNull { file ->
                val matched = allItems.find { it.downloadedFilePath == file.absolutePath }
                matched?.let {
                    MediaItem.Builder()
                        .setUri(file.toUri())
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(it.title)
                                .build()
                        )
                        .build()
                }
            }

            withContext(Dispatchers.Main) {
                val player = exoPlayer ?: return@withContext

                // 🔄 Add a one-time listener for metadata change
                player.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                        val title = mediaMetadata.title?.toString() ?: "Unknown Title"
                        val artist = mediaMetadata.artist?.toString() ?: "Unknown Artist"
                        showMediaNotification(
                            context,
                            player.isPlaying,
                            title,
                            artist,
                            mediaSessionCompat
                        )
                        player.removeListener(this) // ✅ Remove after first update
                    }
                })

                player.setMediaItems(mediaItems, startIndex, C.TIME_UNSET)
                player.prepare()
                player.play()
            }
        }
    }
}
