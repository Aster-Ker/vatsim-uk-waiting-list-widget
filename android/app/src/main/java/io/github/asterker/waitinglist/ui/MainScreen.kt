package io.github.asterker.waitinglist.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.asterker.waitinglist.data.AppState
import io.github.asterker.waitinglist.data.IntervalChoices
import io.github.asterker.waitinglist.data.ReminderChoices
import io.github.asterker.waitinglist.data.SessionBooking
import io.github.asterker.waitinglist.data.Status
import io.github.asterker.waitinglist.data.WaitingListEntry
import io.github.asterker.waitinglist.data.formatInterval
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Everything the pages can ask the activity to do.
class Actions(
    val checkNow: () -> Unit,
    val signIn: () -> Unit,
    val openWebsite: () -> Unit,
    val openPage: (String) -> Unit,
    val addToCalendar: (SessionBooking) -> Unit,
    val setNotify: (Boolean) -> Unit,
    val setAlarmSound: (Boolean) -> Unit,
    val openSoundSettings: () -> Unit,
    val testNotification: () -> Unit,
    val setSessionAlerts: (Boolean) -> Unit,
    val setRemindMinutes: (Int) -> Unit,
    val setInterval: (Int) -> Unit,
    val pinWidget: (WidgetKind) -> Unit,
    val signOut: () -> Unit,
)

enum class WidgetKind(val title: String, val description: String) {
    NextSession("Next session", "Your next booking with a countdown"),
    BestPosition("Best position", "Your best waiting list position, 1×1 to 2×2"),
    PositionsStrip("Positions strip", "Several waiting lists side by side in one row"),
    WaitingLists("Waiting lists", "Every list; make it taller to see more"),
}

private enum class Page(val label: String, val title: String, val icon: ImageVector) {
    Sessions("Sessions", "Sessions", PanelIcons.Calendar),
    WaitingLists("Lists", "Waiting lists", PanelIcons.ListOrdered),
    Settings("Settings", "Settings", PanelIcons.Settings),
}

