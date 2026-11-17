package com.simplecalculator.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.simplecalculator.app.R
import com.simplecalculator.app.data.source.Tags
import com.simplecalculator.app.ui.theme.TextMuted

data class FilterOption(val id: String?, val label: String, val hint: String? = null)

private enum class Picker { NONE, SOURCE, TAG }

/**
 * Mobile-first filters: one compact chip per dimension instead of a single
 * sheet containing every control. Each chip opens a short, single-purpose list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterChipRow(
    sourceOptions: List<FilterOption>,
    sourceFilter: String?,
    tagFilter: String?,
    compactSources: Boolean = false,
    onSource: (String?) -> Unit,
    onTag: (String?) -> Unit,
    onReset: () -> Unit
) {
    var picker by remember { mutableStateOf(Picker.NONE) }
    val sourceLabel = sourceOptions.firstOrNull { it.id == sourceFilter }?.label
        ?: sourceOptions.firstOrNull { it.id == null }?.label.orEmpty()
    val tagLabel = tagFilter?.let { id -> Tags.all.firstOrNull { it.id == id }?.label ?: id }
        ?: stringResource(R.string.filter_any)
    val active = sourceFilter != null || tagFilter != null

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AssistChip(
            onClick = { picker = Picker.SOURCE },
            label = {
                Text(
                    text = stringResource(R.string.filter_source_chip, sourceLabel),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            trailingIcon = {
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = com.simplecalculator.app.ui.theme.Surface2,
                labelColor = if (sourceFilter != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                }
            ),
            border = null
        )
        AssistChip(
            onClick = { picker = Picker.TAG },
            label = {
                Text(
                    text = stringResource(R.string.filter_tag_chip, tagLabel),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            trailingIcon = {
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = com.simplecalculator.app.ui.theme.Surface2,
                labelColor = if (tagFilter != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                }
            ),
            border = null
        )
        if (active) {
            TextButton(onClick = onReset) {
                Text(stringResource(R.string.filter_reset))
            }
        }
    }

    when (picker) {
        Picker.SOURCE -> OptionSheet(
            title = stringResource(R.string.filter_sources),
            options = sourceOptions,
            selected = sourceFilter,
            searchable = !compactSources,
            onDismiss = { picker = Picker.NONE },
            onSelect = { onSource(it); picker = Picker.NONE }
        )

        Picker.TAG -> OptionSheet(
            title = stringResource(R.string.filter_tags),
            options = listOf(FilterOption(null, stringResource(R.string.filter_any))) +
                Tags.all.map { FilterOption(it.id, it.label) },
            selected = tagFilter,
            searchable = true,
            onDismiss = { picker = Picker.NONE },
            onSelect = { onTag(it); picker = Picker.NONE }
        )

        Picker.NONE -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionSheet(
    title: String,
    options: List<FilterOption>,
    selected: String?,
    searchable: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val visible = options.filter {
        query.isBlank() || it.label.contains(query, ignoreCase = true)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            if (searchable) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.filter_search_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(12.dp)
                )
            } else {
                Spacer(Modifier.height(8.dp))
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(visible, key = { it.id ?: "any" }) { option ->
                    val isSelected = option.id == selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option.id) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (option.hint != null) {
                                Text(
                                    text = option.hint,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted
                                )
                            }
                        }
                        if (isSelected) Icon(Icons.Filled.Check, contentDescription = null)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    }
}

/** Small action sheet used by long-press on cards. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(
    title: String,
    actions: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            actions.forEach { (label, action) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            onDismiss()
                            action()
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
