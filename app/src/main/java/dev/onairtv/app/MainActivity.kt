package dev.onairtv.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.darkColorScheme
import dev.onairtv.app.data.Channel
import dev.onairtv.app.data.SavedPlaylist
import dev.onairtv.app.ui.ChannelsScreen
import dev.onairtv.app.ui.EpgSource
import dev.onairtv.app.ui.ErrorScreen
import dev.onairtv.app.ui.LoadingScreen
import dev.onairtv.app.ui.PlayerScreen
import dev.onairtv.app.ui.PlaylistsScreen
import dev.onairtv.app.ui.SetupScreen
import dev.onairtv.app.ui.rememberEpgClock

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), shape = RectangleShape) {
                    OnAirTvApp()
                }
            }
        }
    }
}

/** The list being zapped through and the position within it. */
private data class Playback(val channels: List<Channel>, val position: Int)

@Composable
private fun OnAirTvApp(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()

    // No `by` and no read of clock.value here: a tick must recompose the rows showing a programme,
    // not this whole tree.
    val guide by vm.guide.collectAsStateWithLifecycle()
    val clock = rememberEpgClock()
    val epg = remember(guide, clock) { EpgSource(guide, clock) }

    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val activeUrl by vm.activeUrl.collectAsStateWithLifecycle()

    var showPlaylists by rememberSaveable { mutableStateOf(false) }
    var showSetup by rememberSaveable { mutableStateOf(false) }
    // Non-null when the setup screen is editing an existing playlist rather than adding one.
    var editPlaylist by remember { mutableStateOf<SavedPlaylist?>(null) }
    var selectedGroup by rememberSaveable { mutableStateOf(ALL_CHANNELS) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var playback by remember { mutableStateOf<Playback?>(null) }
    var focusUrl by remember { mutableStateOf(vm.lastChannelUrl) }
    // Replaced on playlist switch, so the new list doesn't open at the old scroll position.
    var channelListState by remember { mutableStateOf(LazyListState()) }

    // Called when a different playlist becomes current: browsing starts over.
    val resetBrowsing = {
        selectedGroup = ALL_CHANNELS
        searchQuery = ""
        channelListState = LazyListState()
    }
    val onPlaylistOpened = {
        showSetup = false
        editPlaylist = null
        showPlaylists = false
        resetBrowsing()
    }

    when (val s = state) {
        PlaylistState.NotConfigured -> SetupScreen(
            onSubmit = { url, name, epgUrl ->
                vm.addPlaylist(url, name, epgUrl)
                onPlaylistOpened()
            },
            onCancel = null,
        )

        else -> if (showSetup) {
            SetupScreen(
                onSubmit = { url, name, epgUrl ->
                    vm.addPlaylist(url, name, epgUrl)
                    onPlaylistOpened()
                },
                onCancel = { showSetup = false; editPlaylist = null },
                initial = editPlaylist,
            )
        } else if (showPlaylists) {
            PlaylistsScreen(
                playlists = playlists,
                activeUrl = activeUrl,
                onSelect = { playlist ->
                    if (playlist.url != activeUrl) {
                        vm.selectPlaylist(playlist)
                        onPlaylistOpened()
                    } else {
                        showPlaylists = false
                    }
                },
                onEditEpg = { playlist ->
                    editPlaylist = playlist
                    showSetup = true
                },
                onRemove = { playlist ->
                    if (playlist.url == activeUrl) resetBrowsing()
                    vm.removePlaylist(playlist)
                },
                onAdd = { editPlaylist = null; showSetup = true },
                onBack = { showPlaylists = false },
            )
        } else when (s) {
            PlaylistState.Loading -> LoadingScreen()

            is PlaylistState.Failed -> ErrorScreen(
                message = s.message,
                onRetry = vm::retry,
                onChangePlaylist = { showPlaylists = true },
            )

            is PlaylistState.Ready -> {
                val current = playback
                if (current == null) {
                    ChannelsScreen(
                        state = s,
                        selectedGroup = selectedGroup,
                        onGroupSelected = { selectedGroup = it },
                        favorites = favorites,
                        onToggleFavorite = vm::toggleFavorite,
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        epg = epg,
                        listState = channelListState,
                        focusUrl = focusUrl,
                        onPlay = { list, position ->
                            playback = Playback(list, position)
                        },
                        onChangePlaylist = { showPlaylists = true },
                    )
                } else {
                    PlayerScreen(
                        channels = current.channels,
                        position = current.position,
                        onPositionChange = { playback = current.copy(position = it) },
                        onChannelStarted = { channel ->
                            vm.rememberChannel(channel)
                            focusUrl = channel.url
                        },
                        favorites = favorites,
                        onToggleFavorite = vm::toggleFavorite,
                        epg = epg,
                        onExit = { playback = null },
                    )
                }
            }

            PlaylistState.NotConfigured -> Unit // handled above
        }
    }
}
