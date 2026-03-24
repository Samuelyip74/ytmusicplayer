package com.example.ytmusicplayer.ui.search

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.example.ytmusicplayer.DownloaderImpl
import com.example.ytmusicplayer.R
import com.example.ytmusicplayer.StreamExtractorHelper
import com.example.ytmusicplayer.database.PlaylistDatabase
import com.example.ytmusicplayer.database.dao.PlaylistDao
import com.example.ytmusicplayer.database.model.Playlist
import com.example.ytmusicplayer.database.model.PlaylistItem
import com.example.ytmusicplayer.database.model.YouTubeVideoItem
import com.example.ytmusicplayer.services.YouTubeApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class SearchFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var searchAdapter: SearchAdapter
    private val searchResults = mutableListOf<YouTubeVideoItem>()
    private lateinit var playlistDao: PlaylistDao

    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private var progressDialog: AlertDialog? = null
    private var progressBar: ProgressBar? = null
    private var progressText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_search, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        playlistDao = PlaylistDatabase.getDatabase(requireContext()).playlistDao()

        recyclerView = view.findViewById(R.id.recyclerView)

        searchAdapter = SearchAdapter(searchResults) {
            Toast.makeText(context, "Clicked: ${it.snippet.title}", Toast.LENGTH_SHORT).show()
        }

        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = searchAdapter

        setupSwipeToAddToPlaylist()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_search, menu)

        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView

        searchView.queryHint = "Search YouTube"
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                query?.let {
                    searchYouTube(it.trim())
                    searchView.clearFocus()
                }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean = false
        })
    }

    private fun searchYouTube(query: String) {
        lifecycleScope.launch {
            try {
                val results = YouTubeApi.search(query)
                searchResults.clear()
                searchResults.addAll(results)
                searchAdapter.notifyDataSetChanged()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Search error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupSwipeToAddToPlaylist() {
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                tgt: RecyclerView.ViewHolder
            ) = false

            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                val video = searchResults[vh.adapterPosition]
                showPlaylistSelection(video)
                searchAdapter.notifyItemChanged(vh.adapterPosition)
            }

            override fun onChildDraw(
                c: Canvas,
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                state: Int,
                isActive: Boolean
            ) {
                super.onChildDraw(c, rv, vh, dX, dY, state, isActive)
            }
        })
        itemTouchHelper.attachToRecyclerView(recyclerView)
    }

    private fun showPlaylistSelection(video: YouTubeVideoItem) {
        lifecycleScope.launch {
            val playlists = playlistDao.getAllPlaylists()
            val names = playlists.map { it.name }.toTypedArray()

            if (playlists.isNotEmpty()) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Add to Playlist")
                    .setItems(names) { _, index ->
                        lifecycleScope.launch {
                            handlePlaylistInsertOrUpdate(video, playlists[index])
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                Toast.makeText(requireContext(), "No playlists found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun handlePlaylistInsertOrUpdate(video: YouTubeVideoItem, playlist: Playlist) {
        val newItem = PlaylistItem(
            playlistId = playlist.id,
            videoId = video.id.videoId,
            title = video.snippet.title,
            channelName = video.snippet.channelTitle,
            thumbnailUrl = video.snippet.thumbnails.medium.url
        )

        val resultId = playlistDao.insertPlaylistItem(newItem)

        val targetItem = if (resultId == -1L) {
            playlistDao.getPlaylistItem(playlist.id, video.id.videoId)
        } else {
            newItem.copy(id = resultId.toInt())
        }

        targetItem?.let { item ->
            downloadAndSaveMp3(requireContext(), "https://www.youtube.com/watch?v=${video.id.videoId}") { filePath ->
                updatePlaylistItemFilePath(requireContext(), item, filePath)
            }
        }
    }

    private fun updatePlaylistItemFilePath(context: Context, item: PlaylistItem, path: String) {
        val updated = item.copy(downloadedFilePath = path)
        CoroutineScope(Dispatchers.IO).launch {
            PlaylistDatabase.getDatabase(context).playlistDao().updatePlaylistItem(updated)
        }
    }

    private fun showProgressDialog() {
        if (progressDialog?.isShowing == true) return
        val view = layoutInflater.inflate(R.layout.dialog_progress, null)
        progressBar = view.findViewById(R.id.progressBar)
        progressText = view.findViewById(R.id.progressText)
        progressDialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .setCancelable(false)
            .create()
        progressDialog?.show()
    }

    private fun updateProgress(percent: Int) {
        progressBar?.progress = percent
        progressText?.text = "Downloading... $percent%"
    }

    private fun updateProgressText(text: String) {
        progressText?.text = text
    }

    private fun hideProgressDialog() {
        progressDialog?.dismiss()
        progressDialog = null
    }

    private suspend fun extractStreamInfoWithRetry(url: String) =
        withContext(Dispatchers.IO) {
            NewPipe.init(DownloaderImpl())

            var lastError: Exception? = null

            repeat(2) { attempt ->
                try {
                    return@withContext StreamExtractorHelper.extractStreams(ServiceList.YouTube, url)
                } catch (e: Exception) {
                    lastError = e
                    if (attempt == 0) {
                        delay(1200)
                    }
                }
            }

            throw lastError ?: Exception("Failed to extract stream info")
        }

    private fun sanitizeFileName(input: String): String {
        return input
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), "_")
            .take(120)
            .ifBlank { "yt_audio" }
    }

    private fun downloadAndSaveMp3(context: Context, url: String, onComplete: (String) -> Unit) {
        showProgressDialog()

        CoroutineScope(Dispatchers.IO).launch {
            var tempFile: File? = null

            try {
                withContext(Dispatchers.Main) {
                    updateProgressText("Fetching stream info...")
                }

                val streamInfo = extractStreamInfoWithRetry(url)

                val audioStream = streamInfo.audioStreams
                    .filter { stream ->
                        val mime = stream.format?.mimeType.orEmpty().lowercase()
                        mime.contains("audio") || mime.contains("mp4") || mime.contains("webm")
                    }
                    .maxByOrNull { it.averageBitrate }

                    ?: throw Exception("No suitable audio stream found")

                val audioUrl = audioStream.content ?: throw Exception("Audio stream URL is missing")
                val suffix = audioStream.format?.getSuffix() ?: "m4a"
                val safeTitle = sanitizeFileName(streamInfo.name)

                tempFile = File(context.cacheDir, "$safeTitle.$suffix")
                val outputFile = File(context.filesDir, "$safeTitle.mp3")

                val request = Request.Builder()
                    .url(audioUrl)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Referer", "https://www.youtube.com/")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Download failed: ${response.code}")
                    }

                    val total = response.body?.contentLength() ?: -1
                    var downloaded = 0L

                    response.body?.byteStream()?.use { input ->
                        FileOutputStream(tempFile!!).use { output ->
                            val buffer = ByteArray(8 * 1024)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                downloaded += read

                                val percent = if (total > 0) {
                                    (downloaded * 100 / total).toInt()
                                } else {
                                    0
                                }

                                withContext(Dispatchers.Main) {
                                    updateProgress(percent)
                                }
                            }
                        }
                    } ?: throw IOException("Empty response body")
                }

                withContext(Dispatchers.Main) {
                    updateProgressText("Converting to MP3...")
                }

                val ffmpegCommand =
                    "-y -i \"${tempFile!!.absolutePath}\" -vn -ar 44100 -ac 2 -b:a 192k \"${outputFile.absolutePath}\""

                val session: FFmpegSession = FFmpegKit.execute(ffmpegCommand)

                withContext(Dispatchers.Main) {
                    hideProgressDialog()
                    if (ReturnCode.isSuccess(session.returnCode)) {
                        Toast.makeText(context, "MP3 saved to app directory", Toast.LENGTH_SHORT).show()
                        onComplete(outputFile.absolutePath)
                    } else {
                        val failStack = session.failStackTrace ?: "Unknown FFmpeg error"
                        Toast.makeText(
                            context,
                            "FFmpeg conversion failed: $failStack",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                tempFile?.delete()
            } catch (e: Exception) {
                tempFile?.delete()

                withContext(Dispatchers.Main) {
                    hideProgressDialog()
                    Toast.makeText(
                        context,
                        "Download error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                e.printStackTrace()
            }
        }
    }
}