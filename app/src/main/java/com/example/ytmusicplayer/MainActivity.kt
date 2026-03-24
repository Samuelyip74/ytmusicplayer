package com.example.ytmusicplayer

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.annotation.OptIn
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media.session.MediaButtonReceiver
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.example.ytmusicplayer.database.PlaylistDatabase
import com.example.ytmusicplayer.databinding.ActivityMainBinding
import com.example.ytmusicplayer.services.MediaPlaybackService
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.NewPipe

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var mediaSession: MediaSessionCompat? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)

        // Initialize NewPipe globally at app startup
        NewPipe.init(DownloaderImpl())

        // ✅ 1. Initialize view binding first
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ✅ 2. Setup session and player
        createNotificationChannel()
        setupMediaSession(this)

        PlaylistPlayerManager.initialize(this)

        // Start background media service
        val intent = Intent(this, MediaPlaybackService::class.java)
        ContextCompat.startForegroundService(this, intent)

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
        if (playlistId != -1) {
            // Fetch playlist name from DB if needed
            lifecycleScope.launch {
                val dao = PlaylistDatabase.getDatabase(this@MainActivity).playlistDao()
                val playlist = dao.getAllPlaylists().find { it.id == playlistId }
                playlist?.let {
                    val bundle = Bundle().apply {
                        putInt("playlistId", it.id)
                        putString("playlistName", it.name)
                    }
                    findNavController(R.id.nav_host_fragment_activity_main)
                        .navigate(R.id.playlistDetailFragment, bundle)
                }
            }
        }
    }
}
