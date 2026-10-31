package io.github.asterker.waitinglist.ui

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import io.github.asterker.waitinglist.data.Checker
import io.github.asterker.waitinglist.data.Notifications
import io.github.asterker.waitinglist.data.Scheduler
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
        // First run: go straight to sign-in. Otherwise refresh when the app opens.
        if (state.lastChecked == null && state.status == Status.Idle) {
            signIn.launch(Intent(context, LoginActivity::class.java))
        } else if (state.status != Status.LoginRequired) {
            check()
        }
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
        onSignOut = {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            Store.update { it.copy(status = Status.LoginRequired, message = null) }
            WaitingListWidget.updateAll(context)
        },
    )
}
