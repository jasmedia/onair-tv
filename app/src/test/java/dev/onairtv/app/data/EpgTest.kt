package dev.onairtv.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class EpgTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val kolkata = TimeZone.getTimeZone("Asia/Kolkata") // +05:30, no DST
    private val london = TimeZone.getTimeZone("Europe/London") // +00:00 / +01:00

    private fun channel(name: String, tvgId: String? = null) = Channel(
        index = 0,
        name = name,
        url = "http://stream.example/${name}",
        logo = null,
        tvgId = tvgId,
        groups = listOf("News"),
        userAgent = null,
        referrer = null,
    )

    private fun programme(start: Long, stop: Long, title: String = "T") = Programme(start, stop, title)

    // ---- parseXmltvTime -------------------------------------------------------------------

    /** 2026-09-20T14:00:00Z, the instant every offset case below is written against. */
    private val noonish = 1_789_912_800_000L

    @Test
    fun parsesTheEpochItself() {
        assertEquals(0L, parseXmltvTime("19700101000000 +0000"))
        assertEquals(0L, parseXmltvTime("19700101000000", utc))
        assertEquals(-1_000L, parseXmltvTime("19691231235959 +0000"))
    }

    @Test
    fun appliesTheOffset() {
        assertEquals(noonish, parseXmltvTime("20260920140000 +0000"))
        // +0530 means the wall clock is 5.5 h ahead of UTC, so the instant is 5.5 h earlier.
        assertEquals(noonish - 16_200_000L, parseXmltvTime("20260920140000 +0430"))
        assertEquals(noonish - 19_800_000L, parseXmltvTime("20260920140000 +0530"))
        assertEquals(noonish + 14_400_000L, parseXmltvTime("20260920140000 -0400"))
    }

    @Test
    fun acceptsOffsetSpellingVariants() {
        val expected = parseXmltvTime("20260920140000 +0530")
        assertEquals(expected, parseXmltvTime("20260920140000+0530"))
        assertEquals(expected, parseXmltvTime("20260920140000   +05:30"))
        assertEquals(expected, parseXmltvTime("  20260920140000 +0530  "))
    }

    @Test
    fun withoutAnOffsetTheTimeIsLocalWallTime() {
        assertEquals(noonish - 19_800_000L, parseXmltvTime("20260920140000", kolkata))
        // London in September is on BST (+01:00), in January on GMT.
        assertEquals(parseXmltvTime("20260920130000 +0000"), parseXmltvTime("20260920140000", london))
        assertEquals(parseXmltvTime("20260120140000 +0000"), parseXmltvTime("20260120140000", london))
    }

    @Test
    fun missingTimeFieldsAreZero() {
        assertEquals(parseXmltvTime("20260920000000 +0000"), parseXmltvTime("20260920 +0000"))
        assertEquals(parseXmltvTime("20260920140000 +0000"), parseXmltvTime("2026092014 +0000"))
        assertEquals(parseXmltvTime("20260920143000 +0000"), parseXmltvTime("202609201430 +0000"))
    }

    @Test
    fun garbageIsNull() {
        assertNull(parseXmltvTime(""))
        assertNull(parseXmltvTime("not a time"))
        assertNull(parseXmltvTime("2026 +0000")) // too few digits
        assertNull(parseXmltvTime("2026092014000 +0000")) // 13 digits
        assertNull(parseXmltvTime("20261320140000 +0000")) // month 13
        assertNull(parseXmltvTime("20260920250000 +0000")) // hour 25
        assertNull(parseXmltvTime("20260920140000 0530")) // no sign
        assertNull(parseXmltvTime("20260920140000 +53")) // truncated offset
    }

    // ---- keys -----------------------------------------------------------------------------

    @Test
    fun idKeysIgnoreCaseAndSurroundingSpace() {
        assertEquals("asianetnews.in", epgIdKey("  AsianetNews.in "))
        assertEquals(epgIdKey("BBCOne.uk"), epgIdKey("bbcone.uk"))
    }

    @Test
    fun nameKeysDropPunctuationQualityAndQuality() {
        assertEquals("asianetnews", epgNameKey("Asianet News (720p) HD"))
        assertEquals("asianetnews", epgNameKey("Asianet News"))
        assertEquals("asianetnews", epgNameKey("asianet-news HD"))
        assertEquals("skysportsnews", epgNameKey("Sky Sports News SD"))
        assertEquals("tv9", epgNameKey("TV 9"))
        // A name that reduces to nothing keeps something to match on rather than colliding.
        assertEquals("+++", epgNameKey("+++"))
    }

    // ---- nowNextIn ------------------------------------------------------------------------

    private val schedule = listOf(
        programme(1_000, 2_000, "First"),
        programme(2_000, 3_000, "Second"),
        programme(4_000, 5_000, "After a gap"), // 3_000..4_000 is unprogrammed
    )

    @Test
    fun insideAProgrammeGivesItAndTheFollowingOne() {
        assertEquals(NowNext(schedule[0], schedule[1]), nowNextIn(schedule, 1_500))
        assertEquals(NowNext(schedule[1], schedule[2]), nowNextIn(schedule, 2_500))
        assertEquals(NowNext(schedule[2], null), nowNextIn(schedule, 4_500))
    }

    @Test
    fun aProgrammeIsOverAtItsStopTime() {
        assertEquals(NowNext(schedule[1], schedule[2]), nowNextIn(schedule, 2_000))
        assertEquals(NowNext(schedule[0], schedule[1]), nowNextIn(schedule, 1_999))
    }

    @Test
    fun inAGapNothingIsOnButSomethingIsNext() {
        assertEquals(NowNext(null, schedule[2]), nowNextIn(schedule, 3_000))
        assertEquals(NowNext(null, schedule[2]), nowNextIn(schedule, 3_999))
    }

    @Test
    fun beforeTheFirstProgrammeAndAfterTheLast() {
        assertEquals(NowNext(null, schedule[0]), nowNextIn(schedule, 0))
        assertNull(nowNextIn(schedule, 5_000))
        assertNull(nowNextIn(schedule, 99_999))
        assertNull(nowNextIn(emptyList(), 1_500))
    }

    // ---- EpgGuide -------------------------------------------------------------------------

    private val asianet = listOf(programme(1_000, 2_000, "Asianet Now"))
    private val bbc = listOf(programme(1_000, 2_000, "BBC Now"))
    private val guide = EpgGuide(
        byId = mapOf("asianetnews.in" to asianet),
        byName = mapOf("asianetnews" to asianet, "bbcworldnews" to bbc),
        validUntil = 2_000,
    )

    @Test
    fun matchesOnTvgIdFirst() {
        val found = guide.nowNext(channel("Something Else", tvgId = "AsianetNews.in"), 1_500)
        assertEquals("Asianet Now", found?.now?.title)
    }

    @Test
    fun fallsBackToTheDisplayName() {
        assertEquals("BBC Now", guide.nowNext(channel("BBC World News (1080p)"), 1_500)?.now?.title)
        assertEquals("BBC Now", guide.nowNext(channel("bbc world news"), 1_500)?.now?.title)
    }

    @Test
    fun aTvgIdTheGuideDoesNotCarryFallsBackToTheName() {
        assertEquals(
            "BBC Now",
            guide.nowNext(channel("BBC World News", tvgId = "Unknown.uk"), 1_500)?.now?.title,
        )
    }

    @Test
    fun anUnknownChannelHasNoGuideData() {
        assertNull(guide.nowNext(channel("Nothing Here"), 1_500))
        assertNull(guide.nowNext(channel("Nothing Here", tvgId = "Unknown.uk"), 1_500))
    }

    @Test
    fun aKnownChannelPastTheEndOfItsGuideIsNull() {
        assertNull(guide.nowNext(channel("BBC World News"), 9_999))
    }

    @Test
    fun emptyGuide() {
        assertTrue(EpgGuide.EMPTY.isEmpty)
        assertNull(EpgGuide.EMPTY.nowNext(channel("Anything", tvgId = "a.b"), 1_500))
        assertFalse(guide.isEmpty)
    }
}
