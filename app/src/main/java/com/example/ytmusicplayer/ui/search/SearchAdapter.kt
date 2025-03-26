package com.example.ytmusicplayer.ui.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.ytmusicplayer.R
import com.example.ytmusicplayer.database.model.YouTubeVideoItem

class SearchAdapter(
    private var results: List<YouTubeVideoItem>,
    private val onItemClick: (YouTubeVideoItem) -> Unit
) : RecyclerView.Adapter<SearchAdapter.SearchViewHolder>() {

    inner class SearchViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.videoTitle)
        val channel: TextView = view.findViewById(R.id.channelName)
        val thumbnail: ImageView = view.findViewById(R.id.videoThumbnail)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_youtube_video, parent, false)
        return SearchViewHolder(view)
    }

    override fun getItemCount(): Int = results.size

    override fun onBindViewHolder(holder: SearchViewHolder, position: Int) {
        val video = results[position]
        holder.title.text = video.snippet.title
        holder.channel.text = video.snippet.channelTitle

        Glide.with(holder.thumbnail.context)
            .load(video.snippet.thumbnails.medium.url)
            .into(holder.thumbnail)

        holder.itemView.setOnClickListener {
            onItemClick(video)
        }
    }

    fun updateResults(newResults: List<YouTubeVideoItem>) {
        results = newResults
        notifyDataSetChanged()
    }
}
