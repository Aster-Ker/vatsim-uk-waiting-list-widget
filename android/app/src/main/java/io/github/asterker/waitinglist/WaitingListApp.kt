package io.github.asterker.waitinglist

import android.app.Application
import io.github.asterker.waitinglist.data.Notifications
import io.github.asterker.waitinglist.data.Scheduler
import io.github.asterker.waitinglist.data.Store

class WaitingListApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Notifications.createChannels(this)
        Scheduler.schedule(this, Store.state.value.intervalMinutes, replace = false)
    }
}
