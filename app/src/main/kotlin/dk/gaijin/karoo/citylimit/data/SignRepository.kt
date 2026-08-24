package dk.gaijin.karoo.citylimit.data

import dk.gaijin.karoo.citylimit.core.Cells
import dk.gaijin.karoo.citylimit.core.CityLimitSign
import dk.gaijin.karoo.citylimit.core.LatLng
import dk.gaijin.karoo.citylimit.core.Overpass
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * What the downloader is currently doing, surfaced in the settings screen.
 */
sealed interface DownloadStatus {
    data object Idle : DownloadStatus

    data class Downloading(val completed: Int, val total: Int) : DownloadStatus

    data class Done(val cells: Int, val signs: Int, val at: Long) : DownloadStatus

    data class Failed(val message: String, val at: Long) : DownloadStatus
}

/**
 * Keeps a local copy of the town-entry signs around the rider.
 *
 * Data comes from the Overpass API in grid cells (see [Cells]) and is cached on disk, so the same
 * area is only downloaded once. Requests are spaced out to stay a good Overpass citizen.
 */
class SignRepository(
    private val cache: SignCache,
    private val http: KarooHttp,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val downloadMutex = Mutex()
    private val retryAfter = HashMap<String, Long>()
    private var nextRequestAt = 0L
    private var consecutiveFailures = 0

    private val _status = MutableStateFlow<DownloadStatus>(DownloadStatus.Idle)
    val status: StateFlow<DownloadStatus> = _status.asStateFlow()

    suspend fun signsNear(position: LatLng, radiusMeters: Double = SIGN_LOOKUP_RADIUS_METERS): List<CityLimitSign> =
        cache.signsNear(position, radiusMeters)

    suspend fun stats(): CacheStats = cache.stats()

    suspend fun clearCache() {
        cache.clear()
        retryAfter.clear()
        _status.value = DownloadStatus.Idle
    }

    /**
     * Make sure the cell the rider is in - and the ones just ahead - are downloaded.
     */
    suspend fun ensureCoverage(
        position: LatLng,
        overpassUrl: String,
        radiusMeters: Double = COVERAGE_RADIUS_METERS,
    ) {
        val keys = Cells.keysWithin(position, radiusMeters)
        download(keys, overpassUrl)
    }

    /**
     * Download every cell a route passes through, so an upcoming ride works without a connection.
     */
    suspend fun prefetchRoute(points: List<LatLng>, overpassUrl: String) {
        if (points.isEmpty()) return
        val keys = Cells.keysAlong(points).take(MAX_ROUTE_CELLS)
        download(keys, overpassUrl)
    }

    private suspend fun download(keys: List<Cells.Key>, overpassUrl: String) {
        // A single download at a time: Overpass is a shared, donated resource.
        downloadMutex.withLock {
            val timestamp = now()
            val pending = keys.filterNot { key ->
                cache.isFresh(key, timestamp, CACHE_MAX_AGE_MILLIS) || isBackingOff(key, timestamp)
            }
            if (pending.isEmpty()) return

            var completed = 0
            var signCount = 0
            _status.value = DownloadStatus.Downloading(0, pending.size)
            for (key in pending) {
                waitForSlot()
                val result = fetchCell(key, overpassUrl)
                result.onSuccess { signs ->
                    cache.put(key, signs, now())
                    retryAfter.remove(key.id)
                    consecutiveFailures = 0
                    completed++
                    signCount += signs.size
                    _status.value = DownloadStatus.Downloading(completed, pending.size)
                }
                result.onFailure { throwable ->
                    consecutiveFailures++
                    val backoff = backoffMillis()
                    retryAfter[key.id] = now() + backoff
                    Timber.w(throwable, "Sign download failed for cell %s, retrying in %d s", key.id, backoff / 1000)
                    _status.value = DownloadStatus.Failed(
                        message = throwable.message ?: throwable.javaClass.simpleName,
                        at = now(),
                    )
                    // Give up on the rest of this batch; the next location update will try again.
                    return
                }
            }
            _status.value = DownloadStatus.Done(cells = completed, signs = signCount, at = now())
        }
    }

    private suspend fun fetchCell(key: Cells.Key, overpassUrl: String): Result<List<CityLimitSign>> {
        val query = Overpass.query(key.queryBounds())
        Timber.d("Downloading signs for cell %s", key.id)
        return http.post(
            url = overpassUrl,
            body = query.toByteArray(Charsets.UTF_8),
            headers = mapOf(
                "Content-Type" to "text/plain; charset=utf-8",
                "User-Agent" to USER_AGENT,
            ),
        ).mapCatching { body -> Overpass.parseSigns(body) }
    }

    private suspend fun waitForSlot() {
        val wait = nextRequestAt - now()
        if (wait > 0) delay(wait)
        nextRequestAt = now() + MIN_REQUEST_INTERVAL_MILLIS
    }

    private fun isBackingOff(key: Cells.Key, timestamp: Long): Boolean =
        (retryAfter[key.id] ?: 0L) > timestamp

    private fun backoffMillis(): Long {
        val exponent = (consecutiveFailures - 1).coerceIn(0, 6)
        return (INITIAL_BACKOFF_MILLIS shl exponent).coerceAtMost(MAX_BACKOFF_MILLIS)
    }

    companion object {
        /** Signs are matched against the rider within this distance of the current position. */
        const val SIGN_LOOKUP_RADIUS_METERS = 2_000.0

        /** Cells overlapping this radius around the rider are kept downloaded. */
        const val COVERAGE_RADIUS_METERS = 2_500.0

        /** Signs change rarely; a cell is re-downloaded after this long. */
        val CACHE_MAX_AGE_MILLIS = 90L * 24 * 60 * 60 * 1000

        const val MIN_REQUEST_INTERVAL_MILLIS = 4_000L
        const val INITIAL_BACKOFF_MILLIS = 30_000L
        const val MAX_BACKOFF_MILLIS = 30L * 60 * 1000
        const val MAX_ROUTE_CELLS = 150

        const val USER_AGENT = "karoo-citylimit/1.0 (+https://github.com/ttopholm/karoo-citylimit)"
    }
}
