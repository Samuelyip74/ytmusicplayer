package com.example.ytmusicplayer.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import com.example.ytmusicplayer.MainActivity
import com.example.ytmusicplayer.R

@androidx.annotation.OptIn(UnstableApi::class)
fun showMediaNotification(
    context: Context,
    isPlaying: Boolean,
    title: String,
    artist: String,
    mediaSession: MediaSessionCompat,
    playlistId: Int ?= -1
) : Notification {
    val playPauseIntent = Intent(
        context,
        NotificationActionReceiver::class.java
    ).apply {
        action = if (isPlaying) NotificationActionReceiver.ACTION_PAUSE
        else NotificationActionReceiver.ACTION_PLAY
    }

    val playPauseAction = NotificationCompat.Action.Builder(
        if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
        if (isPlaying) "Pause" else "Play",
        PendingIntent.getBroadcast(
            context, 1, playPauseIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    ).build()

    val nextAction = NotificationCompat.Action.Builder(
        R.drawable.ic_next, "Next",
        PendingIntent.getBroadcast(
            context, 2,
            Intent(context, NotificationActionReceiver::class.java).apply {
                putExtra("playlistId", playlistId)
                action = NotificationActionReceiver.ACTION_NEXT
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    ).build()

    val prevAction = NotificationCompat.Action.Builder(
        R.drawable.ic_previous, "Previous",
        PendingIntent.getBroadcast(
            context, 3,
            Intent(context, NotificationActionReceiver::class.java).apply {
                putExtra("playlistId", playlistId)
                action = NotificationActionReceiver.ACTION_PREVIOUS
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    ).build()

    val intent = Intent(context, MainActivity::class.java).apply {
        putExtra("playlistId", playlistId)

    }

    val pendingIntent = PendingIntent.getActivity(
        context,
        0,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    val notification = NotificationCompat.Builder(context, "yt_music_playback_channel")
        .setContentTitle(title)
        .setSmallIcon(R.drawable.ic_music_note)
        .setContentIntent(pendingIntent)
        .setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
        )
        .addAction(prevAction)
        .addAction(playPauseAction)
        .addAction(nextAction)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(isPlaying)
        .build()

    if (context is android.app.Service) {
        //context.startForeground(1, notification)
    } else {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, notification)
    }

    return notification

}
