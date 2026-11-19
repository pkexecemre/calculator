package com.simplecalculator.app.data.stt.lookahead

import android.util.Log
import com.simplecalculator.app.data.stt.Caption
import com.simplecalculator.app.data.stt.CaptionAssembler
import com.simplecalculator.app.data.stt.CueCache
import com.simplecalculator.app.data.stt.ModelCatalog
import com.simplecalculator.app.data.stt.ModelStore
import com.simplecalculator.app.data.stt.PcmDecoder
import com.simplecalculator.app.data.stt.StreamResampler
import com.simplecalculator.app.data.stt.WhisperBridge
import com.simplecalculator.app.data.stt.WindowBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Transcribes audio that is *ahead* of the playhead.
 *
 * The audio is handed over by [LookaheadPlayer], which decodes as fast as the
 * device allows, so this lane is never racing real time: it can use a bigger
 * window, a better model and no staleness dropping. Cues land in the transcript
 * cache, and the player shows them from there when the playhead reaches them.
 */
class LookaheadTranscriber(
    private val modelStore: ModelStore,
    private val cache: CueCache
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<Chunk>(capacity = 6, onBufferOverflow = BufferOverflow.SUSPEND)
    private val gate = Mutex()
    private val epoch = AtomicLong(0)
    private val assembler = CaptionAssembler()

    private val window = WindowBuffer(SAMPLE_RATE, FIRST_WINDOW_MS, WINDOW_MS, OVERLAP_MS)
    private val audioLock = Any()

    @Volatile private var resampler: StreamResampler? = null
    @Volatile private var resamplerRate = 0
    @Volatile private var mono = FloatArray(0)
    @Volatile private var resampled = FloatArray(0)

    @Volatile private var running = false
    @Volatile private var videoKey: String? = null
    @Volatile private var languageHint: String? = null
    @Volatile private var cacheLang = "en"
    @Volatile private var producedUntilMs = 0L
    @Volatile private var modelReady = false
    @Volatile private var modelId: String? = null

    /** Every cue produced for the current video, sorted by start. */
    @Volatile var cues: List<Caption> = emptyList()
        private set

    var onCues: (List<Caption>) -> Unit = {}
    var onState: (State) -> Unit = {}

    enum class State { IDLE, PREPARING, PRODUCING, UNAVAILABLE }

    fun start(videoKey: String, language: String, sourceLanguage: String, startMs: Long) {
        if (running && this.videoKey == videoKey) {
            seek(startMs)
            return
        }
        stop()
        running = true
        this.videoKey = videoKey
        this.cacheLang = language
        this.languageHint = sourceLanguage
        cues = emptyList()
        producedUntilMs = startMs
        modelReady = false
        epoch.incrementAndGet()
        onState(State.PREPARING)
        synchronized(audioLock) {
            window.reset(startMs)
            resampler = null
            resamplerRate = 0
        }
        val generation = epoch.get()
        scope.launch {
            val loaded = loadModel()
            if (generation != epoch.get()) return@launch
            if (loaded == null) {
                onState(State.UNAVAILABLE)
                return@launch
            }
            modelId = loaded
            modelReady = true
            onState(State.PRODUCING)
            // Already transcribed stretches come back for free.
            val cached = runCatching { cache.load(videoKey, language) }.getOrDefault(emptyList())
            if (cached.isNotEmpty() && generation == epoch.get()) {
                cues = cached
                onCues(cached)
                Log.i(TAG, "cache: " + cached.size + " cues for " + videoKey)
            }
            consume(generation)
        }
    }

    fun seek(positionMs: Long) {
        if (!running) return
        epoch.incrementAndGet()
        drain()
        assembler.reset()
        synchronized(audioLock) {
            window.reset(positionMs)
            producedUntilMs = maxOf(producedUntilMs, positionMs)
        }
        Log.i(TAG, "seek to " + positionMs + "ms")
    }

    fun stop() {
        running = false
        epoch.incrementAndGet()
        drain()
        producedUntilMs = 0L
        modelReady = false
        onState(State.IDLE)
    }

    fun isRunning(): Boolean = running

    fun producedUntil(): Long = producedUntilMs

    /** PCM straight from the look-ahead player. Called on its renderer thread. */
    fun onPcm(buffer: ByteBuffer, sampleRate: Int, channels: Int, encoding: Int, positionMs: Long) {
        if (!running || !modelReady) return
        val bytesPerSample = PcmDecoder.bytesPerSample(encoding)
        if (bytesPerSample <= 0) return
        val frames = buffer.remaining() / (bytesPerSample * channels).coerceAtLeast(1)
        if (frames <= 0) return
        val monoBuffer = if (mono.size < frames) FloatArray(frames) else mono
        if (!PcmDecoder.toMono(buffer, encoding, channels, frames, monoBuffer)) return
        mono = monoBuffer

        val converter = synchronized(audioLock) {
            if (resampler == null || resamplerRate != sampleRate) {
                resampler = StreamResampler(sampleRate, SAMPLE_RATE)
                resamplerRate = sampleRate
            }
            resampler!!
        }
        val capacity = (frames.toLong() * SAMPLE_RATE / sampleRate).toInt() + 8
        if (resampled.size < capacity) resampled = FloatArray(capacity)
        val outCount = converter.process(mono, frames, resampled)
        if (outCount <= 0) return

        synchronized(audioLock) {
            window.append(resampled, outCount)
            while (true) {
                val next = window.poll() ?: break
                if (next.rms < SILENCE_RMS) continue
                val result = queue.trySend(
                    Chunk(epoch.get(), next.startMs, next.cutMs, next.endMs, next.pcm)
                )
                if (result.isFailure) return@synchronized
            }
        }
    }

    private suspend fun consume(generation: Long) {
        while (running && generation == epoch.get()) {
            val chunk = queue.receiveCatching().getOrNull() ?: break
            if (!running || generation != epoch.get()) continue
            // Audio skipped by a seek belongs to the old position.
            if (chunk.cutMs < windowPositionFloor()) continue
            if (covered(chunk.startMs, chunk.endMs)) {
                // Already transcribed on an earlier watch: no inference, but the
                // decoder still counts as ahead so pacing stays honest.
                producedUntilMs = maxOf(producedUntilMs, chunk.endMs)
                continue
            }

            val translate = languageHint != null && languageHint != "en"
            val model = modelId ?: continue
            val started = System.nanoTime()
            val raw = gate.withLock {
                WhisperBridge.nativeTranscribe(
                    pcm = chunk.pcm,
                    length = chunk.pcm.size,
                    translateToEnglish = translate,
                    language = languageHint,
                    threads = THREADS,
                    initialPrompt = null,
                    audioCtx = audioCtxFor(chunk.pcm.size)
                )
            }
            val inferMs = (System.nanoTime() - started) / 1_000_000
            if (!running || generation != epoch.get()) continue
            val audioSec = chunk.pcm.size / SAMPLE_RATE.toFloat()
            val rtf = if (audioSec > 0f) inferMs / 1_000f / audioSec else 0f
            val segments = parseSegments(raw, chunk.startMs)
            val emitted = assembler.accept(segments, chunk.cutMs)
            if (generation != epoch.get()) continue
            producedUntilMs = maxOf(producedUntilMs, chunk.endMs)
            if (emitted.isNotEmpty()) {
                val merged = (cues + emitted).sortedBy { it.startMs }.distinctBy { it.startMs }
                cues = merged
                onCues(merged)
                videoKey?.let { key -> runCatching { cache.save(key, cacheLang, emitted) } }
            }
            Log.i(
                TAG,
                "lookahead window " + chunk.cutMs + "ms audio=" + "%.1f".format(audioSec) +
                    "s infer=" + inferMs + "ms rtf=" + "%.2f".format(rtf) +
                    " ahead=" + (chunk.endMs) + " model=" + model
            )
        }
    }

    private fun windowPositionFloor(): Long = synchronized(audioLock) { producedUntilMs }

    private fun covered(startMs: Long, endMs: Long): Boolean {
        if (cues.isEmpty()) return false
        val from = startMs + 250L
        val to = endMs - 250L
        if (to <= from) return false
        return cues.any { it.startMs <= from && it.endMs >= to }
    }

    private suspend fun loadModel(): String? {
        modelStore.ensureBundled()
        // This lane is not bound to real time, so prefer the quality tier when
        // the device has it; fall back to whatever is on disk.
        val preferred = ModelCatalog.all
            .filter { it.liveFriendly && modelStore.isReady(it) }
            .maxByOrNull { it.sizeMb }
        val chosen = preferred ?: ModelCatalog.all.firstOrNull { modelStore.isReady(it) }
        if (chosen == null) {
            Log.e(TAG, "no model on disk")
            return null
        }
        val ok = gate.withLock {
            WhisperBridge.nativeInit(modelStore.fileFor(chosen).absolutePath)
        }
        if (!ok) {
            Log.e(TAG, "model init failed: " + chosen.id)
            return null
        }
        Log.i(TAG, "model " + chosen.id + " ready for look-ahead")
        return chosen.id
    }

    private fun parseSegments(raw: String, offsetMs: Long): List<com.simplecalculator.app.data.stt.Segment> {
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<com.simplecalculator.app.data.stt.Segment>()
        for (line in raw.lineSequence()) {
            val clean = line.trim()
            if (clean.isEmpty()) continue
            val parts = clean.split('	')
            if (parts.size < 4) continue
            val start = parts[0].toLongOrNull() ?: continue
            val end = parts[1].toLongOrNull() ?: continue
            val noSpeech = parts[2].toFloatOrNull() ?: 0f
            val text = parts.drop(3).joinToString("	")
            out.add(
                com.simplecalculator.app.data.stt.Segment(
                    startMs = start + offsetMs,
                    endMs = end + offsetMs,
                    noSpeechProb = noSpeech,
                    text = text
                )
            )
        }
        return out
    }

    private fun audioCtxFor(sampleCount: Int): Int {
        // One encoder position covers two mel frames; grow in powers of two the
        // same way the real-time lane does so both share native behaviour.
        val melFrames = (sampleCount + 159) / 160
        var ctx = 256
        while (ctx < 1500 && ctx * 2 < melFrames + 4) ctx *= 2
        return ctx.coerceAtMost(1500)
    }

    private fun drain() {
        while (queue.tryReceive().isSuccess) Unit
    }

    private data class Chunk(
        val epoch: Long,
        val startMs: Long,
        val cutMs: Long,
        val endMs: Long,
        val pcm: FloatArray
    )

    companion object {
        private const val TAG = "LookaheadSTT"
        private const val SAMPLE_RATE = 16_000
        private const val FIRST_WINDOW_MS = 6_000L
        private const val WINDOW_MS = 8_000L
        private const val OVERLAP_MS = 800L
        private const val SILENCE_RMS = 0.002f
        private const val HOP_MS = 10
        private val THREADS = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
    }
}
