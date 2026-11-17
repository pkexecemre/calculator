package com.simplecalculator.app.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Best-effort JA -> EN/TR translation for titles. Results are cached on disk so a
 * title is fetched once, and requests are throttled to two at a time.
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

    fun cached(text: String): String? = memory[text] ?: prefs.getString(key(text), null)

    suspend fun translate(text: String): String? {
        if (!needsTranslation(text)) return null
        cached(text)?.let { return it }
        val lang = targetLanguage
        val result = gate.withPermit { request(text, lang) } ?: return null
        val clean = result.trim()
        if (clean.isEmpty() || clean.equals(text, ignoreCase = true)) return null
        if (prefs.all.size > 800) prefs.edit().clear().apply()
        prefs.edit().putString(key(text), clean).apply()
        memory[text] = clean
        return clean
    }

    private suspend fun request(text: String, lang: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://translate.googleapis.com/translate_a/single" +
                "?client=gtx&sl=ja&tl=$lang&dt=t&q=" + java.net.URLEncoder.encode(text, "UTF-8")
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

    private fun key(text: String) = Integer.toHexString(text.hashCode()) + ":" + text.length
}
