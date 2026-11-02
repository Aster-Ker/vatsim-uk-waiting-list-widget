package io.github.asterker.waitinglist.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.asterker.waitinglist.R
import io.github.asterker.waitinglist.ui.MainActivity
import io.github.asterker.waitinglist.ui.WebPageActivity

object Notifications {
    // Android fixes a channel's sound when it's created, so the loud channels have new IDs
    // and the original quiet "position_changes" channel is deleted.
    private const val CHANNEL_ALARM = "position_alarm"
    private const val CHANNEL_SOUND = "position_sound"
    private const val OLD_CHANNEL_CHANGES = "position_changes"
    private const val CHANNEL_ACCOUNT = "account"
    private const val CHANNEL_REMINDER_ALARM = "session_reminder_alarm"
    private const val CHANNEL_REMINDER_SOUND = "session_reminder_sound"
    private const val CHANNEL_SESSION_UPDATES = "session_updates"
    private const val ID_SIGN_IN = 1
    private var nextChangeId = 100
    private val VIBRATION = longArrayOf(0, 400, 200, 400, 200, 400)

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(OLD_CHANNEL_CHANGES)

        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val alarmAudio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_ALARM, "Position changes (alarm)", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Pops up with your alarm sound when a waiting list position changes, even on silent"
                    setSound(alarmSound, alarmAudio)
                    enableVibration(true)
                    vibrationPattern = VIBRATION
                    enableLights(true)
                },
                NotificationChannel(CHANNEL_SOUND, "Position changes", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Pops up with your notification sound when a waiting list position changes"
                    enableVibration(true)
                    vibrationPattern = VIBRATION
                },
                NotificationChannel(CHANNEL_REMINDER_ALARM, "Session reminders (alarm)", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Rings with your alarm sound before a booked session, even on silent"
                    setSound(alarmSound, alarmAudio)
                    enableVibration(true)
                    vibrationPattern = VIBRATION
                    enableLights(true)
                },
                NotificationChannel(CHANNEL_REMINDER_SOUND, "Session reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Pops up with your notification sound before a booked session"
                    enableVibration(true)
                    vibrationPattern = VIBRATION
                },
                NotificationChannel(CHANNEL_SESSION_UPDATES, "Bookings and feedback", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "When a session is booked, moved or removed, and when a mentor files feedback"
                },
                NotificationChannel(CHANNEL_ACCOUNT, "Sign-in reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When you need to sign in to vatsim.uk again"
                },
            )
        )
    }

    // Opens Android's settings for the channel in use, to change its sound or volume.
    fun openChannelSettings(context: Context) {
        val channel = if (Store.state.value.alarmSound) CHANNEL_ALARM else CHANNEL_SOUND
        context.startActivity(
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, channel)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun permissionGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun showChanges(context: Context, changes: List<String>, test: Boolean = false) {
        val title = when {
            test -> "Waiting list update (test)"
            changes.size == 1 -> "Waiting list update"
            else -> "${changes.size} waiting list updates"
        }
        val alarm = Store.state.value.alarmSound
        val builder = NotificationCompat.Builder(context, if (alarm) CHANNEL_ALARM else CHANNEL_SOUND)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (alarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .setContentTitle(title)
            .setContentText(changes.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(changes.joinToString("\n")))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
        post(context, nextChangeId++, builder)
    }

    fun showSignInNeeded(context: Context) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ACCOUNT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sign in to VATSIM UK")
            .setContentText("Your vatsim.uk session has expired. Tap to sign in again.")
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
        post(context, ID_SIGN_IN, builder)
    }

    fun showSessionReminder(context: Context, booking: SessionBooking, minutesBefore: Int) {
        val alarm = Store.state.value.alarmSound
        val lead = if (minutesBefore < 60) "$minutesBefore minutes" else if (minutesBefore == 60) "1 hour" else "${minutesBefore / 60} hours"
        val builder = NotificationCompat.Builder(context, if (alarm) CHANNEL_REMINDER_ALARM else CHANNEL_REMINDER_SOUND)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (alarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setContentTitle("${booking.title} in $lead")
            .setContentText(listOfNotNull(SessionsSync.timeRange(booking), booking.withWhom).joinToString(" · "))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
        post(context, booking.uid.hashCode(), builder)
    }

    fun showSessionUpdate(context: Context, notice: SessionsSync.Notice) {
        val builder = NotificationCompat.Builder(context, CHANNEL_SESSION_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentTitle(notice.title)
            .setContentText(notice.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.body))
            .setContentIntent(notice.url?.let { openPage(context, it) } ?: openApp(context))
            .setAutoCancel(true)
        post(context, nextChangeId++, builder)
    }

    // Opens a vatsim.uk page (a mentor report) inside the app, where you're already signed in.
    private fun openPage(context: Context, url: String): PendingIntent = PendingIntent.getActivity(
        context,
        url.hashCode(),
        WebPageActivity.intent(context, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @SuppressLint("MissingPermission") // Checked by permissionGranted().
    private fun post(context: Context, id: Int, builder: NotificationCompat.Builder) {
        if (permissionGranted(context)) NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
