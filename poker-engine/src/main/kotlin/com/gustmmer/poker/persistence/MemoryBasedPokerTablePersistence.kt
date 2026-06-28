package com.gustmmer.poker.persistence

import com.gustmmer.poker.PokerTableState
import org.slf4j.LoggerFactory

class MemoryBasedPokerTablePersistence(
    private val serializer: PokerTableStateSerializer,
) : PokerTablePersistence {

    companion object {
        private val log = LoggerFactory.getLogger(MemoryBasedPokerTablePersistence::class.java)
        fun json() = MemoryBasedPokerTablePersistence(JsonSerializer())
    }

    private val states = mutableMapOf<Int, String>()

    @Synchronized
    override fun loadState(pokerTableId: Int): PokerTableState? {
        return states[pokerTableId]?.let { content ->
            try {
                serializer.deserialize(content)
            } catch (e: Exception) {
                log.error("Error loading state for table {}", pokerTableId, e)
                null
            }
        }
    }

    /**
     * Test-only seam: unconditionally writes [state] (no version check). Lets tests stand up an
     * arbitrary table state without going through the staged-mutation/commit path. Not part of
     * [PokerTablePersistence] — production code cannot reach it.
     */
    @Synchronized
    fun seed(state: PokerTableState) {
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