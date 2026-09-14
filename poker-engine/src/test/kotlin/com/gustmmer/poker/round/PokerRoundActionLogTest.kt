package com.gustmmer.poker.round

import com.gustmmer.poker.Blinds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PokerRoundActionLogTest {

    @Test
    fun `a command from a player who isn't in the hand is rejected as out of turn`() {
        PokerRoundForTest.withShuffledDeck(Blinds(20, 10), playerCount = 3, playerChips = 1000) {
            // Seat 42 was never dealt in (e.g. joined mid-hand): an out-of-turn rejection, not a crash.
            assertThrows<IllegalStateException> { call(42) }
            assertThrows<IllegalStateException> { raise(42, 40) }
            assertNextToAct(0)
            fold(0)
            fold(1)
        }
    }

    @Test
    fun `logs checks, bets, raises, calls, all-ins and folds by street`() {
        PokerRoundForTest.withShuffledDeck(Blinds(20, 10), playerCount = 3, playerChips = 1000) {
            call(0)
            call(1)
            call(2) // big blind checks its option
            call(1) // flop: check
            raise(2, 40) // bet
            raise(0, 80) // raise to 120
            fold(1)
            allIn(2)
            call(0)

            val log = actions().map { Triple(it.playerId, it.type, it.amount) }
            assertEquals(
                listOf(
                    Triple(1, HandActionType.SMALL_BLIND, 10),
                    Triple(2, HandActionType.BIG_BLIND, 20),
                    Triple(0, HandActionType.CALL, 20),
                    Triple(1, HandActionType.CALL, 10),
                    Triple(2, HandActionType.CHECK, 0),
                    Triple(1, HandActionType.CHECK, 0),
                    Triple(2, HandActionType.BET, 40),
                    Triple(0, HandActionType.RAISE, 120),
                    Triple(1, HandActionType.FOLD, 0),
                    Triple(2, HandActionType.ALL_IN, 940),
                    Triple(0, HandActionType.CALL, 860),
                ),
                log,
            )
            assertEquals(listOf(false, false, false, false, false, false, true, true, false, true, false), actions().map { it.raised })
        }
    }
}
