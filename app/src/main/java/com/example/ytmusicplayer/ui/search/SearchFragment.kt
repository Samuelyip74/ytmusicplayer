package com.example.ytmusicplayer.ui.search

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.net.Uri
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
import androidx.fragment.app.activityViewModels
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.ytmusicplayer.DownloaderImpl
import com.example.ytmusicplayer.MainActivity
import com.example.ytmusicplayer.PlaylistPlayerManager
import com.example.ytmusicplayer.R
import com.example.ytmusicplayer.StreamExtractorHelper
import com.example.ytmusicplayer.database.PlaylistDatabase
import com.example.ytmusicplayer.database.dao.PlaylistDao
import com.example.ytmusicplayer.database.model.Playlist
import com.example.ytmusicplayer.database.model.Thumbnail
import com.example.ytmusicplayer.database.model.PlaylistItem
import com.example.ytmusicplayer.database.model.VideoId
import com.example.ytmusicplayer.database.model.VideoSnippet
import com.example.ytmusicplayer.database.model.VideoThumbnails
import com.example.ytmusicplayer.database.model.YouTubeVideoItem
import com.example.ytmusicplayer.services.YouTubeDownloadService
import com.example.ytmusicplayer.services.YouTubeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList

class SearchFragment : Fragment() {

    private val searchViewModel: SearchViewModel by activityViewModels()

    private lateinit var recyclerView: RecyclerView
    private lateinit var searchLoadingContainer: View
    private lateinit var searchLoadingText: TextView
    private lateinit var searchAdapter: SearchAdapter
    private val searchResults = mutableListOf<YouTubeVideoItem>()
    private lateinit var playlistDao: PlaylistDao

    private var progressDialog: AlertDialog? = null
    private var progressBar: ProgressBar? = null
    private var progressText: TextView? = null

