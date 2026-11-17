package com.simplecalculator.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplecalculator.app.R
import com.simplecalculator.app.core.formatDuration
import com.simplecalculator.app.ui.CategoryTabs
import com.simplecalculator.app.ui.EmptyState
import com.simplecalculator.app.ui.LocalContainer
import com.simplecalculator.app.ui.Thumb
import com.simplecalculator.app.ui.theme.TextMuted
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(onOpen: (String) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    val favorites by container.content.favorites().collectAsStateWithLifecycle(initialValue = emptyList())
    val history by container.content.history().collectAsStateWithLifecycle(initialValue = emptyList())

    Column(Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.nav_library),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
        )
        CategoryTabs(
            labels = listOf(
                stringResource(R.string.library_favorites, favorites.size),
                stringResource(R.string.library_history, history.size),
                stringResource(R.string.nav_downloads)
            ),
            selectedIndex = tab,
            onSelect = { tab = it },
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(10.dp))

        when (tab) {
            0 -> {
                if (favorites.isEmpty()) {
                    EmptyState(stringResource(R.string.favorites_empty_title), stringResource(R.string.favorites_empty_body), icon = Icons.Filled.BookmarkBorder)
                } else {
                    LazyColumn(contentPadding = PaddingValues(12.dp)) {
                        items(favorites, key = { it.key }) { row ->
                            LibraryRow(
                                key = row.key,
                                title = row.title,
                                thumb = row.thumb,
                                subtitle = null,
                                progress = -1f,
                                onClick = { onOpen(row.key) },
                                onDelete = { scope.launch { container.content.toggleFavorite(row.key, row.sourceId, row.title, row.thumb) } }
                            )
                        }
                    }
                }
            }

            1 -> {
                if (history.isEmpty()) {
                    EmptyState(stringResource(R.string.history_empty_title), stringResource(R.string.history_empty_body), icon = Icons.Filled.History)
                } else {
                    LazyColumn(contentPadding = PaddingValues(12.dp)) {
                        items(history, key = { it.key }) { row ->
                            val fraction = if (row.durationMs > 0) {
                                (row.positionMs.toFloat() / row.durationMs).coerceIn(0f, 1f)
                            } else 0f
                            LibraryRow(
                                key = row.key,
                                title = row.title,
                                thumb = row.thumb,
                                subtitle = formatDuration((row.positionMs / 1000).toInt()) + " / " +
                                    formatDuration((row.durationMs / 1000).toInt()),
                                progress = fraction,
                                onClick = { onOpen(row.key) },
                                onDelete = { scope.launch { container.content.removeHistory(row.key) } }
                            )
                        }
                    }
                }
            }

            else -> DownloadsScreen(onOpen = onOpen)
        }
    }
}

@Composable
private fun LibraryRow(
    key: String,
    title: String,
    thumb: String,
    subtitle: String?,
    progress: Float,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(124.dp)) {
            Thumb(thumb, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (progress >= 0f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .padding(top = 6.dp)
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = null, tint = TextMuted)
        }
    }
}
