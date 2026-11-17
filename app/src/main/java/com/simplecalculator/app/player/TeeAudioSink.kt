package com.simplecalculator.app.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer

/**
 * Installs a tee in front of the audio sink so playback audio can be tapped for
 * live transcription without touching the stream or downloading the file.
 */
@UnstableApi
class TeeAudioSink(
    context: Context,
    private val onPcm: (ByteBuffer, Int, Int) -> Unit
) : DefaultRenderersFactory(context) {

    private val tee = TeeAudioProcessor(object : TeeAudioProcessor.AudioBufferSink {
        private var sampleRate = 48_000
        private var channels = 2

        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            sampleRate = sampleRateHz
            channels = channelCount
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            // The tee owns the buffer only for this call: copy before returning.
            val copy = ByteBuffer.allocate(buffer.remaining())
            copy.put(buffer.duplicate())
            copy.flip()
            onPcm(copy, sampleRate, channels)
        }
    })

    @Suppress("UNUSED_PARAMETER")
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink? = DefaultAudioSink.Builder(context)
        .setAudioProcessors(arrayOf(tee))
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .build()
}
