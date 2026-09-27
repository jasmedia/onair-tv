package dev.onairtv.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class EpgFormatTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val kolkata = TimeZone.getTimeZone("Asia/Kolkata")

    /** 2026-09-20T12:00:00Z. */
    private val noon = 1_789_905_600_000L

    @Test
    fun clockIs24HourInTheGivenZone() {
        assertEquals("12:00", formatClock(noon, utc))
        assertEquals("17:30", formatClock(noon, kolkata))
        assertEquals("00:00", formatClock(noon - 12 * 3_600_000L, utc))
        assertEquals("23:59", formatClock(noon - 12 * 3_600_000L - 60_000L, utc))
    }

    @Test
    fun remainingRoundsDownToWholeMinutes() {
        assertEquals("24m left", remainingLabel(noon, noon + 24 * 60_000L))
        assertEquals("1m left", remainingLabel(noon, noon + 119_000L))
        assertEquals("1h left", remainingLabel(noon, noon + 3_600_000L))
        assertEquals("1h 5m left", remainingLabel(noon, noon + 3_900_000L))
        assertEquals("2h 30m left", remainingLabel(noon, noon + 9_000_000L))
    }

    @Test
    fun aProgrammeThatShouldHaveEndedSaysSo() {
        assertEquals("ending", remainingLabel(noon, noon))
        assertEquals("ending", remainingLabel(noon, noon + 59_000L))
        assertEquals("ending", remainingLabel(noon, noon - 600_000L))
    }

    @Test
    fun progressIsClampedToTheProgramme() {
        assertEquals(0f, progressFraction(noon, noon, noon + 3_600_000L), 0.0001f)
        assertEquals(0.5f, progressFraction(noon + 1_800_000L, noon, noon + 3_600_000L), 0.0001f)
        assertEquals(1f, progressFraction(noon + 3_600_000L, noon, noon + 3_600_000L), 0.0001f)
        assertEquals(0f, progressFraction(noon - 1L, noon, noon + 3_600_000L), 0.0001f)
        assertEquals(1f, progressFraction(noon + 99_000_000L, noon, noon + 3_600_000L), 0.0001f)
    }

    @Test
    fun aZeroOrNegativeLengthProgrammeDoesNotDivideByZero() {
        assertEquals(0f, progressFraction(noon, noon, noon), 0.0001f)
        assertEquals(0f, progressFraction(noon, noon + 1_000L, noon), 0.0001f)
    }
}
