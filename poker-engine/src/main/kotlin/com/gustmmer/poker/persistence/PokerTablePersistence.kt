package com.gustmmer.poker.persistence

import com.gustmmer.poker.PokerTableState

/**
 * The only contract for table persistence: read a snapshot, or commit a mutation with an
 * optimistic-concurrency check. There is intentionally no unconditional write — every state change
 * flows through [PokerTable] (staged in memory) and is committed via [saveStateIfVersionMatches] at
 * a single transaction boundary. Tests that need to seed arbitrary state use the impl-specific,
 * clearly-marked seam on the in-memory/file implementations, not this interface.
 */
interface PokerTablePersistence {

    fun loadState(pokerTableId: Int): PokerTableState?

    /**
     * Commits [state] only if the stored version equals `state.version - 1`.
     * Returns false on a version mismatch (concurrent write detected).
     */
    fun saveStateIfVersionMatches(state: PokerTableState): Boolean
}