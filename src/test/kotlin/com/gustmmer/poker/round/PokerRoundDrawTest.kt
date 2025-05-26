package com.gustmmer.poker.round

import com.gustmmer.poker.Blinds
import org.junit.jupiter.api.Test

class PokerRoundDrawTest {

    @Test
    fun `three player with same chips draw`() {
        val pokerRoundSetup = PokerRoundForTest
            .setup(Blinds(big = 200, small = 100), communityCards = "K♥, 6♥, 7♥, 8♦, 9♦, 10♦")
            .withPlayer(1000, "A♠, 2♠")
            .withPlayer(1000, "A♥, 3♠")
            .withPlayer(1000, "A♦, 2♣")

        pokerRoundSetup.execute {
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
            call(2)
            call(0)

            afterPotResolution = {
                assertPlayerChips(0, 1000)
                assertPlayerChips(1, 1000)
                assertPlayerChips(2, 1000)
            }
        }
    }

    @Test
    fun `three player with different chips draw`() {
        val pokerRoundSetup = PokerRoundForTest
            .setup(Blinds(big = 200, small = 100), communityCards = "K♥, 6♥, 7♥, 8♦, 9♦, 10♦")
            .withPlayer(1000, "A♠, 2♠")
            .withPlayer(800, "A♥, 3♠")
            .withPlayer(500, "A♦, 2♣")

        pokerRoundSetup.execute {
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
            call(2)
            call(0)

            afterPotResolution = {
                assertPlayerChips(0, 1000)
                assertPlayerChips(1, 800)
                assertPlayerChips(2, 500)
            }
        }
    }
}