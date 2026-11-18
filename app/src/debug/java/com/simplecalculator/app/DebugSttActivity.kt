package com.simplecalculator.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.simplecalculator.app.data.stt.LiveTranscriber
import com.simplecalculator.app.data.stt.ModelCatalog
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Debug-only end-to-end harness for the live caption pipeline. It feeds the
 * bundled JFK sample into the real transcriber at playback speed and prints the
 * cues, so the windowing, resampling, JNI and filtering can be verified without
 * driving the whole player UI.
 *
 * adb shell am start -n com.simplecalculator.app/.DebugSttActivity
 */
class DebugSttActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var output: TextView
    private var transcriber: LiveTranscriber? = null
    private var previousModelId: String? = null
    private var lines = 0
    private lateinit var audioAsset: String
    private lateinit var languageHint: String

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
            ScrollView(this).apply {
                addView(output)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)

        val container = (application as App).container
        previousModelId = container.modelStore.selected().id
        val requested = intent.getStringExtra("model") ?: "base"
        container.modelStore.select(ModelCatalog.byId(requested))
        audioAsset = intent.getStringExtra("audio") ?: "jfk.wav"
        languageHint = intent.getStringExtra("hint") ?: "en"
        append("model=" + requested + " previous=" + previousModelId)
        append("audio=" + audioAsset + " hint=" + languageHint)

        val ready = CountDownLatch(1)
        val stt = LiveTranscriber(container.modelStore)
        transcriber = stt
        stt.onState = { state ->
            append("state=" + state.name.lowercase())
            if (state == LiveTranscriber.State.LISTENING) ready.countDown()
            if (state == LiveTranscriber.State.UNAVAILABLE) ready.countDown()
        }
        stt.captions = { cues ->
            val text = cues.joinToString(" | ") { it.text }
            append("caption " + cues.size + ": " + text)
            Log.i("LocalSTT_TEST", "caption cues=" + cues.size + " text=" + text)
        }
        append("starting transcriber")
        stt.start(languageHint)

        worker.execute {
            try {
                if (!ready.await(60, TimeUnit.SECONDS)) {
                    append("model never became ready")
                    return@execute
                }
                feed(stt)
            } catch (e: Exception) {
                append("feed failed: " + e)
                Log.e("LocalSTT_TEST", "feed failed", e)
            }
        }
    }

    private fun feed(stt: LiveTranscriber) {
        val wav = assets.open(audioAsset).use { input ->
            val out = ByteArrayOutputStream()
            input.copyTo(out)
            out.toByteArray()
        }
        val (offset, length) = findDataChunk(wav)
        append("wav data bytes=" + length)
        val chunkBytes = 640 // 20 ms of 16 kHz mono
        var position = 0L
        var cursor = offset
        val started = System.currentTimeMillis()
        while (cursor + chunkBytes <= offset + length) {
            val buffer = ByteBuffer.wrap(wav, cursor, chunkBytes).order(ByteOrder.LITTLE_ENDIAN)
            stt.onPcm(buffer, 16_000, 1, 2, position)
            cursor += chunkBytes
            position += 20L
            Thread.sleep(20L)
        }
        append("audio fed in " + (System.currentTimeMillis() - started) + "ms; flushing tail")
        stt.flush()
        Thread.sleep(20_000L)
        val elapsed = System.currentTimeMillis() - started
        append("done in " + elapsed + "ms")
        Log.i("LocalSTT_TEST", "done in " + elapsed + "ms")
    }

    private fun findDataChunk(wav: ByteArray): Pair<Int, Int> {
        var position = 12
        while (position + 8 <= wav.size) {
            val id = String(wav, position, 4, Charsets.US_ASCII)
            val size = (wav[position + 4].toInt() and 0xff) or
                ((wav[position + 5].toInt() and 0xff) shl 8) or
                ((wav[position + 6].toInt() and 0xff) shl 16) or
                ((wav[position + 7].toInt() and 0xff) shl 24)
            if (id == "data") return (position + 8) to size
            position += 8 + size + (size and 1)
        }
        throw IllegalStateException("no data chunk")
    }

    private fun append(line: String) {
        Log.i("LocalSTT_TEST", line)
        handler.post {
            if (lines++ > 200) return@post
            output.append(line + "\n")
        }
    }

    override fun onDestroy() {
        transcriber?.stop()
        worker.shutdownNow()
        previousModelId?.let { id ->
            runCatching {
                (application as App).container.modelStore.select(ModelCatalog.byId(id))
            }
        }
        super.onDestroy()
    }
}
