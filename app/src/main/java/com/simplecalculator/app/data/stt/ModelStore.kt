package com.simplecalculator.app.data.stt

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Keeps one Whisper model on disk. The base model ships inside the APK, so a
 * fresh install can caption immediately; bigger models are downloaded once.
 */
class ModelStore(context: Context, private val http: OkHttpClient) {

    private val dir = File(context.filesDir, "stt").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("stt", Context.MODE_PRIVATE)
    private val assets = context.assets

    fun selected(): SttModel = ModelCatalog.byId(prefs.getString("model_id", "base"))

    fun select(model: SttModel) = prefs.edit().putString("model_id", model.id).apply()

    fun fileFor(model: SttModel): File = File(dir, model.file)

    fun isReady(model: SttModel = selected()): Boolean =
        fileFor(model).let { it.exists() && it.length() > MIN_BYTES }

    /** Copies the bundled model out of the APK on first use. */
    suspend fun ensureBundled(): Boolean = withContext(Dispatchers.IO) {
        val model = ModelCatalog.all.firstOrNull { it.bundled } ?: return@withContext false
        val target = fileFor(model)
        if (target.exists() && target.length() > MIN_BYTES) return@withContext true
        runCatching {
            assets.open("models/${model.file}").use { input ->
                target.outputStream().use { output -> input.copyTo(output, 512 * 1024) }
            }
            target.length() > MIN_BYTES
        }.getOrDefault(false)
    }

    /** Streams a model to disk; [onProgress] is 0..100. */
    suspend fun download(model: SttModel, onProgress: (Int) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val target = fileFor(model)
            val partial = File(dir, "${model.file}.part")
            runCatching {
                val request = Request.Builder().url(ModelCatalog.url(model)).build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use false
                    val total = response.body?.contentLength() ?: -1L
                    response.body?.byteStream()?.use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(512 * 1024)
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

    fun delete(model: SttModel) {
        if (model.bundled) return
        fileFor(model).delete()
    }

    fun sizeLabel(model: SttModel = selected()): String =
        "${model.sizeMb} MB"

    private companion object {
        const val MIN_BYTES = 20L * 1024 * 1024
    }
}
