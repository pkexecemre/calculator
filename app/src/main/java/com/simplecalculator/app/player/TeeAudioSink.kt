package com.simplecalculator.app.player

import android.content.Context
import androidx.media3.common.audio.ToInt16PcmAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer

/**
 * Installs a tee in front of the audio sink so playback audio can be tapped for
 * live transcription without touching the stream or downloading the file.
 *
 * The tee is deliberately placed after [ToInt16PcmAudioProcessor]: whatever the
 * decoder emits (float, 24-bit, 32-bit, 8-bit) is normalized to little-endian
 * PCM16 before the caption pipeline sees it. Passthrough is disabled too, so a
 * Dolby/AC3 track cannot reach the tee as compressed bytes.
 */
@UnstableApi
class TeeAudioSink(
    context: Context,
    private val onPcm: (ByteBuffer, Int, Int, Int) -> Unit
) : DefaultRenderersFactory(context) {

    private val toPcm16 = ToInt16PcmAudioProcessor()

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
        .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
        .setAudioProcessors(arrayOf(toPcm16, tee))
        .setEnableFloatOutput(false)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .build()
}
