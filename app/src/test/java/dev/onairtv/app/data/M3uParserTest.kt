package dev.onairtv.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {

    private val sample = """
        #EXTM3U x-tvg-url="https://example.com/guide.xml"
        #EXTINF:-1 tvg-id="AsianetNews.in" tvg-logo="https://i.imgur.com/a.png" group-title="News",Asianet News (720p)
        https://example.com/asianet/index.m3u8
        #EXTINF:-1 tvg-id="Foo.in" group-title="Entertainment;Movies",Foo, the Channel
        #EXTVLCOPT:http-referrer=https://foo.example/
        #EXTVLCOPT:http-user-agent=Mozilla/5.0
        http://foo.example/live
        #EXTINF:-1,No Attributes
        #EXTGRP:Music
        http://bar.example/stream.ts|User-Agent=Kodi&Referer=https://bar.example/
    """.trimIndent()

    @Test
    fun parsesAllChannels() {
        val channels = M3uParser.parse(sample)
        assertEquals(3, channels.size)

        val a = channels[0]
        assertEquals("Asianet News (720p)", a.name)
        assertEquals("https://example.com/asianet/index.m3u8", a.url)
        assertEquals("https://i.imgur.com/a.png", a.logo)
        assertEquals(listOf("News"), a.groups)
        assertNull(a.referrer)

        val b = channels[1]
        assertEquals("Foo, the Channel", b.name)
        assertEquals(listOf("Entertainment", "Movies"), b.groups)
        assertEquals("https://foo.example/", b.referrer)
        assertEquals("Mozilla/5.0", b.userAgent)

        val c = channels[2]
        assertEquals("No Attributes", c.name)
        assertEquals(listOf("Music"), c.groups)
        assertEquals("http://bar.example/stream.ts", c.url)
        assertEquals("Kodi", c.userAgent)
        assertEquals("https://bar.example/", c.referrer)
    }

    @Test
    fun ignoresUrlsWithoutExtinfAndEmptyInput() {
        assertEquals(0, M3uParser.parse("").size)
        assertEquals(0, M3uParser.parse("#EXTM3U\nhttp://orphan.example/stream").size)
    }

    private fun parseOne(text: String): Channel = M3uParser.parse(text).single()

    @Test
    fun stripsByteOrderMark() {
        val channels = M3uParser.parse("\uFEFF#EXTM3U\n#EXTINF:-1,A\nhttp://a.example/1")
        assertEquals(listOf("A"), channels.map { it.name })
        assertEquals("B", parseOne("\uFEFF#EXTINF:-1,B\nhttp://b.example/1").name)
    }

    @Test
    fun handlesCrlfLineEndings() {
        val channels = M3uParser.parse(
            "#EXTM3U\r\n#EXTINF:-1 group-title=\"News\",A\r\nhttp://a.example/1\r\n" +
                "#EXTINF:-1,B\r\nhttp://b.example/2\r\n",
        )
        assertEquals(listOf("A", "B"), channels.map { it.name })
        assertEquals(listOf("http://a.example/1", "http://b.example/2"), channels.map { it.url })
        assertEquals(listOf("News"), channels[0].groups)
    }

    @Test
    fun directivesAreCaseInsensitive() {
        val channel = parseOne(
            "#extinf:-1,Lower\n#extvlcopt:http-user-agent=UA\n#extgrp:Music\nhttp://a.example/1",
        )
        assertEquals("Lower", channel.name)
        assertEquals("UA", channel.userAgent)
        assertEquals(listOf("Music"), channel.groups)
    }

    @Test
    fun attributeKeysAreCaseInsensitive() {
        val channel = parseOne(
            "#EXTINF:-1 TVG-ID=\"X.in\" TVG-LOGO=\"http://logo\" Group-Title=\"News\",A\nhttp://a.example/1",
        )
        assertEquals("X.in", channel.tvgId)
        assertEquals("http://logo", channel.logo)
        assertEquals(listOf("News"), channel.groups)
    }

    @Test
    fun commaInsideQuotedAttributeDoesNotSplitTitle() {
        val channel = parseOne(
            "#EXTINF:-1 group-title=\"News, Weather\" tvg-name=\"a,b\",Real Title\nhttp://a.example/1",
        )
        assertEquals("Real Title", channel.name)
        assertEquals(listOf("News, Weather"), channel.groups)
    }

    @Test
    fun nameFallsBackToTvgNameThenPosition() {
        val channels = M3uParser.parse(
            """
            #EXTINF:-1 tvg-name="From Attr",
            http://a.example/1
            #EXTINF:-1 tvg-name="No Comma"
            http://a.example/2
            #EXTINF:-1 tvg-name="",
            http://a.example/3
            #EXTINF:-1 tvg-name="Ignored",Title Wins
            http://a.example/4
            """.trimIndent(),
        )
        assertEquals(listOf("From Attr", "No Comma", "Channel 3", "Title Wins"), channels.map { it.name })
    }

    @Test
    fun groupHandling() {
        val channels = M3uParser.parse(
            """
            #EXTINF:-1 group-title="",Empty Title Uses Extgrp
            #EXTGRP:Music
            http://a.example/1
            #EXTINF:-1 group-title="A;;B ",Split
            http://a.example/2
            #EXTINF:-1,None
            http://a.example/3
            #EXTINF:-1 group-title=" ; ",Only Separators
            http://a.example/4
            #EXTINF:-1 group-title="News",Attr Beats Extgrp
            #EXTGRP:Music
            http://a.example/5
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                listOf("Music"),
                listOf("A", "B"),
                listOf(M3uParser.UNGROUPED),
                listOf(M3uParser.UNGROUPED),
                listOf("News"),
            ),
            channels.map { it.groups },
        )
    }

    @Test
    fun emptyLogoAndIdAreNull() {
        val channel = parseOne("#EXTINF:-1 tvg-id=\"\" tvg-logo=\"  \",A\nhttp://a.example/1")
        assertNull(channel.logo)
        assertNull(channel.tvgId)
    }

    @Test
    fun acceptsHttpRefererSpelling() {
        val channel = parseOne("#EXTINF:-1,A\n#EXTVLCOPT:http-referer=https://ref.example/\nhttp://a.example/1")
        assertEquals("https://ref.example/", channel.referrer)
    }

    @Test
    fun vlcoptBeatsExtinfAttributesBeatsPipeSuffix() {
        val channels = M3uParser.parse(
            """
            #EXTINF:-1 user-agent="AttrUA" referrer="https://attr/",All Three
            #EXTVLCOPT:http-user-agent=VlcUA
            #EXTVLCOPT:http-referrer=https://vlc/
            http://a.example/1|User-Agent=PipeUA&Referer=https://pipe/
            #EXTINF:-1 user-agent="AttrUA" referrer="https://attr/",Attr And Pipe
            http://a.example/2|User-Agent=PipeUA&Referer=https://pipe/
            #EXTINF:-1,Pipe Only
            http://a.example/3|user-agent=PipeUA&Referrer=https://pipe-referrer/
            """.trimIndent(),
        )
        assertEquals(listOf("VlcUA", "AttrUA", "PipeUA"), channels.map { it.userAgent })
        assertEquals(
            listOf("https://vlc/", "https://attr/", "https://pipe-referrer/"),
            channels.map { it.referrer },
        )
        assertEquals(
            listOf("http://a.example/1", "http://a.example/2", "http://a.example/3"),
            channels.map { it.url },
        )
    }

    @Test
    fun malformedPipePairsAreIgnored() {
        val channel = parseOne("#EXTINF:-1,A\nhttp://a.example/1|junk&=novalue&Referer=&User-Agent=UA")
        assertEquals("http://a.example/1", channel.url)
        assertEquals("UA", channel.userAgent)
        assertNull(channel.referrer)
    }

    @Test
    fun vlcoptBeforeExtinfIsDiscarded() {
        val channel = parseOne(
            "#EXTVLCOPT:http-user-agent=Stale\n#EXTVLCOPT:http-referrer=https://stale/\n" +
                "#EXTINF:-1,A\nhttp://a.example/1",
        )
        assertNull(channel.userAgent)
        assertNull(channel.referrer)
    }

    @Test
    fun headersDoNotCarryOverToNextChannel() {
        val channels = M3uParser.parse(
            """
            #EXTINF:-1,A
            #EXTVLCOPT:http-user-agent=UA
            #EXTVLCOPT:http-referrer=https://ref/
            #EXTGRP:Music
            http://a.example/1
            #EXTINF:-1,B
            http://a.example/2
            """.trimIndent(),
        )
        val b = channels[1]
        assertNull(b.userAgent)
        assertNull(b.referrer)
        assertEquals(listOf(M3uParser.UNGROUPED), b.groups)
    }

    @Test
    fun extinfWithoutUrlIsDroppedByTheNextOne() {
        val channels = M3uParser.parse(
            "#EXTINF:-1,Orphan\n#EXTVLCOPT:http-user-agent=UA\n#EXTINF:-1,Kept\nhttp://a.example/1",
        )
        assertEquals(listOf("Kept"), channels.map { it.name })
        assertEquals(0, channels[0].index)
        assertNull(channels[0].userAgent)
    }

    @Test
    fun indicesAreSequential() {
        val channels = M3uParser.parse(sample + "\nhttp://orphan.example/x\n#EXTINF:-1,Last\nhttp://last.example/")
        assertEquals(listOf(0, 1, 2, 3), channels.map { it.index })
    }
}
