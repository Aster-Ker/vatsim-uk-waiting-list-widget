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

@Composable
fun NextSession(booking: SessionBooking, phase: SessionPhase, now: Long, onOpenPage: (String) -> Unit) {
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
