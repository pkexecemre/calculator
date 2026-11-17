package com.simplecalculator.app.data.source.hentai

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

/**
 * Iwara has a real JSON API, which makes it the most stable source in the app:
 *
 *   GET /videos?limit=24&page=N          -> catalog
 *   GET /video/<id>                       -> { fileUrl }
 *   GET <fileUrl>                         -> [ { name: "360", src: { view: "//cdn/view?hash=…" } } ]
 *
 * The rendition URL is a plain MP4 with Range support, so it plays and downloads
 * like any other native source.
 */
open class IwaraSource(
    private val http: OkHttpClient,
    override val id: String = "iw",
    override val displayName: String = "Iwara",
    private val web: String = "https://www.iwara.tv"
) : Source {

    override val category: Category = Category.HENTAI
    override val playerMode: PlayerMode = PlayerMode.NATIVE
    override val supportsStars: Boolean = false

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .header("Referer", web + "/")
            .header("Origin", web)
            .header("X-Site", web.removePrefix("https://"))
            .header("X-Version", API_VERSION)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP " + response.code + " " + url)
            response.body?.string() ?: ""
        }
    }

    override suspend fun listing(
        kind: ListingKind,
        page: Int,
        query: String?,
        slug: String?
    ): List<VideoSummary> {
        val base = "https://api.iwara.tv/videos?limit=24&page=" + (page - 1).coerceAtLeast(0)
        val url = when (kind) {
            ListingKind.SEARCH -> base + "&query=" + URLEncoder.encode(query ?: "", "UTF-8")
            else -> base + "&sort=" + if (page <= 1) "recent" else "recent"
        }
        return parseListing(runCatching { get(url) }.getOrElse { "" })
    }

    internal fun parseListing(raw: String): List<VideoSummary> {
        val root = runCatching { json.parseToJsonElement(raw) as JsonObject }.getOrNull() ?: return emptyList()
        val results = root["results"] as? JsonArray ?: return emptyList()
        val out = ArrayList<VideoSummary>()
        for (element in results) {
            val video = element as? JsonObject ?: continue
            val id = (video["id"] as? JsonPrimitive)?.content ?: continue
            val title = (video["title"] as? JsonPrimitive)?.content ?: ""
            val file = video["file"] as? JsonObject
            val fileId = (file?.get("id") as? JsonPrimitive)?.content
            val thumbIndex = (video["thumbnail"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
            val duration = (file?.get("duration") as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
            val thumb = if (fileId != null) {
                "https://i.iwara.tv/image/thumbnail/" + fileId + "/thumbnail-" +
                    thumbIndex.toString().padStart(2, '0') + ".jpg"
            } else ""
            if (!Tags.isAllowed(emptyList(), title)) continue
            out.add(
                VideoSummary(
                    sourceId = id,
                    vkey = id,
                    title = title,
                    thumb = thumb,
                    durationSec = duration,
                    views = 0,
                    uploaderSlug = null,
                    uploaderName = null,
                    verifiedUploader = false,
                    category = Category.HENTAI,
                    flavor = HentaiFlavor.USER
                )
            )
        }
        return out
    }

    override suspend fun detail(vkey: String): VideoDetail {
        val detailRaw = runCatching { get("https://api.iwara.tv/video/" + vkey) }.getOrElse { "" }
        val detail = runCatching { json.parseToJsonElement(detailRaw) as JsonObject }.getOrNull()
        val fileUrl = (detail?.get("fileUrl") as? JsonPrimitive)?.content ?: return VideoDetail(playerMode = PlayerMode.NATIVE)
        val renditionsRaw = runCatching { get(fileUrl) }.getOrElse { "" }
        return VideoDetail(streams = parseRenditions(renditionsRaw), playerMode = PlayerMode.NATIVE)
    }

    internal fun parseRenditions(raw: String): List<StreamVariant> {
        val array = runCatching { json.parseToJsonElement(raw) as JsonArray }.getOrNull() ?: return emptyList()
        val out = ArrayList<StreamVariant>()
        for (element in array) {
            val rendition = element as? JsonObject ?: continue
            val name = (rendition["name"] as? JsonPrimitive)?.content ?: "video"
            val view = ((rendition["src"] as? JsonObject)?.get("view") as? JsonPrimitive)?.content ?: continue
            val url = if (view.startsWith("//")) "https:" + view else view
            val quality = when {
                name.contains("1080") -> 1080
                name.contains("720") -> 720
                name.contains("480") -> 480
                name.contains("360") -> 360
                name == "source" -> 1000
                name == "preview" -> 0
                else -> 500
            }
            if (name == "preview") continue
            out.add(StreamVariant(quality = quality, label = name, url = url, hls = url.contains(".m3u8")))
        }
        return out.distinctBy { it.url }
    }

    override suspend fun comments(vkey: String, commentsUrl: String?, offset: Int): List<CommentItem> =
        emptyList()

    override suspend fun related(vkey: String, relatedUrl: String?): List<VideoSummary> = emptyList()

    companion object {
        private const val API_VERSION = "b25f20ee9a87452f0c4b36e4bd53264b4eb37f46"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
