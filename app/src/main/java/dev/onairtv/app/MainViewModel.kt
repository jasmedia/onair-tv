package dev.onairtv.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.onairtv.app.data.Channel
import dev.onairtv.app.data.PlaylistRepository
import dev.onairtv.app.data.SavedPlaylist
import dev.onairtv.app.data.SavedPlaylists
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

    private val _playlists = MutableStateFlow(repo.playlists)
    /** Saved playlists, in the order they were added. */
    val playlists: StateFlow<List<SavedPlaylist>> = _playlists.asStateFlow()

    private val _activeUrl = MutableStateFlow(repo.playlistUrl)
    /** URL of the playlist being shown. */
    val activeUrl: StateFlow<String?> = _activeUrl.asStateFlow()

    val lastChannelUrl: String? get() = repo.lastChannelUrl

    private var loadJob: Job? = null

    init {
        val url = repo.playlistUrl
        if (url == null) _state.value = PlaylistState.NotConfigured else open(url)
    }

    /** Saves the playlist (or renames it, if the URL is already saved) and switches to it. */
    fun addPlaylist(url: String, name: String? = null) {
        val trimmed = url.trim()
        val displayName = name?.takeIf { it.isNotBlank() } ?: SavedPlaylists.defaultName(trimmed)
        val playlist = SavedPlaylist(displayName, trimmed)
        updatePlaylists(SavedPlaylists.upsert(_playlists.value, playlist))
        open(trimmed)
    }

    fun selectPlaylist(playlist: SavedPlaylist) {
        open(playlist.url)
    }

    /** Forgets a playlist. Removing the one being shown switches to the first remaining one. */
    fun removePlaylist(playlist: SavedPlaylist) {
        val remaining = _playlists.value.filter { it.url != playlist.url }
        updatePlaylists(remaining)
        viewModelScope.launch { runCatching { repo.deleteCache(playlist.url) } }
        if (playlist.url == _activeUrl.value) {
            val next = remaining.firstOrNull()
            if (next != null) {
                open(next.url)
            } else {
                loadJob?.cancel()
                setActive(null)
                _state.value = PlaylistState.NotConfigured
            }
        }
    }

    fun retry() {
        repo.playlistUrl?.let(::open)
    }

    /** Shows the cached copy of the playlist instantly (if any), then refreshes it from the network. */
    private fun open(url: String) {
        setActive(url)
        loadJob?.cancel()
        _state.value = PlaylistState.Loading
        loadJob = viewModelScope.launch {
            val cached = runCatching { repo.loadCached(url) }.getOrNull()
            if (!cached.isNullOrEmpty()) _state.value = ready(cached)

            try {
                val fresh = repo.download(url)
                if (fresh != cached) _state.value = ready(fresh)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (cached.isNullOrEmpty()) _state.value = PlaylistState.Failed(e.readable())
            }
        }
    }

    private fun setActive(url: String?) {
        repo.playlistUrl = url
        _activeUrl.value = url
    }

    private fun updatePlaylists(playlists: List<SavedPlaylist>) {
        repo.playlists = playlists
        _playlists.value = playlists
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
