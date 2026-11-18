package com.simplecalculator.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.simplecalculator.app.Container
import com.simplecalculator.app.R
import com.simplecalculator.app.core.formatDuration
import com.simplecalculator.app.core.formatViews
import com.simplecalculator.app.data.source.SeriesGroup
import com.simplecalculator.app.data.source.VideoSummary
import com.simplecalculator.app.ui.theme.Surface2
import com.simplecalculator.app.ui.theme.TextMuted

val LocalContainer = staticCompositionLocalOf<Container> { error("Container not provided") }

/** Protocol-relative and space-containing URLs are common in scraped pages. */
internal fun normalizeImageUrl(raw: String): String {
    val trimmed = raw.trim()
    val absolute = when {
        trimmed.startsWith("//") -> "https:$trimmed"
        trimmed.startsWith("http") -> trimmed
        trimmed.isEmpty() -> ""
        else -> "https://$trimmed"
    }
    return absolute.replace(" ", "%20")
}

@Composable
fun Thumb(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Surface2)
    ) {
        val source = normalizeImageUrl(url)
        if (source.isNotEmpty()) {
            // Placeholder under the image: a failed hotlink never leaves a hole.
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = TextMuted.copy(alpha = 0.3f),
                modifier = Modifier.align(Alignment.Center).size(28.dp)
            )
            AsyncImage(
                model = source,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.align(Alignment.Center).size(36.dp)
            )
        }
    }
}

/**
 * Poster frame that never crops the artwork: the image is fitted on top of a
 * blurred, darkened copy of itself. Portrait covers fill the frame and landscape
 * stills bleed into the background instead of losing their edges.
 */
@Composable
fun PosterThumb(
    url: String,
    modifier: Modifier = Modifier,
    ratio: Float = 2f / 3f
) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Surface2)
    ) {
        val source = normalizeImageUrl(url)
        if (source.isEmpty()) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.align(Alignment.Center).size(36.dp)
            )
        } else {
            // The bleed exists to be blurred: decoding a 96 px copy is much
            // cheaper than decoding the poster twice, and the visual result is
            // indistinguishable after the blur.
            val bleed = ImageRequest.Builder(LocalContext.current)
                .data(source)
                .size(96, 96)
                .build()
            AsyncImage(
                model = bleed,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .blur(10.dp)
                    .alpha(0.85f)
            )
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.35f))
            )
            AsyncImage(
                model = source,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize()
            )
        }
    }
}

@Composable
fun DurationBadge(seconds: Int, modifier: Modifier = Modifier) {
    if (seconds <= 0) return
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = formatDuration(seconds),
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoCard(
    video: VideoSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    modelLabel: String? = null,
    progress: Float? = null,
    onLongClick: (() -> Unit)? = null
) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(14.dp))
                .background(Surface2)
        ) {
            Thumb(video.thumb, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            // Bottom scrim keeps badges legible over bright thumbnails.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(46.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))
                        )
                    )
            )
            DurationBadge(video.durationSec, Modifier.align(Alignment.BottomEnd).padding(8.dp))
            if (trailing != null) {
                Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) { trailing() }
            }
            if (progress != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.Black.copy(alpha = 0.45f))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val translated = translatedTitle(video.title, enabled = true)
        Text(
            text = translated ?: video.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onBackground
        )
        if (translated != null) {
            Text(
                text = video.title,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        val secondary = modelLabel ?: video.uploaderName
        if (secondary != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (modelLabel != null) MaterialTheme.colorScheme.secondary else TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (video.verifiedUploader) {
                    Spacer(Modifier.width(3.dp))
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(11.dp)
                    )
                }
                if (video.views > 0) {
                    Text(
                        text = "  " + formatViews(video.views),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SeriesCard(
    group: SeriesGroup,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    watchedEpisodes: Int = 0,
    onLongClick: (() -> Unit)? = null
) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            PosterThumb(group.thumb, Modifier.fillMaxSize())
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = if (watchedEpisodes > 0) {
                        stringResource(
                            R.string.series_progress,
                            watchedEpisodes,
                            group.displayEpisodes
                        )
                    } else {
                        androidx.compose.ui.res.pluralStringResource(
                            R.plurals.series_episode_count,
                            group.displayEpisodes,
                            group.displayEpisodes
                        )
                    },
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        val translatedGroup = translatedTitle(group.title, enabled = true)
        Text(
            text = translatedGroup ?: group.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = group.sourceIds.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun EmptyState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.Person
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
            modifier = Modifier.size(44.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

/** Wide hero banner for the first item of a feed. */
@Composable
fun HeroCard(
    video: VideoSummary,
    label: String,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Surface2)
            .clickable(onClick = onPlay)
    ) {
        // Same treatment as the poster cards: portrait covers are shown whole
        // over a blurred bleed instead of being cropped to a sliver.
        PosterThumb(video.thumb, Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.25f),
                            Color.Black.copy(alpha = 0.6f),
                            Color.Black.copy(alpha = 0.93f)
                        )
                    )
                )
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        ) {
            Text(
                text = label.uppercase(),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = video.title,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontSize = 21.sp,
                lineHeight = 26.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = Color.White,
                    contentColor = Color.Black,
                    shape = RoundedCornerShape(50)
                ) {
                    Row(
                        Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.player_play),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
                if (video.durationSec > 0) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = formatDuration(video.durationSec),
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

/** Section title used above rails and grids. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null) {
    Row(
        modifier.fillMaxWidth().padding(top = 16.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .padding(end = 8.dp)
                .size(width = 3.dp, height = 16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        if (action != null) {
            Text(
                text = action,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
        }
    }
}

/** Shimmer-free placeholder that still reads as "loading" rather than "broken". */
@Composable
fun SkeletonCard(modifier: Modifier = Modifier) {
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(14.dp))
                .background(Surface2)
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth(0.9f)
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Surface2)
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth(0.55f)
                .height(10.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Surface2)
        )
    }
}

/** Segmented category switcher: one closed pill, selected segment filled. */
@Composable
fun CategoryTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface2)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        else Color.Transparent
                    )
                    .clickable { onSelect(index) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else TextMuted,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

/** Small scrollable pills, same visual language as CategoryTabs. */
@Composable
fun PillChips(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        itemsIndexed(labels) { index, label ->
            val selected = index == selectedIndex
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        else Surface2
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = label,
                    color = if (selected) MaterialTheme.colorScheme.primary else TextMuted,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}
