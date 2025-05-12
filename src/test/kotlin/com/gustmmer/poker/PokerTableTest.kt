package com.gustmmer.poker

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PokerTableTest {
    @Test
    fun `dealer moves clockwise after each round`() = runTest {
        val players = listOf(
            Player(0).apply { addChips(1000) },
            Player(1).apply { addChips(1000) },
            Player(2).apply { addChips(1000) }
        )
        val table = PokerTableForTest(
            players = players,
            blinds = Blinds(big = 200, small = 100),
            ruleSet = TexasHoldEm
        )

        assertEquals(players[0], table.dealer)
        table.executeNewPokerRound()

        assertEquals(players[1], table.dealer)
        table.executeNewPokerRound()

        assertEquals(players[2], table.dealer)
        table.executeNewPokerRound()

        assertEquals(players[0], table.dealer)
    }

    @Test
    fun `player ordering updates when players leave`() = runTest {
        val players = listOf(
            Player(0).apply { addChips(1000) },
            Player(1).apply { addChips(1000) },
            Player(2).apply { addChips(1000) },
        )
        val table = PokerTableForTest(
            players = players,
            blinds = Blinds(big = 200, small = 100),
            ruleSet = TexasHoldEm
        )

        assertEquals(players[0], table.dealer)
        table.executeNewPokerRound()
        assertEquals(players[1], table.dealer)

        table.playerLeave(players[1])
        assertEquals(players[2], table.dealer)

        table.executeNewPokerRound()
        assertEquals(players[0], table.dealer)
    }

    @Test
    fun `player ordering updates when new players join`() = runTest {
        val players = listOf(
            Player(1).apply { addChips(1000) },
            Player(2).apply { addChips(1000) }
        )

        val table = PokerTableForTest(
            players = players,
            blinds = Blinds(big = 200, small = 100),
            ruleSet = TexasHoldEm
        )

        assertEquals(players[0], table.dealer)
        table.executeNewPokerRound()
        assertEquals(players[1], table.dealer)

        val newPlayer = Player(3).apply { addChips(1000) }
        table.playerJoin(newPlayer)

        table.executeNewPokerRound()
        assertEquals(newPlayer, table.dealer)
    }
}