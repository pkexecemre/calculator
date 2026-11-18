package com.simplecalculator.app.player

import android.util.Log
import java.nio.ByteBuffer

/**
 * Hand-off between the playback service (which taps PCM) and the player screen
 * (which owns the transcriber). Kept tiny on purpose: no context, no state.
 * The buffer is only valid for the duration of the callback; the consumer must
 * copy or convert it before returning.
 */
object PcmBridge {
    @Volatile
    var listener: ((ByteBuffer, Int, Int, Int) -> Unit)? = null

    fun emit(buffer: ByteBuffer, sampleRate: Int, channels: Int, encoding: Int) {
        val sink = listener ?: return
        // Caption bugs must never take playback down with them.
        try {
            sink(buffer, sampleRate, channels, encoding)
        } catch (t: Throwable) {
            Log.e("PcmBridge", "audio tap failed", t)
        }
    }
}
