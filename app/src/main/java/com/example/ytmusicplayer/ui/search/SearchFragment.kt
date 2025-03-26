package com.example.ytmusicplayer.ui.search

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.*
import com.arthenica.ffmpegkit.*
import com.example.ytmusicplayer.DownloaderImpl
import com.example.ytmusicplayer.R
import com.example.ytmusicplayer.StreamExtractorHelper
import com.example.ytmusicplayer.database.PlaylistDatabase
import com.example.ytmusicplayer.database.dao.PlaylistDao
import com.example.ytmusicplayer.database.model.Playlist
import com.example.ytmusicplayer.database.model.PlaylistItem
import com.example.ytmusicplayer.database.model.YouTubeVideoItem
import com.example.ytmusicplayer.services.YouTubeApi
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import java.io.*
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
        setHasOptionsMenu(true) // ✅ enable ActionBar menu
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
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

            override fun onQueryTextChange(newText: String?): Boolean {
                return false
            }
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
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, tgt: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                val video = searchResults[vh.adapterPosition]
                showPlaylistSelection(video)
                searchAdapter.notifyItemChanged(vh.adapterPosition)
            }

            override fun onChildDraw(c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder, dX: Float, dY: Float, state: Int, isActive: Boolean) {
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
        progressDialog = AlertDialog.Builder(requireContext()).setView(view).setCancelable(false).create()
        progressDialog?.show()
    }

    private fun updateProgress(percent: Int) {
        progressBar?.progress = percent
        progressText?.text = "Downloading... $percent%"
    }

    private fun hideProgressDialog() {
        progressDialog?.dismiss()
        progressDialog = null
    }

    private fun downloadAndSaveMp3(context: Context, url: String, onComplete: (String) -> Unit) {
        showProgressDialog()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                NewPipe.init(DownloaderImpl())
                val streamInfo = StreamExtractorHelper.extractStreams(ServiceList.YouTube, url)
                val audioStream = streamInfo.videoStreams
                    .filter { it.format?.mimeType?.contains("mp4") ?: false }
                    .maxByOrNull { it.resolution } ?: throw Exception("No suitable stream")

                val audioUrl = audioStream.content
                val suffix = audioStream.format?.getSuffix()
                val title = streamInfo.name.replace("[^a-zA-Z0-9]".toRegex(), "_")
                val tempFile = File(context.cacheDir, "$title.$suffix")
                val outputFile = File(context.filesDir, "$title.mp3")

                val request = Request.Builder()
                    .url(audioUrl!!)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Referer", "https://www.youtube.com/")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Download failed: ${response.code}")
                    val total = response.body?.contentLength() ?: -1
                    var downloaded = 0L

                    response.body?.byteStream()?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(8 * 1024)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                downloaded += read
                                val percent = if (total > 0) (downloaded * 100 / total).toInt() else 0
                                withContext(Dispatchers.Main) { updateProgress(percent) }
                            }
                        }
                    }
                }

                val session: FFmpegSession = FFmpegKit.execute(
                    "-y -i ${tempFile.absolutePath} -vn -ar 44100 -ac 2 -b:a 192k ${outputFile.absolutePath}"
                )

                withContext(Dispatchers.Main) {
                    hideProgressDialog()
                    if (ReturnCode.isSuccess(session.returnCode)) {
                        Toast.makeText(context, "MP3 saved to app directory", Toast.LENGTH_SHORT).show()
                        onComplete(outputFile.absolutePath)
                    } else {
                        Toast.makeText(context, "FFmpeg conversion failed", Toast.LENGTH_SHORT).show()
                    }
                }

                tempFile.delete()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    hideProgressDialog()
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                e.printStackTrace()
            }
        }
    }
}
