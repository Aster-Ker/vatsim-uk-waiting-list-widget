package io.github.asterker.waitinglist.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.asterker.waitinglist.R
import io.github.asterker.waitinglist.ui.MainActivity

object Notifications {
    private const val CHANNEL_CHANGES = "position_changes"
    private const val CHANNEL_ACCOUNT = "account"
    private const val ID_SIGN_IN = 1
    private var nextChangeId = 100

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_CHANGES, "Position changes", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When your position on a VATSIM UK waiting list changes"
                },
                NotificationChannel(CHANNEL_ACCOUNT, "Sign-in reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When you need to sign in to vatsim.uk again"
                },
            )
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
        val builder = NotificationCompat.Builder(context, CHANNEL_CHANGES)
            .setSmallIcon(R.drawable.ic_notification)
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
