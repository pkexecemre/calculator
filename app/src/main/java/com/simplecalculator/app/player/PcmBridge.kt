package com.simplecalculator.app.player

import java.nio.ByteBuffer

/**
 * Hand-off between the playback service (which taps PCM) and the player screen
 * (which owns the transcriber). Kept tiny on purpose: no context, no state.
 */
object PcmBridge {
    @Volatile
    var listener: ((ByteBuffer, Int, Int) -> Unit)? = null

    fun emit(buffer: ByteBuffer, sampleRate: Int, channels: Int) {
        listener?.invoke(buffer, sampleRate, channels)
    }
}
