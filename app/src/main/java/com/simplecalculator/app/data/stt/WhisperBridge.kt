package com.simplecalculator.app.data.stt

/** Thin JNI surface over whisper.cpp. All calls are synchronous and blocking. */
internal object WhisperBridge {

    init {
        System.loadLibrary("localstt")
    }

    external fun nativeInit(modelPath: String): Boolean

    external fun nativeFree()

    /** Returns "startMs|endMs|text" lines for one 16 kHz mono chunk. */
    external fun nativeTranscribe(pcm: FloatArray, length: Int, translateToEnglish: Boolean): String
}
