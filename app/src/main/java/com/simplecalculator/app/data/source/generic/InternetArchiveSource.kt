package com.simplecalculator.app.data.source.generic

import com.simplecalculator.app.data.source.Category
import com.simplecalculator.app.data.source.CommentItem
import com.simplecalculator.app.data.source.ListingKind
import com.simplecalculator.app.data.source.PlayerMode
import com.simplecalculator.app.data.source.Source
import com.simplecalculator.app.data.source.StreamVariant
import com.simplecalculator.app.data.source.VideoDetail
import com.simplecalculator.app.data.source.VideoSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

/** archive.org advancedsearch + metadata APIs, following MISSPipe's endpoints. */
class InternetArchiveSource(private val http: OkHttpClient) : Source {

    override val id = "ia"
    override val displayName = "Internet Archive"
    override val category = Category.ARCHIVE
    override val playerMode = PlayerMode.NATIVE
    override val supportsStars = false

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (compatible; CalculatorVault/1.0)")
            .header("Accept", "application/json")
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
        val q = when (kind) {
            ListingKind.SEARCH -> "(${query.orEmpty().trim()}) AND mediatype:movies"
            else -> "mediatype:movies"
        }
        val url = "https://archive.org/advancedsearch.php".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("fl[]", "identifier")
            .addQueryParameter("fl[]", "title")
            .addQueryParameter("rows", "24")
            .addQueryParameter("page", page.coerceAtLeast(1).toString())
            .addQueryParameter("output", "json")
            .apply {
                if (kind != ListingKind.SEARCH) {
                    addQueryParameter("sort[]", "downloads desc")
                }
            }
            .build()
            .toString()
        return parseListing(get(url))
    }

    internal fun parseListing(raw: String): List<VideoSummary> {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return emptyList()
        val docs = (root["response"] as? JsonObject)?.get("docs") as? JsonArray
            ?: return emptyList()
        return docs.mapNotNull { element ->
            val doc = element as? JsonObject ?: return@mapNotNull null
            val identifier = doc.string("identifier") ?: return@mapNotNull null
            val title = doc.string("title")?.takeIf { it.isNotBlank() } ?: identifier
            VideoSummary(
                sourceId = id,
                vkey = identifier,
                title = title,
                thumb = "https://archive.org/services/img/${encode(identifier)}",
                durationSec = 0,
                views = 0,
                uploaderSlug = null,
                uploaderName = null,
                verifiedUploader = false,
                category = category
            )
        }.distinctBy { it.vkey }
    }

    override suspend fun detail(vkey: String): VideoDetail {
        val raw = runCatching { get("https://archive.org/metadata/${encode(vkey)}") }.getOrNull()
            ?: return VideoDetail(playerMode = PlayerMode.NATIVE)
        return parseDetail(raw, vkey)
    }

    internal fun parseDetail(raw: String, vkey: String): VideoDetail {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return VideoDetail(playerMode = PlayerMode.NATIVE)
        val files = root["files"] as? JsonArray ?: return VideoDetail(playerMode = PlayerMode.NATIVE)
        val streams = LinkedHashMap<String, StreamVariant>()
        for (element in files) {
            val file = element as? JsonObject ?: continue
            val name = file.string("name") ?: continue
            if (!name.contains(Regex("""\.(mp4|m3u8)$""", RegexOption.IGNORE_CASE))) continue
            val url = "https://archive.org/download/${encode(vkey)}/${encode(name)}"
            val quality = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
                .find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val hls = name.endsWith(".m3u8", ignoreCase = true)
            streams.putIfAbsent(
                url,
                StreamVariant(
                    quality = quality,
                    label = if (quality > 0) "${quality}p" else if (hls) "HLS" else "MP4",
                    url = url,
                    hls = hls
                )
            )
        }
        return VideoDetail(
            streams = streams.values.sortedByDescending { it.quality },
            playerMode = PlayerMode.NATIVE
        )
    }

    override suspend fun comments(vkey: String, commentsUrl: String?, offset: Int): List<CommentItem> =
        emptyList()

    override suspend fun related(vkey: String, relatedUrl: String?): List<VideoSummary> =
        emptyList()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
