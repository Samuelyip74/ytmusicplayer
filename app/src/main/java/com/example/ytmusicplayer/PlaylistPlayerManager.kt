package com.example.ytmusicplayer

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    var currentPlaylistId: Int? = null
        private set

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
                            val player = exoPlayer ?: return
                            if (player.currentMediaItemIndex < player.mediaItemCount - 1) {
                                player.seekToNext()
                                player.play()
                            } else {
                                currentPlaylistId?.let { autoPlayNextPlaylist(context, it) }
                            }
                        }

                        override fun onSkipToPrevious() {
                            val player = exoPlayer ?: return
                            if (player.hasPreviousMediaItem()) {
                                player.seekToPrevious()
                                player.play()
                            } else {
                                currentPlaylistId?.let { autoPlayPreviousPlaylist(context, it) }
                            }
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
        abandonAudioFocus()
    }

    fun playPlaylist(context: Context, files: List<File>, playlistId: Int? = 0, startIndex: Int = 0) {
        if (files.isEmpty()) return

        currentPlaylistId = playlistId
        initialize(context)
        initializeAudioFocus(context)

        val hasFocus = requestAudioFocus()
        if (!hasFocus) return

//        val startIntent = Intent(context, MediaPlaybackService::class.java)
//        ContextCompat.startForegroundService(context, startIntent)

        CoroutineScope(Dispatchers.IO).launch {
            val dao = PlaylistDatabase.getDatabase(context.applicationContext).playlistDao()
            val allItems = dao.getAllPlaylistItems()

            val mediaItems = files.mapNotNull { file ->
                val matched = allItems.find { it.downloadedFilePath == file.absolutePath }
                matched?.let {
                    MediaItem.Builder()
                        .setUri(file.toUri())
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(it.title).build())
                        .build()
                }
            }

            withContext(Dispatchers.Main) {
                val player = exoPlayer ?: return@withContext
                val safeIndex = startIndex.coerceIn(0, mediaItems.lastIndex)
                player.setMediaItems(mediaItems, safeIndex, C.TIME_UNSET)
                player.prepare()
                player.play()
                updateNotification(context) // Optional fallback
            }
        }
    }

    private fun initializeAudioFocus(context: Context) {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_GAIN -> exoPlayer?.playWhenReady = true
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> exoPlayer?.volume = 0.2f
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> exoPlayer?.pause()
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        exoPlayer?.pause()
                        abandonAudioFocus()
                    }
                }
            }
            .build()
        audioFocusRequest = focusRequest
    }

    private fun requestAudioFocus(): Boolean {
        return audioFocusRequest?.let {
            audioManager?.requestAudioFocus(it) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } ?: false
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let {
            audioManager?.abandonAudioFocusRequest(it)
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
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_FAST_FORWARD or    // <-- add this
                PlaybackStateCompat.ACTION_REWIND             // <-- and this
            )
            .setState(state, exoPlayer?.currentPosition ?: 0L, 1f)
            .build()
        _mediaSessionCompat?.setPlaybackState(playbackState)
    }

    private fun autoPlayNextPlaylist(context: Context, currentId: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            val dao = PlaylistDatabase.getDatabase(context).playlistDao()
            val allPlaylists = dao.getAllPlaylists().sortedBy { it.id }
            val currentIndex = allPlaylists.indexOfFirst { it.id == currentId }
            val next = allPlaylists.getOrNull(currentIndex + 1)

            next?.let { playlist ->
                val items = dao.getItemsForPlaylist(playlist.id).sortedBy { it.position }
                val files = items.mapNotNull { it.downloadedFilePath }.map { File(it) }.filter { it.exists() }
                withContext(Dispatchers.Main) {
                    playPlaylist(context, files, playlist.id)
                }
            }
        }
    }

    private fun autoPlayPreviousPlaylist(context: Context, currentId: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            val dao = PlaylistDatabase.getDatabase(context).playlistDao()
            val allPlaylists = dao.getAllPlaylists().sortedBy { it.id }
            val currentIndex = allPlaylists.indexOfFirst { it.id == currentId }
            val previous = allPlaylists.getOrNull(currentIndex - 1)

            previous?.let { playlist ->
                val items = dao.getItemsForPlaylist(playlist.id).sortedBy { it.position }
                val files = items.mapNotNull { it.downloadedFilePath }.map { File(it) }.filter { it.exists() }
                withContext(Dispatchers.Main) {
                    playPlaylist(context, files, playlist.id)
                }
            }
        }
    }
}
