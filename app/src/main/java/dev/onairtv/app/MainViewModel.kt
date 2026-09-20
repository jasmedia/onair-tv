package dev.onairtv.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.onairtv.app.data.Channel
import dev.onairtv.app.data.EpgGuide
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

    private val _guide = MutableStateFlow(EpgGuide.EMPTY)
    /**
     * Programmes for the channels of the playlist being shown. Deliberately a second flow rather
     * than a field on [PlaylistState.Ready]: a guide that fails to load cannot then break, delay
     * or re-render the playlist.
     */
    val guide: StateFlow<EpgGuide> = _guide.asStateFlow()

    private val _activeUrl = MutableStateFlow(repo.playlistUrl)
    /** URL of the playlist being shown. */
    val activeUrl: StateFlow<String?> = _activeUrl.asStateFlow()

    val lastChannelUrl: String? get() = repo.lastChannelUrl

    private var loadJob: Job? = null
    private var epgJob: Job? = null

    init {
        val url = repo.playlistUrl
        if (url == null) _state.value = PlaylistState.NotConfigured else open(url)
    }

    /**
     * Saves the playlist (or renames it, if the URL is already saved) and switches to it.
     *
     * [epgUrl] *replaces* whatever guide URL was stored for this playlist, so passing nothing
     * clears an override the user had set.
     */
    fun addPlaylist(url: String, name: String? = null, epgUrl: String? = null) {
        val trimmed = url.trim()
        val displayName = name?.takeIf { it.isNotBlank() } ?: SavedPlaylists.defaultName(trimmed)
        val playlist = SavedPlaylist(displayName, trimmed, epgUrl?.trim()?.ifEmpty { null })
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
                clearEpg()
                setActive(null)
                _state.value = PlaylistState.NotConfigured
            }
        }
    }

    fun retry() {
        repo.playlistUrl?.let(::open)
    }

    /** Reloads the guide for the playlist being shown, e.g. once its programmes have run out. */
    fun refreshEpg() {
        val url = _activeUrl.value ?: return
        val ready = _state.value as? PlaylistState.Ready ?: return
        startEpg(url, ready.channels)
    }

    /** Shows the cached copy of the playlist instantly (if any), then refreshes it from the network. */
    private fun open(url: String) {
        setActive(url)
        loadJob?.cancel()
        clearEpg() // never show the previous playlist's programmes against this one's channels
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

            // Whether the refresh succeeded, failed, or only the cache was there.
            (_state.value as? PlaylistState.Ready)?.let { startEpg(url, it.channels) }
        }
    }

    /**
     * Loads the guide for [playlistUrl] in the background. Launched straight into viewModelScope
     * rather than under loadJob, so the next [open] cancels it explicitly instead of inheriting it.
     */
    private fun startEpg(playlistUrl: String, channels: List<Channel>) {
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            try {
                val saved = _playlists.value.firstOrNull { it.url == playlistUrl }
                // A URL the user typed beats the one the playlist advertises.
                val epgUrl = saved?.epgUrl ?: repo.detectedEpgUrl(playlistUrl) ?: return@launch
                repo.epgGuide(epgUrl, channels)?.let { _guide.value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No guide just means the rows keep showing their groups.
            }
        }
    }

    private fun clearEpg() {
        epgJob?.cancel()
        epgJob = null
        _guide.value = EpgGuide.EMPTY
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
