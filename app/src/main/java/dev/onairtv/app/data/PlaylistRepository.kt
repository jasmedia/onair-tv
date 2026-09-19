package dev.onairtv.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

const val DEFAULT_USER_AGENT = "OnAirTV/0.1 (Android TV)"

/** Downloads the playlist, keeps a cached copy on disk, and stores simple settings. */
class PlaylistRepository(context: Context) {

    private val prefs = context.getSharedPreferences("onairtv", Context.MODE_PRIVATE)
    private val cacheFile = File(context.filesDir, "playlist.m3u")

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    var playlistUrl: String?
        get() = prefs.getString(KEY_URL, null)
        set(value) = prefs.edit().putString(KEY_URL, value).apply()

    /** Stream URL of the last channel watched, so the list can reopen on it. */
    var lastChannelUrl: String?
        get() = prefs.getString(KEY_LAST_CHANNEL, null)
        set(value) = prefs.edit().putString(KEY_LAST_CHANNEL, value).apply()

    suspend fun loadCached(): List<Channel>? = withContext(Dispatchers.IO) {
        if (cacheFile.exists()) M3uParser.parse(cacheFile.readText()) else null
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
            cacheFile.writeText(body)
            channels
        }
    }

    private companion object {
        const val KEY_URL = "playlist_url"
        const val KEY_LAST_CHANNEL = "last_channel_url"
    }
}
