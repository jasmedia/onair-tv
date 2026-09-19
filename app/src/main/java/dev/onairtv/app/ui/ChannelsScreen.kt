package dev.onairtv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.onairtv.app.ALL_CHANNELS
import dev.onairtv.app.PlaylistState
import dev.onairtv.app.data.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ChannelsScreen(
    state: PlaylistState.Ready,
    selectedGroup: String,
    onGroupSelected: (String) -> Unit,
    listState: LazyListState,
    focusUrl: String?,
    onPlay: (channels: List<Channel>, position: Int) -> Unit,
    onChangePlaylist: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val group = if (selectedGroup in state.groups) selectedGroup else ALL_CHANNELS

    val visible = remember(state, group) {
        if (group == ALL_CHANNELS) state.channels
        else state.channels.filter { group in it.groups }
    }

    // Which row should get focus when this screen appears: the last watched channel, else the first.
    val initialFocusIndex = remember(visible) {
        visible.indexOfFirst { it.url == focusUrl }.takeIf { it >= 0 } ?: 0
    }
    val initialFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (visible.isEmpty()) return@LaunchedEffect
        val info = listState.layoutInfo
        val onScreen = info.visibleItemsInfo.any { it.index == initialFocusIndex }
        if (!onScreen) listState.scrollToItem((initialFocusIndex - 3).coerceAtLeast(0))
        delay(50) // let the row compose before focusing it
        runCatching { initialFocus.requestFocus() }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("OnAir TV", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "$group · ${visible.size} channels",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onChangePlaylist) { Text("Change playlist") }
        }

        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            // Groups (select with OK; moving focus alone doesn't switch groups).
            LazyColumn(
                modifier = Modifier.width(260.dp).fillMaxHeight(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(state.groups, key = { it }) { g ->
                    ListItem(
                        selected = g == group,
                        onClick = {
                            if (g != group) {
                                onGroupSelected(g)
                                scope.launch { listState.scrollToItem(0) }
                            }
                        },
                        headlineContent = {
                            Text(g, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                    )
                }
            }

            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No channels in this group")
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(visible, key = { _, ch -> ch.index }) { position, channel ->
                        ChannelRow(
                            channel = channel,
                            number = position + 1,
                            onClick = { onPlay(visible, position) },
                            modifier = if (position == initialFocusIndex) {
                                Modifier.focusRequester(initialFocus)
                            } else Modifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: Channel,
    number: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        selected = false,
        onClick = onClick,
        modifier = modifier,
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(width = 72.dp, height = 40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.logo != null) {
                    AsyncImage(
                        model = channel.logo,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(4.dp),
                    )
                } else {
                    Text(
                        channel.name.take(2).uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        },
        headlineContent = {
            Text("$number  ${channel.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                channel.groups.joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}
