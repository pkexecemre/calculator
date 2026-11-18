package com.simplecalculator.app.data.stt

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts interleaved PCM of any encoding the audio sink may hand us into the
 * mono float samples Whisper expects. The player normalizes the tee to PCM16,
 * but keeping every PCM depth supported here makes the pipeline immune to a
 * decoder or sink changing format mid-video.
 */
internal object PcmDecoder {

    const val PCM_8BIT = 3
    const val PCM_16BIT = 2
    const val PCM_24BIT = 21
    const val PCM_32BIT = 22
    const val PCM_FLOAT = 4

    fun bytesPerSample(encoding: Int): Int = when (encoding) {
        PCM_8BIT -> 1
        PCM_16BIT -> 2
        PCM_24BIT -> 3
        PCM_32BIT -> 4
        PCM_FLOAT -> 4
        else -> 0
    }

    /**
     * Writes [frames] mono samples into [out]. Returns false for a non-PCM
     * encoding; the caller must not feed garbage to Whisper in that case.
     */
    fun toMono(
        pcm: ByteBuffer,
        encoding: Int,
        channels: Int,
        frames: Int,
        out: FloatArray
    ): Boolean {
        val buffer = pcm.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        when (encoding) {
            PCM_8BIT -> {
                for (frame in 0 until frames) {
                    var sum = 0f
                    for (channel in 0 until channels) {
                        sum += ((buffer.get().toInt() and 0xff) - 128) / 128f
                    }
                    out[frame] = sum / channels
                }
            }
            PCM_16BIT -> {
                for (frame in 0 until frames) {
                    var sum = 0f
                    for (channel in 0 until channels) sum += buffer.short / 32768f
                    out[frame] = sum / channels
                }
            }
            PCM_24BIT -> {
                for (frame in 0 until frames) {
                    var sum = 0f
                    for (channel in 0 until channels) {
                        val b0 = buffer.get().toInt() and 0xff
                        val b1 = buffer.get().toInt() and 0xff
                        val b2 = buffer.get().toInt() and 0xff
                        var value = b0 or (b1 shl 8) or (b2 shl 16)
                        if (value and 0x800000 != 0) value = value or -0x1000000
                        sum += value / 8388608f
                    }
                    out[frame] = sum / channels
                }
            }
            PCM_32BIT -> {
                for (frame in 0 until frames) {
                    var sum = 0f
                    for (channel in 0 until channels) sum += buffer.int / 2147483648f
                    out[frame] = sum / channels
                }
            }
            PCM_FLOAT -> {
                for (frame in 0 until frames) {
                    var sum = 0f
                    for (channel in 0 until channels) {
                        sum += buffer.float.coerceIn(-1f, 1f)
                    }
                    out[frame] = sum / channels
                }
            }
            else -> return false
        }
        return true
    }
}
