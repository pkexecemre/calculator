package com.simplecalculator.app.data.stt

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A recognized cue on the media timeline. */
data class Caption(val startMs: Long, val endMs: Long, val text: String)

/** One Whisper segment before filtering, with the model's speech probability. */
internal data class Segment(
    val startMs: Long,
    val endMs: Long,
    val noSpeechProb: Float,
    val text: String
)

/**
 * Drops what Whisper hallucinates on silence and music. Keeping this pure makes
 * the rules testable without a model or a phone.
 */
internal object CaptionFilter {

    private val WORD = Regex("[\\p{L}\\p{N}']+")
    private val TAGS = Regex("""\s*[\[(<].*[\])>]\s*""")
    private val MUSIC = Regex("""[\s♪♫]+""")

    private val HALLUCINATIONS = setOf(
        "thank you",
        "thanks",
        "thanks for watching",
        "thank you for watching",
        "thank you very much",
        "please subscribe",
        "subscribe",
        "subtitles by",
        "amara org",
        "blank audio",
        "silence",
        "music",
        "applause",
        "you can find the transcript"
    )

    fun clean(raw: String): String? {
        var text = raw
            .replace('\u200b', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        text = text.trim('♪', '♫', ' ').trim()
        if (text.isEmpty()) return null
        if (MUSIC.matches(text) || TAGS.matches(text)) return null
        val normalized = normalize(text)
        if (normalized.isEmpty()) return null
        if (isRepeatedLoop(normalized) || isRunaway(normalized)) return null
        return text
    }

    /**
     * These phrases are what the model emits on silence. Keep the check separate
     * from [clean] so a real "Thank you" with confident speech survives.
     */
    fun isKnownHallucination(text: String, noSpeechProb: Float): Boolean =
        noSpeechProb >= 0.35f && normalize(text) in HALLUCINATIONS

    /**
     * A four-second window cannot contain dozens of words. A decoder that loses
     * its EOT token produces a single 30 s segment full of repeated phrases;
     * that is a hallucination, not speech.
     */
    fun isRunaway(normalized: String): Boolean {
        val words = normalized.split(' ').filter { it.isNotEmpty() }
        if (words.size > 45) return true
        if (words.size < 9) return false
        val maxPhrase = minOf(10, words.size / 2)
        for (length in 3..maxPhrase) {
            val counts = HashMap<String, Int>()
            for (start in 0..words.size - length) {
                val phrase = words.subList(start, start + length).joinToString(" ")
                val count = (counts[phrase] ?: 0) + 1
                // Three repeats of any phrase, or two repeats of a 4+ word
                // phrase that dominate the segment, are decoder loops.
                if (count >= 3) return true
                if (length >= 4 && count >= 2 && count * length * 10 >= words.size * 6) return true
                counts[phrase] = count
            }
        }
        return false
    }

    fun normalize(text: String): String =
        WORD.findAll(text.lowercase()).joinToString(" ") { it.value }

    private fun isRepeatedLoop(normalized: String): Boolean {
        val words = normalized.split(' ')
        if (words.size < 6) return false
        if (words.all { it == words[0] }) return true
        // "you know you know you know" style loops from a confused decoder.
        for (period in 1..3) {
            if (words.size < period * 3) continue
            var same = true
            for (index in words.indices) {
                if (words[index] != words[index % period]) {
                    same = false
                    break
                }
            }
            if (same) return true
        }
        return false
    }
}

/**
 * Turns the segments of one audio window into cues that continue the previous
 * window instead of repeating the overlap. It also guards against duplicate
 * text caused by Whisper re-decoding the lead-in samples.
 */
internal class CaptionAssembler {

    private var lastText = ""
    private var lastEndMs = Long.MIN_VALUE

    @Synchronized
    fun reset() {
        lastText = ""
        lastEndMs = Long.MIN_VALUE
    }

