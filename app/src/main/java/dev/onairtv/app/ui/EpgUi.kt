package dev.onairtv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.onairtv.app.data.Channel
import dev.onairtv.app.data.EpgGuide
import dev.onairtv.app.data.NowNext
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.TimeZone

/**
 * The guide plus the clock that makes "now" advance, as one thing to pass down the screens.
 *
 * Holding the clock as an unread [State] is the point: a screen can carry it through without
 * subscribing to it, so a tick recomposes only the rows that actually read it. [Immutable] keeps
 * the rows that take one skippable, and keeps the Compose annotation out of `data/`.
 */
@Immutable
data class EpgSource(val guide: EpgGuide, val clock: State<Long>)

/**
 * Ticks every [periodMillis]. Call once, high up; a programme runs 30-120 minutes, so half a
 * minute is as precise as the progress bar needs.
 */
@Composable
internal fun rememberEpgClock(periodMillis: Long = 30_000L): State<Long> =
    produceState(System.currentTimeMillis(), periodMillis) {
        while (true) {
            delay(periodMillis)
            value = System.currentTimeMillis()
        }
    }

/**
 * What's on [channel] now and next, or null when the guide has nothing for it.
 *
 * Being `@Composable`, the clock read below happens in the *caller's* recomposition scope, so a
 * tick redraws that one row rather than everything holding the [EpgSource].
 */
@Composable
internal fun rememberNowNext(epg: EpgSource, channel: Channel): NowNext? {
    val at = epg.clock.value
    return remember(epg.guide, channel, at) { epg.guide.nowNext(channel, at) }
}

/** Two lines of "what's on": the programme, how much is left, a progress bar, and what follows. */
@Composable
internal fun NowNextSupporting(nowNext: NowNext?, at: Long, fallback: String) {
    val now = nowNext?.now
    val next = nowNext?.next
    if (now == null && next == null) {
        Text(fallback, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }

    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(
                if (now != null) "${formatClock(now.start)}  ${now.title}" else "Nothing scheduled",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (now != null) {
                Text(
                    remainingLabel(at, now.stop),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        if (now != null) {
            ProgressBar(
                fraction = progressFraction(at, now.start, now.stop),
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
            )
        }
        if (next != null) {
            Text(
                "→ ${next.title}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * How far through the programme we are. Drawn by hand because material3 (which has
 * `LinearProgressIndicator`) isn't a dependency, and tv-material3 has no equivalent.
 */
@Composable
internal fun ProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    track: Color = MaterialTheme.colorScheme.surfaceVariant,
    fill: Color = MaterialTheme.colorScheme.primary,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(track),
    ) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(fill))
    }
}

/**
 * `"20:00"`, in the device's time zone. Calendar rather than SimpleDateFormat so it is pure and
 * unit-testable, and so digits can't come out localised.
 *
 * Always 24-hour: honouring the user's 12/24-hour setting needs `LocalContext`, which would make
 * this a composable.
 */
internal fun formatClock(millis: Long, zone: TimeZone = TimeZone.getDefault()): String {
    val calendar = Calendar.getInstance(zone).apply { timeInMillis = millis }
    return "%02d:%02d".format(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))
}

/** `"24m left"`, `"1h 5m left"`, or `"ending"` once the guide has fallen behind the clock. */
internal fun remainingLabel(atMillis: Long, stopMillis: Long): String {
    val minutes = (stopMillis - atMillis) / 60_000L
    return when {
        minutes <= 0 -> "ending"
        minutes < 60 -> "${minutes}m left"
        minutes % 60 == 0L -> "${minutes / 60}h left"
        else -> "${minutes / 60}h ${minutes % 60}m left"
    }
}

/** How far through a programme [atMillis] is, clamped, and 0 for a zero-length one. */
internal fun progressFraction(atMillis: Long, start: Long, stop: Long): Float {
    val length = stop - start
    if (length <= 0L) return 0f
    return ((atMillis - start).toFloat() / length).coerceIn(0f, 1f)
}
