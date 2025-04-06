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

    val dismissIntent = Intent(context, NotificationDismissedReceiver::class.java)
    val deletePendingIntent = PendingIntent.getBroadcast(
        context,
        4,
        dismissIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val fastForwardIntent = Intent(context, NotificationActionReceiver::class.java).apply {
        action = NotificationActionReceiver.ACTION_FAST_FORWARD
    }
    val fastForwardAction = NotificationCompat.Action.Builder(
        R.drawable.ic_fast_forward, "Forward",
        PendingIntent.getBroadcast(
            context, 5, fastForwardIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    ).build()

    val rewindIntent = Intent(context, NotificationActionReceiver::class.java).apply {
        action = NotificationActionReceiver.ACTION_REWIND
    }

    val rewindAction = NotificationCompat.Action.Builder(
        R.drawable.ic_rewind, "Rewind",
        PendingIntent.getBroadcast(
            context, 6, rewindIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    ).build()


    val notification = NotificationCompat.Builder(context, "yt_music_playback_channel")
        .setContentTitle(title)
        .setSmallIcon(R.drawable.ic_music_note)
        .setContentIntent(pendingIntent)
        .setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2, 3, 4)
        )
        .addAction(prevAction)          // 0
        .addAction(rewindAction)        // 1
        .addAction(playPauseAction)     // 2
        .addAction(fastForwardAction)   // 3
        .addAction(nextAction)          // 4
        .setDeleteIntent(deletePendingIntent)
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
