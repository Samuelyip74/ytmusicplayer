package com.example.ytmusicplayer.ui.playlists

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.*
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.ytmusicplayer.R
import com.example.ytmusicplayer.database.PlaylistDatabase
import com.example.ytmusicplayer.database.dao.PlaylistDao
import com.example.ytmusicplayer.database.model.Playlist
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.io.File


class PlaylistFragment : Fragment() {

    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var playlistDao: PlaylistDao
    private lateinit var textNotifications: TextView
    private lateinit var recyclerView: RecyclerView

    private val playlists = mutableListOf<Playlist>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_playlist, container, false)
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        playlistDao = PlaylistDatabase.getDatabase(requireContext()).playlistDao()
        textNotifications = view.findViewById(R.id.textNotifications)
        recyclerView = view.findViewById(R.id.playlistRecyclerView)

        playlistAdapter = PlaylistAdapter(
            playlists,
            onSelect = {
                findNavController().navigate(
                    R.id.action_playlistFragment_to_playlistDetailFragment,
                    Bundle().apply {
                        putInt("playlistId", it.id)
                        putString("playlistName", it.name)
                    }
                )
            }
        )

        recyclerView.adapter = playlistAdapter
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        setupItemTouchHelper()
        loadPlaylists()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadPlaylists() {
        lifecycleScope.launch {
            playlists.clear()
            playlists.addAll(playlistDao.getAllPlaylists().sortedBy { it.position })
            playlistAdapter.notifyDataSetChanged()
            checkEmptyState()
        }
    }

    private fun showAddPlaylistDialog() {
        val input = EditText(requireContext())
        AlertDialog.Builder(requireContext())
            .setTitle("Add Playlist")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val newPlaylist = Playlist(name = name, position = playlists.size)
                    lifecycleScope.launch {
                        playlistDao.insertPlaylist(newPlaylist)
                        loadPlaylists()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditPlaylistDialog(playlist: Playlist) {
        val input = EditText(requireContext()).apply { setText(playlist.name) }
        AlertDialog.Builder(requireContext())
            .setTitle("Edit Playlist")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    playlist.name = name
                    lifecycleScope.launch {
                        playlistDao.updatePlaylist(playlist)
                        playlistAdapter.notifyDataSetChanged()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkEmptyState() {
        textNotifications.visibility = if (playlists.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun persistPlaylistOrder() {
        lifecycleScope.launch {
            playlists.forEachIndexed { index, playlist ->
                playlist.position = index
                playlistDao.updatePlaylist(playlist)
            }
        }
    }

    private fun setupItemTouchHelper() {
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.adapterPosition
                val to = target.adapterPosition
                val moved = playlists.removeAt(from)
                playlists.add(to, moved)
                playlistAdapter.notifyItemMoved(from, to)
                persistPlaylistOrder()
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val position = vh.adapterPosition
                val item = playlists[position]

                when (direction) {
                    ItemTouchHelper.LEFT -> {
                        playlists.removeAt(position)
                        playlistAdapter.notifyItemRemoved(position)
                        checkEmptyState()

                        Snackbar.make(requireView(), "Deleted \"${item.name}\"", Snackbar.LENGTH_LONG)
                            .setAction("UNDO") {
                                playlists.add(position, item)
                                playlistAdapter.notifyItemInserted(position)
                                checkEmptyState()
                            }
                            .addCallback(object : Snackbar.Callback() {
                                override fun onDismissed(snackbar: Snackbar?, event: Int) {
                                    if (event != DISMISS_EVENT_ACTION) {
                                        lifecycleScope.launch {
                                            val dao = PlaylistDatabase.getDatabase(requireContext()).playlistDao()
                                            val items = dao.getItemsForPlaylists(item.id)

                                            items.forEach { playlistItem ->
                                                playlistItem.downloadedFilePath?.let { path ->
                                                    val file = File(path)
                                                    if (file.exists()) {
                                                        val deleted = file.delete()
                                                        val msg = if (deleted) "Deleted file: ${file.name}" else "Failed to delete file"
                                                        println(msg) // or use Log.d(...)
                                                    }
                                                }
                                            }

                                            dao.deletePlaylist(item)
                                        }
                                    }
                                }
                            })
                            .show()
                    }

                    ItemTouchHelper.RIGHT -> {
                        playlistAdapter.notifyItemChanged(position)
                        showEditPlaylistDialog(item)
                    }
                }
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
                val itemView = vh.itemView
                val iconMargin = (itemView.height - 48) / 2

                if (state == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    if (dX < 0) {
                        val background = ColorDrawable(Color.parseColor("#f44336"))
                        background.setBounds(itemView.right + dX.toInt(), itemView.top, itemView.right, itemView.bottom)
                        background.draw(c)

                        ContextCompat.getDrawable(requireContext(), R.drawable.ic_delete)?.let {
                            val top = itemView.top + iconMargin
                            val bottom = top + it.intrinsicHeight
                            val right = itemView.right - iconMargin
                            val left = right - it.intrinsicWidth
                            it.setBounds(left, top, right, bottom)
                            it.draw(c)
                        }
                    } else if (dX > 0) {
                        val background = ColorDrawable(Color.parseColor("#1976D2"))
                        background.setBounds(itemView.left, itemView.top, itemView.left + dX.toInt(), itemView.bottom)
                        background.draw(c)

                        ContextCompat.getDrawable(requireContext(), R.drawable.ic_edit)?.let {
                            val top = itemView.top + iconMargin
                            val bottom = top + it.intrinsicHeight
                            val left = itemView.left + iconMargin
                            val right = left + it.intrinsicWidth
                            it.setBounds(left, top, right, bottom)
                            it.draw(c)
                        }
                    }
                }

                super.onChildDraw(c, rv, vh, dX, dY, state, isActive)
            }
        })

        itemTouchHelper.attachToRecyclerView(recyclerView)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_playlist, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_add_playlist -> {
                showAddPlaylistDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
