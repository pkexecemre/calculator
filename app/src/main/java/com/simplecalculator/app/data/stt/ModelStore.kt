package com.simplecalculator.app.data.stt

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Downloads a ggml Whisper model once and keeps it in app storage. tiny.en
 * (~32 MB) is the default: it is accurate enough for speech and runs on a phone.
 */
class ModelStore(context: Context, private val http: OkHttpClient) {

    private val dir = File(context.filesDir, "stt").apply { mkdirs() }

    fun modelFile(): File = File(dir, FILE_NAME)

    fun isReady(): Boolean = modelFile().let { it.exists() && it.length() > MIN_BYTES }

    /** Streams the model to disk; [onProgress] is 0..1 or -1 when unknown. */
    suspend fun download(onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val target = modelFile()
        val partial = File(dir, "$FILE_NAME.part")
        runCatching {
            val request = Request.Builder().url(URL).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use false
                val total = response.body?.contentLength() ?: -1L
                response.body?.byteStream()?.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var written = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) onProgress(((written * 100) / total).toInt())
                        }
                    }
                }
                true
            }
        }.getOrDefault(false).also { ok ->
            if (ok && partial.length() > MIN_BYTES) {
                target.delete()
                partial.renameTo(target)
                onProgress(100)
            } else {
                partial.delete()
            }
        }
    }

    fun delete() {
        modelFile().delete()
    }

    fun sizeBytes(): Long = modelFile().length()

    private companion object {
        const val FILE_NAME = "ggml-tiny.en-q5_1.bin"
        const val URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$FILE_NAME"
        const val MIN_BYTES = 10L * 1024 * 1024
    }
}
