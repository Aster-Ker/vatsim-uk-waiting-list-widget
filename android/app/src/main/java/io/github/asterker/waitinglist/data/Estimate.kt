package io.github.asterker.waitinglist.data

import kotlin.math.roundToInt

// Estimates how long until you reach #1 from how fast your own position has moved.
// VATSIM UK doesn't publish list speeds, so this only knows what the app has seen.
sealed interface Estimate {
    // Days until #1 at the current pace, and the pace in places per month. fromJoin means the
    // pace was measured from the position you entered for the day you joined.
    data class Ready(val days: Double, val placesPerMonth: Double, val fromJoin: Boolean = false) : Estimate
    data object Next : Estimate
    // Not enough movement seen yet; tracking began at `since`.
    data class Gathering(val since: Long) : Estimate
    data object Stalled : Estimate

    companion object {
        private const val DAY = 24L * 60 * 60 * 1000
        private const val WINDOW_DAYS = 180
        private const val MIN_DAYS = 7.0

        fun of(entry: WaitingListEntry, now: Long = System.currentTimeMillis()): Estimate? {
            val position = entry.position ?: return null
            if (position <= 1) return Next
            // Your join position, if entered, counts as a sample on the day you joined.
            val join = entry.joinPosition?.let { joinPosition ->
                joinedAt(entry.joined)?.let { PositionSample(it, joinPosition) }
            }
            val samples = (entry.history + listOfNotNull(join)).sortedBy { it.at }
            val first = samples.firstOrNull() ?: return null

            // Pace over the last six months, counting the quiet weeks since the last move. The join
            // position is the one known point before then, so from it the pace covers the whole wait.
            val windowStart = now - WINDOW_DAYS * DAY
            val baseline = samples.lastOrNull { it.at <= windowStart } ?: first
            val from = if (baseline === join) baseline.at else maxOf(baseline.at, windowStart)
            val days = (now - from).toDouble() / DAY
            val moved = baseline.position - position

            return when {
                days < MIN_DAYS -> Gathering(first.at)
                moved <= 0 -> if (moved < 0 || days >= 60) Stalled else Gathering(first.at)
                else -> {
                    val perDay = moved / days
                    Ready((position - 1) / perDay, perDay * 30.44, fromJoin = baseline === join)
                }
            }
        }

        // The join date as vatsim.uk shows it, e.g. "03 Feb 2026". English month names, as PHP prints them.
        fun joinedAt(joined: String): Long? = runCatching {
            java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.US).apply { isLenient = false }.parse(joined.trim())?.time
        }.getOrNull()

        // Records the position whenever it changes, keeping a year of history.
        fun record(history: List<PositionSample>, position: Int?, now: Long): List<PositionSample> {
            if (position == null || history.lastOrNull()?.position == position) return history
            return (history + PositionSample(now, position)).filter { now - it.at <= 366 * DAY }.takeLast(200)
        }

        fun formatDays(days: Double): String = when {
            days < 1.5 -> "about a day"
            days < 14 -> "about ${days.roundToInt()} days"
            days < 60 -> "about ${(days / 7).roundToInt()} weeks"
            days < 730 -> "about ${(days / 30.44).roundToInt().coerceAtLeast(2)} months"
            else -> "over 2 years"
        }

        // Short form for widgets: "~3 mo", "~5 wk", "~4 d".
        fun formatShort(days: Double): String = when {
            days < 14 -> "~${days.roundToInt().coerceAtLeast(1)} d"
            days < 60 -> "~${(days / 7).roundToInt()} wk"
            days < 730 -> "~${(days / 30.44).roundToInt().coerceAtLeast(2)} mo"
            else -> "2 yr+"
        }
    }
}