    private val downloadProgressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != YouTubeDownloadService.ACTION_DOWNLOAD_PROGRESS) return

            val videoId = intent.getStringExtra(YouTubeDownloadService.EXTRA_VIDEO_ID).orEmpty()
            if (videoId.isBlank()) return

            val status = intent.getStringExtra(YouTubeDownloadService.EXTRA_STATUS).orEmpty()
            val progress = intent.getIntExtra(YouTubeDownloadService.EXTRA_PROGRESS, 0)
            val indeterminate = intent.getBooleanExtra(YouTubeDownloadService.EXTRA_INDETERMINATE, false)

            when (status) {
                YouTubeDownloadService.STATUS_STARTED,
                YouTubeDownloadService.STATUS_DOWNLOADING,
                YouTubeDownloadService.STATUS_CONVERTING -> {
                    val state = DownloadUiState(
                        visible = true,
                        progress = progress,
                        indeterminate = indeterminate
                    )
                    SearchSessionStore.downloadStates[videoId] = state
                    searchAdapter.updateDownloadState(
                        videoId,
                        state
                    )
                    refreshDisplayedResults()
                }

                YouTubeDownloadService.STATUS_COMPLETED,
                YouTubeDownloadService.STATUS_FAILED -> {
                    SearchSessionStore.downloadStates.remove(videoId)
                    SearchSessionStore.activeDownloads.remove(videoId)
                    searchAdapter.updateDownloadState(videoId, null)
                    refreshDisplayedResults()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_search, container, false)

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            requireContext(),
            downloadProgressReceiver,
            IntentFilter(YouTubeDownloadService.ACTION_DOWNLOAD_PROGRESS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        requireContext().unregisterReceiver(downloadProgressReceiver)
        super.onStop()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        playlistDao = PlaylistDatabase.getDatabase(requireContext()).playlistDao()

        recyclerView = view.findViewById(R.id.recyclerView)
        searchLoadingContainer = view.findViewById(R.id.searchLoadingContainer)
        searchLoadingText = view.findViewById(R.id.searchLoadingText)

        searchAdapter = SearchAdapter(searchResults) {
            showVideoActions(it)
        }
        searchAdapter.setDownloadStates(SearchSessionStore.downloadStates)

        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = searchAdapter

        refreshDisplayedResults()

        setupSwipeToAddToPlaylist()
        parentFragmentManager.setFragmentResultListener(
            MainActivity.SHARE_TO_SEARCH_REQUEST,
            viewLifecycleOwner
        ) { _, bundle ->
            bundle.getString(MainActivity.SHARE_QUERY_KEY)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let(::searchYouTube)

            parentFragmentManager.clearFragmentResult(MainActivity.SHARE_TO_SEARCH_REQUEST)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_search, menu)

        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView

        searchView.queryHint = "Search YouTube or paste link"
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
                searchViewModel.currentQuery = query
                SearchSessionStore.currentQuery = query
                setSearchLoading(true, if (isYouTubeUrl(query)) "Resolving link..." else "Searching...")
                val results = if (isYouTubeUrl(query)) {
                    listOf(resolveYouTubeLink(query))
                } else {
                    YouTubeApi.search(query)
                }
                searchViewModel.results = results
                SearchSessionStore.results = results
                refreshDisplayedResults()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Search error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                setSearchLoading(false)
            }
        }
    }

    private fun refreshDisplayedResults() {
        val mergedResults = buildList {
            addAll(SearchSessionStore.activeDownloads.values)
            addAll(
                SearchSessionStore.results.filter { result ->
                    !SearchSessionStore.activeDownloads.containsKey(result.id.videoId)
                }
            )
        }

        searchResults.clear()
        searchResults.addAll(mergedResults)
        searchAdapter.updateResults(mergedResults)
    }

    private fun setSearchLoading(isLoading: Boolean, message: String = "Searching...") {
        searchLoadingText.text = message
        searchLoadingContainer.isVisible = isLoading
    }

    private fun isYouTubeUrl(query: String): Boolean {
        val normalized = query.lowercase()
        return normalized.contains("youtube.com/") || normalized.contains("youtu.be/")
    }

    private fun extractVideoId(url: String): String? {
        val uri = Uri.parse(url)
        val host = uri.host.orEmpty().lowercase()
        val pathSegments = uri.pathSegments

        return when {
            host.contains("youtu.be") -> uri.lastPathSegment
            host.contains("youtube.com") -> {
                uri.getQueryParameter("v")
                    ?: pathSegments.indexOf("embed")
                        .takeIf { it >= 0 }
                        ?.let { pathSegments.getOrNull(it + 1) }
                    ?: pathSegments.indexOf("shorts")
                        .takeIf { it >= 0 }
                        ?.let { pathSegments.getOrNull(it + 1) }
            }
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    private suspend fun resolveYouTubeLink(url: String): YouTubeVideoItem {
        val streamInfo = extractStreamInfoWithRetry(url)
        val videoId = extractVideoId(url)
            ?: throw IllegalArgumentException("Invalid YouTube link")

        val thumbnailUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"

        return YouTubeVideoItem(
            id = VideoId(videoId = videoId),
            snippet = VideoSnippet(
                title = streamInfo.name.orEmpty().ifBlank { "YouTube Video" },
                description = "",
                thumbnails = VideoThumbnails(
                    default = Thumbnail(thumbnailUrl),
                    medium = Thumbnail(thumbnailUrl),
                    high = Thumbnail(thumbnailUrl)
                ),
                channelTitle = streamInfo.uploaderName.orEmpty().ifBlank { "YouTube" }
            )
        )
    }

    private fun showVideoActions(video: YouTubeVideoItem) {
        val options = arrayOf("Play Preview", "Download to Playlist")
        AlertDialog.Builder(requireContext())
            .setTitle(video.snippet.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> previewVideo(video)
                    1 -> showPlaylistSelection(video)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun previewVideo(video: YouTubeVideoItem) {
        showProgressDialog()
        updateProgressText("Loading preview...")
        progressBar?.isIndeterminate = true

        lifecycleScope.launch {
            try {
                val url = "https://www.youtube.com/watch?v=${video.id.videoId}"
                val streamInfo = extractStreamInfoWithRetry(url)
                val audioStream = streamInfo.audioStreams
                    .filter { stream ->
                        val mime = stream.format?.mimeType.orEmpty().lowercase()
                        mime.contains("audio") || mime.contains("mp4") || mime.contains("webm")
                    }
                    .maxByOrNull { it.averageBitrate }
                    ?: throw Exception("No suitable audio stream found")

                val audioUrl = audioStream.content ?: throw Exception("Audio stream URL is missing")
                PlaylistPlayerManager.playStream(
                    requireContext(),
                    streamUrl = audioUrl,
                    title = video.snippet.title,
                    artist = video.snippet.channelTitle
                )

                hideProgressDialog()
                Toast.makeText(requireContext(), "Playing preview", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                hideProgressDialog()
                Toast.makeText(requireContext(), "Preview error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                progressBar?.isIndeterminate = false
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
        val nextPosition = playlistDao.getNextPlaylistItemPosition(playlist.id)
        val newItem = PlaylistItem(
            playlistId = playlist.id,
            videoId = video.id.videoId,
            title = video.snippet.title,
            channelName = video.snippet.channelTitle,
            thumbnailUrl = video.snippet.thumbnails.medium.url,
            position = nextPosition
        )

        val resultId = playlistDao.insertPlaylistItem(newItem)

        val targetItem = if (resultId == -1L) {
            playlistDao.getPlaylistItem(playlist.id, video.id.videoId)
        } else {
            newItem.copy(id = resultId.toInt())
        }

        targetItem?.let { item ->
            val initialState = DownloadUiState(visible = true, progress = 0, indeterminate = true)
            val downloadIntent = YouTubeDownloadService.createIntent(
                context = requireContext(),
                itemId = item.id,
                videoUrl = "https://www.youtube.com/watch?v=${video.id.videoId}",
                videoTitle = video.snippet.title,
                videoId = video.id.videoId
            )
            SearchSessionStore.activeDownloads[video.id.videoId] = video
            SearchSessionStore.downloadStates[video.id.videoId] = initialState
            refreshDisplayedResults()
            searchAdapter.updateDownloadState(video.id.videoId, initialState)
            ContextCompat.startForegroundService(requireContext(), downloadIntent)
            Toast.makeText(
                requireContext(),
                "Download started in background",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showProgressDialog() {
        if (progressDialog?.isShowing == true) return
        val view = layoutInflater.inflate(R.layout.dialog_progress, null)
        progressBar = view.findViewById(R.id.progressBar)
        progressText = view.findViewById(R.id.progressText)
        progressBar?.isIndeterminate = false
        progressDialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .setCancelable(false)
            .create()
        progressDialog?.show()
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

}
