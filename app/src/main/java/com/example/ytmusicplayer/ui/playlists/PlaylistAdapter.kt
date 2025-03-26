package com.example.ytmusicplayer.ui.playlists

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.ytmusicplayer.R
import com.example.ytmusicplayer.database.model.Playlist

class PlaylistAdapter(
    private val playlists: List<Playlist>,
    private val onSelect: (Playlist) -> Unit // Add this parameter
) : RecyclerView.Adapter<PlaylistAdapter.PlaylistViewHolder>() {

    inner class PlaylistViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.playlistName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false)
        return PlaylistViewHolder(view)
    }

    override fun getItemCount(): Int = playlists.size

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        val playlist = playlists[position]

        holder.name.text = playlist.name

        holder.itemView.setOnClickListener {
            onSelect(playlist)  // Call onSelect when playlist is clicked
        }
    }
}

