package com.simplecalculator.app.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.Locale

/**
 * Best-effort machine translation for titles and subtitle cues. Results are
 * cached on disk and in memory, and requests are throttled to two at a time.
 *
 * Titles are Japanese -> device language. Live captions are already English and
 * use a separate auto-detected source, so Turkish captions work for every
 * source language Google can identify.
 */
class Translator(
    context: Context,
    private val http: OkHttpClient
) {
    private val prefs = context.getSharedPreferences("translations", Context.MODE_PRIVATE)
    private val memory = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val gate = Semaphore(2)

    private val japanese = Regex("""[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff]""")

    val targetLanguage: String
        get() = if (Locale.getDefault().language.equals("tr", ignoreCase = true)) "tr" else "en"

    fun needsTranslation(text: String): Boolean =
        text.length > 1 && japanese.containsMatchIn(text)

    fun cached(text: String): String? = cachedFor(text, targetLanguage, TITLE_PREFIX)

    /** Translates a Whisper cue (English or source audio) into [to]. */
    suspend fun translateCaption(text: String, to: String = "tr"): String? {
        val source = text.trim()
        if (source.length < 2 || source.length > MAX_CAPTION_CHARS) return null
        if (to.isBlank() || to.equals("en", ignoreCase = true)) return null
        return cachedFor(source, to, CAPTION_PREFIX)
            ?: translateAndCache(source, from = "auto", to = to, prefix = CAPTION_PREFIX)
    }

    suspend fun translate(text: String): String? {
        if (!needsTranslation(text)) return null
        val to = targetLanguage
        return cachedFor(text, to, TITLE_PREFIX)
            ?: translateAndCache(text, from = "ja", to = to, prefix = TITLE_PREFIX)
    }

    private fun cachedFor(text: String, to: String, prefix: String): String? {
        val key = key(text, to, prefix)
        memory[key]?.let { return it }
        val stored = prefs.getString(key, null) ?: return null
        memory[key] = stored
        return stored
    }

    private suspend fun translateAndCache(
        text: String,
        from: String,
        to: String,
        prefix: String
    ): String? {
        val result = gate.withPermit { request(text, from, to) } ?: return null
        val clean = result.trim()
        if (clean.isEmpty() || clean.equals(text, ignoreCase = true)) return null
        if (prefs.all.size > 800) prefs.edit().clear().apply()
        val key = key(text, to, prefix)
        prefs.edit().putString(key, clean).apply()
        memory[key] = clean
        return clean
    }

    private suspend fun request(text: String, from: String, to: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://translate.googleapis.com/translate_a/single" +
                    "?client=gtx&sl=" + from + "&tl=" + to + "&dt=t&q=" +
                    java.net.URLEncoder.encode(text, "UTF-8")
                val request = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string() ?: return@use null
                    val root = JSONArray(body)
                    val segments = root.optJSONArray(0) ?: return@use null
                    buildString {
                        for (i in 0 until segments.length()) {
                            val part = segments.optJSONArray(i) ?: continue
                            append(part.optString(0, ""))
                        }
                    }
                }
            }.getOrNull()
        }

    private fun key(text: String, to: String, prefix: String): String =
        prefix + Integer.toHexString(text.hashCode()) + ":" + text.length + ":" + to

    private companion object {
        const val TITLE_PREFIX = "t:"
        const val CAPTION_PREFIX = "c:"
        const val MAX_CAPTION_CHARS = 600
    }
}
