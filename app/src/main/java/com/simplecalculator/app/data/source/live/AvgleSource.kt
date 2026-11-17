package com.simplecalculator.app.data.source.live

import com.simplecalculator.app.data.source.CommentItem
import com.simplecalculator.app.data.source.ListingKind
import com.simplecalculator.app.data.source.PlayerMode
import com.simplecalculator.app.data.source.StreamVariant
import com.simplecalculator.app.data.source.VideoDetail
import com.simplecalculator.app.data.source.VideoSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLEncoder

/**
 * Avgle keeps the player URL behind a same-origin endpoint:
 *   playEndpoint = "/video_source.php?slug=…&fid=…&exp=…&token=…"
 * Fetching it returns { ok: true, url: "https://…mp4" }. Resolving that on the
 * device keeps playback native instead of falling back to a WebView.
 */
class AvgleSource(http: OkHttpClient) : HtmlLiveSource(http) {

    override val id = "avg"
    override val displayName = "Avgle"
    override val base = "https://avgle.net"

    override suspend fun listing(
        kind: ListingKind,
        page: Int,
        query: String?,
        slug: String?
    ): List<VideoSummary> {
        val path = when (kind) {
            ListingKind.SEARCH -> "/search.php?lang=ja&q=" +
                URLEncoder.encode(query ?: "", "UTF-8") + if (page > 1) "&page=$page" else ""
            else -> if (page <= 1) "/?lang=ja" else "/?lang=ja&page=$page"
        }
        return parseListing(fetch(path))
    }

    internal fun parseListing(html: String): List<VideoSummary> {
        val doc = Jsoup.parse(html, base)
        val out = ArrayList<VideoSummary>()
        for (link in doc.select("a[href*=/video.php]")) {
            val href = link.attr("abs:href").ifBlank { link.attr("href") }
            if (!href.contains("slug=")) continue
            val title = link.attr("title").ifBlank { link.text() }.trim()
            if (title.length < 2) continue
            val card = link.parent() ?: link
            val item = summary(href, title, thumbOf(card))
            if (item != null) out.add(item)
        }
        return out.distinctBy { it.vkey }
    }

    override suspend fun detail(vkey: String): VideoDetail {
        val html = runCatching { fetch(vkey) }.getOrElse { return VideoDetail(playerMode = PlayerMode.NATIVE) }
        return parseStreams(html, vkey)
    }

    internal suspend fun parseStreams(html: String, pageUrl: String): VideoDetail {
        val endpoint = Regex("""playEndpoint\s*=\s*["']([^"']+)["']""")
            .find(html)?.groupValues?.get(1) ?: return VideoDetail(playerMode = PlayerMode.NATIVE)
        val url = if (endpoint.startsWith("http")) endpoint else base + endpoint
        val raw = runCatching { getJson(url, pageUrl) }.getOrNull()
            ?: return VideoDetail(playerMode = PlayerMode.NATIVE)
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return VideoDetail(playerMode = PlayerMode.NATIVE)
        val media = (root["url"] as? JsonPrimitive)?.content ?: return VideoDetail(playerMode = PlayerMode.NATIVE)
        val hls = media.contains(".m3u8")
        return VideoDetail(
            streams = listOf(
                StreamVariant(
                    quality = if (hls) 1080 else 720,
                    label = if (hls) "HLS" else "720p",
                    url = media,
                    hls = hls
                )
            ),
            playerMode = PlayerMode.NATIVE
        )
    }

    private suspend fun getJson(url: String, referer: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", referer)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            response.body?.string() ?: ""
        }
    }

    override suspend fun comments(vkey: String, commentsUrl: String?, offset: Int): List<CommentItem> =
        emptyList()

    override suspend fun related(vkey: String, relatedUrl: String?): List<VideoSummary> =
        emptyList()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
}
