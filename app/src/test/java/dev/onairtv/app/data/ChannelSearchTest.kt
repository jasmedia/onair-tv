package dev.onairtv.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelSearchTest {

    private val channels = listOf("BBC World News", "Asianet News", "Canal Sur Andalucía", "Kairali TV")
        .mapIndexed { i, name ->
            Channel(
                index = i,
                name = name,
                url = "http://example.com/$i.m3u8",
                logo = null,
                tvgId = null,
                groups = listOf("General"),
                userAgent = null,
                referrer = null,
            )
        }

    private fun search(query: String) = ChannelSearch.filter(channels, query).map { it.name }

    @Test
    fun blankQueryReturnsEverything() {
        assertEquals(channels.map { it.name }, search(""))
        assertEquals(channels.map { it.name }, search("   "))
    }

    @Test
    fun matchesCaseInsensitiveSubstring() {
        assertEquals(listOf("BBC World News", "Asianet News"), search("NEWS"))
        assertEquals(listOf("Kairali TV"), search("kair"))
    }

    @Test
    fun allWordsMustMatchInAnyOrder() {
        assertEquals(listOf("BBC World News"), search("news bbc"))
        assertEquals(emptyList<String>(), search("bbc kairali"))
    }

    @Test
    fun ignoresAccents() {
        assertEquals(listOf("Canal Sur Andalucía"), search("andalucia"))
        assertEquals(listOf("Canal Sur Andalucía"), search("Andalucía"))
    }

    @Test
    fun ignoresExtraWhitespace() {
        assertEquals(listOf("BBC World News"), search("  bbc    news  "))
        assertEquals(listOf("BBC World News"), search("\tbbc\n"))
    }

    @Test
    fun accentedQueryMatchesPlainName() {
        assertEquals(listOf("Kairali TV"), search("Kaírálí"))
    }

    @Test
    fun matchesInsideWords() {
        assertEquals(listOf("Asianet News"), search("sianet"))
        assertEquals(listOf("Canal Sur Andalucía"), search("dalu"))
    }

    @Test
    fun emptyChannelListGivesEmptyResult() {
        assertEquals(emptyList<Channel>(), ChannelSearch.filter(emptyList(), "news"))
        assertEquals(emptyList<Channel>(), ChannelSearch.filter(emptyList(), ""))
    }

    @Test
    fun keepsInputOrder() {
        val reversed = channels.reversed()
        assertEquals(
            listOf("Asianet News", "BBC World News"),
            ChannelSearch.filter(reversed, "news").map { it.name },
        )
    }
}
