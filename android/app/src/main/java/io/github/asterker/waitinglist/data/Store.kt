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
            .apply()
    }

    private fun JSONObject.intOrNull(name: String): Int? = if (has(name) && !isNull(name)) getInt(name) else null
    private fun JSONObject.longOrNull(name: String): Long? = if (has(name) && !isNull(name)) getLong(name) else null
    private fun JSONObject.boolOrNull(name: String): Boolean? = if (has(name) && !isNull(name)) getBoolean(name) else null
}
