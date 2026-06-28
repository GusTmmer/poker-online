package com.gustmmer.poker.server.persistence

import com.gustmmer.poker.PokerTableState
import com.gustmmer.poker.persistence.PokerTablePersistence
import com.gustmmer.poker.server.bus.InMemoryTableUpdateBus

/**
 * Wraps an in-process [PokerTablePersistence] so a successful commit also publishes the new state to an
 * [InMemoryTableUpdateBus]. This is the in-memory analogue of Firestore's snapshot listener: in
 * production the database fires the listener after a committed write; in a single process we have to
 * fire it ourselves, right after the write succeeds.
 *
 * Only used with the in-memory persistence (local/dev/tests). The Firestore path doesn't need this —
 * [com.gustmmer.poker.server.bus.FirestoreTableUpdateBus] is driven by the database itself.
 */
class NotifyingPersistence(
    private val delegate: PokerTablePersistence,
    private val bus: InMemoryTableUpdateBus,
) : PokerTablePersistence {

    override fun loadState(pokerTableId: Int): PokerTableState? = delegate.loadState(pokerTableId)

    override fun saveStateIfVersionMatches(state: PokerTableState): Boolean {
        val committed = delegate.saveStateIfVersionMatches(state)
        if (committed) bus.publish(state)
        return committed
    }
}
