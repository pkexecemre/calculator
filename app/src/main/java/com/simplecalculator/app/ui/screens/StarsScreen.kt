package com.simplecalculator.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplecalculator.app.R
import com.simplecalculator.app.data.db.StarEntity
import com.simplecalculator.app.ui.EmptyState
import com.simplecalculator.app.ui.LocalContainer
import com.simplecalculator.app.ui.theme.Surface2
import com.simplecalculator.app.ui.theme.TextMuted

@Composable
fun StarsScreen(onOpenStar: (String) -> Unit, onOpenVideo: (String) -> Unit) {
    val container = LocalContainer.current
    val stars by container.content.followedStars().collectAsStateWithLifecycle(initialValue = emptyList())

    Column(Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.nav_stars),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
        )
        if (stars.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.stars_empty_title),
                subtitle = stringResource(R.string.stars_empty_body),
                icon = Icons.Filled.StarBorder
            )
            return@Column
        }
        Text(
            text = stringResource(R.string.stars_count, stars.size),
            style = MaterialTheme.typography.labelMedium,
            color = TextMuted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            items(stars, key = { it.slug }) { star ->
                StarRow(star = star, onClick = { onOpenStar(star.rawSlug) })
            }
        }
    }
}

@Composable
fun StarRow(star: StarEntity, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(Surface2),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = star.name.take(1).uppercase(),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = star.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.stars_videos_watched, star.hits),
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.titleLarge,
            color = TextMuted
        )
    }
}
