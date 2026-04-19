package com.example.ytmusicplayer.ui.search

import com.example.ytmusicplayer.database.model.YouTubeVideoItem

object SearchSessionStore {
    var currentQuery: String = ""
    var results: List<YouTubeVideoItem> = emptyList()
    val activeDownloads: LinkedHashMap<String, YouTubeVideoItem> = linkedMapOf()
    val downloadStates: MutableMap<String, DownloadUiState> = mutableMapOf()
}
