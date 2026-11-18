package com.simplecalculator.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.simplecalculator.app.R
import com.simplecalculator.app.data.source.Category
import com.simplecalculator.app.data.source.HentaiFlavor
import com.simplecalculator.app.data.source.SeriesGroup
import com.simplecalculator.app.data.source.Tags
import com.simplecalculator.app.data.source.VideoSummary
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.aspectRatio
import com.simplecalculator.app.ui.EmptyState
import com.simplecalculator.app.ui.ActionSheet
import com.simplecalculator.app.ui.CategoryTabs
import com.simplecalculator.app.ui.FilterChipRow
import com.simplecalculator.app.ui.HeroCard
import com.simplecalculator.app.ui.PillChips
import com.simplecalculator.app.ui.SectionHeader
import com.simplecalculator.app.ui.SkeletonCard
import com.simplecalculator.app.ui.FilterOption
import com.simplecalculator.app.ui.Thumb
import com.simplecalculator.app.ui.LocalContainer
import com.simplecalculator.app.ui.SeriesCard
import com.simplecalculator.app.ui.VideoCard
import com.simplecalculator.app.ui.theme.TextMuted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    category: Category,
    onCategoryChange: (Category) -> Unit,
    onOpen: (String) -> Unit,
    onOpenSeries: (String) -> Unit
) {
    val container = LocalContainer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<VideoSummary>>(emptyList()) }
    var groups by remember { mutableStateOf<List<SeriesGroup>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(1) }
    var loadingMore by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<com.simplecalculator.app.data.source.VideoSummary?>(null) }
    var seriesMenuFor by remember { mutableStateOf<com.simplecalculator.app.data.source.SeriesGroup?>(null) }
    var tagFilter by remember { mutableStateOf<String?>(null) }
    var flavorFilter by remember { mutableStateOf<HentaiFlavor?>(null) }
    var releaseFilter by remember { mutableStateOf<String?>(null) }
    var sourceFilter by remember { mutableStateOf<String?>(null) }
    var failures by remember { mutableStateOf<List<String>>(emptyList()) }
    val gridState = rememberLazyGridState()
    val history by container.content.history().collectAsStateWithLifecycle(initialValue = emptyList())
    val progressByKey = remember(history) {
        history.associate { row ->
            row.key to if (row.durationMs > 0) {
                (row.positionMs.toFloat() / row.durationMs).coerceIn(0f, 1f)
            } else 0f
        }
    }
    val continueWatching = history
        .filter { it.durationMs > 0 && it.positionMs > 10_000 && it.positionMs < it.durationMs - 15_000 }
        .take(10)

    /** Streams a page in: first cards appear as soon as one source answers. */
    suspend fun load(target: Int, reset: Boolean) {
        if (reset) {
            // Paint the previous session immediately, then stream fresh results.
            var cached = runCatching { container.content.cachedFeed(category) }.getOrDefault(emptyList())
            // The instant paint must honour the active release pill.
            when (releaseFilter) {
                com.simplecalculator.app.data.repo.ContentRepository.RELEASE_UNCENSORED -> cached =
                    cached.filter {
                        com.simplecalculator.app.data.source.JavTitles.isUncensored(it.title, it.vkey)
                    }
                com.simplecalculator.app.data.repo.ContentRepository.RELEASE_SUBTITLED -> cached =
                    cached.filter {
                        com.simplecalculator.app.data.source.JavTitles.isSubtitled(it.title, it.vkey)
                    }
            }
            if (cached.isNotEmpty()) {
                if (category == Category.HENTAI) {
                    groups = com.simplecalculator.app.data.source.SeriesStore.putAll(
                        com.simplecalculator.app.data.source.SeriesGrouping.group(cached)
                    )
                } else {
                    items = cached
                }
                loading = false
            } else {
                items = emptyList()
                groups = emptyList()
                loading = true
            }
        }
        error = false
        runCatching {
            container.content.feedStream(
                category, target, tagFilter, sourceFilter, releaseFilter
            ).collect { update ->
                    if (reset && update.items.isEmpty() && !update.done) return@collect
                    val mapped = if (category == Category.HENTAI) {
                        val fresh = com.simplecalculator.app.data.source.SeriesStore
                            .putAll(com.simplecalculator.app.data.source.SeriesGrouping.group(update.items))
                        val merged = (groups + fresh).distinctBy { it.key }
                        if (flavorFilter != null) {
                            merged.filter { group -> group.videos.any { it.flavor == flavorFilter } }
                        } else {
                            merged
                        }
                    } else {
                        groups
                    }
                    groups = if (category == Category.HENTAI) mapped else groups
                    // An empty update means "nothing yet"; keep what is on screen
                    // so a slow or failing refresh does not blank the feed.
                    if (update.items.isNotEmpty() || items.isEmpty()) {
                        items = update.items
                    }
                    failures = update.failures
                    if (update.items.isNotEmpty()) loading = false
                }
        }.onFailure { error = true }
        loading = false
    }

    LaunchedEffect(category, tagFilter, sourceFilter, flavorFilter, releaseFilter) {
        page = 1
        load(1, true)
    }

    // Artwork for whatever just landed, plus the first few detail pages so the
    // next taps open immediately instead of waiting for a source round-trip.
    LaunchedEffect(items.take(3).map { it.key }, groups.firstOrNull()?.key) {
        val thumbs = items.ifEmpty { groups.flatMap { it.videos } }
        if (thumbs.isNotEmpty()) {
            com.simplecalculator.app.ui.ImageWarmup.prefetch(context, thumbs, limit = 12)
        }
        val targets = items.take(3).map { it.key }
        for ((index, key) in targets.withIndex()) {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                kotlinx.coroutines.delay(600L + index * 800L)
                container.content.prefetchDetail(key)
            }
        }
    }

    val listSize = if (category == Category.HENTAI) groups.size else items.size
    // Warm page two while the user is still reading page one.
    LaunchedEffect(category, tagFilter, sourceFilter, releaseFilter, listSize > 0) {
        if (listSize == 0 || loading) return@LaunchedEffect
        kotlinx.coroutines.delay(1_500)
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            container.content.prefetchPage(category, page + 1, tagFilter, sourceFilter, releaseFilter)
        }
    }

    val shouldLoadMore by remember {
        androidx.compose.runtime.derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listSize - 4 && !loading && listSize > 0
        }
    }

    LaunchedEffect(shouldLoadMore) {
        // Guarded: without this the bottom-of-list trigger fires a new page on
        // every recomposition and floods the sources.
        if (!shouldLoadMore || loadingMore) return@LaunchedEffect
        loadingMore = true
        val next = page + 1
        runCatching {
            val more = container.content.feed(category, next, tagFilter, sourceFilter, releaseFilter)
            if (more.isNotEmpty()) {
                if (category == Category.HENTAI) {
                    val fresh = com.simplecalculator.app.data.source.SeriesStore
                        .putAll(com.simplecalculator.app.data.source.SeriesGrouping.group(more))
                    groups = (groups + fresh).distinctBy { it.key }
                } else {
                    items = (items + more).distinctBy { it.key }
                }
                page = next
            }
            failures = container.content.lastFailures
        }
        loadingMore = false
    }

    LazyVerticalGrid(
        // Portrait posters read better three-up; video stills stay two-up.
        columns = GridCells.Fixed(if (category == Category.HENTAI) 3 else 2),
        state = gridState,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(titleFor(category)),
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            text = stringResource(subtitleFor(category)),
                            style = MaterialTheme.typography.labelMedium,
                            color = TextMuted
                        )
                    }
                    IconButton(onClick = {
                        scope.launch {
                            page = 1
                            load(1, true)
                        }
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.home_refresh))
                    }
                }
                Spacer(Modifier.height(8.dp))
                CategoryTabs(
                    labels = Category.entries.map { stringResource(labelFor(it)) },
                    selectedIndex = Category.entries.indexOf(category),
                    onSelect = { onCategoryChange(Category.entries[it]) }
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            val sourceOptions = listOf(
                FilterOption(null, stringResource(R.string.filter_source_all))
            ) + container.sources.byCategory(category).map {
                FilterOption(it.id, it.displayName, it.category.name)
            }
            Column {
                FilterChipRow(
                    sourceOptions = sourceOptions,
                    sourceFilter = sourceFilter,
                    tagFilter = tagFilter,
                    onSource = { sourceFilter = it },
                    onTag = { tagFilter = it },
                    onReset = {
                        sourceFilter = null
                        tagFilter = null
                    }
                )
                if (category == Category.JAV) {
                    Spacer(Modifier.height(8.dp))
                    PillChips(
                        labels = listOf(
                            stringResource(R.string.jav_all),
                            stringResource(R.string.jav_uncensored),
                            stringResource(R.string.release_subtitled)
                        ),
                        selectedIndex = when (releaseFilter) {
                            com.simplecalculator.app.data.repo.ContentRepository.RELEASE_UNCENSORED -> 1
                            com.simplecalculator.app.data.repo.ContentRepository.RELEASE_SUBTITLED -> 2
                            else -> 0
                        },
                        onSelect = { index ->
                            releaseFilter = when (index) {
                                1 -> com.simplecalculator.app.data.repo.ContentRepository.RELEASE_UNCENSORED
                                2 -> com.simplecalculator.app.data.repo.ContentRepository.RELEASE_SUBTITLED
                                else -> null
                            }
                        }
                    )
                }
                if (category == Category.LIVE) {
                    Spacer(Modifier.height(8.dp))
                    PillChips(
                        labels = listOf(
                            stringResource(R.string.jav_all),
                            stringResource(R.string.release_subtitled)
                        ),
                        selectedIndex = if (releaseFilter == com.simplecalculator.app.data.repo.ContentRepository.RELEASE_SUBTITLED) 1 else 0,
                        onSelect = { index ->
                            releaseFilter = if (index == 1) {
                                com.simplecalculator.app.data.repo.ContentRepository.RELEASE_SUBTITLED
                            } else {
                                null
                            }
                        }
                    )
                }
                if (category == Category.HENTAI) {
                    Spacer(Modifier.height(8.dp))
                    PillChips(
                        labels = listOf(
                            stringResource(R.string.flavor_all),
                            stringResource(R.string.flavor_studio),
                            stringResource(R.string.flavor_user),
                            stringResource(R.string.flavor_vr)
                        ),
                        selectedIndex = when (flavorFilter) {
                            null -> 0
                            HentaiFlavor.STUDIO -> 1
                            HentaiFlavor.USER -> 2
                            HentaiFlavor.VR, HentaiFlavor.UNKNOWN -> 3
                        },
                        onSelect = { index ->
                            flavorFilter = when (index) {
                                1 -> HentaiFlavor.STUDIO
                                2 -> HentaiFlavor.USER
                                3 -> HentaiFlavor.VR
                                else -> null
                            }
                        }
                    )
                }
            }
        }

        val hero = items.firstOrNull()
        if (hero != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Spacer(Modifier.height(4.dp))
                HeroCard(
                    video = hero,
                    label = stringResource(R.string.home_featured),
                    onPlay = { onOpen(hero.key) }
                )
            }
        }

        if (continueWatching.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(bottom = 4.dp)) {
                    SectionHeader(stringResource(R.string.home_continue))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(continueWatching) { row ->
                            Column(
                                Modifier
                                    .width(190.dp)
                                    .clickable { onOpen(row.key) }
                            ) {
                                Box {
                                    Thumb(row.thumb, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                                    if (row.durationMs > 0) {
                                        LinearProgressIndicator(
                                            progress = {
                                                (row.positionMs.toFloat() / row.durationMs).coerceIn(0f, 1f)
                                            },
                                            modifier = Modifier
                                                .align(Alignment.BottomStart)
                                                .fillMaxWidth()
                                                .height(3.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = row.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 3.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        when {
            listSize == 0 && loading -> {
                items(6) {
                    SkeletonCard(Modifier.padding(vertical = 4.dp))
                }
            }

            listSize == 0 -> {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(
                        title = stringResource(R.string.home_empty_title),
                        subtitle = when {
                            failures.isNotEmpty() ->
                                stringResource(R.string.source_unreachable) + " " +
                                    failures.joinToString(", ")
                            releaseFilter != null -> stringResource(R.string.filter_release_empty)
                            error -> stringResource(R.string.home_empty_body)
                            tagFilter == null -> stringResource(R.string.home_empty_body)
                            else -> stringResource(R.string.filter_empty_body)
                        }
                    )
                }
            }

            category == Category.HENTAI -> {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeader(
                        title = if (tagFilter != null || sourceFilter != null) {
                            stringResource(R.string.home_results)
                        } else {
                            stringResource(R.string.home_series)
                        }
                    )
                }
                items(groups, key = { it.key }) { group ->
                    SeriesCard(
                        group = group,
                        onClick = { onOpenSeries(group.key) },
                        watchedEpisodes = group.videos.count { progressByKey.containsKey(it.key) },
                        onLongClick = { seriesMenuFor = group }
                    )
                }
            }

            else -> {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeader(
                        title = if (tagFilter != null || sourceFilter != null) {
                            stringResource(R.string.home_results)
                        } else {
                            stringResource(R.string.home_trending)
                        }
                    )
                }
                items(items, key = { it.key }) { video ->
                    VideoCard(
                        video = video,
                        onClick = { onOpen(video.key) },
                        progress = progressByKey[video.key]?.takeIf { it > 0.01f && it < 0.95f },
                        onLongClick = { menuFor = video }
                    )
                }
            }
        }
    }

    menuFor?.let { video ->
        ActionSheet(
            title = video.title,
            actions = listOf(
                stringResource(R.string.action_save) to {
                    scope.launch {
                        container.content.toggleFavorite(
                            video.key, video.sourceId, video.title, video.thumb
                        )
                    }
                },
                stringResource(R.string.action_hide) to {
                    container.hidden.hide(video.key, video.title)
                    items = items.filterNot { it.key == video.key }
                }
            ),
            onDismiss = { menuFor = null }
        )
    }

    seriesMenuFor?.let { group ->
        ActionSheet(
            title = group.title,
            actions = listOf(
                stringResource(R.string.action_hide) to {
                    container.hidden.hide(group.key, group.title)
                    groups = groups.filterNot { it.key == group.key }
                }
            ),
            onDismiss = { seriesMenuFor = null }
        )
    }
}

private fun flavorLabel(flavor: HentaiFlavor): Int = when (flavor) {
    HentaiFlavor.STUDIO -> R.string.flavor_studio
    HentaiFlavor.USER -> R.string.flavor_user
    HentaiFlavor.VR -> R.string.flavor_vr
    HentaiFlavor.UNKNOWN -> R.string.flavor_all
}

private fun titleFor(category: Category): Int = when (category) {
    Category.LIVE -> R.string.home_for_you
    Category.JAV -> R.string.home_jav
    Category.HENTAI -> R.string.home_hentai
    Category.ARCHIVE -> R.string.home_archive
}

private fun subtitleFor(category: Category): Int = when (category) {
    Category.LIVE -> R.string.home_subtitle
    Category.JAV -> R.string.home_jav_subtitle
    Category.HENTAI -> R.string.home_hentai_subtitle
    Category.ARCHIVE -> R.string.home_archive_subtitle
}

private fun labelFor(category: Category): Int = when (category) {
    Category.LIVE -> R.string.category_live
    Category.JAV -> R.string.category_jav
    Category.HENTAI -> R.string.category_hentai
    Category.ARCHIVE -> R.string.category_archive
}
