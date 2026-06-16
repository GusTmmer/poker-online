package com.gustmmer.poker.server.persistence

import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import com.gustmmer.poker.PokerTableState
import com.gustmmer.poker.WireablePokerTableState
import com.gustmmer.poker.persistence.PokerTablePersistence
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.temporal.ChronoUnit

class FirestorePokerTablePersistence(
    projectId: String,
) : PokerTablePersistence {

    private val firestore: Firestore = FirestoreOptions.newBuilder()
        .setProjectId(projectId)
        .build()
        .service

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
            println("Error loading state for table $pokerTableId: ${e.message}")
            null
        }
    }

    override fun saveState(state: PokerTableState) {
        val wireable = state.toWire()
        val stateJson = json.encodeToString(wireable)
        val expiresAt = Instant.now().plus(TTL_HOURS, ChronoUnit.HOURS)

        firestore.collection(COLLECTION)
            .document(state.id.toString())
            .set(
                mapOf(
                    FIELD_STATE to stateJson,
                    FIELD_VERSION to state.version,
                    FIELD_EXPIRES_AT to expiresAt.toString(),
                )
            )
            .get()
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

            val wireable = state.toWire()
            val stateJson = json.encodeToString(wireable)
            val expiresAt = Instant.now().plus(TTL_HOURS, ChronoUnit.HOURS)

            transaction.set(
                docRef,
                mapOf(
                    FIELD_STATE to stateJson,
                    FIELD_VERSION to state.version,
                    FIELD_EXPIRES_AT to expiresAt.toString(),
                )
            )
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
        private const val COLLECTION = "tables"
        private const val FIELD_STATE = "state"
        private const val FIELD_VERSION = "version"
        private const val FIELD_EXPIRES_AT = "expiresAt"
        private const val TTL_HOURS = 12L
    }
}
