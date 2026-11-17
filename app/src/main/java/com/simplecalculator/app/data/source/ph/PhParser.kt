package com.simplecalculator.app.data.source.ph

import com.simplecalculator.app.data.source.CommentItem
import com.simplecalculator.app.data.source.StreamVariant
import com.simplecalculator.app.data.source.VideoDetail
import com.simplecalculator.app.data.source.VideoSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * DOM/JSON extraction for pornhub. Kept free of IO so it is unit testable, and
 * written defensively: a layout change degrades a row instead of crashing the feed.
 */
object PhParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseList(html: String, sourceId: String): List<VideoSummary> {
        val doc = Jsoup.parse(html)
        // Model/channel pages repeat the video card markup inside header dropdowns
        // ("Hottest", "Recommended"), so scope to the real list when it exists and
        // always drop anything nested in a header submenu.
        val scope = doc.selectFirst("#mostRecentVideosSection")
            ?: doc.selectFirst("div.videoUList")
            ?: doc
        return scope.select("li.pcVideoListItem, div.pcVideoListItem")
            .filter { item ->
                item.parents().none { parent ->
                    parent.hasClass("headerSubMenu") ||
                        parent.hasClass("subMenu") ||
                        parent.id().endsWith("MenuSection")
                }
            }
            .mapNotNull { item -> fromItem(item, sourceId) }
            .distinctBy { it.vkey }
    }

    private fun fromItem(item: Element, sourceId: String): VideoSummary? {
        val link = item.selectFirst("a[href*=view_video]") ?: return null
        val href = link.attr("href")
        val vkey = item.attr("data-video-vkey").ifBlank { href.substringAfter("viewkey=", "").substringBefore("&") }
        if (vkey.isBlank()) return null

        val img = item.selectFirst("img.thumb") ?: item.selectFirst("img")
        val thumb = img?.let { it.attr("data-image").ifBlank { it.attr("src") } }.orEmpty()

        val title = item.selectFirst("span.title a")?.let { a -> a.attr("title").ifBlank { a.text() } }
            ?: link.attr("title").ifBlank { link.text() }

        val duration = parseClock(item.selectFirst("var.duration")?.text())
        val views = parseCount(item.selectFirst("span.views var")?.text())

        val uploaderLink = item.selectFirst("div.videoUploaderBlock a[href^=/model/]")
            ?: item.selectFirst("a[href^=/model/]")
        val slug = uploaderLink?.attr("href")?.substringAfter("/model/", "")?.trim('/')?.takeIf { it.isNotEmpty() }
        val uploaderName = uploaderLink?.let { it.attr("title").ifBlank { it.text() } }
        val verified = item.selectFirst(".verified-icon, .verified-icon flag") != null

        return VideoSummary(
            sourceId = sourceId,
            vkey = vkey,
            title = title,
            thumb = thumb,
            durationSec = duration,
            views = views,
            uploaderSlug = slug,
            uploaderName = uploaderName,
            verifiedUploader = verified
        )
    }

    fun parseDetail(html: String): VideoDetail {
        val doc = Jsoup.parse(html)
        val flash = extractFlashvars(html)
        val streams = flash?.let { parseStreams(it) }.orEmpty()
        val commentsUrl = doc.select("[data-ajax-url]")
            .map { it.attr("data-ajax-url") }
            .firstOrNull { it.contains("comment") }
        val relatedUrl = flash?.get("related_url")?.let { prim(it) }
        val tags = doc.select("div.tagsWrapper a[href*=search]").map { it.text() }.filter { it.isNotBlank() }.take(20)
        val uploader = parseUploader(html)
        return VideoDetail(
            streams = streams,
            commentsUrl = commentsUrl,
            relatedUrl = relatedUrl,
            tags = tags,
            uploaderSlug = uploader?.first,
            uploaderName = uploader?.second
        )
    }

    fun parseUploader(html: String): Pair<String, String?>? {
        val doc = Jsoup.parse(html)
        val link = doc.selectFirst("div.userInfo a[href^=/model/]")
            ?: doc.selectFirst("div.videoInfo a[href^=/model/]")
            ?: doc.selectFirst("a[href^=/model/]")
            ?: return null
        val slug = link.attr("href").substringAfter("/model/", "").trim('/')
        if (slug.isEmpty()) return null
        return slug to link.attr("title").ifBlank { link.text() }.ifBlank { null }
    }

    private fun parseStreams(flash: JsonObject): List<StreamVariant> {
        val defs = flash["mediaDefinitions"] as? JsonArray ?: return emptyList()
        return defs.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val format = prim(obj["format"]).orEmpty()
            val url = prim(obj["videoUrl"]).orEmpty()
            if (url.isEmpty()) return@mapNotNull null
            // The "mp4" bucket is sometimes a get_media stub that answers []; only
            // accept real media paths.
            val looksPlayable = url.contains(".m3u8") || url.contains(".mp4")
            if (!looksPlayable || url.contains("/get_media")) return@mapNotNull null
            val quality = qualityOf(obj["quality"])
            StreamVariant(
                quality = quality,
                label = if (quality > 0) quality.toString() + "p" else format.uppercase(),
                url = url,
                hls = format.equals("hls", ignoreCase = true)
            )
        }.distinctBy { it.url }
    }

    private fun qualityOf(element: JsonElement?): Int {
        val value = element as? JsonPrimitive ?: return 0
        val raw = value.content
        val digits = raw.takeWhile { it.isDigit() }
        return digits.toIntOrNull() ?: 0
    }

    private fun prim(element: JsonElement?): String? {
        val value = element as? JsonPrimitive ?: return null
        return if (value.isString) value.content else value.content
    }

    /** Scans the "var flashvars_NNN = {...}" object with brace matching. */
    fun extractFlashvars(html: String): JsonObject? {
        val marker = html.indexOf("flashvars_")
        if (marker < 0) return null
        val start = html.indexOf('{', marker)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        var index = start
        var end = -1
        while (index < html.length) {
            val ch = html[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            end = index
                            break
                        }
                    }
                }
            }
            index++
        }
        if (end < 0) return null
        val rawJson = html.substring(start, end + 1)
        return try {
            json.parseToJsonElement(rawJson) as? JsonObject
        } catch (e: Exception) {
            null
        }
    }

    fun parseComments(html: String): List<CommentItem> {
        val doc = Jsoup.parse(html)
        val blocks = doc.select("div.commentBlock")
        val result = ArrayList<CommentItem>()
        for (block in blocks) {
            val nested = block.parents().any { it.hasClass("commentBlock") }
            if (nested) continue
            val author = block.selectFirst("a.usernameLink")?.text().orEmpty()
            if (author.isEmpty()) continue
            val body = block.selectFirst("div.commentMessage > span")?.text()
                ?: block.selectFirst("div.commentMessage")?.ownText().orEmpty()
            val avatar = block.selectFirst("img.commentAvatarImg")?.attr("src")
            val date = block.selectFirst("div.date")?.text().orEmpty()
            val votes = block.selectFirst("span.voteTotal")?.text()?.toIntOrNull() ?: 0
            val verified = block.selectFirst(".verified-icon") != null
            result.add(CommentItem(author, avatar, body, date, votes, verified))
        }
        return result
    }

    fun parseRelated(rawJson: String, sourceId: String): List<VideoSummary> {
        val root = try {
            json.parseToJsonElement(rawJson) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return emptyList()

        val output = ArrayList<VideoSummary>()
        for (bucket in listOf("related", "mostViewed", "topRated")) {
            val array = root[bucket] as? JsonArray ?: continue
            for (element in array) {
                val tuple = element as? JsonArray ?: continue
                val parsed = fromTuple(tuple, sourceId) ?: continue
                output.add(parsed)
            }
        }
        return output.distinctBy { it.vkey }
    }

    private fun fromTuple(tuple: JsonArray, sourceId: String): VideoSummary? {
        var url = ""
        var thumb = ""
        var title = ""
        var duration = 0
        var views = 0L
        for (element in tuple) {
            val primitive = element as? JsonPrimitive ?: continue
            val raw = primitive.content
            if (raw.contains("view_video.php")) {
                if (url.isEmpty()) url = raw
                continue
            }
            if (raw.startsWith("http") && (raw.contains("phncdn") || raw.contains("pix-cdn"))) {
                if (thumb.isEmpty()) thumb = raw
                continue
            }
            val clock = parseClock(raw)
            if (clock > 0 && raw.length <= 8) {
                if (duration == 0) duration = clock
                continue
            }
            if (!primitive.isString) {
                views = raw.toLongOrNull() ?: views
                continue
            }
            if (raw.startsWith("http") || raw.startsWith("/model/") || raw.contains("/model/")) {
                continue
            }
            if (raw.length > title.length) {
                title = org.jsoup.parser.Parser.unescapeEntities(raw, false)
            }
        }
        val vkey = url.substringAfter("viewkey=", "").substringBefore("&")
        if (vkey.isEmpty()) return null
        return VideoSummary(
            sourceId = sourceId,
            vkey = vkey,
            title = title,
            thumb = thumb,
            durationSec = duration,
            views = views,
            uploaderSlug = null,
            uploaderName = null,
            verifiedUploader = false
        )
    }

    fun parseClock(raw: String?): Int {
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

    fun parseCount(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val cleaned = raw.trim().replace(",", "")
        val suffix = cleaned.lastOrNull()
        val body = if (suffix != null && suffix.isLetter()) cleaned.dropLast(1) else cleaned
        val base = body.toDoubleOrNull() ?: return 0L
        return when (suffix?.uppercaseChar()) {
            'K' -> (base * 1_000).toLong()
            'M' -> (base * 1_000_000).toLong()
            'B' -> (base * 1_000_000_000).toLong()
            else -> base.toLong()
        }
    }
}
