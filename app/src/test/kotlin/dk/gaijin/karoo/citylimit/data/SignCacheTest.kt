package dk.gaijin.karoo.citylimit.data

import dk.gaijin.karoo.citylimit.core.Cells
import dk.gaijin.karoo.citylimit.core.CityLimitSign
import dk.gaijin.karoo.citylimit.core.LatLng
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SignCacheTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun cache(name: String = SignCache.FILE_NAME) = SignCache(File(folder.root, name))

    private fun sign(id: Long, position: LatLng, name: String? = "By") =
        CityLimitSign(id = id, position = position, name = name, entryHeading = 180.0)

    private val hillerod = LatLng(55.9339, 12.3010)

    @Test
    fun `signs survive a round trip through disk`() = runTest {
        val key = Cells.keyFor(hillerod)
        cache().put(key, listOf(sign(1, hillerod)), now = 1_000)

        val reopened = cache()
        val signs = reopened.signsNear(hillerod, 1_000.0)
        assertEquals(1, signs.size)
        assertEquals("By", signs.first().name)
        assertEquals(1, reopened.stats().cellCount)
        assertEquals(1_000L, reopened.stats().newestFetchAt)
    }

    @Test
    fun `only signs within the radius are returned`() = runTest {
        val store = cache()
        val far = LatLng(56.1, 12.6)
        store.put(Cells.keyFor(hillerod), listOf(sign(1, hillerod)), now = 0)
        store.put(Cells.keyFor(far), listOf(sign(2, far)), now = 0)

        assertEquals(listOf(1L), store.signsNear(hillerod, 2_000.0).map { it.id })
        assertEquals(2, store.stats().signCount)
    }

    @Test
    fun `freshness follows the age of the cell`() = runTest {
        val store = cache()
        val key = Cells.keyFor(hillerod)
        assertFalse(store.isFresh(key, now = 0, maxAgeMillis = 1_000))

        store.put(key, listOf(sign(1, hillerod)), now = 0)
        assertTrue(store.isFresh(key, now = 500, maxAgeMillis = 1_000))
        assertFalse(store.isFresh(key, now = 1_500, maxAgeMillis = 1_000))
    }

    @Test
    fun `stale cells are dropped on write`() = runTest {
        val store = cache()
        val old = Cells.keyFor(LatLng(55.0, 12.0))
        store.put(old, listOf(sign(1, LatLng(55.0, 12.0))), now = 0)
        store.put(Cells.keyFor(hillerod), listOf(sign(2, hillerod)), now = SignCache.MAX_AGE_MILLIS + 1)

        assertEquals(1, store.stats().cellCount)
        assertFalse(store.isFresh(old, now = SignCache.MAX_AGE_MILLIS + 1, maxAgeMillis = Long.MAX_VALUE))
    }

    @Test
    fun `clearing removes everything`() = runTest {
        val store = cache()
        store.put(Cells.keyFor(hillerod), listOf(sign(1, hillerod)), now = 0)
        store.clear()
        assertEquals(0, store.stats().cellCount)
        assertTrue(store.signsNear(hillerod, 5_000.0).isEmpty())
        assertEquals(0, cache().stats().signCount)
    }

    @Test
    fun `a damaged cache file is ignored`() = runTest {
        File(folder.root, "broken.json").writeText("{ this is not json")
        val store = cache("broken.json")
        assertEquals(0, store.stats().cellCount)
        store.put(Cells.keyFor(hillerod), listOf(sign(1, hillerod)), now = 0)
        assertEquals(1, store.stats().cellCount)
    }
}
