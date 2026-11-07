package io.github.asterker.waitinglist.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import io.github.asterker.waitinglist.widget.WaitingListWidget
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// One sync of bookings and training history, plus what changed since the last one.
object SessionsSync {

    data class Notice(val title: String, val body: String, val url: String? = null)

    data class Outcome(val state: SessionsState, val notices: List<Notice>, val loginRequired: Boolean = false)

    fun sync(context: Context, previous: SessionsState): Outcome {
        val now = System.currentTimeMillis()
        var message: String? = null

        // Bookings, from the calendar feed. Look the link up again if it was reset on the dashboard.
        var feedUrl = previous.feedUrl
        var bookings: List<SessionBooking>? = null
        for (attempt in 0 until 2) {
            if (feedUrl == null) {
                when (val found = SessionsClient.findFeedUrl(context)) {
                    is SessionsClient.Result.Ok -> feedUrl = found.value
                    SessionsClient.Result.LoginRequired -> return Outcome(previous, emptyList(), loginRequired = true)
                    is SessionsClient.Result.Error -> message = found.message
                }
            }
            val url = feedUrl ?: break
            when (val fetched = SessionsClient.fetchBookings(url)) {
                is SessionsClient.Result.Ok -> {
                    if (fetched.value == null) feedUrl = null else bookings = fetched.value
                }
                SessionsClient.Result.LoginRequired -> Unit
                is SessionsClient.Result.Error -> message = fetched.message
            }
            if (bookings != null || feedUrl != null) break
        }

        // Training history, for mentor reports.
        var history: List<TrainingRecord>? = null
        var historyAvailable = previous.historyAvailable
        when (val fetched = SessionsClient.fetchHistory(context)) {
            is SessionsClient.Result.Ok -> {
                historyAvailable = fetched.value != null
                history = fetched.value ?: emptyList()
            }
            SessionsClient.Result.LoginRequired -> return Outcome(previous, emptyList(), loginRequired = true)
            is SessionsClient.Result.Error -> message = message ?: fetched.message
        }

        // Exam results, from My Exams.
        var exams: List<ExamRecord>? = null
        when (val fetched = SessionsClient.fetchExams(context)) {
            is SessionsClient.Result.Ok -> exams = fetched.value ?: emptyList()
            SessionsClient.Result.LoginRequired -> return Outcome(previous, emptyList(), loginRequired = true)
            is SessionsClient.Result.Error -> message = message ?: fetched.message
        }

        val firstSync = previous.lastChecked == null
        val notices = mutableListOf<Notice>()
        if (bookings != null && !firstSync) notices += bookingChanges(previous.bookings, bookings, now)
        if (history != null && !firstSync && previous.historyAvailable) notices += reportChanges(previous.history, history)
        if (exams != null && previous.examsChecked != null) notices += examChanges(previous.exams, exams)

        val state = previous.copy(
            feedUrl = feedUrl,
            bookings = bookings ?: previous.bookings,
            history = history ?: previous.history,
            historyAvailable = historyAvailable,
            exams = exams ?: previous.exams,
            examsChecked = if (exams != null) previous.examsChecked ?: now else previous.examsChecked,
            lastChecked = if (bookings != null || history != null) now else previous.lastChecked,
            message = message,
        )
        return Outcome(state, notices)
    }

    private fun bookingChanges(old: List<SessionBooking>, new: List<SessionBooking>, now: Long): List<Notice> {
        val notices = mutableListOf<Notice>()
        val before = old.associateBy { it.uid }
        val after = new.associateBy { it.uid }

        for (booking in new.filter { it.start > now }) {
            val was = before[booking.uid]
            when {
                was == null -> notices += Notice(
                    "New ${booking.kind.lowercase()} booked",
                    listOfNotNull(booking.position, timeRange(booking), booking.withWhom).joinToString(" · "),
                )
                was.start != booking.start || was.end != booking.end -> notices += Notice(
                    "${booking.kind} moved",
                    listOfNotNull(booking.position, "now ${timeRange(booking)}", "was ${day(was.start)} ${time(was.start)}").joinToString(" · "),
                )
            }
        }
        for (removed in old.filter { it.start > now && it.uid !in after }) {
            notices += Notice(
                "${removed.kind} removed",
                listOfNotNull(removed.position, timeRange(removed)).joinToString(" · ") + " is no longer booked",
            )
        }
        return notices
    }

