package dk.gaijin.karoo.citylimit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverpassTest {
    /** Trimmed real response from overpass-api.de around Nørre Herlev, Denmark. */
    private val response = """
        {
          "version": 0.6,
          "generator": "Overpass API",
          "elements": [
            {"type":"node","id":6369208480,"lat":55.8955018,"lon":12.2774175,
             "tags":{"direction":"backward","name":"Nørre Herlev","traffic_sign":"city_limit"}},
            {"type":"node","id":2669730909,"lat":55.8888146,"lon":12.2783865,
             "tags":{"direction":"backward","name":"Nørre Herlev","traffic_sign":"city_limit"}},
            {"type":"node","id":6371351384,"lat":55.8909104,"lon":12.2768885,
             "tags":{"traffic_sign":"city_limit"}},
            {"type":"node","id":999000001,"lat":55.8830000,"lon":12.2700000,
             "tags":{"traffic_sign":"DK:E56","name":"Nørre Herlev"}},
            {"type":"node","id":10292867727,"lat":55.8889703,"lon":12.2740223,
             "tags":{"name":"Nørre Herlev","place":"village","population":"429"}},
            {"type":"node","id":10292867722,"lat":55.9025827,"lon":12.2412664,
             "tags":{"name":"Freerslev","place":"hamlet","population":"87"}}
          ]
        }
    """.trimIndent()

    @Test
    fun `crossed out signs are dropped and entry signs kept`() {
        val signs = Overpass.parseSigns(response)
        val ids = signs.map { it.id }.toSet()
        assertTrue(ids.contains(6369208480L))
        assertTrue(ids.contains(2669730909L))
        assertTrue(ids.contains(6371351384L))
        assertTrue("exit-only sign must not produce an alert", !ids.contains(999000001L))
        assertEquals(3, signs.size)
    }

    @Test
    fun `entry heading points from the sign towards the town`() {
        val signs = Overpass.parseSigns(response).associateBy { it.id }

        // North of the village: entering means riding south.
        val fromNorth = signs.getValue(6369208480L)
        assertEquals("Nørre Herlev", fromNorth.name)
        assertEquals(180.0, fromNorth.entryHeading!!, 25.0)

        // East of the village: entering means riding west.
        val fromEast = signs.getValue(2669730909L)
        assertEquals(270.0, fromEast.entryHeading!!, 20.0)
    }

    @Test
    fun `unnamed sign falls back to the nearest place`() {
        val sign = Overpass.parseSigns(response).first { it.id == 6371351384L }
        // No name on the sign itself, so the nearest place supplies both the name and the direction.
        assertEquals("Nørre Herlev", sign.name)
        assertEquals(10292867727L, sign.townId)
        assertNotNull(sign.entryHeading)
        assertTrue(sign.genericBoundary)
    }

    @Test
    fun `named place further away still wins over a closer unrelated place`() {
        val signPosition = LatLng(55.9000000, 12.2500000)
        val places = listOf(
            PlaceNode(1, LatLng(55.9010000, 12.2510000), "Freerslev", "hamlet"),
            PlaceNode(2, LatLng(55.8889703, 12.2740223), "Nørre Herlev", "village"),
        )
        val matched = Overpass.matchPlace(signPosition, "Nørre Herlev", places)
        assertEquals(2L, matched?.id)
    }

    @Test
    fun `sign without any nearby place has no entry heading`() {
        val heading = Overpass.entryHeading(LatLng(55.0, 12.0), Overpass.matchPlace(LatLng(55.0, 12.0), "Nowhere", emptyList()))
        assertNull(heading)
    }

    @Test
    fun `query contains the bounding box and both node filters`() {
        val query = Overpass.query(BoundingBox(55.85, 12.25, 55.90, 12.35))
        assertTrue(query.contains("55.850000,12.250000,55.900000,12.350000"))
        assertTrue(query.contains("traffic_sign"))
        assertTrue(query.contains("place"))
        assertTrue(query.contains("[out:json]"))
    }

    @Test
    fun `empty response parses to no signs`() {
        assertTrue(Overpass.parseSigns("""{"elements":[]}""").isEmpty())
    }
}
