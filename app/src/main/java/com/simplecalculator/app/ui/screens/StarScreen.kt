package com.simplecalculator.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import com.simplecalculator.app.R
import com.simplecalculator.app.data.source.VideoSummary
import com.simplecalculator.app.ui.LocalContainer
import com.simplecalculator.app.ui.VideoCard
import com.simplecalculator.app.ui.theme.TextMuted
import kotlinx.coroutines.launch

@Composable
fun StarScreen(slug: String, onBack: () -> Unit, onOpenVideo: (String) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<VideoSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(1) }
    var hasMore by remember { mutableStateOf(true) }
    var following by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(slug) }
    var avatar by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(slug) {
        following = container.content.isFollowing(slug)
        val cached = container.db.stars().bySlug("ph:" + slug)
        name = cached?.name ?: slug
        avatar = cached?.avatar
        if (avatar == null) {
            avatar = container.sources.primary().starAvatar(slug)
        }
        items = runCatching { container.content.starFeed(slug, 1) }.getOrDefault(emptyList())
        loading = false
    }

    Column {
        Box {
            IconButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp, start = 4.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
        }
        if (loading) {
            Box(Modifier.padding(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    val avatarUrl = avatar
                    if (avatarUrl != null) {
                        coil.compose.AsyncImage(
                            model = avatarUrl,
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier
                                .padding(bottom = 8.dp)
                                .size(72.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                        )
                    }
                    Text(name, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = if (hasMore) stringResource(R.string.star_videos_more, items.size)
                        else stringResource(R.string.star_videos, items.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = TextMuted
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 8.dp))
                    if (following) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                container.content.toggleFollow(slug, name, null)
                                following = false
                            }
                        }) { Text(stringResource(R.string.star_following)) }
                    } else {
                        Button(onClick = {
                            scope.launch {
                                container.content.toggleFollow(slug, name, null)
                                following = true
                            }
                        }) { Text(stringResource(R.string.star_follow)) }
                    }
                }
            }
            items(items, key = { it.key }) { video ->
                VideoCard(
                    video = video,
                    onClick = { onOpenVideo(video.key) },
                    modelLabel = name
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                if (loadingMore) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    TextButton(
                        onClick = {
                            val next = page + 1
                            scope.launch {
                                loadingMore = true
                                runCatching { container.content.starFeed(slug, next) }
                                    .onSuccess { batch ->
                                        if (batch.isEmpty()) {
                                            hasMore = false
                                            return@onSuccess
                                        }
                                        page = next
                                        items = (items + batch).distinctBy { it.key }
                                    }
                                loadingMore = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.common_more)) }
                }
            }
        }
    }
}
