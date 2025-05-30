package com.gustmmer.poker.round

import com.gustmmer.poker.Blinds
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PokerRoundRaisesTest {

    @Test
    fun `player raises and steals the pot`() {
        PokerRoundForTest.withShuffledDeck(Blinds(200, 100), 3, 1000) {
            call(0)
            call(1)
            raise(2, 500)
            fold(0)
            fold(1)

            // Pot resolution
            assertPlayerChips(0, 800)
            assertPlayerChips(1, 800)
            assertPlayerChips(2, 1400)
        }
    }

    @Test
    fun `player goes all-in and steals the pot`() {
        PokerRoundForTest.withShuffledDeck(Blinds(200, 100), 3, 1000) {
            call(0)
            allIn(1)
            fold(2)
            fold(0)

            // Pot resolution
            assertPlayerChips(0, 800)
            assertPlayerChips(1, 1400)
            assertPlayerChips(2, 800)
        }
    }

    @Test
    fun `all players all-in`() {
        val setup = PokerRoundForTest.setup(Blinds(200, 100), communityCards = "K♥, 6♥, 7♥, 8♦, 9♦, 10♦")
            .withPlayer(1000, "A♠, 2♠")
            .withPlayer(1000, "6♠, 3♠")
            .withPlayer(1000, "7♠, 4♠")

        setup.execute {
            allIn(0)
            allIn(1)
            allIn(2)

            // Pot resolution
            assertPlayerChips(0, 0)
            assertPlayerChips(1, 0)
            assertPlayerChips(2, 3000)
        }
    }

    @Test
    fun `player can re-raise after being raised`() {
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

            assertPlayerChips(0, 300)
            assertPlayerChips(1, 1800)
            assertPlayerChips(2, 300)

            // Flop
            call(2)
            call(0)

            // Turn
            call(2)
            call(0)

            // River
            call(2)
            call(0)

            // Pot resolution
            assertPlayerChips(0, 300)
            assertPlayerChips(1, 1800)
            assertPlayerChips(2, 3900)
        }
    }

    @Test
    fun `minimum raise must be at least big blind or current bet`() {
        val setup = PokerRoundForTest.setup(Blinds(big = 200, small = 100), "K♥, 6♥, 7♥, 8♦, 9♦")
            .withPlayer(1000, "A♠, 2♠")
            .withPlayer(1000, "K♠, 3♠")
            .withPlayer(1000, "7♠, 4♠")

        setup.execute {
            // Blinds
            call(0)
            call(1)
            assertThrows(IllegalArgumentException::class.java) {
                raise(2, 150) // Invalid raise (less than big blind)
            }
            raise(2, 200)
            assertThrows(IllegalArgumentException::class.java) {
                raise(0, 200) // Invalid raise (less than current bet == 400)
            }
            raise(0, 400)
            call(1)
            call(2)

            assertPlayerChips(0, 200)
            assertPlayerChips(1, 200)
            assertPlayerChips(2, 200)

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

            // Pot resolution
            assertPlayerChips(0, 200)
            assertPlayerChips(1, 2600)
            assertPlayerChips(2, 200)
        }
    }

    @Test
    fun `player cannot raise more than their remaining chips`() {
        val setup = PokerRoundForTest.setup(Blinds(big = 200, small = 100), "K♥, 6♥, 7♥, 8♦, 9♦")
            .withPlayer(1000, "A♠, 2♠")
            .withPlayer(1000, "K♠, 3♠")
            .withPlayer(1000, "7♠, 4♠")

        setup.execute {
            // Blinds
            call(0)
            call(1)
            assertThrows(IllegalArgumentException::class.java) {
                raise(2, 1500) // Invalid raise (more than chips)
            }
            assertThrows(IllegalArgumentException::class.java) {
                raise(2, 1000) // Player hasn't yet placed a bet, but still owes the big-blind.
            }
            raise(2, 800)
            call(0)
            call(1)

            // Pot resolution
            assertPlayerChips(0, 0)
            assertPlayerChips(1, 3000)
            assertPlayerChips(2, 0)
        }
    }

    @Test
    fun `raise on top of player's all-in`() {
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

            assertPlayerChips(0, 1000)
            assertPlayerChips(1, 0)
            assertPlayerChips(2, 1000)

            // Flop
            call(2)
            call(0)

            // Turn
            call(2)
            call(0)

            // River
            call(2)
            call(0)

            // Pot resolution
            assertPlayerChips(0, 1000)
            assertPlayerChips(1, 1500)
            assertPlayerChips(2, 2000)
        }
    }
}