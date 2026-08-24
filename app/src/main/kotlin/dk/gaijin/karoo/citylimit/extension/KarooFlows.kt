package dk.gaijin.karoo.citylimit.extension

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.KarooEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Karoo events as a flow, unregistering the consumer when collection stops.
 */
inline fun <reified T : KarooEvent> KarooSystemService.consumerFlow(): Flow<T> = callbackFlow {
    val consumerId = addConsumer<T> { event -> trySend(event) }
    awaitClose { removeConsumer(consumerId) }
}
