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
import io.github.asterker.waitinglist.data.Estimate
import io.github.asterker.waitinglist.data.Scheduler
import io.github.asterker.waitinglist.data.SessionPhase
import io.github.asterker.waitinglist.data.SessionsSync
import io.github.asterker.waitinglist.data.Status
import io.github.asterker.waitinglist.data.Store
import io.github.asterker.waitinglist.data.WaitingListEntry
import io.github.asterker.waitinglist.ui.MainActivity
import io.github.asterker.waitinglist.ui.relative
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Shared plumbing for the home screen widgets. Each widget supplies layouts for a range of
// sizes (smallest first); Android 12+ picks between them as the widget is resized.
abstract class BaseWidget : AppWidgetProvider() {

    abstract fun layouts(context: Context, state: AppState): List<Pair<SizeF, RemoteViews>>

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

    fun update(context: Context, manager: AppWidgetManager, id: Int) {
        Store.init(context)
        val sizes = layouts(context, Store.state.value)
        val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            RemoteViews(sizes.toMap())
        } else {
            // Before Android 12, pick the largest layout that fits the portrait size.
            val options = manager.getAppWidgetOptions(id)
            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            sizes.lastOrNull { (size, _) -> size.width <= width && size.height <= height }?.second
                ?: sizes.first().second
        }
        manager.updateAppWidget(id, views)
    }

    companion object {
        const val ACTION_REFRESH = "io.github.asterker.waitinglist.REFRESH"

        fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun refresh(context: Context, widget: Class<out BaseWidget>): PendingIntent = PendingIntent.getBroadcast(
            context, widget.name.hashCode(),
            Intent(context, widget).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // Shows ▲/▼ in green/red, or a muted fallback, using whichever TextView has the right colour.
        fun showDelta(views: RemoteViews, change: Int?, fallback: String) {
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

        // Fills the position/name/delta views that the best-position layouts share.
        fun bindBest(views: RemoteViews, state: AppState, compact: Boolean) {
            val best = state.bestPosition
            when {
                state.status == Status.LoginRequired -> {
                    views.setTextViewText(R.id.position, "!")
                    views.setTextViewText(R.id.list_name, "Sign in needed")
                    showDelta(views, null, if (compact) "Sign in" else "Tap to open")
                }
                best != null -> {
                    views.setTextViewText(R.id.position, "#${best.position}")
                    views.setTextViewText(R.id.list_name, best.name)
                    showDelta(views, best.change.takeIf { best.movedRecently() }, if (compact) "" else "Best position")
                }
                else -> {
                    views.setTextViewText(R.id.position, "—")
                    views.setTextViewText(R.id.list_name, if (state.lists.isEmpty()) "No lists" else "Not ranked yet")
                    showDelta(views, null, if (compact) "" else "Waiting lists")
                }
            }
        }
    }
}

// "Waiting lists": header, status and as many list rows as the widget's height allows.
class WaitingListWidget : BaseWidget() {

    override fun layouts(context: Context, state: AppState) = listOf(
        SizeF(180f, 90f) to large(context, state, maxRows = 1),
        SizeF(180f, 150f) to large(context, state, maxRows = 2),
        SizeF(180f, 210f) to large(context, state, maxRows = 3),
        SizeF(180f, 270f) to large(context, state, maxRows = 4),
        SizeF(180f, 360f) to large(context, state, maxRows = 6),
    )

    private fun large(context: Context, state: AppState, maxRows: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_large)
        views.setOnClickPendingIntent(R.id.widget_root, openApp(context))
        views.setOnClickPendingIntent(R.id.refresh, refresh(context, WaitingListWidget::class.java))

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
        val showAlert = alert != null && !state.checking
        views.setViewVisibility(R.id.alert, if (showAlert) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.alert, alert ?: "")
        views.setInt(
            R.id.alert, "setBackgroundResource",
            if (state.status == Status.Error) R.drawable.alert_destructive else R.drawable.alert_warning,
        )

        // The alert takes the place of one row so the widget doesn't overflow.
        val rows = (if (showAlert) maxRows - 1 else maxRows).coerceAtLeast(0)
        views.removeAllViews(R.id.rows)
        state.lists.take(rows).forEach { views.addView(R.id.rows, row(context, it)) }
        val hidden = state.lists.size - rows
        val empty = when {
            state.lists.isEmpty() && state.status == Status.Ok -> "You aren't on any waiting lists."
            state.lists.isEmpty() -> "No data yet. Open the app to sign in."
            hidden > 0 && rows > 0 -> "+$hidden more in the app"
            else -> null
        }
        views.setViewVisibility(R.id.empty, if (empty != null) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.empty, empty ?: "")
        return views
    }

    private fun row(context: Context, entry: WaitingListEntry): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_row)
        views.setInt(
            R.id.row_root, "setBackgroundResource",
            when {
                !entry.movedRecently() -> R.drawable.row_plain
                (entry.change ?: 0) < 0 -> R.drawable.row_down
                else -> R.drawable.row_up
            },
        )
        views.setTextViewText(R.id.name, entry.name)
        val details = buildList {
            add(entry.department)
            when (val estimate = Estimate.of(entry)) {
                is Estimate.Ready -> add("${Estimate.formatShort(estimate.days)} to #1")
                Estimate.Next -> add("You're next")
                else -> Unit
            }
            entry.theoryPassed?.let { add(if (it) "Theory ✓" else "Theory ✗") }
            if (entry.joined.isNotEmpty()) add("Joined ${entry.joined}")
        }
        views.setTextViewText(R.id.details, details.joinToString(" · "))
        views.setTextViewText(R.id.position, entry.position?.let { "#$it" } ?: "—")
        showDelta(views, entry.change, if (entry.position == null) "Not ranked" else "No change")
        return views
    }

    companion object {
        private val ALL = listOf(
            WaitingListWidget::class.java,
            BestPositionWidget::class.java,
            PositionsStripWidget::class.java,
            NextSessionWidget::class.java,
        )

        // Redraws every placed widget of every kind.
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            for (type in ALL) {
                val ids = manager.getAppWidgetIds(ComponentName(context, type))
                if (ids.isEmpty()) continue
                val widget = type.getDeclaredConstructor().newInstance()
                ids.forEach { widget.update(context, manager, it) }
            }
        }
    }
}

