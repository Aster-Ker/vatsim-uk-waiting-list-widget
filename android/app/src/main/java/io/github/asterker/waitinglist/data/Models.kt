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
    // Your position each time it changed, oldest first, for the time estimate.
    val history: List<PositionSample> = emptyList(),
) {
    val key: String get() = "$department|$name"

    fun movedRecently(now: Long = System.currentTimeMillis()): Boolean =
        changedAt != null && now - changedAt < HIGHLIGHT_MILLIS

    companion object {
        const val HIGHLIGHT_MILLIS = 24L * 60 * 60 * 1000
    }
}

data class PositionSample(val at: Long, val position: Int)

enum class Status { Idle, Ok, LoginRequired, Error }

data class AppState(
    val lists: List<WaitingListEntry> = emptyList(),
    val lastChecked: Long? = null,
    val status: Status = Status.Idle,
    val message: String? = null,
    val checking: Boolean = false,
    val notify: Boolean = true,
    // Alarm sound (plays even on silent) rather than the normal notification sound.
    val alarmSound: Boolean = true,
    val intervalMinutes: Int = 60,
    val sessions: SessionsState = SessionsState(),
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

// A booking from the member's VATSIM UK calendar feed: ATC booking, mentoring session or exam.
data class SessionBooking(
    val uid: String,
    val kind: String,
    val position: String?,
    val start: Long,
    val end: Long,
    val mentor: String? = null,
    val examiner: String? = null,
    val student: String? = null,
    val url: String? = null,
) {
    // The feed names the student only when you're the mentor or examiner.
    val asInstructor: Boolean get() = student != null
    val title: String get() = listOfNotNull(position, kind).joinToString(" · ")
    val withWhom: String?
        get() = when {
            student != null -> "Student: $student"
            mentor != null -> "Mentor: $mentor"
            examiner != null -> "Examiner: $examiner"
            else -> null
        }

    fun phase(now: Long, remindMillis: Long): SessionPhase = when {
        now >= end -> SessionPhase.Finished
        now >= start -> SessionPhase.InProgress
        start - now <= remindMillis -> SessionPhase.StartingSoon
        else -> SessionPhase.Upcoming
    }
}

enum class SessionPhase { Upcoming, StartingSoon, InProgress, Finished }

// A row from "My Training History": a past mentoring session and whether its report is filed.
data class TrainingRecord(
    val id: String,
    val position: String,
    val date: String,
    val mentor: String,
    val status: String,
    val reportUrl: String?,
)

// A row from "My Exams": a practical or theory exam and its result.
data class ExamRecord(
    val id: String,
    val type: String,
    val exam: String,
    val position: String?,
    val result: String,
    val score: String?,
    val date: String,
    val reportUrl: String?,
) {
    val title: String get() = listOfNotNull(exam.ifBlank { null }, position?.ifBlank { null }).joinToString(" · ").ifEmpty { "$type exam" }
}

data class SessionsState(
    val feedUrl: String? = null,
    val bookings: List<SessionBooking> = emptyList(),
    val history: List<TrainingRecord> = emptyList(),
    val historyAvailable: Boolean = true,
    val exams: List<ExamRecord> = emptyList(),
    // When exam results were first read, so results already there don't trigger alerts.
    val examsChecked: Long? = null,
    val lastChecked: Long? = null,
    val message: String? = null,
    val alerts: Boolean = true,
    val remindMinutes: Int = 30,
) {
    fun upcoming(now: Long = System.currentTimeMillis()): List<SessionBooking> =
        bookings.filter { it.end > now }.sortedBy { it.start }
}

val ReminderChoices = listOf(10, 15, 30, 60, 120)
