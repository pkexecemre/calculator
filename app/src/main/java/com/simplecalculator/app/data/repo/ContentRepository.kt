package com.simplecalculator.app.data.repo

import android.content.Context
import android.content.SharedPreferences
import com.simplecalculator.app.data.db.AppDb
import com.simplecalculator.app.data.db.EventEntity
import com.simplecalculator.app.data.db.FavoriteEntity
import com.simplecalculator.app.data.db.HistoryEntity
import com.simplecalculator.app.data.db.StarEntity
import com.simplecalculator.app.data.db.VideoEntity
import com.simplecalculator.app.data.net.NetworkErrors
import com.simplecalculator.app.data.rank.Ranker
import com.simplecalculator.app.data.source.Category
import com.simplecalculator.app.data.source.CommentItem
import com.simplecalculator.app.data.source.ContentFilters
import com.simplecalculator.app.data.source.FeedPolicy
import com.simplecalculator.app.data.source.JavTitles
import com.simplecalculator.app.data.source.ListingKind
import com.simplecalculator.app.data.source.Source
import com.simplecalculator.app.data.source.SourceRegistry
import com.simplecalculator.app.data.source.VideoDetail
import com.simplecalculator.app.data.source.VideoSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Two-source repository: Pornhub (LIVE) and MISSAV (JAV).
 *
 * Every read either returns fresh data, returns the last good data with
 * `stale = true`, or returns an empty list plus a human-readable error. It never
 * throws at the screen and it never blanks a previously loaded feed because a
 * refresh failed. Category-specific policy lives here so the sources stay dumb.
 */
