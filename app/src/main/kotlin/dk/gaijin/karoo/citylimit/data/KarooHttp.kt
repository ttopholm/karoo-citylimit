package dk.gaijin.karoo.citylimit.data

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.IOException

/**
 * Performs HTTP requests through the Karoo system, which picks the best available connection:
 * Wi-Fi when the device is online, otherwise the companion phone over Bluetooth.
 */
class KarooHttp(private val karooSystem: KarooSystemService) {
    /**
     * POST [body] to [url] and return the response body as text.
     */
    suspend fun post(
        url: String,
        body: ByteArray,
        headers: Map<String, String> = emptyMap(),
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): Result<String> {
        if (!karooSystem.connected) {
            return Result.failure(IOException("Karoo system service is not connected"))
        }
        if (body.size > OnHttpResponse.MAX_REQUEST_SIZE) {
            return Result.failure(IOException("Request body too large"))
        }

        val response = withTimeoutOrNull(timeoutMillis) {
            callbackFlow {
                val consumerId = karooSystem.addConsumer<OnHttpResponse>(
                    OnHttpResponse.MakeHttpRequest(
                        method = "POST",
                        url = url,
                        headers = headers,
                        body = body,
                        // Queuing a query until a connection appears is not useful mid-ride:
                        // by the time it runs the rider has moved on.
                        waitForConnection = false,
                    ),
                    onError = { message ->
                        Timber.w("HTTP error from Karoo system: %s", message)
                        trySend(HttpResponseState.Complete(0, emptyMap(), null, message))
                    },
                    onComplete = { close() },
                ) { event ->
                    trySend(event.state)
                }
                awaitClose { karooSystem.removeConsumer(consumerId) }
            }
                .filterIsInstance<HttpResponseState.Complete>()
                .first()
        } ?: return Result.failure(IOException("Request timed out after ${timeoutMillis / 1000} s"))

        response.error?.let { return Result.failure(IOException(it)) }
        if (response.statusCode !in 200..299) {
            return Result.failure(IOException("HTTP ${response.statusCode}"))
        }
        val text = response.body?.toString(Charsets.UTF_8)
            ?: return Result.failure(IOException("Empty response"))
        return Result.success(text)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 90_000L
    }
}
