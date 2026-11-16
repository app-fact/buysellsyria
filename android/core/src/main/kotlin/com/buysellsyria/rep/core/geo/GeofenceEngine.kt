package com.buysellsyria.rep.core.geo

/** A GPS reading. */
data class Fix(val lat: Double, val lng: Double, val accuracy: Float, val timeMillis: Long) {
    val point get() = GeoPoint(lat, lng)
}

/** A customer the rep may visit. [point] is null when the customer has no location yet. */
data class Target(val key: String, val point: GeoPoint?)

data class GeofenceConfig(
    /** Visit opens when the rep is within this distance (m). */
    val radius: Double = 50.0,
    /** Visit closes when the rep is farther than radius + exitBuffer (m). */
    val exitBuffer: Double = 10.0,
    /** Fixes with a worse accuracy (m) are ignored. */
    val maxAccuracy: Double = 40.0,
    /** Consecutive fixes needed to confirm an enter/exit (filters GPS jumps). */
    val confirmFixes: Int = 2,
    /**
     * Neighbouring shops: while a visit is open, switch to another customer only
     * if he is this many metres closer than the current one and inside half the radius.
     */
    val switchMargin: Double = 25.0,
)

sealed class GeofenceEvent {
    abstract val targetKey: String
    abstract val distance: Double
    abstract val fix: Fix

    data class Enter(override val targetKey: String, override val distance: Double, override val fix: Fix) :
        GeofenceEvent()

    data class Exit(override val targetKey: String, override val distance: Double, override val fix: Fix) :
        GeofenceEvent()
}

/**
 * Pure state machine deciding when a visit opens / closes from GPS fixes.
 *
 * - At most one visit is open at a time.
 * - Open: nearest customer within [GeofenceConfig.radius] for N consecutive fixes.
 * - Close: farther than radius + exitBuffer for N consecutive fixes.
 * - A visit opened manually for a customer without coordinates is never auto-closed.
 */
class GeofenceEngine(var config: GeofenceConfig = GeofenceConfig()) {

    /** Key of the customer whose visit is open, or null. Set it when restoring state or after a manual open/close. */
    var openKey: String? = null
        private set

    private var enterCandidate: String? = null
    private var enterCount = 0
    private var exitCount = 0
    private var switchCandidate: String? = null
    private var switchCount = 0

    fun setOpen(key: String?) {
        openKey = key
        resetCounters()
    }

    private fun resetCounters() {
        enterCandidate = null; enterCount = 0; exitCount = 0; switchCandidate = null; switchCount = 0
    }

    /** Feed one fix; returns the events to apply in order (an Exit may be followed by an Enter). */
    fun onFix(fix: Fix, targets: List<Target>): List<GeofenceEvent> {
        if (fix.accuracy > config.maxAccuracy) return emptyList()
        val located = targets.mapNotNull { t -> t.point?.let { t.key to Geo.distanceMeters(fix.point, it) } }
        val nearest = located.minByOrNull { it.second }
        val current = openKey

        if (current == null) {
            exitCount = 0
            if (nearest == null || nearest.second > config.radius) {
                enterCandidate = null; enterCount = 0
                return emptyList()
            }
            if (enterCandidate == nearest.first) enterCount++ else { enterCandidate = nearest.first; enterCount = 1 }
            if (enterCount >= config.confirmFixes) {
                setOpen(nearest.first)
                return listOf(GeofenceEvent.Enter(nearest.first, nearest.second, fix))
            }
            return emptyList()
        }

        val currentDistance = located.firstOrNull { it.first == current }?.second
            ?: return emptyList() // open customer has no coordinates (manual visit): only a manual close

        // Exit check
        if (currentDistance > config.radius + config.exitBuffer) {
            exitCount++
            if (exitCount >= config.confirmFixes) {
                setOpen(null)
                return listOf(GeofenceEvent.Exit(current, currentDistance, fix))
            }
            return emptyList()
        }
        exitCount = 0

        // Neighbour switch check
        if (nearest != null && nearest.first != current &&
            nearest.second <= config.radius / 2 &&
            currentDistance - nearest.second >= config.switchMargin
        ) {
            if (switchCandidate == nearest.first) switchCount++ else { switchCandidate = nearest.first; switchCount = 1 }
            if (switchCount >= config.confirmFixes) {
                setOpen(nearest.first)
                return listOf(
                    GeofenceEvent.Exit(current, currentDistance, fix),
                    GeofenceEvent.Enter(nearest.first, nearest.second, fix),
                )
            }
        } else {
            switchCandidate = null; switchCount = 0
        }
        return emptyList()
    }
}