class ContentRepository(
    context: Context,
    private val db: AppDb,
    private val sources: SourceRegistry,
    private val ranker: Ranker,
    private val hidden: com.simplecalculator.app.core.HiddenStore? = null
) {
    data class FeedResult(
        val items: List<VideoSummary> = emptyList(),
        val stale: Boolean = false,
        val error: String? = null
    )

    data class SearchResult(
        val items: List<VideoSummary> = emptyList(),
        val stale: Boolean = false,
        val error: String? = null
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences("feed_cache", Context.MODE_PRIVATE)

    private val pageCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<VideoSummary>>>()
    private val searchCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<VideoSummary>>>()
    private val detailCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, VideoDetail>>()
    private val commentsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<CommentItem>>>()
    private val relatedCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<VideoSummary>>>()

    private val source: Source get() = sources.primary()

    fun sourceFor(key: String): Source? = sources.byId(key.substringBefore(":"))

    /** Off by default: one Japanese watch must not take over the feed. */
    fun showJapaneseInFeed(): Boolean = prefs.getBoolean(KEY_SHOW_JAPANESE, false)

    fun setJapaneseInFeed(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_JAPANESE, enabled).apply()
        // Cached pages were ranked under the old policy; drop them so the next
        // feed load immediately reflects the switch.
        pageCache.clear()
        searchCache.clear()
    }

    /** The "hide Japanese" rule belongs to the Pornhub feed only. */
    private fun List<VideoSummary>.feedVisible(category: Category): List<VideoSummary> {
        if (category != Category.LIVE) return this
        val show = showJapaneseInFeed()
        return filter { FeedPolicy.visibleInFeed(it, show) }
    }

    /**
     * Category-specific feed policy. JAV keeps MISSAV's release rules:
     * hidden-camera/compilation titles are dropped, Uncensored falls back to
     * the full page when nothing matches, Subtitled is a strict promise.
     */
    private fun applyFeedPolicy(
        items: List<VideoSummary>,
        category: Category,
        releaseFilter: String?
    ): List<VideoSummary> {
        val base = items
            .filter { it.vkey.isNotBlank() && it.title.isNotBlank() }
            .distinctBy { it.key }
            .withoutHidden()

        val filtered = when (category) {
            Category.LIVE -> base.feedVisible(category)
            Category.JAV -> base
                .filterNot { ContentFilters.isLowQuality(it.title, it.vkey) }
                .let { filterReleases(it, releaseFilter) }
            else -> base
        }
        // Never blank the Pornhub feed over a language policy.
        return if (filtered.isEmpty() && base.isNotEmpty() && category == Category.LIVE) base else filtered
    }

    private fun filterReleases(
        items: List<VideoSummary>,
        releaseFilter: String?
    ): List<VideoSummary> = when (releaseFilter) {
        RELEASE_UNCENSORED -> items
            .filter { JavTitles.isUncensored(it.title, it.vkey) }
            .ifEmpty { items }
        RELEASE_SUBTITLED -> items.filter { JavTitles.isSubtitled(it.title, it.vkey) }
        else -> items
    }

    // ---------------------------------------------------------------- feed

    /**
     * Room-backed first paint. The ordered keys of the last successful page 1
     * are stored in SharedPreferences, so an offline launch shows exactly the
     * feed the viewer saw last time instead of an arbitrary sample.
     */
    suspend fun cachedFeed(
        category: Category = Category.LIVE,
        limit: Int = 60,
        releaseFilter: String? = null
    ): List<VideoSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val keys = prefs.getString(feedKeysKey(category), null)
                ?.split('\n')
                ?.filter { it.isNotBlank() }
                ?.take(limit)
                .orEmpty()
            val rows = if (keys.isNotEmpty()) {
                val byKey = db.videos().byKeys(keys).associateBy { it.key }
                keys.mapNotNull { byKey[it] }
            } else {
                db.videos().byCategory(category.name).take(limit)
            }
            val visible = rows.map { it.toSummary() }.withoutHidden().feedVisible(category)
            if (category == Category.JAV) filterReleases(visible, releaseFilter) else visible
        }.getOrDefault(emptyList())
    }

    suspend fun feed(
        category: Category = Category.LIVE,
        page: Int = 1,
        releaseFilter: String? = null
    ): FeedResult = withContext(Dispatchers.IO) {
        val cacheKey = "p:${category.name}:$page:${releaseFilter.orEmpty()}"
        // Page 1 is refreshed explicitly by the UI; serving it from the short
        // page cache would make the refresh button a no-op. Later pages may use
        // the cache because scrolling fires them repeatedly.
        if (page != 1) {
            pageCache[cacheKey]?.let { (at, items) ->
                if (System.currentTimeMillis() - at < PAGE_TTL_MS) {
                    return@withContext FeedResult(
                        items.withoutHidden().feedVisible(category)
                    )
                }
            }
        }

        val active = sources.byCategory(category)
        if (active.isEmpty()) {
            return@withContext FeedResult(error = "No source for ${category.name}")
        }

        try {
            val listings = active.map { src ->
                withTimeout(SOURCE_TIMEOUT_MS) {
                    src.listing(ListingKind.TRENDING, page, null, null)
                }
            }
            val rawItems = listings.flatMap { it.items }
            val pool = applyFeedPolicy(rawItems, category, releaseFilter)
            val stale = listings.any { it.stale }

            // An empty release pill is a valid answer, not a network failure.
            if (pool.isEmpty() && category == Category.JAV &&
                releaseFilter != null && rawItems.isNotEmpty()
            ) {
                return@withContext FeedResult(emptyList())
            }

            if (pool.isEmpty()) {
                // A genuinely empty page 1 means the site answered with
                // something other than a feed. Keep the last good screen.
                if (page == 1) {
                    val fallback = pageCache[cacheKey]?.second.orEmpty()
                        .ifEmpty { cachedFeed(category, releaseFilter = releaseFilter) }
                        .feedVisible(category)
                    return@withContext FeedResult(
                        fallback,
                        stale = fallback.isNotEmpty(),
                        error = "The source returned an empty feed"
                    )
                }
                return@withContext FeedResult(emptyList())
            }

            // On LIVE, Japanese catalogue uploads are held back by default; if
            // that would leave nothing, they stay but are ranked last.
            val suppressed = category == Category.LIVE &&
                !showJapaneseInFeed() &&
                pool.any { FeedPolicy.isJapaneseCatalog(it) }
            val penalty: (VideoSummary) -> Double = if (suppressed) {
                { FeedPolicy.rankPenalty(it, showJapaneseInFeed()) }
            } else {
                { 0.0 }
            }

            upsertAll(pool)
            val ordered = rank(pool, category, penalty)
            putPage(cacheKey, ordered)
            if (page == 1) storeFeedKeys(category, ordered.map { it.key })
            FeedResult(ordered, stale = stale)
        } catch (e: TimeoutCancellationException) {
            offlineFeed(cacheKey, category, page, releaseFilter, "The source timed out")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            offlineFeed(cacheKey, category, page, releaseFilter, NetworkErrors.describe(e))
        }
    }

    private suspend fun offlineFeed(
        cacheKey: String,
        category: Category,
        page: Int,
        releaseFilter: String?,
        error: String
    ): FeedResult {
        val fallback = pageCache[cacheKey]?.second.orEmpty()
            .ifEmpty { if (page == 1) cachedFeed(category, releaseFilter = releaseFilter) else emptyList() }
            .feedVisible(category)
        return FeedResult(
            items = fallback,
            stale = fallback.isNotEmpty(),
            error = error
        )
    }

    /** Prefetches the next page; failures are irrelevant because the UI has cache. */
    suspend fun prefetchPage(
        category: Category = Category.LIVE,
        page: Int,
        releaseFilter: String? = null
    ) {
        runCatching { feed(category, page, releaseFilter) }
    }

    private suspend fun rank(
        items: List<VideoSummary>,
        category: Category,
        penalty: (VideoSummary) -> Double = { 0.0 }
    ): List<VideoSummary> = runCatching {
        val followed = db.stars().followedNow().map { it.rawSlug }.toSet()
        // One watch is not a preference. Require a second view before an
        // uploader can push their whole catalogue into the feed.
        val affinity = db.events().recentUploaders().groupingBy { it }.eachCount()
            .filterValues { it >= AFFINITY_MIN_VIEWS }
        val seen = db.events().recentVideos().toSet()
        val categoryBonus = ContentFilters.rankBonus(category)
        val ranked = ranker.rank(
            candidates = items,
            followedSlugs = followed,
            affinity = affinity,
            seen = seen,
            bonus = { video -> categoryBonus(video) + penalty(video) }
        )
        val isStarred: (VideoSummary) -> Boolean =
            { it.uploaderSlug != null && it.uploaderSlug in followed }
        ranker.mix(ranked.filterNot(isStarred), ranked.filter(isStarred))
    }.getOrDefault(items)

    private fun putPage(key: String, items: List<VideoSummary>) {
        if (pageCache.size > 24) pageCache.clear()
        pageCache[key] = System.currentTimeMillis() to items
    }

    private fun feedKeysKey(category: Category): String =
        if (category == Category.LIVE) KEY_FEED_KEYS else KEY_FEED_KEYS + ":" + category.name

    private fun storeFeedKeys(category: Category, keys: List<String>) {
        prefs.edit()
            .putString(feedKeysKey(category), keys.take(60).joinToString("\n"))
            .putLong(KEY_FEED_AT, System.currentTimeMillis())
            .apply()
    }

    // -------------------------------------------------------------- search

    suspend fun search(
        category: Category = Category.LIVE,
        query: String,
        page: Int = 1
    ): SearchResult = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isBlank()) return@withContext SearchResult()
        val cacheKey = "s:${category.name}:" + clean.lowercase() + ":" + page
        if (page != 1) {
            searchCache[cacheKey]?.let { (at, items) ->
                if (System.currentTimeMillis() - at < SEARCH_TTL_MS) {
                    return@withContext SearchResult(items.withoutHidden())
                }
            }
        }

        val active = sources.byCategory(category)
        if (active.isEmpty()) return@withContext SearchResult(error = "No source for ${category.name}")

        try {
            val listings = active.map { src ->
                withTimeout(SOURCE_TIMEOUT_MS) {
                    src.listing(ListingKind.SEARCH, page, clean, null)
                }
            }
            val raw = listings.flatMap { it.items }.withoutHidden()
            // MISSAV mirrors the same release with subtitle/uncensored suffixes;
            // keep one card per catalogue cut, like the old JAV search did.
            val visible = if (category == Category.JAV) {
                raw.distinctBy { ContentFilters.releaseIdentity(it.title, it.vkey) ?: it.key }
            } else {
                raw.distinctBy { it.key }
            }
            if (visible.isNotEmpty()) {
                upsertAll(visible)
                if (searchCache.size > 24) searchCache.clear()
                searchCache[cacheKey] = System.currentTimeMillis() to visible
            }
            SearchResult(visible, stale = listings.any { it.stale })
        } catch (e: TimeoutCancellationException) {
            offlineSearch(cacheKey, category, clean, "The source timed out")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            offlineSearch(cacheKey, category, clean, NetworkErrors.describe(e))
        }
    }

    private suspend fun offlineSearch(
        cacheKey: String,
        category: Category,
        query: String,
        error: String
    ): SearchResult {
        val fallback = searchCache[cacheKey]?.second.orEmpty().ifEmpty {
            cachedFeed(category).filter { it.title.contains(query, ignoreCase = true) }.take(30)
        }
        return SearchResult(fallback, stale = fallback.isNotEmpty(), error = error)
    }

    // ---------------------------------------------------------------- stars

    suspend fun starFeed(slug: String, page: Int): List<VideoSummary> = withContext(Dispatchers.IO) {
        val cacheKey = "star:$slug:$page"
        pageCache[cacheKey]?.let { (at, items) ->
            if (System.currentTimeMillis() - at < PAGE_TTL_MS) return@withContext items.withoutHidden()
        }
        try {
            val items = withTimeout(SOURCE_TIMEOUT_MS) {
                source.listing(ListingKind.STAR, page, null, slug)
            }.items.withoutHidden().distinctBy { it.key }
            if (items.isNotEmpty()) {
                upsertAll(items)
                putPage(cacheKey, items)
                items
            } else {
                cachedStar(slug)
            }
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) cachedStar(slug) else throw e
        } catch (e: Exception) {
            cachedStar(slug)
        }
    }

    private suspend fun cachedStar(slug: String): List<VideoSummary> =
        db.videos().byUploader(slug).map { it.toSummary() }.withoutHidden()

    // --------------------------------------------------------------- detail

    suspend fun detailFor(video: VideoEntity): VideoDetail = withContext(Dispatchers.IO) {
        val cached = detailCache[video.key]
        if (cached != null && System.currentTimeMillis() - cached.first < DETAIL_TTL_MS) {
            return@withContext cached.second
        }
        try {
            val pageResult = withTimeout(SOURCE_TIMEOUT_MS) {
                (sourceFor(video.key) ?: source).detail(video.vkey)
            }
            val detail = pageResult.detail
            if (detail.streams.isNotEmpty() || detail.embedUrl != null) {
                if (detailCache.size > 40) detailCache.clear()
                detailCache[video.key] = System.currentTimeMillis() to detail
                detail
            } else {
                // Unavailable/premium page: keep the previous good resolution if
                // we have one instead of replacing it with an empty player.
                cached?.second ?: detail
            }
        } catch (e: TimeoutCancellationException) {
            cached?.second ?: throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cached?.second ?: throw e
        }
    }

    /** Warms the detail cache; failures are irrelevant here. */
    suspend fun prefetchDetail(key: String) {
        val video = db.videos().byKey(key) ?: return
        if (detailCache.containsKey(key)) return
        runCatching { detailFor(video) }
    }

    // --------------------------------------------------------- comments / related

    suspend fun comments(
        vkey: String,
        sourceId: String,
        commentsUrl: String?,
        offset: Int
    ): List<CommentItem> = withContext(Dispatchers.IO) {
        val cacheKey = sourceId + ":" + vkey + ":" + offset
        commentsCache[cacheKey]?.let { (at, items) ->
            if (System.currentTimeMillis() - at < COMMENTS_TTL_MS) return@withContext items
        }
        val items = runCatching {
            withTimeout(SOURCE_TIMEOUT_MS) {
                sources.byId(sourceId)?.comments(vkey, commentsUrl, offset) ?: emptyList()
            }
        }.getOrDefault(emptyList())
        if (items.isNotEmpty() && offset == 0) {
            if (commentsCache.size > 40) commentsCache.clear()
            commentsCache[cacheKey] = System.currentTimeMillis() to items
        }
        items
    }

    suspend fun related(vkey: String, sourceId: String, relatedUrl: String?): List<VideoSummary> =
        withContext(Dispatchers.IO) {
            val cacheKey = sourceId + ":" + vkey
            relatedCache[cacheKey]?.let { (at, items) ->
                if (System.currentTimeMillis() - at < RELATED_TTL_MS) return@withContext items
            }
            val items = runCatching {
                withTimeout(SOURCE_TIMEOUT_MS) {
                    sources.byId(sourceId)?.related(vkey, relatedUrl) ?: emptyList()
                }
            }.getOrDefault(emptyList())
            if (items.isNotEmpty()) {
                if (relatedCache.size > 40) relatedCache.clear()
                relatedCache[cacheKey] = System.currentTimeMillis() to items
            }
            // Anything the UI can navigate to must exist in the cache, otherwise
            // the player opens with no title and no metadata.
            upsertAll(items)
            items
        }

    // -------------------------------------------------------------- library

    fun followedStars(): Flow<List<StarEntity>> = db.stars().followed()

    suspend fun toggleFollow(slug: String, name: String, avatar: String?) {
        val key = source.id + ":" + slug
        val existing = db.stars().bySlug(key)
        if (existing != null && existing.followed) {
            db.stars().upsert(existing.copy(followed = false))
        } else {
            db.stars().upsert(
                StarEntity(
                    slug = key,
                    sourceId = source.id,
                    rawSlug = slug,
                    name = name,
                    avatar = avatar ?: existing?.avatar,
                    followed = true,
                    followedAt = System.currentTimeMillis(),
                    hits = existing?.hits ?: 0
                )
            )
        }
    }

    suspend fun isFollowing(slug: String?): Boolean {
        if (slug == null) return false
        val star = db.stars().bySlug(source.id + ":" + slug) ?: return false
        return star.followed
    }

    fun history(): Flow<List<HistoryEntity>> = db.history().observeAll()

    fun favorites(): Flow<List<FavoriteEntity>> = db.favorites().observeAll()

    suspend fun progressFor(key: String): Long = db.history().byKey(key)?.positionMs ?: 0L

    suspend fun recordProgress(
        key: String,
        sourceId: String,
        title: String,
        thumb: String,
        positionMs: Long,
        durationMs: Long
    ) {
        if (positionMs < 5_000L) return
        db.history().upsert(
            HistoryEntity(
                key = key,
                sourceId = sourceId,
                title = title,
                thumb = thumb,
                positionMs = positionMs,
                durationMs = durationMs,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun removeHistory(key: String) = db.history().remove(key)

    /** Clears watch history *and* the ranking events the UI promises to reset. */
    suspend fun clearHistory() {
        db.history().clear()
        db.events().clear()
    }

    suspend fun isFavorite(key: String): Boolean = db.favorites().byKey(key) != null

    suspend fun toggleFavorite(key: String, sourceId: String, title: String, thumb: String): Boolean {
        val existing = db.favorites().byKey(key)
        return if (existing != null) {
            db.favorites().remove(key)
            false
        } else {
            db.favorites().add(
                FavoriteEntity(
                    key = key,
                    sourceId = sourceId,
                    title = title,
                    thumb = thumb,
                    addedAt = System.currentTimeMillis()
                )
            )
            true
        }
    }

    suspend fun cachedVideo(key: String): VideoEntity? = db.videos().byKey(key)

    suspend fun videoFor(key: String): VideoEntity? = db.videos().byKey(key)

    suspend fun applyUploader(key: String, slug: String?, name: String?) {
        if (slug.isNullOrBlank() || name.isNullOrBlank()) return
        val current = db.videos().byKey(key) ?: return
        if (current.uploaderSlug != null) return
        db.videos().upsert(listOf(current.copy(uploaderSlug = slug, uploaderName = name)))
        val starKey = source.id + ":" + slug
        if (db.stars().bySlug(starKey) == null) {
            db.stars().upsert(
                StarEntity(
                    slug = starKey,
                    sourceId = source.id,
                    rawSlug = slug,
                    name = name,
                    avatar = null,
                    followed = false,
                    followedAt = 0L,
                    hits = 0
                )
            )
        }
    }

    suspend fun recordView(video: VideoEntity, completed: Boolean) {
        db.events().add(
            EventEntity(
                videoKey = video.key,
                uploaderSlug = video.uploaderSlug,
                kind = if (completed) "complete" else "view",
                weight = if (completed) 1.5f else 1f,
                at = System.currentTimeMillis()
            )
        )
        val slug = video.uploaderSlug ?: return
        val existing = db.stars().bySlug(source.id + ":" + slug)
        if (existing != null) {
            db.stars().upsert(existing.copy(hits = existing.hits + 1))
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun List<VideoSummary>.withoutHidden(): List<VideoSummary> {
        val store = hidden ?: return this
        val keys = store.keys()
        if (keys.isEmpty()) return this
        val titles = store.titles().map { it.lowercase() }.filter { it.isNotBlank() }.toSet()
        return filterNot { video ->
            video.key in keys || titles.any { video.title.lowercase().contains(it) }
        }
    }

    private suspend fun upsertAll(items: List<VideoSummary>) {
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        db.videos().upsert(
            items.map { summary ->
                VideoEntity(
                    key = summary.key,
                    sourceId = summary.sourceId,
                    vkey = summary.vkey,
                    title = summary.title,
                    thumb = summary.thumb,
                    durationSec = summary.durationSec,
                    views = summary.views,
                    uploaderSlug = summary.uploaderSlug,
                    uploaderName = summary.uploaderName,
                    cachedAt = now,
                    category = summary.category.name,
                    tags = summary.tags.joinToString(",")
                )
            }
        )
        for (summary in items) {
            val slug = summary.uploaderSlug ?: continue
            val name = summary.uploaderName ?: continue
            val starKey = source.id + ":" + slug
            if (db.stars().bySlug(starKey) == null) {
                db.stars().upsert(
                    StarEntity(
                        slug = starKey,
                        sourceId = source.id,
                        rawSlug = slug,
                        name = name,
                        avatar = null,
                        followed = false,
                        followedAt = 0L,
                        hits = 0
                    )
                )
            }
        }
    }

    private fun VideoEntity.toSummary(): VideoSummary = VideoSummary(
        sourceId = sourceId,
        vkey = vkey,
        title = title,
        thumb = thumb,
        durationSec = durationSec,
        views = views,
        uploaderSlug = uploaderSlug,
        uploaderName = uploaderName,
        verifiedUploader = false,
        category = runCatching { Category.valueOf(category) }.getOrDefault(Category.LIVE),
        tags = tags.split(',').filter { it.isNotBlank() }
    )

    companion object {
        private const val SOURCE_TIMEOUT_MS = 25_000L
        private const val DETAIL_TTL_MS = 10 * 60 * 1000L
        private const val PAGE_TTL_MS = 3 * 60 * 1000L
        private const val COMMENTS_TTL_MS = 2 * 60 * 1000L
        private const val RELATED_TTL_MS = 5 * 60 * 1000L
        private const val SEARCH_TTL_MS = 2 * 60 * 1000L
        private const val KEY_FEED_KEYS = "feed_keys"
        private const val KEY_FEED_AT = "feed_at"
        private const val KEY_SHOW_JAPANESE = "show_japanese_feed"
        private const val AFFINITY_MIN_VIEWS = 2

        const val RELEASE_UNCENSORED = "uncensored"
        const val RELEASE_SUBTITLED = "subtitled"
    }
}
