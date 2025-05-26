package com.gustmmer.poker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PokerTableTest {
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
            ruleSet = TexasHoldEm
        )

        assertEquals(0, table.dealer.id)
        table.newPokerRound()

        assertEquals(1, table.dealer.id)
        table.newPokerRound()

        assertEquals(2, table.dealer.id)
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
            ruleSet = TexasHoldEm
        )

        assertEquals(0, table.dealer.id)
        table.newPokerRound()
        assertEquals(1, table.dealer.id)

        table.playerLeave(players[1])
        assertEquals(2, table.dealer.id)

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
            ruleSet = TexasHoldEm
        )

        assertEquals(0, table.dealer.id)
        table.newPokerRound()
        assertEquals(1, table.dealer.id)

        val newPlayer = Player(2).apply { addChips(1000) }
        table.playerJoin(newPlayer)

        table.newPokerRound()
        assertEquals(2, table.dealer.id)
    }
}