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
    fun `unnamed sign takes its name from the place it belongs to`() {
        val sign = Overpass.parseSigns(response).first { it.id == 6371351384L }
        // No name on the sign itself, so the place it lies inside supplies the name and the direction.
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
    fun `query asks for signs in the box and places around it`() {
        val query = Overpass.query(BoundingBox(55.85, 12.25, 55.90, 12.35))
        assertTrue(query.contains("[out:json]"))
        assertTrue(query.contains("out center qt;"))

        // Signs come from the box itself.
        assertTrue(query.contains("node(55.850000,12.250000,55.900000,12.350000)[~"))

        // Places are collected from a wider area, as nodes, ways and relations, so a sign near the
        // edge can still find the town it names no matter which cell it is looked up from.
        val placeLines = query.lines().filter { it.contains("\"place\"") }
        assertEquals(3, placeLines.size)
        assertTrue(placeLines.any { it.trim().startsWith("node(") })
        assertTrue(placeLines.any { it.trim().startsWith("way(") })
        assertTrue(placeLines.any { it.trim().startsWith("relation(") })
        placeLines.forEach { line ->
            val box = line.substringAfter('(').substringBefore(')').split(',').map { it.toDouble() }
            assertTrue("place box should reach south of the sign box", box[0] < 55.85)
            assertTrue("place box should reach west of the sign box", box[1] < 12.25)
            assertTrue("place box should reach north of the sign box", box[2] > 55.90)
            assertTrue("place box should reach east of the sign box", box[3] > 12.35)
        }
    }

    @Test
    fun `a named sign without a matching place has no direction`() {
        // Real case from Odsherred: signs reading "Lyngen", where no place of that name is mapped.
        // Matching them to the nearest hamlet pointed the arrow at a neighbouring village - and at a
        // different one depending on which area had been downloaded.
        val body = """
            {"elements":[
              {"type":"node","id":6348603353,"lat":55.8830000,"lon":11.5400000,
               "tags":{"traffic_sign":"city_limit","name":"Lyngen"}},
              {"type":"node","id":10,"lat":55.8809000,"lon":11.5384000,
               "tags":{"name":"Ellinge Kongepart","place":"hamlet"}},
              {"type":"node","id":11,"lat":55.8764000,"lon":11.5426000,
               "tags":{"name":"Ellinge Lyng","place":"hamlet"}}
            ]}
        """.trimIndent()
        val sign = Overpass.parseSigns(body).single()
        assertEquals("Lyngen", sign.name)
        assertNull("a named sign must not borrow a neighbour's direction", sign.entryHeading)
        assertNull(sign.townId)
    }

    @Test
    fun `spacing in a name does not matter`() {
        // Real case: the sign reads "Vesterlyng", the hamlet is mapped as "Vester Lyng".
        val signPosition = LatLng(55.9300, 11.6400)
        val places = listOf(
            PlaceNode(10038782603, LatLng(55.9348, 11.6438), "Vester Lyng", "hamlet"),
            PlaceNode(2512942163, LatLng(55.9312, 11.6967), "Øster Lyng", "village"),
        )
        assertEquals(10038782603L, Overpass.matchPlace(signPosition, "Vesterlyng", places)?.id)
        assertEquals(10038782603L, Overpass.matchPlace(signPosition, "Vester  Lyng", places)?.id)
    }

    @Test
    fun `a sign drops the town's regional qualifier`() {
        // The sign into Nykøbing Sjælland reads "Nykøbing". The hamlet "Nykøbing Lyng" carries the
        // same prefix, so the more significant place has to win.
        val signPosition = LatLng(55.9140, 11.6530)
        val places = listOf(
            PlaceNode(21686563, LatLng(55.9233, 11.6690), "Nykøbing Sjælland", "village"),
            PlaceNode(4607335659, LatLng(55.9416, 11.6782), "Nykøbing Lyng", "hamlet"),
        )
        assertEquals(21686563L, Overpass.matchPlace(signPosition, "Nykøbing", places)?.id)
    }

    @Test
    fun `a qualifier match works in both directions`() {
        val signPosition = LatLng(55.8800, 11.5400)
        val places = listOf(PlaceNode(1, LatLng(55.8700, 11.5400), "Ellinge", "village"))
        assertEquals(1L, Overpass.matchPlace(signPosition, "Ellinge Lyng", places)?.id)
    }

    @Test
    fun `an exact name still beats a qualifier match`() {
        val signPosition = LatLng(55.8700, 11.5500)
        val places = listOf(
            PlaceNode(1, LatLng(55.8600, 11.5300), "Hønsinge Lyng", "hamlet"),
            PlaceNode(2, LatLng(55.8648, 11.5562), "Hønsinge", "hamlet"),
        )
        assertEquals(2L, Overpass.matchPlace(signPosition, "Hønsinge", places)?.id)
    }

    @Test
    fun `an ambiguous qualifier match is no match`() {
        // Three equally significant hamlets share the prefix, and they lie in different directions.
        val signPosition = LatLng(55.8850, 11.5400)
        val places = listOf(
            PlaceNode(1, LatLng(55.8764, 11.5426), "Ellinge Lyng", "hamlet"),
            PlaceNode(2, LatLng(55.8809, 11.5384), "Ellinge Kongepart", "hamlet"),
            PlaceNode(3, LatLng(55.8983, 11.5618), "Ellinge Kohave", "hamlet"),
        )
        assertNull(Overpass.matchPlace(signPosition, "Ellinge", places))
    }

    @Test
    fun `a qualifier match does not cross unrelated names`() {
        val signPosition = LatLng(55.8850, 11.5400)
        val places = listOf(PlaceNode(1, LatLng(55.8800, 11.5400), "Nykøbing Sjælland", "village"))
        assertNull(Overpass.matchPlace(signPosition, "Nyk", places))
        assertNull(Overpass.matchPlace(signPosition, "Lyngen", places))
    }

    @Test
    fun `a sign with an unknown direction raises no alert by default`() {
        val sign = CityLimitSign(id = 1, position = LatLng(55.8830, 11.5400), name = "Lyngen", entryHeading = null)
        val position = sign.position.destination(150.0, 0.0)
        assertNull(ApproachDetector().update(position, heading = 180.0, signs = listOf(sign), nowMillis = 0))
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

    /*
     * A sign with no name belongs to the town it lies deepest inside, not to whatever is nearest.
     * A town's boundary is kilometres from its centre, and the nearest mapped place out there is
     * usually an outlying farm.
     */
    @Test
    fun `a town outreaches a hamlet nearer by for an unnamed sign`() {
        val places = listOf(
            PlaceNode(1, LatLng(55.8865000, 12.2800000), "Købstad", "town", isArea = false),
            PlaceNode(2, LatLng(55.8973000, 12.2800000), "Udflyttergård", "hamlet", isArea = false),
        )
        // The hamlet is 300 m away and the town 1.5 km, and the sign still belongs to the town.
        assertEquals(1L, Overpass.matchPlace(LatLng(55.9000000, 12.2800000), null, places)?.id)
        // Standing on the hamlet, though, the hamlet is the answer.
        assertEquals(2L, Overpass.matchPlace(LatLng(55.8974000, 12.2800000), null, places)?.id)
    }

    @Test
    fun `an area centre is a town like any other`() {
        val signPosition = LatLng(55.9000000, 12.2800000)
        val places = listOf(
            PlaceNode(1, LatLng(55.8960000, 12.2800000), "Nodeby", "hamlet", isArea = false),
            PlaceNode(2, LatLng(55.8990000, 12.2800000), "Arealby", "town", isArea = true),
        )
        assertEquals(2L, Overpass.matchPlace(signPosition, null, places)?.id)
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
