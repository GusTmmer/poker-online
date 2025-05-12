package com.gustmmer.poker.round

import com.gustmmer.poker.Blinds
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class PokerRoundRaisesTest {

    @Test
    fun `player raises and steals the pot`() = runTest {
        PokerRoundForTest.withShuffledDeck(Blinds(200, 100), 3, 1000) {
            call(0)
            call(1)
            raise(2, 500)
            fold(0)
            fold(1)

            afterPokerRound = {
                assertPlayerChips(0, 800)
                assertPlayerChips(1, 800)
                assertPlayerChips(2, 1400)
            }
        }
    }

    @Test
    fun `all players all-in`() = runTest {
        val setup = PokerRoundForTest.setup(Blinds(200, 100), communityCards = "K♥, 6♥, 7♥, 8♦, 9♦, 10♦")
            .withPlayer(1000, "A♠, 2♠")
            .withPlayer(1000, "6♠, 3♠")
            .withPlayer(1000, "7♠, 4♠")

        setup.execute {
            allIn(0)
            allIn(1)
            allIn(2)

            afterPokerRound = {
                assertPlayerChips(0, 0)
                assertPlayerChips(1, 0)
                assertPlayerChips(2, 3000)
            }
        }
    }

    @Test
    fun `player can re-raise after being raised`() = runTest {
        val setup = PokerRoundForTest.setup(Blinds(big = 200, small = 100), "K♥, 6♥, 7♥, 8♦, 9♦")
            .withPlayer(2000, "A♠, 2♠")
            .withPlayer(2000, "K♠, 3♠")
            .withPlayer(2000, "7♠, 4♠")

        setup.execute {
            // Blinds
            call(0)
            call(1)
            raise(2, 500)
            raise(0, 1000)
            fold(1)
            call(2)

            afterBlindBets = {
                assertPlayerChips(0, 300)
                assertPlayerChips(1, 1800)
                assertPlayerChips(2, 300)
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
                assertPlayerChips(0, 300)
                assertPlayerChips(1, 1800)
                assertPlayerChips(2, 3900)
            }
        }
    }

    @Test
    fun `minimum raise must be at least big blind or current bet`() = runTest {
        val setup = PokerRoundForTest.setup(Blinds(big = 200, small = 100), "K♥, 6♥, 7♥, 8♦, 9♦")
            .withPlayer(1000, "A♠, 2♠")
            .withPlayer(1000, "K♠, 3♠")
            .withPlayer(1000, "7♠, 4♠")

        setup.execute {
            // Blinds
            call(0)
            call(1)
            raise(2, 150) // Invalid raise (less than big blind)
            raise(2, 200)
            raise(0, 200) // Invalid raise (less than current bet == 400)
            raise(0, 400)
            call(1)
            call(2)

            afterBlindBets = {
                assertPlayerChips(0, 200)
                assertPlayerChips(1, 200)
                assertPlayerChips(2, 200)
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
                assertPlayerChips(0, 200)
                assertPlayerChips(1, 2600)
                assertPlayerChips(2, 200)
            }
        }
    }

    @Test
    fun `player cannot raise more than their remaining chips`() = runTest {
        val setup = PokerRoundForTest.setup(Blinds(big = 200, small = 100), "K♥, 6♥, 7♥, 8♦, 9♦")
            .withPlayer(1000, "A♠, 2♠")
            .withPlayer(1000, "K♠, 3♠")
            .withPlayer(1000, "7♠, 4♠")

        setup.execute {
            // Blinds
            call(0)
            call(1)
            raise(2, 1500) // Invalid raise (more than chips)
            raise(2, 1000) // Invalid raise (more than big blind + chips)
            raise(2, 800)
            call(0)
            call(1)

            afterBlindBets = {
                assertPlayerChips(0, 0)
                assertPlayerChips(1, 0)
                assertPlayerChips(2, 0)
            }

            afterPokerRound = {
                assertPlayerChips(0, 0)
                assertPlayerChips(1, 3000)
                assertPlayerChips(2, 0)
            }
        }
    }

    @Test
    fun `raise on top of player's all-in`() = runTest {
        val setup = PokerRoundForTest.setup(Blinds(big = 200, small = 100), "K♥, 6♥, 7♥, 8♦, 9♦")
            .withPlayer(2000, "A♠, 2♠")
            .withPlayer(500, "K♠, 3♠")
            .withPlayer(2000, "7♠, 4♠")

        setup.execute {
            // Blinds
            call(0)
            allIn(1)
            raise(2, 500)
            call(0)

            afterBlindBets = {
                assertPlayerChips(0, 1000)
                assertPlayerChips(1, 0)
                assertPlayerChips(2, 1000)
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
                assertPlayerChips(0, 1000)
                assertPlayerChips(1, 1500)
                assertPlayerChips(2, 2000)
            }
        }
    }
}