package com.simplecalculator.app.data.stt.lookahead

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioSink.ConfigurationException
import androidx.media3.exoplayer.audio.AudioSink.InitializationException
import androidx.media3.exoplayer.audio.AudioSink.WriteException
import java.nio.ByteBuffer

/**
 * An audio sink that plays nothing. It accepts decoded PCM as fast as the
 * decoder can produce it and hands every buffer to [onPcm] with its playback
 * timestamp, which is what lets a second player run far ahead of the video the
 * user is watching.
 *
 * Returning instantly from [handleBuffer] and reporting the buffered position
 * from [getCurrentPositionUs] means the renderer is never held back by a real
 * output device.
 */
@UnstableApi
class PcmCaptureSink(
    private val onPcm: (buffer: ByteBuffer, sampleRate: Int, channels: Int, positionMs: Long) -> Unit
) : AudioSink {

    private var listener: AudioSink.Listener? = null
    private var audioAttributes: AudioAttributes = AudioAttributes.DEFAULT
    private var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT
    private var sampleRate: Int = 44_100
    private var channelCount: Int = 2
    private var encoding: Int = C.ENCODING_PCM_16BIT
    private var positionUs: Long = 0L
    private var baseMs: Long = 0L
    private var elapsedMs: Long = 0L
    private var ended: Boolean = false
    private var skipSilence: Boolean = false
    private var volume: Float = 1f
    private var firstBuffer: Boolean = true

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    override fun supportsFormat(format: Format): Boolean = true

    override fun getFormatSupport(format: Format): Int = AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long = positionUs

    /** Timeline origin of this source; called on open/seek. */
    fun setBase(positionMs: Long) {
        baseMs = positionMs.coerceAtLeast(0L)
        elapsedMs = 0L
        positionUs = baseMs * 1_000L
    }

    @Throws(ConfigurationException::class)
    override fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        sampleRate = if (format.sampleRate > 0) format.sampleRate else 44_100
        channelCount = if (format.channelCount > 0) format.channelCount else 2
        encoding = if (format.pcmEncoding != Format.NO_VALUE) {
            format.pcmEncoding
        } else {
            C.ENCODING_PCM_16BIT
        }
        ended = false
        positionUs = 0L

    }

    override fun play() = Unit

    override fun handleDiscontinuity() = Unit

    @Throws(InitializationException::class, WriteException::class)
    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val bytesPerFrame = when (encoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> 2
        }
        val frames = buffer.remaining() / (bytesPerFrame * channelCount).coerceAtLeast(1)
        if (firstBuffer) {
            firstBuffer = false
            android.util.Log.i("PcmSink", "first buffer frames=" + frames)
        }
        // The renderer's timestamps are not reliable for every container; keep
        // our own clock from the requested start position instead.
        val positionMs = baseMs + elapsedMs
        onPcm(buffer, sampleRate, channelCount, positionMs)
        val spanMs = (frames.toLong() * 1_000L) / sampleRate.coerceAtLeast(1)
        elapsedMs += spanMs
        positionUs = (baseMs + elapsedMs) * 1_000L
        return true
    }

    @Throws(WriteException::class)
    override fun playToEndOfStream() {
        ended = true
    }

    override fun isEnded(): Boolean = ended && !hasPendingData()

    override fun hasPendingData(): Boolean = false

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        this.playbackParameters = playbackParameters
    }

    override fun getPlaybackParameters(): PlaybackParameters = playbackParameters

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        skipSilence = skipSilenceEnabled
    }

    override fun getSkipSilenceEnabled(): Boolean = skipSilence

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        this.audioAttributes = audioAttributes
    }

    override fun getAudioAttributes(): AudioAttributes = audioAttributes

    override fun setAudioSessionId(audioSessionId: Int) = Unit

    override fun setAuxEffectInfo(auxEffectInfo: androidx.media3.common.AuxEffectInfo) = Unit

    override fun enableTunnelingV21() = Unit

    override fun disableTunneling() = Unit

    override fun setVolume(volume: Float) {
        this.volume = volume
    }

    override fun pause() = Unit

    override fun flush() {
        positionUs = 0L
        ended = false
    }

    override fun reset() {
        positionUs = 0L
        ended = false
    }

    override fun release() = Unit
}
