package com.gustmmer.poker.persistence

import com.gustmmer.poker.PokerTableState

interface PokerTableStateSerializer {

    fun serialize(pokerTableState: PokerTableState): String

    fun deserialize(serializedPokerTableState: String): PokerTableState
}