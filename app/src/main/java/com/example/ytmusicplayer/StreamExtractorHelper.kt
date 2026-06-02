package com.example.ytmusicplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeService

object StreamExtractorHelper {
    data class SelectedAudioSource(
        val url: String,
        val suffix: String
    )

    suspend fun extractStreams(service: YoutubeService, url: String): StreamInfo =
        withContext(Dispatchers.IO) {
            StreamInfo.getInfo(service, url)
        }

    fun selectAudioSource(streamInfo: StreamInfo): SelectedAudioSource {
        val audioStream = streamInfo.audioStreams
            .filter { stream ->
                val mime = stream.format?.mimeType.orEmpty().lowercase()
                mime.contains("audio") || mime.contains("mp4") || mime.contains("webm")
            }
            .filter { stream -> !stream.content.isNullOrBlank() }
            .maxByOrNull { it.averageBitrate }

        if (audioStream != null) {
            return SelectedAudioSource(
                url = audioStream.content,
                suffix = audioStream.format?.getSuffix() ?: "m4a"
            )
        }

        val muxedStream = streamInfo.videoStreams
            .filter { stream ->
                val mime = stream.format?.mimeType.orEmpty().lowercase()
                mime.contains("video") || mime.contains("mp4") || mime.contains("webm")
            }
            .firstOrNull { stream -> !stream.content.isNullOrBlank() }

        if (muxedStream != null) {
            return SelectedAudioSource(
                url = muxedStream.content,
                suffix = muxedStream.format?.getSuffix() ?: "mp4"
            )
        }

        throw Exception("No suitable audio stream found")
    }
}
