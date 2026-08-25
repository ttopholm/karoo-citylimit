package dk.gaijin.karoo.citylimit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ApproachDetectorTest {
    private val townCentre = LatLng(55.8889703, 12.2740223)

    /** Sign north of town: a rider entering the town heads south. */
    private val sign = CityLimitSign(
        id = 1,
        position = LatLng(55.8955018, 12.2774175),
        name = "Nørre Herlev",
        entryHeading = 180.0,
    )

    private fun approaching(distanceMeters: Double, fromBearing: Double = 0.0): LatLng =
        sign.position.destination(distanceMeters, fromBearing)

    @Test
    fun `alerts when riding into town`() {
        val detector = ApproachDetector()
        val position = approaching(180.0)
        val alert = detector.update(position, heading = 180.0, signs = listOf(sign), nowMillis = 0)
        assertNotNull(alert)
        assertEquals(sign.id, alert!!.sign.id)
        assertEquals(180.0, alert.distanceMeters, 5.0)
    }

    @Test
    fun `does not alert when leaving town`() {
        val detector = ApproachDetector()
        // South of the sign, riding north: this is the crossed-out sign's side.
        val position = sign.position.destination(180.0, 180.0)
        assertNull(detector.update(position, heading = 0.0, signs = listOf(sign), nowMillis = 0))
    }

    @Test
    fun `does not alert for a sign that is already behind`() {
        val detector = ApproachDetector()
        // Just past the sign, still heading into town.
        val position = sign.position.destination(50.0, 180.0)
        assertNull(detector.update(position, heading = 180.0, signs = listOf(sign), nowMillis = 0))
    }

    @Test
    fun `does not alert while still far away`() {
        val detector = ApproachDetector()
        val position = approaching(600.0)
        assertNull(detector.update(position, heading = 180.0, signs = listOf(sign), nowMillis = 0))
    }

    @Test
    fun `does not alert when crossing the sign sideways`() {
        val detector = ApproachDetector()
        val position = sign.position.destination(150.0, 270.0)
        // Riding north, sign is to the east: not approaching it.
        assertNull(detector.update(position, heading = 0.0, signs = listOf(sign), nowMillis = 0))
    }

    @Test
    fun `does not alert without a heading`() {
        val detector = ApproachDetector()
        assertNull(detector.update(approaching(150.0), heading = null, signs = listOf(sign), nowMillis = 0))
    }

    @Test
    fun `same town is only announced once within the cooldown`() {
        val detector = ApproachDetector()
        assertNotNull(detector.update(approaching(190.0), 180.0, listOf(sign), nowMillis = 0))
        assertNull(detector.update(approaching(190.0), 180.0, listOf(sign), nowMillis = 5_000))
        // Another sign of the same town, e.g. a parallel road, is suppressed too.
        val sameTown = sign.copy(id = 2, position = sign.position.destination(30.0, 90.0))
        assertNull(detector.update(approaching(190.0), 180.0, listOf(sameTown), nowMillis = 10_000))
        // After the cooldown the town can be announced again.
        assertNotNull(detector.update(approaching(190.0), 180.0, listOf(sign), nowMillis = 11 * 60 * 1000))
    }

    @Test
    fun `reset forgets announced towns`() {
        val detector = ApproachDetector()
        assertNotNull(detector.update(approaching(190.0), 180.0, listOf(sign), nowMillis = 0))
        detector.reset()
        assertNotNull(detector.update(approaching(190.0), 180.0, listOf(sign), nowMillis = 1_000))
    }

    @Test
    fun `signs with unknown direction are skipped by default and can be opted in`() {
        val unknown = sign.copy(entryHeading = null, name = "Ukendt")
        val strict = ApproachDetector()
        assertNull(strict.update(approaching(190.0), 180.0, listOf(unknown), nowMillis = 0))

        val lenient = ApproachDetector(DetectorConfig(alertWhenDirectionUnknown = true))
        assertNotNull(lenient.update(approaching(190.0), 180.0, listOf(unknown), nowMillis = 0))
    }

    @Test
    fun `closest matching sign wins`() {
        val detector = ApproachDetector()
        // A second town further down the same road, still inside the alert distance.
        val further = sign.copy(id = 3, name = "Fjerntby", position = sign.position.destination(50.0, 180.0))
        val position = approaching(150.0)
        val alert = detector.update(position, 180.0, listOf(further, sign), nowMillis = 0)
        assertEquals(sign.id, alert!!.sign.id)
    }

    @Test
    fun `alert distance is configurable`() {
        val detector = ApproachDetector(DetectorConfig(alertDistanceMeters = 400.0))
        assertNotNull(detector.update(approaching(350.0), 180.0, listOf(sign), nowMillis = 0))
    }

    @Test
    fun `town centre is used as the entry direction reference`() {
        // Sanity check on the fixture: the sign really is north of the town centre, so riding
        // towards the centre means riding south.
        assertEquals(196.0, sign.position.bearingTo(townCentre), 2.0)
    }
}
