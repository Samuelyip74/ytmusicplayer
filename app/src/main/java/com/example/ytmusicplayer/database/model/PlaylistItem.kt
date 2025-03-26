package com.example.ytmusicplayer.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "playlist_items",
    foreignKeys = [ForeignKey(
        entity = Playlist::class,
        parentColumns = ["id"],
        childColumns = ["playlistId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class PlaylistItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,

    val playlistId: Int,
    val videoId: String,
    val title: String,
    val channelName: String,
    val thumbnailUrl: String,
    val downloadedFilePath: String? = null,

    @ColumnInfo(name = "position")
    var position: Int = 0 // ✅ Added for reordering
)

