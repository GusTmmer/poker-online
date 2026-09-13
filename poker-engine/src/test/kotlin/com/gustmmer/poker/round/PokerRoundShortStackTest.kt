package com.gustmmer.poker.round

import com.gustmmer.poker.Blinds
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PokerRoundShortStackTest {

    @Test
    fun `heads-up small blind all-in on the blind runs straight to showdown`() {
        // Heads-up the dealer (player 0) posts the small blind — here their whole stack.
        PokerRoundForTest.setup(Blinds(big = 40, small = 20), "K♥, 6♥, 9♣, 8♦, 3♦")
            .withPlayer(20, "A♠, A♥")
            .withPlayer(1000, "7♠, 2♦")
            .execute {
                assertStage(PokerRoundStage.SHOWDOWN)
                assertPlayerChips(0, 40)
                assertPlayerChips(1, 980)
            }
    }

    @Test
    fun `heads-up big blind all-in for less than the blind still gets the small blind's decision`() {
        PokerRoundForTest.setup(Blinds(big = 40, small = 20), "K♥, 6♥, 9♣, 8♦, 3♦")
            .withPlayer(1000, "7♠, 2♦")
            .withPlayer(30, "A♠, A♥")
            .execute {
                assertStage(PokerRoundStage.BET_BLINDS)
                assertNextToAct(0)

                call(0)

                assertStage(PokerRoundStage.SHOWDOWN)
                assertPlayerChips(0, 970)
                assertPlayerChips(1, 60)
            }
    }

    @Test
    fun `heads-up both stacks covered by the blinds runs straight to showdown`() {
        PokerRoundForTest.setup(Blinds(big = 40, small = 20), "K♥, 6♥, 9♣, 8♦, 3♦")
            .withPlayer(20, "A♠, A♥")
            .withPlayer(30, "7♠, 2♦")
            .execute {
                assertStage(PokerRoundStage.SHOWDOWN)
                assertPlayerChips(0, 40)
                assertPlayerChips(1, 10)
            }
    }

    @Test
    fun `heads-up the button acts first pre-flop and last after the flop`() {
        PokerRoundForTest.withShuffledDeck(Blinds(big = 20, small = 10), 2, 1000) {
            assertNextToAct(0)
            call(0)
            call(1)

            assertStage(PokerRoundStage.BET_FLOP)
            assertNextToAct(1)
            assertThrows(IllegalStateException::class.java) { call(0) }
            call(1)
            call(0)
            assertStage(PokerRoundStage.BET_TURN)
            fold(1)
        }
    }

    @Test
    fun `a minimum re-raise matches the previous raise size`() {
        PokerRoundForTest.withShuffledDeck(Blinds(big = 20, small = 10), 3, 1000) {
            call(0)          // UTG calls 20
            raise(1, 40)     // SB raises to 60
            assertThrows(IllegalArgumentException::class.java) { raise(2, 30) }
            raise(2, 40)     // BB re-raises to 100
            call(0)
            call(1)
            assertStage(PokerRoundStage.BET_FLOP)
            assertPlayerChips(0, 900)
            assertPlayerChips(1, 900)
            assertPlayerChips(2, 900)
            fold(1)
            fold(2)
        }
    }

    @Test
    fun `an all-in smaller than a full raise does not reopen raising for players who already acted`() {
        PokerRoundForTest.setup(Blinds(big = 20, small = 10), "K♥, 6♥, 9♣, 8♦, 4♦")
            .withPlayer(1000, "Q♠, J♣")
            .withPlayer(1000, "2♠, 3♣")
            .withPlayer(150, "A♠, A♥")  // big blind
            .execute {
                call(0)          // UTG 20
                raise(1, 100)    // SB to 120, a raise of 100
                allIn(2)         // BB all-in for 150: only 30 more, an under-raise

                // UTG hasn't acted since the full raise, so could still raise; the SB (the raiser) may not.
                assertNextToAct(0)
                call(0)
                assertThrows(IllegalArgumentException::class.java) { raise(1, 100) }
                call(1)

                assertStage(PokerRoundStage.BET_FLOP)
                assertPlayerChips(0, 850)
                assertPlayerChips(1, 850)

                fold(1)
                assertStage(PokerRoundStage.SHOWDOWN)
                assertPlayerChips(0, 850)
                assertPlayerChips(2, 450)
            }
    }

    @Test
    fun `players folded out of turn leave the hand to the last active player`() {
        PokerRoundForTest.withShuffledDeck(Blinds(big = 20, small = 10), 3, 1000) {
            assertNextToAct(0)
            forceFold(1)
            assertStage(PokerRoundStage.BET_BLINDS)
            assertNextToAct(0)
            forceFold(2)

            assertStage(PokerRoundStage.SHOWDOWN)
            assertPlayerChips(0, 1020)
        }
    }

    @Test
    fun `folded chips above a short all-in go to the side pot instead of vanishing`() {
        PokerRoundForTest.setup(Blinds(big = 20, small = 10), "K♥, 6♥, 9♣, 8♦, 4♦")
            .withPlayer(1000, "Q♠, J♣")
            .withPlayer(1000, "2♠, 3♣")
            .withPlayer(5, "A♠, A♥")  // big blind, all-in for 5
            .execute {
                call(0)          // UTG 20
                raise(1, 40)     // SB to 60
                fold(0)

                assertStage(PokerRoundStage.SHOWDOWN)
                assertPlayerChips(0, 980)
                assertPlayerChips(1, 1010)   // side pot: its own 15 + UTG's dead 15
                assertPlayerChips(2, 15)     // main pot: 5 from each player
            }
    }
}