    @Synchronized
    fun accept(segments: List<Segment>, cutMs: Long, noSpeechThreshold: Float = 0.72f): List<Caption> {
        val out = ArrayList<Caption>(segments.size)
        for (segment in segments) {
            // A cue that ends before the new audio starts was already covered by
            // the previous window. Cues crossing the cut carry new words.
            if (segment.endMs <= cutMs + 40L) continue
            if (segment.noSpeechProb >= noSpeechThreshold) continue
            // Segment timestamps are relative to a <= 4.6 s window. Anything
            // spanning much longer is the decoder running to the 30 s limit.
            if (segment.endMs - segment.startMs > 9_000L) continue
            if (CaptionFilter.isKnownHallucination(segment.text, segment.noSpeechProb)) continue
            val clean = CaptionFilter.clean(segment.text) ?: continue
            val trimmed = if (lastEndMs != Long.MIN_VALUE && cutMs - lastEndMs <= 1_200L) {
                trimOverlap(lastText, clean)
            } else {
                clean
            }
            if (trimmed.isBlank()) continue
            val start = max(segment.startMs, cutMs)
            val end = max(segment.endMs, start + 250L)
            out.add(Caption(start, end, trimmed))
            lastText = trimmed
            lastEndMs = end
        }
        return out
    }

    /**
     * Removes the words the new cue repeats from the tail of the last cue. The
     * overlap is at most a few words because only 1 s of audio is shared.
     */
    private fun trimOverlap(previous: String, current: String): String {
        val previousWords = CaptionFilter.normalize(previous)
            .split(' ')
            .filter { it.isNotEmpty() }
        if (previousWords.isEmpty()) return current
        val matches = Regex("[\\p{L}\\p{N}']+").findAll(current).toList()
        if (matches.isEmpty()) return current
        val currentWords = matches.map { it.value.lowercase() }
        val limit = min(min(previousWords.size, currentWords.size), MAX_OVERLAP_WORDS)
        var overlap = 0
        for (count in limit downTo 1) {
            if (previousWords.subList(previousWords.size - count, previousWords.size) ==
                currentWords.subList(0, count)
            ) {
                overlap = count
                break
            }
        }
        if (overlap == 0) return current
        val firstKept = matches.getOrNull(overlap) ?: return ""
        return current.substring(firstKept.range.first)
            .trimStart(' ', ',', '.', '!', '?', ';', ':', '-', '—', '\'', '"')
    }

