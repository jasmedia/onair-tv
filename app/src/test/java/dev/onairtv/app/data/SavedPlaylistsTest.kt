package dev.onairtv.app.data

import org.junit.Assert.assertEquals
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
}
