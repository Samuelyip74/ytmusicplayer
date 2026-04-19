package com.example.ytmusicplayer.ui.playlist

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.core.view.isVisible
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import androidx.recyclerview.widget.*
import com.bumptech.glide.Glide
import com.example.ytmusicplayer.PlaylistPlayerManager
import com.example.ytmusicplayer.R
import com.example.ytmusicplayer.database.PlaylistDatabase
import com.example.ytmusicplayer.database.model.PlaylistItem
import com.example.ytmusicplayer.notifications.showMediaNotification
import com.example.ytmusicplayer.ui.playlistdetail.PlaylistItemAdapter
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar

import kotlinx.coroutines.launch
import java.io.File

class PlaylistDetailFragment : Fragment() {

    private lateinit var playlistItemAdapter: PlaylistItemAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var playerView: PlayerView
    private lateinit var playlistTitle: TextView
    private lateinit var currentTrackTitle: TextView
    private lateinit var currentTrackImage: ImageView
    private lateinit var expandedNowPlayingContainer: View
    private lateinit var collapsedNowPlayingContainer: View
    private lateinit var collapsedTrackTitle: TextView
    private lateinit var collapsedTrackImage: ImageView
    private lateinit var collapsedButtonPlayPause: ImageButton
    private lateinit var collapsedButtonNext: ImageButton
    private lateinit var collapsedButtonPrev: ImageButton
    private lateinit var buttonPlayPause: ImageButton
    private lateinit var buttonNext: ImageButton
    private lateinit var buttonPrev: ImageButton

    private var playlistId: Int = -1
    private var playlistName: String = ""
    private val items = mutableListOf<PlaylistItem>()
    private var isMiniPlayerVisible = false

