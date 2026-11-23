package com.simplecalculator.app.data.source.ph

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * One page from Pornhub. [stale] is true when the network path failed and the
 * body came from the on-disk HTTP cache instead, so the UI can say "showing
 * saved videos" instead of pretending the request succeeded.
 */
data class PhPage(val body: String, val stale: Boolean = false)

class PhHttpException(
    message: String,
    val code: Int,
    val retryAfterMs: Long? = null
) : IOException(message)

/**
 * Retry policy kept pure so it can be unit-tested without a server.
 * Only transient failures are retried; a 403/404 is a real answer and retrying
 * it just burns the viewer's battery.
 */
object PhRetryPolicy {
    const val MAX_ATTEMPTS = 3

    fun isRetryable(code: Int): Boolean =
        code == 408 || code == 425 || code == 429 || code in 500..599

    fun backoffMs(attempt: Int, retryAfterMs: Long? = null): Long {
        if (retryAfterMs != null) return retryAfterMs.coerceIn(0L, 5_000L)
        val base = 300L shl attempt.coerceIn(0, 4)
        return base + Random.nextLong(0L, 250L)
    }
}

/**
 * Every request carries the mobile UA plus the age cookie; without both the
 * site serves an interstitial instead of the page we need to parse.
 *
 * Reliability:
 *  - transient IO/HTTP failures are retried three times with jittered backoff;
 *  - Retry-After is honoured (capped at 5 s so a feed never hangs for minutes);
 *  - if the network stays down, the same request is served from the OkHttp disk
 *    cache when possible and flagged as stale.
 */
class PhClient(private val http: OkHttpClient) {

    suspend fun get(path: String): PhPage = fetch(request(absolute(path), xhr = false, referer = null))

    suspend fun getWithXhr(path: String, referer: String?): PhPage =
        fetch(request(absolute(path), xhr = true, referer = referer))

    private suspend fun fetch(request: Request): PhPage {
        var lastError: IOException? = null
        var retryAfterMs: Long? = null

        for (attempt in 0 until PhRetryPolicy.MAX_ATTEMPTS) {
            try {
                return execute(request)
            } catch (e: PhHttpException) {
                lastError = e
                if (!PhRetryPolicy.isRetryable(e.code)) throw e
                retryAfterMs = e.retryAfterMs
            } catch (e: IOException) {
                lastError = e
            }
            if (attempt < PhRetryPolicy.MAX_ATTEMPTS - 1) {
                delay(PhRetryPolicy.backoffMs(attempt, retryAfterMs))
            }
        }

        return staleBody(request) ?: throw lastError ?: IOException("request failed")
    }

    private suspend fun execute(request: Request): PhPage = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw PhHttpException(
                    "HTTP " + response.code + " for " + request.url,
                    response.code,
                    retryAfter(response.header("Retry-After"))
                )
            }
            val body = response.body?.string() ?: ""
            PhPage(body, stale = false)
        }
    }

    /**
     * OkHttp answers 504 when a cache-only request has no entry, so a failure
     * here simply means "no offline copy"; the original exception is what the
     * caller should see.
     */
    private suspend fun staleBody(request: Request): PhPage? = withContext(Dispatchers.IO) {
        runCatching {
            val cacheOnly = request.newBuilder()
                .cacheControl(
                    CacheControl.Builder()
                        .onlyIfCached()
                        .maxStale(7, TimeUnit.DAYS)
                        .build()
                )
                .build()
            http.newCall(cacheOnly).execute().use { response ->
                if (response.isSuccessful) {
                    PhPage(response.body?.string().orEmpty(), stale = true)
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun request(url: String, xhr: Boolean, referer: String?): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Cookie", COOKIE)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", referer ?: (BASE + "/"))
        if (xhr) {
            builder
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("X-Requested-With", "XMLHttpRequest")
        } else {
            builder.header(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
        }
        return builder.build()
    }

    private fun absolute(path: String): String =
        if (path.startsWith("http")) path else BASE + path

    private fun retryAfter(raw: String?): Long? {
        val seconds = raw?.trim()?.toLongOrNull() ?: return null
        return seconds.coerceIn(0L, 300L) * 1_000L
    }

    companion object {
        const val BASE = "https://www.pornhub.com"
        const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        const val COOKIE = "age_verified=1; platform=pc; accessAgeDisclaimerPH=1"
    }
}
