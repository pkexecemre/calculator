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

    /**
     * Downloads a model with resume support. The shared HTTP client has a 30 s
     * call timeout, which aborts any large file, so this uses its own client
     * without a total-call limit and keeps a `.part` file to continue from.
     */
    suspend fun download(model: SttModel, onProgress: (Int) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val target = fileFor(model)
            val part = File(dir, "${model.file}.part")
            var attempt = 0
            var lastError: String? = null

            while (attempt < MAX_ATTEMPTS) {
                attempt++
                val existing = if (part.exists()) part.length() else 0L
                val builder = Request.Builder().url(ModelCatalog.url(model))
                if (existing > 0) builder.header("Range", "bytes=$existing-")

                try {
                    downloadClient.newCall(builder.build()).execute().use { response ->
                        if (!response.isSuccessful) {
                            lastError = "HTTP ${response.code}"
                            return@use
                        }
                        val append = response.code == 206
                        if (!append && existing > 0) part.delete()

                        val total = totalBytes(response, if (append) existing else 0L)
                        val startAt = if (append) existing else 0L
                        val body = response.body ?: run {
                            lastError = "empty body"
                            return@use
                        }
                        body.byteStream().use { input ->
                            // Append mode keeps whatever a previous attempt wrote.
                            java.io.FileOutputStream(part, append).use { output ->
                                val buffer = ByteArray(512 * 1024)
                                var written = startAt
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    written += read
                                    if (total > 0) {
                                        onProgress(((written * 100) / total).toInt().coerceIn(0, 99))
                                    }
                                }
                                output.flush()
                            }
                        }
                        val size = part.length()
                        if (total > 0 && size < total) {
                            // Connection dropped early: keep the part and resume.
                            lastError = "incomplete ($size/$total)"
                        } else if (size > MIN_BYTES) {
                            target.delete()
                            if (part.renameTo(target)) {
                                onProgress(100)
                                return@withContext true
                            }
                            lastError = "could not finalize"
                        } else {
                            part.delete()
                            lastError = "file too small"
                        }
                    }
                } catch (e: Exception) {
                    lastError = e.message
                }
                kotlinx.coroutines.delay(1_000L * attempt)
            }
            android.util.Log.w("ModelStore", "download failed for ${model.id}: $lastError")
            false
        }

    /** Total size of the finished file, honouring Content-Range on resumed calls. */
    private fun totalBytes(response: okhttp3.Response, alreadyHave: Long): Long {
        val range = response.header("Content-Range")
        if (range != null) {
            range.substringAfter('/', "").trim().toLongOrNull()?.let { return it }
        }
        val length = response.body?.contentLength() ?: -1L
        return if (length > 0) length + alreadyHave else -1L
    }

    fun delete(model: SttModel) {
        if (model.bundled) return
        fileFor(model).delete()
    }

    fun sizeLabel(model: SttModel = selected()): String =
        "${model.sizeMb} MB"

    /** No total call timeout: big models legitimately take minutes. */
    private val downloadClient: OkHttpClient by lazy {
        http.newBuilder()
            .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private companion object {
        const val MIN_BYTES = 20L * 1024 * 1024
        const val MAX_ATTEMPTS = 8
    }
}
