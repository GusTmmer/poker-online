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

    @BeforeEach
    fun setup() {
        persistence = MemoryBasedPokerTablePersistence.json()
    }

    @Test
    fun `full game sequence with serialization and restoration`() {
        val players = listOf(
            Player(0).apply { addChips(1000) }, // Dealer
            Player(1).apply { addChips(900) },  // Small blind
            Player(2).apply { addChips(1200) }  // Big blind
        )

        val tableId = 1
        val table = PokerTable.new(
            id = tableId,
            players = players,
            blinds = Blinds(200, 100),
            ruleSet = TexasHoldEm,
            persistence = persistence,
        )

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
        restoreTable(tableId).processPlayerCommand(Raise(0, 200)) // 1000
        restoreTable(tableId).processPlayerCommand(Fold(1))
        restoreTable(tableId).processPlayerCommand(Fold(2))

        with(persistence.loadState(tableId)!!) {
            assertEquals(2600, this.players[0].chips)
            assertEquals(100, this.players[1].chips)
            assertEquals(400, this.players[2].chips)
        }
    }

    @Test
    fun `dealer moves clockwise after each round`() {
        val players = listOf(
            Player(0).apply { addChips(1000) },
            Player(1).apply { addChips(1000) },
            Player(2).apply { addChips(1000) }
        )
        val table = PokerTable.new(
            players = players,
            blinds = Blinds(big = 200, small = 100),
            ruleSet = TexasHoldEm,
            persistence = MemoryBasedPokerTablePersistence.json(),
        )

        assertEquals(0, table.dealer.id)

        table.advancePlayerOrdering()
        table.newPokerRound()

        assertEquals(1, table.dealer.id)
        table.advancePlayerOrdering()
        table.newPokerRound()

        assertEquals(2, table.dealer.id)
        table.advancePlayerOrdering()
        table.newPokerRound()

        assertEquals(0, table.dealer.id)
    }

    @Test
    fun `player ordering updates when players leave`() {
        val players = listOf(
            Player(0).apply { addChips(1000) },
            Player(1).apply { addChips(1000) },
            Player(2).apply { addChips(1000) },
        )
        val table = PokerTable.new(
            players = players,
            blinds = Blinds(big = 200, small = 100),
            ruleSet = TexasHoldEm,
            persistence = MemoryBasedPokerTablePersistence.json(),
        )

        assertEquals(0, table.dealer.id)
        table.advancePlayerOrdering()
        table.newPokerRound()
        assertEquals(1, table.dealer.id)

        table.playerLeave(players[1])
        assertEquals(2, table.dealer.id)

        table.advancePlayerOrdering()
        table.newPokerRound()
        assertEquals(0, table.dealer.id)
    }

    @Test
    fun `player ordering updates when new players join`() {
        val players = listOf(
            Player(0).apply { addChips(1000) },
            Player(1).apply { addChips(1000) }
        )

        val table = PokerTable.new(
            players = players,
            blinds = Blinds(big = 200, small = 100),
            ruleSet = TexasHoldEm,
            persistence = MemoryBasedPokerTablePersistence.json(),
        )

        assertEquals(0, table.dealer.id)
        table.advancePlayerOrdering()
        table.newPokerRound()
        assertEquals(1, table.dealer.id)

        val newPlayer = Player(2).apply { addChips(1000) }
        table.playerJoin(newPlayer)

        table.advancePlayerOrdering()
        table.newPokerRound()
        assertEquals(2, table.dealer.id)
    }

    private fun restoreTable(tableId: Int): PokerTable {
        return PokerTable.restore(tableId, persistence)!!
    }
}