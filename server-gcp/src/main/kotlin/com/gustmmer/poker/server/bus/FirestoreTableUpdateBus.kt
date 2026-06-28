package com.gustmmer.poker.server.bus

import com.google.cloud.firestore.Firestore
import com.gustmmer.poker.PokerTableState
import com.gustmmer.poker.WireablePokerTableState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Production [TableUpdateBus] backed by Firestore real-time snapshot listeners. Subscribing attaches a
 * listener to `tables/{id}`; Firestore pushes the document to it whenever it changes — including writes
 * made by *other* Cloud Run instances. That is the cross-instance fan-out: the committing instance just
 * writes, and every instance listening gets notified.
 *
 * The snapshot is decoded the same way [com.gustmmer.poker.server.persistence.FirestorePokerTablePersistence]
 * reads it. Firestore delivers per-document events in order, so per-table ordering is preserved.
 */
class FirestoreTableUpdateBus(
    private val firestore: Firestore,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : TableUpdateBus {

    private val json = Json { ignoreUnknownKeys = true }

    override fun subscribe(tableId: Int, onChange: suspend (PokerTableState) -> Unit): Subscription {
        val registration = firestore.collection(COLLECTION).document(tableId.toString())
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    log.warn("snapshot listener error for table {}", tableId, error)
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
                // Hop off Firestore's callback thread onto a coroutine for the suspend handler.
                scope.launch { onChange(state) }
            }
        return Subscription { registration.remove() }
    }

    companion object {
        private val log = LoggerFactory.getLogger(FirestoreTableUpdateBus::class.java)
        private const val COLLECTION = "tables"
        private const val FIELD_STATE = "state"
    }
}
