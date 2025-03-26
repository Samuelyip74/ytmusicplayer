package com.example.ytmusicplayer.ui.playlists

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.view.*
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
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
import kotlinx.coroutines.launch


class PlaylistFragment : Fragment() {

    private lateinit var playlistAdapter: PlaylistAdapter
    private val playlists = mutableListOf<Playlist>()
    private lateinit var playlistDao: PlaylistDao
    private lateinit var textNotifications: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true) // Enable ActionBar menu
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_playlist, container, false)

    @SuppressLint("NotifyDataSetChanged")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        playlistDao = PlaylistDatabase.getDatabase(requireContext()).playlistDao()

        val recyclerView = view.findViewById<RecyclerView>(R.id.playlistRecyclerView)
        textNotifications = view.findViewById(R.id.textNotifications)

        playlistAdapter = PlaylistAdapter(
            playlists,
            onSelect = { playlist ->
                findNavController().navigate(
                    R.id.action_playlistFragment_to_playlistDetailFragment,
                    bundleOf(
                        "playlistId" to playlist.id,
                        "playlistName" to playlist.name
                    )
                )
            }
        )

        recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = playlistAdapter
        }

        setupSwipeGestures(recyclerView)
        loadPlaylists()
    }

    private fun setupSwipeGestures(recyclerView: RecyclerView) {
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val playlist = playlists[viewHolder.adapterPosition]
                when (direction) {
                    ItemTouchHelper.LEFT -> {
                        AlertDialog.Builder(requireContext())
                            .setTitle("Delete Playlist")
                            .setMessage("Are you sure you want to delete \"${playlist.name}\"?")
                            .setPositiveButton("Delete") { _, _ ->
                                lifecycleScope.launch {
                                    playlistDao.deletePlaylist(playlist)
                                    playlists.removeAt(viewHolder.adapterPosition)
                                    playlistAdapter.notifyItemRemoved(viewHolder.adapterPosition)
                                    checkEmptyState()
                                    Toast.makeText(requireContext(), "Deleted", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .setNegativeButton("Cancel") { _, _ ->
                                playlistAdapter.notifyItemChanged(viewHolder.adapterPosition)
                            }
                            .show()
                    }

                    ItemTouchHelper.RIGHT -> {
                        showEditPlaylistDialog(playlist)
                        playlistAdapter.notifyItemChanged(viewHolder.adapterPosition)
                    }
                }
            }
        })
        itemTouchHelper.attachToRecyclerView(recyclerView)
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadPlaylists() {
        lifecycleScope.launch {
            playlists.clear()
            playlists.addAll(playlistDao.getAllPlaylists())
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
                    val newPlaylist = Playlist(name = name)
                    lifecycleScope.launch {
                        playlistDao.insertPlaylist(newPlaylist)
                        loadPlaylists()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @SuppressLint("NotifyDataSetChanged")
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
