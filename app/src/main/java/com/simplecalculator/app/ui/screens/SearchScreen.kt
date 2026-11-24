package com.simplecalculator.app.ui.screens

import android.content.Context
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.simplecalculator.app.R
import com.simplecalculator.app.data.source.Category
import com.simplecalculator.app.data.source.VideoSummary
import com.simplecalculator.app.ui.EmptyState
import com.simplecalculator.app.ui.LocalContainer
import com.simplecalculator.app.ui.SectionHeader
import com.simplecalculator.app.ui.SkeletonCard
import com.simplecalculator.app.ui.VideoCard
import com.simplecalculator.app.ui.theme.Surface2
import com.simplecalculator.app.ui.theme.TextMuted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(category: Category, onOpen: (String) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<VideoSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var stale by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(1) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    val prefs = remember {
        container.appContext.getSharedPreferences("search_state", Context.MODE_PRIVATE)
    }
    // Per-source history: a MISSAV query must not clutter Pornhub recents.
    val recentKey = "recent:" + category.name
    fun recent(): List<String> =
        prefs.getString(recentKey, "")!!.split('\n').filter { it.isNotBlank() }

    var recents by remember(category) { mutableStateOf(recent()) }

    fun rememberQuery(text: String) {
        val clean = text.trim()
        if (clean.length < 2) return
        val next = (listOf(clean) + recents.filterNot { it.equals(clean, ignoreCase = true) }).take(10)
        recents = next
        prefs.edit().putString(recentKey, next.joinToString("\n")).apply()
    }

    fun run(nextPage: Int) {
        val clean = query.trim()
        if (clean.isBlank() || loading || loadingMore) return
        if (nextPage == 1) {
            rememberQuery(clean)
            items = emptyList()
            searched = false
            error = null
            stale = false
            hasMore = false
        }
        searchJob?.cancel()
        loadingMore = false
        if (nextPage == 1) loading = true else loadingMore = true
        searchJob = scope.launch {
            try {
                val result = container.content.search(
                    category = category,
                    query = clean,
                    page = nextPage
                )
                stale = if (nextPage == 1) result.stale else stale || result.stale
                error = result.error
                if (nextPage == 1) {
                    if (result.items.isNotEmpty()) {
                        items = result.items
                        page = 1
                        hasMore = true
                    }
                } else if (result.items.isNotEmpty()) {
                    val before = items.size
                    val merged = (items + result.items).distinctBy { it.key }
                    items = merged
                    page = nextPage
                    // A page with no new cards means pagination is not making
                    // progress; hide the button instead of repeating it.
                    hasMore = merged.size > before
                } else {
                    hasMore = false
                }
                searched = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: ""
                searched = true
            } finally {
                // Only the current job may clear the flags; a cancelled job must
                // not overwrite the replacement search's state.
                if (searchJob == currentCoroutineContext()[Job]) {
                    loading = false
                    loadingMore = false
                    searchJob = null
                }
            }
        }
    }

    fun clearQuery() {
        searchJob?.cancel()
        searchJob = null
        loading = false
        loadingMore = false
        query = ""
        items = emptyList()
        searched = false
        error = null
        stale = false
        hasMore = false
        page = 1
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            Text(
                text = stringResource(R.string.nav_search),
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = stringResource(
                    if (category == Category.JAV) R.string.search_subtitle_msav else R.string.search_subtitle
                ),
                style = MaterialTheme.typography.labelMedium,
                color = TextMuted
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            placeholder = { Text(stringResource(R.string.search_hint)) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Surface2,
                unfocusedContainerColor = Surface2,
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                unfocusedBorderColor = Color.Transparent
            ),
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = null, tint = TextMuted)
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { clearQuery() }) {
                        Icon(Icons.Filled.Close, contentDescription = null, tint = TextMuted)
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { run(1) })
        )

        if (error != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        if (category == Category.JAV) R.string.search_failed_msav else R.string.search_failed
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { run(1) }) {
                    Text(stringResource(R.string.common_retry))
                }
            }
        }
        if (stale) {
            Text(
                text = stringResource(
                    if (category == Category.JAV) R.string.search_offline_banner_msav else R.string.search_offline_banner
                ),
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        if (!searched && !loading && recents.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.search_recent),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMuted,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    recents = emptyList()
                    prefs.edit().putString(recentKey, "").apply()
                }) { Text(stringResource(R.string.search_clear_recent)) }
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(recents) { term ->
                    AssistChip(
                        onClick = {
                            query = term
                            run(1)
                        },
                        label = { Text(term) }
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        when {
            loading && items.isEmpty() -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(6) { SkeletonCard() }
            }

            items.isEmpty() -> EmptyState(
                title = if (searched) {
                    stringResource(R.string.search_empty)
                } else {
                    stringResource(R.string.search_prompt_title)
                },
                subtitle = if (searched) {
                    stringResource(R.string.search_empty_body)
                } else {
                    stringResource(R.string.search_prompt_body)
                },
                icon = Icons.Filled.Search
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeader(stringResource(R.string.search_results, items.size))
                }
                items(items, key = { it.key }) { video ->
                    VideoCard(video = video, onClick = { onOpen(video.key) })
                }
                when {
                    loadingMore -> {
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
                    hasMore -> {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            TextButton(
                                onClick = { run(page + 1) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.common_more))
                            }
                        }
                    }
                }
            }
        }
    }
}
