package com.example.ytmusicplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeService

object StreamExtractorHelper {
    suspend fun extractStreams(service: YoutubeService, url: String): StreamInfo =
        withContext(Dispatchers.IO) {
            StreamInfo.getInfo(service, url)
        }
}