package com.simplecalculator.app.data.stt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

data class Caption(val startMs: Long, val endMs: Long, val text: String)

/**
 * Transcribes the audio that is actually playing: the tee in the player hands us
 * decoded PCM, we fold it to 16 kHz mono and run Whisper on ~12 s windows, so
 * captions appear a few seconds behind speech instead of after the whole file.
 */
class LiveTranscriber(
    private val modelStore: ModelStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Mutex()
    private val queue = Channel<Chunk>(Channel.UNLIMITED)
    private val pending = ArrayList<Float>(SAMPLE_RATE * WINDOW_SECONDS)
    private val ready = AtomicBoolean(false)

    var captions: (List<Caption>) -> Unit = {}
    var onState: (State) -> Unit = {}

    enum class State { IDLE, LOADING, LISTENING, DOWNLOADING }

    private var chunkStartMs = 0L
    private var sourceRate = 48_000
    private var channels = 2
    private var lastCaptionAt = 0L

    fun start() {
        onState(State.LOADING)
        scope.launch {
            val model = modelStore.selected()
            if (!modelStore.isReady(model) && !modelStore.ensureBundled()) {
                onState(State.DOWNLOADING)
                return@launch
            }
            val ok = gate.withLock {
                WhisperBridge.nativeInit(modelStore.fileFor(model).absolutePath)
            }
            ready.set(ok)
            onState(if (ok) State.LISTENING else State.IDLE)
        }
        scope.launch { consume() }
    }

    fun stop() {
        ready.set(false)
        scope.launch { gate.withLock { WhisperBridge.nativeFree() } }
        onState(State.IDLE)
    }

    /** Called from the audio thread with interleaved PCM16. */
    private var sawPcm = false

    fun onPcm(pcm: ByteBuffer, sampleRate: Int, channelCount: Int) {
        if (!sawPcm) {
            sawPcm = true
            android.util.Log.i("LocalSTT", "pcm flowing: rate=$sampleRate ch=$channelCount")
        }
        if (!ready.get()) return
        sourceRate = sampleRate
        channels = channelCount
        val samples = pcm.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val mono = ArrayList<Float>(samples.remaining() / channels)
        var index = 0
        var sum = 0f
        while (samples.hasRemaining()) {
            sum += samples.get() / 32768f
            index++
            if (index == channels) {
                mono.add(sum / channels)
                index = 0
                sum = 0f
            }
        }
        synchronized(this.pending) {
            pending.addAll(downsample(mono, sourceRate).toList())
            if (pending.size >= SAMPLE_RATE * WINDOW_SECONDS) {
                val chunk = pending.toFloatArray()
                pending.clear()
                val start = chunkStartMs
                chunkStartMs += (chunk.size * 1000L) / SAMPLE_RATE
                queue.trySend(Chunk(start, chunk))
            }
        }
    }

    /** A seek invalidates the timestamps we buffered. */
    fun onSeek(positionMs: Long) {
        synchronized(this.pending) { pending.clear() }
        chunkStartMs = positionMs
        lastCaptionAt = positionMs
        captions(emptyList())
    }

    private suspend fun consume() {
        for (chunk in queue) {
            if (!ready.get()) continue
            val raw = gate.withLock {
                WhisperBridge.nativeTranscribe(chunk.pcm, chunk.pcm.size, false, null, 4)
            }
            val cues = parse(raw, chunk.startMs)
            if (cues.isNotEmpty()) {
                lastCaptionAt = cues.last().endMs
                captions(cues)
            }
        }
    }

    private fun parse(raw: String, offsetMs: Long): List<Caption> = raw.lineSequence()
        .mapNotNull { line ->
            val parts = line.split('|', limit = 3)
            if (parts.size < 3) return@mapNotNull null
            val start = parts[0].toLongOrNull() ?: return@mapNotNull null
            val end = parts[1].toLongOrNull() ?: return@mapNotNull null
            val text = parts[2].trim()
            if (text.isBlank()) null else Caption(offsetMs + start, offsetMs + end, text)
        }
        .toList()

    /** Cheap linear resample to 16 kHz; speech survives it fine. */
    private fun downsample(input: List<Float>, rate: Int): FloatArray {
        if (rate == SAMPLE_RATE) return input.toFloatArray()
        val ratio = rate.toDouble() / SAMPLE_RATE
        val out = FloatArray((input.size / ratio).toInt().coerceAtLeast(1))
        for (i in out.indices) {
            val source = (i * ratio).toInt().coerceIn(0, input.size - 1)
            out[i] = input[source]
        }
        return out
    }

    private data class Chunk(val startMs: Long, val pcm: FloatArray)

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val WINDOW_SECONDS = 12
    }
}
