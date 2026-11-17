package com.simplecalculator.app.data.source.generic

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
import kotlin.math.max

/**
 * MissAV hides the HLS playlist in a packed JS blob, and Cloudflare answers 403
 * to some networks. Two things make it work natively:
 *
 *  - mirror fallback between missav.ws and missav123.com;
 *  - a small unpacker for the `eval(function(p,a,c,k,e,d)…` wrapper, which
 *    reveals `source=…/playlist.m3u8` plus per-quality `…/720p/video.m3u8`.
 */
class MissAvSource(private val http: OkHttpClient) : Source {

    override val id = "msav"
    override val displayName = "MISSAV"
    override val category = Category.JAV
    override val playerMode = PlayerMode.NATIVE
    override val supportsStars = false

    private companion object {
        // missav.ws answers 403 on many networks; the 123 mirror is the reliable one.
        val BASES = listOf("https://missav123.com", "https://missav.ws")
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /** Card links look like /ja/mxgs-1455; these are navigation instead. */
        val NAV = setOf(
            "actresses", "genres", "makers", "search", "vip", "login", "signup",
            "history", "new", "release", "uncensored", "chinese-subtitle", "english-subtitle"
        )
        val CARD_PATH = Regex("""/ja/([a-z0-9]+-[a-z0-9]+)/?$""")
        val CODE_ONLY = Regex("""(?i)^[a-z]{2,6}-?\d{2,5}$""")
        val MEDIA = Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*""")
        const val MARKER = "eval(function(p,a,c,k,e,d)"
        const val DIGITS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    }

    private var activeBase: String? = null

    private suspend fun fetch(path: String): String = withContext(Dispatchers.IO) {
        val bases = activeBase?.let { listOf(it) + BASES.filter { b -> b != it } } ?: BASES
        var last: Exception? = null
        for (base in bases) {
            val url = if (path.startsWith("http")) path else base + path
            try {
                val request = Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                    .header("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
                    .header("Referer", base + "/")
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
                    activeBase = base
                    return@withContext response.body?.string() ?: ""
                }
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("no mirror answered")
    }

    override suspend fun listing(
        kind: ListingKind,
        page: Int,
        query: String?,
        slug: String?
    ): List<VideoSummary> {
        val path = when (kind) {
            ListingKind.SEARCH -> "/ja/search/" + URLEncoder.encode(query.orEmpty(), "UTF-8") +
                if (page > 1) "?page=$page" else ""
            else -> if (page <= 1) "/ja" else "/ja?page=$page"
        }
        val html = fetch(path)
        return parseListing(html, activeBase ?: BASES.first())
    }

    internal fun parseListing(html: String, base: String = BASES.first()): List<VideoSummary> {
        val doc = Jsoup.parse(html, base)
        val out = LinkedHashMap<String, VideoSummary>()
        // Cards are <div class="thumbnail group"> with a cover <a>, a duration
        // <span> and the real title in a sibling <div class="my-2 …">.
        for (card in doc.select("div.thumbnail.group, div.thumbnail")) {
            val link = card.selectFirst("a[href]") ?: continue
            val href = link.attr("abs:href").ifBlank { link.attr("href") }
            val path = runCatching { java.net.URI(href).path.orEmpty() }.getOrDefault("")
            val match = CARD_PATH.find(path) ?: continue
            val slug = match.groupValues[1]
            if (slug in NAV || slug.none { it.isDigit() }) continue
            if (NAV.any { slug.startsWith(it) }) continue

            val img = card.selectFirst("img")
            val rawTitle = listOf(
                card.selectFirst("div.my-2 a")?.text(),
                card.selectFirst("div.truncate a")?.text(),
                img?.attr("alt"),
                link.attr("alt"),
                link.attr("title")
            ).firstNotNullOfOrNull { candidate ->
                candidate?.trim()?.takeIf { it.length >= 4 && !it.matches(CODE_ONLY) }
            } ?: slug.uppercase()
            val cleanedTitle = Titles.clean(rawTitle)

            val thumb = img?.let { el ->
                el.attr("abs:data-src").ifBlank { el.attr("data-src") }
                    .ifBlank { el.attr("abs:src") }
            }.orEmpty().takeUnless { it.startsWith("data:") }.orEmpty()

            val duration = card.selectFirst("span.absolute")?.text()
                ?.let { clock(it) } ?: 0

            val key = "/ja/$slug"
            out.putIfAbsent(
                key,
                VideoSummary(
                    sourceId = id,
                    vkey = key,
                    title = cleanedTitle,
                    thumb = thumb,
                    durationSec = duration,
                    views = 0,
                    uploaderSlug = null,
                    uploaderName = null,
                    verifiedUploader = false,
                    category = category,
                    flavor = HentaiFlavor.STUDIO
                )
            )
        }
        return out.values.toList()
    }

    private fun clock(raw: String): Int {
        val parts = raw.trim().split(":")
        if (parts.size < 2) return 0
        var seconds = 0
        for (part in parts) seconds = seconds * 60 + (part.toIntOrNull() ?: return 0)
        return seconds
    }

    override suspend fun detail(vkey: String): VideoDetail {
        val html = runCatching { fetch(vkey) }.getOrElse { return VideoDetail(playerMode = PlayerMode.EMBED) }
        return parseStreams(html)
    }

    internal fun parseStreams(html: String): VideoDetail {
        val found = LinkedHashMap<String, StreamVariant>()
        val haystack = buildString {
            append(html)
            append('\n')
            unpackPackers(html).forEach { append(it).append('\n') }
        }
        for (match in MEDIA.findAll(haystack)) {
            val url = match.value
                .replace("\\/", "/")
                .trimEnd('\\', '\'', '"', ',', ';')
            val quality = when {
                url.contains("1080") -> 1080
                url.contains("720") -> 720
                url.contains("480") -> 480
                url.contains("playlist") || url.contains("source") -> 1080
                else -> 0
            }
            val label = if (quality > 0) "${quality}p" else "HLS"
            found.putIfAbsent(
                url,
                StreamVariant(quality = quality, label = label, url = url, hls = true)
            )
        }
        return VideoDetail(
            streams = found.values.sortedByDescending { it.quality },
            playerMode = PlayerMode.NATIVE
        )
    }

    /** All packing layers found on MissAV pages. */
    internal fun unpackPackers(html: String): List<String> =
        JsPacker.unpackAll(html).split('\n').filter { it.isNotBlank() }

    override suspend fun comments(vkey: String, commentsUrl: String?, offset: Int): List<CommentItem> =
        emptyList()

    override suspend fun related(vkey: String, relatedUrl: String?): List<VideoSummary> =
        emptyList()
}
