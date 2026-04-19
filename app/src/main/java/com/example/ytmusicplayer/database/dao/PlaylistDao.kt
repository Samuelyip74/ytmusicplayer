package com.example.ytmusicplayer.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.ytmusicplayer.database.model.Playlist
import com.example.ytmusicplayer.database.model.PlaylistItem

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists")
    suspend fun getAllPlaylists(): List<Playlist>

    @Query("SELECT * FROM playlists WHERE id = :playlistId LIMIT 1")
    suspend fun getPlaylistById(playlistId: Int): Playlist?

    @Update
    suspend fun updatePlaylistItem(item: PlaylistItem)

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId AND videoId = :videoId LIMIT 1")
    suspend fun getPlaylistItem(playlistId: Int, videoId: String): PlaylistItem?

    @Query("SELECT * FROM playlist_items WHERE id = :itemId LIMIT 1")
    suspend fun getPlaylistItemById(itemId: Int): PlaylistItem?


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: Playlist): Long

    @Update
    suspend fun updatePlaylist(playlist: Playlist)

    @Delete
    suspend fun deletePlaylist(playlist: Playlist)

    @Query("SELECT * FROM playlist_items")
    suspend fun getAllPlaylistItems(): List<PlaylistItem>


    // Playlist Items operations
    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getItemsForPlaylist(playlistId: Int): List<PlaylistItem>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun getNextPlaylistItemPosition(playlistId: Int): Int

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun getItemsForPlaylists(playlistId: Int): List<PlaylistItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistItem(item: PlaylistItem): Long

    @Delete
    suspend fun deletePlaylistItem(item: PlaylistItem)
}
