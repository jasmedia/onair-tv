package dev.onairtv.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.TimeZone
import java.util.zip.GZIPOutputStream

class XmltvParserTest {

    private val utc = TimeZone.getTimeZone("UTC")

    /** 2026-09-20T12:00:00Z; every fixture time below is written in UTC around it. */
    private val now = 1_789_905_600_000L

    private fun channel(name: String, tvgId: String? = null) = Channel(
        index = 0,
        name = name,
        url = "http://stream.example/$name",
        logo = null,
        tvgId = tvgId,
        groups = listOf("News"),
        userAgent = null,
        referrer = null,
    )

    private fun parse(
        xml: String,
        channels: List<Channel>,
        horizon: Long = XmltvParser.DEFAULT_HORIZON_MILLIS,
    ) = XmltvParser.parse(ByteArrayInputStream(xml.toByteArray()), channels, now, horizon, utc)

    private fun titlesFor(guide: EpgGuide, channel: Channel): List<String?> =
        guide.nowNext(channel, now).let { listOf(it?.now?.title, it?.next?.title) }

    private val sample = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "xmltv.dtd">
        <tv generator-info-name="test">
          <channel id="AsianetNews.in">
            <display-name>Asianet News</display-name>
            <display-name lang="ml">ഏഷ്യാനെറ്റ്</display-name>
            <icon src="http://logo.example/a.png" />
          </channel>
          <channel id="BBCOne.uk">
            <display-name>BBC One</display-name>
          </channel>
          <channel id="Unwanted.xx">
            <display-name>Nobody Watches This</display-name>
          </channel>
          <programme start="20260920110000 +0000" stop="20260920120000 +0000" channel="AsianetNews.in">
            <title>Already Over</title>
          </programme>
          <programme start="20260920113000 +0000" stop="20260920123000 +0000" channel="AsianetNews.in">
            <title>News &amp; Views</title>
            <desc>A long description that must never be kept in memory.</desc>
          </programme>
          <programme start="20260920123000 +0000" stop="20260920133000 +0000" channel="AsianetNews.in">
            <title><![CDATA[Prime Time]]></title>
          </programme>
          <programme start="20260922120000 +0000" stop="20260922130000 +0000" channel="AsianetNews.in">
            <title>Beyond The Horizon</title>
          </programme>
          <programme start="20260920113000 +0000" stop="20260920123000 +0000" channel="BBCOne.uk">
            <title>Breakfast</title>
          </programme>
          <programme start="20260920113000 +0000" stop="20260920123000 +0000" channel="Unwanted.xx">
            <title>Not Requested</title>
          </programme>
        </tv>
    """.trimIndent()

    private val asianet = channel("Asianet News (720p)", tvgId = "asianetnews.in")
    private val bbc = channel("BBC One") // matched by display name: no tvg-id

    @Test
    fun keepsRequestedChannelsAndDropsTheRest() {
        val guide = parse(sample, listOf(asianet, bbc))

        assertEquals(listOf("News & Views", "Prime Time"), titlesFor(guide, asianet))
        assertEquals(listOf("Breakfast", null), titlesFor(guide, bbc))
        assertNull(guide.nowNext(channel("Nobody Watches This"), now))
    }

    @Test
    fun dropsProgrammesAlreadyOverAndBeyondTheHorizon() {
        val guide = parse(sample, listOf(asianet))

        // "Already Over" stopped exactly at `now`, so an hour earlier the guide starts empty.
        assertEquals(NowNext(null, guide.nowNext(asianet, now)!!.now), guide.nowNext(asianet, now - 3_600_000))
        assertEquals("Prime Time", guide.nowNext(asianet, now + 3_600_000)?.now?.title)
        // "Beyond The Horizon" is two days out: the last kept stop is 13:30Z.
        assertEquals(now + 5_400_000L, guide.validUntil)
    }

    @Test
    fun aShorterHorizonDropsMore() {
        val guide = parse(sample, listOf(asianet), horizon = 25 * 60 * 1000L)

        // Only the programme already running survives: the 12:30 one starts past the horizon.
        assertEquals(listOf("News & Views", null), titlesFor(guide, asianet))
    }

    @Test
    fun aDoctypeWithAnUnresolvableSystemIdStillParses() {
        // Without an EntityResolver the parser would try to fetch xmltv.dtd and fail here.
        assertTrue(sample.contains("<!DOCTYPE tv SYSTEM"))
        assertNotNull(parse(sample, listOf(asianet)).nowNext(asianet, now))
    }

    @Test
    fun entitiesAndCdataInTitlesAreDecoded() {
        val guide = parse(sample, listOf(asianet))
        assertEquals("News & Views", guide.nowNext(asianet, now)?.now?.title)
        assertEquals("Prime Time", guide.nowNext(asianet, now)?.next?.title)
    }

    @Test
    fun anyDisplayNameCanMatch() {
        val malayalam = channel("ഏഷ്യാനെറ്റ്")
        assertEquals("News & Views", parse(sample, listOf(malayalam)).nowNext(malayalam, now)?.now?.title)
    }

    @Test
    fun programmesWithoutUsableTimesAreSkipped() {
        val xml = """
            <tv>
              <channel id="a.x"><display-name>A</display-name></channel>
              <programme start="20260920113000 +0000" channel="a.x"><title>No Stop</title></programme>
              <programme stop="20260920123000 +0000" channel="a.x"><title>No Start</title></programme>
              <programme start="nonsense" stop="20260920123000 +0000" channel="a.x"><title>Junk</title></programme>
              <programme start="20260920113000 +0000" stop="20260920123000 +0000"><title>No Channel</title></programme>
              <programme start="20260920113000 +0000" stop="20260920123000 +0000" channel="a.x"></programme>
              <programme start="20260920113000 +0000" stop="20260920123000 +0000" channel="a.x">
                <title>Kept</title>
              </programme>
            </tv>
        """.trimIndent()
        val a = channel("A", tvgId = "a.x")

        assertEquals(listOf("Kept", null), titlesFor(parse(xml, listOf(a)), a))
    }

    @Test
    fun onlyTheFirstTitleIsKept() {
        val xml = """
            <tv>
              <channel id="a.x"><display-name>A</display-name></channel>
              <programme start="20260920113000 +0000" stop="20260920123000 +0000" channel="a.x">
                <title>English</title>
                <title lang="ml">Malayalam</title>
                <desc>Ignored</desc>
              </programme>
            </tv>
        """.trimIndent()
        val a = channel("A", tvgId = "a.x")

        assertEquals("English", parse(xml, listOf(a)).nowNext(a, now)?.now?.title)
    }

    @Test
    fun programmesOutOfOrderAreSorted() {
        val xml = """
            <tv>
              <channel id="a.x"><display-name>A</display-name></channel>
              <programme start="20260920123000 +0000" stop="20260920133000 +0000" channel="a.x">
                <title>Second</title>
              </programme>
              <programme start="20260920113000 +0000" stop="20260920123000 +0000" channel="a.x">
                <title>First</title>
              </programme>
            </tv>
        """.trimIndent()
        val a = channel("A", tvgId = "a.x")

        assertEquals(listOf("First", "Second"), titlesFor(parse(xml, listOf(a)), a))
    }

    @Test
    fun aProgrammeForAnUndeclaredButRequestedIdIsStillKept() {
        val xml = """
            <tv>
              <programme start="20260920113000 +0000" stop="20260920123000 +0000" channel="a.x">
                <title>Kept</title>
              </programme>
            </tv>
        """.trimIndent()
        val a = channel("Whatever", tvgId = "A.X")

        assertEquals("Kept", parse(xml, listOf(a)).nowNext(a, now)?.now?.title)
    }

    @Test
    fun noChannelsMeansTheStreamIsNeverRead() {
        val stream = object : InputStream() {
            override fun read(): Int = throw AssertionError("the guide must not be read")
        }

        val guide = XmltvParser.parse(stream, emptyList(), now, zone = utc)

        assertTrue(guide.isEmpty)
        assertEquals(0L, guide.validUntil)
    }

    @Test
    fun aGuideWithNothingForThePlaylistIsEmpty() {
        assertTrue(parse(sample, listOf(channel("Some Other Channel"))).isEmpty)
    }

    @Test
    fun anInterruptedParseGivesUpInsteadOfFinishing() {
        // 2000 programmes, so the every-512 cancellation check is reached well before the end.
        val many = buildString {
            append("<tv><channel id=\"a.x\"><display-name>A</display-name></channel>")
            repeat(2_000) {
                append("<programme start=\"20260920113000 +0000\" stop=\"20260920123000 +0000\" ")
                append("channel=\"a.x\"><title>P$it</title></programme>")
            }
            append("</tv>")
        }
        val a = channel("A", tvgId = "a.x")

        Thread.currentThread().interrupt()
        try {
            parse(many, listOf(a))
            fail("Expected the parse to give up")
        } catch (e: Exception) {
            assertTrue(e.toString(), e is InterruptedIOException || e.cause is InterruptedIOException)
        } finally {
            Thread.interrupted() // clear the flag for the tests that follow
        }
    }

    // ---- maybeGunzip ----------------------------------------------------------------------

    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
    }.toByteArray()

    @Test
    fun gunzipsOnlyWhatIsActuallyGzipped() {
        val plain = "<tv></tv>"
        assertEquals(
            plain,
            maybeGunzip(ByteArrayInputStream(plain.toByteArray())).readBytes().decodeToString(),
        )
        assertEquals(
            plain,
            maybeGunzip(ByteArrayInputStream(gzip(plain))).readBytes().decodeToString(),
        )
    }

    @Test
    fun gunzipHandlesShortAndEmptyStreams() {
        assertEquals("", maybeGunzip(ByteArrayInputStream(ByteArray(0))).readBytes().decodeToString())
        assertEquals("<", maybeGunzip(ByteArrayInputStream("<".toByteArray())).readBytes().decodeToString())
    }

    @Test
    fun parsesAGzippedGuide() {
        val guide = XmltvParser.parse(
            maybeGunzip(ByteArrayInputStream(gzip(sample))),
            listOf(asianet),
            now,
            zone = utc,
        )
        assertEquals("News & Views", guide.nowNext(asianet, now)?.now?.title)
    }
}