@Composable
fun MainScreen(state: AppState, canPinWidget: Boolean, actions: Actions) {
    val c = LocalPanel.current
    // Swipe left or right anywhere to change page, or tap the bar.
    val pager = rememberPagerState { Page.entries.size }
    val scope = rememberCoroutineScope()
    fun goTo(page: Page) {
        scope.launch { pager.animateScrollToPage(page.ordinal, animationSpec = tween(220)) }
    }
    val page = Page.entries[pager.currentPage]
    // Back from another page returns to Sessions before leaving the app.
    BackHandler(enabled = page != Page.Sessions) { goTo(Page.Sessions) }

    // Ticks so countdowns stay current while the screen is open.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }

    Column(Modifier.fillMaxSize().background(c.background)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            beyondViewportPageCount = 1,
            verticalAlignment = Alignment.Top,
        ) { index ->
            val shown = Page.entries[index]
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Header(shown.title, state)
                Spacer(Modifier.height(16.dp))

                if (state.status == Status.LoginRequired && shown != Page.Settings) {
                    PanelAlert(c.warning, "Sign in needed", "Your vatsim.uk session has expired.", "Sign in", actions.signIn)
                    Spacer(Modifier.height(16.dp))
                }

                when (shown) {
                    Page.Sessions -> SessionsPage(state.sessions, now, actions.openPage, actions.addToCalendar)
                    Page.WaitingLists -> WaitingListsPage(state, actions)
                    Page.Settings -> SettingsPage(state, canPinWidget, actions)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
        // Follows the swipe: the tab switches as soon as the new page is more than halfway in.
        BottomBar(Page.entries[pager.targetPage]) { goTo(it) }
    }
}

// A compact bar: the selected tab's pill grows in behind its icon and its label darkens.
@Composable
private fun BottomBar(current: Page, onSelect: (Page) -> Unit) {
    val c = LocalPanel.current
    Column(Modifier.fillMaxWidth().background(c.card)) {
        Divider()
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Page.entries.forEach { page ->
                val selected = page == current
                val pillWidth by animateDpAsState(if (selected) 52.dp else 24.dp, tween(140), label = "pill")
                val pillColor by animateColorAsState(if (selected) c.secondary else c.card, tween(140), label = "pillColor")
                val tint by animateColorAsState(if (selected) c.foreground else c.muted, tween(140), label = "tint")
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(page) }
                        .padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .width(pillWidth)
                            .height(28.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(pillColor),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(page.icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                    }
                    Text(
                        page.label,
                        color = tint,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, state: AppState) {
    val c = LocalPanel.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.foreground, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
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

// ---------- Waiting lists page ----------

@Composable
private fun WaitingListsPage(state: AppState, actions: Actions) {
    val c = LocalPanel.current
    if (state.status == Status.Error && !state.checking) {
        PanelAlert(c.destructive, "Couldn't check vatsim.uk", state.message ?: "Something went wrong.", "Retry", actions.checkNow)
        Spacer(Modifier.height(12.dp))
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
            actions.checkNow,
            style = ButtonStyle.Primary,
            enabled = !state.checking,
            modifier = Modifier.weight(1f),
        )
        PanelButton("Open vatsim.uk", actions.openWebsite, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
    val checked = state.lastChecked?.let { "Last checked " + SimpleDateFormat("EEE d MMM, HH:mm", Locale.UK).format(Date(it)) }
    Text(
        listOfNotNull(checked, "checks every ${formatInterval(state.intervalMinutes)}").joinToString(" · "),
        color = c.muted,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 4.dp),
    )
}

// ---------- Settings page ----------

@Composable
private fun SettingsPage(state: AppState, canPinWidget: Boolean, actions: Actions) {
    val c = LocalPanel.current

    SectionLabel("Waiting list alerts")
    PanelCard {
        SettingRow("Position changes", "Notify me when a waiting list position moves") {
            PanelSwitch(state.notify, actions.setNotify)
        }
        Divider()
        SettingRow(
            "Alarm sound",
            if (state.alarmSound) "Rings with your alarm sound and vibrates, even on silent"
            else "Uses your normal notification sound",
        ) {
            PanelSwitch(state.alarmSound, actions.setAlarmSound, enabled = state.notify)
        }
        Divider()
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelButton("Send a test", actions.testNotification, modifier = Modifier.weight(1f))
            PanelButton("Sound settings", actions.openSoundSettings, modifier = Modifier.weight(1f))
        }
    }

    Spacer(Modifier.height(24.dp))
    SectionLabel("Session reminders")
    PanelCard {
        SettingRow(
            "Session alerts",
            "A reminder before each booked session, plus alerts when bookings change or a mentor files feedback",
        ) {
            PanelSwitch(state.sessions.alerts, actions.setSessionAlerts)
        }
        Divider()
        Column(Modifier.padding(16.dp)) {
            Text("Remind me before a session", color = c.foreground, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            Segmented(
                options = ReminderChoices.map { if (it < 60) "${it}m" else "${it / 60}h" },
                selected = ReminderChoices.indexOf(state.sessions.remindMinutes).coerceAtLeast(0),
                onSelect = { actions.setRemindMinutes(ReminderChoices[it]) },
            )
        }
    }

    Spacer(Modifier.height(24.dp))
    SectionLabel("Check every")
    Segmented(
        options = IntervalChoices.map { if (it < 60) "${it}m" else "${it / 60}h" },
        selected = IntervalChoices.indexOf(state.intervalMinutes).coerceAtLeast(0),
        onSelect = { actions.setInterval(IntervalChoices[it]) },
    )
    Text(
        "Applies to waiting lists, bookings and feedback. Reminder alarms are set separately, so they ring on time.",
        color = c.muted,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )

    if (canPinWidget) {
        Spacer(Modifier.height(24.dp))
        SectionLabel("Home screen widgets")
        PanelCard {
            WidgetKind.entries.forEachIndexed { i, kind ->
                if (i > 0) Divider()
                SettingRow(kind.title, kind.description) {
                    PanelButton("Add", { actions.pinWidget(kind) })
                }
            }
        }
    }

    Spacer(Modifier.height(24.dp))
    SectionLabel("Account")
    PanelCard {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
            PanelButton("Sign in again", actions.signIn, style = ButtonStyle.Ghost, modifier = Modifier.weight(1f))
            PanelButton("Sign out", actions.signOut, style = ButtonStyle.Destructive, modifier = Modifier.weight(1f))
        }
    }
    Spacer(Modifier.height(16.dp))
    Text(
        "Unofficial app. Not affiliated with VATSIM or VATSIM UK.",
        color = c.muted,
        fontSize = 11.5.sp,
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun SettingRow(title: String, description: String, control: @Composable () -> Unit) {
    val c = LocalPanel.current
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.foreground, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(description, color = c.muted, fontSize = 12.5.sp)
        }
        Spacer(Modifier.width(12.dp))
        control()
    }
}

@Composable
private fun PanelSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val c = LocalPanel.current
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = c.primary,
            checkedThumbColor = c.primaryForeground,
            uncheckedTrackColor = c.secondary,
            uncheckedBorderColor = c.border,
            uncheckedThumbColor = c.muted,
        ),
    )
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
