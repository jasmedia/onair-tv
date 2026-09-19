package dev.onairtv.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.onairtv.app.data.SavedPlaylist

/** Saved playlists: OK switches to one, ▶ reaches its Remove button. */
@Composable
fun PlaylistsScreen(
    playlists: List<SavedPlaylist>,
    activeUrl: String?,
    onSelect: (SavedPlaylist) -> Unit,
    onRemove: (SavedPlaylist) -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val activeIndex = playlists.indexOfFirst { it.url == activeUrl }
    val initialFocus = remember { FocusRequester() }
    // Also re-run after a removal: the removed row may have held focus.
    LaunchedEffect(playlists) { runCatching { initialFocus.requestFocus() } }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Playlists", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "OK opens a playlist. Press ▶ on a playlist to remove it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onAdd,
                modifier = if (activeIndex < 0) Modifier.focusRequester(initialFocus) else Modifier,
            ) {
                Text("Add playlist")
            }
        }

        LazyColumn(
            modifier = Modifier.widthIn(max = 1000.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(playlists, key = { it.url }) { playlist ->
                val active = playlist.url == activeUrl
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ListItem(
                        selected = active,
                        onClick = { onSelect(playlist) },
                        modifier = Modifier.weight(1f).then(
                            if (active) Modifier.focusRequester(initialFocus) else Modifier,
                        ),
                        headlineContent = {
                            Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(playlist.url, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        trailingContent = if (active) {
                            { Text("Current", style = MaterialTheme.typography.labelLarge) }
                        } else null,
                    )
                    OutlinedButton(onClick = { onRemove(playlist) }) { Text("Remove") }
                }
            }
        }
    }
}
