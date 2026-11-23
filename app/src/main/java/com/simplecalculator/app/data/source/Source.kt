package com.simplecalculator.app.data.source

/**
 * The app ships one source. The enum is kept because cached rows and the
 * player's language hint persist it, but only [LIVE] is produced by the
 * Pornhub client.
 */
enum class Category {
    LIVE, JAV, HENTAI, ARCHIVE;

    /** Sections behind the additional 18+ confirmation. */
    val requiresGate: Boolean get() = this == JAV || this == HENTAI
}

/**
 * NATIVE: the app resolves a stream URL and plays it in Media3.
 * EMBED:  the source's own player is the only way in; rendered in an isolated
 *         WebView with ad/tracker hosts blocked.
 */
enum class PlayerMode { NATIVE, EMBED }

data class VideoSummary(
    val sourceId: String,
    val vkey: String,
    val title: String,
    val thumb: String,
    val durationSec: Int,
    val views: Long,
    val uploaderSlug: String?,
    val uploaderName: String?,
    val verifiedUploader: Boolean,
    val category: Category = Category.LIVE,
    val tags: List<String> = emptyList()
) {
    val key: String get() = sourceId + ":" + vkey
}

data class StreamVariant(
    val quality: Int,
    val label: String,
    val url: String,
    val hls: Boolean
)

data class VideoDetail(
    val streams: List<StreamVariant> = emptyList(),
    val commentsUrl: String? = null,
    val relatedUrl: String? = null,
    val tags: List<String> = emptyList(),
    val uploaderSlug: String? = null,
    val uploaderName: String? = null,
    val embedUrl: String? = null,
    val playerMode: PlayerMode = PlayerMode.NATIVE
)

data class CommentItem(
    val author: String,
    val avatar: String?,
    val text: String,
    val date: String,
    val votes: Int,
    val verified: Boolean
)

enum class ListingKind { TRENDING, SEARCH, STAR }

/** A listing plus whether it came from the offline HTTP cache. */
data class ListingPage(
    val items: List<VideoSummary> = emptyList(),
    val stale: Boolean = false
)

/** A resolved video plus whether it came from the offline HTTP cache. */
data class DetailPage(
    val detail: VideoDetail = VideoDetail(),
    val stale: Boolean = false
)

interface Source {
    val id: String
    val displayName: String

    suspend fun listing(kind: ListingKind, page: Int, query: String?, slug: String?): ListingPage

    suspend fun detail(vkey: String): DetailPage

    suspend fun comments(vkey: String, commentsUrl: String?, offset: Int): List<CommentItem>

    suspend fun related(vkey: String, relatedUrl: String?): List<VideoSummary>

    /** Optional: a star's avatar, used to fill the follow card and star header. */
    suspend fun starAvatar(slug: String): String? = null
}

class SourceRegistry(private val sources: List<Source>) {

    private val byId = sources.associateBy { it.id }

    fun byId(id: String): Source? = byId[id]

    fun primary(): Source = sources.first()

    fun all(): List<Source> = sources
}