    private fun reportChanges(old: List<TrainingRecord>, new: List<TrainingRecord>): List<Notice> {
        val hadReport = old.filter { it.reportUrl != null }.flatMap { listOf(it.id, "${it.position}|${it.date}") }.toSet()
        return new
            .filter { it.reportUrl != null && it.id !in hadReport && "${it.position}|${it.date}" !in hadReport }
            .map { record ->
                Notice(
                    "Mentor feedback ready",
                    listOfNotNull(
                        record.position.ifBlank { null },
                        record.date.ifBlank { null },
                        record.mentor.ifBlank { null }?.let { "with $it" },
                    ).joinToString(" · ") + ". Tap to read the report.",
                    record.reportUrl,
                )
            }
    }

    private fun examChanges(old: List<ExamRecord>, new: List<ExamRecord>): List<Notice> {
        val known = old.map { it.id }.toSet()
        return new.filter { it.id !in known && it.result.isNotBlank() }.map { exam ->
            Notice(
                "Exam result: ${exam.result}",
                listOfNotNull("${exam.type} exam", exam.title, exam.score, exam.date.ifBlank { null }).joinToString(" · ") +
                    if (exam.reportUrl != null) ". Tap to read the report." else "",
                exam.reportUrl,
            )
        }
    }

    fun timeRange(b: SessionBooking): String = "${day(b.start)}, ${time(b.start)}–${time(b.end)}"
    fun day(at: Long): String = SimpleDateFormat("EEE d MMM", Locale.UK).format(Date(at))
    fun time(at: Long): String = SimpleDateFormat("HH:mm", Locale.UK).format(Date(at))
}

// Reminder alarms before each upcoming session, and widget refreshes when sessions start and end.
object SessionReminders {
    private const val REMIND = "remind"
    private const val START = "start"
    private const val END = "end"

    fun reschedule(context: Context, old: List<SessionBooking>, state: SessionsState) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        for (booking in old + state.bookings) {
            for (kind in listOf(REMIND, START, END)) {
                pending(context, kind, booking.uid, create = false)?.let { alarms.cancel(it) }
            }
        }

        val now = System.currentTimeMillis()
        for (booking in state.bookings.filter { it.end > now }) {
            val remindAt = booking.start - state.remindMinutes * 60_000L
            if (state.alerts && remindAt > now) set(alarms, remindAt, pending(context, REMIND, booking.uid, create = true)!!, exact = true)
            if (booking.start > now) set(alarms, booking.start, pending(context, START, booking.uid, create = true)!!, exact = false)
            set(alarms, booking.end, pending(context, END, booking.uid, create = true)!!, exact = false)
        }
    }

    private fun set(alarms: AlarmManager, at: Long, intent: PendingIntent, exact: Boolean) {
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (exact && canExact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
    }

    private fun pending(context: Context, kind: String, uid: String, create: Boolean): PendingIntent? {
        val intent = Intent(context, SessionAlarmReceiver::class.java)
            .setData(Uri.Builder().scheme("session").authority(kind).appendPath(uid).build())
        val flags = PendingIntent.FLAG_IMMUTABLE or (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    fun handle(context: Context, intent: Intent) {
        val data = intent.data ?: return
        Store.init(context)
        val state = Store.state.value.sessions
        val booking = state.bookings.firstOrNull { it.uid == data.lastPathSegment }
        if (data.authority == REMIND && booking != null && state.alerts) {
            Notifications.showSessionReminder(context, booking, state.remindMinutes)
        }
        WaitingListWidget.updateAll(context)
    }
}

class SessionAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = SessionReminders.handle(context, intent)
}

// Alarms don't survive a reboot or app update, so set them again.
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        SessionReminders.reschedule(context, emptyList(), Store.state.value.sessions)
        WaitingListWidget.updateAll(context)
    }
}
