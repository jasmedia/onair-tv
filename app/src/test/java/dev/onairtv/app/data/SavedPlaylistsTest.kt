package dev.onairtv.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedPlaylistsTest {

    private val india = SavedPlaylist("India", "https://iptv-org.github.io/iptv/countries/in.m3u")
    private val news = SavedPlaylist("News", "http://example.com/news.m3u8?token=a|b")

    @Test
    fun encodeDecodeRoundTripKeepsOrder() {
        val list = listOf(news, india)
        assertEquals(list, SavedPlaylists.decode(SavedPlaylists.encode(list)))
    }

    @Test
    fun namesWithTabsOrNewlinesDoNotBreakTheFormat() {
        val odd = SavedPlaylist("My\tlist\nhere", "http://example.com/a.m3u")
        assertEquals(
            listOf(SavedPlaylist("My list here", odd.url)),
            SavedPlaylists.decode(SavedPlaylists.encode(listOf(odd))),
        )
    }

    @Test
    fun decodeSkipsMalformedLinesAndDuplicates() {
        val text = "garbage\nIndia\t${india.url}\nEmpty\t \nAgain\t${india.url}\n"
        assertEquals(listOf(india), SavedPlaylists.decode(text))
        assertEquals(emptyList<SavedPlaylist>(), SavedPlaylists.decode(null))
        assertEquals(emptyList<SavedPlaylist>(), SavedPlaylists.decode(""))
    }

    @Test
    fun upsertReplacesInPlaceOrAppends() {
        val renamed = india.copy(name = "Bharat")
        assertEquals(listOf(renamed, news), SavedPlaylists.upsert(listOf(india, news), renamed))
        assertEquals(listOf(india, news), SavedPlaylists.upsert(listOf(india), news))
    }

    @Test
    fun defaultNameUsesFileAndHost() {
        assertEquals("in (iptv-org.github.io)", SavedPlaylists.defaultName(india.url))
        assertEquals("news (example.com)", SavedPlaylists.defaultName(news.url))
        assertEquals("example.com", SavedPlaylists.defaultName("https://www.example.com/"))
        assertEquals("not a url", SavedPlaylists.defaultName("not a url"))
    }

    @Test
    fun decodeTrimsWhitespace() {
        assertEquals(listOf(india), SavedPlaylists.decode("  India \t  ${india.url}  "))
    }

    @Test
    fun decodeKeepsFirstOfDuplicateUrls() {
        val text = "First\t${india.url}\nNews\t${news.url}\nSecond\t${india.url}"
        assertEquals(
            listOf(india.copy(name = "First"), news),
            SavedPlaylists.decode(text),
        )
    }

    @Test
    fun controlCharactersInUrlsAreCleaned() {
        val odd = SavedPlaylist("Name\r", "http://example.com/a.m3u\r\n")
        assertEquals(
            listOf(SavedPlaylist("Name", "http://example.com/a.m3u")),
            SavedPlaylists.decode(SavedPlaylists.encode(listOf(odd))),
        )
        val tabbed = SavedPlaylist("A", "http://example.com/\tb.m3u")
        assertEquals(
            listOf(SavedPlaylist("A", "http://example.com/ b.m3u")),
            SavedPlaylists.decode(SavedPlaylists.encode(listOf(tabbed))),
        )
    }

    @Test
    fun linesStoredBeforeEpgUrlExistedDecodeWithoutOne() {
        assertEquals(listOf(india), SavedPlaylists.decode("India\t${india.url}"))
        assertNull(SavedPlaylists.decode("India\t${india.url}").single().epgUrl)
        // And keep writing the two-field form, so the stored value is byte-identical.
        assertEquals("India\t${india.url}", SavedPlaylists.encode(listOf(india)))
    }

    @Test
    fun epgUrlRoundTrips() {
        val withEpg = india.copy(epgUrl = "https://example.com/guide.xml.gz")
        assertEquals(
            "India\t${india.url}\thttps://example.com/guide.xml.gz",
            SavedPlaylists.encode(listOf(withEpg)),
        )
        assertEquals(listOf(withEpg), SavedPlaylists.decode(SavedPlaylists.encode(listOf(withEpg))))
    }

    @Test
    fun blankOrTabbedEpgUrlsCannotCorruptALine() {
        val tabbed = india.copy(epgUrl = "https://example.com/\tguide.xml")
        assertEquals(
            listOf(india.copy(epgUrl = "https://example.com/ guide.xml")),
            SavedPlaylists.decode(SavedPlaylists.encode(listOf(tabbed))),
        )
        assertEquals(listOf(india), SavedPlaylists.decode(SavedPlaylists.encode(listOf(india.copy(epgUrl = "  ")))))
        assertEquals(listOf(india), SavedPlaylists.decode("India\t${india.url}\t  "))
    }

    @Test
    fun upsertIntoEmptyListAppends() {
        assertEquals(listOf(india), SavedPlaylists.upsert(emptyList(), india))
    }

    @Test
    fun defaultNameEdgeCases() {
        assertEquals("live (example.com)", SavedPlaylists.defaultName("https://www.example.com/live.m3u8"))
        assertEquals("list (example.com)", SavedPlaylists.defaultName("https://example.com/tv/list.m3u"))
        assertEquals("example.com", SavedPlaylists.defaultName("https://example.com"))
        assertEquals(
            "a (example.com)",
            SavedPlaylists.defaultName("https://example.com/a.m3u|User-Agent=Kodi"),
        )
        assertEquals("sports", SavedPlaylists.defaultName("lists/sports.m3u"))
        assertEquals("http://example.com/my list.m3u", SavedPlaylists.defaultName("http://example.com/my list.m3u"))
    }
}
