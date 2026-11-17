package com.simplecalculator.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplecalculator.app.R
import com.simplecalculator.app.core.formatDuration
import com.simplecalculator.app.data.source.SeriesStore
import com.simplecalculator.app.ui.LocalContainer
import com.simplecalculator.app.ui.Thumb

@Composable
fun SeriesScreen(
    seriesKey: String,
    onBack: () -> Unit,
    onOpenVideo: (String) -> Unit
) {
    val container = LocalContainer.current
    val cached = remember(seriesKey) { SeriesStore.get(seriesKey) }
    var group by remember(seriesKey) { mutableStateOf(cached) }
    val history by container.content.history().collectAsStateWithLifecycle(initialValue = emptyList())
    val progressByKey = remember(history) {
        history.associate { row ->
            row.key to if (row.durationMs > 0) {
                (row.positionMs.toFloat() / row.durationMs).coerceIn(0f, 1f)
            } else 0f
        }
    }

    var loadingEpisodes by remember(seriesKey) { mutableStateOf(cached != null) }
    var reloadTick by remember(seriesKey) { mutableStateOf(0) }
    var expandFailed by remember(seriesKey) { mutableStateOf(false) }
    LaunchedEffect(seriesKey, reloadTick) {
        val current = cached ?: return@LaunchedEffect
        loadingEpisodes = true
        val expanded = runCatching { container.content.expandGroup(current) }.getOrNull()
        expandFailed = expanded == null || expanded.episodeCount <= 1
        if (expanded != null && expanded.episodeCount >= current.episodeCount) {
            group = expanded
        }
        loadingEpisodes = false
    }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
        ) {
            Thumb(group?.thumb.orEmpty(), Modifier.fillMaxSize())
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.55f),
                                Color.Black.copy(alpha = 0.35f),
                                MaterialTheme.colorScheme.background
                            )
                        )
                    )
            )
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = Color.White
                )
            }
            Text(
                text = group?.title.orEmpty(),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }

        if (group == null) {
            // The in-memory hand-off can be cleared by a feed reload; rebuild it.
            LaunchedEffect(seriesKey) {
                val rebuilt = runCatching {
                    container.content.searchGroups(com.simplecalculator.app.data.source.Category.HENTAI, seriesKey, 1)
                }.getOrDefault(emptyList())
                group = rebuilt.firstOrNull { it.key == seriesKey }
                    ?: rebuilt.maxByOrNull { it.episodeCount }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        val current = group!!
        val watched = current.videos.count { progressByKey[it.key]?.let { p -> p > 0.9f } == true }
        val nextUp = current.videos.firstOrNull { progressByKey[it.key] == null }

        LazyColumn(contentPadding = PaddingValues(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = when {
                            loadingEpisodes && current.episodeCount <= 1 ->
                                stringResource(R.string.series_loading_episodes)
                            current.displayEpisodes > 1 ->
                                stringResource(R.string.series_progress, watched, current.displayEpisodes)
                            else -> androidx.compose.ui.res.pluralStringResource(
                                R.plurals.series_episode_count,
                                1,
                                1
                            )
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (expandFailed && !loadingEpisodes && current.advertisedEpisodes > current.episodeCount) {
                        OutlinedButton(onClick = { reloadTick += 1 }) {
                            Text(stringResource(R.string.series_reload))
                        }
                    } else if (nextUp != null && current.episodeCount > 1) {
                        Button(onClick = { onOpenVideo(nextUp.key) }) {
                            Text(stringResource(R.string.series_next))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            items(current.videos, key = { it.key }) { video ->
                val fraction = progressByKey[video.key]
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenVideo(video.key) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.width(124.dp)) {
                        Box {
                            Thumb(video.thumb, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                            if (fraction != null && fraction > 0.01f) {
                                Box(
                                    Modifier
                                        .align(Alignment.BottomStart)
                                        .fillMaxWidth()
                                        .height(3.dp)
                                        .background(Color.Black.copy(alpha = 0.45f))
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth(fraction.coerceIn(0f, 1f))
                                            .height(3.dp)
                                            .background(MaterialTheme.colorScheme.primary)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = episodeTitle(video, current.title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (fraction != null && fraction > 0.9f) {
                                SmallBadge(stringResource(R.string.series_watched))
                                Spacer(Modifier.width(6.dp))
                            } else if (nextUp?.key == video.key) {
                                SmallBadge(stringResource(R.string.series_next))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                text = buildList {
                                    add(video.sourceId)
                                    episodeVariant(video)?.let(::add)
                                    if (video.durationSec > 0) add(formatDuration(video.durationSec))
                                }.joinToString("  ·  "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "Shoujo Ramune Vol 4 Sub-Eng" -> "Episode 4". */
private fun episodeTitle(video: com.simplecalculator.app.data.source.VideoSummary, series: String): String {
    val number = com.simplecalculator.app.data.source.SeriesGrouping.episodeKey(video)
    if (number.toIntOrNull() != null) return "Episode $number"
    return video.title
        .removePrefix(series)
        .trim(' ', '-', '–', '—', '|', ':', ',')
        .ifBlank { video.title }
}

/** The subtitle/quality tag that distinguishes mirrors of one episode. */
private fun episodeVariant(video: com.simplecalculator.app.data.source.VideoSummary): String? {
    val text = video.title.lowercase()
    return when {
        text.contains("removecensored") || text.contains("remove censored") -> "uncensored"
        text.contains("uncen") -> "uncensored"
        text.contains("sub-eng") || text.contains("eng-sub") -> "sub-eng"
        text.contains("ซับไทย") -> "thai"
        text.contains("censored") -> "censored"
        else -> null
    }
}

@Composable
private fun SmallBadge(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
