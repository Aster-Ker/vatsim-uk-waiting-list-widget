package io.github.asterker.waitinglist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

private const val MAX_UPCOMING = 4
private const val MAX_HISTORY = 5

@Composable
fun SessionsSection(sessions: SessionsState, now: Long, onOpenPage: (String) -> Unit, onShowAll: () -> Unit) {
    val c = LocalPanel.current
    val upcoming = sessions.upcoming(now)
    val remindMillis = sessions.remindMinutes * 60_000L

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

    val later = upcoming.drop(1).take(MAX_UPCOMING)
    if (later.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        PanelCard {
            later.forEachIndexed { i, booking ->
                if (i > 0) Divider()
                BookingRow(booking, now)
            }
        }
    }

    if (sessions.historyAvailable && sessions.history.isNotEmpty()) {
        Spacer(Modifier.height(24.dp))
        SectionLabel("Recent training")
        PanelCard {
            sessions.history.take(MAX_HISTORY).forEachIndexed { i, record ->
                if (i > 0) Divider()
                HistoryRow(record, onOpenPage)
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    PanelButton("All sessions", onShowAll, modifier = Modifier.fillMaxWidth())

    if (sessions.message != null && sessions.feedUrl != null) {
        Text(
            sessions.message,
            color = c.destructive.foreground,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        )
    }
}

@Composable
private fun NextSession(booking: SessionBooking, phase: SessionPhase, now: Long, onOpenPage: (String) -> Unit) {
    val c = LocalPanel.current
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Badge(booking.kind, c.foreground, c.secondary)
            when (phase) {
                SessionPhase.InProgress -> RoleBadge("In progress · ends ${SessionsSync.time(booking.end)}", c.success, dot = true)
                SessionPhase.StartingSoon -> RoleBadge("Starts in ${relative(booking.start - now)}", c.warning, dot = true)
                SessionPhase.Upcoming -> RoleBadge("In ${relative(booking.start - now)}", c.info)
                SessionPhase.Finished -> RoleBadge("Finished", c.info)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            booking.position ?: booking.kind,
            color = c.foreground,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(SessionsSync.timeRange(booking), color = c.foreground, fontSize = 14.sp)
        booking.withWhom?.let { Text(it, color = c.muted, fontSize = 13.sp) }
        booking.url?.let { url ->
            Spacer(Modifier.height(12.dp))
            PanelButton("Open on vatsim.uk", { onOpenPage(url) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun BookingRow(booking: SessionBooking, now: Long) {
    val c = LocalPanel.current
    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(booking.title, color = c.foreground, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(SessionsSync.timeRange(booking), booking.withWhom).joinToString(" · "),
                color = c.muted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text("in ${relative(booking.start - now)}", color = c.muted, fontSize = 12.sp)
    }
}

@Composable
private fun HistoryRow(record: TrainingRecord, onOpenPage: (String) -> Unit) {
    val c = LocalPanel.current
    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    record.position.ifBlank { "Session" },
                    color = c.foreground,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                when (record.status) {
                    "Completed" -> RoleBadge("Report filed", c.success)
                    "Pending" -> RoleBadge("Awaiting report", c.info)
                    "Cancelled" -> RoleBadge("Cancelled", c.destructive)
                    "No Show" -> RoleBadge("No show", c.warning)
                    else -> RoleBadge(record.status, c.info)
                }
            }
            Text(
                listOf(record.date, record.mentor).filter { it.isNotBlank() }.joinToString(" · "),
                color = c.muted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        record.reportUrl?.let { url ->
            Spacer(Modifier.width(12.dp))
            PanelButton("Read", { onOpenPage(url) })
        }
    }
}

fun relative(millis: Long): String {
    val minutes = (millis.coerceAtLeast(0) + 59_999) / 60_000
    return when {
        minutes < 60 -> "$minutes min"
        minutes < 24 * 60 -> {
            val h = minutes / 60
            val m = minutes % 60
            if (m == 0L) "$h h" else "$h h $m min"
        }
        else -> {
            val days = minutes / (24 * 60)
            if (days == 1L) "1 day" else "$days days"
        }
    }
}
