package com.bettergecko.app

import android.app.Application
import com.bettergecko.app.data.Schedules
import com.bettergecko.app.data.SecureStore
import com.bettergecko.app.data.Store

class BetterGeckoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        SecureStore.init(this)
        Schedules.createChannel(this)
        Schedules.rearmAll(this)
    }
}
