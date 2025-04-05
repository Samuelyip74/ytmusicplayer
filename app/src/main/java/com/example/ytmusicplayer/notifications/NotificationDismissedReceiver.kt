package com.example.ytmusicplayer.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.ytmusicplayer.services.MediaPlaybackService

class NotificationDismissedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // Stop playback and background service
        val stopIntent = Intent(context, MediaPlaybackService::class.java)
        context.stopService(stopIntent)
    }
}