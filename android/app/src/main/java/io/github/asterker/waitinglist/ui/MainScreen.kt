package io.github.asterker.waitinglist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.asterker.waitinglist.data.AppState
import io.github.asterker.waitinglist.data.IntervalChoices
import io.github.asterker.waitinglist.data.Status
import io.github.asterker.waitinglist.data.WaitingListEntry
import io.github.asterker.waitinglist.data.formatInterval
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MainScreen(
    state: AppState,
    canPinWidget: Boolean,
    onCheckNow: () -> Unit,
    onSignIn: () -> Unit,
    onOpenWebsite: () -> Unit,
    onNotifyChange: (Boolean) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onTestNotification: () -> Unit,
    onPinWidget: () -> Unit,
    onSignOut: () -> Unit,
) {
    val c = LocalPanel.current
    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Header(state)
        Spacer(Modifier.height(16.dp))

        when (state.status) {
            Status.LoginRequired -> {
                PanelAlert(c.warning, "Sign in needed", "Your vatsim.uk session has expired.", "Sign in", onSignIn)
                Spacer(Modifier.height(12.dp))
            }
            Status.Error -> if (!state.checking) {
                PanelAlert(c.destructive, "Couldn't check vatsim.uk", state.message ?: "Something went wrong.", "Retry", onCheckNow)
                Spacer(Modifier.height(12.dp))
            }
            else -> Unit
        }

        PanelCard {
            if (state.lists.isEmpty()) {
                val text = when {
                    state.status == Status.Ok -> "You aren't on any waiting lists."
                    state.checking -> "Checking vatsim.uk…"
                    else -> "No data yet. Sign in to see your waiting lists."
                }
                Text(text, color = c.muted, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
            }
            state.lists.forEachIndexed { i, entry ->
                if (i > 0) Divider()
                ListRow(entry)
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelButton(
                if (state.checking) "Checking…" else "Check now",
                onCheckNow,
                style = ButtonStyle.Primary,
                enabled = !state.checking,
                modifier = Modifier.weight(1f),
            )
            PanelButton("Open vatsim.uk", onOpenWebsite, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        val checked = state.lastChecked?.let { "Last checked " + SimpleDateFormat("EEE d MMM, HH:mm", Locale.UK).format(Date(it)) }
        Text(
            listOfNotNull(checked, "checks every ${formatInterval(state.intervalMinutes)}").joinToString(" · "),
            color = c.muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 4.dp),
        )

        Spacer(Modifier.height(24.dp))
        SectionLabel("Notifications")
        PanelCard {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Position changes", color = c.foreground, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("Notify me when a waiting list position moves", color = c.muted, fontSize = 12.5.sp)
                }
                Switch(
                    checked = state.notify,
                    onCheckedChange = onNotifyChange,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = c.primary,
                        checkedThumbColor = c.primaryForeground,
                        uncheckedTrackColor = c.secondary,
                        uncheckedBorderColor = c.border,
                        uncheckedThumbColor = c.muted,
                    ),
                )
            }
            Divider()
            Box(Modifier.padding(12.dp)) {
                PanelButton("Send a test notification", onTestNotification, modifier = Modifier.fillMaxWidth())
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("Check every")
        val selected = IntervalChoices.indexOf(state.intervalMinutes).coerceAtLeast(0)
        Segmented(
            options = IntervalChoices.map { if (it < 60) "${it}m" else "${it / 60}h" },
            selected = selected,
            onSelect = { onIntervalChange(IntervalChoices[it]) },
        )
        Text(
            "VATSIM UK updates waiting lists about once a day, so hourly is plenty.",
            color = c.muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        )

        if (canPinWidget) {
            Spacer(Modifier.height(24.dp))
            SectionLabel("Home screen")
            PanelCard {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Waiting list widget", color = c.foreground, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("Resize it small for your best position, or larger for every list", color = c.muted, fontSize = 12.5.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    PanelButton("Add", onPinWidget)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("Account")
        PanelCard {
            Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                PanelButton("Sign in again", onSignIn, style = ButtonStyle.Ghost, modifier = Modifier.weight(1f))
                PanelButton("Sign out", onSignOut, style = ButtonStyle.Destructive, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Unofficial app. Not affiliated with VATSIM or VATSIM UK.",
            color = c.muted,
            fontSize = 11.5.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun Header(state: AppState) {
    val c = LocalPanel.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Waiting lists", color = c.foreground, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text("VATSIM UK", color = c.muted, fontSize = 14.sp)
        }
        when {
            state.checking -> RoleBadge("Checking", c.info, dot = true)
            state.status == Status.Ok -> RoleBadge("Up to date", c.success, dot = true)
            state.status == Status.LoginRequired -> RoleBadge("Sign in", c.warning, dot = true)
            state.status == Status.Error -> RoleBadge("Error", c.destructive, dot = true)
            else -> RoleBadge("Starting", c.info, dot = true)
        }
    }
}

@Composable
private fun ListRow(entry: WaitingListEntry) {
    val c = LocalPanel.current
    val recent = entry.movedRecently()
    val highlight = if ((entry.change ?: 0) < 0) c.destructive else c.success
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (recent) Modifier.background(highlight.tint) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(64.dp)
                .background(if (recent) highlight.solid else c.card.copy(alpha = 0f))
        )
        Row(
            Modifier.padding(start = 13.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(entry.name, color = c.foreground, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Badge(entry.department, c.foreground, c.secondary)
                    entry.theoryPassed?.let { passed ->
                        if (passed) RoleBadge("Theory ✓", c.success) else RoleBadge("Theory ✗", c.warning)
                    }
                    if (entry.joined.isNotEmpty()) {
                        Text(
                            "Joined ${entry.joined}",
                            color = c.muted,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    entry.position?.let { "#$it" } ?: "—",
                    color = if (entry.position == null) c.muted else c.foreground,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                val change = entry.change
                when {
                    entry.position == null -> Text("Not ranked", color = c.muted, fontSize = 12.sp)
                    change != null && change > 0 ->
                        Text("▲ $change", color = c.success.foreground, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    change != null && change < 0 ->
                        Text("▼ ${-change}", color = c.destructive.foreground, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    else -> Text("No change", color = c.muted, fontSize = 12.sp)
                }
            }
        }
    }
}
