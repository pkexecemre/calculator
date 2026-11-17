package com.simplecalculator.app.data.source.hentai

import com.simplecalculator.app.data.source.Category
import com.simplecalculator.app.data.source.CommentItem
import com.simplecalculator.app.data.source.ListingKind
import com.simplecalculator.app.data.source.PlayerMode
import com.simplecalculator.app.data.source.Source
import com.simplecalculator.app.data.source.Tags
import com.simplecalculator.app.data.source.VideoDetail
import com.simplecalculator.app.data.source.StreamVariant
import com.simplecalculator.app.data.source.VideoSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder

/**
 * Shared behaviour for hentai tube sites. Their players are JS-gated, so playback
 * runs in an isolated WebView (EmbedPlayerScreen) rather than Media3.
 */
abstract class HtmlHentaiSource(protected val http: OkHttpClient) : Source {

    override val category: Category = Category.HENTAI
    override val playerMode: PlayerMode = PlayerMode.EMBED
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
            .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
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
        tags: List<String> = emptyList(),
        durationSec: Int = 0
    ): VideoSummary? {
        val clean = title.trim()
        if (!Tags.isAllowed(tags, clean)) return null
        if (vkey.isBlank() || clean.length < 2 || isJunk(clean)) return null
        return VideoSummary(
            sourceId = id,
            vkey = vkey,
            title = clean,
            thumb = thumb,
            durationSec = durationSec,
            views = 0,
            uploaderSlug = null,
            uploaderName = null,
            verifiedUploader = false,
            category = Category.HENTAI,
            tags = Tags.normalize(tags)
        )
    }

    override suspend fun detail(vkey: String): VideoDetail = VideoDetail(
        embedUrl = if (vkey.startsWith("http")) vkey else base + vkey,
        playerMode = PlayerMode.EMBED,
        tags = emptyList()
    )

    override suspend fun comments(vkey: String, commentsUrl: String?, offset: Int): List<CommentItem> =
        emptyList()

    override suspend fun related(vkey: String, relatedUrl: String?): List<VideoSummary> =
        emptyList()

    protected fun thumbOf(element: Element): String {
        val img = element.selectFirst("img") ?: return ""
        val raw = img.attr("data-src").ifBlank { img.attr("src") }
            .ifBlank { img.attr("data-lazy-src") }
            .ifBlank { img.attr("data-original") }
        return absolute(raw)
    }

    protected fun absolute(url: String): String {
        if (url.isBlank()) return url
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:" + url
        if (url.startsWith("/")) return base + url
        return url
    }

    /** Section headings that some sites mark up as if they were cards. */
    private val junkTitles = setOf(
        "recently updated", "trending", "popular", "new releases", "latest",
        "browse", "view all", "see all", "more", "home"
    )

    protected fun isJunk(title: String): Boolean =
        title.trim().lowercase() in junkTitles

    /** Walks up until a card container that carries an image is found. */
    protected fun cardOf(link: Element, depth: Int = 4): Element {
        var node: Element? = link
        var steps = 0
        while (node != null && steps < depth) {
            if (node.selectFirst("img") != null) return node
            node = node.parent()
            steps++
        }
        return link.parent() ?: link
    }
}

class HentaiHavenSource(http: OkHttpClient) : HtmlHentaiSource(http) {

    override val id: String = "hh"
    override val displayName: String = "HentaiHaven"
    override val base: String = "https://hentaihaven.xxx"

    /** Their episode pages embed a plain <source src="…master.m3u8">, so playback
     *  is a real Media3 stream — no WebView, no site chrome. */
    override val playerMode: PlayerMode = PlayerMode.NATIVE

    override suspend fun detail(vkey: String): VideoDetail {
        val seriesHtml = runCatching { fetch(vkey) }.getOrElse { "" }
        val episodes = Jsoup.parse(seriesHtml)
            .select("a[href^=/watch/][href*=/episode-]")
            .map { it.attr("href").trimEnd('/') }
            .distinct()
        val chosen = episodes.maxByOrNull { episodeNumber(it) } ?: vkey
        val pageHtml = runCatching { fetch(chosen) }.getOrElse { "" }
        return VideoDetail(
            streams = parseEpisodeStreams(pageHtml),
            tags = emptyList(),
            embedUrl = null,
            playerMode = PlayerMode.NATIVE
        )
    }

    /** Pulls the playable sources out of an episode page. */
    internal fun parseEpisodeStreams(html: String): List<StreamVariant> {
        val doc = Jsoup.parse(html)
        return doc.select("source[src], source[data-src]").mapNotNull { source ->
            val url = source.attr("src").ifBlank { source.attr("data-src") }
            if (!url.startsWith("http")) return@mapNotNull null
            val hls = url.contains(".m3u8")
            StreamVariant(
                quality = if (hls) 1080 else 720,
                label = if (hls) "HLS" else "MP4",
                url = url,
                hls = hls
            )
        }.distinctBy { it.url }
    }

