package com.simplecalculator.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplecalculator.app.R
import com.simplecalculator.app.data.source.VideoSummary
import com.simplecalculator.app.ui.ActionSheet
import com.simplecalculator.app.ui.EmptyState
import com.simplecalculator.app.ui.HeroCard
import com.simplecalculator.app.ui.ImageWarmup
import com.simplecalculator.app.ui.LocalContainer
import com.simplecalculator.app.ui.SectionHeader
import com.simplecalculator.app.ui.SkeletonCard
import com.simplecalculator.app.ui.Thumb
import com.simplecalculator.app.ui.VideoCard
import com.simplecalculator.app.ui.theme.TextMuted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(onOpen: (String) -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()

    var items by remember { mutableStateOf<List<VideoSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var stale by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(1) }
    var menuFor by remember { mutableStateOf<VideoSummary?>(null) }

    val history by container.content.history()
        .collectAsStateWithLifecycle(initialValue = emptyList())
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

    /** First page. A failed refresh keeps the cards already on screen. */
    suspend fun loadFirst() {
        if (refreshing) return
        refreshing = true
        if (items.isEmpty()) loading = true
        try {
            val result = container.content.feed(1)
            error = result.error
            stale = result.stale
            if (result.items.isNotEmpty()) {
                // On a failed refresh the repository hands back its fallback.
                // Keep the richer list already on screen instead of replacing it.
                if (result.error == null || items.isEmpty()) {
                    items = result.items
                    page = 1
                }
                if (result.error == null) hasMore = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Only the banner changes; the visible list is left untouched.
            error = e.message ?: ""
        } finally {
            refreshing = false
            loading = false
        }
    }

    /** Next page. Appends what arrives and stops asking once a page comes back empty. */
    suspend fun loadMore() {
        if (loadingMore || refreshing || loading || !hasMore || items.isEmpty()) return
        loadingMore = true
        try {
            val current = page
            val result = container.content.feed(current + 1)
            // A refresh landed while this page was in flight; its list is newer.
            if (page != current) return
            result.error?.let { error = it }
            if (result.stale) stale = true
            if (result.items.isNotEmpty()) {
                val before = items.size
                val merged = (items + result.items).distinctBy { it.key }
                items = merged
                page = current + 1
                // No new cards means no progress; stop instead of re-triggering.
                if (merged.size == before) hasMore = false
            } else {
                hasMore = false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: ""
        } finally {
            loadingMore = false
        }
    }

    LaunchedEffect(Unit) {
        // Instant paint from Room, then the network page replaces it.
        val cached = runCatching { container.content.cachedFeed() }.getOrDefault(emptyList())
        if (cached.isNotEmpty()) {
            items = cached
            loading = false
        }
        loadFirst()
    }

    // Artwork for what just landed, plus the first detail pages so the next tap
    // opens immediately instead of waiting for a source round-trip.
    LaunchedEffect(items.take(3).map { it.key }) {
        if (items.isEmpty()) return@LaunchedEffect
        runCatching { ImageWarmup.prefetch(context, items, limit = 12) }
        items.take(3).forEachIndexed { index, video ->
            withContext(Dispatchers.IO) {
                delay(600L + index * 800L)
                runCatching { container.content.prefetchDetail(video.key) }
            }
        }
    }

    // Warm page two while the user is still reading page one.
    LaunchedEffect(items.isNotEmpty(), page, loading, hasMore) {
        if (items.isEmpty() || loading || !hasMore) return@LaunchedEffect
        delay(1_500)
        withContext(Dispatchers.IO) {
            runCatching { container.content.prefetchPage(page + 1) }
        }
    }

    LaunchedEffect(gridState) {
        // Collect the visible range instead of keying an effect on a flag:
        // starting a page must not cancel the coroutine that is fetching it.
        snapshotFlow {
            val info = gridState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }
            .distinctUntilChanged()
            .collect { (last, total) ->
                if (total > 0 && last >= total - 4) loadMore()
            }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = gridState,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_title),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        text = stringResource(R.string.home_subtitle),
                        style = MaterialTheme.typography.labelMedium,
                        color = TextMuted
                    )
                }
                IconButton(
                    onClick = { scope.launch { loadFirst() } },
                    enabled = !refreshing
                ) {
                    if (refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.home_refresh)
                        )
                    }
                }
            }
        }

        if (error != null || stale) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    if (error != null) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(start = 12.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.home_failed),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { scope.launch { loadFirst() } }) {
                                Text(stringResource(R.string.common_retry))
                            }
                        }
                    }
                    if (stale) {
                        Text(
                            text = stringResource(R.string.home_offline_banner),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            modifier = Modifier.padding(start = 12.dp, top = 4.dp)
                        )
                    }
                }
            }
        }

        val hero = items.firstOrNull()
        if (hero != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
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
            items.isEmpty() && loading -> {
                items(6) {
                    SkeletonCard(Modifier.padding(vertical = 4.dp))
                }
            }

            items.isEmpty() -> {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        EmptyState(
                            title = stringResource(R.string.home_empty_title),
                            subtitle = stringResource(R.string.home_empty_body),
                            icon = Icons.Filled.CloudOff,
                            modifier = Modifier.fillMaxWidth().height(260.dp)
                        )
                        TextButton(onClick = { scope.launch { loadFirst() } }) {
                            Text(stringResource(R.string.common_retry))
                        }
                    }
                }
            }

            else -> {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeader(stringResource(R.string.home_trending))
                }
                items(items, key = { it.key }) { video ->
                    VideoCard(
                        video = video,
                        onClick = { onOpen(video.key) },
                        progress = progressByKey[video.key]?.takeIf { it > 0.01f && it < 0.95f },
                        onLongClick = { menuFor = video }
                    )
                }
                if (loadingMore) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }
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
}
