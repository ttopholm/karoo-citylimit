package dk.gaijin.karoo.citylimit.data

import dk.gaijin.karoo.citylimit.core.LatLng
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class PackRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** Serves the files tools/build-packs.mjs writes. */
    private class FakeNetwork(private val files: Map<String, String>) : NetworkClient {
        val requested = mutableListOf<String>()
        var failFor: String? = null

        override suspend fun get(url: String): Result<String> {
            requested += url
            failFor?.let { if (url.endsWith(it)) return Result.failure(IOException("boom")) }
            val body = files[url.substringAfterLast('/')] ?: return Result.failure(IOException("404"))
            return Result.success(body)
        }
    }

    private val catalog = """
        {"generatedAt":"2026-08-25T10:00:00Z","regions":[
          {"id":"dk","name":"Danmark","signs":2,"cells":2,"bytes":420}
        ]}
    """.trimIndent()

    private val index = """
        {"id":"dk","name":"Danmark","generatedAt":"2026-08-25T10:00:00Z","signs":2,"cells":2,
         "bytes":420,"chunks":["dk-000.json","dk-001.json"]}
    """.trimIndent()

    private val chunk0 = """
        {"region":"dk","chunk":0,"cells":{"1117/122":[
          {"id":1,"position":{"lat":55.8955018,"lng":12.2774175},"name":"Noerre Herlev","entryHeading":196.3}
        ]}}
    """.trimIndent()

    private val chunk1 = """
        {"region":"dk","chunk":1,"cells":{"1118/123":[
          {"id":2,"position":{"lat":55.9339,"lng":12.3010},"name":"Hilleroed","entryHeading":12.0}
        ]}}
    """.trimIndent()

    private fun cache() = SignCache(File(folder.root, SignCache.FILE_NAME))

    private fun repository(network: FakeNetwork, cache: SignCache = cache()) =
        PackRepository(cache = cache, http = network, baseUrl = "https://example.test/packs") { 1_000L }

    private fun network() = FakeNetwork(
        mapOf("regions.json" to catalog, "dk.json" to index, "dk-000.json" to chunk0, "dk-001.json" to chunk1),
    )

    @Test
    fun `catalog lists the regions on offer`() = runTest {
        val repository = repository(network())
        val regions = repository.loadCatalog().getOrThrow()
        assertEquals(1, regions.size)
        assertEquals("Danmark", regions.first().name)
        assertEquals(PackStatus.Idle, repository.status.value)
    }

    @Test
    fun `installing a region fills the cache`() = runTest {
        val cache = cache()
        val network = network()
        val repository = repository(network, cache)
        val region = repository.loadCatalog().getOrThrow().first()

        assertEquals(2, repository.install(region).getOrThrow())

        // The catalogue entry carries no chunk list, so the region's own index is fetched.
        assertTrue(network.requested.any { it.endsWith("dk.json") })
        assertEquals(2, cache.stats().cellCount)
        assertEquals(2, cache.stats().signCount)
        assertEquals(1_000L, cache.stats().newestFetchAt)

        val nearby = cache.signsNear(LatLng(55.8955018, 12.2774175), 1_000.0)
        assertEquals(listOf("Noerre Herlev"), nearby.map { it.name })
        assertEquals(196.3, nearby.single().entryHeading!!, 0.01)

        val status = repository.status.value
        assertTrue(status is PackStatus.Installed && status.signs == 2)
    }

    @Test
    fun `a failed chunk is reported and leaves the cache alone`() = runTest {
        val cache = cache()
        val network = network().apply { failFor = "dk-001.json" }
        val repository = repository(network, cache)
        val region = repository.loadCatalog().getOrThrow().first()

        assertTrue(repository.install(region).isFailure)
        assertEquals(0, cache.stats().cellCount)
        assertTrue(repository.status.value is PackStatus.Failed)
    }

    @Test
    fun `a region that already carries its chunk list skips the index`() = runTest {
        val network = network()
        val repository = repository(network)
        val region = RegionPack(id = "dk", name = "Danmark", chunks = listOf("dk-000.json"))

        assertEquals(1, repository.install(region).getOrThrow())
        assertTrue(network.requested.none { it.endsWith("dk.json") })
    }

    @Test
    fun `falling back reaches the second client when the first fails`() = runTest {
        val failing = NetworkClient { Result.failure(IOException("no wifi")) }
        val working = NetworkClient { Result.success("ok") }
        assertEquals("ok", FallbackNetworkClient(failing, working).get("https://example.test").getOrThrow())

        val bothFail = FallbackNetworkClient(failing, NetworkClient { Result.failure(IOException("no phone")) })
        assertTrue(bothFail.get("https://example.test").isFailure)
    }
}
