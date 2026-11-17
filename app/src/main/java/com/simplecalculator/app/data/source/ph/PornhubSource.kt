package com.simplecalculator.app.data.source.ph

import com.simplecalculator.app.data.source.CommentItem
import com.simplecalculator.app.data.source.ListingKind
import com.simplecalculator.app.data.source.Source
import com.simplecalculator.app.data.source.VideoDetail
import com.simplecalculator.app.data.source.VideoSummary
import okhttp3.OkHttpClient
import java.net.URLEncoder

class PornhubSource(http: OkHttpClient) : Source {

    private val client = PhClient(http)

    override val id: String = "ph"
    override val displayName: String = "Pornhub"
    override val category = com.simplecalculator.app.data.source.Category.LIVE
    override val playerMode = com.simplecalculator.app.data.source.PlayerMode.NATIVE
    override val supportsStars: Boolean = true

    override suspend fun listing(
        kind: ListingKind,
        page: Int,
        query: String?,
        slug: String?
    ): List<VideoSummary> {
        val path = when (kind) {
            ListingKind.TRENDING -> PhClient.BASE + "/video?o=mv&page=" + page
            ListingKind.SEARCH -> PhClient.BASE + "/video/search?search=" +
                URLEncoder.encode(query ?: "", "UTF-8") + "&page=" + page
            ListingKind.STAR -> PhClient.BASE + "/model/" + (slug ?: "") + "/videos?page=" + page
        }
        val html = if (kind == ListingKind.STAR) {
            runCatching { client.get(path) }
                .getOrElse { client.get(PhClient.BASE + "/model/" + (slug ?: "") + "?page=" + page) }
        } else {
            client.get(path)
        }
        return PhParser.parseList(html, id)
    }

    override suspend fun starAvatar(slug: String): String? {
        val html = runCatching { client.get(PhClient.BASE + "/model/" + slug) }.getOrNull() ?: return null
        val doc = org.jsoup.Jsoup.parse(html)
        return doc.selectFirst("img#getAvatar")?.attr("src")
            ?: doc.selectFirst(".modelProfile img")?.attr("src")
            ?: doc.selectFirst("link[rel=image_src]")?.attr("href")
    }

    override suspend fun detail(vkey: String): VideoDetail {
        val html = client.get(PhClient.BASE + "/view_video.php?viewkey=" + vkey)
        return PhParser.parseDetail(html)
    }

    override suspend fun comments(vkey: String, commentsUrl: String?, offset: Int): List<CommentItem> {
        val path = commentsUrl
        val url = if (path != null) {
            path + (if (path.contains("offset=")) "" else "&offset=" + offset)
        } else {
            "/comment/show?id=" + vkey + "&limit=20&what=video&popular=0&offset=" + offset
        }
        val html = client.getWithXhr(url, PhClient.BASE + "/view_video.php?viewkey=" + vkey)
        return PhParser.parseComments(html)
    }

    override suspend fun related(vkey: String, relatedUrl: String?): List<VideoSummary> {
        val url = relatedUrl ?: ("/api/v1/video/player_related_datas?id=" + vkey)
        val raw = client.getWithXhr(url, PhClient.BASE + "/view_video.php?viewkey=" + vkey)
        return PhParser.parseRelated(raw, id)
    }
}
