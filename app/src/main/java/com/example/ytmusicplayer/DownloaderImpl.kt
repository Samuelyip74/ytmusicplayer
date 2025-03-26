package com.example.ytmusicplayer

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.downloader.Request as NewPipeRequest
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException

class DownloaderImpl : Downloader() {

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun execute(request: NewPipeRequest): Response {
        try {
            val builder = Request.Builder()
                .url(request.url())
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")

            val requestBody = request.dataToSend()
            if (requestBody != null && requestBody.isNotEmpty()) {
                builder.post(requestBody.toRequestBody())
            } else {
                builder.get()
            }

            request.headers().forEach { (name, values) ->
                values.forEach { value ->
                    builder.header(name, value)
                }
            }

            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string() ?: ""
                return Response(
                    response.code,
                    response.message,
                    response.headers.toMultimap(),
                    body,
                    request.url()
                )
            }
        } catch (e: IOException) {
            throw ReCaptchaException("Network Error: ${e.message}", request.url())
        }
    }

    companion object {
        // Using the latest Chrome User-Agent ensures YouTube doesn't suspect automated requests.
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
    }
}
