package com.gustmmer.poker.round

import com.gustmmer.poker.Blinds
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class PokerRoundSplitPotTest {
    @Test
    fun `with side pot`() = runTest {
        val pokerRoundSetup = PokerRoundForTest.setup(Blinds(200, 100), "K♥, 6♥, 7♥, 8♦, 9♦, 10♦")
            .withPlayer(1000, "A♠, 2♠")
            .withPlayer(800, "K♠, 3♠")
            .withPlayer(500, "A♦, A♥")

        pokerRoundSetup.execute {
            // Blind
            call(0)
            call(1)
            allIn(2)
            call(0)
            call(1)

            afterBlindBets = {
                assertPlayerChips(0, 500)
                assertPlayerChips(1, 300)
                assertPlayerChips(2, 0)
            }

            // Flop
            allIn(1)
            call(0)

            afterFlopBets = {
                assertPlayerChips(0, 200)
                assertPlayerChips(1, 0)
                assertPlayerChips(2, 0)
            }

            afterPokerRound = {
                assertPlayerChips(0, 200)
                assertPlayerChips(1, 600)
                assertPlayerChips(2, 1500)
            }
        }
    }

    @Test
    fun `multiple all-ins with different chip amounts create correct side pots`() = runTest {
        val setup = PokerRoundForTest.setup(Blinds(big = 200, small = 100), "K♥, 6♥, 7♥, 8♦, 9♦")
            .withPlayer(500, "8♠, 3♠")  // Small Blind (1)
            .withPlayer(300, "9♠, 4♠")  // Big Blind (2)
            .withPlayer(200, "K♠, 5♠")  // 3rd blind (3)
            .withPlayer(1000, "7♠, 2♠") // Dealer (0)

        setup.execute {
            // Blinds
            call(3)
            allIn(0)
            allIn(1)
            allIn(2)
            call(3)

            afterBlindBets = {
                assertPlayerChips(0, 0)
                assertPlayerChips(1, 0)
                assertPlayerChips(2, 0)
                assertPlayerChips(3, 500)
            }

            afterPokerRound = {
                assertPlayerChips(0, 400)
                assertPlayerChips(1, 300)
                assertPlayerChips(2, 800)
                assertPlayerChips(3, 500)
            }
        }
    }

    @Test
    fun `side pot is resolved preemptively when having only one active player`() = runTest {
        val pokerRoundSetup = PokerRoundForTest.setup(Blinds(200, 100), "K♥, 6♥, 7♥, 8♦, 9♦, 10♦")
            .withPlayer(1000, "A♠, 2♠")
            .withPlayer(800, "K♠, 3♠")
            .withPlayer(500, "A♦, A♥")

        pokerRoundSetup.execute {
            // Blind
            call(0)
            call(1)
            allIn(2)
            call(0)
            call(1)

            afterBlindBets = {
                assertPlayerChips(0, 500)
                assertPlayerChips(1, 300)
                assertPlayerChips(2, 0)
            }

            // Flop
            raise(1, 200)
            call(0)

            afterFlopBets = {
                assertPlayerChips(0, 300)
                assertPlayerChips(1, 100)
                assertPlayerChips(2, 0)
            }

            fold(1)
            // Player 0 wins Player 1's last bet ($200 + self Call $200)

            afterTurnBets = {
                assertPlayerChips(0, 700)
                assertPlayerChips(1, 100)
                assertPlayerChips(2, 0)
            }

            afterPokerRound = {
                assertPlayerChips(0, 700)
                assertPlayerChips(1, 100)
                assertPlayerChips(2, 1500)
            }
        }
    }
}