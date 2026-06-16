package com.gustmmer.poker.persistence

import com.gustmmer.poker.PokerTableState

interface PokerTablePersistence {

    fun loadState(pokerTableId: Int): PokerTableState?

    fun saveState(state: PokerTableState)

    /**
     * Saves only if the stored version equals state.version - 1.
     * Returns false on a version mismatch (concurrent write detected).
     */
    fun saveStateIfVersionMatches(state: PokerTableState): Boolean
}