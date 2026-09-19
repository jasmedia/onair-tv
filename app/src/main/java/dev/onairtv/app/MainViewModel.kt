package dev.onairtv.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.onairtv.app.data.Channel
import dev.onairtv.app.data.PlaylistRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

const val ALL_CHANNELS = "All channels"
const val FAVORITES = "★ Favorites"

sealed interface PlaylistState {
    /** No playlist configured yet: show the setup screen. */
    data object NotConfigured : PlaylistState
    data object Loading : PlaylistState
    data class Failed(val message: String) : PlaylistState
    data class Ready(val channels: List<Channel>, val groups: List<String>) : PlaylistState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = PlaylistRepository(app)

    private val _state = MutableStateFlow<PlaylistState>(PlaylistState.Loading)
    val state: StateFlow<PlaylistState> = _state.asStateFlow()

    private val _favorites = MutableStateFlow(repo.favoriteUrls)
    /** Stream URLs of favorite channels. */
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    val playlistUrl: String? get() = repo.playlistUrl
    val lastChannelUrl: String? get() = repo.lastChannelUrl

    init {
        val url = repo.playlistUrl
        if (url == null) {
            _state.value = PlaylistState.NotConfigured
        } else {
            viewModelScope.launch {
                // Show the cached copy instantly, then refresh in the background.
                val cached = runCatching { repo.loadCached() }.getOrNull()
                if (!cached.isNullOrEmpty()) _state.value = ready(cached)

                try {
                    val fresh = repo.download(url)
                    if (fresh != cached) _state.value = ready(fresh)
                } catch (e: Exception) {
                    if (cached.isNullOrEmpty()) _state.value = PlaylistState.Failed(e.readable())
                }
            }
        }
    }

    fun loadPlaylist(url: String) {
        val trimmed = url.trim()
        repo.playlistUrl = trimmed
        _state.value = PlaylistState.Loading
        viewModelScope.launch {
            _state.value = try {
                ready(repo.download(trimmed))
            } catch (e: Exception) {
                PlaylistState.Failed(e.readable())
            }
        }
    }

    fun retry() {
        repo.playlistUrl?.let(::loadPlaylist)
    }

    fun rememberChannel(channel: Channel) {
        repo.lastChannelUrl = channel.url
    }

    fun toggleFavorite(channel: Channel) {
        val current = _favorites.value
        val updated = if (channel.url in current) current - channel.url else current + channel.url
        repo.favoriteUrls = updated
        _favorites.value = updated
    }

    private fun ready(channels: List<Channel>): PlaylistState.Ready {
        val groups = channels.asSequence().flatMap { it.groups }.distinct().sorted().toList()
        return PlaylistState.Ready(channels, listOf(ALL_CHANNELS, FAVORITES) + groups)
    }

    private fun Exception.readable(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}
