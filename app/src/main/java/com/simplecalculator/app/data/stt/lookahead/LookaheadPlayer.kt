package com.simplecalculator.app.data.stt.lookahead

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.OkHttpClient

/**
 * A silent ExoPlayer that decodes the same stream far ahead of what the viewer
 * is watching.
 *
 * It uses [PcmCaptureSink] instead of a real audio output and no video renderer,
 * so nothing throttles it to real time: it runs as fast as the decoder allows.
 * The caption pipeline consumes its PCM, which is why captions can be ready
 * *before* the playhead reaches the speech.
 */
@OptIn(UnstableApi::class)
class LookaheadPlayer(
    private val context: Context,
    private val http: OkHttpClient,
    private val headers: Map<String, String>,
    private val onPcm: (buffer: java.nio.ByteBuffer, sampleRate: Int, channels: Int, positionMs: Long) -> Unit
) {

    private var player: ExoPlayer? = null
    private var url: String? = null
    private var sinkRef: PcmCaptureSink? = null

    /** Starts decoding [url] from [startMs]. Safe to call with the same URL again. */
    fun open(url: String, startMs: Long) {
        if (this.url == url && player != null) {
            seek(startMs)
            return
        }
        release()
        this.url = url
        val sink = PcmCaptureSink(onPcm)
        sink.setBase(startMs)
        sinkRef = sink
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                android.util.Log.i("LookaheadPlayer", "buildAudioSink called")
                return sink
            }

            override fun buildVideoRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: android.os.Handler,
                eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: ArrayList<Renderer>
            ) {
                android.util.Log.i("LookaheadPlayer", "buildVideoRenderers called (suppressed)")
            }
        }
        val remote = OkHttpDataSource.Factory(http)
            .setUserAgent(headers["User-Agent"] ?: "Mozilla/5.0")
            .setDefaultRequestProperties(headers)
        val dataSource = DefaultDataSource.Factory(context, remote)
        val exo = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo.volume = 0f
        exo.setMediaItem(MediaItem.fromUri(url), startMs.coerceAtLeast(0L))
        exo.prepare()
        exo.playWhenReady = true
        player = exo
        Log.i(TAG, "lookahead open at " + startMs + "ms")
    }

    fun seek(positionMs: Long) {
        val exo = player ?: return
        sinkRef?.setBase(positionMs)
        exo.seekTo(positionMs.coerceAtLeast(0L))
    }

    /** Paused when the transcript is far enough ahead; resumed when it is not. */
    fun setRunning(running: Boolean) {
        val exo = player ?: return
        if (exo.playWhenReady != running) exo.playWhenReady = running
    }

    fun positionMs(): Long = player?.currentPosition ?: 0L

    /** ExoPlayer state name, for pacing diagnostics. */
    fun state(): String = when (player?.playbackState) {
        androidx.media3.common.Player.STATE_IDLE -> "IDLE"
        androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
        androidx.media3.common.Player.STATE_READY -> "READY"
        androidx.media3.common.Player.STATE_ENDED -> "ENDED"
        else -> "?"
    }

    fun release() {
        player?.release()
        player = null
        url = null
    }

    private companion object {
        const val TAG = "LookaheadPlayer"
    }
}
