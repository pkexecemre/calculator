package com.simplecalculator.app.data.source.generic

import com.simplecalculator.app.data.source.Category
import com.simplecalculator.app.data.source.CommentItem
import com.simplecalculator.app.data.source.HentaiFlavor
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
import java.io.IOException
import java.net.URLEncoder

val DEFAULT_MEDIA_REGEX: Regex = Regex(
    """https?://[^\s"'<>\\]+\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?""",
    RegexOption.IGNORE_CASE
)

private const val DEFAULT_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

/** A site described by selectors and URL shapes instead of a bespoke parser. */
data class SiteSpec(
    val id: String,
    val displayName: String,
    val category: Category,
    val base: String,
    val trendingPath: (Int) -> String = { if (it <= 1) "/" else "/?page=$it" },
    val searchPath: (String, Int) -> String,
    val cardSelector: String,
    val linkSelector: String = "a[href]",
    val linkFilter: Regex? = null,
    val titleSelector: String? = null,
    val titleAttribute: String? = null,
    val thumbSelector: String? = null,
    val durationSelector: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val tags: List<String> = emptyList(),
    val flavor: HentaiFlavor? = null,
    val forceEmbed: Boolean = false,
    val mediaRegex: Regex = DEFAULT_MEDIA_REGEX
)

/**
 * Shared HTML adapter for the sites whose public pages expose cards as links and
 * streams as <source>/<video> URLs or plain media links in the page.
 */
private val CLOCK = Regex("""^\d{1,2}:\d{2}(:\d{2})?$""")
private val CODE_ONLY = Regex("""(?i)^[a-z]{2,8}-?\d{2,6}$""")

