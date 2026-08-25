package dk.gaijin.karoo.citylimit.data

import dk.gaijin.karoo.citylimit.core.CityLimitSign
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * A region whose signs can be downloaded in one go, so a whole country is on the device before the
 * ride starts. Built by tools/build-packs.mjs and published with the releases.
 */
@Serializable
data class RegionPack(
    val id: String,
    val name: String,
    val generatedAt: String? = null,
    val signs: Int = 0,
    val cells: Int = 0,
    val bytes: Long = 0,
    /** Only present in the region's own index file, not in the catalogue. */
    val chunks: List<String> = emptyList(),
)

@Serializable
data class RegionCatalog(
    val generatedAt: String? = null,
    val regions: List<RegionPack> = emptyList(),
)

@Serializable
private data class PackChunk(
    val region: String = "",
    val chunk: Int = 0,
    val cells: Map<String, List<CityLimitSign>> = emptyMap(),
)

/**
 * What the region download is doing, surfaced in the settings screen.
 */
sealed interface PackStatus {
    data object Idle : PackStatus

    data object LoadingCatalog : PackStatus

    data class Downloading(val region: String, val completed: Int, val total: Int) : PackStatus

    data class Installed(val region: String, val signs: Int, val at: Long) : PackStatus

    data class Failed(val message: String, val at: Long) : PackStatus
}

/**
 * Downloads prepared region packs and writes them straight into the sign cache.
 *
 * Chunks are kept under 100 KB so they can also come through the Karoo system's HTTP API when the
 * device has no Wi-Fi of its own, but a direct connection is tried first: this is a "before you
 * leave home" download, and over Wi-Fi it is one plain request per chunk.
 */
class PackRepository(
    private val cache: SignCache,
    private val http: NetworkClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _status = MutableStateFlow<PackStatus>(PackStatus.Idle)
    val status: StateFlow<PackStatus> = _status.asStateFlow()

    private val _catalog = MutableStateFlow<List<RegionPack>>(emptyList())
    val catalog: StateFlow<List<RegionPack>> = _catalog.asStateFlow()

    /** Read the list of downloadable regions. */
    suspend fun loadCatalog(): Result<List<RegionPack>> {
        _status.value = PackStatus.LoadingCatalog
        return http.get("$baseUrl/regions.json")
            .mapCatching { body -> json.decodeFromString(RegionCatalog.serializer(), body).regions }
            .onSuccess { regions ->
                _catalog.value = regions
                _status.value = PackStatus.Idle
            }
            .onFailure { failure(it) }
    }

    /**
     * Download every chunk of [region] and store its cells.
     */
    suspend fun install(region: RegionPack): Result<Int> {
        _status.value = PackStatus.Downloading(region.name, 0, region.chunks.size.coerceAtLeast(1))

        val index = if (region.chunks.isEmpty()) {
            val loaded = http.get("$baseUrl/${region.id}.json")
                .mapCatching { json.decodeFromString(RegionPack.serializer(), it) }
                .onFailure { return failureResult(it) }
                .getOrThrow()
            loaded
        } else {
            region
        }

        var signs = 0
        val cells = HashMap<String, List<CityLimitSign>>()
        index.chunks.forEachIndexed { position, file ->
            val body = http.get("$baseUrl/$file")
                .onFailure { return failureResult(it) }
                .getOrThrow()
            val chunk = runCatching { json.decodeFromString(PackChunk.serializer(), body) }
                .onFailure { return failureResult(it) }
                .getOrThrow()
            chunk.cells.forEach { (id, cellSigns) ->
                cells[id] = cellSigns
                signs += cellSigns.size
            }
            _status.value = PackStatus.Downloading(index.name, position + 1, index.chunks.size)
        }

        cache.putAll(cells, now())
        Timber.i("Installed %s: %d signs in %d cells", index.id, signs, cells.size)
        _status.value = PackStatus.Installed(index.name, signs, now())
        return Result.success(signs)
    }

    private fun failure(throwable: Throwable) {
        Timber.w(throwable, "Region pack failed")
        _status.value = PackStatus.Failed(throwable.message ?: throwable.javaClass.simpleName, now())
    }

    private fun <T> failureResult(throwable: Throwable): Result<T> {
        failure(throwable)
        return Result.failure(throwable)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://github.com/ttopholm/karoo-citylimit/releases/download/packs"
    }
}
