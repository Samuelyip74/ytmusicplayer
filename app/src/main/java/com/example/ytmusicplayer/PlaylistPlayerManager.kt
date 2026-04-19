package com.example.ytmusicplayer

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.ytmusicplayer.database.PlaylistDatabase
import com.example.ytmusicplayer.notifications.showMediaNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object PlaylistPlayerManager {
    private const val PREFS_NAME = "playback_session"
    private const val KEY_PLAYLIST_ID = "playlist_id"
    private const val KEY_PLAYLIST_NAME = "playlist_name"
    private const val KEY_PLAYLIST_ITEM_ID = "playlist_item_id"
    private const val KEY_MEDIA_INDEX = "media_index"
    private const val KEY_POSITION_MS = "position_ms"
    private const val KEY_WAS_PLAYING = "was_playing"

    data class SavedPlaybackSession(
        val playlistId: Int,
        val playlistName: String,
        val playlistItemId: Int?,
        val mediaIndex: Int,
        val positionMs: Long,
        val wasPlaying: Boolean
    )

    private var exoPlayer: ExoPlayer? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var appContext: Context? = null
    private var hasAttemptedRestore = false

    var currentPlaylistId: Int? = null
        private set

    var currentPlaylistName: String? = null
        private set

    private val listeners = mutableListOf<Player.Listener>()

    private var _mediaSessionCompat: MediaSessionCompat? = null
    val mediaSessionCompat: MediaSessionCompat
        get() = _mediaSessionCompat
            ?: throw IllegalStateException("MediaSessionCompat not initialized. Call initialize(context) first.")

    var onPlaylistEnded: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val positionSaver = object : Runnable {
        override fun run() {
            persistCurrentPlaybackState()
            if (exoPlayer?.isPlaying == true) {
                mainHandler.postDelayed(this, 1_000L)
            }
        }
    }

    private val internalListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == ExoPlayer.STATE_ENDED) {
                onPlaylistEnded?.invoke()
            }
            persistCurrentPlaybackState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateMediaSessionPlaybackState(isPlaying)
            updatePositionTracking()
            persistCurrentPlaybackState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            appContext?.let(::updateNotification)

            val title = mediaItem?.mediaMetadata?.title ?: "Unknown Title"
            val metadataCompat = android.support.v4.media.MediaMetadataCompat.Builder()
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE, title.toString())
                .build()

            _mediaSessionCompat?.setMetadata(metadataCompat)
            persistCurrentPlaybackState()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            persistCurrentPlaybackState()
        }
    }

    fun initialize(context: Context) {
        val applicationContext = context.applicationContext
        appContext = applicationContext

        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(applicationContext).build().apply {
                addListener(internalListener)
            }

            listeners.forEach { exoPlayer?.addListener(it) }

            if (_mediaSessionCompat == null) {
                _mediaSessionCompat = MediaSessionCompat(applicationContext, "YTMusicMediaSession").apply {
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
                                currentPlaylistId?.let { autoPlayNextPlaylist(applicationContext, it) }
                            }
                        }

                        override fun onSkipToPrevious() {
                            val player = exoPlayer ?: return
                            if (player.hasPreviousMediaItem()) {
                                player.seekToPrevious()
                                player.play()
                            } else {
                                currentPlaylistId?.let { autoPlayPreviousPlaylist(applicationContext, it) }
                            }
                        }
                    })
                    isActive = true
                }
            }
        }

        if (!hasAttemptedRestore) {
            hasAttemptedRestore = true
            restoreLastPlaybackSession(applicationContext)
        }
    }

    fun addListener(listener: Player.Listener) {
        listeners.add(listener)
        exoPlayer?.addListener(listener)
    }

    fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
        exoPlayer?.removeListener(listener)
    }

    fun getPlayer(): ExoPlayer? = exoPlayer

    fun release() {
        persistCurrentPlaybackState()
        mainHandler.removeCallbacks(positionSaver)
        exoPlayer?.removeListener(internalListener)
        exoPlayer?.release()
        exoPlayer = null
        _mediaSessionCompat?.release()
        _mediaSessionCompat = null
        abandonAudioFocus()
    }

    fun playPlaylist(
        context: Context,
        files: List<File>,
        playlistId: Int? = 0,
        startIndex: Int = 0,
        startPositionMs: Long = 0L,
        playWhenReady: Boolean = true
    ) {
        if (files.isEmpty()) return

        currentPlaylistId = playlistId
        initialize(context)
        initializeAudioFocus(context)

        val hasFocus = if (playWhenReady) requestAudioFocus() else true
        if (!hasFocus) return

        CoroutineScope(Dispatchers.IO).launch {
            val dao = PlaylistDatabase.getDatabase(context.applicationContext).playlistDao()
            val allItems = dao.getAllPlaylistItems()
            val playlistName = playlistId?.let { dao.getPlaylistById(it)?.name }.orEmpty()

            val mediaItems = files.mapNotNull { file ->
                val matched = allItems.find { it.downloadedFilePath == file.absolutePath }
                matched?.let {
                    MediaItem.Builder()
                        .setMediaId(it.id.toString())
                        .setUri(file.toUri())
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(it.title).build())
                        .build()
                }
            }

            withContext(Dispatchers.Main) {
                val player = exoPlayer ?: return@withContext
                if (mediaItems.isEmpty()) return@withContext
                val safeIndex = startIndex.coerceIn(0, mediaItems.lastIndex)
                currentPlaylistName = playlistName
                player.setMediaItems(mediaItems, safeIndex, startPositionMs)
                player.prepare()
                if (playWhenReady) {
                    player.play()
                } else {
                    player.pause()
                }
                persistCurrentPlaybackState()
                updateNotification(context)
            }
        }
    }

    fun playStream(
        context: Context,
        streamUrl: String,
        title: String,
        artist: String? = null
    ) {
        currentPlaylistId = null
        currentPlaylistName = null
        clearSavedPlaybackSession(context)
        initialize(context)
        initializeAudioFocus(context)

        val hasFocus = requestAudioFocus()
        if (!hasFocus) return

        val mediaItem = MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .build()
            )
            .build()

        val player = exoPlayer ?: return
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
        updateNotification(context)
    }

    fun getSavedPlaybackSession(context: Context): SavedPlaybackSession? {
        val prefs = getPrefs(context.applicationContext)
        val playlistId = prefs.getInt(KEY_PLAYLIST_ID, -1)
        if (playlistId == -1) return null

        val rawItemId = prefs.getInt(KEY_PLAYLIST_ITEM_ID, -1)
        return SavedPlaybackSession(
            playlistId = playlistId,
            playlistName = prefs.getString(KEY_PLAYLIST_NAME, "").orEmpty(),
            playlistItemId = rawItemId.takeIf { it != -1 },
            mediaIndex = prefs.getInt(KEY_MEDIA_INDEX, 0),
            positionMs = prefs.getLong(KEY_POSITION_MS, 0L),
            wasPlaying = prefs.getBoolean(KEY_WAS_PLAYING, false)
        )
    }

    private fun restoreLastPlaybackSession(context: Context) {
        if (exoPlayer?.mediaItemCount ?: 0 > 0) return

        val savedSession = getSavedPlaybackSession(context) ?: return

        CoroutineScope(Dispatchers.IO).launch {
            val dao = PlaylistDatabase.getDatabase(context).playlistDao()
            val playlist = dao.getPlaylistById(savedSession.playlistId) ?: run {
                clearSavedPlaybackSession(context)
                return@launch
            }

            val playableItems = dao.getItemsForPlaylist(savedSession.playlistId)
                .sortedBy { it.position }
                .mapNotNull { item ->
                    item.downloadedFilePath
                        ?.let(::File)
                        ?.takeIf { it.exists() }
                        ?.let { file -> item to file }
                }

            if (playableItems.isEmpty()) {
                clearSavedPlaybackSession(context)
                return@launch
            }

            val savedIndex = savedSession.playlistItemId?.let { itemId ->
                playableItems.indexOfFirst { (item, _) -> item.id == itemId }
            } ?: -1
            val startIndex = if (savedIndex >= 0) savedIndex else savedSession.mediaIndex
            val files = playableItems.map { it.second }

            withContext(Dispatchers.Main) {
                playPlaylist(
                    context = context,
                    files = files,
                    playlistId = playlist.id,
                    startIndex = startIndex,
                    startPositionMs = savedSession.positionMs,
                    playWhenReady = savedSession.wasPlaying
                )
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
                    PlaybackStateCompat.ACTION_FAST_FORWARD or
                    PlaybackStateCompat.ACTION_REWIND
            )
            .setState(state, exoPlayer?.currentPosition ?: 0L, 1f)
            .build()
        _mediaSessionCompat?.setPlaybackState(playbackState)
    }

    private fun updatePositionTracking() {
        mainHandler.removeCallbacks(positionSaver)
        if (exoPlayer?.isPlaying == true) {
            mainHandler.post(positionSaver)
        }
    }

    private fun persistCurrentPlaybackState() {
        val context = appContext ?: return
        val playlistId = currentPlaylistId ?: return
        val player = exoPlayer ?: return
        if (player.mediaItemCount == 0 || player.currentMediaItemIndex == C.INDEX_UNSET) return

        val playlistItemId = player.currentMediaItem?.mediaId?.toIntOrNull()
        getPrefs(context).edit()
            .putInt(KEY_PLAYLIST_ID, playlistId)
            .putString(KEY_PLAYLIST_NAME, currentPlaylistName.orEmpty())
            .putInt(KEY_PLAYLIST_ITEM_ID, playlistItemId ?: -1)
            .putInt(KEY_MEDIA_INDEX, player.currentMediaItemIndex)
            .putLong(KEY_POSITION_MS, player.currentPosition.coerceAtLeast(0L))
            .putBoolean(KEY_WAS_PLAYING, player.isPlaying)
            .apply()
    }

    private fun clearSavedPlaybackSession(context: Context) {
        getPrefs(context.applicationContext).edit().clear().apply()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
