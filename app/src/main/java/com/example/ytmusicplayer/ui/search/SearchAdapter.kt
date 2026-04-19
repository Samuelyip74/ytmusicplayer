package com.example.ytmusicplayer.ui.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.ytmusicplayer.R
import com.example.ytmusicplayer.database.model.YouTubeVideoItem

data class DownloadUiState(
    val visible: Boolean,
    val progress: Int = 0,
    val indeterminate: Boolean = false
)

class SearchAdapter(
    private var results: List<YouTubeVideoItem>,
    private val onItemClick: (YouTubeVideoItem) -> Unit
) : RecyclerView.Adapter<SearchAdapter.SearchViewHolder>() {

    private val downloadStates = mutableMapOf<String, DownloadUiState>()

    inner class SearchViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.videoTitle)
        val channel: TextView = view.findViewById(R.id.channelName)
        val thumbnail: ImageView = view.findViewById(R.id.videoThumbnail)
        val downloadProgressBar: ProgressBar = view.findViewById(R.id.downloadProgressBar)
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

        val downloadState = downloadStates[video.id.videoId]
        holder.downloadProgressBar.visibility = if (downloadState?.visible == true) View.VISIBLE else View.GONE
        holder.downloadProgressBar.isIndeterminate = downloadState?.indeterminate == true
        holder.downloadProgressBar.progress = downloadState?.progress ?: 0

        holder.itemView.setOnClickListener {
            onItemClick(video)
        }
    }

    fun updateResults(newResults: List<YouTubeVideoItem>) {
        results = newResults
        notifyDataSetChanged()
    }

    fun setDownloadStates(states: Map<String, DownloadUiState>) {
        downloadStates.clear()
        downloadStates.putAll(states)
        notifyDataSetChanged()
    }

    fun updateDownloadState(videoId: String, state: DownloadUiState?) {
        if (state == null) {
            downloadStates.remove(videoId)
        } else {
            downloadStates[videoId] = state
        }
        val index = results.indexOfFirst { it.id.videoId == videoId }
        if (index >= 0) {
            notifyItemChanged(index)
        }
    }
}
