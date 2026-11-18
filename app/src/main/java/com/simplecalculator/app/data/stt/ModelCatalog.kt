package com.simplecalculator.app.data.stt

/**
 * Whisper models that run on a phone through whisper.cpp. Sizes are the q5
 * quantised files; accuracy and CPU cost grow with them.
 */
data class SttModel(
    val id: String,
    val label: String,
    val shortLabel: String,
    val file: String,
    val sizeMb: Int,
    val note: String,
    val bundled: Boolean = false
)

object ModelCatalog {

    val all: List<SttModel> = listOf(
        SttModel(
            id = "base",
            label = "Base (English)",
            shortLabel = "Base",
            file = "ggml-base.en-q5_1.bin",
            sizeMb = 56,
            note = "Ships inside the app. Fast, good for clear speech.",
            bundled = true
        ),
        SttModel(
            id = "small",
            label = "Small (English)",
            shortLabel = "Small",
            file = "ggml-small.en-q5_1.bin",
            sizeMb = 181,
            note = "Better with accents and background noise."
        ),
        SttModel(
            id = "large",
            label = "Large v3 Turbo (multilingual)",
            shortLabel = "Large Turbo",
            file = "ggml-large-v3-turbo-q5_0.bin",
            sizeMb = 547,
            note = "Most accurate. Heavy: needs a recent phone and a fast CPU."
        )
    )

    fun byId(id: String?): SttModel = all.firstOrNull { it.id == id } ?: all.first()

    /** Everything larger than the bundled model is downloaded on demand. */
    val downloadable: List<SttModel> get() = all

    private const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

    fun url(model: SttModel): String = "$BASE_URL/${model.file}"
}
