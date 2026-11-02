package io.github.asterker.waitinglist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.asterker.waitinglist.data.SessionBooking
import io.github.asterker.waitinglist.data.SessionPhase
import io.github.asterker.waitinglist.data.SessionsState
import io.github.asterker.waitinglist.data.SessionsSync
import io.github.asterker.waitinglist.data.TrainingRecord
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// The Sessions page: the next session with a countdown, then every session event: upcoming
// bookings grouped by day, and past ones with their report status.
@Composable
fun SessionsPage(
    sessions: SessionsState,
    now: Long,
    onOpenPage: (String) -> Unit,
    onAddToCalendar: (SessionBooking) -> Unit,
) {
    val c = LocalPanel.current
    // Null until you pick a tab; until then, open on Past when there's nothing to show under Upcoming.
    var picked by rememberSaveable { mutableStateOf<Int?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    val remindMillis = sessions.remindMinutes * 60_000L
    val upcoming = sessions.upcoming(now)
    val later = upcoming.drop(1)
    val tab = picked ?: if (later.isEmpty()) 1 else 0

    Column {
        SectionLabel("Next session")
        PanelCard {
            val next = upcoming.firstOrNull()
            if (next == null) {
                val text = when {
                    sessions.lastChecked == null && sessions.message == null -> "Checking your bookings…"
                    sessions.feedUrl == null && sessions.message != null -> sessions.message
                    else -> "No upcoming bookings."
                }
                Text(text, color = c.muted, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
            } else {
                NextSession(next, next.phase(now, remindMillis), now, onOpenPage)
            }
        }
        if (sessions.message != null && sessions.feedUrl != null) {
            Text(
                sessions.message,
                color = c.destructive.foreground,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
        Segmented(listOf("Upcoming", "Past"), tab, { picked = it })
        Spacer(Modifier.height(16.dp))

        if (tab == 0) {
            if (later.isEmpty()) {
                EmptyCard(if (upcoming.isEmpty()) "No upcoming bookings." else "Nothing else booked after your next session.")
            }
            later.groupBy { dayLabel(it.start, now) }.forEach { (day, bookings) ->
                SectionLabel(day)
                PanelCard {
                    bookings.forEachIndexed { i, booking ->
                        if (i > 0) Divider()
                        EventRow(
                            booking = booking,
                            badge = { PhaseBadge(booking, booking.phase(now, remindMillis), now) },
                            expanded = expanded == booking.uid,
                            onClick = { expanded = if (expanded == booking.uid) null else booking.uid },
                        ) {
                            booking.url?.let { PanelButton("Open on vatsim.uk", { onOpenPage(it) }, Modifier.weight(1f)) }
                            PanelButton("Add to calendar", { onAddToCalendar(booking) }, Modifier.weight(1f))
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        } else {
            val past = sessions.bookings.filter { it.end <= now }.sortedByDescending { it.start }
            val matched = mutableSetOf<String>()
            if (past.isEmpty() && sessions.history.isEmpty()) {
                EmptyCard("No past sessions in the last 30 days.")
            }
            if (past.isNotEmpty()) {
                SectionLabel("Last 30 days")
                PanelCard {
                    past.forEachIndexed { i, booking ->
                        if (i > 0) Divider()
                        val record = sessions.history.firstOrNull { matches(it, booking) }
                        record?.let { matched += it.id }
                        EventRow(
                            booking = booking,
                            badge = { record?.let { StatusBadge(it) } },
                            expanded = expanded == booking.uid,
                            onClick = { expanded = if (expanded == booking.uid) null else booking.uid },
                        ) {
                            record?.reportUrl?.let { PanelButton("Read feedback", { onOpenPage(it) }, Modifier.weight(1f), ButtonStyle.Primary) }
                            booking.url?.let { PanelButton("Open on vatsim.uk", { onOpenPage(it) }, Modifier.weight(1f)) }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            // Training history goes back further than the calendar feed's 30 days.
            val earlier = sessions.history.filter { it.id !in matched }
            if (earlier.isNotEmpty()) {
                SectionLabel(if (past.isEmpty()) "Training history" else "Earlier training")
                PanelCard {
                    earlier.forEachIndexed { i, record ->
                        if (i > 0) Divider()
                        HistoryEventRow(record, onOpenPage)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyCard(text: String) {
    PanelCard {
        Text(text, color = LocalPanel.current.muted, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun EventRow(
    booking: SessionBooking,
    badge: @Composable () -> Unit,
    expanded: Boolean,
    onClick: () -> Unit,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val c = LocalPanel.current
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(56.dp)) {
                Text(SessionsSync.time(booking.start), color = c.foreground, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(SessionsSync.time(booking.end), color = c.muted, fontSize = 12.sp)
            }
            Column(Modifier.weight(1f)) {
                Text(booking.position ?: booking.kind, color = c.foreground, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(booking.kind, color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            badge()
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            Column(Modifier.padding(start = 56.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(SessionsSync.timeRange(booking), color = c.foreground, fontSize = 13.sp)
                booking.withWhom?.let { Text(it, color = c.muted, fontSize = 13.sp) }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
        }
    }
}

@Composable
private fun HistoryEventRow(record: TrainingRecord, onOpenPage: (String) -> Unit) {
    val c = LocalPanel.current
    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(record.position.ifBlank { "Session" }, color = c.foreground, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(record.date, record.mentor).filter { it.isNotBlank() }.joinToString(" · "),
                color = c.muted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        StatusBadge(record)
        record.reportUrl?.let {
            Spacer(Modifier.width(8.dp))
            PanelButton("Read", { onOpenPage(it) })
        }
    }
}

@Composable
private fun PhaseBadge(booking: SessionBooking, phase: SessionPhase, now: Long) {
    val c = LocalPanel.current
    when (phase) {
        SessionPhase.InProgress -> RoleBadge("In progress", c.success, dot = true)
        SessionPhase.StartingSoon -> RoleBadge("In ${relative(booking.start - now)}", c.warning, dot = true)
        SessionPhase.Upcoming -> RoleBadge("In ${relative(booking.start - now)}", c.info)
        SessionPhase.Finished -> RoleBadge("Finished", c.info)
    }
}

@Composable
private fun StatusBadge(record: TrainingRecord) {
    val c = LocalPanel.current
    when (record.status) {
        "Completed" -> RoleBadge("Report filed", c.success)
        "Pending" -> RoleBadge("Awaiting report", c.info)
        "Cancelled" -> RoleBadge("Cancelled", c.destructive)
        "No Show" -> RoleBadge("No show", c.warning)
        else -> RoleBadge(record.status, c.info)
    }
}

// Training history shows dates as "03. 11. 2026 18:00" in UK time.
private fun matches(record: TrainingRecord, booking: SessionBooking): Boolean {
    if (booking.position == null || !record.position.equals(booking.position, ignoreCase = true)) return false
    val format = SimpleDateFormat("dd. MM. yyyy", Locale.UK).apply { timeZone = TimeZone.getTimeZone("Europe/London") }
    return record.date.startsWith(format.format(Date(booking.start)))
}

private fun dayLabel(at: Long, now: Long): String {
    val day = Calendar.getInstance().apply { timeInMillis = at }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    val tomorrow = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
    return when {
        sameDay(day, today) -> "Today"
        sameDay(day, tomorrow) -> "Tomorrow"
        else -> SimpleDateFormat("EEEE d MMMM", Locale.UK).format(Date(at))
    }
}
