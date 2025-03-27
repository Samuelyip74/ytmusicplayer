package com.example.ytmusicplayer

import android.content.Context
import android.content.Intent
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import com.example.ytmusicplayer.database.PlaylistDatabase
import com.example.ytmusicplayer.notifications.showMediaNotification
import com.example.ytmusicplayer.services.MediaPlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object PlaylistPlayerManager {
    private var exoPlayer: ExoPlayer? = null
    private var currentPlaylistId: Int? = null
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

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateMediaSessionPlaybackState(isPlaying)
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    updateNotification(context)
                    val title = mediaItem?.mediaMetadata?.title ?: "Unknown Title"

                    val metadataCompat = android.support.v4.media.MediaMetadataCompat.Builder()
                        .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE, title.toString())
                        .build()


                    _mediaSessionCompat?.setMetadata(metadataCompat)
                }
            })

            listeners.forEach { exoPlayer?.addListener(it) }

            if (_mediaSessionCompat == null) {
                _mediaSessionCompat = MediaSessionCompat(context, "YTMusicMediaSession").apply {
                    setCallback(object : MediaSessionCompat.Callback() {
                        override fun onPlay() {
                            exoPlayer?.play()
                        }

                        override fun onPause() {
                            exoPlayer?.pause()
                        }

                        override fun onSkipToNext() {
                            exoPlayer?.seekToNext()
                            exoPlayer?.play()
                        }

                        override fun onSkipToPrevious() {
                            exoPlayer?.seekToPrevious()
                            exoPlayer?.play()
                        }
                    })
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

    fun getCurrentPlaylistId(): Int? = currentPlaylistId

    fun playPlaylist(context: Context, files: List<File>, playlistId:Int ?= 0, startIndex: Int = 0) {
        if (files.isEmpty()) return

        currentPlaylistId = playlistId  // 🔐 Save it here

        initialize(context)

        val startIntent = Intent(context, MediaPlaybackService::class.java).apply {
            putExtra("playlistId", playlistId)
            // putExtra("playlistName", playlistName)
        }

        ContextCompat.startForegroundService(context, startIntent)

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
                Log.d("PlaylistID - PLManager","Playlist ID is $playlistId")
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
                            mediaSessionCompat,
                            playlistId
                        )
                        player.removeListener(this) // ✅ Remove after first update
                    }
                })

                player.setMediaItems(mediaItems, startIndex, C.TIME_UNSET)
                player.prepare()

                // Start background service before playing
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, MediaPlaybackService::class.java)
                )


                player.play()
            }
        }
    }


    private fun updateNotification(context: Context) {
        val player = exoPlayer ?: return
        val currentItem = player.currentMediaItem?.mediaMetadata

        val title = currentItem?.title?.toString() ?: "Unknown Title"
        val artist = currentItem?.artist?.toString() ?: "Unknown Artist"

        showMediaNotification(
            context,
            player.isPlaying,
            title,
            artist,
            mediaSessionCompat,
            currentPlaylistId
        )
    }



    private fun updateMediaSessionPlaybackState(isPlaying: Boolean) {
        val state = if (isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(state, exoPlayer?.currentPosition ?: 0L, 1f)
            .build()

        _mediaSessionCompat?.setPlaybackState(playbackState)
    }

}
