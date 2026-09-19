package dev.opentv.app.data

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
}
