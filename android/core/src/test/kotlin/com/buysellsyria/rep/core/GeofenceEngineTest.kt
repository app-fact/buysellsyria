package com.buysellsyria.rep.core

import com.buysellsyria.rep.core.geo.Fix
import com.buysellsyria.rep.core.geo.Geo
import com.buysellsyria.rep.core.geo.GeoPoint
import com.buysellsyria.rep.core.geo.GeofenceConfig
import com.buysellsyria.rep.core.geo.GeofenceEngine
import com.buysellsyria.rep.core.geo.GeofenceEvent
import com.buysellsyria.rep.core.geo.Target
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeofenceEngineTest {
    // Damascus, ~1 m per 0.000009 deg latitude
    private val shop = GeoPoint(33.5138, 36.2765)
    private fun north(m: Double) = GeoPoint(shop.lat + m / 111_195.0, shop.lng)
    private var t = 0L
    private fun fix(p: GeoPoint, acc: Float = 10f) = Fix(p.lat, p.lng, acc, t++)
    private val targets = listOf(Target("A", shop))

    @Test
    fun distanceIsAccurate() {
        val d = Geo.distanceMeters(shop, north(50.0))
        assertTrue(d in 49.5..50.5, "got $d")
    }

    @Test
    fun opensAfterTwoFixesInsideRadiusAndClosesOutside() {
        val e = GeofenceEngine(GeofenceConfig(radius = 50.0, exitBuffer = 10.0))
        assertTrue(e.onFix(fix(north(200.0)), targets).isEmpty())
        assertTrue(e.onFix(fix(north(40.0)), targets).isEmpty()) // first inside fix: not yet
        val open = e.onFix(fix(north(30.0)), targets)
        assertIs<GeofenceEvent.Enter>(open.single())
        assertEquals("A", e.openKey)
        // 55 m: beyond radius but inside exit buffer -> still open
        assertTrue(e.onFix(fix(north(55.0)), targets).isEmpty())
        assertTrue(e.onFix(fix(north(55.0)), targets).isEmpty())
        // 70 m twice -> close
        assertTrue(e.onFix(fix(north(70.0)), targets).isEmpty())
        assertIs<GeofenceEvent.Exit>(e.onFix(fix(north(70.0)), targets).single())
        assertNull(e.openKey)
    }

    @Test
    fun singleGpsJumpDoesNotCloseVisit() {
        val e = GeofenceEngine()
        e.onFix(fix(north(10.0)), targets); e.onFix(fix(north(10.0)), targets)
        assertEquals("A", e.openKey)
        assertTrue(e.onFix(fix(north(300.0)), targets).isEmpty()) // jump
        assertTrue(e.onFix(fix(north(10.0)), targets).isEmpty())
        assertTrue(e.onFix(fix(north(300.0)), targets).isEmpty())
        assertEquals("A", e.openKey)
    }

    @Test
    fun inaccurateFixesAreIgnored() {
        val e = GeofenceEngine()
        e.onFix(fix(north(10.0), acc = 100f), targets)
        e.onFix(fix(north(10.0), acc = 100f), targets)
        assertNull(e.openKey)
    }

    @Test
    fun switchesToNeighbourShopWhenClearlyCloser() {
        val b = north(40.0)
        val two = listOf(Target("A", shop), Target("B", b))
        val e = GeofenceEngine()
        e.onFix(fix(north(0.0)), two); e.onFix(fix(north(0.0)), two)
        assertEquals("A", e.openKey)
        e.onFix(fix(north(39.0)), two)
        val events = e.onFix(fix(north(39.0)), two)
        assertEquals(listOf("A", "B"), events.map { it.targetKey })
        assertIs<GeofenceEvent.Exit>(events[0]); assertIs<GeofenceEvent.Enter>(events[1])
        assertEquals("B", e.openKey)
    }

    @Test
    fun manualVisitWithoutLocationIsNotAutoClosed() {
        val e = GeofenceEngine()
        e.setOpen("NOLOC")
        val ts = targets + Target("NOLOC", null)
        repeat(5) { assertTrue(e.onFix(fix(north(500.0)), ts).isEmpty()) }
        assertEquals("NOLOC", e.openKey)
    }
}
