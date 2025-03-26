package com.example.ytmusicplayer.database.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    var name: String,
    var position: Int = 0 // ✅ add this field
)
