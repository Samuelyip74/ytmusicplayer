package com.example.ytmusicplayer.services

import com.example.ytmusicplayer.database.model.YouTubeSearchResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface YouTubeApiService {

    @GET("youtube/v3/search")
    suspend fun searchVideos(
        @Query("part") part: String = "snippet",
        @Query("q") query: String,
        @Query("type") type: String = "video",
        @Query("key") apiKey: String,
        @Query("maxResults") maxResults: Int = 20
    ): YouTubeSearchResponse
}
