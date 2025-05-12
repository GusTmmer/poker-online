package com.gustmmer.poker.hand.rankings

import com.gustmmer.poker.deck.toCards
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HighCardTest {

    @Test
    fun `should select highest five cards`() {
        val pool = "A♠, K♠, Q♠, J♠, 10♠, 9♠, 8♠".toCards()
        val expected = "A♠, K♠, Q♠, J♠, 10♠".toCards()

        val actual = HighCard().highestMatchingPlayerHandOrNull(pool).cards

        assertEquals(expected, actual)
    }

    @Test
    fun `high card comparison`() {
        val higherHighCard = "A♠, K♠, Q♠, J♠, 10♠".toCards()
        val lowerHighCard = "K♠, Q♠, J♠, 10♠, 9♠".toCards()

        val expectedHigherHand = HighCard().highestMatchingPlayerHandOrNull(higherHighCard)!!
        val expectedLowerHand = HighCard().highestMatchingPlayerHandOrNull(lowerHighCard)!!

        assertTrue(expectedHigherHand > expectedLowerHand)
    }
} 