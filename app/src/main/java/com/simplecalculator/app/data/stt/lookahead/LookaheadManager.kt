package com.simplecalculator.app.data.stt.lookahead

import android.content.Context
import com.simplecalculator.app.data.stt.CueCache
import com.simplecalculator.app.data.stt.ModelStore
import okhttp3.OkHttpClient

/**
 * Owns the second player and the transcript lane that runs ahead of playback.
 *
 * Pace: the decoder may run as fast as it can, but there is no point decoding
 * two minutes ahead — that is bandwidth, battery and memory for text nobody will
 * read for two minutes. It is paused above [MAX_AHEAD_MS] and resumed below
 * [MIN_AHEAD_MS].
 */
class LookaheadManager(
    context: Context,
    http: OkHttpClient,
    headers: Map<String, String>,
    modelStore: ModelStore,
    cache: CueCache
) {

    val transcriber = LookaheadTranscriber(modelStore, cache)

    private val appContext = context.applicationContext
    private val http = http
    private val headers = headers
    private var decoder: LookaheadPlayer? = null

    @Volatile private var url: String? = null
    private var lastState = ""
    private var rewound = false
    private var openedAt = 0L

    /** True while a look-ahead lane is supposed to be producing captions. */
    @Volatile var enabled: Boolean = false
        private set

    fun start(
        streamUrl: String,
        startMs: Long,
        videoKey: String,
        displayLanguage: String,
        sourceLanguage: String
    ) {
        enabled = true
        openedAt = android.os.SystemClock.elapsedRealtime()
        rewound = false
        transcriber.start(videoKey, displayLanguage, sourceLanguage, startMs)
        if (decoder == null || url != streamUrl) {
            decoder?.release()
            decoder = LookaheadPlayer(appContext, http, headers) { buffer, rate, channels, positionMs ->
                transcriber.onPcm(buffer, rate, channels, android.media.AudioFormat.ENCODING_PCM_16BIT, positionMs)
            }
            url = streamUrl
            decoder?.open(streamUrl, startMs)
        } else {
            decoder?.seek(startMs)
        }
    }

    fun seek(positionMs: Long) {
        transcriber.seek(positionMs)
        decoder?.seek(positionMs)
    }

    /** Called from the player's position tick; keeps the decoder a sane distance ahead. */
    fun pace(positionMs: Long) {
        val d = decoder ?: return
        if (!enabled) return
        val produced = transcriber.producedUntil()
        val behind = produced - positionMs
        val running = when {
            behind >= MAX_AHEAD_MS -> false
            behind <= MIN_AHEAD_MS -> true
            else -> true
        }
        d.setRunning(running)
        val now = android.os.SystemClock.elapsedRealtime()
        val state = d.state()
        if (state != lastState) {
            lastState = state
            android.util.Log.i("LookaheadPace", "decoder " + state + " pos=" + positionMs + " produced=" + produced)
        }
        // A bad start position (stale seek) can leave the decoder buffering
        // forever; give it one clean retry from the beginning.
        if (state == "BUFFERING" && !rewound && now - openedAt > 10_000L) {
            val streamUrl = url
            if (streamUrl != null) {
                rewound = true
                android.util.Log.w("LookaheadPace", "decoder stuck buffering; restarting at 0")
                d.open(streamUrl, 0L)
            }
        }
    }

    fun stop() {
        enabled = false
        transcriber.stop()
        decoder?.release()
        decoder = null
        url = null
    }

    private companion object {
        const val MAX_AHEAD_MS = 90_000L
        const val MIN_AHEAD_MS = 30_000L
    }
}
