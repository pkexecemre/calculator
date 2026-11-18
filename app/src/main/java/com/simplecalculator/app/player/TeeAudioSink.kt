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
    private val onPcm: (ByteBuffer, Int, Int, Int) -> Unit
) : DefaultRenderersFactory(context) {

    private val tee = TeeAudioProcessor(object : TeeAudioProcessor.AudioBufferSink {
        private var sampleRate = 48_000
        private var channels = 2
        private var encoding = 2 // C.ENCODING_PCM_16BIT

        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            sampleRate = sampleRateHz
            channels = channelCount
            this.encoding = encoding
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            // The consumer reads the buffer synchronously before returning, so
            // there is no need to allocate a copy for every audio callback.
            onPcm(buffer, sampleRate, channels, encoding)
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
