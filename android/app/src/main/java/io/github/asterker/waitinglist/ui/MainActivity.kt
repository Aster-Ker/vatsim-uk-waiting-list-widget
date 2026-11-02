package io.github.asterker.waitinglist.ui

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.widget.Toast
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import io.github.asterker.waitinglist.data.Checker
import io.github.asterker.waitinglist.data.Notifications
import io.github.asterker.waitinglist.data.Scheduler
import io.github.asterker.waitinglist.data.SessionReminders
import io.github.asterker.waitinglist.data.Status
import io.github.asterker.waitinglist.data.Store
import io.github.asterker.waitinglist.data.WaitingListClient
import io.github.asterker.waitinglist.widget.WaitingListWidget
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Store.init(this)
        setContent {
            PanelTheme {
                App()
            }
        }
    }
}

@Composable
private fun App() {
    val context = LocalContext.current
    val state by Store.state.collectAsState()
    val scope = rememberCoroutineScope()

    fun check() {
        scope.launch { Checker.run(context) }
    }

    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) check()
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notifications.permissionGranted(context)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        if (state.notify) requestNotificationPermission()
        // First run: go straight to sign-in. Otherwise only check if the last check is older
        // than the chosen interval; Check now and the widget's refresh button check at any time.
        val lastChecked = state.lastChecked
        val intervalMillis = state.intervalMinutes * 60_000L
        if (lastChecked == null && state.status == Status.Idle) {
            signIn.launch(Intent(context, LoginActivity::class.java))
        } else if (state.status != Status.LoginRequired &&
            (lastChecked == null || System.currentTimeMillis() - lastChecked >= intervalMillis)
        ) {
            check()
        }
    }

    var showSessions by rememberSaveable { mutableStateOf(false) }
    if (showSessions) {
        val now by produceState(System.currentTimeMillis()) {
            while (true) {
                delay(30_000)
                value = System.currentTimeMillis()
            }
        }
        SessionsScreen(
            sessions = state.sessions,
            now = now,
            onBack = { showSessions = false },
            onOpenPage = { url -> context.startActivity(WebPageActivity.intent(context, url)) },
            onAddToCalendar = { booking ->
                val intent = Intent(Intent.ACTION_INSERT)
                    .setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, booking.start)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, booking.end)
                    .putExtra(CalendarContract.Events.TITLE, booking.title)
                    .putExtra(CalendarContract.Events.EVENT_LOCATION, booking.position ?: "")
                    .putExtra(CalendarContract.Events.DESCRIPTION, listOfNotNull(booking.withWhom, booking.url).joinToString("\n"))
                runCatching { context.startActivity(intent) }.onFailure {
                    Toast.makeText(context, "No calendar app found", Toast.LENGTH_SHORT).show()
                }
            },
        )
        return
    }

    val widgetManager = AppWidgetManager.getInstance(context)
    MainScreen(
        state = state,
        canPinWidget = widgetManager.isRequestPinAppWidgetSupported,
        onCheckNow = ::check,
        onSignIn = { signIn.launch(Intent(context, LoginActivity::class.java)) },
        onOpenWebsite = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WaitingListClient.WAITING_LISTS_URL)))
        },
        onNotifyChange = { enabled ->
            Store.update { it.copy(notify = enabled) }
            if (enabled) requestNotificationPermission()
        },
        onAlarmSoundChange = { enabled -> Store.update { it.copy(alarmSound = enabled) } },
        onSoundSettings = { Notifications.openChannelSettings(context) },
        onIntervalChange = { minutes ->
            Store.update { it.copy(intervalMinutes = minutes) }
            Scheduler.schedule(context, minutes, replace = true)
        },
        onTestNotification = {
            requestNotificationPermission()
            val sample = state.bestPosition
            val line = if (sample?.position != null) {
                "${sample.name}: #${sample.position + 2} → #${sample.position} (up 2)"
            } else {
                "S2 Training: #14 → #12 (up 2)"
            }
            Notifications.showChanges(context, listOf(line), test = true)
        },
        onPinWidget = {
            widgetManager.requestPinAppWidget(ComponentName(context, WaitingListWidget::class.java), null, null)
        },
        onOpenPage = { url -> context.startActivity(WebPageActivity.intent(context, url)) },
        onSessionAlertsChange = { enabled ->
            Store.update { it.copy(sessions = it.sessions.copy(alerts = enabled)) }
            SessionReminders.reschedule(context, state.sessions.bookings, Store.state.value.sessions)
            if (enabled) requestNotificationPermission()
        },
        onRemindChange = { minutes ->
            Store.update { it.copy(sessions = it.sessions.copy(remindMinutes = minutes)) }
            SessionReminders.reschedule(context, state.sessions.bookings, Store.state.value.sessions)
        },
        onShowAllSessions = { showSessions = true },
        onSignOut = {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            Store.update { it.copy(status = Status.LoginRequired, message = null) }
            WaitingListWidget.updateAll(context)
        },
    )
}