    private companion object {
        const val MAX_OVERLAP_WORDS = 5
    }
}

/**
 * Detects a model that cannot keep up with real time. Two consecutive slow
 * windows are enough; a single scheduling spike should not downgrade anything.
 */
internal class SpeedGuard(
    private val limit: Float = 1.2f,
    private val strikes: Int = 2
) {
    private var slow = 0

    fun sample(rtf: Float): Boolean {
        if (rtf > limit) slow++ else if (slow > 0) slow--
        return slow >= strikes
    }

    fun reset() {
        slow = 0
    }
}

/**
 * Collects 16 kHz samples and cuts them into overlapping windows. Only the new
 * part of a window is new information; the lead-in keeps a word cut by the
 * previous boundary intact.
 */
internal class WindowBuffer(
    private val sampleRate: Int,
    firstWindowMs: Long,
    private val windowMs: Long,
    private val overlapMs: Long
) {

    data class Window(
        val startMs: Long,
        val cutMs: Long,
        val endMs: Long,
        val pcm: FloatArray,
        val rms: Float
    )

    private val firstWindowSamples = msToSamples(firstWindowMs)
    private val windowSamples = msToSamples(windowMs)
    private val overlapSamples = msToSamples(overlapMs)

    private companion object {
        const val MIN_FLUSH_MS = 1_200L
    }

    private var samples = FloatArray(sampleRate * 6)
    private var size = 0
    private var baseSample = 0L
    private var total = 0L
    private var windowStart = 0L
    private var basePositionMs = 0L
    private var first = true

    fun reset(positionMs: Long) {
        samples = FloatArray(sampleRate * 6)
        size = 0
        baseSample = 0
        total = 0
        windowStart = 0
        basePositionMs = positionMs
        first = true
    }

    fun bufferedMs(): Long = (size * 1_000L) / sampleRate

    fun append(input: FloatArray, count: Int) {
        if (count <= 0) return
        ensure(size + count)
        System.arraycopy(input, 0, samples, size, count)
        size += count
        total += count
    }

    /**
     * Cuts whatever is buffered into a final short window. Called when playback
     * pauses, so the last few seconds of speech are not stranded waiting for a
     * full window that will never arrive.
     */
    fun flush(): Window? {
        val endSample = total
        val minimum = msToSamples(MIN_FLUSH_MS)
        if (endSample - windowStart < minimum) return null
        val leadIn = if (first) 0L else overlapSamples
        val startSample = (windowStart - leadIn).coerceAtLeast(0L)
        val from = (startSample - baseSample).toInt()
        val to = (endSample - baseSample).toInt()
        if (from < 0 || to > size || to <= from) return null

        val pcm = samples.copyOfRange(from, to)
        val newFrom = (windowStart - startSample).toInt()
        val newCount = (endSample - windowStart).toInt()
        val startMs = positionOf(startSample)
        val cutMs = positionOf(windowStart)
        val endMs = positionOf(endSample)
        val rms = rms(pcm, newFrom, newCount)

        windowStart = endSample
        first = false
        trim()
        return Window(startMs, cutMs, endMs, pcm, rms)
    }

    fun poll(): Window? {
        val length = if (first) firstWindowSamples else windowSamples
        val endSample = windowStart + length
        if (total < endSample) return null

        val leadIn = if (first) 0L else overlapSamples
        val startSample = (windowStart - leadIn).coerceAtLeast(0L)
        val from = (startSample - baseSample).toInt()
        val to = (endSample - baseSample).toInt()
        if (from < 0 || to > size || to <= from) return null

        val pcm = samples.copyOfRange(from, to)
        val newFrom = (windowStart - startSample).toInt()
        val newCount = (endSample - windowStart).toInt()
        val startMs = positionOf(startSample)
        val cutMs = positionOf(windowStart)
        val endMs = positionOf(endSample)
        val rms = rms(pcm, newFrom, newCount)

        windowStart = endSample
        first = false
        trim()
        return Window(startMs, cutMs, endMs, pcm, rms)
    }

    private fun trim() {
        val keepFrom = (windowStart - overlapSamples).coerceAtLeast(0L)
        val remove = (keepFrom - baseSample).toInt()
        if (remove <= 0 || remove > size) return
        System.arraycopy(samples, remove, samples, 0, size - remove)
        size -= remove
        baseSample = keepFrom
    }

    private fun ensure(capacity: Int) {
        if (capacity <= samples.size) return
        var next = samples.size * 2
        while (next < capacity) next *= 2
        samples = samples.copyOf(next)
    }

    private fun positionOf(sample: Long): Long = basePositionMs + (sample * 1_000L) / sampleRate

    private fun msToSamples(ms: Long): Long = (ms * sampleRate) / 1_000L

    private fun rms(pcm: FloatArray, offset: Int, count: Int): Float {
        if (count <= 0) return 0f
        var sum = 0.0
        val end = (offset + count).coerceAtMost(pcm.size)
        for (index in offset until end) {
            val value = pcm[index]
            sum += value * value
        }
        return kotlin.math.sqrt(sum / (end - offset)).toFloat()
    }
}

/**
 * Streaming sample-rate converter. Positions are computed from cumulative
 * counts instead of per-callback rounding, so a long playback does not drift
 * away from the video clock.
 */
internal class StreamResampler(
    private val fromRate: Int,
    private val toRate: Int
) {
    private var inputTotal = 0L
    private var outputTotal = 0L

    fun process(input: FloatArray, count: Int, out: FloatArray): Int {
        if (count <= 0) return 0
        val inputStart = inputTotal
        inputTotal += count
        val target = inputTotal * toRate / fromRate
        val needed = (target - outputTotal).toInt()
        if (needed <= 0) return 0
        require(needed <= out.size) { "resample output too small" }

        if (fromRate == toRate) {
            val n = min(count, needed)
            System.arraycopy(input, 0, out, 0, n)
            outputTotal += n
            return n
        }

        val step = fromRate.toDouble() / toRate
        if (fromRate > toRate) {
            for (index in 0 until needed) {
                val source = (outputTotal + index) * step
                val start = floor(source).toLong()
                var end = floor(source + step).toLong()
                if (end <= start) end = start + 1
                var sum = 0.0
                var used = 0
                var position = start
                while (position < end) {
                    val local = position - inputStart
                    if (local in 0 until count) {
                        sum += input[local.toInt()]
                        used++
                    }
                    position++
                }
                out[index] = if (used > 0) {
                    (sum / used).toFloat()
                } else {
                    val local = (start - inputStart).toInt().coerceIn(0, count - 1)
                    input[local]
                }
            }
        } else {
            for (index in 0 until needed) {
                val source = (outputTotal + index) * step
                val lower = floor(source).toLong()
                val fraction = (source - lower).toFloat()
                val local0 = (lower - inputStart).toInt().coerceIn(0, count - 1)
                val local1 = (local0 + 1).coerceAtMost(count - 1)
                out[index] = input[local0] + (input[local1] - input[local0]) * fraction
            }
        }
        outputTotal += needed
        return needed
    }
}
