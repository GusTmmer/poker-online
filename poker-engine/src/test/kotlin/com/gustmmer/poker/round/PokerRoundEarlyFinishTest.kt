package com.gustmmer.poker.round

import com.gustmmer.poker.Blinds
import org.junit.jupiter.api.Test

class PokerRoundEarlyFinishTest {

    @Test
    fun `betting round ends when only one player remains - first round`() {
        PokerRoundForTest.withShuffledDeck(Blinds(200, 100), playerCount = 3, playerChips = 1000) {
            fold(0)
            fold(1)

            // Pot resolution
            assertPlayerChips(0, 1000)
            assertPlayerChips(1, 900)
            assertPlayerChips(2, 1100)

            assertWentThroughBettingRound(PokerRoundStage.BET_BLINDS)
        }
    }

    @Test
    fun `betting round ends when only one player remains - second round`() {
        PokerRoundForTest.withShuffledDeck(Blinds(200, 100), playerCount = 3, playerChips = 1000) {
            // Blinds
            fold(0)
            call(1)
            call(2)

            // Flop
            call(1)
            fold(2)

            // Pot resolution
            assertPlayerChips(0, 1000)
            assertPlayerChips(1, 1200)
            assertPlayerChips(2, 800)

            assertWentThroughBettingRound(PokerRoundStage.BET_FLOP)
        }
    }

    @Test
    fun `betting round ends when only one player remains - third round`() {
        PokerRoundForTest.withShuffledDeck(Blinds(200, 100), playerCount = 3, playerChips = 1000) {
            // Blinds
            call(0)
            call(1)
            call(2)

            // Flop
            call(1)
            call(2)
            call(0)

            // Turn
            fold(1)
            fold(2)

            // Pot resolution
            assertPlayerChips(0, 1400)
            assertPlayerChips(1, 800)
            assertPlayerChips(2, 800)

            assertWentThroughBettingRound(PokerRoundStage.BET_TURN)
        }
    }

    @Test
    fun `betting round ends when only one player remains - final round`() {
        PokerRoundForTest.withShuffledDeck(Blinds(200, 100), playerCount = 3, playerChips = 1000) {
            // Blinds
            call(0)
            call(1)
            call(2)

            // Flop
            call(1)
            call(2)
            call(0)

            // Turn
            call(1)
            call(2)
            call(0)

            // River
            call(1)
            fold(2)
            fold(0)

            // Pot resolution
            assertPlayerChips(0, 800)
            assertPlayerChips(1, 1400)
            assertPlayerChips(2, 800)

            assertWentThroughBettingRound(PokerRoundStage.BET_RIVER)
        }
    }
}