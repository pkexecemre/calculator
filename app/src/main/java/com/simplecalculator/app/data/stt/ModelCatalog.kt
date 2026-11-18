package com.simplecalculator.app.data.stt

/**
 * Whisper models that run on a phone through whisper.cpp. Sizes are the q5
 * quantised files; accuracy and CPU cost grow with them. The multilingual
 * models are the useful default here: the app's catalogue is mostly Japanese
 * and European, and Whisper can translate that to English while transcribing
 * English just as well.
 */
data class SttModel(
    val id: String,
    val label: String,
    val shortLabel: String,
    val file: String,
    val sizeMb: Int,
    val note: String,
    val englishOnly: Boolean = false,
    val bundled: Boolean = false
)

object ModelCatalog {

    val all: List<SttModel> = listOf(
        SttModel(
            id = "base",
            label = "Base (multilingual)",
            shortLabel = "Base",
            file = "ggml-base-q5_1.bin",
            sizeMb = 57,
            note = "Ships inside the app. Fast; English speech is transcribed and other languages are translated to English.",
            bundled = true
        ),
        SttModel(
            id = "small",
            label = "Small (multilingual)",
            shortLabel = "Small",
            file = "ggml-small-q5_1.bin",
            sizeMb = 181,
            note = "Better with accents and background noise, still fast enough for live captions."
        ),
        SttModel(
            id = "large",
            label = "Large v3 Turbo (multilingual)",
            shortLabel = "Large Turbo",
            file = "ggml-large-v3-turbo-q5_0.bin",
            sizeMb = 547,
            note = "Most accurate. Heavy: live captions fall back to Base if it cannot keep up."
        )
    )

    fun byId(id: String?): SttModel = all.firstOrNull { it.id == id } ?: all.first()

    /** Everything larger than the bundled model is downloaded on demand. */
    val downloadable: List<SttModel> get() = all

    private const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

    fun url(model: SttModel): String = "${BASE_URL}/${model.file}"
}
