package dev.onairtv.app

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import dev.onairtv.app.data.Channel
import dev.onairtv.app.data.EpgGuide
import dev.onairtv.app.data.PlaylistRepository
import dev.onairtv.app.data.SavedPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MainViewModelTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun m3u(vararg names: String, group: String = "G") = buildString {
        appendLine("#EXTM3U")
        names.forEach { appendLine("#EXTINF:-1 group-title=\"$group\",$it\nhttp://stream.example/$it.m3u8") }
    }

    /** Like [m3u], but advertising an XMLTV guide the way iptv-org lists do. */
    private fun m3uWithEpg(vararg names: String, epgPath: String = "/g.xml") = buildString {
        appendLine("#EXTM3U url-tvg=\"${url(epgPath)}\"")
        names.forEach {
            appendLine("#EXTINF:-1 tvg-id=\"$it.x\" group-title=\"G\",$it\nhttp://stream.example/$it.m3u8")
        }
    }

    private fun xmltv(channelId: String, title: String) = """
        <tv>
          <channel id="$channelId"><display-name>$channelId</display-name></channel>
          <programme start="$START" stop="$STOP" channel="$channelId"><title>$title</title></programme>
        </tv>
    """.trimIndent()

    /**
     * Answers by path rather than in order: the playlist and the guide are fetched by separate
     * jobs, and [MockWebServer.enqueue] is strictly FIFO.
     */
    private fun serve(vararg responses: Pair<String, MockResponse>) {
        val byPath = responses.toMap()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                byPath[request.path?.substringBefore('?')] ?: MockResponse().setResponseCode(404)
        }
    }

    private fun url(path: String) = server.url(path).toString()

    private fun ok(body: String) = MockResponse().setBody(body)

    private fun error(code: Int = 500) = MockResponse().setResponseCode(code)

    /** Saves [url] as the active playlist, with [cachedBody] (if any) already in its cache. */
    private fun seed(url: String, cachedBody: String? = null, name: String = "Seeded") = runBlocking {
        val repo = PlaylistRepository(app)
        if (cachedBody != null) {
            server.enqueue(ok(cachedBody))
            repo.download(url)
            server.takeRequest()
        }
        repo.playlists = repo.playlists + SavedPlaylist(name, url)
        repo.playlistUrl = url
    }

    /** Waits for every load (and cache delete) the ViewModel has started to finish. */
    private fun MainViewModel.awaitIdle() = runBlocking {
        withTimeout(10_000) {
            while (true) {
                val active = viewModelScope.coroutineContext.job.children.filter { it.isActive }.toList()
                if (active.isEmpty()) break
                active.forEach { it.join() }
            }
        }
    }

    private fun MainViewModel.channelNames(): List<String> =
        (state.value as PlaylistState.Ready).channels.map { it.name }

    @Test
    fun noPlaylistIsNotConfigured() {
        val vm = MainViewModel(app)
        vm.awaitIdle()
        assertEquals(PlaylistState.NotConfigured, vm.state.value)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun cacheAndUnchangedNetworkCopyStaysReady() {
        val url = url("/a.m3u")
        seed(url, m3u("One", "Two"))
        server.enqueue(ok(m3u("One", "Two")).setHeadersDelay(300, TimeUnit.MILLISECONDS))

        val vm = MainViewModel(app)
        // The cached copy is shown before the network answers.
        val first = runBlocking { withTimeout(10_000) { vm.state.first { it is PlaylistState.Ready } } }
        assertTrue(vm.viewModelScope.coroutineContext.job.children.any { it.isActive })
        assertEquals(listOf("One", "Two"), (first as PlaylistState.Ready).channels.map { it.name })
        vm.awaitIdle()

        assertEquals(2, server.requestCount) // seed download + one refresh
        assertEquals(listOf("One", "Two"), vm.channelNames())
    }

    @Test
    fun cacheThenDifferentNetworkCopySwapsInFreshList() {
        val url = url("/a.m3u")
        seed(url, m3u("Old"))
        server.enqueue(ok(m3u("New", "Newer")))

        val vm = MainViewModel(app)
        vm.awaitIdle()

        assertEquals(listOf("New", "Newer"), vm.channelNames())
    }

    @Test
    fun cacheAndNetworkErrorKeepsCachedList() {
        val url = url("/a.m3u")
        seed(url, m3u("Cached"))
        server.enqueue(error())

        val vm = MainViewModel(app)
        vm.awaitIdle()

        assertEquals(listOf("Cached"), vm.channelNames())
    }

    @Test
    fun noCacheAndNetworkErrorFails() {
        seed(url("/a.m3u"))
        server.enqueue(error(503))

        val vm = MainViewModel(app)
        vm.awaitIdle()

        assertEquals(PlaylistState.Failed("Server returned HTTP 503"), vm.state.value)
    }

    @Test
    fun addPlaylistTrimsSavesAndLoads() {
        val vm = MainViewModel(app)
        val url = url("/countries/in.m3u")
        server.enqueue(ok(m3u("One")).setHeadersDelay(200, TimeUnit.MILLISECONDS))

        vm.addPlaylist("  $url  ")
        assertEquals(PlaylistState.Loading, vm.state.value)
        vm.awaitIdle()

        assertEquals(listOf("One"), vm.channelNames())
        val saved = SavedPlaylist("in (${server.hostName})", url)
        assertEquals(listOf(saved), vm.playlists.value)
        assertEquals(url, vm.activeUrl.value)
        val repo = PlaylistRepository(app)
        assertEquals(listOf(saved), repo.playlists)
        assertEquals(url, repo.playlistUrl)
    }

    @Test
    fun addPlaylistUsesGivenNameAndRenamesExistingUrlInPlace() {
        val first = url("/first.m3u")
        val second = url("/second.m3u")
        repeat(3) { server.enqueue(ok(m3u("X"))) }
        val vm = MainViewModel(app)

        vm.addPlaylist(first, name = "  ")
        vm.addPlaylist(second, name = "Second")
        vm.addPlaylist(first, name = "Renamed")
        vm.awaitIdle()

        assertEquals(
            listOf(SavedPlaylist("Renamed", first), SavedPlaylist("Second", second)),
            vm.playlists.value,
        )
        assertEquals(first, vm.activeUrl.value)
    }

    @Test
    fun addPlaylistFailureStillSavesIt() {
        val vm = MainViewModel(app)
        val url = url("/bad.m3u")
        server.enqueue(ok("<html></html>"))

        vm.addPlaylist(url)
        vm.awaitIdle()

        assertEquals(
            PlaylistState.Failed("No channels found. Is this an M3U playlist URL?"),
            vm.state.value,
        )
        assertEquals(listOf(url), vm.playlists.value.map { it.url })
    }

    @Test
    fun retryDownloadsAgainAndRecovers() {
        seed(url("/a.m3u"))
        server.enqueue(error())
        server.enqueue(ok(m3u("Back")))
        val vm = MainViewModel(app)
        vm.awaitIdle()
        assertTrue(vm.state.value is PlaylistState.Failed)

        vm.retry()
        vm.awaitIdle()

        assertEquals(listOf("Back"), vm.channelNames())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun groupsAreVirtualGroupsThenSortedDistinctGroups() {
        val body = "#EXTM3U\n" +
            "#EXTINF:-1 group-title=\"Sports;News\",A\nhttp://s/a\n" +
            "#EXTINF:-1 group-title=\"Movies\",B\nhttp://s/b\n" +
            "#EXTINF:-1 group-title=\"News\",C\nhttp://s/c\n"
        seed(url("/a.m3u"))
        server.enqueue(ok(body))

        val vm = MainViewModel(app)
        vm.awaitIdle()

        assertEquals(
            listOf(ALL_CHANNELS, FAVORITES, "Movies", "News", "Sports"),
            (vm.state.value as PlaylistState.Ready).groups,
        )
    }

    @Test
    fun toggleFavoriteAddsRemovesAndPersists() {
        val vm = MainViewModel(app)
        val a = channel("http://stream.example/a")
        val b = channel("http://stream.example/b")

        vm.toggleFavorite(a)
        vm.toggleFavorite(b)
        assertEquals(setOf(a.url, b.url), vm.favorites.value)
        assertEquals(setOf(a.url, b.url), PlaylistRepository(app).favoriteUrls)

        vm.toggleFavorite(a)
        assertEquals(setOf(b.url), vm.favorites.value)
        assertEquals(setOf(b.url), PlaylistRepository(app).favoriteUrls)
        assertEquals(setOf(b.url), MainViewModel(app).favorites.value)
    }

    @Test
    fun rememberChannelPersistsLastChannel() {
        val vm = MainViewModel(app)
        assertNull(vm.lastChannelUrl)

        vm.rememberChannel(channel("http://stream.example/a"))

        assertEquals("http://stream.example/a", vm.lastChannelUrl)
        assertEquals("http://stream.example/a", PlaylistRepository(app).lastChannelUrl)
    }

    @Test
    fun selectPlaylistSwitchesAndPersists() {
        val a = url("/a.m3u")
        val b = url("/b.m3u")
        seed(b, m3u("B"), name = "B")
        seed(a, m3u("A"), name = "A")
        server.enqueue(ok(m3u("A")))
        server.enqueue(ok(m3u("B")))
        val vm = MainViewModel(app)
        vm.awaitIdle()
        assertEquals(listOf("A"), vm.channelNames())

        vm.selectPlaylist(SavedPlaylist("B", b))
        vm.awaitIdle()

        assertEquals(listOf("B"), vm.channelNames())
        assertEquals(b, vm.activeUrl.value)
        assertEquals(b, PlaylistRepository(app).playlistUrl)
    }

    @Test
    fun removeInactivePlaylistKeepsCurrentOneAndDeletesCache() = runBlocking {
        val a = url("/a.m3u")
        val b = url("/b.m3u")
        seed(b, m3u("B"), name = "B")
        seed(a, m3u("A"), name = "A")
        server.enqueue(ok(m3u("A")))
        val vm = MainViewModel(app)
        vm.awaitIdle()

        vm.removePlaylist(SavedPlaylist("B", b))
        vm.awaitIdle()

        assertEquals(listOf(SavedPlaylist("A", a)), vm.playlists.value)
        assertEquals(a, vm.activeUrl.value)
        assertEquals(listOf("A"), vm.channelNames())
        assertEquals(listOf(SavedPlaylist("A", a)), PlaylistRepository(app).playlists)
        assertNull(PlaylistRepository(app).loadCached(b))
    }

    @Test
    fun removeActivePlaylistSwitchesToFirstRemaining() {
        val a = url("/a.m3u")
        val b = url("/b.m3u")
        val c = url("/c.m3u")
        seed(b, m3u("B"), name = "B")
        seed(c, m3u("C"), name = "C")
        seed(a, m3u("A"), name = "A")
        server.enqueue(ok(m3u("A")))
        server.enqueue(ok(m3u("B")))
        val vm = MainViewModel(app)
        vm.awaitIdle()

        vm.removePlaylist(SavedPlaylist("A", a))
        vm.awaitIdle()

        assertEquals(listOf(b, c), vm.playlists.value.map { it.url })
        assertEquals(b, vm.activeUrl.value)
        assertEquals(listOf("B"), vm.channelNames())
    }

    @Test
    fun removeLastPlaylistIsNotConfigured() {
        val a = url("/a.m3u")
        seed(a, m3u("A"), name = "A")
        server.enqueue(ok(m3u("A")))
        val vm = MainViewModel(app)
        vm.awaitIdle()

        vm.removePlaylist(SavedPlaylist("A", a))
        vm.awaitIdle()

        assertEquals(PlaylistState.NotConfigured, vm.state.value)
        assertEquals(emptyList<SavedPlaylist>(), vm.playlists.value)
        assertNull(vm.activeUrl.value)
        assertNull(PlaylistRepository(app).playlistUrl)
    }

    // ---- EPG ------------------------------------------------------------------------------

    @Test
    fun aPlaylistAdvertisingAGuideLoadsIt() {
        seed(url("/a.m3u"))
        serve(
            "/a.m3u" to ok(m3uWithEpg("One")),
            "/g.xml" to ok(xmltv("One.x", "The Show")),
        )

        val vm = MainViewModel(app)
        vm.awaitIdle()

        assertEquals(listOf("One"), vm.channelNames())
        val channel = (vm.state.value as PlaylistState.Ready).channels.single()
        assertEquals("The Show", vm.guide.value.nowNext(channel, NOW)?.now?.title)
    }

    @Test
    fun aPlaylistWithoutAGuideMakesNoEpgRequest() {
        seed(url("/a.m3u"))
        server.enqueue(ok(m3u("One")))

        val vm = MainViewModel(app)
        vm.awaitIdle()

        assertEquals(1, server.requestCount) // the playlist, and nothing else
        assertEquals(EpgGuide.EMPTY, vm.guide.value)
    }

    @Test
    fun aFailingGuideLeavesThePlaylistReady() {
        seed(url("/a.m3u"))
        serve(
            "/a.m3u" to ok(m3uWithEpg("One")),
            "/g.xml" to error(),
        )

        val vm = MainViewModel(app)
        vm.awaitIdle()

        assertEquals(listOf("One"), vm.channelNames())
        assertTrue(vm.guide.value.isEmpty)
    }

    @Test
    fun switchingPlaylistsDropsThePreviousGuide() {
        val a = url("/a.m3u")
        val b = url("/b.m3u")
        seed(b, name = "B")
        seed(a, name = "A")
        serve(
            "/a.m3u" to ok(m3uWithEpg("One")),
            "/b.m3u" to ok(m3u("Two")), // no guide of its own
            "/g.xml" to ok(xmltv("One.x", "The Show")),
        )
        val vm = MainViewModel(app)
        vm.awaitIdle()
        assertNotNull(vm.guide.value.nowNext(channel("http://x", tvgId = "One.x"), NOW))

        vm.selectPlaylist(SavedPlaylist("B", b))
        vm.awaitIdle()

        assertEquals(listOf("Two"), vm.channelNames())
        assertEquals(EpgGuide.EMPTY, vm.guide.value)
    }

    @Test
    fun aSavedEpgUrlBeatsTheOneInTheHeader() = runBlocking {
        val url = url("/a.m3u")
        PlaylistRepository(app).apply {
            playlists = listOf(SavedPlaylist("A", url, epgUrl = url("/override.xml")))
            playlistUrl = url
        }
        serve(
            "/a.m3u" to ok(m3uWithEpg("One")),
            "/g.xml" to ok(xmltv("One.x", "From The Header")),
            "/override.xml" to ok(xmltv("One.x", "From The Override")),
        )

        val vm = MainViewModel(app)
        vm.awaitIdle()

        val channel = (vm.state.value as PlaylistState.Ready).channels.single()
        assertEquals("From The Override", vm.guide.value.nowNext(channel, NOW)?.now?.title)
    }

    private fun channel(url: String, tvgId: String? = null) = Channel(
        index = 0,
        name = url,
        url = url,
        logo = null,
        tvgId = tvgId,
        groups = listOf("G"),
        userAgent = null,
        referrer = null,
    )

    private companion object {
        /** A programme running from an hour ago until an hour from now, wherever the test runs. */
        val NOW = System.currentTimeMillis()
        val START = xmltvTime(NOW - 3_600_000)
        val STOP = xmltvTime(NOW + 3_600_000)

        fun xmltvTime(millis: Long): String =
            java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.US).format(java.util.Date(millis))
    }
}
