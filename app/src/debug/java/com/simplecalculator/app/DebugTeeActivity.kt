package com.simplecalculator.app

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.simplecalculator.app.data.stt.LiveTranscriber
import com.simplecalculator.app.data.stt.ModelCatalog
import com.simplecalculator.app.player.PcmBridge
import com.simplecalculator.app.player.TeeAudioSink
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug-only smoke test for the real audio path: ExoPlayer decodes the bundled
 * JFK sample through the same TeeAudioSink as PlaybackService, PcmBridge hands
 * the tap to LiveTranscriber, and the captions are printed.
 *
 * adb shell am start -n com.simplecalculator.app/.DebugTeeActivity
 */
@UnstableApi
class DebugTeeActivity : Activity() {

    private lateinit var output: TextView
    private lateinit var player: ExoPlayer
    private var transcriber: LiveTranscriber? = null
    private var previousModelId: String? = null
    private val position = AtomicLong(0L)
    private val handler = Handler(Looper.getMainLooper())
    private var lines = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
            setBackgroundColor(Color.BLACK)
        }
        output = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.START
        }
        root.addView(
            ScrollView(this).apply { addView(output) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)

        val container = (application as App).container
        previousModelId = container.modelStore.selected().id
        container.modelStore.select(ModelCatalog.byId("base"))
        val audioAsset = intent.getStringExtra("audio") ?: "jfk.wav"
        append("model=base previous=" + previousModelId)
        append("audio=" + audioAsset)

        val stt = LiveTranscriber(container.modelStore)
        transcriber = stt
        stt.onState = { state -> append("state=" + state.name.lowercase()) }
        stt.captions = { cues ->
            val text = cues.joinToString(" | ") { it.text }
            append("caption: " + text)
            Log.i("LocalSTT_TEST", "tee caption: " + text)
        }
        PcmBridge.listener = { buffer, rate, channels, encoding ->
            stt.onPcm(buffer, rate, channels, encoding, position.get())
        }
        stt.start("en")

        val renderers = TeeAudioSink(this) { buffer, rate, channels, encoding ->
            PcmBridge.emit(buffer, rate, channels, encoding)
        }
        player = ExoPlayer.Builder(this, renderers).build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                append("player state=" + playbackState)
                if (playbackState == Player.STATE_ENDED) {
                    stt.onPlaybackState(false)
                }
            }
        })
        player.setMediaItem(MediaItem.fromUri(Uri.parse("asset:///" + audioAsset)))
        player.prepare()
        player.playWhenReady = true

        handler.post(object : Runnable {
            override fun run() {
                position.set(player.currentPosition)
                handler.postDelayed(this, 250L)
            }
        })
        append("playing asset")
    }

    private fun append(line: String) {
        Log.i("LocalSTT_TEST", line)
        handler.post {
            if (lines++ > 200) return@post
            output.append(line + "\n")
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player.release()
        PcmBridge.listener = null
        transcriber?.stop()
        previousModelId?.let { id ->
            runCatching {
                (application as App).container.modelStore.select(ModelCatalog.byId(id))
            }
        }
        super.onDestroy()
    }
}
