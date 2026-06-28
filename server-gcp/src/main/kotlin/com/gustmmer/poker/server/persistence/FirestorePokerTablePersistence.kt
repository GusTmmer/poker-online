package com.gustmmer.poker.server.persistence

import com.google.cloud.Timestamp
import com.google.cloud.firestore.Firestore
import com.gustmmer.poker.PokerTableState
import com.gustmmer.poker.WireablePokerTableState
import com.gustmmer.poker.persistence.PokerTablePersistence
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

class FirestorePokerTablePersistence(
    private val firestore: Firestore,
) : PokerTablePersistence {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    override fun loadState(pokerTableId: Int): PokerTableState? {
        val doc = firestore.collection(COLLECTION)
            .document(pokerTableId.toString())
            .get()
            .get()

        if (!doc.exists()) return null

        val stateJson = doc.getString(FIELD_STATE) ?: return null
        return try {
            val wireable = json.decodeFromString<WireablePokerTableState>(stateJson)
            PokerTableState.restore(wireable)
        } catch (e: Exception) {
            log.error("Error loading state for table {}", pokerTableId, e)
            null
        }
    }

    /**
     * Builds the Firestore document. [FIELD_EXPIRES_AT] is stored as a Firestore [Timestamp] (not a
     * string) so the collection's native TTL policy actually deletes expired tables.
     */
    private fun documentFields(state: PokerTableState): Map<String, Any> {
        val expiresAt = Instant.now().plus(TTL_HOURS, ChronoUnit.HOURS)
        return mapOf(
            FIELD_STATE to json.encodeToString(state.toWire()),
            FIELD_VERSION to state.version,
            FIELD_EXPIRES_AT to Timestamp.ofTimeSecondsAndNanos(expiresAt.epochSecond, expiresAt.nano),
        )
    }

    override fun saveStateIfVersionMatches(state: PokerTableState): Boolean =
        saveStateWithVersion(state, state.version - 1)

    fun saveStateWithVersion(state: PokerTableState, expectedVersion: Long): Boolean {
        val docRef = firestore.collection(COLLECTION).document(state.id.toString())

        return firestore.runTransaction<Boolean> { transaction ->
            val snapshot = transaction.get(docRef).get()
            val currentVersion = snapshot.getLong(FIELD_VERSION) ?: 0L

            if (currentVersion != expectedVersion) {
                return@runTransaction false
            }

            transaction.set(docRef, documentFields(state))
            true
        }.get()
    }

    fun deleteTable(tableId: Int) {
        firestore.collection(COLLECTION)
            .document(tableId.toString())
            .delete()
            .get()
    }

    companion object {
        private val log = LoggerFactory.getLogger(FirestorePokerTablePersistence::class.java)
        private const val COLLECTION = "tables"
        private const val FIELD_STATE = "state"
        private const val FIELD_VERSION = "version"
        private const val FIELD_EXPIRES_AT = "expiresAt"
        private const val TTL_HOURS = 12L
    }
}
