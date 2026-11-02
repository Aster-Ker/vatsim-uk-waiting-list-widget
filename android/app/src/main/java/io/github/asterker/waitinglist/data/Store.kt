package io.github.asterker.waitinglist.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.updateAndGet
import org.json.JSONArray
import org.json.JSONObject

// App state, kept in memory for the UI and widget and saved to SharedPreferences.
object Store {
    private lateinit var prefs: SharedPreferences
    private val mutableState = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = mutableState

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("state", Context.MODE_PRIVATE)
        mutableState.value = load()
    }

    fun update(transform: (AppState) -> AppState) {
        save(mutableState.updateAndGet(transform))
    }

    private fun load(): AppState {
        val lists = runCatching {
            val array = JSONArray(prefs.getString("lists", "[]"))
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                WaitingListEntry(
                    department = o.optString("department"),
                    name = o.optString("name"),
                    position = o.intOrNull("position"),
                    joined = o.optString("joined"),
                    onRoster = o.boolOrNull("onRoster"),
                    theoryPassed = o.boolOrNull("theoryPassed"),
                    change = o.intOrNull("change"),
                    changedAt = o.longOrNull("changedAt"),
                    history = o.optJSONArray("history")?.let { h ->
                        (0 until h.length()).map { j ->
                            val sample = h.getJSONObject(j)
                            PositionSample(sample.getLong("at"), sample.getInt("position"))
                        }
                    } ?: emptyList(),
                )
            }
        }.getOrDefault(emptyList())

        return AppState(
            lists = lists,
            lastChecked = prefs.getLong("lastChecked", -1).takeIf { it > 0 },
            status = runCatching { Status.valueOf(prefs.getString("status", null)!!) }.getOrDefault(Status.Idle),
            message = prefs.getString("message", null),
            notify = prefs.getBoolean("notify", true),
            alarmSound = prefs.getBoolean("alarmSound", true),
            intervalMinutes = prefs.getInt("interval", 60),
            sessions = runCatching { loadSessions(JSONObject(prefs.getString("sessions", "{}")!!)) }.getOrDefault(SessionsState()),
        )
    }

    private fun save(state: AppState) {
        val array = JSONArray()
        for (e in state.lists) {
            array.put(JSONObject().apply {
                put("department", e.department)
                put("name", e.name)
                putOpt("position", e.position)
                put("joined", e.joined)
                putOpt("onRoster", e.onRoster)
                putOpt("theoryPassed", e.theoryPassed)
                putOpt("change", e.change)
                putOpt("changedAt", e.changedAt)
                put("history", JSONArray().apply {
                    e.history.forEach { put(JSONObject().put("at", it.at).put("position", it.position)) }
                })
            })
        }
        prefs.edit()
            .putString("lists", array.toString())
            .putLong("lastChecked", state.lastChecked ?: -1)
            .putString("status", state.status.name)
            .putString("message", state.message)
            .putBoolean("notify", state.notify)
            .putBoolean("alarmSound", state.alarmSound)
            .putInt("interval", state.intervalMinutes)
            .putString("sessions", saveSessions(state.sessions).toString())
            .apply()
    }

    private fun loadSessions(o: JSONObject): SessionsState {
        val bookings = o.optJSONArray("bookings") ?: JSONArray()
        val history = o.optJSONArray("history") ?: JSONArray()
        return SessionsState(
            feedUrl = o.stringOrNull("feedUrl"),
            bookings = (0 until bookings.length()).map { i ->
                val b = bookings.getJSONObject(i)
                SessionBooking(
                    uid = b.getString("uid"),
                    kind = b.optString("kind"),
                    position = b.stringOrNull("position"),
                    start = b.getLong("start"),
                    end = b.getLong("end"),
                    mentor = b.stringOrNull("mentor"),
                    examiner = b.stringOrNull("examiner"),
                    student = b.stringOrNull("student"),
                    url = b.stringOrNull("url"),
                )
            },
            history = (0 until history.length()).map { i ->
                val h = history.getJSONObject(i)
                TrainingRecord(
                    id = h.getString("id"),
                    position = h.optString("position"),
                    date = h.optString("date"),
                    mentor = h.optString("mentor"),
                    status = h.optString("status"),
                    reportUrl = h.stringOrNull("reportUrl"),
                )
            },
            historyAvailable = o.optBoolean("historyAvailable", true),
            lastChecked = o.longOrNull("lastChecked"),
            message = o.stringOrNull("message"),
            alerts = o.optBoolean("alerts", true),
            remindMinutes = o.optInt("remindMinutes", 30),
        )
    }

    private fun saveSessions(s: SessionsState): JSONObject = JSONObject().apply {
        putOpt("feedUrl", s.feedUrl)
        put("bookings", JSONArray().apply {
            s.bookings.forEach { b ->
                put(JSONObject().apply {
                    put("uid", b.uid)
                    put("kind", b.kind)
                    putOpt("position", b.position)
                    put("start", b.start)
                    put("end", b.end)
                    putOpt("mentor", b.mentor)
                    putOpt("examiner", b.examiner)
                    putOpt("student", b.student)
                    putOpt("url", b.url)
                })
            }
        })
        put("history", JSONArray().apply {
            s.history.forEach { h ->
                put(JSONObject().apply {
                    put("id", h.id)
                    put("position", h.position)
                    put("date", h.date)
                    put("mentor", h.mentor)
                    put("status", h.status)
                    putOpt("reportUrl", h.reportUrl)
                })
            }
        })
        put("historyAvailable", s.historyAvailable)
        putOpt("lastChecked", s.lastChecked)
        putOpt("message", s.message)
        put("alerts", s.alerts)
        put("remindMinutes", s.remindMinutes)
    }

    private fun JSONObject.intOrNull(name: String): Int? = if (has(name) && !isNull(name)) getInt(name) else null
    private fun JSONObject.longOrNull(name: String): Long? = if (has(name) && !isNull(name)) getLong(name) else null
    private fun JSONObject.stringOrNull(name: String): String? = if (has(name) && !isNull(name)) getString(name) else null
    private fun JSONObject.boolOrNull(name: String): Boolean? = if (has(name) && !isNull(name)) getBoolean(name) else null
}
