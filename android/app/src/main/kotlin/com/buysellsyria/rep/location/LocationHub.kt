package com.buysellsyria.rep.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.buysellsyria.rep.core.geo.Fix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Latest GPS fix shared by the tracking service and the screens. Uses the platform
 * LocationManager (no Google Play Services needed). */
class LocationHub(private val context: Context) {
    private val _last = MutableStateFlow<Fix?>(null)
    val last: StateFlow<Fix?> = _last

    fun update(fix: Fix) { _last.value = fix }

    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

    fun isGpsOn(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
    }

    /** A fresh fix: the last one if recent enough, otherwise waits for a new one. */
    @SuppressLint("MissingPermission")
    suspend fun currentFix(maxAgeMs: Long = 30_000, timeoutMs: Long = 25_000): Fix? {
        _last.value?.let { if (System.currentTimeMillis() - it.timeMillis < maxAgeMs) return it }
        if (!hasPermission()) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        val fix = location.toFix()
                        update(fix)
                        lm.removeUpdates(this)
                        if (cont.isActive) cont.resume(fix)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { p ->
                    runCatching {
                        if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper())
                    }
                }
                cont.invokeOnCancellation { lm.removeUpdates(listener) }
            }
        } ?: _last.value
    }
}

fun Location.toFix() = Fix(latitude, longitude, if (hasAccuracy()) accuracy else 999f, System.currentTimeMillis())
