package dev.onairtv.app.data

import java.util.TimeZone

/** One programme from an XMLTV guide. Times are epoch milliseconds. */
data class Programme(val start: Long, val stop: Long, val title: String)

/** What a channel is showing now, and what follows. Either half can be missing. */
data class NowNext(val now: Programme?, val next: Programme?)

/**
 * The parsed guide for one playlist: programmes for the channels that playlist actually contains.
 *
 * Channels are matched on `tvg-id` first and on a normalised display name second; a channel that
 * matches neither simply has no guide data, and the UI falls back to what it showed before.
 */
class EpgGuide internal constructor(
    private val byId: Map<String, List<Programme>>,
    private val byName: Map<String, List<Programme>>,
    /** When the guide runs out: the latest stop time it holds. */
    val validUntil: Long,
) {

    val isEmpty: Boolean get() = byId.isEmpty() && byName.isEmpty()

    /** Null when this guide has nothing for [channel] at all. */
    fun nowNext(channel: Channel, atMillis: Long): NowNext? {
        val programmes = channel.tvgId?.let { byId[epgIdKey(it)] }
            ?: byName[epgNameKey(channel.name)]
            ?: return null
        return nowNextIn(programmes, atMillis)
    }

    companion object {
        val EMPTY = EpgGuide(emptyMap(), emptyMap(), 0L)
    }
}

/** XMLTV ids are compared case-insensitively; providers are inconsistent about capitals. */
internal fun epgIdKey(value: String): String = value.trim().lowercase()

/**
 * A display name reduced to what two sources are likely to agree on: letters and digits only.
 * `"Asianet News (720p) HD"` and `"Asianet News"` both become `"asianetnews"`.
 */
internal fun epgNameKey(value: String): String = buildString {
    for (c in value) {
        when {
            c.isLetterOrDigit() -> append(c.lowercaseChar())
            // Drop quality/format suffixes that only one of the two sides tends to carry.
            c == '(' -> return@buildString
        }
    }
}.removeSuffix("hd").removeSuffix("sd").ifEmpty { value.trim().lowercase() }

/**
 * A linear scan, deliberately: these lists hold a day's programmes for one channel (a few dozen),
 * and unlike a binary search this stays correct when a guide has gaps or overlapping entries.
 *
 * A programme whose `stop` is exactly [atMillis] is over.
 */
internal fun nowNextIn(programmes: List<Programme>, atMillis: Long): NowNext? {
    val index = programmes.indexOfFirst { it.stop > atMillis }
    if (index < 0) return null // the guide has run out for this channel
    val candidate = programmes[index]
    val following = programmes.getOrNull(index + 1)
    // Before `candidate` starts we're in a gap: nothing is on, but something is coming.
    return if (candidate.start <= atMillis) NowNext(candidate, following) else NowNext(null, candidate)
}

/**
 * Parses an XMLTV timestamp (`"20260920140000 +0530"`) to epoch milliseconds, or null.
 *
 * The date part may be 8, 10, 12 or 14 digits; missing fields are zero. Without an offset the
 * timestamp is read as local wall time in [zone] (a parameter only so tests can pin it).
 *
 * Hand-rolled rather than `java.time`: minSdk 23 and no core library desugaring. Days-from-civil
 * arithmetic also avoids allocating a `Calendar` per programme, and there are 100k+ of those.
 */
internal fun parseXmltvTime(value: String, zone: TimeZone = TimeZone.getDefault()): Long? {
    val text = value.trim()
    val digits = text.takeWhile { it.isDigit() }
    when (digits.length) {
        8, 10, 12, 14 -> Unit // YYYYMMDD, then hours, minutes and seconds as they're given
        else -> return null
    }

    val year = digits.substring(0, 4).toInt()
    val month = digits.substring(4, 6).toInt()
    val day = digits.substring(6, 8).toInt()
    if (month !in 1..12 || day !in 1..31) return null
    val hour = digits.twoDigitsAt(8)
    val minute = digits.twoDigitsAt(10)
    val second = digits.twoDigitsAt(12)
    if (hour > 23 || minute > 59 || second > 59) return null

    // days-from-civil: Howard Hinnant's algorithm, shifting the era to start on 1 March.
    var y = year
    if (month <= 2) y -= 1
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    val days = era * 146_097L + doe - 719_468L
    val utc = days * 86_400_000L + (hour * 3600 + minute * 60 + second) * 1000L

    val rest = text.substring(digits.length).trim()
    if (rest.isEmpty()) {
        // Local wall time. Two passes, because the offset to subtract is the one in force at the
        // local instant, not at the same number read as UTC -- they differ across a DST change.
        val guess = utc - zone.getOffset(utc)
        return utc - zone.getOffset(guess)
    }

    val sign = when (rest[0]) {
        '+' -> 1
        '-' -> -1
        else -> return null
    }
    val offsetDigits = rest.substring(1).replace(":", "")
    if (offsetDigits.length != 4 || !offsetDigits.all { it.isDigit() }) return null
    val offsetMinutes = offsetDigits.substring(0, 2).toInt() * 60 + offsetDigits.substring(2, 4).toInt()
    return utc - sign * offsetMinutes * 60_000L
}

private fun String.twoDigitsAt(index: Int): Int =
    if (index + 2 <= length) substring(index, index + 2).toInt() else 0
