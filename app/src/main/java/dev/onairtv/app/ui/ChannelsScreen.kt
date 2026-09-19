package dev.onairtv.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.onairtv.app.ALL_CHANNELS
import dev.onairtv.app.FAVORITES
import dev.onairtv.app.PlaylistState
import dev.onairtv.app.data.Channel
import dev.onairtv.app.data.ChannelSearch
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ChannelsScreen(
    state: PlaylistState.Ready,
    selectedGroup: String,
    onGroupSelected: (String) -> Unit,
    favorites: Set<String>,
    onToggleFavorite: (Channel) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    listState: LazyListState,
    focusUrl: String?,
    onPlay: (channels: List<Channel>, position: Int) -> Unit,
    onChangePlaylist: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val group = if (selectedGroup in state.groups) selectedGroup else ALL_CHANNELS
    // A search covers the whole playlist, whichever group is selected.
    val searching = query.isNotBlank()

    val visible = remember(state, group, favorites, query) {
        when {
            searching -> ChannelSearch.filter(state.channels, query)
            group == ALL_CHANNELS -> state.channels
            group == FAVORITES -> state.channels.filter { it.url in favorites }
            else -> state.channels.filter { group in it.groups }
        }
    }

    var editingQuery by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    // Bumped to move focus back into the channel list (e.g. after closing the search field).
    var listFocusRequest by remember { mutableIntStateOf(0) }

    LaunchedEffect(editingQuery) {
        if (editingQuery) runCatching { searchFocus.requestFocus() }
    }
    // Back closes the search field first, then clears the search.
    BackHandler(enabled = editingQuery) {
        editingQuery = false
        listFocusRequest++
    }
    BackHandler(enabled = !editingQuery && searching) {
        onQueryChange("")
        listFocusRequest++
    }

    // Which row should get focus when this screen appears: the last watched channel, else the first.
    val initialFocusIndex = remember(visible) {
        visible.indexOfFirst { it.url == focusUrl }.takeIf { it >= 0 } ?: 0
    }
    val initialFocus = remember { FocusRequester() }

    LaunchedEffect(listFocusRequest) {
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
                    "${if (searching) "Search \"${query.trim()}\"" else group} · ${visible.size} channels",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (editingQuery) {
                    SearchField(
                        query = query,
                        onQueryChange = onQueryChange,
                        onDone = {
                            editingQuery = false
                            listFocusRequest++
                        },
                        modifier = Modifier.focusRequester(searchFocus),
                    )
                } else {
                    OutlinedButton(onClick = { editingQuery = true }) {
                        Text(
                            if (searching) "Search: ${query.trim()}" else "Search",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 280.dp),
                        )
                    }
                }
                OutlinedButton(onClick = onChangePlaylist) { Text("Change playlist") }
            }
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
                        selected = !searching && g == group,
                        onClick = {
                            if (searching || g != group) {
                                onQueryChange("")
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
                    Text(
                        when {
                            searching -> "No channels match \"${query.trim()}\""
                            group == FAVORITES -> "No favorites yet. Hold OK on a channel to add it."
                            else -> "No channels in this group"
                        },
                    )
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
                            isFavorite = channel.url in favorites,
                            onClick = { onPlay(visible, position) },
                            onLongClick = { onToggleFavorite(channel) },
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
internal fun ChannelRow(
    channel: Channel,
    number: Int,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        selected = false,
        onClick = onClick,
        onLongClick = onLongClick,
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
        trailingContent = if (isFavorite) {
            { Text("★", style = MaterialTheme.typography.titleMedium) }
        } else null,
    )
}

/** The search box. Typing filters the list live; Done (or Back) returns focus to the list. */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onDone() }, onDone = { onDone() }),
        modifier = modifier
            .width(320.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        decorationBox = { inner ->
            if (query.isEmpty()) {
                Text("Channel name", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            inner()
        },
    )
}
