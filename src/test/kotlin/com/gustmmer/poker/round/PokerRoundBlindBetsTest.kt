package com.gustmmer.poker.round

import com.gustmmer.poker.Blinds
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class PokerRoundBlindBetsTest {

    @Test
    fun `player with insufficient chips for big blind can still play`() = runTest {
        val setup = PokerRoundForTest.setup(Blinds(big = 200, small = 100), "K♥, 6♥, 7♥, 8♦, 9♦")
            .withPlayer(1000, "A♠, 2♠")
            .withPlayer(150, "K♠, 3♠")
            .withPlayer(1000, "7♠, 4♠")

        setup.execute {
            // Blinds
            call(0)
            allIn(1)
            call(2)

            afterBlindBets = {
                assertPlayerChips(0, 800)
                assertPlayerChips(1, 0)
                assertPlayerChips(2, 800)
            }

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
            call(2)
            call(0)

            afterPokerRound = {
                assertPlayerChips(0, 800)
                assertPlayerChips(1, 450)
                assertPlayerChips(2, 900)
            }
        }
    }

    @Test
    fun `when small-blind and big-blind players have just enough, no need to take action`() = runTest {
        val setup = PokerRoundForTest.setup(Blinds(big = 200, small = 100), "K♥, 6♥, 7♥, 8♦, 9♦")
            .withPlayer(1000, "A♠, 2♠")
            .withPlayer(100, "K♠, 3♠")
            .withPlayer(200, "7♠, 4♠")

        setup.execute {
            // Blinds
            call(0)

            afterBlindBets = {
                assertPlayerChips(0, 800)
                assertPlayerChips(1, 0)
                assertPlayerChips(2, 0)
            }

            afterPokerRound = {
                assertPlayerChips(0, 800)
                assertPlayerChips(1, 300)
                assertPlayerChips(2, 200)
            }
        }
    }

    @Test
    fun `when small-blind player has just enough for the small-blind, no need to take action`() = runTest {
        val setup = PokerRoundForTest.setup(Blinds(big = 200, small = 100), "K♥, 6♥, 7♥, 8♦, 9♦")
            .withPlayer(1000, "A♠, 2♠")
            .withPlayer(100, "K♠, 3♠")
            .withPlayer(400, "7♠, 4♠")

        setup.execute {
            // Blinds
            call(0)
            call(2)

            afterBlindBets = {
                assertPlayerChips(0, 800)
                assertPlayerChips(1, 0)
                assertPlayerChips(2, 200)
            }

            // Flop
            call(2)
            call(0)

            // Turn
            call(2)
            call(0)

            // River
            call(2)
            call(0)

            afterPokerRound = {
                assertPlayerChips(0, 800)
                assertPlayerChips(1, 300)
                assertPlayerChips(2, 400)
            }
        }
    }

    @Test
    fun `big-blind player has less than table's big-blind`() = runTest {
        val setup = PokerRoundForTest.setup(Blinds(big = 200, small = 100), "K♥, 6♥, 7♥, 8♦, 9♦")
            .withPlayer(1000, "A♠, 2♠")
            .withPlayer(1000, "K♠, 3♠")
            .withPlayer(150, "7♠, 4♠") // Less than big blind

        setup.execute {
            // Blinds
            call(0)
            call(1)
            call(2)
            call(0)
            call(1)

            afterBlindBets = {
                assertPlayerChips(0, 800)
                assertPlayerChips(1, 800)
                assertPlayerChips(2, 0)
            }

            // Flop
            call(1)
            raise(0, 200)
            call(1)

            afterFlopBets = {
                assertPlayerChips(0, 600)
                assertPlayerChips(1, 600)
                assertPlayerChips(2, 0)
            }

            // Turn
            call(1)
            call(0)

            // River
            call(1)
            call(0)

            afterPokerRound = {
                assertPlayerChips(0, 600)
                assertPlayerChips(1, 1550)
                assertPlayerChips(2, 0)
            }
        }
    }
}