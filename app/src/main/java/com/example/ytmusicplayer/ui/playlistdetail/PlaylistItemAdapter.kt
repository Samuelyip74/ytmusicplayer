package com.example.ytmusicplayer.ui.playlistdetail

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.ytmusicplayer.R
import com.example.ytmusicplayer.database.model.PlaylistItem

class PlaylistItemAdapter(
    private val items: MutableList<PlaylistItem>,
    private val onPlay: (PlaylistItem) -> Unit,
    private val onDelete: (PlaylistItem) -> Unit
) : RecyclerView.Adapter<PlaylistItemAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.videoThumbnail)
        val title: TextView = view.findViewById(R.id.videoTitle)

        init {
            view.setOnClickListener {
                val item = items[adapterPosition]
                onPlay(item)
            }
            view.setOnLongClickListener {
                val item = items[adapterPosition]
                onDelete(item)
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist_video, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = item.title

        Glide.with(holder.itemView.context)
            .load(item.thumbnailUrl)
            .centerCrop()
            .placeholder(R.drawable.ic_music_video)
            .error(R.drawable.ic_music_video)
            .into(holder.thumbnail)
    }

    fun moveItem(from: Int, to: Int) {
        val movedItem = items.removeAt(from)
        items.add(to, movedItem)
        notifyItemMoved(from, to)
    }
}
