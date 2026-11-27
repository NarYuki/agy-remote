package moe.n4tsu.agyremote

import android.app.Application
import moe.n4tsu.agyremote.push.Notifications

class AgyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}
