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
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.media.session.MediaButtonReceiver
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
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

    companion object {
        const val SHARE_TO_SEARCH_REQUEST = "share_to_search_request"
        const val SHARE_QUERY_KEY = "share_query"
    }

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
        val serviceIntent = Intent(this, MediaPlaybackService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)

        // ✅ 3. Navigation setup
        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        val navView: BottomNavigationView = binding.navView

        val appBarConfiguration = AppBarConfiguration(
            setOf(R.id.navigation_search, R.id.navigation_playlists)
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        handleIntent(intent)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(sourceIntent: Intent?) {
        val playlistId = sourceIntent?.getIntExtra("playlistId", -1) ?: -1
        if (playlistId != -1) {
            lifecycleScope.launch {
                val dao = PlaylistDatabase.getDatabase(this@MainActivity).playlistDao()
                val playlist = dao.getPlaylistById(playlistId)
                playlist?.let {
                    val bundle = Bundle().apply {
                        putInt("playlistId", it.id)
                        putString("playlistName", it.name)
                    }
                    findNavController(R.id.nav_host_fragment_activity_main)
                        .navigate(R.id.playlistDetailFragment, bundle)
                }
            }
            return
        }

        extractSharedQuery(sourceIntent)?.let { sharedQuery ->
            val navController = findNavController(R.id.nav_host_fragment_activity_main)
            binding.navView.selectedItemId = R.id.navigation_search
            if (navController.currentDestination?.id != R.id.navigation_search) {
                navController.navigate(R.id.navigation_search)
            }
            val navHostFragment =
                supportFragmentManager.findFragmentById(R.id.nav_host_fragment_activity_main) as? NavHostFragment
            navHostFragment?.childFragmentManager?.setFragmentResult(
                SHARE_TO_SEARCH_REQUEST,
                bundleOf(SHARE_QUERY_KEY to sharedQuery)
            )
            sourceIntent?.removeExtra(Intent.EXTRA_TEXT)
            sourceIntent?.action = null
            return
        }

        restoreLastPlayedPlaylist()
    }

    private fun extractSharedQuery(sourceIntent: Intent?): String? {
        if (sourceIntent?.action != Intent.ACTION_SEND) return null
        if (sourceIntent.type != "text/plain") return null

        return sourceIntent.getStringExtra(Intent.EXTRA_TEXT)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun restoreLastPlayedPlaylist() {
        val savedSession = PlaylistPlayerManager.getSavedPlaybackSession(this) ?: return
        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        if (navController.currentDestination?.id == R.id.playlistDetailFragment) return

        lifecycleScope.launch {
            val dao = PlaylistDatabase.getDatabase(this@MainActivity).playlistDao()
            val playlist = dao.getPlaylistById(savedSession.playlistId) ?: return@launch

            binding.navView.selectedItemId = R.id.navigation_playlists
            val bundle = Bundle().apply {
                putInt("playlistId", playlist.id)
                putString("playlistName", playlist.name)
            }
            navController.navigate(R.id.playlistDetailFragment, bundle)
        }
    }
}
