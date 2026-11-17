package com.simplecalculator.app.data.source.hentai

import com.simplecalculator.app.data.source.ListingKind
import com.simplecalculator.app.data.source.PlayerMode
import com.simplecalculator.app.data.source.StreamVariant
import com.simplecalculator.app.data.source.VideoDetail
import com.simplecalculator.app.data.source.VideoSummary
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * HentaiCloud serves progressive MP4 files from a static path:
 *
 *   /media/videos/hd/<id>.mp4      205 MB, video/mp4, accepts Range
 *   /media/videos/iphone/<id>.mp4   97 MB
 *
 * No token, no redirector, no JS. That makes it a genuine native source: Media3
 * plays it and the download path works with the plain MP4 branch.
 */
class HentaiCloudSource(http: OkHttpClient) : HtmlHentaiSource(http) {

    override val id: String = "hc"
    override val displayName: String = "HentaiCloud"
    override val base: String = "https://www.hentaicloud.com"
    override val playerMode: PlayerMode = PlayerMode.NATIVE

    override suspend fun listing(
        kind: ListingKind,
        page: Int,
        query: String?,
        slug: String?
    ): List<VideoSummary> {
        val path = when (kind) {
            ListingKind.SEARCH -> "/search?search_type=videos&search_query=" +
                URLEncoder.encode(query ?: "", "UTF-8")
            ListingKind.STAR -> slug ?: "/videos"
            ListingKind.TRENDING -> if (page <= 1) "/videos" else "/videos?page=" + page
        }
        return parseListing(fetch(path))
    }

    internal fun parseListing(html: String): List<VideoSummary> {
        val doc = Jsoup.parse(html)
        val out = ArrayList<VideoSummary>()
        for (item in doc.select("div.video-item")) {
            val link = item.selectFirst("a[href^=/video/]") ?: continue
            val href = link.attr("href")
            val img = item.selectFirst("img")
            val title = img?.attr("title").orEmpty()
                .ifBlank { img?.attr("alt").orEmpty() }
                .ifBlank { link.attr("title") }
                .ifBlank { link.text() }
            val thumb = img?.attr("src").orEmpty().ifBlank { img?.attr("data-src").orEmpty() }
            val summary = summary(vkey = href, title = title, thumb = absolute(thumb))
            if (summary != null) out.add(summary)
        }
        if (out.isEmpty()) {
            for (link in doc.select("a[href^=/video/]")) {
                val href = link.attr("href")
                val img = cardOf(link).selectFirst("img")
                val title = img?.attr("title").orEmpty().ifBlank { link.text() }
                val summary = summary(vkey = href, title = title, thumb = thumbOf(cardOf(link)))
                if (summary != null) out.add(summary)
            }
        }
        return out.distinctBy { it.vkey }
    }

    override suspend fun detail(vkey: String): VideoDetail {
        val html = runCatching { fetch(vkey) }.getOrElse { "" }
        return VideoDetail(
            streams = parseStreams(html),
            tags = emptyList(),
            embedUrl = null,
            playerMode = PlayerMode.NATIVE
        )
    }

    internal fun parseStreams(html: String): List<StreamVariant> {
        val found = LinkedHashSet<String>()
        for (source in Jsoup.parse(html).select("source[src]")) {
            val url = source.attr("src")
            if (url.contains(".mp4") || url.contains(".m3u8")) found.add(url)
        }
        Regex("""https://[a-z0-9.-]*hentaicloud\.com/media/videos/(hd|iphone)/\d+\.mp4""")
            .findAll(html)
            .forEach { found.add(it.value) }

        return found.map { url ->
            val hd = url.contains("/hd/")
            StreamVariant(
                quality = if (hd) 1080 else 480,
                label = if (hd) "1080p" else "480p",
                url = url,
                hls = url.contains(".m3u8")
            )
        }
    }
}
