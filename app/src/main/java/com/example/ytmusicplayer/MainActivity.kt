package com.example.ytmusicplayer

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.annotation.OptIn
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.example.ytmusicplayer.databinding.ActivityMainBinding

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var mediaSession: MediaSessionCompat? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)

        // ✅ 1. Initialize view binding first
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ✅ 2. Setup session and player
        createNotificationChannel()
        setupMediaSession(this)

        PlaylistPlayerManager.initialize(this)

        // ✅ 3. Navigation setup
        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        val navView: BottomNavigationView = binding.navView

        val appBarConfiguration = AppBarConfiguration(
            setOf(R.id.navigation_search, R.id.navigation_playlists)
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        handleIntent()
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "yt_music_playback_channel",
                "YT Music Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "YT music background playback"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun setupMediaSession(context: Context) {
        mediaSession = MediaSessionCompat(context, "YTMusicSession")
        mediaSession!!.isActive = true
        mediaSession?.setMediaButtonReceiver(null)
    }

    private fun handleIntent(){
        val playlistId = intent?.getIntExtra("playlistId", -1) ?: -1
        Log.d("PlaylistID - Main","Playlist ID is $playlistId")
        if (playlistId != -1) {
            val bundle = Bundle().apply {
                putInt("playlistId", playlistId)
            }

            val navController = findNavController(R.id.nav_host_fragment_activity_main)
            navController.navigate(R.id.playlistDetailFragment, bundle)
        }
    }
}





