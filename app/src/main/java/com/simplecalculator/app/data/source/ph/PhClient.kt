package com.simplecalculator.app.data.source.ph

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Every request carries the desktop-ish mobile UA plus the age cookie; without
 * both the site serves the interstitial instead of the page we need to parse.
 */
class PhClient(private val http: OkHttpClient) {

    suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val url = if (path.startsWith("http")) path else BASE + path
        var lastError: Exception? = null
        for (attempt in 0 until 2) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Cookie", "age_verified=1; platform=pc; accessAgeDisclaimerPH=1")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Referer", BASE + "/")
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP " + response.code + " for " + url)
                    }
                    return@withContext response.body?.string() ?: ""
                }
            } catch (e: IOException) {
                lastError = e
                if (attempt == 1) break
            }
        }
        throw lastError ?: IOException("request failed")
    }

    suspend fun getWithXhr(path: String, referer: String?): String = withContext(Dispatchers.IO) {
        val url = if (path.startsWith("http")) path else BASE + path
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Cookie", "age_verified=1; platform=pc; accessAgeDisclaimerPH=1")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", referer ?: (BASE + "/"))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP " + response.code)
            response.body?.string() ?: ""
        }
    }

    companion object {
        const val BASE = "https://www.pornhub.com"
        const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
