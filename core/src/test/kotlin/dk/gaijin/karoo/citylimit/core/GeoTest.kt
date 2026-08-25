package dk.gaijin.karoo.citylimit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    private val hillerod = LatLng(55.9339, 12.3010)

    @Test
    fun `distance between two known points`() {
        val copenhagen = LatLng(55.6761, 12.5683)
        // ~33 km as the crow flies
        assertEquals(33_180.0, hillerod.distanceTo(copenhagen), 200.0)
    }

    @Test
    fun `distance to self is zero`() {
        assertEquals(0.0, hillerod.distanceTo(hillerod), 0.001)
    }

    @Test
    fun `bearing north and east`() {
        assertEquals(0.0, hillerod.bearingTo(LatLng(56.0, 12.3010)), 0.5)
        assertEquals(90.0, hillerod.bearingTo(LatLng(55.9339, 12.5)), 0.5)
        assertEquals(180.0, hillerod.bearingTo(LatLng(55.8, 12.3010)), 0.5)
        assertEquals(270.0, hillerod.bearingTo(LatLng(55.9339, 12.0)), 0.5)
    }

    @Test
    fun `destination round trips with bearing and distance`() {
        val target = hillerod.destination(1_000.0, 135.0)
        assertEquals(1_000.0, hillerod.distanceTo(target), 1.0)
        assertEquals(135.0, hillerod.bearingTo(target), 0.5)
    }

    @Test
    fun `bearing difference wraps around north`() {
        assertEquals(20.0, bearingDifference(350.0, 10.0), 0.001)
        assertEquals(20.0, bearingDifference(10.0, 350.0), 0.001)
        assertEquals(180.0, bearingDifference(0.0, 180.0), 0.001)
        assertEquals(179.0, bearingDifference(0.0, 181.0), 0.001)
        assertEquals(0.0, bearingDifference(-10.0, 350.0), 0.001)
    }

    @Test
    fun `polyline decodes to expected points`() {
        // Example from Google's encoded polyline documentation.
        val points = decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, points.size)
        assertEquals(38.5, points[0].lat, 1e-5)
        assertEquals(-120.2, points[0].lng, 1e-5)
        assertEquals(40.7, points[1].lat, 1e-5)
        assertEquals(-120.95, points[1].lng, 1e-5)
        assertEquals(43.252, points[2].lat, 1e-5)
        assertEquals(-126.453, points[2].lng, 1e-5)
    }

    @Test
    fun `polyline handles empty and truncated input`() {
        assertTrue(decodePolyline("").isEmpty())
        assertTrue(decodePolyline("_p~iF").isEmpty())
    }

    @Test
    fun `bounding box contains and expands`() {
        val box = BoundingBox(55.0, 12.0, 55.1, 12.2)
        assertTrue(box.contains(LatLng(55.05, 12.1)))
        assertTrue(!box.contains(LatLng(55.2, 12.1)))
        val expanded = box.expand(0.01, 0.01)
        assertEquals(54.99, expanded.south, 1e-9)
        assertEquals(12.21, expanded.east, 1e-9)
    }
}
