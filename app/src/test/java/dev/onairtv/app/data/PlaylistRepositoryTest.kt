package dev.onairtv.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class PlaylistRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("onairtv", Context.MODE_PRIVATE)
    private val legacyCache get() = File(context.filesDir, "playlist.m3u")
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun m3u(vararg names: String) = buildString {
        appendLine("#EXTM3U")
        names.forEach { appendLine("#EXTINF:-1 group-title=\"G\",$it\nhttp://stream.example/$it.m3u8") }
    }

    private fun url(path: String) = server.url(path).toString()

    private suspend fun assertDownloadFails(repo: PlaylistRepository, url: String, message: String) {
        try {
            repo.download(url)
            fail("Expected download to fail")
        } catch (e: IOException) {
            assertEquals(message, e.message)
        }
    }

    @Test
    fun downloadParsesSendsUserAgentAndCaches() = runTest {
        val repo = PlaylistRepository(context)
        val url = url("/a.m3u")
        server.enqueue(MockResponse().setBody(m3u("One", "Two")))

        val channels = repo.download(url)

        assertEquals(listOf("One", "Two"), channels.map { it.name })
        assertEquals(DEFAULT_USER_AGENT, server.takeRequest().getHeader("User-Agent"))
        assertEquals(channels, repo.loadCached(url))
        assertEquals(channels, PlaylistRepository(context).loadCached(url))
    }

    @Test
    fun loadCachedUnknownUrlIsNull() = runTest {
        assertNull(PlaylistRepository(context).loadCached(url("/never.m3u")))
    }

    @Test
    fun eachUrlHasItsOwnCache() = runTest {
        val repo = PlaylistRepository(context)
        server.enqueue(MockResponse().setBody(m3u("A")))
        server.enqueue(MockResponse().setBody(m3u("B")))
        repo.download(url("/a.m3u"))
        repo.download(url("/b.m3u"))

        assertEquals(listOf("A"), repo.loadCached(url("/a.m3u"))?.map { it.name })
        assertEquals(listOf("B"), repo.loadCached(url("/b.m3u"))?.map { it.name })
    }

    @Test
    fun httpErrorThrowsAndDoesNotCache() = runTest {
        val repo = PlaylistRepository(context)
        server.enqueue(MockResponse().setResponseCode(404))

        assertDownloadFails(repo, url("/a.m3u"), "Server returned HTTP 404")
        assertNull(repo.loadCached(url("/a.m3u")))
    }

    @Test
    fun nonM3uBodyThrowsAndDoesNotCache() = runTest {
        val repo = PlaylistRepository(context)
        server.enqueue(MockResponse().setBody("<html>Not a playlist</html>"))

        assertDownloadFails(repo, url("/a.m3u"), "No channels found. Is this an M3U playlist URL?")
        assertNull(repo.loadCached(url("/a.m3u")))
    }

    @Test
    fun failuresKeepTheLastGoodCache() = runTest {
        val repo = PlaylistRepository(context)
        val url = url("/a.m3u")
        server.enqueue(MockResponse().setBody(m3u("Good")))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody("garbage"))

        val good = repo.download(url)
        assertDownloadFails(repo, url, "Server returned HTTP 500")
        assertDownloadFails(repo, url, "No channels found. Is this an M3U playlist URL?")

        assertEquals(good, repo.loadCached(url))
    }

    @Test
    fun deleteCacheRemovesIt() = runTest {
        val repo = PlaylistRepository(context)
        server.enqueue(MockResponse().setBody(m3u("A")))
        server.enqueue(MockResponse().setBody(m3u("B")))
        repo.download(url("/a.m3u"))
        repo.download(url("/b.m3u"))

        repo.deleteCache(url("/a.m3u"))

        assertNull(repo.loadCached(url("/a.m3u")))
        assertEquals(listOf("B"), repo.loadCached(url("/b.m3u"))?.map { it.name })
    }

    @Test
    fun settingsDefaults() {
        val repo = PlaylistRepository(context)
        assertNull(repo.playlistUrl)
        assertNull(repo.lastChannelUrl)
        assertEquals(emptySet<String>(), repo.favoriteUrls)
        assertEquals(emptyList<SavedPlaylist>(), repo.playlists)
    }

    @Test
    fun settingsSurviveANewRepository() {
        val playlists = listOf(
            SavedPlaylist("B", "http://example.com/b.m3u"),
            SavedPlaylist("A", "http://example.com/a.m3u"),
        )
        PlaylistRepository(context).apply {
            this.playlists = playlists
            playlistUrl = "http://example.com/a.m3u"
            lastChannelUrl = "http://stream.example/1"
            favoriteUrls = setOf("http://stream.example/1", "http://stream.example/2")
        }

        val reopened = PlaylistRepository(context)
        assertEquals(playlists, reopened.playlists)
        assertEquals("http://example.com/a.m3u", reopened.playlistUrl)
        assertEquals("http://stream.example/1", reopened.lastChannelUrl)
        assertEquals(setOf("http://stream.example/1", "http://stream.example/2"), reopened.favoriteUrls)

        reopened.playlistUrl = null
        reopened.favoriteUrls = emptySet()
        assertNull(PlaylistRepository(context).playlistUrl)
        assertEquals(emptySet<String>(), PlaylistRepository(context).favoriteUrls)
    }

    @Test
    fun migratesLegacySinglePlaylist() = runTest {
        val url = "https://iptv-org.github.io/iptv/countries/in.m3u"
        prefs.edit().putString("playlist_url", url).commit()
        legacyCache.writeText(m3u("Legacy"))

        val repo = PlaylistRepository(context)

        assertEquals(listOf(SavedPlaylist("in (iptv-org.github.io)", url)), repo.playlists)
        assertEquals(url, repo.playlistUrl)
        assertEquals(listOf("Legacy"), repo.loadCached(url)?.map { it.name })
        assertFalse(legacyCache.exists())
    }

    @Test
    fun migratesLegacyUrlWithoutCacheFile() = runTest {
        val url = "http://example.com/list.m3u"
        prefs.edit().putString("playlist_url", url).commit()

        val repo = PlaylistRepository(context)

        assertEquals(listOf(SavedPlaylist("list (example.com)", url)), repo.playlists)
        assertNull(repo.loadCached(url))
    }

    @Test
    fun noMigrationOncePlaylistsAreSaved() = runTest {
        val existing = listOf(SavedPlaylist("Other", "http://example.com/other.m3u"))
        PlaylistRepository(context).playlists = existing
        prefs.edit().putString("playlist_url", "http://example.com/list.m3u").commit()
        legacyCache.writeText(m3u("Legacy"))

        val repo = PlaylistRepository(context)

        assertEquals(existing, repo.playlists)
        assertTrue(legacyCache.exists())
        assertNull(repo.loadCached("http://example.com/list.m3u"))
    }

    @Test
    fun noMigrationWithoutLegacyUrl() {
        legacyCache.writeText(m3u("Legacy"))

        val repo = PlaylistRepository(context)

        assertEquals(emptyList<SavedPlaylist>(), repo.playlists)
        assertFalse(prefs.contains("playlists"))
        assertTrue(legacyCache.exists())
    }
}
