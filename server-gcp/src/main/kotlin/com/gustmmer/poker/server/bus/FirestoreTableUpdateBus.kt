package com.gustmmer.poker.server.bus

import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.ListenerRegistration
import com.gustmmer.poker.PokerTableState
import com.gustmmer.poker.WireablePokerTableState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Production [TableUpdateBus] backed by Firestore real-time snapshot listeners. Subscribing attaches a
 * listener to `tables/{id}`; Firestore pushes the document to it whenever it changes — including writes
 * made by *other* Cloud Run instances. That is the cross-instance fan-out.
 *
 * Two correctness details:
 *  - **Ordering.** Firestore delivers per-document changes in order, but we must not lose that order
 *    when hopping onto a coroutine. Each subscription drains its snapshots through a single-consumer
 *    [Channel], so `onChange` runs strictly in commit order (and one table's slow broadcast can't block
 *    another's, unlike a shared single-thread dispatcher).
 *  - **Self-heal.** On a listener error we re-subscribe after a short delay, so a terminal error doesn't
 *    silently strand a table's clients on this instance. (The SDK already retries transient errors.)
 */
class FirestoreTableUpdateBus(
    private val firestore: Firestore,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : TableUpdateBus {

    private val json = Json { ignoreUnknownKeys = true }

    override fun subscribe(tableId: Int, onChange: suspend (PokerTableState) -> Unit): Subscription {
        // Ordered hand-off: the listener offers snapshots to the channel; one consumer forwards them.
        val channel = Channel<PokerTableState>(Channel.UNLIMITED)
        val consumer = scope.launch {
            for (state in channel) {
                runCatching { onChange(state) }
                    .onFailure { log.error("broadcast handler failed for table {}", tableId, it) }
            }
        }

        val active = AtomicBoolean(true)
        val registration = AtomicReference<ListenerRegistration?>(null)
        val docRef = firestore.collection(COLLECTION).document(tableId.toString())

        fun listen() {
            if (!active.get()) return
            registration.set(docRef.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    log.warn("snapshot listener error for table {} — re-subscribing", tableId, error)
                    registration.getAndSet(null)?.remove()
                    if (active.get()) scope.launch { delay(RESUBSCRIBE_DELAY_MS); listen() }
                    return@addSnapshotListener
                }
                if (snapshot == null || !snapshot.exists()) return@addSnapshotListener
                val stateJson = snapshot.getString(FIELD_STATE) ?: return@addSnapshotListener
                val state = runCatching {
                    PokerTableState.restore(json.decodeFromString<WireablePokerTableState>(stateJson))
                }.getOrElse {
                    log.error("failed to decode snapshot for table {}", tableId, it)
                    return@addSnapshotListener
                }
                channel.trySend(state) // UNLIMITED channel: never fails
            })
        }
        listen()

        return Subscription {
            active.set(false)
            registration.getAndSet(null)?.remove()
            channel.close()
            consumer.cancel()
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(FirestoreTableUpdateBus::class.java)
        private const val COLLECTION = "tables"
        private const val FIELD_STATE = "state"
        private const val RESUBSCRIBE_DELAY_MS = 1000L
    }
}
