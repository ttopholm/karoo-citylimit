package dk.gaijin.karoo.citylimit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CellsTest {
    @Test
    fun `key round trips through its id`() {
        val key = Cells.keyFor(LatLng(55.9339, 12.3010))
        assertEquals(key, Cells.Key.parse(key.id))
        assertTrue(key.bounds().contains(LatLng(55.9339, 12.3010)))
    }

    @Test
    fun `cell ids are the ones the pack builder writes`() {
        // tools/build-packs.mjs groups signs by these ids, and the parity check pins the same values.
        assertEquals("1118/123", Cells.keyFor(LatLng(55.9339, 12.3010)).id)
        assertEquals("-678/-707", Cells.keyFor(LatLng(-33.9, -70.7)).id)
    }

    @Test
    fun `negative coordinates land in the right cell`() {
        val key = Cells.keyFor(LatLng(-33.9, -70.7))
        assertTrue(key.bounds().contains(LatLng(-33.9, -70.7)))
    }

    @Test
    fun `query bounds cover the cell with a margin`() {
        val key = Cells.keyFor(LatLng(55.9339, 12.3010))
        val bounds = key.bounds()
        val query = key.queryBounds()
        assertTrue(query.south < bounds.south && query.north > bounds.north)
        assertTrue(query.west < bounds.west && query.east > bounds.east)
    }

    @Test
    fun `keys within a small radius include the current cell first`() {
        val position = LatLng(55.9339, 12.3010)
        val keys = Cells.keysWithin(position, 500.0)
        assertEquals(Cells.keyFor(position), keys.first())
        assertTrue(keys.size <= 4)
    }

    @Test
    fun `keys within a larger radius cover neighbours`() {
        val keys = Cells.keysWithin(LatLng(55.9339, 12.3010), 8_000.0)
        assertTrue(keys.size >= 6)
        assertTrue(keys.contains(Cells.keyFor(LatLng(55.9339, 12.3010))))
    }

    @Test
    fun `route cells are contiguous even with sparse points`() {
        val start = LatLng(55.60, 12.10)
        val end = LatLng(55.95, 12.40)
        val keys = Cells.keysAlong(listOf(start, end))
        assertTrue(keys.contains(Cells.keyFor(start)))
        assertTrue(keys.contains(Cells.keyFor(end)))
        // The straight line between them crosses roughly seven rows of cells.
        assertTrue("expected intermediate cells, got ${keys.size}", keys.size >= 7)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `route cells keep riding order`() {
        val points = (0..10).map { LatLng(55.60 + it * 0.05, 12.10) }
        val keys = Cells.keysAlong(points)
        assertEquals(Cells.keyFor(points.first()), keys.first())
        assertEquals(Cells.keyFor(points.last()), keys.last())
    }
}
