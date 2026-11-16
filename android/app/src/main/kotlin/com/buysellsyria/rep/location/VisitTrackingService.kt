package com.buysellsyria.rep.location

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.buysellsyria.rep.MainActivity
import com.buysellsyria.rep.R
import com.buysellsyria.rep.RepApp
import com.buysellsyria.rep.core.geo.Fix
import com.buysellsyria.rep.core.geo.GeoPoint
import com.buysellsyria.rep.core.geo.GeofenceEngine
import com.buysellsyria.rep.core.geo.GeofenceEvent
import com.buysellsyria.rep.core.geo.Target
import com.buysellsyria.rep.data.Fmt
import com.buysellsyria.rep.data.Where
import com.buysellsyria.rep.sync.SyncWorker
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that follows the rep's GPS during the work day and
 * opens / closes customer visits automatically (radius configured in Odoo, 50 m by default).
 */
class VisitTrackingService : LifecycleService() {

    private val app get() = application as RepApp
    private val engine = GeofenceEngine()
    @Volatile private var targets: List<Target> = emptyList()
    private val fixes = Channel<Fix>(Channel.CONFLATED)
    private var listener: LocationListener? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground("تتبع الزيارات يعمل")
        val repo = app.repo
        engine.config = repo.session.geofenceConfig()

        lifecycleScope.launch {
            repo.customers.collectLatest { list ->
                targets = list.map { c -> Target(c.key, if (c.lat != null && c.lng != null) GeoPoint(c.lat, c.lng) else null) }
            }
        }
        lifecycleScope.launch {
            // Keep the engine aligned with visits opened/closed by hand in the app.
            repo.openVisit.collectLatest { v ->
                if (engine.openKey != v?.customerKey) engine.setOpen(v?.customerKey)
                updateNotification(v?.let { "زيارة: ${it.customerName} منذ ${Fmt.time(it.checkIn)}" } ?: "لا توجد زيارة مفتوحة")
            }
        }
        lifecycleScope.launch {
            for (fix in fixes) handle(fix)
        }
        requestUpdates()
    }

    private suspend fun handle(fix: Fix) {
        app.location.update(fix)
        val repo = app.repo
        engine.config = repo.session.geofenceConfig()
        val events = engine.onFix(fix, targets)
        for (e in events) {
            val where = Where(fix.lat, fix.lng, e.distance)
            when (e) {
                is GeofenceEvent.Enter -> {
                    val v = repo.openVisit(e.targetKey, "auto", where)
                    v?.let { notifyVisit("بدأت زيارة", it.customerName) }
                }
                is GeofenceEvent.Exit -> {
                    val open = repo.currentOpenVisit()
                    repo.closeVisit("auto", where, null)
                    open?.let { notifyVisit("انتهت زيارة", "${it.customerName} (${Fmt.duration(it.checkIn)})") }
                }
            }
        }
        if (events.isNotEmpty()) SyncWorker.syncSoon(this)
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates() {
        if (!app.location.hasPermission()) { stopSelf(); return }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) { fixes.trySend(location.toFix()) }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        listener = l
        runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10_000L, 0f, l, Looper.getMainLooper()) }
        runCatching { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 30_000L, 0f, l, Looper.getMainLooper()) }
    }

    override fun onDestroy() {
        listener?.let { (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(it) }
        fixes.close()
        super.onDestroy()
    }

    private fun contentIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, RepApp.CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("المندوب")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentIntent())
            .build()

    private fun startAsForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), type)
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification(text: String) {
        runCatching { NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification(text)) }
    }

    @SuppressLint("MissingPermission")
    private fun notifyVisit(title: String, text: String) {
        val n = NotificationCompat.Builder(this, RepApp.CHANNEL_VISITS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
        runCatching { NotificationManagerCompat.from(this).notify((System.currentTimeMillis() % 100000).toInt(), n) }
    }

    companion object {
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, VisitTrackingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VisitTrackingService::class.java))
        }
    }
}
