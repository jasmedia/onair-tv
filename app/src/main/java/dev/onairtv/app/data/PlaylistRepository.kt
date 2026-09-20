package dev.onairtv.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

const val DEFAULT_USER_AGENT = "OnAirTV/0.1 (Android TV)"

/** How long a downloaded XMLTV guide is used before being refreshed. */
internal const val EPG_MAX_AGE_MILLIS = 6L * 60 * 60 * 1000

/** How long a guide nothing has asked for is kept on disk. */
internal const val EPG_PRUNE_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000

/** Downloads playlists, keeps a cached copy of each on disk, and stores simple settings. */
class PlaylistRepository(context: Context) {

    private val prefs = context.getSharedPreferences("onairtv", Context.MODE_PRIVATE)
    private val cacheDir = File(context.filesDir, "playlists")
    private val epgDir = File(context.filesDir, "epg")

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

    /**
     * The XMLTV guide URL the cached copy of [playlistUrl] advertises, if any.
     *
     * Only the header is read, not the whole (often several MB) playlist. [download] writes the
     * cache before it returns, so this answers on both the cached and the freshly-downloaded path.
     */
    suspend fun detectedEpgUrl(playlistUrl: String): String? = withContext(Dispatchers.IO) {
        val file = cacheFile(playlistUrl)
        if (!file.exists()) return@withContext null
        runCatching { file.useLines { M3uParser.tvgUrl(it.take(20).joinToString("\n")) } }.getOrNull()
    }

    /**
     * The guide at [epgUrl], for [channels] only. Refreshes the on-disk copy when it's older than
     * [EPG_MAX_AGE_MILLIS]; a failed refresh falls back to the stale copy.
     *
     * Returns null instead of throwing: a missing guide must never break playback.
     */
    suspend fun epgGuide(
        epgUrl: String,
        channels: List<Channel>,
        now: Long = System.currentTimeMillis(),
    ): EpgGuide? = withContext(Dispatchers.IO) {
        pruneEpgCache()
        val file = epgFile(epgUrl)
        if (!file.exists() || now - file.lastModified() > EPG_MAX_AGE_MILLIS) {
            runCatching { downloadEpg(epgUrl, file) }
        }
        if (!file.exists()) return@withContext null
        runCatching {
            FileInputStream(file).use { XmltvParser.parse(maybeGunzip(it).buffered(), channels, now) }
        }.onFailure { file.delete() }.getOrNull() // a corrupt cache is worse than none
    }

    /** Deletes guides nothing has asked for in a week. */
    suspend fun pruneEpgCache() = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - EPG_PRUNE_AGE_MILLIS
        epgDir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        Unit
    }

    /**
     * Streams the guide to disk. Unlike [download] it never holds the body in memory -- guides run
     * to 100 MB -- and it writes through a temporary file, so a truncated download cannot destroy
     * a good cached copy. The bytes are stored exactly as they arrived, still gzipped if that's
     * how they came: roughly 10x smaller, and [maybeGunzip] makes the read path identical.
     */
    private suspend fun downloadEpg(epgUrl: String, target: File) {
        val request = Request.Builder()
            .url(epgUrl)
            .header("User-Agent", DEFAULT_USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Server returned HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty response")
            epgDir.mkdirs()
            val temp = File(target.path + ".tmp")
            try {
                temp.outputStream().use { out -> body.byteStream().copyTo(out) }
                // Don't install a guide for a playlist that was switched away mid-download.
                currentCoroutineContext().ensureActive()
                if (!temp.renameTo(target)) throw IOException("Could not replace the cached guide")
            } finally {
                temp.delete()
            }
        }
    }

    private fun cacheFile(url: String) = File(cacheDir, "${hash(url)}.m3u")

    private fun epgFile(epgUrl: String) = File(epgDir, "${hash(epgUrl)}.xml")

    private fun hash(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }

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
