package com.gustmmer.poker.persistence

import com.gustmmer.poker.PokerTableState

class MemoryBasedPokerTablePersistence(
    private val serializer: PokerTableStateSerializer,
) : PokerTablePersistence {

    companion object {
        fun json() = MemoryBasedPokerTablePersistence(JsonSerializer())
    }

    private val states = mutableMapOf<Int, String>()

    @Synchronized
    override fun loadState(pokerTableId: Int): PokerTableState? {
        return states[pokerTableId]?.let { content ->
            try {
                serializer.deserialize(content)
            } catch (e: Exception) {
                println("Error loading state for table $pokerTableId: ${e.message}")
                null
            }
        }
    }

    @Synchronized
    override fun saveState(state: PokerTableState) {
        states[state.id] = serializer.serialize(state)
    }

    @Synchronized
    override fun saveStateIfVersionMatches(state: PokerTableState): Boolean {
        val currentVersion = states[state.id]?.let { serializer.deserialize(it).version } ?: 0L
        if (currentVersion != state.version - 1) return false
        states[state.id] = serializer.serialize(state)
        return true
    }
} 