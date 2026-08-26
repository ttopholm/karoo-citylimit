package dk.gaijin.karoo.citylimit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A sign belongs to the road it stands on, and is entered along that road.
 *
 * The case that prompted this is Kulhuse in Hornsherred, from real OpenStreetMap data. Kulhusvej
 * runs past the village at 314/134; the signs stand on short link roads off it, running 58/238. A
 * sign 8 metres from Kulhusvej must stay quiet for a rider carrying on along it, and must speak for
 * one who turns off onto the road it stands on.
 */
class RoadBearingTest {
    /** On Solsortevej, a link road off Kulhusvej; you enter the village riding down it, at 238. */
    private val sideRoadSign = CityLimitSign(
        id = 7996794555,
        position = LatLng(55.91813, 11.92277),
        name = "Kulhuse",
        entryHeading = 237.9,
        roadBearing = 57.9,
    )

    /** On Kulhusvej itself. */
    private val throughRoadSign = CityLimitSign(
        id = 13935704590,
        position = LatLng(55.93532, 11.90776),
        name = "Kulhuse",
        entryHeading = 134.0,
        roadBearing = 314.0,
    )

    private fun approaching(sign: CityLimitSign, heading: Double, distance: Double = 150.0) =
        sign.position.destination(distance, normalizeBearing(heading + 180.0))

    private fun alertFor(sign: CityLimitSign, heading: Double, distance: Double = 150.0) =
        ApproachDetector().update(
            position = approaching(sign, heading, distance),
            heading = heading,
            signs = listOf(sign),
            nowMillis = 0,
        )

    @Test
    fun `riding past a sign on a side road says nothing`() {
        assertNull("a sign on a road across yours is not your sign", alertFor(sideRoadSign, 330.0))
    }

    @Test
    fun `turning off onto the road the sign stands on announces the town`() {
        // Coming down Kulhusvej and turning left into Kulhuse: the sign sits metres past the corner.
        val alert = alertFor(sideRoadSign, 238.0, distance = 40.0)
        assertNotNull("turning in is entering the town", alert)
        assertEquals("Kulhuse", alert!!.sign.name)
    }

    @Test
    fun `riding back out of the side road says nothing`() {
        assertNull(alertFor(sideRoadSign, 58.0, distance = 40.0))
    }

    @Test
    fun `riding along the road the sign stands on still announces it`() {
        val alert = alertFor(throughRoadSign, 134.0)
        assertNotNull(alert)
        assertEquals("Kulhuse", alert!!.sign.name)
    }

    @Test
    fun `the road rules out a sign the town direction would have allowed`() {
        // 300 is within the 80 degrees of the entry heading, but 62 off the road: not this rider's.
        assertEquals(62.0, bearingDifference(300.0, 237.9), 0.2)
        assertEquals(62.1, lineDifference(300.0, 57.9), 0.2)
        assertNull(alertFor(sideRoadSign, 300.0))
    }

    @Test
    fun `the road counts either way round`() {
        // Kulhusvej runs 314/134; a rider heading 134 is on it just as much as one heading 314.
        assertEquals(0.0, lineDifference(134.0, 314.0), 0.001)
        assertEquals(20.0, lineDifference(154.0, 314.0), 0.001)
        assertEquals(88.0, lineDifference(330.0, 58.0), 0.001)
        assertEquals(90.0, lineDifference(0.0, 90.0), 0.001)
        assertEquals(10.0, lineDifference(5.0, 175.0), 0.001)
    }

    @Test
    fun `a sign that does not know its road behaves as before`() {
        // Live cells fetched from Overpass carry no road, and must not go silent because of it.
        val unknownRoad = sideRoadSign.copy(roadBearing = null)
        assertNotNull(alertFor(unknownRoad, 238.0))
    }

    @Test
    fun `a bend in the road is still the same road`() {
        // Roads are not straight: the alert fires from a couple of hundred metres back, where the
        // rider's heading can differ from the road at the sign. 45 degrees leaves room for that.
        assertNotNull(alertFor(throughRoadSign.copy(entryHeading = 120.0), 100.0))
    }
}
