package com.simplecalculator.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplecalculator.app.R
import com.simplecalculator.app.core.formatBytes
import com.simplecalculator.app.data.db.DownloadEntity
import com.simplecalculator.app.ui.EmptyState
import com.simplecalculator.app.ui.LocalContainer
import com.simplecalculator.app.ui.Thumb
import com.simplecalculator.app.ui.theme.TextMuted
import kotlinx.coroutines.launch

@Composable
fun DownloadsScreen(onOpen: (String) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val rows by container.downloads.observe().collectAsStateWithLifecycle(initialValue = emptyList())

    Column(Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.nav_downloads),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
        )
        if (rows.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.downloads_empty_title),
                subtitle = stringResource(R.string.downloads_empty_body),
                icon = Icons.Filled.Download
            )
            return@Column
        }
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
            items(rows, key = { it.key }) { row ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                        .clickable(enabled = row.state == "done") { onOpen(row.key) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.width(124.dp)) {
                        Thumb(row.thumb, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                        if (row.state == "done") {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .background(
                                        androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f),
                                        androidx.compose.foundation.shape.CircleShape
                                    )
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = row.title,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        when (row.state) {
                            "done" -> Text(
                                text = formatBytes(row.bytes) + " · " + stringResource(R.string.downloads_play),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                            "failed" -> Column {
                                Text(
                                    text = stringResource(R.string.downloads_failed),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                if (row.error != null) {
                                    Text(
                                        text = row.error,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            "queued" -> Text(
                                text = stringResource(R.string.downloads_queued),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                            else -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    LinearProgressIndicator(
                                        progress = { row.progress / 100f },
                                        modifier = Modifier.weight(1f).height(4.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = row.progress.toString() + "%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted
                                    )
                                }
                            }
                        }
                    }
                    if (row.state == "failed") {
                        IconButton(onClick = { scope.launch { container.downloads.retry(row) } }) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.downloads_retry),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    IconButton(onClick = { scope.launch { container.downloads.cancel(row) } }) {
                        Icon(Icons.Filled.Delete, contentDescription = null, tint = TextMuted)
                    }
                }
            }
        }
    }
}
