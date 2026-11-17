package com.simplecalculator.app.data.source.live

import com.simplecalculator.app.data.source.Category
import com.simplecalculator.app.data.source.CommentItem
import com.simplecalculator.app.data.source.ListingKind
import com.simplecalculator.app.data.source.PlayerMode
import com.simplecalculator.app.data.source.Source
import com.simplecalculator.app.data.source.StreamVariant
import com.simplecalculator.app.data.source.Tags
import com.simplecalculator.app.data.source.VideoDetail
import com.simplecalculator.app.data.source.VideoSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder

/**
 * Live-action tube sites that expose a playable URL straight in the page HTML.
 * No JS engine, no WebView: Media3 gets the URL and does the rest.
 */
abstract class HtmlLiveSource(protected val http: OkHttpClient) : Source {

    override val category: Category = Category.LIVE
    override val playerMode: PlayerMode = PlayerMode.NATIVE
    override val supportsStars: Boolean = false

    protected abstract val base: String

    protected open val userAgent: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    protected suspend fun fetch(path: String): String = withContext(Dispatchers.IO) {
        val url = if (path.startsWith("http")) path else base + path
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", base + "/")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP " + response.code + " " + url)
            response.body?.string() ?: ""
        }
    }

    protected fun summary(
        vkey: String,
        title: String,
        thumb: String,
        duration: Int = 0,
        tags: List<String> = emptyList()
    ): VideoSummary? {
        val clean = title.trim()
        if (vkey.isBlank() || clean.length < 2) return null
        if (!Tags.isAllowed(tags, clean)) return null
        return VideoSummary(
            sourceId = id,
            vkey = vkey,
            title = clean,
            thumb = thumb,
            durationSec = duration,
            views = 0,
            uploaderSlug = null,
            uploaderName = null,
            verifiedUploader = false,
            category = Category.LIVE
        )
    }

    protected fun absolute(url: String): String {
        if (url.isBlank()) return url
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:" + url
        if (url.startsWith("/")) return base + url
        return url
    }

    protected fun thumbOf(element: Element): String {
        val img = element.selectFirst("img") ?: return ""
        val raw = img.attr("data-src").ifBlank { img.attr("src") }
            .ifBlank { img.attr("data-original") }
            .ifBlank { img.attr("srcset").substringBefore(' ') }
        return absolute(raw)
    }

    protected fun clock(raw: String?): Int {
        if (raw.isNullOrBlank()) return 0
        val parts = raw.trim().split(":")
        if (parts.size < 2) return 0
        var seconds = 0
        for (part in parts) {
            val value = part.toIntOrNull() ?: return 0
            seconds = seconds * 60 + value
        }
        return seconds
    }

    override suspend fun comments(vkey: String, commentsUrl: String?, offset: Int): List<CommentItem> =
        emptyList()

    override suspend fun related(vkey: String, relatedUrl: String?): List<VideoSummary> =
        emptyList()
}

class XvideosSource(http: OkHttpClient) : HtmlLiveSource(http) {

    override val id: String = "xv"
    override val displayName: String = "XVideos"
    override val base: String = "https://www.xvideos.com"

    override suspend fun listing(
        kind: ListingKind,
        page: Int,
        query: String?,
        slug: String?
    ): List<VideoSummary> {
        val path = when (kind) {
            ListingKind.SEARCH -> "/?k=" + URLEncoder.encode(query ?: "", "UTF-8")
            else -> if (page <= 1) "/" else "/new/" + page
        }
        return parseListing(fetch(path))
    }

    internal fun parseListing(html: String): List<VideoSummary> {
        val doc = Jsoup.parse(html)
        val out = ArrayList<VideoSummary>()
        for (block in doc.select("div.thumb-block, div.mozaique div.thumb-inside")) {
            val link = block.selectFirst("a[href^=/video]") ?: continue
            val href = link.attr("href").trimEnd('/')
            val title = block.selectFirst("p.title a, a[id^=title]")?.text()
                ?: link.attr("title").ifBlank { "" }
            val duration = clock(block.selectFirst("span.duration, .duration")?.text())
            val item = summary(href, title, thumbOf(block), duration)
            if (item != null) out.add(item)
        }
        return out.distinctBy { it.vkey }
    }

    override suspend fun detail(vkey: String): VideoDetail {
        val html = runCatching { fetch(vkey) }.getOrElse { "" }
        return VideoDetail(streams = parseStreams(html), playerMode = PlayerMode.NATIVE)
    }

    internal fun parseStreams(html: String): List<StreamVariant> {
        val out = ArrayList<StreamVariant>()
        fun grab(marker: String): String? {
            val start = html.indexOf(marker)
            if (start < 0) return null
            val from = start + marker.length
            val quote = html.getOrNull(from) ?: return null
            if (quote != '\'' && quote != '"') return null
            val end = html.indexOf(quote, from + 1)
            if (end < 0) return null
            return html.substring(from + 1, end).takeIf { it.startsWith("http") }
        }
        grab("setVideoHLS(")?.let { out.add(StreamVariant(1000, "HLS", it, true)) }
        grab("setVideoUrlHigh(")?.let { out.add(StreamVariant(1080, "1080p", it, false)) }
        grab("setVideoUrlLow(")?.let { out.add(StreamVariant(480, "480p", it, false)) }
        return out.distinctBy { it.url }
    }
}

class EpornerSource(http: OkHttpClient) : HtmlLiveSource(http) {

    override val id: String = "ep"
    override val displayName: String = "Eporner"
    override val base: String = "https://www.eporner.com"

    override suspend fun listing(
        kind: ListingKind,
        page: Int,
        query: String?,
        slug: String?
    ): List<VideoSummary> {
        val path = when (kind) {
            ListingKind.SEARCH -> "/search/" + URLEncoder.encode(query ?: "", "UTF-8") + "/"
            else -> if (page <= 1) "/" else "/" + page + "/"
        }
        return parseListing(fetch(path))
    }

    internal fun parseListing(html: String): List<VideoSummary> {
        val doc = Jsoup.parse(html)
        val out = ArrayList<VideoSummary>()
        for (block in doc.select("div.mb")) {
            val link = block.selectFirst("a[href^=/video-]") ?: continue
            val href = link.attr("href").trimEnd('/')
            val title = block.selectFirst("p.mbtit a, p.mbtit")?.text()
                ?: link.selectFirst("img")?.attr("alt").orEmpty()
                ?: ""
            if (title.isBlank()) continue
            val duration = clock(block.selectFirst("span.mbtim")?.text())
            val item = summary(href, title, thumbOf(block), duration)
            if (item != null) out.add(item)
        }
        return out.distinctBy { it.vkey }
    }

    override suspend fun detail(vkey: String): VideoDetail {
        val html = runCatching { fetch(vkey) }.getOrElse { "" }
        return VideoDetail(streams = parseStreams(html), playerMode = PlayerMode.NATIVE)
    }

    internal fun parseStreams(html: String): List<StreamVariant> {
        val urls = Regex("""https://[a-z0-9.\-]*eporner\.com/[a-zA-Z0-9]+/[a-zA-Z0-9]+\.mp4""")
            .findAll(html).map { it.value }.distinct().toList()
        return urls.mapIndexed { index, url ->
            StreamVariant(quality = 1080 - index * 240, label = if (index == 0) "HD" else "SD", url = url, hls = false)
        }
    }
}