// "Best position": from a 1×1 tile up to a 2×2 card.
class BestPositionWidget : BaseWidget() {

    override fun layouts(context: Context, state: AppState) = listOf(
        SizeF(40f, 40f) to build(context, state, R.layout.widget_tiny, compact = true),
        SizeF(110f, 40f) to build(context, state, R.layout.widget_best_strip, compact = false),
        SizeF(100f, 100f) to build(context, state, R.layout.widget_small, compact = false),
    )

    private fun build(context: Context, state: AppState, layout: Int, compact: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, layout)
        views.setOnClickPendingIntent(R.id.widget_root, openApp(context))
        bindBest(views, state, compact)
        return views
    }
}

// "Positions strip": a one-row strip with several lists side by side.
class PositionsStripWidget : BaseWidget() {

    override fun layouts(context: Context, state: AppState) = listOf(
        SizeF(150f, 40f) to strip(context, state, maxItems = 2),
        SizeF(220f, 40f) to strip(context, state, maxItems = 3),
        SizeF(290f, 40f) to strip(context, state, maxItems = 4),
        SizeF(360f, 40f) to strip(context, state, maxItems = 5),
    )

    private fun strip(context: Context, state: AppState, maxItems: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_strip)
        views.setOnClickPendingIntent(R.id.widget_root, openApp(context))
        views.removeAllViews(R.id.items)

        val message = when {
            state.status == Status.LoginRequired -> "Sign in needed. Tap to open the app."
            state.lists.isEmpty() && state.status == Status.Ok -> "You aren't on any waiting lists."
            state.lists.isEmpty() -> "No data yet. Tap to open the app."
            else -> null
        }
        views.setViewVisibility(R.id.empty, if (message != null) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.empty, message ?: "")
        if (message != null) return views

        // Ranked lists first, best position first.
        val shown = state.lists.sortedWith(compareBy(nullsLast<Int>()) { it.position }).take(maxItems)
        for (entry in shown) {
            val item = RemoteViews(context.packageName, R.layout.widget_strip_item)
            item.setTextViewText(R.id.position, entry.position?.let { "#$it" } ?: "—")
            item.setTextViewText(R.id.name, entry.name)
            val change = entry.change.takeIf { entry.movedRecently() }
            item.setViewVisibility(R.id.delta_up, if (change != null && change > 0) View.VISIBLE else View.GONE)
            item.setViewVisibility(R.id.delta_down, if (change != null && change < 0) View.VISIBLE else View.GONE)
            if (change != null) item.setTextViewText(if (change > 0) R.id.delta_up else R.id.delta_down, if (change > 0) "▲$change" else "▼${-change}")
            views.addView(R.id.items, item)
        }
        return views
    }
}

// "Next session": your next booking with a starts-in / in-progress indicator.
class NextSessionWidget : BaseWidget() {

    override fun layouts(context: Context, state: AppState) = listOf(
        SizeF(110f, 40f) to build(context, state, R.layout.widget_session_compact),
        SizeF(170f, 100f) to build(context, state, R.layout.widget_session),
    )

    private fun build(context: Context, state: AppState, layout: Int): RemoteViews {
        val views = RemoteViews(context.packageName, layout)
        views.setOnClickPendingIntent(R.id.widget_root, openApp(context))
        val now = System.currentTimeMillis()
        val next = state.sessions.upcoming(now).firstOrNull()

        if (next == null) {
            views.setTextViewText(R.id.position, "No sessions")
            views.setTextViewText(R.id.kind, "Next session")
            views.setTextViewText(R.id.time, if (state.status == Status.LoginRequired) "Sign in needed" else "Nothing booked")
            views.setTextViewText(R.id.with, "")
            showPhase(views, R.id.phase_muted, "")
            return views
        }

        views.setTextViewText(R.id.position, next.position ?: next.kind)
        views.setTextViewText(R.id.kind, next.kind)
        views.setTextViewText(R.id.time, SessionsSync.timeRange(next))
        views.setTextViewText(R.id.with, next.withWhom ?: "")
        when (next.phase(now, state.sessions.remindMinutes * 60_000L)) {
            SessionPhase.InProgress -> showPhase(views, R.id.phase_live, "In progress · ends ${SessionsSync.time(next.end)}")
            SessionPhase.StartingSoon -> showPhase(views, R.id.phase_soon, "Starts in ${relative(next.start - now)}")
            SessionPhase.Upcoming -> showPhase(views, R.id.phase_muted, "In ${relative(next.start - now)}")
            SessionPhase.Finished -> showPhase(views, R.id.phase_muted, "Finished")
        }
        return views
    }

    private fun showPhase(views: RemoteViews, id: Int, text: String) {
        listOf(R.id.phase_live, R.id.phase_soon, R.id.phase_muted).forEach {
            views.setViewVisibility(it, if (it == id) View.VISIBLE else View.GONE)
        }
        views.setTextViewText(id, text)
    }
}