    private val playbackListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseButton()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateCurrentTrackInfo()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            playlistId = it.getInt("playlistId")
            playlistName = it.getString("playlistName").orEmpty()
        }

        setHasOptionsMenu(true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_playlist_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        recyclerView = view.findViewById(R.id.playlistItemRecyclerView)
        playerView = view.findViewById(R.id.playerView)
        playlistTitle = view.findViewById(R.id.playlistTitle)
        currentTrackTitle = view.findViewById(R.id.currentTrackTitle)
        currentTrackImage = view.findViewById(R.id.currentTrackImage)
        expandedNowPlayingContainer = view.findViewById(R.id.expandedNowPlayingContainer)
        collapsedNowPlayingContainer = view.findViewById(R.id.collapsedNowPlayingContainer)
        collapsedTrackTitle = view.findViewById(R.id.collapsedTrackTitle)
        collapsedTrackImage = view.findViewById(R.id.collapsedTrackImage)
        collapsedButtonPlayPause = view.findViewById(R.id.collapsedButtonPlayPause)
        collapsedButtonNext = view.findViewById(R.id.collapsedButtonNext)
        collapsedButtonPrev = view.findViewById(R.id.collapsedButtonPrev)
        buttonPlayPause = view.findViewById(R.id.buttonPlayPause)
        buttonNext = view.findViewById(R.id.buttonNext)
        buttonPrev = view.findViewById(R.id.buttonPrev)

        playlistTitle.text = playlistName

        playlistItemAdapter = PlaylistItemAdapter(
            items,
            onPlay = { playFromItem(it) },
            onDelete = { deleteItem(it) }
        )

        recyclerView.adapter = playlistItemAdapter
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateNowPlayingCardState()
            }
        })

        playerView.player = PlaylistPlayerManager.getPlayer()
        buttonPlayPause.setOnClickListener { togglePlayPause() }
        buttonNext.setOnClickListener { skipToNext() }
        buttonPrev.setOnClickListener { skipToPrevious() }
        collapsedButtonPlayPause.setOnClickListener { togglePlayPause() }
        collapsedButtonNext.setOnClickListener { skipToNext() }
        collapsedButtonPrev.setOnClickListener { skipToPrevious() }
        collapsedNowPlayingContainer.setOnClickListener {
            recyclerView.smoothScrollToPosition(0)
        }

        PlaylistPlayerManager.addListener(playbackListener)

        PlaylistPlayerManager.onPlaylistEnded = {
            navigateToNextPlaylist()
        }

        setupItemTouchHelper()
        loadItems()
        updateNowPlayingCardState()
    }

    private fun updateNowPlayingCardState() {
        val collapseThresholdPx = (24 * resources.displayMetrics.density).toInt()
        val shouldShowMiniPlayer = recyclerView.computeVerticalScrollOffset() > collapseThresholdPx

        if (shouldShowMiniPlayer == isMiniPlayerVisible) {
            return
        }

        isMiniPlayerVisible = shouldShowMiniPlayer

        if (shouldShowMiniPlayer) {
            showMiniPlayer()
        } else {
            showNowPlayingCard()
        }
    }

    private fun showMiniPlayer() {
        collapsedNowPlayingContainer.alpha = 0f
        collapsedNowPlayingContainer.translationY = -collapsedNowPlayingContainer.height.coerceAtLeast(1).toFloat() / 3f
        collapsedNowPlayingContainer.isVisible = true
        collapsedNowPlayingContainer.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(160)
            .start()

        expandedNowPlayingContainer.animate()
            .alpha(0f)
            .translationY(-expandedNowPlayingContainer.height.coerceAtLeast(1).toFloat() / 6f)
            .setDuration(160)
            .withEndAction {
                expandedNowPlayingContainer.isVisible = false
                expandedNowPlayingContainer.alpha = 1f
                expandedNowPlayingContainer.translationY = 0f
            }
            .start()
    }

    private fun showNowPlayingCard() {
        expandedNowPlayingContainer.alpha = 0f
        expandedNowPlayingContainer.translationY = -expandedNowPlayingContainer.height.coerceAtLeast(1).toFloat() / 6f
        expandedNowPlayingContainer.isVisible = true
        expandedNowPlayingContainer.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(160)
            .start()

        collapsedNowPlayingContainer.animate()
            .alpha(0f)
            .translationY(-collapsedNowPlayingContainer.height.coerceAtLeast(1).toFloat() / 3f)
            .setDuration(160)
            .withEndAction {
                collapsedNowPlayingContainer.isVisible = false
                collapsedNowPlayingContainer.alpha = 1f
                collapsedNowPlayingContainer.translationY = 0f
            }
            .start()
    }

    private fun navigateToNextPlaylist() {
        lifecycleScope.launch {
            val dao = PlaylistDatabase.getDatabase(requireContext()).playlistDao()
            val allPlaylists = dao.getAllPlaylists().sortedBy { it.id }
            val currentIndex = allPlaylists.indexOfFirst { it.id == playlistId }
            val next = allPlaylists.getOrNull(currentIndex + 1)

            if (next != null) {
                val items = dao.getItemsForPlaylist(next.id).sortedBy { it.position }

                val files = items.mapNotNull { it.downloadedFilePath }
                    .map { File(it) }
                    .filter { it.exists() }

                if (files.isNotEmpty()) {
                    PlaylistPlayerManager.playPlaylist(requireContext(), files, startIndex = 0, playlistId = next.id)

                    val bundle = Bundle().apply {
                        putInt("playlistId", next.id)
                        putString("playlistName", next.name)
                    }

                    val navOptions = NavOptions.Builder()
                        .setPopUpTo(R.id.playlistDetailFragment, true)
                        .build()

                    findNavController().navigate(R.id.playlistDetailFragment, bundle, navOptions)
                } else {
                    Toast.makeText(requireContext(), "No songs in the next playlist.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(requireContext(), "This is the last playlist.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updatePlayPauseButton() {
        val isPlaying = PlaylistPlayerManager.getPlayer()?.isPlaying == true
        val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        buttonPlayPause.setImageResource(playPauseIcon)
        collapsedButtonPlayPause.setImageResource(playPauseIcon)
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadItems() {
        lifecycleScope.launch {
            items.clear()
            val dao = PlaylistDatabase.getDatabase(requireContext()).playlistDao()
            items.addAll(dao.getItemsForPlaylist(playlistId).sortedBy { it.position })
            playlistItemAdapter.notifyDataSetChanged()

            val files = items.mapNotNull { it.downloadedFilePath }
                .map { File(it) }
                .filter { it.exists() }

            if (files.isNotEmpty()) {
                val player = PlaylistPlayerManager.getPlayer()
                val savedSession = PlaylistPlayerManager.getSavedPlaybackSession(requireContext())
                val isCurrentPlaylistLoaded =
                    PlaylistPlayerManager.currentPlaylistId == playlistId &&
                        player != null &&
                        player.mediaItemCount > 0 &&
                        player.currentMediaItemIndex != C.INDEX_UNSET
                val shouldWaitForRestore =
                    savedSession?.playlistId == playlistId &&
                        (player == null || player.mediaItemCount == 0)

                if (!isCurrentPlaylistLoaded && !shouldWaitForRestore) {
                    PlaylistPlayerManager.playPlaylist(requireContext(), files, playlistId)
                }

                updateCurrentTrackInfo()

                recyclerView.postDelayed({
                    updatePlayPauseButton()
                }, 300)
            } else {
                Toast.makeText(requireContext(), "No downloaded songs to play.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playFromItem(selectedItem: PlaylistItem) {
        val playableItems = items.mapNotNull { item ->
            item.downloadedFilePath
                ?.let(::File)
                ?.takeIf { it.exists() }
                ?.let { file -> item to file }
        }

        val files = playableItems.map { it.second }
        val startIndex = playableItems.indexOfFirst { (item, _) -> item.id == selectedItem.id }

        if (files.isNotEmpty() && startIndex >= 0) {
            PlaylistPlayerManager.playPlaylist(requireContext(), files, playlistId, startIndex)
            recyclerView.postDelayed({
                updateCurrentTrackInfo()
                updatePlayPauseButton()
                updateNotificationBanner(context, playlistId)
            }, 300)
        } else {
            Toast.makeText(requireContext(), "This song is not available for playback.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun togglePlayPause() {
        val player = PlaylistPlayerManager.getPlayer()
        if (player == null || player.currentMediaItemIndex == C.INDEX_UNSET) {
            val files = items.mapNotNull { it.downloadedFilePath }
                .map { File(it) }
                .filter { it.exists() }

            if (files.isNotEmpty()) {
                PlaylistPlayerManager.playPlaylist(requireContext(), files, playlistId)
                updatePlayPauseButton()
                updateNotificationBanner(context, playlistId)
                recyclerView.postDelayed({ updateCurrentTrackInfo() }, 300)
            } else {
                Toast.makeText(requireContext(), "No downloaded songs to play.", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }

        updatePlayPauseButton()
        updateNotificationBanner(context, playlistId)
    }

    private fun skipToNext() {
        val player = PlaylistPlayerManager.getPlayer()

        if (player == null || items.isEmpty()) return

        if (player.currentMediaItemIndex < player.mediaItemCount - 1) {
            player.seekToNext()
            player.play()
            recyclerView.postDelayed({
                updateCurrentTrackInfo()
                updatePlayPauseButton()
                updateNotificationBanner(context, playlistId)
            }, 300)
        } else {
            navigateToNextPlaylist()
        }
    }

    private fun skipToPrevious() {
        val player = PlaylistPlayerManager.getPlayer()

        if (player == null || items.isEmpty()) return

        if (player.hasPreviousMediaItem()) {
            player.seekToPrevious()
            player.play()
            recyclerView.postDelayed({
                updateCurrentTrackInfo()
                updatePlayPauseButton()
                updateNotificationBanner(context, playlistId)
            }, 300)
        }
        else {
            lifecycleScope.launch {
                val dao = PlaylistDatabase.getDatabase(requireContext()).playlistDao()
                val allPlaylists = dao.getAllPlaylists().sortedBy { it.id }
                val currentIndex = allPlaylists.indexOfFirst { it.id == playlistId }
                val previous = allPlaylists.getOrNull(currentIndex - 1)

                if (previous != null) {
                    val items = dao.getItemsForPlaylist(previous.id).sortedBy { it.position }

                    val files = items.mapNotNull { it.downloadedFilePath }
                        .map { File(it) }
                        .filter { it.exists() }

                    if (files.isNotEmpty()) {
                        PlaylistPlayerManager.playPlaylist(requireContext(), files, startIndex = 0, playlistId = previous.id)

                        val bundle = Bundle().apply {
                            putInt("playlistId", previous.id)
                            putString("playlistName", previous.name)
                        }

                        val navOptions = NavOptions.Builder()
                            .setPopUpTo(R.id.playlistDetailFragment, true)
                            .build()

                        findNavController().navigate(R.id.playlistDetailFragment, bundle, navOptions)
                    } else {
                        Toast.makeText(requireContext(), "No songs in the previous playlist.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(requireContext(), "This is the first playlist.", Toast.LENGTH_SHORT).show()
                }
            }
        }

    }


    private fun updateCurrentTrackInfo() {
        val player = PlaylistPlayerManager.getPlayer() ?: return
        val mediaItem = player.currentMediaItem ?: return

        val title = mediaItem.mediaMetadata?.title?.toString()
            ?: mediaItem.localConfiguration?.uri?.lastPathSegment
                ?.removeSuffix(".mp3")
                ?.replace("_", " ")

        currentTrackTitle.text = "Now Playing: ${title ?: "-"}"
        collapsedTrackTitle.text = title ?: "-"

        val filePath = mediaItem.localConfiguration?.uri?.path
        val matchedItem = items.find {
            it.downloadedFilePath?.let { path ->
                filePath?.endsWith(File(path).name) == true
            } ?: false
        }

        val thumbnailUrl = matchedItem?.thumbnailUrl
        if (!thumbnailUrl.isNullOrEmpty()) {
            Glide.with(requireContext())
                .load(thumbnailUrl)
                .error(R.drawable.ic_music_placeholder)
                .into(currentTrackImage)

            Glide.with(requireContext())
                .load(thumbnailUrl)
                .error(R.drawable.ic_music_placeholder)
                .into(collapsedTrackImage)
        } else {
            currentTrackImage.setImageResource(R.drawable.ic_music_placeholder)
            collapsedTrackImage.setImageResource(R.drawable.ic_music_placeholder)
        }
    }

    private fun updateNotificationBanner(context: Context?, playlistId: Int) {
        val player = PlaylistPlayerManager.getPlayer()
        val currentItem = player?.currentMediaItem
        val title = currentItem?.mediaMetadata?.title?.toString() ?: "Unknown Title"
        val artist = currentItem?.mediaMetadata?.artist?.toString() ?: "Unknown Artist"

        context?.let {
            showMediaNotification(
                context = it,
                isPlaying = player?.isPlaying == true,
                title = title,
                artist = artist,
                mediaSession = PlaylistPlayerManager.mediaSessionCompat,
                playlistId
            )
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun deleteItem(item: PlaylistItem) {
        lifecycleScope.launch {
            val dao = PlaylistDatabase.getDatabase(requireContext()).playlistDao()
            dao.deletePlaylistItem(item)
            items.remove(item)
            playlistItemAdapter.notifyDataSetChanged()
        }
    }

    private fun setupItemTouchHelper() {
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.adapterPosition
                val to = target.adapterPosition
                val moved = items.removeAt(from)
                items.add(to, moved)
                playlistItemAdapter.notifyItemMoved(from, to)
                persistItemOrder()
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val position = vh.adapterPosition
                val item = items[position]
                when (direction) {
                    ItemTouchHelper.LEFT -> handleDeleteSwipe(item, position)
                    ItemTouchHelper.RIGHT -> showMoveToPlaylistDialog(item, position)
                }
            }

            override fun onChildDraw(c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder, dX: Float, dY: Float, state: Int, isActive: Boolean) {
                if (state == ItemTouchHelper.ACTION_STATE_SWIPE && dX < 0) {
                    val itemView = vh.itemView
                    val background = ColorDrawable(Color.parseColor("#f44336"))
                    background.setBounds(itemView.right + dX.toInt(), itemView.top, itemView.right, itemView.bottom)
                    background.draw(c)

                    ContextCompat.getDrawable(requireContext(), R.drawable.ic_delete)?.let {
                        val margin = (itemView.height - it.intrinsicHeight) / 2
                        val top = itemView.top + margin
                        val bottom = top + it.intrinsicHeight
                        val right = itemView.right - margin
                        val left = right - it.intrinsicWidth
                        it.setBounds(left, top, right, bottom)
                        it.draw(c)
                    }
                } else if (state == ItemTouchHelper.ACTION_STATE_SWIPE && dX > 0) {
                    val itemView = vh.itemView
                    val background = ColorDrawable(Color.parseColor("#2e7d32"))
                    background.setBounds(itemView.left, itemView.top, itemView.left + dX.toInt(), itemView.bottom)
                    background.draw(c)

                    val label = "Move"
                    val paint = android.graphics.Paint().apply {
                        color = Color.WHITE
                        textSize = 40f
                        isAntiAlias = true
                        textAlign = android.graphics.Paint.Align.LEFT
                    }
                    val textX = itemView.left + 48f
                    val textY = itemView.top + (itemView.height / 2f) - ((paint.descent() + paint.ascent()) / 2f)
                    c.drawText(label, textX, textY, paint)
                }
                super.onChildDraw(c, rv, vh, dX, dY, state, isActive)
            }
        })
        itemTouchHelper.attachToRecyclerView(recyclerView)
    }

    private fun handleDeleteSwipe(item: PlaylistItem, position: Int) {
        if (isCurrentlyPlayingItem(item)) {
            Toast.makeText(requireContext(), "Can't delete currently playing song", Toast.LENGTH_SHORT).show()
            playlistItemAdapter.notifyItemChanged(position)
            return
        }

        items.removeAt(position)
        playlistItemAdapter.notifyItemRemoved(position)

        Snackbar.make(requireView(), "Removed \"${item.title}\"", Snackbar.LENGTH_LONG)
            .setAction("UNDO") {
                items.add(position, item)
                playlistItemAdapter.notifyItemInserted(position)
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(snackbar: Snackbar?, event: Int) {
                    if (event != DISMISS_EVENT_ACTION) {
                        lifecycleScope.launch {
                            val dao = PlaylistDatabase.getDatabase(requireContext()).playlistDao()
                            dao.deletePlaylistItem(item)
                            item.downloadedFilePath?.let { path ->
                                File(path).takeIf { it.exists() }?.delete()
                            }
                            persistVisiblePlaylistOrder(dao)
                        }
                    }
                }
            })
            .show()
    }

    private fun showMoveToPlaylistDialog(item: PlaylistItem, position: Int) {
        if (isCurrentlyPlayingItem(item)) {
            Toast.makeText(requireContext(), "Can't move currently playing song", Toast.LENGTH_SHORT).show()
            playlistItemAdapter.notifyItemChanged(position)
            return
        }

        lifecycleScope.launch {
            val dao = PlaylistDatabase.getDatabase(requireContext()).playlistDao()
            val targetPlaylists = dao.getAllPlaylists()
                .filter { it.id != playlistId }
                .sortedBy { it.position }

            if (targetPlaylists.isEmpty()) {
                Toast.makeText(requireContext(), "No other playlists found", Toast.LENGTH_SHORT).show()
                playlistItemAdapter.notifyItemChanged(position)
                return@launch
            }

            val names = targetPlaylists.map { it.name }.toTypedArray()
            var handled = false

            AlertDialog.Builder(requireContext())
                .setTitle("Move to Playlist")
                .setItems(names) { dialog, index ->
                    handled = true
                    lifecycleScope.launch {
                        moveItemToPlaylist(item, position, targetPlaylists[index].id, targetPlaylists[index].name)
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .setOnDismissListener {
                    if (!handled && position < items.size) {
                        playlistItemAdapter.notifyItemChanged(position)
                    }
                }
                .show()
        }
    }

    private suspend fun moveItemToPlaylist(
        item: PlaylistItem,
        position: Int,
        targetPlaylistId: Int,
        targetPlaylistName: String
    ) {
        val dao = PlaylistDatabase.getDatabase(requireContext()).playlistDao()
        val existingTargetItem = dao.getPlaylistItem(targetPlaylistId, item.videoId)

        if (existingTargetItem != null) {
            playlistItemAdapter.notifyItemChanged(position)
            Toast.makeText(
                requireContext(),
                "\"${item.title}\" is already in $targetPlaylistName",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val movedItem = item.copy(
            playlistId = targetPlaylistId,
            position = dao.getNextPlaylistItemPosition(targetPlaylistId)
        )

        dao.updatePlaylistItem(movedItem)

        items.removeAt(position)
        playlistItemAdapter.notifyItemRemoved(position)
        persistVisiblePlaylistOrder(dao)

        Toast.makeText(
            requireContext(),
            "Moved to $targetPlaylistName",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun isCurrentlyPlayingItem(item: PlaylistItem): Boolean {
        val currentMediaItem = PlaylistPlayerManager.getPlayer()?.currentMediaItem ?: return false
        val currentItemId = currentMediaItem.mediaId.toIntOrNull()
        if (currentItemId != null) {
            return currentItemId == item.id
        }

        val currentPath = currentMediaItem.localConfiguration?.uri?.path ?: return false
        return item.downloadedFilePath?.let { currentPath.endsWith(File(it).name) } == true
    }

    private fun persistItemOrder() {
        lifecycleScope.launch {
            val dao = PlaylistDatabase.getDatabase(requireContext()).playlistDao()
            persistVisiblePlaylistOrder(dao)
        }
    }

    private suspend fun persistVisiblePlaylistOrder(dao: com.example.ytmusicplayer.database.dao.PlaylistDao) {
        items.forEachIndexed { index, item ->
            item.position = index
            dao.updatePlaylistItem(item)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                requireActivity().onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        playerView.player = null
        PlaylistPlayerManager.removeListener(playbackListener)
        PlaylistPlayerManager.onPlaylistEnded = null
    }

    override fun onDestroy() {
        super.onDestroy()
        //PlaylistPlayerManager.release()
    }
}


