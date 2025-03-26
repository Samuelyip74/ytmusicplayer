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
    private val listeners = mutableSetOf<Player.Listener>()

    fun initialize(context: Context) {
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(context.applicationContext).build()
            // Re-register all listeners if they exist
            listeners.forEach { exoPlayer?.addListener(it) }
        }
    }

    fun release() {
        exoPlayer?.release()
        exoPlayer = null
    }

    fun getPlayer(): ExoPlayer? = exoPlayer

    fun playPlaylist(context: Context, files: List<File>, startIndex: Int = 0) {
        if (files.isEmpty()) return

        initialize(context)

        val mediaItems = files.map { file ->
            MediaItem.Builder()
                .setUri(file.toUri())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(file.nameWithoutExtension.replace("_", " "))
                        .build()
                )
                .build()
        }

        exoPlayer?.apply {
            clearMediaItems() // optional: clear existing items
            setMediaItems(mediaItems, startIndex, C.TIME_UNSET)
            prepare()
            play()
        }
    }

    // ✅ Add listener support
    fun addListener(listener: Player.Listener) {
        listeners.add(listener)
        exoPlayer?.addListener(listener)
    }

    fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
        exoPlayer?.removeListener(listener)
    }
}