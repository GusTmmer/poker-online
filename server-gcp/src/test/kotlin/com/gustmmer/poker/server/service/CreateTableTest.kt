package com.gustmmer.poker.server.service

import com.gustmmer.poker.persistence.MemoryBasedPokerTablePersistence
import com.gustmmer.poker.server.routes.CreateTableRequest
import com.gustmmer.poker.server.timer.InMemoryTaskScheduler
import com.gustmmer.poker.server.timer.TurnTimerManager
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CreateTableTest {

    private val persistence = MemoryBasedPokerTablePersistence.json()
    private val gameService = GameService(persistence, TurnTimerManager(persistence, InMemoryTaskScheduler()))

    @Test
    fun `an id that is already taken is skipped, and the table holding it is left alone`() = runBlocking {
        gameService.createTable(CreateTableRequest(playerName = "Alice"), newId = { 42 })

        val ids = ArrayDeque(listOf(42, 43))
        val created = gameService.createTable(CreateTableRequest(playerName = "Bob"), newId = { ids.removeFirst() })

        assertEquals(43, created.tableId)
        assertEquals(listOf("Alice"), persistence.loadState(42)!!.players.map { it.name })
        assertEquals(listOf("Bob"), persistence.loadState(43)!!.players.map { it.name })
    }
}
