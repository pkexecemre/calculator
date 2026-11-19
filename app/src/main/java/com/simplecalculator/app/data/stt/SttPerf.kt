package com.simplecalculator.app.data.stt

/**
 * How fast each model runs on this device, in real-time factor (inference
 * seconds per audio second). Kept for the process so the next session starts on
 * a model the phone can keep up with instead of repeating "lag, then demote".
 */
internal object SttPerf {

    private val rtf = java.util.concurrent.ConcurrentHashMap<String, Float>()

    fun record(modelId: String, value: Float) {
        if (value <= 0f) return
        val previous = rtf[modelId]
        rtf[modelId] = if (previous == null) value else previous * 0.6f + value * 0.4f
    }

    fun rtfFor(modelId: String): Float = rtf[modelId] ?: 0f
}
