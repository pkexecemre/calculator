package com.simplecalculator.app.data.repo

import com.simplecalculator.app.data.db.AppDb
import com.simplecalculator.app.data.db.EventEntity
import com.simplecalculator.app.data.db.StarEntity
import com.simplecalculator.app.data.db.VideoEntity
import com.simplecalculator.app.data.rank.Ranker
import com.simplecalculator.app.data.source.Category
import com.simplecalculator.app.data.source.hentai.AkiHSource
import com.simplecalculator.app.data.source.ListingKind
import com.simplecalculator.app.data.source.Source
import com.simplecalculator.app.data.source.SearchRelevance
import com.simplecalculator.app.data.source.SeriesGroup
import com.simplecalculator.app.data.source.SeriesGrouping
import com.simplecalculator.app.data.source.SeriesStore
import com.simplecalculator.app.data.source.SourceHealth
import com.simplecalculator.app.data.source.SourceRegistry
import com.simplecalculator.app.data.source.Tags
import com.simplecalculator.app.data.source.VideoDetail
import com.simplecalculator.app.data.source.VideoSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import java.util.Collections

class ContentRepository(
    private val db: AppDb,
    private val sources: SourceRegistry,
    private val ranker: Ranker,
    private val health: SourceHealth,
    private val hidden: com.simplecalculator.app.core.HiddenStore? = null
) {
    /** Drops anything the user dismissed, by key or by series title. */
    /**
     * JAV has real release flavours (uncensored leaks, subtitled cuts) and the
     * live sites tag subtitled uploads in their titles. Filtering happens after
     * the merge so every source follows the same rule.
     */
    private fun List<VideoSummary>.filterReleases(
        category: Category,
        releaseFilter: String?
    ): List<VideoSummary> {
        if (releaseFilter == null) return this
        val filtered = when (releaseFilter) {
            RELEASE_UNCENSORED -> filter {
                com.simplecalculator.app.data.source.JavTitles.isUncensored(it.title, it.vkey)
            }
            RELEASE_SUBTITLED -> filter {
                com.simplecalculator.app.data.source.JavTitles.isSubtitled(it.title, it.vkey)
            }
            else -> this
        }
        // Uncensored leaks are sparsely tagged, so fall back rather than showing
        // an empty tab. "Subtitled" is a strict promise: no silent substitution.
        return if (releaseFilter == RELEASE_UNCENSORED) filtered.ifEmpty { this } else filtered
    }

    private fun List<VideoSummary>.withoutHidden(): List<VideoSummary> {
        val store = hidden ?: return this
        val keys = store.keys()
        if (keys.isEmpty()) return this
        val titles = store.titles().map { it.lowercase() }.toSet()
        return filterNot { video ->
            video.key in keys ||
                titles.any { it.isNotBlank() && video.title.lowercase().contains(it) }
        }
    }
    private val sourceGate = Semaphore(MAX_PARALLEL_SOURCES)

    /** The live-action source owns follows and watch affinity. */
    private val primary get() = sources.primary()

    /** Display names of the sources that failed on the most recent feed load. */
    var lastFailures: List<String> = emptyList()
        private set

    fun sourceFor(key: String): Source? = sources.byId(key.substringBefore(":"))

    /** One incremental feed update: what we have so far, and whether all sources replied. */
    data class FeedUpdate(
        val items: List<VideoSummary>,
        val failures: List<String>,
        val done: Boolean
    )

    /**
     * Feed that renders source by source instead of waiting for the slowest one.
     * On a slow connection the first cards show up in well under a second.
     */
    fun feedStream(
        category: Category,
        page: Int = 1,
        tagFilter: String? = null,
        sourceFilter: String? = null,
        releaseFilter: String? = null
    ): Flow<FeedUpdate> = channelFlow {
        val tagQuery = if (category == Category.HENTAI || category == Category.JAV) {
            tagFilter?.let(Tags::label)
        } else {
            null
        }
        val kind = if (tagQuery != null) ListingKind.SEARCH else ListingKind.TRENDING
        val active = sources.byCategory(category)
            .filter { sourceFilter == null || it.id == sourceFilter }
        val acc = java.util.Collections.synchronizedList(ArrayList<VideoSummary>())
        val failures = java.util.Collections.synchronizedList(ArrayList<String>())

        coroutineScope {
            active.forEach { source ->
                launch(Dispatchers.IO) {
                    val items = fetchOne(source, kind, page, tagQuery, failures)
                    if (items.isNotEmpty()) {
                        acc.addAll(items)
                        send(FeedUpdate(acc.toList(), failures.toList(), done = false))
                    }
                }
            }
        }

        val raw = if (sourceFilter != null) expandCards(acc.toList()) else acc.toList()
        val pool = raw.withoutHidden().filterReleases(category, releaseFilter)
        lastFailures = failures.toList().sorted()
        if (pool.isEmpty()) {
            send(FeedUpdate(emptyList(), lastFailures, done = true))
            return@channelFlow
        }
        upsertAll(pool)
        val followed = followedSlugs()
        val ranked = ranker.rank(
            candidates = pool,
            followedSlugs = followed,
            affinity = affinity(),
            seen = db.events().recentVideos().toSet()
        )
        val isStarred: (VideoSummary) -> Boolean =
            { it.uploaderSlug != null && it.uploaderSlug in followed }
        val ordered = ranker.mix(ranked.filterNot(isStarred), ranked.filter(isStarred))
        send(FeedUpdate(ordered, lastFailures, done = true))
    }.flowOn(Dispatchers.Default)

    private suspend fun fetchOne(
        source: Source,
        kind: ListingKind,
        page: Int,
        query: String?,
        failures: MutableList<String>
    ): List<VideoSummary> {
        // Paused sources are deliberately skipped; do not report them as broken.
        if (health.isPaused(source.id)) return emptyList()
        return try {
            val items = withTimeout(SOURCE_TIMEOUT_MS) {
                source.listing(kind, page, query, null)
            }
            health.recordSuccess(source.id)
            items
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) {
                health.recordFailure(source.id, "timeout")
                failures.add(source.displayName)
                emptyList()
            } else {
                throw e
            }
        } catch (e: Exception) {
            health.recordFailure(source.id, e.message)
            failures.add(source.displayName)
            emptyList()
        }
    }

    private val pageCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<VideoSummary>>>()

    private fun pageKey(
        category: Category,
        page: Int,
        tag: String?,
        source: String?,
        releaseFilter: String? = null
    ) = "${category.name}|$page|${tag.orEmpty()}|${source.orEmpty()}|${releaseFilter.orEmpty()}"

    /** Prefetches a page so the next scroll is instant. Best effort only. */
    suspend fun prefetchPage(
        category: Category,
        page: Int,
        tagFilter: String? = null,
        sourceFilter: String? = null,
        releaseFilter: String? = null
    ) {
        val key = pageKey(category, page, tagFilter, sourceFilter, releaseFilter)
        val cached = pageCache[key]
        if (cached != null && System.currentTimeMillis() - cached.first < PAGE_TTL_MS) return
        runCatching { feed(category, page, tagFilter, sourceFilter, releaseFilter) }
    }

    suspend fun feed(
        category: Category,
        page: Int = 1,
        tagFilter: String? = null,
        sourceFilter: String? = null,
        releaseFilter: String? = null
    ): List<VideoSummary> {
        val cacheKey = pageKey(category, page, tagFilter, sourceFilter, releaseFilter)
        pageCache[cacheKey]?.let { (at, items) ->
            if (System.currentTimeMillis() - at < PAGE_TTL_MS) return items
        }
        val result = feedUncached(category, page, tagFilter, sourceFilter, releaseFilter)
        if (result.isNotEmpty()) {
            if (pageCache.size > 24) pageCache.clear()
            pageCache[cacheKey] = System.currentTimeMillis() to result
        }
        return result
    }

    private suspend fun feedUncached(
        category: Category,
        page: Int,
        tagFilter: String?,
        sourceFilter: String?,
        releaseFilter: String?
    ): List<VideoSummary> {
        // Tags are a single selection. Search is the only listing mode every
        // registered source implements, so a tag becomes a query instead of a
        // source-specific slug (which broke on HentaiCloud and Iwara).
        val tagQuery = if (category == Category.HENTAI || category == Category.JAV) {
            tagFilter?.let(Tags::label)
        } else {
            null
        }
        val kind = if (tagQuery != null) ListingKind.SEARCH else ListingKind.TRENDING
        val active = sources.byCategory(category)
            .filter { sourceFilter == null || it.id == sourceFilter }
        // With a single source selected the pool is small enough to expand
        // series cards right away, so the badge shows the real episode count.
        val rawPool = if (sourceFilter != null) {
            expandCards(fetchAll(active, kind, page, tagQuery))
        } else {
            fetchAll(active, kind, page, tagQuery)
        }
        val pool = rawPool.withoutHidden().filterReleases(category, releaseFilter)

        if (pool.isEmpty()) return emptyList()
        upsertAll(pool)
        val followed = followedSlugs()
        val ranked = ranker.rank(
            candidates = pool,
            followedSlugs = followed,
            affinity = affinity(),
            seen = db.events().recentVideos().toSet()
        )
        val isStarred: (VideoSummary) -> Boolean =
            { it.uploaderSlug != null && it.uploaderSlug in followed }
        val starred = ranked.filter(isStarred)
        val general = ranked.filterNot(isStarred)
        return ranker.mix(general, starred)
    }

    /** Instant first paint: whatever this category last showed, straight from Room. */
    suspend fun cachedFeed(category: Category, limit: Int = 60): List<VideoSummary> =
        runCatching {
            db.videos().byCategory(category.name).take(limit).map { row ->
                VideoSummary(
                    sourceId = row.sourceId,
                    vkey = row.vkey,
                    title = row.title,
                    thumb = row.thumb,
                    durationSec = row.durationSec,
                    views = row.views,
                    uploaderSlug = row.uploaderSlug,
                    uploaderName = row.uploaderName,
                    verifiedUploader = false,
                    category = category,
                    tags = row.tags.split(',').filter { it.isNotBlank() },
                    flavor = com.simplecalculator.app.data.source.HentaiFlavor.from(row.title)
                )
            }
        }.onFailure { android.util.Log.w("ContentRepo", "cachedFeed failed", it) }
            .also { result -> android.util.Log.e("ContentRepo", "cachedFeed(${category.name}) -> ${result.getOrNull()?.size ?: 0}") }
            .getOrDefault(emptyList())

    suspend fun search(
        category: Category,
        query: String,
        page: Int,
        sourceFilter: String? = null,
        tagFilter: String? = null
    ): List<VideoSummary> {
        var pool = fetchAll(sources.byCategory(category), ListingKind.SEARCH, page, query)
        if (sourceFilter != null) pool = pool.filter { it.sourceId == sourceFilter }
        if (tagFilter != null) {
            // Videos store canonical ids, the chip carries an id, and titles use
            // the human label: accept a match on any of them.
            val label = Tags.label(tagFilter)
            pool = pool.filter { video ->
                video.tags.any { it.equals(tagFilter, true) || it.equals(label, true) } ||
                    video.title.contains(label, ignoreCase = true)
            }
        }
        val relevant = if (category == Category.HENTAI) {
            pool.filter { SearchRelevance.matches(it.title, query) }
        } else {
            pool
        }
        val finalPool = (if (sourceFilter != null) expandCards(relevant) else relevant).withoutHidden()
        upsertAll(finalPool)
        return finalPool
    }

    /**
     * Opening a series: pull every source for the same title and expand series
     * cards whose site keeps episodes on a page of their own. Kept out of the
     * feed path so browsing does not fire one request per card.
     */
    /** Expands series cards whose site keeps episodes on a page of their own. */
    private suspend fun expandCards(items: List<VideoSummary>): List<VideoSummary> {
        val akih = sources.byId("akih") as? AkiHSource ?: return items
        return items.flatMap { video ->
            if (video.sourceId == "akih" && !video.vkey.contains("/watch/")) {
                runCatching { akih.expandSeries(video) }.getOrDefault(listOf(video))
            } else {
                listOf(video)
            }
        }
    }

    suspend fun expandGroup(group: SeriesGroup): SeriesGroup {
        val akih = sources.byId("akih") as? AkiHSource
        val expandedFromCard = if (akih == null) {
            group.videos
        } else {
            group.videos.flatMap { video ->
                if (video.sourceId == "akih" && !video.vkey.contains("/watch/")) {
                    runCatching { akih.expandSeries(video) }.getOrDefault(listOf(video))
                } else {
                    listOf(video)
                }
            }
        }
        val others = runCatching {
            search(Category.HENTAI, group.title, 1)
        }.getOrDefault(emptyList()).filter { it.title.isNotEmpty() }

        val merged = (expandedFromCard + others)
            .withoutHidden()
            .filter { SeriesGrouping.seriesKey(it) == group.key }
            .distinctBy { SeriesGrouping.episodeKey(it) }
        if (merged.isEmpty()) return group
        val rebuilt = SeriesGrouping.rebuild(group, merged)
        // The player looks videos up by key, so expanded episodes must be cached.
        upsertAll(rebuilt.videos)
        return rebuilt
    }

    private suspend fun fetchAll(
        active: List<Source>,
        kind: ListingKind,
        page: Int,
        query: String?
    ): List<VideoSummary> = coroutineScope {
        val failures = Collections.synchronizedList(ArrayList<String>())
        val results = active.map { source ->
            async {
                when {
                    health.isPaused(source.id) -> {
                        failures.add(source.displayName)
                        emptyList()
                    }
                    else -> {
                        sourceGate.acquire()
                        try {
                            val items = withTimeout(SOURCE_TIMEOUT_MS) {
                                source.listing(kind, page, query, null)
                            }
                            health.recordSuccess(source.id)
                            items
                        } catch (e: CancellationException) {
                            if (e is TimeoutCancellationException) {
                                health.recordFailure(source.id, "timeout")
                                failures.add(source.displayName)
                                emptyList()
                            } else {
                                throw e
                            }
                        } catch (e: Exception) {
                            health.recordFailure(source.id, e.message)
                            failures.add(source.displayName)
                            emptyList()
                        } finally {
                            sourceGate.release()
                        }
                    }
                }
            }
        }.awaitAll()
        lastFailures = failures.toList().sorted()
        results.flatten().distinctBy { it.key }
    }

    suspend fun feedGroups(
        category: Category,
        page: Int = 1,
        tagFilter: String? = null,
        sourceFilter: String? = null
    ): List<SeriesGroup> = SeriesStore.putAll(
        SeriesGrouping.group(feed(category, page, tagFilter, sourceFilter))
    )

    suspend fun searchGroups(
        category: Category,
        query: String,
        page: Int,
        sourceFilter: String? = null,
        tagFilter: String? = null
    ): List<SeriesGroup> = SeriesStore.putAll(
        SeriesGrouping.group(search(category, query, page, sourceFilter, tagFilter))
    )

    suspend fun starFeed(slug: String, page: Int): List<VideoSummary> {
        val items = primary.listing(ListingKind.STAR, page, null, slug)
        upsertAll(items)
        return items
    }

    fun followedStars(): Flow<List<StarEntity>> = db.stars().followed()

    suspend fun toggleFollow(slug: String, name: String, avatar: String?) {
        val key = primary.id + ":" + slug
        val existing = db.stars().bySlug(key)
        if (existing != null && existing.followed) {
            db.stars().upsert(existing.copy(followed = false))
        } else {
            db.stars().upsert(
                StarEntity(
                    slug = key,
                    sourceId = primary.id,
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
        val star = db.stars().bySlug(primary.id + ":" + slug) ?: return false
        return star.followed
    }

    fun history(): kotlinx.coroutines.flow.Flow<List<com.simplecalculator.app.data.db.HistoryEntity>> =
        db.history().observeAll()

    fun favorites(): kotlinx.coroutines.flow.Flow<List<com.simplecalculator.app.data.db.FavoriteEntity>> =
        db.favorites().observeAll()

    suspend fun progressFor(key: String): Long = db.history().byKey(key)?.positionMs ?: 0L

    suspend fun recordProgress(key: String, sourceId: String, title: String, thumb: String, positionMs: Long, durationMs: Long) {
        if (positionMs < 5_000L) return
        db.history().upsert(
            com.simplecalculator.app.data.db.HistoryEntity(
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
                com.simplecalculator.app.data.db.FavoriteEntity(
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

    /**
     * Detail pages are the slowest request in the app (page fetch + unpacking),
     * so keep the last handful of them hot. A prefetch while the feed renders
     * makes "Play" feel instant.
     */
    private val detailCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, VideoDetail>>()

    suspend fun detailFor(video: VideoEntity): VideoDetail {
        val cached = detailCache[video.key]
        if (cached != null && System.currentTimeMillis() - cached.first < DETAIL_TTL_MS) {
            return cached.second
        }
        val source = sourceFor(video.key) ?: throw IllegalStateException("no source")
        val detail = source.detail(video.vkey)
        if (detail.streams.isNotEmpty() || detail.embedUrl != null) {
            if (detailCache.size > 40) detailCache.clear()
            detailCache[video.key] = System.currentTimeMillis() to detail
        }
        return detail
    }

    /** Warms the detail cache; failures are irrelevant here. */
    suspend fun prefetchDetail(key: String) {
        val video = db.videos().byKey(key) ?: return
        if (detailCache.containsKey(key)) return
        runCatching { detailFor(video) }
    }



    suspend fun applyUploader(key: String, slug: String?, name: String?) {
        if (slug.isNullOrBlank() || name.isNullOrBlank()) return
        val current = db.videos().byKey(key) ?: return
        if (current.uploaderSlug != null) return
        db.videos().upsert(listOf(current.copy(uploaderSlug = slug, uploaderName = name)))
        val starKey = primary.id + ":" + slug
        if (db.stars().bySlug(starKey) == null) {
            db.stars().upsert(
                StarEntity(
                    slug = starKey,
                    sourceId = primary.id,
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
        val existing = db.stars().bySlug(primary.id + ":" + slug)
        if (existing != null) {
            db.stars().upsert(existing.copy(hits = existing.hits + 1))
        }
    }

    suspend fun comments(vkey: String, sourceId: String, commentsUrl: String?, offset: Int) =
        sources.byId(sourceId)?.comments(vkey, commentsUrl, offset) ?: emptyList()

    suspend fun related(vkey: String, sourceId: String, relatedUrl: String?): List<VideoSummary> {
        val items = runCatching {
            sources.byId(sourceId)?.related(vkey, relatedUrl) ?: emptyList()
        }.getOrDefault(emptyList())
        // Anything the UI can navigate to must exist in the cache, otherwise the
        // player opens with no title and no metadata.
        upsertAll(items)
        return items
    }

    private suspend fun followedSlugs(): Set<String> =
        db.stars().followedNow().map { it.rawSlug }.toSet()

    private suspend fun affinity(): Map<String, Int> =
        db.events().recentUploaders().groupingBy { it }.eachCount()

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
            if (summary.category != Category.LIVE) continue
            val starKey = primary.id + ":" + slug
            if (db.stars().bySlug(starKey) == null) {
                db.stars().upsert(
                    StarEntity(
                        slug = starKey,
                        sourceId = primary.id,
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

    companion object {
        private const val SOURCE_TIMEOUT_MS = 12_000L
        private const val MAX_PARALLEL_SOURCES = 6
        private const val DETAIL_TTL_MS = 10 * 60 * 1000L
        private const val PAGE_TTL_MS = 3 * 60 * 1000L
        const val RELEASE_UNCENSORED = "uncensored"
        const val RELEASE_SUBTITLED = "subtitled"
    }
}
