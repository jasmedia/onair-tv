package dev.onairtv.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import okio.Buffer
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPOutputStream

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

    private fun m3u(vararg names: String, epgUrl: String? = null) = buildString {
        appendLine("#EXTM3U" + (epgUrl?.let { " url-tvg=\"$it\"" } ?: ""))
        names.forEach {
            appendLine("#EXTINF:-1 tvg-id=\"$it.x\" group-title=\"G\",$it\nhttp://stream.example/$it.m3u8")
        }
    }

    /** 2026-09-20T12:00:00Z, the instant [xmltv] is written around. */
    private val now = 1_789_905_600_000L

    private fun xmltv(channelId: String, title: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "xmltv.dtd">
        <tv>
          <channel id="$channelId"><display-name>$channelId</display-name></channel>
          <programme start="20260920113000 +0000" stop="20260920123000 +0000" channel="$channelId">
            <title>$title</title>
          </programme>
        </tv>
    """.trimIndent()

    private fun gzipped(text: String): Buffer = Buffer().write(
        ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        }.toByteArray(),
    )

    private val oneChannel = M3uParser.parse(m3u("One"))

    private fun nowTitle(guide: EpgGuide?) = guide?.nowNext(oneChannel.single(), now)?.now?.title

    private fun epgCacheFiles() = File(context.filesDir, "epg").listFiles().orEmpty().toList()

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

    // ---- EPG ------------------------------------------------------------------------------

    @Test
    fun detectedEpgUrlComesFromTheCachedHeader() = runTest {
        val repo = PlaylistRepository(context)
        val url = url("/a.m3u")
        assertNull(repo.detectedEpgUrl(url)) // nothing cached yet

        server.enqueue(MockResponse().setBody(m3u("One", epgUrl = "http://guide.example/g.xml")))
        repo.download(url)

        assertEquals("http://guide.example/g.xml", repo.detectedEpgUrl(url))
        assertEquals("http://guide.example/g.xml", PlaylistRepository(context).detectedEpgUrl(url))
    }

    @Test
    fun detectedEpgUrlIsNullForAPlaylistWithoutAHeader() = runTest {
        val repo = PlaylistRepository(context)
        server.enqueue(MockResponse().setBody(m3u("One")))
        repo.download(url("/a.m3u"))

        assertNull(repo.detectedEpgUrl(url("/a.m3u")))
    }

    @Test
    fun epgGuideDownloadsCachesAndParses() = runTest {
        val repo = PlaylistRepository(context)
        server.enqueue(MockResponse().setBody(xmltv("One.x", "The Show")))

        val guide = repo.epgGuide(url("/g.xml"), oneChannel, now)

        assertEquals("The Show", nowTitle(guide))
        assertEquals(DEFAULT_USER_AGENT, server.takeRequest().getHeader("User-Agent"))
        assertEquals(1, epgCacheFiles().size)
    }

    @Test
    fun epgGuideParsesAGzippedBody() = runTest {
        val repo = PlaylistRepository(context)
        server.enqueue(MockResponse().setBody(gzipped(xmltv("One.x", "Gzipped Show"))))

        assertEquals("Gzipped Show", nowTitle(repo.epgGuide(url("/g.xml.gz"), oneChannel, now)))
        // Stored as it arrived, so it re-reads from the cache without a second request.
        assertEquals("Gzipped Show", nowTitle(repo.epgGuide(url("/g.xml.gz"), oneChannel, now)))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aFreshCacheIsNotRefetchedButAStaleOneIs() = runTest {
        val repo = PlaylistRepository(context)
        val epgUrl = url("/g.xml")
        server.enqueue(MockResponse().setBody(xmltv("One.x", "First")))
        server.enqueue(MockResponse().setBody(xmltv("One.x", "Second")))

        assertEquals("First", nowTitle(repo.epgGuide(epgUrl, oneChannel, now)))
        assertEquals("First", nowTitle(repo.epgGuide(epgUrl, oneChannel, now)))
        assertEquals(1, server.requestCount)

        val cached = epgCacheFiles().single()
        cached.setLastModified(System.currentTimeMillis() - EPG_MAX_AGE_MILLIS - 1)

        assertEquals("Second", nowTitle(repo.epgGuide(epgUrl, oneChannel, now)))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aFailedRefreshKeepsUsingTheStaleGuide() = runTest {
        val repo = PlaylistRepository(context)
        val epgUrl = url("/g.xml")
        server.enqueue(MockResponse().setBody(xmltv("One.x", "Stale But Good")))
        server.enqueue(MockResponse().setResponseCode(500))

        repo.epgGuide(epgUrl, oneChannel, now)
        epgCacheFiles().single()
            .setLastModified(System.currentTimeMillis() - EPG_MAX_AGE_MILLIS - 1)

        assertEquals("Stale But Good", nowTitle(repo.epgGuide(epgUrl, oneChannel, now)))
        assertEquals(1, epgCacheFiles().size) // and the good copy is still there
    }

    @Test
    fun anEpgFailureWithNoCacheReturnsNullRatherThanThrowing() = runTest {
        val repo = PlaylistRepository(context)
        server.enqueue(MockResponse().setResponseCode(500))

        assertNull(repo.epgGuide(url("/g.xml"), oneChannel, now))
        assertEquals(emptyList<File>(), epgCacheFiles())
    }

    @Test
    fun aCorruptCachedGuideIsDiscarded() = runTest {
        val repo = PlaylistRepository(context)
        val epgUrl = url("/g.xml")
        server.enqueue(MockResponse().setBody(xmltv("One.x", "Good")))
        repo.epgGuide(epgUrl, oneChannel, now)

        epgCacheFiles().single().writeText("<tv><programme>truncated")

        assertNull(repo.epgGuide(epgUrl, oneChannel, now))
        assertEquals(emptyList<File>(), epgCacheFiles())
    }

    @Test
    fun pruneRemovesOnlyLongUnusedGuides() = runTest {
        val repo = PlaylistRepository(context)
        server.enqueue(MockResponse().setBody(xmltv("One.x", "Keep")))
        server.enqueue(MockResponse().setBody(xmltv("One.x", "Drop")))
        repo.epgGuide(url("/keep.xml"), oneChannel, now)
        repo.epgGuide(url("/drop.xml"), oneChannel, now)
        assertEquals(2, epgCacheFiles().size)

        val doomed = File(context.filesDir, "epg/${epgCacheFiles().map { it.name }.last()}")
        doomed.setLastModified(
            System.currentTimeMillis() - EPG_PRUNE_AGE_MILLIS - 1,
        )
        repo.pruneEpgCache()

        assertEquals(1, epgCacheFiles().size)
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
