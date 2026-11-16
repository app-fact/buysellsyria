package com.buysellsyria.rep

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.buysellsyria.rep.data.Repository
import com.buysellsyria.rep.data.Session
import com.buysellsyria.rep.data.db.AppDatabase
import com.buysellsyria.rep.data.remote.ApiClient
import com.buysellsyria.rep.location.LocationHub
import com.buysellsyria.rep.sync.SyncWorker

class RepApp : Application() {
    lateinit var repo: Repository
        private set
    lateinit var location: LocationHub
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        val session = Session(this)
        val api = ApiClient({ session.serverUrl }, { session.token })
        repo = Repository(AppDatabase.create(this), session, api)
        location = LocationHub(this)
        createChannels()
        if (session.isLoggedIn) SyncWorker.schedulePeriodic(this)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRACKING, "تتبع الزيارات", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_VISITS, "الزيارات", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        const val CHANNEL_TRACKING = "tracking"
        const val CHANNEL_VISITS = "visits"
        lateinit var instance: RepApp
            private set
    }
}
