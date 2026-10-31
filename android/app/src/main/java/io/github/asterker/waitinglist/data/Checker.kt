package io.github.asterker.waitinglist.data

import android.content.Context
import io.github.asterker.waitinglist.widget.WaitingListWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// One check of vatsim.uk: fetch, merge with what we had, notify, refresh widgets.
object Checker {
    private val mutex = Mutex()

    suspend fun run(context: Context): FetchResult = mutex.withLock {
        val app = context.applicationContext
        Store.init(app)
        Store.update { it.copy(checking = true) }
        WaitingListWidget.updateAll(app)

        val result = withContext(Dispatchers.IO) {
            runCatching { WaitingListClient.fetch(app) }
                .getOrElse { FetchResult.Error(it.message ?: "Unexpected error") }
        }
        val previous = Store.state.value

        when (result) {
            is FetchResult.Ok -> {
                val (lists, changes) = merge(previous, result.lists)
                Store.update {
                    it.copy(
                        lists = lists,
                        lastChecked = System.currentTimeMillis(),
                        status = Status.Ok,
                        message = null,
                        checking = false,
                    )
                }
                if (changes.isNotEmpty() && previous.notify) Notifications.showChanges(app, changes)
            }

            FetchResult.LoginRequired -> {
                Store.update { it.copy(status = Status.LoginRequired, message = null, checking = false) }
                if (previous.status != Status.LoginRequired && previous.notify) Notifications.showSignInNeeded(app)
            }

            is FetchResult.Error -> Store.update {
                it.copy(status = Status.Error, message = result.message, checking = false)
            }
        }

        WaitingListWidget.updateAll(app)
        result
    }

    // Carries change history over from the previous check and describes what changed.
    fun merge(previous: AppState, fresh: List<WaitingListEntry>): Pair<List<WaitingListEntry>, List<String>> {
        val changes = mutableListOf<String>()
        val firstCheck = previous.lastChecked == null
        val old = previous.lists.associateBy { it.key }.toMutableMap()
        val now = System.currentTimeMillis()

        val merged = fresh.map { entry ->
            val before = old.remove(entry.key)
            if (before == null) {
                if (!firstCheck) changes += "Added to ${entry.name}" + (entry.position?.let { " at #$it" } ?: "")
                return@map entry
            }

            var result = entry.copy(change = before.change, changedAt = before.changedAt)
            val was = before.position
            val current = entry.position
            if (was != null && current != null && was != current) {
                result = result.copy(change = was - current, changedAt = now)
                val direction = if (was > current) "up ${was - current}" else "down ${current - was}"
                changes += "${entry.name}: #$was → #$current ($direction)"
            } else if (was == null && current != null) {
                result = result.copy(changedAt = now)
                changes += "${entry.name}: you now have a position, #$current"
            }

            if (before.theoryPassed == false && entry.theoryPassed == true) {
                changes += "${entry.name}: theory exam now shows as passed"
            }
            result
        }

        old.values.forEach { changes += "No longer on ${it.name}" }
        return merged to changes
    }
}
