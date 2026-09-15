package com.gustmmer.poker.round

import com.gustmmer.poker.Blinds
import com.gustmmer.poker.deck.toCards
import com.gustmmer.poker.hand.TexasHoldEmHandEvaluator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PotResultsTest {

    private fun hand(board: String, pocket: String) =
        TexasHoldEmHandEvaluator.getMatchingPokerHand(board.toCards(), pocket.toCards())

    private fun reason(board: String, winner: String, loser: String) =
        decidingReason(hand(board, winner), hand(board, loser))

    @Test
    fun `same two pair decided by the kicker`() {
        assertEquals("Queen kicker", reason("2♠, 3♦, 9♦, J♠, J♣", winner = "2♣, Q♠", loser = "2♦, 7♥"))
        assertEquals("King kicker", reason("4♠, J♠, 4♥, 8♦, 8♣", winner = "K♦, 2♦", loser = "5♥, 2♣"))
    }

    @Test
    fun `two pair decided by the second pair`() {
        assertEquals("higher Sevens", reason("K♠, K♥, 7♦, 2♣, 3♠", winner = "7♣, 4♦", loser = "2♥, A♦"))
    }

    @Test
    fun `flush decided by its top card`() {
        assertEquals("Ace high", reason("2♥, 5♥, 9♥, J♣, 3♣", winner = "A♥, 4♥", loser = "K♥, Q♥"))
    }

    @Test
    fun `no reason when the hand names differ or the hands tie`() {
        assertNull(reason("K♠, K♥, 7♦, 2♣, 3♠", winner = "7♣, 7♥", loser = "2♥, A♦"))
        assertNull(reason("5♣, 6♥, 7♥, 8♦, 9♦", winner = "2♠, 2♣", loser = "3♠, 3♣"))
    }

    @Test
    fun `a split pot names both winners`() {
        PokerRoundForTest.setup(Blinds(200, 100), "5♣, 6♥, 7♥, 8♦, 9♦")
            .withPlayer(1000, "2♠, 2♣")
            .withPlayer(1000, "3♠, 3♣")
            .execute {
                allIn(0)
                call(1)

                assertStage(PokerRoundStage.SHOWDOWN)
                val pot = potBreakdown().single()
                assertEquals(2000, pot.amount)
                assertEquals(setOf(0, 1), pot.winnerIds.toSet())
                assertEquals("Split pot", pot.reason)
            }
    }

    @Test
    fun `side pot has its own contenders and winner`() {
        PokerRoundForTest.setup(Blinds(200, 100), "K♥, 6♥, 7♥, 8♦, 9♦, 10♦")
            .withPlayer(1000, "A♠, 2♠")
            .withPlayer(800, "K♠, 3♠")
            .withPlayer(500, "A♦, A♥")
            .execute {
                call(0)
                call(1)
                allIn(2)
                call(0)
                call(1)

                // Before the showdown: one pot, nobody has won anything yet.
                assertEquals(listOf(PotBreakdown(1500, listOf(0, 1, 2))), potBreakdown())

                allIn(1)
                call(0)

                assertPlayerChips(0, 200)
                assertPlayerChips(1, 600)
                assertPlayerChips(2, 1500)

                val (main, side) = potBreakdown()
                assertEquals(1500, main.amount)
                assertEquals(listOf(0, 1, 2), main.contenderIds)
                assertEquals(listOf(2), main.winnerIds)
                assertEquals(600, side.amount)
                assertEquals(listOf(0, 1), side.contenderIds)
                assertEquals(listOf(1), side.winnerIds)
            }
    }
}