class GenericHtmlSource(
    private val spec: SiteSpec,
    private val http: OkHttpClient
) : Source {

    private val KVS_KEYS = listOf(
        "video_alt_url_hd" to "1080p",
        "video_alt_url" to "720p",
        "video_url" to "540p"
    )

    private val AD_HOSTS = listOf(
        "doubleclick", "googlesyndication", "exoclick", "juicyads", "trafficjunky",
        "tsyndicate", "adsterra", "popads", "propellerads"
    )


    override val id: String get() = spec.id
    override val displayName: String get() = spec.displayName
    override val category: Category get() = spec.category
    override val playerMode: PlayerMode = PlayerMode.NATIVE
    override val supportsStars: Boolean = false

    private suspend fun fetch(path: String): String = withContext(Dispatchers.IO) {
        val url = if (path.startsWith("http")) path else spec.base + path
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", spec.headers["User-Agent"] ?: DEFAULT_UA)
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", spec.headers["Referer"] ?: (spec.base + "/"))
        spec.headers.filterKeys { it != "User-Agent" && it != "Referer" }
            .forEach { (name, value) -> builder.header(name, value) }
        http.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            response.body?.string() ?: ""
        }
    }

    override suspend fun listing(
        kind: ListingKind,
        page: Int,
        query: String?,
        slug: String?
    ): List<VideoSummary> {
        val path = when (kind) {
            ListingKind.SEARCH -> spec.searchPath(query.orEmpty(), page)
            ListingKind.STAR -> spec.searchPath(slug.orEmpty(), page)
            ListingKind.TRENDING -> spec.trendingPath(page)
        }
        val url = if (path.startsWith("http")) path else spec.base + path
        return parseListing(fetch(path), url)
    }

    internal fun parseListing(html: String, pageUrl: String): List<VideoSummary> {
        val doc = Jsoup.parse(html, pageUrl)
        val out = LinkedHashMap<String, VideoSummary>()
        for (card in doc.select(spec.cardSelector)) {
            val link = if (card.tagName().equals("a", ignoreCase = true)) {
                card
            } else {
                card.selectFirst(spec.linkSelector)
            } ?: continue
            val href = link.attr("abs:href").ifBlank { link.attr("href") }
            if (href.isBlank()) continue
            if (spec.linkFilter?.containsMatchIn(href) == false) continue
            val vkey = keyOf(href)
            if (vkey.isBlank()) continue

            val image = card.selectFirst(spec.thumbSelector ?: "img")
            val thumb = listOf(
                "abs:data-src", "abs:data-original", "abs:data-lazy-src",
                "abs:data-echo", "abs:src"
            ).firstNotNullOfOrNull { attribute ->
                image?.attr(attribute)?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            }.orEmpty()

            // Several sites put a clock or a bare code in the anchor text and the
            // real title in a neighbouring heading: take the most informative one.
            val candidates = listOfNotNull(
                spec.titleSelector?.let { selector ->
                    card.selectFirst(selector)?.let { element ->
                        if (spec.titleAttribute == null) {
                            element.text()
                        } else {
                            element.attr(spec.titleAttribute)
                        }
                    }
                },
                card.selectFirst("h1, h2, h3, h4, .title, .video-title, .card-title")?.text(),
                link.attr("title").ifBlank { null },
                image?.attr("alt")?.ifBlank { null },
                link.text().ifBlank { null }
            ).map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.length >= 2 }

            val title = Titles.clean(
                candidates.firstOrNull { candidate ->
                    !CLOCK.matches(candidate) &&
                        !CODE_ONLY.matches(candidate) &&
                        !Titles.isMetadataOnly(candidate)
                } ?: candidates.maxByOrNull { it.length } ?: ""
            )
            if (title.length < 2) continue
            if (!Tags.isAllowed(spec.tags, title)) continue

            val duration = spec.durationSelector
                ?.let { clock(card.selectFirst(it)?.text()) } ?: 0
            out.putIfAbsent(
                vkey,
                VideoSummary(
                    sourceId = spec.id,
                    vkey = vkey,
                    title = title,
                    thumb = thumb,
                    durationSec = duration,
                    views = 0,
                    uploaderSlug = null,
                    uploaderName = null,
                    verifiedUploader = false,
                    category = spec.category,
                    tags = spec.tags,
                    flavor = spec.flavor ?: HentaiFlavor.from(title, spec.tags)
                )
            )
        }
        return out.values.toList()
    }

    override suspend fun detail(vkey: String): VideoDetail {
        val url = if (vkey.startsWith("http")) vkey else spec.base + vkey
        if (spec.forceEmbed) {
            val resolved = resolveEmbed(url, spec.base + "/")
            if (resolved != null) return resolved
            return VideoDetail(embedUrl = url, playerMode = PlayerMode.EMBED)
        }
        val detail = parseStreams(fetch(url), url)
        if (detail.streams.isNotEmpty()) return detail
        // Third-party players on this page: follow one level and unpack them.
        val embed = detail.embedUrl
        if (embed != null) {
            resolveEmbed(embed, url)?.let { return it }
        }
        return detail
    }

    /**
     * Resolves an embed page (lplaygo, lulustream, …) by unpacking its player
     * script and verifying the playlist answers. Returns null when the player is
     * genuinely JS-only, so the caller can fall back to a WebView.
     */
    private suspend fun resolveEmbed(embedUrl: String, referer: String): VideoDetail? {
        // Some players reject a foreign Referer, so try their own origin too.
        val origin = runCatching {
            val uri = java.net.URI(embedUrl)
            "${uri.scheme}://${uri.host}/"
        }.getOrDefault(referer)
        val page = runCatching { fetchRaw(embedUrl, origin) }.getOrNull()
            ?: runCatching { fetchRaw(embedUrl, referer) }.getOrNull()
            ?: return null
        val decoded = JsPacker.unpackAll(page)
        val haystack = page + "\n" + decoded
        val candidates = Regex("""https?://[^"'\s<>\\]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>\\]*)?""")
            .findAll(haystack.replace("\\/", "/"))
            .map { it.value }
            .distinct()
            .toList()
        for (url in candidates) {
            if (!looksLikeMedia(url)) continue
            if (reachable(url, origin)) {
                val hls = url.contains(".m3u8")
                return VideoDetail(
                    streams = listOf(
                        StreamVariant(
                            quality = if (hls) 1080 else 720,
                            label = if (hls) "HLS" else "720p",
                            url = url,
                            hls = hls
                        )
                    ),
                    playerMode = PlayerMode.NATIVE
                )
            }
        }
        // Nothing verified: hand the unpacked playlist to Media3 anyway and keep
        // the embed around so the player can fall back to the site's own page.
        val fallback = candidates.firstOrNull()
        if (fallback != null) {
            val hls = fallback.contains(".m3u8")
            return VideoDetail(
                streams = listOf(
                    StreamVariant(
                        quality = if (hls) 1080 else 720,
                        label = if (hls) "HLS" else "720p",
                        url = fallback,
                        hls = hls
                    )
                ),
                embedUrl = embedUrl,
                playerMode = PlayerMode.NATIVE
            )
        }
        return null
    }

    private fun looksLikeMedia(url: String): Boolean =
        url.contains(".m3u8") || url.contains(".mp4")

    private suspend fun reachable(url: String, referer: String): Boolean =
        withContext(Dispatchers.IO) {
            repeat(3) { attempt ->
                // Only back off after a failure; the first try must be instant.
                if (attempt > 0) Thread.sleep(500L * attempt)
                val ok = runCatching {
                    val builder = Request.Builder().url(url)
                        .header("User-Agent", spec.headers["User-Agent"] ?: DEFAULT_UA)
                    // Some CDNs 403 the first hit right after the page fetch.
                    if (attempt > 0) {
                        builder.header("Referer", referer)
                        if (!url.contains(".m3u8")) builder.header("Range", "bytes=0-1024")
                    } else if (!url.contains(".m3u8")) {
                        builder.header("Range", "bytes=0-1024")
                    }
                    http.newCall(builder.build()).execute().use { it.isSuccessful }
                }.getOrDefault(false)
                if (ok) return@withContext true
                Thread.sleep(600L * (attempt + 1))
            }
            false
        }

    private suspend fun fetchRaw(url: String, referer: String): String = withContext(Dispatchers.IO) {
        // Keep this minimal: player hosts serve a different (JS-only) variant
        // when they see a full browser Accept header.
        val request = Request.Builder().url(url)
            .header("User-Agent", spec.headers["User-Agent"] ?: DEFAULT_UA)
            .header("Referer", referer)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            response.body?.string() ?: ""
        }
    }

    internal fun parseStreams(html: String, detailUrl: String): VideoDetail {
        val doc = Jsoup.parse(html, detailUrl)
        val found = LinkedHashMap<String, StreamVariant>()
        for (element in doc.select("source[src], video[src]")) {
            val url = element.attr("abs:src")
            if (url.startsWith("http")) addStream(found, url, element.attr("res") + " " + element.attr("label"))
        }
        val haystack = html.replace("\\/", "/").replace("&amp;", "&")
        // JW Player setups (OHentai and several sister sites) keep the media in
        // a `file: "..."` field instead of a <source> tag.
        val fileRegex = Regex("""["']file["']\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        for (match in fileRegex.findAll(haystack)) {
            addStream(found, normalize(match.groupValues[1]), match.groupValues[1])
        }
        // Kernel Video Sharing sites hide the file URL as base64 in video_url.
        for ((key, quality) in KVS_KEYS) {
            val match = Regex(key + """\s*:\s*['"]([A-Za-z0-9+/=]{16,})['"]""")
                .find(haystack) ?: continue
            val decoded = decodeBase64(match.groupValues[1])
            if (decoded.startsWith("http")) {
                addStream(found, normalize(decoded), quality)
            }
        }
        for (match in spec.mediaRegex.findAll(haystack)) {
            addStream(found, normalize(match.value), match.value)
        }
        if (found.isEmpty()) {
            // Some sites only expose a third-party player iframe.
            val frame = doc.select("iframe[src]")
                .map { it.attr("abs:src") }
                .firstOrNull { it.startsWith("http") && AD_HOSTS.none { host -> it.contains(host) } }
            if (frame != null) {
                return VideoDetail(
                    tags = spec.tags,
                    embedUrl = frame,
                    playerMode = PlayerMode.EMBED
                )
            }
        }
        return VideoDetail(
            streams = found.values.sortedByDescending { it.quality },
            tags = spec.tags,
            playerMode = PlayerMode.NATIVE
        )
    }

    override suspend fun comments(vkey: String, commentsUrl: String?, offset: Int): List<CommentItem> =
        emptyList()

    override suspend fun related(vkey: String, relatedUrl: String?): List<VideoSummary> =
        emptyList()

    private fun normalize(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> spec.base + url
        else -> url
    }

    private fun decodeBase64(raw: String): String = runCatching {
        String(java.util.Base64.getDecoder().decode(raw), Charsets.UTF_8).trim()
    }.getOrDefault("")

    private fun keyOf(url: String): String =
        if (url.startsWith(spec.base, ignoreCase = true)) {
            "/" + url.drop(spec.base.length).trimStart('/')
        } else {
            url
        }

    private fun addStream(target: LinkedHashMap<String, StreamVariant>, url: String, hint: String) {
        if (url.isBlank() || !url.startsWith("http") || target.containsKey(url)) return
        if (url.contains("/ad", ignoreCase = true) || url.contains("tracker", ignoreCase = true)) return
        val quality = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
            .find(hint)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val hls = url.contains(".m3u8", ignoreCase = true)
        target[url] = StreamVariant(
            quality = quality,
            label = if (quality > 0) "${quality}p" else if (hls) "HLS" else "MP4",
            url = url,
            hls = hls
        )
    }

    private fun clock(raw: String?): Int {
        if (raw.isNullOrBlank()) return 0
        var seconds = 0
        for (part in raw.trim().split(":")) {
            seconds = seconds * 60 + (part.toIntOrNull() ?: return 0)
        }
        return seconds
    }

}

