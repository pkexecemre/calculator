package com.simplecalculator.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.simplecalculator.app.R
import com.simplecalculator.app.data.source.Category
import com.simplecalculator.app.data.source.HentaiFlavor
import com.simplecalculator.app.data.source.SeriesGroup
import com.simplecalculator.app.data.source.SeriesGrouping
import com.simplecalculator.app.data.source.SeriesStore
import com.simplecalculator.app.data.source.VideoSummary
import com.simplecalculator.app.ui.EmptyState
import com.simplecalculator.app.ui.FilterChipRow
import com.simplecalculator.app.ui.LocalContainer
import com.simplecalculator.app.ui.SectionHeader
import com.simplecalculator.app.ui.SeriesCard
import com.simplecalculator.app.ui.SkeletonCard
import com.simplecalculator.app.ui.VideoCard
import com.simplecalculator.app.ui.theme.TextMuted
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    category: Category,
    onOpen: (String) -> Unit,
    onOpenSeries: (String) -> Unit
) {
    val container = LocalContainer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<VideoSummary>>(emptyList()) }
    var groups by remember { mutableStateOf<List<SeriesGroup>>(emptyList()) }
    var flavorFilter by remember { mutableStateOf<HentaiFlavor?>(null) }
    var sourceFilter by remember { mutableStateOf<String?>(null) }
    var tagFilter by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(1) }
    var searchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val prefs = remember {
        container.appContext.getSharedPreferences("search_state", android.content.Context.MODE_PRIVATE)
    }
    fun recent(): List<String> =
        prefs.getString("recent", "")!!.split('\n').filter { it.isNotBlank() }

    var recents by remember { mutableStateOf(recent()) }

    fun rememberQuery(text: String) {
        val clean = text.trim()
        if (clean.length < 2) return
        val next = (listOf(clean) + recents.filterNot { it.equals(clean, ignoreCase = true) }).take(10)
        recents = next
        prefs.edit().putString("recent", next.joinToString("\n")).apply()
    }

    fun run(nextPage: Int) {
        if (query.isBlank() || loading) return
        if (nextPage == 1) {
            rememberQuery(query)
            items = emptyList()
            groups = emptyList()
            searched = false
        }
        searchJob?.cancel()
        searchJob = scope.launch {
            loading = true
            page = nextPage
            try {
                // Every category streams: the first source's hits render while
                // the rest are still loading. Hentai just groups each update.
                container.content.searchStream(
                    category, query.trim(), nextPage, sourceFilter, tagFilter
                ).collect { update ->
                    if (category == Category.HENTAI) {
                        val fresh = SeriesStore.putAll(SeriesGrouping.group(update.items))
                        val merged = if (nextPage == 1) {
                            fresh
                        } else {
                            (groups + fresh).distinctBy { it.key }
                        }
                        groups = if (flavorFilter != null) {
                            merged.filter { group ->
                                group.videos.any { it.flavor == flavorFilter }
                            }
                        } else {
                            merged
                        }
                        items = emptyList()
                    } else {
                        items = if (nextPage == 1) {
                            update.items
                        } else {
                            (items + update.items).distinctBy { it.key }
                        }
                    }
                    if (items.isNotEmpty() || groups.isNotEmpty() || update.done) {
                        searched = true
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                searched = true
            } finally {
                // Only the current job may clear the loading flag; a canceled
                // job must not overwrite the replacement search's state.
                if (searchJob == kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]) {
                    loading = false
                    searchJob = null
                }
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(category) {
        searchJob?.cancel()
        searchJob = null
        loading = false
        items = emptyList()
        groups = emptyList()
        flavorFilter = null
        sourceFilter = null
        tagFilter = null
        page = 1
        searched = false
    }

    val listSize = if (category == Category.HENTAI) groups.size else items.size

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            Text(
                text = stringResource(R.string.nav_search),
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = stringResource(
                    when (category) {
                        Category.LIVE -> R.string.category_live
                        Category.JAV -> R.string.category_jav
                        Category.HENTAI -> R.string.category_hentai
                        Category.ARCHIVE -> R.string.category_archive
                    }
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
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedContainerColor = com.simplecalculator.app.ui.theme.Surface2,
                unfocusedContainerColor = com.simplecalculator.app.ui.theme.Surface2,
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent
            ),
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = null, tint = TextMuted)
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = ""; searched = false; items = emptyList(); groups = emptyList() }) {
                        Icon(Icons.Filled.Close, contentDescription = null, tint = TextMuted)
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { run(1) })
        )

        val sourceOptions = remember(category) {
            listOf(com.simplecalculator.app.ui.FilterOption(null, context.getString(R.string.filter_source_all))) +
                container.sources.byCategory(category).map {
                    com.simplecalculator.app.ui.FilterOption(it.id, it.displayName, it.category.name)
                }
        }
        FilterChipRow(
            sourceOptions = sourceOptions,
            sourceFilter = sourceFilter,
            tagFilter = tagFilter,
            onSource = { sourceFilter = it; if (searched) run(1) },
            onTag = { tagFilter = it; if (searched) run(1) },
            onReset = {
                sourceFilter = null
                tagFilter = null
                if (searched) run(1)
            }
        )
        Spacer(Modifier.height(6.dp))

        if (!searched && recents.isNotEmpty()) {
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
                    prefs.edit().putString("recent", "").apply()
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
                            rememberQuery(term)
                        },
                        label = { Text(term) }
                    )
                }
            }
        }

        when {
            loading && listSize == 0 -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(6) { SkeletonCard() }
            }

            listSize == 0 -> EmptyState(
                title = if (searched) stringResource(R.string.search_empty) else stringResource(R.string.search_prompt_title),
                subtitle = if (searched) stringResource(R.string.search_empty_body) else stringResource(R.string.search_prompt_body),
                icon = Icons.Filled.Search
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Column {
                        SectionHeader(stringResource(R.string.search_results, listSize))
                    }
                }
                if (category == Category.HENTAI) {
                    items(groups, key = { it.key }) { group ->
                        SeriesCard(group = group, onClick = { onOpenSeries(group.key) })
                    }
                } else {
                    items(items, key = { it.key }) { video ->
                        VideoCard(video = video, onClick = { onOpen(video.key) })
                    }
                }
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    androidx.compose.material3.TextButton(
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

private fun flavorLabel(flavor: HentaiFlavor): Int = when (flavor) {
    HentaiFlavor.STUDIO -> R.string.flavor_studio
    HentaiFlavor.USER -> R.string.flavor_user
    HentaiFlavor.VR -> R.string.flavor_vr
    HentaiFlavor.UNKNOWN -> R.string.flavor_all
}
