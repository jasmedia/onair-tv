package dev.onairtv.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

const val DEFAULT_USER_AGENT = "OnAirTV/0.1 (Android TV)"

/** Downloads playlists, keeps a cached copy of each on disk, and stores simple settings. */
class PlaylistRepository(context: Context) {

    private val prefs = context.getSharedPreferences("onairtv", Context.MODE_PRIVATE)
    private val cacheDir = File(context.filesDir, "playlists")

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    init {
        migrateSinglePlaylist(File(context.filesDir, "playlist.m3u"))
    }

    /** URL of the playlist currently shown. */
    var playlistUrl: String?
        get() = prefs.getString(KEY_URL, null)
        set(value) = prefs.edit().putString(KEY_URL, value).apply()

    /** All saved playlists, in the order they were added. */
    var playlists: List<SavedPlaylist>
        get() = SavedPlaylists.decode(prefs.getString(KEY_PLAYLISTS, null))
        set(value) = prefs.edit().putString(KEY_PLAYLISTS, SavedPlaylists.encode(value)).apply()

    /** Stream URL of the last channel watched, so the list can reopen on it. */
    var lastChannelUrl: String?
        get() = prefs.getString(KEY_LAST_CHANNEL, null)
        set(value) = prefs.edit().putString(KEY_LAST_CHANNEL, value).apply()

    /** Stream URLs of favorite channels. Keyed by URL so favorites survive playlist refreshes. */
    var favoriteUrls: Set<String>
        // Copy: the set returned by getStringSet must not be modified or kept.
        get() = prefs.getStringSet(KEY_FAVORITES, null)?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_FAVORITES, value).apply()

    suspend fun loadCached(url: String): List<Channel>? = withContext(Dispatchers.IO) {
        val file = cacheFile(url)
        if (file.exists()) M3uParser.parse(file.readText()) else null
    }

    suspend fun download(url: String): List<Channel> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DEFAULT_USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Server returned HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("Empty response")
            val channels = M3uParser.parse(body)
            if (channels.isEmpty()) {
                throw IOException("No channels found. Is this an M3U playlist URL?")
            }
            ensureActive() // don't re-create the cache of a playlist removed mid-download
            cacheDir.mkdirs()
            cacheFile(url).writeText(body)
            channels
        }
    }

    suspend fun deleteCache(url: String) = withContext(Dispatchers.IO) {
        cacheFile(url).delete()
    }

    private fun cacheFile(url: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(cacheDir, "$hash.m3u")
    }

    /** Older versions stored a single playlist URL and `filesDir/playlist.m3u`. */
    private fun migrateSinglePlaylist(legacyCache: File) {
        val url = playlistUrl
        if (prefs.contains(KEY_PLAYLISTS) || url == null) return
        playlists = listOf(SavedPlaylist(SavedPlaylists.defaultName(url), url))
        if (legacyCache.exists()) {
            cacheDir.mkdirs()
            legacyCache.renameTo(cacheFile(url))
        }
    }

    private companion object {
        const val KEY_URL = "playlist_url"
        const val KEY_PLAYLISTS = "playlists"
        const val KEY_LAST_CHANNEL = "last_channel_url"
        const val KEY_FAVORITES = "favorite_urls"
    }
}
