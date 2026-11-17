package com.simplecalculator.app.data.source.hentai

import com.simplecalculator.app.data.source.Category
import com.simplecalculator.app.data.source.CommentItem
import com.simplecalculator.app.data.source.HentaiFlavor
import com.simplecalculator.app.data.source.ListingKind
import com.simplecalculator.app.data.source.PlayerMode
import com.simplecalculator.app.data.source.Source
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
 * OHentai keeps the file next to the poster:
 *
 *   video_data/<Series>/<Episode>/Episode 2.jpg  (og:image)
 *   video_data/<Series>/<Episode>/Episode 2.mp4  (Range supported)
 *
 * The player URL itself is injected by JavaScript, so deriving the sibling MP4
 * is what makes this source playable natively.
 */
class OhentaiSource(private val http: OkHttpClient) : Source {

    override val id = "ohe"
    override val displayName = "OHentai"
    override val category = Category.HENTAI
    override val playerMode = PlayerMode.NATIVE
    override val supportsStars = false

    private companion object {
        const val BASE = "https://ohentai.org"
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    private suspend fun fetch(path: String): String = withContext(Dispatchers.IO) {
        val url = if (path.startsWith("http")) path else BASE + "/" + path.trimStart('/')
        val request = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            .header("Referer", BASE + "/")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            response.body?.string() ?: ""
        }
    }

    private suspend fun isPlayable(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Referer", BASE + "/")
                .header("Range", "bytes=0-1024")
                .build()
            http.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    override suspend fun listing(
        kind: ListingKind,
        page: Int,
        query: String?,
        slug: String?
    ): List<VideoSummary> {
        val path = when (kind) {
            ListingKind.SEARCH -> "/search.php?k=" + URLEncoder.encode(query.orEmpty(), "UTF-8")
            else -> if (page <= 1) "/" else "/?page=$page"
        }
        return parseListing(runCatching { fetch(path) }.getOrElse { return emptyList() })
    }

    internal fun parseListing(html: String): List<VideoSummary> {
        val doc = Jsoup.parse(html, BASE)
        val out = ArrayList<VideoSummary>()
        for (link in doc.select("a[href*=detail.php]")) {
            val href = link.attr("abs:href").ifBlank { link.attr("href") }
            if (href.isBlank()) continue
            val title = (link.attr("title").ifBlank { link.text() }).trim()
            if (title.length < 2) continue
            val card = link.parent() ?: link
            val img = card.selectFirst("img")
            val thumb = img?.attr("abs:src").orEmpty().ifBlank { img?.attr("abs:data-src").orEmpty() }
            out.add(
                VideoSummary(
                    sourceId = id,
                    vkey = href,
                    title = title,
                    thumb = thumb,
                    durationSec = 0,
                    views = 0,
                    uploaderSlug = null,
                    uploaderName = null,
                    verifiedUploader = false,
                    category = category,
                    flavor = HentaiFlavor.STUDIO
                )
            )
        }
        return out.distinctBy { it.vkey }
    }

    override suspend fun detail(vkey: String): VideoDetail {
        val page = runCatching { fetch(vkey) }.getOrNull()
            ?: return VideoDetail(playerMode = PlayerMode.EMBED, embedUrl = absolute(vkey))
        val poster = Regex("""property=["']og:image["']\s+content=["']([^"']+)["']""")
            .find(page)?.groupValues?.get(1)
            ?: Regex("""content=["']([^"']+\.jpg)["']\s+property=["']og:image["']""")
                .find(page)?.groupValues?.get(1)
        val candidates = buildList {
            if (poster != null) {
                // Posters are .jpg or .png; the video is the sibling .mp4.
                add(poster.replace(Regex("""\.(jpg|jpeg|png|webp)$""", RegexOption.IGNORE_CASE), ".mp4"))
            }
            // Fall back to any same-folder MP4 referenced in the page.
            addAll(Regex("""https?://[^"'\s<>]+?\.mp4""").findAll(page).map { it.value }.take(2))
        }.distinct()

        for (url in candidates) {
            val absolute = absolute(url)
            if (isPlayable(absolute)) {
                return VideoDetail(
                    streams = listOf(
                        StreamVariant(quality = 720, label = "720p", url = absolute, hls = false)
                    ),
                    playerMode = PlayerMode.NATIVE
                )
            }
        }
        // Poster/MP4 mismatch: let the site's own player handle it.
        return VideoDetail(playerMode = PlayerMode.EMBED, embedUrl = absolute(vkey))
    }

    private fun absolute(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> BASE + url
        else -> BASE + "/" + url
    }

    override suspend fun comments(vkey: String, commentsUrl: String?, offset: Int): List<CommentItem> =
        emptyList()

    override suspend fun related(vkey: String, relatedUrl: String?): List<VideoSummary> =
        emptyList()
}
