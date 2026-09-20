package dev.onairtv.app.ui

import dev.onairtv.app.ALL_CHANNELS
import dev.onairtv.app.FAVORITES
import dev.onairtv.app.PlaylistState
import dev.onairtv.app.data.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelListsTest {

    private fun channel(index: Int, name: String, vararg groups: String) = Channel(
        index = index,
        name = name,
        url = "http://example.com/$index.m3u8",
        logo = null,
        tvgId = null,
        groups = groups.toList(),
        userAgent = null,
        referrer = null,
    )

    private val bbc = channel(0, "BBC World News", "News")
    private val espn = channel(1, "ESPN", "Sports")
    private val sky = channel(2, "Sky Sports News", "News", "Sports")
    private val state = PlaylistState.Ready(
        channels = listOf(bbc, espn, sky),
        groups = listOf(ALL_CHANNELS, FAVORITES, "News", "Sports"),
    )

    private fun visible(group: String, favorites: Set<String> = emptySet(), query: String = "") =
        visibleChannels(state, group, favorites, query)

    @Test
    fun allChannels() {
        assertEquals(listOf(bbc, espn, sky), visible(ALL_CHANNELS))
    }

    @Test
    fun favoritesKeepPlaylistOrder() {
        assertEquals(listOf(bbc, sky), visible(FAVORITES, favorites = setOf(sky.url, bbc.url)))
        assertEquals(emptyList<Channel>(), visible(FAVORITES))
        assertEquals(emptyList<Channel>(), visible(FAVORITES, favorites = setOf("http://gone/")))
    }

    @Test
    fun namedGroupIncludesMultiGroupChannels() {
        assertEquals(listOf(bbc, sky), visible("News"))
        assertEquals(listOf(espn, sky), visible("Sports"))
    }

    @Test
    fun searchOverridesSelectedGroup() {
        assertEquals(listOf(bbc), visible("Sports", query = "bbc"))
        assertEquals(listOf(bbc, sky), visible(FAVORITES, query = "news"))
    }

    @Test
    fun blankQueryIsNotASearch() {
        assertEquals(listOf(espn, sky), visible("Sports", query = "   "))
    }

    @Test
    fun unknownGroupFallsBackToAllChannels() {
        assertEquals(listOf(bbc, espn, sky), visible("Removed Group"))
        assertEquals(listOf(bbc, espn, sky), visible(""))
    }

    @Test
    fun zapMovesAndWraps() {
        assertEquals(3, zapPosition(2, 1, 5))
        assertEquals(1, zapPosition(2, -1, 5))
        assertEquals(0, zapPosition(4, 1, 5))
        assertEquals(4, zapPosition(0, -1, 5))
    }

    @Test
    fun zapOnSingleChannelStaysPut() {
        assertEquals(0, zapPosition(0, 1, 1))
        assertEquals(0, zapPosition(0, -1, 1))
    }

    @Test
    fun hlsDetection() {
        assertTrue(isHlsUrl("http://example.com/live/index.m3u8"))
        assertTrue(isHlsUrl("http://example.com/LIVE.M3U8"))
        assertTrue(isHlsUrl("http://example.com/live.m3u8?token=abc"))
        assertFalse(isHlsUrl("http://example.com/live.ts"))
        assertFalse(isHlsUrl("http://example.com/live"))
    }
}