/** The sites from MISSPipe's supported list that fit the generic HTML adapter. */
object ExtraSites {
    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    val all: List<SiteSpec> = listOf(

        SiteSpec(
            id = "kjv", displayName = "KissJAV", category = Category.JAV,
            base = "https://kissjav.li",
            trendingPath = { if (it <= 1) "/latest-updates/" else "/latest-updates/?page=$it" },
            searchPath = { q, _ -> "/search/${enc(q)}/" },
            cardSelector = "a[href*=/video/]"
        ),
        SiteSpec(
            id = "85p", displayName = "85po", category = Category.JAV,
            base = "https://www.85po.com",
            trendingPath = { if (it <= 1) "/ja/latest-updates/" else "/ja/latest-updates/?page=$it" },
            searchPath = { q, _ -> "/ja/search/${enc(q)}/" },
            cardSelector = "a[href*=/ja/v/]"
        ),
        SiteSpec(
            id = "jvn", displayName = "JAV-NONI", category = Category.JAV,
            base = "https://jav-noni.vip",
            trendingPath = { if (it <= 1) "/" else "/?page=$it" },
            searchPath = { q, _ -> "/?s=${enc(q)}" },
            cardSelector = "a[href*=/archives/]",
            linkFilter = Regex("""/archives/(?!category/|tag/)[^/]+""")
        ),
        SiteSpec(
            id = "jvs", displayName = "JAVSB", category = Category.JAV,
            base = "https://jav.sb",
            trendingPath = { if (it <= 1) "/ja/latest-updates/" else "/ja/latest-updates/?page=$it" },
            searchPath = { q, _ -> "/ja/vod/search.html?wd=${enc(q)}" },
            cardSelector = "a[href*=/jav/]"
        ),
        SiteSpec(
            id = "tmo", displayName = "TOKYO Motion", category = Category.JAV,
            base = "https://www.tokyomotion.net",
            trendingPath = { if (it <= 1) "/videos" else "/videos?page=$it" },
            searchPath = { q, _ -> "/search?search_query=${enc(q)}&search_type=videos" },
            cardSelector = "a.thumb-popu, a[href*=/video/]"
        ),
        SiteSpec(
            id = "mrd", displayName = "MRDOUGA", category = Category.JAV,
            base = "https://mrdouga.com",
            trendingPath = { if (it <= 1) "/latest-updates/" else "/latest-updates/?page=$it" },
            searchPath = { q, page -> "/search/${enc(q)}/" + if (page > 1) "?page=$page" else "" },
            cardSelector = "a[href*=/video/]"
        ),
        SiteSpec(
            id = "jvf", displayName = "JAV-FUN", category = Category.JAV,
            base = "https://jav-fun.cc",
            trendingPath = { if (it <= 1) "/" else "/page/$it/" },
            searchPath = { q, _ -> "/?s=${enc(q)}" },
            cardSelector = "article",
            titleSelector = "h2, h3, .entry-title",
            thumbSelector = "img"
        ),
        SiteSpec(
            id = "spb", displayName = "SpankBang", category = Category.LIVE,
            base = "https://www.spankbang.com",
            trendingPath = { if (it <= 1) "/" else "/?page=$it" },
            searchPath = { q, _ -> "/s/${enc(q)}/" },
            cardSelector = "a[href$=/video], a[href*=/video/]"
        ),
        SiteSpec(
            id = "xh", displayName = "xHamster", category = Category.LIVE,
            base = "https://jp.xhamster.com",
            trendingPath = { if (it <= 1) "/newest" else "/newest?page=$it" },
            searchPath = { q, _ -> "/search/${enc(q)}" },
            cardSelector = "a.video-thumb__image-container[href*=/videos/]:has(img)",
            titleSelector = "a[aria-label]",
            titleAttribute = "aria-label"
        ),
        SiteSpec(
            id = "xnx", displayName = "XNXX", category = Category.LIVE,
            base = "https://www.xnxx.com",
            trendingPath = { if (it <= 1) "/best/" else "/best/$it/" },
            searchPath = { q, _ -> "/search/${enc(q)}" },
            cardSelector = "a[href*=/video-]",
            titleSelector = "span.item-title"
        ),
        SiteSpec(
            id = "141", displayName = "141tube", category = Category.LIVE,
            base = "https://141tube.com",
            trendingPath = { if (it <= 1) "/" else "/?page=$it" },
            searchPath = { q, page -> "/search/videos?search_query=${enc(q)}" + if (page > 1) "&page=$page" else "" },
            cardSelector = "a[href*=/video/]"
        ),

        SiteSpec(
            id = "h1", displayName = "Hanime1", category = Category.HENTAI,
            base = "https://hanime1.me",
            trendingPath = { if (it <= 1) "/" else "/?page=$it" },
            searchPath = { q, _ -> "/search?query=${enc(q)}" },
            cardSelector = ".playlist-hover-wrap, .video-item-container, .video-card",
            linkSelector = "a[href*=/watch]",
            headers = mapOf("Accept-Language" to "en-US,en;q=0.9")
        ),

        SiteSpec(
            id = "r34", displayName = "Rule34Video", category = Category.HENTAI,
            base = "https://rule34video.com",
            trendingPath = { if (it <= 1) "/latest-updates/" else "/latest-updates/?page=$it" },
            searchPath = { q, _ -> "/search/?q=${enc(q)}" },
            cardSelector = "a.th[href*=/video/]",
            titleSelector = ".thumb_title",
            flavor = HentaiFlavor.USER
        ),
        SiteSpec(
            id = "hm", displayName = "HentaiMama", category = Category.HENTAI,
            base = "https://hentaimama.io",
            trendingPath = { if (it <= 1) "/" else "/page/$it/" },
            searchPath = { q, _ -> "/?s=${enc(q)}" },
            cardSelector = "article.item",
            linkSelector = "a[href*=/episodes/]",
            titleSelector = "h3 a"
        )
    )
}
