package com.simplecalculator.app

import com.simplecalculator.app.data.stt.PcmDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmDecoderTest {

    private fun buffer(vararg bytes: Int): ByteBuffer {
        val b = ByteBuffer.allocate(bytes.size).order(ByteOrder.LITTLE_ENDIAN)
        bytes.forEach { b.put(it.toByte()) }
        b.flip()
        return b
    }

    @Test
    fun pcm16StereoAveragesChannels() {
        val pcm = buffer(
            0x00, 0x00, 0x00, 0x40, // 0, 16384 -> 0.25
            0x00, 0x80, 0x00, 0x80  // -32768, -32768 -> -1
        )
        val out = FloatArray(2)
        assertTrue(PcmDecoder.toMono(pcm, PcmDecoder.PCM_16BIT, 2, 2, out))
        assertEquals(0.25f, out[0], 0.001f)
        assertEquals(-1f, out[1], 0.001f)
    }

    @Test
    fun pcm24StereoIsSigned() {
        val pcm = buffer(
            0x00, 0x00, 0x40, 0x00, 0x00, 0xC0 // 0.5, -0.5 -> 0
        )
        val out = FloatArray(1)
        assertTrue(PcmDecoder.toMono(pcm, PcmDecoder.PCM_24BIT, 2, 1, out))
        assertEquals(0f, out[0], 0.001f)
    }

    @Test
    fun pcm32StereoIsSigned() {
        val pcm = buffer(
            0x00, 0x00, 0x00, 0x40, // 0.5
            0x00, 0x00, 0x00, 0xC0  // -0.5
        )
        val out = FloatArray(1)
        assertTrue(PcmDecoder.toMono(pcm, PcmDecoder.PCM_32BIT, 2, 1, out))
        assertEquals(0f, out[0], 0.001f)
    }

    @Test
    fun floatStereoIsClampedAndAveraged() {
        val pcm = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        pcm.putFloat(0.25f)
        pcm.putFloat(0.75f)
        pcm.flip()
        val out = FloatArray(1)
        assertTrue(PcmDecoder.toMono(pcm, PcmDecoder.PCM_FLOAT, 2, 1, out))
        assertEquals(0.5f, out[0], 0.001f)
    }

    @Test
    fun pcm8IsUnsignedCenteredAt128() {
        val pcm = buffer(128, 255)
        val out = FloatArray(2)
        assertTrue(PcmDecoder.toMono(pcm, PcmDecoder.PCM_8BIT, 1, 2, out))
        assertEquals(0f, out[0], 0.001f)
        assertEquals(0.992f, out[1], 0.01f)
    }

    @Test
    fun compressedEncodingsAreRejected() {
        assertFalse(PcmDecoder.toMono(buffer(0, 0), 0x0A, 1, 1, FloatArray(1)))
        assertEquals(0, PcmDecoder.bytesPerSample(0x0A))
    }
}
