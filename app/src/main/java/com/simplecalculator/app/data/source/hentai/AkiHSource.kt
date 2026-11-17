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
import com.simplecalculator.app.data.source.generic.JsPacker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder

/** aki-h.com: series pages list episodes; playback uses the site's JS player. */
class AkiHSource(private val http: OkHttpClient) : Source {

    override val id = "akih"
    override val displayName = "aki-h"
    override val category = Category.HENTAI
    override val playerMode = PlayerMode.EMBED
    override val supportsStars = false

    /** watch path -> (series path, episode number), filled while parsing series pages. */
    private val watchMeta = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()


    /** Google Drive previews are playable directly with Range support. */
    private fun driveDirect(url: String): String? {
        val id = DRIVE_FILE.find(url)?.groupValues?.get(1) ?: return null
        return "$DRIVE_DOWNLOAD?id=$id&export=download&confirm=t"
    }

    private suspend fun reachable(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0")
                .header("Range", "bytes=0-1024")
                .build()
            http.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private fun mediaVariant(url: String): StreamVariant {
        val hls = url.contains(".m3u8")
        return StreamVariant(
            quality = if (hls) 1080 else 720,
            label = if (hls) "HLS" else "720p",
            url = url,
            hls = hls
        )
    }

    private companion object {
        const val BASE = "https://aki-h.com"
        const val DRIVE_DOWNLOAD = "https://drive.usercontent.google.com/download"
        val DRIVE_FILE = Regex("""drive\.google\.com/file/d/([A-Za-z0-9_-]{10,})""")
        val VOL_EPISODE = Regex("""(?i)\bvol\.?\s*(\d+)|ตอนที่\s*(\d+)""")
    }

    private suspend fun fetch(path: String): String = withContext(Dispatchers.IO) {
        val url = if (path.startsWith("http")) path else BASE + path
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            .header("Referer", BASE + "/")
            .build()
        http.newCall(request).execute().use { response ->
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
            ListingKind.SEARCH -> "/search/?s=${URLEncoder.encode(query.orEmpty(), "UTF-8")}" +
                if (page > 1) "&page=$page" else ""
            ListingKind.STAR -> slug ?: "/latest/"
            ListingKind.TRENDING -> if (page <= 1) "/latest/" else "/page/$page/"
        }
        val html = runCatching { fetch(path) }.getOrElse { return emptyList() }
        return parseCards(html)
    }

    internal fun parseCards(html: String): List<VideoSummary> {
        val doc = Jsoup.parse(html, BASE)
        val out = ArrayList<VideoSummary>()
        for (card in doc.select("div.ak-card")) {
            val link = card.selectFirst("a.ak-card__poster") ?: continue
            val href = link.attr("abs:href").ifBlank { link.attr("href") }
            if (href.isBlank()) continue
            val rawPath = href.substringAfter(BASE, href)
            val vkey = "/" + rawPath.trim('/') + "/"
            val title = link.attr("title").ifBlank {
                card.selectFirst(".ak-card__title")?.text().orEmpty()
            }
            if (title.isBlank()) continue
            val img = card.selectFirst("img")
            val thumb = img?.let { el -> el.attr("abs:data-src").ifBlank { el.attr("abs:src") } }.orEmpty()
            val range = card.selectFirst(".tick-ep")?.text()?.ifBlank { null }
            out.add(
                VideoSummary(
                    sourceId = id,
                    vkey = vkey,
                    title = title.trim(),
                    thumb = thumb,
                    durationSec = 0,
                    views = 0,
                    uploaderSlug = null,
                    uploaderName = null,
                    verifiedUploader = false,
                    category = category,
                    flavor = HentaiFlavor.STUDIO,
                    episodeLabel = range
                )
            )
        }
        return out.distinctBy { it.vkey }
    }

    /** Opens a series page and returns its episodes, deduplicated by episode number. */
    internal suspend fun expandSeries(summary: VideoSummary): List<VideoSummary> {
        val path = "/" + summary.vkey.trim('/') + "/"
        var body: String? = null
        var attempt = 0
        while (body == null && attempt < 3) {
            body = runCatching { fetch(path) }.getOrNull()
            if (body == null && attempt < 2) Thread.sleep(300L * (attempt + 1))
            attempt++
        }
        val html = body ?: return emptyList()
        val maxEpisode = Regex("""(?i)EP\s*\d+\s*-\s*(\d+)""")
            .find(summary.episodeLabel.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        val episodes = parseEpisodes(html, summary.title, maxEpisode, path)
        return episodes.ifEmpty { listOf(summary) }
    }

    internal fun parseEpisodes(
        html: String,
        seriesTitle: String,
        maxEpisode: Int? = null,
        seriesPath: String? = null,
        keepVariants: Boolean = false
    ): List<VideoSummary> {
        val doc = Jsoup.parse(html, BASE)
        val seen = LinkedHashMap<String, VideoSummary>()
        for (block in doc.select("div.ak-ep")) {
            val link = block.selectFirst("a.ak-ep__thumb, a.ak-ep__title a") ?: continue
            val href = link.attr("abs:href").ifBlank { link.attr("href") }
            if (!href.contains("/watch/")) continue
            val title = link.attr("title").ifBlank { link.text() }.trim()
            if (title.isBlank()) continue
            val number = VOL_EPISODE.find(title)?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }
                ?: title
            if (maxEpisode != null && number.toIntOrNull()?.let { it > maxEpisode } == true) continue
            // Prefer the first variant (Sub-Eng) per episode number, unless the
            // caller wants every mirror (used to retry dead links).
            if (!keepVariants && seen.containsKey(number)) continue
            val img = block.selectFirst("img")
            val thumb = img?.let { el -> el.attr("abs:data-src").ifBlank { el.attr("abs:src") } }.orEmpty()
            val rawPath = href.substringAfter(BASE, href)
            val vkey = "/" + rawPath.trim('/') + "/"
            if (seriesPath != null) {
                if (watchMeta.size > 2000) watchMeta.clear()
                watchMeta[vkey] = seriesPath to number
            }
            seen[number] = VideoSummary(
                sourceId = id,
                vkey = vkey,
                title = title,
                thumb = thumb.ifBlank { img?.attr("abs:src").orEmpty() },
                durationSec = 0,
                views = 0,
                uploaderSlug = null,
                uploaderName = null,
                verifiedUploader = false,
                category = category,
                flavor = HentaiFlavor.STUDIO,
                episodeLabel = number
            )
        }
        return seen.values.sortedBy { it.episodeLabel?.toIntOrNull() ?: Int.MAX_VALUE }
    }

    /** watch page -> /video/<id>/ -> VIDEO_APP source (direct media or player URL). */
    private suspend fun resolvePlayer(watchPath: String): VideoDetail {
        val watch = runCatching { fetch(watchPath) }.getOrNull() ?: return VideoDetail()
        val videoId = Regex("""displayvideo\(\s*\d+\s*,\s*(\d+)\s*\)""")
            .find(watch)?.groupValues?.get(1)
            ?: Regex("""/video/(\d+)/?""").find(watch)?.groupValues?.get(1)
            ?: return VideoDetail()
        val playerPage = runCatching { fetch("/video/$videoId/") }.getOrNull() ?: return VideoDetail()
        val app = Regex("""window\.VIDEO_APP\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
            .find(playerPage)?.groupValues?.get(1) ?: return VideoDetail()
        val type = Regex(""""type"\s*:\s*"([^"]+)"""").find(app)?.groupValues?.get(1).orEmpty()
        val url = Regex(""""source"\s*:\s*\{[^}]*"url"\s*:\s*"([^"]+)"""")
            .find(app)?.groupValues?.get(1).orEmpty()
        if (url.isBlank()) return VideoDetail()

        // 1) Direct media on the page.
        if (url.contains(".mp4") || url.contains(".m3u8") || type == "hotlink") {
            return VideoDetail(streams = listOf(mediaVariant(url)), playerMode = PlayerMode.NATIVE)
        }

        // 2) Drive-hosted episodes stream straight from Google with Range.
        driveDirect(url)?.let { direct ->
            if (reachable(direct)) {
                return VideoDetail(
                    streams = listOf(
                        StreamVariant(
                            quality = 1080,
                            label = "Drive",
                            url = direct,
                            hls = false
                        )
                    ),
                    playerMode = PlayerMode.NATIVE
                )
            }
        }

        // 3) The site's own iframe player: follow it once more for a media URL.
        if (url.contains("v.aki-h.com")) {
            resolveIframe(url)?.let { return it }
            // The /v/<id> and /v2/<id> shells expose different boot payloads, so
            // try the sibling form before giving up on the native player.
            val sibling = when {
                url.contains("/v2/") -> url.replace("/v2/", "/v/")
                url.contains("/v/") -> url.replace("/v/", "/v2/")
                else -> null
            }
            if (sibling != null) resolveIframe(sibling)?.let { return it }
        }

        // 4) Last resort: the site's player page, not the ad-heavy wrapper.
        return VideoDetail(embedUrl = url, playerMode = PlayerMode.EMBED)
    }

    /**
     * v.aki-h.com player shell -> /e|f/<iden> boot JSON -> either a Drive file or
     * a streaming server whose page packs the real HLS under /file/<code>/.
     */
    private suspend fun resolveIframe(playerUrl: String): VideoDetail? {
        val host = "https://v.aki-h.com"
        val shell = runCatching { fetch(playerUrl) }.getOrNull() ?: return null
        val link = Regex("""/(e|f)/([A-Za-z0-9]+)""").find(shell) ?: return null
        val kind = link.groupValues[1]
        val iden = link.groupValues[2]
        val boot = runCatching { fetch("$host/$kind/$iden") }.getOrNull() ?: return null

        val inner = Regex(""""source"\s*:\s*\{[^}]*"url"\s*:\s*"([^"]+)"""")
            .find(boot)?.groupValues?.get(1)
        if (inner != null) {
            driveDirect(inner)?.let { direct ->
                if (reachable(direct)) {
                    return VideoDetail(
                        streams = listOf(
                            StreamVariant(quality = 1080, label = "Drive", url = direct, hls = false)
                        ),
                        playerMode = PlayerMode.NATIVE
                    )
                }
            }
            if ((inner.contains(".mp4") || inner.contains(".m3u8")) && reachable(inner)) {
                return VideoDetail(
                    streams = listOf(mediaVariant(inner)),
                    playerMode = PlayerMode.NATIVE
                )
            }
        }

        val serverList = if (boot.contains(""""servers"""")) {
            boot
        } else {
            runCatching { fetch("$host/api/servers/$iden") }.getOrNull() ?: return null
        }
        for (match in Regex(""""url"\s*:\s*"([^"]+)"""").findAll(serverList)) {
            val serverUrl = match.groupValues[1].replace("\\/", "/")
            if (!serverUrl.startsWith("http")) continue
            val page = runCatching { fetch(serverUrl) }.getOrNull() ?: continue
            // The playback page embeds aki-h.stream/v/<code>, whose packed JS
            // holds the real playlist under /file/<code>/.
            val streamUrl = Regex("""https://aki-h\.stream/[^"'\s<>]+""").find(page)?.value
            val streamPage = if (streamUrl != null) {
                runCatching { fetch(streamUrl) }.getOrNull()
            } else {
                page
            }
            val decoded = JsPacker.unpackAll(streamPage ?: page)
            val file = Regex(""""file"\s*:\s*"(https?://[^"]+)"""")
                .findAll(decoded)
                .map { it.groupValues[1].replace("\\/", "/") }
                .firstOrNull { it.contains("/file/") }
            if (file != null && reachable(file)) {
                return VideoDetail(
                    streams = listOf(
                        StreamVariant(quality = 1080, label = "HLS", url = file, hls = true)
                    ),
                    playerMode = PlayerMode.NATIVE
                )
            }
            for (media in Regex("""https?://[^"'\s<>]+?\.(?:m3u8|mp4)[^"'\s<>]*""")
                .findAll(decoded)) {
                if (reachable(media.value)) {
                    return VideoDetail(
                        streams = listOf(mediaVariant(media.value)),
                        playerMode = PlayerMode.NATIVE
                    )
                }
            }
        }
        return null
    }

    override suspend fun detail(vkey: String): VideoDetail {
        val watchPath = if (vkey.contains("/watch/")) {
            vkey
        } else {
            val html = runCatching { fetch(if (vkey.endsWith("/")) vkey else "$vkey/") }.getOrNull()
            if (html == null) {
                // Fall back to the series page so the WebView still lands somewhere useful.
                return VideoDetail(embedUrl = BASE + vkey, playerMode = PlayerMode.EMBED)
            }
            parseEpisodes(html, "").firstOrNull()?.vkey ?: vkey
        }
        val embed = if (watchPath.endsWith("/")) watchPath else "$watchPath/"
        val resolved = resolvePlayer(embed)
        if (resolved.streams.isNotEmpty() || resolved.embedUrl != null) return resolved

        // Mirrored variants rot: try the other language/quality links for the
        // same episode before giving up on the series page.
        val meta = watchMeta[embed]
        if (meta != null) {
            val (seriesPath, number) = meta
            val fresh = runCatching { fetch(seriesPath) }.getOrNull()
            if (fresh != null) {
                val variants = parseEpisodes(fresh, "", null, seriesPath, keepVariants = true)
                    .filter { watchMeta[it.vkey]?.second == number && it.vkey != embed }
                for (candidate in variants.take(3)) {
                    val attempt = resolvePlayer(candidate.vkey)
                    if (attempt.streams.isNotEmpty() || attempt.embedUrl != null) return attempt
                }
            }
        }
        return VideoDetail(
            embedUrl = BASE + embed,
            playerMode = PlayerMode.EMBED
        )
    }

    override suspend fun comments(vkey: String, commentsUrl: String?, offset: Int): List<CommentItem> =
        emptyList()

    override suspend fun related(vkey: String, relatedUrl: String?): List<VideoSummary> =
        emptyList()
}
