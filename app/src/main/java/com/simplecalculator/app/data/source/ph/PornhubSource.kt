package com.simplecalculator.app.data.source.ph

import com.simplecalculator.app.data.source.CommentItem
import com.simplecalculator.app.data.source.DetailPage
import com.simplecalculator.app.data.source.ListingKind
import com.simplecalculator.app.data.source.ListingPage
import com.simplecalculator.app.data.source.Source
import com.simplecalculator.app.data.source.VideoDetail
import com.simplecalculator.app.data.source.VideoSummary
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.URLEncoder

class PornhubSource(http: OkHttpClient) : Source {

    private val client = PhClient(http)

    override val id: String = "ph"
    override val displayName: String = "Pornhub"

    override suspend fun listing(
        kind: ListingKind,
        page: Int,
        query: String?,
        slug: String?
    ): ListingPage {
        val path = when (kind) {
            ListingKind.TRENDING -> PhClient.BASE + "/video?o=mv&page=" + page
            ListingKind.SEARCH -> PhClient.BASE + "/video/search?search=" +
                URLEncoder.encode(query ?: "", "UTF-8") + "&page=" + page
            ListingKind.STAR -> PhClient.BASE + "/model/" + (slug ?: "") + "/videos?page=" + page
        }
        if (kind == ListingKind.STAR) {
            val first = runCatching { client.get(path) }.getOrNull()
            if (first != null) {
                val items = PhParser.parseList(first.body, id)
                if (items.isNotEmpty()) return ListingPage(items, first.stale)
            }
            // Some model pages redirect the /videos suffix to their profile.
            val fallback = client.get(PhClient.BASE + "/model/" + (slug ?: "") + "?page=" + page)
            return ListingPage(PhParser.parseList(fallback.body, id), fallback.stale)
        }
        val pageResult = client.get(path)
        val items = PhParser.parseList(pageResult.body, id)
        if (items.isEmpty() && !pageResult.stale && PhParser.isBlockedPage(pageResult.body)) {
            throw IOException("Pornhub returned an age-verification page")
        }
        return ListingPage(items, pageResult.stale)
    }

    override suspend fun starAvatar(slug: String): String? {
        val page = runCatching { client.get(PhClient.BASE + "/model/" + slug) }.getOrNull() ?: return null
        return PhParser.parseStarAvatar(page.body)
    }

    override suspend fun detail(vkey: String): DetailPage {
        val page = client.get(PhClient.BASE + "/view_video.php?viewkey=" + vkey)
        val detail = PhParser.parseDetail(page.body)
        if (detail.streams.isEmpty() && detail.embedUrl == null && !page.stale &&
            PhParser.isBlockedPage(page.body)
        ) {
            throw IOException("Pornhub returned an age-verification page")
        }
        return DetailPage(detail, page.stale)
    }

    override suspend fun comments(vkey: String, commentsUrl: String?, offset: Int): List<CommentItem> {
        val url = if (commentsUrl != null) {
            commentsUrl + (if (commentsUrl.contains("offset=")) "" else "&offset=" + offset)
        } else {
            "/comment/show?id=" + vkey + "&limit=20&what=video&popular=0&offset=" + offset
        }
        val page = client.getWithXhr(url, PhClient.BASE + "/view_video.php?viewkey=" + vkey)
        return PhParser.parseComments(page.body)
    }

    override suspend fun related(vkey: String, relatedUrl: String?): List<VideoSummary> {
        val url = relatedUrl ?: ("/api/v1/video/player_related_datas?id=" + vkey)
        val page = client.getWithXhr(url, PhClient.BASE + "/view_video.php?viewkey=" + vkey)
        return PhParser.parseRelated(page.body, id)
    }
}
