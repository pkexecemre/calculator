package com.simplecalculator.app.data.source.live

import com.simplecalculator.app.data.source.ListingKind
import com.simplecalculator.app.data.source.PlayerMode
import com.simplecalculator.app.data.source.StreamVariant
import com.simplecalculator.app.data.source.VideoDetail
import com.simplecalculator.app.data.source.VideoSummary
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Kernel Video Sharing sites embed their sources as a JavaScript object with
 * single-quoted values:
 *
 *   video_url: 'https://…/get_file/18/…/123.mp4/'
 *   video_alt_url: 'https://…/get_file/19/…/123_720.mp4/'
 */
private fun Element.card(depth: Int = 4): Element {
    var node: Element? = this
    var steps = 0
    while (node != null && steps < depth) {
        if (node.selectFirst("img") != null) return node
        node = node.parent()
        steps++
    }
    return this.parent() ?: this
}

open class KvsSiteSource(
    http: OkHttpClient,
    override val id: String,
    override val displayName: String,
    override val base: String,
    private val listingPath: String = "/latest-updates/"
) : HtmlLiveSource(http) {

    override suspend fun listing(
        kind: ListingKind,
        page: Int,
        query: String?,
        slug: String?
    ): List<VideoSummary> {
        val path = when (kind) {
            ListingKind.SEARCH -> "/search/?q=" + URLEncoder.encode(query ?: "", "UTF-8")
            else -> listingPath + (if (page <= 1) "" else page.toString() + "/")
        }
        return parseListing(fetch(path))
    }

    internal fun parseListing(html: String): List<VideoSummary> {
        val doc = Jsoup.parse(html)
        val out = ArrayList<VideoSummary>()
        for (link in doc.select("a[href*=/videos/]")) {
            val raw = link.attr("href").trimEnd('/')
            val href = if (raw.startsWith("http")) {
                raw.substringAfter(base.removeSuffix("/"), raw)
            } else {
                raw
            }
            if (href.count { it == '/' } < 3) continue
            val card = link.card()
            val img = card.selectFirst("img")
            val title = img?.attr("alt").orEmpty().ifBlank { link.attr("title") }
                .ifBlank { card.selectFirst("strong.title, .title")?.text().orEmpty() }
                .ifBlank { link.text() }
            val duration = clock(card.selectFirst("span.duration, .duration")?.text())
            val item = summary(href, title, thumbOf(card), duration)
            if (item != null) out.add(item)
        }
        return out.distinctBy { it.vkey }
    }

    override suspend fun detail(vkey: String): VideoDetail {
        // KVS sites answer 404 when the trailing slash is missing, and a silent
        // empty result is worse than a visible failure.
        val path = if (vkey.endsWith("/")) vkey else "$vkey/"
        val html = fetch(path)
        var streams = parseStreams(html)
        if (streams.isEmpty()) {
            // KVS sites keep the real file URLs on a separate embed page.
            val videoId = Regex("""/video/(\\d+)""").find(path)?.groupValues?.get(1)
            if (videoId != null) {
                val embed = runCatching { fetch("/embed/$videoId/") }.getOrElse { "" }
                streams = parseStreams(embed)
            }
        }
        return VideoDetail(streams = streams, playerMode = PlayerMode.NATIVE)
    }

    internal fun parseStreams(html: String): List<StreamVariant> {
        val out = ArrayList<StreamVariant>()
        listOf("video_url", "video_alt_url", "video_url_hd").forEach { key ->
            val match = Regex(key + """\s*:\s*['"]([^'"]+)['"]""").find(html)
            val url = match?.groupValues?.get(1)?.takeIf { it.startsWith("http") } ?: return@forEach
            val quality = when (key) {
                "video_alt_url" -> 1080
                "video_url_hd" -> 1080
                else -> 480
            }
            out.add(
                StreamVariant(
                    quality = quality,
                    label = if (quality > 720) "1080p" else "540p",
                    url = url,
                    hls = false
                )
            )
        }
        return out.distinctBy { it.url }
    }
}

/** hdsex.org publishes an HLS playlist per quality right in the page. */
class HdsexSource(http: OkHttpClient) : HtmlLiveSource(http) {

    override val id: String = "hs"
    override val displayName: String = "hdsex.org"
    override val base: String = "https://hdsex.org"

    override suspend fun listing(
        kind: ListingKind,
        page: Int,
        query: String?,
        slug: String?
    ): List<VideoSummary> {
        // Only the front page answers 200; /latest/N returns 404 with the same
        // body, so keep to "/" and let the client dedupe.
        val path = when (kind) {
            ListingKind.SEARCH -> "/search?q=" + URLEncoder.encode(query ?: "", "UTF-8")
            else -> "/"
        }
        return parseListing(fetch(path))
    }

    internal fun parseListing(html: String): List<VideoSummary> {
        val doc = Jsoup.parse(html)
        val out = ArrayList<VideoSummary>()
        for (link in doc.select("a[href^=/video/]")) {
            val href = link.attr("href").trimEnd('/')
            if (href == "/video") continue
            val card = link.card()
            val img = card.selectFirst("img")
            val title = img?.attr("alt").orEmpty().ifBlank { link.attr("title") }
                .ifBlank { card.selectFirst(".title, h3")?.text().orEmpty() }
            val duration = clock(card.selectFirst(".duration, span.time")?.text())
            val item = summary(href, title, thumbOf(card), duration)
            if (item != null) out.add(item)
        }
        return out.distinctBy { it.vkey }
    }

    override suspend fun detail(vkey: String): VideoDetail {
        val html = runCatching { fetch(vkey) }.getOrElse { "" }
        return VideoDetail(streams = parseStreams(html), playerMode = PlayerMode.NATIVE)
    }

    internal fun parseStreams(html: String): List<StreamVariant> {
        val urls = Regex("""https?://[^"'\s\\]+\.m3u8[^"'\s\\]*""").findAll(html)
            .map { it.value.replace("&amp;", "&") }
            .distinct().toList()
        return urls.map { url ->
            val quality = when {
                url.contains("1080") -> 1080
                url.contains("720") -> 720
                url.contains("480") -> 480
                else -> 500
            }
            StreamVariant(quality = quality, label = quality.toString() + "p", url = url, hls = true)
        }
    }
}