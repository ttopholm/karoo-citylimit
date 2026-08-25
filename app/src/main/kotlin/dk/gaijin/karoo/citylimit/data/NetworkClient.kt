package dk.gaijin.karoo.citylimit.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a URL as text.
 */
fun interface NetworkClient {
    suspend fun get(url: String): Result<String>
}

/**
 * A plain connection from the device itself. Works when the Karoo is on Wi-Fi, which is the normal
 * case for downloading a region before a ride, and has no size limit.
 */
class DirectNetworkClient(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : NetworkClient {
    override suspend fun get(url: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
    }

    companion object {
        const val USER_AGENT = "karoo-citylimit/1.0 (+https://github.com/ttopholm/karoo-citylimit)"
    }
}

/** Routes requests through the Karoo system, which can use the companion phone over Bluetooth. */
class KarooNetworkClient(private val http: KarooHttp) : NetworkClient {
    override suspend fun get(url: String): Result<String> = http.get(url)
}

/**
 * Tries [primary] and falls back to [secondary].
 *
 * A region download is normally done at home on Wi-Fi, where the direct connection is quicker and
 * unlimited; the Karoo system's relay through the phone stands in when the device has no network of
 * its own, which is why pack chunks are kept under its 100 KB response limit.
 */
class FallbackNetworkClient(
    private val primary: NetworkClient,
    private val secondary: NetworkClient,
) : NetworkClient {
    override suspend fun get(url: String): Result<String> {
        val direct = primary.get(url)
        if (direct.isSuccess) return direct
        Timber.i("Direct request failed (%s), trying the Karoo system", direct.exceptionOrNull()?.message)
        return secondary.get(url).recoverCatching { throw direct.exceptionOrNull() ?: it }
    }
}
