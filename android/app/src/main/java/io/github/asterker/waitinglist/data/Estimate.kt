package io.github.asterker.waitinglist.data

import kotlin.math.roundToInt

// Estimates how long until you reach #1 from how fast your own position has moved.
// VATSIM UK doesn't publish list speeds, so this only knows what the app has seen.
sealed interface Estimate {
    // Days until #1 at the current pace, and the pace in places per month.
    data class Ready(val days: Double, val placesPerMonth: Double) : Estimate
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
            val samples = entry.history.sortedBy { it.at }
            val first = samples.firstOrNull() ?: return null

            // Pace over the last six months, counting the quiet weeks since the last move.
            val windowStart = now - WINDOW_DAYS * DAY
            val baseline = samples.lastOrNull { it.at <= windowStart } ?: first
            val from = maxOf(baseline.at, windowStart)
            val days = (now - from).toDouble() / DAY
            val moved = baseline.position - position

            return when {
                days < MIN_DAYS -> Gathering(first.at)
                moved <= 0 -> if (moved < 0 || days >= 60) Stalled else Gathering(first.at)
                else -> {
                    val perDay = moved / days
                    Ready((position - 1) / perDay, perDay * 30.44)
                }
            }
        }

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
