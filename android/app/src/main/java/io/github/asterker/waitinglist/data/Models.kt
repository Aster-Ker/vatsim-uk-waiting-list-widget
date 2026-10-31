package io.github.asterker.waitinglist.data

data class WaitingListEntry(
    val department: String,
    val name: String,
    val position: Int?,
    val joined: String,
    val onRoster: Boolean?,
    val theoryPassed: Boolean?,
    // Places gained (+) or lost (-) at the most recent position change.
    val change: Int? = null,
    // When that change was seen, so recently moved lists can be highlighted.
    val changedAt: Long? = null,
) {
    val key: String get() = "$department|$name"

    fun movedRecently(now: Long = System.currentTimeMillis()): Boolean =
        changedAt != null && now - changedAt < HIGHLIGHT_MILLIS

    companion object {
        const val HIGHLIGHT_MILLIS = 24L * 60 * 60 * 1000
    }
}

enum class Status { Idle, Ok, LoginRequired, Error }

data class AppState(
    val lists: List<WaitingListEntry> = emptyList(),
    val lastChecked: Long? = null,
    val status: Status = Status.Idle,
    val message: String? = null,
    val checking: Boolean = false,
    val notify: Boolean = true,
    val intervalMinutes: Int = 60,
) {
    val bestPosition: WaitingListEntry?
        get() = lists.filter { it.position != null }.minByOrNull { it.position!! }
}

sealed interface FetchResult {
    data class Ok(val lists: List<WaitingListEntry>) : FetchResult
    data object LoginRequired : FetchResult
    data class Error(val message: String) : FetchResult
}

val IntervalChoices = listOf(15, 30, 60, 180, 360)

fun formatInterval(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes == 60 -> "1 hour"
    else -> "${minutes / 60} hours"
}
