package com.example.ytmusicplayer.services

import com.example.ytmusicplayer.database.model.YouTubeVideoItem
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object YouTubeApi {

    private const val BASE_URL = "https://www.googleapis.com/"
    private const val API_KEY = "AIzaSyBvyqtkpZQRA7Zh2AhszqP0SQI1-3sMkl4" // Replace with your API key

    private val apiService: YouTubeApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(YouTubeApiService::class.java)
    }

    suspend fun search(query: String): List<YouTubeVideoItem> {
        return apiService.searchVideos(query = query, apiKey = API_KEY).items
    }
}
