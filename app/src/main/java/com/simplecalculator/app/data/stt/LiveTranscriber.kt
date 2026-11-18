package com.simplecalculator.app.data.stt

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Turns the audio that is actually playing into English captions, on device.
 *
 * The player's audio tee hands us decoded PCM. We fold it to 16 kHz mono and cut
 * it into short overlapping windows (4 s of new audio with 1 s of lead-in). A
 * window is transcribed off the audio thread while the next one is being
 * recorded, so captions follow speech instead of waiting for a whole segment.
 * Silence is skipped, overlapping words are removed between windows, and a
 * bounded queue plus a stale-window check keep a slow device from falling
 * minutes behind.
 */
class LiveTranscriber(
    private val modelStore: ModelStore
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Mutex()
    // Only the newest windows matter for live captions. Dropping the oldest one
    // under load keeps latency low instead of transcribing a growing backlog.
    private val queue = Channel<Chunk>(capacity = 2, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val ready = AtomicBoolean(false)
    private val epoch = AtomicLong(0)
    private val window = WindowBuffer(SAMPLE_RATE, FIRST_WINDOW_MS, WINDOW_MS, OVERLAP_MS)
    private val assembler = CaptionAssembler()
    private val speedGuard = SpeedGuard()
    private val audioLock = Any()

    private var worker: Job? = null
    private var loader: Job? = null
    private var idleUnload: Job? = null
    private var flushJob: Job? = null
    private var watchdog: Job? = null
    private var fallbackDone = false

    private var resampler: StreamResampler? = null
    private var resamplerRate = 0
    private var mono = FloatArray(0)
    private var resampled = FloatArray(0)

    @Volatile private var anchored = true
    @Volatile private var freshSession = true
    @Volatile private var lastPositionMs = 0L
    @Volatile private var languageHint: String? = null
    @Volatile private var loaded: SttModel? = null
    @Volatile private var sawPcm = false
    @Volatile private var sessionPcm = false
    @Volatile private var sessionCue = false

    var captions: (List<Caption>) -> Unit = {}
    var onState: (State) -> Unit = {}

    enum class State { IDLE, LOADING, LISTENING, NO_AUDIO, SLOW, UNAVAILABLE }

    /**
     * @param languageHint source language of the video ("en", "ja", ...).
     * English is transcribed; other languages are translated to English.
     * Null lets Whisper detect the language, which is slower.
     */
    fun start(languageHint: String? = null) {
        this.languageHint = languageHint?.trim()?.lowercase()?.takeIf { it != "auto" && it.isNotEmpty() }
        flushJob?.cancel()
        watchdog?.cancel()
        watchdog = null
        idleUnload?.cancel()
        idleUnload = null
        loader?.cancel()
        worker?.cancel()
        ready.set(false)
        loaded = null
        sessionPcm = false
        sessionCue = false
        fallbackDone = false
        speedGuard.reset()
        epoch.incrementAndGet()
        drainQueue()
        assembler.reset()
        synchronized(audioLock) {
            window.reset(lastPositionMs)
            anchored = true
            freshSession = true
            resampler = null
            resamplerRate = 0
        }
        onState(State.LOADING)
        val generation = epoch.get()

        loader = scope.launch {
            val model = loadModel()
            if (generation != epoch.get()) return@launch
            if (model == null) {
                Log.e(TAG, "no usable Whisper model")
                onState(State.UNAVAILABLE)
                return@launch
            }
            loaded = model
            ready.set(true)
            onState(State.LISTENING)
            worker?.cancel()
            worker = scope.launch { consume() }
            startWatchdog(generation)
        }
    }

    /**
     * Distinguishes "captions are merely slow" from "no audio is reaching the
     * transcriber" instead of showing "listening" forever.
     */
    private fun startWatchdog(generation: Long) {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(6_000L)
            if (generation != epoch.get() || !ready.get()) return@launch
            if (!sessionPcm) onState(State.NO_AUDIO)
            delay(8_000L)
            if (generation == epoch.get() && ready.get() && sessionPcm && !sessionCue) {
                onState(State.SLOW)
            }
        }
    }

    fun stop() {
        flushJob?.cancel()
        watchdog?.cancel()
        watchdog = null
        ready.set(false)
        epoch.incrementAndGet()
        worker?.cancel()
        worker = null
        loader?.cancel()
        loader = null
        loaded = null
        drainQueue()
        assembler.reset()
        synchronized(audioLock) { window.reset(lastPositionMs) }
        onState(State.IDLE)

        // Keep the model warm while the user is toggling captions, but do not
        // hold hundreds of megabytes for the rest of the session.
        idleUnload?.cancel()
        idleUnload = scope.launch {
            delay(45_000L)
            if (!ready.get()) {
                gate.withLock { WhisperBridge.nativeFree() }
                Log.i(TAG, "unloaded idle model")
            }
        }
    }

    /**
     * Called from the audio renderer thread with interleaved PCM. Everything in
     * here must stay allocation-light: a long GC pause is audible.
     */
    fun onPcm(
        pcm: ByteBuffer,
        sampleRate: Int,
        channelCount: Int,
        encoding: Int,
        positionMs: Long
    ) {
        if (!ready.get() || sampleRate <= 0 || channelCount <= 0) return
        lastPositionMs = positionMs
        val isFloat = encoding == ENCODING_PCM_FLOAT
        val buffer = pcm.order(ByteOrder.LITTLE_ENDIAN)
        val frames: Int
        if (isFloat) {
            frames = buffer.asFloatBuffer().remaining() / channelCount
        } else {
            frames = buffer.asShortBuffer().remaining() / channelCount
        }
        if (frames <= 0) return
        if (!sawPcm) {
            sawPcm = true
            Log.i(TAG, "pcm flowing: rate=$sampleRate ch=$channelCount encoding=$encoding")
        }
        if (!sessionPcm) {
            sessionPcm = true
            onState(State.LISTENING)
        }

        if (mono.size < frames) mono = FloatArray(frames)
        if (isFloat) {
            val floats = buffer.asFloatBuffer()
            for (frame in 0 until frames) {
                var sum = 0f
                for (channel in 0 until channelCount) sum += floats.get()
                mono[frame] = sum / channelCount
            }
        } else {
            val shorts = buffer.asShortBuffer()
            for (frame in 0 until frames) {
                var sum = 0f
                for (channel in 0 until channelCount) sum += shorts.get() / 32768f
                mono[frame] = sum / channelCount
            }
        }

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
            if (freshSession) {
                // Audio before the model finished loading was dropped; anchor
                // the first window at the position where capture really starts.
                freshSession = false
                window.reset(positionMs)
                anchored = true
            }
            if (!anchored) {
                window.reset(positionMs)
                anchored = true
            }
            window.append(resampled, outCount)
            while (true) {
                val next = window.poll() ?: break
                if (next.rms < SILENCE_RMS) {
                    Log.d(TAG, "skip silence window cut=${next.cutMs}ms rms=${next.rms}")
                    continue
                }
                val chunk = Chunk(
                    epoch.get(),
                    next.startMs,
                    next.cutMs,
                    next.endMs,
                    next.pcm,
                    System.nanoTime()
                )
                queue.trySend(chunk)
            }
        }
    }

    /**
     * Playback stopped: give the audio sink a moment to hand over its last
     * buffers, then transcribe the tail instead of waiting for a full window.
     */
    fun onPlaybackState(playing: Boolean) {
        flushJob?.cancel()
        flushJob = null
        if (playing) return
        flushJob = scope.launch {
            delay(500L)
            flush()
        }
    }

    /** Cuts the buffered tail into a window now. */
    fun flush() {
        if (!ready.get()) return
        val next = synchronized(audioLock) { window.flush() } ?: return
        if (next.rms < SILENCE_RMS) return
        Log.i(TAG, "flush window cut=${next.cutMs}ms")
        queue.trySend(
            Chunk(epoch.get(), next.startMs, next.cutMs, next.endMs, next.pcm, System.nanoTime())
        )
    }

    /** A seek invalidates buffered audio and every result still in flight. */
    fun onSeek(positionMs: Long) {
        flushJob?.cancel()
        lastPositionMs = positionMs
        epoch.incrementAndGet()
        drainQueue()
        assembler.reset()
        synchronized(audioLock) {
            window.reset(positionMs)
            anchored = true
            freshSession = false
        }
        captions(emptyList())
        Log.i(TAG, "seek to ${positionMs}ms")
    }

    private suspend fun loadModel(): SttModel? {
        modelStore.ensureBundled()
        val requested = modelStore.selected()
        val bundled = ModelCatalog.all.firstOrNull { it.bundled }
        // A selected model that cannot plausibly keep up must not make the
        // first caption take a minute. Base is always ready and fast enough for
        // the target phone; the setting still applies to smaller models.
        if (!requested.liveFriendly && bundled != null) {
            Log.i(TAG, "selected model ${requested.id} is not live-friendly; using ${bundled.id}")
            init(bundled)?.let { return it }
        }
        init(requested)?.let { return it }
        if (bundled != null && bundled.id != requested.id) {
            Log.w(TAG, "falling back to bundled model")
            return init(bundled)
        }
        return null
    }

    private suspend fun init(model: SttModel): SttModel? {
        if (!modelStore.isReady(model)) {
            Log.w(TAG, "model ${model.id} is not on disk")
            return null
        }
        val started = System.nanoTime()
        val ok = gate.withLock {
            WhisperBridge.nativeInit(modelStore.fileFor(model).absolutePath)
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000
        Log.i(TAG, "init ${model.id}: ok=$ok in ${elapsed}ms")
        return if (ok) model else null
    }

    private suspend fun consume() {
        while (true) {
            val chunk = queue.receiveCatching().getOrNull() ?: return
            val model = loaded ?: continue
            if (!ready.get() || chunk.epoch != epoch.get()) continue
            // If the decoder is behind, transcribing very old audio only makes
            // the visible captions older. Drop the window and catch up instead.
            if (isStale(chunk)) {
                Log.d(TAG, "drop stale window cut=${chunk.cutMs}ms age=${ageMs(chunk)}ms")
                continue
            }

            val hint = languageHint
            val translate = !model.englishOnly && hint != "en"
            val language = when {
                model.englishOnly -> "en"
                hint != null -> hint
                else -> null
            }
            val started = System.nanoTime()
            val raw = gate.withLock {
                WhisperBridge.nativeTranscribe(
                    pcm = chunk.pcm,
                    length = chunk.pcm.size,
                    translateToEnglish = translate,
                    language = language,
                    threads = THREADS,
                    // No prompt: on such short windows a prompt makes the
                    // decoder continue it and fall into repetition loops.
                    initialPrompt = null,
                    audioCtx = audioCtxFor(chunk.pcm.size)
                )
            }
            val inferMs = (System.nanoTime() - started) / 1_000_000
            if (!ready.get() || chunk.epoch != epoch.get()) continue
            val audioSec = chunk.pcm.size / SAMPLE_RATE.toFloat()
            val rtf = if (audioSec > 0f) inferMs / 1_000f / audioSec else 0f
            // Decide on a slow model before the latency drop: otherwise every
            // window is discarded and the fallback never gets a chance to run.
            if (speedGuard.sample(rtf) && !fallbackDone && model.id != bundledModel()?.id) {
                fallbackDone = true
                switchToBundled(model, rtf)
                if (!ready.get() || chunk.epoch != epoch.get()) continue
            }
            if (inferMs > MAX_RESULT_LATENCY_MS) {
                Log.w(TAG, "drop slow result cut=${chunk.cutMs}ms infer=${inferMs}ms")
                continue
            }
            if (isStale(chunk)) {
                Log.d(TAG, "drop late result cut=${chunk.cutMs}ms age=${ageMs(chunk)}ms")
                continue
            }

            val segments = parseSegments(raw, chunk.startMs)
            val cues = assembler.accept(segments, chunk.cutMs)
            if (chunk.epoch != epoch.get()) {
                // A seek landed while this window was being assembled; the
                // cues belong to the old position and must not be shown.
                assembler.reset()
                continue
            }
            if (cues.isNotEmpty()) {
                if (!sessionCue) {
                    sessionCue = true
                    onState(State.LISTENING)
                }
                captions(cues)
            }
            Log.i(
                TAG,
                "window ${chunk.cutMs}ms audio=${"%.1f".format(audioSec)}s " +
                    "infer=${inferMs}ms rtf=${"%.2f".format(rtf)} cues=${cues.size} " +
                    "text=${cues.joinToString(" | ") { it.text }.take(90)}"
            )
        }
    }

    /**
     * A model larger than the phone can run in real time makes captions later
     * and later. Fall back to the model shipped in the APK instead of showing
     * nothing, and keep the choice for this playback session.
     */
    private suspend fun switchToBundled(current: SttModel, rtf: Float) {
        val bundled = bundledModel() ?: return
        if (!modelStore.isReady(bundled)) return
        Log.w(TAG, "model ${current.id} is too slow (rtf=${"%.2f".format(rtf)}); switching to ${bundled.id}")
        val ok = gate.withLock {
            WhisperBridge.nativeInit(modelStore.fileFor(bundled).absolutePath)
        }
        if (ok) {
            loaded = bundled
            speedGuard.reset()
            Log.i(TAG, "fallback model ready: ${bundled.id}")
        } else {
            Log.e(TAG, "fallback model failed to load")
            ready.set(false)
            onState(State.UNAVAILABLE)
        }
    }

    private fun bundledModel(): SttModel? = ModelCatalog.all.firstOrNull { it.bundled }

    private fun ageMs(chunk: Chunk): Long = (System.nanoTime() - chunk.createdAtNs) / 1_000_000

    private fun isStale(chunk: Chunk): Boolean = ageMs(chunk) > STALE_WINDOW_MS

    private fun parseSegments(raw: String, offsetMs: Long): List<Segment> {
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<Segment>()
        for (line in raw.lineSequence()) {
            val parts = line.split('\t', limit = 4)
            if (parts.size < 4) continue
            val start = parts[0].trim().toLongOrNull() ?: continue
            val end = parts[1].trim().toLongOrNull() ?: continue
            val noSpeech = parts[2].trim().toFloatOrNull() ?: 0f
            out.add(Segment(offsetMs + start, offsetMs + end, noSpeech, parts[3]))
        }
        return out
    }

    private fun drainQueue() {
        while (queue.tryReceive().isSuccess) {
            // Drop stale windows after a seek or a restart.
        }
    }

    /**
     * The encoder cost is dominated by the 30 s context. A 5 s window only has
     * ~500 mel frames, so a shorter context is enough and roughly halves the
     * encoder and cross-attention work without cutting any speech.
     */
    private fun audioCtxFor(sampleCount: Int): Int {
        // One encoder position covers two mel frames. Keep a small margin and
        // grow in powers of two, matching the padding used by ggml.
        val melFrames = (sampleCount + 159) / 160
        var ctx = 256
        while (ctx < 1500 && ctx * 2 < melFrames + 4) ctx *= 2
        return ctx.coerceAtMost(1500)
    }

    private data class Chunk(
        val epoch: Long,
        val startMs: Long,
        val cutMs: Long,
        val endMs: Long,
        val pcm: FloatArray,
        val createdAtNs: Long
    )

    companion object {
        const val SAMPLE_RATE = 16_000
        const val ENCODING_PCM_FLOAT = 4
        private const val TAG = "LocalSTT"
        private const val FIRST_WINDOW_MS = 4_000L
        private const val WINDOW_MS = 4_000L
        private const val OVERLAP_MS = 1_000L
        private const val SILENCE_RMS = 0.002f
        // A slow phone should still show a late caption instead of dropping
        // every result; the queue and age checks keep the backlog bounded.
        private const val STALE_WINDOW_MS = 25_000L
        private const val MAX_RESULT_LATENCY_MS = 20_000L
        private val THREADS = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    }
}
