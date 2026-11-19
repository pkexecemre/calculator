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
    private val modelStore: ModelStore,
    private val cache: CueCache = CueCache.None
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
    // Demote on the very first slow window: with RTF above ~1 the pipeline can
    // never catch up, and waiting for a second strike costs another 10 s.
    private val speedGuard = SpeedGuard(limit = 0.95f, strikes = 1)
    private var fastWindows = 0
    private var upgraded = false
    private var demotions = 0
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
    @Volatile private var requestedModelId: String? = null
    @Volatile private var loaded: SttModel? = null
    @Volatile private var sawPcm = false
    @Volatile private var sessionPcm = false
    @Volatile private var sessionCue = false
    // Transcripts already paid for by an earlier watch of this video.
    @Volatile private var cachedCues: List<Caption> = emptyList()
    @Volatile private var cachedRanges: List<LongRange> = emptyList()
    private var cacheKey: String? = null
    private var cacheLang: String = "en"

    var captions: (List<Caption>) -> Unit = {}
    var onState: (State) -> Unit = {}

    enum class State { IDLE, LOADING, LISTENING, NO_AUDIO, SLOW, UNAVAILABLE }

    /**
     * @param languageHint source language of the video ("en", "ja", ...).
     * English is transcribed; other languages are translated to English.
     * Null lets Whisper detect the language, which is slower.
     */
    fun start(
        languageHint: String? = null,
        modelId: String? = null,
        cacheKey: String? = null,
        cacheLang: String? = null
    ) {
        this.cacheKey = cacheKey
        this.cacheLang = cacheLang?.takeIf { it.isNotBlank() } ?: "en"
        cachedCues = emptyList()
        cachedRanges = emptyList()
        if (cacheKey != null) {
            scope.launch {
                val loaded = runCatching { cache.load(cacheKey, this@LiveTranscriber.cacheLang) }
                    .getOrDefault(emptyList())
                if (loaded.isEmpty() || cacheKey != this@LiveTranscriber.cacheKey) return@launch
                cachedCues = loaded
                cachedRanges = toRanges(loaded)
                // A seek into an already transcribed stretch should show text
                // immediately instead of waiting for the next window.
                emitCachedAt(lastPositionMs)
            }
        }
        this.languageHint = languageHint?.trim()?.lowercase()?.takeIf { it != "auto" && it.isNotEmpty() }
        this.requestedModelId = modelId
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

    /** True once the current session has received at least one PCM buffer. */
    fun hasPcm(): Boolean = sessionPcm

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
            delay(300_000L)
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
        val bytesPerSample = PcmDecoder.bytesPerSample(encoding)
        if (bytesPerSample <= 0) {
            Log.w(TAG, "unsupported audio encoding=$encoding (not PCM)")
            return
        }
        val frames = pcm.remaining() / (bytesPerSample * channelCount)
        if (frames <= 0) return
        if (mono.size < frames) mono = FloatArray(frames)
        if (!PcmDecoder.toMono(pcm, encoding, channelCount, frames, mono)) return
        if (!sawPcm) {
            sawPcm = true
            Log.i(TAG, "pcm flowing: rate=$sampleRate ch=$channelCount encoding=$encoding")
        }
        if (!sessionPcm) {
            sessionPcm = true
            onState(State.LISTENING)
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
                if (coveredByCache(next.startMs, next.endMs)) {
                    Log.d(TAG, "cache hit window")
                    continue
                }
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

    /**
     * Called as playback advances. Cached cues are shown from the transcript
     * cache without any inference, which is what makes seeking back instant.
     */
    fun onPosition(positionMs: Long) {
        lastPositionMs = positionMs
        if (cachedCues.isNotEmpty()) emitCachedAt(positionMs)
    }

    private var lastEmitMs = Long.MIN_VALUE

    private fun emitCachedAt(positionMs: Long) {
        if (cachedCues.isEmpty()) return
        val window = cachedCues.filter { positionMs >= it.startMs - 250L && positionMs <= it.endMs + 400L }
        if (window.isEmpty()) return
        val key = window.first().startMs
        if (key == lastEmitMs) return
        lastEmitMs = key
        captions(window)
    }

    private fun toRanges(cues: List<Caption>): List<LongRange> {
        if (cues.isEmpty()) return emptyList()
        val sorted = cues.sortedBy { it.startMs }
        val out = ArrayList<LongRange>()
        var start = sorted.first().startMs
        var end = sorted.first().endMs
        for (cue in sorted.drop(1)) {
            if (cue.startMs <= end + 500L) {
                end = maxOf(end, cue.endMs)
            } else {
                out.add(start until end)
                start = cue.startMs
                end = cue.endMs
            }
        }
        out.add(start until end)
        return out
    }

    private fun coveredByCache(startMs: Long, endMs: Long): Boolean {
        if (cachedRanges.isEmpty()) return false
        val from = startMs + 250L
        val to = endMs - 250L
        if (to <= from) return false
        return cachedRanges.any { it.first <= from && it.last >= to }
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

    /** Newly transcribed cues join the cache so a rewatch is free. */
    private fun remember(cues: List<Caption>) {
        val key = cacheKey ?: return
        val merged = (cachedCues + cues).sortedBy { it.startMs }.distinctBy { it.startMs }
        cachedCues = merged
        cachedRanges = toRanges(merged)
        scope.launch { runCatching { cache.save(key, cacheLang, cues) } }
    }

    private suspend fun loadModel(): SttModel? {
        modelStore.ensureBundled()
        val requested0 = requestedModelId
            ?.let { ModelCatalog.byId(it) }
            ?: modelStore.selected()
        // If this device already failed to keep up with the requested model,
        // start the session on the fastest one instead of repeating the lag.
        // Live captions open on the smallest ready model: waiting ~4 s for a
        // bigger model's first window is what made captions feel late. If the
        // device keeps up, the pipeline promotes itself after a few windows.
        val measuredSlow = SttPerf.rtfFor(requested0.id) > 0.95f
        val requested = if (requestedModelId == null || measuredSlow) {
            fastestReadyModel(requested0) ?: requested0
        } else {
            requested0
        }
        if (requested.id != requested0.id) {
            Log.i(
                TAG,
                "starting on " + requested.id + " (" + requested0.id + " is the quality tier)"
            )
        }
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
            var model = loaded ?: continue
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
            SttPerf.record(model.id, rtf)
            if (speedGuard.sample(rtf) && demotions < 2) {
                // Too slow for real time: drop to the smallest ready model.
                val faster = fastestReadyModel(model)
                if (faster != null) {
                    demotions++
                    Log.w(TAG, "demoting " + model.id + " rtf=" + "%.2f".format(rtf))
                    switchModel(faster, model, rtf)
                    if (!ready.get() || chunk.epoch != epoch.get()) continue
                    model = loaded ?: model
                }
            } else if (rtf < 0.55f && !upgraded) {
                // Plenty of headroom: step back up once for better text.
                fastWindows++
                if (fastWindows >= 6) {
                    val better = betterReadyModel(model)
                    if (better != null) {
                        upgraded = true
                        switchModel(better, model, rtf)
                        if (!ready.get() || chunk.epoch != epoch.get()) continue
                        model = loaded ?: model
                    }
                }
            } else if (rtf >= 0.5f) {
                fastWindows = 0
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
                remember(cues)
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

    private fun bundledModel(): SttModel? = ModelCatalog.all.firstOrNull { it.bundled }

    /** The smallest ready model that is allowed to run live captions. */
    private fun fastestReadyModel(current: SttModel?): SttModel? =
        ModelCatalog.all
            .filter { it.liveFriendly && it.id != current?.id && modelStore.isReady(it) }
            .filter { current == null || it.sizeMb < current.sizeMb }
            .minByOrNull { it.sizeMb }

    /** A step up in quality, only when the device proved it has the headroom. */
    private fun betterReadyModel(current: SttModel?): SttModel? =
        ModelCatalog.all
            .filter { it.liveFriendly && it.id != current?.id && modelStore.isReady(it) }
            .filter { it.sizeMb > (current?.sizeMb ?: 0) }
            .minByOrNull { it.sizeMb }

    private suspend fun switchModel(target: SttModel, current: SttModel?, rtf: Float) {
        val ok = gate.withLock {
            WhisperBridge.nativeInit(modelStore.fileFor(target).absolutePath)
        }
        if (ok) {
            loaded = target
            speedGuard.reset()
            fastWindows = 0
            Log.w(TAG, "model " + (current?.id) + " rtf=" + "%.2f".format(rtf) + " -> " + target.id)
        } else {
            Log.e(TAG, "switch to " + target.id + " failed")
            ready.set(false)
            onState(State.UNAVAILABLE)
        }
    }

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
        private const val TAG = "LocalSTT"
        // Shorter windows mean the first caption lands in ~2 s instead of ~5,
        // and each inference covers less audio. 3 s + 0.7 s overlap keeps ~23 %
        // redundancy, which Base still transcribes faster than real time.
        private const val FIRST_WINDOW_MS = 2_000L
        private const val WINDOW_MS = 3_000L
        private const val OVERLAP_MS = 700L
        private const val SILENCE_RMS = 0.002f
        // A slow phone should still show a late caption instead of dropping
        // every result; the queue and age checks keep the backlog bounded.
        private const val STALE_WINDOW_MS = 8_000L
        private const val MAX_RESULT_LATENCY_MS = 8_000L
        // whisper.cpp keeps scaling to ~8 threads on x86 and ~6 on big cores;
        // the old cap of 4 left a third of the machine idle on slow windows.
        private val THREADS = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
    }
}
