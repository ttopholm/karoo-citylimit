package dk.gaijin.karoo.citylimit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A sign belongs to the road it stands on.
 *
 * The case that prompted this is Kulhuse in Hornsherred, from real OpenStreetMap data: two signs
 * reading "Kulhuse" stand on side roads running 58°/238° while the through road, Kulhusvej, runs
 * 314°/134°. One of them is 8 metres from Kulhusvej, so no measure of "how far to the side" can tell
 * it apart — riding north-west along Kulhusvej announced a town the rider was only passing.
 */
class RoadBearingTest {
    /** On Solsortevej, a residential road off Kulhusvej. */
    private val sideRoadSign = CityLimitSign(
        id = 7996794555,
        position = LatLng(55.91813, 11.92277),
        name = "Kulhuse",
        entryHeading = 333.0,
        roadBearing = 58.0,
    )

    /** On Kulhusvej itself. */
    private val throughRoadSign = CityLimitSign(
        id = 13935704590,
        position = LatLng(55.93532, 11.90776),
        name = "Kulhuse",
        entryHeading = 154.4,
        roadBearing = 314.0,
    )

    private fun approaching(sign: CityLimitSign, heading: Double, distance: Double = 150.0) =
        sign.position.destination(distance, normalizeBearing(heading + 180.0))

    @Test
    fun `riding past a sign on a side road says nothing`() {
        val heading = 330.0
        val alert = ApproachDetector().update(
            position = approaching(sideRoadSign, heading),
            heading = heading,
            signs = listOf(sideRoadSign),
            nowMillis = 0,
        )
        assertNull("a sign on a road across yours is not your sign", alert)
    }

    @Test
    fun `riding along the road the sign stands on still announces it`() {
        val heading = 134.0
        val alert = ApproachDetector().update(
            position = approaching(throughRoadSign, heading),
            heading = heading,
            signs = listOf(throughRoadSign),
            nowMillis = 0,
        )
        assertNotNull(alert)
        assertEquals("Kulhuse", alert!!.sign.name)
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
        val heading = 333.0
        assertNotNull(
            ApproachDetector().update(
                position = approaching(unknownRoad, heading),
                heading = heading,
                signs = listOf(unknownRoad),
                nowMillis = 0,
            ),
        )
    }

    @Test
    fun `a bend in the road is still the same road`() {
        // Roads are not straight: the alert fires from a couple of hundred metres back, where the
        // rider's heading can differ from the road at the sign. 45 degrees leaves room for that.
        val heading = 100.0
        assertNotNull(
            ApproachDetector().update(
                position = approaching(throughRoadSign, heading),
                heading = heading,
                signs = listOf(throughRoadSign.copy(entryHeading = 120.0)),
                nowMillis = 0,
            ),
        )
    }
}
