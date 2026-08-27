package dk.gaijin.karoo.citylimit.data

import dk.gaijin.karoo.citylimit.core.Cells
import dk.gaijin.karoo.citylimit.core.CityLimitSign
import dk.gaijin.karoo.citylimit.core.LatLng
import dk.gaijin.karoo.citylimit.core.distanceTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

@Serializable
data class CachedCell(
    val fetchedAt: Long,
    val signs: List<CityLimitSign>,
)

@Serializable
private data class CacheFile(
    val version: Int = SignCache.FORMAT_VERSION,
    val cells: Map<String, CachedCell> = emptyMap(),
)

data class CacheStats(
    val cellCount: Int,
    val signCount: Int,
    val newestFetchAt: Long?,
)

/**
 * On-disk store of downloaded signs, one entry per [Cells.Key], so a ride through an area that has
 * been ridden before needs no connection at all.
 */
class SignCache(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private var cells: MutableMap<String, CachedCell>? = null

    /**
     * The settings screen and the extension service hold separate instances of this cache, so a
     * region pack installed from the screen has to be picked up by the running extension. Reloading
     * whenever the file has changed underneath us is enough, and costs one stat call per read.
     */
    private var loadedAt = 0L

    /**
     * The signs within a radius, found through the grid rather than by reading the whole cache.
     *
     * With one country downloaded that is the difference between looking at a dozen cells and at
     * every sign in Germany, and this runs again every few hundred metres of a ride.
     */
    suspend fun signsNear(position: LatLng, radiusMeters: Double): List<CityLimitSign> =
        mutex.withLock {
            val loaded = loadLocked()
            Cells.keysWithin(position, radiusMeters)
                .asSequence()
                .mapNotNull { loaded[it.id] }
                .flatMap { it.signs }
                .filter { position.distanceTo(it.position) <= radiusMeters }
                .toList()
        }

    suspend fun isFresh(key: Cells.Key, now: Long, maxAgeMillis: Long): Boolean {
        val cell = cells()[key.id] ?: return false
        return now - cell.fetchedAt < maxAgeMillis
    }

    suspend fun put(key: Cells.Key, signs: List<CityLimitSign>, now: Long) {
        mutex.withLock {
            val loaded = loadLocked()
            loaded[key.id] = CachedCell(fetchedAt = now, signs = signs)
            prune(loaded, now)
            saveLocked(loaded)
        }
    }

    /**
     * Store many cells at once, as when a downloaded region pack is unpacked. Written in one go so a
     * pack of a few thousand cells does not rewrite the file once per cell.
     */
    suspend fun putAll(cells: Map<String, List<CityLimitSign>>, now: Long) {
        if (cells.isEmpty()) return
        mutex.withLock {
            val loaded = loadLocked()
            cells.forEach { (id, signs) -> loaded[id] = CachedCell(fetchedAt = now, signs = signs) }
            prune(loaded, now)
            saveLocked(loaded)
        }
    }

    suspend fun stats(): CacheStats {
        val loaded = cells()
        return CacheStats(
            cellCount = loaded.size,
            signCount = loaded.values.sumOf { it.signs.size },
            newestFetchAt = loaded.values.maxOfOrNull { it.fetchedAt },
        )
    }

    suspend fun clear() {
        mutex.withLock {
            cells = LinkedHashMap()
            withContext(Dispatchers.IO) {
                runCatching { if (file.exists()) file.delete() }
            }
        }
    }

    private suspend fun cells(): Map<String, CachedCell> = mutex.withLock { loadLocked().toMap() }

    private suspend fun loadLocked(): MutableMap<String, CachedCell> {
        val modified = withContext(Dispatchers.IO) { runCatching { file.lastModified() }.getOrDefault(0L) }
        cells?.let { if (modified == loadedAt) return it }
        loadedAt = modified
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                if (!file.exists()) return@runCatching LinkedHashMap<String, CachedCell>()
                val parsed = json.decodeFromString(CacheFile.serializer(), file.readText())
                if (parsed.version != FORMAT_VERSION) {
                    LinkedHashMap()
                } else {
                    LinkedHashMap(parsed.cells)
                }
            }.getOrElse { throwable ->
                Timber.w(throwable, "Could not read sign cache, starting over")
                LinkedHashMap()
            }
        }
        cells = loaded
        return loaded
    }

    private suspend fun saveLocked(loaded: Map<String, CachedCell>) {
        withContext(Dispatchers.IO) {
            runCatching {
                file.parentFile?.mkdirs()
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.writeText(json.encodeToString(CacheFile.serializer(), CacheFile(cells = loaded)))
                if (!temp.renameTo(file)) {
                    file.writeText(temp.readText())
                    temp.delete()
                }
                loadedAt = file.lastModified()
            }.onFailure { Timber.w(it, "Could not write sign cache") }
        }
    }

    private fun prune(loaded: MutableMap<String, CachedCell>, now: Long) {
        loaded.entries.removeAll { now - it.value.fetchedAt > MAX_AGE_MILLIS }
        if (loaded.size <= MAX_CELLS) return
        loaded.entries
            .sortedBy { it.value.fetchedAt }
            .take(loaded.size - MAX_CELLS)
            .forEach { loaded.remove(it.key) }
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val FILE_NAME = "sign-cache.json"
        val MAX_AGE_MILLIS = 180L * 24 * 60 * 60 * 1000
        /**
         * Enough for a downloaded country and then some: Denmark is roughly 1,300 cells, and a
         * cell holds a handful of signs.
         */
        const val MAX_CELLS = 8_000
    }
}
