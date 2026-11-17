package com.simplecalculator.app.data.source.live

import com.simplecalculator.app.data.source.CommentItem
import com.simplecalculator.app.data.source.ListingKind
import com.simplecalculator.app.data.source.PlayerMode
import com.simplecalculator.app.data.source.StreamVariant
import com.simplecalculator.app.data.source.VideoDetail
import com.simplecalculator.app.data.source.VideoSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLEncoder

/**
 * YouJizz publishes the playable file as `data-clip` on the card itself, so the
 * signed MP4 is captured while listing and handed to the player later.
 */
class YouJizzSource(http: OkHttpClient) : HtmlLiveSource(http) {

    override val id = "yj"
    override val displayName = "YouJizz"
    override val base = "https://www.youjizz.com"

    private val clips = java.util.concurrent.ConcurrentHashMap<String, String>()

    override suspend fun listing(
        kind: ListingKind,
        page: Int,
        query: String?,
        slug: String?
    ): List<VideoSummary> {
        val path = when (kind) {
            ListingKind.SEARCH -> "/search/?q=" + URLEncoder.encode(query ?: "", "UTF-8")
            else -> if (page <= 1) "/" else "/most-popular/$page.html"
        }
        return parseListing(fetch(path))
    }

    internal fun parseListing(html: String): List<VideoSummary> {
        val doc = Jsoup.parse(html, base)
        val out = ArrayList<VideoSummary>()
        for (card in doc.select("div.video-thumb")) {
            val link = card.selectFirst("a.frame.video[href]") ?: continue
            val href = link.attr("href").trimEnd('/')
            if (!href.contains("/videos/")) continue
            val img = card.selectFirst("img")
            val title = img?.attr("alt").orEmpty()
                .ifBlank { link.attr("title") }
                .ifBlank { href.substringAfterLast('/') }
                .trim()
            if (title.length < 2) continue
            val clip = card.selectFirst("[data-clip]")?.attr("data-clip").orEmpty()
            if (clip.isNotBlank()) clips[href] = decodeAmp(clip)

            val item = summary(
                vkey = href,
                title = title,
                thumb = img?.let { el ->
                    el.attr("abs:data-original").ifBlank { el.attr("abs:data-src") }
                        .ifBlank { el.attr("abs:src") }
                }.orEmpty(),
                duration = clock(card.selectFirst("span.time, .duration")?.text())
            )
            if (item != null) out.add(item)
        }
        return out.distinctBy { it.vkey }
    }

    override suspend fun detail(vkey: String): VideoDetail {
        // Prefer the card's signed clip; refresh the page when it expired/lost.
        val direct = clips[vkey]?.let(::absolute)
        if (direct != null && direct.isNotBlank()) {
            return VideoDetail(
                streams = listOf(StreamVariant(quality = 720, label = "720p", url = direct, hls = false)),
                playerMode = PlayerMode.NATIVE
            )
        }
        val page = withContext(Dispatchers.IO) {
            val request = Request.Builder().url(if (vkey.startsWith("http")) vkey else base + vkey)
                .header("User-Agent", userAgent)
                .header("Referer", base + "/")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                response.body?.string() ?: ""
            }
        }
        val clip = Jsoup.parse(page, base)
            .selectFirst("[data-clip]")?.attr("data-clip").orEmpty()
        if (clip.isBlank()) return VideoDetail(playerMode = PlayerMode.EMBED, embedUrl = base + vkey)
        return VideoDetail(
            streams = listOf(
                StreamVariant(quality = 720, label = "720p", url = decodeAmp(clip), hls = false)
            ),
            playerMode = PlayerMode.NATIVE
        )
    }

    private fun decodeAmp(raw: String): String =
        absolute(raw.trim().replace("&amp;", "&"))

    override suspend fun comments(vkey: String, commentsUrl: String?, offset: Int): List<CommentItem> =
        emptyList()

    override suspend fun related(vkey: String, relatedUrl: String?): List<VideoSummary> =
        emptyList()
}
