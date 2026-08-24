package dk.gaijin.karoo.citylimit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end check against a real Overpass response (OpenStreetMap data, © OpenStreetMap
 * contributors, ODbL) covering the villages north-west of Allerød, Denmark.
 *
 * A rider is moved along the road through Nørre Herlev in both directions; the town must be
 * announced once when riding in, and never when riding out past the crossed-out sign.
 */
class RideSimulationTest {
    private val signs: List<CityLimitSign> by lazy {
        val body = requireNotNull(javaClass.getResourceAsStream("/overpass-noerre-herlev.json"))
            .bufferedReader()
            .use { it.readText() }
        Overpass.parseSigns(body)
    }

    /** Northern town-entry sign of Nørre Herlev. */
    private val northSign = LatLng(55.8955018, 12.2774175)

    /** Southern town-entry sign of the same village. */
    private val southSign = LatLng(55.8872001, 12.2765744)

    @Test
    fun `fixture contains signs for several towns`() {
        assertTrue(signs.size >= 15)
        assertTrue(signs.any { it.name == "Nørre Herlev" })
        assertTrue(signs.any { it.name == "Lillerød" })
        assertTrue("every sign should have a direction into town", signs.all { it.entryHeading != null })
    }

    @Test
    fun `riding south through the village announces it once`() {
        val alerts = ride(from = extend(northSign, southSign, 800.0), to = extend(southSign, northSign, 800.0))
        assertEquals(listOf("Nørre Herlev"), alerts.map { it.sign.name })
    }

    @Test
    fun `riding north through the village announces it once`() {
        val alerts = ride(from = extend(southSign, northSign, 800.0), to = extend(northSign, southSign, 800.0))
        assertEquals(listOf("Nørre Herlev"), alerts.map { it.sign.name })
    }

    @Test
    fun `the alert comes before the sign, not after it`() {
        val alerts = ride(from = extend(northSign, southSign, 800.0), to = extend(southSign, northSign, 800.0))
        val alert = alerts.single()
        assertTrue("alert raised ${alert.distanceMeters} m before the sign", alert.distanceMeters > 100.0)
        assertTrue(alert.distanceMeters <= DetectorConfig().alertDistanceMeters)
    }

    @Test
    fun `standing still at the sign does not announce anything`() {
        val detector = ApproachDetector()
        val position = extend(northSign, southSign, 150.0)
        repeat(20) { step ->
            assertEquals(null, detector.update(position, heading = null, signs = signs, nowMillis = step * 1_000L))
        }
    }

    /**
     * Ride a straight line in 10 m steps, feeding every position to the detector like the
     * extension does with location updates.
     */
    private fun ride(from: LatLng, to: LatLng): List<CityLimitAlert> {
        val detector = ApproachDetector()
        val heading = from.bearingTo(to)
        val total = from.distanceTo(to)
        val alerts = ArrayList<CityLimitAlert>()
        var travelled = 0.0
        var time = 0L
        while (travelled <= total) {
            val position = from.destination(travelled, heading)
            detector.update(position, heading, signs, time)?.let { alerts.add(it) }
            travelled += 10.0
            // 10 m at ~25 km/h
            time += 1_400
        }
        return alerts
    }

    /** A point [meters] beyond [point], on the line coming from [towards]. */
    private fun extend(point: LatLng, towards: LatLng, meters: Double): LatLng =
        point.destination(meters, point.bearingTo(towards) + 180.0)
}
