package com.example.ytmusicplayer

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.downloader.Request as NewPipeRequest
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.TimeUnit

class DownloaderImpl : Downloader() {

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun execute(request: NewPipeRequest): Response {
        try {
            val builder = Request.Builder()
                .url(request.url())
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cache-Control", "no-cache")

            // Add headers from NewPipe request
            request.headers().forEach { (name, values) ->
                values.forEach { value ->
                    builder.addHeader(name, value)
                }
            }

            val requestBody = request.dataToSend()
            if (requestBody != null && requestBody.isNotEmpty()) {
                builder.post(requestBody.toRequestBody())
            } else {
                builder.get()
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
            // NewPipe expects ReCaptchaException for network failures to trigger retries/checks
            throw ReCaptchaException("Network Error: ${e.message}", request.url())
        }
    }

    companion object {
        // A modern, consistent User-Agent helps avoid bot detection "Reload" errors
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
    }
}
