package com.simplecalculator.app

import com.simplecalculator.app.data.stt.CaptionAssembler
import com.simplecalculator.app.data.stt.CaptionFilter
import com.simplecalculator.app.data.stt.Segment
import com.simplecalculator.app.data.stt.StreamResampler
import com.simplecalculator.app.data.stt.WindowBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCaptionTest {

    // --- windowing -----------------------------------------------------------

    private fun fill(value: Float, count: Int) = FloatArray(count) { value }

    @Test
    fun firstWindowIsShortAndTheNextOneCarriesLeadIn() {
        val buffer = WindowBuffer(
            sampleRate = 1_000,
            firstWindowMs = 1_000,
            windowMs = 2_000,
            overlapMs = 500
        )
        buffer.reset(0)
        buffer.append(fill(0.1f, 999), 999)
        assertNull("must wait for the first full window", buffer.poll())

        buffer.append(fill(0.1f, 1), 1)
        val first = buffer.poll()
        assertNotNull(first)
        assertEquals(0L, first!!.startMs)
        assertEquals(0L, first.cutMs)
        assertEquals(1_000L, first.endMs)
        assertEquals(1_000, first.pcm.size)
        assertTrue("speech window is not silent", first.rms > 0.05f)

        buffer.append(fill(0.1f, 1_500), 1_500)
        assertNull(buffer.poll())
        buffer.append(fill(0.1f, 500), 500)
        val second = buffer.poll()
        assertNotNull(second)
        assertEquals("600 ms of the previous window is replayed", 500L, second!!.startMs)
        assertEquals(1_000L, second.cutMs)
        assertEquals(3_000L, second.endMs)
        assertEquals(2_500, second.pcm.size)
    }

    @Test
    fun resetMovesTimestampsToTheSeekPosition() {
        val buffer = WindowBuffer(1_000, 1_000, 2_000, 500)
        buffer.reset(61_000)
        buffer.append(fill(0.2f, 1_000), 1_000)
        val window = buffer.poll()!!
        assertEquals(61_000L, window.startMs)
        assertEquals(61_000L, window.cutMs)
        assertEquals(62_000L, window.endMs)
    }

    @Test
    fun flushCutsTheTailAndKeepsTheBufferAligned() {
        val buffer = WindowBuffer(1_000, 1_200, 2_000, 500)
        buffer.reset(0)
        buffer.append(fill(0.1f, 1_000), 1_000)
        assertNull("1 s is too short to send to Whisper", buffer.flush())

        buffer.append(fill(0.1f, 300), 300)
        val tail = buffer.flush()
        assertNotNull(tail)
        assertEquals(0L, tail!!.startMs)
        assertEquals(1_300L, tail.endMs)

        buffer.append(fill(0.1f, 1_500), 1_500)
        assertNull(buffer.poll())
        buffer.append(fill(0.1f, 500), 500)
        val next = buffer.poll()!!
        assertEquals("the new window starts with the 0.5 s lead-in", 800L, next.startMs)
        assertEquals(1_300L, next.cutMs)
        assertEquals(3_300L, next.endMs)
    }

    @Test
    fun silenceHasLowRms() {
        val buffer = WindowBuffer(1_000, 1_000, 2_000, 500)
        buffer.reset(0)
        buffer.append(fill(0f, 1_000), 1_000)
        assertEquals(0f, buffer.poll()!!.rms, 0.0001f)
    }

    // --- resampling ----------------------------------------------------------

    @Test
    fun resamplerKeepsTheExactDurationAt48k() {
        val resampler = StreamResampler(48_000, 16_000)
        val input = fill(0.25f, 4_800)
        val out = FloatArray(2_000)
        var produced = 0
        repeat(10) {
            val count = resampler.process(input, input.size, out)
            for (index in 0 until count) assertEquals(0.25f, out[index], 0.0001f)
            produced += count
        }
        assertEquals(16_000, produced)
    }

    @Test
    fun resamplerDoesNotDriftAt44k1() {
        val resampler = StreamResampler(44_100, 16_000)
        val input = fill(0.5f, 441)
        val out = FloatArray(200)
        var produced = 0
        repeat(100) { produced += resampler.process(input, input.size, out) }
        assertEquals(16_000, produced)
    }

    @Test
    fun resamplerUpsamples8k() {
        val resampler = StreamResampler(8_000, 16_000)
        val input = fill(0.3f, 800)
        val out = FloatArray(2_000)
        var produced = 0
        repeat(10) { produced += resampler.process(input, input.size, out) }
        assertEquals(16_000, produced)
        assertEquals(0.3f, out[100], 0.0001f)
    }

    // --- filtering -----------------------------------------------------------

    @Test
    fun hallucinatedSilenceIsDropped() {
        assertNull(CaptionFilter.clean("[BLANK_AUDIO]"))
        assertNull(CaptionFilter.clean("(music)"))
        assertTrue(CaptionFilter.isKnownHallucination("Thank you.", 0.8f))
        assertTrue(!CaptionFilter.isKnownHallucination("Thank you.", 0.1f))
        assertNull(CaptionFilter.clean("♪ ♪"))
        assertNull(CaptionFilter.clean("I I I I I I"))
        assertNull(CaptionFilter.clean("you know you know you know"))
        assertNull(CaptionFilter.clean("   "))
    }

    @Test
    fun runawayDecoderLoopsAreDropped() {
        val loop = "Ask not what you want to ask. ".repeat(12)
        assertNull(CaptionFilter.clean(loop))
        assertTrue(CaptionFilter.isRunaway(CaptionFilter.normalize(loop)))
        assertNull(
            CaptionFilter.clean(
                "and so my fellow Americans. And so my fellow Americans. And..."
            )
        )
        val longButReal = (1..34).joinToString(" ") { "word$it" }
        assertNotNull(CaptionFilter.clean(longButReal))
    }

    @Test
    fun realSpeechSurvivesAndIsTidied() {
        assertEquals("Hello world.", CaptionFilter.clean("  Hello   world.  "))
        assertEquals("Thank you for the meal.", CaptionFilter.clean("Thank you for the meal."))
        assertEquals("We're going home.", CaptionFilter.clean("We're going home."))
    }

    @Test
    fun slowModelDetectionNeedsTwoStrikes() {
        val guard = com.simplecalculator.app.data.stt.SpeedGuard(limit = 1.2f, strikes = 2)
        assertTrue(!guard.sample(0.4f))
        assertTrue(!guard.sample(1.4f))
        assertTrue("one slow window is not enough", !guard.sample(0.5f))
        assertTrue(!guard.sample(1.4f))
        assertTrue(guard.sample(1.5f))
        guard.reset()
        assertTrue(!guard.sample(1.5f))
    }

    @Test
    fun repeatedOverlapIsTrimmedBetweenWindows() {
        val assembler = CaptionAssembler()
        val first = assembler.accept(
            listOf(Segment(0, 2_000, 0.1f, "the quick brown fox")),
            cutMs = 0
        )
        assertEquals("the quick brown fox", first.single().text)

        val second = assembler.accept(
            listOf(Segment(1_800, 3_000, 0.1f, "brown fox jumps over")),
            cutMs = 2_000
        )
        assertEquals("jumps over", second.single().text)

        val duplicate = assembler.accept(
            listOf(Segment(2_900, 4_000, 0.1f, "jumps over")),
            cutMs = 3_000
        )
        assertTrue("a decoder echo must not be shown twice", duplicate.isEmpty())
    }

    @Test
    fun oldCuesAreDroppedAndUncertainSpeechIsFiltered() {
        val assembler = CaptionAssembler()
        val kept = assembler.accept(
            listOf(
                Segment(500, 1_800, 0.1f, "already shown"),
                Segment(2_100, 3_000, 0.9f, "hallucinated"),
                Segment(2_200, 3_400, 0.1f, "new words")
            ),
            cutMs = 2_000
        )
        assertEquals(1, kept.size)
        assertEquals("new words", kept.single().text)
        assertEquals(2_200L, kept.single().startMs)
    }

    @Test
    fun absurdlyLongSegmentsAreDropped() {
        val assembler = CaptionAssembler()
        val kept = assembler.accept(
            listOf(Segment(0, 30_000, 0.0f, "a normal sounding sentence")),
            cutMs = 0
        )
        assertTrue("a 30 s segment cannot come from a 4 s window", kept.isEmpty())
    }

    @Test
    fun resetForgetsThePreviousWindow() {
        val assembler = CaptionAssembler()
        assembler.accept(listOf(Segment(0, 1_000, 0.1f, "before seek")), cutMs = 0)
        assembler.reset()
        val after = assembler.accept(
            listOf(Segment(0, 1_000, 0.1f, "after seek")),
            cutMs = 0
        )
        assertEquals("after seek", after.single().text)
    }
}
