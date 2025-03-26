package com.example.ytmusicplayer

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

object PlaylistPlayerManager {
    private var exoPlayer: ExoPlayer? = null
    private var currentIndex = 0

    var onPlaylistEnded: (() -> Unit)? = null // ✅ Auto-play next callback

    fun initialize(context: Context) {
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(context).build()
            exoPlayer?.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        onPlaylistEnded?.invoke()
                    }
                }
            })
        }
    }

    fun release() {
        exoPlayer?.release()
        exoPlayer = null
    }

    fun playPlaylist(context: Context, files: List<File>, startIndex: Int = 0) {
        if (files.isEmpty()) return

        initialize(context)

        val mediaItems = files.mapIndexed { index, file ->
            MediaItem.Builder()
                .setUri(file.toUri())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(file.nameWithoutExtension.replace("_", " "))
                        .build()
                )
                .build()
        }

        exoPlayer?.setMediaItems(mediaItems, startIndex, C.TIME_UNSET)
        exoPlayer?.prepare()
        exoPlayer?.play()
    }

    fun getPlayer(): ExoPlayer? = exoPlayer

    fun addListener(listener: Player.Listener) {
        exoPlayer?.addListener(listener)
    }

    fun removeListener(listener: Player.Listener) {
        exoPlayer?.removeListener(listener)
    }
}
