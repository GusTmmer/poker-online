package com.gustmmer.poker.persistence

import com.gustmmer.poker.PokerTableState

interface PokerTablePersistence {

    fun loadState(pokerTableId: Int): PokerTableState?

    fun saveState(state: PokerTableState)
}