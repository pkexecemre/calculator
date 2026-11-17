package com.simplecalculator.app

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Owns the player so audio keeps running with the app in the background and the
 * system media notification / lock screen controls work.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as App).container
        val remote = OkHttpDataSource.Factory(container.http)
            .setUserAgent(com.simplecalculator.app.Container.DESKTOP_UA)
            .setDefaultRequestProperties(container.mediaHeaders)
        val dataSource = DefaultDataSource.Factory(this, remote)
        // Generous buffering: on slow links this trades a little startup latency
        // for far fewer rebuffers in the middle of playback.
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                20_000, // min buffer
                90_000, // max buffer
                1_500,  // enough to start playing
                3_000   // after a rebuffer
            )
            .build()
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        // Only our own UI and system surfaces may inspect or control playback.
        val pkg = controllerInfo.packageName
        val allowed = controllerInfo.uid == android.os.Process.myUid() ||
            pkg == "com.android.systemui" ||
            pkg == "android" ||
            pkg.startsWith("com.android.launcher")
        return if (allowed) mediaSession else null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
