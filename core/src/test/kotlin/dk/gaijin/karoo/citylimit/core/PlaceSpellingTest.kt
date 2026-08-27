package dk.gaijin.karoo.citylimit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A sign and a place naming the same town do not always spell it the same way.
 *
 * Every case here is real, from the Danish sign data. Before this rule, 431 named signs found no
 * town at all and were left out of the packs for want of a direction.
 */
class PlaceSpellingTest {
    private fun place(name: String, lat: Double = 55.8790, lng: Double = 11.6690, kind: String = "hamlet") =
        PlaceNode(1, LatLng(lat, lng), name, kind)

    /** The sign 373 m from the hamlet Strandhuse, node 11112564951 in Odsherred. */
    private val strandhusene = LatLng(55.8764611, 11.6705286)

    @Test
    fun `a sign speaks of the town it is entering`() {
        // "Strandhusene" is "Strandhuse" in the definite form. So is "Øerne" for the sign "Øer".
        assertEquals(1L, Overpass.matchPlace(strandhusene, "Strandhusene", listOf(place("Strandhuse")))?.id)
        assertEquals(1L, Overpass.matchPlace(strandhusene, "Øer", listOf(place("Øerne")))?.id)
    }

    @Test
    fun `an abbreviation on the sign is written out`() {
        val position = LatLng(55.5900, 11.8600)
        val hvalsoe = place("Kirke Hvalsø", 55.5905, 11.8610, "village")
        assertEquals(1L, Overpass.matchPlace(position, "Kr. Hvalsø", listOf(hvalsoe))?.id)
        assertEquals(1L, Overpass.matchPlace(position, "Kr Hvalsø", listOf(hvalsoe))?.id)
    }

    @Test
    fun `one letter wrong is still the same town`() {
        // Real pairs: the sign says one thing, the map another, and they are metres apart.
        val cases = listOf(
            "Feldbulle" to "Feldballe",
            "Ganløsev" to "Ganløse",
            "Slaglunde" to "Slagslunde",
            "Hårmark" to "Hårdmark",
            "Naurbjerg" to "Navrbjerg",
        )
        for ((sign, town) in cases) {
            val position = LatLng(56.2000, 10.5000)
            val places = listOf(place(town, 56.2010, 10.5010, "village"))
            assertEquals("$sign should find $town", 1L, Overpass.matchPlace(position, sign, places)?.id)
        }
    }

    @Test
    fun `letters that sound alike count as one`() {
        // "Åes" against "Ås" is three letters apart written out, one when å and aa are the same.
        val position = LatLng(56.0000, 10.0000)
        assertEquals(1L, Overpass.matchPlace(position, "Åes", listOf(place("Ås", 56.0010, 10.0010)))?.id)
    }

    @Test
    fun `a direction word is the name, not a slip of the pen`() {
        // Øster and Vester Sottrup are one letter apart and two villages a kilometre apart.
        val position = LatLng(54.9600, 9.6800)
        val vester = listOf(place("Vester Sottrup", 54.9605, 9.6810, "village"))
        assertNull(Overpass.matchPlace(position, "Øster Sottrup", vester))
        assertNull(Overpass.matchPlace(position, "Nørre Alling", listOf(place("Sønder Alling", 54.9605, 9.6810))))
    }

    @Test
    fun `two towns spelled equally close are no answer`() {
        val position = LatLng(56.0000, 10.0000)
        val places = listOf(
            PlaceNode(1, LatLng(56.0010, 10.0010), "Hoven", "hamlet"),
            PlaceNode(2, LatLng(56.0012, 10.0012), "Hoves", "hamlet"),
        )
        assertNull("one letter from each of them is one letter from neither",
            Overpass.matchPlace(position, "Hover", places))
    }

    @Test
    fun `a town too far away is not the one on the sign`() {
        // Within a kilometre a different spelling is worth believing; beyond it, it is a guess.
        val position = LatLng(56.0000, 10.0000)
        val near = place("Feldballe", 56.0050, 10.0000, "village")
        val far = place("Feldballe", 56.0500, 10.0000, "village")
        assertEquals(1L, Overpass.matchPlace(position, "Feldbulle", listOf(near))?.id)
        assertNull(Overpass.matchPlace(position, "Feldbulle", listOf(far)))
    }

    @Test
    fun `a short name is left alone`() {
        // One letter of three is a different place, not a misspelling.
        val position = LatLng(56.0000, 10.0000)
        assertNull(Overpass.matchPlace(position, "Hem", listOf(place("Hee", 56.0010, 10.0010))))
    }

    @Test
    fun `an exact match is never overruled`() {
        val position = LatLng(56.0000, 10.0000)
        val places = listOf(
            PlaceNode(1, LatLng(56.0060, 10.0000), "Hørning", "village"),
            PlaceNode(2, LatLng(56.0005, 10.0000), "Hørninge", "hamlet"),
        )
        assertEquals("the sign's own spelling wins, however close the other one sits",
            1L, Overpass.matchPlace(position, "Hørning", places)?.id)
    }

    @Test
    fun `a sign naming a town nobody mapped still finds nothing`() {
        // The Odsherred case this whole rule has to stay clear of.
        val position = LatLng(55.8850, 11.5400)
        val places = listOf(PlaceNode(1, LatLng(55.8800, 11.5400), "Nykøbing Sjælland", "village"))
        assertNull(Overpass.matchPlace(position, "Lyngen", places))
    }
}
