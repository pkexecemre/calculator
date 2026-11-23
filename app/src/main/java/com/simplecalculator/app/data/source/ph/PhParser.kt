package com.simplecalculator.app.data.source.ph

import com.simplecalculator.app.data.source.CommentItem
import com.simplecalculator.app.data.source.StreamVariant
import com.simplecalculator.app.data.source.VideoDetail
import com.simplecalculator.app.data.source.VideoSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser as JsoupParser

/**
 * DOM/JSON extraction for pornhub. Kept free of IO so it is unit testable, and
 * written defensively: a layout change degrades a row instead of crashing the feed.
 *
 * The site regularly renames containers and moves the same card markup between
 * them, so every lookup has an ordered fallback and every parser accepts missing
 * fields. Nothing here throws on bad input.
 */
object PhParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val FLASHVARS_ASSIGN = Regex("""flashvars_[A-Za-z0-9_]*\s*=\s*\{""")
    private val COUNT = Regex("""(\d[\d.,]*)\s*([kmb])?""")
    private val CLOCK = Regex("""^\d{1,3}:\d{2}(?::\d{2})?$""")
    private val RELATED_URL = Regex(""""related_url"\s*:\s*"([^"]+)"""")
    private val UNAVAILABLE = Regex(""""video_unavailable"\s*:\s*"?true"?""", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("""\s+""")

    private val LIST_SCOPES = listOf(
        "#mostRecentVideosSection",
        "div.videoUList",
        "#videoCategory",
        "div.pcVideoList"
    )

    private val LIST_ITEMS = "li.pcVideoListItem, div.pcVideoListItem"

    private val UPLOADER_PREFIXES = listOf("/model/", "/channels/", "/users/", "/pornstar/")

    private val BLOCKED_SELECTORS = listOf(
        "#ageDisclaimer",
        "#accessAgeDisclaimer",
        "#ageVerification",
        "#ageGate",
        ".ageDisclaimer",
        ".accessAgeDisclaimer",
        ".ageVerification",
        ".ageGate",
        ".ageDisclaimerButtons",
        "#js-ageDisclaimerModal",
        "[class*=ageVerification]",
        "[class*=ageDisclaimer]",
        "[id*=ageVerification]",
        "[id*=accessAgeDisclaimer]"
    )

    private val BLOCKED_TEXT = listOf(
        "age verification",
        "age-verification",
        "verify your age",
        "verify you are 18",
        "verify you are over 18",
        "are you 18",
        "you must be 18",
        "enter your age",
        "age gate",
        "age-gate",
        "access age disclaimer"
    )

    private val BLOCKED_RAW = listOf(
        "agedisclaimer",
        "accessagedisclaimer",
        "age verification",
        "age-verification",
        "agegate",
        "age-gate",
        "js-closeagemodal"
    )

    // --- listings ------------------------------------------------------------

    fun parseList(html: String, sourceId: String): List<VideoSummary> {
        if (html.isBlank()) return emptyList()
        val doc = parseDocument(html) ?: return emptyList()

        // Model/channel pages repeat the video card markup inside header dropdowns
        // ("Hottest", "Recommended"), so scope to the real list when it exists and
        // always drop anything nested in a header submenu.
        val scopes = ArrayList<Element>()
        for (selector in LIST_SCOPES) {
            doc.selectFirst(selector)?.let { scopes.add(it) }
        }
        scopes.add(doc)

        for (scope in scopes) {
            val parsed = collectListItems(scope)
                .filterNot { isInHeaderMenu(it) }
                .mapNotNull { fromItem(it, sourceId) }
                .filter { it.vkey.isNotBlank() && it.title.isNotBlank() }
                .distinctBy { it.vkey }
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    private fun collectListItems(scope: Element): List<Element> {
        val primary = scope.select(LIST_ITEMS)
        // Cards that no longer carry a pcVideoListItem class are still usable:
        // the vkey attribute survives most redesigns. Skip descendants of a card
        // that was already collected so one card cannot become two rows.
        val extras = scope.select("[data-video-vkey]").filter { candidate ->
            !candidate.hasClass("pcVideoListItem") &&
                candidate.parents().none { it.hasClass("pcVideoListItem") }
        }
        if (primary.isEmpty()) return extras
        if (extras.isEmpty()) return primary
        return primary + extras
    }

    private fun isInHeaderMenu(element: Element): Boolean {
        var current: Element? = element
        while (current != null) {
            if (current.tagName().equals("header", ignoreCase = true)) return true
            val id = current.id().lowercase()
            if (id.endsWith("menusection") || id.contains("submenu")) return true
            for (rawClass in current.classNames()) {
                val name = rawClass.lowercase()
                if (name.contains("submenu") ||
                    name == "headersubmenu" ||
                    name == "headermenu" ||
                    name == "dropdownhottestvideos" ||
                    name == "dropdownmenu"
                ) {
                    return true
                }
            }
            current = current.parent()
        }
        return false
    }

    private fun fromItem(item: Element, sourceId: String): VideoSummary? {
        val link = item.selectFirst("a[href*=view_video]") ?: item.selectFirst("a[href*=viewkey]")
        val href = link?.attr("href").orEmpty()

        val vkey = item.attr("data-video-vkey").trim()
            .ifBlank { item.attr("data-vkey").trim() }
            .ifBlank { vkeyFromHref(href) }
        if (vkey.isBlank()) return null

        val img = item.selectFirst("img.thumb")
            ?: item.selectFirst(".phimage img")
            ?: item.selectFirst("img")
        val thumb = firstUrl(
            img?.attr("data-image"),
            img?.attr("data-src"),
            img?.attr("src"),
            img?.attr("data-mediumthumb")
        )

        val title = firstText(
            item.selectFirst("span.title a")?.let { it.attr("title").ifBlank { it.text() } },
            link?.attr("title"),
            item.selectFirst("a[title]")?.attr("title"),
            img?.attr("alt")
        )
        if (title.isBlank()) return null

        val durationText = item.selectFirst("var.duration")?.text()
            ?: item.selectFirst(".duration")?.text()
        val viewsText = item.selectFirst("span.views var")?.text()
            ?: item.selectFirst(".views")?.text()

        val uploaderLink = item.selectFirst("div.videoUploaderBlock a[href*=/model/]")
            ?: item.selectFirst("a[href*=/model/]")
            ?: item.selectFirst("div.videoUploaderBlock a[href*=/channels/]")
            ?: item.selectFirst("a[href*=/channels/]")
        val uploaderSlug = uploaderLink?.let { uploaderSlugFromHref(it.attr("href")) }
        val uploaderName = uploaderLink?.let { linkName(it) }

        return VideoSummary(
            sourceId = sourceId,
            vkey = vkey,
            title = title,
            thumb = thumb,
            durationSec = parseClock(durationText),
            views = parseCount(viewsText),
            uploaderSlug = uploaderSlug,
            uploaderName = uploaderName,
            verifiedUploader = item.selectFirst(".verified-icon") != null
        )
    }

    // --- detail --------------------------------------------------------------

    fun parseDetail(html: String): VideoDetail {
        if (html.isBlank()) return VideoDetail()
        val doc = parseDocument(html)
        val flash = extractFlashvars(html)
        val unavailable = isVideoUnavailable(flash, html)
        val streams = if (unavailable) emptyList() else parseStreams(flash, html)
        val commentsUrl = doc?.let { findCommentsUrl(it) }
        val relatedUrl = flash?.get("related_url")?.let { primitiveText(it) }
            ?: scanJsonString(html, "related_url")
        val tags = doc?.let { parseTags(it) }.orEmpty()
        val uploader = doc?.let { uploaderFrom(it) } ?: parseUploader(html)

        return VideoDetail(
            streams = streams,
            commentsUrl = commentsUrl,
            relatedUrl = relatedUrl,
            tags = tags,
            uploaderSlug = uploader?.first,
            uploaderName = uploader?.second
        )
    }

    private fun isVideoUnavailable(flash: JsonObject?, html: String): Boolean {
        val flag = flash?.get("video_unavailable")
        if (flag != null) {
            val primitive = flag as? JsonPrimitive ?: return false
            return primitive.content.equals("true", ignoreCase = true) || primitive.content == "1"
        }
        return UNAVAILABLE.containsMatchIn(html)
    }

    private fun findCommentsUrl(doc: Document): String? {
        val urls = doc.select("[data-ajax-url]").map { it.attr("data-ajax-url").trim() }
            .filter { it.isNotEmpty() }
        return urls.firstOrNull { it.contains("/comment/show") && !it.contains("commentId=") && it.contains("limit=") }
            ?: urls.firstOrNull { it.contains("/comment/show") }
            ?: urls.firstOrNull { it.contains("comment") }
    }

    private fun parseTags(doc: Document): List<String> {
        val anchors = doc.select(
            "div.tagsWrapper a[href*=search], " +
                "div.tagsWrapper a[href*=/tags/], " +
                "a[href*=/tags/]"
        )
        val output = ArrayList<String>(20)
        val seen = HashSet<String>()
        for (anchor in anchors) {
            val tag = firstText(anchor.text(), anchor.attr("title"))
            if (tag.isBlank()) continue
            if (seen.add(tag.lowercase())) output.add(tag)
            if (output.size == 20) break
        }
        return output
    }

    private fun parseStreams(flash: JsonObject?, html: String): List<StreamVariant> {
        val embedded = flash?.get("mediaDefinitions") as? JsonArray
        val definitions = embedded?.takeIf { it.isNotEmpty() }
            ?: scanMediaDefinitions(html)
            ?: return emptyList()

        val output = ArrayList<StreamVariant>()
        val seen = HashSet<String>()
        for (element in definitions) {
            val obj = element as? JsonObject ?: continue
            val url = normalizeUrl(primitiveText(obj["videoUrl"]).orEmpty())
            if (url.isBlank()) continue

            // The "mp4" bucket is sometimes a get_media stub that answers []; only
            // accept real media paths.
            if (url.contains("get_media", ignoreCase = true)) continue
            val format = primitiveText(obj["format"]).orEmpty().lowercase()
            val hls = format == "hls" || url.contains(".m3u8", ignoreCase = true)
            val quality = qualityOf(obj["quality"]).takeIf { it > 0 }
                ?: qualityOf(obj["height"])
            val label = when {
                quality > 0 -> quality.toString() + "p"
                hls -> "HLS"
                else -> format.uppercase().ifBlank { "Video" }
            }
            if (!seen.add(url)) continue
            output.add(
                StreamVariant(
                    quality = quality,
                    label = label,
                    url = url,
                    hls = hls
                )
            )
        }
        return output
    }

    private fun qualityOf(element: JsonElement?): Int {
        if (element == null || element is JsonNull || element is JsonArray) return 0
        val value = element as? JsonPrimitive ?: return 0
        val raw = value.content.trim()
        val digits = raw.takeWhile { it.isDigit() }
        return digits.toIntOrNull() ?: 0
    }

    private fun uploaderFrom(doc: Document): Pair<String, String?>? {
        // The about block is the uploader. Recommendation cards elsewhere on the
        // page also contain /model/ links, so they may only be used as a last resort.
        val selectors = listOf(
            "div.userInfo a[href*=/model/]",
            "div.userInfo a[href*=/channels/]",
            "div.userInfo a[href*=/users/]",
            "div.userInfoBlock a[href*=/model/]",
            "div.userInfoBlock a[href*=/channels/]",
            "div.userInfoBlock a[href*=/users/]",
            "div.videoUploaderBlock a[href*=/model/]",
            "div.videoUploaderBlock a[href*=/channels/]",
            "a[href*=/model/]",
            "a[href*=/channels/]",
            "a[href*=/users/]",
            "a[href*=/pornstar/]"
        )
        for (selector in selectors) {
            val link = doc.selectFirst(selector) ?: continue
            val slug = uploaderSlugFromHref(link.attr("href")) ?: continue
            return slug to linkName(link)
        }
        return null
    }

    fun parseUploader(html: String): Pair<String, String?>? {
        if (html.isBlank()) return null
        val doc = parseDocument(html) ?: return null
        return uploaderFrom(doc)
    }

    /** Scans the "var flashvars_NNN = {...}" object with brace matching. */
    fun extractFlashvars(html: String): JsonObject? {
        if (html.isBlank()) return null
        var match = FLASHVARS_ASSIGN.find(html)
        while (match != null) {
            val open = match.range.last
            val raw = balancedSlice(html, open, '{', '}')
            if (raw != null) {
                val parsed = runCatching { json.parseToJsonElement(raw) }.getOrNull()
                if (parsed is JsonObject) return parsed
            }
            match = FLASHVARS_ASSIGN.find(html, match.range.last + 1)
        }
        return null
    }

    private fun scanMediaDefinitions(html: String): JsonArray? {
        var from = 0
        while (from < html.length) {
            val key = html.indexOf("\"mediaDefinitions\"", from)
            if (key < 0) return null
            val colon = html.indexOf(':', key + "\"mediaDefinitions\"".length)
            if (colon >= 0) {
                var open = colon + 1
                while (open < html.length && html[open].isWhitespace()) open++
                if (open < html.length && html[open] == '[') {
                    val raw = balancedSlice(html, open, '[', ']')
                    if (raw != null) {
                        val parsed = parseJsonArrayLenient(raw)
                        if (parsed != null && parsed.isNotEmpty()) return parsed
                    }
                }
            }
            from = key + 1
        }
        return null
    }

    private fun parseJsonArrayLenient(raw: String): JsonArray? {
        runCatching { json.parseToJsonElement(raw) }.getOrNull()
            ?.let { if (it is JsonArray) return it }

        // A single malformed definition should not discard the rest of the list.
        val objects = ArrayList<JsonElement>()
        var from = 0
        while (from < raw.length) {
            val start = raw.indexOf('{', from)
            if (start < 0) break
            val slice = balancedSlice(raw, start, '{', '}') ?: break
            val parsed = runCatching { json.parseToJsonElement(slice) }.getOrNull()
            if (parsed is JsonObject) objects.add(parsed)
            from = start + slice.length
        }
        return if (objects.isEmpty()) null else JsonArray(objects)
    }

    private fun scanJsonString(html: String, key: String): String? {
        if (key != "related_url") return null
        val raw = RELATED_URL.find(html)?.groupValues?.getOrNull(1) ?: return null
        return raw.replace("\\/", "/").replace("\\u0026", "&")
    }

    // --- comments ------------------------------------------------------------

    fun parseComments(html: String): List<CommentItem> {
        if (html.isBlank()) return emptyList()
        val doc = parseDocument(html) ?: return emptyList()
        val output = ArrayList<CommentItem>()
        for (block in doc.select("div.commentBlock")) {
            // Replies share the commentBlock class; the top-level row already
            // renders its children, so parsing them again would duplicate rows.
            if (block.parents().any { it.hasClass("commentBlock") }) continue

            val authorLink = block.selectFirst("a.usernameLink")
                ?: block.selectFirst(".usernameWrap a[href^=/users/]")
                ?: block.selectFirst("a[href^=/users/]")
            val author = firstText(authorLink?.text(), authorLink?.attr("title"))
            if (author.isBlank()) continue

            val message = block.selectFirst("div.commentMessage")
            val body = firstText(
                block.selectFirst("div.commentMessage > span")?.text(),
                message?.ownText()
            )

            val avatar = firstUrl(
                block.selectFirst("img.commentAvatarImg")?.attr("src"),
                block.selectFirst("img.commentAvatarImg")?.attr("data-src"),
                block.selectFirst("img.avatarTrigger")?.attr("src")
            ).takeIf { it.isNotBlank() }

            val date = firstText(
                block.selectFirst("div.date")?.text(),
                block.selectFirst(".date")?.text()
            )
            val voteText = block.selectFirst("span.voteTotal")?.text()?.trim()
            val votes = voteText?.toIntOrNull()
                ?: (parseCount(voteText).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())

            output.add(
                CommentItem(
                    author = author,
                    avatar = avatar,
                    text = body,
                    date = date,
                    votes = votes,
                    verified = block.selectFirst(".verified-icon") != null
                )
            )
        }
        return output
    }

    // --- related -------------------------------------------------------------

    fun parseRelated(rawJson: String, sourceId: String): List<VideoSummary> {
        if (rawJson.isBlank()) return emptyList()
        val root = runCatching { json.parseToJsonElement(rawJson) }.getOrNull() ?: return emptyList()
        val output = ArrayList<VideoSummary>()

        when (root) {
            is JsonObject -> {
                for (bucket in listOf("related", "mostViewed", "topRated")) {
                    val array = root[bucket] as? JsonArray ?: continue
                    collectRelated(array, sourceId, output)
                }
            }
            is JsonArray -> collectRelated(root, sourceId, output)
            else -> return emptyList()
        }
        return output.distinctBy { it.vkey }
    }

    private fun collectRelated(element: JsonElement, sourceId: String, output: MutableList<VideoSummary>) {
        when (element) {
            is JsonArray -> {
                if (looksLikeTuple(element)) {
                    fromTuple(element, sourceId)?.let { output.add(it) }
                } else {
                    for (child in element) collectRelated(child, sourceId, output)
                }
            }
            is JsonObject -> fromObject(element, sourceId)?.let { output.add(it) }
            else -> Unit
        }
    }

    private fun looksLikeTuple(array: JsonArray): Boolean = array.any { element ->
        val primitive = element as? JsonPrimitive ?: return@any false
        primitive.content.contains("view_video.php") || primitive.content.contains("viewkey=")
    }

    private fun fromTuple(tuple: JsonArray, sourceId: String): VideoSummary? {
        var url = ""
        var thumb = ""
        var title = ""
        var duration = 0
        var views = 0L
        var uploaderSlug: String? = null
        var verified = false

        for (element in tuple) {
            if (element is JsonObject) {
                if (thumb.isBlank()) {
                    thumb = firstUrl(
                        primitiveText(element["highResThumb"]),
                        primitiveText(element["thumb"])
                    )
                }
                val checkmark = primitiveText(element["hasCheckmark"])
                if (checkmark.equals("true", ignoreCase = true)) verified = true
                continue
            }
            val primitive = element as? JsonPrimitive ?: continue
            val raw = primitive.content.trim()
            if (raw.isEmpty()) continue

            if (raw.contains("view_video.php") || raw.contains("viewkey=")) {
                if (url.isEmpty()) url = normalizeUrl(raw)
                continue
            }
            if (raw.contains("verified-badge") || raw.contains("channel-badge")) {
                if (raw.contains("verified")) verified = true
                continue
            }
            if (isUploaderUrl(raw)) {
                if (uploaderSlug == null) uploaderSlug = uploaderSlugFromHref(raw)
                continue
            }
            if (isImageUrl(raw)) {
                if (thumb.isBlank()) thumb = normalizeUrl(raw)
                continue
            }
            if (isClockToken(raw)) {
                val parsedClock = parseClock(raw)
                if (duration == 0 && parsedClock > 0) duration = parsedClock
                continue
            }
            if (!primitive.isString) {
                // A tuple carries both the rating (0..100) and the view count;
                // the view count is the larger number.
                val numeric = raw.toLongOrNull() ?: 0L
                if (numeric > views) views = numeric
                continue
            }
            if (raw.startsWith("http") || raw.startsWith("//") || raw.startsWith("/")) continue
            if (raw.length > title.length) title = decodeText(raw)
        }

        val vkey = vkeyFromHref(url)
        if (vkey.isBlank()) return null
        return VideoSummary(
            sourceId = sourceId,
            vkey = vkey,
            title = title,
            thumb = thumb,
            durationSec = duration,
            views = views,
            uploaderSlug = uploaderSlug,
            uploaderName = null,
            verifiedUploader = verified
        )
    }

    private fun fromObject(obj: JsonObject, sourceId: String): VideoSummary? {
        val vkey = firstText(
            primitiveText(obj["vkey"]),
            primitiveText(obj["videoKey"]),
            primitiveText(obj["video_id"]),
            primitiveText(obj["vid"])
        )
        if (vkey.isBlank()) return null
        val title = firstText(
            primitiveText(obj["title"]),
            primitiveText(obj["videoTitle"]),
            primitiveText(obj["name"])
        )
        val thumb = firstUrl(
            primitiveText(obj["thumb"]),
            primitiveText(obj["thumbnail"]),
            primitiveText(obj["image"]),
            primitiveText(obj["defaultThumb"])
        )
        val durationRaw = primitiveText(obj["duration"]).orEmpty()
        val duration = if (durationRaw.contains(':')) parseClock(durationRaw)
        else durationRaw.toIntOrNull() ?: 0
        val views = primitiveText(obj["views"])?.let { it.toLongOrNull() ?: parseCount(it) } ?: 0L
        return VideoSummary(
            sourceId = sourceId,
            vkey = vkey,
            title = title,
            thumb = thumb,
            durationSec = duration,
            views = views,
            uploaderSlug = primitiveText(obj["uploaderSlug"]),
            uploaderName = primitiveText(obj["uploaderName"]),
            verifiedUploader = primitiveText(obj["verified"]).equals("true", ignoreCase = true)
        )
    }

    // --- stars / blocked pages ----------------------------------------------

    fun parseStarAvatar(html: String): String? {
        if (html.isBlank()) return null
        val doc = parseDocument(html) ?: return null
        val avatar = doc.selectFirst("img#getAvatar")
        val profile = doc.selectFirst(".modelProfile img")
        val candidates = listOf(
            avatar?.attr("src"),
            avatar?.attr("data-src"),
            profile?.attr("src"),
            profile?.attr("data-src"),
            doc.selectFirst("link[rel=image_src]")?.attr("href"),
            doc.selectFirst("meta[property=og:image]")?.attr("content")
        )
        return candidates
            .mapNotNull { it?.trim()?.takeIf { value -> value.isNotEmpty() && !value.startsWith("data:") } }
            .map { normalizeUrl(it) }
            .firstOrNull { it.isNotBlank() }
    }

    /**
     * The age modal is embedded in every normal page (hidden), so the presence
     * of its markup alone is not proof of an interstitial. A page that still has
     * a feed, player, comments or model profile was served normally.
     */
    fun isBlockedPage(html: String): Boolean {
        if (html.isBlank()) return false
        val doc = parseDocument(html) ?: return false
        val hasContent = doc.selectFirst(
            "#videoCategory, #mostRecentVideosSection, div.videoUList, div.pcVideoList, " +
                "li.pcVideoListItem, div.pcVideoListItem, [data-video-vkey], " +
                "#videoSearchResult, div.search-video-thumbs, div.videosListingWrapper, " +
                "div.commentBlock, img#getAvatar, .modelProfile"
        ) != null ||
            html.contains("flashvars_") ||
            html.contains("\"mediaDefinitions\"")
        if (hasContent) return false

        val visible = (doc.title() + "\n" + (doc.body()?.text().orEmpty())).lowercase()
        if (BLOCKED_TEXT.any { visible.contains(it) }) return true
        if (BLOCKED_SELECTORS.any { doc.selectFirst(it) != null }) return true
        val raw = html.lowercase()
        return BLOCKED_RAW.any { raw.contains(it) }
    }

    // --- primitives ----------------------------------------------------------

    fun parseClock(raw: String?): Int {
        if (raw.isNullOrBlank()) return 0
        val parts = raw.trim().split(":")
        if (parts.size !in 2..3) return 0
        var seconds = 0
        for (part in parts) {
            val value = part.trim().toIntOrNull() ?: return 0
            if (value < 0) return 0
            seconds = seconds * 60 + value
        }
        return seconds
    }

    fun parseCount(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val cleaned = raw.trim().replace('\u00a0', ' ').lowercase()
        val match = COUNT.find(cleaned) ?: return 0L
        val number = match.groupValues[1].trim('.', ',')
        if (number.isEmpty()) return 0L
        val suffix = match.groupValues[2].firstOrNull()
        val normalized = normalizeNumber(number, suffix != null)
        val base = normalized.toDoubleOrNull() ?: return 0L
        val multiplier = when (suffix) {
            'k' -> 1_000L
            'm' -> 1_000_000L
            'b' -> 1_000_000_000L
            else -> 1L
        }
        return (base * multiplier).toLong()
    }

    private fun normalizeNumber(raw: String, hasSuffix: Boolean): String {
        val value = raw.trim().trim('.', ',')
        if (value.isEmpty()) return ""
        val comma = value.lastIndexOf(',')
        val dot = value.lastIndexOf('.')
        if (comma >= 0 && dot >= 0) {
            return if (comma > dot) {
                value.replace(".", "").replace(',', '.')
            } else {
                value.replace(",", "")
            }
        }
        if (comma >= 0) {
            val pieces = value.split(',')
            if (pieces.size > 2) return value.replace(",", "")
            val decimals = pieces.getOrNull(1)?.length ?: 0
            val useDecimal = decimals in 1..2 && (hasSuffix || pieces[0].length <= 2)
            return if (useDecimal) pieces[0] + "." + pieces[1] else value.replace(",", "")
        }
        if (dot >= 0) {
            val pieces = value.split('.')
            if (pieces.size > 2) return value.replace(".", "")
            return value
        }
        return value
    }

    private fun primitiveText(element: JsonElement?): String? {
        if (element == null || element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return null
        return primitive.content
    }

    private fun balancedSlice(text: String, start: Int, open: Char, close: Char): String? {
        if (start !in text.indices || text[start] != open) return null
        var depth = 0
        var inString = false
        var escaped = false
        var index = start
        while (index < text.length) {
            val ch = text[index]
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
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) return text.substring(start, index + 1)
                    }
                }
            }
            index++
        }
        return null
    }

    private fun vkeyFromHref(href: String): String {
        if (href.isBlank()) return ""
        val marker = "viewkey="
        val index = href.indexOf(marker)
        if (index < 0) return ""
        return href.substring(index + marker.length)
            .substringBefore('&')
            .substringBefore('#')
            .substringBefore('?')
            .trim()
    }

    private fun uploaderSlugFromHref(href: String): String? {
        if (href.isBlank()) return null
        for (prefix in UPLOADER_PREFIXES) {
            val index = href.indexOf(prefix)
            if (index < 0) continue
            val slug = href.substring(index + prefix.length)
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
                .trim()
            if (slug.isNotEmpty()) return slug
        }
        return null
    }

    private fun linkName(link: Element): String? {
        val direct = firstText(link.attr("title"), link.text())
        if (direct.isNotBlank()) return direct
        val alt = link.selectFirst("img[alt]")?.attr("alt")
        return firstText(alt).takeIf { it.isNotBlank() }
    }

    private fun isUploaderUrl(raw: String): Boolean =
        UPLOADER_PREFIXES.any { raw.contains(it) }

    private fun isImageUrl(raw: String): Boolean {
        val value = raw.trim()
        if (!value.startsWith("http") && !value.startsWith("//")) return false
        val lower = value.lowercase()
        if (lower.contains("view_video")) return false
        if (lower.contains("verified-badge") || lower.contains("channel-badge")) return false
        // The first absolute URL in a related tuple is the poster. Exclude the
        // preview stream that follows it; every other CDN shape is accepted,
        // including protocol-relative URLs and extensionless thumb proxies.
        return !lower.contains(".mp4") &&
            !lower.contains(".webm") &&
            !lower.contains(".m3u8") &&
            !lower.contains(".m4v")
    }

    private fun isClockToken(raw: String): Boolean = raw.length <= 9 && CLOCK.matches(raw)

    private fun firstText(vararg values: String?): String {
        for (value in values) {
            val cleaned = decodeText(value)
            if (cleaned.isNotEmpty()) return cleaned
        }
        return ""
    }

    private fun firstUrl(vararg values: String?): String {
        for (value in values) {
            val raw = value?.trim().orEmpty()
            if (raw.isEmpty() || raw.startsWith("data:")) continue
            return normalizeUrl(raw)
        }
        return ""
    }

    private fun normalizeUrl(raw: String): String {
        var value = raw.trim()
        if (value.isEmpty()) return ""
        if (value.startsWith("//")) value = "https:" + value
        if (value.startsWith("http://", ignoreCase = true)) {
            value = "https://" + value.substring("http://".length)
        }
        return value
    }

    private fun decodeText(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        return JsoupParser.unescapeEntities(raw, false)
            .replace('\u00a0', ' ')
            .replace(WHITESPACE, " ")
            .trim()
    }

    private fun parseDocument(html: String): Document? =
        runCatching { Jsoup.parse(html) }.getOrNull()
}
