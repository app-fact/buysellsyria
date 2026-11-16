package com.buysellsyria.rep.data

import android.content.Context
import com.buysellsyria.rep.core.geo.GeofenceConfig
import com.buysellsyria.rep.data.remote.CompanyDto
import com.buysellsyria.rep.data.remote.ConfigDto
import com.buysellsyria.rep.data.remote.CurrencyDto
import com.buysellsyria.rep.data.remote.RepDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class Profile(
    val rep: RepDto,
    val company: CompanyDto,
    val currency: CurrencyDto,
    val config: ConfigDto,
    val defaultPricelistId: Long? = null,
    val repBalance: Double = 0.0,
)

/** Small key/value settings (login, counters, printer). */
class Session(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _profile = MutableStateFlow(loadProfile())
    val profile: StateFlow<Profile?> = _profile

    var serverUrl: String
        get() = prefs.getString("server_url", "") ?: ""
        set(v) = prefs.edit().putString("server_url", v.trim()).apply()

    var token: String?
        get() = prefs.getString("token", null)
        set(v) = prefs.edit().putString("token", v).apply()

    val isLoggedIn get() = token != null && _profile.value != null

    val deviceId: String
        get() = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }

    var lastPull: String?
        get() = prefs.getString("last_pull", null)
        set(v) = prefs.edit().putString("last_pull", v).apply()

    var lastSyncAt: Long
        get() = prefs.getLong("last_sync_at", 0)
        set(v) = prefs.edit().putLong("last_sync_at", v).apply()

    var printerAddress: String?
        get() = prefs.getString("printer_address", null)
        set(v) = prefs.edit().putString("printer_address", v).apply()

    var printerName: String?
        get() = prefs.getString("printer_name", null)
        set(v) = prefs.edit().putString("printer_name", v).apply()

    /** Printable width in dots: 384 for 58 mm printers, 576 for 80 mm. */
    var paperDots: Int
        get() = prefs.getInt("paper_dots", 384)
        set(v) = prefs.edit().putInt("paper_dots", v).apply()

    var trackingEnabled: Boolean
        get() = prefs.getBoolean("tracking", true)
        set(v) = prefs.edit().putBoolean("tracking", v).apply()

    var saleSeq: Int
        get() = prefs.getInt("sale_seq", 0)
        set(v) = prefs.edit().putInt("sale_seq", v).apply()

    var collectionSeq: Int
        get() = prefs.getInt("collection_seq", 0)
        set(v) = prefs.edit().putInt("collection_seq", v).apply()

    fun saveProfile(p: Profile) {
        prefs.edit().putString("profile", json.encodeToString(Profile.serializer(), p)).apply()
        _profile.value = p
    }

    private fun loadProfile(): Profile? = prefs.getString("profile", null)?.let {
        runCatching { json.decodeFromString(Profile.serializer(), it) }.getOrNull()
    }

    val decimals get() = _profile.value?.currency?.decimals ?: 0

    fun geofenceConfig(): GeofenceConfig {
        val c = _profile.value?.config ?: ConfigDto()
        return GeofenceConfig(
            radius = c.geofence_radius.toDouble(),
            exitBuffer = c.geofence_exit_buffer.toDouble(),
            maxAccuracy = c.gps_max_accuracy.toDouble(),
        )
    }

    fun logout() {
        val keep = serverUrl
        val device = deviceId
        val printer = printerAddress
        val printerN = printerName
        val dots = paperDots
        prefs.edit().clear().apply()
        serverUrl = keep
        prefs.edit().putString("device_id", device).apply()
        printerAddress = printer; printerName = printerN; paperDots = dots
        _profile.value = null
    }
}
