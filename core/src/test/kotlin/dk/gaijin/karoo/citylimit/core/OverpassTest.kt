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
    fun `query contains the bounding box and every filter`() {
        val query = Overpass.query(BoundingBox(55.85, 12.25, 55.90, 12.35))
        assertTrue(query.contains("55.850000,12.250000,55.900000,12.350000"))
        assertTrue(query.contains("traffic_sign"))
        assertTrue(query.contains("[out:json]"))
        // Places are collected as nodes, ways and relations so towns mapped only as an area count.
        assertTrue(query.contains("""node(55.850000,12.250000,55.900000,12.350000)["place"~"""".trimMargin()))
        assertTrue(query.contains("""way(55.850000,12.250000,55.900000,12.350000)["place"~"""".trimMargin()))
        assertTrue(query.contains("""relation(55.850000,12.250000,55.900000,12.350000)["place"~"""".trimMargin()))
        assertTrue(query.contains("out center qt;"))
    }

    @Test
    fun `a town mapped only as an area still gives a direction`() {
        val body = """
            {"elements":[
              {"type":"node","id":1,"lat":55.9100000,"lon":12.2800000,
               "tags":{"traffic_sign":"city_limit","name":"Arealby"}},
              {"type":"way","id":500,"center":{"lat":55.9000000,"lon":12.2800000},
               "tags":{"name":"Arealby","place":"village"}}
            ]}
        """.trimIndent()
        val sign = Overpass.parseSigns(body).single()
        // The area centre lies due south of the sign, so riding in means riding south.
        assertEquals(180.0, sign.entryHeading!!, 2.0)
        assertEquals(500L, sign.townId)
        assertEquals("Arealby", sign.name)
    }

    @Test
    fun `an area tagged as a sign is not treated as a sign`() {
        val body = """
            {"elements":[
              {"type":"way","id":600,"center":{"lat":55.90,"lon":12.28},
               "tags":{"traffic_sign":"city_limit","name":"Vejby"}}
            ]}
        """.trimIndent()
        assertTrue(Overpass.parseSigns(body).isEmpty())
    }

    @Test
    fun `a mapped node beats an area centre for an unnamed sign`() {
        val signPosition = LatLng(55.9000000, 12.2800000)
        val places = listOf(
            PlaceNode(1, LatLng(55.8960000, 12.2800000), "Nodeby", "village", isArea = false),
            PlaceNode(2, LatLng(55.8990000, 12.2800000), "Arealby", "suburb", isArea = true),
        )
        // The area centre is closer, but a node sits at the real town centre.
        assertEquals(1L, Overpass.matchPlace(signPosition, null, places)?.id)
    }

    @Test
    fun `an area with the right name beats an unrelated node`() {
        val signPosition = LatLng(55.9000000, 12.2800000)
        val places = listOf(
            PlaceNode(1, LatLng(55.8980000, 12.2800000), "Nabolandsby", "village", isArea = false),
            PlaceNode(2, LatLng(55.8950000, 12.2800000), "Skiltby", "town", isArea = true),
        )
        assertEquals(2L, Overpass.matchPlace(signPosition, "Skiltby", places)?.id)
    }

    @Test
    fun `empty response parses to no signs`() {
        assertTrue(Overpass.parseSigns("""{"elements":[]}""").isEmpty())
    }
}
