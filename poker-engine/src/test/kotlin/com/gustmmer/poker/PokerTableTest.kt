package com.gustmmer.poker

import com.gustmmer.poker.persistence.MemoryBasedPokerTablePersistence
import com.gustmmer.poker.persistence.PokerTablePersistence
import com.gustmmer.poker.round.Call
import com.gustmmer.poker.round.Fold
import com.gustmmer.poker.round.Raise
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PokerTableTest {

    private lateinit var persistence: PokerTablePersistence

    private val defaultConfig = TableConfig(
        startingChips = 1000,
        turnTimerSeconds = 30,
        maxPlayers = 6,
    )

    @BeforeEach
    fun setup() {
        persistence = MemoryBasedPokerTablePersistence.json()
    }

    @Test
    fun `full game sequence with serialization and restoration`() {
        val tableId = 1
        val table = PokerTable.new(
            id = tableId,
            firstPlayer = Player(0, "Alice"),
            config = defaultConfig,
            persistence = persistence,
        )
        table.playerJoin(Player(1, "Bob"))
        table.playerJoin(Player(2, "Charlie"))

        table.newPokerRound()

        // Blinds round
        restoreTable(tableId).processPlayerCommand(Call(0))
        restoreTable(tableId).processPlayerCommand(Call(1))
        restoreTable(tableId).processPlayerCommand(Raise(2, 400))
        restoreTable(tableId).processPlayerCommand(Call(0))
        restoreTable(tableId).processPlayerCommand(Call(1))

        // Flop round
        restoreTable(tableId).processPlayerCommand(Call(1))
        restoreTable(tableId).processPlayerCommand(Call(2))
        restoreTable(tableId).processPlayerCommand(Raise(0, 200))
        restoreTable(tableId).processPlayerCommand(Call(1))
        restoreTable(tableId).processPlayerCommand(Call(2))

        // Turn round
        restoreTable(tableId).processPlayerCommand(Call(1))
        restoreTable(tableId).processPlayerCommand(Call(2))
        restoreTable(tableId).processPlayerCommand(Call(0))

        // River round
        restoreTable(tableId).processPlayerCommand(Call(1))
        restoreTable(tableId).processPlayerCommand(Call(2))
        restoreTable(tableId).processPlayerCommand(Raise(0, 200))
        restoreTable(tableId).processPlayerCommand(Fold(1))
        restoreTable(tableId).processPlayerCommand(Fold(2))

        with(persistence.loadState(tableId)!!) {
            assertEquals(3000, this.players.sumOf { it.chips })
        }
    }

    @Test
    fun `dealer moves clockwise after each round`() {
        val table = createTableWithPlayers(3)

        assertEquals(0, table.dealer.id)

        table.clearRoundState()
        table.advancePlayerOrdering()
        table.newPokerRound()
        assertEquals(1, table.dealer.id)

        table.clearRoundState()
        table.advancePlayerOrdering()
        table.newPokerRound()
        assertEquals(2, table.dealer.id)

        table.clearRoundState()
        table.advancePlayerOrdering()
        table.newPokerRound()
        assertEquals(0, table.dealer.id)
    }

    @Test
    fun `player ordering updates when new players join`() {
        val table = PokerTable.new(
            firstPlayer = Player(0, "Alice"),
            config = defaultConfig,
            persistence = persistence,
        )
        table.playerJoin(Player(1, "Bob"))

        assertEquals(0, table.dealer.id)
        table.clearRoundState()
        table.advancePlayerOrdering()
        table.newPokerRound()
        assertEquals(1, table.dealer.id)

        table.playerJoin(Player(2, "Charlie"))

        table.clearRoundState()
        table.advancePlayerOrdering()
        table.newPokerRound()
        assertEquals(2, table.dealer.id)
    }

    @Test
    fun `player join gives starting chips from config`() {
        val table = PokerTable.new(
            firstPlayer = Player(0, "Alice"),
            config = defaultConfig,
            persistence = persistence,
        )

        val bob = Player(1, "Bob")
        table.playerJoin(bob)

        assertEquals(1000, bob.chips)
    }

    @Test
    fun `player join fails when table is full`() {
        val config = defaultConfig.copy(maxPlayers = 2)
        val table = PokerTable.new(
            firstPlayer = Player(0, "Alice"),
            config = config,
            persistence = persistence,
        )
        table.playerJoin(Player(1, "Bob"))

        val joined = table.playerJoin(Player(2, "Charlie"))
        assertEquals(false, joined)
    }

    @Test
    fun `player join fails when table is closed`() {
        val config = defaultConfig.copy(isOpen = false)
        val table = PokerTable.new(
            firstPlayer = Player(0, "Alice"),
            config = config,
            persistence = persistence,
        )

        val joined = table.playerJoin(Player(1, "Bob"))
        assertEquals(false, joined)
    }

    @Test
    fun `restart game resets all chips and statuses`() {
        val table = createTableWithPlayers(3)
        table.newPokerRound()

        foldAllPlayers(table, listOf(0, 1))
        table.clearRoundState()
        table.advancePlayerOrdering()

        table.restartGame()

        val state = table.currentState
        state.players.forEach { player ->
            assertEquals(1000, player.chips, "Player ${player.id} should have starting chips")
            assertEquals(PlayerStatus.ONLINE, player.status)
        }
    }

    private fun createTableWithPlayers(count: Int): PokerTable {
        val table = PokerTable.new(
            firstPlayer = Player(0, "P0"),
            config = defaultConfig,
            persistence = persistence,
        )
        for (i in 1 until count) {
            table.playerJoin(Player(i, "P$i"))
        }
        return table
    }

    private fun foldAllPlayers(table: PokerTable, playerIds: List<Int>) {
        playerIds.forEach { table.processPlayerCommand(Fold(it)) }
    }

    private fun restoreTable(tableId: Int): PokerTable {
        return PokerTable.restore(tableId, persistence)!!
    }
}
