package com.simplecalculator.app.data.stt

/** Thin JNI surface over whisper.cpp. All calls are synchronous and blocking. */
internal object WhisperBridge {

    init {
        System.loadLibrary("localstt")
    }

    external fun nativeInit(modelPath: String): Boolean

    external fun nativeFree()

    /**
     * Returns "startMs\tendMs\tnoSpeechProb\ttext" lines for one 16 kHz mono
     * chunk. [audioCtx] shortens Whisper's 30 s encoder context to match a short
     * window; 0 keeps the model default.
     */
    external fun nativeTranscribe(
        pcm: FloatArray,
        length: Int,
        translateToEnglish: Boolean,
        language: String?,
        threads: Int,
        initialPrompt: String?,
        audioCtx: Int
    ): String
}
