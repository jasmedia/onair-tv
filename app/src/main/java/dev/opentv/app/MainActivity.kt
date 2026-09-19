package dev.opentv.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
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
import dev.opentv.app.data.Channel
import dev.opentv.app.ui.ChannelsScreen
import dev.opentv.app.ui.ErrorScreen
import dev.opentv.app.ui.LoadingScreen
import dev.opentv.app.ui.PlayerScreen
import dev.opentv.app.ui.SetupScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), shape = RectangleShape) {
                    OpenTvApp()
                }
            }
        }
    }
}

/** The list being zapped through and the position within it. */
private data class Playback(val channels: List<Channel>, val position: Int)

@Composable
private fun OpenTvApp(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    var showSetup by rememberSaveable { mutableStateOf(false) }
    var selectedGroup by rememberSaveable { mutableStateOf(ALL_CHANNELS) }
    var playback by remember { mutableStateOf<Playback?>(null) }
    var focusUrl by remember { mutableStateOf(vm.lastChannelUrl) }
    val channelListState = rememberLazyListState()

    when (val s = state) {
        PlaylistState.NotConfigured -> SetupScreen(
            initialUrl = vm.playlistUrl.orEmpty(),
            onSubmit = { url -> vm.loadPlaylist(url); selectedGroup = ALL_CHANNELS },
            onCancel = null,
        )

        else -> if (showSetup) {
            SetupScreen(
                initialUrl = vm.playlistUrl.orEmpty(),
                onSubmit = { url ->
                    vm.loadPlaylist(url)
                    selectedGroup = ALL_CHANNELS
                    showSetup = false
                },
                onCancel = { showSetup = false },
            )
        } else when (s) {
            PlaylistState.Loading -> LoadingScreen()

            is PlaylistState.Failed -> ErrorScreen(
                message = s.message,
                onRetry = vm::retry,
                onChangePlaylist = { showSetup = true },
            )

            is PlaylistState.Ready -> {
                val current = playback
                if (current == null) {
                    ChannelsScreen(
                        state = s,
                        selectedGroup = selectedGroup,
                        onGroupSelected = { selectedGroup = it },
                        listState = channelListState,
                        focusUrl = focusUrl,
                        onPlay = { list, position ->
                            playback = Playback(list, position)
                        },
                        onChangePlaylist = { showSetup = true },
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
                        onExit = { playback = null },
                    )
                }
            }

            PlaylistState.NotConfigured -> Unit // handled above
        }
    }
}
