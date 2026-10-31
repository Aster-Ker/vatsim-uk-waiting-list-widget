package io.github.asterker.waitinglist.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import io.github.asterker.waitinglist.R
import io.github.asterker.waitinglist.data.AppState
import io.github.asterker.waitinglist.data.Scheduler
import io.github.asterker.waitinglist.data.Status
import io.github.asterker.waitinglist.data.Store
import io.github.asterker.waitinglist.data.WaitingListEntry
import io.github.asterker.waitinglist.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Home screen widget. Small sizes show your best position; larger sizes list every waiting list.
class WaitingListWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        update(context, manager, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) Scheduler.checkNow(context)
    }

    companion object {
        private const val ACTION_REFRESH = "io.github.asterker.waitinglist.REFRESH"
        private const val MAX_ROWS = 4
        private const val LARGE_MIN_WIDTH_DP = 200

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, WaitingListWidget::class.java))
            ids.forEach { update(context, manager, it) }
        }

        private fun update(context: Context, manager: AppWidgetManager, id: Int) {
            Store.init(context)
            val state = Store.state.value
            val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                RemoteViews(
                    mapOf(
                        SizeF(100f, 60f) to small(context, state),
                        SizeF(LARGE_MIN_WIDTH_DP.toFloat(), 110f) to large(context, state),
                    )
                )
            } else {
                val minWidth = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                if (minWidth >= LARGE_MIN_WIDTH_DP) large(context, state) else small(context, state)
            }
            manager.updateAppWidget(id, views)
        }

        private fun small(context: Context, state: AppState): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_small)
            views.setOnClickPendingIntent(R.id.widget_root, openApp(context))

            val best = state.bestPosition
            when {
                state.status == Status.LoginRequired -> {
                    views.setTextViewText(R.id.position, "!")
                    views.setTextViewText(R.id.list_name, "Sign in needed")
                    showDelta(views, null, "Tap to open")
                }
                best != null -> {
                    views.setTextViewText(R.id.position, "#${best.position}")
                    views.setTextViewText(R.id.list_name, best.name)
                    showDelta(views, best.change.takeIf { best.movedRecently() }, "Best position")
                }
                else -> {
                    views.setTextViewText(R.id.position, "—")
                    views.setTextViewText(R.id.list_name, if (state.lists.isEmpty()) "No lists" else "Not ranked yet")
                    showDelta(views, null, "Waiting lists")
                }
            }
            return views
        }

        private fun large(context: Context, state: AppState): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_large)
            views.setOnClickPendingIntent(R.id.widget_root, openApp(context))
            views.setOnClickPendingIntent(R.id.refresh, refresh(context))

            val checked = state.lastChecked?.let { "Checked " + SimpleDateFormat("EEE HH:mm", Locale.UK).format(Date(it)) }
            views.setTextViewText(R.id.subtitle, listOfNotNull("VATSIM UK", checked).joinToString(" · "))

            val badge = when {
                state.checking -> R.id.badge_checking
                state.status == Status.Ok -> R.id.badge_ok
                state.status == Status.LoginRequired -> R.id.badge_sign_in
                state.status == Status.Error -> R.id.badge_error
                else -> R.id.badge_checking
            }
            listOf(R.id.badge_ok, R.id.badge_checking, R.id.badge_sign_in, R.id.badge_error).forEach {
                views.setViewVisibility(it, if (it == badge) View.VISIBLE else View.GONE)
            }

            val alert = when (state.status) {
                Status.LoginRequired -> "Sign in needed. Tap to open the app."
                Status.Error -> state.message ?: "Couldn't check vatsim.uk"
                else -> null
            }
            views.setViewVisibility(R.id.alert, if (alert != null && !state.checking) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.alert, alert ?: "")
            views.setInt(
                R.id.alert, "setBackgroundResource",
                if (state.status == Status.Error) R.drawable.alert_destructive else R.drawable.alert_warning,
            )

            views.removeAllViews(R.id.rows)
            state.lists.take(MAX_ROWS).forEach { views.addView(R.id.rows, row(context, it)) }
            val hidden = state.lists.size - MAX_ROWS
            val empty = when {
                state.lists.isEmpty() && state.status == Status.Ok -> "You aren't on any waiting lists."
                state.lists.isEmpty() -> "No data yet. Open the app to sign in."
                hidden > 0 -> "+$hidden more in the app"
                else -> null
            }
            views.setViewVisibility(R.id.empty, if (empty != null) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.empty, empty ?: "")
            return views
        }

        private fun row(context: Context, entry: WaitingListEntry): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_row)
            val recent = entry.movedRecently()
            views.setInt(
                R.id.row_root, "setBackgroundResource",
                when {
                    !recent -> R.drawable.row_plain
                    (entry.change ?: 0) < 0 -> R.drawable.row_down
                    else -> R.drawable.row_up
                },
            )
            views.setTextViewText(R.id.name, entry.name)
            val details = buildList {
                add(entry.department)
                entry.theoryPassed?.let { add(if (it) "Theory ✓" else "Theory ✗") }
                if (entry.joined.isNotEmpty()) add("Joined ${entry.joined}")
            }
            views.setTextViewText(R.id.details, details.joinToString(" · "))
            views.setTextViewText(R.id.position, entry.position?.let { "#$it" } ?: "—")
            showDelta(views, entry.change, if (entry.position == null) "Not ranked" else "No change")
            return views
        }

        // Shows ▲/▼ in green/red, or a muted fallback, using whichever TextView has the right colour.
        private fun showDelta(views: RemoteViews, change: Int?, fallback: String) {
            val (id, text) = when {
                change != null && change > 0 -> R.id.delta_up to "▲ $change"
                change != null && change < 0 -> R.id.delta_down to "▼ ${-change}"
                else -> R.id.delta_muted to fallback
            }
            listOf(R.id.delta_up, R.id.delta_down, R.id.delta_muted).forEach {
                views.setViewVisibility(it, if (it == id) View.VISIBLE else View.GONE)
            }
            views.setTextViewText(id, text)
        }

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun refresh(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, WaitingListWidget::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