    private fun episodeNumber(path: String): Int {
        val marker = "/episode-"
        val index = path.lastIndexOf(marker)
        if (index < 0) return 0
        return path.substring(index + marker.length).takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }

    override suspend fun listing(
        kind: ListingKind,
        page: Int,
        query: String?,
        slug: String?
    ): List<VideoSummary> {
        val path = when (kind) {
            ListingKind.SEARCH -> "/browse/?q=" + URLEncoder.encode(query ?: "", "UTF-8")
            ListingKind.TRENDING -> if (page <= 1) "/" else "/browse/page/" + page + "/"
            ListingKind.STAR -> "/series/" + (slug ?: "") + "/"
        }
        val html = fetch(path)
        return parseListing(html)
    }

    internal fun parseListing(html: String): List<VideoSummary> {
        val doc = Jsoup.parse(html)
        val out = ArrayList<VideoSummary>()
        for (link in doc.select("a[href^=/watch/]")) {
            val href = link.attr("href").trimEnd('/')
            if (href.isEmpty() || href == "/watch") continue
            val card = cardOf(link)
            val img = card.selectFirst("img")
            val title = link.attr("title").ifBlank {
                card.selectFirst("h3, h2, .card-title, .title")?.text()
                    ?: img?.attr("alt").orEmpty()
                    ?: ""
            }.ifBlank { link.text() }
            val item = summary(vkey = href, title = title, thumb = thumbOf(card))
            if (item != null) out.add(item)
        }
        return out.distinctBy { it.vkey }
    }
}

class HentaiTvSource(http: OkHttpClient) : HtmlHentaiSource(http) {

    override val id: String = "htv"
    override val displayName: String = "Hentai.tv"
    override val base: String = "https://hentai.tv"

    override suspend fun listing(
        kind: ListingKind,
        page: Int,
        query: String?,
        slug: String?
    ): List<VideoSummary> {
        val path = when (kind) {
            ListingKind.SEARCH -> "/browse?q=" + URLEncoder.encode(query ?: "", "UTF-8")
            ListingKind.STAR -> "/series/" + (slug ?: "")
            ListingKind.TRENDING -> if (page <= 1) "/" else "/browse?page=" + page
        }
        return parseListing(fetch(path))
    }

    internal suspend fun parseListing(html: String): List<VideoSummary> {
        val doc = Jsoup.parse(html)
        val out = ArrayList<VideoSummary>()
        val links = doc.select("a[href^=/series/]").map { it.attr("href").trimEnd('/') }
            .filter { it.isNotEmpty() && it != "/series" }.distinct()
        for (href in links) {
            val title = href.substringAfterLast('/').replace('-', ' ')
                .split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            // Fetch the series page for its og:image poster. Sequential but bounded
            // by the page size (16 items max).
            val poster = runCatching {
                val detail = fetch(href)
                Regex("""og:image[^>]*content="([^"]+)"""").find(detail)?.groupValues?.get(1)
                    ?: Regex("""twitter:image[^>]*content="([^"]+)"""").find(detail)?.groupValues?.get(1)
                    ?: ""
            }.getOrDefault("")
            val item = summary(vkey = href, title = title, thumb = poster)
            if (item != null) out.add(item)
        }
        return out.distinctBy { it.vkey }
    }
}

class HentaiMamaSource(http: OkHttpClient) : HtmlHentaiSource(http) {

    override val id: String = "hm"
    override val displayName: String = "HentaiMama"
    override val base: String = "https://hentaimama.io"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun listing(
        kind: ListingKind,
        page: Int,
        query: String?,
        slug: String?
    ): List<VideoSummary> {
        val path = when (kind) {
            ListingKind.SEARCH -> "/?s=" + URLEncoder.encode(query ?: "", "UTF-8")
            ListingKind.TRENDING -> if (page <= 1) "/" else "/page/" + page + "/"
            ListingKind.STAR -> "/" + (slug ?: "")
        }
        return parseListing(fetch(path))
    }

    internal fun parseListing(html: String): List<VideoSummary> {
        val doc = Jsoup.parse(html)
        val out = ArrayList<VideoSummary>()
        // Cards: <article class="item" id="post-N"><div class="image"><a href="…">
        // <img src="…" alt="Title"></a>…<h3 class="title">Title</h3>
        for (article in doc.select("article.item, article")) {
            val link = article.selectFirst("div.image a[href]")
                ?: article.selectFirst("a[href]") ?: continue
            val href = link.attr("href")
            if (!href.contains("/tvshows/") && !href.contains("/hentai/")) continue
            val title = article.selectFirst("h3.title, .title, .entry-title")?.text()?.trim()
                ?: link.attr("title").ifBlank { "" }
            val item = summary(vkey = href, title = title, thumb = thumbOf(article))
            if (item != null) out.add(item)
        }
        return out.distinctBy { it.vkey }
    }
}
